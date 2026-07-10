import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, EventEmitter, HostListener, Input, Output, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SmartAmountComponent } from '../../../../core/components/smart-amount/smart-amount.component';

import { COLORS } from '../../../../core/data/dashboard.constants';
import {
  ALL_TRANSACTION_CATEGORIES,
  BridgeStatus,
  DEFAULT_TRANSACTION_CATEGORIES,
  EditableTransactionDraft,
  EditableTransactionFlow,
  FlowRole,
  NetworkId,
  NetworkInfo,
  PriceSource,
  PRICE_SOURCES,
  TransactionCategory,
  TransactionFlow,
  TransactionItem,
  TransactionStatus,
  TransactionType,
  TRANSACTION_TYPES,
  WalletId,
  WalletInfo,
} from '../../../../core/models/dashboard.models';
import { formatDateTimeWithSeconds } from '../../../../core/utils/date-time.util';
import { CopyHashComponent } from '../../../../core/components/copy-hash/copy-hash.component';

type SaveState = 'idle' | 'saving' | 'saved';
type PillVariant = 'def' | 'cyan' | 'green' | 'red' | 'amber' | 'purple' | 'blue';

@Component({
  selector: 'wr-dashboard-transactions-pane',
  standalone: true,
  imports: [CommonModule, FormsModule, CopyHashComponent, SmartAmountComponent],
  templateUrl: './dashboard-transactions-pane.component.html',
  styleUrl: './dashboard-transactions-pane.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DashboardTransactionsPaneComponent {
  private readonly walletsSignal = signal<ReadonlyArray<WalletInfo>>([]);
  private readonly networksSignal = signal<ReadonlyArray<NetworkInfo>>([]);

  @Input({ required: true }) set wallets(value: ReadonlyArray<WalletInfo>) {
    this.walletsSignal.set(value ?? []);
  }

  @Input({ required: true }) set networks(value: ReadonlyArray<NetworkInfo>) {
    this.networksSignal.set(value ?? []);
  }

  @Input() set transactionSearchValue(value: string) {
    this.transactionSearch.set(value ?? '');
  }

  @Input() set enabledCategories(value: ReadonlySet<TransactionCategory>) {
    this.enabledCategoriesSignal.set(value ?? new Set(DEFAULT_TRANSACTION_CATEGORIES));
  }

  @Input() set page(value: number) {
    this.pageSignal.set(Math.max(0, value ?? 0));
  }

  @Input() pageSize = 50;

  @Input() set totalCount(value: number) {
    this.totalCountSignal.set(Math.max(0, value ?? 0));
  }

  @Input({ required: true }) set sourceTransactions(value: ReadonlyArray<TransactionItem>) {
    this.transactions.set([...value]);
    this.cancelTransactionEdit();
  }

  @Input() isLoading = false;
  @Input() isReadOnly = false;
  @Input() emptyStateMessage = 'No transactions for current filters.';

  @Output() readonly transactionSearchChange = new EventEmitter<string>();
  @Output() readonly categoriesChange = new EventEmitter<ReadonlySet<TransactionCategory>>();
  @Output() readonly pageChange = new EventEmitter<number>();

  readonly colors = COLORS;
  readonly transactionTypes = TRANSACTION_TYPES;
  readonly priceSources = PRICE_SOURCES;
  readonly flowRoles: ReadonlyArray<FlowRole> = ['BUY', 'SELL', 'FEE', 'TRANSFER'];

  readonly INLINE_CATEGORIES: ReadonlyArray<TransactionCategory> = [
    'SWAP', 'LP', 'LENDING', 'BRIDGE', 'EXTERNAL_TRANSFER', 'INTERNAL_TRANSFER',
  ];
  readonly OVERFLOW_CATEGORIES: ReadonlyArray<TransactionCategory> = [
    'NEED_REVIEW', 'REWARD', 'DUST', 'SPAM',
  ];
  private readonly DEFAULT_OVERFLOW = new Set<TransactionCategory>(['NEED_REVIEW', 'REWARD']);

  readonly transactionSearch = signal('');
  readonly enabledCategoriesSignal = signal<ReadonlySet<TransactionCategory>>(
    new Set(DEFAULT_TRANSACTION_CATEGORIES)
  );
  readonly overflowOpen = signal(false);
  readonly pageSignal = signal(0);
  readonly totalCountSignal = signal(0);
  readonly expandedTransactionIds = signal<ReadonlySet<string>>(new Set<string>());
  readonly editingTransactionId = signal<string | null>(null);
  readonly editingDraft = signal<EditableTransactionDraft | null>(null);
  readonly saveState = signal<SaveState>('idle');
  readonly transactions = signal<ReadonlyArray<TransactionItem>>([]);
  readonly hasBridgeStatuses = computed(() => this.transactions().some((tx) => tx.bridgeStatus !== null && tx.bridgeStatus !== undefined));

  readonly overflowBadgeCount = computed(() => {
    const enabled = this.enabledCategoriesSignal();
    let count = 0;
    for (const cat of this.OVERFLOW_CATEGORIES) {
      if (enabled.has(cat) !== this.DEFAULT_OVERFLOW.has(cat)) count++;
    }
    return count;
  });
  readonly totalPages = computed(() => {
    const total = this.totalCountSignal();
    return total === 0 ? 1 : Math.ceil(total / this.pageSize);
  });
  readonly paginatedTransactions = computed(() => this.transactions());
  readonly pageStart = computed(() => {
    if (this.totalCountSignal() === 0) {
      return 0;
    }
    return this.pageSignal() * this.pageSize + 1;
  });
  readonly pageEnd = computed(() => Math.min(this.pageStart() + this.transactions().length - 1, this.totalCountSignal()));

  readonly missingPricesInDraft = computed(() => {
    const draft = this.editingDraft();
    if (draft === null) {
      return 0;
    }
    return draft.flows.filter((flow) => flow.priceUsd === null).length;
  });

  setTransactionSearch(value: string): void {
    this.transactionSearch.set(value);
    this.transactionSearchChange.emit(value);
  }

  toggleCategory(cat: TransactionCategory, event?: Event): void {
    event?.stopPropagation();
    const current = this.enabledCategoriesSignal();
    const next = new Set(current);
    if (next.has(cat)) {
      next.delete(cat);
    } else {
      next.add(cat);
    }
    this.enabledCategoriesSignal.set(next);
    this.categoriesChange.emit(next);
  }

  isCategoryEnabled(cat: TransactionCategory): boolean {
    return this.enabledCategoriesSignal().has(cat);
  }

  categoryLabel(cat: TransactionCategory): string {
    switch (cat) {
      case 'SWAP': return 'Swap';
      case 'LP': return 'LP';
      case 'LENDING': return 'Lending';
      case 'BRIDGE': return 'Bridge';
      case 'EXTERNAL_TRANSFER': return 'Ext. Transfer';
      case 'INTERNAL_TRANSFER': return 'Int. Transfer';
      case 'NEED_REVIEW': return 'Need Review';
      case 'REWARD': return 'Reward';
      case 'DUST': return 'Dust';
      case 'SPAM': return 'Spam';
    }
  }

  toggleOverflow(event?: Event): void {
    event?.stopPropagation();
    this.overflowOpen.update((v) => !v);
  }

  @HostListener('document:click')
  closeOverflow(): void {
    if (this.overflowOpen()) {
      this.overflowOpen.set(false);
    }
  }

  previousPage(event?: Event): void {
    event?.preventDefault();
    event?.stopPropagation();
    const nextPage = Math.max(0, this.pageSignal() - 1);
    this.pageSignal.set(nextPage);
    this.pageChange.emit(nextPage);
  }

  nextPage(event?: Event): void {
    event?.preventDefault();
    event?.stopPropagation();
    const nextPage = Math.min(this.totalPages() - 1, this.pageSignal() + 1);
    this.pageSignal.set(nextPage);
    this.pageChange.emit(nextPage);
  }

  currentPageNumber(): number {
    return this.totalPages() === 0 ? 0 : Math.min(this.pageSignal(), this.totalPages() - 1) + 1;
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

  txCounterpartyRef(tx: TransactionItem): string | null {
    const matchedCounterparty = tx.matchedCounterparty?.trim();
    if (matchedCounterparty === undefined || matchedCounterparty === null || matchedCounterparty.length === 0) {
      return null;
    }
    return matchedCounterparty;
  }

  txCounterpartyDirection(tx: TransactionItem): string | null {
    const matchedCounterparty = this.txCounterpartyRef(tx);
    if (matchedCounterparty === null) {
      return null;
    }
    return this.isInboundTransaction(tx) ? 'From' : 'To';
  }

  isExternalLedgerAssociation(tx: TransactionItem): boolean {
    return this.externalLedgerRef(tx) !== null;
  }

  isExternalLedgerWallet(tx: TransactionItem): boolean {
    return this.isExternalLedgerRef(tx.walletId);
  }

  counterpartyTooltip(tx: TransactionItem): string | null {
    return this.externalLedgerRef(tx) ?? this.txCounterpartyRef(tx);
  }

  private externalLedgerRef(tx: TransactionItem): string | null {
    const matchedCounterparty = this.txCounterpartyRef(tx);
    if (matchedCounterparty !== null && this.isExternalLedgerRef(matchedCounterparty)) {
      return matchedCounterparty;
    }
    return this.isExternalLedgerRef(tx.walletId) ? tx.walletId : null;
  }

  private isExternalLedgerRef(ref: string | null | undefined): ref is string {
    if (ref === null || ref === undefined) {
      return false;
    }
    const normalized = ref.trim();
    return normalized.length > 0 && !normalized.startsWith('0x') && normalized.includes(':');
  }

  walletTooltip(tx: TransactionItem): string {
    return this.getWalletById(tx.walletId)?.label ?? tx.walletId;
  }

  networkTooltip(tx: TransactionItem): string {
    return this.getNetworkById(tx.networkId)?.label ?? tx.networkId;
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

  getPriceSourceColor(source: PriceSource | null): string {
    if (source === null) {
      return COLORS.textSubtle;
    }
    if (source === 'STABLECOIN') {
      return COLORS.green;
    }
    if (source === 'SWAP_DERIVED') {
      return COLORS.cyan;
    }
    if (source === 'COINGECKO') {
      return COLORS.purple;
    }
    if (source === 'BYBIT') {
      return '#f4b53f';
    }
    if (source === 'BINANCE') {
      return '#f0b90b';
    }
    if (source === 'ECB') {
      return '#60a5fa';
    }
    if (source === 'EXECUTION') {
      return '#22c55e';
    }
    if (source === 'WRAPPER') {
      return '#a78bfa';
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
    return tx.issue === 'missing_price';
  }

  isPendingReview(tx: TransactionItem): boolean {
    return tx.status === 'PENDING_PRICE' || tx.status === 'NEEDS_REVIEW' || tx.bridgeStatus === 'REVIEW';
  }

  isSpamTransaction(tx: TransactionItem): boolean {
    return tx.issue === 'spam';
  }

  getPrimaryPositiveFlow(tx: TransactionItem) {
    return tx.flows.find((flow) => this.getSignedQuantity(flow) > 0) ?? null;
  }

  getPrimaryNegativeFlow(tx: TransactionItem) {
    return tx.flows.find((flow) => this.getSignedQuantity(flow) < 0) ?? null;
  }

  getPreviewFlows(tx: TransactionItem): ReadonlyArray<TransactionFlow> {
    const materialFlows = tx.flows.filter((flow) => flow.role !== 'FEE' && flow.quantity !== 0);
    const outbound = materialFlows.filter((flow) => this.getSignedQuantity(flow) < 0);
    const inbound = materialFlows.filter((flow) => this.getSignedQuantity(flow) > 0);
    return (outbound.length > 0 ? outbound : inbound).slice(0, 1);
  }

  getHiddenPreviewFlowCount(tx: TransactionItem): number {
    const materialFlowCount = tx.flows.filter((flow) => flow.role !== 'FEE' && flow.quantity !== 0).length;
    return Math.max(0, materialFlowCount - this.getPreviewFlows(tx).length);
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

  getAbsoluteSignedQuantity(flow: { readonly role: FlowRole; readonly quantity: number; readonly signedQuantity?: number }): number {
    return Math.abs(this.getSignedQuantity(flow));
  }

  getAbsoluteQuantity(value: number): number {
    return Math.abs(value);
  }

  getFlowPrefix(flow: { readonly role: FlowRole; readonly quantity: number; readonly signedQuantity?: number }): '+' | '-' {
    return this.getSignedQuantity(flow) < 0 ? '-' : '+';
  }

  shortReference(value: string | null | undefined): string {
    const normalized = value?.trim() ?? '';
    if (normalized.length <= 16) {
      return normalized;
    }
    if (normalized.includes(':') && normalized.length > 28) {
      return `${normalized.slice(0, 18)}…${normalized.slice(-8)}`;
    }
    return `${normalized.slice(0, 10)}…${normalized.slice(-6)}`;
  }

  formatUsd(value: number): string {
    const absolute = Math.abs(value);
    const compact = absolute >= 1000 ? `$${(absolute / 1000).toFixed(1)}k` : `$${absolute.toFixed(2)}`;
    return value < 0 ? `-${compact}` : compact;
  }

  formatQuantity(value: number): string {
    const absolute = Math.abs(value);
    if (absolute >= 1000) {
      return absolute.toLocaleString(undefined, {
        minimumFractionDigits: 0,
        maximumFractionDigits: 3,
      });
    }
    if (absolute > 0 && absolute < 0.001) {
      return absolute.toLocaleString(undefined, {
        minimumFractionDigits: 0,
        maximumFractionDigits: 8,
      });
    }
    return absolute.toLocaleString(undefined, {
      minimumFractionDigits: 0,
      maximumFractionDigits: 3,
    });
  }

  formatQuantityFull(value: number): string {
    return Math.abs(value).toLocaleString(undefined, {
      minimumFractionDigits: 0,
      maximumFractionDigits: 12,
    });
  }

  formatTimestampLabel(value: string): string {
    return formatDateTimeWithSeconds(value);
  }

  prettifyLabel(value: string): string {
    if (value === 'GAS_ONLY') {
      return 'Gas';
    }
    return value.replaceAll('_', ' ');
  }

  private isInboundTransaction(tx: TransactionItem): boolean {
    return tx.type === 'EXTERNAL_INBOUND' || tx.type === 'EXTERNAL_TRANSFER_IN' || tx.type === 'BRIDGE_IN';
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
        source: flow.source ?? 'MANUAL',
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
