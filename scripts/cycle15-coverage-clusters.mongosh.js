// DEBUG / Cycle 15 — remove after coverage acceptance.
/**
 * Read-only: top uncovered ledger positions and residual BRIDGE_OUT misclassification hints.
 * Usage: mongosh <uri> --file scripts/cycle15-coverage-clusters.mongosh.js
 */
const db = db.getSiblingDB("walletradar");

function toNum(v) {
  if (v == null) return 0;
  if (typeof v === "number") return v;
  if (v.$numberDecimal) return parseFloat(v.$numberDecimal);
  return parseFloat(String(v)) || 0;
}

print("=== Z4: cont=false BRIDGE_OUT with same-tx inbound (should be 0 after swap demotion) ===");
const sameTxBridgeOut = db.normalized_transactions.countDocuments({
  type: "BRIDGE_OUT",
  excludedFromAccounting: { $ne: true },
  flows: { $elemMatch: { role: "TRANSFER", quantityDelta: { $gt: 0 } } },
});
print("  count=" + sameTxBridgeOut);

print("");
print("=== Top uncovered positions (latest ledger point per wallet/net/symbol) ===");
db.asset_ledger_points.aggregate([
  { $sort: { blockTimestamp: -1 } },
  {
    $group: {
      _id: { wallet: "$walletAddress", net: "$networkId", sym: "$assetSymbol" },
      latest: { $first: "$$ROOT" },
    },
  },
  { $match: { "latest.uncoveredQuantityAfter": { $gt: 0 } } },
  {
    $project: {
      sym: "$_id.sym",
      wallet: "$_id.wallet",
      net: "$_id.net",
      qty: "$latest.quantityAfter",
      uncov: "$latest.uncoveredQuantityAfter",
      txId: "$latest.transactionId",
      txType: "$latest.transactionType",
    },
  },
  { $sort: { uncov: -1 } },
  { $limit: 30 },
]).forEach((d) => {
  const w = d.wallet == null ? "?" : String(d.wallet).substring(0, 14);
  print(
      "  " + d.sym + " net=" + d.net + " w=" + w
      + " qty=" + toNum(d.qty) + " uncov=" + toNum(d.uncov)
      + " lastTxType=" + (d.txType || "?")
  );
});

print("");
print("=== Sample: 0x2438714a… (LI.FI USDe→USDC) ===");
db.normalized_transactions.find({ _id: /^0x2438714a/ }).forEach((d) => {
  print("  id=" + d._id);
  print("  type=" + d.type + " protocol=" + d.protocolName + " cont=" + d.continuityCandidate);
  (d.flows || []).forEach((f, i) => {
    print("    [" + i + "] " + f.role + " " + f.assetSymbol + " qty=" + f.quantityDelta);
  });
});

print("");
print("Done.");
