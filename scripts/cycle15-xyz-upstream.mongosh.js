// DEBUG / Cycle 15 — remove after coverage acceptance.
/**
 * Read-only: trace XYZ memecoin upstream pricing on BSC (Pancake LP 643922 / 750857).
 * Usage: mongosh <uri> --file scripts/cycle15-xyz-upstream.mongosh.js
 */
const db = db.getSiblingDB("walletradar");

const wallet = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";

print("=== XYZ flows with priceSource on BSC ===");
db.normalized_transactions
  .find({
    networkId: "BSC",
    walletAddress: wallet,
    "flows.assetSymbol": "XYZ",
    excludedFromAccounting: { $ne: true },
  })
  .sort({ blockTimestamp: 1 })
  .limit(20)
  .forEach((d) => {
    const f = (d.flows || []).find((x) => x.assetSymbol === "XYZ");
    print(
      "  " + (d._id || "").substring(0, 50)
        + " type=" + d.type
        + " ts=" + d.blockTimestamp
        + " qty=" + (f ? f.quantityDelta : "?")
        + " px=" + (f ? f.unitPriceUsd : "?")
        + " src=" + (f ? f.priceSource : "?")
    );
  });

print("");
print("=== LP-RECEIPT pools (latest uncov) ===");
db.asset_ledger_points
  .find({ networkId: "BSC", walletAddress: wallet, assetSymbol: /^LP-RECEIPT:bsc:pancakeswap:/i })
  .sort({ blockTimestamp: -1 })
  .limit(5)
  .forEach((p) => {
    print(
      "  " + p.assetSymbol
        + " qty=" + (p.quantityAfter?.$numberDecimal || p.quantityAfter)
        + " basis=" + (p.totalCostBasisAfterUsd?.$numberDecimal || p.totalCostBasisAfterUsd)
        + " uncov=" + (p.uncoveredQuantityAfter?.$numberDecimal || p.uncoveredQuantityAfter)
    );
  });

print("");
print("Done.");
