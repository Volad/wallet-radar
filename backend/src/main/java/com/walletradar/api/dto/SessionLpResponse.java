package com.walletradar.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record SessionLpResponse(
        String sessionId,
        Summary summary,
        List<Position> positions
) {
    public record Summary(
            BigDecimal activeTvlUsd,
            BigDecimal feesEarnedUsd,
            BigDecimal unclaimedUsd,
            int inRange,
            int outOfRange,
            BigDecimal realizedPnlUsd,
            String activeTvlPrecision,
            String feesEarnedPrecision,
            String unclaimedPrecision,
            String realizedPnlPrecision
    ) {
    }

    public record Position(
            String correlationId,
            String protocol,
            String family,
            String networkId,
            String walletAddress,
            String pair,
            String tokenId,
            String status,
            Boolean staked,
            Token token0,
            Token token1,
            BigDecimal feeTierPct,
            Range range,
            BigDecimal tvlUsd,
            String tvlPrecision,
            BigDecimal costBasisUsd,
            String costBasisPrecision,
            BigDecimal depositedMarketUsd,
            String depositedMarketPrecision,
            Token entryToken0,
            Token entryToken1,
            BigDecimal withdrawnUsd,
            String withdrawnPrecision,
            Fees fees,
            Il il,
            BigDecimal priceAppreciationUsd,
            String priceAppreciationPrecision,
            BigDecimal netPnlUsd,
            String netPnlPrecision,
            BigDecimal accountingUnrealizedUsd,
            String accountingUnrealizedPrecision,
            Apr apr,
            List<EarningDay> earningsDaily,
            List<AprDay> aprDaily,
            List<Txn> txns,
            Instant enteredAt,
            Instant closedAt,
            Instant snapshotAt,
            Boolean snapshotStale,
            String unavailableReason
    ) {
    }

    public record Token(
            String sym,
            String contract,
            BigDecimal qty,
            BigDecimal usd,
            BigDecimal hodlUsd,
            String qtyPrecision,
            String usdPrecision
    ) {
    }

    public record Range(
            BigDecimal priceLow,
            BigDecimal priceHigh,
            BigDecimal priceCurrent,
            Integer tickLower,
            Integer tickUpper,
            Integer currentTick,
            List<LiquidityBin> liquidityBins,
            String precision
    ) {
    }

    public record LiquidityBin(
            int tickLower,
            int tickUpper,
            BigDecimal priceLower,
            BigDecimal priceUpper,
            double liquidityShare
    ) {
    }

    public record Fees(
            BigDecimal claimedUsd,
            BigDecimal unclaimedUsd,
            List<FeeToken> perToken,
            String claimedPrecision,
            String unclaimedPrecision
    ) {
    }

    public record FeeToken(
            String sym,
            BigDecimal qtyUnclaimed,
            BigDecimal usdUnclaimed,
            BigDecimal qtyClaimed,
            BigDecimal usdClaimed
    ) {
    }

    public record Il(BigDecimal pct, BigDecimal usd, String precision) {
    }

    public record Apr(BigDecimal nowPct, BigDecimal avgPct, String precision) {
    }

    public record EarningDay(LocalDate day, BigDecimal earnedUsd, String precision) {
    }

    public record AprDay(LocalDate day, BigDecimal aprPct, String precision) {
    }

    public record Txn(
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
