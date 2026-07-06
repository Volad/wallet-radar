#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

mongo_uri=$(resolve_mongo_uri)

printf 'Resetting replay-only derived state against %s\n' "$mongo_uri"

run_mongosh "$mongo_uri" <<'EOF'
function count(name, filter = {}) {
  return db.getCollection(name).countDocuments(filter);
}

const replayFieldFilter = {
  flows: {
    $elemMatch: {
      $or: [
        {avcoAtTimeOfSale: {$exists: true}},
        {realisedPnlUsd: {$exists: true}}
      ]
    }
  }
};

const before = {
  confirmedTransactions: count("normalized_transactions", {status: "CONFIRMED"}),
  transactionsWithReplayFields: count("normalized_transactions", replayFieldFilter),
  assetLedgerPoints: count("asset_ledger_points"),
  onChainBalances: count("on_chain_balances"),
  sessionsWithPipelineState: count("user_sessions", {pipelineState: {$exists: true, $ne: null}})
};

db.getCollection("normalized_transactions").updateMany(
  {},
  {
    $unset: {
      "flows.$[].avcoAtTimeOfSale": "",
      "flows.$[].realisedPnlUsd": ""
    }
  }
);

db.getCollection("asset_ledger_points").deleteMany({});
db.getCollection("on_chain_balances").deleteMany({});
db.getCollection("user_sessions").updateMany({}, {$unset: {pipelineState: ""}});

const after = {
  confirmedTransactions: count("normalized_transactions", {status: "CONFIRMED"}),
  transactionsWithReplayFields: count("normalized_transactions", replayFieldFilter),
  assetLedgerPoints: count("asset_ledger_points"),
  onChainBalances: count("on_chain_balances"),
  sessionsWithPipelineState: count("user_sessions", {pipelineState: {$exists: true, $ne: null}})
};

printjson({
  resetCompletedAt: new Date().toISOString(),
  before,
  after
});
EOF
