package com.walletradar.costbasis.application;

import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.costbasis.application.replay.support.GenericFlowReplayEngine;
import com.walletradar.domain.common.NetworkId;
import com.walletradar.domain.common.PriceSource;
import com.walletradar.domain.transaction.normalized.NormalizedLegRole;
import com.walletradar.domain.transaction.normalized.NormalizedTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class GenericFlowReplayEngineTest {

    private final GenericFlowReplayEngine engine = new GenericFlowReplayEngine();

    @Test
    void buyWithoutKnownPriceStaysUncoveredAndUnresolved() {
        PositionState position = new PositionState(assetKey());

        engine.applyBuy(flow(NormalizedLegRole.BUY, "2", null, null), position);

        assertThat(position.quantity()).isEqualByComparingTo("2");
        assertThat(position.uncoveredQuantity()).isEqualByComparingTo("2");
        assertThat(position.totalCostBasisUsd()).isZero();
        assertThat(position.perWalletAvco()).isNull();
        assertThat(position.hasIncompleteHistory()).isTrue();
        assertThat(position.hasUnresolvedFlags()).isTrue();
        assertThat(position.unresolvedFlagCount()).isEqualTo(1);
    }

    @Test
    void coveredSellRealisesPnlWithoutLeavingFlags() {
        PositionState position = new PositionState(assetKey());
        position.setQuantity(new BigDecimal("1.5"));
        position.setTotalCostBasisUsd(new BigDecimal("150"));
        engine.recomputePerWalletAvco(position);

        NormalizedTransaction.Flow sell = flow(NormalizedLegRole.SELL, "-1", "175", PriceSource.BINANCE);
        engine.applySell(sell, position);

        assertThat(position.quantity()).isEqualByComparingTo("0.5");
        assertThat(position.uncoveredQuantity()).isZero();
        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo("50");
        assertThat(position.totalRealisedPnlUsd()).isEqualByComparingTo("75");
        assertThat(position.perWalletAvco()).isEqualByComparingTo("100");
        assertThat(sell.getAvcoAtTimeOfSale()).isEqualByComparingTo("100");
        assertThat(sell.getRealisedPnlUsd()).isEqualByComparingTo("75");
        assertThat(position.hasUnresolvedFlags()).isFalse();
    }

    @Test
    void sellWithExternalShortfallPreservesShortfallAndClearsSaleAnnotations() {
        PositionState position = new PositionState(assetKey());
        position.setQuantity(new BigDecimal("1"));
        position.setTotalCostBasisUsd(new BigDecimal("100"));
        engine.recomputePerWalletAvco(position);

        NormalizedTransaction.Flow sell = flow(NormalizedLegRole.SELL, "-1.5", "150", PriceSource.BINANCE);
        engine.applySell(sell, position);

        assertThat(position.quantity()).isZero();
        assertThat(position.totalCostBasisUsd()).isZero();
        assertThat(position.quantityShortfall()).isEqualByComparingTo("0.5");
        assertThat(position.hasIncompleteHistory()).isTrue();
        assertThat(position.hasUnresolvedFlags()).isTrue();
        assertThat(position.unresolvedFlagCount()).isEqualTo(2);
        assertThat(sell.getAvcoAtTimeOfSale()).isNull();
        assertThat(sell.getRealisedPnlUsd()).isNull();
    }

    @Test
    void sellConsumesUncoveredTailBeforeCoveredBasis() {
        PositionState position = new PositionState(assetKey());
        position.setQuantity(new BigDecimal("2"));
        position.setUncoveredQuantity(new BigDecimal("0.75"));
        position.setTotalCostBasisUsd(new BigDecimal("125"));
        engine.recomputePerWalletAvco(position);

        NormalizedTransaction.Flow sell = flow(NormalizedLegRole.SELL, "-1", "150", PriceSource.BINANCE);
        engine.applySell(sell, position);

        assertThat(position.quantity()).isEqualByComparingTo("1");
        assertThat(position.uncoveredQuantity()).isZero();
        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo("100");
        assertThat(position.perWalletAvco()).isEqualByComparingTo("100");
        assertThat(sell.getAvcoAtTimeOfSale()).isNull();
        assertThat(sell.getRealisedPnlUsd()).isNull();
        assertThat(position.hasUnresolvedFlags()).isTrue();
    }

    @Test
    void applyBuyWithExplicitAcquisitionCostSetsWeightedAvco() {
        PositionState position = new PositionState(assetKey());
        position.setQuantity(new BigDecimal("100"));
        position.setTotalCostBasisUsd(new BigDecimal("105"));
        engine.recomputePerWalletAvco(position);

        NormalizedTransaction.Flow buy = flow(NormalizedLegRole.BUY, "100", "1.05", PriceSource.BINANCE);
        engine.applyBuyWithAcquisitionCost(buy, position, new BigDecimal("100"));

        assertThat(position.quantity()).isEqualByComparingTo("200");
        assertThat(position.perWalletAvco()).isEqualByComparingTo("1.025");
    }

    @Test
    void sponsoredGasInAddsZeroCostCoveredInventory() {
        PositionState position = new PositionState(assetKey());

        engine.applySponsoredGasIn(flow(NormalizedLegRole.TRANSFER, "0.25", null, null), position);

        assertThat(position.quantity()).isEqualByComparingTo("0.25");
        assertThat(position.uncoveredQuantity()).isZero();
        assertThat(position.totalCostBasisUsd()).isZero();
        assertThat(position.perWalletAvco()).isEqualByComparingTo("0");
        assertThat(position.hasUnresolvedFlags()).isFalse();
    }

    @Test
    void removeFromPositionUsesDerivedAvcoWhenStoredAvcoIsZeroButBasisExists() {
        PositionState position = new PositionState(assetKey());
        position.setQuantity(new BigDecimal("0.5459"));
        position.setTotalCostBasisUsd(new BigDecimal("1748.29"));
        position.setPerWalletAvco(BigDecimal.ZERO);

        CarryTransfer carry = engine.removeFromPosition(
                flow(NormalizedLegRole.TRANSFER, "-0.5459", null, null),
                position
        );

        assertThat(carry.costBasisUsd()).isEqualByComparingTo("1748.29");
        assertThat(position.quantity()).isZero();
        assertThat(position.totalCostBasisUsd()).isZero();
        assertThat(position.perWalletAvco()).isNull();
    }

    private AssetKey assetKey() {
        return new AssetKey("wallet-a", NetworkId.BASE, "NATIVE:BASE", "ETH", "NATIVE:BASE");
    }

    private NormalizedTransaction.Flow flow(
            NormalizedLegRole role,
            String quantityDelta,
            String unitPriceUsd,
            PriceSource priceSource
    ) {
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(role);
        flow.setAssetSymbol("ETH");
        flow.setAssetContract("NATIVE:BASE");
        flow.setQuantityDelta(new BigDecimal(quantityDelta));
        flow.setUnitPriceUsd(unitPriceUsd == null ? null : new BigDecimal(unitPriceUsd));
        flow.setPriceSource(priceSource);
        return flow;
    }
}
