export type WalletId = string;
export type NetworkId = string;

export type TransactionType =
  | 'SWAP'
  | 'WRAP'
  | 'UNWRAP'
  | 'EXTERNAL_INBOUND'
  | 'EXTERNAL_TRANSFER_OUT'
  | 'LP_ENTRY'
  | 'LP_EXIT'
  | 'LP_EXIT_PARTIAL'
  | 'LP_EXIT_FINAL'
  | 'LP_FEE_CLAIM'
  | 'LP_POSITION_STAKE'
  | 'LP_POSITION_UNSTAKE'
  | 'LEND_DEPOSIT'
  | 'LEND_WITHDRAWAL'
  | 'BORROW'
  | 'REPAY'
  | 'REWARD_CLAIM'
  | 'STAKE_DEPOSIT'
  | 'STAKE_WITHDRAWAL'
  | 'APPROVAL'
  | 'UNCLASSIFIED'
  | 'MANUAL_COMPENSATING'
  | 'LP_ADJUST';

export type TransactionStatus = 'CONFIRMED' | 'PENDING_PRICE' | 'NEEDS_REVIEW';
export type FlowRole = 'BUY' | 'SELL' | 'FEE' | 'TRANSFER';
export type PriceSource = 'STABLECOIN' | 'SWAP_DERIVED' | 'COINGECKO' | 'MANUAL' | 'UNKNOWN';
export type BridgeStatus = 'BRIDGE_OUT' | 'BRIDGE_IN' | 'MATCHED' | 'REVIEW';
export type TransactionBridgeFilter = 'ALL' | BridgeStatus;
export type TransactionSpamFilter = 'HIDE_SPAM' | 'ALL' | 'SPAM_ONLY';

export type IssueCode =
  | 'spam'
  | 'missing_price'
  | 'unconfirmed'
  | 'yield_accrual'
  | 'coverage_gap'
  | 'history_flags'
  | 'missing_replay_point'
  | null;

export type DashboardSection = 'tokens' | 'lp' | 'lending' | 'staking';

export interface WalletInfo {
  readonly id: WalletId;
  readonly label: string;
  readonly address: string;
  readonly color: string;
}

export interface NetworkInfo {
  readonly id: NetworkId;
  readonly icon: string;
  readonly label: string;
  readonly color: string;
}

export interface PortfolioMetric {
  readonly label: string;
  readonly value: string;
  readonly color: string;
}

export interface BackfillInfo {
  readonly progressPct: number;
  readonly networksLabel: string;
}

export interface TokenPosition {
  readonly familyIdentity: string;
  readonly symbol: string;
  readonly name: string;
  readonly quantity: number;
  readonly priceUsd: number;
  readonly avcoUsd: number;
  readonly unrealizedPnlPct: number;
  readonly unrealizedPnlUsd: number;
  readonly realizedPnlUsd: number;
  readonly networkId: NetworkId;
  readonly walletId: WalletId;
  readonly issue: IssueCode;
}

export interface LpPosition {
  readonly id: string;
  readonly protocol: string;
  readonly pair: string;
  readonly range: string;
  readonly status: 'open' | 'closed';
  readonly currentValueUsd: number;
  readonly feesUsd: number;
  readonly impermanentLossPct: number;
  readonly impermanentLossUsd: number;
  readonly totalPnlPct: number;
  readonly totalPnlUsd: number;
  readonly enteredDate: string;
  readonly exitedDate?: string;
  readonly networkId: NetworkId;
  readonly walletId: WalletId;
  readonly nftId: string | null;
}

export interface LendingPosition {
  readonly id: string;
  readonly protocol: string;
  readonly type: 'deposit' | 'borrow';
  readonly asset: string;
  readonly quantity: number;
  readonly valueUsd: number;
  readonly apyPct: number;
  readonly earnedUsd?: number;
  readonly interestUsd?: number;
  readonly networkId: NetworkId;
  readonly walletId: WalletId;
}

export interface TransactionFlow {
  readonly role: FlowRole;
  readonly symbol: string;
  readonly quantity: number;
  readonly signedQuantity?: number;
  readonly priceUsd: number | null;
  readonly source: PriceSource;
}

export interface TransactionItem {
  readonly id: string;
  readonly hash: string;
  readonly timestamp: string;
  readonly type: TransactionType;
  readonly symbol: string;
  readonly networkId: NetworkId;
  readonly walletId: WalletId;
  readonly matchedCounterparty?: string | null;
  readonly status: TransactionStatus;
  readonly issue: IssueCode;
  readonly bridgeStatus?: BridgeStatus | null;
  readonly hasOverride: boolean;
  readonly note?: string;
  readonly flows: ReadonlyArray<TransactionFlow>;
}

export interface DashboardData {
  readonly wallets: ReadonlyArray<WalletInfo>;
  readonly networks: ReadonlyArray<NetworkInfo>;
  readonly sections: ReadonlyArray<SectionMeta>;
  readonly metrics: ReadonlyArray<PortfolioMetric>;
  readonly backfill: BackfillInfo;
  readonly tokenPositions: ReadonlyArray<TokenPosition>;
  readonly lpPositions: ReadonlyArray<LpPosition>;
  readonly lendingPositions: ReadonlyArray<LendingPosition>;
  readonly transactions: ReadonlyArray<TransactionItem>;
}

export interface SectionMeta {
  readonly id: DashboardSection;
  readonly icon: string;
  readonly label: string;
  readonly color: string;
  readonly soon: boolean;
}

export interface EditableTransactionFlow {
  readonly role: FlowRole;
  readonly symbol: string;
  readonly quantity: number | null;
  readonly priceUsd: number | null;
  readonly source: PriceSource;
}

export interface EditableTransactionDraft {
  readonly id: string;
  readonly type: TransactionType;
  readonly timestamp: string;
  readonly note: string;
  readonly flows: ReadonlyArray<EditableTransactionFlow>;
}

export type DashboardViewState =
  | { readonly status: 'loading' }
  | { readonly status: 'error'; readonly message: string }
  | { readonly status: 'success'; readonly data: DashboardData };

export const TRANSACTION_TYPES: ReadonlyArray<TransactionType> = [
  'SWAP',
  'WRAP',
  'UNWRAP',
  'EXTERNAL_INBOUND',
  'EXTERNAL_TRANSFER_OUT',
  'LP_ENTRY',
  'LP_EXIT',
  'LP_EXIT_PARTIAL',
  'LP_EXIT_FINAL',
  'LP_FEE_CLAIM',
  'LP_POSITION_STAKE',
  'LP_POSITION_UNSTAKE',
  'LEND_DEPOSIT',
  'LEND_WITHDRAWAL',
  'BORROW',
  'REPAY',
  'REWARD_CLAIM',
  'STAKE_DEPOSIT',
  'STAKE_WITHDRAWAL',
  'APPROVAL',
  'UNCLASSIFIED',
  'MANUAL_COMPENSATING',
  'LP_ADJUST',
];

export const PRICE_SOURCES: ReadonlyArray<PriceSource> = [
  'STABLECOIN',
  'SWAP_DERIVED',
  'COINGECKO',
  'MANUAL',
  'UNKNOWN',
];
