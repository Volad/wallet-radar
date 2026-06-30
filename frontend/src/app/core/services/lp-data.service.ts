import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';

import {
  LpData,
  LpDailyPoint,
  LpFamily,
  LpFeeTokenBreakdown,
  LpFees,
  LpImpermanentLoss,
  LpLiquidityBin,
  LpPosition,
  LpPositionScope,
  LpPositionStatus,
  LpPrecision,
  LpPriceRange,
  LpTokenSide,
  LpTransaction,
  LpTxnLeg,
  LpUsdMetric,
} from '../models/lp.models';
import {
  SessionLpDailyPointResponse,
  SessionLpFeeTokenResponse,
  SessionLpFeesResponse,
  SessionLpIlResponse,
  SessionLpLiquidityBinResponse,
  SessionLpPositionResponse,
  SessionLpPriceRangeResponse,
  SessionLpResponse,
  SessionLpTokenSideResponse,
  SessionLpTransactionResponse,
} from '../models/wallet-api.models';
import { WalletApiService } from './wallet-api.service';

@Injectable({ providedIn: 'root' })
export class LpDataService {
  constructor(private readonly walletApiService: WalletApiService) {}

  getSessionLp(sessionId: string, scope: LpPositionScope = 'active'): Observable<LpData> {
    return this.walletApiService.getSessionLp(sessionId, scope).pipe(
      map((response) => this.toLpData(response))
    );
  }

  getSessionLpPosition(
    sessionId: string,
    correlationId: string,
    scope: LpPositionScope = 'active'
  ): Observable<LpPosition> {
    return this.walletApiService.getSessionLpPosition(sessionId, correlationId, scope).pipe(
      map((response) => this.toPosition(response))
    );
  }

  mapPosition(response: SessionLpPositionResponse): LpPosition {
    return this.toPosition(response);
  }

  private toLpData(response: SessionLpResponse): LpData {
    return {
      sessionId: response.sessionId,
      summary: {
        activeTvlUsd: response.summary.activeTvlUsd ?? 0,
        feesEarnedUsd: response.summary.feesEarnedUsd ?? 0,
        unclaimedUsd: response.summary.unclaimedUsd ?? 0,
        inRange: response.summary.inRange ?? 0,
        outOfRange: response.summary.outOfRange ?? 0,
        realizedPnlUsd: response.summary.realizedPnlUsd ?? null,
      },
      positions: response.positions.map((position) => this.toPosition(position)),
    };
  }

  private toPosition(response: SessionLpPositionResponse): LpPosition {
    const wallet = response.wallet ?? response.walletAddress;
    return {
      correlationId: response.correlationId,
      protocol: response.protocol?.trim() || 'Unknown',
      family: (response.family?.trim() || 'FUNGIBLE') as LpFamily,
      networkId: response.networkId ?? 'ETHEREUM',
      wallet: wallet?.trim().toLowerCase() ?? 'unknown',
      pair: response.pair?.trim() || 'Unknown pair',
      token0: this.toTokenSide(response.token0),
      token1: this.toTokenSide(response.token1),
      feeTierPct: response.feeTierPct ?? null,
      tokenId: response.tokenId ?? null,
      status: this.toStatus(response.status),
      staked: response.staked ?? false,
      range: this.toRange(response.range),
      tvlUsd: this.toUsdMetric(response.tvlUsd, response.tvlPrecision),
      depositedMarketUsd: response.depositedMarketUsd ?? null,
      costBasisUsd: response.costBasisUsd ?? null,
      entryToken0: this.toOptionalTokenSide(response.entryToken0),
      entryToken1: this.toOptionalTokenSide(response.entryToken1),
      withdrawnUsd: response.withdrawnUsd ?? null,
      fees: this.toFees(response.fees),
      il: this.toIl(response.il),
      priceAppreciationUsd: response.priceAppreciationUsd ?? null,
      priceAppreciationPrecision: this.toPrecision(response.priceAppreciationPrecision),
      netPnlUsd: response.netPnlUsd ?? null,
      accountingUnrealizedUsd: response.accountingUnrealizedUsd ?? null,
      apr: {
        now: response.apr?.now ?? response.apr?.nowPct ?? null,
        avg: response.apr?.avg ?? response.apr?.avgPct ?? null,
        precision: this.toPrecision(response.apr?.precision),
      },
      earningsDaily: this.toDailyPoints(response.earningsDaily, 'earnedUsd'),
      aprDaily: this.toDailyPoints(response.aprDaily, 'aprPct'),
      txns: (response.txns ?? []).map((txn) => this.toTransaction(txn)),
      enteredAt: response.enteredAt ?? null,
      closedAt: response.closedAt ?? null,
      snapshotAt: response.snapshotAt ?? null,
      snapshotStale: response.snapshotStale ?? false,
      trackingStartedAt: response.trackingStartedAt ?? null,
    };
  }

  private toOptionalTokenSide(response: SessionLpTokenSideResponse | null | undefined): LpTokenSide | null {
    if (!response) {
      return null;
    }
    const symbol = response.symbol ?? response.sym;
    if (!symbol) {
      return null;
    }
    return this.toTokenSide(response);
  }

  private toTokenSide(response: SessionLpTokenSideResponse | null): LpTokenSide {
    const symbol = response?.symbol ?? response?.sym;
    return {
      symbol: symbol?.trim().toUpperCase() || 'UNKNOWN',
      quantity: response?.quantity ?? response?.qty ?? 0,
      valueUsd: response?.valueUsd ?? response?.usd ?? 0,
      hodlUsd: response?.hodlUsd ?? null,
    };
  }

  private toRange(response: SessionLpPriceRangeResponse | null): LpPriceRange {
    return {
      priceLow: response?.priceLow ?? null,
      priceHigh: response?.priceHigh ?? null,
      priceCurrent: response?.priceCurrent ?? null,
      priceUnit: response?.priceUnit ?? null,
      tickLower: response?.tickLower ?? null,
      tickUpper: response?.tickUpper ?? null,
      currentTick: response?.currentTick ?? null,
      liquidityBins: this.toLiquidityBins(response?.liquidityBins),
    };
  }

  private toLiquidityBins(
    bins: ReadonlyArray<SessionLpLiquidityBinResponse> | null | undefined
  ): ReadonlyArray<LpLiquidityBin> {
    if (!bins || bins.length === 0) return [];
    return bins.map((b) => ({
      tickLower: b.tickLower,
      tickUpper: b.tickUpper,
      priceLower: b.priceLower ?? null,
      priceUpper: b.priceUpper ?? null,
      liquidityShare: b.liquidityShare ?? 0,
    }));
  }

  private toUsdMetric(
    value: SessionLpPositionResponse['tvlUsd'],
    precision?: string | null
  ): LpUsdMetric {
    if (typeof value === 'number') {
      return {
        valueUsd: value,
        precision: this.toPrecision(precision),
      };
    }
    if (value && typeof value === 'object') {
      return {
        valueUsd: value.valueUsd ?? null,
        precision: this.toPrecision(value.precision ?? precision),
      };
    }
    return {
      valueUsd: null,
      precision: this.toPrecision(precision),
    };
  }

  private toFees(response: SessionLpFeesResponse | null): LpFees {
    return {
      claimedUsd: response?.claimedUsd ?? null,
      unclaimedUsd: response?.unclaimedUsd ?? null,
      precision: this.toPrecision(response?.precision ?? response?.claimedPrecision),
      perToken: this.toFeeTokens(response?.perToken),
    };
  }

  private toFeeTokens(
    perToken: SessionLpFeesResponse['perToken'] | Record<string, number> | null | undefined
  ): LpFees['perToken'] {
    if (!perToken) {
      return [];
    }
    if (Array.isArray(perToken)) {
      return perToken.map((token) => this.toFeeToken(token));
    }
    return Object.entries(perToken).map(([symbol, amount]) => ({
      symbol: symbol.trim().toUpperCase(),
      qtyUnclaimed: typeof amount === 'number' ? amount : null,
      usdUnclaimed: null,
      qtyClaimed: null,
      usdClaimed: null,
    }));
  }

  private toFeeToken(response: SessionLpFeeTokenResponse): LpFeeTokenBreakdown {
    const symbol = (response.sym ?? response.symbol ?? 'UNKNOWN').trim().toUpperCase();
    return {
      symbol,
      qtyUnclaimed: response.qtyUnclaimed ?? response.unclaimed ?? null,
      usdUnclaimed: response.usdUnclaimed ?? null,
      qtyClaimed: response.qtyClaimed ?? response.claimed ?? null,
      usdClaimed: response.usdClaimed ?? null,
    };
  }

  private toIl(response: SessionLpIlResponse | null): LpImpermanentLoss {
    return {
      pct: response?.pct ?? null,
      usd: response?.usd ?? null,
      precision: this.toPrecision(response?.precision),
    };
  }

  private toDailyPoints(
    points: ReadonlyArray<SessionLpDailyPointResponse> | null,
    valueKey: 'earnedUsd' | 'aprPct' | 'value'
  ): ReadonlyArray<LpDailyPoint> {
    return (points ?? [])
      .map((point) => {
        const date = point.date ?? point.day;
        const value = point.value ?? point.earnedUsd ?? point.aprPct ?? 0;
        return date ? { date, value: value ?? 0 } : null;
      })
      .filter((point): point is LpDailyPoint => point !== null);
  }

  private toTransaction(response: SessionLpTransactionResponse): LpTransaction {
    const legs: LpTxnLeg[] = [];
    const pushLeg = (symbol: string | null | undefined, quantity: number | null | undefined, valueUsd: number | null | undefined) => {
      const sym = symbol?.trim().toUpperCase();
      if (!sym || quantity == null || quantity === 0) {
        return;
      }
      legs.push({
        symbol: sym,
        quantity,
        valueUsd: valueUsd ?? null,
      });
    };
    pushLeg(response.assetSymbol, response.quantity, response.valueUsd);
    pushLeg(response.assetSymbol1, response.quantity1, response.valueUsd1);

    const totalValueUsd = response.totalValueUsd
      ?? (legs.length > 0
        ? legs.reduce((sum, leg) => sum + (leg.valueUsd ?? 0), 0) || null
        : response.valueUsd);

    return {
      id: response.id?.trim() || crypto.randomUUID(),
      type: response.type?.trim() || 'UNKNOWN',
      label: response.label?.trim() || response.type?.trim() || 'Transaction',
      legs,
      totalValueUsd: totalValueUsd ?? null,
      gasFeeUsd: response.gasFeeUsd ?? null,
      valueUsdPrecision: this.toPrecision(response.valueUsdPrecision),
      txHash: response.txHash ?? null,
      blockTimestamp: response.blockTimestamp ?? response.timestamp ?? null,
    };
  }

  private toStatus(status: SessionLpPositionResponse['status']): LpPositionStatus {
    if (status === 'in_range' || status === 'out_of_range' || status === 'closed' || status === 'unknown') {
      return status;
    }
    return 'unknown';
  }

  private toPrecision(value: string | null | undefined): LpPrecision {
    const normalized = value?.trim().toUpperCase();
    if (normalized === 'ESTIMATE') {
      return 'ESTIMATED';
    }
    if (normalized === 'NOT_APPLICABLE') {
      return 'N/A';
    }
    if (normalized === 'EXACT' || normalized === 'ESTIMATED' || normalized === 'N/A' || normalized === 'UNAVAILABLE') {
      return normalized;
    }
    return normalized && normalized.length > 0 ? (normalized as LpPrecision) : 'UNAVAILABLE';
  }
}
