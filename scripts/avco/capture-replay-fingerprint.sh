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
derived_dir="$run_dir/data/derived"
mkdir -p "$derived_dir"

flows_file="$derived_dir/confirmed-flow-replay.jsonl"
ledger_file="$derived_dir/asset-ledger-points.jsonl"
fingerprints_file="$derived_dir/replay-fingerprints.json"

run_mongosh "$mongo_uri" <<'EOF' > "$flows_file"
db.getCollection("normalized_transactions")
  .find(
    {status: "CONFIRMED"},
    {
      _id: 1,
      walletAddress: 1,
      networkId: 1,
      blockTimestamp: 1,
      transactionIndex: 1,
      correlationId: 1,
      type: 1,
      status: 1,
      flows: 1
    }
  )
  .sort({blockTimestamp: 1, transactionIndex: 1, _id: 1})
  .forEach(tx => {
    const flows = Array.isArray(tx.flows) ? tx.flows : [];
    flows.forEach((flow, index) => {
      print(EJSON.stringify({
        id: tx._id,
        walletAddress: tx.walletAddress,
        networkId: tx.networkId,
        blockTimestamp: tx.blockTimestamp,
        transactionIndex: tx.transactionIndex,
        correlationId: tx.correlationId,
        type: tx.type,
        status: tx.status,
        flowIndex: index,
        role: flow.role,
        assetContract: flow.assetContract,
        assetSymbol: flow.assetSymbol,
        quantityDelta: flow.quantityDelta,
        unitPriceUsd: flow.unitPriceUsd,
        valueUsd: flow.valueUsd,
        avcoAtTimeOfSale: flow.avcoAtTimeOfSale,
        realisedPnlUsd: flow.realisedPnlUsd
      }, null, 0));
    });
  });
EOF

run_mongosh "$mongo_uri" <<'EOF' > "$ledger_file"
db.getCollection("asset_ledger_points")
  .find(
    {},
    {
      _id: 1,
      accountingUniverseId: 1,
      accountingAssetIdentity: 1,
      normalizedTransactionId: 1,
      flowIndex: 1,
      replaySequence: 1,
      basisEffect: 1,
      quantityDelta: 1,
      costBasisDeltaUsd: 1,
      realisedPnlDeltaUsd: 1,
      gasDeltaUsd: 1,
      quantityAfter: 1,
      totalCostBasisAfterUsd: 1,
      avcoAfterUsd: 1,
      quantityShortfallAfter: 1,
      uncoveredQuantityAfter: 1,
      hasIncompleteHistoryAfter: 1,
      hasUnresolvedFlagsAfter: 1,
      unresolvedFlagCountAfter: 1
    }
  )
  .sort({accountingUniverseId: 1, normalizedTransactionId: 1, flowIndex: 1, replaySequence: 1, _id: 1})
  .forEach(point => print(EJSON.stringify(point, null, 0)));
EOF

flows_sha=$(shasum -a 256 "$flows_file" | awk '{print $1}')
ledger_sha=$(shasum -a 256 "$ledger_file" | awk '{print $1}')

cat > "$fingerprints_file" <<EOF
{
  "capturedAt": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")",
  "confirmedFlowReplaySha256": "$flows_sha",
  "assetLedgerPointsSha256": "$ledger_sha"
}
EOF

printf 'Replay fingerprints written to %s\n' "$fingerprints_file"
