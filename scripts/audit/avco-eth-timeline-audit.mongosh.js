// ETH family AVCO timeline audit (read-only).
// Usage:
//   docker exec walletradar-mongodb-prod mongosh walletradar --quiet \
//     --file scripts/audit/avco-eth-timeline-audit.mongosh.js

const db = db.getSiblingDB("walletradar");

function env(key, fallback) {
  try {
    const v = process.env[key];
    return v == null || String(v).trim() === "" ? fallback : String(v);
  } catch (e) {
    return fallback;
  }
}

const SESSION_ID = env("AUDIT_SESSION_ID", "df5e69cc-a0c0-4910-8b7d-74488fa266e2");
const MAIN_WALLET = env("AUDIT_MAIN_WALLET", "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f").toLowerCase();
const BYBIT_UMBRELLA = env("AUDIT_BYBIT_UMBRELLA", "BYBIT:33625378");
const SPOT_EXCLUDED = new Set(["CMETH", "METH", "WEETH", "BBSOL", "STETH"]);

const session = db.user_sessions.findOne({ _id: SESSION_ID }, { accountingUniverseId: 1 });
const universeId =
  env("AUDIT_UNIVERSE_ID", session && session.accountingUniverseId ? String(session.accountingUniverseId) : SESSION_ID);

function n(v) {
  if (v == null) return 0;
  const x = Number(v);
  return Number.isFinite(x) ? x : 0;
}

function isLpReceipt(symbol) {
  return symbol != null && String(symbol).startsWith("LP-RECEIPT:");
}

function includeSpotTimeline(symbol) {
  if (!symbol) return false;
  const s = String(symbol).toUpperCase();
  if (isLpReceipt(s)) return false;
  if (SPOT_EXCLUDED.has(s)) return false;
  return ["ETH", "WETH"].includes(s) || (s.startsWith("A") && s.endsWith("ETH"));
}

function avcoFromPoint(p) {
  const bb = n(p.basisBackedQuantityAfter);
  const basis = n(p.totalCostBasisUsdAfter);
  if (bb > 0 && basis > 0) return basis / bb;
  const av = p.avcoAfterUsd;
  return av != null ? n(av) : null;
}

function tailPoints(filter, limit) {
  return db.asset_ledger_points
    .find(filter, {
      sort: { blockTimestamp: -1, replaySequence: -1 },
      limit: limit || 5,
      projection: {
        walletAddress: 1,
        assetSymbol: 1,
        accountingAssetIdentity: 1,
        accountingFamilyIdentity: 1,
        basisEffect: 1,
        normalizedType: 1,
        quantityDelta: 1,
        quantityAfter: 1,
        basisBackedQuantityAfter: 1,
        uncoveredQuantityAfter: 1,
        uncoveredQuantityDelta: 1,
        costBasisDeltaUsd: 1,
        avcoBeforeUsd: 1,
        avcoAfterUsd: 1,
        totalCostBasisUsdAfter: 1,
        blockTimestamp: 1,
        txHash: 1,
        normalizedTransactionId: 1,
        replaySequence: 1,
      },
    })
    .toArray();
}

function reconstructSpotAvco(walletFilter, symbols) {
  const pts = db.asset_ledger_points
    .find(
      {
        accountingUniverseId: universeId,
        walletAddress: walletFilter,
        assetSymbol: { $in: symbols },
        basisEffect: { $in: ["ACQUIRE", "DISPOSE", "SWAP", "CARRY_IN", "CARRY_OUT", "REALLOCATE_IN", "REALLOCATE_OUT"] },
      },
      {
        sort: { blockTimestamp: 1, replaySequence: 1 },
        projection: {
          assetSymbol: 1,
          basisEffect: 1,
          quantityDelta: 1,
          costBasisDeltaUsd: 1,
          uncoveredQuantityDelta: 1,
          avcoAfterUsd: 1,
          blockTimestamp: 1,
          normalizedType: 1,
          txHash: 1,
        },
      }
    )
    .toArray();

  let q = 0;
  let u = 0;
  let b = 0;
  const tails = {};
  for (const p of pts) {
    q += n(p.quantityDelta);
    u += n(p.uncoveredQuantityDelta);
    b += n(p.costBasisDeltaUsd);
    const sym = p.assetSymbol;
    if (!tails[sym]) tails[sym] = { q: 0, u: 0, b: 0, lastAvco: null };
    tails[sym].q += n(p.quantityDelta);
    tails[sym].u += n(p.uncoveredQuantityDelta);
    tails[sym].b += n(p.costBasisDeltaUsd);
    if (p.avcoAfterUsd != null) tails[sym].lastAvco = n(p.avcoAfterUsd);
  }
  const covered = q - u;
  return {
    wallet: walletFilter,
    symbols,
    pointCount: pts.length,
    runningQty: q,
    runningUncov: u,
    runningBasis: b,
    reconstructedAvco: covered > 1e-12 ? b / covered : null,
    perSymbol: Object.fromEntries(
      Object.entries(tails).map(([sym, t]) => {
        const cov = t.q - t.u;
        return [
          sym,
          {
            qty: t.q,
            uncov: t.u,
            basis: t.b,
            avcoFromDeltas: cov > 1e-12 ? t.b / cov : null,
            lastLedgerAvco: t.lastAvco,
          },
        ];
      })
    ),
  };
}

function familyAggregate(familyId) {
  const pts = db.asset_ledger_points
    .find({ accountingUniverseId: universeId, accountingFamilyIdentity: familyId })
    .sort({ blockTimestamp: 1, replaySequence: 1 })
    .toArray();
  let qAll = 0,
    uAll = 0,
    bAll = 0;
  let qSpot = 0,
    uSpot = 0,
    bSpot = 0;
  let qLp = 0,
    uLp = 0,
    bLp = 0;
  const avcoSamples = [];
  for (const p of pts) {
    const sym = p.assetSymbol;
    qAll += n(p.quantityDelta);
    uAll += n(p.uncoveredQuantityDelta);
    bAll += n(p.costBasisDeltaUsd);
    if (isLpReceipt(sym)) {
      qLp += n(p.quantityDelta);
      uLp += n(p.uncoveredQuantityDelta);
      bLp += n(p.costBasisDeltaUsd);
    } else if (includeSpotTimeline(sym)) {
      qSpot += n(p.quantityDelta);
      uSpot += n(p.uncoveredQuantityDelta);
      bSpot += n(p.costBasisDeltaUsd);
      if (p.avcoAfterUsd != null && n(p.avcoAfterUsd) > 0) avcoSamples.push(n(p.avcoAfterUsd));
    }
  }
  avcoSamples.sort((a, b) => a - b);
  const median =
    avcoSamples.length === 0
      ? null
      : avcoSamples.length % 2 === 1
        ? avcoSamples[(avcoSamples.length - 1) / 2]
        : (avcoSamples[avcoSamples.length / 2 - 1] + avcoSamples[avcoSamples.length / 2]) / 2;
  const covAll = qAll - uAll;
  const covSpot = qSpot - uSpot;
  return {
    familyId,
    totalPoints: pts.length,
    familyAvcoAllMembers: covAll > 1e-12 ? bAll / covAll : null,
    spotOnlyAvcoFromDeltas: covSpot > 1e-12 ? bSpot / covSpot : null,
    spotQty: qSpot,
    lpReceiptQty: qLp,
    medianSpotPointAvco: median,
    avcoMin: avcoSamples.length ? avcoSamples[0] : null,
    avcoMax: avcoSamples.length ? avcoSamples[avcoSamples.length - 1] : null,
    avcoSampleCount: avcoSamples.length,
  };
}

function clusterSamples() {
  const anchors = [
    { label: "lp_reallocate", tx: /0xc17e7b91/i },
    { label: "cmeth_carry", id: /57a3f2ba/i },
    { label: "eth_acquire_tail", tx: /0x49366a1e/i },
    { label: "earn_ae372912", id: /ae372912/i },
    { label: "earn_1fff0ae8", id: /1fff0ae8/i },
  ];
  const out = [];
  for (const a of anchors) {
    const filter = { accountingUniverseId: universeId, accountingFamilyIdentity: "FAMILY:ETH" };
    if (a.tx) filter.txHash = a.tx;
    if (a.id) filter.normalizedTransactionId = a.id;
    const pts = db.asset_ledger_points.find(filter).sort({ replaySequence: 1 }).toArray();
    out.push({
      anchor: a.label,
      matchCount: pts.length,
      legs: pts.map((p) => ({
        wallet: p.walletAddress,
        symbol: p.assetSymbol,
        effect: p.basisEffect,
        type: p.normalizedType,
        qtyD: n(p.quantityDelta),
        uncovD: n(p.uncoveredQuantityDelta),
        basisD: n(p.costBasisDeltaUsd),
        avcoBefore: p.avcoBeforeUsd != null ? n(p.avcoBeforeUsd) : null,
        avcoAfter: p.avcoAfterUsd != null ? n(p.avcoAfterUsd) : null,
        bbAfter: n(p.basisBackedQuantityAfter),
        uncovAfter: n(p.uncoveredQuantityAfter),
        family: p.accountingFamilyIdentity,
      })),
    });
  }
  return out;
}

const report = {
  generatedAt: new Date().toISOString(),
  universeId,
  sessionId: SESSION_ID,
  mainWallet: MAIN_WALLET,
  tails: {
    mainEth: tailPoints({
      accountingUniverseId: universeId,
      walletAddress: MAIN_WALLET,
      assetSymbol: "ETH",
    }),
    mainWeth: tailPoints({
      accountingUniverseId: universeId,
      walletAddress: MAIN_WALLET,
      assetSymbol: "WETH",
    }),
    bybitEth: tailPoints({
      accountingUniverseId: universeId,
      walletAddress: BYBIT_UMBRELLA,
      assetSymbol: "ETH",
    }),
    bybitCmeth: tailPoints({
      accountingUniverseId: universeId,
      walletAddress: BYBIT_UMBRELLA,
      assetSymbol: "CMETH",
    }),
  },
  reconstruction: {
    mainWalletEthWeth: reconstructSpotAvco(MAIN_WALLET, ["ETH", "WETH"]),
    bybitUmbrellaEth: reconstructSpotAvco(BYBIT_UMBRELLA, ["ETH"]),
    bybitEarnCmeth: reconstructSpotAvco("BYBIT:33625378:EARN", ["CMETH"]),
  },
  family: {
    eth: familyAggregate("FAMILY:ETH"),
    lpReceipt: familyAggregate("FAMILY:LP_RECEIPT"),
  },
  counts: {
    ethFamilyPoints: db.asset_ledger_points.countDocuments({
      accountingUniverseId: universeId,
      accountingFamilyIdentity: "FAMILY:ETH",
    }),
    lpReceiptFamilyPoints: db.asset_ledger_points.countDocuments({
      accountingUniverseId: universeId,
      accountingFamilyIdentity: "FAMILY:LP_RECEIPT",
    }),
    ethFamilyStillLpTagged: db.asset_ledger_points.countDocuments({
      accountingUniverseId: universeId,
      accountingFamilyIdentity: "FAMILY:ETH",
      assetSymbol: /^LP-RECEIPT:/,
    }),
    bybitLendingWithdrawUncov: db.asset_ledger_points.countDocuments({
      accountingUniverseId: universeId,
      walletAddress: /^BYBIT:/i,
      normalizedType: "LENDING_WITHDRAW",
      basisEffect: "REALLOCATE_IN",
      uncoveredQuantityDelta: { $gt: 0 },
    }),
  },
  anchorClusters: clusterSamples(),
  lpOpen: db.asset_ledger_points
    .find(
      {
        accountingUniverseId: universeId,
        assetSymbol: /^LP-RECEIPT:/,
        quantityAfter: { $gt: 0 },
      },
      { sort: { blockTimestamp: -1 }, limit: 10, projection: { assetSymbol: 1, walletAddress: 1, quantityAfter: 1, blockTimestamp: 1 } }
    )
    .toArray(),
};

printjson(report);
