package com.walletradar.application.costbasis.support;

import com.walletradar.application.costbasis.domain.AssetLedgerPoint;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * Shared read-time credit for receipt-less locked lending/staking/vault collateral.
 *
 * <p>On networks without a fungible receipt token (e.g. Jupiter Lend on Solana) a
 * {@code LENDING_DEPOSIT} carries the underlying's basis OUT of the spot bucket via a
 * {@code REALLOCATE_OUT}, but there is no in-family receipt-token {@code on_chain_balance} to
 * re-cover it — while a live-balance provider merges the still-locked amount back into the native
 * balance, so it reads as held-but-uncovered and its basis "vanishes" from the family. This support
 * reconstructs the parked basis as the net same-family {@code REALLOCATE_OUT} minus any restored
 * {@code REALLOCATE_IN}, so both the move-basis reconciliation and the dashboard family rollup can
 * credit it back symmetrically with how an EVM aToken retains basis inside its family.
 *
 * <p>Receipt-bearing lending nets to ~0 within the family (the basis lands on the aToken bucket,
 * which reconciles via its own {@code on_chain_balance}), so summing the same-family REALLOCATE
 * deltas credits ONLY receipt-less collateral. Network-agnostic: driven by lifecycle + basis effect,
 * with no chain gate and no hardcoded protocol/wallet/address.
 */
public final class ReceiptlessLockedCollateralSupport {

    private static final MathContext MC = MathContext.DECIMAL128;

    private ReceiptlessLockedCollateralSupport() {
    }

    /**
     * Outstanding basis parked by a receipt-less locked position. All amounts are non-negative;
     * {@link #isPresent()} is true only when there is still locked quantity to credit.
     */
    public record LockedCollateralBasis(
            BigDecimal quantity,
            BigDecimal grossBasisUsd,
            BigDecimal netBasisUsd
    ) {
        public static final LockedCollateralBasis EMPTY =
                new LockedCollateralBasis(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        public boolean isPresent() {
            return quantity != null && quantity.signum() > 0;
        }
    }

    /**
     * True when the point is a same-family lifecycle REALLOCATE that parks (or restores) receipt-less
     * collateral basis: lifecycle in {@code LENDING/STAKING/VAULT} and a {@code REALLOCATE_OUT} or
     * {@code REALLOCATE_IN} basis effect.
     */
    public static boolean isReceiptlessLockedPoint(AssetLedgerPoint point) {
        if (point == null) {
            return false;
        }
        AssetLedgerPoint.LifecycleKind lifecycleKind = point.getLifecycleKind();
        if (lifecycleKind != AssetLedgerPoint.LifecycleKind.LENDING
                && lifecycleKind != AssetLedgerPoint.LifecycleKind.STAKING
                && lifecycleKind != AssetLedgerPoint.LifecycleKind.VAULT) {
            return false;
        }
        AssetLedgerPoint.BasisEffect basisEffect = point.getBasisEffect();
        return basisEffect == AssetLedgerPoint.BasisEffect.REALLOCATE_OUT
                || basisEffect == AssetLedgerPoint.BasisEffect.REALLOCATE_IN;
    }

    /**
     * Accumulates the parked basis from an already-filtered set of same-family receipt-less locked
     * points ({@link #isReceiptlessLockedPoint} true, restricted to a single family by the caller).
     * Computed as the negated net of REALLOCATE_OUT minus REALLOCATE_IN; a receipt-bearing position
     * nets to ~0 because the basis re-enters the family on the receipt-token bucket.
     */
    public static LockedCollateralBasis fromFamilyPoints(Iterable<AssetLedgerPoint> familyReceiptlessPoints) {
        BigDecimal netQty = BigDecimal.ZERO;
        BigDecimal netBasis = BigDecimal.ZERO;
        BigDecimal netNetBasis = BigDecimal.ZERO;
        for (AssetLedgerPoint point : familyReceiptlessPoints) {
            if (!isReceiptlessLockedPoint(point)) {
                continue;
            }
            netQty = netQty.add(zeroIfNull(point.getQuantityDelta()), MC);
            netBasis = netBasis.add(zeroIfNull(point.getCostBasisDeltaUsd()), MC);
            BigDecimal netBasisDelta = point.getNetCostBasisDeltaUsd() != null
                    ? point.getNetCostBasisDeltaUsd()
                    : zeroIfNull(point.getCostBasisDeltaUsd());
            netNetBasis = netNetBasis.add(netBasisDelta, MC);
        }
        return new LockedCollateralBasis(
                netQty.negate().max(BigDecimal.ZERO),
                netBasis.negate().max(BigDecimal.ZERO),
                netNetBasis.negate().max(BigDecimal.ZERO)
        );
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
