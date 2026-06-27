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
  | 'ZKSYNC'
  | 'KATANA'
  | 'PLASMA'
  /** Exchange custody bucket (dashboard / allocation); not an EVM chain. */
  | 'BYBIT';

/** On-chain wallet networks selectable in session settings (EVM chains + non-EVM tracking-only). */
export type OnChainWalletNetworkId = Exclude<EvmNetworkId, 'BYBIT'> | 'SOLANA' | 'TON';

export type SessionBackfillAggregateStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'BLOCKED'
  | 'COMPLETE'
  | 'PARTIAL'
  | 'FAILED';

export interface AddSessionRequestItem {
  readonly address: string;
  readonly label: string;
  readonly color: string;
  readonly networks: ReadonlyArray<OnChainWalletNetworkId>;
}

export interface AddSessionRequest {
  readonly wallets: ReadonlyArray<AddSessionRequestItem>;
  readonly sessionId: string;
}

export interface AddSessionResponse {
  readonly sessionId?: string;
  readonly message?: string;
}

export type SessionRefreshStatus = 'SCHEDULED' | 'UP_TO_DATE';

export interface SessionRefreshResponse {
  readonly sessionId: string;
  readonly status: SessionRefreshStatus;
  readonly scheduledTargets: number;
  readonly skippedTargets: number;
  readonly message: string;
}

export interface SessionWalletResponse {
  readonly address: string;
  readonly label: string;
  readonly color: string;
  readonly networks: ReadonlyArray<OnChainWalletNetworkId>;
}

export interface SessionResponse {
  readonly sessionId: string;
  readonly wallets: ReadonlyArray<SessionWalletResponse>;
}

export interface IntegrationStreamSyncEntry {
  readonly stream: string;
  readonly lastSegmentCompletedAt: string | null;
  readonly newestStoredEventAt: string | null;
}

export interface SessionIntegrationResponse {
  readonly integrationId: string;
  readonly provider: string | null;
  readonly status: string | null;
  readonly displayName: string | null;
  readonly accountRef: string | null;
  readonly maskedKey: string | null;
  readonly readOnly: boolean;
  readonly capabilities: ReadonlyArray<string>;
  readonly lastValidatedAt: string | null;
  readonly lastSyncAt: string | null;
  readonly lastError: string | null;
  readonly totalSegments: number;
  readonly completedSegments: number;
  readonly failedSegments: number;
  readonly progressPct: number;
  /** Bybit: per-API-stream ingestion timestamps (empty for other providers). */
  readonly streamSync?: ReadonlyArray<IntegrationStreamSyncEntry>;
}

/**
 * Cycle/9 S2: owned external-venue counterparty address (Paradex/MEX/etc.).
 * Mirrored into accounting universe so it is excluded from Net Inflow while still carrying AVCO.
 */
export interface SessionExternalVenueEntry {
  readonly address: string;
  readonly provider: string | null;
  readonly label: string | null;
  readonly networks: ReadonlyArray<OnChainWalletNetworkId>;
}

export interface SessionSettingsResponse {
  readonly sessionId: string;
  readonly wallets: ReadonlyArray<SessionWalletResponse>;
  readonly integrations: ReadonlyArray<SessionIntegrationResponse>;
  readonly externalVenues: ReadonlyArray<SessionExternalVenueEntry>;
  readonly hideSmallAssets: boolean | null;
  readonly showReconciliationWarnings: boolean | null;
}

export interface SessionSettingsIntegrationUpdateRequest {
  readonly provider: string;
  readonly displayName: string;
  readonly apiKey: string;
  readonly apiSecret: string;
}

export interface PutSessionSettingsRequest {
  readonly wallets: ReadonlyArray<AddSessionRequestItem>;
  readonly integrations: ReadonlyArray<SessionSettingsIntegrationUpdateRequest>;
  readonly externalVenues: ReadonlyArray<SessionExternalVenueEntry>;
  readonly hideSmallAssets: boolean;
  readonly showReconciliationWarnings: boolean;
}

export interface UpsertBybitIntegrationRequest {
  readonly displayName: string;
  readonly apiKey: string;
  readonly apiSecret: string;
}

export interface UpsertBybitIntegrationResponse {
  readonly integrationId: string;
  readonly provider: string;
  readonly status: string;
  readonly displayName: string;
  readonly accountRef: string;
  readonly maskedKey: string;
  readonly message: string;
}

export interface DeleteIntegrationResponse {
  readonly integrationId: string;
  readonly message: string;
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

export interface SessionPhaseProgressResponse {
  readonly phase: string;
  readonly progressPct: number;
  readonly processedCount: number;
  readonly leftCount: number;
  readonly totalCount: number;
}

export interface SessionBackfillStatusResponse {
  readonly sessionId: string;
  readonly status: SessionBackfillAggregateStatus;
  readonly acquisitionStatus: SessionBackfillAggregateStatus;
  readonly overallProgressPct: number;
  readonly totalTargets: number;
  readonly completedTargets: number;
  readonly pipelineStage?: string | null;
  readonly pipelineStatus?: string | null;
  readonly pipelineMessage?: string | null;
  readonly phaseProgress?: SessionPhaseProgressResponse | null;
  readonly wallets: ReadonlyArray<SessionBackfillWalletStatus>;
}

export type SessionTransactionSourceType = 'CHAIN' | 'MANUAL' | 'OVERRIDE';

export type SessionBridgeStatus = 'BRIDGE_OUT' | 'BRIDGE_IN' | 'MATCHED' | 'REVIEW';
export type SessionTransactionsBridgeFilter = 'ALL' | SessionBridgeStatus;
export type SessionTransactionsSpamFilter = 'HIDE_SPAM' | 'ALL' | 'SPAM_ONLY';

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
  readonly matchedCounterparty: string | null;
  readonly blockTimestamp: string | null;
  readonly type: string | null;
  readonly status: string | null;
  readonly issue: string | null;
  readonly bridgeStatus: SessionBridgeStatus | null;
  readonly realisedPnlUsdTotal: number | null;
  readonly avcoSnapshotVersion: number | null;
  readonly flows: ReadonlyArray<SessionTransactionFlowResponse>;
}

export interface SessionTransactionsResponse {
  readonly sessionId: string;
  readonly offset: number;
  readonly limit: number;
  readonly totalCount: number;
  readonly hasMore: boolean;
  readonly items: ReadonlyArray<SessionTransactionItemResponse>;
}

export interface GetSessionTransactionsRequest {
  readonly limit?: number;
  readonly offset?: number;
  readonly search?: string | null;
  readonly bridgeStatus?: SessionTransactionsBridgeFilter | null;
  readonly spamFilter?: SessionTransactionsSpamFilter | null;
  readonly walletIds?: ReadonlyArray<string>;
  readonly networkIds?: ReadonlyArray<EvmNetworkId>;
}

export interface RebuildSessionTransactionsResponse {
  readonly sessionId: string;
  readonly projectedTransactions: number;
  readonly message: string;
}

export interface SessionAssetLedgerCurrentResponse {
  readonly quantity: number | null;
  readonly coveredQuantity: number | null;
  readonly uncoveredQuantity: number | null;
  readonly totalCostBasisUsd: number | null;
  readonly avcoUsd: number | null;
  readonly realisedPnlUsd: number | null;
  readonly gasPaidUsd: number | null;
}

export interface SessionAssetLedgerTimelineEntryResponse {
  readonly blockTimestamp: string | null;
  readonly txHash: string | null;
  readonly eventGroupId: string | null;
  readonly normalizedTransactionId: string | null;
  readonly normalizedType: string | null;
  readonly protocolName: string | null;
  readonly lifecycleKind: string | null;
  readonly lifecycleStage: string | null;
  readonly basisEffects: ReadonlyArray<string>;
  readonly quantityDelta: number | null;
  readonly costBasisDeltaUsd: number | null;
  readonly realisedPnlDeltaUsd: number | null;
  readonly gasDeltaUsd: number | null;
  readonly quantityAfter: number | null;
  readonly coveredQuantityAfter: number | null;
  readonly uncoveredQuantityAfter: number | null;
  readonly totalCostBasisAfterUsd: number | null;
  readonly avcoAfterUsd: number | null;
  /** PRIMARY_FLOW = venue spot AVCO; FAMILY_ROLLUP = family aggregate (may include LP shares). */
  readonly avcoKind: string | null;
  readonly fromAddress: string | null;
  readonly toAddress: string | null;
  readonly memberNormalizedTransactionIds: ReadonlyArray<string>;
}

export interface SessionAssetLedgerEventFlowResponse {
  readonly role: string | null;
  readonly assetContract: string | null;
  readonly assetSymbol: string | null;
  readonly quantityDelta: number | null;
  readonly unitPriceUsd: number | null;
  readonly valueUsd: number | null;
  readonly priceSource: string | null;
  readonly logIndex: number | null;
}

export interface SessionAssetLedgerEventOverlayResponse {
  readonly eventGroupId: string | null;
  readonly normalizedTransactionId: string | null;
  readonly txHash: string | null;
  readonly blockTimestamp: string | null;
  readonly normalizedType: string | null;
  readonly protocolName: string | null;
  readonly lifecycleKind: string | null;
  readonly walletAddresses: ReadonlyArray<string>;
  readonly networkIds: ReadonlyArray<string>;
  readonly flows: ReadonlyArray<SessionAssetLedgerEventFlowResponse>;
  readonly fromAddress: string | null;
  readonly toAddress: string | null;
  readonly memberNormalizedTransactionIds: ReadonlyArray<string>;
}

export interface SessionAssetLedgerPointResponse {
  readonly walletAddress: string | null;
  readonly networkId: string | null;
  readonly accountingAssetIdentity: string | null;
  readonly accountingFamilyIdentity: string | null;
  readonly familyDisplaySymbol: string | null;
  readonly assetSymbol: string | null;
  readonly assetContract: string | null;
  readonly normalizedTransactionId: string | null;
  readonly txHash: string | null;
  readonly correlationId: string | null;
  readonly lifecycleChainId: string | null;
  readonly matchedCounterparty: string | null;
  readonly blockTimestamp: string | null;
  readonly replaySequence: number | null;
  readonly normalizedType: string | null;
  readonly lifecycleKind: string | null;
  readonly lifecycleStage: string | null;
  readonly basisEffect: string | null;
  readonly protocolName: string | null;
  readonly quantityDelta: number | null;
  readonly costBasisDeltaUsd: number | null;
  readonly realisedPnlDeltaUsd: number | null;
  readonly gasDeltaUsd: number | null;
  readonly quantityBefore: number | null;
  readonly quantityAfter: number | null;
  readonly totalCostBasisBeforeUsd: number | null;
  readonly totalCostBasisAfterUsd: number | null;
  readonly avcoBeforeUsd: number | null;
  readonly avcoAfterUsd: number | null;
  readonly basisBackedQuantityAfter: number | null;
  readonly uncoveredQuantityDelta: number | null;
  readonly quantityShortfallAfter: number | null;
  readonly uncoveredQuantityAfter: number | null;
  readonly hasIncompleteHistoryAfter: boolean | null;
  readonly hasUnresolvedFlagsAfter: boolean | null;
  readonly unresolvedFlagCountAfter: number | null;
}

export interface SessionAssetLedgerResponse {
  readonly sessionId: string;
  readonly familyIdentity: string;
  readonly current: SessionAssetLedgerCurrentResponse;
  readonly timeline: ReadonlyArray<SessionAssetLedgerTimelineEntryResponse>;
  readonly events: ReadonlyArray<SessionAssetLedgerEventOverlayResponse>;
  readonly ledgerPoints: ReadonlyArray<SessionAssetLedgerPointResponse>;
}

export interface SessionDashboardSummaryResponse {
  readonly portfolioValueUsd: number | null;
  readonly totalUnrealizedPnlUsd: number | null;
  readonly totalUnrealizedPnlPct: number | null;
  readonly totalRealizedPnlUsd: number | null;
  readonly netExternalCapitalUsd: number | null;
  readonly lifetimeExternalInflowUsd: number | null;
  readonly markToMarketUsd: number | null;
  readonly expectedPnlUsd: number | null;
  readonly reportedPnlUsd: number | null;
  readonly conservationDeltaUsd: number | null;
  readonly conservationThresholdUsd: number | null;
  readonly conservationBreached: boolean | null;
}

export interface SessionDashboardTokenPositionResponse {
  readonly familyIdentity: string;
  readonly symbol: string;
  readonly name: string;
  readonly quantity: number;
  readonly coveredQuantity: number;
  readonly priceUsd: number;
  readonly marketValueUsd: number;
  readonly priceSource: string | null;
  readonly pricedAt: string | null;
  readonly stalenessSeconds: number | null;
  readonly isLiveQuote: boolean;
  readonly priceIssue: string | null;
  readonly avcoUsd: number;
  readonly unrealizedPnlPct: number;
  readonly unrealizedPnlUsd: number;
  readonly realizedPnlUsd: number;
  readonly networkId: EvmNetworkId;
  readonly walletAddress: string;
  readonly issue: string | null;
  readonly valuationModel: string | null;
  readonly valuationUnderlyingSymbol: string | null;
  readonly unsupportedValuationReason: string | null;
}

export interface SessionDashboardResponse {
  readonly sessionId: string;
  readonly summary: SessionDashboardSummaryResponse;
  readonly wallets: ReadonlyArray<SessionWalletResponse>;
  readonly tokenPositions: ReadonlyArray<SessionDashboardTokenPositionResponse>;
}

export interface SessionLendingSummaryResponse {
  readonly totalSuppliedUsd: number | null;
  readonly totalBorrowedUsd: number | null;
  readonly netExposureUsd: number | null;
  readonly openGroups: number | null;
  readonly closedGroups: number | null;
  readonly protocols: number | null;
}

export interface SessionLendingPositionResponse {
  readonly id: string;
  readonly marketKey: string | null;
  readonly side: 'SUPPLY' | 'BORROW';
  readonly assetSymbol: string;
  readonly underlyingSymbol: string;
  readonly assetContract: string | null;
  readonly quantity: number | null;
  readonly coveredQuantity: number | null;
  readonly valueUsd: number | null;
  readonly earnedUsd: number | null;
  readonly apyPct: number | null;
  readonly metricStatus: string | null;
  readonly metricSource: string | null;
  readonly protocolSupplyApyPct: number | null;
  readonly protocolBorrowApyPct: number | null;
  readonly rewardAprPct: number | null;
  readonly netProtocolApyPct: number | null;
  readonly protocolApyStatus: string | null;
  readonly protocolApySource: string | null;
  readonly protocolApyCapturedAt: string | null;
  readonly protocolApyStale: boolean | null;
  readonly rewardAprStatus: string | null;
  readonly rewardAprUnavailableReason: string | null;
  readonly apyConvention: string | null;
}

export interface SessionLendingHistoryEntryResponse {
  readonly id: string;
  readonly txHash: string | null;
  readonly marketKey: string | null;
  readonly cycleId: string | null;
  readonly networkId: EvmNetworkId | null;
  readonly walletAddress: string | null;
  readonly blockTimestamp: string | null;
  readonly type: string;
  readonly eventSubtype: string | null;
  readonly displayType: string;
  readonly assetSymbol: string;
  readonly quantity: number | null;
  readonly valueUsd: number | null;
  readonly feeUsd: number | null;
  readonly loopId: string | null;
}

export interface SessionLendingAssetDeltasResponse {
  readonly principalInByAsset: Readonly<Record<string, number | null>>;
  readonly principalOutByAsset: Readonly<Record<string, number | null>>;
  readonly principalOutCashByAsset: Readonly<Record<string, number | null>> | null;
  readonly internalReceiptMovementByAsset: Readonly<Record<string, number | null>> | null;
  readonly borrowedByAsset: Readonly<Record<string, number | null>>;
  readonly repaidByAsset: Readonly<Record<string, number | null>>;
  readonly withdrawnByAsset: Readonly<Record<string, number | null>>;
  readonly rewardByAsset: Readonly<Record<string, number | null>>;
  readonly feesByAsset: Readonly<Record<string, number | null>>;
  readonly netCashDeltaByAsset: Readonly<Record<string, number | null>>;
}

export interface SessionLendingPnlResponse {
  readonly valueUsd: number | null;
  readonly precision: string | null;
  readonly method: string | null;
}

export interface SessionLendingPnlBreakdownResponse {
  readonly interestEarnedUsd: number | null;
  readonly interestPaidUsd: number | null;
  readonly gasUsd: number | null;
  readonly netPnlUsd: number | null;
  readonly precision: string | null;
  readonly method: string | null;
  readonly reason: string | null;
}

export interface SessionLendingPnlAssetBreakdownResponse {
  readonly supplyIncomeByAsset: Readonly<Record<string, number | null>>;
  readonly borrowCostByAsset: Readonly<Record<string, number | null>>;
  readonly rewardsByAsset: Readonly<Record<string, number | null>>;
  readonly gasByAsset: Readonly<Record<string, number | null>>;
  readonly netIncomeByAsset: Readonly<Record<string, number | null>>;
  readonly precisionByAsset: Readonly<Record<string, string | null>>;
  readonly reasonByAsset: Readonly<Record<string, string | null>>;
  readonly supplyPnlUsdByAsset?: Readonly<Record<string, number | null>> | null;
  readonly borrowPnlUsdByAsset?: Readonly<Record<string, number | null>> | null;
  readonly rewardsUsdByAsset?: Readonly<Record<string, number | null>> | null;
  readonly gasUsdByAsset?: Readonly<Record<string, number | null>> | null;
  readonly netIncomeUsdByAsset?: Readonly<Record<string, number | null>> | null;
  readonly usdPrecisionByAsset?: Readonly<Record<string, string | null>> | null;
}

export interface SessionLendingTotalValuationResponse {
  readonly principalInUsd: number | null;
  readonly principalOutUsd: number | null;
  readonly borrowedUsd: number | null;
  readonly repaidUsd: number | null;
  readonly rewardsUsd: number | null;
  readonly feesUsd: number | null;
  readonly gasUsd: number | null;
  readonly totalUsdPnl: number | null;
  readonly currentUsdValue: number | null;
  readonly unrealizedTotalUsdPnl: number | null;
  readonly totalUsdPnlPrecision: string | null;
  readonly yieldOnlyPnl: number | null;
  readonly yieldOnlyPnlPrecision: string | null;
  readonly valuationMethod: string | null;
  readonly unavailableReason: string | null;
}

export interface SessionLendingFactualApyResponse {
  readonly factualSupplyAprByAsset: Readonly<Record<string, number | null>>;
  readonly factualSupplyApyByAsset: Readonly<Record<string, number | null>>;
  readonly factualBorrowAprByAsset: Readonly<Record<string, number | null>>;
  readonly factualBorrowApyByAsset: Readonly<Record<string, number | null>>;
  readonly netStrategyAprPct: number | null;
  readonly netStrategyApyPct: number | null;
  readonly apyPrecision: string | null;
  readonly apyMethod: string | null;
  readonly apyUnavailableReason: string | null;
  readonly apyConvention: string | null;
}

export interface SessionLendingObservedFlowResponse {
  readonly assetSymbol: string;
  readonly assetContract: string | null;
  readonly quantity: number | null;
  readonly sourceTxHash: string | null;
  readonly sourceKind: string | null;
  readonly isAuthoritativeForPnl: boolean | null;
  readonly unavailableReason: string | null;
}

export interface SessionLendingTxItemResponse {
  readonly id: string;
  readonly type: string;
  readonly label: string;
  readonly assetSymbol: string;
  readonly quantity: number | null;
  readonly valueUsd: number | null;
  readonly txHash: string | null;
  readonly blockTimestamp: string | null;
}

export interface SessionLendingTxGroupResponse {
  readonly id: string;
  readonly type: 'open' | 'borrow' | 'loop' | 'mid' | 'close' | 'reward' | string;
  readonly timestamp: string | null;
  readonly dateLabel: string | null;
  readonly loopSteps: number | null;
  readonly loopAssetIn: string | null;
  readonly loopAssetOut: string | null;
  readonly items: ReadonlyArray<SessionLendingTxItemResponse>;
}

export interface SessionLendingCycleResponse {
  readonly id: string;
  readonly marketKey: string;
  readonly marketLabel: string;
  readonly status: 'OPEN' | 'CLOSED' | 'AMBIGUOUS_NEEDS_REVIEW';
  readonly startTimestamp: string | null;
  readonly closeTimestamp: string | null;
  readonly startTxHash: string | null;
  readonly closeTxHash: string | null;
  readonly statusDetail: string | null;
  readonly warningReason: string | null;
  readonly assetDenominatedPnlByAsset: Readonly<Record<string, number | null>> | null;
  readonly assetDenominatedPrecisionByAsset: Readonly<Record<string, string | null>> | null;
  readonly assetDenominatedReasonByAsset: Readonly<Record<string, string | null>> | null;
  readonly primaryAssetPnlSummary: string | null;
  readonly largePnlReason: string | null;
  readonly largePnlReasons: ReadonlyArray<string> | null;
  readonly primaryLargePnlReason: string | null;
  readonly assetDeltas: SessionLendingAssetDeltasResponse;
  readonly realizedPnl: SessionLendingPnlResponse;
  readonly unrealizedPnl: SessionLendingPnlResponse;
  readonly pnlBreakdown: SessionLendingPnlBreakdownResponse;
  readonly pnlAssetBreakdown: SessionLendingPnlAssetBreakdownResponse;
  readonly factualApy: SessionLendingFactualApyResponse | null;
  readonly totalValuation: SessionLendingTotalValuationResponse | null;
  readonly observedFlowsByAsset: Readonly<Record<string, ReadonlyArray<SessionLendingObservedFlowResponse>>> | null;
  readonly peakSupplyUsd: number | null;
  readonly peakBorrowUsd: number | null;
  readonly durationDays: number | null;
  readonly positions: ReadonlyArray<SessionLendingPositionResponse>;
  readonly events: ReadonlyArray<SessionLendingHistoryEntryResponse>;
  readonly txGroups: ReadonlyArray<SessionLendingTxGroupResponse>;
}

export interface SessionLendingGroupResponse {
  readonly id: string;
  readonly protocol: string;
  readonly networkId: EvmNetworkId | null;
  readonly walletAddress: string;
  readonly status: 'OPEN' | 'CLOSED';
  readonly healthFactor: number | null;
  readonly healthLabel: string | null;
  readonly healthProgress: number | null;
  readonly healthStatus: string | null;
  readonly healthSource: string | null;
  readonly healthStale: boolean | null;
  readonly supplyUsd: number | null;
  readonly borrowUsd: number | null;
  readonly netExposureUsd: number | null;
  readonly positions: ReadonlyArray<SessionLendingPositionResponse>;
  readonly cycles: ReadonlyArray<SessionLendingCycleResponse>;
  readonly history: ReadonlyArray<SessionLendingHistoryEntryResponse>;
}

export interface SessionLendingResponse {
  readonly sessionId: string;
  readonly summary: SessionLendingSummaryResponse;
  readonly groups: ReadonlyArray<SessionLendingGroupResponse>;
}

export interface SessionLpUsdMetricResponse {
  readonly valueUsd: number | null;
  readonly precision: string | null;
}

export interface SessionLpTokenSideResponse {
  readonly symbol?: string | null;
  readonly sym?: string | null;
  readonly quantity?: number | null;
  readonly qty?: number | null;
  readonly valueUsd?: number | null;
  readonly usd?: number | null;
  readonly hodlUsd?: number | null;
}

export interface SessionLpLiquidityBinResponse {
  readonly tickLower: number;
  readonly tickUpper: number;
  readonly priceLower: number | null;
  readonly priceUpper: number | null;
  readonly liquidityShare: number;
}

export interface SessionLpPriceRangeResponse {
  readonly priceLow: number | null;
  readonly priceHigh: number | null;
  readonly priceCurrent: number | null;
  readonly priceUnit?: string | null;
  readonly tickLower?: number | null;
  readonly tickUpper?: number | null;
  readonly currentTick?: number | null;
  readonly liquidityBins?: ReadonlyArray<SessionLpLiquidityBinResponse> | null;
  readonly precision?: string | null;
}

export interface SessionLpFeeTokenResponse {
  readonly sym?: string | null;
  readonly symbol?: string | null;
  readonly qtyUnclaimed?: number | null;
  readonly usdUnclaimed?: number | null;
  readonly qtyClaimed?: number | null;
  readonly usdClaimed?: number | null;
  readonly claimed?: number | null;
  readonly unclaimed?: number | null;
}

export interface SessionLpFeesResponse {
  readonly claimedUsd: number | null;
  readonly unclaimedUsd: number | null;
  readonly precision?: string | null;
  readonly claimedPrecision?: string | null;
  readonly unclaimedPrecision?: string | null;
  readonly perToken?: ReadonlyArray<SessionLpFeeTokenResponse> | Record<string, number> | null;
}

export interface SessionLpIlResponse {
  readonly pct: number | null;
  readonly usd: number | null;
  readonly precision: string | null;
}

export interface SessionLpAprResponse {
  readonly now?: number | null;
  readonly avg?: number | null;
  readonly nowPct?: number | null;
  readonly avgPct?: number | null;
  readonly precision: string | null;
}

export interface SessionLpDailyPointResponse {
  readonly date?: string | null;
  readonly day?: string | null;
  readonly value?: number | null;
  readonly earnedUsd?: number | null;
  readonly aprPct?: number | null;
  readonly precision?: string | null;
}

export interface SessionLpTransactionResponse {
  readonly id: string | null;
  readonly type: string | null;
  readonly label?: string | null;
  readonly detail?: string | null;
  readonly assetSymbol?: string | null;
  readonly quantity?: number | null;
  readonly valueUsd: number | null;
  readonly assetSymbol1?: string | null;
  readonly quantity1?: number | null;
  readonly valueUsd1?: number | null;
  readonly totalValueUsd?: number | null;
  readonly gasFeeUsd?: number | null;
  readonly valueUsdPrecision?: string | null;
  readonly txHash: string | null;
  readonly blockTimestamp?: string | null;
  readonly timestamp?: string | null;
}

export interface SessionLpPositionResponse {
  readonly correlationId: string;
  readonly protocol: string | null;
  readonly family: string | null;
  readonly networkId: EvmNetworkId | null;
  readonly wallet?: string | null;
  readonly walletAddress?: string | null;
  readonly pair: string | null;
  readonly token0: SessionLpTokenSideResponse | null;
  readonly token1: SessionLpTokenSideResponse | null;
  readonly feeTierPct: number | null;
  readonly tokenId: string | null;
  readonly status: 'in_range' | 'out_of_range' | 'closed' | 'unknown' | null;
  readonly staked: boolean | null;
  readonly range: SessionLpPriceRangeResponse | null;
  readonly tvlUsd: SessionLpUsdMetricResponse | number | null;
  readonly tvlPrecision?: string | null;
  readonly costBasisUsd: number | null;
  readonly costBasisPrecision?: string | null;
  readonly depositedMarketUsd?: number | null;
  readonly depositedMarketPrecision?: string | null;
  readonly entryToken0?: SessionLpTokenSideResponse | null;
  readonly entryToken1?: SessionLpTokenSideResponse | null;
  readonly withdrawnUsd: number | null;
  readonly fees: SessionLpFeesResponse | null;
  readonly il: SessionLpIlResponse | null;
  readonly priceAppreciationUsd: number | null;
  readonly priceAppreciationPrecision: string | null;
  readonly netPnlUsd: number | null;
  readonly accountingUnrealizedUsd: number | null;
  readonly apr: SessionLpAprResponse | null;
  readonly earningsDaily: ReadonlyArray<SessionLpDailyPointResponse> | null;
  readonly aprDaily: ReadonlyArray<SessionLpDailyPointResponse> | null;
  readonly txns: ReadonlyArray<SessionLpTransactionResponse> | null;
  readonly enteredAt: string | null;
  readonly closedAt: string | null;
  readonly snapshotAt: string | null;
  readonly snapshotStale: boolean | null;
  readonly trackingStartedAt: string | null;
}

export interface SessionLpSummaryResponse {
  readonly activeTvlUsd: number | null;
  readonly feesEarnedUsd: number | null;
  readonly unclaimedUsd: number | null;
  readonly inRange: number | null;
  readonly outOfRange: number | null;
  readonly realizedPnlUsd: number | null;
}

export interface SessionLpResponse {
  readonly sessionId: string;
  readonly summary: SessionLpSummaryResponse;
  readonly positions: ReadonlyArray<SessionLpPositionResponse>;
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
  'KATANA',
  'PLASMA',
];
