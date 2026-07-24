package com.walletradar.application.costbasis.support;

import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;

import java.util.Locale;

/**
 * RC-6 (ADR-047 addendum): identity-driven recognition of a <em>non-realizing LP-receipt wrap</em> —
 * staking an LP receipt into an Equilibria/Penpie booster (a {@code STAKING_DEPOSIT}) and the
 * symmetric unstake/zap-out unwrap (a {@code STAKING_WITHDRAW}).
 *
 * <p>Staking a Pendle LP receipt (e.g. {@code PENDLE-LPT}) into an Equilibria/Penpie booster mints a
 * 1:1 wrapper receipt ({@code eqb<X>}/{@code pnp<X>}); it is a continuation of the same LP position,
 * not a disposal. Booking the receipt out-leg as a sale realizes a phantom P&amp;L (proven blast
 * radius: −$23.178 on {@code 0x8dd9}). The authoritative basis for the position lives in the
 * {@code pendle-lp:*} receipt basis pool (deposited at LP entry, restored at the zap-out
 * {@code LP_EXIT}); the stake must therefore leave that pool untouched and simply drain the synthetic
 * receipt holding with <b>no realized P&amp;L and no market pricing</b>.</p>
 *
 * <p>Detection is protocol-generic (no wallet/tx hardcoding):</p>
 * <ul>
 *   <li>transaction type is {@code STAKING_DEPOSIT} or {@code STAKING_WITHDRAW};</li>
 *   <li>the leg is an LP receipt — the durable {@code lpReceipt} flag, the {@code FAMILY:LP_RECEIPT}
 *       accounting identity, or the deterministic {@code -LPT}/{@code -LP} receipt symbol grammar; and</li>
 *   <li>the transaction has a resolved Equilibria/Penpie {@code PROTOCOL} counterparty
 *       <em>or</em> carries an LP correlation ({@code pendle-lp:*} / {@code lp-position:*}).</li>
 * </ul>
 *
 * <p>Negative cases (do NOT match): an LP receipt sold into a DEX router is classified {@code SWAP}
 * (never {@code STAKING_*}); the plain {@code PENDLE} reward/governance token is not an {@code -LPT}
 * receipt.</p>
 */
public final class LpReceiptStakeWrapSupport {

    private static final String EQUILIBRIA = "equilibria";
    private static final String PENPIE = "penpie";
    private static final String PENDLE_LP_CORR_PREFIX = "pendle-lp:";
    private static final String LP_POSITION_CORR_PREFIX = "lp-position:";

    private LpReceiptStakeWrapSupport() {
    }

    /**
     * Returns {@code true} when {@code flow} is a non-realizing LP-receipt stake/unstake wrap leg that
     * must be booked as a {@code CARRY} (never {@code DISPOSE}/{@code ACQUIRE}) and excluded from
     * market pricing.
     */
    public static boolean isNonRealizingLpReceiptStakeLeg(
            NormalizedTransaction transaction,
            NormalizedTransaction.Flow flow
    ) {
        if (transaction == null || flow == null) {
            return false;
        }
        NormalizedTransactionType type = transaction.getType();
        if (type != NormalizedTransactionType.STAKING_DEPOSIT
                && type != NormalizedTransactionType.STAKING_WITHDRAW) {
            return false;
        }
        if (flow.getRole() == NormalizedLegRole.FEE
                || flow.getQuantityDelta() == null
                || flow.getQuantityDelta().signum() == 0) {
            return false;
        }
        return isLpReceiptLeg(flow) && hasBoosterWrapContext(transaction);
    }

    private static boolean isLpReceiptLeg(NormalizedTransaction.Flow flow) {
        if (Boolean.TRUE.equals(flow.getLpReceipt())) {
            return true;
        }
        return AccountingAssetFamilySupport.isLpReceiptHolding(
                AccountingAssetFamilySupport.continuityIdentity(flow),
                flow.getAssetSymbol(),
                flow.getAssetContract()
        );
    }

    private static boolean hasBoosterWrapContext(NormalizedTransaction transaction) {
        String protocolName = transaction.getProtocolName();
        if (protocolName != null) {
            String normalized = protocolName.trim().toLowerCase(Locale.ROOT);
            if (EQUILIBRIA.equals(normalized) || PENPIE.equals(normalized)) {
                return true;
            }
        }
        String correlationId = transaction.getCorrelationId();
        return correlationId != null
                && (correlationId.startsWith(PENDLE_LP_CORR_PREFIX)
                || correlationId.startsWith(LP_POSITION_CORR_PREFIX));
    }
}
