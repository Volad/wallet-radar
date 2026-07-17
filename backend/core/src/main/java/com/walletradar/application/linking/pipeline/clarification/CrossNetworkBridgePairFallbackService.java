package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.application.costbasis.support.AccountingAssetFamilySupport;
import com.walletradar.domain.common.NetworkAddressFormat;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Pairs orphan {@code BRIDGE_IN} rows with a same-wallet cross-network {@code BRIDGE_OUT} when the
 * on-chain destination leg never materialized in-session but the outbound source is present.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CrossNetworkBridgePairFallbackService {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal MAX_RELATIVE_QTY_DIFF = new BigDecimal("0.05");
    private static final Duration MAX_INBOUND_DELAY = Duration.ofHours(24);
    /**
     * NEW-08 Layer B/D: a cross-asset orphan bridge pair (e.g. USDC BRIDGE_OUT → ETH BRIDGE_IN) is
     * only accepted inside a tight window, unlike the loose 24h same-asset lookback. Both legs are
     * already pre-classified bridges (registered-contract/route anchored at classification), so the
     * hard anchors here are: tight time proximity, USD-value proximity, single principal each side,
     * and caller uniqueness ({@link #pair} requires exactly one match).
     */
    private static final Duration CROSS_ASSET_MAX_TIME_DELTA = Duration.ofSeconds(180);
    private static final BigDecimal CROSS_ASSET_MAX_RELATIVE_USD_DIFF = new BigDecimal("0.15");
    private static final String BRIDGE_MISSING_REASON = "BRIDGE_ON_CHAIN_LEG_NOT_FOUND";
    private static final String CROSSNET_CORR_PREFIX = "bridge:crossnet:";

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int reconcileOrphanInbounds(int batchSize) {
        int changed = 0;
        for (NormalizedTransaction inbound : loadOrphanInboundBatch(batchSize)) {
            if (pair(inbound)) {
                changed++;
            }
        }
        if (changed > 0) {
            log.info("CrossNetworkBridgePairFallback: paired={}", changed);
        }
        return changed;
    }

    boolean pair(NormalizedTransaction inbound) {
        if (!isOrphanBridgeInbound(inbound)) {
            return false;
        }
        List<NormalizedTransaction> matches = findMatchingOutbounds(inbound);
        if (matches.size() != 1) {
            return false;
        }
        return materializePair(matches.getFirst(), inbound);
    }

    private List<NormalizedTransaction> loadOrphanInboundBatch(int batchSize) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("type").is(NormalizedTransactionType.BRIDGE_IN),
                Criteria.where("walletAddress").exists(true).ne(""),
                Criteria.where("networkId").exists(true),
                Criteria.where("blockTimestamp").exists(true),
                orphanInboundCriteria()
        ));
        query.with(Sort.by(
                Sort.Order.asc("blockTimestamp"),
                Sort.Order.asc("transactionIndex"),
                Sort.Order.asc("_id")
        ));
        query.limit(Math.max(1, batchSize));
        return mongoOperations.find(query, NormalizedTransaction.class).stream()
                .filter(this::isOrphanBridgeInbound)
                .toList();
    }

    private Criteria orphanInboundCriteria() {
        return new Criteria().orOperator(
                Criteria.where("missingDataReasons").is(BRIDGE_MISSING_REASON),
                Criteria.where("correlationId").exists(false),
                Criteria.where("correlationId").is(null),
                Criteria.where("correlationId").is(""),
                Criteria.where("correlationId").not().regex("^bridge:", "i")
        );
    }

    private boolean isOrphanBridgeInbound(NormalizedTransaction transaction) {
        if (transaction == null
                || transaction.getSource() != NormalizedTransactionSource.ON_CHAIN
                || transaction.getType() != NormalizedTransactionType.BRIDGE_IN
                || transaction.getWalletAddress() == null
                || transaction.getNetworkId() == null
                || transaction.getBlockTimestamp() == null) {
            return false;
        }
        if (hasBridgeCorrelation(transaction.getCorrelationId())) {
            return false;
        }
        return (transaction.getMissingDataReasons() != null
                && transaction.getMissingDataReasons().contains(BRIDGE_MISSING_REASON))
                || !hasBridgeCorrelation(transaction.getCorrelationId());
    }

    private List<NormalizedTransaction> findMatchingOutbounds(NormalizedTransaction inbound) {
        Optional<NormalizedTransaction.Flow> inboundPrincipal = BridgePairLinkSupport
                .selectPrimaryPrincipalFlow(inbound, 1);
        if (inboundPrincipal.isEmpty()) {
            return List.of();
        }
        String inboundFamily = AccountingAssetFamilySupport.continuityIdentity(inboundPrincipal.get());
        if (inboundFamily == null) {
            return List.of();
        }
        Instant earliestOutbound = inbound.getBlockTimestamp().minus(MAX_INBOUND_DELAY);
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("type").is(NormalizedTransactionType.BRIDGE_OUT),
                Criteria.where("walletAddress").is(inbound.getWalletAddress()),
                Criteria.where("networkId").ne(inbound.getNetworkId()),
                Criteria.where("blockTimestamp").gte(earliestOutbound).lte(inbound.getBlockTimestamp())
        ));
        query.with(Sort.by(
                Sort.Order.desc("blockTimestamp"),
                Sort.Order.desc("transactionIndex"),
                Sort.Order.desc("_id")
        ));
        query.limit(16);
        List<NormalizedTransaction> accepted = new ArrayList<>();
        for (NormalizedTransaction outbound : mongoOperations.find(query, NormalizedTransaction.class)) {
            if (!isCompatibleOutbound(inbound, inboundPrincipal.get(), inboundFamily, outbound)) {
                continue;
            }
            accepted.add(outbound);
        }
        return accepted;
    }

    private boolean isCompatibleOutbound(
            NormalizedTransaction inbound,
            NormalizedTransaction.Flow inboundPrincipal,
            String inboundFamily,
            NormalizedTransaction outbound
    ) {
        if (outbound == null
                || outbound.getNetworkId() == null
                || outbound.getNetworkId() == inbound.getNetworkId()
                || outbound.getBlockTimestamp() == null
                || outbound.getBlockTimestamp().isAfter(inbound.getBlockTimestamp())) {
            return false;
        }
        Optional<NormalizedTransaction.Flow> outboundPrincipal = BridgePairLinkSupport
                .selectPrimaryPrincipalFlow(outbound, -1);
        if (outboundPrincipal.isEmpty()) {
            return false;
        }
        String outboundFamily = AccountingAssetFamilySupport.continuityIdentity(outboundPrincipal.get());
        if (outboundFamily == null) {
            return false;
        }
        // Same-asset corridor keeps precedence with its existing quantity tolerance and 24h window.
        if (Objects.equals(inboundFamily, outboundFamily)) {
            return quantitiesCompatible(
                    inboundPrincipal.getQuantityDelta().abs(),
                    outboundPrincipal.get().getQuantityDelta().abs()
            );
        }
        // NEW-08 Layer B/D: cross-asset corridor (USDC → ETH etc.) — deterministic, USD-value gated.
        return acceptsCrossAssetOrphanBridge(inbound, inboundPrincipal, outbound, outboundPrincipal.get());
    }

    /**
     * NEW-08 Layer B/D: accept a cross-asset orphan {@code BRIDGE_OUT → BRIDGE_IN} pair only when the
     * hard anchors hold — single principal each side, tight time proximity ({@link #CROSS_ASSET_MAX_TIME_DELTA}),
     * and USD-value proximity ({@link #CROSS_ASSET_MAX_RELATIVE_USD_DIFF}) with BOTH legs USD-resolvable
     * (never quantity across different assets — abstain otherwise). The registered-contract and route
     * anchors are already carried by the pre-classification of both legs as bridges; uniqueness is
     * enforced by the caller ({@link #pair} requires exactly one match).
     */
    private boolean acceptsCrossAssetOrphanBridge(
            NormalizedTransaction inbound,
            NormalizedTransaction.Flow inboundPrincipal,
            NormalizedTransaction outbound,
            NormalizedTransaction.Flow outboundPrincipal
    ) {
        if (principalFlowCount(outbound, -1) != 1 || principalFlowCount(inbound, 1) != 1) {
            return false;
        }
        long deltaSeconds = Math.abs(Duration.between(
                outbound.getBlockTimestamp(), inbound.getBlockTimestamp()).toSeconds());
        if (deltaSeconds > CROSS_ASSET_MAX_TIME_DELTA.toSeconds()) {
            return false;
        }
        BigDecimal outboundUsd = resolveFlowUsdValue(outboundPrincipal);
        BigDecimal inboundUsd = resolveFlowUsdValue(inboundPrincipal);
        if (outboundUsd == null || inboundUsd == null) {
            return false;
        }
        return relativeUsdDiff(outboundUsd, inboundUsd).compareTo(CROSS_ASSET_MAX_RELATIVE_USD_DIFF) <= 0;
    }

    private boolean materializePair(NormalizedTransaction outbound, NormalizedTransaction inbound) {
        String correlationId = crossNetworkCorrelationId(outbound);
        boolean continuityCandidate = BridgePairLinkSupport.supportsPlainMoveBasis(outbound, inbound);
        Instant now = Instant.now();
        List<NormalizedTransaction> updates = new ArrayList<>();

        // NEW-08 Layer C: a simple 1:1 cross-asset pair is shaped into the existing asset-changing
        // bridge-settlement carry — KEEP matchedCounterparty on BOTH legs so bridgeSettlementKey()
        // emits "bridge-settlement:<corrId>" on both, draining the source basis into the destination
        // via REALLOCATE_OUT/REALLOCATE_IN. Only genuinely ambiguous multi-flow cross-asset shapes
        // (not single principal each side) suppress the outbound counterparty.
        boolean crossAssetSettlement = !continuityCandidate && isSinglePrincipalCrossAsset(outbound, inbound);
        boolean keepOutboundCounterparty = continuityCandidate || crossAssetSettlement;
        boolean outboundChanged = applyPairMetadata(outbound, correlationId, keepOutboundCounterparty ? inbound.getTxHash() : null, continuityCandidate, now);
        boolean inboundChanged = applyPairMetadata(inbound, correlationId, outbound.getTxHash(), continuityCandidate, now);
        if (removeMissingReason(inbound, BRIDGE_MISSING_REASON)) {
            inboundChanged = true;
        }
        // BR-1: both corridor legs must carry counterpartyType=BRIDGE so the discovered pair
        // renders as a connected edge on the dashboard chart (independent of basis-carry).
        if (BridgePairLinkSupport.stampBridgePrincipalCounterpartyType(outbound, now)) {
            outboundChanged = true;
        }
        if (BridgePairLinkSupport.applyLinkedBridgeCounterparty(outbound, inbound, now)) {
            inboundChanged = true;
        }
        if (continuityCandidate || crossAssetSettlement) {
            // Same-asset (cc=true) → plain move-basis carry; cross-asset (cc=false) → asset-changing
            // settlement carry. Both require BOTH principal legs as single-principal TRANSFER (prices
            // cleared) so bridgeSettlementKey/bridgeTransferKey fires symmetrically in replay.
            if (BridgePairLinkSupport.retagPrincipalFlowsForBridgeContinuity(outbound, now)) {
                outboundChanged = true;
            }
            if (BridgePairLinkSupport.retagPrincipalFlowsForBridgeContinuity(inbound, now)) {
                inboundChanged = true;
            }
        }
        if (!outboundChanged && !inboundChanged) {
            return false;
        }
        normalizedTransactionRepository.saveAll(deduplicateById(List.of(outbound, inbound)));
        return true;
    }

    private boolean applyPairMetadata(
            NormalizedTransaction transaction,
            String correlationId,
            String matchedCounterparty,
            boolean continuityCandidate,
            Instant now
    ) {
        boolean changed = false;
        if (!Objects.equals(transaction.getCorrelationId(), correlationId)) {
            transaction.setCorrelationId(correlationId);
            changed = true;
        }
        if (!Objects.equals(transaction.getContinuityCandidate(), continuityCandidate)) {
            transaction.setContinuityCandidate(continuityCandidate);
            changed = true;
        }
        boolean counterpartyMismatch = matchedCounterparty != null
                ? !sameHash(transaction.getMatchedCounterparty(), matchedCounterparty)
                : (transaction.getMatchedCounterparty() != null && !transaction.getMatchedCounterparty().isBlank());
        if (counterpartyMismatch) {
            transaction.setMatchedCounterparty(matchedCounterparty);
            changed = true;
        }
        if (changed) {
            transaction.setUpdatedAt(now);
        }
        return changed;
    }

    private static String crossNetworkCorrelationId(NormalizedTransaction outbound) {
        NetworkId networkId = outbound.getNetworkId();
        String canonicalHash = NetworkAddressFormat.canonicalTxHash(networkId, outbound.getTxHash());
        String hash = canonicalHash != null ? canonicalHash : outbound.getTxHash();
        if (networkId != NetworkId.SOLANA && hash != null) {
            hash = hash.toLowerCase(Locale.ROOT);
        }
        return CROSSNET_CORR_PREFIX + (hash == null ? "" : hash);
    }

    private boolean isSinglePrincipalCrossAsset(NormalizedTransaction outbound, NormalizedTransaction inbound) {
        if (principalFlowCount(outbound, -1) != 1 || principalFlowCount(inbound, 1) != 1) {
            return false;
        }
        Optional<NormalizedTransaction.Flow> outboundPrincipal = BridgePairLinkSupport
                .selectPrimaryPrincipalFlow(outbound, -1);
        Optional<NormalizedTransaction.Flow> inboundPrincipal = BridgePairLinkSupport
                .selectPrimaryPrincipalFlow(inbound, 1);
        if (outboundPrincipal.isEmpty() || inboundPrincipal.isEmpty()) {
            return false;
        }
        String outboundFamily = AccountingAssetFamilySupport.continuityIdentity(outboundPrincipal.get());
        String inboundFamily = AccountingAssetFamilySupport.continuityIdentity(inboundPrincipal.get());
        return outboundFamily != null && inboundFamily != null && !outboundFamily.equals(inboundFamily);
    }

    private int principalFlowCount(NormalizedTransaction transaction, int direction) {
        if (transaction == null || transaction.getFlows() == null) {
            return 0;
        }
        return (int) transaction.getFlows().stream()
                .filter(Objects::nonNull)
                .filter(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .filter(flow -> flow.getQuantityDelta() != null
                        && Integer.signum(flow.getQuantityDelta().signum()) == direction)
                .count();
    }

    private BigDecimal resolveFlowUsdValue(NormalizedTransaction.Flow flow) {
        if (flow == null) {
            return null;
        }
        if (flow.getValueUsd() != null && flow.getValueUsd().signum() > 0) {
            return flow.getValueUsd().abs();
        }
        if (flow.getUnitPriceUsd() != null && flow.getUnitPriceUsd().signum() > 0
                && flow.getQuantityDelta() != null && flow.getQuantityDelta().signum() != 0) {
            BigDecimal usd = flow.getQuantityDelta().abs().multiply(flow.getUnitPriceUsd(), MC);
            if (usd.signum() > 0) {
                return usd;
            }
        }
        return null;
    }

    private BigDecimal relativeUsdDiff(BigDecimal left, BigDecimal right) {
        BigDecimal denominator = left.max(right);
        if (denominator.signum() == 0) {
            return BigDecimal.ONE;
        }
        return left.subtract(right).abs().divide(denominator, MC);
    }

    private boolean quantitiesCompatible(BigDecimal left, BigDecimal right) {
        if (left == null || right == null || left.signum() <= 0 || right.signum() <= 0) {
            return false;
        }
        BigDecimal denominator = left.max(right);
        if (denominator.signum() == 0) {
            return false;
        }
        return left.subtract(right).abs().divide(denominator, MC).compareTo(MAX_RELATIVE_QTY_DIFF) <= 0;
    }

    private static boolean hasBridgeCorrelation(String correlationId) {
        return correlationId != null && correlationId.toLowerCase(Locale.ROOT).startsWith("bridge:");
    }

    private static boolean removeMissingReason(NormalizedTransaction transaction, String reason) {
        if (transaction.getMissingDataReasons() == null || transaction.getMissingDataReasons().isEmpty()) {
            return false;
        }
        return transaction.getMissingDataReasons().removeIf(reason::equals);
    }

    private static boolean sameHash(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.trim().equalsIgnoreCase(right.trim());
    }

    private List<NormalizedTransaction> deduplicateById(List<NormalizedTransaction> candidates) {
        Map<String, NormalizedTransaction> deduplicated = new LinkedHashMap<>();
        for (NormalizedTransaction candidate : candidates) {
            if (candidate == null || candidate.getId() == null || candidate.getId().isBlank()) {
                continue;
            }
            deduplicated.putIfAbsent(candidate.getId(), candidate);
        }
        return List.copyOf(deduplicated.values());
    }
}
