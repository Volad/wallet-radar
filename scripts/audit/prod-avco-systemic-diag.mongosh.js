// Post-rebuild systemic AVCO diagnostics (read-only).
// Usage:
//   AUDIT_UNIVERSE_ID=df5e69cc-a0c0-4910-8b7d-74488fa266e2 \
//   docker exec walletradar-mongodb-prod mongosh walletradar --quiet --file scripts/audit/prod-avco-systemic-diag.mongosh.js

const db = db.getSiblingDB("walletradar");

function env(key, fallback) {
  try {
    const v = process.env[key];
    return v == null || String(v).trim() === "" ? fallback : String(v);
  } catch (e) {
    return fallback;
  }
}

const session = db.user_sessions.findOne(
  { _id: env("AUDIT_SESSION_ID", "df5e69cc-a0c0-4910-8b7d-74488fa266e2") },
  { accountingUniverseId: 1, _id: 1 }
);
const universeId = env(
  "AUDIT_UNIVERSE_ID",
  session && session.accountingUniverseId ? String(session.accountingUniverseId) : String(session._id)
);

printjson({ universeId, sessionId: session ? String(session._id) : null });

// Reference earn redemption legs
const earnRef = db.normalized_transactions.findOne(
  { _id: /1fff0ae8-d1a5-404e-b1b5-305b63a52705/ },
  { _id: 1, type: 1, status: 1, walletAddress: 1, correlationId: 1, continuityCandidate: 1,
    excludedFromAccounting: 1, flows: 1, blockTimestamp: 1 }
);
const fundRef = db.normalized_transactions.findOne(
  { _id: /ae372912/ },
  { _id: 1, type: 1, status: 1, walletAddress: 1, correlationId: 1, continuityCandidate: 1,
    excludedFromAccounting: 1, flows: 1, blockTimestamp: 1 }
);
print("=== earn reference txs ===");
printjson({ earn: earnRef, fund: fundRef });

const ethUmbrellaTail = db.asset_ledger_points.find(
  { accountingUniverseId: universeId, walletAddress: "BYBIT:33625378", assetSymbol: "ETH" },
  { sort: { blockTimestamp: -1, replaySequence: -1 }, limit: 1 }
).toArray();
print("=== ETH umbrella ledger tail ===");
printjson(ethUmbrellaTail[0] || null);

const lendingUncov = db.asset_ledger_points.countDocuments({
  accountingUniverseId: universeId,
  walletAddress: /^BYBIT:/i,
  normalizedType: "LENDING_WITHDRAW",
  basisEffect: "REALLOCATE_IN",
  uncoveredQuantityDelta: { $gt: 0 }
});
printjson({ bybitLendingWithdrawReallocateInUncov: lendingUncov });

const lpFamily = db.asset_ledger_points.countDocuments({
  accountingUniverseId: universeId,
  accountingFamilyIdentity: "FAMILY:LP_RECEIPT"
});
printjson({ familyLpReceiptPoints: lpFamily });

const unpricedLending = db.normalized_transactions.countDocuments({
  source: "BYBIT",
  type: { $in: ["LENDING_DEPOSIT", "LENDING_WITHDRAW"] },
  excludedFromAccounting: { $ne: true },
  status: "CONFIRMED",
  flows: {
    $elemMatch: {
      role: "TRANSFER",
      $or: [{ unitPriceUsd: null }, { unitPriceUsd: { $exists: false } }]
    }
  }
});
printjson({ confirmedBybitLendingMissingFlowPrice: unpricedLending });

const earnPrincipalPairs = db.normalized_transactions.countDocuments({
  correlationId: /^bybit-earn-principal-v1:/
});
printjson({ bybitEarnPrincipalCorrelationPairs: earnPrincipalPairs });
