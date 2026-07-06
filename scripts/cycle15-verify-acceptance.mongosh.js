// DEBUG / Cycle 15 — remove after coverage acceptance.
/**
 * Cycle/15 Stage 4: post-replay acceptance checks.
 * Read-only diagnostic; no side effects on pipeline.
 * Usage: mongosh <uri> --file scripts/cycle15-verify-acceptance.mongosh.js
 */
const db = db.getSiblingDB("walletradar");

function toNum(v) {
  if (v == null) return 0;
  if (typeof v === "number") return v;
  if (v.$numberDecimal) return parseFloat(v.$numberDecimal);
  if (v.toString) return parseFloat(v.toString());
  return 0;
}

print("=== Z1: Bybit econ orphans (cont=false, bybit-econ-v1) ===");
const z1 = db.normalized_transactions.countDocuments({
  source: "BYBIT",
  continuityCandidate: false,
  correlationId: /^bybit-econ-v1/,
});
print("  count=" + z1 + " (target < 5)");

print("\n=== Z2: Mantle corridor carry (2026-02-19 Bybit→0x1a87) ===");
const mantleCarry = db.asset_ledger_points
  .find({
    walletAddress: /^0x1a87/i,
    networkId: "MANTLE",
    normalizedType: "EXTERNAL_TRANSFER_IN",
    correlationId: /^BYBIT-CORRIDOR:MANTLE/i,
  })
  .sort({ blockTimestamp: -1 })
  .limit(3)
  .toArray();
mantleCarry.forEach((p) => {
  print(
    "  tx=" +
      p.normalizedTransactionId +
      " qtyDelta=" +
      toNum(p.quantityDelta) +
      " basisBackedAfter=" +
      toNum(p.basisBackedQuantityAfter)
  );
});

print("\n=== Z3: BASE 0x1a87 ETH-family ledger coverage snapshot ===");
const ETH_SYMS = ["ETH", "WETH", "AWETH", "aArbWETH", "aManWETH"];
const basePoints = db.asset_ledger_points
  .find({
    walletAddress: /^0x1a87/i,
    networkId: "BASE",
    assetSymbol: { $in: ETH_SYMS },
  })
  .sort({ replaySequence: -1 })
  .toArray();
const byAsset = {};
basePoints.forEach((p) => {
  const k = p.assetSymbol;
  if (!byAsset[k] || (p.replaySequence || 0) > (byAsset[k].replaySequence || 0)) {
    byAsset[k] = p;
  }
});
Object.keys(byAsset).forEach((sym) => {
  const p = byAsset[sym];
  const qty = toNum(p.quantityAfter);
  const backed = toNum(p.basisBackedQuantityAfter);
  const cov = qty === 0 ? 100 : (100 * backed) / qty;
  print("  " + sym + " qty=" + qty + " backed=" + backed + " coverage%=" + cov.toFixed(1));
});

print("\n=== Shortfall audits (latest 5) ===");
db.accounting_shortfall_audit
  .find({})
  .sort({ blockTimestamp: -1 })
  .limit(5)
  .forEach((a) => {
    print(
      "  " +
        a.normalizedTransactionId +
        " " +
        a.assetSymbol +
        " shortfallDelta=" +
        toNum(a.quantityShortfallDelta)
    );
  });

print("\nDone. Compare dashboard Net Inflow vs $14,150 ± 2% manually.");
