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
  blockingNeedsReview: db.getCollection('normalized_transactions').countDocuments({
    status: 'NEEDS_REVIEW',
    \$or: [{excludedFromAccounting: {\$exists: false}}, {excludedFromAccounting: false}]
  }),
  excludedNeedsReview: db.getCollection('normalized_transactions').countDocuments({
    status: 'NEEDS_REVIEW',
    excludedFromAccounting: true
  }),
  excludedConfirmed: db.getCollection('normalized_transactions').countDocuments({
    status: 'CONFIRMED',
    excludedFromAccounting: true
  }),
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

const excludedNormalizedIds = db.getCollection('normalized_transactions')
  .find({excludedFromAccounting: true}, {_id: 1})
  .toArray()
  .map(row => row._id);
phaseCoverage.excludedLedgerPoints = excludedNormalizedIds.length === 0
  ? 0
  : db.getCollection('asset_ledger_points').countDocuments({normalizedTransactionId: {\$in: excludedNormalizedIds}});

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
  exactCoverage[symbol] = {
    current: 0,
    covered: 0,
    uncovered: 0,
    blockingUncovered: 0,
    nonBlockingUncovered: 0,
    coverageRatio: 1,
    blockingCoverageRatio: 1,
    dirtyBuckets: 0,
    nonBlockingBuckets: 0
  };
}

const familyCoverage = {};
for (const symbol of mandatorySymbols) {
  familyCoverage['FAMILY:' + symbol] = {
    current: 0,
    covered: 0,
    uncovered: 0,
    blockingUncovered: 0,
    nonBlockingUncovered: 0,
    coverageRatio: 1,
    blockingCoverageRatio: 1,
    dirtyBuckets: 0,
    nonBlockingBuckets: 0
  };
}

function isYieldAccrualCandidate(point) {
  if (!point || point.basisEffect !== 'REALLOCATE_IN') return false;
  return point.lifecycleKind === 'LENDING'
    || point.lifecycleKind === 'STAKING'
    || point.lifecycleKind === 'VAULT';
}

function isNativeGasResidualCandidate(point, balance, uncovered) {
  if (!point || !balance || !(uncovered > 0)) return false;
  const assetIdentity = normalizeAssetIdentity(point.networkId, point.accountingAssetIdentity);
  if (!assetIdentity || !assetIdentity.startsWith('NATIVE:')) return false;
  const symbol = canonicalSymbol(balance.assetSymbol);
  return symbol === 'ETH'
    && point.basisEffect === 'GAS_ONLY'
    && uncovered <= 0.0015;
}

function uncoveredReason(point, balance, current, covered) {
  const hasUncovered = covered < current;
  if (!point) return 'missing_replay_point';
  const incomplete = point.hasIncompleteHistoryAfter === true;
  const unresolved = point.hasUnresolvedFlagsAfter === true;
  if (!hasUncovered) return incomplete || unresolved ? 'history_flags' : null;
  const uncovered = Math.max(current - covered, 0);
  if (isNativeGasResidualCandidate(point, balance, uncovered)) {
    return 'native_gas_residual';
  }
  if (!incomplete && !unresolved && isYieldAccrualCandidate(point)) {
    return 'yield_accrual';
  }
  return 'coverage_gap';
}

function applyCoverage(row, current, covered, uncovered, reason) {
  row.current += current;
  row.covered += covered;
  row.uncovered += uncovered;
  if (uncovered > 1e-18 || reason === 'history_flags') {
    row.dirtyBuckets += 1;
  }
  if (reason === 'yield_accrual' || reason === 'native_gas_residual') {
    row.nonBlockingUncovered += uncovered;
    row.nonBlockingBuckets += 1;
  } else {
    row.blockingUncovered += uncovered;
  }
}

for (const [key, balance] of Object.entries(balanceByBucket)) {
  const symbol = canonicalSymbol(balance.assetSymbol);
  const current = toNum(balance.quantity);
  if (!(current > 0)) continue;
  const point = latestPointByBucket[key] || null;
  const covered = point ? Math.min(toNum(point.basisBackedQuantityAfter), current) : 0;
  const uncovered = Math.max(current - covered, 0);
  const reason = uncoveredReason(point, balance, current, covered);

  if (exactCoverage[symbol]) {
    applyCoverage(exactCoverage[symbol], current, covered, uncovered, reason);
    if (reason === 'native_gas_residual') {
      exactCoverage[symbol].nativeGasResidualPolicy = {
        status: 'NON_BLOCKING_NATIVE_GAS_RESIDUAL',
        threshold: 0.0015
      };
    }
  }

  const familyId = point && point.accountingFamilyIdentity ? point.accountingFamilyIdentity : 'FAMILY:' + symbol;
  if (familyCoverage[familyId]) {
    applyCoverage(familyCoverage[familyId], current, covered, uncovered, reason);
    if (reason === 'native_gas_residual') {
      familyCoverage[familyId].nativeGasResidualPolicy = {
        status: 'NON_BLOCKING_NATIVE_GAS_RESIDUAL',
        threshold: 0.0015
      };
    }
  }
}

const familyBtcDustThreshold = 0.0000001;
if (exactCoverage.BTC && familyCoverage['FAMILY:BTC']) {
  const exactBtcClean = exactCoverage.BTC.blockingUncovered <= 1e-18;
  const familyBtc = familyCoverage['FAMILY:BTC'];
  if (exactBtcClean
      && familyBtc.blockingUncovered > 0
      && familyBtc.blockingUncovered <= familyBtcDustThreshold) {
    familyBtc.nonBlockingUncovered += familyBtc.blockingUncovered;
    familyBtc.blockingUncovered = 0;
    familyBtc.nonBlockingBuckets += 1;
    familyBtc.dustPolicy = {
      status: 'NON_BLOCKING_FAMILY_ONLY_DUST',
      threshold: familyBtcDustThreshold
    };
  }
}

const subUnitDustThreshold = 0.000000001;
function applySubUnitDustPolicy(row) {
  if (!row || !(row.blockingUncovered > 0) || row.blockingUncovered > subUnitDustThreshold) {
    return;
  }
  row.nonBlockingUncovered += row.blockingUncovered;
  row.blockingUncovered = 0;
  row.nonBlockingBuckets += 1;
  row.subUnitDustPolicy = {
    status: 'NON_BLOCKING_SUB_UNIT_DUST',
    threshold: subUnitDustThreshold
  };
}

for (const row of Object.values(exactCoverage)) {
  applySubUnitDustPolicy(row);
}
for (const row of Object.values(familyCoverage)) {
  applySubUnitDustPolicy(row);
}

for (const row of Object.values(exactCoverage)) {
  row.coverageRatio = row.current > 0 ? row.covered / row.current : 1;
  row.blockingCoverageRatio = row.current > 0 ? (row.current - row.blockingUncovered) / row.current : 1;
}
for (const row of Object.values(familyCoverage)) {
  row.coverageRatio = row.current > 0 ? row.covered / row.current : 1;
  row.blockingCoverageRatio = row.current > 0 ? (row.current - row.blockingUncovered) / row.current : 1;
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

const terminalResolutionStates = [
  'TERMINAL_METADATA_ONLY',
  'IRREDUCIBLE_EVIDENCE_MISSING',
  'UNSUPPORTED_SCOPE'
];
const supportedProtocolTypes = [
  'SWAP',
  'BRIDGE_OUT',
  'BRIDGE_IN',
  'LENDING_DEPOSIT',
  'LENDING_WITHDRAW',
  'BORROW',
  'REPAY',
  'VAULT_DEPOSIT',
  'VAULT_WITHDRAW',
  'LP_ENTRY',
  'LP_EXIT',
  'LP_ENTRY_REQUEST',
  'LP_EXIT_REQUEST',
  'LP_ENTRY_SETTLEMENT',
  'LP_EXIT_SETTLEMENT',
  'LP_EXIT_PARTIAL',
  'LP_EXIT_FINAL',
  'LP_FEE_CLAIM',
  'REWARD_CLAIM',
  'STAKING_DEPOSIT',
  'STAKING_WITHDRAW',
  'STAKING_WITHDRAW_REQUEST',
  'PROTOCOL_CUSTODY_DEPOSIT',
  'PROTOCOL_CUSTODY_WITHDRAW'
];
const supportedCounterpartyTypes = supportedProtocolTypes.concat([
  'EXTERNAL_TRANSFER_IN',
  'EXTERNAL_TRANSFER_OUT',
  'INTERNAL_TRANSFER'
]);

const protocolResolutionCoverage = db.getCollection('normalized_transactions').aggregate([
  {
    \$match: {
      status: 'CONFIRMED',
      source: 'ON_CHAIN',
      type: {\$in: supportedProtocolTypes}
    }
  },
  {
    \$project: {
      type: 1,
      recoverableGap: {
        \$and: [
          {\$in: [{\$ifNull: ['\$protocolName', null]}, [null, '']]},
          {\$not: [{\$in: ['\$protocolResolutionState', terminalResolutionStates]}]}
        ]
      },
      terminalGap: {\$in: ['\$protocolResolutionState', terminalResolutionStates]},
      resolved: {
        \$or: [
          {\$not: [{\$in: [{\$ifNull: ['\$protocolName', null]}, [null, '']]}]},
          {\$in: ['\$protocolResolutionState', ['RESOLVED_EXACT', 'RESOLVED_FAMILY']]}
        ]
      }
    }
  },
  {
    \$group: {
      _id: null,
      recoverableProtocolGaps: {\$sum: {\$cond: ['\$recoverableGap', 1, 0]}},
      terminalProtocolGaps: {\$sum: {\$cond: ['\$terminalGap', 1, 0]}},
      resolvedProtocolRows: {\$sum: {\$cond: ['\$resolved', 1, 0]}},
      supportedProtocolRows: {\$sum: 1}
    }
  }
]).toArray()[0] || {
  recoverableProtocolGaps: 0,
  terminalProtocolGaps: 0,
  resolvedProtocolRows: 0,
  supportedProtocolRows: 0
};

const counterpartyResolutionCoverage = db.getCollection('normalized_transactions').aggregate([
  {
    \$match: {
      status: 'CONFIRMED',
      source: 'ON_CHAIN',
      type: {\$in: supportedCounterpartyTypes}
    }
  },
  {
    \$project: {
      type: 1,
      recoverableGap: {
        \$and: [
          {\$in: [{\$ifNull: ['\$counterpartyAddress', null]}, [null, '']]},
          {\$not: [{\$in: ['\$counterpartyResolutionState', terminalResolutionStates]}]}
        ]
      },
      terminalGap: {\$in: ['\$counterpartyResolutionState', terminalResolutionStates]},
      resolved: {
        \$or: [
          {\$not: [{\$in: [{\$ifNull: ['\$counterpartyAddress', null]}, [null, '']]}]},
          {\$in: ['\$counterpartyResolutionState', ['RESOLVED_EXACT', 'RESOLVED_FAMILY']]}
        ]
      }
    }
  },
  {
    \$group: {
      _id: null,
      recoverableCounterpartyGaps: {\$sum: {\$cond: ['\$recoverableGap', 1, 0]}},
      terminalCounterpartyGaps: {\$sum: {\$cond: ['\$terminalGap', 1, 0]}},
      resolvedCounterpartyRows: {\$sum: {\$cond: ['\$resolved', 1, 0]}},
      supportedCounterpartyRows: {\$sum: 1}
    }
  }
]).toArray()[0] || {
  recoverableCounterpartyGaps: 0,
  terminalCounterpartyGaps: 0,
  resolvedCounterpartyRows: 0,
  supportedCounterpartyRows: 0
};

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
  protocolResolutionCoverage,
  counterpartyResolutionCoverage,
  needsReviewBySourceType,
  pipelineState
}, null, 2));
EOF
