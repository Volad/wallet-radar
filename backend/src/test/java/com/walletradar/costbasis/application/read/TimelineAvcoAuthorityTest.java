package com.walletradar.costbasis.application.read;

import com.walletradar.costbasis.domain.AssetLedgerPoint;
import com.walletradar.domain.common.NetworkId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TimelineAvcoAuthorityTest {

    @Test
    void prefersSpotWethOverLpReceiptInSameTransaction() {
        AssetLedgerPoint weth = spotPoint(
                "weth-out",
                "WETH",
                AssetLedgerPoint.BasisEffect.REALLOCATE_OUT,
                "-0.5",
                "2722",
                "2722"
        );
        AssetLedgerPoint lpReceipt = spotPoint(
                "lp-in",
                "LP-RECEIPT:arbitrum:pancakeswap:196975",
                AssetLedgerPoint.BasisEffect.REALLOCATE_IN,
                "513.47",
                "2.30",
                "2.30"
        );

        TimelineAvcoAuthority.Resolution resolution = TimelineAvcoAuthority.resolve(
                "FAMILY:ETH",
                List.of(lpReceipt, weth),
                new BigDecimal("2500")
        );

        assertThat(resolution.avcoKind()).isEqualTo(TimelineAvcoAuthority.KIND_PRIMARY_FLOW);
        assertThat(resolution.avcoAfterUsd()).isEqualByComparingTo("2722");
    }

    @Test
    void rejectsCmethOutlierFromSpotTimeline() {
        AssetLedgerPoint eth = spotPoint(
                "eth-buy",
                "ETH",
                AssetLedgerPoint.BasisEffect.ACQUIRE,
                "1",
                "2100",
                "2100"
        );
        AssetLedgerPoint cmeth = spotPoint(
                "cmeth-carry",
                "CMETH",
                AssetLedgerPoint.BasisEffect.CARRY_IN,
                "0.14341964",
                "783705",
                "783705"
        );
        cmeth.setBasisBackedQuantityAfter(new BigDecimal("0.0005"));
        cmeth.setUncoveredQuantityAfter(new BigDecimal("0.14291964"));

        TimelineAvcoAuthority.Resolution resolution = TimelineAvcoAuthority.resolve(
                "FAMILY:ETH",
                List.of(eth, cmeth),
                new BigDecimal("2100")
        );

        assertThat(resolution.avcoKind()).isEqualTo(TimelineAvcoAuthority.KIND_PRIMARY_FLOW);
        assertThat(resolution.avcoAfterUsd()).isEqualByComparingTo("2100");
    }

    @Test
    void carriesForwardAvcoWhenFamilyCoveredQuantityUnchanged() {
        Map<String, BigDecimal> series = TimelineAvcoAuthority.newSeriesTracker();
        TimelineAvcoAuthority.updateSeries(
                series,
                new TimelineAvcoAuthority.Resolution(new BigDecimal("2873"), TimelineAvcoAuthority.KIND_PRIMARY_FLOW, "SYMBOL:ETH")
        );
        AssetLedgerPoint earnDrain = spotPoint(
                "earn-drain",
                "ETH",
                AssetLedgerPoint.BasisEffect.REALLOCATE_OUT,
                "-0.151",
                "0",
                null
        );
        earnDrain.setWalletAddress("BYBIT:33625378:EARN");
        earnDrain.setAccountingAssetIdentity("SYMBOL:ETH");
        earnDrain.setBasisBackedQuantityAfter(BigDecimal.ZERO);
        earnDrain.setUncoveredQuantityAfter(BigDecimal.ZERO);
        earnDrain.setQuantityAfter(BigDecimal.ZERO);

        AssetLedgerPoint fundRestore = spotPoint(
                "fund-restore",
                "ETH",
                AssetLedgerPoint.BasisEffect.REALLOCATE_IN,
                "0.151",
                "434",
                null
        );
        fundRestore.setWalletAddress("BYBIT:33625378:FUND");
        fundRestore.setAccountingAssetIdentity("SYMBOL:ETH");
        fundRestore.setBasisBackedQuantityAfter(new BigDecimal("0.151"));
        fundRestore.setUncoveredQuantityAfter(BigDecimal.ZERO);
        fundRestore.setQuantityAfter(new BigDecimal("0.151"));

        TimelineAvcoAuthority.Resolution resolution = TimelineAvcoAuthority.resolve(
                "FAMILY:ETH",
                List.of(earnDrain, fundRestore),
                new BigDecimal("2873"),
                new BigDecimal("1.0"),
                new BigDecimal("1.0"),
                new BigDecimal("2873"),
                series
        );

        assertThat(resolution.avcoKind()).isEqualTo(TimelineAvcoAuthority.KIND_CARRIED_FORWARD);
        assertThat(resolution.avcoAfterUsd()).isEqualByComparingTo("2873");
    }

    @Test
    void carriesForwardAvcoWhenLpEntryFullyLocksSpotEth() {
        Map<String, BigDecimal> series = TimelineAvcoAuthority.newSeriesTracker();
        TimelineAvcoAuthority.updateSeries(
                series,
                new TimelineAvcoAuthority.Resolution(
                        new BigDecimal("2177"),
                        TimelineAvcoAuthority.KIND_PRIMARY_FLOW,
                        "NATIVE:ARBITRUM"
                )
        );

        AssetLedgerPoint lpEntry = spotPoint(
                "lp-entry",
                "WETH",
                AssetLedgerPoint.BasisEffect.REALLOCATE_OUT,
                "-1.0",
                "0",
                null
        );
        lpEntry.setAccountingAssetIdentity("NATIVE:ARBITRUM");
        lpEntry.setBasisBackedQuantityAfter(BigDecimal.ZERO);
        lpEntry.setUncoveredQuantityAfter(BigDecimal.ZERO);
        lpEntry.setQuantityAfter(BigDecimal.ZERO);

        TimelineAvcoAuthority.Resolution resolution = TimelineAvcoAuthority.resolve(
                "FAMILY:ETH",
                List.of(lpEntry),
                new BigDecimal("2177"),
                new BigDecimal("1.0"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                series
        );

        assertThat(resolution.avcoKind()).isEqualTo(TimelineAvcoAuthority.KIND_CARRIED_FORWARD);
        assertThat(resolution.avcoAfterUsd()).isEqualByComparingTo("2177");
        assertThat(resolution.accountingAssetIdentity()).isEqualTo("NATIVE:ARBITRUM");
    }

    @Test
    void carriesForwardAvcoWhenLpEntryDrainsOneIdentityWhileFamilyRetainsBalance() {
        Map<String, BigDecimal> series = TimelineAvcoAuthority.newSeriesTracker();
        TimelineAvcoAuthority.updateSeries(
                series,
                new TimelineAvcoAuthority.Resolution(
                        new BigDecimal("1673"),
                        TimelineAvcoAuthority.KIND_PRIMARY_FLOW,
                        "NATIVE:BASE"
                )
        );

        AssetLedgerPoint lpEntry = spotPoint(
                "lp-entry",
                "ETH",
                AssetLedgerPoint.BasisEffect.REALLOCATE_OUT,
                "-0.047",
                "79",
                null
        );
        lpEntry.setAccountingAssetIdentity("NATIVE:BASE");
        lpEntry.setBasisBackedQuantityAfter(BigDecimal.ZERO);
        lpEntry.setUncoveredQuantityAfter(BigDecimal.ZERO);
        lpEntry.setQuantityAfter(BigDecimal.ZERO);

        TimelineAvcoAuthority.Resolution resolution = TimelineAvcoAuthority.resolve(
                "FAMILY:ETH",
                List.of(lpEntry),
                new BigDecimal("1673"),
                new BigDecimal("8.5"),
                new BigDecimal("8.45"),
                new BigDecimal("14000"),
                series
        );

        assertThat(resolution.avcoKind()).isEqualTo(TimelineAvcoAuthority.KIND_CARRIED_FORWARD);
        assertThat(resolution.avcoAfterUsd()).isEqualByComparingTo("1673");
        assertThat(resolution.accountingAssetIdentity()).isEqualTo("NATIVE:BASE");
    }

    @Test
    void allGasOnlyEventUsesCarryForwardInsteadOfDilutedPerWalletAvco() {
        // SPONSORED_GAS_IN adds free ETH to the covered pool at $0 cost, diluting per-wallet AVCO.
        // The timeline should carry forward the last known AVCO instead of showing the diluted value.
        Map<String, BigDecimal> series = TimelineAvcoAuthority.newSeriesTracker();
        TimelineAvcoAuthority.updateSeries(
                series,
                new TimelineAvcoAuthority.Resolution(new BigDecimal("3420"), TimelineAvcoAuthority.KIND_PRIMARY_FLOW, "NATIVE:ETHEREUM")
        );

        AssetLedgerPoint sponsoredGas = sponsoredGasInPoint(
                "sponsored-gas-1",
                "ETH",
                "0.00018695",   // free ETH received
                "14.31",        // cost basis unchanged
                "2512"          // diluted per-wallet AVCO
        );

        // Aggregated session state: large session, global AVCO ≈ $3200
        TimelineAvcoAuthority.Resolution resolution = TimelineAvcoAuthority.resolve(
                "FAMILY:ETH",
                List.of(sponsoredGas),
                new BigDecimal("2500"),
                new BigDecimal("0.00551"),   // coveredBefore
                new BigDecimal("0.00570"),   // coveredAfter (increased by free ETH)
                new BigDecimal("18.24"),     // totalCostBasisAfter
                series
        );

        // Must NOT return the diluted per-wallet AVCO ($2512).
        // Should carry forward either the series AVCO ($3420) or global session AVCO.
        assertThat(resolution.avcoKind()).isEqualTo(TimelineAvcoAuthority.KIND_CARRIED_FORWARD);
        assertThat(resolution.avcoAfterUsd()).isGreaterThan(new BigDecimal("2512"));
    }

    @Test
    void allGasOnlyEventFallsBackToGlobalAvcoWhenNoSeriesEntry() {
        // Without a series entry, a pure GAS_ONLY event (SPONSORED_GAS_IN, ADMIN_CONFIG, etc.)
        // should use global session AVCO (basis / covered qty) rather than per-wallet diluted AVCO.
        AssetLedgerPoint sponsoredGas = sponsoredGasInPoint(
                "sponsored-gas-tiny",
                "ETH",
                "0.000002",
                "0.04",
                "1087"   // diluted per-wallet AVCO of tiny position
        );

        TimelineAvcoAuthority.Resolution resolution = TimelineAvcoAuthority.resolve(
                "FAMILY:ETH",
                List.of(sponsoredGas),
                new BigDecimal("2500"),
                new BigDecimal("2.5"),
                new BigDecimal("2.500002"),
                new BigDecimal("6000"),     // global basis → global AVCO ≈ $2400
                TimelineAvcoAuthority.newSeriesTracker()
        );

        assertThat(resolution.avcoKind()).isEqualTo(TimelineAvcoAuthority.KIND_CARRIED_FORWARD);
        // Global AVCO ≈ 6000 / 2.500002 ≈ $2399, not $1087
        assertThat(resolution.avcoAfterUsd()).isGreaterThan(new BigDecimal("2000"));
    }

    @Test
    void tracksAvcoBeforePerAccountingAssetIdentity() {
        Map<String, BigDecimal> series = TimelineAvcoAuthority.newSeriesTracker();
        TimelineAvcoAuthority.updateSeries(
                series,
                new TimelineAvcoAuthority.Resolution(new BigDecimal("2000"), TimelineAvcoAuthority.KIND_PRIMARY_FLOW, "NATIVE:ARBITRUM")
        );
        assertThat(TimelineAvcoAuthority.avcoBeforeForSeries(series, "NATIVE:ARBITRUM")).isEqualByComparingTo("2000");
        assertThat(TimelineAvcoAuthority.avcoBeforeForSeries(series, "WETH:0x82af")).isNull();
        TimelineAvcoAuthority.updateSeries(
                series,
                new TimelineAvcoAuthority.Resolution(new BigDecimal("2722"), TimelineAvcoAuthority.KIND_PRIMARY_FLOW, "WETH:0x82af")
        );
        assertThat(TimelineAvcoAuthority.avcoBeforeForSeries(series, "WETH:0x82af")).isEqualByComparingTo("2722");
        assertThat(TimelineAvcoAuthority.avcoBeforeForSeries(series, "NATIVE:ARBITRUM")).isEqualByComparingTo("2000");
    }

    private static AssetLedgerPoint sponsoredGasInPoint(
            String id,
            String symbol,
            String quantityDelta,
            String totalCostBasisAfterUsd,
            String avcoAfterUsd
    ) {
        AssetLedgerPoint point = new AssetLedgerPoint();
        point.setId(id);
        point.setWalletAddress("wallet-a");
        point.setNetworkId(NetworkId.ETHEREUM);
        point.setAccountingFamilyIdentity("FAMILY:ETH");
        point.setAccountingAssetIdentity("NATIVE:ETHEREUM");
        point.setAssetSymbol(symbol);
        point.setNormalizedType("SPONSORED_GAS_IN");
        point.setNormalizedTransactionId("tx-" + id);
        point.setBlockTimestamp(Instant.parse("2026-04-05T10:00:00Z"));
        point.setReplaySequence(1L);
        point.setBasisEffect(AssetLedgerPoint.BasisEffect.GAS_ONLY);
        BigDecimal qty = new BigDecimal(quantityDelta);
        point.setQuantityDelta(qty);
        BigDecimal basisBacked = qty.abs().add(new BigDecimal("0.00551"));
        point.setQuantityAfter(basisBacked);
        point.setBasisBackedQuantityAfter(basisBacked);
        point.setUncoveredQuantityAfter(BigDecimal.ZERO);
        point.setTotalCostBasisAfterUsd(new BigDecimal(totalCostBasisAfterUsd));
        point.setAvcoAfterUsd(new BigDecimal(avcoAfterUsd));
        return point;
    }

    private static AssetLedgerPoint spotPoint(
            String id,
            String symbol,
            AssetLedgerPoint.BasisEffect basisEffect,
            String quantityDelta,
            String totalCostBasisAfterUsd,
            String avcoAfterUsd
    ) {
        AssetLedgerPoint point = new AssetLedgerPoint();
        point.setId(id);
        point.setWalletAddress("wallet-a");
        point.setNetworkId(NetworkId.ARBITRUM);
        point.setAccountingFamilyIdentity("FAMILY:ETH");
        point.setAccountingAssetIdentity("NATIVE:ARBITRUM");
        point.setAssetSymbol(symbol);
        point.setNormalizedTransactionId("tx-" + id);
        point.setBlockTimestamp(Instant.parse("2026-04-05T10:00:00Z"));
        point.setReplaySequence(1L);
        point.setBasisEffect(basisEffect);
        point.setQuantityDelta(new BigDecimal(quantityDelta));
        point.setQuantityAfter(new BigDecimal(quantityDelta).abs());
        point.setTotalCostBasisAfterUsd(new BigDecimal(totalCostBasisAfterUsd));
        point.setAvcoAfterUsd(avcoAfterUsd == null ? null : new BigDecimal(avcoAfterUsd));
        point.setBasisBackedQuantityAfter(new BigDecimal(quantityDelta).abs());
        point.setUncoveredQuantityAfter(BigDecimal.ZERO);
        return point;
    }
}
