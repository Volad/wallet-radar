package com.walletradar.application.cex.normalization.venue.bybit;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.application.normalization.config.BybitInternalTransferProperties;
import com.walletradar.application.linking.pipeline.clarification.CorridorCorrelationKeyFactory;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.walletradar.application.cex.normalization.venue.bybit.BybitStreamAuthorityCollapserSupport.idTiebreak;

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
public class BybitInternalTransferPairer {

    public static final String BUNDLE_CORRELATION_PREFIX = "bybit-it-bundle-v1:";
    public static final String ROUNDTRIP_CORRELATION_PREFIX = "bybit-it-roundtrip-v1:";
    public static final String PAIR_CORRELATION_PREFIX = "bybit-it-pair-v1:";
    public static final String REKEYED_CORRELATION_PREFIX = "bybit-rekeyed-v1:";
    public static final String CROSS_UID_CORRELATION_PREFIX = "bybit-cross-uid-v1:";
    public static final String SAME_SIGN_MIRROR_REASON = "BYBIT_STREAM_MIRROR_SAME_SIGN";

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final int QTY_SCALE = 10;
    private static final int BROAD_QTY_SCALE = 3;
    private static final BigDecimal BROAD_QTY_TOLERANCE_PCT = new BigDecimal("0.0005");
    private static final Duration MAX_PAIR_DRIFT = Duration.ofHours(2);
    private static final Duration BROAD_PAIR_DRIFT = Duration.ofMinutes(10);
    private static final Duration SAME_SIGN_MIRROR_WINDOW = Duration.ofMinutes(10);
    private static final Pattern EVM_HEX_ADDRESS = Pattern.compile("^0x[a-fA-F0-9]{40}$");

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;
    private final BybitInternalTransferProperties properties;

    private final BybitCrossUidUniversalTransferPairer crossUidUniversalTransferPairer;

    public BybitInternalTransferPairer(
            MongoOperations mongoOperations,
            NormalizedTransactionRepository normalizedTransactionRepository,
            BybitInternalTransferProperties properties,
            BybitCrossUidUniversalTransferPairer crossUidUniversalTransferPairer
    ) {
        this.mongoOperations = mongoOperations;
        this.normalizedTransactionRepository = normalizedTransactionRepository;
        this.properties = properties;
        this.crossUidUniversalTransferPairer = crossUidUniversalTransferPairer;
    }

    public int repairAll() {
        int total = crossUidUniversalTransferPairer.pairCrossUidUniversalTransfers();
        total += pairBroadEconomicFingerprint();
        total += dedupSameSignMirrors();
        total += repairSingletonPairs();
        total += pairBundles();
        total += pairSameWalletRoundTrips();
        return total;
    }

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
            String key = BybitInternalTransferPairingPrimitives.broadQtySignature(tx);
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
            docs.sort(Comparator
                    .comparing(NormalizedTransaction::getBlockTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(idTiebreak()));
            for (int leftIndex = 0; leftIndex < docs.size(); leftIndex++) {
                NormalizedTransaction left = docs.get(leftIndex);
                if (rewritten.contains(left.getId())) {
                    continue;
                }
                int sign = BybitInternalTransferPairingPrimitives.principalQuantitySign(left);
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
                    int rightSign = BybitInternalTransferPairingPrimitives.principalQuantitySign(right);
                    if (rightSign == 0 || rightSign == sign) {
                        continue;
                    }
                    if (!BybitInternalTransferPairingPrimitives.isBroadOppositeQty(left, right)) {
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
                BybitInternalTransferPairingPrimitives.applyRekeyedDemotedPair(left, bestPartner, now);
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
            String key = BybitInternalTransferPairingPrimitives.broadQtySignature(tx);
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
            docs.sort(Comparator
                    .comparing(NormalizedTransaction::getBlockTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(idTiebreak()));
            for (int leftIndex = 0; leftIndex < docs.size(); leftIndex++) {
                NormalizedTransaction left = docs.get(leftIndex);
                if (rewritten.contains(left.getId())) {
                    continue;
                }
                int sign = BybitInternalTransferPairingPrimitives.principalQuantitySign(left);
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
                    int rightSign = BybitInternalTransferPairingPrimitives.principalQuantitySign(right);
                    if (rightSign == 0 || rightSign == sign) {
                        continue;
                    }
                    if (!BybitInternalTransferPairingPrimitives.isBroadOppositeQty(left, right)) {
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
                BybitInternalTransferPairingPrimitives.applyRekeyedPairCorrelation(left, bestPartner, now);
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
            String key = BybitInternalTransferPairingPrimitives.sameSignMirrorSignature(tx);
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
            // RC-9 D1: stable keeper selection. blockTimestamp alone is not a total order — two
            // stream mirrors can share the same minute bucket, so the keeper (and therefore which
            // sibling is demoted) flipped between full rebuild and incremental refresh. Add a
            // lowest-_id tiebreaker so the keeper is a pure function of the candidate set.
            docs.sort(Comparator
                    .comparing(NormalizedTransaction::getBlockTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(NormalizedTransaction::getId, Comparator.nullsLast(Comparator.naturalOrder())));
            NormalizedTransaction keeper = docs.get(0);
            for (int i = 1; i < docs.size(); i++) {
                NormalizedTransaction mirror = docs.get(i);
                if (BybitInternalTransferPairingPrimitives.isFa001OnChainWithdrawAnchor(mirror)) {
                    continue;
                }
                if (BybitInternalTransferPairingPrimitives.isEarnPrincipalOwned(mirror)) {
                    continue;
                }
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
            String key = BybitInternalTransferPairingPrimitives.exactQtySignature(tx);
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
            docs.sort(Comparator
                    .comparing(NormalizedTransaction::getBlockTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(idTiebreak()));
            for (int leftIndex = 0; leftIndex < docs.size(); leftIndex++) {
                NormalizedTransaction left = docs.get(leftIndex);
                if (rewritten.contains(left.getId())) {
                    continue;
                }
                int sign = BybitInternalTransferPairingPrimitives.principalQuantitySign(left);
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
                    int rightSign = BybitInternalTransferPairingPrimitives.principalQuantitySign(right);
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
                BybitInternalTransferPairingPrimitives.applyPairCorrelation(left, bestPartner, now);
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
            String key = BybitInternalTransferPairingPrimitives.familySignature(tx);
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
            docs.sort(Comparator
                    .comparing(NormalizedTransaction::getBlockTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(idTiebreak()));

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
                if (window.size() < minMembers || !BybitInternalTransferPairingPrimitives.isNearZeroBundle(window, residualPct)) {
                    continue;
                }
                String canonicalCorrelation = BybitInternalTransferPairingPrimitives.canonicalBundleCorrelationId(window);
                NormalizedTransaction firstInbound = BybitInternalTransferPairingPrimitives.firstInboundLeg(window);
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
            String key = BybitInternalTransferPairingPrimitives.sameWalletSignature(tx);
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
            docs.sort(Comparator
                    .comparing(NormalizedTransaction::getBlockTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(idTiebreak()));

            for (int outIndex = 0; outIndex < docs.size(); outIndex++) {
                NormalizedTransaction outbound = docs.get(outIndex);
                if (rewritten.contains(outbound.getId())) {
                    continue;
                }
                BigDecimal outQty = BybitInternalTransferPairingPrimitives.principalQuantity(outbound);
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
                    BigDecimal inQty = BybitInternalTransferPairingPrimitives.principalQuantity(inbound);
                    if (inQty == null || inQty.signum() <= 0) {
                        continue;
                    }
                    if (!BybitInternalTransferPairingPrimitives.isOppositeQty(outQty, inQty, tolerancePct)) {
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
                String canonicalCorrelation = BybitInternalTransferPairingPrimitives.canonicalRoundtripCorrelationId(outbound, bestInbound);
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
            if (BybitInternalTransferPairingPrimitives.isFa001OnChainCorridorAnchor(tx)) {
                continue;
            }
            if (BybitInternalTransferPairingPrimitives.isEarnPrincipalOwned(tx)) {
                continue;
            }
            String corr = tx.getCorrelationId();
            if (corr == null || corr.isBlank()) {
                continue;
            }
            // RC-9 D1 idempotency: BybitStreamAuthorityCollapser is authoritative for any leg already
            // carrying a bybit-collapsed-v1: corridor id. A collapsed pair whose non-excluded leg
            // appears as a count=1 singleton here (because its mirrored partner is excluded) must NOT
            // be re-paired by the roundtrip pass — doing so overwrites the collapser's corrId with a
            // bybit-it-roundtrip-v1: id, breaking the corridor on every subsequent run.
            if (corr.startsWith(BybitStreamAuthorityCollapser.COLLAPSED_CORR_PREFIX)) {
                continue;
            }
            if (corrIdCount.getOrDefault(corr, 0) == 1) {
                singletons.add(tx);
            }
        }
        return singletons;
    }
}
