package com.walletradar.application.linking.pipeline.clarification;

import com.walletradar.canonical.correlation.BybitCarryContinuitySupport;
import com.walletradar.canonical.correlation.CorrelationContract;
import com.walletradar.application.costbasis.support.AccountingAssetClassificationSupport;
import com.walletradar.application.costbasis.support.AccountingAssetFamilySupport;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionRepository;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionStatus;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import com.walletradar.domain.wallet.WalletDomainKind;
import com.walletradar.domain.wallet.WalletRef;
import com.walletradar.application.cex.normalization.venue.bybit.BybitEarnPrincipalTransferPairer;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * B-EARN-DEPOSIT-MISSING: synthesises missing EARN-account counterpart for Bybit "On-chain Earn
 * subscription" FUND outflows where Bybit's API did not emit the matching EARN inflow event.
 *
 * <p>Normal Bybit Flexible Savings flows are recorded on both sides (FUND debit +
 * EARN_FLEXIBLE_SAVING credit), which the {@code BybitStreamAuthorityCollapser} pairs into a
 * {@code bybit-collapsed-v1:} corrId. For certain "On-chain Earn" subscriptions the EARN-side
 * FUNDING_HISTORY event is absent from the ingested data. The FUND outflow then carries basis out
 * of the accounting universe with no matching CARRY_IN at EARN.
 *
 * <p>This service detects FUND {@code INTERNAL_TRANSFER} legs with a blank {@code correlationId}
 * and no existing EARN-side counterpart (same uid, same asset family, same |qty| ±ε, within ±6h),
 * then synthesises a matching EARN {@code INTERNAL_TRANSFER} and assigns a shared deterministic
 * {@code bybit-earn-onchain-v1:} corrId to both. The replay engine then routes both legs to the
 * same {@code corr-family} queue, correctly emitting CARRY_OUT at FUND and CARRY_IN at EARN.
 *
 * <p><b>Existing real EARN credit (no synthesis — link instead).</b> For many subscriptions Bybit
 * <em>does</em> emit the EARN-side credit, but typed as {@code EARN_FLEXIBLE_SAVING} (or
 * {@code LENDING_DEPOSIT}) rather than {@code INTERNAL_TRANSFER}. Previously the counterpart probe
 * only looked at {@code INTERNAL_TRANSFER} EARN legs, so it missed these and synthesised a
 * <em>duplicate</em> EARN credit — the real {@code EARN_FLEXIBLE_SAVING} credit AND the synthetic
 * both landed on {@code :EARN}, while only the real one was later redeemed. The synthetic residue
 * accumulated on {@code :EARN} every cycle (e.g. MNT/USDT/METH phantom pools). The probe now
 * recognises {@code EARN_FLEXIBLE_SAVING}/{@code LENDING_DEPOSIT} EARN credits, and when one
 * exists the FUND debit is <em>paired to that real credit</em> with a shared
 * {@code bybit-earn-principal-v1:} corrId instead of synthesising a duplicate. Replay then carries
 * the FUND principal basis into the existing {@code :EARN} credit (single key) and a closed
 * subscribe→redeem cycle nets to zero on {@code :EARN}.
 *
 * <p>Idempotent: skips candidates whose synthetic partner already exists by deterministic ID, and
 * linked legs gain a non-blank corrId so they are no longer FUND orphans on re-run.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BybitOnChainEarnOrphanRepairService {

    /**
     * Used for FUND events that arrived via spot trading (not a corridor deposit). The position
     * key for these is the stripped {@code BYBIT:uid} wallet — {@code isEarnPrincipalPaired}
     * in {@link com.walletradar.application.costbasis.support.AccountingAssetIdentitySupport} does NOT
     * activate for this prefix so the FUND sub-account suffix is stripped as normal.
     */
    public static final String EARN_ONCHAIN_CORR_PREFIX = CorrelationContract.BYBIT_EARN_ONCHAIN_V1_PREFIX;

    /**
     * Used for FUND events that arrived via a BYBIT-CORRIDOR deposit directly into the
     * {@code :FUND} sub-account. The position key must preserve the full {@code :FUND} wallet
     * so the CARRY_OUT drains the funded sub-account position rather than the empty root.
     * {@code isEarnPrincipalPaired} activates only for this prefix.
     */
    public static final String EARN_ONCHAIN_FUND_CORR_PREFIX = CorrelationContract.BYBIT_EARN_ONCHAIN_FUND_V1_PREFIX;

    public static final String SYNTHETIC_ID_PREFIX = "bybit-earn-onchain-synthetic-v1:";
    private static final int QTY_SCALE = 8;
    private static final Duration EARN_COUNTERPART_WINDOW = Duration.ofHours(6);
    private static final BigDecimal QTY_TOLERANCE = new BigDecimal("0.00000001");
    private static final BigDecimal CORRIDOR_QTY_TOLERANCE_PCT = new BigDecimal("0.01");
    private static final MathContext MC = MathContext.DECIMAL128;

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    public int repairOrphans() {
        List<NormalizedTransaction> candidates = loadFundOrphans();
        if (candidates.isEmpty()) {
            return 0;
        }

        List<NormalizedTransaction> allEarnLegs = loadEarnCreditLegs();
        // EARN credits already consumed by a FUND debit in this run must not be linked twice.
        Set<String> consumedEarnIds = new HashSet<>();

        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        int repaired = 0;

        for (NormalizedTransaction fund : candidates) {
            // Idempotency guard (linking convergence): a FUND debit already carrying a correlation
            // id was paired/handled on a prior pass and must never be re-processed. loadFundOrphans
            // already filters blank corrIds at the DB level; this in-memory guard keeps the repair a
            // true fixed point (second invocation over the same input reports repaired=0) even if a
            // stamped row is handed back to us, so the LinkingBatchProcessor convergence loop ends.
            if (fund.getCorrelationId() != null && !fund.getCorrelationId().isBlank()) {
                continue;
            }
            NormalizedTransaction.Flow principal = principalFlow(fund);
            if (principal == null || principal.getQuantityDelta() == null
                    || principal.getQuantityDelta().signum() >= 0) {
                continue;
            }

            String uid = extractBybitUid(fund.getWalletAddress());
            if (uid == null) {
                continue;
            }

            String assetSymbol = principal.getAssetSymbol();
            String assetContract = principal.getAssetContract();
            BigDecimal absQty = principal.getQuantityDelta().abs();
            String assetFamily = assetFamily(principal);

            // Determine corridor funding up-front: corridor-funded events use a 1% quantity
            // tolerance for EARN counterpart matching (Bybit truncates EARN amounts to fewer
            // decimal places than the FUND debit, causing a sub-microsecond diff that exceeds
            // the absolute tolerance). BLOCKER-5A fix.
            String corrIdPrefix = hasRecentCorridorDeposit(uid, assetFamily, absQty, fund.getBlockTimestamp())
                    ? EARN_ONCHAIN_FUND_CORR_PREFIX
                    : EARN_ONCHAIN_CORR_PREFIX;
            boolean corridorFunded = EARN_ONCHAIN_FUND_CORR_PREFIX.equals(corrIdPrefix);

            NormalizedTransaction earnCounterpart = findEarnCounterpart(
                    allEarnLegs, uid, assetFamily, absQty, fund.getBlockTimestamp(), consumedEarnIds, corridorFunded);
            if (earnCounterpart != null) {
                // A real EARN-side credit (EARN_FLEXIBLE_SAVING / LENDING_DEPOSIT) already records
                // this subscription: pair the FUND debit to it under a shared earn-principal corrId
                // rather than synthesising a duplicate EARN credit (which would double-count :EARN).
                // INTERNAL_TRANSFER counterparts are left untouched (already handled/synthesised
                // elsewhere) — same skip behaviour as before.
                if (isLinkableEarnCredit(earnCounterpart)) {
                    // Capture existing EARN corrId before overwriting: if the EARN credit was
                    // already paired to a UTA transfer via bybit-earn-principal-v1, that UTA
                    // counterpart must be excluded from accounting to avoid a redundant umbrella
                    // drain that creates a phantom shortfall (corridor-funded FUND→EARN scenario).
                    String existingEarnCorrId = earnCounterpart.getCorrelationId();
                    linkEarnPrincipalPair(fund, earnCounterpart, assetFamily, absQty, now);
                    consumedEarnIds.add(earnCounterpart.getId());
                    dirty.add(fund);
                    dirty.add(earnCounterpart);
                    if (corridorFunded
                            && existingEarnCorrId != null
                            && existingEarnCorrId.startsWith(CorrelationContract.BYBIT_EARN_PRINCIPAL_V1_PREFIX)) {
                        NormalizedTransaction utaCounterpart = findEarnPrincipalUtaCounterpart(
                                existingEarnCorrId, earnCounterpart.getId());
                        if (utaCounterpart != null) {
                            utaCounterpart.setExcludedFromAccounting(true);
                            utaCounterpart.setUpdatedAt(now);
                            dirty.add(utaCounterpart);
                        }
                    }
                    repaired++;
                }
                continue;
            }

            // Cross-asset ETH-family earn conversion guard. A Bybit on-chain "Earn" subscription in
            // the ETH family is a cross-asset conversion (e.g. METH/ETH → cmETH): the FUND debit and
            // the different-symbol EARN credit are fused into a single STAKING_DEPOSIT during
            // normalization (BybitCanonicalTransactionBuilder#buildCrossSubAccountStakingPair) so the
            // source family basis carries into the received token. If such a fused pair was NOT formed
            // (e.g. the credit leg fell just outside the liquid-staking window), synthesising a
            // SAME-asset EARN credit here would create an irreducible phantom — a standing :EARN
            // credit with no redemption to ever close it. Skip synthesis in that case. Same-symbol
            // earn (MNT/LTC/LDO/ARB/LINK/USDT) has no different-symbol same-family credit, so this
            // guard never fires for it.
            if (hasCrossAssetFamilyEarnCredit(
                    allEarnLegs, uid, assetFamily, assetSymbol, fund.getBlockTimestamp(), consumedEarnIds)) {
                log.info("BYBIT_EARN_ONCHAIN_CROSS_ASSET_SKIP fundId={} symbol={} family={}",
                        fund.getId(), assetSymbol, assetFamily);
                continue;
            }

            String syntheticId = syntheticId(fund.getId());
            // Idempotency: skip if synthetic already persisted from a prior run.
            if (syntheticExists(syntheticId)) {
                continue;
            }

            // corrIdPrefix already computed above based on corridor detection.
            String corrId = corrId(corrIdPrefix, fund.getId(), assetFamily, absQty);

            NormalizedTransaction synthetic = buildSyntheticEarnTransaction(
                    syntheticId, uid, assetSymbol, assetContract, absQty,
                    fund.getBlockTimestamp(), corrId, fund.getWalletAddress(), now
            );

            fund.setCorrelationId(corrId);
            fund.setMatchedCounterparty(earnWallet(uid));
            fund.setContinuityCandidate(true);
            fund.setUpdatedAt(now);

            dirty.add(fund);
            dirty.add(synthetic);
            repaired++;
        }

        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("BYBIT_EARN_ONCHAIN_ORPHAN_REPAIR candidates={} repaired={}", candidates.size(), repaired);
        }
        return repaired;
    }

    /**
     * Retroactive repair for corridor-funded earn duplicates that were incorrectly synthesised
     * on a prior run.
     *
     * <p>Root cause: {@link #repairOrphans()} used an absolute quantity tolerance ({@code 1e-8})
     * that failed to match EARN_FLEXIBLE_SAVING events where Bybit truncated the amount to fewer
     * decimal places than the FUND debit (typical difference: ~1e-7). The service synthesised a
     * duplicate EARN credit (via {@code bybit-earn-onchain-fund-v1:}) instead of linking to the
     * existing EARN_FLEXIBLE_SAVING. This caused:
     * <ol>
     *   <li>A double credit on {@code :EARN} (synthetic + EARN_FLEXIBLE_SAVING).</li>
     *   <li>The UTA counterpart of the EARN_FLEXIBLE_SAVING draining the already-empty umbrella,
     *       creating a phantom {@code quantityShortfall} without a ledger point (suppressed by
     *       phantom-carry detection).</li>
     * </ol>
     *
     * <p>This method finds those already-stamped FUND drains (corrId prefix
     * {@code bybit-earn-onchain-fund-v1:}), re-links them to the existing EARN_FLEXIBLE_SAVING
     * using a loose (1%) quantity tolerance, excludes the synthetic, and excludes the UTA
     * counterpart that was previously paired with the EARN_FLEXIBLE_SAVING.
     *
     * <p>Idempotent: FUND drains that were already re-linked (corrId changed to
     * {@code bybit-earn-principal-v1:}) are no longer found by the corridor-drain query.
     */
    public int repairCorridorEarnDuplicates() {
        List<NormalizedTransaction> corridorFundDrains = loadCorridorFundedFundDrains();
        if (corridorFundDrains.isEmpty()) {
            return 0;
        }
        List<NormalizedTransaction> allEarnLegs = loadEarnCreditLegs();
        Set<String> consumedEarnIds = new HashSet<>();
        Instant now = Instant.now();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        int repaired = 0;

        for (NormalizedTransaction fund : corridorFundDrains) {
            NormalizedTransaction.Flow principal = principalFlow(fund);
            if (principal == null || principal.getQuantityDelta() == null
                    || principal.getQuantityDelta().signum() >= 0) {
                continue;
            }
            String uid = extractBybitUid(fund.getWalletAddress());
            if (uid == null) {
                continue;
            }
            BigDecimal absQty = principal.getQuantityDelta().abs();
            String assetFamily = assetFamily(principal);

            // Use corridor (1%) tolerance: Bybit truncates EARN amounts to fewer decimal places.
            NormalizedTransaction earnCredit = findEarnCounterpart(
                    allEarnLegs, uid, assetFamily, absQty, fund.getBlockTimestamp(), consumedEarnIds, true);
            if (earnCredit == null || !isLinkableEarnCredit(earnCredit)) {
                continue;
            }
            // Only re-link if the EARN credit is already associated with a UTA earn-principal pair:
            // that UTA drain is the duplicate that creates the phantom umbrella shortfall.
            String existingEarnCorrId = earnCredit.getCorrelationId();
            if (existingEarnCorrId == null
                    || !existingEarnCorrId.startsWith(CorrelationContract.BYBIT_EARN_PRINCIPAL_V1_PREFIX)) {
                continue;
            }

            // Find UTA counterpart to exclude (paired with EARN credit via earn-principal corrId).
            NormalizedTransaction utaCounterpart = findEarnPrincipalUtaCounterpart(
                    existingEarnCorrId, earnCredit.getId());

            // Find the synthetic to exclude (was created for this FUND drain on the prior run).
            NormalizedTransaction synthetic = loadSyntheticForFund(fund.getId());

            // Re-link FUND drain directly to the existing EARN credit.
            linkEarnPrincipalPair(fund, earnCredit, assetFamily, absQty, now);
            consumedEarnIds.add(earnCredit.getId());
            dirty.add(fund);
            dirty.add(earnCredit);
            repaired++;

            if (utaCounterpart != null) {
                utaCounterpart.setExcludedFromAccounting(true);
                utaCounterpart.setUpdatedAt(now);
                dirty.add(utaCounterpart);
            }
            if (synthetic != null) {
                synthetic.setExcludedFromAccounting(true);
                synthetic.setUpdatedAt(now);
                dirty.add(synthetic);
            }
        }

        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("BYBIT_EARN_CORRIDOR_DUPLICATE_REPAIR corridorDrains={} repaired={}", corridorFundDrains.size(), repaired);
        }
        return repaired;
    }

    /**
     * Real EARN-side credit recorded by Bybit (not a synthetic INTERNAL_TRANSFER from a prior run).
     * These are the canonical earn events whose FUND debit we pair to instead of duplicating.
     */
    private static boolean isLinkableEarnCredit(NormalizedTransaction earn) {
        NormalizedTransactionType type = earn.getType();
        return type == NormalizedTransactionType.EARN_FLEXIBLE_SAVING
                || type == NormalizedTransactionType.LENDING_DEPOSIT;
    }

    /**
     * Pairs the orphan FUND debit with the existing real EARN credit under a deterministic
     * {@code bybit-earn-principal-v1:} corrId so replay carries FUND principal basis into the
     * single {@code :EARN} credit (no duplicate, closed cycle nets to zero on {@code :EARN}).
     */
    private static void linkEarnPrincipalPair(
            NormalizedTransaction fund,
            NormalizedTransaction earn,
            String assetFamily,
            BigDecimal absQty,
            Instant now
    ) {
        String corrId = earnPrincipalCorrId(fund.getId(), assetFamily, absQty);
        fund.setCorrelationId(corrId);
        fund.setContinuityCandidate(true);
        fund.setMatchedCounterparty(earn.getWalletAddress());
        fund.setUpdatedAt(now);

        earn.setCorrelationId(corrId);
        earn.setContinuityCandidate(true);
        earn.setMatchedCounterparty(fund.getWalletAddress());
        earn.setUpdatedAt(now);
    }

    private static String earnPrincipalCorrId(String fundId, String assetFamily, BigDecimal absQty) {
        String qtyPlain = absQty.setScale(QTY_SCALE, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
        String payload = (fundId == null ? "" : fundId) + "|" + assetFamily + "|" + qtyPlain;
        return BybitEarnPrincipalTransferPairer.EARN_PRINCIPAL_CORRELATION_PREFIX + sha256Hex(payload);
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} when there is a BYBIT-CORRIDOR deposit into {@code BYBIT:uid:FUND}
     * for the same asset family and approximately the same quantity within ±6 hours. A 1%
     * quantity tolerance accommodates small corridor fees that may reduce the deposited amount.
     */
    boolean hasRecentCorridorDeposit(String uid, String assetFamily, BigDecimal absQty, Instant timestamp) {
        if (uid == null || assetFamily == null || absQty == null || timestamp == null) {
            return false;
        }
        String fundWallet = CorrelationContract.VENUE_BYBIT + ":" + uid + CorrelationContract.WALLET_SUFFIX_FUND;
        Instant windowStart = timestamp.minus(EARN_COUNTERPART_WINDOW);
        Instant windowEnd = timestamp.plus(EARN_COUNTERPART_WINDOW);

        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("type").is(NormalizedTransactionType.INTERNAL_TRANSFER),
                Criteria.where("walletAddress").is(fundWallet),
                Criteria.where("correlationId").regex("^BYBIT-CORRIDOR:"),
                Criteria.where("blockTimestamp").gte(Date.from(windowStart)).lte(Date.from(windowEnd))
        ));

        List<NormalizedTransaction> candidates = mongoOperations.find(query, NormalizedTransaction.class);
        BigDecimal tolerance = absQty.multiply(CORRIDOR_QTY_TOLERANCE_PCT, MC);

        for (NormalizedTransaction candidate : candidates) {
            NormalizedTransaction.Flow flow = principalFlow(candidate);
            if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() <= 0) {
                continue;
            }
            if (!assetFamily.equals(assetFamily(flow))) {
                continue;
            }
            BigDecimal candidateQty = flow.getQuantityDelta();
            BigDecimal diff = candidateQty.subtract(absQty, MC).abs();
            if (diff.compareTo(tolerance) <= 0) {
                return true;
            }
        }
        return false;
    }

    private List<NormalizedTransaction> loadFundOrphans() {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("type").is(NormalizedTransactionType.INTERNAL_TRANSFER),
                Criteria.where("excludedFromAccounting").ne(true),
                new Criteria().orOperator(
                        Criteria.where("correlationId").exists(false),
                        Criteria.where("correlationId").is("")
                )
        ));
        return mongoOperations.find(query, NormalizedTransaction.class).stream()
                .filter(tx -> isFundAccount(tx.getWalletAddress()))
                .toList();
    }

    /**
     * EARN-side credit legs that may already record a subscription's EARN side. Beyond the
     * synthetic {@code INTERNAL_TRANSFER} legs, Bybit emits the real EARN credit as
     * {@code EARN_FLEXIBLE_SAVING} or {@code LENDING_DEPOSIT}; recognising those prevents the
     * service from synthesising a duplicate EARN credit on top of the real one.
     */
    private List<NormalizedTransaction> loadEarnCreditLegs() {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("type").in(
                        NormalizedTransactionType.INTERNAL_TRANSFER,
                        NormalizedTransactionType.EARN_FLEXIBLE_SAVING,
                        NormalizedTransactionType.LENDING_DEPOSIT
                ),
                Criteria.where("excludedFromAccounting").ne(true)
        ));
        return mongoOperations.find(query, NormalizedTransaction.class).stream()
                .filter(tx -> isEarnAccount(tx.getWalletAddress()))
                .toList();
    }

    private boolean syntheticExists(String syntheticId) {
        return mongoOperations.exists(
                Query.query(Criteria.where("_id").is(syntheticId)),
                NormalizedTransaction.class
        );
    }

    // -------------------------------------------------------------------------
    // Counterpart matching
    // -------------------------------------------------------------------------

    /**
     * Returns the earliest unconsumed EARN-side credit (same uid, family, |qty| ±ε, within ±6h)
     * for this FUND debit, or {@code null} if none exists. Deterministic: candidates are scanned in
     * block-timestamp order so the same pairing is chosen on every run regardless of import order.
     *
     * <p>When {@code corridorFunded=true} a percentage tolerance ({@link #CORRIDOR_QTY_TOLERANCE_PCT})
     * is applied instead of the absolute {@link #QTY_TOLERANCE}: Bybit truncates EARN-side amounts
     * to fewer decimal places than the corridor-deposited FUND debit, causing differences of ~1e-7
     * that exceed the absolute gate but are well within 1%.
     */
    private NormalizedTransaction findEarnCounterpart(
            List<NormalizedTransaction> earnLegs,
            String uid,
            String assetFamily,
            BigDecimal absQty,
            Instant fundTimestamp,
            Set<String> consumedEarnIds,
            boolean corridorFunded
    ) {
        BigDecimal effectiveTolerance = corridorFunded
                ? absQty.multiply(CORRIDOR_QTY_TOLERANCE_PCT, MC)
                : QTY_TOLERANCE;
        NormalizedTransaction best = null;
        Instant bestTs = null;
        for (NormalizedTransaction earn : earnLegs) {
            if (consumedEarnIds.contains(earn.getId())) {
                continue;
            }
            if (!uid.equals(extractBybitUid(earn.getWalletAddress()))) {
                continue;
            }
            NormalizedTransaction.Flow flow = principalFlow(earn);
            if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() <= 0) {
                continue;
            }
            if (!assetFamily.equals(assetFamily(flow))) {
                continue;
            }
            if (flow.getQuantityDelta().subtract(absQty).abs().compareTo(effectiveTolerance) > 0) {
                continue;
            }
            if (fundTimestamp != null && earn.getBlockTimestamp() != null) {
                Duration drift = Duration.between(fundTimestamp, earn.getBlockTimestamp()).abs();
                if (drift.compareTo(EARN_COUNTERPART_WINDOW) > 0) {
                    continue;
                }
            }
            Instant ts = earn.getBlockTimestamp();
            if (best == null
                    || (bestTs == null && ts != null)
                    || (bestTs != null && ts != null && ts.isBefore(bestTs))) {
                best = earn;
                bestTs = ts;
            }
        }
        return best;
    }

    /**
     * Finds the UTA (non-EARN) INTERNAL_TRANSFER counterpart that was previously paired with
     * {@code earnCreditId} under {@code existingCorrId}. Returns {@code null} if not found.
     *
     * <p>Used to exclude the UTA transfer from accounting when the corridor-funded FUND drain is
     * re-linked directly to the EARN_FLEXIBLE_SAVING, making the UTA drain redundant.
     */
    private NormalizedTransaction findEarnPrincipalUtaCounterpart(
            String existingCorrId, String earnCreditId
    ) {
        if (existingCorrId == null || earnCreditId == null) {
            return null;
        }
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("type").is(NormalizedTransactionType.INTERNAL_TRANSFER),
                Criteria.where("correlationId").is(existingCorrId),
                Criteria.where("_id").ne(earnCreditId),
                Criteria.where("excludedFromAccounting").ne(true)
        ));
        List<NormalizedTransaction> candidates = mongoOperations.find(query, NormalizedTransaction.class);
        // The UTA counterpart lives on a non-EARN wallet address.
        return candidates.stream()
                .filter(tx -> !isEarnAccount(tx.getWalletAddress()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Loads the synthetic EARN INTERNAL_TRANSFER that was created for a given FUND drain ID on a
     * prior repair run. Returns {@code null} if no synthetic was persisted for this FUND drain.
     */
    private NormalizedTransaction loadSyntheticForFund(String fundId) {
        String syntheticId = syntheticId(fundId);
        Query query = Query.query(Criteria.where("_id").is(syntheticId));
        return mongoOperations.findOne(query, NormalizedTransaction.class);
    }

    /**
     * Returns FUND-side drains that were stamped with a corridor-funded earn-onchain corrId on a
     * prior repair run. These are candidates for retroactive re-linking to existing EARN credits.
     */
    private List<NormalizedTransaction> loadCorridorFundedFundDrains() {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("type").is(NormalizedTransactionType.INTERNAL_TRANSFER),
                Criteria.where("excludedFromAccounting").ne(true),
                Criteria.where("correlationId").regex(
                        "^" + Pattern.quote(EARN_ONCHAIN_FUND_CORR_PREFIX))
        ));
        return mongoOperations.find(query, NormalizedTransaction.class).stream()
                .filter(tx -> isFundAccount(tx.getWalletAddress()))
                .toList();
    }

    /**
     * Returns {@code true} when an EARN-side credit of the SAME accounting family but a DIFFERENT
     * asset symbol exists within ±6h of this FUND debit — i.e. this is a cross-asset ETH-family
     * earn conversion (METH/ETH → cmETH) rather than a same-asset earn subscription. Quantity is
     * intentionally NOT gated: cross-asset conversions carry a redemption ratio (e.g. 0.6929746 ETH
     * → 0.65107655 cmETH), so the two legs do not share a magnitude.
     */
    private boolean hasCrossAssetFamilyEarnCredit(
            List<NormalizedTransaction> earnLegs,
            String uid,
            String assetFamily,
            String assetSymbol,
            Instant fundTimestamp,
            Set<String> consumedEarnIds
    ) {
        String sourceSymbol = assetSymbol == null ? "" : assetSymbol.trim().toUpperCase(Locale.ROOT);
        for (NormalizedTransaction earn : earnLegs) {
            if (consumedEarnIds.contains(earn.getId())) {
                continue;
            }
            if (!uid.equals(extractBybitUid(earn.getWalletAddress()))) {
                continue;
            }
            NormalizedTransaction.Flow flow = principalFlow(earn);
            if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() <= 0) {
                continue;
            }
            if (!AccountingAssetClassificationSupport.sharesLiquidStakingNormalizationCluster(
                    assetSymbol, flow.getAssetSymbol())) {
                continue;
            }
            String candidateSymbol = flow.getAssetSymbol() == null
                    ? "" : flow.getAssetSymbol().trim().toUpperCase(Locale.ROOT);
            if (candidateSymbol.isEmpty() || candidateSymbol.equals(sourceSymbol)) {
                continue;
            }
            if (fundTimestamp != null && earn.getBlockTimestamp() != null) {
                Duration drift = Duration.between(fundTimestamp, earn.getBlockTimestamp()).abs();
                if (drift.compareTo(EARN_COUNTERPART_WINDOW) > 0) {
                    continue;
                }
            }
            return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Synthetic transaction construction
    // -------------------------------------------------------------------------

    private static NormalizedTransaction buildSyntheticEarnTransaction(
            String id,
            String uid,
            String assetSymbol,
            String assetContract,
            BigDecimal absQty,
            Instant timestamp,
            String corrId,
            String fundWallet,
            Instant now
    ) {
        String earnWallet = earnWallet(uid);

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol(assetSymbol);
        flow.setAssetContract(assetContract);
        flow.setQuantityDelta(absQty);
        flow.setAccountRef(earnWallet);

        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId(id);
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
        tx.setStatus(NormalizedTransactionStatus.CONFIRMED);
        tx.setWalletAddress(earnWallet);
        tx.setBlockTimestamp(timestamp);
        tx.setCorrelationId(corrId);
        tx.setMatchedCounterparty(fundWallet);
        tx.setContinuityCandidate(true);
        tx.setFlows(new ArrayList<>(List.of(flow)));
        tx.setCreatedAt(now);
        tx.setUpdatedAt(now);
        return tx;
    }

    // -------------------------------------------------------------------------
    // ID / corrId derivation
    // -------------------------------------------------------------------------

    private static String syntheticId(String fundId) {
        return SYNTHETIC_ID_PREFIX + sha256Hex(fundId == null ? "" : fundId);
    }

    private static String corrId(String prefix, String fundId, String assetFamily, BigDecimal absQty) {
        String qtyPlain = absQty.setScale(QTY_SCALE, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
        String payload = (fundId == null ? "" : fundId) + "|" + assetFamily + "|" + qtyPlain;
        return prefix + sha256Hex(payload);
    }

    // -------------------------------------------------------------------------
    // Wallet helpers
    // -------------------------------------------------------------------------

    private static boolean isFundAccount(String walletAddress) {
        if (walletAddress == null) return false;
        WalletRef ref = WalletRef.parse(walletAddress);
        return ref.domain() == WalletDomainKind.CEX && "FUND".equalsIgnoreCase(ref.subAccount());
    }

    private static boolean isEarnAccount(String walletAddress) {
        if (walletAddress == null) return false;
        WalletRef ref = WalletRef.parse(walletAddress);
        return ref.domain() == WalletDomainKind.CEX && "EARN".equalsIgnoreCase(ref.subAccount());
    }

    private static String earnWallet(String uid) {
        return CorrelationContract.VENUE_BYBIT + ":" + uid + CorrelationContract.WALLET_SUFFIX_EARN;
    }

    private static String extractBybitUid(String walletAddress) {
        if (walletAddress == null) {
            return null;
        }
        WalletRef ref = WalletRef.parse(walletAddress);
        if (ref.domain() != WalletDomainKind.CEX || ref.uid().isBlank()) {
            return null;
        }
        return ref.uid();
    }

    // -------------------------------------------------------------------------
    // Flow helpers
    // -------------------------------------------------------------------------

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

    private static String assetFamily(NormalizedTransaction.Flow flow) {
        if (flow == null) {
            return "?";
        }
        String family = AccountingAssetFamilySupport.continuityIdentity(flow);
        if (family != null) {
            return family;
        }
        return flow.getAssetSymbol() == null ? "?" : flow.getAssetSymbol().trim().toUpperCase(Locale.ROOT);
    }

    // -------------------------------------------------------------------------
    // Hashing
    // -------------------------------------------------------------------------

    private static String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
