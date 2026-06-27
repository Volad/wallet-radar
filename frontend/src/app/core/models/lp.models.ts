import { EvmNetworkId } from './wallet-api.models';

export type LpPrecision = 'EXACT' | 'ESTIMATED' | 'N/A' | 'UNAVAILABLE' | string;
export type LpPositionStatus = 'in_range' | 'out_of_range' | 'closed' | 'unknown';
export type LpPositionScope = 'active' | 'closed' | 'all';
export type LpFamily = 'CL_NFT' | 'FUNGIBLE' | 'GMX' | 'PENDLE' | string;

export interface LpUsdMetric {
  readonly valueUsd: number | null;
  readonly precision: LpPrecision;
}

export interface LpTokenSide {
  readonly symbol: string;
  readonly quantity: number;
  readonly valueUsd: number;
  readonly hodlUsd: number | null;
}

export interface LpLiquidityBin {
  readonly tickLower: number;
  readonly tickUpper: number;
  readonly priceLower: number | null;
  readonly priceUpper: number | null;
  readonly liquidityShare: number;
}

export interface LpPriceRange {
  readonly priceLow: number | null;
  readonly priceHigh: number | null;
  readonly priceCurrent: number | null;
  readonly priceUnit: string | null;
  readonly tickLower: number | null;
  readonly tickUpper: number | null;
  readonly currentTick: number | null;
  readonly liquidityBins: ReadonlyArray<LpLiquidityBin>;
}

export interface LpTxnLeg {
  readonly symbol: string;
  readonly quantity: number;
  readonly valueUsd: number | null;
}

export interface LpFeeTokenBreakdown {
  readonly symbol: string;
  readonly qtyUnclaimed: number | null;
  readonly usdUnclaimed: number | null;
  readonly qtyClaimed: number | null;
  readonly usdClaimed: number | null;
}

export interface LpFees {
  readonly claimedUsd: number | null;
  readonly unclaimedUsd: number | null;
  readonly precision: LpPrecision;
  readonly perToken: ReadonlyArray<LpFeeTokenBreakdown>;
}

export interface LpImpermanentLoss {
  readonly pct: number | null;
  readonly usd: number | null;
  readonly precision: LpPrecision;
}

export interface LpAprMetrics {
  readonly now: number | null;
  readonly avg: number | null;
  readonly precision: LpPrecision;
}

export interface LpDailyPoint {
  readonly date: string;
  readonly value: number;
}

export interface LpTransaction {
  readonly id: string;
  readonly type: string;
  readonly label: string;
  readonly legs: ReadonlyArray<LpTxnLeg>;
  readonly totalValueUsd: number | null;
  readonly gasFeeUsd: number | null;
  readonly valueUsdPrecision: LpPrecision;
  readonly txHash: string | null;
  readonly blockTimestamp: string | null;
}

export interface LpPosition {
  readonly correlationId: string;
  readonly protocol: string;
  readonly family: LpFamily;
  readonly networkId: EvmNetworkId;
  readonly wallet: string;
  readonly pair: string;
  readonly token0: LpTokenSide;
  readonly token1: LpTokenSide;
  readonly feeTierPct: number | null;
  readonly tokenId: string | null;
  readonly status: LpPositionStatus;
  readonly staked: boolean;
  readonly range: LpPriceRange;
  readonly tvlUsd: LpUsdMetric;
  readonly depositedMarketUsd: number | null;
  readonly costBasisUsd: number | null;
  readonly entryToken0: LpTokenSide | null;
  readonly entryToken1: LpTokenSide | null;
  readonly withdrawnUsd: number | null;
  readonly fees: LpFees;
  readonly il: LpImpermanentLoss;
  readonly priceAppreciationUsd: number | null;
  readonly priceAppreciationPrecision: LpPrecision;
  readonly netPnlUsd: number | null;
  readonly accountingUnrealizedUsd: number | null;
  readonly apr: LpAprMetrics;
  readonly earningsDaily: ReadonlyArray<LpDailyPoint>;
  readonly aprDaily: ReadonlyArray<LpDailyPoint>;
  readonly txns: ReadonlyArray<LpTransaction>;
  readonly enteredAt: string | null;
  readonly closedAt: string | null;
  readonly snapshotAt: string | null;
  readonly snapshotStale: boolean;
  readonly trackingStartedAt: string | null;
}

export interface LpSummary {
  readonly activeTvlUsd: number;
  readonly feesEarnedUsd: number;
  readonly unclaimedUsd: number;
  readonly inRange: number;
  readonly outOfRange: number;
  readonly realizedPnlUsd: number | null;
}

export interface LpData {
  readonly sessionId: string;
  readonly summary: LpSummary;
  readonly positions: ReadonlyArray<LpPosition>;
}

export type LpViewState =
  | { readonly status: 'idle' }
  | { readonly status: 'loading' }
  | { readonly status: 'error'; readonly message: string }
  | { readonly status: 'success'; readonly data: LpData };
