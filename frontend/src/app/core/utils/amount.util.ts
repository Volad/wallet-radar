/**
 * Smart amount formatting utilities.
 *
 * Rules (applied to the absolute value):
 *   abs >= 1000  → 2 decimal places + "k" suffix  (e.g. $2.51k)
 *   abs >= 100   → 2 decimal places               (e.g. $173.75)
 *   abs >= 0     → 3 decimal places               (e.g. $99.113 / $0.001)
 */

const FULL_PRECISION_FORMAT = new Intl.NumberFormat('en-US', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 10,
});

export function smartFormatNumber(value: number): string {
  const abs = Math.abs(value);
  if (abs >= 1000) {
    const k = value / 1000;
    return new Intl.NumberFormat('en-US', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(k) + 'k';
  }
  if (abs >= 100) {
    return new Intl.NumberFormat('en-US', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(value);
  }
  return new Intl.NumberFormat('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 3,
  }).format(value);
}

/** Smart-formatted USD string, e.g. "$2.51k", "$173.75", "$0.145" */
export function smartFormatUsd(value: number | null): string {
  if (value === null || !isFinite(value)) return '—';
  return '$' + smartFormatNumber(value);
}

/** Smart-formatted USD with explicit sign, e.g. "+$2.51k", "-$0.145" */
export function smartFormatSignedUsd(value: number | null): string {
  if (value === null || !isFinite(value)) return '—';
  const prefix = value > 0 ? '+$' : value < 0 ? '-$' : '$';
  return prefix + smartFormatNumber(Math.abs(value));
}

/** Smart-formatted plain quantity (no $ prefix). */
export function smartFormatQty(value: number | null): string {
  if (value === null || !isFinite(value)) return '—';
  return smartFormatNumber(value);
}

/** Full-precision USD tooltip string, e.g. "$173.754892". */
export function fullPrecisionUsd(value: number | null): string {
  if (value === null || !isFinite(value)) return '';
  return '$' + FULL_PRECISION_FORMAT.format(value);
}

/** Full-precision signed USD tooltip string, e.g. "+$173.754892". */
export function fullPrecisionSignedUsd(value: number | null): string {
  if (value === null || !isFinite(value)) return '';
  const prefix = value > 0 ? '+$' : value < 0 ? '-$' : '$';
  return prefix + FULL_PRECISION_FORMAT.format(Math.abs(value));
}

/** Full-precision plain quantity tooltip string. */
export function fullPrecisionQty(value: number | null): string {
  if (value === null || !isFinite(value)) return '';
  return FULL_PRECISION_FORMAT.format(value);
}
