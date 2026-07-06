// DEBUG / Cycle 15 Round 5 Phase A2 — lineage trace for top uncov clusters
// READ-ONLY. Remove after audit.
const db = db.getSiblingDB("walletradar");

function traceCluster(wallet, network, asset, opts) {
  opts = opts || {};
  print("");
  print("=========================================================================");
  print("CLUSTER: " + wallet + " | " + network + " | " + asset);
  print("=========================================================================");

  const filter = { walletAddress: wallet, assetSymbol: asset };
  if (network && network !== "?") filter.networkId = network;

  // first ever event for this cluster
  const first = db.asset_ledger_points.find(filter).sort({ replaySequence: 1 }).limit(1).toArray()[0];
  const last = db.asset_ledger_points.find(filter).sort({ replaySequence: -1 }).limit(1).toArray()[0];
  if (!first || !last) {
    print("  (cluster has no points — odd)");
    return;
  }
  print("  total points: " + db.asset_ledger_points.countDocuments(filter));
  print("  first: " + first.blockTimestamp.toISOString() + " seq=" + first.replaySequence + " eff=" + first.basisEffect);
  print("  last:  " + last.blockTimestamp.toISOString() + " seq=" + last.replaySequence + " eff=" + last.basisEffect +
        " | qty=" + String(last.quantityAfter) +
        " backed=" + String(last.basisBackedQuantityAfter) +
        " uncov=" + String(last.uncoveredQuantityAfter) +
        " basisUsd=" + String(last.totalCostBasisAfterUsd));

  // find events that CREATED new uncov (uncoveredQuantityDelta > 0)
  const uncovCreators = db.asset_ledger_points
    .find(Object.assign({}, filter, { uncoveredQuantityDelta: { $gt: 0 } }))
    .sort({ replaySequence: 1 })
    .toArray();
  print("  uncov-creating events count: " + uncovCreators.length);

  // top 5 biggest uncov creators
  const sorted = uncovCreators.slice().sort((a, b) => Number(b.uncoveredQuantityDelta) - Number(a.uncoveredQuantityDelta));
  print("  top 5 by uncoveredQuantityDelta:");
  sorted.slice(0, 5).forEach((p) => {
    print("    " + p.blockTimestamp.toISOString() +
          " seq=" + p.replaySequence +
          " eff=" + p.basisEffect +
          " qtyDelta=" + String(p.quantityDelta) +
          " uncovDelta=" + String(p.uncoveredQuantityDelta) +
          " uncovAfter=" + String(p.uncoveredQuantityAfter) +
          " qtyAfter=" + String(p.quantityAfter) +
          " tx=" + (p.normalizedTransactionId || "").substring(0, 50));
  });

  // first uncov-creating event (the primary root)
  if (uncovCreators.length > 0) {
    const primary = uncovCreators[0];
    print("");
    print("  PRIMARY UNCOV ROOT EVENT:");
    print("    ts=" + primary.blockTimestamp.toISOString());
    print("    seq=" + primary.replaySequence + " eff=" + primary.basisEffect);
    print("    qtyDelta=" + String(primary.quantityDelta));
    print("    uncovDelta=" + String(primary.uncoveredQuantityDelta));
    print("    tx=" + primary.normalizedTransactionId);

    const primaryTx = db.normalized_transactions.findOne({ _id: primary.normalizedTransactionId });
    if (primaryTx) {
      print("    tx type=" + primaryTx.type + " corr=" + primaryTx.correlationId + " cont=" + primaryTx.continuityCandidate);
      print("    tx matchedCounterparty=" + primaryTx.matchedCounterparty);
      print("    tx pairing=" + JSON.stringify(primaryTx.pairing));
      print("    tx flows (" + (primaryTx.flows || []).length + "):");
      (primaryTx.flows || []).forEach((f) => {
        print("      role=" + f.role +
              " sym=" + f.assetSymbol +
              " qty=" + String(f.quantityDelta) +
              " unit=" + String(f.unitPriceUsd) +
              " value=" + String(f.valueUsd) +
              " priceSrc=" + f.priceSource +
              " cpAddress=" + (f.counterpartyAddress || "").substring(0, 30) +
              " cpType=" + (f.counterpartyType || ""));
      });
    } else {
      print("    tx NOT FOUND in normalized_transactions");
    }
  }

  // also check last 5 uncov-creating events (recent ones)
  print("");
  print("  last 5 uncov-creating events (most recent):");
  const recent = uncovCreators.slice(-5).reverse();
  recent.forEach((p) => {
    print("    " + p.blockTimestamp.toISOString() +
          " seq=" + p.replaySequence +
          " eff=" + p.basisEffect +
          " qtyDelta=" + String(p.quantityDelta) +
          " uncovDelta=" + String(p.uncoveredQuantityDelta) +
          " tx=" + (p.normalizedTransactionId || "").substring(0, 50));
  });
}

// === Top 6 clusters from A1 ===
traceCluster("0xf03b52e8686b962e051a6075a06b96cb8a663021", "ARBITRUM", "ETH");
traceCluster("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f", "MANTLE", "AMANWETH");
traceCluster("BYBIT:33625378:FUND", null, "CMETH");
traceCluster("0xf03b52e8686b962e051a6075a06b96cb8a663021", "AVALANCHE", "AAVAUSDC");
traceCluster("BYBIT:33625378:FUND", null, "ETH");
traceCluster("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f", "AVALANCHE", "AAVE GHO/USDT/USDC");
traceCluster("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f", "AVALANCHE", "AAVE GHO/USDT/USDC-GAUGE");
traceCluster("BYBIT:33625378:EARN", null, "LINK");
traceCluster("BYBIT:33625378:EARN", null, "LDO");

print("");
print("Done A2.");
