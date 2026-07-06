#!/usr/bin/env sh
# Prepare MongoDB for a full 2-year cold backfill (on-chain wallets + Bybit integrations).
#
# Scope (DESTRUCTIVE — entire database named in WR_MONGO_URI / profile default):
# - Deletes all backfill_segments (on-chain + integration).
# - Deletes all integration_raw_events and bybit_extracted_events.
# - Deletes all external_ledger_raw (legacy Bybit staging).
# - Deletes all raw_transactions (forces RPC re-fetch from sync_status windows).
# - Resets on-chain sync_status rows to PENDING and clears block progress.
# - Resets integration sync_status rows to PENDING (windows preserved when present).
# - Clears user_sessions.pipelineState (integration status left unchanged — planner
#   will drive BACKFILLING again when segments are planned).
#
# Then invokes scripts/avco/reset-derived.sh to drop normalized_transactions,
# asset_ledger_points, on_chain_balances, and reset raw normalization flags.
#
# Usage (from repo root):
#   WR_RUNTIME_PROFILE=prod sh scripts/mongo-prep-full-2yr-backfill.sh
#   sh scripts/mongo-prep-full-2yr-backfill.sh   # local profile when mongo is local
#
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
# common.sh recomputes REPO_ROOT from $0; when this file is sourced or $0 is the
# caller script, that path is wrong. Preserve repo root for downstream scripts.
WR_REPO_ROOT="$REPO_ROOT"

# shellcheck disable=SC1091
. "$REPO_ROOT/scripts/avco/common.sh"
REPO_ROOT="$WR_REPO_ROOT"

mongo_uri=$(resolve_mongo_uri)

printf 'mongo-prep-full-2yr-backfill: preparing %s\n' "$mongo_uri"

run_mongosh "$mongo_uri" <<'EOF'
function n(name, filter) {
  return db.getCollection(name).countDocuments(filter || {});
}

const before = {
  backfill_segments: n("backfill_segments"),
  integration_raw_events: n("integration_raw_events"),
  bybit_extracted_events: n("bybit_extracted_events"),
  external_ledger_raw: n("external_ledger_raw"),
  raw_transactions: n("raw_transactions"),
  sync_status: n("sync_status"),
};

db.getCollection("backfill_segments").deleteMany({});
db.getCollection("integration_raw_events").deleteMany({});
db.getCollection("bybit_extracted_events").deleteMany({});
db.getCollection("external_ledger_raw").deleteMany({});
db.getCollection("raw_transactions").deleteMany({});

db.getCollection("sync_status").updateMany(
  { sourceKind: "ONCHAIN" },
  {
    $set: {
      status: "PENDING",
      progressPct: 0,
      lastBlockSynced: null,
      backfillComplete: false,
      rawFetchComplete: false,
      syncBannerMessage: "Full replay: awaiting backfill",
      retryCount: 0,
      nextRetryAfter: null,
      updatedAt: new Date(),
    },
    $unset: {
      windowFromBlock: "",
      windowToBlock: "",
      windowFromTime: "",
      windowToTime: "",
    },
  }
);

db.getCollection("sync_status").updateMany(
  { sourceKind: "INTEGRATION" },
  {
    $set: {
      status: "PENDING",
      progressPct: 0,
      backfillComplete: false,
      rawFetchComplete: false,
      syncBannerMessage: "Full replay: awaiting integration backfill",
      retryCount: 0,
      nextRetryAfter: null,
      updatedAt: new Date(),
    },
  }
);

db.getCollection("user_sessions").updateMany({}, { $unset: { pipelineState: "" } });

const after = {
  backfill_segments: n("backfill_segments"),
  integration_raw_events: n("integration_raw_events"),
  bybit_extracted_events: n("bybit_extracted_events"),
  external_ledger_raw: n("external_ledger_raw"),
  raw_transactions: n("raw_transactions"),
  sync_status: n("sync_status"),
};

printjson({ mongoPrepFull2yrBackfill: new Date().toISOString(), before, after });
EOF

printf 'Running reset-derived pipeline wipe...\n'
sh "$REPO_ROOT/scripts/avco/reset-derived.sh"

printf 'Done. Re-run session backfill planner / BackfillJobRunner so segments are recreated.\n'
