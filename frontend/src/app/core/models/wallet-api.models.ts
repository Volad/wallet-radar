export type EvmNetworkId =
  | 'ETHEREUM'
  | 'ARBITRUM'
  | 'OPTIMISM'
  | 'POLYGON'
  | 'BASE'
  | 'BSC'
  | 'AVALANCHE'
  | 'MANTLE'
  | 'LINEA'
  | 'UNICHAIN'
  | 'ZKSYNC';

export type SessionBackfillAggregateStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'COMPLETE'
  | 'PARTIAL'
  | 'FAILED';

export interface AddSessionRequestItem {
  readonly address: string;
  readonly label: string;
  readonly color: string;
  readonly networks: ReadonlyArray<EvmNetworkId>;
}

export interface AddSessionRequest {
  readonly wallets: ReadonlyArray<AddSessionRequestItem>;
  readonly sessionId: string;
}

export interface AddSessionResponse {
  readonly sessionId?: string;
  readonly message?: string;
}

export interface SessionWalletResponse {
  readonly address: string;
  readonly label: string;
  readonly color: string;
  readonly networks: ReadonlyArray<EvmNetworkId>;
}

export interface SessionResponse {
  readonly sessionId: string;
  readonly wallets: ReadonlyArray<SessionWalletResponse>;
}

export interface SessionBackfillNetworkStatus {
  readonly networkId: EvmNetworkId;
  readonly status: string;
  readonly progressPct: number;
  readonly lastBlockSynced: number | null;
  readonly backfillComplete: boolean;
  readonly syncBannerMessage: string | null;
}

export interface SessionBackfillWalletStatus {
  readonly address: string;
  readonly label: string;
  readonly color: string;
  readonly networks: ReadonlyArray<SessionBackfillNetworkStatus>;
}

export interface SessionBackfillStatusResponse {
  readonly sessionId: string;
  readonly status: SessionBackfillAggregateStatus;
  readonly overallProgressPct: number;
  readonly totalTargets: number;
  readonly completedTargets: number;
  readonly wallets: ReadonlyArray<SessionBackfillWalletStatus>;
}

export type SessionTransactionSourceType = 'CHAIN' | 'MANUAL' | 'OVERRIDE';

export type SessionBridgeStatus = 'BRIDGE_OUT' | 'BRIDGE_IN' | 'MATCHED' | 'REVIEW';

export interface SessionTransactionFlowResponse {
  readonly role: string | null;
  readonly assetContract: string | null;
  readonly assetSymbol: string | null;
  readonly quantityDelta: number | null;
  readonly unitPriceUsd: number | null;
  readonly valueUsd: number | null;
  readonly priceSource: string | null;
  readonly logIndex: number | null;
}

export interface SessionTransactionItemResponse {
  readonly id: string;
  readonly sourceType: SessionTransactionSourceType | null;
  readonly txHash: string | null;
  readonly networkId: EvmNetworkId | null;
  readonly walletAddress: string | null;
  readonly blockTimestamp: string | null;
  readonly type: string | null;
  readonly bridgeStatus: SessionBridgeStatus | null;
  readonly realisedPnlUsdTotal: number | null;
  readonly avcoSnapshotVersion: number | null;
  readonly flows: ReadonlyArray<SessionTransactionFlowResponse>;
}

export interface SessionTransactionsResponse {
  readonly sessionId: string;
  readonly items: ReadonlyArray<SessionTransactionItemResponse>;
}

export interface RebuildSessionTransactionsResponse {
  readonly sessionId: string;
  readonly projectedTransactions: number;
  readonly message: string;
}

// Backward-compatible alias used across current component code.
export type WalletAddRequestItem = AddSessionRequestItem;

// Backward-compatible alias used across current component code.
export type WalletAddRequest = AddSessionRequest;

// Backward-compatible alias used across current component code.
export type WalletAddResponse = AddSessionResponse;

export interface SessionPostResult {
  readonly sessionId: string;
  readonly message?: string;
}

export const SUPPORTED_EVM_NETWORKS: ReadonlyArray<EvmNetworkId> = [
  'ETHEREUM',
  'ARBITRUM',
  'OPTIMISM',
  'POLYGON',
  'BASE',
  'BSC',
  'AVALANCHE',
  'MANTLE',
  'LINEA',
  'UNICHAIN',
  'ZKSYNC',
];
