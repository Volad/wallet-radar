package com.walletradar.application.cex.normalization.venue.bybit;

import com.walletradar.canonical.correlation.CorrelationContract;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.walletradar.application.cex.normalization.venue.bybit.BybitStreamAuthorityCollapserSupport.idTiebreak;

/**
 * ADR-056: pairs Bybit On-chain Earn subscription and redemption legs that both land on the same
 * {@code :FUND} sub-account with the same asset (e.g. TON). This is the same-asset FUND
 * self-round-trip pattern for non-ETH-family On-chain Earn products where Bybit does not emit an
 * {@code EARN_FLEXIBLE_SAVING} counterpart.
 *
 * <p>Pairing criteria:
 * <ul>
 *   <li>Subscribe-out: {@code INTERNAL_TRANSFER} with
 *       {@code correlationId = "bybit-earn-self-rt-v1:subscribe-pending"} (set by
 *       {@code BybitNormalizationService.normalizeLiquidStakingRow} for non-clustered assets).</li>
 *   <li>Redeem-in: any uncorrelated {@code INTERNAL_TRANSFER} on the same {@code :FUND} wallet,
 *       same {@code assetSymbol}, equal {@code |quantityDelta|} within {@link #QTY_TOLERANCE},
 *       block-timestamp strictly after the subscribe-out and within {@link #MAX_HOLD_WINDOW}.</li>
 *   <li>Matching is FIFO: the earliest redeem-in eligible for a subscribe-out is selected.</li>
 * </ul>
 *
 * <p>On match: both rows receive a shared deterministic {@code bybit-earn-self-rt-v1:<hash>}
 * {@code correlationId} and {@code continuityCandidate=true}. The replay engine then routes the
 * subscribe-out as CARRY_OUT and the redeem-in as CARRY_IN on the same UID-umbrella position,
 * preserving cost basis with no P&amp;L event.
 *
 * <p>Guards:
 * <ul>
 *   <li>ETH/AVAX/SOL-family assets (CMETH, METH, BBSOL, SAVAX) are naturally excluded: their
 *       On-chain Earn subscribe-out lands on a different asset on {@code :EARN}, so
 *       {@code isLiquidStakingRow} never produces a subscribe-pending marker for them.</li>
 *   <li>ETH-family NEEDS_REVIEW rows (handled by {@link
 *       com.walletradar.application.linking.pipeline.clarification.BybitOnChainEarnOrphanRepairService})
 *       are NEEDS_REVIEW status and have no {@code subscribe-pending} correlationId — not loaded.</li>
 *   <li>Existing {@code bybit-earn-principal-v1:} and {@code bybit-earn-onchain-fund-v1:} corrIds
 *       are unaffected — this pairer uses a distinct {@code bybit-earn-self-rt-v1:} prefix.</li>
 *   <li>{@link com.walletradar.application.linking.pipeline.clarification.BybitOnChainEarnOrphanRepairService}
 *       only processes rows with blank/null correlationId on {@code :FUND} — the subscribe-pending
 *       marker is non-blank, so the orphan repair skips those rows cleanly.</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BybitOnChainEarnFundPairer {

    /**
     * Pending marker assigned to non-clustered On-chain Earn subscription rows during
     * normalization. Queried here to identify subscribe-out legs for pairing.
     */
    public static final String SUBSCRIBE_PENDING_MARKER =
            CorrelationContract.BYBIT_EARN_SELF_RT_V1_PREFIX + "subscribe-pending";

    /**
     * ADR-056 §Scope limitations: Bybit On-chain Earn does not support partial redemption; the
     * subscribe qty always equals the redeem qty in observed data. The hold window is 400 days —
     * well above the published maximum lock period for any Bybit On-chain Earn product.
     */
    private static final Duration MAX_HOLD_WINDOW = Duration.ofDays(400);
    private static final BigDecimal QTY_TOLERANCE = new BigDecimal("0.00000001");
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final int QTY_SCALE = 8;

    private final MongoOperations mongoOperations;
    private final NormalizedTransactionRepository normalizedTransactionRepository;

    /**
     * Finds subscribe-pending INTERNAL_TRANSFERs and pairs each with the earliest compatible
     * redeem-in leg on the same {@code :FUND} wallet. Returns the number of pairs completed.
     * Idempotent: subscribe-out rows that already carry a non-pending correlationId are skipped on
     * re-run because the query filters on the pending marker specifically.
     */
    public int pairOnChainEarnFundRoundTrips() {
        // --- Load subscribe-out candidates (set by normalizeLiquidStakingRow for non-clustered) ---
        Query subQuery = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("type").is(NormalizedTransactionType.INTERNAL_TRANSFER),
                Criteria.where("correlationId").is(SUBSCRIBE_PENDING_MARKER),
                Criteria.where("excludedFromAccounting").ne(true)
        ));
        List<NormalizedTransaction> subscriptions = new ArrayList<>(mongoOperations.find(subQuery, NormalizedTransaction.class));
        log.info("BYBIT_ONCHAIN_EARN_FUND_PAIRER_DEBUG subscriptions_found={}", subscriptions.size());
        if (subscriptions.isEmpty()) {
            return 0;
        }

        // Collect the unique wallet addresses so we only load redemption candidates for those wallets.
        Set<String> subscriptionWallets = new HashSet<>();
        for (NormalizedTransaction sub : subscriptions) {
            if (sub.getWalletAddress() != null) {
                subscriptionWallets.add(sub.getWalletAddress());
            }
        }

        // --- Load redeem-in candidates on the same wallets (uncorrelated INTERNAL_TRANSFERs) ---
        // Redemptions arrive via the default buildMappedRow path (no correlationId, positive qty).
        Query redeemQuery = Query.query(new Criteria().andOperator(
                Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
                Criteria.where("type").is(NormalizedTransactionType.INTERNAL_TRANSFER),
                Criteria.where("walletAddress").in(subscriptionWallets),
                Criteria.where("excludedFromAccounting").ne(true),
                new Criteria().orOperator(
                        Criteria.where("correlationId").exists(false),
                        Criteria.where("correlationId").is(null),
                        Criteria.where("correlationId").is("")
                )
        ));
        List<NormalizedTransaction> redemptionCandidates = mongoOperations.find(redeemQuery, NormalizedTransaction.class);
        // Keep only positive-qty rows — redemptions credit back the subscribed principal.
        List<NormalizedTransaction> redemptions = new ArrayList<>();
        for (NormalizedTransaction tx : redemptionCandidates) {
            NormalizedTransaction.Flow f = principalFlow(tx);
            if (f != null && f.getQuantityDelta() != null && f.getQuantityDelta().signum() > 0) {
                redemptions.add(tx);
            }
        }
        log.info("BYBIT_ONCHAIN_EARN_FUND_PAIRER_DEBUG wallets={} candidates={} redemptions={}",
                subscriptionWallets, redemptionCandidates.size(), redemptions.size());

        if (redemptions.isEmpty()) {
            return 0;
        }

        // Sort subscriptions FIFO (earliest subscribe first).
        subscriptions.sort(Comparator
                .comparing(NormalizedTransaction::getBlockTimestamp, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(idTiebreak()));

        Set<String> consumedRedemptionIds = new HashSet<>();
        List<NormalizedTransaction> dirty = new ArrayList<>();
        int paired = 0;
        Instant now = Instant.now();

        for (NormalizedTransaction subscribe : subscriptions) {
            NormalizedTransaction.Flow subFlow = principalFlow(subscribe);
            if (subFlow == null || subFlow.getQuantityDelta() == null
                    || subFlow.getQuantityDelta().signum() >= 0) {
                // Subscribe-out must be negative (outbound).
                continue;
            }
            String walletAddress = subscribe.getWalletAddress();
            String assetSymbol = assetSymbol(subFlow);
            if (assetSymbol == null) {
                continue;
            }
            BigDecimal absQty = subFlow.getQuantityDelta().abs();

            NormalizedTransaction redeem = findMatchingRedemption(
                    redemptions, consumedRedemptionIds,
                    walletAddress, assetSymbol, absQty, subscribe.getBlockTimestamp());
            if (redeem == null) {
                continue;
            }

            String corrId = selfRtCorrelationId(walletAddress, assetSymbol, absQty, subscribe.getBlockTimestamp());
            applyPair(subscribe, redeem, corrId, now);
            dirty.add(subscribe);
            dirty.add(redeem);
            consumedRedemptionIds.add(redeem.getId());
            paired++;
            log.debug("BYBIT_ONCHAIN_EARN_FUND_PAIR subscribeId={} redeemId={} asset={} qty={} corrId={}",
                    subscribe.getId(), redeem.getId(), assetSymbol, absQty, corrId);
        }

        if (!dirty.isEmpty()) {
            normalizedTransactionRepository.saveAll(dirty);
            log.info("BYBIT_ONCHAIN_EARN_FUND_PAIRER subscriptions={} pairs={}", subscriptions.size(), paired);
        }
        return paired;
    }

    // -------------------------------------------------------------------------
    // Matching
    // -------------------------------------------------------------------------

    /**
     * Returns the earliest unconsumed redeem-in leg that matches the given subscribe-out on
     * wallet, asset, quantity (within {@link #QTY_TOLERANCE}), and temporal order (redeem strictly
     * after subscribe within {@link #MAX_HOLD_WINDOW}). Returns {@code null} if none found.
     */
    private NormalizedTransaction findMatchingRedemption(
            List<NormalizedTransaction> redemptions,
            Set<String> consumed,
            String walletAddress,
            String assetSymbol,
            BigDecimal absQty,
            Instant subscribeTs
    ) {
        NormalizedTransaction best = null;
        Instant bestTs = null;

        for (NormalizedTransaction redeem : redemptions) {
            if (consumed.contains(redeem.getId())) {
                continue;
            }
            if (!walletAddress.equals(redeem.getWalletAddress())) {
                continue;
            }
            NormalizedTransaction.Flow flow = principalFlow(redeem);
            if (flow == null || flow.getQuantityDelta() == null || flow.getQuantityDelta().signum() <= 0) {
                continue;
            }
            if (!assetSymbol.equals(assetSymbol(flow))) {
                continue;
            }
            if (flow.getQuantityDelta().subtract(absQty, MC).abs().compareTo(QTY_TOLERANCE) > 0) {
                continue;
            }
            Instant redeemTs = redeem.getBlockTimestamp();
            if (redeemTs == null || subscribeTs == null) {
                continue;
            }
            if (!redeemTs.isAfter(subscribeTs)) {
                // Redeem must be strictly after subscribe (same-timestamp = same-event, not round-trip).
                continue;
            }
            Duration hold = Duration.between(subscribeTs, redeemTs);
            if (hold.compareTo(MAX_HOLD_WINDOW) > 0) {
                log.warn("BYBIT_ONCHAIN_EARN_FUND_HOLD_EXCEEDED wallet={} asset={} qty={} holdDays={}",
                        walletAddress, assetSymbol, absQty, hold.toDays());
                continue;
            }
            // FIFO: prefer earliest redeem.
            if (best == null || redeemTs.isBefore(bestTs)) {
                best = redeem;
                bestTs = redeemTs;
            }
        }
        return best;
    }

    // -------------------------------------------------------------------------
    // Pair application
    // -------------------------------------------------------------------------

    private static void applyPair(
            NormalizedTransaction subscribe,
            NormalizedTransaction redeem,
            String corrId,
            Instant now
    ) {
        subscribe.setCorrelationId(corrId);
        subscribe.setContinuityCandidate(true);
        subscribe.setMatchedCounterparty(redeem.getWalletAddress());
        subscribe.setUpdatedAt(now);

        redeem.setCorrelationId(corrId);
        redeem.setContinuityCandidate(true);
        redeem.setMatchedCounterparty(subscribe.getWalletAddress());
        redeem.setUpdatedAt(now);
    }

    // -------------------------------------------------------------------------
    // CorrelationId derivation
    // -------------------------------------------------------------------------

    /**
     * Deterministic {@code bybit-earn-self-rt-v1:<hash>} correlation ID keyed on
     * {@code walletAddress|assetSymbol|qty|subscribeEpochSecond}. Unique per subscribe-out event
     * so each pair gets its own ID.
     */
    static String selfRtCorrelationId(
            String walletAddress,
            String assetSymbol,
            BigDecimal absQty,
            Instant subscribeTs
    ) {
        String qtyPlain = absQty.setScale(QTY_SCALE, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
        long epoch = subscribeTs == null ? 0L : subscribeTs.getEpochSecond();
        String payload = (walletAddress == null ? "" : walletAddress)
                + "|" + (assetSymbol == null ? "" : assetSymbol)
                + "|" + qtyPlain
                + "|" + epoch;
        return CorrelationContract.BYBIT_EARN_SELF_RT_V1_PREFIX + sha256Hex(payload);
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

    private static String assetSymbol(NormalizedTransaction.Flow flow) {
        if (flow == null || flow.getAssetSymbol() == null) {
            return null;
        }
        return flow.getAssetSymbol().trim().toUpperCase(Locale.ROOT);
    }

    // -------------------------------------------------------------------------
    // Hashing
    // -------------------------------------------------------------------------

    private static String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of()
                    .formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
