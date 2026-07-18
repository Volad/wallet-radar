import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  Input,
  OnChanges,
  SimpleChanges,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { catchError, of, startWith, Subscription } from 'rxjs';

import { COLORS, EVM_NETWORK_PRESENTATION_BY_ID } from '../../core/data/dashboard.constants';
import {
  LpData,
  LpLiquidityBin,
  LpPosition,
  LpPositionScope,
  LpPrecision,
  LpViewState,
} from '../../core/models/lp.models';
import { EvmNetworkId, RefreshStateItemResponse, RefreshStatusResponse } from '../../core/models/wallet-api.models';
import { LpDataService } from '../../core/services/lp-data.service';
import { RefreshStatusPollerService } from '../../core/services/refresh-status-poller.service';
import { WalletApiService } from '../../core/services/wallet-api.service';
import { CopyHashComponent } from '../../core/components/copy-hash/copy-hash.component';
import { FilterSidebarComponent } from '../../core/components/filter-sidebar/filter-sidebar.component';
import { SmartAmountComponent } from '../../core/components/smart-amount/smart-amount.component';
import { smartFormatQty, smartFormatSignedUsd, smartFormatUsd } from '../../core/utils/amount.util';

type EarningsChartMode = 'daily' | 'total';

interface DistributionBar {
  readonly x: number;
  readonly barWidth: number;
  readonly height: number;
  readonly inRange: boolean;
}

interface ChartTooltipState {
  readonly left: number;
  readonly top: number;
  readonly label: string;
  readonly value: string;
}

interface BarChartLayout {
  readonly width: number;
  readonly height: number;
  readonly padLeft: number;
  readonly padRight: number;
  readonly padTop: number;
  readonly padBottom: number;
  readonly innerWidth: number;
  readonly innerHeight: number;
  readonly slotWidth: number;
  readonly barWidth: number;
  readonly maxValue: number;
}

interface LineChartLayout {
  readonly width: number;
  readonly height: number;
  readonly padLeft: number;
  readonly padRight: number;
  readonly padTop: number;
  readonly padBottom: number;
  readonly innerWidth: number;
  readonly innerHeight: number;
  readonly maxValue: number;
}

const DUST_THRESHOLD_USD = 0.5;
const CHART_WIDTH = 470;
const CHART_HEIGHT = 152;

@Component({
  selector: 'wr-lp-page',
  standalone: true,
  imports: [CommonModule, CopyHashComponent, FilterSidebarComponent, SmartAmountComponent],
  templateUrl: './lp-page.component.html',
  styleUrl: './lp-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LpPageComponent implements OnChanges {
  private readonly lpDataService = inject(LpDataService);
  private readonly walletApiService = inject(WalletApiService);
  private readonly refreshStatusPoller = inject(RefreshStatusPollerService);
  private readonly destroyRef = inject(DestroyRef);

  @Input() sessionId: string | null = null;
  @Input() refreshNonce = 0;

  readonly colors = COLORS;
  readonly viewState = signal<LpViewState>({ status: 'idle' });
  readonly expandedPositionIds = signal<ReadonlySet<string>>(new Set<string>());
  readonly selectedWallets = signal<ReadonlySet<string>>(new Set<string>());
  readonly selectedProtocols = signal<ReadonlySet<string>>(new Set<string>());
  readonly selectedNetworks = signal<ReadonlySet<string>>(new Set<string>());
  readonly selectedStatuses = signal<ReadonlySet<string>>(new Set<string>());
  readonly hideDust = signal(true);
  readonly positionScope = signal<LpPositionScope>('active');
  readonly earningsChartModes = signal<ReadonlyMap<string, EarningsChartMode>>(new Map());
  readonly refreshStateById = signal<ReadonlyMap<string, RefreshStateItemResponse>>(new Map());
  readonly refreshAnyActive = signal(false);
  readonly chartTooltip = signal<ChartTooltipState | null>(null);
  readonly copiedHashes = signal<ReadonlySet<string>>(new Set<string>());
  private readonly pendingSingleRefreshIds = signal<ReadonlySet<string>>(new Set<string>());
  private refreshPollSubscription: Subscription | null = null;

  ngOnChanges(changes: SimpleChanges): void {
    if ('sessionId' in changes || 'refreshNonce' in changes) {
      this.load();
      this.startRefreshStatusPolling();
    }
  }

  private startRefreshStatusPolling(): void {
    this.refreshPollSubscription?.unsubscribe();
    this.refreshPollSubscription = null;
    const sessionId = this.sessionId;
    if (sessionId === null || sessionId.trim().length === 0) {
      this.refreshStateById.set(new Map());
      this.refreshAnyActive.set(false);
      return;
    }
    this.refreshPollSubscription = this.refreshStatusPoller.startAdaptivePolling(
      () => this.walletApiService.getLpRefreshStatus(sessionId),
      {
        onStatus: (status, previous) => this.applyRefreshStatus(status, previous),
      },
      this.destroyRef
    );
  }

  private applyRefreshStatus(status: RefreshStatusResponse, previous: RefreshStatusResponse | null): void {
    const priorLocal = this.refreshStateById();
    const next = new Map<string, RefreshStateItemResponse>();
    for (const item of status.items) {
      const merged = item.status === 'SYNCED' && item.lastSyncedAt === null && item.completedAt !== null
        ? { ...item, lastSyncedAt: item.completedAt }
        : item;
      next.set(item.id, merged);
    }
    this.refreshStateById.set(next);
    this.refreshAnyActive.set(status.anyActive);

    const newlySynced = status.items.filter((item) => {
      if (item.status !== 'SYNCED') {
        return false;
      }
      const prev = previous?.items.find((candidate) => candidate.id === item.id);
      const local = priorLocal.get(item.id);
      return prev?.status === 'UPDATING'
        || prev?.status === 'QUEUED'
        || local?.status === 'UPDATING'
        || local?.status === 'QUEUED';
    });
    if (newlySynced.length === 0) {
      return;
    }

    const pending = this.pendingSingleRefreshIds();

    // Only react to pools the user explicitly refreshed. Background cron completions
    // (pools not in pending) must not trigger a page reload.
    const userInitiatedSynced = pending.size > 0
      ? newlySynced.filter((item) => pending.has(item.id))
      : newlySynced;  // refreshAll(): no pending tracking, treat all as user-initiated

    if (userInitiatedSynced.length === 0) {
      // All completions are background cron — suppress reload.
      return;
    }

    if (pending.size > 0 && userInitiatedSynced.length === 1) {
      this.reloadPosition(userInitiatedSynced[0].id);
      const remaining = new Set(pending);
      remaining.delete(userInitiatedSynced[0].id);
      this.pendingSingleRefreshIds.set(remaining);
      return;
    }
    this.pendingSingleRefreshIds.set(new Set());
    this.load();
  }

  private reloadPosition(correlationId: string): void {
    const sessionId = this.sessionId;
    if (sessionId === null || sessionId.trim().length === 0) {
      return;
    }
    this.lpDataService.getSessionLpPosition(sessionId, correlationId, this.positionScope()).pipe(
      catchError(() => of(null)),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe((position) => {
      if (position === null) {
        this.load();
        return;
      }
      const state = this.viewState();
      if (state.status !== 'success') {
        this.load();
        return;
      }
      const exists = state.data.positions.some((item) => item.correlationId === correlationId);
      const positions = exists
        ? state.data.positions.map((item) => item.correlationId === correlationId ? position : item)
        : [...state.data.positions, position];
      this.viewState.set({
        status: 'success',
        data: {
          ...state.data,
          positions,
        },
      });
    });
  }

  refreshAllLoading(): boolean {
    return this.refreshAnyActive() && this.positionScope() === 'active';
  }

  data(): LpData | null {
    const state = this.viewState();
    return state.status === 'success' ? state.data : null;
  }

  positions(): ReadonlyArray<LpPosition> {
    return this.data()?.positions ?? [];
  }

  errorMessage(): string {
    const state = this.viewState();
    return state.status === 'error' ? state.message : 'Unable to load liquidity pool positions.';
  }

  load(): void {
    const sessionId = this.sessionId;
    if (sessionId === null || sessionId.trim().length === 0) {
      this.viewState.set({ status: 'idle' });
      return;
    }

    this.lpDataService.getSessionLp(sessionId, this.positionScope()).pipe(
      startWith<LpData | null>(null),
      catchError(() => of({ status: 'error' as const, message: 'Unable to load liquidity pool positions.' })),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe((value) => {
      if (value === null) {
        this.viewState.set({ status: 'loading' });
        return;
      }
      if ('status' in value) {
        this.viewState.set(value);
        return;
      }
      const expandedBefore = this.expandedPositionIds();
      this.viewState.set({ status: 'success', data: value });
      const ids = new Set(value.positions.map((position) => position.correlationId));
      this.expandedPositionIds.set(new Set([...expandedBefore].filter((id) => ids.has(id))));
    });
  }

  filteredPositions(): ReadonlyArray<LpPosition> {
    const wallets = this.selectedWallets();
    const protocols = this.selectedProtocols();
    const networks = this.selectedNetworks();
    const statuses = this.selectedStatuses();
    const hideDust = this.hideDust();

    return this.positions().filter((position) => {
      if (wallets.size > 0 && !wallets.has(position.wallet)) {
        return false;
      }
      if (protocols.size > 0 && !protocols.has(position.protocol)) {
        return false;
      }
      if (networks.size > 0 && !networks.has(position.networkId)) {
        return false;
      }
      if (statuses.size > 0 && !statuses.has(position.status)) {
        return false;
      }
      if (hideDust && position.status !== 'closed') {
        const tvl = position.tvlUsd.valueUsd ?? 0;
        const principal = position.costBasisUsd ?? position.depositedMarketUsd ?? 0;
        if (Math.abs(tvl) < DUST_THRESHOLD_USD && principal < DUST_THRESHOLD_USD && position.status !== 'unknown') {
          return false;
        }
      }
      return true;
    });
  }

  filteredSummary() {
    const positions = this.filteredPositions();
    const open = positions.filter((position) => position.status !== 'closed');
    return {
      activeTvlUsd: open.reduce((sum, position) => sum + (position.tvlUsd.valueUsd ?? 0), 0),
      feesEarnedUsd: positions.reduce((sum, position) => sum + this.feesTotal(position), 0),
      unclaimedUsd: positions.reduce((sum, position) => sum + (position.fees.unclaimedUsd ?? 0), 0),
      inRange: open.filter((position) => position.status === 'in_range').length,
      outOfRange: open.filter((position) => position.status === 'out_of_range').length,
      realizedPnlUsd: positions
        .filter((position) => position.status === 'closed')
        .reduce((sum, position) => sum + (position.netPnlUsd ?? 0), 0),
    };
  }

  wallets(): ReadonlyArray<string> {
    return [...new Set(this.positions().map((position) => position.wallet))].sort();
  }

  protocols(): ReadonlyArray<string> {
    return [...new Set(this.positions().map((position) => position.protocol))].sort();
  }

  networks(): ReadonlyArray<EvmNetworkId> {
    return [...new Set(this.positions().map((position) => position.networkId))].sort();
  }

  statuses(): ReadonlyArray<{ readonly id: string; readonly label: string }> {
    const base = [
      { id: 'unknown', label: 'Tracking' },
      { id: 'in_range', label: 'In range' },
      { id: 'out_of_range', label: 'Out of range' },
    ];
    if (this.positionScope() !== 'active') {
      base.push({ id: 'closed', label: 'Closed' });
    }
    return base;
  }

  activeFilterCount(): number {
    return this.selectedWallets().size
      + this.selectedProtocols().size
      + this.selectedNetworks().size
      + this.selectedStatuses().size;
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

  toggleStatus(status: string): void {
    this.selectedStatuses.set(this.toggleSet(this.selectedStatuses(), status));
  }

  toggleHideDust(): void {
    this.hideDust.update((value) => !value);
  }

  setPositionScope(scope: LpPositionScope): void {
    if (this.positionScope() === scope) {
      return;
    }
    this.positionScope.set(scope);
    // Clear status filter on scope switch to avoid conflicts (e.g. 'closed' selected in active scope)
    if (this.selectedStatuses().size > 0) {
      this.selectedStatuses.set(new Set<string>());
    }
    this.load();
  }

  clearFilters(): void {
    this.selectedWallets.set(new Set<string>());
    this.selectedProtocols.set(new Set<string>());
    this.selectedNetworks.set(new Set<string>());
    this.selectedStatuses.set(new Set<string>());
  }

  toggleExpanded(correlationId: string): void {
    this.expandedPositionIds.set(this.toggleSet(this.expandedPositionIds(), correlationId));
  }

  isExpanded(correlationId: string): boolean {
    return this.expandedPositionIds().has(correlationId);
  }

  refreshPosition(position: LpPosition): void {
    const sessionId = this.sessionId;
    if (sessionId === null || position.status === 'closed' || this.isRefreshing(position.correlationId)) {
      return;
    }
    this.pendingSingleRefreshIds.update((ids) => {
      const next = new Set(ids);
      next.add(position.correlationId);
      return next;
    });
    this.markRefreshQueued(position.correlationId);
    this.walletApiService.refreshLpPosition(sessionId, position.correlationId).pipe(
      catchError((_error: HttpErrorResponse) => of(null)),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe((response) => {
      if (response !== null) {
        this.applyRefreshStatus(response, { sessionId, items: [...this.refreshStateById().values()], anyActive: this.refreshAnyActive() });
      }
    });
  }

  isRefreshing(correlationId: string): boolean {
    const state = this.refreshStateById().get(correlationId);
    return state?.status === 'QUEUED' || state?.status === 'UPDATING';
  }

  refreshAll(): void {
    const sessionId = this.sessionId;
    if (sessionId === null || this.refreshAllLoading() || this.positionScope() !== 'active') {
      return;
    }
    this.walletApiService.refreshAllLpPositions(sessionId).pipe(
      catchError((_error: HttpErrorResponse) => of(null)),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe((response) => {
      if (response !== null) {
        this.applyRefreshStatus(response, { sessionId, items: [...this.refreshStateById().values()], anyActive: this.refreshAnyActive() });
      }
    });
  }

  syncStatusLabel(position: LpPosition): string {
    if (this.isRefreshing(position.correlationId)) {
      return 'Updating…';
    }
    const state = this.refreshStateById().get(position.correlationId);
    if (state?.status === 'FAILED') {
      return 'Refresh failed';
    }
    const freshness = this.formatSyncFreshness(position);
    if (freshness !== null && (state?.status === 'SYNCED' || this.hasRecentSync(position))) {
      return freshness;
    }
    if (position.snapshotStale) {
      return 'Stale';
    }
    return freshness ?? 'Synced';
  }

  isSyncStale(position: LpPosition): boolean {
    if (this.isRefreshing(position.correlationId)) {
      return false;
    }
    const state = this.refreshStateById().get(position.correlationId);
    if (state?.status === 'FAILED') {
      return true;
    }
    if (this.hasRecentSync(position)) {
      return false;
    }
    return position.snapshotStale;
  }

  isSyncUpdating(position: LpPosition): boolean {
    return this.isRefreshing(position.correlationId);
  }

  earningsMode(correlationId: string): EarningsChartMode {
    return this.earningsChartModes().get(correlationId) ?? 'daily';
  }

  setEarningsMode(correlationId: string, mode: EarningsChartMode): void {
    const next = new Map(this.earningsChartModes());
    next.set(correlationId, mode);
    this.earningsChartModes.set(next);
  }

  earningsSeries(position: LpPosition): ReadonlyArray<number> {
    const daily = position.earningsDaily.map((point) => point.value);
    if (this.earningsMode(position.correlationId) === 'daily') {
      return daily;
    }
    const cumulative: number[] = [];
    daily.reduce((acc, value) => {
      const next = acc + value;
      cumulative.push(next);
      return next;
    }, 0);
    return cumulative;
  }

  earningsLabels(position: LpPosition): ReadonlyArray<string> {
    return position.earningsDaily.map((point) => this.formatChartDate(point.date));
  }

  earningsTotal(position: LpPosition): number {
    return position.earningsDaily.reduce((sum, point) => sum + point.value, 0);
  }

  aprSeries(position: LpPosition): ReadonlyArray<number> {
    return position.aprDaily.map((point) => point.value);
  }

  rangeChartCoords(position: LpPosition): {
    readonly lx: number;
    readonly rx: number;
    readonly cx: number | null;
    readonly band: string;
  } | null {
    const { priceLow, priceHigh, priceCurrent } = position.range;
    if (priceLow === null || priceHigh === null) {
      return null;
    }
    const W = 460;
    const padL = 8;
    const padR = 8;
    return {
      lx: this.rangeChartX(priceLow, position, W, padL, padR),
      rx: this.rangeChartX(priceHigh, position, W, padL, padR),
      cx: priceCurrent === null ? null : this.rangeChartX(priceCurrent, position, W, padL, padR),
      band: position.status === 'in_range' ? 'in-range' : 'out-of-range',
    };
  }

  compareSides(): ReadonlyArray<'token0' | 'token1'> {
    return ['token0', 'token1'];
  }

  tokenForSide(position: LpPosition, side: 'token0' | 'token1') {
    return side === 'token0' ? position.token0 : position.token1;
  }

  barChartLayout(values: ReadonlyArray<number>): BarChartLayout {
    const maxValue = Math.max(...values, 0.0001);
    const padLeft = 38;
    const padRight = 8;
    const padTop = 12;
    const padBottom = 22;
    const innerWidth = CHART_WIDTH - padLeft - padRight;
    const innerHeight = CHART_HEIGHT - padTop - padBottom;
    const slotWidth = values.length > 0 ? innerWidth / values.length : innerWidth;
    return {
      width: CHART_WIDTH,
      height: CHART_HEIGHT,
      padLeft,
      padRight,
      padTop,
      padBottom,
      innerWidth,
      innerHeight,
      slotWidth,
      barWidth: slotWidth * 0.6,
      maxValue,
    };
  }

  lineChartLayout(values: ReadonlyArray<number>, reference: number | null): LineChartLayout {
    const maxValue = Math.max(...values, reference ?? 0, 0.0001) * 1.12;
    const padLeft = 36;
    const padRight = 8;
    const padTop = 12;
    const padBottom = 22;
    return {
      width: CHART_WIDTH,
      height: CHART_HEIGHT,
      padLeft,
      padRight,
      padTop,
      padBottom,
      innerWidth: CHART_WIDTH - padLeft - padRight,
      innerHeight: CHART_HEIGHT - padTop - padBottom,
      maxValue,
    };
  }

  barHeight(value: number, layout: BarChartLayout): number {
    return layout.innerHeight * (value / layout.maxValue);
  }

  barX(index: number, layout: BarChartLayout): number {
    return layout.padLeft + index * layout.slotWidth + (layout.slotWidth - layout.barWidth) / 2;
  }

  linePointX(index: number, count: number, layout: LineChartLayout): number {
    if (count <= 1) {
      return layout.padLeft;
    }
    return layout.padLeft + (index / (count - 1)) * layout.innerWidth;
  }

  linePointY(value: number, layout: LineChartLayout): number {
    return layout.padTop + layout.innerHeight * (1 - value / layout.maxValue);
  }

  linePath(values: ReadonlyArray<number>, layout: LineChartLayout): string {
    if (values.length === 0) {
      return '';
    }
    return values
      .map((value, index) => {
        const x = this.linePointX(index, values.length, layout).toFixed(1);
        const y = this.linePointY(value, layout).toFixed(1);
        return `${index === 0 ? 'M' : 'L'}${x},${y}`;
      })
      .join(' ');
  }

  lineAreaPath(values: ReadonlyArray<number>, layout: LineChartLayout): string {
    if (values.length === 0) {
      return '';
    }
    const line = this.linePath(values, layout);
    const lastX = this.linePointX(values.length - 1, values.length, layout).toFixed(1);
    const baseY = (layout.padTop + layout.innerHeight).toFixed(1);
    const firstX = layout.padLeft.toFixed(1);
    return `${line} L${lastX},${baseY} L${firstX},${baseY} Z`;
  }

  yAxisTicks(maxValue: number): ReadonlyArray<number> {
    return [0, maxValue / 2, maxValue];
  }

  formatAxisUsd(value: number): string {
    return value >= 10 ? `$${value.toFixed(0)}` : `$${value.toFixed(2)}`;
  }

  formatAxisPct(value: number): string {
    return `${value.toFixed(0)}%`;
  }

  showChartTooltip(event: MouseEvent, label: string, value: string): void {
    const tipWidth = 220;
    const left = event.clientX + 16 + tipWidth > window.innerWidth
      ? event.clientX - (tipWidth + 16)
      : event.clientX + 16;
    const top = Math.min(Math.max(12, event.clientY - 20), Math.max(12, window.innerHeight - 120));
    this.chartTooltip.set({ left, top, label, value });
  }

  hideChartTooltip(): void {
    this.chartTooltip.set(null);
  }

  feesTotal(position: LpPosition): number {
    return (position.fees.claimedUsd ?? 0) + (position.fees.unclaimedUsd ?? 0);
  }

  netPnl(position: LpPosition): number | null {
    if (position.netPnlUsd !== null) {
      return position.netPnlUsd;
    }
    const fees = this.feesTotal(position);
    const il = position.il.usd ?? 0;
    const appreciation = position.priceAppreciationUsd ?? 0;
    if (position.il.precision === 'UNAVAILABLE' && position.fees.precision === 'UNAVAILABLE') {
      return null;
    }
    return fees + il + appreciation;
  }

  pnlPct(position: LpPosition): number | null {
    const net = this.netPnl(position);
    if (net === null || position.costBasisUsd === null || position.costBasisUsd <= 0) {
      return null;
    }
    return (net / position.costBasisUsd) * 100;
  }

  compareToHodl(position: LpPosition): number | null {
    const fees = this.feesTotal(position);
    if (position.il.usd === null) {
      return null;
    }
    return fees + position.il.usd;
  }

  isConcentrated(position: LpPosition): boolean {
    return position.family === 'CL_NFT';
  }

  hasPriceRange(position: LpPosition): boolean {
    return position.range.priceLow !== null && position.range.priceHigh !== null;
  }

  familyLabel(family: string): string {
    switch (family) {
      case 'CL_NFT':
        return 'Concentrated (NFT)';
      case 'FUNGIBLE':
        return 'Full-range (LP token)';
      case 'GMX_LP':
        return 'GMX GM pool';
      case 'GLV_LP':
        return 'GMX GLV vault';
      case 'PENDLE':
      case 'PENDLE_LP':
        return 'Pendle LPT';
      default:
        return family.replaceAll('_', ' ');
    }
  }

  statusLabel(status: string): string {
    switch (status) {
      case 'in_range':
        return 'In range';
      case 'out_of_range':
        return 'Out of range';
      case 'closed':
        return 'Closed';
      case 'unknown':
        return 'Tracking';
      default:
        return status;
    }
  }

  statusClass(status: string): string {
    return status.replaceAll('_', '-');
  }

  precisionClass(precision: LpPrecision): string {
    return precision.toLowerCase().replaceAll('_', '-');
  }

  formatPrecisionUsd(value: number | null, precision: LpPrecision): string {
    if (precision === 'UNAVAILABLE' || precision === 'N/A') {
      return precision === 'N/A' ? 'N/A' : 'Unavailable';
    }
    if (value === null) {
      return 'Unavailable';
    }
    return this.formatUsd(value);
  }

  formatPrecisionSignedUsd(value: number | null, precision: LpPrecision): string {
    if (precision === 'UNAVAILABLE' || precision === 'N/A') {
      return precision === 'N/A' ? 'N/A' : 'Unavailable';
    }
    if (value === null) {
      return 'Unavailable';
    }
    const formatted = this.formatUsd(value);
    return value > 0 ? `+${formatted}` : formatted;
  }

  formatPrecisionPct(value: number | null, precision: LpPrecision): string {
    if (precision === 'UNAVAILABLE' || precision === 'N/A') {
      return precision === 'N/A' ? 'N/A' : 'Unavailable';
    }
    if (value === null) {
      return 'Unavailable';
    }
    return `${value >= 0 ? '+' : ''}${value.toFixed(2)}%`;
  }

  formatApr(value: number | null, precision: LpPrecision): string {
    if (precision === 'UNAVAILABLE' || precision === 'N/A' || value === null) {
      return precision === 'N/A' ? 'N/A' : '--';
    }
    const prefix = precision === 'ESTIMATED' ? 'Est. ' : '';
    return `${prefix}${value.toFixed(2)}%`;
  }

  formatSignedUsd(value: number | null): string {
    if (value === null) return 'Unavailable';
    return smartFormatSignedUsd(value);
  }

  formatUsd(value: number | null): string {
    return smartFormatUsd(value);
  }

  formatQuantity(value: number | null): string {
    return smartFormatQty(value);
  }

  formatDate(value: string | null): string {
    if (value === null) {
      return 'unknown';
    }
    return new Intl.DateTimeFormat('en-US', { month: 'short', day: '2-digit', year: 'numeric' }).format(new Date(value));
  }

  formatChartDate(value: string): string {
    const date = new Date(value);
    const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    return `${date.getDate()} ${months[date.getMonth()]}`;
  }

  formatSnapshotFreshness(position: LpPosition): string {
    const freshness = this.formatSyncFreshness(position);
    if (freshness === null) {
      return 'No snapshot';
    }
    return freshness === 'just now' ? 'Updated just now' : `Updated ${freshness}`;
  }

  private formatSyncFreshness(position: LpPosition): string | null {
    const syncedAt = this.latestSyncAt(position);
    if (syncedAt === null) {
      return null;
    }
    const ageMinutes = Math.floor((Date.now() - syncedAt.getTime()) / 60_000);
    if (ageMinutes < 1) {
      return 'just now';
    }
    if (ageMinutes < 60) {
      return `${ageMinutes}m ago`;
    }
    const hours = Math.floor(ageMinutes / 60);
    if (hours < 48) {
      return `${hours}h ago`;
    }
    return `${Math.floor(hours / 24)}d ago`;
  }

  private hasRecentSync(position: LpPosition): boolean {
    const syncedAt = this.latestSyncAt(position);
    if (syncedAt === null) {
      return false;
    }
    return Date.now() - syncedAt.getTime() < 5 * 60_000;
  }

  private latestSyncAt(position: LpPosition): Date | null {
    const state = this.refreshStateById().get(position.correlationId);
    const candidates = [state?.lastSyncedAt, state?.completedAt, position.snapshotAt]
      .filter((raw): raw is string => raw !== null && raw !== undefined)
      .map((raw) => new Date(raw))
      .filter((date) => !Number.isNaN(date.getTime()));
    if (candidates.length === 0) {
      return null;
    }
    return new Date(Math.max(...candidates.map((date) => date.getTime())));
  }

  private markRefreshQueued(correlationId: string): void {
    const now = new Date().toISOString();
    const next = new Map(this.refreshStateById());
    const previous = next.get(correlationId);
    next.set(correlationId, {
      id: correlationId,
      status: 'QUEUED',
      trigger: 'MANUAL',
      requestedAt: now,
      startedAt: null,
      completedAt: null,
      lastSyncedAt: previous?.lastSyncedAt ?? null,
      error: null,
    });
    this.refreshStateById.set(next);
    this.refreshAnyActive.set(true);
  }

  miniRangeStyle(position: LpPosition): { readonly leftPct: number; readonly widthPct: number; readonly currentPct: number } | null {
    const { priceLow, priceHigh, priceCurrent } = position.range;
    if (priceLow === null || priceHigh === null || priceCurrent === null) {
      return null;
    }
    const margin = 0.25;
    const min = priceLow * (1 - margin);
    const max = priceHigh * (1 + margin);
    const range = max - min;
    return {
      leftPct: ((priceLow - min) / range) * 100,
      widthPct: ((priceHigh - priceLow) / range) * 100,
      currentPct: ((priceCurrent - min) / range) * 100,
    };
  }

  rangeChartX(value: number, position: LpPosition, width: number, padLeft: number, padRight: number): number {
    const { priceLow, priceHigh } = position.range;
    if (priceLow === null || priceHigh === null) {
      return padLeft;
    }
    const margin = 0.3;
    const min = priceLow * (1 - margin);
    const max = priceHigh * (1 + margin);
    const innerWidth = width - padLeft - padRight;
    return padLeft + ((value - min) / (max - min)) * innerWidth;
  }

  pnlClass(value: number | null): string {
    if (value === null || value === 0) {
      return 'muted-value';
    }
    return value >= 0 ? 'pos' : 'neg';
  }

  txClass(type: string): string {
    if (type.startsWith('LP_EXIT')) {
      return 'exit';
    }
    switch (type) {
      case 'LP_ENTRY':
        return 'entry';
      case 'LP_FEE_CLAIM':
        return 'claim';
      case 'LP_POSITION_STAKE':
      case 'LP_POSITION_UNSTAKE':
        return 'stake';
      default:
        return 'default';
    }
  }

  txIcon(type: string): string {
    if (type.startsWith('LP_EXIT')) {
      return '↓';
    }
    if (type === 'LP_FEE_CLAIM') {
      return '+';
    }
    if (type === 'LP_POSITION_STAKE' || type === 'LP_POSITION_UNSTAKE') {
      return '⚡';
    }
    return '↑';
  }

  formatTxnUsd(txn: { readonly valueUsd: number | null; readonly valueUsdPrecision: LpPrecision }): string {
    if (txn.valueUsdPrecision === 'N/A') {
      return '—';
    }
    if (txn.valueUsdPrecision === 'UNAVAILABLE' || txn.valueUsd === null) {
      return 'Unavailable';
    }
    return this.formatSignedUsd(txn.valueUsd);
  }

  networkIcon(networkId: string): string {
    return EVM_NETWORK_PRESENTATION_BY_ID.get(networkId as EvmNetworkId)?.icon ?? '•';
  }

  networkLabel(networkId: string): string {
    return EVM_NETWORK_PRESENTATION_BY_ID.get(networkId as EvmNetworkId)?.label ?? networkId;
  }

  networkColor(networkId: string): string {
    return EVM_NETWORK_PRESENTATION_BY_ID.get(networkId as EvmNetworkId)?.color ?? COLORS.textSubtle;
  }

  shortAddress(address: string): string {
    return address.length <= 12 ? address : `${address.slice(0, 6)}...${address.slice(-4)}`;
  }

  shortHash(hash: string | null): string {
    return hash === null || hash.length <= 14 ? hash ?? '' : `${hash.slice(0, 8)}...${hash.slice(-6)}`;
  }

  entryShare(position: LpPosition, side: 'token0' | 'token1'): number {
    const entry = side === 'token0' ? position.entryToken0 : position.entryToken1;
    const other = side === 'token0' ? position.entryToken1 : position.entryToken0;
    const entryUsd = entry?.valueUsd ?? 0;
    const otherUsd = other?.valueUsd ?? 0;
    const total = entryUsd + otherUsd;
    if (total <= 0) {
      return 0.5;
    }
    return entryUsd / total;
  }

  entryToken(position: LpPosition, side: 'token0' | 'token1') {
    return side === 'token0' ? position.entryToken0 : position.entryToken1;
  }

  tokenCompositionShare(position: LpPosition, side: 'token0' | 'token1'): number {
    const token0Usd = position.token0.valueUsd ?? 0;
    const token1Usd = position.token1.valueUsd ?? 0;
    const total = token0Usd + token1Usd;
    if (total <= 0) {
      return 0.5;
    }
    return side === 'token0' ? token0Usd / total : token1Usd / total;
  }

  readonly DIST_W = 460;
  readonly DIST_PAD_L = 8;
  readonly DIST_PAD_R = 8;
  readonly DIST_PAD_T = 18;
  readonly DIST_PAD_B = 28;
  readonly DIST_H = 160;

  distInnerW(): number { return this.DIST_W - this.DIST_PAD_L - this.DIST_PAD_R; }
  distInnerH(): number { return this.DIST_H - this.DIST_PAD_T - this.DIST_PAD_B; }
  distBaseY(): number { return this.DIST_PAD_T + this.distInnerH(); }

  liquidityHistogramBars(position: LpPosition): ReadonlyArray<DistributionBar> {
    const bins = position.range.liquidityBins;
    if (!bins || bins.length === 0) return [];

    const prices = bins.flatMap(b => [b.priceLower, b.priceUpper].filter((p): p is number => p != null));
    if (prices.length === 0) return [];

    const minPrice = Math.min(...prices);
    const maxPrice = Math.max(...prices);
    const priceRange = maxPrice - minPrice;
    if (priceRange === 0) return [];

    const innerW = this.distInnerW();
    const innerH = this.distInnerH();
    const xAt = (price: number) => this.DIST_PAD_L + ((price - minPrice) / priceRange) * innerW;
    const { tickLower, tickUpper } = position.range;

    return bins.map(bin => {
      const x = xAt(bin.priceLower ?? minPrice);
      const xRight = xAt(bin.priceUpper ?? maxPrice);
      const barWidth = Math.max(xRight - x - 1, 1);
      const height = Math.max(innerH * bin.liquidityShare, 1);
      const inRange = tickLower !== null && tickUpper !== null
        && bin.tickLower < tickUpper && bin.tickUpper > tickLower;
      return { x, barWidth, height, inRange };
    });
  }

  hasLiquidityBins(position: LpPosition): boolean {
    return position.range.liquidityBins.length > 0;
  }

  distributionPriceAxisTicks(position: LpPosition): ReadonlyArray<{ x: number; label: string }> {
    const bins = position.range.liquidityBins;
    if (bins.length === 0) return [];
    const prices = bins.flatMap(b => [b.priceLower, b.priceUpper].filter((p): p is number => p != null));
    if (prices.length === 0) return [];
    const minPrice = Math.min(...prices);
    const maxPrice = Math.max(...prices);
    const priceRange = maxPrice - minPrice;
    if (priceRange === 0) return [];
    const innerW = this.distInnerW();
    const xAt = (price: number) => this.DIST_PAD_L + ((price - minPrice) / priceRange) * innerW;
    const ticks: { x: number; label: string }[] = [];
    const steps = 4;
    for (let i = 0; i <= steps; i++) {
      const price = minPrice + (priceRange * i) / steps;
      ticks.push({ x: xAt(price), label: this.formatPrice(price) });
    }
    return ticks;
  }

  distributionCurrentPriceX(position: LpPosition): number | null {
    const { priceCurrent, liquidityBins } = position.range;
    if (priceCurrent === null || liquidityBins.length === 0) return null;
    const prices = liquidityBins.flatMap(b => [b.priceLower, b.priceUpper].filter((p): p is number => p != null));
    if (prices.length === 0) return null;
    const minPrice = Math.min(...prices);
    const maxPrice = Math.max(...prices);
    const priceRange = maxPrice - minPrice;
    if (priceRange === 0) return null;
    return this.DIST_PAD_L + ((priceCurrent - minPrice) / priceRange) * this.distInnerW();
  }

  distributionRangeX(position: LpPosition, which: 'low' | 'high'): number | null {
    const price = which === 'low' ? position.range.priceLow : position.range.priceHigh;
    if (price === null || position.range.liquidityBins.length === 0) return null;
    const bins = position.range.liquidityBins;
    const prices = bins.flatMap(b => [b.priceLower, b.priceUpper].filter((p): p is number => p != null));
    if (prices.length === 0) return null;
    const minPrice = Math.min(...prices);
    const maxPrice = Math.max(...prices);
    const priceRange = maxPrice - minPrice;
    if (priceRange === 0) return null;
    const x = this.DIST_PAD_L + ((price - minPrice) / priceRange) * this.distInnerW();
    return Math.max(this.DIST_PAD_L, Math.min(this.DIST_PAD_L + this.distInnerW(), x));
  }

  distributionCurvePath(position: LpPosition): string {
    const { priceLow, priceHigh } = position.range;
    if (priceLow === null || priceHigh === null) {
      return '';
    }
    const width = 460;
    const padL = 8;
    const padR = 8;
    const padT = 18;
    const padB = 24;
    const innerH = 150 - padT - padB;
    const margin = 0.3;
    const min = priceLow * (1 - margin);
    const max = priceHigh * (1 + margin);
    const xAt = (price: number) => padL + ((price - min) / (max - min)) * (width - padL - padR);
    const lx = xAt(priceLow);
    const rx = xAt(priceHigh);
    const mid = (lx + rx) / 2;
    const peakY = padT + innerH * 0.15;
    const baseY = padT + innerH;
    return `M${lx},${baseY} Q${mid},${peakY} ${rx},${baseY} Z`;
  }

  earningsLabelStep(count: number): number {
    if (count <= 30) return 7;
    if (count <= 90) return 14;
    if (count <= 180) return 30;
    return 60;
  }

  // ── Copy-to-clipboard ───────────────────────────────────────────────────────

  copyTxHash(hash: string | null): void {
    if (!hash) return;
    navigator.clipboard.writeText(hash).then(() => {
      this.copiedHashes.update(s => new Set([...s, hash]));
      setTimeout(() => this.copiedHashes.update(s => new Set([...s].filter(h => h !== hash))), 2000);
    }).catch(() => { /* ignore */ });
  }

  isCopied(hash: string | null): boolean {
    return hash !== null && this.copiedHashes().has(hash);
  }

  // ── Price formatting ────────────────────────────────────────────────────────

  formatPrice(price: number | null): string {
    if (price === null) return '';
    const abs = Math.abs(price);
    if (abs === 0) return '$0';
    if (abs >= 10_000) return `$${(price / 1000).toFixed(1)}k`;
    if (abs >= 1_000) return `$${price.toFixed(0)}`;
    if (abs >= 100) return `$${price.toFixed(1)}`;
    if (abs >= 1) return `$${price.toFixed(2)}`;
    if (abs >= 0.01) return `$${price.toFixed(4)}`;
    return `$${price.toPrecision(3)}`;
  }

  formatTxnLeg(quantity: number, symbol: string): string {
    const sign = quantity > 0 ? '+' : '';
    return `${sign}${this.formatQuantity(quantity)} ${symbol}`;
  }

  formatTxnTotal(valueUsd: number | null, precision: LpPrecision): string {
    if (precision === 'N/A') {
      return '—';
    }
    if (valueUsd === null) {
      return '';
    }
    return this.formatSignedUsd(valueUsd);
  }

  formatTxnGas(valueUsd: number | null): string {
    if (valueUsd === null || valueUsd === 0) {
      return '';
    }
    return this.formatUsd(valueUsd);
  }

  txnLegClass(quantity: number): string {
    return quantity < 0 ? 'out' : 'in';
  }

  entryVsCurrentRows(position: LpPosition): ReadonlyArray<{
    label: string;
    entry: string;
    current: string;
    change: string;
    changePositive: boolean | null;
  }> {
    const rows: { label: string; entry: string; current: string; change: string; changePositive: boolean | null }[] = [];
    const currentTvl = position.tvlUsd.valueUsd ?? 0;

    for (const side of ['token0', 'token1'] as const) {
      const entry = side === 'token0' ? position.entryToken0 : position.entryToken1;
      const token = side === 'token0' ? position.token0 : position.token1;
      if (!entry) continue;
      const qtyDelta = token.quantity - entry.quantity;
      rows.push({
        label: `${token.symbol} amount`,
        entry: this.formatQuantity(entry.quantity),
        current: this.formatQuantity(token.quantity),
        change: qtyDelta === 0 ? '—' : `${qtyDelta > 0 ? '+' : ''}${this.formatQuantity(qtyDelta)}`,
        changePositive: qtyDelta === 0 ? null : qtyDelta > 0,
      });
    }

    // For open positions: use hodl value (entry qty × current prices) so the TVL delta
    // equals impermanent loss — the canonical LP performance metric.
    // For closed positions: use AVCO cost basis (tax-correct realized cost).
    const closed = position.status === 'closed';
    const entryTvl = closed
      ? (position.costBasisUsd ?? 0)
      : ((position.entryToken0?.valueUsd ?? 0) + (position.entryToken1?.valueUsd ?? 0)) || (position.costBasisUsd ?? 0);
    const tvlDelta = currentTvl - entryTvl;
    rows.push({
      label: 'TVL (USD)',
      entry: this.formatUsd(entryTvl),
      current: this.formatPrecisionUsd(currentTvl, position.tvlUsd.precision),
      change: entryTvl === 0 ? '—' : `${tvlDelta >= 0 ? '+' : ''}${this.formatUsd(tvlDelta)}`,
      changePositive: entryTvl === 0 ? null : tvlDelta >= 0,
    });

    const aprEntry = position.apr.avg ?? position.apr.now;
    const aprNow = position.apr.now;
    if (aprEntry !== null && aprNow !== null) {
      const aprDelta = aprNow - aprEntry;
      rows.push({
        label: 'APR',
        entry: this.formatApr(aprEntry, position.apr.precision),
        current: this.formatApr(aprNow, position.apr.precision),
        change: `${aprDelta >= 0 ? '+' : ''}${aprDelta.toFixed(2)} pp`,
        changePositive: aprDelta >= 0,
      });
    }

    return rows;
  }

  statusChipColor(status: string): string {
    switch (status) {
      case 'in_range':   return 'var(--wr-green)';
      case 'out_of_range': return 'var(--wr-red)';
      case 'closed':     return 'var(--wr-subtle)';
      case 'unknown':    return 'var(--wr-amber)';
      default:           return 'var(--wr-subtle)';
    }
  }

  summaryStats(): ReadonlyArray<{ label: string; value: string; cls: string }> {
    const summary = this.filteredSummary();
    return [
      { label: 'Active TVL',   value: this.formatUsd(summary.activeTvlUsd),          cls: '' },
      { label: 'Fees Earned',  value: this.formatUsd(summary.feesEarnedUsd),          cls: 'pos' },
      { label: 'Unclaimed',    value: this.formatUsd(summary.unclaimedUsd),           cls: 'warn' },
      { label: 'In Range',     value: String(summary.inRange),                         cls: 'pos' },
      { label: 'Out of Range', value: String(summary.outOfRange),                      cls: summary.outOfRange > 0 ? 'neg' : 'muted-value' },
      { label: 'Realized P&L', value: this.formatSignedUsd(summary.realizedPnlUsd),   cls: this.pnlClass(summary.realizedPnlUsd) },
    ];
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
}
