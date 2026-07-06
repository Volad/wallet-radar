// ETH-C8 deep trace — read-only
const UID = "df5e69cc-a0c0-4910-8b7d-74488fa266e2";
const W = "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f".toLowerCase();

function n(v) {
  if (v == null) return 0;
  const x = Number(v);
  return Number.isFinite(x) ? x : 0;
}

function ts(p) {
  return p.blockTimestamp ? new Date(p.blockTimestamp).toISOString().slice(0, 19) : "?";
}

function hash(p) {
  const h = p.txHash || p.transactionHash;
  return h ? String(h).slice(0, 22) : "?";
}

print("=== FEB 2026 LEDGER (0x1a87 ETH family) ===");
const feb = db.asset_ledger_points
  .find({
    accountingUniverseId: UID,
    walletAddress: W,
    assetSymbol: { $in: ["ETH", "WETH", "AMANWETH"] },
    blockTimestamp: { $gte: ISODate("2026-02-01"), $lte: ISODate("2026-02-28T23:59:59Z") },
  })
  .sort({ blockTimestamp: 1, replaySequence: 1 })
  .toArray();

for (const p of feb) {
  print(
    [
      ts(p),
      p.networkId || "?",
      p.assetSymbol,
      p.normalizedType,
      p.basisEffect,
      "dq=" + n(p.quantityDelta).toFixed(4),
      "q=" + n(p.quantityAfter).toFixed(4),
      "bb=" + n(p.basisBackedQuantityAfter).toFixed(4),
      "avcoB=" + n(p.avcoBeforeUsd).toFixed(2),
      "avcoA=" + n(p.avcoAfterUsd).toFixed(2),
      "cbd=" + n(p.costBasisDeltaUsd).toFixed(2),
      "basis=" + n(p.totalCostBasisUsdAfter).toFixed(2),
      hash(p),
    ].join("|")
  );
}

print("\n=== LENDING / CARRY / REALLOCATE (all time) ===");
const carry = db.asset_ledger_points
  .find({
    accountingUniverseId: UID,
    walletAddress: W,
    assetSymbol: { $in: ["ETH", "WETH", "AMANWETH"] },
    $or: [
      { normalizedType: { $in: ["LENDING_DEPOSIT", "LENDING_WITHDRAW", "WRAP", "UNWRAP"] } },
      { basisEffect: { $in: ["CARRY_IN", "CARRY_OUT", "REALLOCATE_IN", "REALLOCATE_OUT"] } },
    ],
  })
  .sort({ blockTimestamp: 1, replaySequence: 1 })
  .toArray();

for (const p of carry) {
  print(
    [
      ts(p).slice(0, 10),
      p.networkId,
      p.assetSymbol,
      p.normalizedType,
      p.basisEffect,
      "dq=" + n(p.quantityDelta).toFixed(4),
      "avcoA=" + n(p.avcoAfterUsd).toFixed(2),
      "basis=" + n(p.totalCostBasisUsdAfter).toFixed(2),
      hash(p),
    ].join("|")
  );
}

print("\n=== AMANWETH LOT FORMATION (first point qty>2) ===");
const aman = db.asset_ledger_points
  .find({
    accountingUniverseId: UID,
    walletAddress: W,
    assetSymbol: "AMANWETH",
    networkId: "MANTLE",
  })
  .sort({ blockTimestamp: 1, replaySequence: 1 })
  .toArray();

let printed = 0;
for (const p of aman) {
  if (n(p.quantityAfter) >= 2.5 || printed < 15) {
    print(
      [
        ts(p),
        p.normalizedType,
        p.basisEffect,
        "q=" + n(p.quantityAfter).toFixed(4),
        "bb=" + n(p.basisBackedQuantityAfter).toFixed(4),
        "avcoA=" + n(p.avcoAfterUsd).toFixed(2),
        "basis=" + n(p.totalCostBasisUsdAfter).toFixed(2),
        hash(p),
      ].join("|")
    );
    printed++;
    if (n(p.quantityAfter) >= 3 && printed > 20) break;
  }
}

print("\n=== WITHDRAW TX 2026-02-19 (search LENDING_WITHDRAW WETH) ===");
const wd = db.asset_ledger_points
  .find({
    accountingUniverseId: UID,
    walletAddress: W,
    assetSymbol: "WETH",
    normalizedType: "LENDING_WITHDRAW",
    basisEffect: "REALLOCATE_IN",
    blockTimestamp: { $gte: ISODate("2026-02-18"), $lte: ISODate("2026-02-20") },
  })
  .sort({ blockTimestamp: 1, replaySequence: 1 })
  .toArray();
printjson(wd.map((p) => ({
  ts: ts(p),
  network: p.networkId,
  dq: n(p.quantityDelta),
  avcoBefore: n(p.avcoBeforeUsd),
  avcoAfter: n(p.avcoAfterUsd),
  cbd: n(p.costBasisDeltaUsd),
  basis: n(p.totalCostBasisUsdAfter),
  bb: n(p.basisBackedQuantityAfter),
  tx: hash(p),
  ntId: p.normalizedTransactionId,
})));

print("\n=== MANTLE WETH deposits before 2026-02-19 ===");
const deps = db.asset_ledger_points
  .find({
    accountingUniverseId: UID,
    walletAddress: W,
    networkId: "MANTLE",
    assetSymbol: { $in: ["WETH", "AMANWETH"] },
    normalizedType: "LENDING_DEPOSIT",
    blockTimestamp: { $lt: ISODate("2026-02-19") },
  })
  .sort({ blockTimestamp: 1, replaySequence: 1 })
  .toArray();
for (const p of deps) {
  print(
    [
      ts(p).slice(0, 10),
      p.assetSymbol,
      "dq=" + n(p.quantityDelta).toFixed(4),
      "avcoA=" + n(p.avcoAfterUsd).toFixed(2),
      "basis=" + n(p.totalCostBasisUsdAfter).toFixed(2),
      hash(p),
    ].join("|")
  );
}

print("\n=== BYBIT corridor CARRY_IN (0xa5e755) ===");
const corr = db.asset_ledger_points
  .find({
    accountingUniverseId: UID,
    txHash: /a5e755/i,
  })
  .sort({ blockTimestamp: 1, replaySequence: 1 })
  .toArray();
for (const p of corr) {
  print(
    [
      p.walletAddress?.slice(0, 12),
      p.assetSymbol,
      p.basisEffect,
      "dq=" + n(p.quantityDelta).toFixed(4),
      "avcoB=" + n(p.avcoBeforeUsd).toFixed(2),
      "avcoA=" + n(p.avcoAfterUsd).toFixed(2),
      "cbd=" + n(p.costBasisDeltaUsd).toFixed(2),
    ].join("|")
  );
}
