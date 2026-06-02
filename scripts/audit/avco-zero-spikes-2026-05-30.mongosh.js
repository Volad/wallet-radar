// Zero-AVCO spike audit — Phase 1 (read-only)
// Usage: docker exec -i walletradar-mongodb-prod mongosh walletradar --quiet \
//   < scripts/audit/avco-zero-spikes-2026-05-30.mongosh.js

const fs = require("fs");
const path = require("path");

const db = db.getSiblingDB("walletradar");

function env(key, fallback) {
  try {
    const v = process.env[key];
    return v == null || String(v).trim() === "" ? fallback : String(v);
  } catch (e) {
    return fallback;
  }
}

function n(v) {
  if (v == null) return 0;
  if (typeof v === "number") return v;
  if (typeof v === "string") return Number(v);
  if (v.$numberDecimal) return parseFloat(v.$numberDecimal);
  if (v.toString) return parseFloat(v.toString());
  return 0;
}

function round(x, dp) {
  const f = Math.pow(10, dp || 6);
  return Math.round(n(x) * f) / f;
}

const SESSION_ID = env("AUDIT_SESSION_ID", "df5e69cc-a0c0-4910-8b7d-74488fa266e2");
const session = db.user_sessions.findOne({ _id: SESSION_ID }, { accountingUniverseId: 1, integrations: 1 });
const universeId = env(
  "AUDIT_UNIVERSE_ID",
  session && session.accountingUniverseId ? String(session.accountingUniverseId) : SESSION_ID
);

const ETH_FAMILY = "FAMILY:ETH";
const SPOT_ETH_SYMBOLS = ["ETH", "WETH", "CMETH", "METH", "STETH", "WEETH", "WSTETH", "CBETH", "RETH", "EETH"];
const SPOT_EXCLUDED = new Set(["BBSOL"]);

function isEthFamilySpot(sym) {
  if (!sym) return false;
  const s = String(sym).toUpperCase();
  if (String(sym).startsWith("LP-RECEIPT:")) return false;
  if (SPOT_EXCLUDED.has(s)) return false;
  if (SPOT_ETH_SYMBOLS.includes(s)) return true;
  if (s.startsWith("A") && s.endsWith("ETH")) return true;
  return false;
}

function avcoFromPoint(p) {
  const bb = n(p.basisBackedQuantityAfter);
  const basis = n(p.totalCostBasisAfterUsd != null ? p.totalCostBasisAfterUsd : p.totalCostBasisUsdAfter);
  if (bb > 1e-12 && basis > 0) return basis / bb;
  const av = p.avcoAfterUsd != null ? n(p.avcoAfterUsd) : null;
  if (av != null && av > 0) return av;
  return null;
}

function perWalletAvco(p) {
  return p.perWalletAvco != null ? n(p.perWalletAvco) : avcoFromPoint(p);
}

// --- A. Zero-AVCO spike scan ---
const allEthFamily = db.asset_ledger_points
  .find({ accountingUniverseId: universeId, accountingFamilyIdentity: ETH_FAMILY })
  .sort({ replaySequence: 1 })
  .toArray();

let famQ = 0,
  famU = 0,
  famB = 0;
const zeroDrops = [];
const qtyZeroRecoveries = [];
let prevQty = 0,
  prevAvco = null;

for (const p of allEthFamily) {
  const qd = n(p.quantityDelta);
  const ud = n(p.uncoveredQuantityDelta);
  const bd = n(p.costBasisDeltaUsd);
  famQ += qd;
  famU += ud;
  famB += bd;
  const covQ = famQ - famU;
  const famAvco = covQ > 1e-12 ? famB / covQ : null;

  const qtyAfter = n(p.quantityAfter);
  const bbAfter = n(p.basisBackedQuantityAfter);
  const pwAvco = perWalletAvco(p);
  const sym = p.assetSymbol;

  if (isEthFamilySpot(sym)) {
    // Bug: qty>0 but avco=0 or near-zero
    if (bbAfter > 1e-9 && (pwAvco == null || pwAvco < 1)) {
      zeroDrops.push({
        kind: "BUG_QTY_GT0_AVCO_ZERO",
        replaySequence: p.replaySequence,
        txId: p.normalizedTransactionId,
        txHash: p.txHash,
        wallet: p.walletAddress,
        symbol: sym,
        basisEffect: p.basisEffect,
        normalizedType: p.normalizedType,
        qtyAfter: round(qtyAfter, 12),
        bbAfter: round(bbAfter, 12),
        perWalletAvco: pwAvco == null ? null : round(pwAvco, 4),
        avcoAfterUsd: p.avcoAfterUsd != null ? round(p.avcoAfterUsd, 4) : null,
        costBasisDeltaUsd: round(bd, 4),
        uncoveredQtyAfter: round(n(p.uncoveredQuantityAfter), 12),
        blockTimestamp: p.blockTimestamp,
        famAvcoAfter: famAvco == null ? null : round(famAvco, 4),
      });
    }
    // Family-level zero drop while covered qty > 0
    if (covQ > 1e-9 && famAvco != null && famAvco < 1 && prevAvco != null && prevAvco > 100) {
      zeroDrops.push({
        kind: "BUG_FAMILY_AVCO_DROP_TO_ZERO",
        replaySequence: p.replaySequence,
        txId: p.normalizedTransactionId,
        txHash: p.txHash,
        wallet: p.walletAddress,
        symbol: sym,
        basisEffect: p.basisEffect,
        normalizedType: p.normalizedType,
        famAvcoBefore: round(prevAvco, 4),
        famAvcoAfter: round(famAvco, 4),
        famCoveredQty: round(covQ, 12),
        costBasisDeltaUsd: round(bd, 4),
        blockTimestamp: p.blockTimestamp,
      });
    }
    // qty goes to 0 then recovers incorrectly
    if (prevQty > 1e-9 && qtyAfter <= 1e-9 && qd < -1e-9) {
      qtyZeroRecoveries.push({
        kind: "QTY_TO_ZERO",
        replaySequence: p.replaySequence,
        txId: p.normalizedTransactionId,
        wallet: p.walletAddress,
        symbol: sym,
        qtyAfter: round(qtyAfter, 12),
        basisEffect: p.basisEffect,
      });
    }
    prevAvco = famAvco;
    prevQty = qtyAfter;
  }
}

// --- B. Coverage vs AVCO ---
function familyTail() {
  const byWallet = {};
  const pts = db.asset_ledger_points
    .find({ accountingUniverseId: universeId, accountingFamilyIdentity: ETH_FAMILY })
    .sort({ replaySequence: -1 })
    .toArray();
  const seen = new Set();
  let totalQty = 0,
    totalBb = 0,
    totalUncov = 0,
    totalBasis = 0;
  for (const p of pts) {
    const k = `${p.walletAddress}|${p.assetSymbol}`;
    if (seen.has(k)) continue;
    seen.add(k);
    if (!isEthFamilySpot(p.assetSymbol)) continue;
    const q = n(p.quantityAfter);
    const bb = n(p.basisBackedQuantityAfter);
    const u = n(p.uncoveredQuantityAfter);
    const b = n(p.totalCostBasisAfterUsd != null ? p.totalCostBasisAfterUsd : p.totalCostBasisUsdAfter);
    totalQty += q;
    totalBb += bb;
    totalUncov += u;
    totalBasis += b;
    byWallet[k] = { wallet: p.walletAddress, symbol: p.assetSymbol, q, bb, u, b, avco: bb > 0 ? b / bb : null };
  }
  const coveredAvco = totalBb > 0 ? totalBasis / totalBb : null;
  return { totalQty, totalBb, totalUncov, totalBasis, coveredAvco, byWallet };
}

const tail = familyTail();

// Live ETH from on_chain + bybit
let liveEthQty = 0;
db.on_chain_balances
  .find({ sessionId: SESSION_ID, assetSymbol: { $in: ["ETH", "WETH"] }, quantity: { $gt: 0 } })
  .forEach((b) => {
    liveEthQty += n(b.quantity);
  });
db.bybit_live_balances
  .find({ integrationId: "BYBIT-33625378", assetSymbol: { $in: ["ETH", "CMETH", "METH"] } })
  .forEach((b) => {
    liveEthQty += n(b.umbrellaQty);
  });

// --- C. Suspicious tx deep dive ---
const SUSPICIOUS = [
  "convert:BYBIT-33625378:TRANSACTION_LOG:1018593482554086883672064_ETH336253780|BYBIT-33625378:TRANSACTION_LOG:1018593482554086883672064_ETH336253781",
  "BYBIT-33625378:EARN_FLEXIBLE_SAVING:a67c0479-6c52-4ee5-9a21-8be216fc738f",
  "BYBIT-33625378:EARN_FLEXIBLE_SAVING:734bf6f1-3622-4747-8b4a-9b2a88eb7d8d",
  "BYBIT-33625378:EXECUTION_SPOT:2200000000707964104:base|BYBIT-33625378:EXECUTION_SPOT:2200000000707964104:quote",
  "0x6ac6fc6010af5b146194439f261c97fd1bffd1fc185ca4a5b0073bca848f8029",
  "0x258ed5c30165032d02467ca36c3f94e716bc16765d0f20ef92c71b32c353569a",
  "0x6c5bd905efe5f9c4b35110c9269e333acddab0ac051dcc418ec68ed954e41784",
];

function lookupTx(idOrHash) {
  let norm = null;
  if (idOrHash.includes("|")) {
    const parts = idOrHash.split("|");
    norm = db.normalized_transactions.find({ _id: { $in: parts } }).toArray();
  } else if (idOrHash.startsWith("0x")) {
    norm = db.normalized_transactions.find({ txHash: idOrHash }).toArray();
    if (norm.length === 0) {
      norm = db.normalized_transactions.find({ _id: idOrHash }).toArray();
    }
  } else {
    norm = db.normalized_transactions.find({ _id: idOrHash }).toArray();
    if (norm.length === 0) {
      norm = db.normalized_transactions.find({ _id: { $regex: idOrHash } }).toArray();
    }
  }
  const ledgerFilter = idOrHash.includes("|")
    ? { normalizedTransactionId: { $in: idOrHash.split("|") } }
    : idOrHash.startsWith("0x")
      ? { txHash: idOrHash }
      : { normalizedTransactionId: { $regex: idOrHash.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") } };
  const ledger = db.asset_ledger_points
    .find({ accountingUniverseId: universeId, ...ledgerFilter })
    .sort({ replaySequence: 1 })
    .toArray();
  return {
    query: idOrHash,
    normalized: norm.map((t) => ({
      _id: t._id,
      type: t.type,
      status: t.status,
      source: t.source,
      walletAddress: t.walletAddress,
      networkId: t.networkId,
      blockTimestamp: t.blockTimestamp,
      correlationId: t.correlationId,
      continuityCandidate: t.continuityCandidate,
      flows: (t.flows || []).map((f) => ({
        role: f.role,
        assetSymbol: f.assetSymbol,
        quantity: n(f.quantity),
        unitPriceUsd: f.unitPriceUsd != null ? n(f.unitPriceUsd) : null,
        priceSource: f.priceSource,
        direction: f.direction,
      })),
    })),
    ledger: ledger.map((p) => ({
      replaySequence: p.replaySequence,
      wallet: p.walletAddress,
      symbol: p.assetSymbol,
      family: p.accountingFamilyIdentity,
      basisEffect: p.basisEffect,
      normalizedType: p.normalizedType,
      qtyD: round(n(p.quantityDelta), 12),
      qtyAfter: round(n(p.quantityAfter), 12),
      bbAfter: round(n(p.basisBackedQuantityAfter), 12),
      uncovAfter: round(n(p.uncoveredQuantityAfter), 12),
      uncovD: round(n(p.uncoveredQuantityDelta), 12),
      cbD: round(n(p.costBasisDeltaUsd), 4),
      avcoBefore: p.avcoBeforeUsd != null ? round(p.avcoBeforeUsd, 4) : null,
      avcoAfter: p.avcoAfterUsd != null ? round(p.avcoAfterUsd, 4) : null,
      perWalletAvco: p.perWalletAvco != null ? round(p.perWalletAvco, 4) : null,
      totalBasisAfter: round(n(p.totalCostBasisAfterUsd != null ? p.totalCostBasisAfterUsd : p.totalCostBasisUsdAfter), 4),
    })),
  };
}

const suspiciousDeepDive = SUSPICIOUS.map(lookupTx);

// --- D. EARN_FLEXIBLE_SAVING pattern ---
const earnEvents = db.asset_ledger_points
  .find({
    accountingUniverseId: universeId,
    normalizedTransactionId: /EARN_FLEXIBLE_SAVING/i,
    accountingFamilyIdentity: ETH_FAMILY,
  })
  .sort({ replaySequence: 1 })
  .toArray();

const earnPrincipalMismatches = [];
for (const p of earnEvents) {
  const effect = p.basisEffect;
  const cbD = n(p.costBasisDeltaUsd);
  const uncovD = n(p.uncoveredQuantityDelta);
  const pwAvco = perWalletAvco(p);
  if (
    (effect === "CARRY_IN" || effect === "CARRY_OUT" || effect === "REALLOCATE_IN" || effect === "REALLOCATE_OUT") &&
    (Math.abs(cbD) > 0.01 || uncovD !== 0 || (n(p.basisBackedQuantityAfter) > 1e-9 && (pwAvco == null || pwAvco < 1)))
  ) {
    earnPrincipalMismatches.push({
      txId: p.normalizedTransactionId,
      replaySequence: p.replaySequence,
      wallet: p.walletAddress,
      symbol: p.assetSymbol,
      effect,
      type: p.normalizedType,
      qtyD: round(n(p.quantityDelta), 12),
      cbD: round(cbD, 4),
      uncovD: round(uncovD, 12),
      avcoAfter: p.avcoAfterUsd != null ? round(p.avcoAfterUsd, 4) : null,
      perWalletAvco: pwAvco == null ? null : round(pwAvco, 4),
      bbAfter: round(n(p.basisBackedQuantityAfter), 12),
    });
  }
}

// Earn deposit/withdraw carry pairs
const earnCarryPairs = db.asset_ledger_points
  .find({
    accountingUniverseId: universeId,
    walletAddress: /^BYBIT:33625378/i,
    basisEffect: { $in: ["CARRY_IN", "CARRY_OUT", "REALLOCATE_IN", "REALLOCATE_OUT"] },
    normalizedType: { $in: ["LENDING_DEPOSIT", "LENDING_WITHDRAW", "INTERNAL_TRANSFER"] },
    assetSymbol: { $in: ["ETH", "CMETH", "METH"] },
  })
  .sort({ blockTimestamp: 1, replaySequence: 1 })
  .toArray()
  .map((p) => ({
    txId: p.normalizedTransactionId,
    seq: p.replaySequence,
    wallet: p.walletAddress,
    symbol: p.assetSymbol,
    effect: p.basisEffect,
    type: p.normalizedType,
    qtyD: round(n(p.quantityDelta), 12),
    cbD: round(n(p.costBasisDeltaUsd), 4),
    uncovD: round(n(p.uncoveredQuantityDelta), 12),
    avcoAfter: p.avcoAfterUsd != null ? round(p.avcoAfterUsd, 4) : null,
    ts: p.blockTimestamp,
  }));

// --- Bridge unpaired scan ---
const bridgeOuts = db.normalized_transactions
  .find({ type: "BRIDGE_OUT", status: "CONFIRMED", "flows.assetSymbol": { $in: ["ETH", "WETH"] } })
  .toArray();
const bridgeIns = db.normalized_transactions
  .find({ type: "BRIDGE_IN", status: "CONFIRMED", "flows.assetSymbol": { $in: ["ETH", "WETH"] } })
  .toArray();
const bridgeInCorr = new Set(bridgeIns.map((t) => t.correlationId).filter(Boolean));
const unpairedBridges = bridgeOuts
  .filter((t) => t.correlationId && !bridgeInCorr.has(t.correlationId))
  .map((t) => ({
    txHash: t.txHash,
    networkId: t.networkId,
    correlationId: t.correlationId,
    wallet: t.walletAddress,
    ts: t.blockTimestamp,
  }));

// Per-wallet zero avco scan (all ETH family points)
const perWalletZeroAvco = db.asset_ledger_points
  .find({
    accountingUniverseId: universeId,
    accountingFamilyIdentity: ETH_FAMILY,
    basisBackedQuantityAfter: { $gt: 1e-9 },
    $or: [{ avcoAfterUsd: { $lte: 1 } }, { avcoAfterUsd: null }],
  })
  .sort({ replaySequence: 1 })
  .toArray()
  .filter((p) => isEthFamilySpot(p.assetSymbol))
  .map((p) => ({
    seq: p.replaySequence,
    txId: p.normalizedTransactionId,
    wallet: p.walletAddress,
    symbol: p.assetSymbol,
    effect: p.basisEffect,
    bbAfter: round(n(p.basisBackedQuantityAfter), 12),
    avcoAfter: p.avcoAfterUsd != null ? round(p.avcoAfterUsd, 4) : null,
    perWalletAvco: p.perWalletAvco != null ? round(p.perWalletAvco, 4) : null,
    cbD: round(n(p.costBasisDeltaUsd), 4),
    type: p.normalizedType,
  }));

// Timeline famAvco samples — find drops >90%
let runQ = 0,
  runU = 0,
  runB = 0;
let lastFamAvco = null;
const famAvcoDrops = [];
for (const p of allEthFamily) {
  if (!isEthFamilySpot(p.assetSymbol)) continue;
  runQ += n(p.quantityDelta);
  runU += n(p.uncoveredQuantityDelta);
  runB += n(p.costBasisDeltaUsd);
  const cov = runQ - runU;
  const avco = cov > 1e-12 ? runB / cov : null;
  if (lastFamAvco != null && avco != null && lastFamAvco > 100 && avco < lastFamAvco * 0.1) {
    famAvcoDrops.push({
      replaySequence: p.replaySequence,
      txId: p.normalizedTransactionId,
      txHash: p.txHash,
      wallet: p.walletAddress,
      symbol: p.assetSymbol,
      effect: p.basisEffect,
      type: p.normalizedType,
      famAvcoBefore: round(lastFamAvco, 4),
      famAvcoAfter: round(avco, 4),
      pctDrop: round((1 - avco / lastFamAvco) * 100, 2),
      cbD: round(n(p.costBasisDeltaUsd), 4),
      qtyD: round(n(p.quantityDelta), 12),
      ts: p.blockTimestamp,
    });
  }
  if (avco != null) lastFamAvco = avco;
}

const report = {
  generatedAt: new Date().toISOString(),
  universeId,
  sessionId: SESSION_ID,
  counts: {
    ethFamilyPoints: allEthFamily.length,
    zeroDropBugCount: zeroDrops.length,
    perWalletZeroAvcoCount: perWalletZeroAvco.length,
    famAvcoDrops90pct: famAvcoDrops.length,
    earnPrincipalMismatches: earnPrincipalMismatches.length,
    unpairedBridges: unpairedBridges.length,
  },
  coverageAvcoCheck: {
    ledgerTailTotalQty: round(tail.totalQty, 6),
    ledgerTailBasisBacked: round(tail.totalBb, 6),
    ledgerTailUncovered: round(tail.totalUncov, 6),
    ledgerTailTotalBasisUsd: round(tail.totalBasis, 2),
    ledgerCoveredAvco: tail.coveredAvco == null ? null : round(tail.coveredAvco, 2),
    liveEthApproxQty: round(liveEthQty, 6),
    uiExpectedAvco: 2588,
    uiExpectedCovered: 3.123,
    uiExpectedUncovered: 0.019,
    pass:
      tail.coveredAvco != null &&
      Math.abs(tail.coveredAvco - 2588) / 2588 < 0.15 &&
      Math.abs(tail.totalBb - 3.123) < 0.05 &&
      Math.abs(tail.totalUncov - 0.019) < 0.02,
  },
  zeroDrops: zeroDrops.slice(0, 100),
  perWalletZeroAvco: perWalletZeroAvco.slice(0, 50),
  famAvcoDrops90pct: famAvcoDrops,
  suspiciousDeepDive,
  earnPrincipalMismatches,
  earnCarryPairs: earnCarryPairs.slice(0, 80),
  unpairedBridges: unpairedBridges.slice(0, 20),
};

const outDir = path.join("data", "derived");
if (!fs.existsSync(outDir)) fs.mkdirSync(outDir, { recursive: true });
const outPath = path.join(outDir, "avco-zero-spikes-2026-05-30.json");
fs.writeFileSync(outPath, JSON.stringify(report, null, 2));

printjson({
  summary: report.counts,
  coverageAvcoCheck: report.coverageAvcoCheck,
  famAvcoDrops90pct: report.famAvcoDrops90pct,
  perWalletZeroAvcoSample: report.perWalletZeroAvco.slice(0, 10),
  suspiciousCount: report.suspiciousDeepDive.map((s) => ({
    q: s.query,
    norm: s.normalized.length,
    ledger: s.ledger.length,
  })),
  outPath,
});
