// DEBUG / Round 5 F3 — CMETH lineage tracing
const db = db.getSiblingDB("walletradar");

print("=== Q1. ALL CMETH ledger points for BYBIT:33625378:UTA, ordered by seq ===");
db.asset_ledger_points
  .find({ walletAddress: "BYBIT:33625378:UTA", assetSymbol: "CMETH" })
  .sort({ replaySequence: 1 })
  .limit(15)
  .forEach((p) => {
    print("  seq=" + p.replaySequence + " ts=" + p.blockTimestamp.toISOString() +
          " eff=" + p.basisEffect +
          " qtyDelta=" + String(p.quantityDelta) +
          " uncovDelta=" + String(p.uncoveredQuantityDelta) +
          " qty=" + String(p.quantityAfter) +
          " backed=" + String(p.basisBackedQuantityAfter) +
          " uncov=" + String(p.uncoveredQuantityAfter) +
          " tx=" + (p.normalizedTransactionId || "").substring(0, 60));
  });

print("");
print("=== Q2. ALL CMETH on FUND first 10 ===");
db.asset_ledger_points
  .find({ walletAddress: "BYBIT:33625378:FUND", assetSymbol: "CMETH" })
  .sort({ replaySequence: 1 })
  .limit(10)
  .forEach((p) => {
    print("  seq=" + p.replaySequence + " ts=" + p.blockTimestamp.toISOString() +
          " eff=" + p.basisEffect +
          " qtyDelta=" + String(p.quantityDelta) +
          " uncovDelta=" + String(p.uncoveredQuantityDelta) +
          " qty=" + String(p.quantityAfter) +
          " backed=" + String(p.basisBackedQuantityAfter) +
          " uncov=" + String(p.uncoveredQuantityAfter) +
          " tx=" + (p.normalizedTransactionId || "").substring(0, 60));
  });

print("");
print("=== Q3. First CMETH-related normalized_transactions across all wallets/sources ===");
db.normalized_transactions.aggregate([
  { $match: { "flows.assetSymbol": { $regex: /^CMETH$/i } } },
  { $sort: { blockTimestamp: 1 } },
  { $limit: 10 },
  { $project: { _id: 1, source: 1, type: 1, blockTimestamp: 1, walletAddress: 1, networkId: 1, correlationId: 1, continuityCandidate: 1, flows: 1 } }
]).forEach((tx) => {
  print("  " + tx.blockTimestamp.toISOString() +
        " source=" + tx.source +
        " type=" + tx.type +
        " wallet=" + (tx.walletAddress || "").substring(0, 30) +
        " net=" + (tx.networkId || "?") +
        " cont=" + tx.continuityCandidate +
        " corr=" + (tx.correlationId || "").substring(0, 50));
  (tx.flows || []).forEach((f) => {
    if (f.assetSymbol && f.assetSymbol.toUpperCase() === "CMETH") {
      print("    flow role=" + f.role +
            " qty=" + String(f.quantityDelta) +
            " unit=" + String(f.unitPriceUsd) +
            " priceSrc=" + f.priceSource +
            " cp=" + (f.counterpartyAddress || "").substring(0, 30) +
            " cpType=" + (f.counterpartyType || ""));
    }
  });
});

print("");
print("Done CMETH lineage.");
