/**
 * Cycle/13 Stage 3: per Bybit wallet reconcile ledger sum(quantityDelta) vs live balances.
 * Usage: mongosh <uri> --file scripts/cycle13-bybit-ledger-reconcile.mongosh.js
 */
const dbName = "walletradar";
const db = db.getSiblingDB(dbName);

function toNum(v) {
  if (v == null) return 0;
  if (typeof v === "number") return v;
  if (v.$numberDecimal) return parseFloat(v.$numberDecimal);
  if (v.toString) return parseFloat(v.toString());
  return 0;
}

print("=== Bybit live balances ===");
const liveByWallet = {};
db.bybit_live_balances.find({}).forEach((row) => {
  const key = row.walletRef || row.accountRef || "?";
  if (!liveByWallet[key]) liveByWallet[key] = {};
  const sym = row.assetSymbol || row.coin || "?";
  liveByWallet[key][sym] = (liveByWallet[key][sym] || 0) + toNum(row.quantity || row.walletBalance);
});

print("=== Latest ledger quantityAfter per BYBIT wallet+asset ===");
const latest = db.asset_ledger_points.aggregate([
  { $match: { walletAddress: { $regex: /^BYBIT:/i } } },
  { $sort: { blockTimestamp: 1, replaySequence: 1 } },
  {
    $group: {
      _id: { wallet: "$walletAddress", asset: "$assetSymbol" },
      quantityAfter: { $last: "$quantityAfter" },
      basisBacked: { $last: "$basisBackedQuantityAfter" },
    },
  },
]).toArray();

let driftCount = 0;
latest.forEach((row) => {
  const wallet = row._id.wallet;
  const asset = row._id.asset;
  const ledgerQty = toNum(row.quantityAfter);
  const liveMap = liveByWallet[wallet];
  if (!liveMap) return;
  const liveQty = liveMap[asset];
  if (liveQty == null) return;
  const rel = liveQty === 0 ? (ledgerQty === 0 ? 0 : 1) : Math.abs(ledgerQty - liveQty) / Math.abs(liveQty);
  if (rel > 0.01) {
    driftCount++;
    print(
      "DRIFT wallet=" +
        wallet +
        " asset=" +
        asset +
        " ledger=" +
        ledgerQty +
        " live=" +
        liveQty +
        " rel=" +
        (rel * 100).toFixed(2) +
        "% bb=" +
        toNum(row.basisBacked)
    );
  }
});
print("Drift rows (>1% vs live): " + driftCount);

print("\n=== CARRY_OUT clamp hints (|qd| < typical transfer qty) on BYBIT FUND ETH ===");
db.asset_ledger_points
  .find({
    walletAddress: /BYBIT:.*:FUND$/,
    assetSymbol: "ETH",
    basisEffect: "CARRY_OUT",
  })
  .sort({ blockTimestamp: 1 })
  .forEach((p) => {
    const qd = Math.abs(toNum(p.quantityDelta));
    if (qd > 0 && qd < 3) {
      print(
        "  ts=" +
          p.blockTimestamp +
          " type=" +
          p.normalizedType +
          " qd=" +
          p.quantityDelta +
          " qa=" +
          p.quantityAfter +
          " bb=" +
          p.basisBackedQuantityAfter +
          " tx=" +
          (p.txHash || "null")
      );
    }
  });
