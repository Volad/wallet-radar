// DEBUG / Cycle 15 — remove after coverage acceptance.
// SUPERSEDED: pairByEconomicFingerprint reverted in round 2 (false-positive risk
// on stream-mirror duplicates). Kept only for forensic reference.
/**
 * Cycle/15 one-shot (DEPRECATED): re-key Bybit sub-account legs with drifted bybit-econ-v1 corr ids.
 * Usage: mongosh <uri> --file scripts/cycle15-rekey-bybit-econ-orphans.mongosh.js
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

function principalFlow(tx) {
  return (tx.flows || []).find((f) => f.role !== "FEE" && toNum(f.quantityDelta) !== 0);
}

function extractUid(wallet) {
  if (!wallet || !wallet.startsWith("BYBIT:")) return null;
  const r = wallet.substring(6);
  const c = r.indexOf(":");
  return c >= 0 ? r.substring(0, c) : r;
}

function economicFingerprint(tx) {
  const f = principalFlow(tx);
  if (!f) return null;
  const uid = extractUid(tx.walletAddress);
  if (!uid) return null;
  const absQty = Math.abs(toNum(f.quantityDelta)).toFixed(10).replace(/\.?0+$/, "");
  const sym = (f.assetSymbol || "?").toUpperCase();
  return uid + "|" + sym + "|" + absQty;
}

const DRIFT_SEC = 120;
const candidates = db.normalized_transactions
  .find({
    source: "BYBIT",
    excludedFromAccounting: { $ne: true },
    matchedCounterparty: { $regex: "^BYBIT:" },
    $or: [
      { continuityCandidate: false },
      { correlationId: { $regex: "^bybit-econ-v1:|^bybit-stake-pair-v1:" } },
    ],
  })
  .toArray();

const grouped = {};
candidates.forEach((tx) => {
  const key = economicFingerprint(tx);
  if (!key) return;
  if (!grouped[key]) grouped[key] = [];
  grouped[key].push(tx);
});

let rewrites = 0;
const paired = new Set();

Object.values(grouped).forEach((bucket) => {
  if (bucket.length < 2) return;
  bucket.sort((a, b) => a.blockTimestamp - b.blockTimestamp);
  for (let i = 0; i < bucket.length; i++) {
    const left = bucket[i];
    if (paired.has(left._id)) continue;
    const lf = principalFlow(left);
    const leftSign = toNum(lf.quantityDelta) > 0 ? 1 : -1;
    let best = null;
    let bestDelta = DRIFT_SEC + 1;
    for (let j = i + 1; j < bucket.length; j++) {
      const right = bucket[j];
      if (paired.has(right._id)) continue;
      if (left.walletAddress === right.walletAddress) continue;
      if (extractUid(left.walletAddress) !== extractUid(right.walletAddress)) continue;
      const rf = principalFlow(right);
      const rightSign = toNum(rf.quantityDelta) > 0 ? 1 : -1;
      if (rightSign === leftSign) continue;
      const sum = toNum(lf.quantityDelta) + toNum(rf.quantityDelta);
      const denom = Math.max(Math.abs(toNum(lf.quantityDelta)), Math.abs(toNum(rf.quantityDelta)));
      if (denom === 0 || Math.abs(sum) / denom > 1e-6) continue;
      const delta = Math.abs((left.blockTimestamp - right.blockTimestamp) / 1000);
      if (delta > DRIFT_SEC) continue;
      if (delta < bestDelta) {
        bestDelta = delta;
        best = right;
      }
    }
    if (!best) return;
    const low = left._id < best._id ? left._id : best._id;
    const high = low === left._id ? best._id : left._id;
    const corr = "bybit-rekeyed-v1:" + hexSha256(low + "|" + high);
    [left, best].forEach((tx) => {
      const partner = tx === left ? best.walletAddress : left.walletAddress;
      db.normalized_transactions.updateOne(
        { _id: tx._id },
        {
          $set: {
            correlationId: corr,
            continuityCandidate: true,
            matchedCounterparty: partner,
            type: "INTERNAL_TRANSFER",
            status: "PENDING_STAT",
            updatedAt: new Date(),
          },
        }
      );
      (tx.flows || []).forEach((f, idx) => {
        if (f.role !== "FEE") {
          db.normalized_transactions.updateOne(
            { _id: tx._id },
            { $set: { ["flows." + idx + ".role"]: "TRANSFER" } }
          );
        }
      });
    });
    paired.add(left._id);
    paired.add(best._id);
    rewrites += 2;
  }
});

function hexSha256(s) {
  // mongosh has no crypto in all versions — use deterministic placeholder from ids
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
  return ("00000000" + h.toString(16)).slice(-8) + s.length.toString(16);
}

print("BYBIT_ECONOMIC_FINGERPRINT_REKEY rewrites=" + rewrites);
print(
  "remaining cont=false bybit-econ=" +
    db.normalized_transactions.countDocuments({
      source: "BYBIT",
      continuityCandidate: false,
      correlationId: { $regex: "^bybit-econ-v1:" },
    })
);
