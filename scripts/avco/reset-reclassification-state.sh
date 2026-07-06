#!/usr/bin/env sh
# Targeted reset for on-chain transactions that need reclassification re-run.
# Sets CONFIRMED/PENDING_PRICE ON_CHAIN transactions back to PENDING_RECLASSIFICATION
# and clears all downstream derived data (linking, replay, snapshot).
# Bybit/external_ledger normalisation state is preserved.
# Use with --reclassification-only in prod-reset-rebuild-backend.sh.
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

mongo_uri=$(resolve_mongo_uri)

run_mongosh "$mongo_uri" <<'EOF'
function count(col, filter) { return db.getCollection(col).countDocuments(filter || {}); }

const before = {
  pendingReclass:  count("normalized_transactions", { source: "ON_CHAIN", status: "PENDING_RECLASSIFICATION" }),
  confirmed:       count("normalized_transactions", { source: "ON_CHAIN", status: { $in: ["CONFIRMED", "PENDING_PRICE"] } }),
  assetLedger:     count("asset_ledger_points"),
  counterparty:    count("counterparty_basis_pools"),
  lp_receipt:      count("lp_receipt_basis_pools"),
};

// Reset ON_CHAIN confirmed rows to PENDING_RECLASSIFICATION.
// Bybit rows are unaffected — they remain at their current status.
const resetResult = db.normalized_transactions.updateMany(
  { source: "ON_CHAIN", status: { $in: ["CONFIRMED", "PENDING_PRICE"] } },
  {
    $set:  { status: "PENDING_RECLASSIFICATION", retryCount: 0 },
    $unset: {
      correlationId: "", matchedCounterparty: "",
      continuityCandidate: "", lastError: "", nextRetryAt: ""
    }
  }
);

// Clear all replay/snapshot derived data — must be rebuilt after reclassification + linking.
db.asset_ledger_points.deleteMany({});
db.counterparty_basis_pools.deleteMany({});
db.borrow_liabilities.deleteMany({});
db.lp_receipt_basis_pools.deleteMany({});
db.lp_position_snapshots.deleteMany({});
db.on_chain_balances.deleteMany({});
db.lending_receipt_identity.deleteMany({});

// Reset Bybit/external_ledger linking state (onChainCorrelation) so corridor links are re-resolved.
db.bybit_extracted_events.updateMany(
  { "onChainCorrelation.status": { $exists: true } },
  { $unset: { "onChainCorrelation.status": "", "onChainCorrelation.correlationId": "", "onChainCorrelation.matchedDocId": "" } }
);
db.external_ledger_raw.updateMany(
  { "onChainCorrelation.status": { $exists: true } },
  { $unset: { "onChainCorrelation.status": "", "onChainCorrelation.correlationId": "", "onChainCorrelation.matchedDocId": "" } }
);

// Clear pipeline run state so backend starts a fresh pass.
db.user_sessions.updateMany({}, { $unset: { pipelineState: "" } });

const after = {
  pendingReclass:  count("normalized_transactions", { source: "ON_CHAIN", status: "PENDING_RECLASSIFICATION" }),
  confirmed:       count("normalized_transactions", { source: "ON_CHAIN", status: { $in: ["CONFIRMED", "PENDING_PRICE"] } }),
  assetLedger:     count("asset_ledger_points"),
  counterparty:    count("counterparty_basis_pools"),
  lp_receipt:      count("lp_receipt_basis_pools"),
};

printjson({ resetAt: new Date().toISOString(), modified: resetResult.modifiedCount, before, after });
EOF
