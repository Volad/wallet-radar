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

function toNum(value) {
  if (value === null || value === undefined) return 0;
  if (typeof value === 'number') return value;
  if (typeof value === 'string') return Number(value);
  if (value && typeof value.toString === 'function') return Number(value.toString());
  return Number(value);
}

function normalizeAddress(value) {
  return value == null ? '' : String(value).trim().toLowerCase();
}

function normalizeAssetIdentity(networkId, raw) {
  if (raw == null) return networkId ? 'NATIVE:' + networkId : null;
  const value = String(raw).trim();
  if (!value) return networkId ? 'NATIVE:' + networkId : null;
  if (/^native:/i.test(value)) return networkId ? 'NATIVE:' + networkId : value.toUpperCase();
  if (value === '0x0000000000000000000000000000000000000000') return networkId ? 'NATIVE:' + networkId : value;
  return value.toLowerCase();
}

function canonicalSymbol(symbol) {
  const s = symbol == null ? '' : String(symbol).trim().toUpperCase();
  switch (s) {
    case 'WETH': return 'ETH';
    case 'WBTC': return 'BTC';
    case 'WAVAX': return 'AVAX';
    case 'WMNT': return 'MNT';
    default: return s;
  }
}

const accountingUniverseId = sessionId || null;
const mandatorySymbols = ['ETH', 'BTC', 'MNT', 'AVAX', 'USDC', 'USDT'];
const latestPointByBucket = {};
db.getCollection('asset_ledger_points')
  .find(accountingUniverseId ? {accountingUniverseId} : {})
  .sort({walletAddress: 1, networkId: 1, accountingAssetIdentity: 1, blockTimestamp: 1, transactionIndex: 1, replaySequence: 1})
  .forEach(point => {
    const key = [
      normalizeAddress(point.walletAddress),
      point.networkId || '',
      normalizeAssetIdentity(point.networkId, point.accountingAssetIdentity) || ''
    ].join('|');
    latestPointByBucket[key] = point;
  });

const balanceByBucket = {};
db.getCollection('on_chain_balances')
  .find(sessionId ? {sessionId} : {})
  .forEach(balance => {
    const key = [
      normalizeAddress(balance.walletAddress),
      balance.networkId || '',
      normalizeAssetIdentity(balance.networkId, balance.assetContract) || ''
    ].join('|');
    balanceByBucket[key] = balance;
  });

const exactCoverage = {};
for (const symbol of mandatorySymbols) {
  exactCoverage[symbol] = {current: 0, covered: 0, uncovered: 0, coverageRatio: 1, dirtyBuckets: 0};
}

const familyCoverage = {};
for (const symbol of mandatorySymbols) {
  familyCoverage['FAMILY:' + symbol] = {current: 0, covered: 0, uncovered: 0, coverageRatio: 1, dirtyBuckets: 0};
}

for (const [key, balance] of Object.entries(balanceByBucket)) {
  const symbol = canonicalSymbol(balance.assetSymbol);
  const current = toNum(balance.quantity);
  if (!(current > 0)) continue;
  const point = latestPointByBucket[key] || null;
  const covered = point ? Math.min(toNum(point.basisBackedQuantityAfter), current) : 0;
  const uncovered = Math.max(current - covered, 0);

  if (exactCoverage[symbol]) {
    exactCoverage[symbol].current += current;
    exactCoverage[symbol].covered += covered;
    exactCoverage[symbol].uncovered += uncovered;
    if (uncovered > 1e-18 || (point && (point.hasIncompleteHistoryAfter || point.hasUnresolvedFlagsAfter))) {
      exactCoverage[symbol].dirtyBuckets += 1;
    }
  }

  const familyId = point && point.accountingFamilyIdentity ? point.accountingFamilyIdentity : 'FAMILY:' + symbol;
  if (familyCoverage[familyId]) {
    familyCoverage[familyId].current += current;
    familyCoverage[familyId].covered += covered;
    familyCoverage[familyId].uncovered += uncovered;
    if (uncovered > 1e-18 || (point && (point.hasIncompleteHistoryAfter || point.hasUnresolvedFlagsAfter))) {
      familyCoverage[familyId].dirtyBuckets += 1;
    }
  }
}

for (const row of Object.values(exactCoverage)) {
  row.coverageRatio = row.current > 0 ? row.covered / row.current : 1;
}
for (const row of Object.values(familyCoverage)) {
  row.coverageRatio = row.current > 0 ? row.covered / row.current : 1;
}

const protocolGapByType = db.getCollection('normalized_transactions').aggregate([
  {
    \$match: {
      status: 'CONFIRMED',
      source: 'ON_CHAIN',
      \$or: [{protocolName: null}, {protocolName: {\$exists: false}}, {protocolName: ''}],
      type: {
        \$nin: [
          'EXTERNAL_TRANSFER_IN',
          'EXTERNAL_TRANSFER_OUT',
          'INTERNAL_TRANSFER',
          'APPROVE',
          'UNKNOWN',
          'ADMIN_CONFIG',
          'SPONSORED_GAS_IN'
        ]
      }
    }
  },
  {\$group: {_id: '\$type', count: {\$sum: 1}}},
  {\$sort: {count: -1}}
]).toArray().map(row => ({type: row._id, count: row.count}));

const counterpartyGapByType = db.getCollection('normalized_transactions').aggregate([
  {
    \$match: {
      status: 'CONFIRMED',
      source: 'ON_CHAIN',
      \$or: [{counterpartyAddress: null}, {counterpartyAddress: {\$exists: false}}, {counterpartyAddress: ''}],
      type: {
        \$in: [
          'EXTERNAL_TRANSFER_IN',
          'EXTERNAL_TRANSFER_OUT',
          'INTERNAL_TRANSFER',
          'BRIDGE_IN',
          'BRIDGE_OUT',
          'SWAP',
          'LENDING_DEPOSIT',
          'LENDING_WITHDRAW',
          'STAKING_DEPOSIT',
          'STAKING_WITHDRAW',
          'LP_ENTRY',
          'LP_EXIT',
          'VAULT_DEPOSIT',
          'VAULT_WITHDRAW',
          'BORROW',
          'REPAY'
        ]
      }
    }
  },
  {\$group: {_id: '\$type', count: {\$sum: 1}}},
  {\$sort: {count: -1}}
]).toArray().map(row => ({type: row._id, count: row.count}));

const needsReviewBySourceType = db.getCollection('normalized_transactions').aggregate([
  {\$match: {status: 'NEEDS_REVIEW'}},
  {\$group: {_id: {source: '\$source', type: '\$type', networkId: '\$networkId'}, count: {\$sum: 1}}},
  {\$sort: {count: -1}}
]).toArray().map(row => ({
  source: row._id.source || null,
  type: row._id.type || null,
  networkId: row._id.networkId || null,
  count: row.count
}));

const pipelineState = sessionId
  ? db.getCollection('user_sessions').findOne({_id: sessionId}, {pipelineState: 1, accountingUniverseId: 1})
  : null;

print(EJSON.stringify({
  capturedAt: new Date().toISOString(),
  sessionId: sessionId || null,
  phaseCoverage,
  exactCoverage,
  familyCoverage,
  protocolGapByType,
  counterpartyGapByType,
  needsReviewBySourceType,
  pipelineState
}, null, 2));
EOF
