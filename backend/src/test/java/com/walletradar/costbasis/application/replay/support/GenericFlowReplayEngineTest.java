package com.walletradar.costbasis.application.replay.support;

import com.walletradar.costbasis.application.replay.model.AssetKey;
import com.walletradar.costbasis.application.replay.model.CarryTransfer;
import com.walletradar.costbasis.application.replay.model.PositionSnapshot;
import com.walletradar.costbasis.application.replay.model.PositionState;
import com.walletradar.costbasis.application.replay.model.QuantityConsumption;
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
    void restoreToPositionClampsExcessUncoveredQuantity() {
        // Cycle/15 R5 F2 — reproduces the 0xf03b/ARBITRUM/ETH defect: a composite-bucket
        // restoration handed back uncovered=8.65 for a 0.622 quantity inbound, violating
        // the math invariant uncov <= qty. Engine must clamp the surplus uncov to qty.
        PositionState position = new PositionState(new AssetKey("0xwallet", NetworkId.ARBITRUM, "0xeth", "ETH", "ETH:eth"));

        engine.restoreToPosition(
                new BigDecimal("0.622018432405778513"),
                position,
                new BigDecimal("1175.55143041448"),
                new BigDecimal("8.647458"),
                null
        );

        assertThat(position.quantity()).isEqualByComparingTo("0.622018432405778513");
        assertThat(position.uncoveredQuantity()).isEqualByComparingTo("0.622018432405778513");
    }

    @Test
    void restoreToPositionPreservesNormalUncoveredQuantity() {
        PositionState position = new PositionState(new AssetKey("0xwallet", NetworkId.ETHEREUM, "0xeth", "ETH", "ETH:eth"));

        engine.restoreToPosition(
                new BigDecimal("1.0"),
                position,
                new BigDecimal("2000"),
                new BigDecimal("0.3"),
                null
        );

        assertThat(position.quantity()).isEqualByComparingTo("1.0");
        assertThat(position.uncoveredQuantity()).isEqualByComparingTo("0.3");
    }

    @Test
    void restoreToPositionTreatsNullUncoveredAsZero() {
        PositionState position = new PositionState(new AssetKey("0xwallet", NetworkId.ETHEREUM, "0xeth", "ETH", "ETH:eth"));

        engine.restoreToPosition(
                new BigDecimal("0.5"),
                position,
                new BigDecimal("1000"),
                null,
                new BigDecimal("2000")
        );

        assertThat(position.quantity()).isEqualByComparingTo("0.5");
        assertThat(position.uncoveredQuantity()).isEqualByComparingTo("0");
    }

    @Test
    void consumeQuantityFullDisposePurgesZombieUncov() {
        // Cycle/15 R5 F2 — reproduces the BASE/WETH zombie pattern: after full dispose
        // the residual uncovered quantity from a prior invariant breach must be cleared
        // rather than left as ghost reporting noise.
        PositionState position = new PositionState(new AssetKey("0xwallet", NetworkId.BASE, "0xweth", "WETH", "WETH:weth"));
        position.setQuantity(new BigDecimal("0.5"));
        position.setUncoveredQuantity(new BigDecimal("0.5"));
        position.setTotalCostBasisUsd(BigDecimal.ZERO);

        QuantityConsumption consumption = engine.consumeQuantity(position, new BigDecimal("0.5"));

        assertThat(consumption.appliedQuantity()).isEqualByComparingTo("0.5");
        assertThat(position.quantity()).isEqualByComparingTo("0");
        assertThat(position.uncoveredQuantity()).isEqualByComparingTo("0");
    }

    @Test
    void consumeQuantityPartialDisposePreservesProportionalUncov() {
        PositionState position = new PositionState(new AssetKey("0xwallet", NetworkId.ETHEREUM, "0xusdc", "USDC", "USDC:usdc"));
        position.setQuantity(new BigDecimal("100"));
        position.setUncoveredQuantity(new BigDecimal("40"));
        position.setTotalCostBasisUsd(new BigDecimal("60"));

        QuantityConsumption consumption = engine.consumeQuantity(position, new BigDecimal("50"));

        assertThat(consumption.appliedQuantity()).isEqualByComparingTo("50");
        assertThat(position.quantity()).isEqualByComparingTo("50");
        assertThat(position.uncoveredQuantity()).isEqualByComparingTo("0");
    }

    @Test
    void inboundShortfallSpotFallbackPromotesUncovBasisForEth() {
        PositionState position = new PositionState(new AssetKey("0xf03b", NetworkId.ARBITRUM, "0xeth", "ETH", "ETH:eth"));
        position.setQuantity(new BigDecimal("0.622"));
        position.setUncoveredQuantity(new BigDecimal("0.622"));
        position.setTotalCostBasisUsd(BigDecimal.ZERO);
        PositionSnapshot before = new PositionSnapshot(
                BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, false, false, 0
        );

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("ETH");
        flow.setQuantityDelta(new BigDecimal("0.622"));
        flow.setUnitPriceUsd(new BigDecimal("2188.26"));
        flow.setPriceSource(PriceSource.BYBIT);

        engine.applyInboundShortfallSpotFallback(flow, position, before);

        assertThat(position.quantity()).isEqualByComparingTo("0.622");
        assertThat(position.uncoveredQuantity()).isEqualByComparingTo("0");
        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo("1361.09772");
    }

    @Test
    void peggedNativeSpotFallbackPromotesUncovBasisForCmeth() {
        // Cycle/15 R5 F3 — Bybit FUND CMETH receiver via collapsed-v1 corridor with empty
        // UTA pool. Flow has spot price (via canonical ETH alias). Engine promotes the unbacked
        // qty into basis at spot.
        PositionState position = new PositionState(new AssetKey("BYBIT:33625378:FUND", null, "cmeth", "CMETH", "CMETH:cmeth"));
        position.setQuantity(new BigDecimal("0.144"));
        position.setUncoveredQuantity(new BigDecimal("0.144"));
        position.setTotalCostBasisUsd(BigDecimal.ZERO);
        PositionSnapshot before = new PositionSnapshot(
                BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, false, false, 0
        );

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("CMETH");
        flow.setQuantityDelta(new BigDecimal("0.144"));
        flow.setUnitPriceUsd(new BigDecimal("2175.66"));
        flow.setPriceSource(PriceSource.PEGGED_NATIVE);

        engine.applyPeggedNativeSpotFallback(flow, position, before);

        assertThat(position.quantity()).isEqualByComparingTo("0.144");
        assertThat(position.uncoveredQuantity()).isEqualByComparingTo("0");
        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo("313.29504");
    }

    @Test
    void peggedNativeSpotFallbackSkipsWhenNoUncovDelta() {
        // Carry path succeeded — fallback must not double-count.
        PositionState position = new PositionState(new AssetKey("BYBIT:33625378:FUND", null, "cmeth", "CMETH", "CMETH:cmeth"));
        position.setQuantity(new BigDecimal("0.144"));
        position.setUncoveredQuantity(BigDecimal.ZERO);
        position.setTotalCostBasisUsd(new BigDecimal("313.0"));
        PositionSnapshot before = new PositionSnapshot(
                BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, false, false, 0
        );

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("CMETH");
        flow.setQuantityDelta(new BigDecimal("0.144"));
        flow.setUnitPriceUsd(new BigDecimal("2175.66"));
        flow.setPriceSource(PriceSource.PEGGED_NATIVE);

        engine.applyPeggedNativeSpotFallback(flow, position, before);

        assertThat(position.uncoveredQuantity()).isEqualByComparingTo("0");
        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo("313.0");
    }

    @Test
    void inboundShortfallSpotFallbackPromotesUncovBasisForUsdc() {
        PositionState position = new PositionState(new AssetKey("BYBIT:33625378:FUND", null, "usdc", "USDC", "USDC:usdc"));
        position.setQuantity(new BigDecimal("100"));
        position.setUncoveredQuantity(new BigDecimal("100"));
        position.setTotalCostBasisUsd(BigDecimal.ZERO);
        PositionSnapshot before = new PositionSnapshot(
                BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, false, false, 0
        );

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("USDC");
        flow.setQuantityDelta(new BigDecimal("100"));
        flow.setUnitPriceUsd(new BigDecimal("1"));
        flow.setPriceSource(PriceSource.STABLECOIN);

        engine.applyInboundShortfallSpotFallback(flow, position, before);

        assertThat(position.uncoveredQuantity()).isEqualByComparingTo("0");
        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo("100");
    }

    @Test
    void removeFromPositionDrainsOrphanBasisOnFullDispose() {
        // Cycle/17 R7 — reproduces zkSync WETH REALLOCATE_OUT seq≈7581: qty→0 but basis stayed
        // on WETH while aZksWETH bucket received zero cost.
        PositionState position = new PositionState(
                new AssetKey("0x1a87", NetworkId.ZKSYNC, "0x5aea", "WETH", "WETH:weth")
        );
        position.setQuantity(new BigDecimal("0.545880216141647307"));
        position.setUncoveredQuantity(BigDecimal.ZERO);
        position.setTotalCostBasisUsd(new BigDecimal("1748.290568236854"));
        position.setPerWalletAvco(new BigDecimal("3202.7"));

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setQuantityDelta(new BigDecimal("-0.545880216141647307"));

        CarryTransfer carry = engine.removeFromPosition(flow, position);

        assertThat(carry.costBasisUsd().signum()).isEqualTo(1);
        assertThat(position.quantity()).isEqualByComparingTo("0");
        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo("0");
    }

    @Test
    void removeFromPositionDrainsBasisWhenPerWalletAvcoIsStaleZero() {
        PositionState position = new PositionState(
                new AssetKey("0x1a87", NetworkId.ZKSYNC, "0x5aea", "WETH", "WETH:weth")
        );
        position.setQuantity(new BigDecimal("0.545880216141647307"));
        position.setUncoveredQuantity(BigDecimal.ZERO);
        position.setTotalCostBasisUsd(new BigDecimal("1748.290568236854"));
        position.setPerWalletAvco(BigDecimal.ZERO);

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setQuantityDelta(new BigDecimal("-0.545880216141647307"));

        CarryTransfer carry = engine.removeFromPosition(flow, position);

        assertThat(carry.costBasisUsd().signum()).isEqualTo(1);
        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo("0");
    }

    @Test
    void peggedNativeSpotFallbackIgnoresNonPeggedSymbol() {
        // USDC is not in the pegged-native whitelist — fallback must be no-op.
        PositionState position = new PositionState(new AssetKey("BYBIT:33625378:FUND", null, "usdc", "USDC", "USDC:usdc"));
        position.setQuantity(new BigDecimal("100"));
        position.setUncoveredQuantity(new BigDecimal("100"));
        position.setTotalCostBasisUsd(BigDecimal.ZERO);
        PositionSnapshot before = new PositionSnapshot(
                BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, false, false, 0
        );

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("USDC");
        flow.setQuantityDelta(new BigDecimal("100"));
        flow.setUnitPriceUsd(new BigDecimal("1"));
        flow.setPriceSource(PriceSource.STABLECOIN);

        engine.applyPeggedNativeSpotFallback(flow, position, before);

        assertThat(position.uncoveredQuantity()).isEqualByComparingTo("100");
        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo("0");
    }

    @Test
    void authoritativeLateInboundCarryBasisReplacesProvisionalInsteadOfStacking() {
        PositionState position = new PositionState(new AssetKey("0x1a87", NetworkId.ZKSYNC, "0x5aea", "WETH", "WETH:0x5aea"));
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setQuantityDelta(new BigDecimal("1"));
        engine.applyBuyWithAcquisitionCost(flow, position, new BigDecimal("3200"));
        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo("3200");

        engine.applyAuthoritativeLateInboundCarryBasis(position, new BigDecimal("3200"), new BigDecimal("3100"));

        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo("3100");
    }

    @Test
    void authoritativeLateInboundCarryBasisAddsWhenNoProvisionalBasisExists() {
        PositionState position = new PositionState(new AssetKey("BYBIT:33625378:FUND", null, "eth", "ETH", "ETH:eth"));
        position.setQuantity(new BigDecimal("3.06"));
        position.setUncoveredQuantity(new BigDecimal("3.06"));
        position.setTotalCostBasisUsd(BigDecimal.ZERO);

        engine.applyAuthoritativeLateInboundCarryBasis(position, BigDecimal.ZERO, new BigDecimal("10042.12"));

        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo("10042.12");
    }

    @Test
    void authoritativeLateInboundCarryBasisDoesNotSubtractUnrelatedPositionBasis() {
        PositionState position = new PositionState(new AssetKey("0x1a87", NetworkId.ARBITRUM, "eth", "ETH", "ETH:eth"));
        position.setQuantity(new BigDecimal("5"));
        position.setUncoveredQuantity(BigDecimal.ZERO);
        position.setTotalCostBasisUsd(new BigDecimal("10000"));
        position.setPerWalletAvco(new BigDecimal("2000"));

        engine.applyAuthoritativeLateInboundCarryBasis(position, new BigDecimal("3200"), new BigDecimal("7128"));

        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo("13928");
    }
}
