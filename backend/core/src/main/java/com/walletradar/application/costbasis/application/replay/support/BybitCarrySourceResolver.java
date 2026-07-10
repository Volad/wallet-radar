package com.walletradar.application.costbasis.application.replay.support;

import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.application.costbasis.application.replay.state.PositionStore;
import com.walletradar.canonical.correlation.CorrelationContract;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Locale;

/**
 * Resolves how an outbound Bybit transfer should be drained across the suffix sub-account
 * ({@code :FUND}/{@code :UTA}/{@code :EARN}) and its UID umbrella sibling, expressed as an ordered
 * {@link BybitDrainPlan} whose slice quantities sum to the outbound quantity.
 *
 * <p><b>Why.</b> Off-venue corridor withdrawals are booked on the {@code :FUND} sub-account, but the
 * principal they spend frequently sits on the UID umbrella (deposits, swaps and collapsed UTA↔FUND
 * legs all net there). The previous single-position carry-source heuristic drained only the suffix
 * slice and clamped against it, leaving the umbrella lot inflated and never recorded as a drain (the
 * ETH/USDT umbrella phantom). The plan makes the umbrella carry-symmetric: when the suffix slice
 * under-covers the outbound, the remainder waterfalls onto the umbrella so net per-uid ledger
 * quantity converges to raw net.
 *
 * <p><b>Determinism.</b> The candidate ordering is fixed — suffix key first, umbrella sibling
 * second — and never derived from map/insertion order. The split decision is driven purely by
 * coverage ({@link ReplayToleranceSupport#carrySourceCoverageRatio()}), not by the wallet suffix or asset symbol, so no
 * asset is special-cased.
 *
 * <p><b>Scope.</b> The umbrella remainder slice is only produced when a <em>distinct</em> umbrella
 * sibling already exists with positive inventory. When the umbrella is empty/absent the plan
 * degenerates to a single slice on the primary source (the pre-existing clamp-with-shortfall
 * behaviour), so every previously single-slice decision — flow-covers, the {@code :EARN}
 * earn-principal redirect, corridor sub-pattern A, the R-1 venue-internal drain, and plain collapsed
 * FUND↔UTA legs that already strip to the umbrella (XRP RC-2) — is preserved byte-for-byte.
 */
public final class BybitCarrySourceResolver {

    private static final MathContext MC = MathContext.DECIMAL128;

    /**
     * Coverage fraction a candidate position must hold to be treated as able to supply the outbound
     * from real inventory (mirrors {@link ReplayToleranceSupport#carrySourceCoverageRatio()}); the
     * tolerance absorbs sub-unit fee rounding while rejecting dust residues.
     */
    private static final List<String> SUB_ACCOUNT_SUFFIXES = List.of(
            CorrelationContract.WALLET_SUFFIX_FUND,
            CorrelationContract.WALLET_SUFFIX_UTA,
            CorrelationContract.WALLET_SUFFIX_EARN
    );

    private BybitCarrySourceResolver() {
    }

    /**
     * A single drained position and the quantity to remove from it. Slice quantities across a plan
     * sum to the outbound quantity (modulo a genuine shortfall on the last slice).
     */
    public record DrainSlice(PositionState position, BigDecimal quantity) {
    }

    /** Ordered list of drain slices (suffix-first, umbrella-second). */
    public record BybitDrainPlan(List<DrainSlice> slices) {
        public boolean isMultiSlice() {
            return slices.size() > 1;
        }
    }

    /**
     * Builds the drain plan for an outbound transfer.
     *
     * @param flowPosition       the dispatcher flow position (for the single-slice degenerate cases)
     * @param primaryCarrySource the position chosen by the existing carry-source resolution (the
     *                           {@code :EARN}/corridor/R-1 contracts are encoded there); used as the
     *                           first candidate
     * @param positions          replay position store, peeked (never mutated) to find the umbrella
     *                           sibling
     * @param outboundQuantity   absolute outbound quantity to drain
     */
    public static BybitDrainPlan plan(
            PositionState flowPosition,
            PositionState primaryCarrySource,
            PositionStore positions,
            BigDecimal outboundQuantity
    ) {
        PositionState primary = primaryCarrySource != null ? primaryCarrySource : flowPosition;
        if (primary == null || outboundQuantity == null || outboundQuantity.signum() <= 0) {
            return single(primary, outboundQuantity);
        }
        // Flow (suffix) candidate covers the outbound → single slice, behaviour-preserving.
        if (positionCoversQuantity(primary, outboundQuantity)) {
            return single(primary, outboundQuantity);
        }
        // Under-covers: waterfall the remainder onto the umbrella sibling when one exists with
        // real inventory. Otherwise keep the single (clamp + shortfall) slice on the primary.
        PositionState umbrella = umbrellaSibling(primary, positions);
        if (umbrella == null
                || umbrella == primary
                || umbrella.assetKey().equals(primary.assetKey())
                || !hasInventory(umbrella)) {
            return single(primary, outboundQuantity);
        }
        BigDecimal held = nonNegative(primary.quantity());
        BigDecimal flowSlice = held.min(outboundQuantity);
        BigDecimal remainder = nonNegative(outboundQuantity.subtract(flowSlice, MC));
        if (remainder.signum() <= 0) {
            return single(primary, outboundQuantity);
        }
        if (flowSlice.signum() <= 0) {
            // Primary holds no inventory (dust residue): drain the umbrella for the full outbound.
            return single(umbrella, outboundQuantity);
        }
        return new BybitDrainPlan(List.of(
                new DrainSlice(primary, flowSlice),
                new DrainSlice(umbrella, remainder)
        ));
    }

    private static BybitDrainPlan single(PositionState position, BigDecimal quantity) {
        return new BybitDrainPlan(List.of(new DrainSlice(position, quantity)));
    }

    /**
     * Resolves the UID umbrella sibling for a suffixed sub-account position by stripping the
     * {@code :FUND}/{@code :UTA}/{@code :EARN} suffix. Returns {@code null} when the position is
     * already the umbrella (no suffix) or the sibling has not been created in the store, so the plan
     * never materialises a phantom umbrella position.
     */
    private static PositionState umbrellaSibling(PositionState primary, PositionStore positions) {
        if (primary == null || positions == null) {
            return null;
        }
        AssetKey key = primary.assetKey();
        String wallet = key == null ? null : key.walletAddress();
        if (wallet == null) {
            return null;
        }
        String upper = wallet.toUpperCase(Locale.ROOT);
        String stripped = null;
        for (String suffix : SUB_ACCOUNT_SUFFIXES) {
            if (upper.endsWith(suffix)) {
                stripped = wallet.substring(0, wallet.length() - suffix.length());
                break;
            }
        }
        if (stripped == null || stripped.equals(wallet)) {
            return null;
        }
        AssetKey umbrellaKey = new AssetKey(
                stripped,
                key.networkId(),
                key.assetContract(),
                key.assetSymbol(),
                key.assetIdentity()
        );
        return positions.asMap().get(umbrellaKey);
    }

    private static boolean positionCoversQuantity(PositionState position, BigDecimal outboundQuantity) {
        if (position == null) {
            return false;
        }
        BigDecimal qty = position.quantity();
        if (qty == null || qty.signum() <= 0) {
            return false;
        }
        if (outboundQuantity == null || outboundQuantity.signum() <= 0) {
            return true;
        }
        return qty.compareTo(outboundQuantity.multiply(ReplayToleranceSupport.carrySourceCoverageRatio(), MC)) >= 0;
    }

    private static boolean hasInventory(PositionState position) {
        return position != null && position.quantity() != null && position.quantity().signum() > 0;
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value.signum() < 0 ? BigDecimal.ZERO : value;
    }
}
