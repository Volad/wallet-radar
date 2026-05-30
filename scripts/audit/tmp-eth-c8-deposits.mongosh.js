const UID = "df5e69cc-a0c0-4910-8b7d-74488fa266e2";
const W = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f".toLowerCase();
const WITHDRAW_TX = "0xe564fec189ce15b308d4";

function n(v) {
  if (v == null) return 0;
  const x = Number(v);
  return Number.isFinite(x) ? x : 0;
}

function ts(p) {
  return p.blockTimestamp ? new Date(p.blockTimestamp).toISOString().slice(0, 19) : "?";
}

print("=== ARB WETH/ETH/A*WETH ledger around withdraw ===");
const arbEth = db.asset_ledger_points
  .find({
    accountingUniverseId: UID,
    walletAddress: W,
    networkId: "ARBITRUM",
    assetSymbol: { $regex: /^(ETH|WETH|A.*WETH)$/i },
    blockTimestamp: { $gte: ISODate("2025-12-01"), $lte: ISODate("2026-02-20") },
  })
  .sort({ blockTimestamp: 1, replaySequence: 1 })
  .toArray();

for (const p of arbEth) {
  if (
    ["LENDING_DEPOSIT", "LENDING_WITHDRAW", "WRAP", "UNWRAP", "SWAP", "BRIDGE_IN", "BRIDGE_OUT", "INTERNAL_TRANSFER"].includes(
      p.normalizedType
    ) ||
    ["REALLOCATE_IN", "REALLOCATE_OUT", "CARRY_IN", "CARRY_OUT", "ACQUIRE"].includes(p.basisEffect)
  ) {
    print(
      [
        ts(p),
        p.assetSymbol,
        p.normalizedType,
        p.basisEffect,
        "dq=" + n(p.quantityDelta).toFixed(4),
        "q=" + n(p.quantityAfter).toFixed(4),
        "bb=" + n(p.basisBackedQuantityAfter).toFixed(4),
        "avcoA=" + n(p.avcoAfterUsd).toFixed(2),
        "cbd=" + n(p.costBasisDeltaUsd).toFixed(2),
        (p.txHash || "").slice(0, 18),
      ].join("|")
    );
  }
}

print("\n=== WETH bucket state just BEFORE withdraw tx ===");
const beforeWd = db.asset_ledger_points
  .find({
    accountingUniverseId: UID,
    walletAddress: W,
    networkId: "ARBITRUM",
    assetSymbol: "WETH",
    blockTimestamp: { $lt: ISODate("2026-02-19T07:45:56Z") },
  })
  .sort({ blockTimestamp: -1, replaySequence: -1 })
  .limit(5)
  .toArray();
printjson(
  beforeWd.map((p) => ({
    ts: ts(p),
    q: n(p.quantityAfter),
    bb: n(p.basisBackedQuantityAfter),
    avco: n(p.avcoAfterUsd),
    type: p.normalizedType,
    effect: p.basisEffect,
    tx: p.txHash,
  }))
);

print("\n=== Aave receipt symbols on ARB (any) ===");
const aaveSyms = db.asset_ledger_points.distinct("assetSymbol", {
  accountingUniverseId: UID,
  walletAddress: W,
  networkId: "ARBITRUM",
  assetSymbol: { $regex: /^A/i },
});
printjson(aaveSyms);

for (const sym of aaveSyms.filter((s) => /ETH|WETH/i.test(s))) {
  print("\n--- " + sym + " tail before 2026-02-19 ---");
  const tail = db.asset_ledger_points
    .find({
      accountingUniverseId: UID,
      walletAddress: W,
      networkId: "ARBITRUM",
      assetSymbol: sym,
      blockTimestamp: { $lt: ISODate("2026-02-19") },
    })
    .sort({ blockTimestamp: -1, replaySequence: -1 })
    .limit(3)
    .toArray();
  for (const p of tail) {
    print(
      ts(p) +
        " q=" +
        n(p.quantityAfter).toFixed(4) +
        " bb=" +
        n(p.basisBackedQuantityAfter).toFixed(4) +
        " avco=" +
        n(p.avcoAfterUsd).toFixed(2) +
        " " +
        p.normalizedType +
        "/" +
        p.basisEffect
    );
  }
}

print("\n=== Normalized tx withdraw 0xe564 ===");
const nt = db.normalized_transactions.find({
  walletAddress: W,
  transactionHash: { $regex: /^0xe564fec189ce15b308d4/i },
}).toArray();
for (const t of nt) {
  printjson({
    hash: t.transactionHash,
    type: t.type,
    status: t.status,
    network: t.networkId,
    flows: (t.flows || []).map((f) => ({
      sym: f.assetSymbol,
      dir: f.direction,
      qty: f.quantity,
      usd: f.usdValue,
    })),
    clarification: t.clarificationLifecycle,
    linking: t.linkingStatus,
  });
}

print("\n=== BYBIT ETH corridor source (0xa5e755) ===");
const bybit = db.asset_ledger_points
  .find({
    accountingUniverseId: UID,
    walletAddress: { $regex: /^BYBIT/i },
    assetSymbol: "ETH",
    blockTimestamp: { $gte: ISODate("2026-02-01"), $lte: ISODate("2026-02-25") },
    $or: [
      { basisEffect: { $in: ["CARRY_OUT", "CARRY_IN", "REALLOCATE_IN", "REALLOCATE_OUT"] } },
      { normalizedType: { $in: ["INTERNAL_TRANSFER", "LENDING_WITHDRAW", "LENDING_DEPOSIT"] } },
    ],
  })
  .sort({ blockTimestamp: 1, replaySequence: 1 })
  .toArray();
for (const p of bybit) {
  if (Math.abs(n(p.quantityDelta)) > 0.01 || p.basisEffect === "CARRY_OUT") {
    print(
      [
        ts(p).slice(0, 10),
        p.walletAddress,
        p.normalizedType,
        p.basisEffect,
        "dq=" + n(p.quantityDelta).toFixed(4),
        "avcoB=" + n(p.avcoBeforeUsd).toFixed(2),
        "avcoA=" + n(p.avcoAfterUsd).toFixed(2),
        "cbd=" + n(p.costBasisDeltaUsd).toFixed(2),
        (p.txHash || "").slice(0, 18),
      ].join("|")
    );
  }
}

print("\n=== Independent lot AVCO recompute: ARB ETH deposits into lending (2025+) ===");
// Sum basis deltas on ETH bucket for material ACQUIRE/SWAP/BRIDGE before withdraw
let ethBasisIn = 0;
let ethQtyIn = 0;
const ethPts = db.asset_ledger_points
  .find({
    accountingUniverseId: UID,
    walletAddress: W,
    networkId: "ARBITRUM",
    assetSymbol: "ETH",
    blockTimestamp: { $gte: ISODate("2025-01-01"), $lt: ISODate("2026-02-19T07:45:56Z") },
    basisEffect: { $in: ["ACQUIRE", "SWAP", "CARRY_IN", "REALLOCATE_IN", "BRIDGE_IN"] },
    quantityDelta: { $gt: 0 },
  })
  .sort({ blockTimestamp: 1, replaySequence: 1 })
  .toArray();

for (const p of ethPts) {
  const dq = n(p.quantityDelta);
  const cbd = n(p.costBasisDeltaUsd);
  if (dq > 0.0001 && cbd > 0) {
    ethBasisIn += cbd;
    ethQtyIn += dq;
    if (dq > 0.05) {
      print(ts(p).slice(0, 10) + " +" + dq.toFixed(4) + " ETH basis+" + cbd.toFixed(2) + " avcoA=" + n(p.avcoAfterUsd).toFixed(2));
    }
  }
}
print("ETH acquire basis sum: $" + ethBasisIn.toFixed(2) + " qty: " + ethQtyIn.toFixed(4) + " implied avg: $" + (ethQtyIn > 0 ? ethBasisIn / ethQtyIn : 0).toFixed(2));
