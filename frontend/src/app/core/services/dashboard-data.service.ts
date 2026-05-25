import { Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { map } from 'rxjs/operators';

import {
  COLORS,
  EMPTY_DASHBOARD_DATA,
  EVM_NETWORK_PRESENTATION_BY_ID,
  SECTIONS,
} from '../data/dashboard.constants';
import {
  DashboardData,
  IssueCode,
  NetworkInfo,
  PortfolioMetric,
  PriceSource,
  WalletInfo,
} from '../models/dashboard.models';
import { EvmNetworkId, SessionDashboardResponse } from '../models/wallet-api.models';
import { WalletApiService } from './wallet-api.service';

@Injectable({ providedIn: 'root' })
export class DashboardDataService {
  constructor(private readonly walletApiService: WalletApiService) {}

  getDashboardData(sessionId: string | null): Observable<DashboardData> {
    if (sessionId === null) {
      return of(EMPTY_DASHBOARD_DATA);
    }
    return this.walletApiService.getSessionDashboard(sessionId).pipe(
      map((response) => this.toDashboardData(response))
    );
  }

  private toDashboardData(response: SessionDashboardResponse): DashboardData {
    const wallets: ReadonlyArray<WalletInfo> = response.wallets.map((wallet) => ({
      id: wallet.address.toLowerCase(),
      label: wallet.label,
      address: wallet.address.toLowerCase(),
      color: wallet.color,
    }));

    const networkIds = new Set<string>();
    response.wallets.forEach((wallet) => {
      wallet.networks.forEach((networkId) => {
        networkIds.add(networkId);
      });
    });
    response.tokenPositions.forEach((position) => {
      networkIds.add(position.networkId);
    });

    const networks: ReadonlyArray<NetworkInfo> = [...networkIds].map((networkId) =>
      this.toNetworkInfo(networkId)
    );

    const metrics: ReadonlyArray<PortfolioMetric> = [
      {
        label: 'Portfolio',
        value: this.formatUsd(response.summary.portfolioValueUsd ?? 0),
        color: COLORS.text,
      },
      {
        label: 'Unrealised',
        value: this.formatPercent(response.summary.totalUnrealizedPnlPct ?? 0),
        color: (response.summary.totalUnrealizedPnlUsd ?? 0) >= 0 ? COLORS.green : COLORS.red,
      },
      {
        label: 'Realised',
        value: this.formatUsd(response.summary.totalRealizedPnlUsd ?? 0),
        color: (response.summary.totalRealizedPnlUsd ?? 0) >= 0 ? COLORS.green : COLORS.red,
      },
      {
        label: 'Net Inflow',
        value: this.formatUsd(response.summary.lifetimeExternalInflowUsd ?? 0),
        color: COLORS.text,
      },
    ];

    return {
      wallets,
      networks,
      sections: SECTIONS,
      metrics,
      backfill: {
        progressPct: 0,
        networksLabel: 'No active backfill',
      },
      tokenPositions: response.tokenPositions.map((position) => ({
        familyIdentity: position.familyIdentity,
        symbol: position.symbol,
        name: position.name,
        quantity: position.quantity,
        coveredQuantity: position.coveredQuantity,
        priceUsd: position.priceUsd,
        marketValueUsd: position.marketValueUsd,
        priceSource: this.toPriceSource(position.priceSource),
        pricedAt: position.pricedAt,
        stalenessSeconds: position.stalenessSeconds,
        isLiveQuote: position.isLiveQuote,
        priceIssue: this.toIssueCode(position.priceIssue),
        avcoUsd: position.avcoUsd,
        unrealizedPnlPct: position.unrealizedPnlPct,
        unrealizedPnlUsd: position.unrealizedPnlUsd,
        realizedPnlUsd: position.realizedPnlUsd,
        networkId: position.networkId,
        walletId: position.walletAddress.toLowerCase(),
        issue: this.toIssueCode(position.issue),
        valuationModel: position.valuationModel,
        valuationUnderlyingSymbol: position.valuationUnderlyingSymbol,
        unsupportedValuationReason: position.unsupportedValuationReason,
      })),
      lpPositions: [],
      lendingPositions: [],
      transactions: [],
    };
  }

  private formatUsd(value: number): string {
    const absolute = Math.abs(value);
    const compact = absolute >= 1000 ? `$${(absolute / 1000).toFixed(1)}k` : `$${absolute.toFixed(2)}`;
    return value < 0 ? `-${compact}` : compact;
  }

  private formatPercent(value: number): string {
    const prefix = value >= 0 ? '+' : '';
    return `${prefix}${value.toFixed(1)}%`;
  }

  private toIssueCode(issue: string | null): IssueCode {
    if (
      issue === 'yield_accrual' ||
      issue === 'coverage_gap' ||
      issue === 'history_flags' ||
      issue === 'missing_replay_point' ||
      issue === 'missing_price' ||
      issue === 'stale_price' ||
      issue === 'historical_price_fallback' ||
      issue === 'unsupported_protocol_valuation'
    ) {
      return issue;
    }
    return null;
  }

  private toPriceSource(priceSource: string | null): PriceSource | null {
    if (
      priceSource === 'STABLECOIN' ||
      priceSource === 'SWAP_DERIVED' ||
      priceSource === 'COINGECKO' ||
      priceSource === 'MANUAL' ||
      priceSource === 'UNKNOWN' ||
      priceSource === 'BYBIT' ||
      priceSource === 'BINANCE' ||
      priceSource === 'ECB' ||
      priceSource === 'EXECUTION' ||
      priceSource === 'WRAPPER' ||
      priceSource === 'AAVE_INDEX_ACCRUING' ||
      priceSource === 'PROTOCOL_SNAPSHOT'
    ) {
      return priceSource;
    }
    return null;
  }

  private toNetworkInfo(networkId: string): NetworkInfo {
    const presentation = EVM_NETWORK_PRESENTATION_BY_ID.get(networkId as EvmNetworkId);
    if (presentation !== undefined) {
      return {
        ...presentation,
        id: networkId,
      };
    }
    return {
      id: networkId,
      icon: '•',
      label: networkId,
      color: COLORS.textSubtle,
    };
  }
}
