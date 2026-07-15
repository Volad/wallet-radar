import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { SmartAmountComponent } from '../../../../core/components/smart-amount/smart-amount.component';

/* ── Input contracts ──────────────────────────────────────── */

export interface BreakdownAllocationRow {
  readonly id: string;
  readonly label: string;
  readonly icon: string | null;
  readonly color: string;
  readonly valueUsd: number;
  readonly sharePct: number;
}

export interface BreakdownMoreSummary {
  readonly count: number;
  readonly valueUsd: number;
  readonly sharePct: number;
}

export interface BreakdownTokenRow {
  readonly familyIdentity: string;
  readonly symbol: string;
  readonly currentValueUsd: number;
  readonly totalCostBasisUsd: number;
  readonly unrealizedPnlUsd: number;
  readonly unrealizedPnlPct: number;
}

export interface OnChainCexSplit {
  readonly onChainPct: number;
  readonly cexPct: number;
}

/* ── Internal types ───────────────────────────────────────── */

interface DonutSegment {
  readonly path: string;
  readonly color: string;
  readonly label: string;
  readonly value: number;
  readonly pct: number;
}

interface PnlRow {
  readonly label: string;
  readonly color: string;
  readonly value: number;
  readonly pnlUsd: number;
  readonly pnlPct: number;
  readonly fillPct: number;
  readonly isPos: boolean;
}

type SortCol   = 'label' | 'value' | 'share' | 'pnlAmt' | 'pnlPct';
type SortDir   = 'asc' | 'desc';
type PageIndex = 0 | 1;
type ChartMode = 'donut' | 'pnl';
type PnlMetric = 'pct' | 'amount';

/* ── Constants ────────────────────────────────────────────── */

const DONUT_COLORS = [
  '#22d3ee', '#34d399', '#f97316', '#a78bfa',
  '#60a5fa', '#fbbf24', '#8b5cf6', '#f472b6',
  '#28a0f0', '#ff0420', '#2a5ada', '#b6509e', '#4a5878',
];

const CX = 70, CY = 70, RA = 62, RI = 40;

/* ── Pure helpers ─────────────────────────────────────────── */

function donutArcPath(cx: number, cy: number, ra: number, ri: number, a0: number, a1: number): string {
  const sweep = a1 - a0;
  const large = sweep > Math.PI ? 1 : 0;
  const c0 = Math.cos(a0), s0 = Math.sin(a0);
  const c1 = Math.cos(a1), s1 = Math.sin(a1);
  return [
    `M ${(cx + ra * c0).toFixed(2)} ${(cy + ra * s0).toFixed(2)}`,
    `A ${ra} ${ra} 0 ${large} 1 ${(cx + ra * c1).toFixed(2)} ${(cy + ra * s1).toFixed(2)}`,
    `L ${(cx + ri * c1).toFixed(2)} ${(cy + ri * s1).toFixed(2)}`,
    `A ${ri} ${ri} 0 ${large} 0 ${(cx + ri * c0).toFixed(2)} ${(cy + ri * s0).toFixed(2)}`,
    'Z',
  ].join(' ');
}

function colorFromId(id: string): string {
  let h = 0;
  for (let i = 0; i < id.length; i++) h = (Math.imul(31, h) + id.charCodeAt(i)) | 0;
  return DONUT_COLORS[Math.abs(h) % DONUT_COLORS.length];
}

/* ── Component ────────────────────────────────────────────── */

@Component({
  selector: 'wr-allocation-breakdown',
  standalone: true,
  imports: [SmartAmountComponent],
  templateUrl: './allocation-breakdown.component.html',
  styleUrl: './allocation-breakdown.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AllocationBreakdownComponent {

  /* ── Inputs ─────────────────────────────────────────────── */

  readonly totalUsd           = input.required<number>();
  readonly onChainCexSplit    = input.required<OnChainCexSplit>();
  readonly networksByBar      = input.required<readonly BreakdownAllocationRow[]>();
  readonly visibleNetworks    = input.required<readonly BreakdownAllocationRow[]>();
  readonly networksMore       = input.required<BreakdownMoreSummary>();
  readonly networksExpanded   = input.required<boolean>();
  readonly visibleWallets     = input.required<readonly BreakdownAllocationRow[]>();
  readonly walletsMore        = input.required<BreakdownMoreSummary>();
  readonly walletsExpanded    = input.required<boolean>();
  readonly walletsForBar      = input.required<readonly BreakdownAllocationRow[]>();
  readonly unrealizedPnlUsd   = input.required<number>();
  readonly unrealizedPnlPct   = input.required<number>();
  readonly realizedPnlUsd     = input.required<number>();
  readonly isFiltered         = input.required<boolean>();
  readonly tokenFamilies      = input.required<readonly BreakdownTokenRow[]>();

  /* ── Outputs ─────────────────────────────────────────────── */

  readonly networksToggle = output<void>();
  readonly walletsToggle  = output<void>();

  /* ── Carousel state ─────────────────────────────────────── */

  readonly allocationPage = signal<PageIndex>(0);

  /* ── Page-2 state ───────────────────────────────────────── */

  readonly chartMode  = signal<ChartMode>('donut');
  readonly pnlMetric  = signal<PnlMetric>('pct');
  readonly sortCol    = signal<SortCol>('value');
  readonly sortDir    = signal<SortDir>('desc');
  readonly hoveredIdx  = signal<number | null>(null);
  readonly pinnedIdx   = signal<number | null>(null);

  /* ── Derived: effective highlight index (pin beats hover) ── */

  readonly effectiveHoverIdx = computed<number | null>(() =>
    this.pinnedIdx() ?? this.hoveredIdx(),
  );

  /* ── Derived: highlighted identity (pin OR hover, for table highlight + donut) */

  readonly highlightedIdentity = computed<string | null>(() => {
    const idx = this.effectiveHoverIdx();
    if (idx === null) return null;
    const tokens = this.tokenFamilies();
    return idx < tokens.length ? tokens[idx].familyIdentity : null;
  });

  /* ── Derived: familyIdentity → segment index map ────────── */

  readonly familyToSegIdx = computed<ReadonlyMap<string, number>>(() => {
    const map = new Map<string, number>();
    this.tokenFamilies().forEach((t, i) => map.set(t.familyIdentity, i));
    return map;
  });

  /* ── Derived: color map (stable by familyIdentity) ──────── */

  readonly tokenColorMap = computed(() => {
    const map = new Map<string, string>();
    this.tokenFamilies().forEach(t => map.set(t.familyIdentity, colorFromId(t.familyIdentity)));
    return map;
  });

  /* ── Derived: donut segments ────────────────────────────── */

  readonly donutSegments = computed<DonutSegment[]>(() => {
    const tokens = this.tokenFamilies();
    const total  = tokens.reduce((s, t) => s + t.currentValueUsd, 0);
    if (total === 0) return [];

    let angle = -Math.PI / 2;
    return tokens.map(t => {
      const pct   = t.currentValueUsd / total;
      const sweep = pct * 2 * Math.PI;
      const a0    = angle;
      angle      += sweep;
      return {
        path  : donutArcPath(CX, CY, RA, RI, a0, a0 + sweep),
        color : colorFromId(t.familyIdentity),
        label : t.symbol,
        value : t.currentValueUsd,
        pct   : pct * 100,
      };
    });
  });

  /* ── Derived: hovered/pinned segment ────────────────────── */

  readonly hoveredSegment = computed<DonutSegment | null>(() => {
    const idx  = this.effectiveHoverIdx();
    const segs = this.donutSegments();
    return idx !== null && idx < segs.length ? segs[idx] : null;
  });

  /* ── Derived: PnL chart rows ────────────────────────────── */

  readonly pnlChartRows = computed<PnlRow[]>(() => {
    const metric   = this.pnlMetric();
    const tokens   = this.tokenFamilies();
    const getValue = (t: BreakdownTokenRow) => metric === 'pct' ? t.unrealizedPnlPct : t.unrealizedPnlUsd;
    const maxAbs   = Math.max(...tokens.map(t => Math.abs(getValue(t))), 0.001);

    return [...tokens]
      .map(t => {
        const v = getValue(t);
        return {
          label   : t.symbol,
          color   : colorFromId(t.familyIdentity),
          value   : v,
          pnlUsd  : t.unrealizedPnlUsd,
          pnlPct  : t.unrealizedPnlPct,
          fillPct : Math.abs(v) / maxAbs * 100,
          isPos   : v >= 0,
        };
      })
      .sort((a, b) => b.value - a.value);
  });

  /* ── Derived: sorted token table ────────────────────────── */

  readonly sortedTokens = computed<readonly BreakdownTokenRow[]>(() => {
    const col = this.sortCol();
    const dir = this.sortDir();

    return [...this.tokenFamilies()].sort((a, b) => {
      let va: number | string;
      let vb: number | string;

      switch (col) {
        case 'label':  va = a.symbol;               vb = b.symbol;               break;
        case 'pnlAmt': va = a.unrealizedPnlUsd;     vb = b.unrealizedPnlUsd;     break;
        case 'pnlPct': va = a.unrealizedPnlPct;     vb = b.unrealizedPnlPct;     break;
        default:       va = a.currentValueUsd;       vb = b.currentValueUsd;      break;
      }

      if (col === 'label') {
        return dir === 'asc'
          ? (va as string).localeCompare(vb as string)
          : (vb as string).localeCompare(va as string);
      }
      return dir === 'asc' ? (va as number) - (vb as number) : (vb as number) - (va as number);
    });
  });

  /* ── Derived: totals ─────────────────────────────────────── */

  readonly totalPnlUsd = computed(() =>
    this.tokenFamilies().reduce((s, t) => s + t.unrealizedPnlUsd, 0),
  );

  readonly totalPnlPct = computed(() => {
    const cost = this.tokenFamilies().reduce((s, t) => s + t.totalCostBasisUsd, 0);
    return cost === 0 ? 0 : (this.totalPnlUsd() / cost) * 100;
  });

  /* ── Page metadata ───────────────────────────────────────── */

  readonly pages = [
    { label: 'Wallets & Networks' },
    { label: 'Token Distribution' },
  ] as const;

  /* ── Actions ─────────────────────────────────────────────── */

  setPage(page: PageIndex): void          { this.allocationPage.set(page); }
  toggleNetworks(): void                  { this.networksToggle.emit(); }
  toggleWallets(): void                   { this.walletsToggle.emit(); }
  setChartMode(mode: ChartMode): void     { this.chartMode.set(mode); }
  setPnlMetric(metric: PnlMetric): void   { this.pnlMetric.set(metric); }
  hoverSegment(idx: number | null): void  { this.hoveredIdx.set(idx); }

  pinByIdentity(id: string): void {
    const idx = this.familyToSegIdx().get(id) ?? null;
    this.pinnedIdx.update(cur => (cur === idx ? null : idx));
  }

  pinByIdx(idx: number): void {
    this.pinnedIdx.update(cur => (cur === idx ? null : idx));
  }

  handleSort(col: SortCol): void {
    if (this.sortCol() === col) {
      this.sortDir.update(d => d === 'desc' ? 'asc' : 'desc');
    } else {
      this.sortCol.set(col);
      this.sortDir.set('desc');
    }
  }

  /* ── Formatting ──────────────────────────────────────────── */

  formatPct(v: number): string {
    return `${v >= 0 ? '+' : ''}${v.toFixed(1)}%`;
  }

  formatAmount(value: number): string {
    const abs = Math.abs(value);
    const sign = value < 0 ? '-' : value > 0 ? '+' : '';
    if (abs >= 1_000_000) return `${sign}$${(abs / 1_000_000).toFixed(2)}M`;
    if (abs >= 1_000)     return `${sign}$${(abs / 1_000).toFixed(2)}k`;
    return `${sign}$${abs.toFixed(2)}`;
  }

  tokenSharePct(valueUsd: number): number {
    const total = this.totalUsd();
    return total > 0 ? (valueUsd / total) * 100 : 0;
  }
}
