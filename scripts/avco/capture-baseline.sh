#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

run_id=""

while [ $# -gt 0 ]; do
  case "$1" in
    --run-id)
      run_id=${2:-}
      shift 2
      ;;
    *)
      printf 'Unknown argument: %s\n' "$1" >&2
      exit 1
      ;;
  esac
done

if [ -z "$run_id" ]; then
  run_id=$(next_stats_run_id)
fi

mongo_uri=$(resolve_mongo_uri)
run_dir="$REPO_ROOT/results/stats/$run_id"
raw_dir="$run_dir/data/raw"
derived_dir="$run_dir/data/derived"

mkdir -p "$raw_dir" "$derived_dir"

printf 'Capturing AVCO baseline into %s\n' "$run_dir"

git -C "$REPO_ROOT" rev-parse HEAD > "$raw_dir/git-sha.txt"
docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}' > "$raw_dir/docker-ps.txt"

if docker ps --format '{{.Names}}' | grep -qx 'walletradar-backend-prod'; then
  docker logs --tail 300 walletradar-backend-prod > "$raw_dir/backend-prod-tail.log" 2>&1 || true
fi

cat > "$run_dir/full-pipeline-audit.md" <<EOF
# Full Pipeline Audit — Run $run_id

Status: baseline captured

This run contains the baseline Mongo and runtime snapshot before or after one
AVCO refactoring slice rerun.

Pending formal financial audit.
EOF

run_mongosh "$mongo_uri" <<'EOF' > "$run_dir/summary.json"
function count(name, filter = {}) {
  return db.getCollection(name).countDocuments(filter);
}

const sessions = db.getCollection("user_sessions")
  .find({}, {_id: 1, pipelineState: 1, accountingUniverseId: 1})
  .toArray();

print(EJSON.stringify({
  capturedAt: new Date().toISOString(),
  counts: {
    rawTransactions: count("raw_transactions"),
    rawPending: count("raw_transactions", {normalizationStatus: "PENDING"}),
    externalLedgerRaw: count("external_ledger_raw"),
    externalLedgerRawRaw: count("external_ledger_raw", {status: "RAW"}),
    bybitExtractedEvents: count("bybit_extracted_events"),
    bybitExtractedRaw: count("bybit_extracted_events", {status: "RAW"}),
    normalizedTransactions: count("normalized_transactions"),
    pendingClarification: count("normalized_transactions", {status: "PENDING_CLARIFICATION"}),
    pendingPrice: count("normalized_transactions", {status: "PENDING_PRICE"}),
    pendingStat: count("normalized_transactions", {status: "PENDING_STAT"}),
    confirmed: count("normalized_transactions", {status: "CONFIRMED"}),
    needsReview: count("normalized_transactions", {status: "NEEDS_REVIEW"}),
    historicalPrices: count("historical_prices"),
    assetLedgerPoints: count("asset_ledger_points"),
    onChainBalances: count("on_chain_balances")
  },
  sessions
}, null, 2));
EOF

cp "$run_dir/summary.json" "$derived_dir/mongo-summary.json"

printf 'Baseline summary written to %s\n' "$run_dir/summary.json"
