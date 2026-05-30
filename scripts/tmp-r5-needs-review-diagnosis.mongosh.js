// DEBUG / Round 5 — diagnose NEEDS_REVIEW blocking replay
const db = db.getSiblingDB("walletradar");

print("=== Status breakdown (active accounting) ===");
db.normalized_transactions.aggregate([
  { $match: { $or: [{ excludedFromAccounting: false }, { excludedFromAccounting: { $exists: false } }] } },
  { $group: { _id: "$status", n: { $sum: 1 } } },
  { $sort: { n: -1 } },
]).forEach((r) => print("  " + r._id + ": " + r.n));

print("");
print("=== NEEDS_REVIEW by type (top 20) ===");
db.normalized_transactions.aggregate([
  { $match: { status: "NEEDS_REVIEW", $or: [{ excludedFromAccounting: false }, { excludedFromAccounting: { $exists: false } }] } },
  { $group: { _id: "$type", n: { $sum: 1 } } },
  { $sort: { n: -1 } },
  { $limit: 20 },
]).forEach((r) => print("  " + r._id + ": " + r.n));

print("");
print("=== NEEDS_REVIEW missingDataReasons (unwind top) ===");
db.normalized_transactions.aggregate([
  { $match: { status: "NEEDS_REVIEW" } },
  { $unwind: "$missingDataReasons" },
  { $group: { _id: "$missingDataReasons", n: { $sum: 1 } } },
  { $sort: { n: -1 } },
  { $limit: 25 },
]).forEach((r) => print("  " + r._id + ": " + r.n));

print("");
print("=== PENDING_STAT count + PRICE_UNRESOLVABLE ===");
const pendingStat = db.normalized_transactions.countDocuments({ status: "PENDING_STAT" });
const pendingPrice = db.normalized_transactions.countDocuments({
  status: "PENDING_STAT",
  missingDataReasons: "PRICE_UNRESOLVABLE",
});
print("  PENDING_STAT: " + pendingStat);
print("  PENDING_STAT + PRICE_UNRESOLVABLE: " + pendingPrice);

print("");
print("=== CMETH INTERNAL_TRANSFER stat blockers ===");
db.normalized_transactions.find({
  status: { $in: ["NEEDS_REVIEW", "PENDING_STAT"] },
  type: "INTERNAL_TRANSFER",
  "flows.assetSymbol": "CMETH",
}).limit(5).forEach((tx) => {
  print("  id=" + (tx._id || "").substring(0, 60));
  print("    status=" + tx.status + " reasons=" + JSON.stringify(tx.missingDataReasons));
  (tx.flows || []).forEach((f) => {
    if (f && /cmeth/i.test(f.assetSymbol || "")) {
      print("      CMETH role=" + f.role + " qty=" + f.quantityDelta + " unit=" + f.unitPriceUsd + " src=" + f.priceSource);
    }
  });
});

print("");
print("=== STAT_FLOW_PRICE_MISSING samples (5) ===");
db.normalized_transactions.find({
  status: "NEEDS_REVIEW",
  missingDataReasons: "STAT_FLOW_PRICE_MISSING",
}).limit(5).forEach((tx) => {
  print("  type=" + tx.type + " wallet=" + (tx.walletAddress || "").substring(0, 30));
  print("    reasons=" + JSON.stringify(tx.missingDataReasons));
  (tx.flows || []).forEach((f) => {
    if (f && f.role !== "FEE" && f.quantityDelta && Math.abs(f.quantityDelta) > 0) {
      print("      " + f.role + " " + f.assetSymbol + " unit=" + f.unitPriceUsd + " src=" + f.priceSource);
    }
  });
});

print("");
print("=== asset_ledger_points / pipeline state ===");
print("  ledger points: " + db.asset_ledger_points.countDocuments({}));
const sess = db.user_sessions.findOne({}, { pipelineState: 1, id: 1 });
if (sess) print("  session pipeline: " + JSON.stringify(sess.pipelineState));

print("");
print("Done diagnosis.");
