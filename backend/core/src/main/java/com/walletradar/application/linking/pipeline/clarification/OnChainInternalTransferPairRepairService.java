package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.accounting.support.AccountingAssetFamilySupport;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.pricing.application.PriceableFlowPolicy;
import com.walletradar.session.application.AccountingUniverseService;
import com.walletradar.session.application.SessionWalletAdjacencyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Cycle/14: pairs same-tx reciprocal on-chain {@code INTERNAL_TRANSFER} rows that were classified
 * without continuity metadata (common after wallet-local rebuilds).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OnChainInternalTransferPairRepairService {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal ABSOLUTE_QTY_TOLERANCE = new BigDecimal("0.000000000001");
    private static final BigDecimal RELATIVE_QTY_TOLERANCE = new BigDecimal("0.000000001");
    private static final Pattern HEX_ADDRESS = Pattern.compile("^0x[a-f0-9]{40}$");
    private static final String INTERNAL_CORR_PREFIX = "internal-tx:";

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final AccountingUniverseService accountingUniverseService;
    private final SessionWalletAdjacencyService sessionWalletAdjacencyService;

    public int reconcileOrphanSameTxPairs(int batchSize) {
        List<NormalizedTransaction> batch = loadOrphanBatch(batchSize);
        if (batch.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        int repaired = 0;
        for (NormalizedTransaction left : batch) {
            if (pairSameTxOrphans(left, now, dirty)) {
                repaired++;
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(deduplicateById(dirty));
            log.info("ONCHAIN_INTERNAL_PAIR_REPAIR batch={} repaired={} saved={}", batch.size(), repaired, dirty.size());
        }
        return repaired;
    }

    private boolean pairSameTxOrphans(
            NormalizedTransaction left,
            Instant now,
            List<NormalizedTransaction> dirty
    ) {
        if (!isOrphanCandidate(left)) {
            return false;
        }
        List<NormalizedTransaction> peers = mongoOperations.find(
                Query.query(new Criteria().andOperator(
                        Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                        Criteria.where("type").is(NormalizedTransactionType.INTERNAL_TRANSFER),
                        Criteria.where("txHash").is(left.getTxHash()),
                        Criteria.where("networkId").is(left.getNetworkId()),
                        Criteria.where("walletAddress").ne(left.getWalletAddress())
                )),
                NormalizedTransaction.class
        );
        if (peers.size() != 1) {
            return false;
        }
        NormalizedTransaction right = peers.getFirst();
        if (!isOrphanCandidate(right) || !isPairable(left, right)) {
            return false;
        }
        String correlationId = INTERNAL_CORR_PREFIX + left.getNetworkId().name().toLowerCase(Locale.ROOT)
                + ":" + left.getTxHash().toLowerCase(Locale.ROOT);
        boolean changed = materializePair(left, right, correlationId, now);
        if (changed) {
            dirty.add(left);
            dirty.add(right);
        }
        return changed;
    }

    private boolean materializePair(
            NormalizedTransaction left,
            NormalizedTransaction right,
            String correlationId,
            Instant now
    ) {
        boolean changed = false;
        changed |= promote(left, right.getWalletAddress(), correlationId, now);
        changed |= promote(right, left.getWalletAddress(), correlationId, now);
        return changed;
    }

    private boolean promote(
            NormalizedTransaction transaction,
            String reciprocalWallet,
            String correlationId,
            Instant now
    ) {
        // RC-9 D1: never overwrite an established shared corridor correlation with a generic
        // internal-tx: key. The orphan candidate filter already excludes corridor rows (they carry
        // a non-blank corrId), but this guard makes the invariant explicit on the write path.
        if (CorridorCorrelationKeyFactory.isCorridorKey(transaction.getCorrelationId())) {
            return false;
        }
        boolean changed = false;
        if (!Boolean.TRUE.equals(transaction.getContinuityCandidate())) {
            transaction.setContinuityCandidate(true);
            changed = true;
        }
        if (!Objects.equals(transaction.getMatchedCounterparty(), reciprocalWallet)) {
            transaction.setMatchedCounterparty(reciprocalWallet);
            changed = true;
        }
        if (!Objects.equals(transaction.getCorrelationId(), correlationId)) {
            transaction.setCorrelationId(correlationId);
            changed = true;
        }
        if (retagPrincipalFlows(transaction)) {
            changed = true;
        }
        NormalizedTransactionStatus targetStatus = postPairingStatus(transaction);
        if (transaction.getStatus() != targetStatus) {
            transaction.setStatus(targetStatus);
            changed = true;
        }
        if (changed) {
            transaction.setUpdatedAt(now);
        }
        return changed;
    }

    private boolean retagPrincipalFlows(NormalizedTransaction transaction) {
        return BridgePairLinkSupport.retagPrincipalFlowsForBridgeContinuity(transaction, null);
    }

    private static NormalizedTransactionStatus postPairingStatus(NormalizedTransaction transaction) {
        return PriceableFlowPolicy.statusAfterContinuityRetag(transaction);
    }

    private boolean isPairable(NormalizedTransaction left, NormalizedTransaction right) {
        if (!isOrphanCandidate(left) || !isOrphanCandidate(right)) {
            return false;
        }
        if (!Objects.equals(left.getTxHash(), right.getTxHash())
                || left.getNetworkId() != right.getNetworkId()) {
            return false;
        }
        boolean sameOwnerScope = accountingUniverseService.shareUniverseMembers(
                left.getWalletAddress(),
                right.getWalletAddress()
        ) || sessionWalletAdjacencyService.anySessionListsBothAddresses(
                left.getWalletAddress(),
                right.getWalletAddress()
        );
        if (!sameOwnerScope) {
            return false;
        }
        List<NormalizedTransaction.Flow> leftFlows = principalFlows(left);
        List<NormalizedTransaction.Flow> rightFlows = principalFlows(right);
        if (leftFlows.size() != 1 || rightFlows.size() != 1) {
            return false;
        }
        NormalizedTransaction.Flow leftPrincipal = leftFlows.getFirst();
        NormalizedTransaction.Flow rightPrincipal = rightFlows.getFirst();
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

    private List<NormalizedTransaction> loadOrphanBatch(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("type").is(NormalizedTransactionType.INTERNAL_TRANSFER),
                Criteria.where("walletAddress").regex("^0x[a-fA-F0-9]{40}$"),
                new Criteria().orOperator(
                        Criteria.where("correlationId").exists(false),
                        Criteria.where("correlationId").is(null),
                        Criteria.where("correlationId").is("")
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

    private boolean isOrphanCandidate(NormalizedTransaction candidate) {
        return candidate != null
                && candidate.getSource() == NormalizedTransactionSource.ON_CHAIN
                && candidate.getType() == NormalizedTransactionType.INTERNAL_TRANSFER
                && hasHexAddress(candidate.getWalletAddress())
                && !blank(candidate.getTxHash())
                && candidate.getNetworkId() != null
                && blank(candidate.getCorrelationId())
                && principalFlows(candidate).size() == 1;
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

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
