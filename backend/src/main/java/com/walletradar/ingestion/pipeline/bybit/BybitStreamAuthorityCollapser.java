package com.walletradar.ingestion.pipeline.bybit;

import com.walletradar.accounting.support.AccountingAssetFamilySupport;
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
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Cycle/7 S1: Collapses Bybit mirror documents that describe the same economic event across
 * multiple ledger streams (FUNDING_HISTORY, INTERNAL_TRANSFER, TRANSACTION_LOG, EARN_FLEXIBLE_SAVING,
 * UNIVERSAL_TRANSFER).
 *
 * <p>Direct Mongo inspection on the user's universe found that one FUND&lt;-&gt;UTA transfer is recorded
 * up to four times — once per stream — with separate {@code _id}s and (because of cross-stream
 * timestamp drift) separate {@code bybit-econ-v1} correlation ids. All four are {@code
 * basisRelevant=true}, all four dispatch through {@code ReplayDispatcher}, and the net effect is
 * inflated quantity, destroyed basis carry, and a monotonically growing {@code quantityShortfall}.</p>
 *
 * <p>This service runs after {@link BybitInternalTransferPairer} and:
 * <ol>
 *   <li>Loads every Bybit {@code INTERNAL_TRANSFER} document.</li>
 *   <li>Groups documents by signature
 *       {@code (uid, accountingFamilyIdentity, abs(qty rounded to 10dp), bucketMinute, walletSubAccount, signDirection)}
 *       where {@code bucketMinute = floor(blockTimestamp / 60s)}.</li>
 *   <li>If a group contains more than one document (a "mirror set"), picks ONE canonical document
 *       per the source-file priority policy and demotes the rest to
 *       {@code excludedFromAccounting=true} with reason {@code BYBIT_STREAM_MIRROR_<sourceFile>}.</li>
 *   <li>Then unifies the canonical correlation id across paired senders / receivers that share the
 *       wider {@code (uid, family, |qty|, bucketMinute±drift, opposing-sub-accounts)} signature, so
 *       both sides land on the same {@code corr-family} replay queue.</li>
 * </ol>
 *
 * <p>Idempotency: re-running on already-collapsed groups is a no-op because mirror documents have
 * {@code excludedFromAccounting=true} and are skipped on subsequent passes.</p>
 *
 * <p>Stream priority policy (highest authority first):</p>
 * <table>
 *   <tr><th>Sub-account</th><th>Sign</th><th>Canonical source</th></tr>
 *   <tr><td>EARN</td><td>any</td><td>EARN_FLEXIBLE_SAVING</td></tr>
 *   <tr><td>UTA</td><td>any</td><td>INTERNAL_TRANSFER (selfTransfer) &gt; TRANSACTION_LOG</td></tr>
 *   <tr><td>FUND</td><td>any</td><td>INTERNAL_TRANSFER (selfTransfer / deposit_c2c) &gt; FUNDING_HISTORY</td></tr>
 * </table>
 *
 * <p>The collapser preserves all {@code EXTERNAL_TRANSFER_IN/OUT} rows (they are independently
 * authoritative under N17). It also leaves {@code SWAP}/{@code REWARD_CLAIM}/{@code BORROW}/{@code REPAY}
 * untouched.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BybitStreamAuthorityCollapser {

    private static final String COLLAPSED_CORR_PREFIX = "bybit-collapsed-v1:";
    private static final String ECON_CORR_PREFIX = "bybit-econ-v1:";
    private static final String EXCLUSION_REASON_PREFIX = "BYBIT_STREAM_MIRROR_";
    /** Cycle/15 round 2: orphan cont=false leg with a collapsed-pair neighbor outside the bucket. */
    private static final String EXCLUSION_REASON_DRIFT = EXCLUSION_REASON_PREFIX + "DRIFT_GT_BUCKET";
    private static final int QTY_SCALE = 10;
    /**
     * Cycle/13: tighten from 6 minutes — real UTA↔FUND transfers can occur 2–3 minutes after a
     * deposit auto-route artifact with the same |qty|; a wide window falsely collapses them.
     */
    private static final Duration BUCKET_DRIFT_WINDOW = Duration.ofSeconds(30);
    /**
     * Cycle/15 round 2: wider lookback for orphan stream-mirror dedup. Production data shows the
     * same FUND/UTA economic event recorded by multiple Bybit streams up to 4-5 minutes apart;
     * ±10 minutes covers the empirical drift envelope without crossing into legitimate same-wallet
     * round-trip territory (round-trip pairs are tagged {@code bybit-it-roundtrip-v1:}, not
     * {@code bybit-collapsed-v1:}, so the orphan-vs-collapsed neighbor lookup ignores them).
     */
    /** Covers FH/TX_LOG mirrors lagging canonical IT pairs by up to ~2 days on prod. */
    private static final Duration ORPHAN_DRIFT_WINDOW = Duration.ofHours(48);
    private static final long BUCKET_SECONDS = 60L;

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int collapseMirrors() {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("type").is(NormalizedTransactionType.INTERNAL_TRANSFER),
                Criteria.where("excludedFromAccounting").ne(true)
        ));
        List<NormalizedTransaction> docs = mongoOperations.find(query, NormalizedTransaction.class);
        if (docs.size() < 2) {
            return 0;
        }

        Map<String, List<NormalizedTransaction>> mirrorGroups = new LinkedHashMap<>();
        for (NormalizedTransaction tx : docs) {
            String signature = mirrorSignature(tx);
            if (signature == null) {
                continue;
            }
            mirrorGroups.computeIfAbsent(signature, ignored -> new ArrayList<>()).add(tx);
        }

        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        Set<String> collapsedIds = new HashSet<>();
        int mirrorRows = 0;
        for (List<NormalizedTransaction> group : mirrorGroups.values()) {
            if (group.size() < 2) {
                continue;
            }
            NormalizedTransaction canonical = pickCanonical(group);
            for (NormalizedTransaction tx : group) {
                if (tx == canonical) {
                    continue;
                }
                if (Boolean.TRUE.equals(tx.getExcludedFromAccounting())) {
                    continue;
                }
                propagateCorrelationMetadata(tx, canonical, now, dirty);
                String reason = EXCLUSION_REASON_PREFIX + sourceFileTag(tx);
                tx.setExcludedFromAccounting(true);
                tx.setAccountingExclusionReason(reason);
                tx.setUpdatedAt(now);
                dirty.add(tx);
                collapsedIds.add(tx.getId());
                mirrorRows++;
            }
        }

        // After mirror demotion, unify correlation id across complementary directions (sender/
        // receiver) within a broader drift window so both surviving legs share the same
        // `corr-family:<corrId>:<assetIdentity>` bucket during replay.
        int corrRewrites = unifyOpposingCorrelations(docs, collapsedIds, now, dirty);

        // After the opposing-pair unifier picks the canonical sender/receiver legs, any
        // remaining same-|qty| document within the same uid+family broad group and within the
        // drift window is a stream mirror of one of the matched legs (typical pattern: FH
        // `Transfer out` posted ~2 minutes after the canonical TX_LOG/INTERNAL_TRANSFER pair).
        // Demote them so replay does not dispatch the same economic flow twice.
        int residualMirrors = demoteResidualMirrors(docs, collapsedIds, now, dirty);

        // Cycle/8 S2: event-count-based mirror demotion. Real FH `Transfer in/out` mirrors can
        // lag the canonical TX_LOG / INTERNAL_TRANSFER pair by 18 hours and even 2 days (Bybit
        // reconciliation), far beyond {@link #BUCKET_DRIFT_WINDOW}. Time-window based pairing
        // misses these mirrors and they enter AVCO replay as phantom CARRY_IN with $0 basis,
        // destroying coverage. Instead, group docs by full (uid, family, |qty|, walletAddress,
        // sign) identity (no time bucket) and rely on per-source-file counts: if a lower-
        // authority source has at most as many active docs as the canonical source, every doc
        // in the lower-authority source is a logging mirror — demote them all regardless of
        // wall-clock distance.
        int eventCountMirrors = demoteEventCountMirrors(docs, collapsedIds, now, dirty);

        // Cycle/15 round 2: drift-window orphan dedup. After all primary passes some
        // `bybit-econ-v1` legs remain `continuityCandidate=false` because their canonical pair
        // sits more than 60s away (typical 1-5 min). If a `bybit-collapsed-v1` neighbor with the
        // same (uid, family, |qty|, walletAddress, sign) exists within ±10 min, the orphan is a
        // late stream-mirror and must be excluded from accounting; otherwise it leaks basis.
        int driftMirrors = collapseOrphanMirrorsAdjacentToCollapsedPairs(docs, collapsedIds, now, dirty);

        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info(
                    "BYBIT_STREAM_AUTHORITY_COLLAPSER candidates={} mirrors_demoted={} residual_mirrors={} event_count_mirrors={} drift_mirrors={} corr_rewrites={}",
                    docs.size(), mirrorRows, residualMirrors, eventCountMirrors, driftMirrors, corrRewrites
            );
        }
        return dirty.size();
    }

    /**
     * Cycle/15 round 2: drift-window orphan stream-mirror demotion.
     *
     * <p>Targets {@code continuityCandidate=false} legs with a {@code bybit-econ-v1:} correlation
     * id whose canonical pair landed on a {@code bybit-collapsed-v1:} neighbor outside the 30s
     * primary {@link #BUCKET_DRIFT_WINDOW} — typical FUND/UTA cross-stream latency is 1-5
     * minutes. Such orphans are stream mirrors of an already-collapsed pair and must be excluded
     * from accounting so AVCO replay does not double-count basis.</p>
     *
     * <p>Match key: {@code (uid, family, |qty| @ 10dp, walletAddress, sign)}. The orphan must
     * have a same-key neighbor within {@link #ORPHAN_DRIFT_WINDOW} whose correlation id starts
     * with {@code bybit-collapsed-v1:}. Round-trip pairs ({@code bybit-it-roundtrip-v1:}) are
     * intentionally NOT counted as canonical neighbors — those are real round-trips, not
     * mirrors.</p>
     */
    private int collapseOrphanMirrorsAdjacentToCollapsedPairs(
            List<NormalizedTransaction> allDocs,
            Set<String> collapsedIds,
            Instant now,
            List<NormalizedTransaction> dirtyAccumulator
    ) {
        Map<String, List<NormalizedTransaction>> bucketsByKey = new HashMap<>();
        for (NormalizedTransaction tx : allDocs) {
            if (Boolean.TRUE.equals(tx.getExcludedFromAccounting())) {
                continue;
            }
            String key = walletSignSignature(tx);
            if (key == null) {
                continue;
            }
            bucketsByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(tx);
        }
        int demoted = 0;
        for (List<NormalizedTransaction> bucket : bucketsByKey.values()) {
            if (bucket.size() < 2) {
                continue;
            }
            for (NormalizedTransaction orphan : bucket) {
                if (!isDriftOrphanCandidate(orphan) || collapsedIds.contains(orphan.getId())) {
                    continue;
                }
                if ("EARN".equals(extractSubAccount(orphan.getWalletAddress()))) {
                    continue;
                }
                Instant orphanTs = orphan.getBlockTimestamp();
                if (orphanTs == null) {
                    continue;
                }
                boolean hasCollapsedNeighbor = false;
                for (NormalizedTransaction neighbor : bucket) {
                    if (neighbor == orphan || neighbor.getBlockTimestamp() == null) {
                        continue;
                    }
                    if (neighbor.getCorrelationId() == null
                            || !neighbor.getCorrelationId().startsWith(COLLAPSED_CORR_PREFIX)) {
                        continue;
                    }
                    Duration delta = Duration.between(orphanTs, neighbor.getBlockTimestamp()).abs();
                    if (delta.compareTo(ORPHAN_DRIFT_WINDOW) <= 0) {
                        hasCollapsedNeighbor = true;
                        break;
                    }
                }
                if (!hasCollapsedNeighbor) {
                    continue;
                }
                orphan.setExcludedFromAccounting(true);
                orphan.setAccountingExclusionReason(EXCLUSION_REASON_DRIFT);
                orphan.setUpdatedAt(now);
                collapsedIds.add(orphan.getId());
                dirtyAccumulator.add(orphan);
                demoted++;
            }
        }
        return demoted;
    }

    private static boolean isDriftOrphanCandidate(NormalizedTransaction tx) {
        if (tx == null) {
            return false;
        }
        if (Boolean.TRUE.equals(tx.getContinuityCandidate())) {
            return false;
        }
        String corr = tx.getCorrelationId();
        return corr != null && corr.startsWith(ECON_CORR_PREFIX);
    }

    /**
     * Cycle/8 S2: event-count-based mirror demotion. Within a per-wallet, per-sign, per-quantity
     * group, the highest-priority source (per {@link #canonicalPriority(String, String)} for the
     * sub-account) is the canonical authority. Any lower-priority source with active doc count
     * not exceeding the canonical count is treated as mirror-only and demoted en bloc.
     *
     * <p>Demotion preserves correlation id rewrites already applied by the primary / unifier
     * passes; only {@code excludedFromAccounting} and {@code accountingExclusionReason} are
     * mutated, so AVCO replay skips the mirror flows.</p>
     */
    private int demoteEventCountMirrors(
            List<NormalizedTransaction> allDocs,
            Set<String> collapsedIds,
            Instant now,
            List<NormalizedTransaction> dirtyAccumulator
    ) {
        // Bucket by (uid, family, |qty|, walletAddress, sign) — full identity except time.
        Map<String, List<NormalizedTransaction>> buckets = new HashMap<>();
        for (NormalizedTransaction tx : allDocs) {
            if (collapsedIds.contains(tx.getId())) {
                continue;
            }
            if (Boolean.TRUE.equals(tx.getExcludedFromAccounting())) {
                continue;
            }
            String key = walletSignSignature(tx);
            if (key == null) {
                continue;
            }
            buckets.computeIfAbsent(key, ignored -> new ArrayList<>()).add(tx);
        }
        int demoted = 0;
        for (List<NormalizedTransaction> bucket : buckets.values()) {
            if (bucket.size() < 2) {
                continue;
            }
            // Group active docs by source file.
            Map<String, List<NormalizedTransaction>> bySource = new LinkedHashMap<>();
            for (NormalizedTransaction tx : bucket) {
                bySource.computeIfAbsent(sourceFileTag(tx), ignored -> new ArrayList<>()).add(tx);
            }
            if (bySource.size() < 2) {
                continue;
            }
            String subAccount = extractSubAccount(bucket.get(0).getWalletAddress());
            // Resolve canonical source: smallest priority value among sources with >=1 active doc.
            String canonicalSource = null;
            int canonicalPriority = Integer.MAX_VALUE;
            for (String source : bySource.keySet()) {
                int priority = canonicalPriority(source, subAccount);
                if (priority < canonicalPriority) {
                    canonicalPriority = priority;
                    canonicalSource = source;
                }
            }
            if (canonicalSource == null) {
                continue;
            }
            int canonicalCount = bySource.get(canonicalSource).size();
            for (Map.Entry<String, List<NormalizedTransaction>> entry : bySource.entrySet()) {
                String source = entry.getKey();
                if (source.equals(canonicalSource)) {
                    continue;
                }
                List<NormalizedTransaction> mirrors = entry.getValue();
                // Conservative guard: more mirror docs than canonical events means we cannot
                // safely assert every mirror is redundant. Leave the entire source alone — the
                // primary / residual passes have already done what they can within the drift
                // window.
                if (mirrors.size() > canonicalCount) {
                    continue;
                }
                for (NormalizedTransaction tx : mirrors) {
                    if (Boolean.TRUE.equals(tx.getExcludedFromAccounting())) {
                        continue;
                    }
                    String reason = EXCLUSION_REASON_PREFIX + source;
                    tx.setExcludedFromAccounting(true);
                    tx.setAccountingExclusionReason(reason);
                    tx.setUpdatedAt(now);
                    collapsedIds.add(tx.getId());
                    dirtyAccumulator.add(tx);
                    demoted++;
                }
            }
        }
        return demoted;
    }

    /**
     * Cycle/8 S2: full per-wallet identity key without a time bucket. Used by event-count
     * mirror demotion so that FH mirrors arriving days after the canonical pair are still
     * grouped together.
     */
    private String walletSignSignature(NormalizedTransaction tx) {
        if (tx == null || tx.getWalletAddress() == null) {
            return null;
        }
        String uid = extractBybitUid(tx.getWalletAddress());
        if (uid == null) {
            return null;
        }
        NormalizedTransaction.Flow principal = principalFlow(tx);
        if (principal == null || principal.getQuantityDelta() == null) {
            return null;
        }
        String family = AccountingAssetFamilySupport.continuityIdentity(principal);
        if (family == null) {
            family = principal.getAssetSymbol() == null
                    ? "?"
                    : principal.getAssetSymbol().trim().toUpperCase(Locale.ROOT);
        }
        BigDecimal absQty = principal.getQuantityDelta().abs()
                .setScale(QTY_SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
        int sign = principal.getQuantityDelta().signum();
        if (sign == 0) {
            return null;
        }
        return String.join("|",
                uid,
                family,
                absQty.toPlainString(),
                tx.getWalletAddress(),
                Integer.toString(sign)
        );
    }

    /**
     * Cycle/7 S1: residual mirror demotion. Iterates broad-signature groups (uid, family, |qty|)
     * that contain at least one bybit-collapsed-v1 paired set, and demotes any remaining same-qty
     * doc within {@link #BUCKET_DRIFT_WINDOW} of the pair as a stream mirror.
     */
    private int demoteResidualMirrors(
            List<NormalizedTransaction> allDocs,
            Set<String> collapsedIds,
            Instant now,
            List<NormalizedTransaction> dirtyAccumulator
    ) {
        Map<String, List<NormalizedTransaction>> broadGroups = new HashMap<>();
        for (NormalizedTransaction tx : allDocs) {
            String key = broadSignature(tx);
            if (key == null) {
                continue;
            }
            broadGroups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(tx);
        }
        int residual = 0;
        for (List<NormalizedTransaction> bucket : broadGroups.values()) {
            if (bucket.size() < 3) {
                continue;
            }
            // Find the canonical pair: two docs (opposite signs) sharing a bybit-collapsed-v1 id.
            NormalizedTransaction canonicalDebit = null;
            NormalizedTransaction canonicalCredit = null;
            for (NormalizedTransaction tx : bucket) {
                if (tx.getCorrelationId() != null
                        && tx.getCorrelationId().startsWith(COLLAPSED_CORR_PREFIX)) {
                    int sign = principalSign(tx);
                    if (sign < 0 && canonicalDebit == null) {
                        canonicalDebit = tx;
                    } else if (sign > 0 && canonicalCredit == null) {
                        canonicalCredit = tx;
                    }
                }
            }
            if (canonicalDebit == null || canonicalCredit == null) {
                continue;
            }
            Instant anchor = canonicalDebit.getBlockTimestamp() != null
                    ? canonicalDebit.getBlockTimestamp()
                    : canonicalCredit.getBlockTimestamp();
            if (anchor == null) {
                continue;
            }
            for (NormalizedTransaction tx : bucket) {
                if (tx == canonicalDebit || tx == canonicalCredit) {
                    continue;
                }
                if (Boolean.TRUE.equals(tx.getExcludedFromAccounting())) {
                    continue;
                }
                if (tx.getBlockTimestamp() == null) {
                    continue;
                }
                Duration delta = Duration.between(anchor, tx.getBlockTimestamp()).abs();
                if (delta.compareTo(BUCKET_DRIFT_WINDOW) > 0) {
                    continue;
                }
                String reason = EXCLUSION_REASON_PREFIX + sourceFileTag(tx);
                tx.setExcludedFromAccounting(true);
                tx.setAccountingExclusionReason(reason);
                tx.setCorrelationId(canonicalDebit.getCorrelationId());
                tx.setUpdatedAt(now);
                collapsedIds.add(tx.getId());
                dirtyAccumulator.add(tx);
                residual++;
            }
        }
        return residual;
    }

    /**
     * When an excluded mirror doc carries correlation metadata that the canonical survivor lacks,
     * propagate it to the survivor so the replay engine can still route the carry correctly.
     */
    private static void propagateCorrelationMetadata(
            NormalizedTransaction excluded,
            NormalizedTransaction canonical,
            Instant now,
            List<NormalizedTransaction> dirtyAccumulator
    ) {
        if (excluded.getCorrelationId() == null || excluded.getCorrelationId().isBlank()) {
            return;
        }
        if (canonical.getCorrelationId() != null && !canonical.getCorrelationId().isBlank()) {
            return;
        }
        canonical.setCorrelationId(excluded.getCorrelationId());
        canonical.setMatchedCounterparty(excluded.getMatchedCounterparty());
        canonical.setContinuityCandidate(excluded.getContinuityCandidate());
        canonical.setUpdatedAt(now);
        if (!dirtyAccumulator.contains(canonical)) {
            dirtyAccumulator.add(canonical);
        }
    }

    // -------------------------------------------------------------------------
    // Mirror grouping
    // -------------------------------------------------------------------------

    private String mirrorSignature(NormalizedTransaction tx) {
        if (tx == null || tx.getWalletAddress() == null || tx.getBlockTimestamp() == null) {
            return null;
        }
        String uid = extractBybitUid(tx.getWalletAddress());
        if (uid == null) {
            return null;
        }
        String subAccount = extractSubAccount(tx.getWalletAddress());
        NormalizedTransaction.Flow principal = principalFlow(tx);
        if (principal == null || principal.getQuantityDelta() == null) {
            return null;
        }
        BigDecimal qty = principal.getQuantityDelta();
        int sign = qty.signum();
        if (sign == 0) {
            return null;
        }
        String family = AccountingAssetFamilySupport.continuityIdentity(principal);
        if (family == null) {
            family = principal.getAssetSymbol() == null
                    ? "?"
                    : principal.getAssetSymbol().trim().toUpperCase(Locale.ROOT);
        }
        String absQty = qty.abs().setScale(QTY_SCALE, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        long bucket = tx.getBlockTimestamp().getEpochSecond() / BUCKET_SECONDS;
        return String.join("|",
                uid,
                family,
                absQty,
                Long.toString(bucket),
                subAccount,
                Integer.toString(sign)
        );
    }

    private NormalizedTransaction pickCanonical(List<NormalizedTransaction> group) {
        group.sort(Comparator
                .comparingInt((NormalizedTransaction tx) -> canonicalPriority(tx))
                .thenComparing(NormalizedTransaction::getBlockTimestamp,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(NormalizedTransaction::getId,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        return group.get(0);
    }

    /**
     * Lower priority = preferred canonical document. Selection rules:
     * <ul>
     *   <li>EARN sub-account: prefer EARN_FLEXIBLE_SAVING (semantics owner).</li>
     *   <li>UTA sub-account: prefer INTERNAL_TRANSFER (raw csv), then TRANSACTION_LOG.</li>
     *   <li>FUND sub-account: prefer INTERNAL_TRANSFER (raw csv), then FUNDING_HISTORY.</li>
     * </ul>
     */
    private int canonicalPriority(NormalizedTransaction tx) {
        return canonicalPriority(sourceFileTag(tx), extractSubAccount(tx.getWalletAddress()));
    }

    /** Cycle/8 S2: source/sub-account variant for the event-count demotion pass. */
    private int canonicalPriority(String source, String subAccount) {
        if ("EARN".equals(subAccount)) {
            return switch (source) {
                case "EARN_FLEXIBLE_SAVING" -> 0;
                case "INTERNAL_TRANSFER" -> 1;
                case "FUNDING_HISTORY" -> 2;
                case "TRANSACTION_LOG" -> 3;
                case "UNIVERSAL_TRANSFER" -> 4;
                default -> 5;
            };
        }
        if ("UTA".equals(subAccount)) {
            return switch (source) {
                case "INTERNAL_TRANSFER" -> 0;
                case "TRANSACTION_LOG" -> 1;
                case "FUNDING_HISTORY" -> 2;
                case "UNIVERSAL_TRANSFER" -> 3;
                case "EARN_FLEXIBLE_SAVING" -> 4;
                default -> 5;
            };
        }
        // FUND and everything else.
        return switch (source) {
            case "INTERNAL_TRANSFER" -> 0;
            case "FUNDING_HISTORY" -> 1;
            case "TRANSACTION_LOG" -> 2;
            case "UNIVERSAL_TRANSFER" -> 3;
            case "EARN_FLEXIBLE_SAVING" -> 4;
            default -> 5;
        };
    }

    // -------------------------------------------------------------------------
    // Cross-direction correlation unification
    // -------------------------------------------------------------------------

    /**
     * After mirror demotion, the surviving canonical documents may still describe one economic
     * transfer split into two legs (sender + receiver) on opposing sub-accounts. Their
     * {@code bybit-econ-v1} correlation ids drift across minutes; here we re-key them onto a single
     * {@code bybit-collapsed-v1:<sha256>} correlation id so the replay engine's
     * {@code corr-family:<corrId>:<assetIdentity>} queue receives both legs.
     */
    private int unifyOpposingCorrelations(
            List<NormalizedTransaction> allDocs,
            Set<String> collapsedIds,
            Instant now,
            List<NormalizedTransaction> dirtyAccumulator
    ) {
        // Bucket by (uid, family, |qty|) and within each bucket pair opposing-sign documents within
        // BUCKET_DRIFT_WINDOW that are not yet excluded.
        Map<String, List<NormalizedTransaction>> byBroadSignature = new HashMap<>();
        for (NormalizedTransaction tx : allDocs) {
            if (collapsedIds.contains(tx.getId())) {
                continue;
            }
            if (Boolean.TRUE.equals(tx.getExcludedFromAccounting())) {
                continue;
            }
            // Cycle/15: omit minute bucket so opposing legs that drifted across a minute boundary
            // still land in the same unify bucket (BUCKET_DRIFT_WINDOW gates pairing).
            String key = economicUnifySignature(tx);
            if (key == null) {
                continue;
            }
            byBroadSignature.computeIfAbsent(key, ignored -> new ArrayList<>()).add(tx);
        }
        int rewrites = 0;
        Set<String> reKeyed = new HashSet<>();
        for (List<NormalizedTransaction> bucket : byBroadSignature.values()) {
            if (bucket.size() < 2) {
                continue;
            }
            bucket.sort(Comparator.comparing(NormalizedTransaction::getBlockTimestamp,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            for (int i = 0; i < bucket.size(); i++) {
                NormalizedTransaction left = bucket.get(i);
                if (reKeyed.contains(left.getId())) {
                    continue;
                }
                int leftSign = principalSign(left);
                if (leftSign == 0) {
                    continue;
                }
                NormalizedTransaction bestRight = null;
                Duration bestDelta = BUCKET_DRIFT_WINDOW.plusSeconds(1);
                for (int j = i + 1; j < bucket.size(); j++) {
                    NormalizedTransaction right = bucket.get(j);
                    if (reKeyed.contains(right.getId())) {
                        continue;
                    }
                    int rightSign = principalSign(right);
                    if (rightSign == 0 || rightSign == leftSign) {
                        continue;
                    }
                    if (left.getBlockTimestamp() == null || right.getBlockTimestamp() == null) {
                        continue;
                    }
                    Duration delta = Duration.between(left.getBlockTimestamp(), right.getBlockTimestamp()).abs();
                    if (delta.compareTo(BUCKET_DRIFT_WINDOW) > 0) {
                        continue;
                    }
                    if (delta.compareTo(bestDelta) < 0) {
                        bestDelta = delta;
                        bestRight = right;
                    }
                }
                if (bestRight == null) {
                    continue;
                }
                String canonicalCorr = collapsedCorrelationId(left, bestRight);
                if (canonicalCorr.equals(left.getCorrelationId())
                        && canonicalCorr.equals(bestRight.getCorrelationId())) {
                    continue;
                }
                left.setCorrelationId(canonicalCorr);
                bestRight.setCorrelationId(canonicalCorr);
                left.setContinuityCandidate(true);
                bestRight.setContinuityCandidate(true);
                if (left.getMatchedCounterparty() == null) {
                    left.setMatchedCounterparty(bestRight.getWalletAddress());
                }
                if (bestRight.getMatchedCounterparty() == null) {
                    bestRight.setMatchedCounterparty(left.getWalletAddress());
                }
                left.setUpdatedAt(now);
                bestRight.setUpdatedAt(now);
                reKeyed.add(left.getId());
                reKeyed.add(bestRight.getId());
                dirtyAccumulator.add(left);
                dirtyAccumulator.add(bestRight);
                rewrites += 2;
            }
        }
        return rewrites;
    }

    private String broadSignature(NormalizedTransaction tx) {
        String base = economicUnifySignature(tx);
        if (base == null) {
            return null;
        }
        long bucket = tx.getBlockTimestamp() != null
                ? tx.getBlockTimestamp().getEpochSecond() / BUCKET_SECONDS
                : 0L;
        return base + "|" + bucket;
    }

    /** uid + family + |qty| without minute bucket — used for cross-minute opposing-leg unify. */
    private String economicUnifySignature(NormalizedTransaction tx) {
        if (tx == null || tx.getWalletAddress() == null) {
            return null;
        }
        String uid = extractBybitUid(tx.getWalletAddress());
        if (uid == null) {
            return null;
        }
        NormalizedTransaction.Flow principal = principalFlow(tx);
        if (principal == null || principal.getQuantityDelta() == null) {
            return null;
        }
        String family = AccountingAssetFamilySupport.continuityIdentity(principal);
        if (family == null) {
            family = principal.getAssetSymbol() == null
                    ? "?"
                    : principal.getAssetSymbol().trim().toUpperCase(Locale.ROOT);
        }
        BigDecimal absQty = principal.getQuantityDelta().abs()
                .setScale(QTY_SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
        return uid + "|" + family + "|" + absQty.toPlainString();
    }

    private static String collapsedCorrelationId(NormalizedTransaction left, NormalizedTransaction right) {
        String leftId = left.getId() == null ? "" : left.getId();
        String rightId = right.getId() == null ? "" : right.getId();
        String low = leftId.compareTo(rightId) <= 0 ? leftId : rightId;
        String high = low.equals(leftId) ? rightId : leftId;
        return COLLAPSED_CORR_PREFIX + sha256Hex(low + "|" + high);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String extractBybitUid(String walletAddress) {
        if (walletAddress == null || !walletAddress.startsWith("BYBIT:")) {
            return null;
        }
        String remainder = walletAddress.substring("BYBIT:".length());
        int colon = remainder.indexOf(':');
        return colon >= 0 ? remainder.substring(0, colon) : remainder;
    }

    private static String extractSubAccount(String walletAddress) {
        if (walletAddress == null) {
            return "";
        }
        int colon = walletAddress.lastIndexOf(':');
        if (colon < 0 || colon == walletAddress.length() - 1) {
            return "";
        }
        return walletAddress.substring(colon + 1).toUpperCase(Locale.ROOT);
    }

    private static String sourceFileTag(NormalizedTransaction tx) {
        if (tx == null || tx.getId() == null) {
            return "UNKNOWN";
        }
        // Normalized id layout: BYBIT-<uid>:<SOURCE_FILE>:<row-key...>
        String id = tx.getId();
        int firstColon = id.indexOf(':');
        if (firstColon < 0) {
            return "UNKNOWN";
        }
        int secondColon = id.indexOf(':', firstColon + 1);
        if (secondColon < 0) {
            return id.substring(firstColon + 1).toUpperCase(Locale.ROOT);
        }
        return id.substring(firstColon + 1, secondColon).toUpperCase(Locale.ROOT);
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

    private static int principalSign(NormalizedTransaction tx) {
        NormalizedTransaction.Flow flow = principalFlow(tx);
        return flow == null || flow.getQuantityDelta() == null ? 0 : flow.getQuantityDelta().signum();
    }

    private static String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
