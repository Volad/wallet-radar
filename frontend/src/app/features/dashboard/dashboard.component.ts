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
  INTEGRATION_PRESENTATION_BY_PROVIDER,
} from '../../core/data/dashboard.constants';
import {
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
import { SettingsPageComponent } from '../settings/settings-page.component';

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
  'GAS_ONLY',
  'EXTERNAL_INBOUND',
  'EXTERNAL_TRANSFER_IN',
  'EXTERNAL_TRANSFER_OUT',
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
    SettingsPageComponent,
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
  readonly isSessionRefreshSubmitting = signal(false);
  readonly sessionRefreshMessage = signal<string | null>(null);
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
  readonly isSettingsMode = toSignal(
    this.route.data.pipe(map((data) => data['mode'] === 'settings')),
    { initialValue: this.route.snapshot.data['mode'] === 'settings' }
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
  readonly showReconciliationWarnings = signal(true);
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
      if (phaseProgress.phase === 'PRICING') {
        return `priced tx: ${phaseProgress.processedCount} · left: ${phaseProgress.leftCount}`;
      }
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

  readonly availableWalletIds = computed<ReadonlyArray<WalletId>>(() => this.data().wallets.map((wallet) => wallet.id));
  readonly availableIntegrationRefs = computed<ReadonlyArray<string>>(() =>
    this.sessionIntegrations().map((integration) => integration.accountRef)
  );
  readonly availableNetworkIds = computed<ReadonlyArray<NetworkId>>(() => this.filterNetworks().map((network) => network.id));

  readonly activeFilterCount = computed(() => {
    const hiddenWallets = this.walletFilterMode() === 'all'
      ? 0
      : Math.max(0, this.availableWalletIds().length - this.selectedWalletIds().size);
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
    return status.wallets.map((wallet) => ({
      id: wallet.address.toLowerCase(),
      label: wallet.label,
      address: wallet.address.toLowerCase(),
      color: wallet.color,
    }));
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
    const baseWallets = sessionWallets.length > 0 ? sessionWallets : this.data().wallets;
    return this.mergeWalletScopes(baseWallets, this.transactionPaneIntegrations());
  });

  readonly filterIntegrations = computed(() => this.sessionIntegrations());

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
    const hasCustomIntegrationFilter = this.integrationFilterMode() === 'custom';
    const selectedNetworks = this.selectedNetworkFilter();
    const hideDust = this.hideDustAssets();

    return this.data().tokenPositions.filter((asset) => {
      if (hasCustomIntegrationFilter) {
        return false;
      }
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
      coveredQuantity: number;
      currentValueUsd: number;
      totalCostBasisUsd: number;
      unrealizedPnlUsd: number;
      realizedPnlUsd: number;
      priceSource: PriceSource | null;
      pricedAt: string | null;
      stalenessSeconds: number | null;
      isLiveQuote: boolean;
      priceIssue: IssueCode;
      networkIds: Set<NetworkId>;
      walletIds: Set<WalletId>;
      issue: IssueCode;
    }>();

    for (const position of this.filteredTokenPositions()) {
      const currentValueUsd = position.marketValueUsd;
      const totalCostBasisUsd = position.quantity * position.avcoUsd;
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
          unrealizedPnlUsd: position.unrealizedPnlUsd,
          realizedPnlUsd: position.realizedPnlUsd,
          priceSource: position.priceSource,
          pricedAt: position.pricedAt,
          stalenessSeconds: position.stalenessSeconds,
          isLiveQuote: position.isLiveQuote,
          priceIssue: position.priceIssue,
          networkIds: new Set([position.networkId]),
          walletIds: new Set([position.walletId]),
          issue: position.issue,
        });
        continue;
      }

      existing.quantity += position.quantity;
      existing.coveredQuantity += position.coveredQuantity;
      existing.currentValueUsd += currentValueUsd;
      existing.totalCostBasisUsd += totalCostBasisUsd;
      existing.unrealizedPnlUsd += position.unrealizedPnlUsd;
      existing.realizedPnlUsd += position.realizedPnlUsd;
      existing.priceSource = this.pickPriceSource(existing, position);
      existing.pricedAt = this.pickLatestPricedAt(existing.pricedAt, position.pricedAt);
      existing.stalenessSeconds = this.pickSmallestStaleness(existing.stalenessSeconds, position.stalenessSeconds);
      existing.isLiveQuote = existing.isLiveQuote || position.isLiveQuote;
      existing.priceIssue = this.mergeIssueCode(existing.priceIssue, position.priceIssue);
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
          coveredQuantity: group.coveredQuantity,
          priceUsd,
          priceSource: group.priceSource,
          pricedAt: group.pricedAt,
          stalenessSeconds: group.stalenessSeconds,
          isLiveQuote: group.isLiveQuote,
          priceIssue: group.priceIssue,
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
    const hasCustomIntegrationFilter = this.integrationFilterMode() === 'custom';
    const selectedNetworks = this.selectedNetworkFilter();
    const currentTab = this.lpTab();

    return this.data().lpPositions.filter((position) => {
      if (hasCustomIntegrationFilter) {
        return false;
      }
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
    const hasCustomIntegrationFilter = this.integrationFilterMode() === 'custom';
    const selectedNetworks = this.selectedNetworkFilter();

    return this.data().lendingPositions.filter((position) => {
      if (hasCustomIntegrationFilter) {
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
    if (this.isSettingsMode()) {
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
    this.selectedAssetFamilyIdentity.set(null);
    void this.router.navigate(['/settings']);
  }

  toggleWallet(walletId: WalletId): void {
    if (this.walletFilterMode() === 'all') {
      this.walletFilterMode.set('custom');
      this.selectedWalletIds.set(new Set(this.availableWalletIds().filter((id) => id !== walletId)));
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
          message: response.message ?? 'Session saved, backfill started',
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
    return `Exact price: ${this.formatUsdFull(asset.priceUsd)}. Loaded: ${pricedAt}. Source: ${source}. ${freshness}, ${mode}.${issue}`;
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
      bridgeStatus: this.transactionBridgeStatusFilter(),
      spamFilter: this.transactionSpamFilter(),
      walletIds:
        this.walletFilterMode() === 'all' && this.integrationFilterMode() === 'all'
          ? undefined
          : walletRefs.length > 0
            ? walletRefs
            : ['__NO_SCOPE__'],
      networkIds:
        this.networkFilterMode() === 'all'
          ? undefined
          : (Array.from(this.selectedNetworkIds()) as ReadonlyArray<EvmNetworkId>),
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
    return status.pipelineStatus === 'RUNNING';
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
      case 'BACKFILL':
      default:
        return 'Backfill';
    }
  }

  networkLabel(networkId: NetworkId): string {
    return this.getNetworkById(networkId)?.label ?? networkId;
  }

  walletLabel(walletId: WalletId): string {
    return this.getWalletById(walletId)?.label ?? this.getIntegrationByRef(walletId)?.label ?? walletId;
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
      color: presentation?.color ?? COLORS.textSubtle,
      icon: presentation?.icon ?? '◎',
      status: integration.status,
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
