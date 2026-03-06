export type EvmNetworkId =
  | 'ETHEREUM'
  | 'ARBITRUM'
  | 'OPTIMISM'
  | 'POLYGON'
  | 'BASE'
  | 'BSC'
  | 'AVALANCHE'
  | 'MANTLE'
  | 'LINEA';

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
];
