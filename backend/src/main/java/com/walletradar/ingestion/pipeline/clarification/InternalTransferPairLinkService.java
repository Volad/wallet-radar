package com.walletradar.ingestion.pipeline.clarification;

import com.walletradar.accounting.support.AccountingAssetFamilySupport;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.session.application.AccountingUniverseService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Promotes simple same-tx reciprocal on-chain transfer pairs into INTERNAL_TRANSFER
 * once both wallet-local canonical rows exist.
 */
@Service
@RequiredArgsConstructor
public class InternalTransferPairLinkService {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal ABSOLUTE_QTY_TOLERANCE = new BigDecimal("0.000000000001");
    private static final BigDecimal RELATIVE_QTY_TOLERANCE = new BigDecimal("0.000000001");
    private static final Pattern HEX_ADDRESS = Pattern.compile("^0x[a-f0-9]{40}$");

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final AccountingUniverseService accountingUniverseService;

    public int reconcileOutstandingPairs(int batchSize) {
        int changed = 0;
        for (NormalizedTransaction candidate : loadCandidateBatch(batchSize)) {
            if (link(candidate)) {
                changed++;
            }
        }
        return changed;
    }

    boolean link(NormalizedTransaction candidate) {
        if (!isCandidate(candidate)) {
            return false;
        }
        NormalizedTransaction peer = normalizedTransactionRepository.findByTxHashAndNetworkIdAndWalletAddress(
                        candidate.getTxHash(),
                        candidate.getNetworkId(),
                        candidate.getMatchedCounterparty()
                )
                .orElse(null);
        if (!isPairable(candidate, peer)) {
            return false;
        }

        Instant now = Instant.now();
        boolean leftChanged = promote(candidate, peer.getWalletAddress(), now);
        boolean rightChanged = promote(peer, candidate.getWalletAddress(), now);
        if (!leftChanged && !rightChanged) {
            return false;
        }

        normalizedTransactionRepository.saveAll(deduplicateById(List.of(candidate, peer)));
        return true;
    }

    private List<NormalizedTransaction> loadCandidateBatch(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("status").is(NormalizedTransactionStatus.CONFIRMED),
                Criteria.where("type").in(
                        NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                        NormalizedTransactionType.EXTERNAL_TRANSFER_OUT
                ),
                Criteria.where("continuityCandidate").is(Boolean.TRUE),
                Criteria.where("matchedCounterparty").regex("^0x[a-fA-F0-9]{40}$"),
                new Criteria().orOperator(
                        Criteria.where("correlationId").exists(false),
                        Criteria.where("correlationId").is(null),
                        Criteria.where("correlationId").is("")
                ),
                new Criteria().orOperator(
                        Criteria.where("protocolName").exists(false),
                        Criteria.where("protocolName").is(null),
                        Criteria.where("protocolName").is("")
                )
        ));
        query.with(Sort.by(
                Sort.Order.asc("blockTimestamp"),
                Sort.Order.asc("transactionIndex"),
                Sort.Order.asc("_id")
        ));
        query.limit(Math.max(1, batchSize));
        return mongoOperations.find(query, NormalizedTransaction.class);
    }

    private boolean isCandidate(NormalizedTransaction candidate) {
        return candidate != null
                && candidate.getSource() == NormalizedTransactionSource.ON_CHAIN
                && candidate.getStatus() == NormalizedTransactionStatus.CONFIRMED
                && (candidate.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                || candidate.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_OUT
                || candidate.getType() == NormalizedTransactionType.INTERNAL_TRANSFER)
                && Boolean.TRUE.equals(candidate.getContinuityCandidate())
                && hasHexAddress(candidate.getWalletAddress())
                && hasHexAddress(candidate.getMatchedCounterparty())
                && sameHashFree(candidate.getWalletAddress(), candidate.getMatchedCounterparty()) == false
                && blank(candidate.getCorrelationId())
                && blank(candidate.getProtocolName())
                && principalFlows(candidate).size() == 1;
    }

    private boolean isPairable(NormalizedTransaction left, NormalizedTransaction right) {
        if (!isCandidate(left) || !isCandidate(right)) {
            return false;
        }
        if (!sameHashFree(left.getTxHash(), right.getTxHash())
                || left.getNetworkId() != right.getNetworkId()
                || !sameHashFree(left.getMatchedCounterparty(), right.getWalletAddress())
                || !sameHashFree(right.getMatchedCounterparty(), left.getWalletAddress())
                || !accountingUniverseService.shareUniverseMembers(left.getWalletAddress(), right.getWalletAddress())) {
            return false;
        }

        NormalizedTransaction.Flow leftPrincipal = principalFlows(left).getFirst();
        NormalizedTransaction.Flow rightPrincipal = principalFlows(right).getFirst();
        if (Integer.signum(leftPrincipal.getQuantityDelta().signum())
                == Integer.signum(rightPrincipal.getQuantityDelta().signum())) {
            return false;
        }

        String leftFamily = AccountingAssetFamilySupport.continuityIdentity(leftPrincipal);
        String rightFamily = AccountingAssetFamilySupport.continuityIdentity(rightPrincipal);
        if (leftFamily == null || !Objects.equals(leftFamily, rightFamily)) {
            return false;
        }

        return quantitiesCompatible(
                leftPrincipal.getQuantityDelta().abs(),
                rightPrincipal.getQuantityDelta().abs()
        );
    }

    private boolean promote(
            NormalizedTransaction transaction,
            String reciprocalWallet,
            Instant now
    ) {
        boolean changed = false;
        if (transaction.getType() != NormalizedTransactionType.INTERNAL_TRANSFER) {
            transaction.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
            changed = true;
        }
        if (transaction.getStatus() != NormalizedTransactionStatus.CONFIRMED) {
            transaction.setStatus(NormalizedTransactionStatus.CONFIRMED);
            changed = true;
        }
        if (!Boolean.TRUE.equals(transaction.getContinuityCandidate())) {
            transaction.setContinuityCandidate(true);
            changed = true;
        }
        if (!sameHashFree(transaction.getMatchedCounterparty(), reciprocalWallet)) {
            transaction.setMatchedCounterparty(reciprocalWallet);
            changed = true;
        }
        if (retagPrincipalFlows(transaction)) {
            changed = true;
        }
        if (changed) {
            transaction.setUpdatedAt(now);
        }
        return changed;
    }

    private boolean retagPrincipalFlows(NormalizedTransaction transaction) {
        if (transaction.getFlows() == null || transaction.getFlows().isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (NormalizedTransaction.Flow flow : transaction.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (flow.getRole() != NormalizedLegRole.TRANSFER) {
                flow.setRole(NormalizedLegRole.TRANSFER);
                changed = true;
            }
            if (flow.getUnitPriceUsd() != null) {
                flow.setUnitPriceUsd(null);
                changed = true;
            }
            if (flow.getValueUsd() != null) {
                flow.setValueUsd(null);
                changed = true;
            }
            if (flow.getPriceSource() != null) {
                flow.setPriceSource(null);
                changed = true;
            }
            if (flow.getAvcoAtTimeOfSale() != null) {
                flow.setAvcoAtTimeOfSale(null);
                changed = true;
            }
            if (flow.getRealisedPnlUsd() != null) {
                flow.setRealisedPnlUsd(null);
                changed = true;
            }
        }
        return changed;
    }

    private List<NormalizedTransaction.Flow> principalFlows(NormalizedTransaction transaction) {
        if (transaction == null || transaction.getFlows() == null) {
            return List.of();
        }
        return transaction.getFlows().stream()
                .filter(Objects::nonNull)
                .filter(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .filter(flow -> flow.getQuantityDelta() != null && flow.getQuantityDelta().signum() != 0)
                .toList();
    }

    private boolean quantitiesCompatible(BigDecimal left, BigDecimal right) {
        if (left == null || right == null || left.signum() <= 0 || right.signum() <= 0) {
            return false;
        }
        BigDecimal delta = left.subtract(right).abs();
        if (delta.compareTo(ABSOLUTE_QTY_TOLERANCE) <= 0) {
            return true;
        }
        BigDecimal denominator = left.max(right);
        if (denominator.signum() <= 0) {
            return false;
        }
        return delta.divide(denominator, MC).compareTo(RELATIVE_QTY_TOLERANCE) <= 0;
    }

    private List<NormalizedTransaction> deduplicateById(List<NormalizedTransaction> candidates) {
        Map<String, NormalizedTransaction> deduplicated = new LinkedHashMap<>();
        for (NormalizedTransaction candidate : candidates) {
            if (candidate == null || blank(candidate.getId())) {
                continue;
            }
            deduplicated.putIfAbsent(candidate.getId(), candidate);
        }
        return List.copyOf(deduplicated.values());
    }

    private boolean hasHexAddress(String value) {
        return !blank(value) && HEX_ADDRESS.matcher(value.trim().toLowerCase(Locale.ROOT)).matches();
    }

    private boolean sameHashFree(String left, String right) {
        return !blank(left) && !blank(right) && left.equalsIgnoreCase(right);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
