#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

session_id=""

while [ $# -gt 0 ]; do
  case "$1" in
    --session-id)
      session_id=${2:-}
      shift 2
      ;;
    *)
      printf 'Unknown argument: %s\n' "$1" >&2
      exit 1
      ;;
  esac
done

mongo_uri=$(resolve_mongo_uri)
session_literal=null
if [ -n "$session_id" ]; then
  session_literal="'$session_id'"
fi

run_mongosh "$mongo_uri" <<EOF
const sessionId = $session_literal;
const phaseCoverage = {
  rawTransactions: db.getCollection('raw_transactions').countDocuments({}),
  rawPending: db.getCollection('raw_transactions').countDocuments({normalizationStatus: 'PENDING'}),
  normalizedTransactions: db.getCollection('normalized_transactions').countDocuments({}),
  confirmed: db.getCollection('normalized_transactions').countDocuments({status: 'CONFIRMED'}),
  needsReview: db.getCollection('normalized_transactions').countDocuments({status: 'NEEDS_REVIEW'}),
  pendingClarification: db.getCollection('normalized_transactions').countDocuments({status: 'PENDING_CLARIFICATION'}),
  pendingPrice: db.getCollection('normalized_transactions').countDocuments({status: 'PENDING_PRICE'}),
  pendingStat: db.getCollection('normalized_transactions').countDocuments({status: 'PENDING_STAT'}),
  assetLedgerPoints: db.getCollection('asset_ledger_points').countDocuments({}),
  onChainBalances: db.getCollection('on_chain_balances').countDocuments({}),
  unmatchedBridgeOut: db.getCollection('normalized_transactions').countDocuments({
    source: 'ON_CHAIN',
    type: 'BRIDGE_OUT',
    \$or: [{matchedCounterparty: null}, {matchedCounterparty: ''}]
  }),
  unmatchedBridgeIn: db.getCollection('normalized_transactions').countDocuments({
    source: 'ON_CHAIN',
    type: 'BRIDGE_IN',
    \$or: [{matchedCounterparty: null}, {matchedCounterparty: ''}]
  })
};

const pipelineState = sessionId
  ? db.getCollection('user_sessions').findOne({_id: sessionId}, {pipelineState: 1, accountingUniverseId: 1})
  : null;

print(EJSON.stringify({
  capturedAt: new Date().toISOString(),
  sessionId: sessionId || null,
  phaseCoverage,
  pipelineState
}, null, 2));
EOF
