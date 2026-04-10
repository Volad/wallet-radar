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
 * Re-attaches same-universe wallet <-> Bybit transfer continuity after an
 * on-chain-only rerun rebuilt the wallet row without the already known Bybit match.
 */
@Service
@RequiredArgsConstructor
public class BybitTransferContinuityRepairService {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal RELATIVE_QTY_TOLERANCE = new BigDecimal("0.002");
    private static final BigDecimal ABSOLUTE_QTY_TOLERANCE = new BigDecimal("0.000001");
    private static final Pattern HEX_ADDRESS = Pattern.compile("^0x[a-fA-F0-9]{40}$");
    private static final Pattern BYBIT_REF = Pattern.compile("^BYBIT:[^\\s]+$");
    private static final String BRIDGE_MISSING_REASON = "BRIDGE_ON_CHAIN_LEG_NOT_FOUND";

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final AccountingUniverseService accountingUniverseService;

    public int reconcileOutstandingPairs(int batchSize) {
        int changed = 0;
        for (NormalizedTransaction candidate : loadCandidateBatch(batchSize)) {
            if (repair(candidate)) {
                changed++;
            }
        }
        return changed;
    }

    boolean repair(NormalizedTransaction onChainCandidate) {
        if (!isOnChainCandidate(onChainCandidate)) {
            return false;
        }
        List<NormalizedTransaction> compatibleBybitRows = normalizedTransactionRepository.findAllByTxHashAndNetworkIdAndSource(
                        onChainCandidate.getTxHash(),
                        onChainCandidate.getNetworkId(),
                        NormalizedTransactionSource.BYBIT
                ).stream()
                .filter(bybitRow -> isPairable(onChainCandidate, bybitRow))
                .toList();
        if (compatibleBybitRows.size() != 1) {
            return false;
        }

        NormalizedTransaction bybitRow = compatibleBybitRows.getFirst();
        String correlationId = correlationId(onChainCandidate.getNetworkId().name(), onChainCandidate.getTxHash());
        Instant now = Instant.now();

        boolean leftChanged = applyContinuityMetadata(
                onChainCandidate,
                correlationId,
                bybitRow.getWalletAddress(),
                now
        );
        boolean rightChanged = applyContinuityMetadata(
                bybitRow,
                correlationId,
                onChainCandidate.getWalletAddress(),
                now
        );
        if (!leftChanged && !rightChanged) {
            return false;
        }

        normalizedTransactionRepository.saveAll(deduplicateById(List.of(onChainCandidate, bybitRow)));
        return true;
    }

    private List<NormalizedTransaction> loadCandidateBatch(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("status").in(
                        NormalizedTransactionStatus.CONFIRMED,
                        NormalizedTransactionStatus.PENDING_PRICE
                ),
                Criteria.where("type").in(
                        NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                        NormalizedTransactionType.EXTERNAL_TRANSFER_OUT
                ),
                Criteria.where("txHash").exists(true).ne(""),
                Criteria.where("networkId").exists(true),
                Criteria.where("walletAddress").regex("^0x[a-fA-F0-9]{40}$"),
                new Criteria().orOperator(
                        Criteria.where("continuityCandidate").ne(Boolean.TRUE),
                        bybitCorrelationMissingCriteria(),
                        bybitCounterpartyMissingCriteria()
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

    private Criteria bybitCorrelationMissingCriteria() {
        return new Criteria().orOperator(
                Criteria.where("correlationId").exists(false),
                Criteria.where("correlationId").is(null),
                Criteria.where("correlationId").is(""),
                Criteria.where("correlationId").not().regex("^BYBIT:")
        );
    }

    private Criteria bybitCounterpartyMissingCriteria() {
        return new Criteria().orOperator(
                Criteria.where("matchedCounterparty").exists(false),
                Criteria.where("matchedCounterparty").is(null),
                Criteria.where("matchedCounterparty").is(""),
                Criteria.where("matchedCounterparty").not().regex("^BYBIT:")
        );
    }

    private boolean isOnChainCandidate(NormalizedTransaction transaction) {
        return transaction != null
                && transaction.getSource() == NormalizedTransactionSource.ON_CHAIN
                && (transaction.getStatus() == NormalizedTransactionStatus.CONFIRMED
                || transaction.getStatus() == NormalizedTransactionStatus.PENDING_PRICE)
                && hasHexAddress(transaction.getWalletAddress())
                && transaction.getNetworkId() != null
                && !blank(transaction.getTxHash())
                && (transaction.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_IN
                || transaction.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_OUT)
                && principalFlows(transaction).size() == 1;
    }

    private boolean isPairable(
            NormalizedTransaction onChain,
            NormalizedTransaction bybit
    ) {
        if (bybit == null
                || bybit.getSource() != NormalizedTransactionSource.BYBIT
                || bybit.getStatus() != NormalizedTransactionStatus.CONFIRMED
                || !hasBybitRef(bybit.getWalletAddress())
                || bybit.getNetworkId() != onChain.getNetworkId()
                || !sameText(bybit.getTxHash(), onChain.getTxHash())
                || !directionCompatible(onChain, bybit)
                || !accountingUniverseService.shareUniverseMembers(onChain.getWalletAddress(), bybit.getWalletAddress())
                || principalFlows(bybit).size() != 1) {
            return false;
        }
        if (!blank(bybit.getMatchedCounterparty())
                && !sameText(bybit.getMatchedCounterparty(), onChain.getWalletAddress())) {
            return false;
        }

        NormalizedTransaction.Flow onChainPrincipal = principalFlows(onChain).getFirst();
        NormalizedTransaction.Flow bybitPrincipal = principalFlows(bybit).getFirst();
        String onChainFamily = AccountingAssetFamilySupport.continuityIdentity(onChainPrincipal);
        String bybitFamily = AccountingAssetFamilySupport.continuityIdentity(bybitPrincipal);
        if (onChainFamily == null || !Objects.equals(onChainFamily, bybitFamily)) {
            return false;
        }
        return quantitiesCompatible(
                onChainPrincipal.getQuantityDelta().abs(),
                bybitPrincipal.getQuantityDelta().abs()
        );
    }

    private boolean applyContinuityMetadata(
            NormalizedTransaction transaction,
            String correlationId,
            String matchedCounterparty,
            Instant now
    ) {
        boolean changed = false;
        if (!sameText(transaction.getCorrelationId(), correlationId)) {
            transaction.setCorrelationId(correlationId);
            changed = true;
        }
        if (!Boolean.TRUE.equals(transaction.getContinuityCandidate())) {
            transaction.setContinuityCandidate(true);
            changed = true;
        }
        if (!sameText(transaction.getMatchedCounterparty(), matchedCounterparty)) {
            transaction.setMatchedCounterparty(matchedCounterparty);
            changed = true;
        }
        if (transaction.getStatus() != NormalizedTransactionStatus.CONFIRMED) {
            transaction.setStatus(NormalizedTransactionStatus.CONFIRMED);
            changed = true;
        }
        if (removeMissingReason(transaction, BRIDGE_MISSING_REASON)) {
            changed = true;
        }
        if (transaction.getConfirmedAt() == null) {
            transaction.setConfirmedAt(now);
            changed = true;
        }
        if (changed) {
            transaction.setUpdatedAt(now);
        }
        return changed;
    }

    private boolean removeMissingReason(NormalizedTransaction transaction, String reason) {
        if (transaction.getMissingDataReasons() == null || transaction.getMissingDataReasons().isEmpty()) {
            return false;
        }
        return transaction.getMissingDataReasons().removeIf(reason::equals);
    }

    private boolean directionCompatible(
            NormalizedTransaction onChain,
            NormalizedTransaction bybit
    ) {
        if (onChain.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_OUT) {
            return bybit.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_IN;
        }
        if (onChain.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_IN) {
            return bybit.getType() == NormalizedTransactionType.EXTERNAL_TRANSFER_OUT;
        }
        return false;
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
        return !blank(value) && HEX_ADDRESS.matcher(value).matches();
    }

    private boolean hasBybitRef(String value) {
        return !blank(value) && BYBIT_REF.matcher(value).matches();
    }

    private boolean sameText(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private String correlationId(String networkId, String txHash) {
        return "BYBIT:" + networkId + ":" + normalize(txHash);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
