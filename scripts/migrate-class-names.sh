#!/usr/bin/env sh
# One-time MongoDB _class migration after package rename (application.* refactor).
# Usage:
#   ./scripts/migrate-class-names.sh --dry-run
#   ./scripts/migrate-class-names.sh
#
# Idempotent: only updates documents whose _class still matches the old FQCN.

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/avco/common.sh"

DRY_RUN=false
if [ "${1:-}" = "--dry-run" ]; then
  DRY_RUN=true
fi

MONGO_URI=$(resolve_mongo_uri)
printf 'Mongo URI: %s\n' "$MONGO_URI"
printf 'Mode: %s\n' "$([ "$DRY_RUN" = true ] && printf 'dry-run' || printf 'apply')"

migrate() {
  col=$1
  old=$2
  new=$3

  if [ "$DRY_RUN" = true ]; then
    count=$(run_mongosh "$MONGO_URI" <<EOF
const n = db.getCollection('$col').countDocuments({ _class: '$old' });
print(n);
EOF
)
    printf 'DRY  %-32s %6s docs  %s -> %s\n' "$col" "$count" "$old" "$new"
    return
  fi

  run_mongosh "$MONGO_URI" <<EOF
const r = db.getCollection('$col').updateMany(
  { _class: '$old' },
  { \$set: { _class: '$new' } }
);
print('$col: ' + r.modifiedCount + ' updated');
EOF
}

# costbasis.domain -> application.costbasis.domain
migrate accounting_shortfall_audit \
  com.walletradar.costbasis.domain.AccountingShortfallAudit \
  com.walletradar.application.costbasis.domain.AccountingShortfallAudit
migrate asset_ledger_points \
  com.walletradar.costbasis.domain.AssetLedgerPoint \
  com.walletradar.application.costbasis.domain.AssetLedgerPoint
migrate borrow_liabilities \
  com.walletradar.costbasis.domain.BorrowLiability \
  com.walletradar.application.costbasis.domain.BorrowLiability
migrate counterparty_basis_pools \
  com.walletradar.costbasis.domain.CounterpartyBasisPool \
  com.walletradar.application.costbasis.domain.CounterpartyBasisPool
migrate lp_receipt_basis_pools \
  com.walletradar.costbasis.domain.LpReceiptBasisPool \
  com.walletradar.application.costbasis.domain.LpReceiptBasisPool
migrate on_chain_balances \
  com.walletradar.costbasis.domain.OnChainBalance \
  com.walletradar.application.costbasis.domain.OnChainBalance

# pricing.persistence -> application.pricing.persistence
migrate historical_prices \
  com.walletradar.pricing.persistence.HistoricalPriceDocument \
  com.walletradar.application.pricing.persistence.HistoricalPriceDocument
migrate current_price_quotes \
  com.walletradar.pricing.persistence.CurrentPriceQuoteDocument \
  com.walletradar.application.pricing.persistence.CurrentPriceQuoteDocument

# lending.persistence -> application.lending.persistence
migrate lending_group_refresh_state \
  com.walletradar.lending.persistence.LendingGroupRefreshState \
  com.walletradar.application.lending.persistence.LendingGroupRefreshState
migrate lending_health_factor_snapshots \
  com.walletradar.lending.persistence.LendingHealthFactorSnapshot \
  com.walletradar.application.lending.persistence.LendingHealthFactorSnapshot
migrate lending_market_rate_snapshots \
  com.walletradar.lending.persistence.LendingMarketRateSnapshot \
  com.walletradar.application.lending.persistence.LendingMarketRateSnapshot
migrate lending_receipt_identity \
  com.walletradar.lending.persistence.LendingReceiptIdentityDocument \
  com.walletradar.application.lending.persistence.LendingReceiptIdentityDocument

# liquiditypools.persistence -> application.liquiditypools.persistence
migrate lp_earning_points \
  com.walletradar.liquiditypools.persistence.LpEarningPoint \
  com.walletradar.application.liquiditypools.persistence.LpEarningPoint
migrate lp_pool_depth_cache \
  com.walletradar.liquiditypools.persistence.LpPoolDepthCache \
  com.walletradar.application.liquiditypools.persistence.LpPoolDepthCache
migrate lp_position_refresh_state \
  com.walletradar.liquiditypools.persistence.LpPositionRefreshState \
  com.walletradar.application.liquiditypools.persistence.LpPositionRefreshState
migrate lp_position_snapshots \
  com.walletradar.liquiditypools.persistence.LpPositionSnapshot \
  com.walletradar.application.liquiditypools.persistence.LpPositionSnapshot

# integration.bybit -> application.cex.acquisition.venue.bybit
migrate bybit_live_balances \
  com.walletradar.integration.bybit.BybitLiveBalance \
  com.walletradar.application.cex.acquisition.venue.bybit.BybitLiveBalance

if [ "$DRY_RUN" = true ]; then
  printf '\nDry-run complete. Run without --dry-run to apply.\n'
else
  printf '\nVerifying stale _class prefixes...\n'
  run_mongosh "$MONGO_URI" <<'EOF'
const prefixes = [
  "com.walletradar.costbasis.",
  "com.walletradar.pricing.",
  "com.walletradar.lending.",
  "com.walletradar.liquiditypools.",
  "com.walletradar.integration.bybit."
];
const regex = new RegExp("^(" + prefixes.map(p => p.replace(/\./g, "\\.")).join("|") + ")");
let staleTotal = 0;
db.getCollectionNames().forEach(c => {
  const n = db.getCollection(c).countDocuments({ _class: { $regex: regex } });
  if (n > 0) {
    print("STALE: " + c + ": " + n);
    staleTotal += n;
  }
});
if (staleTotal === 0) {
  print("OK: no stale _class values remain.");
} else {
  print("WARN: " + staleTotal + " documents still have stale _class.");
}
EOF
  printf 'Migration complete.\n'
fi
