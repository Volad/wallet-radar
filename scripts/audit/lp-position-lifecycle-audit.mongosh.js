// LP position lifecycle acceptance counters (read-only).
//
// Usage:
//   docker exec walletradar-mongodb-prod mongosh walletradar --quiet --file scripts/audit/lp-position-lifecycle-audit.mongosh.js
//
// Optional env:
//   AUDIT_UNIVERSE_ID=<accountingUniverseId>
//   AUDIT_OUT_PATH=results/audit/lp-position-lifecycle.json

const fs = require("fs");
const path = require("path");

const dbConn = db.getSiblingDB("walletradar");

function env(key, fallback) {
  try {
    const v = process.env[key];
    return v == null || String(v).trim() === "" ? fallback : String(v);
  } catch (e) {
    return fallback;
  }
}

function nowIsoCompact() {
  const d = new Date();
  const pad = (x) => String(x).padStart(2, "0");
  return (
    d.getUTCFullYear() +
    pad(d.getUTCMonth() + 1) +
    pad(d.getUTCDate()) +
    "T" +
    pad(d.getUTCHours()) +
    pad(d.getUTCMinutes()) +
    pad(d.getUTCSeconds()) +
    "Z"
  );
}

function ensureDir(dir) {
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
}

const universeId = env("AUDIT_UNIVERSE_ID", null);
const resolvedUniverse = universeId
  || (dbConn.user_sessions.findOne({}, { accountingUniverseId: 1 }) || {}).accountingUniverseId;

const lpTypes = [
  "LP_ENTRY",
  "LP_EXIT",
  "LP_EXIT_PARTIAL",
  "LP_EXIT_FINAL",
  "LP_FEE_CLAIM",
  "LP_ENTRY_REQUEST",
  "LP_ENTRY_SETTLEMENT",
  "LP_EXIT_REQUEST",
  "LP_EXIT_SETTLEMENT",
  "LP_POSITION_STAKE",
  "LP_POSITION_UNSTAKE",
];

const normalized = dbConn.normalized_transactions.find({
  accountingUniverseId: resolvedUniverse,
  status: "CONFIRMED",
  type: { $in: lpTypes },
}).toArray();

function hasLpReceiptFlow(doc) {
  const flows = doc.flows || [];
  return flows.some((f) => {
    const sym = (f.assetSymbol || "").toUpperCase();
    return sym.startsWith("LP-RECEIPT:");
  });
}

function isGmxShare(symbol) {
  if (!symbol) return false;
  const s = String(symbol).toUpperCase();
  return s.startsWith("GM:") || s.startsWith("GLV");
}

const byType = {};
const feeClaimMissingPosition = [];
const lpExitMissingReceipt = [];
const gmxMarketKeys = new Set();
const lpPositionKeys = new Set();
const openPositionCandidates = {};

for (const doc of normalized) {
  const type = doc.type;
  byType[type] = (byType[type] || 0) + 1;

  const corr = doc.correlationId || null;
  if (corr && corr.startsWith("lp-position:")) {
    lpPositionKeys.add(corr);
    openPositionCandidates[corr] = openPositionCandidates[corr] || { entries: 0, exits: 0, feeClaims: 0 };
    if (type === "LP_ENTRY") openPositionCandidates[corr].entries += 1;
    if (type === "LP_EXIT" || type === "LP_EXIT_PARTIAL" || type === "LP_EXIT_FINAL") openPositionCandidates[corr].exits += 1;
    if (type === "LP_FEE_CLAIM") openPositionCandidates[corr].feeClaims += 1;
  }
  if (corr && corr.startsWith("gmx-lp:")) {
    gmxMarketKeys.add(corr);
  }

  if (type === "LP_FEE_CLAIM" && (!corr || !corr.startsWith("lp-position:"))) {
    feeClaimMissingPosition.push({ txHash: doc.txHash, correlationId: corr });
  }

  if ((type === "LP_EXIT" || type === "LP_EXIT_PARTIAL" || type === "LP_EXIT_FINAL")
      && corr && corr.startsWith("lp-position:")
      && !hasLpReceiptFlow(doc)) {
    lpExitMissingReceipt.push({ txHash: doc.txHash, correlationId: corr });
  }

  if (type === "LP_ENTRY_SETTLEMENT" || type === "LP_EXIT_SETTLEMENT") {
    const flows = doc.flows || [];
    for (const f of flows) {
      if (isGmxShare(f.assetSymbol)) {
        if (!corr || !corr.startsWith("gmx-lp:")) {
          gmxMarketKeys.add("(missing-key:" + doc.txHash + ")");
        }
      }
    }
  }
}

const openLpPositions = Object.entries(openPositionCandidates)
  .filter(([_, v]) => v.entries > v.exits)
  .map(([key, v]) => ({ correlationId: key, ...v }));

const ledgerOpenReceipts = dbConn.asset_ledger_points.aggregate([
  { $match: { universeId: resolvedUniverse } },
  { $match: { assetSymbol: { $regex: /^LP-RECEIPT:/i } } },
  { $group: { _id: "$assetSymbol", qty: { $last: "$quantityAfter" } } },
  { $match: { qty: { $gt: 0 } } },
]).toArray();

const report = {
  generatedAt: new Date().toISOString(),
  universeId: resolvedUniverse,
  totals: {
    confirmedLpTransactions: normalized.length,
    byType,
    distinctLpPositionKeys: lpPositionKeys.size,
    distinctGmxMarketKeys: gmxMarketKeys.size,
    lpFeeClaimMissingPositionCorrelation: feeClaimMissingPosition.length,
    lpExitMissingReceiptFlow: lpExitMissingReceipt.length,
    openLpPositionCandidates: openLpPositions.length,
    openLpReceiptLedgerBuckets: ledgerOpenReceipts.length,
  },
  openLpPositions,
  openLpReceiptLedgerBuckets: ledgerOpenReceipts,
  samples: {
    feeClaimMissingPosition: feeClaimMissingPosition.slice(0, 20),
    lpExitMissingReceipt: lpExitMissingReceipt.slice(0, 20),
  },
};

const outPath = env(
  "AUDIT_OUT_PATH",
  path.join("results", "audit", "lp-position-lifecycle-" + nowIsoCompact() + ".json")
);
ensureDir(path.dirname(outPath));
fs.writeFileSync(outPath, JSON.stringify(report, null, 2));

print("LP position lifecycle audit written to " + outPath);
printjson(report.totals);
