import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, Input, OnChanges, OnDestroy, SimpleChanges, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, of, startWith } from 'rxjs';

import { COLORS, EVM_NETWORK_PRESENTATION_BY_ID } from '../../core/data/dashboard.constants';
import {
  LendingData,
  LendingCycle,
  LendingCycleStatus,
  LendingGroup,
  LendingHistoryEntry,
  LendingHistoryFilter,
  LendingObservedFlow,
  LendingPosition,
  LendingViewState,
} from '../../core/models/lending.models';
import { EvmNetworkId } from '../../core/models/wallet-api.models';
import { LendingDataService } from '../../core/services/lending-data.service';

interface LendingCycleSection {
  readonly id: string;
  readonly isVault: boolean;
  readonly label: string;
  readonly supplyUsd: number;
  readonly borrowUsd: number;
  readonly cycles: ReadonlyArray<LendingCycle>;
}

interface LendingAssetPnlLine {
  readonly asset: string;
  readonly value: number | null;
  readonly precision: string;
  readonly reason: string | null;
  readonly valueUsd: number | null;
  readonly usdPrecision: string;
}

@Component({
  selector: 'wr-lending-page',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './lending-page.component.html',
  styleUrl: './lending-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LendingPageComponent implements OnChanges, OnDestroy {
  private readonly lendingDataService = inject(LendingDataService);
  private readonly destroyRef = inject(DestroyRef);

  @Input() sessionId: string | null = null;
  @Input() refreshNonce = 0;

  readonly colors = COLORS;
  readonly viewState = signal<LendingViewState>({ status: 'idle' });
  readonly showClosed = signal(false);
  readonly copiedValueKey = signal<string | null>(null);
  private copyResetTimerId: number | null = null;
  readonly collapsedGroupIds = signal<ReadonlySet<string>>(new Set<string>());
  readonly expandedCycleIds = signal<ReadonlySet<string>>(new Set<string>());
  readonly expandedLoopGroupIds = signal<ReadonlySet<string>>(new Set<string>());
  readonly selectedWallets = signal<ReadonlySet<string>>(new Set<string>());
  readonly selectedProtocols = signal<ReadonlySet<string>>(new Set<string>());
  readonly selectedNetworks = signal<ReadonlySet<string>>(new Set<string>());
  readonly selectedMarkets = signal<ReadonlySet<string>>(new Set<string>());
  readonly selectedCycleStatuses = signal<ReadonlySet<string>>(new Set<string>());
  readonly historyFilters = signal<ReadonlyMap<string, LendingHistoryFilter>>(new Map());

  ngOnChanges(changes: SimpleChanges): void {
    if ('sessionId' in changes || 'refreshNonce' in changes) {
      this.load();
    }
  }

  ngOnDestroy(): void {
    if (this.copyResetTimerId !== null) {
      window.clearTimeout(this.copyResetTimerId);
    }
  }

  async copyText(value: string, copyKey = value): Promise<void> {
    try {
      if ('clipboard' in navigator && navigator.clipboard !== undefined) {
        await navigator.clipboard.writeText(value);
      } else {
        const textarea = document.createElement('textarea');
        textarea.value = value;
        textarea.setAttribute('readonly', 'true');
        textarea.style.position = 'fixed';
        textarea.style.opacity = '0';
        document.body.appendChild(textarea);
        textarea.select();
        document.execCommand('copy');
        document.body.removeChild(textarea);
      }
      this.copiedValueKey.set(copyKey);
      if (this.copyResetTimerId !== null) {
        window.clearTimeout(this.copyResetTimerId);
      }
      this.copyResetTimerId = window.setTimeout(() => {
        this.copiedValueKey.set(null);
        this.copyResetTimerId = null;
      }, 1400);
    } catch {
      this.copiedValueKey.set(null);
    }
  }

  copyTxHash(txHash: string | null): void {
    if (txHash === null || txHash.length === 0) {
      return;
    }
    void this.copyText(txHash, `tx:${txHash}`);
  }

  isHashCopied(txHash: string | null): boolean {
    return txHash !== null && this.copiedValueKey() === `tx:${txHash}`;
  }

  data(): LendingData | null {
    const state = this.viewState();
    return state.status === 'success' ? state.data : null;
  }

  groups(): ReadonlyArray<LendingGroup> {
    return this.data()?.groups ?? [];
  }

  errorMessage(): string {
    const state = this.viewState();
    return state.status === 'error' ? state.message : 'Unable to load lending positions.';
  }

  filteredGroups(): ReadonlyArray<LendingGroup> {
    const wallets = this.selectedWallets();
    const protocols = this.selectedProtocols();
    const networks = this.selectedNetworks();
    const markets = this.selectedMarkets();
    const cycleStatuses = this.selectedCycleStatuses();
    return this.groups().filter((group) => {
      if (wallets.size > 0 && !wallets.has(group.walletAddress)) {
        return false;
      }
      if (protocols.size > 0 && !protocols.has(group.protocol)) {
        return false;
      }
      if (networks.size > 0 && group.networkId !== null && !networks.has(group.networkId)) {
        return false;
      }
      return this.filteredCycles(group).some((cycle) => {
        if (!this.showClosed() && cycle.status !== 'OPEN') {
          return false;
        }
        if (markets.size > 0 && !markets.has(cycle.marketKey)) {
          return false;
        }
        return cycleStatuses.size === 0 || cycleStatuses.has(cycle.status);
      });
    });
  }

  wallets(): ReadonlyArray<string> {
    return [...new Set(this.groups().map((group) => group.walletAddress))].sort();
  }

  protocols(): ReadonlyArray<string> {
    return [...new Set(this.groups().map((group) => group.protocol))].sort();
  }

  networks(): ReadonlyArray<EvmNetworkId> {
    return [...new Set(this.groups().map((group) => group.networkId).filter((network): network is EvmNetworkId => network !== null))].sort();
  }

  activeFilterCount(): number {
    return this.selectedWallets().size
      + this.selectedProtocols().size
      + this.selectedNetworks().size
      + this.selectedMarkets().size
      + this.selectedCycleStatuses().size;
  }

  markets(): ReadonlyArray<{ readonly key: string; readonly label: string }> {
    const byKey = new Map<string, string>();
    for (const group of this.groups()) {
      for (const cycle of group.cycles.filter((item) => this.isWorkspaceCycle(item) && this.shouldDisplayCycle(item))) {
        byKey.set(cycle.marketKey, cycle.marketLabel);
      }
    }
    return [...byKey.entries()]
      .map(([key, label]) => ({ key, label }))
      .sort((left, right) => left.label.localeCompare(right.label));
  }

  cycleStatuses(): ReadonlyArray<LendingCycleStatus> {
    return ['OPEN', 'CLOSED', 'AMBIGUOUS_NEEDS_REVIEW'];
  }

  load(): void {
    const sessionId = this.sessionId;
    if (sessionId === null || sessionId.trim().length === 0) {
      this.viewState.set({ status: 'idle' });
      return;
    }
    this.lendingDataService.getSessionLending(sessionId).pipe(
      startWith<LendingData | null>(null),
      catchError(() => of({ status: 'error' as const, message: 'Unable to load lending positions.' })),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe((value) => {
      if (value === null) {
        this.viewState.set({ status: 'loading' });
      } else if ('status' in value) {
        this.viewState.set(value);
      } else {
        const collapsedBefore = this.collapsedGroupIds();
        const expandedCyclesBefore = this.expandedCycleIds();
        this.viewState.set({ status: 'success', data: value });
        const groupIds = new Set(value.groups.map((group) => group.id));
        this.collapsedGroupIds.set(new Set([...collapsedBefore].filter((id) => groupIds.has(id))));
        const cycleIds = new Set(value.groups.flatMap((group) => group.cycles.map((cycle) => cycle.id)));
        const openCycleIds = value.groups.flatMap((group) => group.cycles)
          .filter((cycle) => cycle.status === 'OPEN')
          .map((cycle) => cycle.id);
        this.expandedCycleIds.set(new Set([
          ...openCycleIds,
          ...[...expandedCyclesBefore].filter((id) => cycleIds.has(id)),
        ]));
      }
    });
  }

  toggleClosed(): void {
    this.showClosed.update((value) => !value);
  }

  toggleExpanded(groupId: string): void {
    this.collapsedGroupIds.set(this.toggleSet(this.collapsedGroupIds(), groupId));
  }

  isGroupExpanded(groupId: string): boolean {
    return !this.collapsedGroupIds().has(groupId);
  }

  toggleCycleExpanded(cycleId: string): void {
    this.expandedCycleIds.set(this.toggleSet(this.expandedCycleIds(), cycleId));
  }

  isCycleExpanded(cycle: LendingCycle): boolean {
    return this.expandedCycleIds().has(cycle.id);
  }

  toggleLoopGroupExpanded(groupId: string): void {
    this.expandedLoopGroupIds.set(this.toggleSet(this.expandedLoopGroupIds(), groupId));
  }

  isLoopGroupExpanded(groupId: string): boolean {
    return this.expandedLoopGroupIds().has(groupId);
  }

  toggleWallet(wallet: string): void {
    this.selectedWallets.set(this.toggleSet(this.selectedWallets(), wallet));
  }

  toggleProtocol(protocol: string): void {
    this.selectedProtocols.set(this.toggleSet(this.selectedProtocols(), protocol));
  }

  toggleNetwork(networkId: string): void {
    this.selectedNetworks.set(this.toggleSet(this.selectedNetworks(), networkId));
  }

  toggleMarket(marketKey: string): void {
    this.selectedMarkets.set(this.toggleSet(this.selectedMarkets(), marketKey));
  }

  toggleCycleStatus(status: string): void {
    this.selectedCycleStatuses.set(this.toggleSet(this.selectedCycleStatuses(), status));
  }

  clearFilters(): void {
    this.selectedWallets.set(new Set<string>());
    this.selectedProtocols.set(new Set<string>());
    this.selectedNetworks.set(new Set<string>());
    this.selectedMarkets.set(new Set<string>());
    this.selectedCycleStatuses.set(new Set<string>());
  }

  setHistoryFilter(groupId: string, filter: LendingHistoryFilter): void {
    const next = new Map(this.historyFilters());
    next.set(groupId, filter);
    this.historyFilters.set(next);
  }

  historyFilter(groupId: string): LendingHistoryFilter {
    return this.historyFilters().get(groupId) ?? 'ALL';
  }

  filteredCycles(group: LendingGroup): ReadonlyArray<LendingCycle> {
    const markets = this.selectedMarkets();
    const statuses = this.selectedCycleStatuses();
    return group.cycles.filter((cycle) => {
      if (!this.isWorkspaceCycle(cycle)) {
        return false;
      }
      if (!this.shouldDisplayCycle(cycle)) {
        return false;
      }
      if (!this.showClosed() && cycle.status !== 'OPEN') {
        return false;
      }
      if (markets.size > 0 && !markets.has(cycle.marketKey)) {
        return false;
      }
      return statuses.size === 0 || statuses.has(cycle.status);
    });
  }

  filteredCycleEvents(cycle: LendingCycle): ReadonlyArray<LendingHistoryEntry> {
    const filter = this.historyFilter(cycle.id);
    return cycle.events.filter((entry) => {
      if (filter === 'ALL') {
        return true;
      }
      if (filter === 'LOOP') {
        return entry.type.startsWith('LENDING_LOOP');
      }
      if (filter === 'LENDING_DEPOSIT') {
        return entry.type === 'LENDING_DEPOSIT' || entry.type === 'VAULT_DEPOSIT';
      }
      if (filter === 'LENDING_WITHDRAW') {
        return entry.type === 'LENDING_WITHDRAW' || entry.type === 'VAULT_WITHDRAW';
      }
      return entry.type === filter;
    });
  }

  supplyPositions(group: LendingGroup): ReadonlyArray<LendingPosition> {
    return group.positions.filter((position) => position.side === 'SUPPLY');
  }

  borrowPositions(group: LendingGroup): ReadonlyArray<LendingPosition> {
    return group.positions.filter((position) => position.side === 'BORROW');
  }

  cycleSupplyPositions(cycle: LendingCycle): ReadonlyArray<LendingPosition> {
    return cycle.positions.filter((position) => position.side === 'SUPPLY');
  }

  cycleBorrowPositions(cycle: LendingCycle): ReadonlyArray<LendingPosition> {
    return cycle.positions.filter((position) => position.side === 'BORROW');
  }

  activeCycleCount(group: LendingGroup): number {
    return group.cycles.filter((cycle) => cycle.status === 'OPEN' && this.shouldDisplayCycle(cycle)).length;
  }

  closedCycleCount(group: LendingGroup): number {
    return group.cycles.filter((cycle) => cycle.status === 'CLOSED' && this.shouldDisplayCycle(cycle)).length;
  }

  reviewCycleCount(group: LendingGroup): number {
    return group.cycles.filter((cycle) => cycle.status === 'AMBIGUOUS_NEEDS_REVIEW' && this.shouldDisplayCycle(cycle)).length;
  }

  allActiveCycleCount(): number {
    return this.groups().flatMap((group) => group.cycles)
      .filter((cycle) => cycle.status === 'OPEN' && this.shouldDisplayCycle(cycle)).length;
  }

  closedPnlTotal(): number | null {
    return this.sumNullable(this.groups()
      .flatMap((group) => group.cycles)
      .filter((cycle) => cycle.status === 'CLOSED' && this.shouldDisplayCycle(cycle))
      .map((cycle) => cycle.totalValuation.totalUsdPnl));
  }

  groupClosedPnl(group: LendingGroup): number | null {
    return this.sumNullable(group.cycles
      .filter((cycle) => cycle.status === 'CLOSED' && this.shouldDisplayCycle(cycle))
      .map((cycle) => cycle.totalValuation.totalUsdPnl));
  }

  groupRunningPnl(group: LendingGroup): number | null {
    return this.sumNullable(group.cycles
      .filter((cycle) => cycle.status === 'OPEN' && this.shouldDisplayCycle(cycle))
      .map((cycle) => cycle.totalValuation.unrealizedTotalUsdPnl ?? cycle.totalValuation.totalUsdPnl));
  }

  visibleCycles(group: LendingGroup): ReadonlyArray<LendingCycle> {
    const cycles = this.filteredCycles(group);
    return [
      ...cycles.filter((cycle) => cycle.status === 'OPEN'),
      ...cycles.filter((cycle) => cycle.status !== 'OPEN'),
    ];
  }

  cycleSections(group: LendingGroup): ReadonlyArray<LendingCycleSection> {
    const cycles = this.visibleCycles(group);
    if (!this.isVaultModel(group)) {
      return [{
        id: `${group.id}:cycles`,
        isVault: false,
        label: '',
        supplyUsd: group.supplyUsd,
        borrowUsd: group.borrowUsd,
        cycles,
      }];
    }

    const byMarket = new Map<string, LendingCycle[]>();
    for (const cycle of cycles) {
      const marketCycles = byMarket.get(cycle.marketKey) ?? [];
      marketCycles.push(cycle);
      byMarket.set(cycle.marketKey, marketCycles);
    }

    return [...byMarket.entries()].map(([marketKey, marketCycles]) => ({
      id: `${group.id}:${marketKey}`,
      isVault: true,
      label: this.vaultSectionLabel(marketCycles),
      supplyUsd: this.sumPositions(group, marketKey, 'SUPPLY'),
      borrowUsd: this.sumPositions(group, marketKey, 'BORROW'),
      cycles: marketCycles,
    }));
  }

  primaryMarketLabel(group: LendingGroup): string {
    return this.visibleCycles(group)[0]?.marketLabel ?? 'Vault account';
  }

  historyFilterOptions(): ReadonlyArray<{ readonly id: LendingHistoryFilter; readonly label: string }> {
    return [
      { id: 'ALL', label: 'All' },
      { id: 'LENDING_DEPOSIT', label: 'Deposit' },
      { id: 'LENDING_WITHDRAW', label: 'Withdraw' },
      { id: 'BORROW', label: 'Borrow' },
      { id: 'REPAY', label: 'Repay' },
      { id: 'REWARD_CLAIM', label: 'Reward' },
    ];
  }

  groupVersion(group: LendingGroup): string {
    const normalized = group.protocol.toLowerCase();
    if (normalized.includes('v2')) {
      return 'v2';
    }
    return 'v3';
  }

  collateralRatioPct(group: LendingGroup): number {
    if (group.supplyUsd <= 0) {
      return 0;
    }
    if (group.borrowUsd <= 0) {
      return 100;
    }
    return Math.max(0, Math.min(100, (group.netExposureUsd / group.supplyUsd) * 100));
  }

  healthDisplay(group: LendingGroup): string {
    return group.healthFactor >= 10 ? '∞' : group.healthFactor.toFixed(2);
  }

  groupNetApy(group: LendingGroup): number | null {
    if (group.netExposureUsd <= 0) {
      return null;
    }
    let supplyYield = 0;
    let borrowCost = 0;
    let hasSignal = false;
    for (const position of group.positions) {
      const apy = this.currentProtocolApy(position);
      if (apy === null) {
        continue;
      }
      hasSignal = true;
      if (position.side === 'BORROW') {
        borrowCost += position.valueUsd * apy;
      } else {
        supplyYield += position.valueUsd * apy;
      }
    }
    if (!hasSignal) {
      return null;
    }
    return (supplyYield - borrowCost) / group.netExposureUsd;
  }

  formatNetApy(group: LendingGroup): string {
    const value = this.groupNetApy(group);
    return value === null ? '--' : `${value.toFixed(1)}%`;
  }

  isHealthStale(group: LendingGroup): boolean {
    return group.healthSource === 'ACCOUNTING_ESTIMATE' || group.healthSource === 'STALE';
  }

  shouldShowHealth(group: LendingGroup): boolean {
    return group.status === 'OPEN' && group.borrowUsd > 0;
  }

  healthLabel(group: LendingGroup): string {
    if (group.healthFactor >= 10) {
      return 'No debt';
    }
    if (group.healthFactor >= 2) {
      return 'Safe';
    }
    if (group.healthFactor >= 1.5) {
      return 'Moderate';
    }
    if (group.healthFactor >= 1.1) {
      return 'At risk';
    }
    return 'Liquidation risk';
  }

  historyColorClass(entry: LendingHistoryEntry): string {
    if (entry.loopId !== null || entry.type.startsWith('LENDING_LOOP')) {
      return 'loop';
    }
    switch (entry.type) {
      case 'LENDING_DEPOSIT':
      case 'VAULT_DEPOSIT':
        return 'deposit';
      case 'LENDING_WITHDRAW':
      case 'VAULT_WITHDRAW':
        return 'withdraw';
      case 'BORROW':
        return 'borrow';
      case 'REPAY':
        return 'repay';
      case 'REWARD_CLAIM':
        return 'reward';
      default:
        return 'default';
    }
  }

  cycleStatusLabel(status: LendingCycleStatus): string {
    return status === 'AMBIGUOUS_NEEDS_REVIEW' ? 'Review' : status.charAt(0) + status.slice(1).toLowerCase();
  }

  precisionClass(precision: string): string {
    return precision.toLowerCase().replaceAll('_', '-');
  }

  formatPnl(value: number | null, precision: string): string {
    if (value === null || precision === 'UNAVAILABLE') {
      return 'Unavailable';
    }
    return this.formatUsd(value);
  }

  formatMaybeUsd(value: number | null): string {
    return value === null ? 'Unavailable' : this.formatUsd(value);
  }

  cycleInterestEarnedUsd(cycle: LendingCycle): number | null {
    if (cycle.pnlBreakdown.interestEarnedUsd !== null) {
      return cycle.pnlBreakdown.interestEarnedUsd;
    }
    if (this.hasDebtActivity(cycle) || cycle.totalValuation.totalUsdPnl === null) {
      return null;
    }
    return Math.max(0, cycle.totalValuation.totalUsdPnl + cycle.totalValuation.gasUsd);
  }

  cycleInterestPaidUsd(cycle: LendingCycle): number | null {
    if (cycle.pnlBreakdown.interestPaidUsd !== null) {
      return cycle.pnlBreakdown.interestPaidUsd;
    }
    return this.hasDebtActivity(cycle) ? null : 0;
  }

  formatPositiveMaybeUsd(value: number | null): string {
    return value === null ? '--' : `+${this.formatUsd(value)}`;
  }

  formatNegativeMaybeUsd(value: number | null): string {
    return value === null ? '--' : `-${this.formatUsd(value)}`;
  }

  interestTitle(cycle: LendingCycle): string {
    if (cycle.pnlBreakdown.reason === null) {
      return 'Interest calculated from reconstructed lending legs.';
    }
    if (cycle.totalValuation.totalUsdPnl !== null && !this.hasDebtActivity(cycle)) {
      return 'Estimated from total cycle P&L because yield-only evidence is incomplete.';
    }
    return this.humanReason(cycle.pnlBreakdown.reason);
  }

  formatSignedUsd(value: number | null): string {
    if (value === null) {
      return 'Unavailable';
    }
    const formatted = this.formatUsd(value);
    return value > 0 ? `+${formatted}` : formatted;
  }

  formatDuration(days: number | null): string {
    if (days === null) {
      return 'unknown';
    }
    if (days < 30) {
      return `${days}d`;
    }
    if (days < 365) {
      return `${Math.round(days / 30)}mo`;
    }
    return `${(days / 365).toFixed(1)}y`;
  }

  pnlReason(method: string): string {
    if (method.startsWith('unavailable:')) {
      return this.humanReason(method.slice('unavailable:'.length));
    }
    if (method === 'asset-delta-only') {
      return 'asset deltas only';
    }
    return method;
  }

  formatAssetDeltas(values: Readonly<Record<string, number>>): string {
    const entries = Object.entries(values).filter(([, value]) => Math.abs(value) > 0);
    if (entries.length === 0) {
      return '0';
    }
    return entries
      .map(([asset, value]) => `${this.formatQuantity(value)} ${asset}`)
      .join(', ');
  }

  assetPnlLines(cycle: LendingCycle): ReadonlyArray<LendingAssetPnlLine> {
    const assets = new Set<string>([
      ...Object.keys(cycle.assetDenominatedPnlByAsset),
      ...Object.keys(cycle.assetDenominatedPrecisionByAsset),
      ...Object.keys(cycle.pnlAssetBreakdown.netIncomeByAsset),
      ...Object.keys(cycle.pnlAssetBreakdown.precisionByAsset),
    ]);
    return [...assets].sort().map((asset) => {
      const precision = cycle.assetDenominatedPrecisionByAsset[asset]
        ?? cycle.pnlAssetBreakdown.precisionByAsset[asset]
        ?? cycle.pnlBreakdown.precision;
      const reason = cycle.assetDenominatedReasonByAsset[asset]
        ?? cycle.pnlAssetBreakdown.reasonByAsset[asset]
        ?? null;
      const usdPrecision = cycle.pnlAssetBreakdown.usdPrecisionByAsset[asset]
        ?? 'UNAVAILABLE';
      const usdValue = cycle.pnlAssetBreakdown.netIncomeUsdByAsset[asset];
      return {
        asset,
        value: precision === 'UNAVAILABLE'
          ? null
          : cycle.assetDenominatedPnlByAsset[asset] ?? cycle.pnlAssetBreakdown.netIncomeByAsset[asset] ?? 0,
        precision,
        reason,
        valueUsd: usdPrecision === 'UNAVAILABLE' || usdValue === undefined ? null : usdValue,
        usdPrecision,
      };
    });
  }

  formatAssetPnl(line: LendingAssetPnlLine): string {
    if (line.value === null) {
      return 'Unavailable';
    }
    const formatted = `${this.formatQuantity(Math.abs(line.value))} ${line.asset}`;
    return line.value > 0 ? `+${formatted}` : line.value < 0 ? `-${formatted}` : formatted;
  }

  formatAssetPnlUsd(line: LendingAssetPnlLine): string {
    if (line.valueUsd === null) {
      return '--';
    }
    return this.formatSignedUsd(line.valueUsd);
  }

  assetPnlUsdClass(line: LendingAssetPnlLine): string {
    if (line.valueUsd === null || line.valueUsd === 0) {
      return 'muted';
    }
    return line.valueUsd > 0 ? 'pos' : 'neg';
  }

  formatApy(position: LendingPosition): string {
    const value = this.currentProtocolApy(position);
    if (value === null) {
      return '--';
    }
    const prefix = position.protocolApyStatus === 'FALLBACK_ESTIMATE' ? 'Est. ' : '';
    return `${prefix}${value.toFixed(2)}%`;
  }

  positionMetricTitle(position: LendingPosition): string {
    const capturedAt = position.protocolApyCapturedAt === null
      ? ''
      : ` · ${new Date(position.protocolApyCapturedAt).toLocaleString()}`;
    const rewards = position.rewardAprStatus === 'UNAVAILABLE'
      ? ` · Rewards: ${this.humanReason(position.rewardAprUnavailableReason ?? position.rewardAprStatus)}`
      : ` · Rewards: ${position.rewardAprPct?.toFixed(2) ?? '--'}%`;
    return `${this.humanReason(position.protocolApyStatus)} · ${this.humanReason(position.protocolApySource)}${capturedAt}${rewards}`;
  }

  apyClass(position: LendingPosition): string {
    if (position.protocolApyStatus === 'PROTOCOL_SNAPSHOT' || position.protocolApyStatus === 'API_SNAPSHOT') {
      return position.side === 'BORROW' ? 'borrow' : 'pos';
    }
    return 'muted-value';
  }

  factualApyDisplay(cycle: LendingCycle): string {
    const apy = cycle.factualApy;
    if (apy.apyPrecision === 'UNAVAILABLE') {
      return '--';
    }
    const netStrategy = apy.netStrategyApyPct;
    if (netStrategy !== null && Number.isFinite(netStrategy) && Math.abs(netStrategy) <= 1000) {
      return `${netStrategy.toFixed(2)}%`;
    }
    const supplyValues = Object.values(apy.factualSupplyApyByAsset)
      .filter((value): value is number => value !== null && Number.isFinite(value));
    if (supplyValues.length > 0) {
      const supplyApy = supplyValues.reduce((sum, value) => sum + value, 0) / supplyValues.length;
      if (Math.abs(supplyApy) <= 1000) {
        return `${supplyApy.toFixed(2)}%`;
      }
    }
    const borrowValues = Object.values(apy.factualBorrowApyByAsset)
      .filter((value): value is number => value !== null && Number.isFinite(value));
    if (borrowValues.length > 0 && supplyValues.length === 0) {
      const borrowApy = borrowValues.reduce((sum, value) => sum + value, 0) / borrowValues.length;
      if (Math.abs(borrowApy) <= 1000) {
        return `${borrowApy.toFixed(2)}%`;
      }
    }
    return '--';
  }

  factualApyTitle(cycle: LendingCycle): string {
    if (cycle.factualApy.apyUnavailableReason !== null) {
      return this.humanReason(cycle.factualApy.apyUnavailableReason);
    }
    return `${this.humanReason(cycle.factualApy.apyPrecision)} · ${this.humanReason(cycle.factualApy.apyMethod)}`;
  }

  cycleProtocolNetApy(cycle: LendingCycle): number | null {
    const positions = cycle.positions ?? [];
    let supplyYield = 0;
    let borrowCost = 0;
    let hasSignal = false;
    for (const position of positions) {
      if (position.side === 'SUPPLY' && position.protocolSupplyApyPct !== null) {
        supplyYield += position.valueUsd * position.protocolSupplyApyPct;
        hasSignal = true;
      }
      if (position.side === 'BORROW' && position.protocolBorrowApyPct !== null) {
        borrowCost += position.valueUsd * position.protocolBorrowApyPct;
        hasSignal = true;
      }
    }
    if (!hasSignal) {
      return null;
    }
    const supplyUsd = positions
      .filter((position) => position.side === 'SUPPLY')
      .reduce((sum, position) => sum + position.valueUsd, 0);
    const borrowUsd = positions
      .filter((position) => position.side === 'BORROW')
      .reduce((sum, position) => sum + position.valueUsd, 0);
    const net = supplyUsd - borrowUsd;
    if (net <= 0) {
      return null;
    }
    return (supplyYield - borrowCost) / net;
  }

  cycleProtocolNetApyDisplay(cycle: LendingCycle): string {
    const value = this.cycleProtocolNetApy(cycle);
    if (value === null || !Number.isFinite(value)) {
      return '--';
    }
    return `${value.toFixed(1)}%`;
  }

  private currentProtocolApy(position: LendingPosition): number | null {
    if (position.side === 'BORROW') {
      return position.protocolBorrowApyPct ?? null;
    }
    return position.protocolSupplyApyPct ?? null;
  }

  humanReason(value: string): string {
    const normalized = value.trim();
    switch (normalized) {
      case 'closed/current-state-zero':
        return 'Closed, verified by current state';
      case 'pnl_unavailable_missing_wrapper_conversion_or_underlying_price_policy':
        return 'Yield P&L unavailable: missing wrapper conversion or underlying price policy';
      case 'pnl_unavailable_missing_full_receipt_logs':
        return 'Yield P&L unavailable: missing full receipt logs';
      case 'missing yield-only valuation evidence':
        return 'Yield P&L unavailable: missing share-rate or conversion evidence';
      case 'unresolved lifecycle':
        return 'Unresolved lifecycle evidence';
      case 'unresolved_principal_exit':
        return 'Unresolved principal exit';
      case 'missing_lending_leg_usd_valuation':
        return 'Total valuation unavailable: missing lending leg price';
      case 'missing_gas_usd_valuation':
        return 'Estimated: missing gas USD valuation';
      case 'ACCOUNTING_ESTIMATE':
        return 'Accounting estimate';
      case 'ESTIMATED':
        return 'Estimated';
      case 'UNKNOWN':
        return 'Unknown';
      case 'PROTOCOL_SNAPSHOT':
        return 'Protocol snapshot';
      case 'API_SNAPSHOT':
        return 'API snapshot';
      case 'FALLBACK_ESTIMATE':
        return 'Fallback estimate';
      case 'STALE':
        return 'Stale';
      case 'UNAVAILABLE':
        return 'Unavailable';
      case 'REWARDS_COLLECTOR_NOT_IMPLEMENTED':
        return 'Rewards collector not implemented';
      case 'PER_SECOND_COMPOUNDING':
        return 'Per-second compounding';
      case 'time-weighted-lifecycle-cashflow':
        return 'Time-weighted lifecycle cashflow';
      case 'MISSING_CYCLE_TIMESTAMP':
        return 'Missing cycle timestamp';
      case 'NON_POSITIVE_EXPOSURE_DURATION':
        return 'Non-positive exposure duration';
      case 'MISSING_TIME_WEIGHTED_DENOMINATOR':
        return 'Missing time-weighted denominator';
      case 'UNRESOLVED_LIFECYCLE':
        return 'Unresolved lifecycle';
      case 'SHARE_RATE_EFFECT':
        return 'Share-rate effect';
      case 'GAS_COST':
        return 'Gas cost';
      case 'REALIZED_MARKET_MOVE':
        return 'Realized market move';
      case 'BORROW_COST':
        return 'Borrow cost';
      case 'MISSING_PRICE':
        return 'Missing price';
      case 'LIFECYCLE_LINKING_GAP':
        return 'Lifecycle linking gap';
      case 'MIGRATION_UNRESOLVED':
        return 'Migration unresolved';
      case 'SHARE_RATE_UNAVAILABLE':
        return 'Share-rate unavailable';
      default:
        return normalized.replaceAll('_', ' ');
    }
  }

  loopGroupLabel(steps: number): string {
    return `Loop group · ${steps} step${steps === 1 ? '' : 's'}`;
  }

  historyIcon(entry: LendingHistoryEntry): string {
    if (entry.loopId !== null || entry.type.startsWith('LENDING_LOOP')) {
      return '↻';
    }
    if (entry.type === 'BORROW' || entry.type === 'LENDING_WITHDRAW' || entry.type === 'VAULT_WITHDRAW') {
      return '↓';
    }
    if (entry.type === 'REWARD_CLAIM') {
      return '+';
    }
    return '↑';
  }

  txGroupTitle(groupType: string): string {
    switch (groupType) {
      case 'open':
        return 'Open';
      case 'borrow':
        return 'Borrow';
      case 'loop':
        return 'Looping strategy';
      case 'close':
        return 'Close';
      case 'reward':
        return 'Reward';
      default:
        return 'Update';
    }
  }

  txGroupClass(groupType: string): string {
    return groupType === 'open'
      ? 'open'
      : groupType === 'borrow'
        ? 'borrow'
        : groupType === 'loop'
          ? 'loop'
          : groupType === 'close'
            ? 'close'
            : groupType === 'reward'
              ? 'reward'
              : 'mid';
  }

  txItemClass(type: string): string {
    if (type.startsWith('LENDING_LOOP')) {
      return 'loop';
    }
    switch (type) {
      case 'LENDING_DEPOSIT':
      case 'VAULT_DEPOSIT':
        return 'deposit';
      case 'LENDING_WITHDRAW':
      case 'VAULT_WITHDRAW':
        return 'withdraw';
      case 'BORROW':
        return 'borrow';
      case 'REPAY':
        return 'repay';
      case 'REWARD_CLAIM':
        return 'reward';
      default:
        return 'default';
    }
  }

  txItemIcon(type: string): string {
    if (type === 'BORROW' || type === 'LENDING_WITHDRAW' || type === 'VAULT_WITHDRAW') {
      return '↓';
    }
    if (type === 'REPAY') {
      return '↑';
    }
    if (type === 'REWARD_CLAIM') {
      return '+';
    }
    if (type.startsWith('LENDING_LOOP')) {
      return '↻';
    }
    return '↑';
  }

  txSignedQuantity(type: string, quantity: number): string {
    const sign = type === 'REPAY' || type === 'LENDING_WITHDRAW' || type === 'VAULT_WITHDRAW'
      || type === 'LENDING_LOOP_DECREASE' || type === 'LENDING_LOOP_CLOSE'
      ? '-'
      : '+';
    return `${sign}${this.formatQuantity(Math.abs(quantity))}`;
  }

  cycleSummary(cycle: LendingCycle): string {
    const supplied = this.formatAssetDeltas(cycle.assetDeltas.principalInByAsset);
    const borrowed = this.formatAssetDeltas(cycle.assetDeltas.borrowedByAsset);
    const hasBorrow = borrowed !== '0';
    return hasBorrow ? `Supply ${supplied} -> Borrow ${borrowed}` : `Supply ${supplied}`;
  }

  cycleMarketLabel(cycle: LendingCycle): string {
    const supply = this.assetSymbolsFrom(cycle.assetDeltas.principalInByAsset);
    const borrow = this.assetSymbolsFrom(cycle.assetDeltas.borrowedByAsset);
    if (supply.length > 0 && borrow.length > 0) {
      return `${supply.join('+')} / ${borrow.join('+')}`;
    }
    if (supply.length > 0) {
      return supply.join(' + ');
    }
    if (borrow.length > 0) {
      return borrow.join(' + ');
    }
    return cycle.marketLabel;
  }

  private assetSymbolsFrom(values: Readonly<Record<string, number>>): ReadonlyArray<string> {
    return Object.entries(values)
      .filter(([, value]) => Math.abs(value) > 0)
      .map(([asset]) => asset)
      .slice(0, 3);
  }

  protocolTag(group: LendingGroup): string {
    const protocol = group.protocol.toLowerCase();
    return protocol.includes('fluid') || protocol.includes('morpho') ? 'vault-based' : this.groupVersion(group);
  }

  isVaultModel(group: LendingGroup): boolean {
    const protocol = group.protocol.toLowerCase();
    return protocol.includes('fluid') || protocol.includes('morpho');
  }

  pnlClass(value: number | null): string {
    if (value === null) {
      return 'muted-value';
    }
    return value >= 0 ? 'pos' : 'neg';
  }

  assetPnlClass(line: LendingAssetPnlLine): string {
    return this.pnlClass(line.value);
  }

  isLoopFirst(group: LendingGroup, entry: LendingHistoryEntry): boolean {
    return this.loopIndex(group.history, entry) === 0;
  }

  isLoopMiddle(group: LendingGroup, entry: LendingHistoryEntry): boolean {
    const index = this.loopIndex(group.history, entry);
    return index > 0 && index < this.loopEntries(group.history, entry.loopId).length - 1;
  }

  isLoopLast(group: LendingGroup, entry: LendingHistoryEntry): boolean {
    const entries = this.loopEntries(group.history, entry.loopId);
    return entries.length > 0 && this.loopIndex(group.history, entry) === entries.length - 1;
  }

  isCycleLoopFirst(cycle: LendingCycle, entry: LendingHistoryEntry): boolean {
    return this.loopIndex(cycle.events, entry) === 0;
  }

  isCycleLoopMiddle(cycle: LendingCycle, entry: LendingHistoryEntry): boolean {
    const index = this.loopIndex(cycle.events, entry);
    return index > 0 && index < this.loopEntries(cycle.events, entry.loopId).length - 1;
  }

  isCycleLoopLast(cycle: LendingCycle, entry: LendingHistoryEntry): boolean {
    const entries = this.loopEntries(cycle.events, entry.loopId);
    return entries.length > 0 && this.loopIndex(cycle.events, entry) === entries.length - 1;
  }

  networkIcon(networkId: string | null): string {
    if (networkId === null) {
      return '•';
    }
    return EVM_NETWORK_PRESENTATION_BY_ID.get(networkId as EvmNetworkId)?.icon ?? '•';
  }

  networkLabel(networkId: string | null): string {
    if (networkId === null) {
      return 'Unknown';
    }
    return EVM_NETWORK_PRESENTATION_BY_ID.get(networkId as EvmNetworkId)?.label ?? networkId;
  }

  networkColor(networkId: string | null): string {
    if (networkId === null) {
      return COLORS.textSubtle;
    }
    return EVM_NETWORK_PRESENTATION_BY_ID.get(networkId as EvmNetworkId)?.color ?? COLORS.textSubtle;
  }

  shortAddress(address: string): string {
    return address.length <= 12 ? address : `${address.slice(0, 6)}...${address.slice(-4)}`;
  }

  shortHash(hash: string | null): string {
    return hash === null || hash.length <= 14 ? hash ?? '' : `${hash.slice(0, 8)}...${hash.slice(-6)}`;
  }

  formatUsd(value: number): string {
    const absolute = Math.abs(value);
    const formatted = absolute >= 1_000_000
      ? `$${(absolute / 1_000_000).toFixed(2)}M`
      : absolute >= 1_000
        ? `$${(absolute / 1_000).toFixed(1)}k`
        : `$${absolute.toFixed(2)}`;
    return value < 0 ? `-${formatted}` : formatted;
  }

  formatQuantity(value: number): string {
    const absolute = Math.abs(value);
    if (absolute >= 1000) {
      return value.toLocaleString('en-US', { maximumFractionDigits: 2 });
    }
    if (absolute >= 1) {
      return value.toLocaleString('en-US', { maximumFractionDigits: 6 });
    }
    return value.toLocaleString('en-US', { maximumSignificantDigits: 6 });
  }

  formatDate(value: string | null): string {
    if (value === null) {
      return 'unknown';
    }
    return new Intl.DateTimeFormat('en-US', { month: 'short', day: '2-digit', year: 'numeric' }).format(new Date(value));
  }

  healthColor(group: LendingGroup): string {
    if (group.healthFactor >= 10 || group.healthFactor >= 2) {
      return COLORS.green;
    }
    if (group.healthFactor >= 1.2) {
      return COLORS.amber;
    }
    return COLORS.red;
  }

  private loopIndex(entries: ReadonlyArray<LendingHistoryEntry>, entry: LendingHistoryEntry): number {
    return this.loopEntries(entries, entry.loopId).findIndex((item) => item.id === entry.id);
  }

  private loopEntries(entries: ReadonlyArray<LendingHistoryEntry>, loopId: string | null): ReadonlyArray<LendingHistoryEntry> {
    if (loopId === null) {
      return [];
    }
    return entries.filter((entry) => entry.loopId === loopId);
  }

  private toggleSet(values: ReadonlySet<string>, value: string): ReadonlySet<string> {
    const next = new Set(values);
    if (next.has(value)) {
      next.delete(value);
    } else {
      next.add(value);
    }
    return next;
  }

  private sumNullable(values: ReadonlyArray<number | null>): number | null {
    const present = values.filter((value): value is number => value !== null);
    if (present.length === 0) {
      return null;
    }
    return present.reduce((sum, value) => sum + value, 0);
  }

  private isWorkspaceCycle(cycle: LendingCycle): boolean {
    return cycle.status === 'OPEN' || cycle.status === 'CLOSED' || cycle.status === 'AMBIGUOUS_NEEDS_REVIEW';
  }

  private shouldDisplayCycle(cycle: LendingCycle): boolean {
    if (cycle.status !== 'AMBIGUOUS_NEEDS_REVIEW') {
      return true;
    }
    return !this.isExitOnlyOrphan(cycle);
  }

  private isExitOnlyOrphan(cycle: LendingCycle): boolean {
    return this.hasAnyAssetAmount(cycle.assetDeltas.principalOutCashByAsset)
      && !this.hasAnyAssetAmount(cycle.assetDeltas.principalInByAsset)
      && !this.hasAnyAssetAmount(cycle.assetDeltas.borrowedByAsset)
      && !this.hasAnyAssetAmount(cycle.assetDeltas.repaidByAsset);
  }

  private hasDebtActivity(cycle: LendingCycle): boolean {
    return this.hasAnyAssetAmount(cycle.assetDeltas.borrowedByAsset)
      || this.hasAnyAssetAmount(cycle.assetDeltas.repaidByAsset);
  }

  private hasAnyAssetAmount(values: Readonly<Record<string, number>>): boolean {
    return Object.values(values).some((value) => Math.abs(value) > 0);
  }

  private sumPositions(group: LendingGroup, marketKey: string, side: 'SUPPLY' | 'BORROW'): number {
    return group.positions
      .filter((position) => position.marketKey === marketKey && position.side === side)
      .reduce((sum, position) => sum + position.valueUsd, 0);
  }

  private vaultSectionLabel(cycles: ReadonlyArray<LendingCycle>): string {
    const supplyAssets = this.uniqueCycleAssets(cycles, 'supply');
    const borrowAssets = this.uniqueCycleAssets(cycles, 'borrow');
    if (supplyAssets.length > 0 && borrowAssets.length > 0) {
      return `${supplyAssets.join('+')}/${borrowAssets.join('+')}`;
    }
    if (supplyAssets.length > 0) {
      return supplyAssets.join('+');
    }
    if (borrowAssets.length > 0) {
      return borrowAssets.join('+');
    }
    return cycles[0]?.marketLabel ?? 'Vault account';
  }

  private uniqueCycleAssets(cycles: ReadonlyArray<LendingCycle>, side: 'supply' | 'borrow'): ReadonlyArray<string> {
    const assets = new Set<string>();
    for (const cycle of cycles) {
      const source = side === 'supply'
        ? cycle.assetDeltas.principalInByAsset
        : { ...cycle.assetDeltas.borrowedByAsset, ...cycle.assetDeltas.repaidByAsset };
      for (const [asset, quantity] of Object.entries(source)) {
        if (Math.abs(quantity) > 0) {
          assets.add(asset);
        }
      }
    }
    return [...assets].slice(0, 3);
  }
}
