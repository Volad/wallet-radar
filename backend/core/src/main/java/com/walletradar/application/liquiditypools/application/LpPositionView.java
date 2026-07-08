package com.walletradar.application.liquiditypools.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record LpPositionView(
        String correlationId,
        String protocol,
        String family,
        String networkId,
        String walletAddress,
        String pair,
        String tokenId,
        String status,
        Boolean staked,
        TokenView token0,
        TokenView token1,
        BigDecimal feeTierPct,
        RangeView range,
        BigDecimal tvlUsd,
        LpFieldPrecision tvlPrecision,
        BigDecimal costBasisUsd,
        LpFieldPrecision costBasisPrecision,
        BigDecimal depositedMarketUsd,
        LpFieldPrecision depositedMarketPrecision,
        TokenView entryToken0,
        TokenView entryToken1,
        BigDecimal withdrawnUsd,
        LpFieldPrecision withdrawnPrecision,
        FeesView fees,
        IlView il,
        BigDecimal priceAppreciationUsd,
        LpFieldPrecision priceAppreciationPrecision,
        BigDecimal netPnlUsd,
        LpFieldPrecision netPnlPrecision,
        BigDecimal accountingUnrealizedUsd,
        LpFieldPrecision accountingUnrealizedPrecision,
        AprView apr,
        List<EarningDayView> earningsDaily,
        List<AprDayView> aprDaily,
        List<TxnView> txns,
        Instant enteredAt,
        Instant closedAt,
        Instant snapshotAt,
        Boolean snapshotStale,
        String unavailableReason
) {
    public record TokenView(
            String sym,
            String contract,
            BigDecimal qty,
            BigDecimal usd,
            BigDecimal hodlUsd,
            LpFieldPrecision qtyPrecision,
            LpFieldPrecision usdPrecision
    ) {
    }

    public record RangeView(
            BigDecimal priceLow,
            BigDecimal priceHigh,
            BigDecimal priceCurrent,
            Integer tickLower,
            Integer tickUpper,
            Integer currentTick,
            List<LiquidityBinView> liquidityBins,
            LpFieldPrecision precision
    ) {
    }

    public record LiquidityBinView(
            int tickLower,
            int tickUpper,
            BigDecimal priceLower,
            BigDecimal priceUpper,
            double liquidityShare
    ) {
    }

    public record FeesView(
            BigDecimal claimedUsd,
            BigDecimal unclaimedUsd,
            List<FeeTokenView> perToken,
            LpFieldPrecision claimedPrecision,
            LpFieldPrecision unclaimedPrecision
    ) {
    }

    public record FeeTokenView(
            String sym,
            BigDecimal qtyUnclaimed,
            BigDecimal usdUnclaimed,
            BigDecimal qtyClaimed,
            BigDecimal usdClaimed
    ) {
    }

    public record IlView(
            BigDecimal pct,
            BigDecimal usd,
            LpFieldPrecision precision
    ) {
    }

    public record AprView(
            BigDecimal nowPct,
            BigDecimal avgPct,
            LpFieldPrecision precision
    ) {
    }

    public record EarningDayView(LocalDate day, BigDecimal earnedUsd, LpFieldPrecision precision) {
    }

    public record AprDayView(LocalDate day, BigDecimal aprPct, LpFieldPrecision precision) {
    }

    public record TxnView(
            String id,
            String txHash,
            Instant timestamp,
            String type,
            String assetSymbol,
            BigDecimal quantity,
            BigDecimal valueUsd,
            String assetSymbol1,
            BigDecimal quantity1,
            BigDecimal valueUsd1,
            BigDecimal totalValueUsd,
            BigDecimal gasFeeUsd
    ) {
    }
}
