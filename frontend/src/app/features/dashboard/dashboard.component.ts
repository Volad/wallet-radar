import { ChangeDetectionStrategy, Component, DestroyRef, ViewChild, computed, effect, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
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
} from '../../core/data/dashboard.constants';
import {
  DashboardSection,
  DashboardViewState,
  FlowRole,
  IssueCode,
  NetworkId,
  NetworkInfo,
  PriceSource,
  SectionMeta,
  TokenPosition,
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
  SessionTransactionsBridgeFilter,
  SessionTransactionFlowResponse,
  SessionTransactionItemResponse,
  SessionTransactionsSpamFilter,
  SUPPORTED_EVM_NETWORKS,
} from '../../core/models/wallet-api.models';
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

type LpTab = 'all' | 'open' | 'closed';
type SessionTransactionsLoadPhase = 'idle' | 'intermediate' | 'final';
type WalletFormGroup = FormGroup<{
  address: FormControl<string>;
  label: FormControl<string>;
  color: FormControl<string>;
}>;
type WalletDialogFormGroup = FormGroup<{
  wallets: FormArray<WalletFormGroup>;
}>;

interface TokenFamilyRow {
  readonly familyIdentity: string;
  readonly symbol: string;
  readonly name: string;
  readonly quantity: number;
  readonly priceUsd: number;
  readonly avcoUsd: number;
  readonly unrealizedPnlPct: number;
  readonly unrealizedPnlUsd: number;
  readonly realizedPnlUsd: number;
  readonly issue: IssueCode;
  readonly networkIds: ReadonlyArray<NetworkId>;
  readonly walletIds: ReadonlyArray<WalletId>;
  readonly currentValueUsd: number;
  readonly totalCostBasisUsd: number;
}

const TRANSACTION_TYPES_BY_ID = new Set<TransactionType>([
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
]);

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
  readonly sessionTransactions = signal<ReadonlyArray<TransactionItem>>([]);
  readonly sessionTransactionsTotalCount = signal(0);
  readonly isSessionTransactionsLoading = signal(false);
  readonly sessionTransactionsError = signal<string | null>(null);
  readonly sessionTransactionsLoadPhase = signal<SessionTransactionsLoadPhase>('idle');
  readonly transactionSearch = signal('');
  readonly transactionBridgeStatusFilter = signal<SessionTransactionsBridgeFilter>('ALL');
  readonly transactionSpamFilter = signal<SessionTransactionsSpamFilter>('HIDE_SPAM');
  readonly transactionPage = signal(0);
  readonly transactionPageSize = 50;
  readonly canOpenAssetLedger = computed(() => this.currentSessionId() !== null);
  readonly selectedAssetFamilyIdentity = signal<string | null>(null);
  readonly routeAssetLedgerSelection = toSignal(
    this.route.paramMap.pipe(
      map((params) => ({
        sessionId: params.get('sessionId')?.trim() ?? null,
        familyIdentity: params.get('familyIdentity')?.trim() ?? null,
      }))
    ),
    {
      initialValue: {
        sessionId: null,
        familyIdentity: null,
      },
    }
  );
  readonly assetLedgerFamilyIdentity = computed(
    () => this.selectedAssetFamilyIdentity() ?? this.routeAssetLedgerSelection().familyIdentity
  );
  readonly assetLedgerSessionId = computed(() => this.routeAssetLedgerSelection().sessionId ?? this.currentSessionId());
  readonly isAssetLedgerMode = computed(() => this.assetLedgerFamilyIdentity() !== null);

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
  readonly selectedWalletIds = signal<ReadonlySet<WalletId>>(new Set<WalletId>());
  readonly selectedNetworkIds = signal<ReadonlySet<NetworkId>>(new Set<NetworkId>());
  readonly hideDustAssets = signal(true);
  readonly isFiltersCollapsed = signal(false);
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
    return `${this.phaseDisplayLabel(phase)}: ${this.backfillProgressPct()}% done`;
  });

  readonly pipelineStatusSubline = computed(() => {
    const status = this.sessionBackfillStatus();
    if (status === null) {
      return this.data().backfill.networksLabel;
    }
    const phaseProgress = status.phaseProgress;
    if (phaseProgress !== null && phaseProgress !== undefined) {
      return `processed: ${phaseProgress.processedCount} · left: ${phaseProgress.leftCount}`;
    }
    if (status.pipelineMessage !== null && status.pipelineMessage !== undefined && status.pipelineMessage.length > 0) {
      return status.pipelineMessage;
    }
    return `${status.completedTargets}/${status.totalTargets} wallet×network complete`;
  });

  readonly showPipelineProgress = computed(() => {
    return this.sessionBackfillStatus() !== null;
  });

  readonly activeSection = computed((): SectionMeta | null => {
    return this.data().sections.find((sectionMeta) => sectionMeta.id === this.section()) ?? null;
  });

  readonly activeFilterCount = computed(() => {
    return this.selectedWalletIds().size + this.selectedNetworkIds().size;
  });

  readonly selectedWalletFilter = computed(() => this.selectedWalletIds());
  readonly selectedNetworkFilter = computed(() => this.selectedNetworkIds());
  readonly sessionWallets = computed<ReadonlyArray<WalletInfo>>(() => {
    const status = this.sessionBackfillStatus();
    if (status === null) {
      return [];
    }
    return status.wallets.map((wallet) => ({
      id: wallet.address.toLowerCase(),
      label: wallet.label,
      address: wallet.address.toLowerCase(),
      color: wallet.color,
    }));
  });

  readonly sessionNetworks = computed<ReadonlyArray<NetworkInfo>>(() => {
    const status = this.sessionBackfillStatus();
    if (status === null) {
      return [];
    }

    const networkIds = new Set<EvmNetworkId>();
    status.wallets.forEach((wallet) => {
      wallet.networks.forEach((network) => {
        networkIds.add(network.networkId);
      });
    });

    return [...networkIds].map((networkId) => {
      const presentation = EVM_NETWORK_PRESENTATION_BY_ID.get(networkId);
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
    return sessionWallets.length > 0 ? sessionWallets : this.data().wallets;
  });

  readonly transactionPaneNetworks = computed(() => {
    const sessionNetworks = this.sessionNetworks();
    return sessionNetworks.length > 0 ? sessionNetworks : this.data().networks;
  });

  readonly filterNetworks = computed<ReadonlyArray<NetworkInfo>>(() => {
    const sessionNetworks = this.sessionNetworks();
    return sessionNetworks.length > 0 ? sessionNetworks : this.data().networks;
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
    const selectedNetworks = this.selectedNetworkFilter();
    const hideDust = this.hideDustAssets();

    return this.data().tokenPositions.filter((asset) => {
      if (hideDust && asset.quantity * asset.priceUsd < 0.5) {
        return false;
      }
      if (selectedWallets.size > 0 && !selectedWallets.has(asset.walletId)) {
        return false;
      }
      if (selectedNetworks.size > 0 && !selectedNetworks.has(asset.networkId)) {
        return false;
      }

      return true;
    });
  });

  readonly filteredTokenFamilies = computed<ReadonlyArray<TokenFamilyRow>>(() => {
    const grouped = new Map<string, {
      familyIdentity: string;
      symbol: string;
      name: string;
      quantity: number;
      currentValueUsd: number;
      totalCostBasisUsd: number;
      unrealizedPnlUsd: number;
      realizedPnlUsd: number;
      networkIds: Set<NetworkId>;
      walletIds: Set<WalletId>;
      issue: IssueCode;
    }>();

    for (const position of this.filteredTokenPositions()) {
      const currentValueUsd = position.quantity * position.priceUsd;
      const totalCostBasisUsd = position.quantity * position.avcoUsd;
      const existing = grouped.get(position.familyIdentity);
      if (existing === undefined) {
        grouped.set(position.familyIdentity, {
          familyIdentity: position.familyIdentity,
          symbol: position.symbol,
          name: position.name,
          quantity: position.quantity,
          currentValueUsd,
          totalCostBasisUsd,
          unrealizedPnlUsd: position.unrealizedPnlUsd,
          realizedPnlUsd: position.realizedPnlUsd,
          networkIds: new Set([position.networkId]),
          walletIds: new Set([position.walletId]),
          issue: position.issue,
        });
        continue;
      }

      existing.quantity += position.quantity;
      existing.currentValueUsd += currentValueUsd;
      existing.totalCostBasisUsd += totalCostBasisUsd;
      existing.unrealizedPnlUsd += position.unrealizedPnlUsd;
      existing.realizedPnlUsd += position.realizedPnlUsd;
      existing.networkIds.add(position.networkId);
      existing.walletIds.add(position.walletId);
      existing.issue = this.mergeIssueCode(existing.issue, position.issue);
    }

    return [...grouped.values()]
      .map((group): TokenFamilyRow => {
        const quantity = group.quantity;
        const priceUsd = quantity === 0 ? 0 : group.currentValueUsd / quantity;
        const avcoUsd = quantity === 0 ? 0 : group.totalCostBasisUsd / quantity;
        const unrealizedPnlPct = group.totalCostBasisUsd === 0 ? 0 : (group.unrealizedPnlUsd / group.totalCostBasisUsd) * 100;
        return {
          familyIdentity: group.familyIdentity,
          symbol: group.symbol,
          name: group.name,
          quantity,
          priceUsd,
          avcoUsd,
          unrealizedPnlPct,
          unrealizedPnlUsd: group.unrealizedPnlUsd,
          realizedPnlUsd: group.realizedPnlUsd,
          issue: group.issue,
          networkIds: [...group.networkIds],
          walletIds: [...group.walletIds],
          currentValueUsd: group.currentValueUsd,
          totalCostBasisUsd: group.totalCostBasisUsd,
        };
      })
      .sort((left, right) => right.currentValueUsd - left.currentValueUsd);
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
      const routeSessionId = this.routeAssetLedgerSelection().sessionId;
      if (routeSessionId === null || routeSessionId.length === 0 || routeSessionId === this.currentSessionId()) {
        return;
      }
      this.sessionStorageService.setSessionId(routeSessionId);
      this.currentSessionId.set(routeSessionId);
      this.loadSessionBackfillStatus(routeSessionId);
    });
    effect(() => {
      const routeFamilyIdentity = this.routeAssetLedgerSelection().familyIdentity;
      if (routeFamilyIdentity === null || routeFamilyIdentity.length === 0) {
        return;
      }
      if (this.selectedAssetFamilyIdentity() === routeFamilyIdentity) {
        return;
      }
      this.selectedAssetFamilyIdentity.set(routeFamilyIdentity);
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
    if (this.isAssetLedgerMode()) {
      this.section.set(sectionId);
      this.closeAssetLedger();
      return;
    }
    this.section.set(sectionId);
  }

  toggleWallet(walletId: WalletId): void {
    this.selectedWalletIds.set(this.toggleSetValue(this.selectedWalletIds(), walletId));
    this.resetTransactionPageAndRefresh();
  }

  toggleNetwork(networkId: NetworkId): void {
    this.selectedNetworkIds.set(this.toggleSetValue(this.selectedNetworkIds(), networkId));
    this.resetTransactionPageAndRefresh();
  }

  clearFilters(): void {
    this.selectedWalletIds.set(new Set<WalletId>());
    this.selectedNetworkIds.set(new Set<NetworkId>());
    this.resetTransactionPageAndRefresh();
  }

  toggleFiltersCollapsed(): void {
    this.isFiltersCollapsed.update((collapsed) => !collapsed);
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

  onTransactionBridgeFilterChange(value: SessionTransactionsBridgeFilter): void {
    this.transactionBridgeStatusFilter.set(value);
    this.resetTransactionPageAndRefresh();
  }

  onTransactionSpamFilterChange(value: SessionTransactionsSpamFilter): void {
    this.transactionSpamFilter.set(value);
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
    this.selectedAssetFamilyIdentity.set(asset.familyIdentity);
  }

  closeAssetLedger(): void {
    this.selectedAssetFamilyIdentity.set(null);
    if (this.routeAssetLedgerSelection().familyIdentity !== null) {
      void this.router.navigate(['/']);
    }
  }

  openAddWalletDialog(): void {
    this.resetWalletSubmissionState();
    this.isAddWalletDialogOpen.set(true);
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
          message: response.message ?? 'Session saved, backfill started',
        })),
        catchError((error: HttpErrorResponse) => {
          this.walletSubmitState.set('error');
          this.walletSubmitMessage.set(this.toWalletSubmitError(error));
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
        this.sessionTransactions.set([]);
        this.sessionTransactionsError.set(null);
        this.isSessionTransactionsLoading.set(false);
        this.sessionTransactionsLoadPhase.set('idle');
        this.selectedWalletIds.set(new Set<WalletId>());
        this.selectedNetworkIds.set(new Set<NetworkId>());
        this.dashboardRefreshNonce.update((value) => value + 1);
        this.startBackfillPolling(sessionId);
        this.isAddWalletDialogOpen.set(false);
      });
  }

  isWalletSelected(walletId: WalletId): boolean {
    return this.selectedWalletIds().has(walletId);
  }

  isNetworkSelected(networkId: NetworkId): boolean {
    return this.selectedNetworkIds().has(networkId);
  }

  getSectionIcon(sectionId: DashboardSection): string {
    switch (sectionId) {
      case 'tokens':
        return '◍';
      case 'lp':
        return '◢';
      case 'lending':
        return '⌂';
      case 'staking':
        return '⚡';
      default:
        return '•';
    }
  }

  getNetworkById(networkId: NetworkId) {
    return this.filterNetworks().find((network) => network.id === networkId) ?? null;
  }

  getWalletById(walletId: WalletId) {
    return this.data().wallets.find((wallet) => wallet.id === walletId) ?? null;
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
      case 'missing_price':
        return 'Missing price';
      case 'unconfirmed':
        return 'Unconfirmed';
      default:
        return '';
    }
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
        const terminal = this.isTerminalBackfillStatus(status.status);
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
        const terminal = this.isTerminalBackfillStatus(status.status);
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

  private refreshSessionTransactions(
    sessionId: string,
    phase: SessionTransactionsLoadPhase = 'final',
    force = false
  ): void {
    if (this.isSessionTransactionsLoading()) {
      return;
    }
    if (!force && phase === 'intermediate' && this.sessionTransactionsLoadPhase() !== 'idle') {
      return;
    }
    if (!force && phase === 'final' && this.sessionTransactionsLoadPhase() === 'final') {
      return;
    }

    this.isSessionTransactionsLoading.set(true);
    this.sessionTransactionsError.set(null);

    this.walletApiService
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
    if (sessionId === null || status === null || !this.isTerminalBackfillStatus(status.status)) {
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
    return {
      limit: this.transactionPageSize,
      offset: this.transactionPage() * this.transactionPageSize,
      search: this.transactionSearch(),
      bridgeStatus: this.transactionBridgeStatusFilter(),
      spamFilter: this.transactionSpamFilter(),
      walletIds: this.selectedWalletIds().size > 0 ? Array.from(this.selectedWalletIds()) : undefined,
      networkIds:
        this.selectedNetworkIds().size > 0
          ? (Array.from(this.selectedNetworkIds()) as ReadonlyArray<EvmNetworkId>)
          : undefined,
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
    if (type !== null && TRANSACTION_TYPES_BY_ID.has(type as TransactionType)) {
      return type as TransactionType;
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
      issue === 'missing_replay_point'
    ) {
      return issue;
    }
    return null;
  }

  private toWalletSubmitError(error: HttpErrorResponse): string {
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

    return 'Wallet submission failed. Please retry.';
  }

  private isPipelineRunning(status: SessionBackfillStatusResponse): boolean {
    return status.pipelineStatus === 'RUNNING';
  }

  private phaseDisplayLabel(phase: string | null | undefined): string {
    switch (phase) {
      case 'ON_CHAIN_NORMALIZATION':
      case 'ON_CHAIN_CLARIFICATION':
      case 'BYBIT_NORMALIZATION':
        return 'Normalization';
      case 'PRICING':
        return 'Pricing';
      case 'ACCOUNTING_REPLAY':
        return 'Basis';
      case 'BACKFILL':
      default:
        return 'Backfill';
    }
  }

  networkLabel(networkId: NetworkId): string {
    return this.getNetworkById(networkId)?.label ?? networkId;
  }

  walletLabel(walletId: WalletId): string {
    return this.getWalletById(walletId)?.label ?? walletId;
  }

  private clearSessionTracking(clearStorage: boolean): void {
    if (clearStorage) {
      this.sessionStorageService.clearSessionId();
    }
    this.stopBackfillPolling();
    this.currentSessionId.set(null);
    this.sessionBackfillStatus.set(null);
    this.isBackfillVisible.set(false);
    this.sessionTransactions.set([]);
    this.sessionTransactionsTotalCount.set(0);
    this.sessionTransactionsError.set(null);
    this.isSessionTransactionsLoading.set(false);
    this.sessionTransactionsLoadPhase.set('idle');
    this.transactionPage.set(0);
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
      case 'spam':
      case 'missing_price':
      case 'unconfirmed':
      case null:
      default:
        return 0;
    }
  }
}
