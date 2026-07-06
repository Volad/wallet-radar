// DEBUG / Cycle 15 — remove after coverage acceptance.
/**
 * Cycle/15 Stage A: ETH-family supply audit — basis-loss cluster baseline.
 * Read-only diagnostic; no side effects on pipeline.
 * Usage: mongosh <uri> --file scripts/cycle15-eth-supply-audit.mongosh.js
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

const ETH_FAMILY = ["ETH", "WETH", "AWETH", "aArbWETH", "aManWETH", "STETH", "CMETH", "METH"];
const WALLETS = [
  "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f",
  "0xf03b52e8686b962e051a6075a06b96cb8a663021",
  "0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f",
];

print("=== ZKSYNC/UNICHAIN backfill status ===");
["ZKSYNC", "UNICHAIN", "LINEA"].forEach((net) => {
  const seg = db.backfill_segments.countDocuments({ networkId: net, status: "COMPLETE" });
  const tx = db.normalized_transactions.countDocuments({ networkId: net });
  print("  " + net + " segments_complete=" + seg + " normalized_tx=" + tx);
});

print("\n=== Per-wallet per-network ETH-family net flow ===");
WALLETS.forEach((w) => {
  print("wallet " + w.substring(0, 10) + "...");
  const nets = db.normalized_transactions.distinct("networkId", { walletAddress: w });
  nets.forEach((net) => {
    let inAmt = 0,
      outAmt = 0;
    db.normalized_transactions.find({ walletAddress: w, networkId: net }).forEach((t) => {
      (t.flows || []).forEach((f) => {
        if (f.role === "FEE") return;
        const sym = (f.assetSymbol || "").toUpperCase();
        if (!ETH_FAMILY.some((e) => sym === e || sym.includes("WETH"))) return;
        const q = toNum(f.quantityDelta);
        if (q > 0.0001) inAmt += q;
        if (q < -0.0001) outAmt += -q;
      });
    });
    if (inAmt + outAmt > 0.001) {
      const net2 = inAmt - outAmt;
      print(
        "  " +
          String(net).padEnd(12) +
          " in=" +
          inAmt.toFixed(4) +
          " out=" +
          outAmt.toFixed(4) +
          " net=" +
          net2.toFixed(4) +
          (Math.abs(net2) > 0.1 ? " GAP" : "")
      );
    }
  });
});

print("\n=== Z1: Bybit subaccount cont=false (bybit-econ-v1 / stake-pair) ===");
const z1Count = db.normalized_transactions.countDocuments({
  source: "BYBIT",
  continuityCandidate: false,
  correlationId: { $regex: "^bybit-econ-v1:|^bybit-stake-pair-v1:" },
});
print("  cont=false bybit-econ/stake-pair: " + z1Count);

print("\n=== Z2: Mantle 3.06 ETH corridor (Feb 2026) ===");
const mantleOut = db.normalized_transactions.findOne({
  source: "BYBIT",
  type: "EXTERNAL_TRANSFER_OUT",
  networkId: "MANTLE",
  "flows.assetSymbol": "ETH",
  blockTimestamp: { $gte: ISODate("2026-02-19T00:00:00Z") },
});
if (mantleOut) {
  const f = (mantleOut.flows || []).find((x) => x.assetSymbol === "ETH" && x.role !== "FEE");
  print(
    "  OUT cont=" +
      mantleOut.continuityCandidate +
      " corr=" +
      (mantleOut.correlationId || "").substring(0, 40) +
      " qty=" +
      toNum(f && f.quantityDelta)
  );
  const lp = db.asset_ledger_points
    .find({
      walletAddress: "BYBIT:33625378:FUND",
      assetSymbol: "ETH",
      eventType: "CARRY_OUT",
      blockTimestamp: mantleOut.blockTimestamp,
    })
    .toArray();
  lp.forEach((p) =>
    print(
      "  CARRY_OUT qd=" +
        toNum(p.quantityDelta) +
        " bbAfter=" +
        toNum(p.basisBackedQuantityAfter)
    )
  );
}

print("\n=== Z3: BASE 0x1a87 LP_ENTRY vs LP_EXIT ETH-family ===");
const w = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f";
let lpIn = 0,
  lpOut = 0;
db.normalized_transactions
  .find({ walletAddress: w, networkId: "BASE", type: { $in: ["LP_ENTRY", "LP_EXIT"] } })
  .forEach((t) => {
    (t.flows || []).forEach((f) => {
      if (f.role === "FEE") return;
      const sym = (f.assetSymbol || "").toUpperCase();
      if (sym !== "ETH" && sym !== "WETH") return;
      const q = toNum(f.quantityDelta);
      if (q > 0.001) lpIn += q;
      if (q < -0.001) lpOut += -q;
    });
  });
print("  LP_ENTRY OUT=" + lpOut.toFixed(4) + " LP_EXIT IN=" + lpIn.toFixed(4) + " locked=" + (lpOut - lpIn).toFixed(4));

print("\n=== Hotspot coverage (ledger tail) ===");
[
  { wallet: w, net: "MANTLE", asset: "WETH" },
  { wallet: w, net: "BASE", asset: "WETH" },
  { wallet: "BYBIT:33625378:FUND", net: null, asset: "ETH" },
].forEach((row) => {
  const q = { walletAddress: row.wallet, assetSymbol: row.asset };
  if (row.net) q.networkId = row.net;
  const last = db.asset_ledger_points.find(q).sort({ blockTimestamp: -1, replaySequence: -1 }).limit(1).toArray()[0];
  if (last) {
    const qa = toNum(last.quantityAfter);
    const bb = toNum(last.basisBackedQuantityAfter);
    const pct = qa === 0 ? 0 : (bb / qa) * 100;
    print(
      "  " +
        (row.net || "BYBIT") +
        " " +
        row.asset +
        " qa=" +
        qa.toFixed(4) +
        " bb=" +
        bb.toFixed(4) +
        " cov=" +
        pct.toFixed(1) +
        "%"
    );
  }
});

print("\n=== Net Inflow (lifetimeExternalInflowUsd) ===");
const session = db.user_sessions.findOne({});
if (session && session.lifetimeExternalInflowUsd != null) {
  print("  lifetimeExternalInflowUsd=" + toNum(session.lifetimeExternalInflowUsd).toFixed(2));
}
