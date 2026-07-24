package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.application.costbasis.support.BridgeAssetFamilySupport;
import com.walletradar.canonical.correlation.CorrelationContract;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * B2a — links a same-network custody/parking round-trip that was mis-shaped as an unlinked
 * {@code BRIDGE_OUT} → {@code BRIDGE_IN} pair.
 *
 * <p>Some protocol vaults/routers are dual-purpose (bridge router <em>and</em> LP/vault), so a
 * deposit into the vault and its later withdrawal are both classified as bridge legs with the
 * on-chain counterpart "not found" ({@code BRIDGE_ON_CHAIN_LEG_NOT_FOUND}). Because the legs are
 * never correlated, the return leg is priced at fresh market instead of inheriting the carried-out
 * basis — silently destroying cost basis with no realized P&amp;L (observed on the Katana
 * weETH+ETH vault round-trip: ≈ $550 of ETH-family basis destroyed).
 *
 * <p>The strong, generalized signal for a custody round-trip is that the funds return <em>from the
 * exact same set of counterparty addresses they were sent to, on the same network</em>. Unlike a
 * genuine cross-network bridge (source router on network A, destination on network B), a custody
 * round-trip deposits into vault addresses {X, Y} and later withdraws from the same {X, Y} on the
 * same chain. This service pairs such round-trips and stamps them as one bridge continuity chain
 * (shared {@code bridge:custody-roundtrip:} correlation, reciprocal {@code matchedCounterparty},
 * {@code continuityCandidate=true}, principal flows retagged to price-less TRANSFER). Replay then
 * routes each principal leg through the per-family bridge continuity queue, so the return leg
 * inherits the carried-out basis <em>per asset family</em> (Σ carried == Σ carried-out,
 * {@code realisedPnl = 0}).
 *
 * <p>Deterministic; no per-transaction-hash / per-address runtime keys. The only anchors are:
 * same wallet + same network, identical principal counterparty-address set, identical principal
 * asset-family set, OUT strictly before IN within a bounded window, and a unique matching OUT.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SameNetworkCustodyRoundTripLinkService {

    private static final String BRIDGE_MISSING_REASON = "BRIDGE_ON_CHAIN_LEG_NOT_FOUND";
    /**
     * Custody positions can be held for extended periods (the reference Katana round-trip spanned
     * 11 days). A generous but bounded lookback keeps the pairing deterministic while excluding
     * unrelated re-use of the same protocol addresses far apart in time.
     */
    private static final Duration MAX_ROUND_TRIP_WINDOW = Duration.ofDays(90);
    private static final int OUTBOUND_SCAN_LIMIT = 32;

    /**
     * Finding 3 — value-conservation band. A genuine custody round-trip returns roughly what it
     * deposited (composition may rebalance inside the vault, but total economic value is conserved);
     * the reference Katana pair is ~$1,771 out vs ~$1,860 in (ratio ≈ 1.05). An unrelated re-use of
     * the same router — e.g. 0.0013 AVAX out → 0.0482 AVAX in (~37×), ~2 months apart — is NOT a
     * round-trip and must be rejected. A symmetric {@code [0.5×, 2×]} band accepts any real
     * deposit/withdraw pair (fees, minor rebalance, dust) while rejecting order-of-magnitude
     * mismatches. Applied to total market value when every principal leg is priced, else per-family
     * quantity conservation (prices are frequently absent on unlinked bridge legs at link time).
     */
    private static final BigDecimal MIN_CONSERVATION_RATIO = new BigDecimal("0.5");
    private static final BigDecimal MAX_CONSERVATION_RATIO = new BigDecimal("2.0");
    private static final MathContext RATIO_MC = MathContext.DECIMAL64;

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
            log.info("SameNetworkCustodyRoundTripLink: paired={}", changed);
        }
        return changed;
    }

    boolean pair(NormalizedTransaction inbound) {
        if (!isOrphanBridgeInbound(inbound)) {
            return false;
        }
        Set<String> inboundCounterparties = principalCounterpartySet(inbound, 1);
        Set<String> inboundFamilies = principalFamilySet(inbound, 1);
        if (inboundCounterparties.isEmpty() || inboundFamilies.isEmpty()) {
            return false;
        }
        List<NormalizedTransaction> matches = new ArrayList<>();
        for (NormalizedTransaction outbound : loadCandidateOutbounds(inbound)) {
            if (isCompatibleRoundTrip(inbound, inboundCounterparties, inboundFamilies, outbound)) {
                matches.add(outbound);
            }
        }
        // Abstain on ambiguity: only an unambiguous single round-trip is linked.
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
                unlinkedBridgeCriteria()
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

    private Criteria unlinkedBridgeCriteria() {
        return new Criteria().orOperator(
                Criteria.where("correlationId").exists(false),
                Criteria.where("correlationId").is(null),
                Criteria.where("correlationId").is(""),
                Criteria.where("correlationId").not().regex("^bridge:", "i")
        );
    }

    private boolean isOrphanBridgeInbound(NormalizedTransaction transaction) {
        return transaction != null
                && transaction.getSource() == NormalizedTransactionSource.ON_CHAIN
                && transaction.getType() == NormalizedTransactionType.BRIDGE_IN
                && transaction.getWalletAddress() != null
                && transaction.getNetworkId() != null
                && transaction.getBlockTimestamp() != null
                && !hasBridgeCorrelation(transaction.getCorrelationId());
    }

    private List<NormalizedTransaction> loadCandidateOutbounds(NormalizedTransaction inbound) {
        Instant earliest = inbound.getBlockTimestamp().minus(MAX_ROUND_TRIP_WINDOW);
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.ON_CHAIN),
                Criteria.where("type").is(NormalizedTransactionType.BRIDGE_OUT),
                Criteria.where("walletAddress").is(inbound.getWalletAddress()),
                Criteria.where("networkId").is(inbound.getNetworkId()),
                Criteria.where("blockTimestamp").gte(earliest).lt(inbound.getBlockTimestamp()),
                unlinkedBridgeCriteria()
        ));
        query.with(Sort.by(
                Sort.Order.desc("blockTimestamp"),
                Sort.Order.desc("transactionIndex"),
                Sort.Order.desc("_id")
        ));
        query.limit(OUTBOUND_SCAN_LIMIT);
        return mongoOperations.find(query, NormalizedTransaction.class).stream()
                .filter(candidate -> candidate.getType() == NormalizedTransactionType.BRIDGE_OUT
                        && !hasBridgeCorrelation(candidate.getCorrelationId()))
                .toList();
    }

    private boolean isCompatibleRoundTrip(
            NormalizedTransaction inbound,
            Set<String> inboundCounterparties,
            Set<String> inboundFamilies,
            NormalizedTransaction outbound
    ) {
        if (outbound == null
                || outbound.getNetworkId() != inbound.getNetworkId()
                || outbound.getBlockTimestamp() == null
                || !outbound.getBlockTimestamp().isBefore(inbound.getBlockTimestamp())) {
            return false;
        }
        // The round-trip must return from the exact same set of counterparty addresses it was sent
        // to (deposit into a vault set, withdraw from the same set) and restore the same set of
        // asset families. This is the deterministic custody signal that separates a parked position
        // from an unrelated bridge.
        Set<String> outboundCounterparties = principalCounterpartySet(outbound, -1);
        if (outboundCounterparties.isEmpty() || !outboundCounterparties.equals(inboundCounterparties)) {
            return false;
        }
        Set<String> outboundFamilies = principalFamilySet(outbound, -1);
        if (outboundFamilies.isEmpty() || !outboundFamilies.equals(inboundFamilies)) {
            return false;
        }
        // Finding 3: same wallet/network/counterparty-set/family-set is necessary but not sufficient
        // — the same dual-purpose router can be re-used for unrelated deposits. Require the returned
        // value/quantity to be conserved within [0.5×, 2×] so an order-of-magnitude mismatch (the
        // 37× AVAX pair) is rejected while a composition-rebalanced round-trip (Katana) is accepted.
        return conservesRoundTripValue(outbound, inbound);
    }

    /**
     * Finding 3 — value-conservation guard for a candidate round-trip. Prefers a total market-value
     * band when every principal leg on BOTH directions is priced; otherwise falls back to per-family
     * quantity conservation (unlinked bridge legs frequently lack a flow price at link time). Both
     * directions must fall inside {@code [0.5×, 2×]}.
     */
    private boolean conservesRoundTripValue(NormalizedTransaction outbound, NormalizedTransaction inbound) {
        BigDecimal outValue = totalPrincipalValueUsd(outbound, -1);
        BigDecimal inValue = totalPrincipalValueUsd(inbound, 1);
        if (outValue != null && inValue != null && outValue.signum() > 0 && inValue.signum() > 0) {
            return withinConservationBand(inValue, outValue);
        }
        Map<String, BigDecimal> outboundByFamily = principalQuantityByFamily(outbound, -1);
        Map<String, BigDecimal> inboundByFamily = principalQuantityByFamily(inbound, 1);
        if (outboundByFamily.isEmpty()
                || inboundByFamily.isEmpty()
                || !outboundByFamily.keySet().equals(inboundByFamily.keySet())) {
            return false;
        }
        for (Map.Entry<String, BigDecimal> entry : outboundByFamily.entrySet()) {
            BigDecimal deposited = entry.getValue();
            BigDecimal returned = inboundByFamily.get(entry.getKey());
            if (deposited == null || returned == null || deposited.signum() <= 0 || returned.signum() <= 0) {
                return false;
            }
            if (!withinConservationBand(returned, deposited)) {
                return false;
            }
        }
        return true;
    }

    private static boolean withinConservationBand(BigDecimal returned, BigDecimal carriedOut) {
        BigDecimal ratio = returned.divide(carriedOut, RATIO_MC);
        return ratio.compareTo(MIN_CONSERVATION_RATIO) >= 0
                && ratio.compareTo(MAX_CONSERVATION_RATIO) <= 0;
    }

    /**
     * Total USD value of the principal legs in the given direction, or {@code null} when any
     * principal leg lacks a resolvable price (so the caller falls back to quantity conservation).
     */
    private static BigDecimal totalPrincipalValueUsd(NormalizedTransaction transaction, int direction) {
        BigDecimal total = BigDecimal.ZERO;
        boolean any = false;
        for (NormalizedTransaction.Flow flow : principalFlows(transaction, direction)) {
            BigDecimal value = principalValueUsd(flow);
            if (value == null) {
                return null;
            }
            total = total.add(value, RATIO_MC);
            any = true;
        }
        return any ? total : null;
    }

    private static BigDecimal principalValueUsd(NormalizedTransaction.Flow flow) {
        if (flow.getValueUsd() != null && flow.getValueUsd().abs().signum() > 0) {
            return flow.getValueUsd().abs();
        }
        if (flow.getUnitPriceUsd() != null
                && flow.getUnitPriceUsd().signum() > 0
                && flow.getQuantityDelta() != null) {
            return flow.getQuantityDelta().abs().multiply(flow.getUnitPriceUsd(), RATIO_MC);
        }
        return null;
    }

    private static Map<String, BigDecimal> principalQuantityByFamily(NormalizedTransaction transaction, int direction) {
        Map<String, BigDecimal> byFamily = new HashMap<>();
        for (NormalizedTransaction.Flow flow : principalFlows(transaction, direction)) {
            String family = BridgeAssetFamilySupport.continuityIdentity(flow);
            if (family == null || family.isBlank() || flow.getQuantityDelta() == null) {
                return Map.of();
            }
            byFamily.merge(family, flow.getQuantityDelta().abs(), BigDecimal::add);
        }
        return byFamily;
    }

    private boolean materializePair(NormalizedTransaction outbound, NormalizedTransaction inbound) {
        String correlationId = custodyRoundTripCorrelationId(outbound);
        Instant now = Instant.now();

        boolean outboundChanged = applyPairMetadata(outbound, correlationId, inbound.getTxHash(), now);
        boolean inboundChanged = applyPairMetadata(inbound, correlationId, outbound.getTxHash(), now);

        if (removeMissingReason(outbound)) {
            outboundChanged = true;
        }
        if (removeMissingReason(inbound)) {
            inboundChanged = true;
        }
        // Render as a connected bridge edge and let corridor matching treat both legs as BRIDGE.
        if (BridgePairLinkSupport.stampBridgePrincipalCounterpartyType(outbound, now)) {
            outboundChanged = true;
        }
        if (BridgePairLinkSupport.applyLinkedBridgeCounterparty(outbound, inbound, now)) {
            inboundChanged = true;
        }
        // Demote both legs' principal flows to price-less TRANSFER so AVCO replay performs a
        // per-family basis carry instead of a monetary disposal/market re-pricing.
        if (BridgePairLinkSupport.retagPrincipalFlowsForBridgeContinuity(outbound, now)) {
            outboundChanged = true;
        }
        if (BridgePairLinkSupport.retagPrincipalFlowsForBridgeContinuity(inbound, now)) {
            inboundChanged = true;
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
            Instant now
    ) {
        boolean changed = false;
        if (!Objects.equals(transaction.getCorrelationId(), correlationId)) {
            transaction.setCorrelationId(correlationId);
            changed = true;
        }
        if (!Boolean.TRUE.equals(transaction.getContinuityCandidate())) {
            transaction.setContinuityCandidate(true);
            changed = true;
        }
        if (!sameHash(transaction.getMatchedCounterparty(), matchedCounterparty)) {
            transaction.setMatchedCounterparty(matchedCounterparty);
            changed = true;
        }
        if (changed) {
            transaction.setUpdatedAt(now);
        }
        return changed;
    }

    private static String custodyRoundTripCorrelationId(NormalizedTransaction outbound) {
        NetworkId networkId = outbound.getNetworkId();
        String canonicalHash = NetworkAddressFormat.canonicalTxHash(networkId, outbound.getTxHash());
        String hash = canonicalHash != null ? canonicalHash : outbound.getTxHash();
        if (networkId != NetworkId.SOLANA && hash != null) {
            hash = hash.toLowerCase(Locale.ROOT);
        }
        return CorrelationContract.BRIDGE_CUSTODY_ROUNDTRIP_PREFIX + (hash == null ? "" : hash);
    }

    private static Set<String> principalCounterpartySet(NormalizedTransaction transaction, int direction) {
        Set<String> counterparties = new HashSet<>();
        for (NormalizedTransaction.Flow flow : principalFlows(transaction, direction)) {
            String counterparty = flow.getCounterpartyAddress();
            if (counterparty == null || counterparty.isBlank()) {
                // A missing counterparty makes the round-trip anchor unverifiable — abstain.
                return Set.of();
            }
            counterparties.add(counterparty.trim().toLowerCase(Locale.ROOT));
        }
        return counterparties;
    }

    private static Set<String> principalFamilySet(NormalizedTransaction transaction, int direction) {
        Set<String> families = new HashSet<>();
        for (NormalizedTransaction.Flow flow : principalFlows(transaction, direction)) {
            String family = BridgeAssetFamilySupport.continuityIdentity(flow);
            if (family == null || family.isBlank()) {
                return Set.of();
            }
            families.add(family);
        }
        return families;
    }

    private static List<NormalizedTransaction.Flow> principalFlows(NormalizedTransaction transaction, int direction) {
        if (transaction == null || transaction.getFlows() == null) {
            return List.of();
        }
        return transaction.getFlows().stream()
                .filter(Objects::nonNull)
                .filter(flow -> flow.getRole() != NormalizedLegRole.FEE)
                .filter(flow -> flow.getQuantityDelta() != null
                        && Integer.signum(flow.getQuantityDelta().signum()) == direction)
                .toList();
    }

    private static boolean hasBridgeCorrelation(String correlationId) {
        return correlationId != null && correlationId.toLowerCase(Locale.ROOT).startsWith("bridge:");
    }

    private static boolean removeMissingReason(NormalizedTransaction transaction) {
        if (transaction.getMissingDataReasons() == null || transaction.getMissingDataReasons().isEmpty()) {
            return false;
        }
        return transaction.getMissingDataReasons().removeIf(BRIDGE_MISSING_REASON::equals);
    }

    private static boolean sameHash(String left, String right) {
        if (left == null || right == null) {
            return left == null && right == null;
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
