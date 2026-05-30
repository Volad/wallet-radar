/**
 * Cycle/14 Stage A: baseline for legacy sealed BRIDGE_OUT repair and coverage hotspots.
 * Usage: mongosh <uri> --file scripts/cycle14-legacy-bridge-repair-audit.mongosh.js
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

print("=== Legacy sealed BRIDGE_OUT (cont=false, correlationId bridge:*) ===");
const legacyStuck = db.normalized_transactions
  .find({
    type: "BRIDGE_OUT",
    continuityCandidate: false,
    correlationId: { $regex: "^bridge:" },
  })
  .toArray();
let legacyUsd = 0;
let repairable = 0;
legacyStuck.forEach((out) => {
  const pair = db.normalized_transactions.findOne({
    correlationId: out.correlationId,
    _id: { $ne: out._id },
  });
  const f = (out.flows || []).find((x) => x.role !== "FEE" && toNum(x.quantityDelta) < 0);
  if (f && f.unitPriceUsd) legacyUsd += Math.abs(toNum(f.quantityDelta)) * toNum(f.unitPriceUsd);
  if (pair && pair.continuityCandidate) repairable++;
});
print("count=" + legacyStuck.length + " repairable_in_cont_true=" + repairable + " usdPricedOut=" + legacyUsd.toFixed(2));

print("\n=== BRIDGE_OUT continuity summary ===");
print("cont=true: " + db.normalized_transactions.countDocuments({ type: "BRIDGE_OUT", continuityCandidate: true }));
print("cont=false: " + db.normalized_transactions.countDocuments({ type: "BRIDGE_OUT", continuityCandidate: false }));

print("\n=== On-chain INTERNAL_TRANSFER same-tx orphan candidates ===");
const internalOrphans = db.normalized_transactions
  .find({
    source: "ON_CHAIN",
    type: "INTERNAL_TRANSFER",
    continuityCandidate: false,
    $or: [{ correlationId: null }, { correlationId: "" }],
  })
  .toArray();
let sameTxPairs = 0;
internalOrphans.forEach((left) => {
  const peers = db.normalized_transactions
    .find({
      txHash: left.txHash,
      networkId: left.networkId,
      walletAddress: { $ne: left.walletAddress },
      type: "INTERNAL_TRANSFER",
    })
    .toArray();
  if (peers.length === 1) sameTxPairs++;
});
print("orphanInternals=" + internalOrphans.length + " withSinglePeer=" + sameTxPairs);

print("\n=== Hotspot ledger tail (ETH MANTLE WETH, USDC AVALANCHE) ===");
[
  { wallet: "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f", net: "MANTLE", asset: "WETH" },
  { wallet: "0xf03b52e8686b962e051a6075a06b96cb8a663021", net: "AVALANCHE", asset: "USDC" },
].forEach((row) => {
  const last = db.asset_ledger_points
    .find({ walletAddress: row.wallet, networkId: row.net, assetSymbol: row.asset })
    .sort({ blockTimestamp: -1, replaySequence: -1 })
    .limit(1)
    .toArray()[0];
  if (last) {
    const qa = toNum(last.quantityAfter);
    const bb = toNum(last.basisBackedQuantityAfter);
    const pct = qa === 0 ? 0 : (bb / qa) * 100;
    print(
      row.asset +
        " " +
        row.net +
        " qa=" +
        qa +
        " bb=" +
        bb +
        " cov%=" +
        pct.toFixed(1)
    );
  }
});

print("\n=== Done (compare dashboard lifetimeExternalInflowUsd after pipeline) ===");
