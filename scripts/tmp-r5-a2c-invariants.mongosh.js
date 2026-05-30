// DEBUG / Round 5 Phase A2c — verify invariant violations + wrapper carry deep dive
const db = db.getSiblingDB("walletradar");

print("=== Q1. Math invariant violations: uncov > qty across all latest cluster points ===");
const violations = db.asset_ledger_points.aggregate(
  [
    { $sort: { replaySequence: -1 } },
    {
      $group: {
        _id: { w: "$walletAddress", n: "$networkId", a: "$assetSymbol" },
        qty: { $first: "$quantityAfter" },
        backed: { $first: "$basisBackedQuantityAfter" },
        uncov: { $first: "$uncoveredQuantityAfter" },
        basisUsd: { $first: "$totalCostBasisAfterUsd" },
        ts: { $first: "$blockTimestamp" },
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
        ts: 1,
      },
    },
    { $match: { qty: { $gt: 0 }, $expr: { $gt: ["$uncov", "$qty"] } } },
    { $sort: { uncov: -1 } },
  ],
  { allowDiskUse: true }
).toArray();
print("  violations count: " + violations.length);
violations.slice(0, 20).forEach((v) => {
  print(
    "  " + v.wallet.substring(0, 30).padEnd(30, " ") +
      " | " + (v.network || "?") +
      " | " + v.asset +
      " | qty=" + v.qty.toFixed(6) +
      " | backed=" + v.backed.toFixed(6) +
      " | uncov=" + v.uncov.toFixed(6) +
      " | basis=$" + v.basisUsd.toFixed(2) +
      " | overflow=" + (v.uncov - v.qty).toFixed(6)
  );
});

print("");
print("=== Q2. Math invariant: backed > qty (also impossible) ===");
const backOverflow = db.asset_ledger_points.aggregate(
  [
    { $sort: { replaySequence: -1 } },
    {
      $group: {
        _id: { w: "$walletAddress", n: "$networkId", a: "$assetSymbol" },
        qty: { $first: "$quantityAfter" },
        backed: { $first: "$basisBackedQuantityAfter" },
      },
    },
    {
      $project: {
        _id: 0,
        wallet: "$_id.w", network: "$_id.n", asset: "$_id.a",
        qty: { $toDouble: "$qty" }, backed: { $toDouble: "$backed" },
      },
    },
    { $match: { qty: { $gt: 0 }, $expr: { $gt: ["$backed", "$qty"] } } },
    { $sort: { backed: -1 } },
  ],
  { allowDiskUse: true }
).toArray();
print("  count: " + backOverflow.length);
backOverflow.slice(0, 10).forEach((v) => {
  print("  " + v.wallet.substring(0,30) + " | " + (v.network||"?") + " | " + v.asset + " | qty=" + v.qty.toFixed(6) + " | backed=" + v.backed.toFixed(6));
});

print("");
print("=== Q3. Wrapper carry deep dive: LP→gauge stake tx 0x13d0771024dc all flows + state ===");
const stake = db.normalized_transactions.findOne({ _id: { $regex: "^0x13d0771024dc" } });
if (stake) {
  print("  id=" + stake._id);
  print("  type=" + stake.type + " cont=" + stake.continuityCandidate);
  print("  ALL flows:");
  (stake.flows || []).forEach((f, i) => {
    print("    [" + i + "] role=" + f.role +
          " sym=" + f.assetSymbol +
          " qty=" + String(f.quantityDelta) +
          " contract=" + (f.assetContract || "") +
          " cpAddress=" + (f.counterpartyAddress || "").substring(0, 30) +
          " cpType=" + (f.counterpartyType || ""));
  });
  print("");
  print("  LP state IMMEDIATELY before stake (last point before 2025-07-31 10:24:58):");
  const lpBefore = db.asset_ledger_points
    .find({
      walletAddress: "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f",
      networkId: "AVALANCHE",
      assetSymbol: "AAVE GHO/USDT/USDC",
      replaySequence: { $lt: 4742 },
    })
    .sort({ replaySequence: -1 })
    .limit(3)
    .toArray();
  lpBefore.forEach((p) => {
    print("    seq=" + p.replaySequence + " ts=" + p.blockTimestamp.toISOString() +
          " eff=" + p.basisEffect +
          " qty=" + String(p.quantityAfter) +
          " backed=" + String(p.basisBackedQuantityAfter) +
          " uncov=" + String(p.uncoveredQuantityAfter) +
          " basis=$" + String(p.totalCostBasisAfterUsd));
  });
}

print("");
print("=== Q4. Check ReplayPendingTransferKeyFactory bucket continuity — any record? ===");
// hard to inspect in DB; instead check if any other LP→wrapper carries work elsewhere
print("  Sampling wrapper-style staking txs across session (TYPE=LENDING_DEPOSIT with 2 TRANSFER, no LENDING token suffix):");
const wrapperLike = db.normalized_transactions.aggregate(
  [
    { $match: { type: "LENDING_DEPOSIT" } },
    {
      $project: {
        _id: 1, blockTimestamp: 1,
        networkId: 1,
        flowCount: { $size: { $ifNull: ["$flows", []] } },
        flows: 1,
      },
    },
    { $match: { flowCount: { $in: [2, 3] } } },
    { $limit: 30 },
  ],
  { allowDiskUse: true }
).toArray();
let workedCount = 0, brokenCount = 0;
wrapperLike.forEach((tx) => {
  const transferLegs = (tx.flows || []).filter((f) => f.role === "TRANSFER");
  if (transferLegs.length !== 2) return;
  const inLeg = transferLegs.find((f) => Number(f.quantityDelta) > 0);
  const outLeg = transferLegs.find((f) => Number(f.quantityDelta) < 0);
  if (!inLeg || !outLeg) return;
  // look at ledger points for inLeg.assetSymbol
  const inPoint = db.asset_ledger_points.findOne({
    normalizedTransactionId: tx._id,
    assetSymbol: inLeg.assetSymbol,
    basisEffect: "REALLOCATE_IN",
  });
  if (inPoint) {
    const inBacked = Number(inPoint.basisBackedQuantityAfter);
    const inQty = Number(inPoint.quantityAfter);
    if (inBacked > inQty * 0.5) workedCount++;
    else brokenCount++;
  }
});
print("  sampled wrapper-like LENDING_DEPOSITs (2 TRANSFER legs): worked=" + workedCount + " broken=" + brokenCount);

print("");
print("=== Q5. Wrapper key for AVAX Curve LP → gauge — see if cpAddress collides ===");
// We need to check whether the LP token's cpAddress in the stake tx == the LP token's cpAddress in the prior mint tx
// because wrapper bucket key uses the counterparty receipt identity
if (stake) {
  const lpLeg = (stake.flows || []).find((f) => /aave gho/i.test(f.assetSymbol) && !/gauge/i.test(f.assetSymbol));
  const gaugeLeg = (stake.flows || []).find((f) => /gauge/i.test(f.assetSymbol));
  print("  stake LP leg cpAddress: " + (lpLeg ? lpLeg.counterpartyAddress : "null"));
  print("  stake LP leg cpType:    " + (lpLeg ? lpLeg.counterpartyType : "null"));
  print("  stake LP leg contract:  " + (lpLeg ? lpLeg.assetContract : "null"));
  print("  stake gauge leg cpAddress: " + (gaugeLeg ? gaugeLeg.counterpartyAddress : "null"));
  print("  stake gauge leg contract: " + (gaugeLeg ? gaugeLeg.assetContract : "null"));
}

print("");
print("=== Q6. Show LP mint tx 0x983f7940 LP leg cpAddress + match check ===");
const mintTx = db.normalized_transactions.findOne({ _id: { $regex: "^0x983f7940" } });
if (mintTx) {
  const lpInLeg = (mintTx.flows || []).find((f) => /aave gho/i.test(f.assetSymbol) && !/gauge/i.test(f.assetSymbol) && Number(f.quantityDelta) > 0);
  print("  mint LP-in leg cpAddress: " + (lpInLeg ? lpInLeg.counterpartyAddress : "null"));
  print("  mint LP-in leg contract:  " + (lpInLeg ? lpInLeg.assetContract : "null"));
}

print("");
print("Done A2c.");
