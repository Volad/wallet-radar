// DEBUG / Cycle 15 — remove after coverage acceptance.
/**
 * Read-only: Avalanche Aave GHO/USDT/USDC LP, gauge, and Aura vault basis after Round 3.
 * Usage: mongosh <uri> --file scripts/cycle15-curve-lp-acceptance.mongosh.js
 */
const db = db.getSiblingDB("walletradar");

function toNum(v) {
  if (v == null) return 0;
  if (typeof v === "number") return v;
  if (v.$numberDecimal) return parseFloat(v.$numberDecimal);
  return parseFloat(String(v)) || 0;
}

const wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
const symbols = [
  "AAVE GHO/USDT/USDC",
  "AAVE GHO/USDT/USDC-GAUGE",
  "AURAAAVE GHO/USDT/USDC-VAULT",
  "auraAave GHO/USDT/USDC-vault",
  "GHO",
  "USDC",
  "AAVAUSDC",
];

print("=== Latest ledger (AVALANCHE, wallet 0x1a87f12a…) ===");
symbols.forEach((sym) => {
  const latest = db.asset_ledger_points
    .find({ networkId: "AVALANCHE", walletAddress: wallet, assetSymbol: sym })
    .sort({ replaySequence: -1 })
    .limit(1)
    .toArray()[0];
  if (!latest) {
    print("  " + sym + ": (no points)");
    return;
  }
  print(
    "  " + sym
      + " qty=" + toNum(latest.quantityAfter)
      + " basis=" + toNum(latest.totalCostBasisAfterUsd)
      + " uncov=" + toNum(latest.uncoveredQuantityAfter)
      + " eff=" + latest.basisEffect
  );
});

print("");
print("Done.");
