package com.walletradar.costbasis.application.replay.support;

import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionSource;
import com.walletradar.domain.transaction.normalized.NormalizedTransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-042 — {@code resolveSellAssetKey} honours {@code flow.accountRef}: a multi-fill convert whose
 * disposal is named on {@code :FUND} keeps every fill sticky to {@code :FUND}, so the sub-account
 * fully drains and the max-quantity tiebreak can no longer flip late fills onto a sibling umbrella
 * lot (the 2025-09-10 cmETH phantom).
 */
class ReplayAssetSupportTest {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final String UMBRELLA = "BYBIT:33625378";
    private static final String FUND = "BYBIT:33625378:FUND";

    private final ReplayAssetSupport assetSupport = new ReplayAssetSupport();

    @Test
    void multiFillConvertSellStaysStickyToNamedFundPoolUntilDrained() {
        AssetKey umbrellaKey = new AssetKey(UMBRELLA, null, "SYMBOL:CMETH", "CMETH", "SYMBOL:CMETH");
        AssetKey fundKey = new AssetKey(FUND, null, "SYMBOL:CMETH", "CMETH", "SYMBOL:CMETH");

        Map<AssetKey, PositionState> positions = new LinkedHashMap<>();
        // Stale umbrella lot bigger than the convert's tail — the old max-quantity tiebreak would
        // flip late fills onto it once :FUND drops below it.
        PositionState umbrella = new PositionState(umbrellaKey);
        umbrella.setQuantity(new BigDecimal("0.2"));
        positions.put(umbrellaKey, umbrella);

        PositionState fund = new PositionState(fundKey);
        BigDecimal fundStart = new BigDecimal("0.862092260317885");
        fund.setQuantity(fundStart);
        positions.put(fundKey, fund);

        BigDecimal fillSize = new BigDecimal("0.1");
        BigDecimal remaining = fundStart;
        int fills = 0;
        boolean crossedBelowUmbrella = false;
        while (remaining.signum() > 0) {
            BigDecimal fill = fillSize.min(remaining);
            NormalizedTransaction convert = convertSellTx(fill);

            AssetKey resolved = assetSupport.resolveSellAssetKey(convert, convert.getFlows().get(0), positions);

            assertThat(resolved)
                    .as("fill %s must dispose against the named :FUND pool (:FUND remaining %s, umbrella %s)",
                            fill, remaining, umbrella.quantity())
                    .isEqualTo(fundKey);

            if (remaining.compareTo(umbrella.quantity()) < 0) {
                crossedBelowUmbrella = true;
            }
            // Simulate the engine disposing this fill from the resolved position.
            remaining = remaining.subtract(fill, MC);
            fund.setQuantity(remaining);
            fills++;
        }

        assertThat(fills).as("multi-fill convert").isGreaterThan(1);
        assertThat(crossedBelowUmbrella)
                .as(":FUND dropped below the umbrella lot mid-convert (where the old tiebreak flipped)")
                .isTrue();
        assertThat(fund.quantity()).as(":FUND fully drained by the convert").isEqualByComparingTo("0");
        assertThat(umbrella.quantity()).as("sibling umbrella lot untouched by the convert").isEqualByComparingTo("0.2");
    }

    private static NormalizedTransaction convertSellTx(BigDecimal fill) {
        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("BYBIT-33625378:CONVERT:cmeth");
        tx.setSource(NormalizedTransactionSource.BYBIT);
        tx.setType(NormalizedTransactionType.SWAP);
        // Umbrella-collapsed walletAddress; the named sub-account is carried on flow.accountRef.
        tx.setWalletAddress(UMBRELLA);

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.SELL);
        flow.setAssetSymbol("CMETH");
        flow.setAccountRef(FUND);
        flow.setQuantityDelta(fill.negate());
        tx.setFlows(java.util.List.of(flow));
        return tx;
    }
}
