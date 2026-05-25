package com.walletradar.ingestion.pipeline.bybit;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.ingestion.config.BybitInternalTransferProperties;
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
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Cycle/6 A2 + Cycle/12: Post-normalization pairer for Bybit {@code INTERNAL_TRANSFER} legs.
 *
 * <p>Passes (in order):
 * <ol>
 *     <li>{@link #pairBroadEconomicFingerprint()} — cross-sub-account pairs with 4dp qty tolerance within 10m.</li>
 *     <li>{@link #dedupSameSignMirrors()} — demote duplicate same-sign stream mirrors on one wallet.</li>
 *     <li>{@link #repairSingletonPairs()} — 1:1 opposite-sign exact-qty pairs within 2h.</li>
 *     <li>{@link #pairBundles()} — N-way near-zero clusters within a short time window (UTA+FUND+EARN).</li>
 *     <li>{@link #pairSameWalletRoundTrips()} — same-sub-account Earn subscribe/unsubscribe round-trips.</li>
 * </ol>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BybitInternalTransferPairer {

    public static final String BUNDLE_CORRELATION_PREFIX = "bybit-it-bundle-v1:";
    public static final String ROUNDTRIP_CORRELATION_PREFIX = "bybit-it-roundtrip-v1:";
    public static final String PAIR_CORRELATION_PREFIX = "bybit-it-pair-v1:";
    public static final String REKEYED_CORRELATION_PREFIX = "bybit-rekeyed-v1:";
    public static final String SAME_SIGN_MIRROR_REASON = "BYBIT_STREAM_MIRROR_SAME_SIGN";

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final int QTY_SCALE = 10;
    private static final int BROAD_QTY_SCALE = 3;
    private static final BigDecimal BROAD_QTY_TOLERANCE_PCT = new BigDecimal("0.0005");
    private static final Duration MAX_PAIR_DRIFT = Duration.ofHours(2);
    private static final Duration BROAD_PAIR_DRIFT = Duration.ofMinutes(10);
    private static final Duration SAME_SIGN_MIRROR_WINDOW = Duration.ofMinutes(10);

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final BybitInternalTransferProperties properties;

    /**
     * Runs all pairing passes after a normalization batch. Each pass reloads candidates from Mongo.
     */
    public int repairAll() {
        int total = pairBroadEconomicFingerprint();
        total += dedupSameSignMirrors();
        total += repairSingletonPairs();
        total += pairBundles();
        total += pairSameWalletRoundTrips();
        return total;
    }

    /**
     * Cycle/15 R3: pairs {@code bybit-econ-v1} legs demoted to {@code EXTERNAL_TRANSFER_*} after
     * orphan fallback — same broad fingerprint as {@link #pairBroadEconomicFingerprint()} but
     * re-promotes both legs to {@code INTERNAL_TRANSFER} with {@link #REKEYED_CORRELATION_PREFIX}.
     */
    public int pairDemotedEconOrphans() {
        Query candidatesQuery = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("correlationId").regex("^bybit-econ-v1:"),
                Criteria.where("continuityCandidate").is(false),
                Criteria.where("excludedFromAccounting").ne(true),
                Criteria.where("type").in(
                        NormalizedTransactionType.EXTERNAL_TRANSFER_IN,
                        NormalizedTransactionType.EXTERNAL_TRANSFER_OUT
                )
        ));
        List<NormalizedTransaction> orphans = mongoOperations.find(candidatesQuery, NormalizedTransaction.class);
        if (orphans.isEmpty()) {
            return 0;
        }

        Map<String, List<NormalizedTransaction>> grouped = new LinkedHashMap<>();
        for (NormalizedTransaction tx : orphans) {
            String key = broadQtySignature(tx);
            if (key == null) {
                continue;
            }
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(tx);
        }

        int rewrites = 0;
        Instant now = Instant.now();
        Set<String> rewritten = new HashSet<>();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        for (List<NormalizedTransaction> docs : grouped.values()) {
            if (docs.size() < 2) {
                continue;
            }
            docs.sort(Comparator.comparing(NormalizedTransaction::getBlockTimestamp,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            for (int leftIndex = 0; leftIndex < docs.size(); leftIndex++) {
                NormalizedTransaction left = docs.get(leftIndex);
                if (rewritten.contains(left.getId())) {
                    continue;
                }
                int sign = principalQuantitySign(left);
                if (sign == 0) {
                    continue;
                }
                NormalizedTransaction bestPartner = null;
                Duration bestDelta = BROAD_PAIR_DRIFT.plusSeconds(1);
                for (int rightIndex = leftIndex + 1; rightIndex < docs.size(); rightIndex++) {
                    NormalizedTransaction right = docs.get(rightIndex);
                    if (rewritten.contains(right.getId())) {
                        continue;
                    }
                    if (Objects.equals(left.getWalletAddress(), right.getWalletAddress())) {
                        continue;
                    }
                    int rightSign = principalQuantitySign(right);
                    if (rightSign == 0 || rightSign == sign) {
                        continue;
                    }
                    if (!isBroadOppositeQty(left, right)) {
                        continue;
                    }
                    if (left.getBlockTimestamp() == null || right.getBlockTimestamp() == null) {
                        continue;
                    }
                    Duration delta = Duration.between(left.getBlockTimestamp(), right.getBlockTimestamp()).abs();
                    if (delta.compareTo(BROAD_PAIR_DRIFT) > 0) {
                        continue;
                    }
                    if (delta.compareTo(bestDelta) < 0) {
                        bestDelta = delta;
                        bestPartner = right;
                    }
                }
                if (bestPartner == null) {
                    continue;
                }
                applyRekeyedDemotedPair(left, bestPartner, now);
                rewritten.add(left.getId());
                rewritten.add(bestPartner.getId());
                dirty.add(left);
                dirty.add(bestPartner);
                rewrites += 2;
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info(
                    "BYBIT_DEMOTED_ECON_ORPHAN_PAIRER candidates={} rewrites={}",
                    orphans.size(),
                    rewrites
            );
        }
        return rewrites;
    }

    /**
     * Cycle/15 R3: pairs singleton legs whose quantities differ only at sub-4dp precision or whose
     * timestamps drift across Bybit stream minute buckets (FH vs INTERNAL_TRANSFER vs TX_LOG).
     */
    public int pairBroadEconomicFingerprint() {
        List<NormalizedTransaction> singletons = loadSingletons();
        if (singletons.isEmpty()) {
            return 0;
        }

        Map<String, List<NormalizedTransaction>> grouped = new LinkedHashMap<>();
        for (NormalizedTransaction tx : singletons) {
            String key = broadQtySignature(tx);
            if (key == null) {
                continue;
            }
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(tx);
        }

        int rewrites = 0;
        Instant now = Instant.now();
        Set<String> rewritten = new HashSet<>();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        for (List<NormalizedTransaction> docs : grouped.values()) {
            if (docs.size() < 2) {
                continue;
            }
            docs.sort(Comparator.comparing(NormalizedTransaction::getBlockTimestamp,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            for (int leftIndex = 0; leftIndex < docs.size(); leftIndex++) {
                NormalizedTransaction left = docs.get(leftIndex);
                if (rewritten.contains(left.getId())) {
                    continue;
                }
                int sign = principalQuantitySign(left);
                if (sign == 0) {
                    continue;
                }
                NormalizedTransaction bestPartner = null;
                Duration bestDelta = BROAD_PAIR_DRIFT.plusSeconds(1);
                for (int rightIndex = leftIndex + 1; rightIndex < docs.size(); rightIndex++) {
                    NormalizedTransaction right = docs.get(rightIndex);
                    if (rewritten.contains(right.getId())) {
                        continue;
                    }
                    if (Objects.equals(left.getWalletAddress(), right.getWalletAddress())) {
                        continue;
                    }
                    int rightSign = principalQuantitySign(right);
                    if (rightSign == 0 || rightSign == sign) {
                        continue;
                    }
                    if (!isBroadOppositeQty(left, right)) {
                        continue;
                    }
                    if (left.getBlockTimestamp() == null || right.getBlockTimestamp() == null) {
                        continue;
                    }
                    Duration delta = Duration.between(left.getBlockTimestamp(), right.getBlockTimestamp()).abs();
                    if (delta.compareTo(BROAD_PAIR_DRIFT) > 0) {
                        continue;
                    }
                    if (delta.compareTo(bestDelta) < 0) {
                        bestDelta = delta;
                        bestPartner = right;
                    }
                }
                if (bestPartner == null) {
                    continue;
                }
                applyRekeyedPairCorrelation(left, bestPartner, now);
                rewritten.add(left.getId());
                rewritten.add(bestPartner.getId());
                dirty.add(left);
                dirty.add(bestPartner);
                rewrites += 2;
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info(
                    "BYBIT_INTERNAL_TRANSFER_BROAD_PAIRER singletons={} rewrites={}",
                    singletons.size(),
                    rewrites
            );
        }
        return rewrites;
    }

    /**
     * Demotes younger same-wallet same-sign legs that share a 4dp-rounded qty fingerprint within
     * {@link #SAME_SIGN_MIRROR_WINDOW} (stream mirror duplicates that cannot form a transfer pair).
     */
    public int dedupSameSignMirrors() {
        Query candidatesQuery = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("type").is(NormalizedTransactionType.INTERNAL_TRANSFER),
                Criteria.where("continuityCandidate").is(true),
                Criteria.where("excludedFromAccounting").ne(true)
        ));
        List<NormalizedTransaction> candidates = mongoOperations.find(candidatesQuery, NormalizedTransaction.class);
        if (candidates.isEmpty()) {
            return 0;
        }

        Map<String, List<NormalizedTransaction>> grouped = new LinkedHashMap<>();
        for (NormalizedTransaction tx : candidates) {
            String key = sameSignMirrorSignature(tx);
            if (key == null) {
                continue;
            }
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(tx);
        }

        int demoted = 0;
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        for (List<NormalizedTransaction> docs : grouped.values()) {
            if (docs.size() < 2) {
                continue;
            }
            docs.sort(Comparator.comparing(NormalizedTransaction::getBlockTimestamp,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            NormalizedTransaction keeper = docs.get(0);
            for (int i = 1; i < docs.size(); i++) {
                NormalizedTransaction mirror = docs.get(i);
                if (keeper.getBlockTimestamp() == null || mirror.getBlockTimestamp() == null) {
                    continue;
                }
                Duration delta = Duration.between(keeper.getBlockTimestamp(), mirror.getBlockTimestamp()).abs();
                if (delta.compareTo(SAME_SIGN_MIRROR_WINDOW) > 0) {
                    keeper = mirror;
                    continue;
                }
                mirror.setExcludedFromAccounting(true);
                mirror.setAccountingExclusionReason(SAME_SIGN_MIRROR_REASON);
                mirror.setContinuityCandidate(false);
                mirror.setUpdatedAt(now);
                dirty.add(mirror);
                demoted++;
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("BYBIT_INTERNAL_TRANSFER_SAME_SIGN_MIRROR demoted={}", demoted);
        }
        return demoted;
    }

    public int repairSingletonPairs() {
        List<NormalizedTransaction> singletons = loadSingletons();
        if (singletons.isEmpty()) {
            return 0;
        }

        Map<String, List<NormalizedTransaction>> grouped = new LinkedHashMap<>();
        for (NormalizedTransaction tx : singletons) {
            String key = exactQtySignature(tx);
            if (key == null) {
                continue;
            }
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(tx);
        }

        int rewrites = 0;
        Instant now = Instant.now();
        Set<String> rewritten = new HashSet<>();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        for (Map.Entry<String, List<NormalizedTransaction>> entry : grouped.entrySet()) {
            List<NormalizedTransaction> docs = entry.getValue();
            if (docs.size() < 2) {
                continue;
            }
            docs.sort(Comparator.comparing(NormalizedTransaction::getBlockTimestamp,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            for (int leftIndex = 0; leftIndex < docs.size(); leftIndex++) {
                NormalizedTransaction left = docs.get(leftIndex);
                if (rewritten.contains(left.getId())) {
                    continue;
                }
                int sign = principalQuantitySign(left);
                if (sign == 0) {
                    continue;
                }
                NormalizedTransaction bestPartner = null;
                Duration bestDelta = MAX_PAIR_DRIFT.plusSeconds(1);
                for (int rightIndex = leftIndex + 1; rightIndex < docs.size(); rightIndex++) {
                    NormalizedTransaction right = docs.get(rightIndex);
                    if (rewritten.contains(right.getId())) {
                        continue;
                    }
                    int rightSign = principalQuantitySign(right);
                    if (rightSign == 0 || rightSign == sign) {
                        continue;
                    }
                    if (left.getBlockTimestamp() == null || right.getBlockTimestamp() == null) {
                        continue;
                    }
                    Duration delta = Duration.between(left.getBlockTimestamp(), right.getBlockTimestamp()).abs();
                    if (delta.compareTo(MAX_PAIR_DRIFT) > 0) {
                        continue;
                    }
                    if (delta.compareTo(bestDelta) < 0) {
                        bestDelta = delta;
                        bestPartner = right;
                    }
                }
                if (bestPartner == null) {
                    continue;
                }
                applyPairCorrelation(left, bestPartner, now);
                rewritten.add(left.getId());
                rewritten.add(bestPartner.getId());
                dirty.add(left);
                dirty.add(bestPartner);
                rewrites += 2;
            }
        }
        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("BYBIT_INTERNAL_TRANSFER_PAIRER singletons={} rewrites={}", singletons.size(), rewrites);
        }
        return rewrites;
    }

    /**
     * Cycle/12: groups singleton legs whose signed quantities net to ~0 within a short window
     * (typical UTA+FUND+EARN three-stream internal transfer).
     */
    public int pairBundles() {
        List<NormalizedTransaction> singletons = loadSingletons();
        if (singletons.isEmpty()) {
            return 0;
        }

        Duration bundleWindow = Duration.ofSeconds(properties.getBundleWindowSeconds());
        BigDecimal residualPct = BigDecimal.valueOf(properties.getBundleResidualPct());
        int minMembers = properties.getBundleMinMembers();

        Map<String, List<NormalizedTransaction>> grouped = new LinkedHashMap<>();
        for (NormalizedTransaction tx : singletons) {
            String key = familySignature(tx);
            if (key == null) {
                continue;
            }
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(tx);
        }

        int bundles = 0;
        int rewrites = 0;
        Instant now = Instant.now();
        Set<String> rewritten = new HashSet<>();
        List<NormalizedTransaction> dirty = new ArrayList<>();

        for (List<NormalizedTransaction> docs : grouped.values()) {
            if (docs.size() < minMembers) {
                continue;
            }
            docs.sort(Comparator.comparing(NormalizedTransaction::getBlockTimestamp,
                    Comparator.nullsLast(Comparator.naturalOrder())));

            for (int anchor = 0; anchor < docs.size(); anchor++) {
                NormalizedTransaction anchorDoc = docs.get(anchor);
                if (rewritten.contains(anchorDoc.getId())) {
                    continue;
                }
                if (anchorDoc.getBlockTimestamp() == null) {
                    continue;
                }
                List<NormalizedTransaction> window = new ArrayList<>();
                window.add(anchorDoc);
                Instant windowStart = anchorDoc.getBlockTimestamp();
                for (int j = anchor + 1; j < docs.size(); j++) {
                    NormalizedTransaction candidate = docs.get(j);
                    if (rewritten.contains(candidate.getId())) {
                        continue;
                    }
                    if (candidate.getBlockTimestamp() == null) {
                        continue;
                    }
                    Duration drift = Duration.between(windowStart, candidate.getBlockTimestamp()).abs();
                    if (drift.compareTo(bundleWindow) > 0) {
                        break;
                    }
                    window.add(candidate);
                }
                if (window.size() < minMembers || !isNearZeroBundle(window, residualPct)) {
                    continue;
                }
                String canonicalCorrelation = canonicalBundleCorrelationId(window);
                NormalizedTransaction firstInbound = firstInboundLeg(window);
                String inboundWallet = firstInbound == null ? null : firstInbound.getWalletAddress();
                for (NormalizedTransaction member : window) {
                    member.setCorrelationId(canonicalCorrelation);
                    member.setContinuityCandidate(true);
                    if (member.getMatchedCounterparty() == null && inboundWallet != null) {
                        member.setMatchedCounterparty(inboundWallet);
                    }
                    member.setUpdatedAt(now);
                    rewritten.add(member.getId());
                    dirty.add(member);
                    rewrites++;
                }
                bundles++;
            }
        }

        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info(
                    "BYBIT_INTERNAL_TRANSFER_BUNDLE_PAIRER candidates={} bundles={} rewrites={}",
                    singletons.size(),
                    bundles,
                    rewrites
            );
        }
        return rewrites;
    }

    /**
     * Cycle/12: pairs opposite-sign legs on the same sub-account (Earn flexible savings round-trips).
     */
    public int pairSameWalletRoundTrips() {
        List<NormalizedTransaction> singletons = loadSingletons();
        if (singletons.isEmpty()) {
            return 0;
        }

        Duration maxWindow = Duration.ofDays(properties.getRoundtripWindowDays());
        BigDecimal tolerancePct = BigDecimal.valueOf(properties.getRoundtripTolerancePct());

        Map<String, List<NormalizedTransaction>> grouped = new LinkedHashMap<>();
        for (NormalizedTransaction tx : singletons) {
            String key = sameWalletSignature(tx);
            if (key == null) {
                continue;
            }
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(tx);
        }

        int pairs = 0;
        int rewrites = 0;
        Instant now = Instant.now();
        Set<String> rewritten = new HashSet<>();
        List<NormalizedTransaction> dirty = new ArrayList<>();

        for (List<NormalizedTransaction> docs : grouped.values()) {
            if (docs.size() < 2) {
                continue;
            }
            docs.sort(Comparator.comparing(NormalizedTransaction::getBlockTimestamp,
                    Comparator.nullsLast(Comparator.naturalOrder())));

            for (int outIndex = 0; outIndex < docs.size(); outIndex++) {
                NormalizedTransaction outbound = docs.get(outIndex);
                if (rewritten.contains(outbound.getId())) {
                    continue;
                }
                BigDecimal outQty = principalQuantity(outbound);
                if (outQty == null || outQty.signum() >= 0) {
                    continue;
                }
                NormalizedTransaction bestInbound = null;
                Duration bestDelta = maxWindow.plusSeconds(1);
                for (int inIndex = outIndex + 1; inIndex < docs.size(); inIndex++) {
                    NormalizedTransaction inbound = docs.get(inIndex);
                    if (rewritten.contains(inbound.getId())) {
                        continue;
                    }
                    BigDecimal inQty = principalQuantity(inbound);
                    if (inQty == null || inQty.signum() <= 0) {
                        continue;
                    }
                    if (!isOppositeQty(outQty, inQty, tolerancePct)) {
                        continue;
                    }
                    if (outbound.getBlockTimestamp() == null || inbound.getBlockTimestamp() == null) {
                        continue;
                    }
                    Duration delta = Duration.between(outbound.getBlockTimestamp(), inbound.getBlockTimestamp()).abs();
                    if (delta.compareTo(maxWindow) > 0) {
                        continue;
                    }
                    if (delta.compareTo(bestDelta) < 0) {
                        bestDelta = delta;
                        bestInbound = inbound;
                    }
                }
                if (bestInbound == null) {
                    continue;
                }
                String canonicalCorrelation = canonicalRoundtripCorrelationId(outbound, bestInbound);
                String wallet = outbound.getWalletAddress();
                outbound.setCorrelationId(canonicalCorrelation);
                bestInbound.setCorrelationId(canonicalCorrelation);
                outbound.setContinuityCandidate(true);
                bestInbound.setContinuityCandidate(true);
                if (outbound.getMatchedCounterparty() == null) {
                    outbound.setMatchedCounterparty(wallet);
                }
                if (bestInbound.getMatchedCounterparty() == null) {
                    bestInbound.setMatchedCounterparty(wallet);
                }
                outbound.setUpdatedAt(now);
                bestInbound.setUpdatedAt(now);
                rewritten.add(outbound.getId());
                rewritten.add(bestInbound.getId());
                dirty.add(outbound);
                dirty.add(bestInbound);
                rewrites += 2;
                pairs++;
            }
        }

        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info(
                    "BYBIT_INTERNAL_TRANSFER_ROUNDTRIP_PAIRER candidates={} pairs={} rewrites={}",
                    singletons.size(),
                    pairs,
                    rewrites
            );
        }
        return rewrites;
    }

    private List<NormalizedTransaction> loadSingletons() {
        Query candidatesQuery = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("type").is(NormalizedTransactionType.INTERNAL_TRANSFER),
                Criteria.where("continuityCandidate").is(true)
        ));
        List<NormalizedTransaction> all = mongoOperations.find(candidatesQuery, NormalizedTransaction.class);
        if (all.isEmpty()) {
            return List.of();
        }

        Map<String, Integer> corrIdCount = new HashMap<>();
        for (NormalizedTransaction tx : all) {
            String corr = tx.getCorrelationId();
            if (corr == null || corr.isBlank()) {
                continue;
            }
            corrIdCount.merge(corr, 1, Integer::sum);
        }

        List<NormalizedTransaction> singletons = new ArrayList<>();
        for (NormalizedTransaction tx : all) {
            if (isFa001OnChainCorridorAnchor(tx)) {
                continue;
            }
            String corr = tx.getCorrelationId();
            if (corr != null && !corr.isBlank() && corrIdCount.getOrDefault(corr, 0) == 1) {
                singletons.add(tx);
            }
        }
        return singletons;
    }

    private static boolean isNearZeroBundle(List<NormalizedTransaction> window, BigDecimal residualPct) {
        boolean hasIn = false;
        boolean hasOut = false;
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal maxAbs = BigDecimal.ZERO;
        for (NormalizedTransaction tx : window) {
            BigDecimal qty = principalQuantity(tx);
            if (qty == null || qty.signum() == 0) {
                return false;
            }
            if (qty.signum() > 0) {
                hasIn = true;
            } else {
                hasOut = true;
            }
            sum = sum.add(qty, MC);
            BigDecimal abs = qty.abs();
            if (abs.compareTo(maxAbs) > 0) {
                maxAbs = abs;
            }
        }
        if (!hasIn || !hasOut || maxAbs.signum() == 0) {
            return false;
        }
        BigDecimal ratio = sum.abs().divide(maxAbs, MC);
        return ratio.compareTo(residualPct) < 0;
    }

    private static boolean isOppositeQty(BigDecimal outbound, BigDecimal inbound, BigDecimal tolerancePct) {
        if (outbound.signum() >= 0 || inbound.signum() <= 0) {
            return false;
        }
        BigDecimal sum = outbound.add(inbound, MC);
        BigDecimal denom = outbound.abs();
        if (denom.signum() == 0) {
            return false;
        }
        return sum.abs().divide(denom, MC).compareTo(tolerancePct) < 0;
    }

    private static NormalizedTransaction firstInboundLeg(List<NormalizedTransaction> window) {
        for (NormalizedTransaction tx : window) {
            BigDecimal qty = principalQuantity(tx);
            if (qty != null && qty.signum() > 0) {
                return tx;
            }
        }
        return null;
    }

    private static void applyPairCorrelation(
            NormalizedTransaction left,
            NormalizedTransaction right,
            Instant now
    ) {
        String canonicalCorrelation = canonicalPairCorrelationId(left, right);
        left.setCorrelationId(canonicalCorrelation);
        right.setCorrelationId(canonicalCorrelation);
        left.setContinuityCandidate(true);
        right.setContinuityCandidate(true);
        if (left.getMatchedCounterparty() == null) {
            left.setMatchedCounterparty(right.getWalletAddress());
        }
        if (right.getMatchedCounterparty() == null) {
            right.setMatchedCounterparty(left.getWalletAddress());
        }
        left.setUpdatedAt(now);
        right.setUpdatedAt(now);
    }

    private static void applyRekeyedPairCorrelation(
            NormalizedTransaction left,
            NormalizedTransaction right,
            Instant now
    ) {
        String canonicalCorrelation = rekeyedPairCorrelationId(left, right);
        left.setCorrelationId(canonicalCorrelation);
        right.setCorrelationId(canonicalCorrelation);
        left.setContinuityCandidate(true);
        right.setContinuityCandidate(true);
        if (left.getMatchedCounterparty() == null) {
            left.setMatchedCounterparty(right.getWalletAddress());
        }
        if (right.getMatchedCounterparty() == null) {
            right.setMatchedCounterparty(left.getWalletAddress());
        }
        left.setUpdatedAt(now);
        right.setUpdatedAt(now);
    }

    private static void applyRekeyedDemotedPair(
            NormalizedTransaction left,
            NormalizedTransaction right,
            Instant now
    ) {
        applyRekeyedPairCorrelation(left, right, now);
        promoteDemotedLegToInternalTransfer(left);
        promoteDemotedLegToInternalTransfer(right);
    }

    private static void promoteDemotedLegToInternalTransfer(NormalizedTransaction tx) {
        if (tx == null) {
            return;
        }
        int sign = principalQuantitySign(tx);
        if (sign > 0) {
            tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
            demoteBuySellToTransfer(tx);
        } else if (sign < 0) {
            tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
            demoteBuySellToTransfer(tx);
        }
    }

    private static void demoteBuySellToTransfer(NormalizedTransaction tx) {
        if (tx.getFlows() == null) {
            return;
        }
        for (NormalizedTransaction.Flow flow : tx.getFlows()) {
            if (flow == null || flow.getRole() == NormalizedLegRole.FEE) {
                continue;
            }
            if (flow.getRole() == NormalizedLegRole.BUY || flow.getRole() == NormalizedLegRole.SELL) {
                flow.setRole(NormalizedLegRole.TRANSFER);
            }
        }
    }

    private static String exactQtySignature(NormalizedTransaction tx) {
        String family = familySignature(tx);
        if (family == null) {
            return null;
        }
        BigDecimal qty = principalQuantity(tx);
        if (qty == null) {
            return null;
        }
        BigDecimal absQty = qty.abs().setScale(QTY_SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
        return family + "|" + absQty.toPlainString();
    }

    private static String broadQtySignature(NormalizedTransaction tx) {
        return familySignature(tx);
    }

    private static boolean isBroadOppositeQty(NormalizedTransaction left, NormalizedTransaction right) {
        BigDecimal leftQty = principalQuantity(left);
        BigDecimal rightQty = principalQuantity(right);
        if (leftQty == null || rightQty == null) {
            return false;
        }
        return isOppositeQty(leftQty, rightQty, BROAD_QTY_TOLERANCE_PCT);
    }

    private static String sameSignMirrorSignature(NormalizedTransaction tx) {
        String broad = broadQtySignature(tx);
        if (broad == null || tx.getWalletAddress() == null) {
            return null;
        }
        int sign = principalQuantitySign(tx);
        if (sign == 0) {
            return null;
        }
        return broad + "|" + tx.getWalletAddress() + "|" + sign;
    }

    private static String familySignature(NormalizedTransaction tx) {
        if (tx == null || tx.getWalletAddress() == null) {
            return null;
        }
        String uid = extractBybitUid(tx.getWalletAddress());
        if (uid == null) {
            return null;
        }
        NormalizedTransaction.Flow principal = principalFlow(tx);
        if (principal == null) {
            return null;
        }
        String familyKey = com.walletradar.accounting.support.AccountingAssetFamilySupport
                .continuityIdentity(principal.getAssetSymbol(), principal.getAssetContract());
        if (familyKey == null || familyKey.isBlank()) {
            familyKey = principal.getAssetSymbol() == null
                    ? ""
                    : principal.getAssetSymbol().trim().toUpperCase(Locale.ROOT);
        }
        return uid + "|" + familyKey;
    }

    private static String sameWalletSignature(NormalizedTransaction tx) {
        String family = familySignature(tx);
        if (family == null || tx.getWalletAddress() == null) {
            return null;
        }
        return family + "|" + tx.getWalletAddress();
    }

    private static String extractBybitUid(String walletAddress) {
        if (walletAddress == null || !walletAddress.startsWith("BYBIT:")) {
            return null;
        }
        String remainder = walletAddress.substring("BYBIT:".length());
        int colon = remainder.indexOf(':');
        return colon >= 0 ? remainder.substring(0, colon) : remainder;
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

    private static BigDecimal principalQuantity(NormalizedTransaction tx) {
        NormalizedTransaction.Flow flow = principalFlow(tx);
        return flow == null ? null : flow.getQuantityDelta();
    }

    private static int principalQuantitySign(NormalizedTransaction tx) {
        BigDecimal qty = principalQuantity(tx);
        if (qty == null) {
            return 0;
        }
        return qty.signum();
    }

    private static String canonicalPairCorrelationId(NormalizedTransaction left, NormalizedTransaction right) {
        String low = canonicalIdOrder(left.getId(), right.getId());
        String high = Objects.equals(low, left.getId()) ? nullSafeId(right.getId()) : nullSafeId(left.getId());
        return PAIR_CORRELATION_PREFIX + low + "|" + high;
    }

    private static String rekeyedPairCorrelationId(NormalizedTransaction left, NormalizedTransaction right) {
        String uid = extractBybitUid(left.getWalletAddress());
        if (uid == null) {
            uid = extractBybitUid(right.getWalletAddress());
        }
        NormalizedTransaction.Flow principal = principalFlow(left);
        if (principal == null) {
            principal = principalFlow(right);
        }
        String familyKey = "";
        String qtyPlain = "";
        if (principal != null) {
            familyKey = com.walletradar.accounting.support.AccountingAssetFamilySupport
                    .continuityIdentity(principal.getAssetSymbol(), principal.getAssetContract());
            if (familyKey == null || familyKey.isBlank()) {
                familyKey = principal.getAssetSymbol() == null
                        ? ""
                        : principal.getAssetSymbol().trim().toUpperCase(Locale.ROOT);
            }
            if (principal.getQuantityDelta() != null) {
                qtyPlain = principal.getQuantityDelta().abs()
                        .setScale(BROAD_QTY_SCALE, RoundingMode.HALF_UP)
                        .stripTrailingZeros()
                        .toPlainString();
            }
        }
        Instant ts = left.getBlockTimestamp();
        if (right.getBlockTimestamp() != null
                && (ts == null || right.getBlockTimestamp().isBefore(ts))) {
            ts = right.getBlockTimestamp();
        }
        long epochSecond = ts == null ? 0L : ts.getEpochSecond();
        String payload = (uid == null ? "" : uid)
                + "|" + familyKey
                + "|" + qtyPlain
                + "|" + epochSecond;
        return REKEYED_CORRELATION_PREFIX + sha256Hex(payload);
    }

    private static String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String canonicalRoundtripCorrelationId(NormalizedTransaction left, NormalizedTransaction right) {
        String low = canonicalIdOrder(left.getId(), right.getId());
        String high = Objects.equals(low, left.getId()) ? nullSafeId(right.getId()) : nullSafeId(left.getId());
        return ROUNDTRIP_CORRELATION_PREFIX + low + "|" + high;
    }

    private static String canonicalBundleCorrelationId(List<NormalizedTransaction> members) {
        List<String> ids = members.stream()
                .map(NormalizedTransaction::getId)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        return BUNDLE_CORRELATION_PREFIX + String.join("|", ids);
    }

    private static String canonicalIdOrder(String leftId, String rightId) {
        String left = nullSafeId(leftId);
        String right = nullSafeId(rightId);
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static String nullSafeId(String id) {
        return id == null ? "" : id;
    }

    /**
     * Cycle/18 R9b: FA-001 on-chain↔Bybit deposit anchors must not be re-paired with Bybit stream
     * mirrors. The corridor correlation id is shared with an ON_CHAIN row, so it looks like a
     * Bybit-only singleton inside {@link #loadSingletons()}.
     */
    private static boolean isFa001OnChainCorridorAnchor(NormalizedTransaction tx) {
        if (tx == null || tx.getSource() != NormalizedTransactionSource.BYBIT) {
            return false;
        }
        String correlationId = tx.getCorrelationId();
        if (correlationId != null && correlationId.startsWith("BYBIT-CORRIDOR:")) {
            return true;
        }
        String matchedCounterparty = tx.getMatchedCounterparty();
        return matchedCounterparty != null
                && matchedCounterparty.startsWith("0x")
                && matchedCounterparty.length() == 42;
    }
}
