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
