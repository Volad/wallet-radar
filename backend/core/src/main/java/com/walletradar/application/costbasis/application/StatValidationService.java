package com.walletradar.application.costbasis.application;

import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionCounterpartySupport;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.pricing.application.PriceableFlowPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates priced canonical rows before they enter confirmed replay.
 */
@Service
@RequiredArgsConstructor
public class StatValidationService {

    static final String EMPTY_FLOWS_REASON = "STAT_EMPTY_FLOWS";
    static final String NO_NON_FEE_FLOW_REASON = "STAT_NO_NON_FEE_FLOW";
    static final String FLOW_ROLE_MISSING_REASON = "STAT_FLOW_ROLE_MISSING";
    static final String FLOW_QUANTITY_MISSING_REASON = "STAT_FLOW_QUANTITY_MISSING";
    static final String FLOW_PRICE_MISSING_REASON = "STAT_FLOW_PRICE_MISSING";
    static final String SWAP_MISSING_BUY_LEG_REASON = "STAT_SWAP_MISSING_BUY_LEG";
    static final String SWAP_MISSING_SELL_LEG_REASON = "STAT_SWAP_MISSING_SELL_LEG";
    static final String COUNTERPARTY_TYPE_MISSING_REASON = "STAT_COUNTERPARTY_TYPE_MISSING";
    static final String FLOW_COUNTERPARTY_MISSING_REASON = "FLOW_COUNTERPARTY_MISSING";

    private final PendingStatQueryService pendingStatQueryService;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public StatValidationOutcome processNextBatch(int batchSize, long retryDelaySeconds) {
        List<NormalizedTransaction> batch = pendingStatQueryService.loadNextBatch(batchSize, retryDelaySeconds);
        return processBatch(batch);
    }

    public StatValidationOutcome processNextBatch(int batchSize, long retryDelaySeconds, Collection<String> walletAddresses) {
        List<NormalizedTransaction> batch = pendingStatQueryService.loadNextBatch(batchSize, retryDelaySeconds, walletAddresses);
        return processBatch(batch);
    }

    public int promoteReplaySafeNeedsReview(Collection<String> walletAddresses) {
        if (walletAddresses == null || walletAddresses.isEmpty()) {
            return 0;
        }
        List<NormalizedTransaction> batch = normalizedTransactionRepository
                .findAllActiveAccountingByWalletAddressInAndStatusOrderByBlockTimestampAscTransactionIndexAscIdAsc(
                        walletAddresses,
                        NormalizedTransactionStatus.NEEDS_REVIEW
                );
        int promoted = 0;
        Instant now = Instant.now();
        for (NormalizedTransaction transaction : batch) {
            if (Boolean.TRUE.equals(transaction.getExcludedFromAccounting())) {
                continue;
            }
            if (!isReplaySafeNeedsReview(transaction)) {
                continue;
            }
            NormalizedTransaction candidate = copy(transaction);
            candidate.setStatus(NormalizedTransactionStatus.CONFIRMED);
            candidate.setConfirmedAt(candidate.getConfirmedAt() != null ? candidate.getConfirmedAt() : now);
            candidate.setUpdatedAt(now);
            normalizedTransactionRepository.save(candidate);
            promoted++;
        }
        return promoted;
    }

    private StatValidationOutcome processBatch(List<NormalizedTransaction> batch) {
        int promoted = 0;
        int demoted = 0;

        for (NormalizedTransaction transaction : batch) {
            Instant now = Instant.now();
            NormalizedTransaction candidate = copy(transaction);
            List<String> validationReasons = validate(candidate);

            Set<String> reasons = new LinkedHashSet<>(candidate.getMissingDataReasons() == null
                    ? List.of()
                    : candidate.getMissingDataReasons());
            reasons.remove(EMPTY_FLOWS_REASON);
            reasons.remove(NO_NON_FEE_FLOW_REASON);
            reasons.remove(FLOW_ROLE_MISSING_REASON);
            reasons.remove(FLOW_QUANTITY_MISSING_REASON);
            reasons.remove(FLOW_PRICE_MISSING_REASON);
            reasons.remove(SWAP_MISSING_BUY_LEG_REASON);
            reasons.remove(SWAP_MISSING_SELL_LEG_REASON);
            reasons.remove(COUNTERPARTY_TYPE_MISSING_REASON);
            reasons.remove(FLOW_COUNTERPARTY_MISSING_REASON);
            reasons.addAll(validationReasons);
            candidate.setMissingDataReasons(new ArrayList<>(reasons));
            candidate.setUpdatedAt(now);
            candidate.setStatAttempts(safeIncrement(candidate.getStatAttempts()));

            if (validationReasons.isEmpty()) {
                candidate.setStatus(NormalizedTransactionStatus.CONFIRMED);
                candidate.setConfirmedAt(candidate.getConfirmedAt() != null ? candidate.getConfirmedAt() : now);
                promoted++;
            } else {
                candidate.setStatus(NormalizedTransactionStatus.NEEDS_REVIEW);
                demoted++;
            }

            normalizedTransactionRepository.save(candidate);
        }

        return new StatValidationOutcome(batch.size(), promoted, demoted);
    }

    private List<String> validate(NormalizedTransaction transaction) {
        if (transaction.getFlows() == null || transaction.getFlows().isEmpty()) {
            return List.of(EMPTY_FLOWS_REASON);
        }

        List<NormalizedTransaction.Flow> nonZeroFlows = transaction.getFlows().stream()
                .filter(flow -> flow != null)
                .filter(flow -> flow.getQuantityDelta() != null && flow.getQuantityDelta().compareTo(BigDecimal.ZERO) != 0)
                .toList();
        if (nonZeroFlows.isEmpty()) {
            return List.of(EMPTY_FLOWS_REASON);
        }

        List<NormalizedTransaction.Flow> nonFeeFlows = transaction.getFlows().stream()
                .filter(flow -> flow != null && flow.getRole() != NormalizedLegRole.FEE)
                .filter(flow -> flow.getQuantityDelta() != null && flow.getQuantityDelta().compareTo(BigDecimal.ZERO) != 0)
                .toList();
        if (nonFeeFlows.isEmpty()) {
            return hasReplaySafeFeeOnlyFlows(nonZeroFlows)
                    ? List.of()
                    : List.of(NO_NON_FEE_FLOW_REASON);
        }

        Set<String> reasons = new LinkedHashSet<>();
        boolean hasBuy = false;
        boolean hasSell = false;

        for (NormalizedTransaction.Flow flow : nonFeeFlows) {
            if (flow.getRole() == null) {
                reasons.add(FLOW_ROLE_MISSING_REASON);
            }
            if (flow.getQuantityDelta() == null || flow.getQuantityDelta().compareTo(BigDecimal.ZERO) == 0) {
                reasons.add(FLOW_QUANTITY_MISSING_REASON);
            }
            if (PriceableFlowPolicy.requiresMarketPrice(transaction, flow)
                    && flow.getUnitPriceUsd() == null
                    && flow.getPriceSource() != PriceSource.UNKNOWN) {
                reasons.add(FLOW_PRICE_MISSING_REASON);
            }
            if (flow.getRole() == NormalizedLegRole.BUY) {
                hasBuy = true;
            }
            if (flow.getRole() == NormalizedLegRole.SELL) {
                hasSell = true;
            }
        }

        if (transaction.getType() == NormalizedTransactionType.SWAP) {
            if (!hasBuy) {
                reasons.add(SWAP_MISSING_BUY_LEG_REASON);
            }
            if (!hasSell) {
                reasons.add(SWAP_MISSING_SELL_LEG_REASON);
            }
        }

        if (transaction.getCounterpartyType() == null || transaction.getCounterpartyType().isBlank()) {
            reasons.add(COUNTERPARTY_TYPE_MISSING_REASON);
        }
        if (NormalizedTransactionCounterpartySupport.flowsMissingCounterparty(transaction)) {
            reasons.add(FLOW_COUNTERPARTY_MISSING_REASON);
        }

        return new ArrayList<>(reasons);
    }

    private boolean hasReplaySafeFeeOnlyFlows(List<NormalizedTransaction.Flow> nonZeroFlows) {
        if (nonZeroFlows == null || nonZeroFlows.isEmpty()) {
            return false;
        }
        for (NormalizedTransaction.Flow flow : nonZeroFlows) {
            if (flow == null || flow.getRole() != NormalizedLegRole.FEE) {
                return false;
            }
        }
        return true;
    }

    private boolean isReplaySafeNeedsReview(NormalizedTransaction transaction) {
        if (transaction == null) {
            return false;
        }
        List<NormalizedTransaction.Flow> flows = transaction.getFlows();
        if (flows == null || flows.isEmpty()) {
            return true;
        }
        boolean sawNonZero = false;
        for (NormalizedTransaction.Flow flow : flows) {
            if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            sawNonZero = true;
            if (flow.getRole() != NormalizedLegRole.FEE) {
                return false;
            }
        }
        return !sawNonZero || hasReplaySafeFeeOnlyFlows(flows);
    }

    private NormalizedTransaction copy(NormalizedTransaction transaction) {
        NormalizedTransaction copy = new NormalizedTransaction();
        copy.setId(transaction.getId());
        copy.setTxHash(transaction.getTxHash());
        copy.setNetworkId(transaction.getNetworkId());
        copy.setWalletAddress(transaction.getWalletAddress());
        copy.setSource(transaction.getSource());
        copy.setBlockTimestamp(transaction.getBlockTimestamp());
        copy.setTransactionIndex(transaction.getTransactionIndex());
        copy.setType(transaction.getType());
        copy.setStatus(transaction.getStatus());
        copy.setClassifiedBy(transaction.getClassifiedBy());
        copy.setConfidence(transaction.getConfidence());
        copy.setCorrelationId(transaction.getCorrelationId());
        copy.setContinuityCandidate(transaction.getContinuityCandidate());
        copy.setMatchedCounterparty(transaction.getMatchedCounterparty());
        copy.setCounterpartyAddress(transaction.getCounterpartyAddress());
        copy.setCounterpartyType(transaction.getCounterpartyType());
        copy.setCounterpartyResolutionState(transaction.getCounterpartyResolutionState());
        copy.setCounterpartyResolutionEvidence(transaction.getCounterpartyResolutionEvidence());
        copy.setExcludedFromAccounting(transaction.getExcludedFromAccounting());
        copy.setAccountingExclusionReason(transaction.getAccountingExclusionReason());
        copy.setProtocolName(transaction.getProtocolName());
        copy.setProtocolVersion(transaction.getProtocolVersion());
        copy.setProtocolResolutionState(transaction.getProtocolResolutionState());
        copy.setProtocolResolutionEvidence(transaction.getProtocolResolutionEvidence());
        copy.setMetadata(copyDocument(transaction.getMetadata()));
        copy.setClarificationEvidence(copyDocument(transaction.getClarificationEvidence()));
        copy.setClarificationAttempts(transaction.getClarificationAttempts());
        copy.setFullReceiptClarificationAttempts(transaction.getFullReceiptClarificationAttempts());
        copy.setPricingAttempts(transaction.getPricingAttempts());
        copy.setStatAttempts(transaction.getStatAttempts());
        copy.setCreatedAt(transaction.getCreatedAt());
        copy.setUpdatedAt(transaction.getUpdatedAt());
        copy.setConfirmedAt(transaction.getConfirmedAt());
        copy.setClientId(transaction.getClientId());
        copy.setExternalCapitalBoundary(transaction.getExternalCapitalBoundary());
        copy.setMissingDataReasons(new ArrayList<>(transaction.getMissingDataReasons() == null
                ? List.of()
                : transaction.getMissingDataReasons()));

        List<NormalizedTransaction.Flow> flows = new ArrayList<>();
        for (NormalizedTransaction.Flow flow : transaction.getFlows() == null ? List.<NormalizedTransaction.Flow>of() : transaction.getFlows()) {
            NormalizedTransaction.Flow flowCopy = new NormalizedTransaction.Flow();
            flowCopy.setRole(flow.getRole());
            flowCopy.setAssetContract(flow.getAssetContract());
            flowCopy.setAssetSymbol(flow.getAssetSymbol());
            flowCopy.setQuantityDelta(flow.getQuantityDelta());
            flowCopy.setUnitPriceUsd(flow.getUnitPriceUsd());
            flowCopy.setValueUsd(flow.getValueUsd());
            flowCopy.setPriceSource(flow.getPriceSource());
            flowCopy.setIsInferred(flow.getIsInferred());
            flowCopy.setInferenceReason(flow.getInferenceReason());
            flowCopy.setConfidence(flow.getConfidence());
            flowCopy.setAvcoAtTimeOfSale(flow.getAvcoAtTimeOfSale());
            flowCopy.setRealisedPnlUsd(flow.getRealisedPnlUsd());
            flowCopy.setLogIndex(flow.getLogIndex());
            flowCopy.setCounterpartyAddress(flow.getCounterpartyAddress());
            flowCopy.setCounterpartyType(flow.getCounterpartyType());
            flowCopy.setAccountRef(flow.getAccountRef());
            flowCopy.setAcquisitionFeeUsd(flow.getAcquisitionFeeUsd());
            flows.add(flowCopy);
        }
        copy.setFlows(flows);
        return copy;
    }

    private org.bson.Document copyDocument(org.bson.Document document) {
        return document == null ? null : new org.bson.Document(document);
    }

    private int safeIncrement(Integer attempts) {
        return Math.max(0, attempts == null ? 0 : attempts) + 1;
    }
}
