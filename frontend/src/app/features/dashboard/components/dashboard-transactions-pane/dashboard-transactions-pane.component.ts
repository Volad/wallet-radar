import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, Input, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { COLORS } from '../../../../core/data/dashboard.mock';
import {
  BridgeStatus,
  EditableTransactionDraft,
  EditableTransactionFlow,
  FlowRole,
  NetworkId,
  NetworkInfo,
  PriceSource,
  PRICE_SOURCES,
  TransactionItem,
  TransactionStatus,
  TransactionType,
  TRANSACTION_TYPES,
  WalletId,
  WalletInfo,
} from '../../../../core/models/dashboard.models';

type SaveState = 'idle' | 'saving' | 'saved';
type PillVariant = 'def' | 'cyan' | 'green' | 'red' | 'amber' | 'purple' | 'blue';
type BridgeFilter = 'ALL' | BridgeStatus;

@Component({
  selector: 'wr-dashboard-transactions-pane',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './dashboard-transactions-pane.component.html',
  styleUrl: './dashboard-transactions-pane.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DashboardTransactionsPaneComponent {
  private readonly walletsSignal = signal<ReadonlyArray<WalletInfo>>([]);
  private readonly networksSignal = signal<ReadonlyArray<NetworkInfo>>([]);
  private readonly selectedWalletIdsSignal = signal<ReadonlySet<WalletId>>(new Set<WalletId>());
  private readonly selectedNetworkIdsSignal = signal<ReadonlySet<NetworkId>>(new Set<NetworkId>());

  @Input({ required: true }) set wallets(value: ReadonlyArray<WalletInfo>) {
    this.walletsSignal.set(value ?? []);
  }

  @Input({ required: true }) set networks(value: ReadonlyArray<NetworkInfo>) {
    this.networksSignal.set(value ?? []);
  }

  @Input({ required: true }) set selectedWalletIds(value: ReadonlySet<WalletId>) {
    this.selectedWalletIdsSignal.set(value ?? new Set<WalletId>());
  }

  @Input({ required: true }) set selectedNetworkIds(value: ReadonlySet<NetworkId>) {
    this.selectedNetworkIdsSignal.set(value ?? new Set<NetworkId>());
  }

  @Input({ required: true }) set sourceTransactions(value: ReadonlyArray<TransactionItem>) {
    this.transactions.set([...value]);
    this.cancelTransactionEdit();
  }

  @Input() isLoading = false;
  @Input() isReadOnly = false;
  @Input() emptyStateMessage = 'No transactions for current filters.';

  readonly colors = COLORS;
  readonly transactionTypes = TRANSACTION_TYPES;
  readonly priceSources = PRICE_SOURCES;
  readonly flowRoles: ReadonlyArray<FlowRole> = ['BUY', 'SELL', 'FEE', 'TRANSFER'];
  readonly bridgeFilters: ReadonlyArray<BridgeFilter> = ['ALL', 'BRIDGE_OUT', 'BRIDGE_IN', 'MATCHED', 'REVIEW'];

  readonly transactionSearch = signal('');
  readonly bridgeStatusFilter = signal<BridgeFilter>('ALL');
  readonly expandedTransactionIds = signal<ReadonlySet<string>>(new Set<string>());
  readonly editingTransactionId = signal<string | null>(null);
  readonly editingDraft = signal<EditableTransactionDraft | null>(null);
  readonly saveState = signal<SaveState>('idle');
  readonly transactions = signal<ReadonlyArray<TransactionItem>>([]);
  readonly hasBridgeStatuses = computed(() => this.transactions().some((tx) => tx.bridgeStatus !== null && tx.bridgeStatus !== undefined));

  readonly filteredTransactions = computed(() => {
    const selectedWallets = this.selectedWalletIdsSignal();
    const selectedNetworks = this.selectedNetworkIdsSignal();
    const searchTerm = this.transactionSearch().trim().toLowerCase();
    const bridgeFilter = this.bridgeStatusFilter();

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
      if (bridgeFilter !== 'ALL' && tx.bridgeStatus !== bridgeFilter) {
        return false;
      }

      return true;
    });
  });

  readonly missingPricesInDraft = computed(() => {
    const draft = this.editingDraft();
    if (draft === null) {
      return 0;
    }
    return draft.flows.filter((flow) => flow.priceUsd === null).length;
  });

  setTransactionSearch(value: string): void {
    this.transactionSearch.set(value);
  }

  setBridgeStatusFilter(value: BridgeFilter): void {
    this.bridgeStatusFilter.set(value);
  }

  isBridgeFilterActive(value: BridgeFilter): boolean {
    return this.bridgeStatusFilter() === value;
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
    if (this.isReadOnly) {
      return;
    }

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

  getNetworkById(networkId: NetworkId): NetworkInfo | null {
    return this.networksSignal().find((network) => network.id === networkId) ?? null;
  }

  getWalletById(walletId: WalletId): WalletInfo | null {
    return this.walletsSignal().find((wallet) => wallet.id === walletId) ?? null;
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

  getBridgeStatusVariant(status: BridgeStatus): PillVariant {
    if (status === 'MATCHED') {
      return 'green';
    }
    if (status === 'BRIDGE_OUT') {
      return 'cyan';
    }
    if (status === 'BRIDGE_IN') {
      return 'blue';
    }
    return 'purple';
  }

  getBridgeStatusLabel(status: BridgeStatus): string {
    return status.replaceAll('_', ' ');
  }

  getBridgeStatusDescription(status: BridgeStatus): string {
    if (status === 'MATCHED') {
      return 'Matched bridge legs across this session.';
    }
    if (status === 'BRIDGE_OUT') {
      return 'Outgoing bridge leg detected. Opposite leg is not matched yet.';
    }
    if (status === 'BRIDGE_IN') {
      return 'Incoming bridge leg detected. Source leg is not matched yet.';
    }
    return 'Bridge matching is ambiguous. Review the opposite-leg candidates before accounting.';
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
    return tx.status === 'PENDING_PRICE' || tx.status === 'NEEDS_REVIEW' || tx.bridgeStatus === 'REVIEW';
  }

  getPrimaryPositiveFlow(tx: TransactionItem) {
    return tx.flows.find((flow) => this.getSignedQuantity(flow) > 0) ?? null;
  }

  getPrimaryNegativeFlow(tx: TransactionItem) {
    return tx.flows.find((flow) => this.getSignedQuantity(flow) < 0) ?? null;
  }

  getSignedQuantity(flow: { readonly role: FlowRole; readonly quantity: number; readonly signedQuantity?: number }): number {
    if (flow.signedQuantity !== undefined) {
      return flow.signedQuantity;
    }
    if (flow.role === 'SELL' || flow.role === 'FEE') {
      return -Math.abs(flow.quantity);
    }
    return Math.abs(flow.quantity);
  }

  getFlowPrefix(flow: { readonly role: FlowRole; readonly quantity: number; readonly signedQuantity?: number }): '+' | '-' {
    return this.getSignedQuantity(flow) < 0 ? '-' : '+';
  }

  formatUsd(value: number): string {
    const absolute = Math.abs(value);
    const compact = absolute >= 1000 ? `$${(absolute / 1000).toFixed(1)}k` : `$${absolute.toFixed(2)}`;
    return value < 0 ? `-${compact}` : compact;
  }

  formatQuantity(value: number): string {
    const absolute = Math.abs(value);
    if (absolute >= 1000) {
      return absolute.toLocaleString();
    }

    if (absolute >= 1) {
      return absolute.toFixed(4).replace(/0+$/u, '').replace(/\.$/u, '');
    }

    return absolute.toString();
  }

  formatTimestampLabel(value: string): string {
    const parsed = new Date(value);
    if (Number.isNaN(parsed.getTime())) {
      return value;
    }
    return parsed.toISOString().slice(0, 10);
  }

  prettifyLabel(value: string): string {
    return value.replaceAll('_', ' ');
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
