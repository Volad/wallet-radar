#!/usr/bin/env sh
# Per-(wallet, network, asset) financial snapshot for AVCO refactor regression gates.
# Keys on business identity (not Mongo _id). Compare outputs with diff for zero-drift verification.
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

run_id=""
output=""

while [ $# -gt 0 ]; do
  case "$1" in
    --run-id)
      run_id=${2:-}
      shift 2
      ;;
    --output)
      output=${2:-}
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
mkdir -p "$run_dir/data/derived"

if [ -z "$output" ]; then
  output="$run_dir/data/derived/financial-snapshot.json"
fi

printf 'Capturing per-asset financial snapshot into %s\n' "$output"

run_mongosh "$mongo_uri" <<'EOF' > "$output"
function terminalByAsset() {
  const pipeline = [
    { $sort: {
        accountingUniverseId: 1,
        walletAddress: 1,
        networkId: 1,
        accountingAssetIdentity: 1,
        blockTimestamp: 1,
        transactionIndex: 1,
        replaySequence: 1
    }},
    { $group: {
        _id: {
          universe: "$accountingUniverseId",
          wallet: "$walletAddress",
          network: "$networkId",
          asset: "$accountingAssetIdentity"
        },
        quantityAfter: { $last: "$quantityAfter" },
        avcoAfterUsd: { $last: "$avcoAfterUsd" },
        totalCostBasisAfterUsd: { $last: "$totalCostBasisAfterUsd" },
        cumulativeRealisedPnlUsd: { $sum: { $ifNull: ["$realisedPnlDeltaUsd", 0] } },
        lastReplaySequence: { $last: "$replaySequence" },
        lastTxHash: { $last: "$txHash" },
        lastBasisEffect: { $last: "$basisEffect" },
        pointCount: { $sum: 1 }
    }},
    { $sort: {
        "_id.universe": 1,
        "_id.wallet": 1,
        "_id.network": 1,
        "_id.asset": 1
    }}
  ];
  return db.asset_ledger_points.aggregate(pipeline, { allowDiskUse: true }).toArray();
}

function conservationIdentity() {
  let realised = 0;
  let terminalBasis = 0;
  let acquisition = 0;
  let disposed = 0;
  db.asset_ledger_points.find({}).forEach(p => {
    const r = p.realisedPnlDeltaUsd;
    const c = p.costBasisDeltaUsd;
    if (r != null) realised += Number(r);
    if (c != null) {
      if (Number(c) > 0) acquisition += Number(c);
      if (Number(c) < 0) disposed += Math.abs(Number(c));
    }
  });
  const terminals = terminalByAsset();
  terminals.forEach(t => {
    const b = t.totalCostBasisAfterUsd;
    if (b != null) terminalBasis += Number(b);
  });
  return {
    sumRealisedPnlUsd: realised,
    sumTerminalCostBasisUsd: terminalBasis,
    sumAcquisitionCostUsd: acquisition,
    sumDisposedBasisUsd: disposed,
    identityResidualUsd: realised + terminalBasis - (acquisition - disposed)
  };
}

print(EJSON.stringify({
  capturedAt: new Date().toISOString(),
  schemaVersion: "financial-snapshot-v1",
  conservation: conservationIdentity(),
  terminalByAsset: terminalByAsset()
}, null, 2));
EOF

printf 'Financial snapshot written to %s\n' "$output"
