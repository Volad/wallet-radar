// DEBUG / Cycle 15 Round 5 Phase A1 v2 — top-N active clusters with uncov, peg-driven USD
// READ-ONLY. Remove after audit.
const db = db.getSiblingDB("walletradar");

// Build spot price map from recent priced flows (best-effort).
print("=== A1.0 Building spot price map from recent flows ===");
const recentSpots = db.normalized_transactions.aggregate(
  [
    {
      $match: {
        blockTimestamp: { $gte: new Date("2026-01-01T00:00:00Z") },
        "flows.priceSource": { $exists: true, $ne: null, $nin: ["UNKNOWN", "undefined"] },
      },
    },
    { $unwind: "$flows" },
    {
      $match: {
        "flows.unitPriceUsd": { $exists: true, $ne: null },
        "flows.assetSymbol": { $exists: true, $ne: null },
      },
    },
    { $sort: { blockTimestamp: -1 } },
    {
      $group: {
        _id: { $toUpper: "$flows.assetSymbol" },
        unitPriceUsd: { $first: "$flows.unitPriceUsd" },
      },
    },
  ],
  { allowDiskUse: true }
).toArray();

const priceMap = {};
recentSpots.forEach((r) => {
  const v = Number(r.unitPriceUsd);
  if (!isNaN(v) && v > 0) priceMap[r._id] = v;
});
// hardcoded peg & alias
const pegs = {
  USDT: 1.0, USDC: 1.0, USDE: 1.0, DAI: 1.0, FDUSD: 1.0, TUSD: 1.0, BUSD: 1.0,
  AUSDT: 1.0, AUSDC: 1.0, AAVAUSDC: 1.0, AMANUSDC: 1.0, AARBUSDC: 1.0, AETHUSDC: 1.0,
  AAVAUSDT: 1.0, AMANUSDT: 1.0, AARBUSDT: 1.0, AETHUSDT: 1.0, GHO: 1.0,
};
Object.assign(priceMap, pegs);
// aliases (use base if available)
function aliasOf(sym) {
  const u = sym.toUpperCase();
  const map = {
    WETH: "ETH", CMETH: "ETH", METH: "ETH", WEETH: "ETH", STETH: "ETH", WSTETH: "ETH",
    RETH: "ETH", CBETH: "ETH", AMANWETH: "ETH", AARBWETH: "ETH", AETHWETH: "ETH",
    "A WETH": "ETH",
    WBTC: "BTC", TBTC: "BTC", AWBTC: "BTC", AETHWBTC: "BTC", ABTC: "BTC",
    WMATIC: "MATIC", AMATIC: "MATIC", AETHMATIC: "MATIC",
    WAVAX: "AVAX", SAVAX: "AVAX", AAVAX: "AVAX",
    WMNT: "MNT", AMANWMNT: "MNT", AMANMNT: "MNT",
    AARBARB: "ARB", AETHARB: "ARB",
    AAVAGHO: "GHO",
  };
  return map[u] || null;
}
print("  priceMap size after seeding: " + Object.keys(priceMap).length);
print("  ETH=" + priceMap.ETH + " BTC=" + priceMap.BTC + " AVAX=" + priceMap.AVAX + " MNT=" + priceMap.MNT + " ARB=" + priceMap.ARB);

function spot(sym) {
  if (!sym) return null;
  const u = sym.toUpperCase();
  if (priceMap[u] != null) return priceMap[u];
  const alias = aliasOf(u);
  if (alias && priceMap[alias] != null) return priceMap[alias];
  // generic Aave A* prefix → strip A
  if (/^A[A-Z]{2,}/.test(u)) {
    const stripped = u.substring(1);
    if (priceMap[stripped] != null) return priceMap[stripped];
    const aliasStrip = aliasOf(stripped);
    if (aliasStrip && priceMap[aliasStrip] != null) return priceMap[aliasStrip];
  }
  // Aave WMNT/WETH double-wrap forms
  if (/^AMAN/.test(u)) {
    const stripped = u.replace(/^AMAN/, "");
    if (priceMap[stripped] != null) return priceMap[stripped];
    const aliasStrip = aliasOf(stripped);
    if (aliasStrip && priceMap[aliasStrip] != null) return priceMap[aliasStrip];
  }
  return null;
}

print("");
print("=== A1.1 Latest point per cluster (qty>0, uncov>0) ===");
const latest = db.asset_ledger_points.aggregate(
  [
    { $sort: { replaySequence: -1 } },
    {
      $group: {
        _id: { w: "$walletAddress", n: "$networkId", a: "$assetSymbol" },
        qty: { $first: "$quantityAfter" },
        backed: { $first: "$basisBackedQuantityAfter" },
        uncov: { $first: "$uncoveredQuantityAfter" },
        basisUsd: { $first: "$totalCostBasisAfterUsd" },
        replaySequence: { $first: "$replaySequence" },
        ts: { $first: "$blockTimestamp" },
        lastTx: { $first: "$normalizedTransactionId" },
      },
    },
    {
      $project: {
        _id: 0,
        wallet: "$_id.w",
        network: "$_id.n",
        asset: "$_id.a",
        qty: { $toDouble: "$qty" },
        backed: { $toDouble: "$backed" },
        uncov: { $toDouble: "$uncov" },
        basisUsd: { $toDouble: "$basisUsd" },
        replaySequence: 1,
        ts: 1,
        lastTx: 1,
      },
    },
    { $match: { qty: { $gt: 0 }, uncov: { $gt: 0 } } },
  ],
  { allowDiskUse: true }
).toArray();

latest.forEach((c) => {
  c.spotUsd = spot(c.asset);
  c.uncovUsd = c.spotUsd != null ? c.uncov * c.spotUsd : null;
  c.coveredPct = c.qty > 0 ? c.backed / c.qty : 0;
});

const priced = latest.filter((c) => c.uncovUsd != null && c.uncovUsd > 0);
const unpriceable = latest.filter((c) => c.spotUsd == null);

priced.sort((a, b) => b.uncovUsd - a.uncovUsd);

print("");
print("=== A1.2 Top 25 PRICED clusters by uncov USD ===");
print("  rank | wallet | network | asset | qty | cov% | uncovUsd | spot");
priced.slice(0, 25).forEach((c, idx) => {
  print(
    "  " + String(idx + 1).padStart(3, " ") +
      " | " + c.wallet.substring(0, 30).padEnd(30, " ") +
      " | " + (c.network || "?").padEnd(10, " ") +
      " | " + (c.asset || "?").substring(0, 32).padEnd(32, " ") +
      " | qty=" + c.qty.toFixed(6).padStart(14, " ") +
      " | cov=" + (c.coveredPct * 100).toFixed(1).padStart(5, " ") + "%" +
      " | uncov=$" + c.uncovUsd.toFixed(2).padStart(9, " ") +
      " | spot=$" + (c.spotUsd != null ? c.spotUsd.toFixed(4) : "?")
  );
});

const totalPricedUncov = priced.reduce((a, c) => a + c.uncovUsd, 0);
const top10Uncov = priced.slice(0, 10).reduce((a, c) => a + c.uncovUsd, 0);
const top20Uncov = priced.slice(0, 20).reduce((a, c) => a + c.uncovUsd, 0);
print("");
print("=== A1.3 Aggregate ===");
print("  Total session priced uncov USD : $" + totalPricedUncov.toFixed(2));
print("  Top 10 captures                : $" + top10Uncov.toFixed(2) + "  (" + (top10Uncov/totalPricedUncov*100).toFixed(1) + "%)");
print("  Top 20 captures                : $" + top20Uncov.toFixed(2) + "  (" + (top20Uncov/totalPricedUncov*100).toFixed(1) + "%)");

print("");
print("=== A1.4 Unpriceable clusters with significant qty (manual review) ===");
unpriceable.sort((a, b) => b.uncov - a.uncov);
print("  count: " + unpriceable.length);
unpriceable.slice(0, 25).forEach((c) => {
  print(
    "  " + c.wallet.substring(0, 30).padEnd(30, " ") +
      " | " + (c.network || "?").padEnd(10, " ") +
      " | " + (c.asset || "?").substring(0, 40).padEnd(40, " ") +
      " | qty=" + c.qty.toFixed(6) +
      " | uncov=" + c.uncov.toFixed(6) +
      " | cov%=" + (c.coveredPct * 100).toFixed(1)
  );
});

print("");
print("=== A1.5 Zombie clusters ===");
const zombies = db.asset_ledger_points.aggregate(
  [
    { $sort: { replaySequence: -1 } },
    { $group: { _id: { w: "$walletAddress", n: "$networkId", a: "$assetSymbol" }, qty: { $first: "$quantityAfter" }, uncov: { $first: "$uncoveredQuantityAfter" } } },
    { $project: { _id: 0, wallet: "$_id.w", network: "$_id.n", asset: "$_id.a", qty: { $toDouble: "$qty" }, uncov: { $toDouble: "$uncov" } } },
    { $match: { qty: { $lte: 0.000001 }, uncov: { $gt: 0.000001 } } },
    { $sort: { uncov: -1 } },
  ],
  { allowDiskUse: true }
).toArray();
print("  zombie cluster count: " + zombies.length);
zombies.forEach((z) => {
  print("  " + z.wallet.substring(0, 30).padEnd(30, " ") + " | " + (z.network||"?") + " | " + z.asset + " | qty=" + z.qty + " | uncov=" + z.uncov.toFixed(6));
});

print("");
print("Done A1 v2.");
