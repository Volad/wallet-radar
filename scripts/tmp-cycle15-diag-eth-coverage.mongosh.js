// Read-only diagnostics for Cycle 15 Round 3 ETH coverage gap.

const db = db.getSiblingDB('walletradar');

function dec(v) {
  if (v === null || v === undefined) return 0;
  try {
    if (typeof v === 'number') return v;
    if (typeof v.toString === 'function') {
      const s = v.toString();
      const n = Number(s);
      return Number.isFinite(n) ? n : 0;
    }
    return Number(v) || 0;
  } catch (e) { return 0; }
}
function decstr(v) {
  if (v === null || v === undefined) return '0';
  try { return v.toString(); } catch (e) { return String(v); }
}

print('======================================================================');
print('SECTION A. Universes / wallets in scope');
print('======================================================================');
const sessions = db.user_sessions.find({}, { _id: 1, accountingUniverseId: 1, wallets: 1, integrations: 1 }).toArray();
sessions.forEach(s => {
  print(`Session/Universe: ${s._id}`);
  if (Array.isArray(s.wallets)) {
    s.wallets.forEach(w => print(`  on-chain wallet ${w.address}  label="${w.label}"  networks=${(w.networks||[]).join(',')}`));
  }
  if (Array.isArray(s.integrations)) {
    s.integrations.forEach(i => print(`  integration ${i.integrationId}  provider=${i.provider}  accountRef=${i.accountRef}`));
  }
});

print('');
print('======================================================================');
print('SECTION B. Latest ETH-family ledger points per (wallet, network, asset)');
print('======================================================================');

const latestEth = db.asset_ledger_points.aggregate([
  { $match: { accountingFamilyIdentity: 'FAMILY:ETH' } },
  { $sort: { replaySequence: -1 } },
  { $group: {
      _id: { wallet: '$walletAddress', network: { $ifNull: ['$networkId', '__cex__'] }, asset: '$accountingAssetIdentity' },
      assetSymbol: { $first: '$assetSymbol' },
      familyDisplaySymbol: { $first: '$familyDisplaySymbol' },
      quantityAfter: { $first: '$quantityAfter' },
      basisBackedQuantityAfter: { $first: '$basisBackedQuantityAfter' },
      totalCostBasisAfterUsd: { $first: '$totalCostBasisAfterUsd' },
      uncoveredQuantityAfter: { $first: '$uncoveredQuantityAfter' },
      quantityShortfallAfter: { $first: '$quantityShortfallAfter' },
      replaySequence: { $first: '$replaySequence' },
      normalizedTransactionId: { $first: '$normalizedTransactionId' },
      normalizedType: { $first: '$normalizedType' },
      blockTimestamp: { $first: '$blockTimestamp' }
  } }
], { allowDiskUse: true }).toArray();

// Sort in JS by uncov desc, then qty desc, to avoid Decimal128 sort weirdness.
latestEth.sort((a,b) => dec(b.uncoveredQuantityAfter) - dec(a.uncoveredQuantityAfter) || dec(b.quantityAfter) - dec(a.quantityAfter));

let totalQty = 0, totalCovered = 0, totalUncov = 0, totalBasisUsd = 0;
print('wallet | network | assetSym | qtyAfter | coveredAfter | uncovAfter | basisUsd | lastTxId | lastType | lastTs');
latestEth.forEach((r, idx) => {
  try {
    const q  = dec(r.quantityAfter);
    const c  = dec(r.basisBackedQuantityAfter);
    const u  = dec(r.uncoveredQuantityAfter);
    const b  = dec(r.totalCostBasisAfterUsd);
    totalQty += q; totalCovered += c; totalUncov += u; totalBasisUsd += b;
    if (Math.abs(q) < 1e-9 && Math.abs(u) < 1e-9 && Math.abs(c) < 1e-9) return;
    const ts = r.blockTimestamp && r.blockTimestamp.toISOString ? r.blockTimestamp.toISOString() : String(r.blockTimestamp);
    print([
      r._id.wallet, r._id.network, r.assetSymbol,
      q.toFixed(8), c.toFixed(8), u.toFixed(8), b.toFixed(2),
      r.normalizedTransactionId, r.normalizedType, ts
    ].join(' | '));
  } catch (e) {
    print(`ROW ${idx} ERROR: ${e.message}  raw=${JSON.stringify(r)}`);
  }
});

print('');
print(`TOTAL ETH-family quantityAfter   = ${totalQty.toFixed(8)}`);
print(`TOTAL ETH-family coveredAfter    = ${totalCovered.toFixed(8)}`);
print(`TOTAL ETH-family uncoveredAfter  = ${totalUncov.toFixed(8)}`);
print(`TOTAL ETH-family basisUsd        = ${totalBasisUsd.toFixed(2)}`);
print(`UI reference                     = 0.665 covered / 2.448 uncovered`);

print('');
print('======================================================================');
print('SECTION C. Top 5 (wallet,network,asset) by uncoveredQuantityAfter');
print('======================================================================');
const topUncov = latestEth.filter(r => dec(r.uncoveredQuantityAfter) > 1e-9).slice(0, 5);
topUncov.forEach(r => {
  print(`TOP: ${r._id.wallet} | ${r._id.network} | ${r.assetSymbol}  uncov=${decstr(r.uncoveredQuantityAfter)}  qty=${decstr(r.quantityAfter)}  basisUsd=${decstr(r.totalCostBasisAfterUsd)}  lastSeq=${decstr(r.replaySequence)}`);
});

print('');
print('======================================================================');
print('SECTION D. Last 20 ledger points for each top-3 uncov asset');
print('======================================================================');
topUncov.slice(0, 3).forEach(r => {
  print('');
  print(`--- HISTORY ${r._id.wallet} | ${r._id.network} | ${r.assetSymbol} (${r._id.asset}) ---`);
  const filter = {
    walletAddress: r._id.wallet,
    accountingAssetIdentity: r._id.asset
  };
  if (r._id.network === '__cex__') filter.networkId = { $exists: false }; else filter.networkId = r._id.network;
  const hist = db.asset_ledger_points.find(filter).sort({ replaySequence: -1 }).limit(20).toArray().reverse();
  hist.forEach(h => {
    try {
      const ts = h.blockTimestamp && h.blockTimestamp.toISOString ? h.blockTimestamp.toISOString() : String(h.blockTimestamp);
      print([
        decstr(h.replaySequence), ts, h.normalizedType, h.lifecycleKind + '/' + h.lifecycleStage,
        'basisEffect=' + h.basisEffect,
        'qtyDelta=' + decstr(h.quantityDelta),
        'qtyAfter=' + decstr(h.quantityAfter),
        'covAfter=' + decstr(h.basisBackedQuantityAfter),
        'uncovAfter=' + decstr(h.uncoveredQuantityAfter),
        'basisDelta=' + decstr(h.costBasisDeltaUsd),
        'totalBasisAfter=' + decstr(h.totalCostBasisAfterUsd),
        'tx=' + h.normalizedTransactionId
      ].join(' | '));
    } catch (e) {
      print(`HIST ERR ${e.message}`);
    }
  });
});

print('');
print('======================================================================');
print('SECTION E. FIRST ledger point per top-3 asset where uncov first appeared');
print('======================================================================');
topUncov.slice(0, 3).forEach(r => {
  const filter = {
    walletAddress: r._id.wallet,
    accountingAssetIdentity: r._id.asset,
    uncoveredQuantityAfter: { $gt: 0 }
  };
  if (r._id.network === '__cex__') filter.networkId = { $exists: false }; else filter.networkId = r._id.network;
  const first = db.asset_ledger_points.find(filter).sort({ replaySequence: 1 }).limit(1).toArray();
  if (first.length === 0) { print(`FIRST-UNCOV: ${r._id.wallet}/${r._id.network}/${r.assetSymbol} -- none found`); return; }
  const f = first[0];
  print('');
  print(`FIRST-UNCOV: ${r._id.wallet} | ${r._id.network} | ${r.assetSymbol}`);
  print(`  seq=${decstr(f.replaySequence)}  ts=${f.blockTimestamp && f.blockTimestamp.toISOString ? f.blockTimestamp.toISOString() : f.blockTimestamp}`);
  print(`  normalizedType=${f.normalizedType} lifecycle=${f.lifecycleKind}/${f.lifecycleStage} basisEffect=${f.basisEffect}`);
  print(`  qtyDelta=${decstr(f.quantityDelta)} qtyAfter=${decstr(f.quantityAfter)} uncovAfter=${decstr(f.uncoveredQuantityAfter)} totalBasisAfter=${decstr(f.totalCostBasisAfterUsd)}`);
  print(`  normalizedTxId=${f.normalizedTransactionId}`);
  print(`  correlationId=${f.correlationId} lifecycleChainId=${f.lifecycleChainId} continuityCandidate=${f.continuityCandidate}`);
  const tx = db.normalized_transactions.findOne({ _id: f.normalizedTransactionId });
  if (tx) {
    print(`  TX: type=${tx.type} source=${tx.source} correlationId=${tx.correlationId} continuityCandidate=${tx.continuityCandidate} txHash=${tx.txHash} blockTs=${tx.blockTimestamp}`);
    if (Array.isArray(tx.flows)) {
      tx.flows.forEach((fl, i) => {
        print(`    flow[${i}] dir=${fl.direction} asset=${fl.assetSymbol||fl.assetContract} amount=${decstr(fl.amount)} counterparty=${(fl.counterparty && (fl.counterparty.address||fl.counterparty.name))||fl.counterparty} role=${fl.role}`);
      });
    }
    print(`  TX-RAW: ${JSON.stringify(tx).slice(0, 4000)}`);
  } else {
    print('  TX not found in normalized_transactions');
  }
});

print('');
print('======================================================================');
print('SECTION F. bybit-econ-v1 continuityCandidate=false legs by type');
print('======================================================================');
const econOrphans = db.normalized_transactions.aggregate([
  { $match: { source: 'bybit-econ-v1', continuityCandidate: false } },
  { $group: { _id: '$type', n: { $sum: 1 } } },
  { $sort: { n: -1 } }
]).toArray();
econOrphans.forEach(r => print(`  ${r._id}: ${r.n}`));
print(`  TOTAL bybit-econ-v1 cont=false: ${econOrphans.reduce((a,b)=>a+b.n,0)}`);

print('');
print('======================================================================');
print('SECTION G. bybit-rekeyed-v1 pair counts');
print('======================================================================');
const rekeyed = db.normalized_transactions.aggregate([
  { $match: { source: 'bybit-rekeyed-v1' } },
  { $group: { _id: { type: '$type', cont: '$continuityCandidate' }, n: { $sum: 1 } } },
  { $sort: { '_id.type': 1 } }
]).toArray();
rekeyed.forEach(r => print(`  type=${r._id.type} cont=${r._id.cont}: ${r.n}`));
print(`  TOTAL bybit-rekeyed-v1: ${rekeyed.reduce((a,b)=>a+b.n,0)}`);

print('');
print('======================================================================');
print('SECTION H. AMANWETH / Mantle corridor');
print('======================================================================');
const corridor = db.normalized_transactions.aggregate([
  { $match: { correlationId: { $regex: '^BYBIT-CORRIDOR:MANTLE' } } },
  { $group: { _id: { type: '$type', cont: '$continuityCandidate', source: '$source' }, n: { $sum: 1 } } },
  { $sort: { n: -1 } }
]).toArray();
print('BYBIT-CORRIDOR:MANTLE correlations:');
corridor.forEach(r => print(`  type=${r._id.type} cont=${r._id.cont} source=${r._id.source}: ${r.n}`));

print('');
print('Unresolved Mantle ETH-family EXTERNAL_TRANSFER_IN:');
const mantleEthIn = db.normalized_transactions.find({
  networkId: 'MANTLE',
  type: 'EXTERNAL_TRANSFER_IN',
  $or: [
    { 'flows.assetSymbol': { $in: ['ETH','WETH','AMANWETH','METH','CMETH'] } },
    { 'flows.familyDisplaySymbol': 'ETH' }
  ]
}).toArray();
mantleEthIn.forEach(t => {
  const interesting = (Array.isArray(t.flows)?t.flows:[]).filter(fl => /ETH|WETH/i.test(fl.assetSymbol||''));
  print(`  tx=${t._id} corr=${t.correlationId||'-'} cont=${t.continuityCandidate} source=${t.source} flows=${interesting.map(fl=>`${fl.assetSymbol}:${decstr(fl.amount)}`).join(',')}`);
});
print(`  TOTAL Mantle ETH-family EXTERNAL_TRANSFER_IN: ${mantleEthIn.length}`);

print('');
print('======================================================================');
print('DIAGNOSTIC COMPLETE');
print('======================================================================');
