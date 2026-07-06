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
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GenericFlowReplayEngineTest {

    private final GenericFlowReplayEngine engine = new GenericFlowReplayEngine(null);

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
    void restoreToPositionFloorsUsdStablecoinCarriedBasisToPeg() {
        // R-3*: a depressed source AVCO (e.g. USDT carried at $0.5575 from a mis-priced upstream
        // pool / borrow proceeds) must not propagate a sub-peg basis through the continuity-carry
        // restore path (CARRY_IN / REALLOCATE_IN / LENDING_WITHDRAW). The covered portion is
        // floored to $1/unit so the receiving sub-ledger settles at peg (fixes BYBIT:…:FUND USDT
        // at $0.846).
        PositionState position = new PositionState(
                new AssetKey("BYBIT:33625378:FUND", null, "SYMBOL:USDT", "USDT", "FAMILY:USDT"));

        engine.restoreToPosition(
                new BigDecimal("1000"),
                position,
                new BigDecimal("557.5"),
                BigDecimal.ZERO,
                new BigDecimal("0.5575")
        );

        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo("1000");
        assertThat(position.perWalletAvco()).isEqualByComparingTo("1");
    }

    @Test
    void restoreToPositionDoesNotReduceAbovePegStablecoinCarriedBasis() {
        // The shared restore path applies only the R-3* floor (not the U-3 cap), so an above-peg
        // carried basis is left intact here — the cap is applied selectively by the same-asset
        // stablecoin withdraw handlers (see pegCappedStablecoinCarryBasis tests) to avoid clamping
        // legitimate cross-asset LP-exit carries that also flow through this method.
        PositionState position = new PositionState(
                new AssetKey("BYBIT:1:FUND", null, "SYMBOL:USDC", "USDC", "FAMILY:USDC"));

        engine.restoreToPosition(
                new BigDecimal("100"),
                position,
                new BigDecimal("105"),
                BigDecimal.ZERO,
                new BigDecimal("1.05")
        );

        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo("105");
    }

    @Test
    void restoreToPositionDoesNotFloorNearPegStablecoinConversionArtifact() {
        // A cross-asset bridge (USDT→USDC) conserves total basis but yields a per-unit basis
        // fractionally below $1 due to differing unit counts. This near-peg artifact must NOT be
        // floored — flooring would manufacture basis and break conservation.
        PositionState position = new PositionState(
                new AssetKey("wallet-a", NetworkId.ARBITRUM, "0xusdc", "USDC", "FAMILY:USDC"));

        engine.restoreToPosition(
                new BigDecimal("21.818316"),
                position,
                new BigDecimal("21.81403"),
                BigDecimal.ZERO,
                new BigDecimal("0.9998")
        );

        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo("21.81403");
    }

    @Test
    void restoreToPositionDoesNotFloorNonStablecoinCarriedBasis() {
        PositionState position = new PositionState(
                new AssetKey("0xwallet", NetworkId.ETHEREUM, "0xeth", "ETH", "FAMILY:ETH"));

        engine.restoreToPosition(
                new BigDecimal("1"),
                position,
                new BigDecimal("0.5"),
                BigDecimal.ZERO,
                new BigDecimal("0.5")
        );

        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo("0.5");
    }

    @Test
    void restoreToPositionDoesNotFloorConfusableStablecoinLookalike() {
        // F-6 homoglyph guard: a Cyrillic "UЅDT" lookalike must never be floored to peg.
        PositionState position = new PositionState(
                new AssetKey("0xwallet", NetworkId.ETHEREUM, "0xscam", "U\u0405DT", "0xscam"));

        engine.restoreToPosition(
                new BigDecimal("1000"),
                position,
                new BigDecimal("10"),
                BigDecimal.ZERO,
                new BigDecimal("0.01")
        );

        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo("10");
    }

    @Test
    void pegFlooredStablecoinCarryBasisFloorsBelowPegBridgeCarry() {
        // R-3* (late-attach path): a BRIDGE_IN USDC corridor carry arriving at $0.8874/unit must be
        // floored to $1/covered-unit so the depressed origin pool cannot propagate a sub-peg basis
        // into the destination across the bridge / pending-late-attach restore.
        AssetKey usdc = new AssetKey("0x1a87", NetworkId.ARBITRUM, "0xusdc", "USDC", "FAMILY:USDC");

        BigDecimal floored = engine.pegFlooredStablecoinCarryBasis(
                usdc, new BigDecimal("1000"), new BigDecimal("887.4"));

        assertThat(floored).isEqualByComparingTo("1000");
    }

    @Test
    void pegCappedStablecoinCarryBasisCapsVaultWithdrawAbovePegToPeg() {
        // U-3: a VAULT_WITHDRAW / LENDING_WITHDRAW USDC leg whose share-rate contamination yields a
        // carried basis of $1.99/unit (1990 on 1000 covered) is capped to $1/covered-unit (1000).
        AssetKey usdc = new AssetKey("0x1a87", NetworkId.MANTLE, "0xusdc", "USDC", "FAMILY:USDC");

        BigDecimal capped = engine.pegCappedStablecoinCarryBasis(
                usdc, new BigDecimal("1000"), new BigDecimal("1990"));

        assertThat(capped).isEqualByComparingTo("1000");
    }

    @Test
    void pegCappedStablecoinCarryBasisCapsExtremeShareRateContaminationToPeg() {
        // U-3: extreme EVK/ERC4626 share-price contamination ($3,021/unit) clamps to peg, not the
        // fabricated value.
        AssetKey usdc = new AssetKey("0x1a87", NetworkId.MANTLE, "0xusdc", "USDC", "FAMILY:USDC");

        BigDecimal capped = engine.pegCappedStablecoinCarryBasis(
                usdc, new BigDecimal("1"), new BigDecimal("3021.31"));

        assertThat(capped).isEqualByComparingTo("1");
    }

    @Test
    void pegCappedStablecoinCarryBasisDoesNotCapNonStablecoinAbovePeg() {
        // U-3 guard: a non-stable asset (cmETH) carried well above $1/unit is NOT capped.
        AssetKey cmeth = new AssetKey("0x1a87", NetworkId.MANTLE, "0xcmeth", "cmETH", "FAMILY:ETH");

        BigDecimal result = engine.pegCappedStablecoinCarryBasis(
                cmeth, new BigDecimal("0.86155"), new BigDecimal("3328.54"));

        assertThat(result).isEqualByComparingTo("3328.54");
    }

    @Test
    void pegCappedStablecoinCarryBasisLeavesGenuinePegCarryUnchanged() {
        // U-3: a genuine $1.00/unit stablecoin carry is unaffected by the cap.
        AssetKey usdc = new AssetKey("0x1a87", NetworkId.MANTLE, "0xusdc", "USDC", "FAMILY:USDC");

        BigDecimal result = engine.pegCappedStablecoinCarryBasis(
                usdc, new BigDecimal("500"), new BigDecimal("500"));

        assertThat(result).isEqualByComparingTo("500");
    }

    @Test
    void pegCappedStablecoinCarryBasisIgnoresConfusableLookalike() {
        // F-6 homoglyph guard: a Cyrillic "UЅDC" lookalike above peg is never capped to peg.
        AssetKey scam = new AssetKey("0x1a87", NetworkId.MANTLE, "0xscam", "U\u0405DC", "0xscam");

        BigDecimal result = engine.pegCappedStablecoinCarryBasis(
                scam, new BigDecimal("1000"), new BigDecimal("3021.31"));

        assertThat(result).isEqualByComparingTo("3021.31");
    }

    @Test
    void stablecoinCarryIsClampedToPegInBothDirections() {
        // R-3* floor + U-3 cap interaction: $0.84/unit floors up to $1, $1.99/unit caps down to $1.
        AssetKey usdc = new AssetKey("0x1a87", NetworkId.MANTLE, "0xusdc", "USDC", "FAMILY:USDC");

        BigDecimal floored = engine.pegFlooredStablecoinCarryBasis(
                usdc, new BigDecimal("1000"), new BigDecimal("840"));
        BigDecimal capped = engine.pegCappedStablecoinCarryBasis(
                usdc, new BigDecimal("1000"), new BigDecimal("1990"));

        assertThat(floored).isEqualByComparingTo("1000");
        assertThat(capped).isEqualByComparingTo("1000");
    }

    @Test
    void pegFlooredStablecoinCarryBasisLeavesNearPegCarryUntouched() {
        AssetKey usdc = new AssetKey("0x1a87", NetworkId.ARBITRUM, "0xusdc", "USDC", "FAMILY:USDC");

        BigDecimal result = engine.pegFlooredStablecoinCarryBasis(
                usdc, new BigDecimal("1000"), new BigDecimal("999.8"));

        assertThat(result).isEqualByComparingTo("999.8");
    }

    @Test
    void pegFlooredStablecoinCarryBasisIgnoresNonStablecoin() {
        AssetKey eth = new AssetKey("0x1a87", NetworkId.ARBITRUM, "0xeth", "ETH", "FAMILY:ETH");

        BigDecimal result = engine.pegFlooredStablecoinCarryBasis(
                eth, new BigDecimal("1"), new BigDecimal("0.5"));

        assertThat(result).isEqualByComparingTo("0.5");
    }

    @Test
    void pegFlooredStablecoinCarryBasisIgnoresConfusableLookalike() {
        // F-6 homoglyph guard: a Cyrillic "UЅDC" lookalike is never floored to peg.
        AssetKey scam = new AssetKey("0x1a87", NetworkId.ARBITRUM, "0xscam", "U\u0405DC", "0xscam");

        BigDecimal result = engine.pegFlooredStablecoinCarryBasis(
                scam, new BigDecimal("1000"), new BigDecimal("10"));

        assertThat(result).isEqualByComparingTo("10");
    }

    @Test
    void pegFlooredStablecoinCarryBasisIgnoresZeroCoveredQuantity() {
        AssetKey usdc = new AssetKey("0x1a87", NetworkId.ARBITRUM, "0xusdc", "USDC", "FAMILY:USDC");

        BigDecimal result = engine.pegFlooredStablecoinCarryBasis(
                usdc, BigDecimal.ZERO, new BigDecimal("0"));

        assertThat(result).isEqualByComparingTo("0");
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
        PositionSnapshot before = PositionSnapshot.mirrorTax(
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
        PositionSnapshot before = PositionSnapshot.mirrorTax(
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
        PositionSnapshot before = PositionSnapshot.mirrorTax(
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
    void inboundShortfallMarketAtTimeFallbackPromotesUncovWhenNoFlowPrice() {
        // F-5(a) — an unpaired zkSync ETH BRIDGE_IN/STAKING_DEPOSIT carry-in with NO flow price and
        // no paired OUT source must inherit market-at-timestamp basis, never enter the pool at $0.
        ReplayMarketAuthority authority = mock(ReplayMarketAuthority.class);
        when(authority.resolve(any(), any())).thenReturn(Optional.of(new ReplayMarketAuthority.ResolvedMarketPrice(
                new BigDecimal("2000"),
                PriceSource.COINGECKO,
                ReplayMarketAuthority.ResolvedMarketPrice.Authority.HISTORICAL_CACHE
        )));
        GenericFlowReplayEngine marketEngine = new GenericFlowReplayEngine(authority);

        PositionState position = new PositionState(new AssetKey("0xwallet", NetworkId.ZKSYNC, "0xeth", "ETH", "ETH:eth"));
        position.setQuantity(new BigDecimal("0.5"));
        position.setUncoveredQuantity(new BigDecimal("0.5"));
        position.setTotalCostBasisUsd(BigDecimal.ZERO);
        PositionSnapshot before = PositionSnapshot.mirrorTax(
                BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, false, false, 0
        );

        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("tx-bridge-in");
        tx.setBlockTimestamp(Instant.parse("2025-06-01T08:30:00Z"));
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("ETH");
        flow.setQuantityDelta(new BigDecimal("0.5"));

        marketEngine.applyInboundShortfallSpotFallback(tx, flow, position, before);

        assertThat(position.uncoveredQuantity()).isEqualByComparingTo("0");
        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo("1000");
    }

    @Test
    void inboundShortfallLeavesUncovWhenNoFlowPriceAndNoMarket() {
        // Fail-safe — when neither a flow price nor a market-at-timestamp quote can be resolved the
        // uncovered quantity is left untouched (excluded from AVCO), never fabricated at $0 basis.
        ReplayMarketAuthority authority = mock(ReplayMarketAuthority.class);
        when(authority.resolve(any(), any())).thenReturn(Optional.empty());
        GenericFlowReplayEngine marketEngine = new GenericFlowReplayEngine(authority);

        PositionState position = new PositionState(new AssetKey("0xwallet", NetworkId.ZKSYNC, "0xeth", "ETH", "ETH:eth"));
        position.setQuantity(new BigDecimal("0.5"));
        position.setUncoveredQuantity(new BigDecimal("0.5"));
        position.setTotalCostBasisUsd(BigDecimal.ZERO);
        PositionSnapshot before = PositionSnapshot.mirrorTax(
                BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, false, false, 0
        );

        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("tx-bridge-in");
        tx.setBlockTimestamp(Instant.parse("2025-06-01T08:30:00Z"));
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("ETH");
        flow.setQuantityDelta(new BigDecimal("0.5"));

        marketEngine.applyInboundShortfallSpotFallback(tx, flow, position, before);

        assertThat(position.uncoveredQuantity()).isEqualByComparingTo("0.5");
        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo("0");
    }

    @Test
    void bridgeCarryInEmptySourceCanonicalRoutesToPendingNeverAvcoZero() {
        // RC-7: a bridge CARRY_IN with an empty source carry (uncovered, no flow price) for a
        // cross-network-priceable canonical asset (ETH on LINEA) that cannot resolve a market quote
        // must be flagged PENDING / incomplete-history — never settled silently at avco $0 (covered).
        ReplayMarketAuthority authority = mock(ReplayMarketAuthority.class);
        when(authority.resolve(any(), any())).thenReturn(Optional.empty());
        GenericFlowReplayEngine marketEngine = new GenericFlowReplayEngine(authority);

        PositionState position = new PositionState(new AssetKey("0xwallet", NetworkId.LINEA, "0xeth", "ETH", "FAMILY:ETH"));
        position.setQuantity(new BigDecimal("0.0116"));
        position.setUncoveredQuantity(new BigDecimal("0.0116"));
        position.setTotalCostBasisUsd(BigDecimal.ZERO);
        PositionSnapshot before = PositionSnapshot.mirrorTax(
                BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, false, false, 0
        );

        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("tx-linea-bridge-in");
        tx.setNetworkId(NetworkId.LINEA);
        tx.setBlockTimestamp(Instant.parse("2025-06-01T08:30:00Z"));
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("ETH");
        flow.setQuantityDelta(new BigDecimal("0.0116"));

        marketEngine.applyInboundShortfallSpotFallback(tx, flow, position, before);

        // Never fabricates covered basis at $0: the quantity stays uncovered and PENDING-flagged.
        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo("0");
        assertThat(position.uncoveredQuantity()).isEqualByComparingTo("0.0116");
        assertThat(position.hasIncompleteHistory()).isTrue();
        assertThat(position.hasUnresolvedFlags()).isTrue();
    }

    @Test
    void bridgeCarryInEmptySourceCanonicalResolvesCrossNetworkQuote() {
        // RC-7 option (a): when a cross-network ETH quote IS available at the inbound timestamp the
        // uncovered bridge carry-in is promoted to covered basis (no PENDING, no $0).
        ReplayMarketAuthority authority = mock(ReplayMarketAuthority.class);
        when(authority.resolve(any(), any())).thenReturn(Optional.of(new ReplayMarketAuthority.ResolvedMarketPrice(
                new BigDecimal("2500"),
                PriceSource.COINGECKO,
                ReplayMarketAuthority.ResolvedMarketPrice.Authority.HISTORICAL_CACHE
        )));
        GenericFlowReplayEngine marketEngine = new GenericFlowReplayEngine(authority);

        PositionState position = new PositionState(new AssetKey("0xwallet", NetworkId.LINEA, "0xeth", "ETH", "FAMILY:ETH"));
        position.setQuantity(new BigDecimal("0.0116"));
        position.setUncoveredQuantity(new BigDecimal("0.0116"));
        position.setTotalCostBasisUsd(BigDecimal.ZERO);
        PositionSnapshot before = PositionSnapshot.mirrorTax(
                BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, false, false, 0
        );

        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("tx-linea-bridge-in");
        tx.setNetworkId(NetworkId.LINEA);
        tx.setBlockTimestamp(Instant.parse("2025-06-01T08:30:00Z"));
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("ETH");
        flow.setQuantityDelta(new BigDecimal("0.0116"));

        marketEngine.applyInboundShortfallSpotFallback(tx, flow, position, before);

        assertThat(position.uncoveredQuantity()).isEqualByComparingTo("0");
        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo("29");
    }

    @Test
    void inboundShortfallPrefersFlowPriceOverMarketAuthority() {
        // The flow's own resolved spot price stays authoritative; market-at-time is only a backstop.
        ReplayMarketAuthority authority = mock(ReplayMarketAuthority.class);
        GenericFlowReplayEngine marketEngine = new GenericFlowReplayEngine(authority);

        PositionState position = new PositionState(new AssetKey("0xwallet", NetworkId.ARBITRUM, "0xeth", "ETH", "ETH:eth"));
        position.setQuantity(new BigDecimal("1"));
        position.setUncoveredQuantity(new BigDecimal("1"));
        position.setTotalCostBasisUsd(BigDecimal.ZERO);
        PositionSnapshot before = PositionSnapshot.mirrorTax(
                BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, false, false, 0
        );

        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("tx");
        tx.setBlockTimestamp(Instant.parse("2025-06-01T08:30:00Z"));
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("ETH");
        flow.setQuantityDelta(new BigDecimal("1"));
        flow.setUnitPriceUsd(new BigDecimal("3000"));
        flow.setPriceSource(PriceSource.BYBIT);

        marketEngine.applyInboundShortfallSpotFallback(tx, flow, position, before);

        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo("3000");
        org.mockito.Mockito.verifyNoInteractions(authority);
    }

    @Test
    void inboundShortfallDoesNotTouchCoveredCrossAssetUsdcCarry() {
        // Regression — a USDC inbound that already absorbed elevated cross-asset basis (VAULT_WITHDRAW
        // / LP_EXIT REALLOCATE_IN, ~$2.5/u, fully covered) must NOT be re-priced or capped by the
        // market-at-time backstop. The backstop fires only on uncovered (basisless) quantity.
        ReplayMarketAuthority authority = mock(ReplayMarketAuthority.class);
        GenericFlowReplayEngine marketEngine = new GenericFlowReplayEngine(authority);

        PositionState position = new PositionState(new AssetKey("0xwallet", NetworkId.ETHEREUM, "0xusdc", "USDC", "USDC:usdc"));
        position.setQuantity(new BigDecimal("100"));
        position.setUncoveredQuantity(BigDecimal.ZERO);
        position.setTotalCostBasisUsd(new BigDecimal("250"));
        PositionSnapshot before = PositionSnapshot.mirrorTax(
                BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, false, false, 0
        );

        NormalizedTransaction tx = new NormalizedTransaction();
        tx.setId("tx-usdc-carry");
        tx.setBlockTimestamp(Instant.parse("2025-06-01T08:30:00Z"));
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.TRANSFER);
        flow.setAssetSymbol("USDC");
        flow.setQuantityDelta(new BigDecimal("100"));

        marketEngine.applyInboundShortfallSpotFallback(tx, flow, position, before);

        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo("250");
        org.mockito.Mockito.verifyNoInteractions(authority);
    }

    @Test
    void inboundShortfallSpotFallbackPromotesUncovBasisForUsdc() {
        PositionState position = new PositionState(new AssetKey("BYBIT:33625378:FUND", null, "usdc", "USDC", "USDC:usdc"));
        position.setQuantity(new BigDecimal("100"));
        position.setUncoveredQuantity(new BigDecimal("100"));
        position.setTotalCostBasisUsd(BigDecimal.ZERO);
        PositionSnapshot before = PositionSnapshot.mirrorTax(
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
        PositionSnapshot before = PositionSnapshot.mirrorTax(
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

    @Test
    void zeroCostAcquisitionAddsTaxBasisButNotNetBasis() {
        PositionState position = new PositionState(new AssetKey("0xwallet", NetworkId.ARBITRUM, "cake", "CAKE", "CAKE:cake"));
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.BUY);
        flow.setAssetSymbol("CAKE");
        flow.setQuantityDelta(new BigDecimal("10"));

        engine.applyBuyWithAcquisitionCost(
                flow,
                position,
                new BigDecimal("50"),
                com.walletradar.domain.transaction.normalized.NormalizedTransactionType.REWARD_CLAIM
        );

        assertThat(position.quantity()).isEqualByComparingTo("10");
        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo("50");
        assertThat(position.netTotalCostBasisUsd()).isEqualByComparingTo("0");
        assertThat(position.perWalletAvco()).isEqualByComparingTo("5");
        assertThat(position.perWalletNetAvco()).isEqualByComparingTo("0");
    }

    @Test
    void applySellTracksNetRealisedPnlSeparatelyFromTax() {
        PositionState position = new PositionState(new AssetKey("0xwallet", NetworkId.ARBITRUM, "eth", "ETH", "ETH:eth"));
        position.setQuantity(new BigDecimal("2"));
        position.setTotalCostBasisUsd(new BigDecimal("4000"));
        position.setNetTotalCostBasisUsd(new BigDecimal("3000"));
        position.setPerWalletAvco(new BigDecimal("2000"));
        position.setPerWalletNetAvco(new BigDecimal("1500"));

        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.SELL);
        flow.setAssetSymbol("ETH");
        flow.setQuantityDelta(new BigDecimal("-1"));
        flow.setUnitPriceUsd(new BigDecimal("2500"));
        flow.setPriceSource(PriceSource.COINGECKO);

        engine.applySell(flow, position);

        assertThat(position.totalRealisedPnlUsd()).isEqualByComparingTo("500");
        assertThat(position.totalNetRealisedPnlUsd()).isEqualByComparingTo("1000");
    }

    @Test
    void applyBuyRoutesBotLedgerPreCoverageLotThroughAuthorityAndClamps() {
        // Issue 3 (ADR-043): a BOT_LEDGER lot carries a stablecoin-derived unitPrice ($0.5766) that
        // hasKnownPrice would otherwise book directly, bypassing the RC-D clamp. applyBuy must route
        // BOT_LEDGER through ReplayMarketAuthority.resolve(), which clamps a genuinely pre-coverage
        // bot lot to the nearest valid market bucket (DOGE $0.23246) → basis 150.591 × 0.23246.
        ReplayMarketAuthority authority = mock(ReplayMarketAuthority.class);
        GenericFlowReplayEngine engineWithAuthority = new GenericFlowReplayEngine(authority);
        PositionState position = new PositionState(
                new AssetKey("BYBIT:421325298", null, "SYMBOL:DOGE", "DOGE", "FAMILY:DOGE"));

        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setBlockTimestamp(Instant.parse("2025-01-31T00:00:00Z"));
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.BUY);
        flow.setAssetSymbol("DOGE");
        flow.setQuantityDelta(new BigDecimal("150.591"));
        flow.setUnitPriceUsd(new BigDecimal("0.5766"));
        flow.setPriceSource(PriceSource.BOT_LEDGER);

        when(authority.resolve(any(), any())).thenReturn(Optional.of(
                new ReplayMarketAuthority.ResolvedMarketPrice(
                        new BigDecimal("0.23246"),
                        PriceSource.COINGECKO,
                        ReplayMarketAuthority.ResolvedMarketPrice.Authority.HISTORICAL_CACHE)));

        engineWithAuthority.applyBuy(transaction, flow, position);

        assertThat(position.quantity()).isEqualByComparingTo("150.591");
        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo("35.00638386");
    }

    @Test
    void applyBuyDoesNotRouteNonBotLedgerLotThroughAuthority() {
        // Issue 3 (ADR-043): the blast radius is BOT_LEDGER only — a normally-priced spot execution
        // (e.g. the 2025-10-10 in-coverage DOGE lot) keeps the hasKnownPrice short-circuit and is NOT
        // routed through the authority, so its book price is untouched.
        ReplayMarketAuthority authority = mock(ReplayMarketAuthority.class);
        GenericFlowReplayEngine engineWithAuthority = new GenericFlowReplayEngine(authority);
        PositionState position = new PositionState(
                new AssetKey("BYBIT:421325298", null, "SYMBOL:DOGE", "DOGE", "FAMILY:DOGE"));

        NormalizedTransaction transaction = new NormalizedTransaction();
        transaction.setBlockTimestamp(Instant.parse("2025-10-10T00:00:00Z"));
        NormalizedTransaction.Flow flow = new NormalizedTransaction.Flow();
        flow.setRole(NormalizedLegRole.BUY);
        flow.setAssetSymbol("DOGE");
        flow.setQuantityDelta(new BigDecimal("111.189"));
        flow.setUnitPriceUsd(new BigDecimal("0.2121"));
        flow.setPriceSource(PriceSource.EXECUTION);

        engineWithAuthority.applyBuy(transaction, flow, position);

        assertThat(position.quantity()).isEqualByComparingTo("111.189");
        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo(
                new BigDecimal("111.189").multiply(new BigDecimal("0.2121")));
        verifyNoInteractions(authority);
    }

    // ── ADR-040 Change 2: Net AVCO carry conservation ────────────────────────────

    /**
     * WRAP→UNWRAP round-trip: the net basis after the round-trip equals the pre-wrap net
     * basis (not the tax basis). The carry-aware restoreToPosition overload must route
     * carry.netCostBasisUsd() instead of forcing net = tax.
     */
    @Test
    void wrapUnwrapRoundTripConservesNetBasis() {
        AssetKey eth = new AssetKey("0xwallet", NetworkId.ETHEREUM, "0xeth", "ETH", "ETH:eth");
        // Pre-wrap state: ETH position with tax $3000, net $1975 (reward-discounted)
        BigDecimal taxBasis = new BigDecimal("3000.00");
        BigDecimal netBasis = new BigDecimal("1975.00");
        BigDecimal qty = new BigDecimal("1.0");

        // Simulate WRAP: build carry as removeFromPosition would produce (9-param, net != tax)
        CarryTransfer carry = new CarryTransfer(
                qty, qty, BigDecimal.ZERO,
                taxBasis, taxBasis,    // avco = $3000
                netBasis, netBasis,    // netAvco = $1975
                false, eth
        );

        assertThat(carry.costBasisUsd()).isEqualByComparingTo("3000.00");
        assertThat(carry.netCostBasisUsd()).isEqualByComparingTo("1975.00");

        // UNWRAP: restore using carry-aware overload → net must NOT be re-seeded from tax
        PositionState position = new PositionState(eth);
        engine.restoreToPosition(carry, position);

        assertThat(position.quantity()).isEqualByComparingTo("1.0");
        assertThat(position.totalCostBasisUsd()).isEqualByComparingTo("3000.00");
        assertThat(position.netTotalCostBasisUsd())
                .as("Net basis must be conserved to pre-wrap value, not inflated to tax")
                .isEqualByComparingTo("1975.00");
        // Σnet residual after round-trip == 0
        assertThat(position.netTotalCostBasisUsd().subtract(netBasis).abs())
                .isLessThan(new BigDecimal("0.000001"));
    }

    /**
     * CARRY_OUT + CARRY_IN corridor: the net AVCO on the IN side equals the source net AVCO.
     * The 9-param CarryTransfer constructor preserves net independently of tax.
     */
    @Test
    void carryInCorridorPreservesNetAvco() {
        AssetKey dst = new AssetKey("BYBIT:1", null, "eth", "ETH", "ETH:eth");
        BigDecimal taxBasis = new BigDecimal("6000.00");
        BigDecimal netBasis = new BigDecimal("3500.00"); // net < tax (reward-discount)
        BigDecimal qty = new BigDecimal("2.0");
        BigDecimal taxAvco = new BigDecimal("3000.00");
        BigDecimal netAvco = new BigDecimal("1750.00");

        // CARRY_OUT carry with separate net fields
        CarryTransfer outCarry = new CarryTransfer(
                qty, qty, BigDecimal.ZERO,
                taxBasis, taxAvco,
                netBasis, netAvco,
                false, dst
        );

        assertThat(outCarry.netCostBasisUsd()).isEqualByComparingTo("3500.00");
        assertThat(outCarry.netAvco()).isEqualByComparingTo("1750.00");

        // CARRY_IN: restore to destination using carry-aware overload
        PositionState dest = new PositionState(dst);
        engine.restoreToPosition(outCarry, dest);

        assertThat(dest.totalCostBasisUsd()).isEqualByComparingTo("6000.00");
        assertThat(dest.netTotalCostBasisUsd())
                .as("CARRY_IN must transport source net basis, not re-seed from tax")
                .isEqualByComparingTo("3500.00");
        assertThat(dest.perWalletNetAvco())
                .as("Net AVCO at destination must equal source net AVCO")
                .isEqualByComparingTo("1750.00");
        // Invariant: net AVCO <= tax AVCO
        assertThat(dest.perWalletNetAvco()).isLessThanOrEqualTo(dest.perWalletAvco());
    }

    @Test
    void beanExposesExactlyOneAutowiredConstructorTakingMarketAuthority() {
        // FIX 2 (ADR-043, replay #13b): the RC-D clamp (and the resolve() paths in
        // materializePendingInbound / applyInboundShortfallSpotFallback) were DEAD in production
        // because a competing no-arg GenericFlowReplayEngine() constructor with @Autowired only on the
        // PARAMETER of the second constructor let Spring select the no-arg one → replayMarketAuthority
        // was null. Guard structurally: there must be EXACTLY ONE constructor, it must be annotated at
        // CONSTRUCTOR level with @Autowired, and it must take the ReplayMarketAuthority so Spring is
        // forced to inject the bean (no no-arg footgun can reappear).
        Constructor<?>[] constructors = GenericFlowReplayEngine.class.getDeclaredConstructors();
        assertThat(constructors).hasSize(1);
        Constructor<?> constructor = constructors[0];
        assertThat(constructor.isAnnotationPresent(Autowired.class)).isTrue();
        assertThat(constructor.getParameterTypes()).containsExactly(ReplayMarketAuthority.class);
    }
}
