package com.walletradar.ingestion.pipeline.bybit;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pairs Bybit Flexible Savings / Earn principal moves ({@code LENDING_DEPOSIT} /
 * {@code LENDING_WITHDRAW} / {@code EARN_FLEXIBLE_SAVING}) across {@code :EARN} and
 * {@code :FUND}/{@code :UTA} with a shared continuity correlation so replay uses correlation
 * keys instead of fragile FIFO-only matching.
 *
 * <p>Uses a running-balance FIFO corridor model: subscribe legs open principal lots; redeem legs
 * consume the earliest open lot (partial redeems supported).</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BybitEarnPrincipalTransferPairer {

    public static final String EARN_PRINCIPAL_CORRELATION_PREFIX = "bybit-earn-principal-v1:";

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final int QTY_SCALE = 8;
    /**
     * RC-0 (ADR-043): the pairing window is the <b>earn-holding horizon</b>, not a tight
     * intra-cycle drift. Real Bybit Flexible-Savings cycles span 18 h → weeks → a month, and open
     * positions redeem months later (see {@code results/blockers.md} L523–536). A 30-minute ceiling
     * broke the FIFO match and left most cycles unpaired, feeding the corridor basis leak (RC-A/RC-B).
     * FIFO equal-principal matching keyed {@code {uid, family, |qty|, redeem-follows-subscribe}} is
     * authoritative; this generous ceiling only rejects an implausibly stale open (a subscribe whose
     * redeem is more than the horizon away is almost certainly a different economic position), so it
     * never breaks a genuine multi-week closed cycle.
     */
    private static final Duration MAX_PAIR_DRIFT = Duration.ofDays(400);
    /**
     * ADR-043 co-event sibling window. Both legs of ONE Bybit subscribe/redeem carry the same
     * {@code blockTimestamp}; a few seconds of tolerance absorbs clock skew between the {@code :EARN}
     * and {@code :FUND}/{@code :UTA} rows of the same event without ever spanning a distinct cycle.
     */
    private static final Duration CO_EVENT_MAX_SKEW = Duration.ofSeconds(5);
    private static final BigDecimal QTY_TOLERANCE = new BigDecimal("0.00000001");

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int pairEarnPrincipalTransfers() {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("type").in(
                        NormalizedTransactionType.INTERNAL_TRANSFER,
                        NormalizedTransactionType.LENDING_DEPOSIT,
                        NormalizedTransactionType.LENDING_WITHDRAW,
                        NormalizedTransactionType.EARN_FLEXIBLE_SAVING
                ),
                Criteria.where("excludedFromAccounting").ne(true)
        ));
        List<NormalizedTransaction> candidates = mongoOperations.find(query, NormalizedTransaction.class);
        if (candidates.size() < 2) {
            return 0;
        }

        Map<String, List<NormalizedTransaction>> corridors = new LinkedHashMap<>();
        for (NormalizedTransaction tx : candidates) {
            String corridorKey = corridorKey(tx);
            if (corridorKey == null) {
                continue;
            }
            corridors.computeIfAbsent(corridorKey, ignored -> new ArrayList<>()).add(tx);
        }

        int rewrites = 0;
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        for (List<NormalizedTransaction> corridor : corridors.values()) {
            corridor.sort(Comparator.comparing(
                    NormalizedTransaction::getBlockTimestamp,
                    Comparator.nullsLast(Comparator.naturalOrder())
            ));
            rewrites += pairCorridorFifo(corridor, now, dirty);
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("BYBIT_EARN_PRINCIPAL_PAIRER candidates={} rewrites={}", candidates.size(), rewrites);
        }
        return rewrites;
    }

    /**
     * ADR-043 (co-event sibling + equal-principal FIFO). Two ordered passes:
     *
     * <ol>
     *   <li><b>Co-event sibling pairing (RC-0 primary).</b> Bybit emits both legs of a single
     *       subscribe (or redeem) at the same {@code blockTimestamp} — one {@code :EARN} leg and one
     *       {@code :FUND}/{@code :UTA} leg with opposite sign and equal principal. These are paired
     *       FIRST on {@code {uid, family, |qty| within tol, |Δt| ≤ few s, opposite earn/non-earn}} so
     *       both legs of ONE event share ONE correlationId and land in the SAME replay queue. This is
     *       what makes an OPEN subscribe credit {@code :EARN} from its own paired {@code :FUND}-out
     *       carry, and prevents the greedy cross-event FIFO from mis-pairing the two legs of one event
     *       with legs of two different cycles (the quantity-conservation regression).</li>
     *   <li><b>Subscribe→redeem hold FIFO (equal-principal).</b> Any legs left un-paired after pass 1
     *       (e.g. a leg whose co-event sibling was collapsed/excluded upstream) are matched
     *       subscribe→redeem by <b>equal principal</b> ({@code |subQty − redQty| ≤ tol}), NOT partial
     *       {@code min} matching, keeping {@link #MAX_PAIR_DRIFT}. A {@code -qty} leg may never consume
     *       an open lot whose {@code |qty|} differs beyond tolerance, so an unequal cross-event pair is
     *       rejected (it surfaces as a genuinely-unpaired boundary the conservation guard flags,
     *       instead of a silent mis-attribution).</li>
     * </ol>
     */
    private int pairCorridorFifo(List<NormalizedTransaction> corridor, Instant now, List<NormalizedTransaction> dirty) {
        boolean[] paired = new boolean[corridor.size()];
        int rewrites = pairCoEventBundleSiblings(corridor, paired, now, dirty);
        rewrites += pairCoEventSiblings(corridor, paired, now, dirty);
        rewrites += pairHoldFifoEqualPrincipal(corridor, paired, now, dirty);
        return rewrites;
    }

    /**
     * Pass 0.5 — same-event multi-source principal bundles. Bybit can split one principal subscribe
     * into several non-EARN legs (for example {@code :FUND} out + {@code :UTA} out) plus one
     * {@code :EARN} inbound, all inside the co-event skew window. When the non-EARN legs sum exactly
     * to the opposite EARN principal, the WHOLE bundle must share one correlationId so replay drains
     * and restores on one deterministic continuity identity.
     */
    private int pairCoEventBundleSiblings(
            List<NormalizedTransaction> corridor,
            boolean[] paired,
            Instant now,
            List<NormalizedTransaction> dirty
    ) {
        int rewrites = 0;
        for (int start = 0; start < corridor.size(); start++) {
            if (paired[start]) {
                continue;
            }
            List<Integer> cluster = coEventCluster(corridor, paired, start);
            if (cluster.size() < 3) {
                continue;
            }
            List<Integer> bundle = deterministicBundle(cluster, corridor);
            if (bundle.isEmpty()) {
                continue;
            }
            String correlationId = earnPrincipalCorrelationId(bundle, corridor);
            for (Integer index : bundle) {
                NormalizedTransaction tx = corridor.get(index);
                applyEarnPrincipalPair(tx, correlationId, matchedCounterparty(bundle, corridor, index), now);
                markDirty(tx, dirty);
                paired[index] = true;
                rewrites++;
            }
        }
        return rewrites;
    }

    /**
     * Pass 1 — pair the two legs of ONE event (co-event siblings). The corridor is time-sorted, so
     * once a later leg is beyond the co-event skew from {@code left} no further leg can be its
     * sibling and the inner scan stops.
     */
    private int pairCoEventSiblings(
            List<NormalizedTransaction> corridor,
            boolean[] paired,
            Instant now,
            List<NormalizedTransaction> dirty
    ) {
        int rewrites = 0;
        for (int i = 0; i < corridor.size(); i++) {
            if (paired[i]) {
                continue;
            }
            NormalizedTransaction left = corridor.get(i);
            int leftSign = principalSign(left);
            if (leftSign == 0) {
                continue;
            }
            BigDecimal leftQty = principalQty(left);
            if (leftQty == null || leftQty.signum() <= 0) {
                continue;
            }
            for (int j = i + 1; j < corridor.size(); j++) {
                if (!withinCoEventSkew(left, corridor.get(j))) {
                    break;
                }
                if (paired[j]) {
                    continue;
                }
                NormalizedTransaction right = corridor.get(j);
                if (!isEarnPrincipalOppositePair(left, right, leftSign)) {
                    continue;
                }
                BigDecimal rightQty = principalQty(right);
                if (rightQty == null || rightQty.signum() <= 0) {
                    continue;
                }
                if (!matchesEqualPrincipal(left, right)) {
                    continue;
                }
                applyEarnPrincipalPair(left, right, now);
                markDirty(left, dirty);
                markDirty(right, dirty);
                paired[i] = true;
                paired[j] = true;
                rewrites += 2;
                break;
            }
        }
        return rewrites;
    }

    /**
     * Pass 2 — subscribe→redeem hold FIFO with equal-principal matching over the legs left unpaired
     * by pass 1. A negative leg consumes the earliest open lot of EQUAL principal (within tolerance),
     * opposite earn/non-earn, within {@link #MAX_PAIR_DRIFT}; unequal candidates are rejected.
     */
    private int pairHoldFifoEqualPrincipal(
            List<NormalizedTransaction> corridor,
            boolean[] paired,
            Instant now,
            List<NormalizedTransaction> dirty
    ) {
        int rewrites = 0;
        Deque<OpenLot> openLots = new ArrayDeque<>();
        for (int i = 0; i < corridor.size(); i++) {
            if (paired[i]) {
                continue;
            }
            NormalizedTransaction tx = corridor.get(i);
            int sign = principalSign(tx);
            if (sign == 0) {
                continue;
            }
            BigDecimal qty = principalQty(tx);
            if (qty == null || qty.signum() <= 0) {
                continue;
            }
            if (sign > 0) {
                openLots.addLast(new OpenLot(tx, qty));
                continue;
            }
            OpenLot match = findEqualPrincipalOpenLot(openLots, tx, qty);
            if (match == null) {
                continue;
            }
            applyEarnPrincipalPair(match.transaction(), tx, now);
            markDirty(match.transaction(), dirty);
            markDirty(tx, dirty);
            rewrites += 2;
            openLots.remove(match);
        }
        return rewrites;
    }

    /**
     * Earliest (FIFO) open lot that is an opposite earn/non-earn pair within drift AND whose principal
     * equals {@code redeemQty} within {@link #QTY_TOLERANCE}. Equal-principal only — a partial/unequal
     * match is never returned, so a redeem cannot drain a differently-sized open lot from another
     * cycle.
     */
    private static OpenLot findEqualPrincipalOpenLot(
            Deque<OpenLot> openLots,
            NormalizedTransaction redeem,
            BigDecimal redeemQty
    ) {
        for (OpenLot lot : openLots) {
            if (!isEarnPrincipalOppositePair(lot.transaction(), redeem, 1)) {
                continue;
            }
            if (!withinDrift(lot.transaction(), redeem)) {
                continue;
            }
            if (!matchesEqualPrincipal(lot.transaction(), redeem)) {
                continue;
            }
            return lot;
        }
        return null;
    }

    /**
     * Issue 1 (ADR-043, replay #13b): equal-principal match only. The earlier interest tolerance band
     * ({@code INTEREST_BAND_FRACTION = 0.25}) was NET-HARMFUL and is removed: it MIS-classified genuine
     * principal as accrued reward for Bybit consolidation-netting subscribes. The {@code earnQty >
     * fundQty} mismatch at subscribe is a CONSOLIDATION-NETTING artifact — Bybit re-subscribes a rolled
     * balance that already absorbed capitalized interest — NOT a second interest stream, so the band
     * removed real principal and priced it below-pool (LDO got worse). Bybit already pays Flexible-
     * Savings interest as explicit daily {@code REWARD_CLAIM} legs (FUNDING_HISTORY), so no synthetic
     * reward is synthesized here. Two opposite earn/non-earn legs match ONLY when their principal
     * quantities are equal within {@link #QTY_TOLERANCE}; any gap is rejected (the equal-principal
     * protection against cross-cycle mis-pairing, the replay #11 fix, is preserved).
     */
    private static boolean matchesEqualPrincipal(
            NormalizedTransaction left,
            NormalizedTransaction right
    ) {
        if (left == null || right == null) {
            return false;
        }
        BigDecimal leftQty = principalQty(left);
        BigDecimal rightQty = principalQty(right);
        if (leftQty == null || rightQty == null
                || leftQty.signum() <= 0 || rightQty.signum() <= 0) {
            return false;
        }
        return leftQty.subtract(rightQty, MC).abs().compareTo(QTY_TOLERANCE) <= 0;
    }

    private static void markDirty(NormalizedTransaction tx, List<NormalizedTransaction> dirty) {
        if (!dirty.contains(tx)) {
            dirty.add(tx);
        }
    }

    private static boolean withinCoEventSkew(NormalizedTransaction left, NormalizedTransaction right) {
        if (left.getBlockTimestamp() == null || right.getBlockTimestamp() == null) {
            return false;
        }
        Duration skew = Duration.between(left.getBlockTimestamp(), right.getBlockTimestamp()).abs();
        return skew.compareTo(CO_EVENT_MAX_SKEW) <= 0;
    }

    private static boolean withinDrift(NormalizedTransaction left, NormalizedTransaction right) {
        if (left.getBlockTimestamp() == null || right.getBlockTimestamp() == null) {
            return false;
        }
        Duration drift = Duration.between(left.getBlockTimestamp(), right.getBlockTimestamp()).abs();
        return drift.compareTo(MAX_PAIR_DRIFT) <= 0;
    }

    private static boolean isEarnPrincipalOppositePair(
            NormalizedTransaction left,
            NormalizedTransaction right,
            int leftSign
    ) {
        if (Objects.equals(left.getWalletAddress(), right.getWalletAddress())) {
            return false;
        }
        if (!sameUid(left.getWalletAddress(), right.getWalletAddress())) {
            return false;
        }
        int rightSign = principalSign(right);
        if (rightSign == 0 || rightSign == leftSign) {
            return false;
        }
        boolean leftEarn = isEarnWallet(left.getWalletAddress());
        boolean rightEarn = isEarnWallet(right.getWalletAddress());
        return leftEarn != rightEarn;
    }

    private static void applyEarnPrincipalPair(
            NormalizedTransaction left,
            NormalizedTransaction right,
            Instant now
    ) {
        String correlationId = earnPrincipalCorrelationId(left, right);
        applyEarnPrincipalPair(left, correlationId, right.getWalletAddress(), now);
        applyEarnPrincipalPair(right, correlationId, left.getWalletAddress(), now);
    }

    private static void applyEarnPrincipalPair(
            NormalizedTransaction transaction,
            String correlationId,
            String matchedCounterparty,
            Instant now
    ) {
        transaction.setCorrelationId(correlationId);
        transaction.setContinuityCandidate(true);
        if (transaction.getMatchedCounterparty() == null || transaction.getMatchedCounterparty().isBlank()) {
            transaction.setMatchedCounterparty(matchedCounterparty);
        }
        transaction.setUpdatedAt(now);
    }

    private static String earnPrincipalCorrelationId(NormalizedTransaction left, NormalizedTransaction right) {
        String uid = extractUid(left.getWalletAddress());
        String family = familySymbol(left);
        BigDecimal qty = principalQty(left).max(principalQty(right));
        String qtyPlain = qty.setScale(QTY_SCALE, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        Instant ts = left.getBlockTimestamp();
        if (right.getBlockTimestamp() != null && (ts == null || right.getBlockTimestamp().isBefore(ts))) {
            ts = right.getBlockTimestamp();
        }
        long epochSecond = ts == null ? 0L : ts.getEpochSecond();
        String payload = (uid == null ? "" : uid) + "|" + family + "|" + qtyPlain + "|" + epochSecond;
        return EARN_PRINCIPAL_CORRELATION_PREFIX + sha256Hex(payload);
    }

    private static String earnPrincipalCorrelationId(List<Integer> indices, List<NormalizedTransaction> corridor) {
        NormalizedTransaction anchor = corridor.get(indices.getFirst());
        String uid = extractUid(anchor.getWalletAddress());
        String family = familySymbol(anchor);
        BigDecimal qty = BigDecimal.ZERO;
        Instant ts = null;
        for (Integer index : indices) {
            NormalizedTransaction tx = corridor.get(index);
            qty = qty.add(principalQty(tx), MC);
            if (ts == null || (tx.getBlockTimestamp() != null && tx.getBlockTimestamp().isBefore(ts))) {
                ts = tx.getBlockTimestamp();
            }
        }
        String qtyPlain = qty.setScale(QTY_SCALE, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        long epochSecond = ts == null ? 0L : ts.getEpochSecond();
        String payload = (uid == null ? "" : uid) + "|" + family + "|" + qtyPlain + "|" + epochSecond;
        return EARN_PRINCIPAL_CORRELATION_PREFIX + sha256Hex(payload);
    }

    private static List<Integer> coEventCluster(
            List<NormalizedTransaction> corridor,
            boolean[] paired,
            int start
    ) {
        List<Integer> cluster = new ArrayList<>();
        NormalizedTransaction anchor = corridor.get(start);
        for (int index = start; index < corridor.size(); index++) {
            if (paired[index]) {
                continue;
            }
            NormalizedTransaction candidate = corridor.get(index);
            if (!withinCoEventSkew(anchor, candidate)) {
                if (index > start) {
                    break;
                }
                continue;
            }
            cluster.add(index);
        }
        return cluster;
    }

    private static List<Integer> deterministicBundle(
            List<Integer> cluster,
            List<NormalizedTransaction> corridor
    ) {
        List<Integer> earnPositive = new ArrayList<>();
        List<Integer> earnNegative = new ArrayList<>();
        List<Integer> nonEarnPositive = new ArrayList<>();
        List<Integer> nonEarnNegative = new ArrayList<>();
        for (Integer index : cluster) {
            NormalizedTransaction tx = corridor.get(index);
            int sign = principalSign(tx);
            if (sign == 0) {
                continue;
            }
            if (isEarnWallet(tx.getWalletAddress())) {
                if (sign > 0) {
                    earnPositive.add(index);
                } else {
                    earnNegative.add(index);
                }
            } else if (sign > 0) {
                nonEarnPositive.add(index);
            } else {
                nonEarnNegative.add(index);
            }
        }
        List<Integer> positiveBundle = exactBundle(earnPositive, nonEarnNegative, corridor);
        if (!positiveBundle.isEmpty()) {
            return positiveBundle;
        }
        return exactBundle(earnNegative, nonEarnPositive, corridor);
    }

    private static List<Integer> exactBundle(
            List<Integer> earnSide,
            List<Integer> nonEarnSide,
            List<NormalizedTransaction> corridor
    ) {
        if (earnSide.size() != 1 || nonEarnSide.isEmpty()) {
            return List.of();
        }
        BigDecimal earnQty = principalQty(corridor.get(earnSide.getFirst()));
        BigDecimal nonEarnQty = BigDecimal.ZERO;
        Set<String> wallets = new HashSet<>();
        for (Integer index : nonEarnSide) {
            NormalizedTransaction tx = corridor.get(index);
            nonEarnQty = nonEarnQty.add(principalQty(tx), MC);
            if (tx.getWalletAddress() != null) {
                wallets.add(tx.getWalletAddress().trim().toUpperCase(Locale.ROOT));
            }
        }
        if (wallets.isEmpty()
                || wallets.size() != nonEarnSide.size()
                || earnQty.subtract(nonEarnQty, MC).abs().compareTo(QTY_TOLERANCE) > 0) {
            return List.of();
        }
        List<Integer> bundle = new ArrayList<>();
        bundle.addAll(earnSide);
        bundle.addAll(nonEarnSide);
        bundle.sort(Integer::compareTo);
        return bundle;
    }

    private static String matchedCounterparty(
            List<Integer> bundle,
            List<NormalizedTransaction> corridor,
            int selfIndex
    ) {
        NormalizedTransaction self = corridor.get(selfIndex);
        for (Integer index : bundle) {
            if (index == selfIndex) {
                continue;
            }
            NormalizedTransaction candidate = corridor.get(index);
            if (isEarnWallet(self.getWalletAddress()) != isEarnWallet(candidate.getWalletAddress())) {
                return candidate.getWalletAddress();
            }
        }
        for (Integer index : bundle) {
            if (index != selfIndex) {
                return corridor.get(index).getWalletAddress();
            }
        }
        return self.getMatchedCounterparty();
    }

    /** Corridor key: uid + family + earn product (flexible savings principal). */
    private static String corridorKey(NormalizedTransaction tx) {
        String uid = extractUid(tx.getWalletAddress());
        String family = familySymbol(tx);
        if (uid == null || family == null) {
            return null;
        }
        return uid + "|" + family + "|flexible";
    }

    private static BigDecimal principalQty(NormalizedTransaction tx) {
        BigDecimal qty = principalQuantity(tx);
        return qty == null ? null : qty.abs();
    }

    private static int principalSign(NormalizedTransaction tx) {
        BigDecimal qty = principalQuantity(tx);
        return qty == null ? 0 : qty.signum();
    }

    private static BigDecimal principalQuantity(NormalizedTransaction tx) {
        NormalizedTransaction.Flow flow = principalFlow(tx);
        return flow == null ? null : flow.getQuantityDelta();
    }

    private static NormalizedTransaction.Flow principalFlow(NormalizedTransaction tx) {
        if (tx == null || tx.getFlows() == null) {
            return null;
        }
        for (NormalizedTransaction.Flow flow : tx.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (flow.getQuantityDelta() != null && flow.getQuantityDelta().signum() != 0) {
                return flow;
            }
        }
        return null;
    }

    private static String familySymbol(NormalizedTransaction tx) {
        if (tx.getFlows() == null) {
            return null;
        }
        for (NormalizedTransaction.Flow flow : tx.getFlows()) {
            if (flow != null && flow.getAssetSymbol() != null && !flow.getAssetSymbol().isBlank()) {
                return flow.getAssetSymbol().trim().toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }

    private static boolean isEarnWallet(String wallet) {
        return wallet != null && wallet.toUpperCase(Locale.ROOT).endsWith(":EARN");
    }

    private static boolean sameUid(String leftWallet, String rightWallet) {
        String left = extractUid(leftWallet);
        String right = extractUid(rightWallet);
        return left != null && left.equals(right);
    }

    private static String extractUid(String walletAddress) {
        if (walletAddress == null || !walletAddress.toUpperCase(Locale.ROOT).startsWith("BYBIT:")) {
            return null;
        }
        String without = walletAddress.substring("BYBIT:".length());
        int colon = without.indexOf(':');
        return colon > 0 ? without.substring(0, colon) : without;
    }

    private static String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * An open subscribe lot awaiting an equal-principal redeem. Equal-principal matching consumes a
     * lot wholesale (no partial draw), so the lot carries its immutable principal only.
     */
    private static final class OpenLot {
        private final NormalizedTransaction transaction;
        private final BigDecimal remainingQty;

        private OpenLot(NormalizedTransaction transaction, BigDecimal remainingQty) {
            this.transaction = transaction;
            this.remainingQty = remainingQty;
        }

        private NormalizedTransaction transaction() {
            return transaction;
        }

        private BigDecimal remainingQty() {
            return remainingQty;
        }
    }
}
