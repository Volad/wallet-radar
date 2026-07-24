import { ChangeDetectionStrategy, Component, DestroyRef, ViewChild, computed, effect, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { FormsModule, FormArray, FormControl, FormGroup } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { takeUntilDestroyed, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { EMPTY, Subscription, catchError, map, of, startWith, switchMap, timer } from 'rxjs';

import {
  COLORS,
  EMPTY_DASHBOARD_DATA,
  EVM_NETWORKS_PRESENTATION,
  EVM_NETWORK_PRESENTATION_BY_ID,
  INTEGRATION_PRESENTATION_BY_PROVIDER,
} from '../../core/data/dashboard.constants';
import {
  ALL_TRANSACTION_CATEGORIES,
  DEFAULT_TRANSACTION_CATEGORIES,
  DashboardSection,
  DashboardViewState,
  FlowRole,
  IntegrationInfo,
  IssueCode,
  NetworkId,
  NetworkInfo,
  PriceSource,
  SectionMeta,
  TokenPosition,
  TransactionCategory,
  TRANSACTION_CATEGORIES_STORAGE_KEY,
  TransactionFlow,
  TransactionItem,
  TransactionStatus,
  TransactionType,
  WalletId,
  WalletInfo,
} from '../../core/models/dashboard.models';
import { DashboardDataService } from '../../core/services/dashboard-data.service';
import {
  AddSessionRequest,
  AddSessionRequestItem,
  EvmNetworkId,
  GetSessionTransactionsRequest,
  SessionBackfillAggregateStatus,
  SessionBackfillStatusResponse,
  SessionBridgeStatus,
  SessionIntegrationResponse,
  SessionRefreshResponse,
  SessionTransactionFlowResponse,
  SessionTransactionItemResponse,
  SUPPORTED_EVM_NETWORKS,
} from '../../core/models/wallet-api.models';
import { isCexAddress, isOnChainAddress, parseSubAccount, parseVenueId } from '../../core/utils/wallet-ref.util';
import { WalletApiService } from '../../core/services/wallet-api.service';
import { SessionStorageService } from '../../core/services/session-storage.service';
import {
  DashboardAddWalletDialogComponent,
  WalletSubmitState,
} from './components/dashboard-add-wallet-dialog/dashboard-add-wallet-dialog.component';
import { DashboardSectionNavComponent } from './components/dashboard-section-nav/dashboard-section-nav.component';
import { DashboardTopbarComponent } from './components/dashboard-topbar/dashboard-topbar.component';
import { DashboardTransactionsPaneComponent } from './components/dashboard-transactions-pane/dashboard-transactions-pane.component';
import { AssetLedgerPageComponent } from '../asset-ledger/asset-ledger-page.component';
import { LendingPageComponent } from '../lending/lending-page.component';
import { LpPageComponent } from '../lp/lp-page.component';
import { SettingsPageComponent } from '../settings/settings-page.component';
import { SmartAmountComponent } from '../../core/components/smart-amount/smart-amount.component';
import { AllocationBreakdownComponent } from './components/allocation-breakdown/allocation-breakdown.component';

type LpTab = 'all' | 'open' | 'closed';
type SessionTransactionsLoadPhase = 'idle' | 'intermediate' | 'final';
type FilterSelectionMode = 'all' | 'custom';
type WalletFormGroup = FormGroup<{
  address: FormControl<string>;
  label: FormControl<string>;
  color: FormControl<string>;
}>;
type WalletDialogFormGroup = FormGroup<{
  wallets: FormArray<WalletFormGroup>;
}>;

type SortColumn = 'asset' | 'qty' | 'net' | 'avgCost' | 'price';

interface TokenFamilyRow {
  readonly familyIdentity: string;
  readonly symbol: string;
  readonly name: string;
  readonly quantity: number;
  readonly coveredQuantity: number;
  readonly priceUsd: number;
  readonly priceSource: PriceSource | null;
  readonly pricedAt: string | null;
  readonly stalenessSeconds: number | null;
  readonly isLiveQuote: boolean;
  readonly priceIssue: IssueCode;
  readonly avcoUsd: number;
  readonly netAvcoUsd: number;
  readonly unrealizedPnlPct: number;
  readonly unrealizedPnlUsd: number;
  readonly realizedPnlUsd: number;
  // ADR-062 break-even (effective-cost) metric — family-level (identical across a family's positions).
  readonly breakEvenUsd: number | null;
  // ADR-062 §5 "Average cost" — family-level weighted market cost basis / ETH-equivalent covered qty
  // (parity with the move-basis header); identical across a family's positions. Null when unusable.
  readonly averageCostUsd: number | null;
  // ADR-062 deviation guard: coveredQuantity / quantity in [0,1]; null when quantity is zero.
  readonly coveredRatio: number | null;
  // ADR-062 deviation guard: true when a $0 break-even is a low-coverage artifact (suppress display).
  readonly breakEvenSuppressed: boolean;
  readonly lockedSurplusUsd: number;
  readonly incomeReceivedUsd: number;
  readonly attributionTargetFamily: string | null;
  readonly issue: IssueCode;
  readonly networkIds: ReadonlyArray<NetworkId>;
  readonly walletIds: ReadonlyArray<WalletId>;
  readonly currentValueUsd: number;
  readonly totalCostBasisUsd: number;
  readonly valuationModel: string | null;
  readonly valuationUnderlyingSymbol: string | null;
  readonly unsupportedValuationReason: string | null;
}

interface AllocationRow {
  readonly id: string;
  readonly label: string;
  readonly icon: string | null;
  readonly color: string;
  readonly valueUsd: number;
  readonly sharePct: number;
}

interface AllocationMoreSummary {
  readonly count: number;
  readonly valueUsd: number;
  readonly sharePct: number;
}

const ALLOCATION_VISIBLE_ROW_COUNT = 4;

const TRANSACTION_TYPES_BY_ID = new Set<TransactionType>([
  'SWAP',
  'WRAP',
  'UNWRAP',
  'GAS_ONLY',
  'EXTERNAL_INBOUND',
  'EXTERNAL_TRANSFER_IN',
  'EXTERNAL_TRANSFER_OUT',
  'FIAT_EXIT',
  'LP_ENTRY',
  'LP_ENTRY_REQUEST',
  'LP_ENTRY_SETTLEMENT',
  'LP_EXIT',
  'LP_EXIT_REQUEST',
  'LP_EXIT_SETTLEMENT',
  'LP_EXIT_PARTIAL',
  'LP_EXIT_FINAL',
  'LP_FEE_CLAIM',
  'LP_POSITION_STAKE',
  'LP_POSITION_UNSTAKE',
  'LEND_DEPOSIT',
  'LEND_WITHDRAWAL',
  'LENDING_DEPOSIT',
  'LENDING_WITHDRAW',
  'LENDING_LOOP_OPEN',
  'LENDING_LOOP_REBALANCE',
  'LENDING_LOOP_DECREASE',
  'LENDING_LOOP_CLOSE',
  'EARN_FLEXIBLE_SAVING',
  'BORROW',
  'REPAY',
  'VAULT_DEPOSIT',
  'VAULT_WITHDRAW',
  'BRIDGE_OUT',
  'BRIDGE_IN',
  'DEX_ORDER_REQUEST',
  'DEX_ORDER_SETTLEMENT',
  'DERIVATIVE_ORDER_REQUEST',
  'DERIVATIVE_ORDER_EXECUTION',
  'DERIVATIVE_ORDER_CANCEL',
  'DERIVATIVE_POSITION_INCREASE',
  'DERIVATIVE_POSITION_DECREASE',
  'PROTOCOL_CUSTODY_DEPOSIT',
  'PROTOCOL_CUSTODY_WITHDRAW',
  'CEX_DERIVATIVE_SETTLEMENT',
  'FEE',
  'NFT_MINT',
  'REWARD_CLAIM',
  'STAKE_DEPOSIT',
  'STAKE_WITHDRAWAL',
  'STAKING_DEPOSIT',
  'STAKING_WITHDRAW_REQUEST',
  'STAKING_WITHDRAW',
  'APPROVAL',
  'APPROVE',
  'ADMIN_CONFIG',
  'SPONSORED_GAS_IN',
  'INTERNAL_TRANSFER',
  'UNKNOWN',
  'UNCLASSIFIED',
  'MANUAL_COMPENSATING',
  'LP_ADJUST',
]);

const TRANSACTION_TYPE_DISPLAY_OVERRIDES: Readonly<Record<string, TransactionType>> = {
  SPONSORED_GAS_IN: 'GAS_ONLY',
};

const FLOW_ROLES = new Set<FlowRole>(['BUY', 'SELL', 'FEE', 'TRANSFER']);
const PRICE_SOURCES = new Set<PriceSource>([
  'STABLECOIN',
  'SWAP_DERIVED',
  'COINGECKO',
  'MANUAL',
  'UNKNOWN',
  'BYBIT',
  'BINANCE',
  'ECB',
  'EXECUTION',
  'WRAPPER',
  'AAVE_INDEX_ACCRUING',
  'PROTOCOL_SNAPSHOT',
]);
const BRIDGE_STATUSES = new Set<SessionBridgeStatus>(['BRIDGE_OUT', 'BRIDGE_IN', 'MATCHED', 'REVIEW']);

@Component({
  selector: 'wr-dashboard',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    DashboardTopbarComponent,
    DashboardSectionNavComponent,
    DashboardAddWalletDialogComponent,
    DashboardTransactionsPaneComponent,
    AssetLedgerPageComponent,
    LendingPageComponent,
    LpPageComponent,
    SettingsPageComponent,
    SmartAmountComponent,
    AllocationBreakdownComponent,
  ],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DashboardComponent {
  private readonly dashboardDataService = inject(DashboardDataService);
  private readonly walletApiService = inject(WalletApiService);
  private readonly sessionStorageService = inject(SessionStorageService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly sanitizer = inject(DomSanitizer);

  @ViewChild(DashboardAddWalletDialogComponent)
  private addWalletDialogComponent?: DashboardAddWalletDialogComponent;

  readonly colors = COLORS;
  readonly lpTabs: ReadonlyArray<LpTab> = ['all', 'open', 'closed'];
  readonly supportedEvmNetworks = SUPPORTED_EVM_NETWORKS;
  readonly evmNetworksPresentation = EVM_NETWORKS_PRESENTATION;

  readonly isAddWalletDialogOpen = signal(false);
  readonly walletSubmitState = signal<WalletSubmitState>('idle');
  readonly walletSubmitMessage = signal<string | null>(null);
  readonly isBackfillVisible = signal(false);
  readonly currentSessionId = signal<string | null>(null);
  readonly dashboardRefreshNonce = signal(0);
  readonly sessionBackfillStatus = signal<SessionBackfillStatusResponse | null>(null);
  readonly isSessionRefreshSubmitting = signal(false);
  readonly sessionRefreshMessage = signal<string | null>(null);
  readonly sessionTransactions = signal<ReadonlyArray<TransactionItem>>([]);
  readonly sessionTransactionsTotalCount = signal(0);
  readonly isSessionTransactionsLoading = signal(false);
  readonly sessionTransactionsError = signal<string | null>(null);
  readonly sessionTransactionsLoadPhase = signal<SessionTransactionsLoadPhase>('idle');
  private pendingTransactionSub: Subscription | null = null;
  readonly transactionSearch = signal('');
  readonly enabledCategories = signal<ReadonlySet<TransactionCategory>>(
    this.loadCategoriesFromStorage()
  );
  readonly transactionPage = signal(0);
  readonly transactionPageSize = 50;
  readonly canOpenAssetLedger = computed(() => this.currentSessionId() !== null);
  readonly isMoveBasisMode = toSignal(
    this.route.data.pipe(map((data) => data['mode'] === 'move-basis')),
    { initialValue: this.route.snapshot.data['mode'] === 'move-basis' }
  );
  readonly moveBasisFamilyIdentity = toSignal(
    this.route.paramMap.pipe(map((params) => params.get('familyIdentity')?.trim() ?? null)),
    { initialValue: this.route.snapshot.paramMap.get('familyIdentity')?.trim() ?? null }
  );
  readonly assetLedgerFamilyIdentity = computed(() => this.moveBasisFamilyIdentity());
  readonly assetLedgerSessionId = computed(() => this.currentSessionId());
  readonly isAssetLedgerMode = computed(
    () => this.isMoveBasisMode() && this.moveBasisFamilyIdentity() !== null
  );
  readonly isSettingsMode = toSignal(
    this.route.data.pipe(map((data) => data['mode'] === 'settings')),
    { initialValue: this.route.snapshot.data['mode'] === 'settings' }
  );
  readonly isLendingMode = toSignal(
    this.route.data.pipe(map((data) => data['mode'] === 'lending')),
    { initialValue: this.route.snapshot.data['mode'] === 'lending' }
  );
  readonly isLpMode = toSignal(
    this.route.data.pipe(map((data) => data['mode'] === 'lp')),
    { initialValue: this.route.snapshot.data['mode'] === 'lp' }
  );

  readonly viewState = toSignal(
    toObservable(
      computed(() => ({
        sessionId: this.currentSessionId(),
        refreshNonce: this.dashboardRefreshNonce(),
      }))
    ).pipe(
      switchMap(({ sessionId }) =>
        this.dashboardDataService.getDashboardData(sessionId).pipe(
          map((data): DashboardViewState => ({ status: 'success', data })),
          startWith<DashboardViewState>({ status: 'loading' }),
          catchError(() =>
            of<DashboardViewState>({ status: 'error', message: 'Unable to load dashboard data.' })
          )
        )
      )
    ),
    { initialValue: { status: 'loading' } }
  );

  readonly section = signal<DashboardSection>('tokens');
  readonly walletFilterMode = signal<FilterSelectionMode>('all');
  readonly selectedWalletIds = signal<ReadonlySet<WalletId>>(new Set<WalletId>());
  readonly integrationFilterMode = signal<FilterSelectionMode>('all');
  readonly selectedIntegrationRefs = signal<ReadonlySet<string>>(new Set<string>());
  readonly networkFilterMode = signal<FilterSelectionMode>('all');
  readonly selectedNetworkIds = signal<ReadonlySet<NetworkId>>(new Set<NetworkId>());
  readonly sessionIntegrations = signal<ReadonlyArray<IntegrationInfo>>([]);
  readonly hideDustAssets = signal(true);
  private static readonly DUST_THRESHOLD_USD = 0.01;
  readonly showReconciliationWarnings = signal(true);
  readonly isFiltersCollapsed = signal(
    typeof window !== 'undefined' && window.innerWidth <= 900
  );
  readonly sortColumn = signal<SortColumn>('net');
  readonly sortDir = signal<'asc' | 'desc'>('desc');
  readonly NETWORK_VISIBLE_LIMIT = 3;
  readonly networksAllocationExpanded = signal(false);
  readonly walletsAllocationExpanded = signal(false);
  readonly lpTab = signal<LpTab>('all');

  readonly data = computed(() => {
    const state = this.viewState();
    if (state.status === 'success') {
      return state.data;
    }
    return EMPTY_DASHBOARD_DATA;
  });

  readonly errorMessage = computed(() => {
    const state = this.viewState();
    return state.status === 'error' ? state.message : 'Unknown error';
  });

  readonly backfillProgressPct = computed(() => {
    const status = this.sessionBackfillStatus();
    return status?.phaseProgress?.progressPct ?? status?.overallProgressPct ?? this.data().backfill.progressPct;
  });

  readonly pipelineStatusLabel = computed(() => {
    const status = this.sessionBackfillStatus();
    if (status === null) {
      return `Backfill ${this.backfillProgressPct()}%`;
    }
    const phase = status.phaseProgress?.phase ?? status.pipelineStage ?? 'BACKFILL';
    // Inter-stage gap: last stage COMPLETE but not the final one — next stage starting
    if (
      status.pipelineStatus === 'COMPLETE' &&
      status.pipelineStage &&
      status.pipelineStage !== 'PORTFOLIO_SNAPSHOT_REFRESH'
    ) {
      return `${this.phaseDisplayLabel(phase)}: preparing next stage…`;
    }
    return `${this.phaseDisplayLabel(phase)}: ${this.backfillProgressPct()}% done`;
  });

  readonly pipelineStatusSubline = computed(() => {
    const status = this.sessionBackfillStatus();
    if (status === null) {
      return this.data().backfill.networksLabel;
    }
    // Inter-stage gap label
    if (
      status.pipelineStatus === 'COMPLETE' &&
      status.pipelineStage &&
      status.pipelineStage !== 'PORTFOLIO_SNAPSHOT_REFRESH'
    ) {
      return 'Starting next pipeline stage…';
    }
    const phaseProgress = status.phaseProgress;
    if (phaseProgress !== null && phaseProgress !== undefined) {
      if (phaseProgress.phase === 'PRICING') {
        return `priced tx: ${phaseProgress.processedCount} · left: ${phaseProgress.leftCount}`;
      }
      if (
        phaseProgress.phase === 'BACKFILL' &&
        phaseProgress.totalCount !== null &&
        phaseProgress.totalCount !== undefined &&
        status.totalTargets !== null &&
        status.totalTargets !== undefined &&
        phaseProgress.totalCount > status.totalTargets
      ) {
        return `segments: ${phaseProgress.processedCount}/${phaseProgress.totalCount} complete · ${phaseProgress.leftCount} pending`;
      }
      return `processed: ${phaseProgress.processedCount} · left: ${phaseProgress.leftCount}`;
    }
    if (status.pipelineMessage !== null && status.pipelineMessage !== undefined && status.pipelineMessage.length > 0) {
      return status.pipelineMessage;
    }
    return `${status.completedTargets}/${status.totalTargets} wallet×network complete`;
  });

  readonly lastSyncedLabel = computed(() => {
    const status = this.sessionBackfillStatus();
    const raw = status?.lastSyncedAt;
    if (!raw) return null;
    const date = new Date(raw);
    const diffMs = Date.now() - date.getTime();
    const diffMin = Math.floor(diffMs / 60_000);
    if (diffMin < 1) return 'Just now';
    if (diffMin < 60) return `${diffMin} min ago`;
    const diffH = Math.floor(diffMin / 60);
    if (diffH < 24) return `${diffH}h ago`;
    return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
  });

  readonly showPipelineProgress = computed(() => {
    return this.sessionBackfillStatus() !== null;
  });

  readonly canRefreshSession = computed(() => {
    const sessionId = this.currentSessionId();
    const status = this.sessionBackfillStatus();
    if (sessionId === null || status === null) {
      return false;
    }
    return this.acquisitionStatus(status) === 'COMPLETE'
      && !this.isPipelineRunning(status)
      && !this.isSessionRefreshSubmitting();
  });

  readonly activeSection = computed((): SectionMeta | null => {
    return this.data().sections.find((sectionMeta) => sectionMeta.id === this.section()) ?? null;
  });

  readonly onChainWallets = computed<ReadonlyArray<WalletInfo>>(() =>
    this.data().wallets.filter((w) => isOnChainAddress(w.address))
  );
  readonly availableWalletIds = computed<ReadonlyArray<WalletId>>(() => this.data().wallets.map((wallet) => wallet.id));
  readonly availableIntegrationRefs = computed<ReadonlyArray<string>>(() =>
    this.sessionIntegrations().map((integration) => integration.accountRef)
  );
  readonly availableNetworkIds = computed<ReadonlyArray<NetworkId>>(() => this.filterNetworks().map((network) => network.id));

  readonly activeFilterCount = computed(() => {
    // Count only on-chain wallets shown as chips — CEX virtual wallets are integration-managed
    const onChainCount = this.onChainWallets().length;
    const selectedOnChainCount = [...this.selectedWalletIds()].filter((id) => isOnChainAddress(id)).length;
    const hiddenWallets = this.walletFilterMode() === 'all'
      ? 0
      : Math.max(0, onChainCount - selectedOnChainCount);
    const hiddenIntegrations = this.integrationFilterMode() === 'all'
      ? 0
      : Math.max(0, this.availableIntegrationRefs().length - this.selectedIntegrationRefs().size);
    const hiddenNetworks = this.networkFilterMode() === 'all'
      ? 0
      : Math.max(0, this.availableNetworkIds().length - this.selectedNetworkIds().size);

    return hiddenWallets + hiddenIntegrations + hiddenNetworks;
  });

  readonly selectedWalletFilter = computed(() =>
    this.walletFilterMode() === 'all'
      ? new Set<WalletId>(this.availableWalletIds())
      : new Set<WalletId>(this.selectedWalletIds())
  );
  readonly selectedIntegrationFilter = computed(() =>
    this.integrationFilterMode() === 'all'
      ? new Set<string>(this.availableIntegrationRefs())
      : new Set<string>(this.selectedIntegrationRefs())
  );
  readonly selectedNetworkFilter = computed(() =>
    this.networkFilterMode() === 'all'
      ? new Set<NetworkId>(this.availableNetworkIds())
      : new Set<NetworkId>(this.selectedNetworkIds())
  );
  readonly sessionWallets = computed<ReadonlyArray<WalletInfo>>(() => {
    const status = this.sessionBackfillStatus();
    if (status === null) {
      return [];
    }
    return status.wallets.map((wallet) => {
      const address = wallet.address.trim();
      const scopeId = address.toLowerCase();
      return {
        id: scopeId,
        label: wallet.label,
        address,
        color: wallet.color,
      };
    });
  });

  readonly transactionPaneIntegrations = computed<ReadonlyArray<WalletInfo>>(() =>
    this.sessionIntegrations().map((integration) => ({
      id: integration.accountRef.toLowerCase(),
      label: integration.label,
      address: integration.accountRef,
      color: integration.color,
    }))
  );

  readonly sessionNetworks = computed<ReadonlyArray<NetworkInfo>>(() => {
    const status = this.sessionBackfillStatus();
    if (status === null) {
      return [];
    }

    const networkIds = new Set<string>();
    status.wallets.forEach((wallet) => {
      wallet.networks.forEach((network) => {
        networkIds.add(network.networkId);
      });
    });

    return [...networkIds].map((networkId) => {
      const presentation = EVM_NETWORK_PRESENTATION_BY_ID.get(networkId as EvmNetworkId);
      if (presentation !== undefined) {
        return {
          id: networkId,
          icon: presentation.icon,
          label: presentation.label,
          color: presentation.color,
        };
      }
      return {
        id: networkId,
        icon: '•',
        label: networkId,
        color: COLORS.textSubtle,
      };
    });
  });

  readonly transactionPaneWallets = computed(() => {
    const sessionWallets = this.sessionWallets();
    const baseWallets = sessionWallets.length > 0 ? sessionWallets : this.data().wallets;
    return this.mergeWalletScopes(baseWallets, this.transactionPaneIntegrations());
  });

  readonly filterIntegrations = computed(() => this.sessionIntegrations());

  readonly transactionPaneNetworks = computed(() => {
    const sessionNetworks = this.sessionNetworks();
    return sessionNetworks.length > 0 ? sessionNetworks : this.data().networks;
  });

  readonly filterNetworks = computed<ReadonlyArray<NetworkInfo>>(() => {
    const fromSession = this.sessionNetworks();
    const fromData = this.data().networks;
    const byId = new Map<NetworkId, NetworkInfo>();
    for (const network of fromSession) {
      byId.set(network.id, network);
    }
    for (const network of fromData) {
      if (!byId.has(network.id)) {
        byId.set(network.id, network);
      }
    }
    let merged = [...byId.values()];
    for (const integration of this.sessionIntegrations()) {
      const venueId = integration.provider.toUpperCase();
      if (!merged.some((network) => network.id === venueId)) {
        const presentation = INTEGRATION_PRESENTATION_BY_PROVIDER.get(venueId);
        merged = [
          ...merged,
          {
            id: venueId,
            icon: presentation?.icon ?? '◈',
            label: presentation?.label ?? integration.label,
            color: presentation?.color ?? COLORS.textSubtle,
          },
        ];
      }
    }
    return merged;
  });

  readonly transactionPaneTransactions = computed(() => {
    if (this.currentSessionId() !== null) {
      return this.sessionTransactions();
    }
    return this.data().transactions;
  });

  readonly transactionPaneEmptyStateMessage = computed(() => {
    if (this.currentSessionId() === null) {
      return 'No transactions match current filters.';
    }
    const sessionTransactionsError = this.sessionTransactionsError();
    if (sessionTransactionsError !== null) {
      return sessionTransactionsError;
    }
    const backfillStatus = this.sessionBackfillStatus();
    if (backfillStatus !== null && !this.isTerminalBackfillStatus(backfillStatus.status)) {
      return 'Backfill is still running. Session transactions will appear after projection rebuild.';
    }
    return 'No session transactions match current filters.';
  });

  readonly filteredTokenPositions = computed(() => {
    const selectedWallets = this.selectedWalletFilter();
    const hasCustomIntegrationFilter = this.integrationFilterMode() === 'custom';
    const selectedIntegrations = this.selectedIntegrationFilter();
    const selectedNetworks = this.selectedNetworkFilter();
    const hideDust = this.hideDustAssets();

    return this.data().tokenPositions.filter((asset) => {
      const isOnChain = asset.domain !== 'CEX';
      // Wallet chip filter applies only to on-chain wallets
      if (isOnChain && selectedWallets.size > 0 && !selectedWallets.has(asset.walletId)) {
        return false;
      }
      if (selectedNetworks.size > 0 && !isOnChain && !selectedNetworks.has(asset.networkId)) {
        return false;
      }
      // Integration filter applies only to CEX/virtual wallets (non-0x); on-chain wallets are unaffected
      if (hasCustomIntegrationFilter && !isOnChain) {
        const walletId = asset.walletId.toLowerCase();
        const matches = [...selectedIntegrations].some((ref) => {
          const normalizedRef = ref.trim().toLowerCase();
          return normalizedRef.length > 0 && (walletId === normalizedRef || walletId.startsWith(`${normalizedRef}:`));
        });
        if (!matches) {
          return false;
        }
      }

      return true;
    });
  });

  readonly filteredTokenFamilies = computed<ReadonlyArray<TokenFamilyRow>>(() => {
    const hideDust = this.hideDustAssets();
    const grouped = new Map<string, {
      familyIdentity: string;
      symbol: string;
      name: string;
      quantity: number;
      coveredQuantity: number;
      currentValueUsd: number;
      totalCostBasisUsd: number;
      totalNetCostBasisUsd: number;
      unrealizedPnlUsd: number;
      realizedPnlUsd: number;
      breakEvenUsd: number | null;
      averageCostUsd: number | null;
      breakEvenSuppressed: boolean;
      lockedSurplusUsd: number;
      incomeReceivedUsd: number;
      attributionTargetFamily: string | null;
      priceSource: PriceSource | null;
      pricedAt: string | null;
      stalenessSeconds: number | null;
      isLiveQuote: boolean;
      priceIssue: IssueCode;
      networkIds: Set<NetworkId>;
      walletIds: Set<WalletId>;
      issue: IssueCode;
      valuationModel: string | null;
      valuationUnderlyingSymbol: string | null;
      unsupportedValuationReason: string | null;
    }>();

    for (const position of this.filteredTokenPositions()) {
      const currentValueUsd = position.marketValueUsd;
      const totalCostBasisUsd = position.coveredQuantity * position.avcoUsd;
      const totalNetCostBasisUsd = position.coveredQuantity * position.netAvcoUsd;
      const existing = grouped.get(position.familyIdentity);
      if (existing === undefined) {
        grouped.set(position.familyIdentity, {
          familyIdentity: position.familyIdentity,
          symbol: position.symbol,
          name: position.name,
          quantity: position.quantity,
          coveredQuantity: position.coveredQuantity,
          currentValueUsd,
          totalCostBasisUsd,
          totalNetCostBasisUsd,
          unrealizedPnlUsd: position.unrealizedPnlUsd,
          realizedPnlUsd: position.realizedPnlUsd,
          breakEvenUsd: position.breakEvenUsd,
          averageCostUsd: position.averageCostUsd,
          breakEvenSuppressed: position.breakEvenSuppressed,
          lockedSurplusUsd: position.lockedSurplusUsd,
          incomeReceivedUsd: position.incomeReceivedUsd,
          attributionTargetFamily: position.attributionTargetFamily,
          priceSource: position.priceSource,
          pricedAt: position.pricedAt,
          stalenessSeconds: position.stalenessSeconds,
          isLiveQuote: position.isLiveQuote,
          priceIssue: position.priceIssue,
          networkIds: new Set([position.networkId]),
          walletIds: new Set([position.walletId]),
          issue: position.issue,
          valuationModel: position.valuationModel,
          valuationUnderlyingSymbol: position.valuationUnderlyingSymbol,
          unsupportedValuationReason: position.unsupportedValuationReason,
        });
        continue;
      }

      existing.quantity += position.quantity;
      existing.coveredQuantity += position.coveredQuantity;
      existing.currentValueUsd += currentValueUsd;
      existing.totalCostBasisUsd += totalCostBasisUsd;
      existing.totalNetCostBasisUsd += totalNetCostBasisUsd;
      existing.unrealizedPnlUsd += position.unrealizedPnlUsd;
      existing.realizedPnlUsd += position.realizedPnlUsd;
      // ADR-062 metrics are family-level (identical across a family's positions); keep the first
      // non-null value so a wallet/network split does not drop the break-even attribution.
      existing.breakEvenUsd = existing.breakEvenUsd ?? position.breakEvenUsd;
      existing.averageCostUsd = existing.averageCostUsd ?? position.averageCostUsd;
      existing.breakEvenSuppressed = existing.breakEvenSuppressed || position.breakEvenSuppressed;
      existing.attributionTargetFamily = existing.attributionTargetFamily ?? position.attributionTargetFamily;
      existing.lockedSurplusUsd = existing.lockedSurplusUsd || position.lockedSurplusUsd;
      existing.incomeReceivedUsd = existing.incomeReceivedUsd || position.incomeReceivedUsd;
      existing.priceSource = this.pickPriceSource(existing, position);
      existing.pricedAt = this.pickLatestPricedAt(existing.pricedAt, position.pricedAt);
      existing.stalenessSeconds = this.pickSmallestStaleness(existing.stalenessSeconds, position.stalenessSeconds);
      existing.isLiveQuote = existing.isLiveQuote || position.isLiveQuote;
      existing.priceIssue = this.mergeIssueCode(existing.priceIssue, position.priceIssue);
      existing.networkIds.add(position.networkId);
      existing.walletIds.add(position.walletId);
      existing.issue = this.mergeIssueCode(existing.issue, position.issue);
      existing.valuationModel = existing.valuationModel ?? position.valuationModel;
      existing.valuationUnderlyingSymbol = existing.valuationUnderlyingSymbol ?? position.valuationUnderlyingSymbol;
      existing.unsupportedValuationReason = existing.unsupportedValuationReason ?? position.unsupportedValuationReason;
    }

    return [...grouped.values()]
      .map((group): TokenFamilyRow => {
        const quantity = group.quantity;
        const coveredQuantity = group.coveredQuantity;
        const priceUsd = quantity === 0 ? 0 : group.currentValueUsd / quantity;
        const avcoUsd = coveredQuantity === 0 ? 0 : group.totalCostBasisUsd / coveredQuantity;
        const netAvcoUsd = coveredQuantity === 0 ? 0 : group.totalNetCostBasisUsd / coveredQuantity;
        const unrealizedPnlPct = group.totalCostBasisUsd === 0 ? 0 : (group.unrealizedPnlUsd / group.totalCostBasisUsd) * 100;
        const coveredRatio = quantity === 0 ? null : coveredQuantity / quantity;
        return {
          familyIdentity: group.familyIdentity,
          symbol: group.symbol,
          name: group.name,
          quantity,
          coveredQuantity: group.coveredQuantity,
          priceUsd,
          priceSource: group.priceSource,
          pricedAt: group.pricedAt,
          stalenessSeconds: group.stalenessSeconds,
          isLiveQuote: group.isLiveQuote,
          priceIssue: group.priceIssue,
          avcoUsd,
          netAvcoUsd,
          unrealizedPnlPct,
          unrealizedPnlUsd: group.unrealizedPnlUsd,
          realizedPnlUsd: group.realizedPnlUsd,
          breakEvenUsd: group.breakEvenUsd,
          averageCostUsd: group.averageCostUsd,
          coveredRatio,
          breakEvenSuppressed: group.breakEvenSuppressed,
          lockedSurplusUsd: group.lockedSurplusUsd,
          incomeReceivedUsd: group.incomeReceivedUsd,
          attributionTargetFamily: group.attributionTargetFamily,
          issue: group.issue,
          networkIds: [...group.networkIds],
          walletIds: [...group.walletIds],
          currentValueUsd: group.currentValueUsd,
          totalCostBasisUsd: group.totalCostBasisUsd,
          valuationModel: group.valuationModel,
          valuationUnderlyingSymbol: group.valuationUnderlyingSymbol,
          unsupportedValuationReason: group.unsupportedValuationReason,
        };
      })
      .filter((family) => {
        // Dust filter: hide families whose NET market value is below $0.01.
        // Applied at family level (aggregated across all wallets/networks for the same symbol).
        if (hideDust && Math.abs(family.currentValueUsd) < DashboardComponent.DUST_THRESHOLD_USD) {
          return false;
        }
        return true;
      })
      .sort((left, right) => {
        const column = this.sortColumn();
        const direction = this.sortDir();
        const multiplier = direction === 'desc' ? -1 : 1;
        let comparison = 0;
        switch (column) {
          case 'asset':
            comparison = left.symbol.localeCompare(right.symbol);
            break;
          case 'qty':
            comparison = left.quantity - right.quantity;
            break;
          case 'net':
            comparison = left.currentValueUsd - right.currentValueUsd;
            break;
          case 'avgCost':
            comparison = this.avgCostDisplay(left) - this.avgCostDisplay(right);
            break;
          case 'price':
            comparison = left.priceUsd - right.priceUsd;
            break;
        }
        return comparison * multiplier;
      });
  });

  readonly filteredLpPositions = computed(() => {
    const selectedWallets = this.selectedWalletFilter();
    const selectedNetworks = this.selectedNetworkFilter();
    const currentTab = this.lpTab();

    return this.data().lpPositions.filter((position) => {
      if (currentTab !== 'all' && position.status !== currentTab) {
        return false;
      }
      if (selectedWallets.size > 0 && !selectedWallets.has(position.walletId)) {
        return false;
      }
      if (selectedNetworks.size > 0 && !selectedNetworks.has(position.networkId)) {
        return false;
      }

      return true;
    });
  });

  readonly filteredLendingPositions = computed(() => {
    const selectedWallets = this.selectedWalletFilter();
    const selectedNetworks = this.selectedNetworkFilter();

    return this.data().lendingPositions.filter((position) => {
      if (selectedWallets.size > 0 && !selectedWallets.has(position.walletId)) {
        return false;
      }
      if (selectedNetworks.size > 0 && !selectedNetworks.has(position.networkId)) {
        return false;
      }

      return true;
    });
  });

  readonly filteredTokenTotalUsd = computed(() => {
    return this.filteredTokenFamilies().reduce((total, token) => total + token.currentValueUsd, 0);
  });

  readonly filteredUnrealizedPnlUsd = computed(() =>
    this.filteredTokenFamilies().reduce((total, token) => total + token.unrealizedPnlUsd, 0)
  );

  readonly filteredRealizedPnlUsd = computed(() =>
    this.filteredTokenFamilies().reduce((total, token) => total + token.realizedPnlUsd, 0)
  );

  readonly totalRealizedPnlUsd = computed(() => this.data().totalRealizedPnlUsd);

  readonly isFiltered = computed(() =>
    this.walletFilterMode() !== 'all' ||
    this.networkFilterMode() !== 'all' ||
    this.integrationFilterMode() !== 'all'
  );

  readonly displayedRealizedPnlUsd = computed(() =>
    this.isFiltered() ? this.filteredRealizedPnlUsd() : this.totalRealizedPnlUsd()
  );

  readonly filteredTotalCostBasisUsd = computed(() =>
    this.filteredTokenFamilies().reduce((total, token) => total + token.totalCostBasisUsd, 0)
  );

  readonly filteredUnrealizedPnlPct = computed(() => {
    const costBasis = this.filteredTotalCostBasisUsd();
    if (costBasis <= 0) {
      return 0;
    }
    return (this.filteredUnrealizedPnlUsd() / costBasis) * 100;
  });

  readonly tokenUsdByNetwork = computed<ReadonlyArray<AllocationRow>>(() => {
    const positions = this.filteredTokenPositions();
    const totals = new Map<NetworkId, number>();
    let totalUsd = 0;
    for (const position of positions) {
      const valueUsd = position.marketValueUsd ?? 0;
      if (!Number.isFinite(valueUsd) || valueUsd <= 0) {
        continue;
      }
      totalUsd += valueUsd;
      totals.set(position.networkId, (totals.get(position.networkId) ?? 0) + valueUsd);
    }
    if (totalUsd <= 0) {
      return [];
    }
    return [...totals.entries()]
      .map(([networkId, valueUsd]): AllocationRow => {
        const network = this.getNetworkById(networkId);
        return {
          id: networkId,
          label: network?.label ?? networkId,
          icon: network?.icon ?? null,
          color: network?.color ?? COLORS.textSubtle,
          valueUsd,
          sharePct: (valueUsd / totalUsd) * 100,
        };
      })
      .sort((left, right) => right.valueUsd - left.valueUsd);
  });

  readonly onChainVsCexSplit = computed(() => {
    const positions = this.filteredTokenPositions();
    let totalUsd = 0;
    let cexUsd = 0;
    for (const position of positions) {
      const valueUsd = position.marketValueUsd ?? 0;
      if (!Number.isFinite(valueUsd) || valueUsd <= 0) {
        continue;
      }
      totalUsd += valueUsd;
      if (position.domain === 'CEX') {
        cexUsd += valueUsd;
      }
    }
    const onChainUsd = totalUsd - cexUsd;
    return {
      onChainPct: totalUsd > 0 ? (onChainUsd / totalUsd) * 100 : 0,
      cexPct: totalUsd > 0 ? (cexUsd / totalUsd) * 100 : 0,
    };
  });

  readonly tokenUsdByWallet = computed<ReadonlyArray<AllocationRow>>(() => {
    const positions = this.filteredTokenPositions();
    const totals = new Map<WalletId, number>();
    let totalUsd = 0;
    for (const position of positions) {
      const valueUsd = position.marketValueUsd ?? 0;
      if (!Number.isFinite(valueUsd) || valueUsd <= 0) {
        continue;
      }
      totalUsd += valueUsd;
      totals.set(position.walletId, (totals.get(position.walletId) ?? 0) + valueUsd);
    }
    if (totalUsd <= 0) {
      return [];
    }
    return [...totals.entries()]
      .map(([walletId, valueUsd]): AllocationRow => {
        return {
          id: walletId,
          label: this.walletLabel(walletId),
          icon: '●',
          color: this.walletColor(walletId),
          valueUsd,
          sharePct: (valueUsd / totalUsd) * 100,
        };
      })
      .sort((left, right) => right.valueUsd - left.valueUsd);
  });

  readonly visibleNetworksAllocation = computed(() => {
    const rows = this.tokenUsdByNetwork();
    return this.networksAllocationExpanded()
      ? rows
      : rows.slice(0, ALLOCATION_VISIBLE_ROW_COUNT);
  });

  readonly visibleWalletsAllocation = computed(() => {
    const rows = this.tokenUsdByWallet();
    return this.walletsAllocationExpanded()
      ? rows
      : rows.slice(0, ALLOCATION_VISIBLE_ROW_COUNT);
  });

  readonly networksAllocationMore = computed(() =>
    this.allocationMoreSummary(this.tokenUsdByNetwork())
  );

  readonly walletsAllocationMore = computed(() =>
    this.allocationMoreSummary(this.tokenUsdByWallet())
  );

  readonly totalOpenLpFeesUsd = computed(() => {
    return this.data()
      .lpPositions.filter((position) => position.status === 'open')
      .reduce((total, position) => total + position.feesUsd, 0);
  });

  readonly lendingSummary = computed(() => {
    const rows = this.filteredLendingPositions();
    const totalSupply = rows
      .filter((position) => position.type === 'deposit')
      .reduce((sum, position) => sum + position.valueUsd, 0);
    const totalBorrow = rows
      .filter((position) => position.type === 'borrow')
      .reduce((sum, position) => sum + position.valueUsd, 0);
    const healthFactor = Number(((totalSupply / Math.max(totalBorrow, 1)) * 0.75).toFixed(2));

    let healthColor: string = COLORS.red;
    if (healthFactor > 2) {
      healthColor = COLORS.green;
    } else if (healthFactor > 1.2) {
      healthColor = COLORS.amber;
    }

    return {
      totalSupply,
      totalBorrow,
      healthFactor,
      healthColor,
      healthProgress: Math.min((healthFactor / 3) * 100, 100),
    };
  });

  private backfillPollingSubscription: Subscription | null = null;

  constructor() {
    this.destroyRef.onDestroy(() => {
      this.stopBackfillPolling();
    });
    this.restoreSessionBackfillIfNeeded();
    effect(() => {
      if (this.isSettingsMode()) {
        return;
      }
      if (this.currentSessionId() !== null) {
        return;
      }
      const storedSessionId = this.sessionStorageService.getSessionId();
      if (storedSessionId && storedSessionId.trim().length > 0) {
        this.currentSessionId.set(storedSessionId.trim());
        return;
      }
      void this.router.navigate(['/settings']);
    });
  }

  get addWalletsForm(): WalletDialogFormGroup {
    if (!this.addWalletDialogComponent) {
      throw new Error('Add wallet dialog is not initialized');
    }
    return this.addWalletDialogComponent.addWalletsForm;
  }

  canSubmitWallets(): boolean {
    return this.addWalletDialogComponent?.canSubmitWallets() ?? false;
  }

  submitWallets(): void {
    this.addWalletDialogComponent?.onSubmit();
  }

  toggleSection(sectionId: DashboardSection): void {
    const sectionMeta = this.data().sections.find((section) => section.id === sectionId);
    if (sectionMeta?.soon) {
      return;
    }
    if (sectionId === 'lending') {
      this.section.set(sectionId);
      if (!this.isLendingMode()) {
        void this.router.navigate(['/lending']);
      }
      return;
    }
    if (sectionId === 'lp') {
      this.section.set(sectionId);
      if (!this.isLpMode()) {
        void this.router.navigate(['/lp']);
      }
      return;
    }
    if (this.isSettingsMode()) {
      this.section.set(sectionId);
      void this.router.navigate(['/']);
      return;
    }
    if (this.isLendingMode()) {
      this.section.set(sectionId);
      void this.router.navigate(['/']);
      return;
    }
    if (this.isLpMode()) {
      this.section.set(sectionId);
      void this.router.navigate(['/']);
      return;
    }
    if (this.isAssetLedgerMode()) {
      this.section.set(sectionId);
      this.closeAssetLedger();
      return;
    }
    this.section.set(sectionId);
  }

  openSettings(): void {
    if (this.isSettingsMode()) {
      return;
    }
    void this.router.navigate(['/settings']);
  }

  toggleWallet(walletId: WalletId): void {
    if (this.walletFilterMode() === 'all') {
      this.walletFilterMode.set('custom');
      // Only track on-chain wallet IDs in the chip filter; bybit virtual wallets bypass wallet filter
      this.selectedWalletIds.set(new Set(this.onChainWallets().map((w) => w.id).filter((id) => id !== walletId)));
    } else {
      this.selectedWalletIds.set(this.toggleSetValue(this.selectedWalletIds(), walletId));
    }
    this.resetTransactionPageAndRefresh();
  }

  toggleIntegration(accountRef: string): void {
    if (this.integrationFilterMode() === 'all') {
      this.integrationFilterMode.set('custom');
      this.selectedIntegrationRefs.set(
        new Set(this.availableIntegrationRefs().filter((ref) => ref !== accountRef))
      );
    } else {
      this.selectedIntegrationRefs.set(this.toggleSetValue(this.selectedIntegrationRefs(), accountRef));
    }
    this.resetTransactionPageAndRefresh();
  }

  toggleNetwork(networkId: NetworkId): void {
    if (this.networkFilterMode() === 'all') {
      this.networkFilterMode.set('custom');
      this.selectedNetworkIds.set(new Set(this.availableNetworkIds().filter((id) => id !== networkId)));
    } else {
      this.selectedNetworkIds.set(this.toggleSetValue(this.selectedNetworkIds(), networkId));
    }
    this.resetTransactionPageAndRefresh();
  }

  clearFilters(): void {
    this.walletFilterMode.set('all');
    this.selectedWalletIds.set(new Set<WalletId>());
    this.integrationFilterMode.set('all');
    this.selectedIntegrationRefs.set(new Set<string>());
    this.networkFilterMode.set('all');
    this.selectedNetworkIds.set(new Set<NetworkId>());
    this.resetTransactionPageAndRefresh();
  }

  toggleFiltersCollapsed(): void {
    this.isFiltersCollapsed.update((collapsed) => !collapsed);
  }

  setSort(column: SortColumn): void {
    if (this.sortColumn() === column) {
      this.sortDir.update((direction) => (direction === 'desc' ? 'asc' : 'desc'));
      return;
    }
    this.sortColumn.set(column);
    this.sortDir.set('desc');
  }

  visibleNetworks(networkIds: ReadonlyArray<NetworkId>): ReadonlyArray<NetworkId> {
    return networkIds.slice(0, this.NETWORK_VISIBLE_LIMIT);
  }

  hiddenNetworkCount(networkIds: ReadonlyArray<NetworkId>): number {
    return Math.max(0, networkIds.length - this.NETWORK_VISIBLE_LIMIT);
  }

  hiddenNetworkTooltip(networkIds: ReadonlyArray<NetworkId>): string {
    return networkIds
      .slice(this.NETWORK_VISIBLE_LIMIT)
      .map((networkId) => this.networkLabel(networkId))
      .join(', ');
  }

  setLpTab(tab: LpTab): void {
    this.lpTab.set(tab);
  }

  setHideDustAssets(value: boolean): void {
    this.hideDustAssets.set(value);
  }

  onTransactionSearchChange(value: string): void {
    this.transactionSearch.set(value);
    this.resetTransactionPageAndRefresh();
  }

  onCategoriesChange(cats: ReadonlySet<TransactionCategory>): void {
    this.enabledCategories.set(cats);
    try {
      localStorage.setItem(TRANSACTION_CATEGORIES_STORAGE_KEY, JSON.stringify([...cats]));
    } catch { /* ignore */ }
    this.resetTransactionPageAndRefresh();
  }

  onTransactionPageChange(value: number): void {
    this.transactionPage.set(Math.max(0, value));
    this.refreshCurrentSessionTransactions(true);
  }

  openAssetLedger(asset: Pick<TokenPosition, 'familyIdentity'>): void {
    if (this.currentSessionId() === null) {
      return;
    }
    void this.router.navigate(['/move-basis', asset.familyIdentity]);
  }

  closeAssetLedger(): void {
    void this.router.navigate(['/']);
  }

  openAddWalletDialog(): void {
    this.resetWalletSubmissionState();
    this.isAddWalletDialogOpen.set(true);
  }

  onRefreshSession(): void {
    const sessionId = this.currentSessionId();
    if (sessionId === null || !this.canRefreshSession()) {
      return;
    }
    this.isSessionRefreshSubmitting.set(true);
    this.sessionRefreshMessage.set(null);

    this.walletApiService
      .refreshSession(sessionId)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        catchError((error: HttpErrorResponse) => {
          this.isSessionRefreshSubmitting.set(false);
          this.sessionRefreshMessage.set(this.toBackendErrorMessage(error, 'Session refresh failed. Please retry.'));
          return EMPTY;
        })
      )
      .subscribe((response: SessionRefreshResponse) => {
        this.isSessionRefreshSubmitting.set(false);
        this.sessionRefreshMessage.set(response.message);
        if (response.status === 'SCHEDULED' && response.scheduledTargets > 0) {
          this.isBackfillVisible.set(true);
          this.sessionTransactionsError.set(null);
          this.sessionTransactionsLoadPhase.set('idle');
          this.loadSessionPreferences(sessionId);
          this.startBackfillPolling(sessionId);
          return;
        }
        this.loadSessionBackfillStatus(sessionId);
      });
  }

  closeAddWalletDialog(): void {
    if (this.isWalletSubmitBusy()) {
      return;
    }
    this.isAddWalletDialogOpen.set(false);
  }

  onWalletDialogSubmit(wallets: ReadonlyArray<AddSessionRequestItem>): void {
    if (wallets.length === 0) {
      this.walletSubmitState.set('error');
      this.walletSubmitMessage.set('Add at least one valid wallet address.');
      return;
    }

    const requestPayload: AddSessionRequest = {
      wallets: [...wallets],
      sessionId: this.sessionStorageService.getSessionId() ?? crypto.randomUUID(),
    };

    this.walletSubmitState.set('submitting');
    this.walletSubmitMessage.set(null);

    this.walletApiService
      .addSession(requestPayload)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        map((response) => ({
          sessionId: response.sessionId ?? requestPayload.sessionId,
          message: response.message ?? 'Session saved, universe sync scheduled',
        })),
        catchError((error: HttpErrorResponse) => {
          this.walletSubmitState.set('error');
          this.walletSubmitMessage.set(this.toBackendErrorMessage(error, 'Wallet submission failed. Please retry.'));
          return EMPTY;
        })
      )
      .subscribe(({ sessionId, message }) => {
        this.walletSubmitState.set('success');
        this.sessionStorageService.setSessionId(sessionId);
        this.currentSessionId.set(sessionId);
        this.walletSubmitMessage.set(message);
        this.isBackfillVisible.set(true);
        this.sessionBackfillStatus.set(null);
        this.isSessionRefreshSubmitting.set(false);
        this.sessionRefreshMessage.set(null);
        this.sessionTransactions.set([]);
        this.sessionTransactionsError.set(null);
        this.isSessionTransactionsLoading.set(false);
        this.sessionTransactionsLoadPhase.set('idle');
        this.walletFilterMode.set('all');
        this.selectedWalletIds.set(new Set<WalletId>());
        this.integrationFilterMode.set('all');
        this.selectedIntegrationRefs.set(new Set<string>());
        this.networkFilterMode.set('all');
        this.selectedNetworkIds.set(new Set<NetworkId>());
        this.sessionIntegrations.set([]);
        this.hideDustAssets.set(true);
        this.showReconciliationWarnings.set(true);
        this.dashboardRefreshNonce.update((value) => value + 1);
        this.loadSessionPreferences(sessionId);
        this.startBackfillPolling(sessionId);
        this.isAddWalletDialogOpen.set(false);
      });
  }

  isWalletSelected(walletId: WalletId): boolean {
    return this.selectedWalletFilter().has(walletId);
  }

  isIntegrationSelected(accountRef: string): boolean {
    return this.selectedIntegrationFilter().has(accountRef);
  }

  isNetworkSelected(networkId: NetworkId): boolean {
    return this.selectedNetworkFilter().has(networkId);
  }

  getSectionIcon(sectionId: DashboardSection): SafeHtml {
    const SVG_ATTRS = `xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"`;
    const icons: Record<DashboardSection, string> = {
      tokens: `<svg ${SVG_ATTRS}><rect x="1" y="1.5" width="18" height="13" rx="1.5"/><path d="M7.5 14.5v3M12.5 14.5v3M5 17.5h10"/><path d="M4.5 10.5V8M7.5 10.5V6.5M10.5 10.5V8.5M13.5 10.5V5.5M16 10.5V7"/><path d="M3 10.5h14"/></svg>`,
      lp: `<svg ${SVG_ATTRS}><path d="M7 1.5v9M13 1.5v9"/><path d="M7 5h6M7 8h6"/><path d="M1.5 13q2.5-2.5 5 0t5 0 5 0"/><ellipse cx="6.5" cy="17.5" rx="2.5" ry="1.2"/><ellipse cx="13.5" cy="17.5" rx="2.5" ry="1.2"/></svg>`,
      lending: `<svg ${SVG_ATTRS}><circle cx="15.5" cy="3.5" r="1.5"/><path d="M13 7c0-1.4 1.1-2.5 2.5-2.5S18 5.6 18 7"/><circle cx="4.5" cy="16.5" r="1.5"/><path d="M2 20c0-1.4 1.1-2.5 2.5-2.5S7 18.6 7 20"/><circle cx="10" cy="10" r="2.5"/><path d="M10 8.3v3.4"/><path d="M13 7.5L11.2 9.2M11.8 9.2l-.6-.6.6-.6"/><path d="M7 12.5L8.8 10.8M8.2 10.8l.6.6-.6.6"/></svg>`,
    };
    return this.sanitizer.bypassSecurityTrustHtml(icons[sectionId] ?? '');
  }

  getNetworkById(networkId: NetworkId) {
    return this.filterNetworks().find((network) => network.id === networkId) ?? null;
  }

  getWalletById(walletId: WalletId) {
    const normalizedWalletId = walletId.toLowerCase();
    return this.transactionPaneWallets().find((wallet) => wallet.id.toLowerCase() === normalizedWalletId) ?? null;
  }

  toggleNetworksAllocationExpanded(): void {
    this.networksAllocationExpanded.update((expanded) => !expanded);
  }

  toggleWalletsAllocationExpanded(): void {
    this.walletsAllocationExpanded.update((expanded) => !expanded);
  }

  getIntegrationByRef(accountRef: string): IntegrationInfo | null {
    const normalized = accountRef.trim().toLowerCase();
    return this.sessionIntegrations().find((integration) => integration.accountRef.toLowerCase() === normalized) ?? null;
  }

  formatUsd(value: number): string {
    const absolute = Math.abs(value);
    const compact = absolute >= 1000 ? `$${(absolute / 1000).toFixed(1)}k` : `$${absolute.toFixed(2)}`;
    return value < 0 ? `-${compact}` : compact;
  }

  formatPercent(value: number): string {
    const prefix = value >= 0 ? '+' : '';
    return `${prefix}${value.toFixed(1)}%`;
  }

  formatQuantity(value: number): string {
    const absolute = Math.abs(value);
    if (absolute >= 1000) {
      return value.toLocaleString(undefined, {
        minimumFractionDigits: 0,
        maximumFractionDigits: 3,
      });
    }
    if (absolute > 0 && absolute < 0.001) {
      return value.toLocaleString(undefined, {
        minimumFractionDigits: 0,
        maximumFractionDigits: 8,
      });
    }
    return value.toLocaleString(undefined, {
      minimumFractionDigits: 0,
      maximumFractionDigits: 3,
    });
  }

  formatQuantityFull(value: number): string {
    return value.toLocaleString(undefined, {
      minimumFractionDigits: 0,
      maximumFractionDigits: 12,
    });
  }

  formatUsdFull(value: number): string {
    return value.toLocaleString(undefined, {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 2,
      maximumFractionDigits: 12,
    });
  }

  issueTitle(issue: IssueCode): string {
    switch (issue) {
      case 'spam':
        return 'Spam or promo-phishing transaction.';
      case 'yield_accrual':
        return 'Yield accrual: current balance grew above covered principal since the last materialized event.';
      case 'coverage_gap':
        return 'Coverage gap: current balance is larger than basis-backed quantity.';
      case 'history_flags':
        return 'History flags: current balance is covered, but the bucket still carries incomplete or unresolved history flags.';
      case 'missing_replay_point':
        return 'Missing replay point: live balance exists, but no replay state was materialized for this bucket.';
      case 'unsupported_protocol_valuation':
        return 'Unsupported protocol valuation: backend has no current protocol snapshot for this position.';
      case 'missing_price':
        return 'Missing price.';
      case 'stale_price':
        return 'Stale price: current quote is older than the dashboard freshness window.';
      case 'historical_price_fallback':
        return 'Historical fallback price: no current quote snapshot is available.';
      case 'unconfirmed':
        return 'Unconfirmed';
      default:
        return '';
    }
  }

  priceTooltip(asset: TokenFamilyRow): string {
    const source = asset.priceSource ?? 'No source';
    const pricedAt = asset.pricedAt === null ? 'not loaded' : new Date(asset.pricedAt).toLocaleString();
    const freshness = asset.stalenessSeconds === null ? 'unknown age' : `${this.formatDuration(asset.stalenessSeconds)} old`;
    const mode = asset.isLiveQuote ? 'current quote' : 'non-live valuation';
    const issue = asset.priceIssue === null ? '' : ` ${this.issueTitle(asset.priceIssue)}`;
    const model = asset.valuationModel === null ? '' : ` Valuation: ${asset.valuationModel}.`;
    const underlying = asset.valuationUnderlyingSymbol === null ? '' : ` Underlying: ${asset.valuationUnderlyingSymbol}.`;
    const unsupported = asset.unsupportedValuationReason === null ? '' : ` ${asset.unsupportedValuationReason}.`;
    return `Exact price: ${this.formatUsdFull(asset.priceUsd)}. Loaded: ${pricedAt}. Source: ${source}. ${freshness}, ${mode}.${issue}${model}${underlying}${unsupported}`;
  }

  // ADR-062 §5 "Average cost": prefer the family-level parity metric (net-lane under offsetLane=NET,
  // ADR-062 2026-07-24, so it stays on the SAME lane as the effective-cost break-even). When the
  // backend value is unavailable, fall back to the net AVCO (NOT the market AVCO) so avg cost and
  // break-even never silently split lanes.
  avgCostDisplay(asset: TokenFamilyRow): number {
    return asset.averageCostUsd ?? asset.netAvcoUsd;
  }

  hasAvgCost(asset: TokenFamilyRow): boolean {
    return asset.averageCostUsd !== null || asset.netAvcoUsd !== null;
  }

  /** Effective cost above the current price → still under water (red). */
  effectiveCostIsLoss(asset: TokenFamilyRow): boolean {
    return asset.breakEvenUsd !== null && asset.breakEvenUsd > asset.priceUsd;
  }

  /** Effective cost at or below the current price → in profit / break-even reached (green). */
  effectiveCostIsProfit(asset: TokenFamilyRow): boolean {
    return asset.breakEvenUsd !== null && asset.breakEvenUsd <= asset.priceUsd;
  }

  avcoTooltip(asset: TokenFamilyRow): string {
    return `Average cost: ${this.formatUsdFull(this.avgCostDisplay(asset))}. Net AVCO: ${this.formatUsdFull(asset.netAvcoUsd)}. Market AVCO: ${this.formatUsdFull(asset.avcoUsd)}.`;
  }

  // ADR-062: strip the `FAMILY:` prefix for display (e.g. `FAMILY:ETH` → `ETH`).
  attributionParentLabel(target: string | null): string {
    if (target === null) {
      return '';
    }
    const trimmed = target.trim();
    return trimmed.startsWith('FAMILY:') ? trimmed.slice('FAMILY:'.length) : trimmed;
  }

  private formatDuration(seconds: number): string {
    if (seconds < 60) {
      return `${seconds}s`;
    }
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) {
      return `${minutes}m`;
    }
    const hours = Math.floor(minutes / 60);
    if (hours < 48) {
      return `${hours}h`;
    }
    return `${Math.floor(hours / 24)}d`;
  }

  shortAddress(address: string): string {
    if (address.length < 12) {
      return address;
    }

    return `${address.slice(0, 6)}...${address.slice(-4)}`;
  }

  private isWalletSubmitBusy(): boolean {
    return this.walletSubmitState() === 'submitting';
  }

  private resetWalletSubmissionState(): void {
    this.walletSubmitState.set('idle');
    this.walletSubmitMessage.set(null);
  }

  private startBackfillPolling(sessionId: string): void {
    this.stopBackfillPolling();
    this.backfillPollingSubscription = timer(0, 3000)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        switchMap(() =>
          this.walletApiService.getSessionBackfillStatus(sessionId).pipe(
            catchError((error: HttpErrorResponse) => {
              if (error.status === 404) {
                this.clearSessionTracking(true);
              }
              return EMPTY;
            })
          )
        )
      )
      .subscribe((status) => {
        this.sessionBackfillStatus.set(status);
        const pipelineRunning = this.isPipelineRunning(status);
        const terminal = this.isTerminalBackfillStatus(this.acquisitionStatus(status));
        this.isBackfillVisible.set(!terminal || pipelineRunning);
        if (terminal && pipelineRunning) {
          this.refreshSessionTransactions(sessionId, 'intermediate');
          return;
        }
        if (terminal) {
          this.isBackfillVisible.set(false);
          this.stopBackfillPolling();
          this.refreshDashboardSnapshot();
          this.refreshSessionTransactions(sessionId, 'final');
        }
      });
  }

  private stopBackfillPolling(): void {
    this.backfillPollingSubscription?.unsubscribe();
    this.backfillPollingSubscription = null;
  }

  private isTerminalBackfillStatus(status: SessionBackfillAggregateStatus): boolean {
    return status === 'COMPLETE' || status === 'FAILED' || status === 'PARTIAL';
  }

  private restoreSessionBackfillIfNeeded(): void {
    const storedSessionId = this.sessionStorageService.getSessionId();
    if (storedSessionId === null) {
      return;
    }

    this.currentSessionId.set(storedSessionId);
    this.loadSessionPreferences(storedSessionId);
    this.loadSessionBackfillStatus(storedSessionId);
  }

  private loadSessionBackfillStatus(sessionId: string): void {
    this.walletApiService
      .getSessionBackfillStatus(sessionId)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        catchError((error: HttpErrorResponse) => {
          if (error.status === 404) {
            this.clearSessionTracking(true);
          }
          return EMPTY;
        })
      )
      .subscribe((status) => {
        this.sessionBackfillStatus.set(status);
        const pipelineRunning = this.isPipelineRunning(status);
        const terminal = this.isTerminalBackfillStatus(this.acquisitionStatus(status));
        if (terminal && pipelineRunning) {
          this.isBackfillVisible.set(true);
          this.refreshSessionTransactions(sessionId, 'intermediate', true);
          this.startBackfillPolling(sessionId);
          return;
        }
        if (terminal) {
          this.isBackfillVisible.set(false);
          this.refreshSessionTransactions(sessionId, 'final', true);
          return;
        }
        this.isBackfillVisible.set(true);
        this.startBackfillPolling(sessionId);
      });
  }

  private loadSessionPreferences(sessionId: string): void {
    this.walletApiService
      .getSessionSettings(sessionId)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        catchError(() => EMPTY)
      )
      .subscribe((settings) => {
        this.hideDustAssets.set(settings.hideSmallAssets ?? true);
        this.showReconciliationWarnings.set(settings.showReconciliationWarnings ?? true);
        const integrations = settings.integrations
          .map((integration) => this.toIntegrationInfo(integration))
          .filter((integration): integration is IntegrationInfo => integration !== null);
        this.sessionIntegrations.set(integrations);
        const allowedRefs = new Set(integrations.map((integration) => integration.accountRef));
        if (this.integrationFilterMode() === 'custom') {
          this.selectedIntegrationRefs.set(
            new Set([...this.selectedIntegrationRefs()].filter((accountRef) => allowedRefs.has(accountRef)))
          );
        }
      });
  }

  private refreshSessionTransactions(
    sessionId: string,
    phase: SessionTransactionsLoadPhase = 'final',
    force = false
  ): void {
    // Cancel any in-flight request so filter changes always use latest state
    if (this.pendingTransactionSub && !this.pendingTransactionSub.closed) {
      this.pendingTransactionSub.unsubscribe();
      this.pendingTransactionSub = null;
    }

    if (!force && phase === 'intermediate' && this.sessionTransactionsLoadPhase() !== 'idle') {
      return;
    }
    if (!force && phase === 'final' && this.sessionTransactionsLoadPhase() === 'final') {
      return;
    }

    this.isSessionTransactionsLoading.set(true);
    this.sessionTransactionsError.set(null);

    this.pendingTransactionSub = this.walletApiService
      .getSessionTransactions(sessionId, this.buildSessionTransactionsRequest())
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        map((response) => ({
          items: response.items.map((item) => this.toTransactionItem(item)),
          totalCount: response.totalCount,
        })),
        catchError((error: HttpErrorResponse) => {
          if (error.status === 404) {
            this.sessionTransactionsError.set('Session transaction projection API is not available yet.');
          } else {
            this.sessionTransactionsError.set('Unable to load session transactions.');
          }
          this.isSessionTransactionsLoading.set(false);
          return of({
            items: [] as ReadonlyArray<TransactionItem>,
            totalCount: 0,
          });
        })
      )
      .subscribe((response) => {
        this.sessionTransactions.set(response.items);
        this.sessionTransactionsTotalCount.set(response.totalCount);
        this.isSessionTransactionsLoading.set(false);
        this.sessionTransactionsLoadPhase.set(phase);
      });
  }

  private refreshCurrentSessionTransactions(force = false): void {
    const sessionId = this.currentSessionId();
    const status = this.sessionBackfillStatus();
    if (sessionId === null || status === null || !this.isTerminalBackfillStatus(this.acquisitionStatus(status))) {
      return;
    }
    const phase: SessionTransactionsLoadPhase = this.isPipelineRunning(status) ? 'intermediate' : 'final';
    this.refreshSessionTransactions(sessionId, phase, force);
  }

  private resetTransactionPageAndRefresh(): void {
    this.transactionPage.set(0);
    this.refreshCurrentSessionTransactions(true);
  }

  private buildSessionTransactionsRequest(): GetSessionTransactionsRequest {
    const walletRefs =
      this.walletFilterMode() === 'all' && this.integrationFilterMode() === 'all'
        ? []
        : [
            ...(this.walletFilterMode() === 'all'
              ? this.availableWalletIds()
              : Array.from(this.selectedWalletIds())),
            ...(this.integrationFilterMode() === 'all'
              ? this.availableIntegrationRefs()
              : Array.from(this.selectedIntegrationRefs())),
          ];

    return {
      limit: this.transactionPageSize,
      offset: this.transactionPage() * this.transactionPageSize,
      search: this.transactionSearch(),
      categories: [...this.enabledCategories()],
      walletIds:
        this.walletFilterMode() === 'all' && this.integrationFilterMode() === 'all'
          ? undefined
          : walletRefs.length > 0
            ? walletRefs
            : ['__NO_SCOPE__'],
      networkIds:
        this.networkFilterMode() === 'all'
          ? undefined
          : Array.from(this.selectedNetworkIds()).filter((id) => !INTEGRATION_PRESENTATION_BY_PROVIDER.has(id)),
    };
  }

  private refreshDashboardSnapshot(): void {
    this.dashboardRefreshNonce.update((value) => value + 1);
  }

  private toTransactionItem(item: SessionTransactionItemResponse): TransactionItem {
    const flows = item.flows.map((flow) => this.toTransactionFlow(flow));
    const firstSymbol = flows.find((flow) => flow.symbol.length > 0)?.symbol ?? 'UNKNOWN';

    return {
      id: item.id,
      hash: item.txHash ?? item.id,
      timestamp: item.blockTimestamp ?? '',
      type: this.toTransactionType(item.type),
      symbol: firstSymbol,
      networkId: item.networkId ?? 'UNKNOWN',
      walletId: item.walletAddress?.toLowerCase() ?? 'unknown',
      matchedCounterparty: item.matchedCounterparty,
      status: this.toTransactionStatus(item.status),
      issue: this.toIssueCode(item.issue),
      bridgeStatus: this.toBridgeStatus(item.bridgeStatus),
      hasOverride: item.sourceType === 'OVERRIDE',
      flows,
    };
  }

  private toTransactionFlow(flow: SessionTransactionFlowResponse): TransactionFlow {
    const signedQuantity = flow.quantityDelta ?? 0;
    return {
      role: this.toFlowRole(flow.role, signedQuantity),
      symbol: flow.assetSymbol?.trim().toUpperCase() ?? 'UNKNOWN',
      quantity: Math.abs(signedQuantity),
      signedQuantity,
      priceUsd: flow.unitPriceUsd,
      source: this.toPriceSource(flow.priceSource),
    };
  }

  private toTransactionType(type: string | null): TransactionType {
    if (type !== null) {
      const override = TRANSACTION_TYPE_DISPLAY_OVERRIDES[type];
      if (override !== undefined) {
        return override;
      }
      if (TRANSACTION_TYPES_BY_ID.has(type as TransactionType)) {
        return type as TransactionType;
      }
    }
    return 'UNCLASSIFIED';
  }

  private toFlowRole(role: string | null, signedQuantity: number): FlowRole {
    if (role !== null && FLOW_ROLES.has(role as FlowRole)) {
      return role as FlowRole;
    }
    if (signedQuantity < 0) {
      return 'SELL';
    }
    return 'BUY';
  }

  private toPriceSource(priceSource: string | null): PriceSource | null {
    if (priceSource === null) {
      return null;
    }
    if (PRICE_SOURCES.has(priceSource as PriceSource)) {
      return priceSource as PriceSource;
    }
    return 'UNKNOWN';
  }

  private loadCategoriesFromStorage(): ReadonlySet<TransactionCategory> {
    try {
      const stored = localStorage.getItem(TRANSACTION_CATEGORIES_STORAGE_KEY);
      if (stored) {
        const parsed: unknown = JSON.parse(stored);
        if (Array.isArray(parsed) && parsed.length > 0) {
          const valid = (parsed as string[]).filter(
            (c): c is TransactionCategory => ALL_TRANSACTION_CATEGORIES.includes(c as TransactionCategory)
          );
          if (valid.length > 0) return new Set(valid);
        }
      }
    } catch { /* ignore */ }
    return new Set(DEFAULT_TRANSACTION_CATEGORIES);
  }

  private toBridgeStatus(bridgeStatus: string | null): SessionBridgeStatus | null {
    if (bridgeStatus !== null && BRIDGE_STATUSES.has(bridgeStatus as SessionBridgeStatus)) {
      return bridgeStatus as SessionBridgeStatus;
    }
    return null;
  }

  private toTransactionStatus(status: string | null): TransactionStatus {
    if (status === 'PENDING_PRICE' || status === 'NEEDS_REVIEW' || status === 'CONFIRMED') {
      return status;
    }
    return 'CONFIRMED';
  }

  private toIssueCode(issue: string | null): IssueCode {
    if (
      issue === 'spam' ||
      issue === 'missing_price' ||
      issue === 'unconfirmed' ||
      issue === 'yield_accrual' ||
      issue === 'coverage_gap' ||
      issue === 'history_flags' ||
      issue === 'missing_replay_point' ||
      issue === 'stale_price' ||
      issue === 'historical_price_fallback' ||
      issue === 'unsupported_protocol_valuation'
    ) {
      return issue;
    }
    return null;
  }

  private toBackendErrorMessage(error: HttpErrorResponse, fallback: string): string {
    if (typeof error.error === 'string' && error.error.trim().length > 0) {
      return error.error;
    }

    const backendMessage =
      typeof error.error === 'object' &&
      error.error !== null &&
      'message' in error.error &&
      typeof error.error.message === 'string'
        ? error.error.message
        : null;

    if (backendMessage !== null) {
      return backendMessage;
    }

    return fallback;
  }

  private isPipelineRunning(status: SessionBackfillStatusResponse): boolean {
    if (status.pipelineStatus === 'RUNNING') return true;
    // Handle inter-stage gaps: pipelineStatus briefly shows COMPLETE between stages while
    // the next stage has not yet marked itself RUNNING. Treat any non-final COMPLETE stage
    // as "running" so the poller keeps active and the user sees continuous progress.
    // The final completed state is PORTFOLIO_SNAPSHOT_REFRESH/COMPLETE.
    if (
      status.pipelineStatus === 'COMPLETE' &&
      status.pipelineStage &&
      status.pipelineStage !== 'PORTFOLIO_SNAPSHOT_REFRESH'
    ) {
      return true;
    }
    return false;
  }

  private acquisitionStatus(status: SessionBackfillStatusResponse): SessionBackfillAggregateStatus {
    return status.acquisitionStatus ?? status.status;
  }

  private phaseDisplayLabel(phase: string | null | undefined): string {
    switch (phase) {
      case 'ON_CHAIN_NORMALIZATION':
      case 'BYBIT_NORMALIZATION':
      case 'INTEGRATION_CLASSIFICATION':
        return 'Classification';
      case 'ON_CHAIN_CLARIFICATION':
        return 'Clarification';
      case 'ON_CHAIN_RECLASSIFICATION':
        return 'Reclassification';
      case 'LINKING':
        return 'Linking';
      case 'PRICING':
        return 'Pricing';
      case 'ACCOUNTING_REPLAY':
        return 'Cost basis';
      case 'PORTFOLIO_SNAPSHOT_REFRESH':
        return 'Portfolio snapshot';
      case 'BACKFILL':
      default:
        return 'Backfill';
    }
  }

  networkLabel(networkId: NetworkId): string {
    return this.getNetworkById(networkId)?.label ?? networkId;
  }

  walletColor(walletId: WalletId): string {
    const wallet = this.getWalletById(walletId);
    if (wallet !== null) {
      return wallet.color;
    }
    const integration = this.getIntegrationByRef(walletId);
    if (integration !== null) {
      return integration.color;
    }
    if (isCexAddress(walletId)) {
      const venueId = parseVenueId(walletId);
      return (venueId ? INTEGRATION_PRESENTATION_BY_PROVIDER.get(venueId)?.color : undefined) ?? COLORS.textSubtle;
    }
    return COLORS.textSubtle;
  }

  walletLabel(walletId: WalletId): string {
    const wallet = this.getWalletById(walletId);
    if (wallet !== null) {
      const sub = parseSubAccount(walletId);
      if (sub !== null) {
        return `${wallet.label} · ${sub}`;
      }
      return wallet.label;
    }
    return this.getIntegrationByRef(walletId)?.label ?? walletId;
  }

  private clearSessionTracking(clearStorage: boolean): void {
    if (clearStorage) {
      this.sessionStorageService.clearSessionId();
    }
    this.stopBackfillPolling();
    this.currentSessionId.set(null);
    this.sessionBackfillStatus.set(null);
    this.isSessionRefreshSubmitting.set(false);
    this.sessionRefreshMessage.set(null);
    this.isBackfillVisible.set(false);
    this.sessionTransactions.set([]);
    this.sessionTransactionsTotalCount.set(0);
    this.sessionTransactionsError.set(null);
    this.isSessionTransactionsLoading.set(false);
    this.sessionTransactionsLoadPhase.set('idle');
    this.transactionPage.set(0);
    this.walletFilterMode.set('all');
    this.selectedWalletIds.set(new Set<WalletId>());
    this.integrationFilterMode.set('all');
    this.selectedIntegrationRefs.set(new Set<string>());
    this.networkFilterMode.set('all');
    this.selectedNetworkIds.set(new Set<NetworkId>());
    this.sessionIntegrations.set([]);
    this.hideDustAssets.set(true);
    this.showReconciliationWarnings.set(true);
  }

  private toIntegrationInfo(integration: SessionIntegrationResponse): IntegrationInfo | null {
    const accountRef = integration.accountRef?.trim();
    const provider = integration.provider?.trim().toUpperCase() ?? '';
    if (!accountRef || !provider || integration.status === 'DISABLED') {
      return null;
    }

    const presentation = INTEGRATION_PRESENTATION_BY_PROVIDER.get(provider);
    return {
      id: integration.integrationId,
      provider,
      label: integration.displayName?.trim() || presentation?.label || provider,
      accountRef,
      // Prefer per-integration color stored in DB; fall back to provider presentation color
      color: integration.color ?? presentation?.color ?? COLORS.textSubtle,
      icon: presentation?.icon ?? '◎',
      status: integration.status,
    };
  }

  private allocationMoreSummary(rows: ReadonlyArray<AllocationRow>): AllocationMoreSummary {
    const hiddenRows = rows.slice(ALLOCATION_VISIBLE_ROW_COUNT);
    const totalUsd = rows.reduce((sum, row) => sum + row.valueUsd, 0);
    const hiddenUsd = hiddenRows.reduce((sum, row) => sum + row.valueUsd, 0);
    return {
      count: hiddenRows.length,
      valueUsd: hiddenUsd,
      sharePct: totalUsd > 0 ? (hiddenUsd / totalUsd) * 100 : 0,
    };
  }

  private mergeWalletScopes(
    wallets: ReadonlyArray<WalletInfo>,
    integrations: ReadonlyArray<WalletInfo>
  ): ReadonlyArray<WalletInfo> {
    const merged = new Map<string, WalletInfo>();
    for (const wallet of wallets) {
      merged.set(wallet.id, wallet);
    }
    for (const integration of integrations) {
      merged.set(integration.id, integration);
    }
    return [...merged.values()];
  }

  private toggleSetValue<T>(set: ReadonlySet<T>, value: T): ReadonlySet<T> {
    const copy = new Set(set);
    if (copy.has(value)) {
      copy.delete(value);
    } else {
      copy.add(value);
    }
    return copy;
  }

  private mergeIssueCode(left: IssueCode, right: IssueCode): IssueCode {
    if (left === null) {
      return right;
    }
    if (right === null) {
      return left;
    }
    return this.issueRank(right) > this.issueRank(left) ? right : left;
  }

  private issueRank(issue: IssueCode): number {
    switch (issue) {
      case 'missing_replay_point':
        return 4;
      case 'coverage_gap':
        return 3;
      case 'history_flags':
        return 2;
      case 'yield_accrual':
        return 1;
      case 'missing_price':
      case 'unsupported_protocol_valuation':
        return 3;
      case 'stale_price':
        return 2;
      case 'historical_price_fallback':
        return 1;
      case 'spam':
      case 'unconfirmed':
      case null:
      default:
        return 0;
    }
  }

  private pickPriceSource(
    existing: {
      readonly priceSource: PriceSource | null;
      readonly pricedAt: string | null;
      readonly isLiveQuote: boolean;
    },
    position: TokenPosition
  ): PriceSource | null {
    if (existing.priceSource === null) {
      return position.priceSource;
    }
    if (position.priceSource === null) {
      return existing.priceSource;
    }
    if (position.isLiveQuote && !existing.isLiveQuote) {
      return position.priceSource;
    }
    const existingTime = existing.pricedAt === null ? 0 : Date.parse(existing.pricedAt);
    const candidateTime = position.pricedAt === null ? 0 : Date.parse(position.pricedAt);
    return candidateTime > existingTime ? position.priceSource : existing.priceSource;
  }

  private pickLatestPricedAt(left: string | null, right: string | null): string | null {
    if (left === null) {
      return right;
    }
    if (right === null) {
      return left;
    }
    return Date.parse(right) > Date.parse(left) ? right : left;
  }

  private pickSmallestStaleness(left: number | null, right: number | null): number | null {
    if (left === null) {
      return right;
    }
    if (right === null) {
      return left;
    }
    return Math.min(left, right);
  }
}
