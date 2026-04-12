#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

clear_pricing_cache=false

for arg in "$@"; do
  case "$arg" in
    --clear-pricing-cache)
      clear_pricing_cache=true
      ;;
    *)
      printf 'Unknown argument: %s\n' "$arg" >&2
      exit 1
      ;;
  esac
done

mongo_uri=$(resolve_mongo_uri)

printf 'Resetting downstream pipeline state against %s\n' "$mongo_uri"

run_mongosh "$mongo_uri" <<EOF
const clearPricingCache = ${clear_pricing_cache};

function count(name, filter = {}) {
  return db.getCollection(name).countDocuments(filter);
}

const before = {
  rawPending: count("raw_transactions", {normalizationStatus: "PENDING"}),
  externalLedgerRaw: count("external_ledger_raw"),
  externalLedgerRawRaw: count("external_ledger_raw", {status: "RAW"}),
  bybitExtracted: count("bybit_extracted_events"),
  bybitExtractedRaw: count("bybit_extracted_events", {status: "RAW"}),
  normalizedTransactions: count("normalized_transactions"),
  historicalPrices: count("historical_prices"),
  assetLedgerPoints: count("asset_ledger_points"),
  onChainBalances: count("on_chain_balances"),
  sessionsWithPipelineState: count("user_sessions", {pipelineState: {\$exists: true, \$ne: null}})
};

db.getCollection("raw_transactions").updateMany(
  {},
  {
    \$set: {
      normalizationStatus: "PENDING",
      retryCount: 0
    },
    \$unset: {
      lastError: "",
      nextRetryAt: "",
      clarificationEvidence: ""
    }
  }
);

db.getCollection("external_ledger_raw").updateMany(
  {},
  {
    \$set: {status: "RAW"},
    \$unset: {
      "onChainCorrelation.status": "",
      "onChainCorrelation.correlationId": "",
      "onChainCorrelation.matchedDocId": ""
    }
  }
);

db.getCollection("bybit_extracted_events").updateMany(
  {},
  {
    \$set: {status: "RAW"},
    \$unset: {
      "onChainCorrelation.status": "",
      "onChainCorrelation.correlationId": "",
      "onChainCorrelation.matchedDocId": ""
    }
  }
);

db.getCollection("normalized_transactions").deleteMany({});
db.getCollection("asset_ledger_points").deleteMany({});
db.getCollection("on_chain_balances").deleteMany({});
db.getCollection("user_sessions").updateMany({}, {\$unset: {pipelineState: ""}});

if (clearPricingCache) {
  db.getCollection("historical_prices").deleteMany({});
}

const after = {
  rawPending: count("raw_transactions", {normalizationStatus: "PENDING"}),
  externalLedgerRaw: count("external_ledger_raw"),
  externalLedgerRawRaw: count("external_ledger_raw", {status: "RAW"}),
  bybitExtracted: count("bybit_extracted_events"),
  bybitExtractedRaw: count("bybit_extracted_events", {status: "RAW"}),
  normalizedTransactions: count("normalized_transactions"),
  historicalPrices: count("historical_prices"),
  assetLedgerPoints: count("asset_ledger_points"),
  onChainBalances: count("on_chain_balances"),
  sessionsWithPipelineState: count("user_sessions", {pipelineState: {\$exists: true, \$ne: null}})
};

printjson({
  resetCompletedAt: new Date().toISOString(),
  clearPricingCache,
  before,
  after
});
EOF
