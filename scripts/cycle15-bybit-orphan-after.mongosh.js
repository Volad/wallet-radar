// DEBUG / Cycle 15 — remove after coverage acceptance.
/**
 * Read-only: Bybit bybit-econ-v1 orphans and rekeyed pair counts after Round 3 pairing.
 * Usage: mongosh <uri> --file scripts/cycle15-bybit-orphan-after.mongosh.js
 */
const db = db.getSiblingDB("walletradar");

print("=== bybit-econ-v1 cont=false orphans (not excluded) ===");
const orphans = db.normalized_transactions.countDocuments({
  source: "BYBIT",
  correlationId: /^bybit-econ-v1:/,
  continuityCandidate: { $ne: true },
  excludedFromAccounting: { $ne: true },
});
print("  count=" + orphans);

print("");
print("=== bybit-rekeyed-v1 paired internals ===");
const rekeyed = db.normalized_transactions.countDocuments({
  source: "BYBIT",
  correlationId: /^bybit-rekeyed-v1:/,
  continuityCandidate: true,
  type: "INTERNAL_TRANSFER",
});
print("  count=" + rekeyed);

print("");
print("=== BYBIT_STREAM_MIRROR_SAME_SIGN exclusions ===");
const sameSign = db.normalized_transactions.countDocuments({
  source: "BYBIT",
  accountingExclusionReason: "BYBIT_STREAM_MIRROR_SAME_SIGN",
});
print("  count=" + sameSign);

print("");
print("Done.");
