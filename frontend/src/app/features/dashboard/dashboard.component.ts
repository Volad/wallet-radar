import { ChangeDetectionStrategy, Component, DestroyRef, ViewChild, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, FormArray, FormControl, FormGroup } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { EMPTY, Subscription, catchError, map, of, startWith, switchMap, timer } from 'rxjs';

import { COLORS, EMPTY_DASHBOARD_DATA } from '../../core/data/dashboard.mock';
import {
  DashboardSection,
  DashboardViewState,
  NetworkId,
  SectionMeta,
  WalletId,
} from '../../core/models/dashboard.models';
import { DashboardDataService } from '../../core/services/dashboard-data.service';
import {
  AddSessionRequest,
  AddSessionRequestItem,
  EvmNetworkId,
  SessionBackfillAggregateStatus,
  SessionBackfillStatusResponse,
  SUPPORTED_EVM_NETWORKS,
} from '../../core/models/wallet-api.models';
import { WalletApiService } from '../../core/services/wallet-api.service';
import { SessionStorageService } from '../../core/services/session-storage.service';
import {
  DashboardAddWalletDialogComponent,
  EvmNetworkPresentation,
  WalletSubmitState,
} from './components/dashboard-add-wallet-dialog/dashboard-add-wallet-dialog.component';
import { DashboardSectionNavComponent } from './components/dashboard-section-nav/dashboard-section-nav.component';
import { DashboardTopbarComponent } from './components/dashboard-topbar/dashboard-topbar.component';
import { DashboardTransactionsPaneComponent } from './components/dashboard-transactions-pane/dashboard-transactions-pane.component';

type LpTab = 'all' | 'open' | 'closed';
type WalletFormGroup = FormGroup<{
  address: FormControl<string>;
  label: FormControl<string>;
  color: FormControl<string>;
}>;
type WalletDialogFormGroup = FormGroup<{
  wallets: FormArray<WalletFormGroup>;
}>;

const EVM_NETWORKS_PRESENTATION: ReadonlyArray<EvmNetworkPresentation> = [
  { id: 'ETHEREUM', icon: '⟠', label: 'Ethereum', color: '#627EEA' },
  { id: 'ARBITRUM', icon: '△', label: 'Arbitrum', color: '#28A0F0' },
  { id: 'OPTIMISM', icon: '○', label: 'Optimism', color: '#FF0420' },
  { id: 'POLYGON', icon: '⬡', label: 'Polygon', color: '#7B3FE4' },
  { id: 'BASE', icon: '◆', label: 'Base', color: '#0052FF' },
  { id: 'BSC', icon: '◈', label: 'BNB Chain', color: '#F0B90B' },
  { id: 'AVALANCHE', icon: '▲', label: 'Avalanche', color: '#E84142' },
  { id: 'MANTLE', icon: '◉', label: 'Mantle', color: '#60A5FA' },
  { id: 'LINEA', icon: '◌', label: 'Linea', color: '#8B5CF6' },
  { id: 'UNICHAIN', icon: '⬢', label: 'Unichain', color: '#ff2e7e' },
  { id: 'ZKSYNC', icon: '◍', label: 'zkSync Era', color: '#8C8DFC' },
];

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
  readonly sessionBackfillStatus = signal<SessionBackfillStatusResponse | null>(null);

  readonly viewState = toSignal(
    this.dashboardDataService.getDashboardData().pipe(
      map((data): DashboardViewState => ({ status: 'success', data })),
      startWith<DashboardViewState>({ status: 'loading' }),
      catchError(() =>
        of<DashboardViewState>({ status: 'error', message: 'Unable to load dashboard data.' })
      )
    ),
    { requireSync: true }
  );

  readonly section = signal<DashboardSection>('tokens');
  readonly selectedWalletIds = signal<ReadonlySet<WalletId>>(new Set<WalletId>());
  readonly selectedNetworkIds = signal<ReadonlySet<NetworkId>>(new Set<NetworkId>());
  readonly hideDustAssets = signal(false);
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
    return this.sessionBackfillStatus()?.overallProgressPct ?? this.data().backfill.progressPct;
  });

  readonly backfillNetworksLabel = computed(() => {
    const status = this.sessionBackfillStatus();
    if (status === null) {
      return this.data().backfill.networksLabel;
    }
    return `${status.completedTargets}/${status.totalTargets} wallet×network complete`;
  });

  readonly activeSection = computed((): SectionMeta | null => {
    return this.data().sections.find((sectionMeta) => sectionMeta.id === this.section()) ?? null;
  });

  readonly activeFilterCount = computed(() => {
    return this.selectedWalletIds().size + this.selectedNetworkIds().size;
  });

  readonly selectedWalletFilter = computed(() => this.selectedWalletIds());
  readonly selectedNetworkFilter = computed(() => this.selectedNetworkIds());

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
    return this.filteredTokenPositions().reduce((total, token) => total + token.quantity * token.priceUsd, 0);
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
    this.section.set(sectionId);
  }

  toggleWallet(walletId: WalletId): void {
    this.selectedWalletIds.set(this.toggleSetValue(this.selectedWalletIds(), walletId));
  }

  toggleNetwork(networkId: NetworkId): void {
    this.selectedNetworkIds.set(this.toggleSetValue(this.selectedNetworkIds(), networkId));
  }

  clearFilters(): void {
    this.selectedWalletIds.set(new Set<WalletId>());
    this.selectedNetworkIds.set(new Set<NetworkId>());
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
    return this.data().networks.find((network) => network.id === networkId) ?? null;
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
    if (value >= 1000) {
      return value.toLocaleString();
    }

    if (value >= 1) {
      return value.toFixed(4).replace(/0+$/u, '').replace(/\.$/u, '');
    }

    return value.toString();
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
        this.isBackfillVisible.set(true);
        if (this.isTerminalBackfillStatus(status.status)) {
          this.isBackfillVisible.set(false);
          this.stopBackfillPolling();
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
    this.walletApiService
      .getSessionBackfillStatus(storedSessionId)
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
        if (this.isTerminalBackfillStatus(status.status)) {
          this.isBackfillVisible.set(false);
          return;
        }
        this.isBackfillVisible.set(true);
        this.startBackfillPolling(storedSessionId);
      });
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

  private clearSessionTracking(clearStorage: boolean): void {
    if (clearStorage) {
      this.sessionStorageService.clearSessionId();
    }
    this.stopBackfillPolling();
    this.currentSessionId.set(null);
    this.sessionBackfillStatus.set(null);
    this.isBackfillVisible.set(false);
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
}
