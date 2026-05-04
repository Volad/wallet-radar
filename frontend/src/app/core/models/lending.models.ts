import { EvmNetworkId } from './wallet-api.models';

export type LendingGroupStatus = 'OPEN' | 'CLOSED';
export type LendingCycleStatus = 'OPEN' | 'CLOSED' | 'AMBIGUOUS_NEEDS_REVIEW';
export type LendingPositionSide = 'SUPPLY' | 'BORROW';
export type LendingHistoryFilter = 'ALL' | 'LENDING_DEPOSIT' | 'LENDING_WITHDRAW' | 'BORROW' | 'REPAY' | 'REWARD_CLAIM' | 'LOOP';
export type LendingPrecision = 'EXACT' | 'ESTIMATED' | 'UNAVAILABLE' | string;

export interface LendingSummary {
  readonly totalSuppliedUsd: number;
  readonly totalBorrowedUsd: number;
  readonly netExposureUsd: number;
  readonly openGroups: number;
  readonly closedGroups: number;
  readonly protocols: number;
}

export interface LendingPosition {
  readonly id: string;
  readonly marketKey: string;
  readonly side: LendingPositionSide;
  readonly assetSymbol: string;
  readonly underlyingSymbol: string;
  readonly assetContract: string | null;
  readonly quantity: number;
  readonly coveredQuantity: number;
  readonly valueUsd: number;
  readonly earnedUsd: number;
  readonly apyPct: number;
  readonly metricStatus: string;
  readonly metricSource: string;
}

export interface LendingHistoryEntry {
  readonly id: string;
  readonly txHash: string | null;
  readonly marketKey: string;
  readonly cycleId: string | null;
  readonly networkId: EvmNetworkId | null;
  readonly walletAddress: string | null;
  readonly blockTimestamp: string | null;
  readonly type: string;
  readonly eventSubtype: string | null;
  readonly displayType: string;
  readonly assetSymbol: string;
  readonly quantity: number;
  readonly valueUsd: number;
  readonly feeUsd: number;
  readonly loopId: string | null;
}

export interface LendingAssetDeltas {
  readonly principalInByAsset: Readonly<Record<string, number>>;
  readonly principalOutByAsset: Readonly<Record<string, number>>;
  readonly principalOutCashByAsset: Readonly<Record<string, number>>;
  readonly internalReceiptMovementByAsset: Readonly<Record<string, number>>;
  readonly borrowedByAsset: Readonly<Record<string, number>>;
  readonly repaidByAsset: Readonly<Record<string, number>>;
  readonly withdrawnByAsset: Readonly<Record<string, number>>;
  readonly rewardByAsset: Readonly<Record<string, number>>;
  readonly feesByAsset: Readonly<Record<string, number>>;
  readonly netCashDeltaByAsset: Readonly<Record<string, number>>;
}

export interface LendingPnl {
  readonly valueUsd: number | null;
  readonly precision: LendingPrecision;
  readonly method: string;
}

export interface LendingPnlBreakdown {
  readonly interestEarnedUsd: number | null;
  readonly interestPaidUsd: number | null;
  readonly gasUsd: number;
  readonly netPnlUsd: number | null;
  readonly precision: LendingPrecision;
  readonly method: string;
  readonly reason: string | null;
}

export interface LendingPnlAssetBreakdown {
  readonly supplyIncomeByAsset: Readonly<Record<string, number>>;
  readonly borrowCostByAsset: Readonly<Record<string, number>>;
  readonly rewardsByAsset: Readonly<Record<string, number>>;
  readonly gasByAsset: Readonly<Record<string, number>>;
  readonly netIncomeByAsset: Readonly<Record<string, number>>;
  readonly precisionByAsset: Readonly<Record<string, LendingPrecision>>;
  readonly reasonByAsset: Readonly<Record<string, string>>;
}

export interface LendingTotalValuation {
  readonly principalInUsd: number;
  readonly principalOutUsd: number;
  readonly borrowedUsd: number;
  readonly repaidUsd: number;
  readonly rewardsUsd: number;
  readonly feesUsd: number;
  readonly gasUsd: number;
  readonly totalUsdPnl: number | null;
  readonly currentUsdValue: number | null;
  readonly unrealizedTotalUsdPnl: number | null;
  readonly totalUsdPnlPrecision: LendingPrecision;
  readonly yieldOnlyPnl: number | null;
  readonly yieldOnlyPnlPrecision: LendingPrecision;
  readonly valuationMethod: string;
  readonly unavailableReason: string | null;
}

export interface LendingObservedFlow {
  readonly assetSymbol: string;
  readonly assetContract: string | null;
  readonly quantity: number;
  readonly sourceTxHash: string | null;
  readonly sourceKind: string;
  readonly isAuthoritativeForPnl: boolean;
  readonly unavailableReason: string | null;
}

export interface LendingTxItem {
  readonly id: string;
  readonly type: string;
  readonly label: string;
  readonly assetSymbol: string;
  readonly quantity: number;
  readonly valueUsd: number;
  readonly txHash: string | null;
  readonly blockTimestamp: string | null;
}

export interface LendingTxGroup {
  readonly id: string;
  readonly type: 'open' | 'borrow' | 'loop' | 'mid' | 'close' | 'reward' | string;
  readonly timestamp: string | null;
  readonly dateLabel: string | null;
  readonly loopSteps: number | null;
  readonly loopAssetIn: string | null;
  readonly loopAssetOut: string | null;
  readonly items: ReadonlyArray<LendingTxItem>;
}

export interface LendingCycle {
  readonly id: string;
  readonly marketKey: string;
  readonly marketLabel: string;
  readonly status: LendingCycleStatus;
  readonly startTimestamp: string | null;
  readonly closeTimestamp: string | null;
  readonly startTxHash: string | null;
  readonly closeTxHash: string | null;
  readonly statusDetail: string | null;
  readonly warningReason: string | null;
  readonly assetDenominatedPnlByAsset: Readonly<Record<string, number>>;
  readonly assetDenominatedPrecisionByAsset: Readonly<Record<string, LendingPrecision>>;
  readonly assetDenominatedReasonByAsset: Readonly<Record<string, string>>;
  readonly primaryAssetPnlSummary: string | null;
  readonly largePnlReason: string | null;
  readonly largePnlReasons: ReadonlyArray<string>;
  readonly primaryLargePnlReason: string | null;
  readonly assetDeltas: LendingAssetDeltas;
  readonly realizedPnl: LendingPnl;
  readonly unrealizedPnl: LendingPnl;
  readonly pnlBreakdown: LendingPnlBreakdown;
  readonly pnlAssetBreakdown: LendingPnlAssetBreakdown;
  readonly totalValuation: LendingTotalValuation;
  readonly observedFlowsByAsset: Readonly<Record<string, ReadonlyArray<LendingObservedFlow>>>;
  readonly peakSupplyUsd: number;
  readonly peakBorrowUsd: number;
  readonly durationDays: number | null;
  readonly positions: ReadonlyArray<LendingPosition>;
  readonly events: ReadonlyArray<LendingHistoryEntry>;
  readonly txGroups: ReadonlyArray<LendingTxGroup>;
}

export interface LendingGroup {
  readonly id: string;
  readonly protocol: string;
  readonly networkId: EvmNetworkId | null;
  readonly walletAddress: string;
  readonly status: LendingGroupStatus;
  readonly healthFactor: number;
  readonly healthLabel: string;
  readonly healthProgress: number;
  readonly healthStatus: string;
  readonly healthSource: string;
  readonly supplyUsd: number;
  readonly borrowUsd: number;
  readonly netExposureUsd: number;
  readonly positions: ReadonlyArray<LendingPosition>;
  readonly cycles: ReadonlyArray<LendingCycle>;
  readonly history: ReadonlyArray<LendingHistoryEntry>;
}

export interface LendingData {
  readonly sessionId: string;
  readonly summary: LendingSummary;
  readonly groups: ReadonlyArray<LendingGroup>;
}

export type LendingViewState =
  | { readonly status: 'idle' }
  | { readonly status: 'loading' }
  | { readonly status: 'error'; readonly message: string }
  | { readonly status: 'success'; readonly data: LendingData };
