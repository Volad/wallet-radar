// Deep coverage/AVCO audit (prod latest) — READ-ONLY for Mongo.
// Writes results JSON to local filesystem under results/audit/.
//
// Usage (example):
//   docker exec walletradar-mongodb-prod mongosh walletradar --quiet --file scripts/audit/prod-coverage-avco-audit.mongosh.js
//
// Optional env vars:
//   AUDIT_UNIVERSE_ID=<accountingUniverseId>  (defaults to first user_sessions doc)
//   AUDIT_TOP_N=50                           (defaults to 50)
//   AUDIT_MIN_UNCOV_USD=10                   (defaults to 10)
//   AUDIT_OUT_PATH=results/audit/<name>.json (auto timestamp if omitted)
//
// NOTE: This script assumes it's executed from repo root so relative paths resolve.

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

function toNum(v) {
  if (v == null) return 0;
  if (typeof v === "number") return v;
  if (typeof v === "string") return Number(v);
  if (v.$numberDecimal) return parseFloat(v.$numberDecimal);
  if (v.toString) return parseFloat(v.toString());
  return 0;
}

function round(n, dp = 6) {
  const x = Number(n);
  if (!isFinite(x)) return null;
  const f = Math.pow(10, dp);
  return Math.round(x * f) / f;
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

function safeDivide(a, b) {
  const x = toNum(a);
  const y = toNum(b);
  if (!isFinite(x) || !isFinite(y) || y === 0) return null;
  return x / y;
}

function min(a, b) {
  return Math.min(toNum(a), toNum(b));
}

function nonNeg(x) {
  const n = toNum(x);
  return n < 0 ? 0 : n;
}

const TOP_N = parseInt(env("AUDIT_TOP_N", "50"), 10);
const MIN_UNCOV_USD = parseFloat(env("AUDIT_MIN_UNCOV_USD", "10"));

const session = db.user_sessions.findOne({}, { accountingUniverseId: 1, _id: 1, integrations: 1 });
if (!session || !session._id) {
  throw new Error("No user_sessions found.");
}
const sessionId = String(session._id);
const defaultUniverseId = session.accountingUniverseId
  ? String(session.accountingUniverseId)
  : sessionId;
const universeId = env("AUDIT_UNIVERSE_ID", defaultUniverseId);

print("=== coverage/AVCO audit ===");
print("  sessionId=" + sessionId);
print("  universeId=" + universeId);

// Price map: best-effort from recent priced flows + peg aliases.
const priceMap = {};
const pegs = {
  USDT: 1.0,
  USDC: 1.0,
  USDE: 1.0,
  USDS: 1.0,
  DAI: 1.0,
  FDUSD: 1.0,
  PYUSD: 1.0,
  TUSD: 1.0,
  USD1: 1.0,
  GHO: 1.0,
  AAVAUSDC: 1.0,
  AMANUSDC: 1.0,
  AARBUSDC: 1.0,
  AETHUSDC: 1.0,
  AOPTUSDC: 1.0,
  ABASUSDC: 1.0,
  AZKSUSDC: 1.0,
};
Object.keys(pegs).forEach((k) => (priceMap[k] = pegs[k]));

function aliasOf(sym) {
  const u = (sym || "").toUpperCase();
  const map = {
    WETH: "ETH",
    CMETH: "ETH",
    METH: "ETH",
    STETH: "ETH",
    WSTETH: "ETH",
    RETH: "ETH",
    CBETH: "ETH",
    WEETH: "ETH",
    EETH: "ETH",
    WMNT: "MNT",
    AMANWMNT: "MNT",
    AMANMNT: "MNT",
    WBTC: "BTC",
  };
  return map[u] || null;
}

function spot(sym) {
  if (!sym) return null;
  const u = sym.toUpperCase();
  if (priceMap[u] != null) return priceMap[u];
  const a = aliasOf(u);
  if (a && priceMap[a] != null) return priceMap[a];
  // Generic lending receipts: strip leading A / AMAN / AAVA / AARB / AETH etc if base exists.
  if (/^(AMAN|AAVA|AARB|AETH|ABAS|AZKS|AOPT|A)/.test(u)) {
    const stripped = u.replace(/^(AMAN|AAVA|AARB|AETH|ABAS|AZKS|AOPT|A)/, "");
    if (priceMap[stripped] != null) return priceMap[stripped];
    const a2 = aliasOf(stripped);
    if (a2 && priceMap[a2] != null) return priceMap[a2];
  }
  return null;
}

// Seed recent spots from normalized flows.
db.normalized_transactions
  .aggregate(
    [
      { $match: { "flows.unitPriceUsd": { $exists: true, $ne: null } } },
      { $unwind: "$flows" },
      {
        $match: {
          "flows.assetSymbol": { $exists: true, $ne: null },
          "flows.unitPriceUsd": { $exists: true, $ne: null },
          "flows.priceSource": { $exists: true, $ne: null, $nin: ["UNKNOWN", "undefined"] },
        },
      },
      { $sort: { blockTimestamp: -1 } },
      { $group: { _id: { $toUpper: "$flows.assetSymbol" }, unitPriceUsd: { $first: "$flows.unitPriceUsd" } } },
    ],
    { allowDiskUse: true }
  )
  .toArray()
  .forEach((r) => {
    const u = r._id;
    const v = toNum(r.unitPriceUsd);
    if (u && isFinite(v) && v > 0 && priceMap[u] == null) {
      priceMap[u] = v;
    }
  });

print("  priceMapSize=" + Object.keys(priceMap).length);

// Latest ledger point per cluster.
const latestLedger = db.asset_ledger_points
  .aggregate(
    [
      { $match: { accountingUniverseId: universeId } },
      { $sort: { replaySequence: -1 } },
      {
        $group: {
          _id: { w: "$walletAddress", n: "$networkId", a: "$assetSymbol" },
          wallet: { $first: "$walletAddress" },
          network: { $first: "$networkId" },
          asset: { $first: "$assetSymbol" },
          accountingFamilyIdentity: { $first: "$accountingFamilyIdentity" },
          accountingAssetIdentity: { $first: "$accountingAssetIdentity" },
          familyDisplaySymbol: { $first: "$familyDisplaySymbol" },
          qty: { $first: "$quantityAfter" },
          backed: { $first: "$basisBackedQuantityAfter" },
          uncov: { $first: "$uncoveredQuantityAfter" },
          basisUsd: { $first: "$totalCostBasisAfterUsd" },
          avco: { $first: "$avcoAfterUsd" },
          lastTx: { $first: "$normalizedTransactionId" },
          lastType: { $first: "$normalizedType" },
          ts: { $first: "$blockTimestamp" },
          replaySequence: { $first: "$replaySequence" },
          incomplete: { $first: "$hasIncompleteHistoryAfter" },
          unresolved: { $first: "$hasUnresolvedFlagsAfter" },
        },
      },
    ],
    { allowDiskUse: true }
  )
  .toArray()
  .map((r) => {
    const qty = toNum(r.qty);
    const backed = toNum(r.backed);
    const uncov = toNum(r.uncov);
    const spotUsd = spot(r.asset);
    const uncovUsd = spotUsd == null ? null : uncov * spotUsd;
    const covPct = qty > 0 ? backed / qty : null;
    const basisPerCovered = backed > 0 ? safeDivide(r.basisUsd, backed) : null;
    const avcoSuspicious = r.avco != null && spotUsd != null && toNum(r.avco) > 0 && toNum(r.avco) < spotUsd * 0.2;
    return {
      wallet: r.wallet,
      network: r.network,
      asset: r.asset,
      accountingFamilyIdentity: r.accountingFamilyIdentity,
      accountingAssetIdentity: r.accountingAssetIdentity,
      familyDisplaySymbol: r.familyDisplaySymbol,
      qty: qty,
      basisBackedQty: backed,
      uncoveredQty: uncov,
      totalCostBasisUsd: toNum(r.basisUsd),
      avcoAfterUsd: r.avco == null ? null : toNum(r.avco),
      basisPerCoveredUsd: basisPerCovered == null ? null : basisPerCovered,
      coveragePct: covPct == null ? null : covPct,
      spotUsd: spotUsd,
      uncoveredUsd: uncovUsd,
      lastTx: r.lastTx,
      lastType: r.lastType,
      ts: r.ts,
      replaySequence: r.replaySequence,
      hasIncompleteHistory: !!r.incomplete,
      hasUnresolvedFlags: !!r.unresolved,
      flags: {
        zombie: qty <= 1e-9 && uncov > 1e-9,
        invariant_uncov_gt_qty: uncov - qty > 1e-9,
        invariant_backed_gt_qty: backed - qty > 1e-9,
        avco_suspicious_low: !!avcoSuspicious,
      },
    };
  });

// Live balances inventory from on_chain_balances (session-scoped) + bybit_live_balances (integration-scoped).
const liveClusters = [];
db.on_chain_balances
  .find({ sessionId: sessionId, quantity: { $gt: 0 } }, { walletAddress: 1, networkId: 1, assetSymbol: 1, quantity: 1 })
  .forEach((b) => {
    liveClusters.push({
      wallet: b.walletAddress,
      network: b.networkId,
      asset: b.assetSymbol,
      liveQty: toNum(b.quantity),
      source: "on_chain_balances",
    });
  });
const integrationIds = (session.integrations || [])
  .map((i) => (i && i.integrationId ? String(i.integrationId) : null))
  .filter((x) => x);
if (integrationIds.length > 0) {
  db.bybit_live_balances
    .find({ integrationId: { $in: integrationIds } }, { integrationId: 1, assetSymbol: 1, umbrellaQty: 1 })
    .forEach((b) => {
      liveClusters.push({
        wallet: "BYBIT:" + String(b.integrationId || "").replace(/^BYBIT-/, ""),
        network: null,
        asset: b.assetSymbol,
        liveQty: toNum(b.umbrellaQty),
        source: "bybit_live_balances",
      });
    });
}

const liveKey = (c) => `${c.wallet}|${c.network || ""}|${(c.asset || "").toUpperCase()}`;
const ledgerByKey = {};
latestLedger.forEach((l) => {
  ledgerByKey[liveKey(l)] = l;
});

const active = liveClusters
  .map((c) => {
    const k = liveKey(c);
    const l = ledgerByKey[k];
    const liveQty = toNum(c.liveQty);
    if (!l) {
      return {
        wallet: c.wallet,
        network: c.network,
        asset: c.asset,
        liveQty,
        issue: "missing_replay_point",
        stage: "replay",
        uncoveredQty: liveQty,
        basisBackedQty: 0,
        coveragePct: 0,
        spotUsd: spot(c.asset),
        uncoveredUsd: spot(c.asset) == null ? null : liveQty * spot(c.asset),
        evidence: { source: c.source },
      };
    }
    const covered = min(liveQty, l.basisBackedQty);
    const uncov = nonNeg(liveQty - covered);
    const covPct = liveQty > 0 ? covered / liveQty : null;
    return {
      wallet: c.wallet,
      network: c.network,
      asset: c.asset,
      liveQty,
      ledgerQty: l.qty,
      basisBackedQty: l.basisBackedQty,
      uncoveredQty: uncov,
      coveragePct: covPct,
      totalCostBasisUsd: l.totalCostBasisUsd,
      avcoAfterUsd: l.avcoAfterUsd,
      basisPerCoveredUsd: l.basisPerCoveredUsd,
      spotUsd: l.spotUsd,
      uncoveredUsd: l.spotUsd == null ? null : uncov * l.spotUsd,
      lastTx: l.lastTx,
      lastType: l.lastType,
      flags: l.flags,
      evidence: {
        source: c.source,
        ledgerFamily: l.accountingFamilyIdentity,
        ledgerAssetIdentity: l.accountingAssetIdentity,
        hasIncompleteHistory: l.hasIncompleteHistory,
        hasUnresolvedFlags: l.hasUnresolvedFlags,
      },
    };
  })
  .filter((r) => r.liveQty > 0);

// Stage classification heuristic (deterministic rules; no time windows).
function classifyStage(row) {
  if (row.issue === "missing_replay_point") return { stage: "replay", reason: "no_asset_ledger_point" };
  if (row.flags && (row.flags.invariant_uncov_gt_qty || row.flags.invariant_backed_gt_qty)) {
    return { stage: "replay", reason: "ledger_invariant_violation" };
  }
  if (row.evidence && (row.evidence.hasIncompleteHistory || row.evidence.hasUnresolvedFlags)) {
    return { stage: "normalization/linking", reason: "position_flags_set" };
  }
  if (row.lastType && String(row.lastType).startsWith("BRIDGE_")) {
    return { stage: "linking", reason: "bridge_tail_uncovered" };
  }
  if (row.lastType && String(row.lastType).includes("TRANSFER") && row.uncoveredQty > 0) {
    return { stage: "linking/replay", reason: "transfer_tail_uncovered" };
  }
  if (row.uncoveredQty > 0 && row.spotUsd == null) {
    return { stage: "pricing/catalog", reason: "no_spot_quote_for_asset" };
  }
  if (row.uncoveredQty > 0) {
    return { stage: "pricing_or_carry", reason: "covered_less_than_live" };
  }
  if (row.flags && row.flags.avco_suspicious_low) {
    return { stage: "replay_or_pricing", reason: "avco_low_vs_spot" };
  }
  return { stage: "ok", reason: "covered" };
}

active.forEach((r) => {
  const c = classifyStage(r);
  r.stage = c.stage;
  r.stageReason = c.reason;
});

// Rank by uncoveredUsd (priced) then by uncoveredQty.
const priced = active.filter((r) => r.uncoveredUsd != null && r.uncoveredUsd >= MIN_UNCOV_USD);
priced.sort((a, b) => toNum(b.uncoveredUsd) - toNum(a.uncoveredUsd));
const topUncoveredUsd = priced.slice(0, TOP_N);

const lowCoverage = active.filter((r) => r.coveragePct != null && r.coveragePct < 0.5);
lowCoverage.sort((a, b) => toNum(b.uncoveredUsd) - toNum(a.uncoveredUsd));
const topLowCoverage = lowCoverage.slice(0, TOP_N);

const avcoSuspicious = active.filter((r) => r.flags && r.flags.avco_suspicious_low);
avcoSuspicious.sort((a, b) => (toNum(a.avcoAfterUsd) || 0) - (toNum(b.avcoAfterUsd) || 0));
const topAvcoSuspicious = avcoSuspicious.slice(0, TOP_N);

const zombies = active.filter((r) => r.flags && r.flags.zombie);
zombies.sort((a, b) => toNum(b.uncoveredUsd) - toNum(a.uncoveredUsd));

const totals = {
  activeClusters: active.length,
  pricedUncoveredClusters: priced.length,
  totalUncoveredUsd: round(priced.reduce((s, r) => s + toNum(r.uncoveredUsd), 0), 2),
  top10UncoveredUsd: round(priced.slice(0, 10).reduce((s, r) => s + toNum(r.uncoveredUsd), 0), 2),
  top20UncoveredUsd: round(priced.slice(0, 20).reduce((s, r) => s + toNum(r.uncoveredUsd), 0), 2),
  zombieCount: zombies.length,
};

function summarizeRow(r) {
  return {
    wallet: r.wallet,
    network: r.network,
    asset: r.asset,
    liveQty: round(r.liveQty, 12),
    basisBackedQty: round(r.basisBackedQty, 12),
    uncoveredQty: round(r.uncoveredQty, 12),
    coveragePct: r.coveragePct == null ? null : round(r.coveragePct * 100, 2),
    spotUsd: r.spotUsd == null ? null : round(r.spotUsd, 8),
    uncoveredUsd: r.uncoveredUsd == null ? null : round(r.uncoveredUsd, 2),
    totalCostBasisUsd: r.totalCostBasisUsd == null ? null : round(r.totalCostBasisUsd, 8),
    avcoAfterUsd: r.avcoAfterUsd == null ? null : round(r.avcoAfterUsd, 8),
    basisPerCoveredUsd: r.basisPerCoveredUsd == null ? null : round(r.basisPerCoveredUsd, 8),
    lastType: r.lastType,
    lastTx: r.lastTx,
    stage: r.stage,
    stageReason: r.stageReason,
    evidence: r.evidence,
    flags: r.flags,
  };
}

const output = {
  generatedAt: new Date().toISOString(),
  sessionId,
  universeId,
  params: { TOP_N, MIN_UNCOV_USD },
  totals,
  topUncoveredUsd: topUncoveredUsd.map(summarizeRow),
  topLowCoverage: topLowCoverage.map(summarizeRow),
  topAvcoSuspicious: topAvcoSuspicious.map(summarizeRow),
  zombies: zombies.slice(0, TOP_N).map(summarizeRow),
};

const outDir = path.join("results", "audit");
ensureDir(outDir);
const outPath = env("AUDIT_OUT_PATH", path.join(outDir, `coverage-avco-${nowIsoCompact()}.json`));
fs.writeFileSync(outPath, JSON.stringify(output, null, 2), { encoding: "utf-8" });

print("");
print("=== Summary ===");
print("  activeClusters=" + totals.activeClusters);
print("  pricedUncoveredClusters=" + totals.pricedUncoveredClusters);
print("  totalUncoveredUsd=$" + totals.totalUncoveredUsd);
print("  top10UncoveredUsd=$" + totals.top10UncoveredUsd);
print("  zombieCount=" + totals.zombieCount);
print("  wrote " + outPath);

