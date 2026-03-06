import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import {
  FormArray,
  FormControl,
  FormGroup,
  FormsModule,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import {
  EMPTY,
  Subscription,
  catchError,
  finalize,
  map,
  of,
  startWith,
  switchMap,
  timer,
} from 'rxjs';

import { COLORS, EMPTY_DASHBOARD_DATA } from '../../core/data/dashboard.mock';
import {
  DashboardSection,
  DashboardViewState,
  EditableTransactionDraft,
  EditableTransactionFlow,
  FlowRole,
  NetworkId,
  PriceSource,
  PRICE_SOURCES,
  SectionMeta,
  TransactionItem,
  TransactionStatus,
  TransactionType,
  TRANSACTION_TYPES,
  WalletId,
} from '../../core/models/dashboard.models';
import { DashboardDataService } from '../../core/services/dashboard-data.service';
import {
  AddSessionRequest,
  EvmNetworkId,
  SessionBackfillAggregateStatus,
  SessionBackfillStatusResponse,
  SUPPORTED_EVM_NETWORKS,
} from '../../core/models/wallet-api.models';
import { WalletApiService } from '../../core/services/wallet-api.service';
import { SessionStorageService } from '../../core/services/session-storage.service';

type LpTab = 'all' | 'open' | 'closed';
type SaveState = 'idle' | 'saving' | 'saved';
type PillVariant = 'def' | 'cyan' | 'green' | 'red' | 'amber' | 'purple' | 'blue';
type WalletSubmitState = 'idle' | 'submitting' | 'success' | 'error';
type WalletAddressState = 'empty' | 'ok' | 'warn' | 'error';
type WalletFormGroup = FormGroup<{
  address: FormControl<string>;
  label: FormControl<string>;
  color: FormControl<string>;
}>;
interface WalletAddressEvaluation {
  readonly state: WalletAddressState;
  readonly message: string | null;
}
interface EvmNetworkPresentation {
  readonly id: EvmNetworkId;
  readonly icon: string;
  readonly label: string;
  readonly color: string;
}

const EVM_ADDRESS_PATTERN = /^0x[a-fA-F0-9]{40}$/u;
const WALLET_COLOR_PALETTE: ReadonlyArray<string> = [
  COLORS.cyan,
  COLORS.purple,
  COLORS.green,
  COLORS.amber,
  '#60a5fa',
  '#f472b6',
  '#34d399',
];
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
];

@Component({
  selector: 'wr-dashboard',
  standalone: true,
  imports: [CommonModule, FormsModule, ReactiveFormsModule],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DashboardComponent {
  private readonly dashboardDataService = inject(DashboardDataService);
  private readonly walletApiService = inject(WalletApiService);
  private readonly sessionStorageService = inject(SessionStorageService);
  private readonly formBuilder = inject(NonNullableFormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  readonly colors = COLORS;
  readonly transactionTypes = TRANSACTION_TYPES;
  readonly priceSources = PRICE_SOURCES;
  readonly lpTabs: ReadonlyArray<LpTab> = ['all', 'open', 'closed'];
  readonly flowRoles: ReadonlyArray<FlowRole> = ['BUY', 'SELL', 'FEE', 'TRANSFER'];
  readonly supportedEvmNetworks = SUPPORTED_EVM_NETWORKS;
  readonly evmNetworksPresentation = EVM_NETWORKS_PRESENTATION;

  readonly addWalletsForm = this.formBuilder.group({
    wallets: this.formBuilder.array([this.createWalletFormGroup(0)]),
  });
  readonly walletFormSnapshot = toSignal(
    this.addWalletsForm.valueChanges.pipe(startWith(this.addWalletsForm.getRawValue())),
    { requireSync: true }
  );

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
  readonly transactionSearch = signal('');

  readonly expandedTransactionIds = signal<ReadonlySet<string>>(new Set<string>());
  readonly editingTransactionId = signal<string | null>(null);
  readonly editingDraft = signal<EditableTransactionDraft | null>(null);
  readonly saveState = signal<SaveState>('idle');

  readonly transactions = signal<ReadonlyArray<TransactionItem>>([]);
  private readonly isTransactionsHydrated = signal(false);

  private readonly hydrateTransactions = effect(
    () => {
      const state = this.viewState();
      if (state.status !== 'success' || this.isTransactionsHydrated()) {
        return;
      }

      this.transactions.set(state.data.transactions);
      this.isTransactionsHydrated.set(true);
    }
  );

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

  readonly filteredTransactions = computed(() => {
    const selectedWallets = this.selectedWalletFilter();
    const selectedNetworks = this.selectedNetworkFilter();
    const searchTerm = this.transactionSearch().trim().toLowerCase();

    return this.transactions().filter((tx) => {
      if (selectedWallets.size > 0 && !selectedWallets.has(tx.walletId)) {
        return false;
      }
      if (selectedNetworks.size > 0 && !selectedNetworks.has(tx.networkId)) {
        return false;
      }
      if (searchTerm.length > 0) {
        const matchHash = tx.hash.toLowerCase().includes(searchTerm);
        const matchSymbol = tx.symbol.toLowerCase().includes(searchTerm);
        if (!matchHash && !matchSymbol) {
          return false;
        }
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

  readonly missingPricesInDraft = computed(() => {
    const draft = this.editingDraft();
    if (draft === null) {
      return 0;
    }

    return draft.flows.filter((flow) => flow.priceUsd === null).length;
  });

  readonly walletRows = computed(() => this.walletFormArray.controls);
  readonly walletValidationSummary = computed(() => {
    this.walletFormSnapshot();

    let filled = 0;
    let ready = 0;

    this.walletRows().forEach((walletGroup, index) => {
      const evaluation = this.evaluateWalletAddress(walletGroup.controls.address, index);
      if (evaluation.state === 'empty') {
        return;
      }

      filled += 1;
      if (evaluation.state === 'ok' || evaluation.state === 'warn') {
        ready += 1;
      }
    });

    return { filled, ready };
  });
  readonly canSubmitWallets = computed(() => {
    const summary = this.walletValidationSummary();
    return summary.ready > 0 && this.supportedEvmNetworks.length > 0 && !this.isWalletSubmitBusy();
  });

  readonly isWalletSubmitBusy = computed(() => this.walletSubmitState() === 'submitting');
  private backfillPollingSubscription: Subscription | null = null;

  constructor() {
    this.destroyRef.onDestroy(() => {
      this.stopBackfillPolling();
    });
    this.restoreSessionBackfillIfNeeded();
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

  setTransactionSearch(value: string): void {
    this.transactionSearch.set(value);
  }

  setLpTab(tab: LpTab): void {
    this.lpTab.set(tab);
  }

  setHideDustAssets(value: boolean): void {
    this.hideDustAssets.set(value);
  }

  openAddWalletDialog(): void {
    this.resetWalletSubmissionState();
    this.resetWalletForm();
    this.isAddWalletDialogOpen.set(true);
  }

  closeAddWalletDialog(): void {
    if (this.isWalletSubmitBusy()) {
      return;
    }

    this.isAddWalletDialogOpen.set(false);
  }

  addWalletField(): void {
    this.walletFormArray.push(this.createWalletFormGroup(this.walletFormArray.length));
  }

  removeWalletField(index: number): void {
    if (this.walletFormArray.length <= 1) {
      return;
    }

    this.walletFormArray.removeAt(index);
  }

  submitWallets(): void {
    if (!this.canSubmitWallets()) {
      this.addWalletsForm.markAllAsTouched();
      if (this.walletValidationSummary().ready === 0) {
        this.walletSubmitState.set('error');
        this.walletSubmitMessage.set('Add at least one valid wallet address.');
      }
      return;
    }

    const requestPayload = this.createAddSessionRequest();
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
        }),
        finalize(() => {
          if (this.walletSubmitState() === 'submitting') {
            this.walletSubmitState.set('success');
          }
        })
      )
      .subscribe(({ sessionId, message }) => {
        this.sessionStorageService.setSessionId(sessionId);
        this.currentSessionId.set(sessionId);
        this.walletSubmitMessage.set(message);
        this.isBackfillVisible.set(true);
        this.sessionBackfillStatus.set(null);
        this.startBackfillPolling(sessionId);
        this.isAddWalletDialogOpen.set(false);
        this.resetWalletForm();
      });
  }

  walletAddressState(control: FormControl<string>, index: number): WalletAddressState {
    return this.evaluateWalletAddress(control, index).state;
  }

  walletAddressMessage(control: FormControl<string>, index: number): string | null {
    return this.evaluateWalletAddress(control, index).message;
  }

  showWalletAddressMessage(control: FormControl<string>, index: number): boolean {
    const evaluation = this.evaluateWalletAddress(control, index);
    if (evaluation.message === null) {
      return false;
    }
    if (evaluation.state === 'warn') {
      return true;
    }

    return control.touched || control.dirty;
  }

  isWalletSelected(walletId: WalletId): boolean {
    return this.selectedWalletIds().has(walletId);
  }

  isNetworkSelected(networkId: NetworkId): boolean {
    return this.selectedNetworkIds().has(networkId);
  }

  toggleTransactionExpanded(transactionId: string): void {
    if (this.editingTransactionId() === transactionId) {
      return;
    }

    this.expandedTransactionIds.update((existing) => {
      const copy = new Set(existing);
      if (copy.has(transactionId)) {
        copy.delete(transactionId);
      } else {
        copy.add(transactionId);
      }
      return copy;
    });
  }

  isTransactionExpanded(transactionId: string): boolean {
    return this.expandedTransactionIds().has(transactionId);
  }

  isTransactionEditing(transactionId: string): boolean {
    return this.editingTransactionId() === transactionId;
  }

  startTransactionEdit(tx: TransactionItem, event?: Event): void {
    event?.stopPropagation();

    this.editingTransactionId.set(tx.id);
    this.editingDraft.set(this.toEditableDraft(tx));
    this.saveState.set('idle');

    this.expandedTransactionIds.update((existing) => {
      const copy = new Set(existing);
      copy.add(tx.id);
      return copy;
    });
  }

  cancelTransactionEdit(): void {
    this.editingTransactionId.set(null);
    this.editingDraft.set(null);
    this.saveState.set('idle');
  }

  saveTransactionEdit(): void {
    const editingId = this.editingTransactionId();
    const draft = this.editingDraft();

    if (editingId === null || draft === null) {
      return;
    }

    this.saveState.set('saving');

    window.setTimeout(() => {
      this.transactions.update((txs) => {
        return txs.map((tx) => {
          if (tx.id !== editingId) {
            return tx;
          }

          return {
            ...tx,
            type: draft.type,
            timestamp: draft.timestamp,
            note: draft.note.trim() || undefined,
            flows: draft.flows.map((flow) => ({
              role: flow.role,
              symbol: flow.symbol.trim().toUpperCase(),
              quantity: flow.quantity ?? 0,
              priceUsd: flow.priceUsd,
              source: flow.source,
            })),
          };
        });
      });

      this.saveState.set('saved');
      window.setTimeout(() => {
        this.cancelTransactionEdit();
      }, 420);
    }, 760);
  }

  setDraftType(value: TransactionType): void {
    this.updateDraft((draft) => ({ ...draft, type: value }));
  }

  setDraftTimestamp(value: string): void {
    this.updateDraft((draft) => ({ ...draft, timestamp: value }));
  }

  setDraftNote(value: string): void {
    this.updateDraft((draft) => ({ ...draft, note: value }));
  }

  setDraftFlowRole(index: number, role: FlowRole): void {
    this.updateDraftFlow(index, (flow) => ({ ...flow, role }));
  }

  setDraftFlowSymbol(index: number, symbol: string): void {
    this.updateDraftFlow(index, (flow) => ({ ...flow, symbol }));
  }

  setDraftFlowSource(index: number, source: PriceSource): void {
    this.updateDraftFlow(index, (flow) => ({ ...flow, source }));
  }

  setDraftFlowQuantity(index: number, value: string): void {
    this.updateDraftFlow(index, (flow) => ({ ...flow, quantity: this.parseNumber(value) }));
  }

  setDraftFlowPrice(index: number, value: string): void {
    this.updateDraftFlow(index, (flow) => ({ ...flow, priceUsd: this.parseNumber(value) }));
  }

  addDraftFlow(): void {
    this.updateDraft((draft) => ({
      ...draft,
      flows: [
        ...draft.flows,
        {
          role: 'BUY',
          symbol: '',
          quantity: null,
          priceUsd: null,
          source: 'MANUAL',
        },
      ],
    }));
  }

  removeDraftFlow(index: number): void {
    this.updateDraft((draft) => {
      if (draft.flows.length <= 1) {
        return draft;
      }

      return {
        ...draft,
        flows: draft.flows.filter((_, currentIndex) => currentIndex !== index),
      };
    });
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

  getStatusVariant(status: TransactionStatus): PillVariant {
    if (status === 'CONFIRMED') {
      return 'green';
    }
    if (status === 'PENDING_PRICE') {
      return 'amber';
    }

    return 'red';
  }

  getPriceSourceColor(source: PriceSource): string {
    if (source === 'STABLECOIN') {
      return COLORS.green;
    }
    if (source === 'SWAP_DERIVED') {
      return COLORS.cyan;
    }
    if (source === 'COINGECKO') {
      return COLORS.purple;
    }
    if (source === 'MANUAL') {
      return COLORS.amber;
    }

    return COLORS.red;
  }

  getPillClass(variant: PillVariant): string {
    return `pill pill-${variant}`;
  }

  getFlowColor(role: FlowRole): string {
    if (role === 'BUY') {
      return COLORS.green;
    }
    if (role === 'SELL') {
      return COLORS.red;
    }
    if (role === 'FEE') {
      return COLORS.amber;
    }

    return COLORS.textSubtle;
  }

  hasMissingPrice(tx: TransactionItem): boolean {
    return tx.flows.some((flow) => flow.priceUsd === null);
  }

  isPendingReview(tx: TransactionItem): boolean {
    return tx.status === 'PENDING_PRICE' || tx.status === 'NEEDS_REVIEW';
  }

  getFirstFlowByRole(tx: TransactionItem, role: FlowRole) {
    return tx.flows.find((flow) => flow.role === role) ?? null;
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

  prettifyLabel(value: string): string {
    return value.replaceAll('_', ' ');
  }

  trackByString(index: number, value: string): string {
    return `${value}-${index}`;
  }

  trackByIndex(index: number): number {
    return index;
  }

  private get walletFormArray(): FormArray<WalletFormGroup> {
    return this.addWalletsForm.controls.wallets;
  }

  private createWalletFormGroup(index: number): WalletFormGroup {
    return this.formBuilder.group({
      address: this.formBuilder.control('', {
        validators: [Validators.pattern(EVM_ADDRESS_PATTERN)],
      }),
      label: this.formBuilder.control(`Wallet ${index + 1}`),
      color: this.formBuilder.control(WALLET_COLOR_PALETTE[index % WALLET_COLOR_PALETTE.length]),
    });
  }

  private evaluateWalletAddress(control: FormControl<string>, index: number): WalletAddressEvaluation {
    const address = control.value.trim();
    if (address.length === 0) {
      return {
        state: 'empty',
        message: null,
      };
    }

    if (!EVM_ADDRESS_PATTERN.test(address)) {
      return {
        state: 'error',
        message: 'Invalid EVM address',
      };
    }

    const lowerCaseAddress = address.toLowerCase();
    const isDuplicateInInput = this.walletFormArray.controls.some((walletGroup, currentIndex) => {
      if (currentIndex === index) {
        return false;
      }
      return walletGroup.controls.address.value.trim().toLowerCase() === lowerCaseAddress;
    });

    if (isDuplicateInInput) {
      return {
        state: 'error',
        message: 'Duplicate address in current list',
      };
    }

    const isExistingWallet = this.data().wallets.some(
      (wallet) => wallet.address.trim().toLowerCase() === lowerCaseAddress
    );
    if (isExistingWallet) {
      return {
        state: 'warn',
        message: 'Already tracked — new networks will be added',
      };
    }

    return {
      state: 'ok',
      message: null,
    };
  }

  private createAddSessionRequest(): AddSessionRequest {
    const sessionId = this.sessionStorageService.getSessionId() ?? crypto.randomUUID();
    const wallets = this.walletFormArray.controls
      .map((walletForm, index) => {
        const evaluation = this.evaluateWalletAddress(walletForm.controls.address, index);
        return {
          state: evaluation.state,
          address: walletForm.controls.address.value.trim(),
          label: walletForm.controls.label.value.trim() || `Wallet ${index + 1}`,
          color: walletForm.controls.color.value,
          networks: [...this.supportedEvmNetworks] as ReadonlyArray<EvmNetworkId>,
        };
      })
      .filter((wallet) => wallet.state === 'ok' || wallet.state === 'warn')
      .map((wallet) => ({
        address: wallet.address,
        label: wallet.label,
        color: wallet.color,
        networks: wallet.networks,
      }));

    return {
      wallets,
      sessionId,
    };
  }

  private resetWalletForm(): void {
    while (this.walletFormArray.length > 1) {
      this.walletFormArray.removeAt(this.walletFormArray.length - 1);
    }

    const firstWalletForm = this.walletFormArray.controls[0];
    firstWalletForm.controls.address.setValue('');
    firstWalletForm.controls.label.setValue('Wallet 1');
    firstWalletForm.controls.color.setValue(WALLET_COLOR_PALETTE[0]);

    firstWalletForm.controls.address.markAsPristine();
    firstWalletForm.controls.address.markAsUntouched();
    firstWalletForm.controls.label.markAsPristine();
    firstWalletForm.controls.label.markAsUntouched();
    firstWalletForm.controls.color.markAsPristine();
    firstWalletForm.controls.color.markAsUntouched();
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

  private updateDraft(transformer: (draft: EditableTransactionDraft) => EditableTransactionDraft): void {
    const currentDraft = this.editingDraft();
    if (currentDraft === null) {
      return;
    }

    this.editingDraft.set(transformer(currentDraft));
  }

  private updateDraftFlow(
    index: number,
    transformer: (flow: EditableTransactionFlow) => EditableTransactionFlow
  ): void {
    this.updateDraft((draft) => {
      const nextFlows = draft.flows.map((flow, currentIndex) => {
        if (currentIndex !== index) {
          return flow;
        }
        return transformer(flow);
      });

      return {
        ...draft,
        flows: nextFlows,
      };
    });
  }

  private toEditableDraft(transaction: TransactionItem): EditableTransactionDraft {
    return {
      id: transaction.id,
      type: transaction.type,
      timestamp: transaction.timestamp,
      note: transaction.note ?? '',
      flows: transaction.flows.map((flow) => ({
        role: flow.role,
        symbol: flow.symbol,
        quantity: flow.quantity,
        priceUsd: flow.priceUsd,
        source: flow.source,
      })),
    };
  }

  private parseNumber(value: string): number | null {
    const normalized = value.trim();
    if (normalized.length === 0) {
      return null;
    }

    const parsed = Number(normalized);
    return Number.isFinite(parsed) ? parsed : null;
  }
}
