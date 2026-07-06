// DEBUG / Cycle 15 Round 5 Phase E acceptance checks
const db = db.getSiblingDB("walletradar");

const ETH_SPOT = 2175.66;
const DUST_USD = 1.0;

function spot(sym) {
  if (!sym) return null;
  const u = sym.toUpperCase();
  const peg = { USDT: 1, USDC: 1, USDE: 1, DAI: 1, GHO: 1, AUSDC: 1, AAVAUSDC: 1 };
  if (peg[u] != null) return peg[u];
  if (["WETH","CMETH","METH","WEETH","STETH","AMANWETH","AARBWETH"].includes(u)) return ETH_SPOT;
  if (u === "AVAX") return 9.485;
  if (u === "MNT") return 0.6698;
  return null;
}

print("=== E1. Active clusters coverage (qty>0, uncov USD > $1) ===");
const latest = db.asset_ledger_points.aggregate([
  { $sort: { replaySequence: -1 } },
  { $group: {
      _id: { w: "$walletAddress", n: "$networkId", a: "$assetSymbol" },
      qty: { $first: "$quantityAfter" },
      backed: { $first: "$basisBackedQuantityAfter" },
      uncov: { $first: "$uncoveredQuantityAfter" },
    }},
  { $project: { wallet: "$_id.w", network: "$_id.n", asset: "$_id.a",
      qty: { $toDouble: "$qty" }, backed: { $toDouble: "$backed" }, uncov: { $toDouble: "$uncov" } }},
  { $match: { qty: { $gt: 0 } } },
], { allowDiskUse: true }).toArray();

let failCount = 0;
let passCount = 0;
const keyClusters = [
  { w: "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f", n: "AVALANCHE", a: "AAVE GHO/USDT/USDC-GAUGE" },
  { w: "BYBIT:33625378:FUND", n: null, a: "CMETH" },
  { w: "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f", n: "MANTLE", a: "AMANWETH" },
  { w: "0xf03b52e8686b962e051a6075a06b96cb8a663021", n: "ARBITRUM", a: "ETH" },
];

keyClusters.forEach((k) => {
  const c = latest.find((x) => x.wallet === k.w && x.asset === k.a && (k.n == null || x.network === k.n));
  if (!c) { print("  MISSING cluster " + k.a); return; }
  const cov = c.qty > 0 ? c.backed / c.qty : 0;
  const s = spot(c.asset);
  const uncovUsd = s ? c.uncov * s : null;
  print("  " + k.asset + " | cov%=" + (cov * 100).toFixed(1) + " | qty=" + c.qty.toFixed(4) + " | uncov=" + c.uncov.toFixed(4) + (uncovUsd ? " ($" + uncovUsd.toFixed(0) + ")" : ""));
});

latest.forEach((c) => {
  const s = spot(c.asset);
  if (s == null) return;
  const uncovUsd = c.uncov * s;
  if (uncovUsd <= DUST_USD) return;
  const cov = c.qty > 0 ? c.backed / c.qty : 0;
  if (cov < 0.99) failCount++;
  else passCount++;
});
print("  Active priced clusters with uncov>$1: pass=" + passCount + " fail=" + failCount);

print("");
print("=== E2. Portfolio backed USD (rough) ===");
let totalBacked = 0;
latest.forEach((c) => {
  const s = spot(c.asset);
  if (s == null || c.qty <= 0) return;
  totalBacked += c.backed * s;
});
print("  sum(backed*spot) ≈ $" + totalBacked.toFixed(0) + " (target ~$11.4k ±5%)");

print("");
print("=== E4. Zombie + invariant violations ===");
const zombies = latest.filter((c) => c.qty <= 0.000001 && c.uncov > 0.000001);
const violations = latest.filter((c) => c.qty > 0 && c.uncov > c.qty);
print("  zombie clusters: " + zombies.length);
zombies.forEach((z) => print("    " + z.asset + " uncov=" + z.uncov));
print("  uncov>qty violations: " + violations.length);
violations.forEach((v) => print("    " + v.wallet.substring(0,20) + " " + v.network + " " + v.asset + " qty=" + v.qty + " uncov=" + v.uncov));

print("");
print("Done E acceptance.");
