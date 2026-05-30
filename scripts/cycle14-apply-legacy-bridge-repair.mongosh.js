/**
 * Cycle/14: one-shot Mongo repair for legacy sealed BRIDGE_OUT + same-tx INTERNAL_TRANSFER orphans.
 * Mirrors BridgePairContinuityRepairService / OnChainInternalTransferPairRepairService.
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

function principalFlow(tx, sign) {
  const flows = (tx.flows || []).filter((f) => f.role !== "FEE" && toNum(f.quantityDelta) * sign > 0);
  if (flows.length === 0) return null;
  return flows.sort((a, b) => Math.abs(toNum(b.quantityDelta)) - Math.abs(toNum(a.quantityDelta)))[0];
}

function sameFamily(a, b) {
  if (!a || !b) return false;
  const sa = (a.assetSymbol || "").toUpperCase();
  const sb = (b.assetSymbol || "").toUpperCase();
  if (sa === sb) return true;
  const eth = new Set(["ETH", "WETH", "CMETH", "METH", "STETH"]);
  return eth.has(sa) && eth.has(sb);
}

function retagFlows(tx) {
  let changed = false;
  (tx.flows || []).forEach((f) => {
    if (f.role === "FEE" || toNum(f.quantityDelta) === 0) return;
    if (f.role !== "TRANSFER") {
      f.role = "TRANSFER";
      changed = true;
    }
    if (f.unitPriceUsd != null) {
      f.unitPriceUsd = null;
      changed = true;
    }
    if (f.valueUsd != null) {
      f.valueUsd = null;
      changed = true;
    }
    if (f.priceSource != null) {
      f.priceSource = null;
      changed = true;
    }
    if (f.avcoAtTimeOfSale != null) {
      f.avcoAtTimeOfSale = null;
      changed = true;
    }
    if (f.realisedPnlUsd != null) {
      f.realisedPnlUsd = null;
      changed = true;
    }
  });
  return changed;
}

let bridgeRepaired = 0;
db.normalized_transactions
  .find({ type: "BRIDGE_OUT", continuityCandidate: false, correlationId: { $regex: "^bridge:" } })
  .forEach((out) => {
    const inbound = db.normalized_transactions.findOne({
      correlationId: out.correlationId,
      _id: { $ne: out._id },
      type: { $in: ["BRIDGE_IN", "EXTERNAL_TRANSFER_IN"] },
    });
    if (!inbound) return;
    const fOut = principalFlow(out, -1);
    const fIn = principalFlow(inbound, 1);
    if (!fOut || !fIn || !sameFamily(fOut, fIn)) return;
    out.continuityCandidate = true;
    inbound.continuityCandidate = true;
    retagFlows(out);
    retagFlows(inbound);
    out.status = "PENDING_STAT";
    inbound.status = "PENDING_STAT";
    out.updatedAt = new Date();
    inbound.updatedAt = new Date();
    db.normalized_transactions.replaceOne({ _id: out._id }, out);
    db.normalized_transactions.replaceOne({ _id: inbound._id }, inbound);
    bridgeRepaired++;
  });
print("bridge pairs repaired: " + bridgeRepaired);

let internalRepaired = 0;
const orphans = db.normalized_transactions
  .find({
    source: "ON_CHAIN",
    type: "INTERNAL_TRANSFER",
    continuityCandidate: false,
    $or: [{ correlationId: null }, { correlationId: "" }],
  })
  .toArray();
orphans.forEach((left) => {
  const peers = db.normalized_transactions
    .find({
      txHash: left.txHash,
      networkId: left.networkId,
      walletAddress: { $ne: left.walletAddress },
      type: "INTERNAL_TRANSFER",
      continuityCandidate: false,
    })
    .toArray();
  if (peers.length !== 1) return;
  const right = peers[0];
  const fL = principalFlow(left, left.flows && toNum(left.flows[0].quantityDelta) > 0 ? 1 : -1);
  const fR = principalFlow(right, toNum(right.flows && right.flows[0] ? right.flows[0].quantityDelta : 0) > 0 ? 1 : -1);
  if (!fL || !fR) return;
  if (toNum(fL.quantityDelta) * toNum(fR.quantityDelta) >= 0) return;
  if (!sameFamily(fL, fR)) return;
  const corr = "internal-tx:" + left.networkId + ":" + left.txHash;
  [left, right].forEach((tx, idx) => {
    tx.continuityCandidate = true;
    tx.correlationId = corr;
    tx.matchedCounterparty = idx === 0 ? right.walletAddress : left.walletAddress;
    retagFlows(tx);
    tx.status = "PENDING_STAT";
    tx.updatedAt = new Date();
    db.normalized_transactions.replaceOne({ _id: tx._id }, tx);
  });
  internalRepaired++;
});
print("internal same-tx pairs repaired: " + internalRepaired);

db.user_sessions.updateMany({}, { $unset: { pipelineState: "" } });
print("pipelineState cleared — resume watchdog will run LINKING then REPLAY");
