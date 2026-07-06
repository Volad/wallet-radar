// DEBUG / Cycle 15 Round 5 Phase A1 — top-N active clusters with uncov
// READ-ONLY. Remove after audit.
const db = db.getSiblingDB("walletradar");

print("=== A1.1 Latest point per (wallet, network, asset) cluster ===");

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
    {
      $match: {
        qty: { $gt: 0 },
        uncov: { $gt: 0 },
      },
    },
  ],
  { allowDiskUse: true }
).toArray();

print("  total active clusters with uncov>0 and qty>0: " + latest.length);

// load latest prices to estimate USD impact
const priceMap = {};
db.current_price_quotes.find({}).forEach((q) => {
  if (q.symbol && q.usdPrice != null) {
    priceMap[q.symbol.toUpperCase()] = Number(q.usdPrice);
  }
});
print("  current_price_quotes entries loaded: " + Object.keys(priceMap).length);

// helper — approximate USD using family/peg fallbacks
function spot(sym) {
  if (!sym) return null;
  const u = sym.toUpperCase();
  if (priceMap[u] != null) return priceMap[u];
  // hard pegs
  if (u === "USDT" || u === "USDC" || u === "USDE" || u === "DAI" || u === "FDUSD") return 1.0;
  // wrapped/lst → underlying
  if (["WETH", "CMETH", "METH", "WEETH", "STETH", "WSTETH", "RETH", "CBETH", "AMANWETH", "AARBWETH", "AETHWETH"].includes(u)) {
    return priceMap.ETH || null;
  }
  if (["WBTC", "TBTC", "AWBTC"].includes(u)) return priceMap.BTC || null;
  if (["WMATIC", "AMATIC"].includes(u)) return priceMap.MATIC || null;
  if (["WAVAX", "SAVAX", "AAVAX"].includes(u)) return priceMap.AVAX || null;
  if (["WMNT", "AMANMNT", "AMANWMNT"].includes(u)) return priceMap.MNT || null;
  // aave receipt prefix strip A*X → X
  if (/^A[A-Z]+/.test(u)) {
    const stripped = u.replace(/^A(MAN|AAVAARB|ARB|ETH|EUL|BAS|AVA|AVAARB|AVA[A-Z]?)/, "");
    if (priceMap[stripped] != null) return priceMap[stripped];
  }
  return null;
}

latest.forEach((c) => {
  c.spotUsd = spot(c.asset);
  c.uncovUsd = c.spotUsd != null ? c.uncov * c.spotUsd : null;
  c.coveredPct = c.qty > 0 ? c.backed / c.qty : 0;
});

const ranked = latest
  .filter((c) => c.uncovUsd != null && c.uncovUsd > 1)
  .sort((a, b) => b.uncovUsd - a.uncovUsd);

print("");
print("=== A1.2 Top 30 active clusters by uncov USD (qty>0, uncov>$1) ===");
print(
  "  rank | wallet (8c) | network | asset | qty | covered% | uncovUsd | spot | lastTs"
);
ranked.slice(0, 30).forEach((c, idx) => {
  const wallet = c.wallet.length > 30 ? c.wallet.substring(0, 30) : c.wallet;
  print(
    "  " +
      String(idx + 1).padStart(3, " ") +
      " | " +
      wallet.padEnd(30, " ") +
      " | " +
      String(c.network).padEnd(10, " ") +
      " | " +
      String(c.asset).padEnd(28, " ") +
      " | qty=" + c.qty.toFixed(6).padStart(14, " ") +
      " | cov=" + (c.coveredPct * 100).toFixed(2).padStart(6, " ") + "%" +
      " | uncovUsd=$" + c.uncovUsd.toFixed(2).padStart(10, " ") +
      " | spot=$" + (c.spotUsd != null ? c.spotUsd.toFixed(4) : "?") +
      " | " +
      (c.ts ? c.ts.toISOString() : "?")
  );
});

const totalUncovUsd = ranked.reduce((a, c) => a + c.uncovUsd, 0);
const top10UncovUsd = ranked.slice(0, 10).reduce((a, c) => a + c.uncovUsd, 0);
const top20UncovUsd = ranked.slice(0, 20).reduce((a, c) => a + c.uncovUsd, 0);

print("");
print("=== A1.3 Aggregate uncov USD ===");
print("  Total session uncov USD (priced clusters)      : $" + totalUncovUsd.toFixed(2));
print("  Top 10 captures                                : $" + top10UncovUsd.toFixed(2) + "  (" + ((top10UncovUsd / totalUncovUsd) * 100).toFixed(1) + "%)");
print("  Top 20 captures                                : $" + top20UncovUsd.toFixed(2) + "  (" + ((top20UncovUsd / totalUncovUsd) * 100).toFixed(1) + "%)");

print("");
print("=== A1.4 Unpriceable clusters (no spot, qty>0, uncov>0) — manual review ===");
const unpriceable = latest
  .filter((c) => c.spotUsd == null)
  .sort((a, b) => b.uncov - a.uncov);
print("  count: " + unpriceable.length);
unpriceable.slice(0, 20).forEach((c) => {
  print(
    "  " + c.wallet.substring(0, 30).padEnd(30, " ") +
      " | " + c.network +
      " | " + c.asset +
      " | qty=" + c.qty.toFixed(6) +
      " | uncov=" + c.uncov.toFixed(6) +
      " | cov%=" + (c.coveredPct * 100).toFixed(2)
  );
});

print("");
print("=== A1.5 Zombie clusters (qty<=dust, uncov>dust) — Phase E4 target ===");
const zombies = db.asset_ledger_points.aggregate(
  [
    { $sort: { replaySequence: -1 } },
    {
      $group: {
        _id: { w: "$walletAddress", n: "$networkId", a: "$assetSymbol" },
        qty: { $first: "$quantityAfter" },
        uncov: { $first: "$uncoveredQuantityAfter" },
      },
    },
    {
      $project: {
        _id: 0,
        wallet: "$_id.w",
        network: "$_id.n",
        asset: "$_id.a",
        qty: { $toDouble: "$qty" },
        uncov: { $toDouble: "$uncov" },
      },
    },
    {
      $match: { qty: { $lte: 0.000001 }, uncov: { $gt: 0.000001 } },
    },
    { $sort: { uncov: -1 } },
  ],
  { allowDiskUse: true }
).toArray();
print("  zombie cluster count: " + zombies.length);
zombies.slice(0, 10).forEach((z) => {
  print(
    "  " + z.wallet.substring(0, 30).padEnd(30, " ") +
      " | " + z.network +
      " | " + z.asset +
      " | qty=" + z.qty +
      " | uncov=" + z.uncov.toFixed(6)
  );
});

print("");
print("Done A1.");
