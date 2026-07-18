package com.walletradar.application.costbasis.application.replay.support;

import com.walletradar.canonical.correlation.CorrelationContract;
import com.walletradar.application.costbasis.application.replay.model.AssetKey;
import com.walletradar.application.costbasis.application.replay.model.PositionState;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.wallet.WalletDomainKind;
import com.walletradar.domain.wallet.WalletRef;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Locale;

/**
 * Bybit earn-principal pairing helpers extracted from {@link com.walletradar.application.costbasis.application.replay.handler.TransferReplayHandler}.
 */
public final class TransferEarnPrincipalReplaySupport {

    public static final BigDecimal EARN_PRINCIPAL_DUST_BASIS_THRESHOLD = new BigDecimal("100");
    public static final BigDecimal EARN_CORRIDOR_INFLATED_BASIS_MULTIPLIER = new BigDecimal("10");
    public static final BigDecimal EARN_BUNDLE_PARTIAL_LEG_THRESHOLD = new BigDecimal("0.001");

    private static final MathContext MC = MathContext.DECIMAL128;

    private TransferEarnPrincipalReplaySupport() {
    }

    public static boolean isBybitEarnPrincipalPaired(NormalizedTransaction transaction) {
        if (transaction == null) {
            return false;
        }
        String correlationId = transaction.getCorrelationId();
        if (correlationId == null
                || !correlationId.startsWith(CorrelationContract.BYBIT_EARN_PRINCIPAL_V1_PREFIX)) {
            return false;
        }
        if (Boolean.TRUE.equals(transaction.getContinuityCandidate())) {
            return true;
        }
        String walletAddress = transaction.getWalletAddress();
        if (walletAddress == null) {
            return false;
        }
        WalletRef ref = WalletRef.parse(walletAddress);
        String sub = ref.subAccount() != null ? ref.subAccount().toUpperCase(Locale.ROOT) : null;
        return "EARN".equals(sub) || !"EARN".equals(sub);
    }

    public static boolean multiSourceEarnPrincipalBundle(NormalizedTransaction transaction) {
        return isBybitEarnPrincipalPaired(transaction);
    }

    public static boolean hasEarnPrincipalCarryBasis(PositionState position) {
        if (position == null) {
            return false;
        }
        BigDecimal quantity = position.quantity();
        if (quantity == null || quantity.signum() <= 0) {
            return false;
        }
        BigDecimal basis = position.totalCostBasisUsd();
        if (basis != null && basis.signum() > 0) {
            return true;
        }
        BigDecimal uncovered = position.uncoveredQuantity() == null ? BigDecimal.ZERO : position.uncoveredQuantity();
        BigDecimal covered = quantity.subtract(uncovered, MC);
        if (covered.signum() <= 0) {
            return false;
        }
        BigDecimal avco = position.perWalletAvco();
        return avco != null && avco.signum() > 0;
    }

    public static AssetKey umbrellaKeyFor(AssetKey flowKey, String umbrellaWallet) {
        return new AssetKey(
                umbrellaWallet,
                flowKey.networkId(),
                flowKey.assetContract(),
                flowKey.assetSymbol(),
                flowKey.assetIdentity()
        );
    }
}
