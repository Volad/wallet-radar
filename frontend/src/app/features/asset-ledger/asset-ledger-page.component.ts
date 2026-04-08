import { HttpErrorResponse } from '@angular/common/http';
import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  HostListener,
  QueryList,
  ViewChild,
  ViewChildren,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { combineLatest, map, of, startWith, switchMap, catchError } from 'rxjs';

import {
  SessionAssetLedgerEventFlowResponse,
  SessionAssetLedgerEventOverlayResponse,
  SessionAssetLedgerResponse,
  SessionAssetLedgerTimelineEntryResponse,
  SessionResponse,
} from '../../core/models/wallet-api.models';
import { WalletApiService } from '../../core/services/wallet-api.service';

type PageState =
  | { readonly status: 'loading' }
  | { readonly status: 'error'; readonly message: string }
  | { readonly status: 'success'; readonly data: AssetLedgerViewModel };

interface AssetLedgerViewModel {
  readonly sessionId: string;
  readonly familyIdentity: string;
  readonly displaySymbol: string;
  readonly subtitle: string;
  readonly current: AssetCurrentView;
  readonly legendItems: ReadonlyArray<LegendItemView>;
  readonly markers: ReadonlyArray<MarkerView>;
  readonly markerLookup: Readonly<Record<string, MarkerView>>;
}

interface AssetCurrentView {
  readonly quantity: number;
  readonly coveredQuantity: number;
  readonly uncoveredQuantity: number;
  readonly totalCostBasisUsd: number | null;
  readonly avcoUsd: number | null;
  readonly realisedPnlUsd: number;
  readonly gasPaidUsd: number;
}

interface LegendItemView {
  readonly key: string;
  readonly typeKey: string;
  readonly label: string;
  readonly glyph: string;
  readonly color: string;
}

interface BasisFilterView {
  readonly key: string;
  readonly label: string;
  readonly color: string;
}

type EventFamilyKey = 'lp' | 'bridge' | 'transfer' | 'lending' | 'reward' | 'staking';

interface EventFamilyFilterView {
  readonly key: EventFamilyKey;
  readonly label: string;
  readonly color: string;
  readonly typeKeys: ReadonlyArray<string>;
  readonly eventCount: number;
}

interface QuickPresetView {
  readonly key: QuickPresetKey;
  readonly label: string;
}

interface MarkerView {
  readonly id: string;
  readonly typeKey: string;
  readonly txHash: string;
  readonly timestamp: string;
  readonly glyph: string;
  readonly color: string;
  readonly label: string;
  readonly protocolName: string | null;
  readonly displayVenue: string | null;
  readonly lifecycleKind: string | null;
  readonly networkLabel: string;
  readonly quantityDelta: number;
  readonly amountUsd: number | null;
  readonly quantityAfter: number;
  readonly coveredQuantityAfter: number;
  readonly uncoveredQuantityAfter: number;
  readonly totalCostBasisAfterUsd: number | null;
  readonly avcoBeforeUsd: number | null;
  readonly avcoAfterUsd: number | null;
  readonly realisedPnlDeltaUsd: number | null;
  readonly gasDeltaUsd: number | null;
  readonly basisEffects: ReadonlyArray<string>;
  readonly basisSummary: string;
  readonly priceUsd: number | null;
  readonly priceSource: string | null;
  readonly primaryFlowLabel: string | null;
  readonly pathFrom: string;
  readonly pathTo: string;
  readonly flows: ReadonlyArray<FlowChipView>;
}

interface FlowChipView {
  readonly role: string;
  readonly assetSymbol: string;
  readonly quantityLabel: string;
  readonly className: string;
}

interface RenderedMarkerView {
  readonly markerId: string;
  readonly x: number;
  readonly y: number;
  readonly avcoY: number;
  readonly hasStem: boolean;
}

interface RenderedPointView {
  readonly markerId: string;
  readonly x: number;
  readonly y: number;
  readonly radius: number;
}

interface RenderedPnlMarkerView extends RenderedPointView {
  readonly barLeft: number;
  readonly barRight: number;
  readonly barTop: number;
  readonly barBottom: number;
  readonly cumulativeY: number;
}

type RangeDragMode = 'move' | 'start' | 'end';

const ETH_FAMILY_SYMBOLS = new Set(['ETH', 'WETH', 'AETHWETH', 'AARBWETH', 'ALINWETH', 'AMANWETH', 'AZKSWETH', 'VBETH']);
const BTC_FAMILY_SYMBOLS = new Set(['BTC', 'WBTC', 'AARBWBTC', 'AETHWBTC', 'ALINWBTC', 'AMANWBTC', 'AZKSWBTC']);
const AVAX_FAMILY_SYMBOLS = new Set(['AVAX', 'WAVAX', 'SAVAX', 'AAVAWAVAX', 'AAVASAVAX']);
const MNT_FAMILY_SYMBOLS = new Set(['MNT', 'WMNT', 'AMANMNT']);
const USDC_FAMILY_SYMBOLS = new Set(['USDC', 'VBUSDC']);
const STABLECOIN_SYMBOLS = new Set(['USDT', 'USDC', 'USDE', 'USDS', 'USDD', 'DAI', 'FDUSD', 'PYUSD', 'TUSD', 'USD1']);
const DEFAULT_RANGE_DAYS = 21;
const DEFAULT_RANGE_MIN_POINTS = 16;
const DEFAULT_DISABLED_TYPE_KEYS = new Set(['WRAP', 'UNWRAP']);
const DEFAULT_HIDDEN_BASIS_EFFECTS = new Set(['GAS_ONLY']);
const BASIS_MOVE_EFFECTS = new Set(['CARRY_IN', 'CARRY_OUT', 'REALLOCATE_IN', 'REALLOCATE_OUT']);
const TRANSFER_TYPE_KEYS = new Set(['BRIDGE_IN', 'BRIDGE_OUT', 'INTERNAL_TRANSFER', 'EXTERNAL_TRANSFER_IN', 'EXTERNAL_TRANSFER_OUT']);

type QuickPresetKey = 'economics' | 'all' | 'transfers' | 'basisMoves';

const CHART = {
  width: 1120,
  height: 420,
  left: 78,
  right: 32,
  top: 28,
  bottom: 56,
};
const QTY_CHART_HEIGHT = 176;
const PNL_CHART_HEIGHT = 208;

type IconRenderer = (ctx: CanvasRenderingContext2D, cx: number, cy: number, r: number) => void;

interface TypeVisualMeta {
  readonly label: string;
  readonly glyph: string;
  readonly color: string;
  readonly icon: IconRenderer;
}

interface TypeDisplayOverride {
  readonly label: string;
  readonly baseType: string;
}

const FALLBACK_ICON: IconRenderer = (ctx, cx, cy, r) => {
  const a = r * 0.45;
  ctx.beginPath();
  ctx.arc(cx, cy, a, 0, Math.PI * 2);
  ctx.stroke();
  ctx.beginPath();
  ctx.moveTo(cx, cy - a * 0.3);
  ctx.lineTo(cx, cy + a * 0.1);
  ctx.moveTo(cx, cy + a * 0.35);
  ctx.arc(cx, cy + a * 0.42, a * 0.1, 0, Math.PI * 2);
  ctx.stroke();
};

function heuristicTypeMeta(typeKey: string): TypeVisualMeta {
  const key = typeKey.toUpperCase();
  const label = prettifyTypeLabel(typeKey);
  if (key.includes('SWAP')) {
    return { ...TYPE_META['SWAP'], label };
  }
  if (key.includes('LENDING') || key.includes('LOOP')) {
    if (key.includes('WITHDRAW') || key.includes('DECREASE') || key.includes('CLOSE')) {
      return { ...TYPE_META['LENDING_WITHDRAW'], label };
    }
    if (key.includes('BORROW')) {
      return { ...TYPE_META['BORROW'], label };
    }
    if (key.includes('REPAY')) {
      return { ...TYPE_META['REPAY'], label };
    }
    return { ...TYPE_META['LENDING_DEPOSIT'], label };
  }
  if (key.includes('STAK')) {
    if (key.includes('WITHDRAW') || key.includes('UNSTAKE')) {
      return { ...TYPE_META['STAKING_WITHDRAW'], label };
    }
    return { ...TYPE_META['STAKING_DEPOSIT'], label };
  }
  if (key.includes('BRIDGE')) {
    if (key.includes('OUT')) {
      return { ...TYPE_META['BRIDGE_OUT'], label };
    }
    return { ...TYPE_META['BRIDGE_IN'], label };
  }
  if (key.includes('INTERNAL_TRANSFER')) {
    return { ...TYPE_META['INTERNAL_TRANSFER'], label };
  }
  if (key.includes('EXTERNAL_TRANSFER_OUT')) {
    return { ...TYPE_META['EXTERNAL_TRANSFER_OUT'], label };
  }
  if (key.includes('EXTERNAL_TRANSFER_IN')) {
    return { ...TYPE_META['EXTERNAL_TRANSFER_IN'], label };
  }
  if (key.includes('TRANSFER')) {
    return { ...TYPE_META['INTERNAL_TRANSFER'], label };
  }
  if (key.includes('REWARD') || key.includes('CLAIM')) {
    return { ...TYPE_META['REWARD_CLAIM'], label };
  }
  if (key.includes('VAULT')) {
    return { ...TYPE_META['VAULT_DEPOSIT'], label };
  }
  if (key.includes('LP')) {
    if (key.includes('EXIT') || key.includes('REMOVE')) {
      return { ...TYPE_META['LP_EXIT'], label };
    }
    return { ...TYPE_META['LP_ENTRY'], label };
  }
  if (key.includes('WRAP')) {
    if (key.includes('UN')) {
      return { ...TYPE_META['UNWRAP'], label };
    }
    return { ...TYPE_META['WRAP'], label };
  }
  if (key.includes('FEE') || key.includes('GAS')) {
    return {
      label,
      glyph: '•',
      color: '#fbbf24',
      icon: FALLBACK_ICON,
    };
  }
  return {
    label,
    glyph: '•',
    color: '#3b82f6',
    icon: FALLBACK_ICON,
  };
}

function prettifyTypeLabel(typeKey: string): string {
  return typeKey
    .trim()
    .split('_')
    .filter((part) => part.length > 0)
    .map((part) => {
      const key = part.toUpperCase();
      switch (key) {
        case 'LP':
          return 'LP';
        case 'DEX':
          return 'DEX';
        case 'PNL':
          return 'PnL';
        default:
          return `${key.slice(0, 1)}${key.slice(1).toLowerCase()}`;
      }
    })
    .join(' ');
}

const TYPE_META: Readonly<Record<string, TypeVisualMeta>> = {
  SWAP: {
    label: 'Swap',
    glyph: '⇄',
    color: '#a78bfa',
    icon: (ctx, cx, cy, r) => {
      const a = r * 0.55;
      ctx.beginPath();
      ctx.moveTo(cx - a, cy - a * 0.5);
      ctx.lineTo(cx + a, cy - a * 0.5);
      ctx.moveTo(cx + a * 0.3, cy - a * 0.9);
      ctx.lineTo(cx + a, cy - a * 0.5);
      ctx.lineTo(cx + a * 0.3, cy - a * 0.1);
      ctx.moveTo(cx + a, cy + a * 0.5);
      ctx.lineTo(cx - a, cy + a * 0.5);
      ctx.moveTo(cx - a * 0.3, cy + a * 0.9);
      ctx.lineTo(cx - a, cy + a * 0.5);
      ctx.lineTo(cx - a * 0.3, cy + a * 0.1);
      ctx.stroke();
    },
  },
  LENDING_DEPOSIT: {
    label: 'Lending deposit',
    glyph: '⊕',
    color: '#34d399',
    icon: (ctx, cx, cy, r) => {
      const a = r * 0.5;
      ctx.beginPath();
      ctx.roundRect(cx - a * 1.1, cy - a * 0.7, a * 2.2, a * 1.4, 2);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(cx, cy - a * 0.9);
      ctx.lineTo(cx, cy + a * 0.9);
      ctx.moveTo(cx - a * 0.4, cy - a * 0.4);
      ctx.lineTo(cx, cy - a * 0.9);
      ctx.lineTo(cx + a * 0.4, cy - a * 0.4);
      ctx.stroke();
    },
  },
  LENDING_WITHDRAW: {
    label: 'Lending withdraw',
    glyph: '⊖',
    color: '#34d399',
    icon: (ctx, cx, cy, r) => {
      const a = r * 0.5;
      ctx.beginPath();
      ctx.roundRect(cx - a * 1.1, cy - a * 0.7, a * 2.2, a * 1.4, 2);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(cx, cy - a * 0.9);
      ctx.lineTo(cx, cy + a * 0.9);
      ctx.moveTo(cx - a * 0.4, cy + a * 0.4);
      ctx.lineTo(cx, cy + a * 0.9);
      ctx.lineTo(cx + a * 0.4, cy + a * 0.4);
      ctx.stroke();
    },
  },
  BORROW: {
    label: 'Borrow',
    glyph: '⟰',
    color: '#fb923c',
    icon: (ctx, cx, cy, r) => {
      const a = r * 0.55;
      ctx.beginPath();
      ctx.arc(cx, cy - a * 0.2, a * 0.5, Math.PI * 0.15, Math.PI * 0.85, false);
      ctx.arc(cx, cy + a * 0.2, a * 0.5, Math.PI * 1.15, Math.PI * 1.85, false);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(cx, cy - a * 0.9);
      ctx.lineTo(cx, cy + a * 0.9);
      ctx.stroke();
    },
  },
  REPAY: {
    label: 'Repay',
    glyph: '⟱',
    color: '#fb923c',
    icon: (ctx, cx, cy, r) => {
      const a = r * 0.5;
      ctx.beginPath();
      ctx.arc(cx, cy, a, 0, Math.PI * 1.7, false);
      ctx.stroke();
      ctx.beginPath();
      const ex = cx + a * Math.cos(Math.PI * 1.7);
      const ey = cy + a * Math.sin(Math.PI * 1.7);
      ctx.moveTo(ex - a * 0.3, ey - a * 0.3);
      ctx.lineTo(ex, ey);
      ctx.lineTo(ex + a * 0.3, ey - a * 0.2);
      ctx.stroke();
    },
  },
  STAKING_DEPOSIT: {
    label: 'Stake deposit',
    glyph: '⬒',
    color: '#fbbf24',
    icon: (ctx, cx, cy, r) => {
      const a = r * 0.52;
      ctx.beginPath();
      ctx.moveTo(cx, cy - a);
      ctx.lineTo(cx + a * 0.8, cy - a * 0.4);
      ctx.lineTo(cx + a * 0.8, cy + a * 0.2);
      ctx.quadraticCurveTo(cx + a * 0.8, cy + a, cx, cy + a);
      ctx.quadraticCurveTo(cx - a * 0.8, cy + a, cx - a * 0.8, cy + a * 0.2);
      ctx.lineTo(cx - a * 0.8, cy - a * 0.4);
      ctx.closePath();
      ctx.stroke();
    },
  },
  STAKING_WITHDRAW: {
    label: 'Unstake',
    glyph: '⬓',
    color: '#fbbf24',
    icon: (ctx, cx, cy, r) => {
      const a = r * 0.52;
      ctx.beginPath();
      ctx.moveTo(cx, cy - a);
      ctx.lineTo(cx + a * 0.8, cy - a * 0.4);
      ctx.lineTo(cx + a * 0.8, cy + a * 0.2);
      ctx.quadraticCurveTo(cx + a * 0.8, cy + a, cx, cy + a);
      ctx.quadraticCurveTo(cx - a * 0.8, cy + a, cx - a * 0.8, cy + a * 0.2);
      ctx.lineTo(cx - a * 0.8, cy - a * 0.4);
      ctx.closePath();
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(cx - a * 0.2, cy - a * 0.1);
      ctx.lineTo(cx + a * 0.2, cy - a * 0.1);
      ctx.stroke();
    },
  },
  LP_ENTRY: {
    label: 'LP add',
    glyph: '◌',
    color: '#818cf8',
    icon: (ctx, cx, cy, r) => {
      const a = r * 0.52;
      ctx.beginPath();
      ctx.arc(cx - a * 0.3, cy, a * 0.5, 0, Math.PI * 2);
      ctx.stroke();
      ctx.beginPath();
      ctx.arc(cx + a * 0.3, cy, a * 0.5, 0, Math.PI * 2);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(cx, cy - a * 0.9);
      ctx.lineTo(cx, cy - a * 0.55);
      ctx.moveTo(cx - a * 0.18, cy - a * 0.73);
      ctx.lineTo(cx + a * 0.18, cy - a * 0.73);
      ctx.stroke();
    },
  },
  LP_EXIT: {
    label: 'LP remove',
    glyph: '◍',
    color: '#818cf8',
    icon: (ctx, cx, cy, r) => {
      const a = r * 0.52;
      ctx.beginPath();
      ctx.arc(cx - a * 0.3, cy, a * 0.5, 0, Math.PI * 2);
      ctx.stroke();
      ctx.beginPath();
      ctx.arc(cx + a * 0.3, cy, a * 0.5, 0, Math.PI * 2);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(cx - a * 0.2, cy - a * 0.73);
      ctx.lineTo(cx + a * 0.2, cy - a * 0.73);
      ctx.stroke();
    },
  },
  BRIDGE_OUT: {
    label: 'Bridge out',
    glyph: '⇢',
    color: '#06b6d4',
    icon: (ctx, cx, cy, r) => {
      const a = r * 0.52;
      ctx.beginPath();
      ctx.moveTo(cx - a, cy + a * 0.2);
      ctx.quadraticCurveTo(cx, cy - a, cx + a, cy + a * 0.2);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(cx + a * 0.2, cy + a * 0.2);
      ctx.lineTo(cx + a * 0.8, cy + a * 0.2);
      ctx.moveTo(cx + a * 0.5, cy - a * 0.1);
      ctx.lineTo(cx + a * 0.8, cy + a * 0.2);
      ctx.lineTo(cx + a * 0.5, cy + a * 0.5);
      ctx.stroke();
    },
  },
  BRIDGE_IN: {
    label: 'Bridge in',
    glyph: '⇠',
    color: '#06b6d4',
    icon: (ctx, cx, cy, r) => {
      const a = r * 0.52;
      ctx.beginPath();
      ctx.moveTo(cx - a, cy + a * 0.2);
      ctx.quadraticCurveTo(cx, cy - a, cx + a, cy + a * 0.2);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(cx - a * 0.2, cy + a * 0.2);
      ctx.lineTo(cx - a * 0.8, cy + a * 0.2);
      ctx.moveTo(cx - a * 0.5, cy - a * 0.1);
      ctx.lineTo(cx - a * 0.8, cy + a * 0.2);
      ctx.lineTo(cx - a * 0.5, cy + a * 0.5);
      ctx.stroke();
    },
  },
  INTERNAL_TRANSFER: {
    label: 'Internal transfer',
    glyph: '↔',
    color: '#3b82f6',
    icon: (ctx, cx, cy, r) => {
      const a = r * 0.5;
      ctx.beginPath();
      ctx.roundRect(cx - a * 1.1, cy - a * 0.8, a * 0.8, a * 0.65, 1.5);
      ctx.stroke();
      ctx.beginPath();
      ctx.roundRect(cx + a * 0.3, cy - a * 0.8, a * 0.8, a * 0.65, 1.5);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(cx - a * 0.3, cy + a * 0.4);
      ctx.lineTo(cx + a * 0.3, cy + a * 0.4);
      ctx.moveTo(cx + a * 0.05, cy + a * 0.1);
      ctx.lineTo(cx + a * 0.3, cy + a * 0.4);
      ctx.lineTo(cx + a * 0.05, cy + a * 0.7);
      ctx.stroke();
    },
  },
  EXTERNAL_TRANSFER_OUT: {
    label: 'External send',
    glyph: '↑',
    color: '#f97316',
    icon: (ctx, cx, cy, r) => {
      const a = r * 0.52;
      ctx.beginPath();
      ctx.moveTo(cx - a, cy + a * 0.3);
      ctx.lineTo(cx - a, cy - a * 0.5);
      ctx.lineTo(cx + a * 0.1, cy - a * 0.5);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(cx, cy - a);
      ctx.lineTo(cx + a, cy - a);
      ctx.lineTo(cx + a, cy + a);
      ctx.moveTo(cx + a * 0.5, cy - a * 0.6);
      ctx.lineTo(cx + a, cy - a);
      ctx.lineTo(cx + a * 0.5, cy - a * 0.4);
      ctx.stroke();
    },
  },
  EXTERNAL_TRANSFER_IN: {
    label: 'External receive',
    glyph: '↓',
    color: '#4ade80',
    icon: (ctx, cx, cy, r) => {
      const a = r * 0.52;
      ctx.beginPath();
      ctx.moveTo(cx - a, cy + a * 0.4);
      ctx.lineTo(cx - a, cy - a * 0.4);
      ctx.lineTo(cx - a * 0.3, cy - a * 0.4);
      ctx.moveTo(cx + a * 0.3, cy - a * 0.4);
      ctx.lineTo(cx + a, cy - a * 0.4);
      ctx.lineTo(cx + a, cy + a * 0.4);
      ctx.lineTo(cx - a, cy + a * 0.4);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(cx, cy - a);
      ctx.lineTo(cx, cy + a * 0.1);
      ctx.moveTo(cx - a * 0.3, cy - a * 0.2);
      ctx.lineTo(cx, cy + a * 0.1);
      ctx.lineTo(cx + a * 0.3, cy - a * 0.2);
      ctx.stroke();
    },
  },
  WRAP: {
    label: 'Wrap',
    glyph: '◎',
    color: '#94a3b8',
    icon: (ctx, cx, cy, r) => {
      const a = r * 0.5;
      ctx.beginPath();
      ctx.arc(cx, cy, a, 0, Math.PI * 2);
      ctx.stroke();
      ctx.beginPath();
      ctx.arc(cx, cy, a * 0.5, 0, Math.PI * 2);
      ctx.stroke();
    },
  },
  UNWRAP: {
    label: 'Unwrap',
    glyph: '◉',
    color: '#94a3b8',
    icon: (ctx, cx, cy, r) => {
      const a = r * 0.5;
      ctx.beginPath();
      ctx.arc(cx, cy, a, 0, Math.PI * 2);
      ctx.stroke();
      ctx.beginPath();
      ctx.arc(cx, cy, a * 0.3, 0, Math.PI * 2);
      ctx.fill();
    },
  },
  REWARD_CLAIM: {
    label: 'Reward claim',
    glyph: '✦',
    color: '#f472b6',
    icon: (ctx, cx, cy, r) => {
      const a = r * 0.5;
      ctx.beginPath();
      for (let i = 0; i < 5; i += 1) {
        const a1 = (Math.PI * 2 * i) / 5 - Math.PI / 2;
        const a2 = (Math.PI * 2 * i) / 5 + Math.PI / 5 - Math.PI / 2;
        if (i === 0) {
          ctx.moveTo(cx + Math.cos(a1) * a, cy + Math.sin(a1) * a);
        } else {
          ctx.lineTo(cx + Math.cos(a1) * a, cy + Math.sin(a1) * a);
        }
        ctx.lineTo(cx + Math.cos(a2) * a * 0.42, cy + Math.sin(a2) * a * 0.42);
      }
      ctx.closePath();
      ctx.stroke();
    },
  },
  VAULT_DEPOSIT: {
    label: 'Vault deposit',
    glyph: '◫',
    color: '#c084fc',
    icon: (ctx, cx, cy, r) => {
      const a = r * 0.5;
      ctx.beginPath();
      ctx.ellipse(cx, cy - a * 0.3, a * 0.9, a * 0.4, 0, 0, Math.PI * 2);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(cx - a * 0.9, cy - a * 0.3);
      ctx.lineTo(cx - a * 0.9, cy + a * 0.4);
      ctx.quadraticCurveTo(cx - a * 0.9, cy + a * 0.8, cx, cy + a * 0.8);
      ctx.quadraticCurveTo(cx + a * 0.9, cy + a * 0.8, cx + a * 0.9, cy + a * 0.4);
      ctx.lineTo(cx + a * 0.9, cy - a * 0.3);
      ctx.stroke();
    },
  },
  PROTOCOL_CUSTODY_DEPOSIT: {
    label: 'Protocol custody in',
    glyph: '⌘',
    color: '#f472b6',
    icon: (ctx, cx, cy, r) => {
      const a = r * 0.48;
      ctx.beginPath();
      ctx.roundRect(cx - a * 0.8, cy - a * 0.1, a * 1.6, a * 1.1, 3);
      ctx.stroke();
      ctx.beginPath();
      ctx.arc(cx, cy - a * 0.1, a * 0.5, Math.PI, 0, false);
      ctx.stroke();
      ctx.beginPath();
      ctx.arc(cx, cy + a * 0.45, a * 0.18, 0, Math.PI * 2);
      ctx.fill();
    },
  },
  OTHER: {
    label: 'Other',
    glyph: '•',
    color: '#3b82f6',
    icon: FALLBACK_ICON,
  },
};

const TYPE_DISPLAY_OVERRIDES: Readonly<Record<string, TypeDisplayOverride>> = {
  STAKING_WITHDRAW_REQUEST: { label: 'Unstake request', baseType: 'STAKING_WITHDRAW' },
  LP_ENTRY_REQUEST: { label: 'LP add request', baseType: 'LP_ENTRY' },
  LP_ENTRY_SETTLEMENT: { label: 'LP add settlement', baseType: 'LP_ENTRY' },
  LP_EXIT_REQUEST: { label: 'LP remove request', baseType: 'LP_EXIT' },
  LP_EXIT_SETTLEMENT: { label: 'LP remove settlement', baseType: 'LP_EXIT' },
  LP_EXIT_PARTIAL: { label: 'LP remove partial', baseType: 'LP_EXIT' },
  LP_EXIT_FINAL: { label: 'LP remove final', baseType: 'LP_EXIT' },
  LP_ADJUST: { label: 'LP adjust', baseType: 'LP_ENTRY' },
  LP_POSITION_STAKE: { label: 'LP stake', baseType: 'LP_ENTRY' },
  LP_POSITION_UNSTAKE: { label: 'LP unstake', baseType: 'LP_EXIT' },
  LP_FEE_CLAIM: { label: 'LP fee claim', baseType: 'REWARD_CLAIM' },
  LENDING_LOOP_OPEN: { label: 'Loop open', baseType: 'LENDING_DEPOSIT' },
  LENDING_LOOP_REBALANCE: { label: 'Loop rebalance', baseType: 'LENDING_DEPOSIT' },
  LENDING_LOOP_DECREASE: { label: 'Loop decrease', baseType: 'LENDING_WITHDRAW' },
  LENDING_LOOP_CLOSE: { label: 'Loop close', baseType: 'LENDING_WITHDRAW' },
  VAULT_WITHDRAW: { label: 'Vault withdraw', baseType: 'VAULT_DEPOSIT' },
  PROTOCOL_CUSTODY_WITHDRAW: { label: 'Protocol custody out', baseType: 'PROTOCOL_CUSTODY_DEPOSIT' },
  DEX_ORDER_REQUEST: { label: 'DEX order request', baseType: 'SWAP' },
  DEX_ORDER_SETTLEMENT: { label: 'DEX order settlement', baseType: 'SWAP' },
  DERIVATIVE_ORDER_REQUEST: { label: 'Derivative order request', baseType: 'SWAP' },
  DERIVATIVE_ORDER_EXECUTION: { label: 'Derivative execution', baseType: 'SWAP' },
  DERIVATIVE_ORDER_CANCEL: { label: 'Derivative cancel', baseType: 'SWAP' },
  DERIVATIVE_POSITION_INCREASE: { label: 'Position increase', baseType: 'SWAP' },
  DERIVATIVE_POSITION_DECREASE: { label: 'Position decrease', baseType: 'SWAP' },
  APPROVE: { label: 'Approve', baseType: 'OTHER' },
  ADMIN_CONFIG: { label: 'Admin config', baseType: 'OTHER' },
  MANUAL_COMPENSATING: { label: 'Manual compensating', baseType: 'OTHER' },
  UNKNOWN: { label: 'Unknown', baseType: 'OTHER' },
};

const EVENT_FAMILY_META: Readonly<Record<EventFamilyKey, { label: string; color: string }>> = {
  lp: { label: 'LP', color: '#818cf8' },
  bridge: { label: 'Bridge', color: '#06b6d4' },
  transfer: { label: 'Transfer', color: '#3b82f6' },
  lending: { label: 'Lending', color: '#34d399' },
  reward: { label: 'Reward', color: '#f472b6' },
  staking: { label: 'Staking', color: '#fbbf24' },
};

@Component({
  selector: 'wr-asset-ledger-page',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './asset-ledger-page.component.html',
  styleUrl: './asset-ledger-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AssetLedgerPageComponent {
  private readonly walletApiService = inject(WalletApiService);

  readonly sessionId = input<string | null>(null);
  readonly familyIdentity = input<string | null>(null);
  readonly back = output<void>();

  readonly hoveredMarkerId = signal<string | null>(null);
  readonly pinnedMarkerId = signal<string | null>(null);
  readonly showTooltip = signal(false);
  readonly tooltipPosition = signal({ left: 0, top: 0 });
  readonly rangeStartIndex = signal<number>(0);
  readonly rangeEndIndex = signal<number>(0);
  readonly isRangeDragging = signal(false);
  readonly disabledTypeKeys = signal<ReadonlySet<string>>(new Set(DEFAULT_DISABLED_TYPE_KEYS));
  readonly selectedBasisEffects = signal<ReadonlySet<string>>(new Set<string>());
  readonly selectedPreset = signal<QuickPresetKey>('economics');
  readonly copiedTxHash = signal<string | null>(null);
  readonly collapsedSections = signal<ReadonlySet<string>>(new Set<string>());
  readonly eventLogSearch = signal('');

  @ViewChild('chartCanvas') private chartCanvasRef?: ElementRef<HTMLCanvasElement>;
  @ViewChild('qtyChartCanvas') private qtyChartCanvasRef?: ElementRef<HTMLCanvasElement>;
  @ViewChild('pnlChartCanvas') private pnlChartCanvasRef?: ElementRef<HTMLCanvasElement>;
  @ViewChild('rangePreviewCanvas') private rangePreviewCanvasRef?: ElementRef<HTMLCanvasElement>;
  @ViewChild('rangeShell') private rangeShellRef?: ElementRef<HTMLDivElement>;
  @ViewChildren('legendCanvas') private legendCanvasRefs?: QueryList<ElementRef<HTMLCanvasElement>>;

  private resizeObserver?: ResizeObserver;
  private renderedMarkers: ReadonlyArray<RenderedMarkerView> = [];
  private quantityRenderedMarkers: ReadonlyArray<RenderedPointView> = [];
  private pnlRenderedMarkers: ReadonlyArray<RenderedPnlMarkerView> = [];
  private copyResetTimerId: number | null = null;
  private rangeDragState: {
    mode: RangeDragMode;
    startClientX: number;
    startIndex: number;
    endIndex: number;
  } | null = null;

  readonly viewState = toSignal(
    combineLatest({
      sessionId: toObservable(this.sessionId),
      familyIdentity: toObservable(this.familyIdentity),
    }).pipe(
      map((params) => ({
        sessionId: params.sessionId?.trim() ?? '',
        familyIdentity: params.familyIdentity?.trim() ?? '',
      })),
      switchMap(({ sessionId, familyIdentity }) => {
        if (sessionId.length === 0 || familyIdentity.length === 0) {
          return of<PageState>({ status: 'error', message: 'Session or asset family is missing.' });
        }
        return combineLatest({
          session: this.walletApiService.getSession(sessionId),
          ledger: this.walletApiService.getSessionAssetLedger(sessionId, familyIdentity),
        }).pipe(
          map(({ session, ledger }): PageState => ({
            status: 'success',
            data: this.toViewModel(session, ledger),
          })),
          startWith<PageState>({ status: 'loading' }),
          catchError((error: HttpErrorResponse) =>
            of<PageState>({ status: 'error', message: this.toErrorMessage(error) })
          )
        );
      })
    ),
    { initialValue: { status: 'loading' } }
  );

  readonly assetData = computed(() => {
    const state = this.viewState();
    return state.status === 'success' ? state.data : null;
  });

  readonly totalMarkerCount = computed(() => {
    const data = this.assetData();
    return data?.markers.length ?? 0;
  });

  readonly basisFilterOptions = computed(() => {
    const data = this.assetData();
    if (data === null) {
      return [] as ReadonlyArray<BasisFilterView>;
    }
    const keys = new Set<string>();
    data.markers.forEach((marker) => {
      const basisEffects = marker.basisEffects.length > 0 ? marker.basisEffects : ['NONE'];
      basisEffects.forEach((basisEffect) => keys.add(basisEffect));
    });
    return [...keys].sort().map((key) => ({
      key,
      label: this.formatBasisEffectLabel(key),
      color: this.basisEffectColor(key),
    }));
  });

  readonly eventFamilyFilters = computed(() => {
    const data = this.assetData();
    if (data === null) {
      return [] as ReadonlyArray<EventFamilyFilterView>;
    }

    const families: EventFamilyFilterView[] = [];
    (Object.keys(EVENT_FAMILY_META) as EventFamilyKey[]).forEach((familyKey) => {
      const typeKeys = [...new Set(
          data.legendItems
            .map((item) => item.typeKey)
            .filter((typeKey) => this.eventFamilyForType(typeKey) === familyKey)
      )];
      const eventCount = data.markers.filter((marker) => this.eventFamilyForType(marker.typeKey) === familyKey).length;
      if (typeKeys.length > 0) {
        families.push({
          key: familyKey,
          label: EVENT_FAMILY_META[familyKey].label,
          color: EVENT_FAMILY_META[familyKey].color,
          typeKeys,
          eventCount,
        });
      }
    });
    return families;
  });

  readonly quickPresets: ReadonlyArray<QuickPresetView> = [
    { key: 'economics', label: 'Economics only' },
    { key: 'all', label: 'All events' },
    { key: 'transfers', label: 'Transfers only' },
    { key: 'basisMoves', label: 'Basis moves' },
  ];

  readonly selectedTypeCount = computed(() => {
    const data = this.assetData();
    if (data === null) {
      return 0;
    }
    return data.legendItems.filter((item) => this.isTypeSelected(item.typeKey)).length;
  });

  readonly selectedBasisCount = computed(() => this.basisFilterOptions().filter((item) => this.isBasisEffectSelected(item.key)).length);

  readonly maxMarkerIndex = computed(() => Math.max(0, this.totalMarkerCount() - 1));

  readonly selectedWindowSize = computed(() => {
    const total = this.totalMarkerCount();
    if (total === 0) {
      return 0;
    }
    return this.rangeEndIndex() - this.rangeStartIndex() + 1;
  });

  readonly windowMarkers = computed(() => {
    const data = this.assetData();
    if (data === null) {
      return [] as ReadonlyArray<MarkerView>;
    }
    if (data.markers.length === 0) {
      return [] as ReadonlyArray<MarkerView>;
    }
    const start = Math.max(0, Math.min(this.rangeStartIndex(), data.markers.length - 1));
    const end = Math.max(start, Math.min(this.rangeEndIndex(), data.markers.length - 1));
    return data.markers.slice(start, end + 1);
  });

  readonly visibleMarkers = computed(() => {
    const disabledTypeKeys = this.disabledTypeKeys();
    const selectedBasisEffects = this.selectedBasisEffects();
    return this.windowMarkers().filter((marker) => {
      if (disabledTypeKeys.has(marker.typeKey)) {
        return false;
      }
      const markerBasisEffects = marker.basisEffects.length > 0 ? marker.basisEffects : ['NONE'];
      return markerBasisEffects.some((basisEffect) => selectedBasisEffects.has(basisEffect));
    });
  });

  readonly visibleWindowLabel = computed(() => {
    const markers = this.windowMarkers();
    if (markers.length === 0) {
      return 'No events';
    }
    const from = this.formatShortDate(markers[0].timestamp);
    const to = this.formatShortDate(markers.at(-1)?.timestamp ?? markers[0].timestamp);
    return from === to ? from : `${from} – ${to}`;
  });

  readonly visibleWindowStartLabel = computed(() => {
    const markers = this.windowMarkers();
    if (markers.length === 0) {
      return '—';
    }
    return this.formatShortDate(markers[0].timestamp);
  });

  readonly visibleWindowEndLabel = computed(() => {
    const markers = this.windowMarkers();
    if (markers.length === 0) {
      return '—';
    }
    return this.formatShortDate(markers.at(-1)?.timestamp ?? markers[0].timestamp);
  });

  readonly rangeStartPercent = computed(() => {
    const maxIndex = this.maxMarkerIndex();
    if (maxIndex <= 0) {
      return 0;
    }
    return (this.rangeStartIndex() / maxIndex) * 100;
  });

  readonly rangeEndPercent = computed(() => {
    const maxIndex = this.maxMarkerIndex();
    if (maxIndex <= 0) {
      return 100;
    }
    return (this.rangeEndIndex() / maxIndex) * 100;
  });

  readonly errorMessage = computed(() => {
    const state = this.viewState();
    return state.status === 'error' ? state.message : null;
  });

  readonly tooltipMarker = computed(() => {
    if (!this.showTooltip()) {
      return null;
    }
    const activeId = this.pinnedMarkerId() ?? this.hoveredMarkerId();
    const data = this.assetData();
    if (activeId === null || data === null) {
      return null;
    }
    return data.markerLookup[activeId] ?? null;
  });

  readonly realisedPnlMarkers = computed(() =>
    this.visibleMarkers().filter((marker) => Math.abs(marker.realisedPnlDeltaUsd ?? 0) > 0.0000001)
  );

  readonly eventLogBaseMarkers = computed(() => [...this.visibleMarkers()].reverse());

  readonly eventLogMarkers = computed(() => {
    const query = this.normalizeSearchQuery(this.eventLogSearch());
    const markers = this.eventLogBaseMarkers();
    if (query.length === 0) {
      return markers;
    }
    return markers.filter((marker) => this.markerSearchHaystack(marker).includes(query));
  });

  readonly eventLogCountLabel = computed(() => {
    const visibleCount = this.eventLogMarkers().length;
    const filteredCount = this.eventLogBaseMarkers().length;
    if (filteredCount === 0) {
      return 'No events in range';
    }
    if (visibleCount === filteredCount) {
      return `${visibleCount} events`;
    }
    return `${visibleCount} of ${filteredCount} events`;
  });

  setHoveredMarker(markerId: string | null): void {
    this.hoveredMarkerId.set(markerId);
    this.showTooltip.set(false);
    this.renderChart();
  }

  toggleAllTypes(): void {
    const data = this.assetData();
    if (data === null) {
      return;
    }
    const allTypeKeys = new Set(data.legendItems.map((item) => item.typeKey));
    const hasVisibleType = data.legendItems.some((item) => this.isTypeSelected(item.typeKey));
    this.disabledTypeKeys.set(hasVisibleType ? allTypeKeys : new Set<string>());
    this.selectedPreset.set('all');
  }

  toggleTypeVisibility(typeKey: string): void {
    const next = new Set(this.disabledTypeKeys());
    if (next.has(typeKey)) {
      next.delete(typeKey);
    } else {
      next.add(typeKey);
    }
    this.disabledTypeKeys.set(next);
    this.selectedPreset.set('all');
  }

  toggleEventFamily(familyKey: EventFamilyKey): void {
    const family = this.eventFamilyFilters().find((item) => item.key === familyKey);
    if (family === undefined) {
      return;
    }
    const next = new Set(this.disabledTypeKeys());
    const allVisible = family.typeKeys.every((typeKey) => !next.has(typeKey));
    family.typeKeys.forEach((typeKey) => {
      if (allVisible) {
        next.add(typeKey);
      } else {
        next.delete(typeKey);
      }
    });
    this.disabledTypeKeys.set(next);
    this.selectedPreset.set('all');
  }

  updateEventLogSearch(value: string): void {
    this.eventLogSearch.set(value);
  }

  isTypeVisible(typeKey: string): boolean {
    return !this.disabledTypeKeys().has(typeKey);
  }

  isTypeSelected(typeKey: string): boolean {
    return !this.disabledTypeKeys().has(typeKey);
  }

  isEventFamilySelected(familyKey: EventFamilyKey): boolean {
    const family = this.eventFamilyFilters().find((item) => item.key === familyKey);
    return family === undefined ? false : family.typeKeys.every((typeKey) => this.isTypeSelected(typeKey));
  }

  isEventFamilyMixed(familyKey: EventFamilyKey): boolean {
    const family = this.eventFamilyFilters().find((item) => item.key === familyKey);
    if (family === undefined) {
      return false;
    }
    const visibleCount = family.typeKeys.filter((typeKey) => this.isTypeSelected(typeKey)).length;
    return visibleCount > 0 && visibleCount < family.typeKeys.length;
  }

  toggleBasisEffect(key: string): void {
    const next = new Set(this.selectedBasisEffects());
    if (next.has(key)) {
      next.delete(key);
    } else {
      next.add(key);
    }
    this.selectedBasisEffects.set(next);
    this.selectedPreset.set('all');
  }

  isBasisEffectSelected(key: string): boolean {
    return this.selectedBasisEffects().has(key);
  }

  applyQuickPreset(preset: QuickPresetKey): void {
    const data = this.assetData();
    if (data === null) {
      return;
    }
    const allTypeKeys = new Set(data.legendItems.map((item) => item.typeKey));
    const allBasisKeys = new Set(this.basisFilterOptions().map((item) => item.key));

    switch (preset) {
      case 'economics':
        this.disabledTypeKeys.set(new Set([...allTypeKeys].filter((key) => DEFAULT_DISABLED_TYPE_KEYS.has(key))));
        this.selectedBasisEffects.set(new Set([...allBasisKeys].filter((key) => !DEFAULT_HIDDEN_BASIS_EFFECTS.has(key))));
        break;
      case 'all':
        this.disabledTypeKeys.set(new Set<string>());
        this.selectedBasisEffects.set(allBasisKeys);
        break;
      case 'transfers':
        this.disabledTypeKeys.set(new Set([...allTypeKeys].filter((key) => !TRANSFER_TYPE_KEYS.has(key))));
        this.selectedBasisEffects.set(new Set([...allBasisKeys].filter((key) => !DEFAULT_HIDDEN_BASIS_EFFECTS.has(key))));
        break;
      case 'basisMoves':
        this.disabledTypeKeys.set(new Set(DEFAULT_DISABLED_TYPE_KEYS));
        this.selectedBasisEffects.set(new Set([...allBasisKeys].filter((key) => BASIS_MOVE_EFFECTS.has(key))));
        break;
    }

    this.selectedPreset.set(preset);
    this.hoveredMarkerId.set(null);
    this.pinnedMarkerId.set(null);
    this.showTooltip.set(false);
  }

  onRangeStartInput(value: string): void {
    const parsed = Number(value);
    if (!Number.isFinite(parsed)) {
      return;
    }
    const maxStart = Math.max(0, this.rangeEndIndex() - 1);
    this.rangeStartIndex.set(Math.max(0, Math.min(Math.round(parsed), maxStart)));
  }

  onRangeEndInput(value: string): void {
    const parsed = Number(value);
    if (!Number.isFinite(parsed)) {
      return;
    }
    const minEnd = Math.min(this.maxMarkerIndex(), this.rangeStartIndex() + 1);
    this.rangeEndIndex.set(Math.max(minEnd, Math.min(Math.round(parsed), this.maxMarkerIndex())));
  }

  onChartWheel(event: WheelEvent): void {
    event.preventDefault();
  }

  onRangeSelectionPointerDown(event: MouseEvent, mode: RangeDragMode = 'move'): void {
    if (this.maxMarkerIndex() <= 0) {
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    this.rangeDragState = {
      mode,
      startClientX: event.clientX,
      startIndex: this.rangeStartIndex(),
      endIndex: this.rangeEndIndex(),
    };
    this.isRangeDragging.set(true);
  }

  formatUsd(value: number | null, digits = 2): string {
    if (value === null || Number.isNaN(value)) {
      return '—';
    }
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      maximumFractionDigits: digits,
    }).format(value);
  }

  formatSignedUsd(value: number | null, digits = 2): string {
    if (value === null || Number.isNaN(value)) {
      return '—';
    }
    const prefix = value > 0 ? '+' : '';
    return `${prefix}${this.formatUsd(value, digits)}`;
  }

  formatQuantity(value: number | null, digits = 6): string {
    if (value === null || Number.isNaN(value)) {
      return '—';
    }
    return new Intl.NumberFormat('en-US', {
      minimumFractionDigits: 0,
      maximumFractionDigits: digits,
    }).format(value);
  }

  formatSignedQuantity(value: number | null, digits = 6): string {
    if (value === null || Number.isNaN(value)) {
      return '—';
    }
    const prefix = value > 0 ? '+' : '';
    return `${prefix}${this.formatQuantity(value, digits)}`;
  }

  shortHash(value: string): string {
    if (value.length <= 16) {
      return value;
    }
    return `${value.slice(0, 10)}…${value.slice(-6)}`;
  }

  formatEventDateTime(value: string): string {
    if (value.length === 0) {
      return '—';
    }
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return value;
    }
    return new Intl.DateTimeFormat('en-US', {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    }).format(date);
  }

  isLogRowActive(markerId: string): boolean {
    return this.pinnedMarkerId() === markerId || this.hoveredMarkerId() === markerId;
  }

  isSectionCollapsed(sectionKey: string): boolean {
    return this.collapsedSections().has(sectionKey);
  }

  toggleSection(sectionKey: string): void {
    const next = new Set(this.collapsedSections());
    if (next.has(sectionKey)) {
      next.delete(sectionKey);
    } else {
      next.add(sectionKey);
    }
    this.collapsedSections.set(next);
  }

  selectMarkerFromLog(marker: MarkerView, event?: MouseEvent): void {
    event?.stopPropagation();
    this.pinnedMarkerId.set(marker.id);
    this.hoveredMarkerId.set(null);
    this.showTooltip.set(false);
    this.renderChart();
    this.renderSupplementalCharts();
  }

  onLogRowPointerEnter(marker: MarkerView): void {
    if (this.pinnedMarkerId() !== null) {
      return;
    }
    this.hoveredMarkerId.set(marker.id);
    this.showTooltip.set(false);
    this.renderChart();
    this.renderSupplementalCharts();
  }

  onLogRowPointerLeave(): void {
    if (this.pinnedMarkerId() !== null) {
      return;
    }
    this.hoveredMarkerId.set(null);
    this.showTooltip.set(false);
    this.renderChart();
    this.renderSupplementalCharts();
  }

  onChartPointerMove(event: MouseEvent): void {
    if (this.pinnedMarkerId() !== null) {
      return;
    }
    const canvas = this.chartCanvasRef?.nativeElement;
    if (canvas === undefined || this.renderedMarkers.length === 0) {
      return;
    }
    const rect = canvas.getBoundingClientRect();
    const x = event.clientX - rect.left;
    const y = event.clientY - rect.top;
    const bestMarker = this.findNearestRenderedMarker(x, y, 16);
    if (bestMarker !== null) {
      this.hoveredMarkerId.set(bestMarker.markerId);
      this.showTooltip.set(true);
      this.positionTooltip(event.clientX, event.clientY);
    }
    this.renderChart();
  }

  onQuantityChartPointerMove(event: MouseEvent): void {
    if (this.pinnedMarkerId() !== null) {
      return;
    }
    const markerId = this.findRenderedPointMarker(
      this.qtyChartCanvasRef?.nativeElement,
      this.quantityRenderedMarkers,
      event
    );
    if (markerId !== null) {
      this.hoveredMarkerId.set(markerId);
      this.showTooltip.set(true);
      this.positionTooltip(event.clientX, event.clientY);
    } else {
      this.hoveredMarkerId.set(null);
      this.showTooltip.set(false);
    }
    this.renderChart();
    this.renderSupplementalCharts();
  }

  onPnlChartPointerMove(event: MouseEvent): void {
    if (this.pinnedMarkerId() !== null) {
      return;
    }
    const markerId = this.findRenderedPnlMarker(this.pnlChartCanvasRef?.nativeElement, event);
    if (markerId !== null) {
      this.hoveredMarkerId.set(markerId);
      this.showTooltip.set(true);
      this.positionTooltip(event.clientX, event.clientY);
    } else {
      this.hoveredMarkerId.set(null);
      this.showTooltip.set(false);
    }
    this.renderChart();
    this.renderSupplementalCharts();
  }

  onChartPointerLeave(): void {
    if (this.pinnedMarkerId() !== null) {
      return;
    }
    this.hoveredMarkerId.set(null);
    this.showTooltip.set(false);
    this.renderChart();
  }

  onSupplementalChartPointerLeave(): void {
    if (this.pinnedMarkerId() !== null) {
      return;
    }
    this.hoveredMarkerId.set(null);
    this.showTooltip.set(false);
    this.renderChart();
    this.renderSupplementalCharts();
  }

  onChartClick(event: MouseEvent): void {
    const canvas = this.chartCanvasRef?.nativeElement;
    if (canvas === undefined || this.renderedMarkers.length === 0) {
      return;
    }
    const rect = canvas.getBoundingClientRect();
    const x = event.clientX - rect.left;
    const y = event.clientY - rect.top;
    const bestMarker = this.findNearestRenderedMarker(x, y, 18);
    if (bestMarker === null) {
      this.clearPinnedTooltip();
      return;
    }
    if (this.pinnedMarkerId() === bestMarker.markerId) {
      this.clearPinnedTooltip();
      return;
    }
    this.pinnedMarkerId.set(bestMarker.markerId);
    this.hoveredMarkerId.set(bestMarker.markerId);
    this.showTooltip.set(true);
    this.positionTooltip(event.clientX, event.clientY);
    this.renderChart();
  }

  onQuantityChartClick(event: MouseEvent): void {
    const markerId = this.findRenderedPointMarker(
      this.qtyChartCanvasRef?.nativeElement,
      this.quantityRenderedMarkers,
      event
    );
    this.togglePinnedMarker(markerId, event.clientX, event.clientY);
  }

  onPnlChartClick(event: MouseEvent): void {
    const markerId = this.findRenderedPnlMarker(this.pnlChartCanvasRef?.nativeElement, event);
    this.togglePinnedMarker(markerId, event.clientX, event.clientY);
  }

  async copyTxHash(txHash: string): Promise<void> {
    try {
      if ('clipboard' in navigator && navigator.clipboard !== undefined) {
        await navigator.clipboard.writeText(txHash);
      } else {
        const textarea = document.createElement('textarea');
        textarea.value = txHash;
        textarea.setAttribute('readonly', 'true');
        textarea.style.position = 'fixed';
        textarea.style.opacity = '0';
        document.body.appendChild(textarea);
        textarea.select();
        document.execCommand('copy');
        document.body.removeChild(textarea);
      }
      this.copiedTxHash.set(txHash);
      if (this.copyResetTimerId !== null) {
        window.clearTimeout(this.copyResetTimerId);
      }
      this.copyResetTimerId = window.setTimeout(() => {
        this.copiedTxHash.set(null);
        this.copyResetTimerId = null;
      }, 1400);
    } catch {
      this.copiedTxHash.set(null);
    }
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent): void {
    const target = event.target;
    if (!(target instanceof Element)) {
      return;
    }
    if (target.closest('#tip') !== null || target.closest('#chart') !== null || target.closest('#qty-chart') !== null || target.closest('#pnl-chart') !== null) {
      return;
    }
    if (this.pinnedMarkerId() !== null) {
      this.clearPinnedTooltip();
    }
  }

  @HostListener('document:mousemove', ['$event'])
  onDocumentMouseMove(event: MouseEvent): void {
    const dragState = this.rangeDragState;
    const rangeShell = this.rangeShellRef?.nativeElement;
    if (dragState === null || rangeShell === undefined) {
      return;
    }
    const width = rangeShell.getBoundingClientRect().width;
    if (width <= 0) {
      return;
    }
    const maxIndex = this.maxMarkerIndex();
    if (maxIndex <= 0) {
      return;
    }
    const deltaX = event.clientX - dragState.startClientX;
    const deltaIndex = Math.round((deltaX / width) * maxIndex);

    if (dragState.mode === 'move') {
      const rangeSpan = Math.max(1, dragState.endIndex - dragState.startIndex);
      const maxStart = Math.max(0, maxIndex - rangeSpan);
      const nextStart = Math.max(0, Math.min(dragState.startIndex + deltaIndex, maxStart));
      this.rangeStartIndex.set(nextStart);
      this.rangeEndIndex.set(Math.min(maxIndex, nextStart + rangeSpan));
      return;
    }

    if (dragState.mode === 'start') {
      const nextStart = Math.max(0, Math.min(dragState.startIndex + deltaIndex, dragState.endIndex - 1));
      this.rangeStartIndex.set(nextStart);
      return;
    }

    const nextEnd = Math.max(dragState.startIndex + 1, Math.min(dragState.endIndex + deltaIndex, maxIndex));
    this.rangeEndIndex.set(nextEnd);
  }

  @HostListener('document:mouseup')
  onDocumentMouseUp(): void {
    if (this.rangeDragState === null) {
      return;
    }
    this.rangeDragState = null;
    this.isRangeDragging.set(false);
  }

  constructor() {
    effect(() => {
      const data = this.assetData();
      if (data === null) {
        return;
      }
      const { startIndex: rangeStart, endIndex: rangeEnd } = this.defaultRangeForRecentWindow(data.markers);
      this.rangeStartIndex.set(rangeStart);
      this.rangeEndIndex.set(rangeEnd);
      const basisKeys = new Set<string>();
      data.markers.forEach((marker) => {
        const markerBasisEffects = marker.basisEffects.length > 0 ? marker.basisEffects : ['NONE'];
        markerBasisEffects.forEach((basisEffect) => basisKeys.add(basisEffect));
      });
      this.disabledTypeKeys.set(new Set(DEFAULT_DISABLED_TYPE_KEYS));
      this.selectedBasisEffects.set(new Set([...basisKeys].filter((key) => !DEFAULT_HIDDEN_BASIS_EFFECTS.has(key))));
      this.selectedPreset.set('economics');
      this.collapsedSections.set(new Set<string>());
      this.hoveredMarkerId.set(null);
      this.pinnedMarkerId.set(null);
      this.copiedTxHash.set(null);
    });
    effect(() => {
      const maxIndex = this.maxMarkerIndex();
      if (this.rangeEndIndex() > maxIndex) {
        this.rangeEndIndex.set(maxIndex);
      }
      if (this.rangeStartIndex() > maxIndex) {
        this.rangeStartIndex.set(Math.max(0, maxIndex - 1));
      }
      if (this.rangeStartIndex() >= this.rangeEndIndex() && maxIndex > 0) {
        this.rangeStartIndex.set(Math.max(0, this.rangeEndIndex() - 1));
      }
    });
    effect(() => {
      const data = this.assetData();
      if (data === null) {
        return;
      }
      queueMicrotask(() => {
        this.renderLegendIcons();
        this.renderChart();
        this.renderSupplementalCharts();
        this.renderRangePreview();
      });
    });
    effect(() => {
      this.hoveredMarkerId();
      this.pinnedMarkerId();
      this.rangeStartIndex();
      this.rangeEndIndex();
      this.disabledTypeKeys();
      this.selectedBasisEffects();
      this.collapsedSections();
      queueMicrotask(() => {
        this.renderChart();
        this.renderSupplementalCharts();
        this.renderRangePreview();
      });
    });
    effect(() => {
      const activeMarkerId = this.pinnedMarkerId() ?? this.hoveredMarkerId();
      if (activeMarkerId === null) {
        return;
      }
      const stillVisible = this.windowMarkers().some((marker) => marker.id === activeMarkerId);
      if (!stillVisible) {
      this.hoveredMarkerId.set(null);
      this.pinnedMarkerId.set(null);
      this.showTooltip.set(false);
    }
  });
  }

  private toViewModel(session: SessionResponse, ledger: SessionAssetLedgerResponse): AssetLedgerViewModel {
    const walletLabels = new Map(
      session.wallets.map((wallet) => [wallet.address.trim().toLowerCase(), wallet.label] as const)
    );
    const displaySymbol =
      ledger.ledgerPoints.find((point) => point.familyDisplaySymbol !== null)?.familyDisplaySymbol ??
      this.familyDisplaySymbol(ledger.familyIdentity);

    const legendItems = this.buildLegendItems(ledger.events);
    const markers = this.buildMarkers(ledger, walletLabels);
    const markerLookup = Object.fromEntries(markers.map((marker) => [marker.id, marker] as const));
    return {
      sessionId: ledger.sessionId,
      familyIdentity: ledger.familyIdentity,
      displaySymbol,
      subtitle: `${session.wallets[0]?.address.slice(0, 10).toLowerCase() ?? 'session'} · all wallets · all networks · AVCO replay`,
      current: {
        quantity: ledger.current.quantity ?? 0,
        coveredQuantity: ledger.current.coveredQuantity ?? 0,
        uncoveredQuantity: ledger.current.uncoveredQuantity ?? 0,
        totalCostBasisUsd: ledger.current.totalCostBasisUsd,
        avcoUsd: ledger.current.avcoUsd,
        realisedPnlUsd: ledger.current.realisedPnlUsd ?? 0,
        gasPaidUsd: ledger.current.gasPaidUsd ?? 0,
      },
      legendItems,
      markers,
      markerLookup,
    };
  }

  private buildLegendItems(events: ReadonlyArray<SessionAssetLedgerEventOverlayResponse>): ReadonlyArray<LegendItemView> {
    const ordered = new Map<string, LegendItemView>();
    events.forEach((event) => {
      const typeKey = this.normalizeTypeKey(event.normalizedType);
      const meta = this.metaForType(typeKey);
      ordered.set(typeKey, {
        key: typeKey,
        typeKey,
        label: meta.label,
        glyph: meta.glyph,
        color: meta.color,
      });
    });
    return [...ordered.values()];
  }

  private buildMarkers(
    ledger: SessionAssetLedgerResponse,
    walletLabels: ReadonlyMap<string, string>
  ): ReadonlyArray<MarkerView> {
    const eventById = new Map(
      ledger.events.map((event) => [event.normalizedTransactionId ?? event.txHash ?? crypto.randomUUID(), event] as const)
    );
    const yProjection = this.buildYProjection(ledger.timeline, ledger.events, ledger.familyIdentity);

    return ledger.timeline.map((entry, index, entries) => {
      const id = entry.normalizedTransactionId ?? entry.txHash ?? `${index}`;
      const event = eventById.get(id) ?? null;
      const previous = index > 0 ? entries[index - 1] : null;
      const typeKey = this.normalizeTypeKey(entry.normalizedType);
      const meta = this.metaForType(typeKey);
      const primaryFlow = this.primaryFlow(event, ledger.familyIdentity);
      const displayQuote = this.resolveDisplayQuote(event, ledger.familyIdentity, primaryFlow);
      const avcoAfter = entry.avcoAfterUsd;
      const path = this.resolvePath(entry, event, walletLabels);
      const displayVenue = this.resolveVenueLabel(entry, event);

      return {
        id,
        typeKey,
        txHash: entry.txHash ?? id,
        timestamp: entry.blockTimestamp ?? '',
        glyph: meta.glyph,
        color: meta.color,
        label: meta.label,
        protocolName: entry.protocolName ?? event?.protocolName ?? null,
        displayVenue,
        lifecycleKind: entry.lifecycleKind,
        networkLabel: this.resolveNetworkLabel(entry, event),
        quantityDelta: entry.quantityDelta ?? 0,
        amountUsd: displayQuote.amountUsd,
        quantityAfter: entry.quantityAfter ?? 0,
        coveredQuantityAfter: entry.coveredQuantityAfter ?? 0,
        uncoveredQuantityAfter: entry.uncoveredQuantityAfter ?? 0,
        totalCostBasisAfterUsd: entry.totalCostBasisAfterUsd,
        avcoBeforeUsd: previous?.avcoAfterUsd ?? null,
        avcoAfterUsd: entry.avcoAfterUsd,
        realisedPnlDeltaUsd: entry.realisedPnlDeltaUsd,
        gasDeltaUsd: entry.gasDeltaUsd,
        basisEffects: entry.basisEffects,
        basisSummary: entry.basisEffects.length > 0 ? entry.basisEffects.join(' · ') : 'No basis effect',
        priceUsd: displayQuote.unitPriceUsd,
        priceSource: displayQuote.priceSource,
        primaryFlowLabel: primaryFlow === null ? null : `${primaryFlow.assetSymbol ?? 'UNKNOWN'} ${this.formatSignedQuantity(primaryFlow.quantityDelta ?? null, 4)}`,
        pathFrom: path.fromLabel,
        pathTo: path.toLabel,
        flows: this.toFlowChips(event?.flows ?? []),
      };
    });
  }

  private buildYProjection(
    timeline: ReadonlyArray<SessionAssetLedgerTimelineEntryResponse>,
    events: ReadonlyArray<SessionAssetLedgerEventOverlayResponse>,
    familyIdentity: string
  ): (value: number | null) => number {
    const prices = events
      .map((event) => this.resolveDisplayQuote(event, familyIdentity, this.primaryFlow(event, familyIdentity)).unitPriceUsd)
      .filter((value): value is number => value !== null);
    const avcos = timeline
      .map((entry) => entry.avcoAfterUsd)
      .filter((value): value is number => value !== null);
    const values = [...prices, ...avcos];
    const min = values.length === 0 ? 0 : Math.min(...values) * 0.88;
    const max = values.length === 0 ? 1 : Math.max(...values) * 1.08;
    const plotHeight = CHART.height - CHART.top - CHART.bottom;

    return (value: number | null) => {
      if (value === null) {
        return CHART.height - CHART.bottom;
      }
      const ratio = (value - min) / Math.max(max - min, 1);
      return CHART.height - CHART.bottom - ratio * plotHeight;
    };
  }

  private resolvePath(
    entry: SessionAssetLedgerTimelineEntryResponse,
    event: SessionAssetLedgerEventOverlayResponse | null,
    walletLabels: ReadonlyMap<string, string>
  ): { fromLabel: string; toLabel: string } {
    const walletLabel = this.walletScopeLabel(event?.walletAddresses ?? [], walletLabels);
    const destination = event?.protocolName ?? entry.lifecycleKind ?? 'Ledger';
    const quantityDelta = entry.quantityDelta ?? 0;
    const basisEffects = new Set(entry.basisEffects);
    const outbound = quantityDelta < 0 || basisEffects.has('CARRY_OUT') || basisEffects.has('DISPOSE') || basisEffects.has('REALLOCATE_OUT');
    const inbound = quantityDelta > 0 || basisEffects.has('CARRY_IN') || basisEffects.has('ACQUIRE') || basisEffects.has('REALLOCATE_IN');

    if (outbound && !inbound) {
      return { fromLabel: walletLabel, toLabel: destination };
    }
    if (inbound && !outbound) {
      return { fromLabel: destination, toLabel: walletLabel };
    }
    return { fromLabel: walletLabel, toLabel: destination };
  }

  private walletScopeLabel(
    walletAddresses: ReadonlyArray<string>,
    walletLabels: ReadonlyMap<string, string>
  ): string {
    if (walletAddresses.length === 0) {
      return 'Session wallets';
    }
    if (walletAddresses.length === 1) {
      const normalized = walletAddresses[0].trim().toLowerCase();
      return walletLabels.get(normalized) ?? this.shortHash(walletAddresses[0]);
    }
    return `${walletAddresses.length} wallets`;
  }

  private resolveVenueLabel(
    entry: SessionAssetLedgerTimelineEntryResponse,
    event: SessionAssetLedgerEventOverlayResponse | null
  ): string | null {
    if (entry.protocolName !== null && entry.protocolName.trim().length > 0) {
      return entry.protocolName;
    }
    if (event?.protocolName != null && event.protocolName.trim().length > 0) {
      return event.protocolName;
    }
    return null;
  }

  private resolveNetworkLabel(
    entry: SessionAssetLedgerTimelineEntryResponse,
    event: SessionAssetLedgerEventOverlayResponse | null
  ): string {
    if (event?.networkIds.length) {
      return event.networkIds.join(' · ');
    }
    if (this.isExternalLedgerEvent(event)) {
      return `EXTERNAL LEDGER · ${entry.normalizedType ?? 'EVENT'}`;
    }
    return 'UNKNOWN';
  }

  private normalizeSearchQuery(value: string | null): string {
    return value?.trim().toLowerCase() ?? '';
  }

  private markerSearchHaystack(marker: MarkerView): string {
    const fields: Array<string> = [
      marker.id,
      marker.txHash,
      marker.typeKey,
      marker.label,
      marker.protocolName ?? '',
      marker.displayVenue ?? '',
      marker.lifecycleKind ?? '',
      marker.networkLabel,
      marker.timestamp,
      marker.pathFrom,
      marker.pathTo,
      marker.basisSummary,
      marker.priceSource ?? '',
      marker.primaryFlowLabel ?? '',
      marker.quantityDelta.toString(),
      marker.amountUsd?.toString() ?? '',
      marker.quantityAfter.toString(),
      marker.coveredQuantityAfter.toString(),
      marker.uncoveredQuantityAfter.toString(),
      marker.totalCostBasisAfterUsd?.toString() ?? '',
      marker.avcoBeforeUsd?.toString() ?? '',
      marker.avcoAfterUsd?.toString() ?? '',
      marker.realisedPnlDeltaUsd?.toString() ?? '',
      marker.gasDeltaUsd?.toString() ?? '',
      marker.priceUsd?.toString() ?? '',
      marker.basisEffects.join(' '),
    ];
    marker.flows.forEach((flow) => {
      fields.push(flow.role, flow.assetSymbol, flow.quantityLabel, flow.className);
    });
    return fields.join(' ').toLowerCase();
  }

  private resolveDisplayQuote(
    event: SessionAssetLedgerEventOverlayResponse | null,
    familyIdentity: string,
    primaryFlow: SessionAssetLedgerEventFlowResponse | null
  ): { unitPriceUsd: number | null; amountUsd: number | null; priceSource: string | null } {
    if (primaryFlow === null) {
      return { unitPriceUsd: null, amountUsd: null, priceSource: null };
    }
    if (primaryFlow.unitPriceUsd !== null || primaryFlow.valueUsd !== null || primaryFlow.priceSource !== null) {
      return {
        unitPriceUsd: primaryFlow.unitPriceUsd ?? null,
        amountUsd: primaryFlow.valueUsd ?? null,
        priceSource: primaryFlow.priceSource ?? null,
      };
    }
    if (this.isExternalLedgerEvent(event)) {
      const familyFlow = event?.flows.find((flow) => this.flowMatchesFamily(flow, familyIdentity)) ?? null;
      if (familyFlow !== null) {
        const stablecoinNotional = this.stablecoinPrincipalNotional(event?.flows ?? []);
        const familyQuantity = Math.abs(familyFlow.quantityDelta ?? 0);
        if (stablecoinNotional !== null && familyQuantity > 0) {
          return {
            unitPriceUsd: stablecoinNotional / familyQuantity,
            amountUsd: stablecoinNotional,
            priceSource: 'STABLECOIN_SIDE',
          };
        }
      }
    }
    return { unitPriceUsd: null, amountUsd: null, priceSource: null };
  }

  private primaryFlow(
    event: SessionAssetLedgerEventOverlayResponse | null,
    familyIdentity: string
  ): SessionAssetLedgerEventFlowResponse | null {
    if (event === null || event.flows.length === 0) {
      return null;
    }
    const familyFlow = event.flows.find((flow) => this.flowMatchesFamily(flow, familyIdentity));
    if (familyFlow !== undefined) {
      return familyFlow;
    }
    return event.flows.find((flow) => flow.unitPriceUsd !== null) ?? event.flows[0];
  }

  private flowMatchesFamily(flow: SessionAssetLedgerEventFlowResponse, familyIdentity: string): boolean {
    const symbol = flow.assetSymbol?.trim().toUpperCase() ?? '';
    if (familyIdentity === 'FAMILY:ETH') {
      return ETH_FAMILY_SYMBOLS.has(symbol);
    }
    if (familyIdentity === 'FAMILY:BTC') {
      return BTC_FAMILY_SYMBOLS.has(symbol);
    }
    if (familyIdentity === 'FAMILY:AVAX') {
      return AVAX_FAMILY_SYMBOLS.has(symbol);
    }
    if (familyIdentity === 'FAMILY:MNT') {
      return MNT_FAMILY_SYMBOLS.has(symbol);
    }
    if (familyIdentity === 'FAMILY:USDC') {
      return USDC_FAMILY_SYMBOLS.has(symbol);
    }
    if (familyIdentity.startsWith('FAMILY:')) {
      return symbol === familyIdentity.slice('FAMILY:'.length);
    }
    if (familyIdentity.startsWith('SYMBOL:')) {
      return symbol === familyIdentity.slice('SYMBOL:'.length);
    }
    return false;
  }

  private stablecoinPrincipalNotional(flows: ReadonlyArray<SessionAssetLedgerEventFlowResponse>): number | null {
    const principalStablecoinFlows = flows.filter((flow) => {
      const symbol = flow.assetSymbol?.trim().toUpperCase() ?? '';
      const role = flow.role?.trim().toUpperCase() ?? '';
      return STABLECOIN_SYMBOLS.has(symbol) && role !== 'FEE' && flow.quantityDelta !== null;
    });
    if (principalStablecoinFlows.length === 0) {
      return null;
    }
    return principalStablecoinFlows.reduce((sum, flow) => sum + Math.abs(flow.quantityDelta ?? 0), 0);
  }

  private isExternalLedgerEvent(event: SessionAssetLedgerEventOverlayResponse | null): boolean {
    if (event === null) {
      return false;
    }
    return event.walletAddresses.some((walletAddress) => walletAddress.trim().toUpperCase().startsWith('BYBIT:'));
  }

  private toFlowChips(flows: ReadonlyArray<SessionAssetLedgerEventFlowResponse>): ReadonlyArray<FlowChipView> {
    return flows.map((flow) => {
      const role = (flow.role ?? 'OTHER').toUpperCase();
      return {
        role,
        assetSymbol: flow.assetSymbol?.trim().toUpperCase() ?? 'UNKNOWN',
        quantityLabel: this.formatSignedQuantity(flow.quantityDelta ?? null, 4),
        className: this.flowClassName(role),
      };
    });
  }

  private normalizeTypeKey(normalizedType: string | null): string {
    switch (normalizedType) {
      case 'LENDING_WITHDRAWAL':
        return 'LENDING_WITHDRAW';
      case 'STAKE_DEPOSIT':
        return 'STAKING_DEPOSIT';
      case 'STAKE_WITHDRAWAL':
        return 'STAKING_WITHDRAW';
      default:
        return normalizedType ?? 'OTHER';
    }
  }

  private metaForType(typeKey: string): TypeVisualMeta {
    if (typeKey in TYPE_META) {
      return TYPE_META[typeKey];
    }
    if (typeKey in TYPE_DISPLAY_OVERRIDES) {
      const override = TYPE_DISPLAY_OVERRIDES[typeKey];
      return {
        ...TYPE_META[override.baseType],
        label: override.label,
      };
    }
    return heuristicTypeMeta(typeKey);
  }

  private eventFamilyForType(typeKey: string): EventFamilyKey | null {
    if (typeKey.startsWith('LP_')) {
      return 'lp';
    }
    if (typeKey === 'BRIDGE_IN' || typeKey === 'BRIDGE_OUT') {
      return 'bridge';
    }
    if (typeKey === 'INTERNAL_TRANSFER' || typeKey === 'EXTERNAL_TRANSFER_IN' || typeKey === 'EXTERNAL_TRANSFER_OUT') {
      return 'transfer';
    }
    if (typeKey.startsWith('LENDING_') || typeKey === 'BORROW' || typeKey === 'REPAY') {
      return 'lending';
    }
    if (typeKey === 'REWARD_CLAIM') {
      return 'reward';
    }
    if (typeKey.startsWith('STAKING_')) {
      return 'staking';
    }
    return null;
  }

  private familyDisplaySymbol(familyIdentity: string): string {
    if (familyIdentity.startsWith('FAMILY:')) {
      return familyIdentity.slice('FAMILY:'.length);
    }
    if (familyIdentity.startsWith('SYMBOL:')) {
      return familyIdentity.slice('SYMBOL:'.length);
    }
    return familyIdentity;
  }

  private flowClassName(role: string): string {
    switch (role) {
      case 'BUY':
        return 'buy';
      case 'SELL':
        return 'sell';
      case 'TRANSFER':
        return 'transfer';
      case 'FEE':
        return 'fee';
      default:
        return 'other';
    }
  }

  private formatShortDate(value: string | null): string {
    if (value === null || value.length === 0) {
      return '—';
    }
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return value;
    }
    return new Intl.DateTimeFormat('en-US', {
      month: 'short',
      day: 'numeric',
    }).format(date);
  }

  private toErrorMessage(error: HttpErrorResponse): string {
    if (error.status === 404) {
      return 'No ledger timeline is available for this asset in the current session.';
    }
    const backendMessage =
      typeof error.error === 'object' &&
      error.error !== null &&
      'message' in error.error &&
      typeof error.error.message === 'string'
        ? error.error.message
        : null;
    return backendMessage ?? 'Unable to load asset ledger timeline.';
  }

  ngAfterViewInit(): void {
    const canvases = [
      this.chartCanvasRef?.nativeElement,
      this.qtyChartCanvasRef?.nativeElement,
      this.pnlChartCanvasRef?.nativeElement,
      this.rangePreviewCanvasRef?.nativeElement,
    ];
    this.resizeObserver = new ResizeObserver(() => {
      this.renderChart();
      this.renderSupplementalCharts();
    });
    canvases.forEach((canvas) => {
      const parent = canvas?.parentElement;
      if (parent !== null && parent !== undefined) {
        this.resizeObserver?.observe(parent);
      }
    });
    this.renderLegendIcons();
    this.renderChart();
    this.renderSupplementalCharts();
    this.renderRangePreview();
  }

  ngOnDestroy(): void {
    this.resizeObserver?.disconnect();
    if (this.copyResetTimerId !== null) {
      window.clearTimeout(this.copyResetTimerId);
    }
  }

  private renderLegendIcons(): void {
    const canvases = this.legendCanvasRefs;
    if (canvases === undefined) {
      return;
    }
    canvases.forEach((canvasRef) => {
      const canvas = canvasRef.nativeElement;
      const typeKey = canvas.dataset['type'] ?? 'OTHER';
      const meta = this.metaForType(typeKey);
      this.paintIconCanvas(canvas, meta.color, meta.icon, 28);
    });
  }

  private renderRangePreview(): void {
    const canvas = this.rangePreviewCanvasRef?.nativeElement;
    const markers = this.assetData()?.markers ?? [];
    const visibleIds = new Set(this.visibleMarkers().map((marker) => marker.id));
    if (canvas === undefined) {
      return;
    }

    const parentRect = canvas.parentElement?.getBoundingClientRect();
    const cssWidth = Math.max(Math.floor(parentRect?.width ?? 240), 240);
    const cssHeight = 32;
    const dpr = window.devicePixelRatio || 1;

    canvas.style.width = `${cssWidth}px`;
    canvas.style.height = `${cssHeight}px`;
    canvas.width = Math.floor(cssWidth * dpr);
    canvas.height = Math.floor(cssHeight * dpr);

    const ctx = canvas.getContext('2d');
    if (ctx === null) {
      return;
    }

    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.scale(dpr, dpr);
    ctx.clearRect(0, 0, cssWidth, cssHeight);

    if (markers.length === 0) {
      return;
    }

    const height = cssHeight;
    const selectionStart = (this.rangeStartPercent() / 100) * cssWidth;
    const selectionEnd = (this.rangeEndPercent() / 100) * cssWidth;
    const projectX = this.buildTimeProjector(markers, cssWidth, 0, 0);

    ctx.fillStyle = 'rgba(255,255,255,.04)';
    ctx.beginPath();
    ctx.roundRect(0, height * 0.28, cssWidth, height * 0.44, 3);
    ctx.fill();

    const selectionWidth = Math.max(selectionEnd - selectionStart, 2);
    ctx.fillStyle = 'rgba(34,211,238,.16)';
    ctx.beginPath();
    ctx.roundRect(selectionStart, height * 0.22, selectionWidth, height * 0.56, 2);
    ctx.fill();
    ctx.strokeStyle = 'rgba(34,211,238,.45)';
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.roundRect(selectionStart, height * 0.22, selectionWidth, height * 0.56, 2);
    ctx.stroke();

    markers.forEach((marker, index) => {
      const meta = this.metaForType(marker.typeKey);
      const x = projectX(index);
      const active = visibleIds.has(marker.id);
      ctx.beginPath();
      ctx.arc(x, height * 0.5, 2.5, 0, Math.PI * 2);
      ctx.fillStyle = active ? `${meta.color}cc` : `${meta.color}22`;
      ctx.fill();
    });
  }

  private renderChart(): void {
    const canvas = this.chartCanvasRef?.nativeElement;
    const windowMarkers = this.windowMarkers();
    const visibleMarkers = this.visibleMarkers();
    if (canvas === undefined) {
      return;
    }

    const parentRect = canvas.parentElement?.getBoundingClientRect();
    const cssWidth = Math.max(Math.floor(parentRect?.width ?? 1020), 320);
    const cssHeight = 360;
    const dpr = window.devicePixelRatio || 1;

    canvas.style.width = `${cssWidth}px`;
    canvas.style.height = `${cssHeight}px`;
    canvas.width = Math.floor(cssWidth * dpr);
    canvas.height = Math.floor(cssHeight * dpr);

    const ctx = canvas.getContext('2d');
    if (ctx === null) {
      return;
    }

    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.scale(dpr, dpr);
    ctx.clearRect(0, 0, cssWidth, cssHeight);

    if (windowMarkers.length === 0) {
      this.renderedMarkers = [];
      return;
    }

    const pad = { top: 20, right: 20, bottom: 50, left: 64 };
    const innerWidth = cssWidth - pad.left - pad.right;
    const innerHeight = cssHeight - pad.top - pad.bottom;
    const values = [
      ...windowMarkers.map((marker) => marker.priceUsd).filter((value): value is number => value !== null),
      ...windowMarkers.map((marker) => marker.avcoAfterUsd).filter((value): value is number => value !== null),
    ];
    const minValue = values.length === 0 ? 0 : Math.min(...values) * 0.85;
    const maxValue = values.length === 0 ? 1 : Math.max(...values) * 1.1;
    const projectY = (value: number | null): number => {
      if (value === null) {
        return cssHeight - pad.bottom;
      }
      const ratio = (value - minValue) / Math.max(maxValue - minValue, 1);
      return cssHeight - pad.bottom - ratio * innerHeight;
    };
    const windowTimestamps = windowMarkers.map((marker) => this.parseMarkerTimestamp(marker.timestamp));
    const knownTimestamps = windowTimestamps.filter((value): value is number => value !== null);
    const minTimestamp = knownTimestamps.length > 0 ? Math.min(...knownTimestamps) : null;
    const maxTimestamp = knownTimestamps.length > 0 ? Math.max(...knownTimestamps) : null;
    const useTimeScale = minTimestamp !== null && maxTimestamp !== null && maxTimestamp > minTimestamp;
    const targetXForIndex = (index: number): number => {
      if (windowMarkers.length <= 1) {
        return pad.left + innerWidth / 2;
      }
      if (!useTimeScale) {
        return pad.left + (innerWidth * index) / (windowMarkers.length - 1);
      }
      const markerTimestamp = windowTimestamps[index];
      if (markerTimestamp === null || minTimestamp === null || maxTimestamp === null) {
        return pad.left + (innerWidth * index) / (windowMarkers.length - 1);
      }
      const ratio = (markerTimestamp - minTimestamp) / Math.max(maxTimestamp - minTimestamp, 1);
      return pad.left + innerWidth * ratio;
    };
    const minimumMarkerGap =
      windowMarkers.length <= 18 ? 18 : windowMarkers.length <= 40 ? 14 : windowMarkers.length <= 90 ? 10 : 7;
    const xTargets = windowMarkers.map((_, index) => targetXForIndex(index));
    const xPositions = [...xTargets];
    if (xPositions.length > 1) {
      for (let index = 1; index < xPositions.length; index += 1) {
        xPositions[index] = Math.max(xPositions[index], xPositions[index - 1] + minimumMarkerGap);
      }
      const maxX = cssWidth - pad.right;
      const overflow = xPositions.at(-1)! - maxX;
      if (overflow > 0) {
        for (let index = 0; index < xPositions.length; index += 1) {
          xPositions[index] -= overflow;
        }
      }
      const minX = pad.left;
      const underflow = minX - xPositions[0];
      if (underflow > 0) {
        for (let index = 0; index < xPositions.length; index += 1) {
          xPositions[index] += underflow;
        }
      }
      for (let index = xPositions.length - 2; index >= 0; index -= 1) {
        xPositions[index] = Math.min(xPositions[index], xPositions[index + 1] - minimumMarkerGap);
      }
    }
    const projectX = (index: number): number => xPositions[index] ?? pad.left + innerWidth / 2;

    for (let i = 0; i <= 5; i += 1) {
      const value = minValue + (i / 5) * (maxValue - minValue);
      const y = projectY(value);
      ctx.strokeStyle = 'rgba(255,255,255,0.04)';
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(pad.left, y);
      ctx.lineTo(cssWidth - pad.right, y);
      ctx.stroke();
      ctx.fillStyle = 'rgba(82,88,112,.7)';
      ctx.font = '9px JetBrains Mono, monospace';
      ctx.textAlign = 'right';
      ctx.fillText(`$${Math.round(value)}`, pad.left - 6, y + 3);
    }

    const avcoPoints = windowMarkers
      .map((marker, index) => ({ x: projectX(index), y: projectY(marker.avcoAfterUsd) }))
      .filter((point) => Number.isFinite(point.y));
    if (avcoPoints.length > 0) {
      ctx.beginPath();
      avcoPoints.forEach((point, index) => {
        if (index === 0) {
          ctx.moveTo(point.x, point.y);
        } else {
          ctx.lineTo(point.x, point.y);
        }
      });
      ctx.strokeStyle = 'rgba(255,255,255,.45)';
      ctx.lineWidth = 1.5;
      ctx.setLineDash([5, 4]);
      ctx.stroke();
      ctx.setLineDash([]);

      ctx.beginPath();
      avcoPoints.forEach((point, index) => {
        if (index === 0) {
          ctx.moveTo(point.x, point.y);
        } else {
          ctx.lineTo(point.x, point.y);
        }
      });
      ctx.lineTo(avcoPoints.at(-1)!.x, cssHeight - pad.bottom);
      ctx.lineTo(avcoPoints[0].x, cssHeight - pad.bottom);
      ctx.closePath();
      const gradient = ctx.createLinearGradient(0, 0, 0, cssHeight);
      gradient.addColorStop(0, 'rgba(255,255,255,.05)');
      gradient.addColorStop(1, 'rgba(255,255,255,0)');
      ctx.fillStyle = gradient;
      ctx.fill();
    }

    const layoutById = new Map(
      windowMarkers.map((marker, index) => [
        marker.id,
        {
          x: projectX(index),
          avcoY: projectY(marker.avcoAfterUsd),
        },
      ] as const)
    );
    this.renderedMarkers = visibleMarkers.map((marker) => {
      const layout = layoutById.get(marker.id) ?? { x: projectX(0), avcoY: projectY(marker.avcoAfterUsd) };
      const x = layout.x;
      const y = projectY(marker.priceUsd ?? marker.avcoAfterUsd);
      return {
        markerId: marker.id,
        x,
        y,
        avcoY: layout.avcoY,
        hasStem:
          marker.priceUsd !== null &&
          marker.avcoAfterUsd !== null &&
          Math.abs(marker.priceUsd - marker.avcoAfterUsd) > 0.0001,
      };
    });

    this.renderedMarkers.forEach((rendered) => {
      if (!rendered.hasStem) {
        return;
      }
      ctx.beginPath();
      ctx.moveTo(rendered.x, Math.min(rendered.y, rendered.avcoY));
      ctx.lineTo(rendered.x, Math.max(rendered.y, rendered.avcoY));
      ctx.strokeStyle = 'rgba(255,255,255,0.06)';
      ctx.lineWidth = 1;
      ctx.stroke();
    });

    const baseRadius = 10;
    visibleMarkers.forEach((marker, index) => {
      const rendered = this.renderedMarkers[index];
      const active = this.isLogRowActive(marker.id);
      const radius = active ? 14 : baseRadius;
      ctx.save();
      if (active) {
        ctx.shadowColor = marker.color;
        ctx.shadowBlur = 16;
      }
      ctx.beginPath();
      ctx.arc(rendered.x, rendered.y, radius, 0, Math.PI * 2);
      ctx.fillStyle = `${marker.color}22`;
      ctx.fill();
      ctx.strokeStyle = active ? marker.color : `${marker.color}bb`;
      ctx.lineWidth = active ? 2 : 1.5;
      ctx.stroke();
      if (active) {
        ctx.beginPath();
        ctx.arc(rendered.x, rendered.y, radius + 4, 0, Math.PI * 2);
        ctx.strokeStyle = `${marker.color}30`;
        ctx.lineWidth = 1.5;
        ctx.stroke();
      }
      const meta = this.metaForType(marker.typeKey);
      ctx.strokeStyle = active ? marker.color : `${marker.color}dd`;
      ctx.fillStyle = active ? marker.color : `${marker.color}dd`;
      ctx.lineWidth = active ? 1.8 : 1.4;
      ctx.lineCap = 'round';
      ctx.lineJoin = 'round';
      meta.icon(ctx, rendered.x, rendered.y, radius * 0.92);
      ctx.restore();

      if (active && marker.priceUsd !== null) {
        const label = `$${Math.round(marker.priceUsd).toLocaleString()}`;
        ctx.font = '500 9px JetBrains Mono, monospace';
        ctx.textAlign = 'center';
        const textWidth = ctx.measureText(label).width + 12;
        const boxX = rendered.x - textWidth / 2;
        const boxY = rendered.y - radius - 18;
        ctx.fillStyle = `${marker.color}22`;
        ctx.beginPath();
        ctx.roundRect(boxX, boxY, textWidth, 15, 2);
        ctx.fill();
        ctx.fillStyle = marker.color;
        ctx.fillText(label, rendered.x, boxY + 11);
      }
    });

    let previousTickX = Number.NEGATIVE_INFINITY;
    windowMarkers.forEach((marker, index) => {
      const x = projectX(index);
      ctx.beginPath();
      ctx.arc(x, cssHeight - pad.bottom + 24, 2.5, 0, Math.PI * 2);
      ctx.fillStyle = `${marker.color}99`;
      ctx.fill();
      const isLast = index === windowMarkers.length - 1;
      if (x - previousTickX >= 72 || isLast) {
        ctx.fillStyle = 'rgba(82,88,112,.55)';
        ctx.font = '8.5px JetBrains Mono, monospace';
        ctx.textAlign = 'center';
        ctx.fillText(this.formatTimelineDate(marker.timestamp), x, cssHeight - pad.bottom + 13);
        previousTickX = x;
      }
    });
  }

  private renderSupplementalCharts(): void {
    this.renderQuantityChart();
    this.renderPnlChart();
  }

  private renderQuantityChart(): void {
    const canvas = this.qtyChartCanvasRef?.nativeElement;
    const windowMarkers = this.windowMarkers();
    const visibleMarkers = this.visibleMarkers();
    if (canvas === undefined) {
      return;
    }
    if (this.isSectionCollapsed('position')) {
      this.quantityRenderedMarkers = [];
      return;
    }

    const { ctx, cssWidth, cssHeight } = this.prepareResponsiveCanvas(canvas, QTY_CHART_HEIGHT);
    if (ctx === null) {
      return;
    }

    const pad = { top: 18, right: 20, bottom: 34, left: 72 };
    this.paintChartBackground(ctx, cssWidth, cssHeight, pad);

    if (windowMarkers.length === 0) {
      this.quantityRenderedMarkers = [];
      this.paintEmptyChartState(ctx, cssWidth, cssHeight, 'No position history in selected range.');
      return;
    }

    const values = windowMarkers.map((marker) => marker.quantityAfter);
    const minValue = Math.min(...values);
    const maxValue = Math.max(...values);
    const span = Math.max(maxValue - minValue, Math.max(Math.abs(maxValue), 1) * 0.03);
    const paddedMin = Math.max(0, minValue - span * 0.18);
    const paddedMax = maxValue + span * 0.18;
    const innerHeight = cssHeight - pad.top - pad.bottom;

    const projectY = (value: number): number => {
      const ratio = (value - paddedMin) / Math.max(paddedMax - paddedMin, 1e-9);
      return cssHeight - pad.bottom - ratio * innerHeight;
    };
    const projectX = this.buildTimeProjector(windowMarkers, cssWidth, pad.left, pad.right);

    for (let index = 0; index <= 4; index += 1) {
      const value = paddedMin + ((paddedMax - paddedMin) * index) / 4;
      const y = projectY(value);
      ctx.strokeStyle = 'rgba(255,255,255,0.04)';
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(pad.left, y);
      ctx.lineTo(cssWidth - pad.right, y);
      ctx.stroke();
      ctx.fillStyle = 'rgba(82,88,112,.7)';
      ctx.font = '9px JetBrains Mono, monospace';
      ctx.textAlign = 'right';
      ctx.fillText(this.formatQuantity(value, 3), pad.left - 8, y + 3);
    }

    ctx.beginPath();
    windowMarkers.forEach((marker, index) => {
      const x = projectX(index);
      const y = projectY(marker.quantityAfter);
      if (index === 0) {
        ctx.moveTo(x, y);
      } else {
        ctx.lineTo(x, y);
      }
    });
    ctx.strokeStyle = 'rgba(34, 211, 238, 0.9)';
    ctx.lineWidth = 1.8;
    ctx.stroke();

    ctx.beginPath();
    windowMarkers.forEach((marker, index) => {
      const x = projectX(index);
      const y = projectY(marker.quantityAfter);
      if (index === 0) {
        ctx.moveTo(x, y);
      } else {
        ctx.lineTo(x, y);
      }
    });
    ctx.lineTo(projectX(windowMarkers.length - 1), cssHeight - pad.bottom);
    ctx.lineTo(projectX(0), cssHeight - pad.bottom);
    ctx.closePath();
    const gradient = ctx.createLinearGradient(0, pad.top, 0, cssHeight);
    gradient.addColorStop(0, 'rgba(34, 211, 238, 0.18)');
    gradient.addColorStop(1, 'rgba(34, 211, 238, 0.02)');
    ctx.fillStyle = gradient;
    ctx.fill();

    const visibleIds = new Set(visibleMarkers.map((marker) => marker.id));
    this.quantityRenderedMarkers = windowMarkers.map((marker, markerIndex) => ({
      markerId: marker.id,
      x: projectX(markerIndex),
      y: projectY(marker.quantityAfter),
      radius: visibleIds.has(marker.id) ? 5 : 4,
    }));

    windowMarkers.forEach((marker, markerIndex) => {
      const x = projectX(markerIndex);
      const y = projectY(marker.quantityAfter);
      const isVisible = visibleIds.has(marker.id);
      const isActive = this.isLogRowActive(marker.id);
      ctx.beginPath();
      ctx.arc(x, y, isActive ? 4.8 : isVisible ? 3.2 : 2.4, 0, Math.PI * 2);
      ctx.fillStyle = isVisible ? marker.color : 'rgba(148,163,184,.55)';
      ctx.fill();
      if (isActive) {
        ctx.beginPath();
        ctx.arc(x, y, 8, 0, Math.PI * 2);
        ctx.strokeStyle = `${marker.color}44`;
        ctx.lineWidth = 1.5;
        ctx.stroke();
      }
    });

    this.paintTimelineAxis(ctx, windowMarkers, projectX, cssHeight, pad.bottom);
  }

  private renderPnlChart(): void {
    const canvas = this.pnlChartCanvasRef?.nativeElement;
    const markers = this.realisedPnlMarkers();
    if (canvas === undefined) {
      return;
    }
    if (this.isSectionCollapsed('pnl')) {
      this.pnlRenderedMarkers = [];
      return;
    }

    const { ctx, cssWidth, cssHeight } = this.prepareResponsiveCanvas(canvas, PNL_CHART_HEIGHT);
    if (ctx === null) {
      return;
    }

    const pad = { top: 18, right: 20, bottom: 34, left: 72 };
    this.paintChartBackground(ctx, cssWidth, cssHeight, pad);

    if (markers.length === 0) {
      this.pnlRenderedMarkers = [];
      this.paintEmptyChartState(ctx, cssWidth, cssHeight, 'No realised P&L events under current filters.');
      return;
    }

    const cumulativeValues: number[] = [];
    let cumulative = 0;
    markers.forEach((marker) => {
      cumulative += marker.realisedPnlDeltaUsd ?? 0;
      cumulativeValues.push(cumulative);
    });
    const pnlValues = markers.map((marker) => marker.realisedPnlDeltaUsd ?? 0);
    const domainMin = Math.min(0, ...pnlValues, ...cumulativeValues);
    const domainMax = Math.max(0, ...pnlValues, ...cumulativeValues);
    const span = Math.max(domainMax - domainMin, 1);
    const paddedMin = domainMin - span * 0.12;
    const paddedMax = domainMax + span * 0.12;
    const innerHeight = cssHeight - pad.top - pad.bottom;

    const projectY = (value: number): number => {
      const ratio = (value - paddedMin) / Math.max(paddedMax - paddedMin, 1e-9);
      return cssHeight - pad.bottom - ratio * innerHeight;
    };
    const projectX = this.buildTimeProjector(markers, cssWidth, pad.left, pad.right);

    for (let index = 0; index <= 4; index += 1) {
      const value = paddedMin + ((paddedMax - paddedMin) * index) / 4;
      const y = projectY(value);
      ctx.strokeStyle = Math.abs(value) < span * 0.02 ? 'rgba(59,130,246,.2)' : 'rgba(255,255,255,0.04)';
      ctx.lineWidth = Math.abs(value) < span * 0.02 ? 1.4 : 1;
      ctx.beginPath();
      ctx.moveTo(pad.left, y);
      ctx.lineTo(cssWidth - pad.right, y);
      ctx.stroke();
      ctx.fillStyle = 'rgba(82,88,112,.7)';
      ctx.font = '9px JetBrains Mono, monospace';
      ctx.textAlign = 'right';
      ctx.fillText(this.formatSignedUsd(value, 0), pad.left - 8, y + 3);
    }

    const barSpacing = markers.length <= 1 ? 46 : projectX(1) - projectX(0);
    const barWidth = Math.max(6, Math.min(18, barSpacing * 0.36));
    const zeroY = projectY(0);

    this.pnlRenderedMarkers = markers.map((marker, index) => {
      const value = marker.realisedPnlDeltaUsd ?? 0;
      const x = projectX(index);
      const y = projectY(value);
      const top = Math.min(y, zeroY);
      const height = Math.max(2, Math.abs(zeroY - y));
      ctx.fillStyle = value >= 0 ? 'rgba(34,197,94,.34)' : 'rgba(239,68,68,.34)';
      ctx.strokeStyle = value >= 0 ? 'rgba(34,197,94,.7)' : 'rgba(239,68,68,.72)';
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.roundRect(x - barWidth / 2, top, barWidth, height, 3);
      ctx.fill();
      ctx.stroke();
      return {
        markerId: marker.id,
        x,
        y,
        radius: 5,
        barLeft: x - barWidth / 2,
        barRight: x + barWidth / 2,
        barTop: top,
        barBottom: top + height,
        cumulativeY: projectY(cumulativeValues[index] ?? 0),
      };
    });

    ctx.beginPath();
    cumulativeValues.forEach((value, index) => {
      const x = projectX(index);
      const y = projectY(value);
      if (index === 0) {
        ctx.moveTo(x, y);
      } else {
        ctx.lineTo(x, y);
      }
    });
    ctx.strokeStyle = 'rgba(59,130,246,.9)';
    ctx.lineWidth = 1.8;
    ctx.stroke();

    markers.forEach((marker, index) => {
      if (!this.isLogRowActive(marker.id)) {
        return;
      }
      const x = projectX(index);
      const y = projectY(cumulativeValues[index] ?? 0);
      ctx.beginPath();
      ctx.arc(x, y, 4.5, 0, Math.PI * 2);
      ctx.fillStyle = '#3b82f6';
      ctx.fill();
      ctx.beginPath();
      ctx.arc(x, y, 9, 0, Math.PI * 2);
      ctx.strokeStyle = 'rgba(59,130,246,.28)';
      ctx.lineWidth = 1.4;
      ctx.stroke();
    });

    this.paintTimelineAxis(ctx, markers, projectX, cssHeight, pad.bottom);
  }

  private paintIconCanvas(
    canvas: HTMLCanvasElement,
    color: string,
    icon: IconRenderer,
    size: number
  ): void {
    const dpr = window.devicePixelRatio || 1;
    canvas.width = size * dpr;
    canvas.height = size * dpr;
    canvas.style.width = `${size}px`;
    canvas.style.height = `${size}px`;
    const ctx = canvas.getContext('2d');
    if (ctx === null) {
      return;
    }
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.scale(dpr, dpr);
    ctx.clearRect(0, 0, size, size);
    ctx.strokeStyle = color;
    ctx.fillStyle = color;
    ctx.lineWidth = 1.4;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    icon(ctx, size / 2, size / 2, size * 0.48);
  }

  private prepareResponsiveCanvas(
    canvas: HTMLCanvasElement,
    cssHeight: number
  ): { ctx: CanvasRenderingContext2D | null; cssWidth: number; cssHeight: number } {
    const parentRect = canvas.parentElement?.getBoundingClientRect();
    const cssWidth = Math.max(Math.floor(parentRect?.width ?? 1020), 320);
    const dpr = window.devicePixelRatio || 1;

    canvas.style.width = `${cssWidth}px`;
    canvas.style.height = `${cssHeight}px`;
    canvas.width = Math.floor(cssWidth * dpr);
    canvas.height = Math.floor(cssHeight * dpr);

    const ctx = canvas.getContext('2d');
    if (ctx !== null) {
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.scale(dpr, dpr);
      ctx.clearRect(0, 0, cssWidth, cssHeight);
    }

    return { ctx, cssWidth, cssHeight };
  }

  private paintChartBackground(
    ctx: CanvasRenderingContext2D,
    cssWidth: number,
    cssHeight: number,
    pad: { top: number; right: number; bottom: number; left: number }
  ): void {
    ctx.fillStyle = 'rgba(255,255,255,0.01)';
    ctx.fillRect(0, 0, cssWidth, cssHeight);
    ctx.strokeStyle = 'rgba(255,255,255,0.04)';
    ctx.lineWidth = 1;
    ctx.strokeRect(pad.left, pad.top, cssWidth - pad.left - pad.right, cssHeight - pad.top - pad.bottom);
  }

  private paintEmptyChartState(
    ctx: CanvasRenderingContext2D,
    cssWidth: number,
    cssHeight: number,
    message: string
  ): void {
    ctx.fillStyle = 'rgba(82,88,112,.88)';
    ctx.font = '11px JetBrains Mono, monospace';
    ctx.textAlign = 'center';
    ctx.fillText(message, cssWidth / 2, cssHeight / 2);
  }

  private buildTimeProjector(
    markers: ReadonlyArray<MarkerView>,
    cssWidth: number,
    padLeft: number,
    padRight: number
  ): (index: number) => number {
    const innerWidth = cssWidth - padLeft - padRight;
    const timestamps = markers.map((marker) => this.parseMarkerTimestamp(marker.timestamp));
    const knownTimestamps = timestamps.filter((value): value is number => value !== null);
    const minTimestamp = knownTimestamps.length > 0 ? Math.min(...knownTimestamps) : null;
    const maxTimestamp = knownTimestamps.length > 0 ? Math.max(...knownTimestamps) : null;
    const useTimeScale = minTimestamp !== null && maxTimestamp !== null && maxTimestamp > minTimestamp;

    const targetXForIndex = (index: number): number => {
      if (markers.length <= 1) {
        return padLeft + innerWidth / 2;
      }
      if (!useTimeScale) {
        return padLeft + (innerWidth * index) / (markers.length - 1);
      }
      const markerTimestamp = timestamps[index];
      if (markerTimestamp === null || minTimestamp === null || maxTimestamp === null) {
        return padLeft + (innerWidth * index) / (markers.length - 1);
      }
      const ratio = (markerTimestamp - minTimestamp) / Math.max(maxTimestamp - minTimestamp, 1);
      return padLeft + innerWidth * ratio;
    };

    const minimumMarkerGap =
      markers.length <= 18 ? 18 : markers.length <= 40 ? 14 : markers.length <= 90 ? 10 : 7;
    const xTargets = markers.map((_, index) => targetXForIndex(index));
    const xPositions = [...xTargets];
    if (xPositions.length > 1) {
      for (let index = 1; index < xPositions.length; index += 1) {
        xPositions[index] = Math.max(xPositions[index], xPositions[index - 1] + minimumMarkerGap);
      }
      const maxX = cssWidth - padRight;
      const overflow = xPositions.at(-1)! - maxX;
      if (overflow > 0) {
        for (let index = 0; index < xPositions.length; index += 1) {
          xPositions[index] -= overflow;
        }
      }
      const minX = padLeft;
      const underflow = minX - xPositions[0];
      if (underflow > 0) {
        for (let index = 0; index < xPositions.length; index += 1) {
          xPositions[index] += underflow;
        }
      }
      for (let index = xPositions.length - 2; index >= 0; index -= 1) {
        xPositions[index] = Math.min(xPositions[index], xPositions[index + 1] - minimumMarkerGap);
      }
    }

    return (index: number) => xPositions[index] ?? padLeft + innerWidth / 2;
  }

  private paintTimelineAxis(
    ctx: CanvasRenderingContext2D,
    markers: ReadonlyArray<MarkerView>,
    projectX: (index: number) => number,
    cssHeight: number,
    bottomPad: number
  ): void {
    let previousTickX = Number.NEGATIVE_INFINITY;
    markers.forEach((marker, index) => {
      const x = projectX(index);
      ctx.beginPath();
      ctx.arc(x, cssHeight - bottomPad + 14, 2.2, 0, Math.PI * 2);
      ctx.fillStyle = `${marker.color}88`;
      ctx.fill();
      const isLast = index === markers.length - 1;
      if (x - previousTickX >= 72 || isLast) {
        ctx.fillStyle = 'rgba(82,88,112,.58)';
        ctx.font = '8.5px JetBrains Mono, monospace';
        ctx.textAlign = 'center';
        ctx.fillText(this.formatTimelineDate(marker.timestamp), x, cssHeight - bottomPad + 28);
        previousTickX = x;
      }
    });
  }

  private formatTimelineDate(value: string): string {
    if (value.length === 0) {
      return '—';
    }
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return value.slice(5, 10);
    }
    const month = String(date.getUTCMonth() + 1).padStart(2, '0');
    const day = String(date.getUTCDate()).padStart(2, '0');
    return `${month}-${day}`;
  }

  private formatBasisEffectLabel(value: string): string {
    if (value === 'NONE') {
      return 'No basis effect';
    }
    return value
      .split('_')
      .map((part) => `${part.slice(0, 1)}${part.slice(1).toLowerCase()}`)
      .join(' ');
  }

  private basisEffectColor(value: string): string {
    switch (value) {
      case 'ACQUIRE':
      case 'CARRY_IN':
      case 'REALLOCATE_IN':
        return '#22c55e';
      case 'DISPOSE':
      case 'CARRY_OUT':
      case 'REALLOCATE_OUT':
        return '#ef4444';
      case 'GAS_ONLY':
        return '#f59e0b';
      case 'NONE':
        return '#94a3b8';
      default:
        return '#3b82f6';
    }
  }

  private findNearestRenderedMarker(x: number, y: number, maxDistance: number): RenderedMarkerView | null {
    let bestMarker: RenderedMarkerView | null = null;
    let bestDistance = Number.POSITIVE_INFINITY;
    for (const marker of this.renderedMarkers) {
      const dx = x - marker.x;
      const dy = y - marker.y;
      const distance = Math.sqrt(dx * dx + dy * dy);
      if (distance < bestDistance) {
        bestDistance = distance;
        bestMarker = marker;
      }
    }
    return bestMarker !== null && bestDistance <= maxDistance ? bestMarker : null;
  }

  private findRenderedPointMarker(
    canvas: HTMLCanvasElement | undefined,
    renderedMarkers: ReadonlyArray<RenderedPointView>,
    event: MouseEvent
  ): string | null {
    if (canvas === undefined || renderedMarkers.length === 0) {
      return null;
    }
    const rect = canvas.getBoundingClientRect();
    const x = event.clientX - rect.left;
    const y = event.clientY - rect.top;
    let bestMatch: RenderedPointView | null = null;
    let bestDistance = Number.POSITIVE_INFINITY;
    for (const marker of renderedMarkers) {
      const dx = x - marker.x;
      const dy = y - marker.y;
      const distance = Math.sqrt(dx * dx + dy * dy);
      if (distance <= marker.radius + 8 && distance < bestDistance) {
        bestDistance = distance;
        bestMatch = marker;
      }
    }
    return bestMatch?.markerId ?? null;
  }

  private findRenderedPnlMarker(canvas: HTMLCanvasElement | undefined, event: MouseEvent): string | null {
    if (canvas === undefined || this.pnlRenderedMarkers.length === 0) {
      return null;
    }
    const rect = canvas.getBoundingClientRect();
    const x = event.clientX - rect.left;
    const y = event.clientY - rect.top;
    let bestMatch: RenderedPnlMarkerView | null = null;
    let bestDistance = Number.POSITIVE_INFINITY;
    for (const marker of this.pnlRenderedMarkers) {
      const insideBar =
        x >= marker.barLeft - 4 &&
        x <= marker.barRight + 4 &&
        y >= marker.barTop - 4 &&
        y <= marker.barBottom + 4;
      if (insideBar) {
        return marker.markerId;
      }
      const dx = x - marker.x;
      const dy = y - marker.cumulativeY;
      const distance = Math.sqrt(dx * dx + dy * dy);
      if (distance <= marker.radius + 6 && distance < bestDistance) {
        bestDistance = distance;
        bestMatch = marker;
      }
    }
    return bestMatch?.markerId ?? null;
  }

  private clearPinnedTooltip(): void {
    this.pinnedMarkerId.set(null);
    this.hoveredMarkerId.set(null);
    this.showTooltip.set(false);
    this.renderChart();
    this.renderSupplementalCharts();
  }

  private togglePinnedMarker(markerId: string | null, clientX: number, clientY: number): void {
    if (markerId === null) {
      this.clearPinnedTooltip();
      return;
    }
    if (this.pinnedMarkerId() === markerId) {
      this.clearPinnedTooltip();
      return;
    }
    this.pinnedMarkerId.set(markerId);
    this.hoveredMarkerId.set(markerId);
    this.showTooltip.set(true);
    this.positionTooltip(clientX, clientY);
    this.renderChart();
    this.renderSupplementalCharts();
  }

  private positionTooltip(clientX: number, clientY: number): void {
    const tipWidth = 268;
    const nextLeft =
      clientX + 16 + tipWidth > window.innerWidth ? clientX - (tipWidth + 16) : clientX + 16;
    const nextTop = Math.min(Math.max(12, clientY - 20), Math.max(12, window.innerHeight - 300));
    this.tooltipPosition.set({
      left: nextLeft,
      top: nextTop,
    });
  }

  private defaultRangeForRecentWindow(markers: ReadonlyArray<MarkerView>): { startIndex: number; endIndex: number } {
    const markerCount = markers.length;
    if (markerCount === 0) {
      return { startIndex: 0, endIndex: 0 };
    }
    if (markerCount === 1) {
      return { startIndex: 0, endIndex: 0 };
    }

    const endIndex = markerCount - 1;
    const lastTimestamp = this.parseMarkerTimestamp(markers[endIndex].timestamp);
    if (lastTimestamp === null) {
      return { startIndex: 0, endIndex };
    }

    const windowStartTimestamp = lastTimestamp - DEFAULT_RANGE_DAYS * 24 * 60 * 60 * 1000;
    const timeBoundStartIndex = markers.findIndex((marker) => {
      const markerTimestamp = this.parseMarkerTimestamp(marker.timestamp);
      return markerTimestamp !== null && markerTimestamp >= windowStartTimestamp;
    });

    if (timeBoundStartIndex <= 0) {
      return { startIndex: 0, endIndex };
    }

    const minimumWindowStartIndex = Math.max(0, endIndex - (Math.min(DEFAULT_RANGE_MIN_POINTS, markerCount) - 1));
    return {
      startIndex: Math.min(timeBoundStartIndex, minimumWindowStartIndex),
      endIndex,
    };
  }

  private parseMarkerTimestamp(value: string | null): number | null {
    if (value === null || value.length === 0) {
      return null;
    }
    const parsed = Date.parse(value);
    return Number.isNaN(parsed) ? null : parsed;
  }
}
