import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import {
  fullPrecisionQty,
  fullPrecisionSignedUsd,
  fullPrecisionUsd,
  smartFormatQty,
  smartFormatSignedUsd,
  smartFormatUsd,
} from '../../utils/amount.util';

/**
 * Renders a smart-formatted monetary or quantity value.
 *
 * Formatting rules (absolute value):
 *   ≥ 1000  → 2 decimals + "k"  (e.g. $2.51k)
 *   ≥ 100   → 2 decimals         (e.g. $173.75)
 *   < 100   → 3 decimals         (e.g. $99.113)
 *
 * On hover the full-precision value is shown as a native tooltip.
 *
 * Usage:
 *   <wr-smart-amount [value]="price" />
 *   <wr-smart-amount [value]="pnl"  mode="usd-signed" />
 *   <wr-smart-amount [value]="qty"  mode="qty" [suffix]="symbol" />
 */
@Component({
  selector: 'wr-smart-amount',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span [title]="tooltip()">{{ display() }}{{ suffix() ? '\u00a0' + suffix() : '' }}</span>`,
})
export class SmartAmountComponent {
  /** The numeric value to format. */
  readonly value = input<number | null>(null);

  /**
   * Formatting mode:
   *  - `usd`        — prepend "$"
   *  - `usd-signed` — prepend "+" / "$" / "-$"
   *  - `qty`        — plain number, no currency prefix
   */
  readonly mode = input<'usd' | 'usd-signed' | 'qty'>('usd');

  /** Optional unit appended after the value (e.g. token symbol). */
  readonly suffix = input<string>('');

  readonly display = computed(() => {
    const v = this.value();
    switch (this.mode()) {
      case 'usd-signed': return smartFormatSignedUsd(v);
      case 'qty':        return smartFormatQty(v);
      default:           return smartFormatUsd(v);
    }
  });

  readonly tooltip = computed(() => {
    const v = this.value();
    const suf = this.suffix();
    let full: string;
    switch (this.mode()) {
      case 'usd-signed': full = fullPrecisionSignedUsd(v); break;
      case 'qty':        full = fullPrecisionQty(v); break;
      default:           full = fullPrecisionUsd(v);
    }
    return suf && full ? `${full}\u00a0${suf}` : full;
  });
}
