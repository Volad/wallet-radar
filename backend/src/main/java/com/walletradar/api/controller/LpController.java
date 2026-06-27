package com.walletradar.api.controller;

import com.walletradar.api.dto.SessionLpResponse;
import com.walletradar.liquiditypools.application.LpFieldPrecision;
import com.walletradar.liquiditypools.application.LpPositionRefreshService;
import com.walletradar.liquiditypools.application.LpPositionScope;
import com.walletradar.liquiditypools.application.LpPositionView;
import com.walletradar.liquiditypools.application.SessionLpQueryService;
import com.walletradar.liquiditypools.application.SessionLpView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class LpController {

    private final SessionLpQueryService sessionLpQueryService;
    private final LpPositionRefreshService refreshService;

    @GetMapping("/{sessionId}/lp")
    public SessionLpResponse getSessionLp(
            @PathVariable String sessionId,
            @RequestParam(name = "scope", defaultValue = "active") String scope
    ) {
        String normalized = normalizedSessionIdOrThrow(sessionId);
        return sessionLpQueryService.findSessionLp(normalized, LpPositionScope.fromQuery(scope))
                .map(this::toResponse)
                .orElseThrow(() -> new ApiNotFoundException("SESSION_NOT_FOUND", "Session not found"));
    }

    @PostMapping("/{sessionId}/lp/positions/{correlationId}/refresh")
    public SessionLpResponse.Position refreshPosition(
            @PathVariable String sessionId,
            @PathVariable String correlationId
    ) {
        String normalized = normalizedSessionIdOrThrow(sessionId);
        if (!sessionLpQueryService.ownsCorrelationId(normalized, correlationId)) {
            throw new ApiNotFoundException("LP_POSITION_NOT_FOUND", "LP position not found for session");
        }
        refreshService.refreshOnDemand(normalized, correlationId);
        // Use ALL scope so closed positions are included in the lookup
        return sessionLpQueryService.findSessionLp(normalized, LpPositionScope.ALL)
                .flatMap(view -> view.positions().stream()
                        .filter(p -> correlationId.equals(p.correlationId()))
                        .findFirst()
                        .map(this::toPosition))
                .orElseThrow(() -> new ApiNotFoundException("LP_POSITION_NOT_FOUND", "LP position not found for session"));
    }

    private SessionLpResponse toResponse(SessionLpView view) {
        return new SessionLpResponse(
                view.sessionId(),
                new SessionLpResponse.Summary(
                        view.summary().activeTvlUsd(),
                        view.summary().feesEarnedUsd(),
                        view.summary().unclaimedUsd(),
                        view.summary().inRange(),
                        view.summary().outOfRange(),
                        view.summary().realizedPnlUsd(),
                        precision(view.summary().activeTvlPrecision()),
                        precision(view.summary().feesEarnedPrecision()),
                        precision(view.summary().unclaimedPrecision()),
                        precision(view.summary().realizedPnlPrecision())
                ),
                view.positions().stream().map(this::toPosition).toList()
        );
    }

    private SessionLpResponse.Position toPosition(LpPositionView position) {
        return new SessionLpResponse.Position(
                position.correlationId(),
                position.protocol(),
                position.family(),
                position.networkId(),
                position.walletAddress(),
                position.pair(),
                position.tokenId(),
                position.status(),
                position.staked(),
                toToken(position.token0()),
                toToken(position.token1()),
                position.feeTierPct(),
                position.range() == null ? null : new SessionLpResponse.Range(
                        position.range().priceLow(),
                        position.range().priceHigh(),
                        position.range().priceCurrent(),
                        position.range().tickLower(),
                        position.range().tickUpper(),
                        position.range().currentTick(),
                        position.range().liquidityBins().stream()
                                .map(b -> new SessionLpResponse.LiquidityBin(
                                        b.tickLower(), b.tickUpper(),
                                        b.priceLower(), b.priceUpper(),
                                        b.liquidityShare()))
                                .toList(),
                        precision(position.range().precision())
                ),
                position.tvlUsd(),
                precision(position.tvlPrecision()),
                position.costBasisUsd(),
                precision(position.costBasisPrecision()),
                position.depositedMarketUsd(),
                precision(position.depositedMarketPrecision()),
                toToken(position.entryToken0()),
                toToken(position.entryToken1()),
                position.withdrawnUsd(),
                precision(position.withdrawnPrecision()),
                position.fees() == null ? null : new SessionLpResponse.Fees(
                        position.fees().claimedUsd(),
                        position.fees().unclaimedUsd(),
                        position.fees().perToken().stream()
                                .map(t -> new SessionLpResponse.FeeToken(
                                        t.sym(), t.qtyUnclaimed(), t.usdUnclaimed(),
                                        t.qtyClaimed(), t.usdClaimed()))
                                .toList(),
                        precision(position.fees().claimedPrecision()),
                        precision(position.fees().unclaimedPrecision())
                ),
                position.il() == null ? null : new SessionLpResponse.Il(
                        position.il().pct(),
                        position.il().usd(),
                        precision(position.il().precision())
                ),
                position.priceAppreciationUsd(),
                precision(position.priceAppreciationPrecision()),
                position.netPnlUsd(),
                precision(position.netPnlPrecision()),
                position.accountingUnrealizedUsd(),
                precision(position.accountingUnrealizedPrecision()),
                position.apr() == null ? null : new SessionLpResponse.Apr(
                        position.apr().nowPct(),
                        position.apr().avgPct(),
                        precision(position.apr().precision())
                ),
                position.earningsDaily().stream()
                        .map(d -> new SessionLpResponse.EarningDay(d.day(), d.earnedUsd(), precision(d.precision())))
                        .toList(),
                position.aprDaily().stream()
                        .map(d -> new SessionLpResponse.AprDay(d.day(), d.aprPct(), precision(d.precision())))
                        .toList(),
                position.txns().stream()
                        .map(t -> new SessionLpResponse.Txn(
                                t.id(), t.txHash(), t.timestamp(), t.type(),
                                t.assetSymbol(), t.quantity(), t.valueUsd(),
                                t.assetSymbol1(), t.quantity1(), t.valueUsd1(),
                                t.totalValueUsd(), t.gasFeeUsd()))
                        .toList(),
                position.enteredAt(),
                position.closedAt(),
                position.snapshotAt(),
                position.snapshotStale(),
                position.unavailableReason()
        );
    }

    private static SessionLpResponse.Token toToken(LpPositionView.TokenView token) {
        if (token == null) {
            return null;
        }
        return new SessionLpResponse.Token(
                token.sym(),
                token.contract(),
                token.qty(),
                token.usd(),
                token.hodlUsd(),
                precision(token.qtyPrecision()),
                precision(token.usdPrecision())
        );
    }

    private static String precision(LpFieldPrecision precision) {
        return precision == null ? LpFieldPrecision.UNAVAILABLE.name() : precision.name();
    }

    private static String normalizedSessionIdOrThrow(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ApiBadRequestException("INVALID_SESSION_ID", "sessionId is required");
        }
        return sessionId.trim();
    }
}
