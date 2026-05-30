// DEBUG / Round 5 Phase A2b — anomaly investigation
const db = db.getSiblingDB("walletradar");

print("=== Q1. 0xf03b/ARBITRUM/ETH — the seq=774 huge uncovDelta=8.65 event ===");
const tx774 = db.normalized_transactions.findOne({ _id: { $regex: "^0x293cf2289fcbf131" } });
if (tx774) {
  print("  _id=" + tx774._id);
  print("  ts=" + tx774.blockTimestamp.toISOString());
  print("  type=" + tx774.type + " corr=" + tx774.correlationId + " cont=" + tx774.continuityCandidate);
  print("  matchedCounterparty=" + tx774.matchedCounterparty);
  print("  flows:");
  (tx774.flows || []).forEach((f) => {
    print("    role=" + f.role +
          " sym=" + f.assetSymbol +
          " qty=" + String(f.quantityDelta) +
          " unit=" + String(f.unitPriceUsd) +
          " value=" + String(f.valueUsd) +
          " priceSrc=" + f.priceSource +
          " cpAddress=" + (f.counterpartyAddress || "").substring(0, 30) +
          " cpType=" + (f.counterpartyType || ""));
  });
  print("  ALL ledger points for this tx (cross-wallet):");
  db.asset_ledger_points
    .find({ normalizedTransactionId: tx774._id })
    .sort({ replaySequence: 1 })
    .forEach((p) => {
      print("    " + p.walletAddress.substring(0, 30) +
            " | " + p.networkId +
            " | " + p.assetSymbol +
            " seq=" + p.replaySequence +
            " eff=" + p.basisEffect +
            " qtyDelta=" + String(p.quantityDelta) +
            " uncovDelta=" + String(p.uncoveredQuantityDelta) +
            " qtyAfter=" + String(p.quantityAfter) +
            " uncovAfter=" + String(p.uncoveredQuantityAfter));
    });
}

print("");
print("=== Q2. 0xf03b/ARBITRUM/ETH — context around seq=774 (5 before, 5 after) ===");
const around774 = db.asset_ledger_points
  .find({
    walletAddress: "0xf03b52e8686b962e051a6075a06b96cb8a663021",
    networkId: "ARBITRUM",
    assetSymbol: "ETH",
    replaySequence: { $gte: 770, $lte: 785 },
  })
  .sort({ replaySequence: 1 })
  .toArray();
around774.forEach((p) => {
  print("  seq=" + p.replaySequence +
        " ts=" + p.blockTimestamp.toISOString() +
        " eff=" + p.basisEffect +
        " qtyDelta=" + String(p.quantityDelta) +
        " uncovDelta=" + String(p.uncoveredQuantityDelta) +
        " qty=" + String(p.quantityAfter) +
        " backed=" + String(p.basisBackedQuantityAfter) +
        " uncov=" + String(p.uncoveredQuantityAfter) +
        " basisUsd=" + String(p.totalCostBasisAfterUsd) +
        " tx=" + (p.normalizedTransactionId || "").substring(0, 28));
});

print("");
print("=== Q3. LP cluster events seq 4730-4750 (LP mint → gauge stake gap) ===");
const aroundLp = db.asset_ledger_points
  .find({
    walletAddress: "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f",
    networkId: "AVALANCHE",
    replaySequence: { $gte: 4730, $lte: 4750 },
  })
  .sort({ replaySequence: 1 })
  .toArray();
aroundLp.forEach((p) => {
  print("  seq=" + p.replaySequence +
        " ts=" + p.blockTimestamp.toISOString() +
        " " + p.assetSymbol +
        " eff=" + p.basisEffect +
        " qtyDelta=" + String(p.quantityDelta) +
        " uncovDelta=" + String(p.uncoveredQuantityDelta) +
        " qty=" + String(p.quantityAfter) +
        " backed=" + String(p.basisBackedQuantityAfter) +
        " uncov=" + String(p.uncoveredQuantityAfter) +
        " basis=" + String(p.totalCostBasisAfterUsd) +
        " tx=" + (p.normalizedTransactionId || "").substring(0, 28));
});

print("");
print("=== Q4. The LP mint tx 0x983f7940 — all wallet ledger points ===");
const mintTx = db.normalized_transactions.findOne({ _id: { $regex: "^0x983f7940b9ae73ea6a9bbcf4c949289db8ac5412" } });
if (mintTx) {
  print("  _id=" + mintTx._id);
  print("  type=" + mintTx.type + " corr=" + mintTx.correlationId + " cont=" + mintTx.continuityCandidate);
  print("  flows (" + mintTx.flows.length + "):");
  mintTx.flows.forEach((f) => {
    print("    role=" + f.role +
          " sym=" + f.assetSymbol +
          " qty=" + String(f.quantityDelta) +
          " unit=" + String(f.unitPriceUsd) +
          " priceSrc=" + f.priceSource +
          " cpAddress=" + (f.counterpartyAddress || "").substring(0, 30));
  });
  print("  all ledger points for this tx:");
  db.asset_ledger_points.find({ normalizedTransactionId: mintTx._id }).sort({ replaySequence: 1 }).forEach((p) => {
    print("    " + p.assetSymbol + " seq=" + p.replaySequence + " eff=" + p.basisEffect +
          " qtyDelta=" + String(p.quantityDelta) +
          " uncovDelta=" + String(p.uncoveredQuantityDelta) +
          " qty=" + String(p.quantityAfter) +
          " backed=" + String(p.basisBackedQuantityAfter) +
          " uncov=" + String(p.uncoveredQuantityAfter) +
          " basis=" + String(p.totalCostBasisAfterUsd));
  });
}

print("");
print("=== Q5. The GHO/USDT/USDC stable sources on AVALANCHE pre-mint ===");
const preStables = db.asset_ledger_points
  .find({
    walletAddress: "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f",
    networkId: "AVALANCHE",
    assetSymbol: { $in: ["GHO", "USDC", "USDt", "USDT"] },
    blockTimestamp: { $lt: new Date("2025-07-31T10:24:26Z") },
  })
  .sort({ replaySequence: -1 })
  .limit(15)
  .toArray();
preStables.forEach((p) => {
  print("  " + p.blockTimestamp.toISOString() +
        " " + p.assetSymbol +
        " seq=" + p.replaySequence +
        " eff=" + p.basisEffect +
        " qtyDelta=" + String(p.quantityDelta) +
        " uncovDelta=" + String(p.uncoveredQuantityDelta) +
        " qty=" + String(p.quantityAfter) +
        " backed=" + String(p.basisBackedQuantityAfter) +
        " uncov=" + String(p.uncoveredQuantityAfter));
});

print("");
print("=== Q6. BYBIT FUND ETH — primary uncov root ===");
const fundEthCreators = db.asset_ledger_points
  .find({
    walletAddress: "BYBIT:33625378:FUND",
    assetSymbol: "ETH",
    uncoveredQuantityDelta: { $gt: 0 },
  })
  .sort({ replaySequence: 1 })
  .limit(5)
  .toArray();
fundEthCreators.forEach((p) => {
  print("  seq=" + p.replaySequence +
        " ts=" + p.blockTimestamp.toISOString() +
        " eff=" + p.basisEffect +
        " qtyDelta=" + String(p.quantityDelta) +
        " uncovDelta=" + String(p.uncoveredQuantityDelta) +
        " tx=" + (p.normalizedTransactionId || "").substring(0, 50));
  const tx = db.normalized_transactions.findOne({ _id: p.normalizedTransactionId });
  if (tx) {
    print("    type=" + tx.type + " cont=" + tx.continuityCandidate + " corr=" + (tx.correlationId || "").substring(0, 80));
    (tx.flows || []).forEach((f) => {
      print("      role=" + f.role + " sym=" + f.assetSymbol + " qty=" + String(f.quantityDelta) + " priceSrc=" + f.priceSource);
    });
  }
});

print("");
print("=== Q7. 0xf03b USDC source on AVALANCHE pre-Aave deposit (2025-11-06) ===");
const usdcPre = db.asset_ledger_points
  .find({
    walletAddress: "0xf03b52e8686b962e051a6075a06b96cb8a663021",
    networkId: "AVALANCHE",
    assetSymbol: "USDC",
    blockTimestamp: { $lt: new Date("2025-11-06T12:26:27Z") },
  })
  .sort({ replaySequence: -1 })
  .limit(15)
  .toArray();
usdcPre.forEach((p) => {
  print("  " + p.blockTimestamp.toISOString() +
        " seq=" + p.replaySequence +
        " eff=" + p.basisEffect +
        " qtyDelta=" + String(p.quantityDelta) +
        " uncovDelta=" + String(p.uncoveredQuantityDelta) +
        " qty=" + String(p.quantityAfter) +
        " backed=" + String(p.basisBackedQuantityAfter) +
        " uncov=" + String(p.uncoveredQuantityAfter) +
        " tx=" + (p.normalizedTransactionId || "").substring(0, 28));
});

print("");
print("Done A2b.");
