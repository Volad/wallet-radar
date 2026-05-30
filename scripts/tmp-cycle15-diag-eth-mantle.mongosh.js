const db = db.getSiblingDB('walletradar');
function decstr(v) { if (v == null) return '0'; try { return v.toString(); } catch (e) { return String(v); } }

print('======================================================================');
print('SECTION I. Full distribution of `source` values on normalized_transactions');
print('======================================================================');
db.normalized_transactions.aggregate([
  { $group: { _id: '$source', n: { $sum: 1 } } }, { $sort: { n: -1 } }
]).toArray().forEach(r => print(`  source=${r._id}: ${r.n}`));

print('');
print('======================================================================');
print('SECTION J. AMANWETH on MANTLE (Metamask 0x1a87) — last 30 ledger points');
print('======================================================================');
const wallet = '0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f';
const network = 'MANTLE';
const histAman = db.asset_ledger_points.find({
  walletAddress: wallet,
  networkId: network,
  assetSymbol: 'AMANWETH'
}).sort({ replaySequence: -1 }).limit(30).toArray().reverse();
histAman.forEach(h => {
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
});

print('');
print('======================================================================');
print('SECTION K. AMANWETH MANTLE — FIRST point where uncov > 0');
print('======================================================================');
const firstAman = db.asset_ledger_points.find({
  walletAddress: wallet, networkId: network, assetSymbol: 'AMANWETH',
  uncoveredQuantityAfter: { $gt: 0 }
}).sort({ replaySequence: 1 }).limit(1).toArray();
if (firstAman.length) {
  const f = firstAman[0];
  print(`seq=${decstr(f.replaySequence)} ts=${f.blockTimestamp} type=${f.normalizedType} lifecycle=${f.lifecycleKind}/${f.lifecycleStage} basisEffect=${f.basisEffect}`);
  print(`qtyDelta=${decstr(f.quantityDelta)} qtyAfter=${decstr(f.quantityAfter)} uncovAfter=${decstr(f.uncoveredQuantityAfter)} basisAfter=${decstr(f.totalCostBasisAfterUsd)}`);
  print(`tx=${f.normalizedTransactionId} corr=${f.correlationId} chain=${f.lifecycleChainId} cont=${f.continuityCandidate}`);
  const tx = db.normalized_transactions.findOne({ _id: f.normalizedTransactionId });
  if (tx) print('TX-RAW: ' + JSON.stringify(tx).slice(0, 4000));
}

print('');
print('======================================================================');
print('SECTION L. All ETH-family ledger points on MANTLE for Metamask 0x1a87');
print('======================================================================');
const allMantleEth = db.asset_ledger_points.find({
  walletAddress: wallet, networkId: network, accountingFamilyIdentity: 'FAMILY:ETH'
}).sort({ replaySequence: 1 }).toArray();
print(`Total Mantle ETH-family points: ${allMantleEth.length}`);
allMantleEth.forEach(h => {
  const ts = h.blockTimestamp && h.blockTimestamp.toISOString ? h.blockTimestamp.toISOString() : String(h.blockTimestamp);
  print([
    decstr(h.replaySequence), ts, h.assetSymbol, h.normalizedType, h.lifecycleKind + '/' + h.lifecycleStage,
    'basisEffect=' + h.basisEffect,
    'qtyDelta=' + decstr(h.quantityDelta),
    'qtyAfter=' + decstr(h.quantityAfter),
    'covAfter=' + decstr(h.basisBackedQuantityAfter),
    'uncovAfter=' + decstr(h.uncoveredQuantityAfter),
    'totalBasisAfter=' + decstr(h.totalCostBasisAfterUsd),
    'tx=' + h.normalizedTransactionId
  ].join(' | '));
});

print('');
print('======================================================================');
print('SECTION M. ALL ETH-family uncov clusters with uncov > 0 (sorted)');
print('======================================================================');
const allClusters = db.asset_ledger_points.aggregate([
  { $match: { accountingFamilyIdentity: 'FAMILY:ETH' } },
  { $sort: { replaySequence: -1 } },
  { $group: {
      _id: { w: '$walletAddress', n: { $ifNull: ['$networkId','__cex__'] }, a: '$accountingAssetIdentity' },
      sym: { $first: '$assetSymbol' },
      qty: { $first: '$quantityAfter' },
      cov: { $first: '$basisBackedQuantityAfter' },
      unc: { $first: '$uncoveredQuantityAfter' },
      basis: { $first: '$totalCostBasisAfterUsd' }
  } }
], { allowDiskUse: true }).toArray();

allClusters.sort((a,b) => Number(b.unc?.toString()||'0') - Number(a.unc?.toString()||'0'));

let sumUncov = 0, sumCov = 0;
print('wallet | network | symbol | qtyAfter | covered | uncov | basisUsd');
allClusters.forEach(r => {
  const u = Number(r.unc?.toString()||'0');
  const c = Number(r.cov?.toString()||'0');
  const q = Number(r.qty?.toString()||'0');
  const b = Number(r.basis?.toString()||'0');
  if (u > 1e-12 || q > 1e-12) {
    print([r._id.w, r._id.n, r.sym, q.toFixed(8), c.toFixed(8), u.toFixed(8), b.toFixed(2)].join(' | '));
  }
  sumUncov += u; sumCov += c;
});
print(`SUM cov=${sumCov.toFixed(8)} uncov=${sumUncov.toFixed(8)}`);

print('');
print('======================================================================');
print('SECTION N. ETH-family quantities where qtyAfter > 0 only (likely UI scope)');
print('======================================================================');
let onlyHeldCov = 0, onlyHeldUncov = 0, onlyHeldQty = 0;
allClusters.forEach(r => {
  const q = Number(r.qty?.toString()||'0');
  if (q > 1e-12) {
    onlyHeldQty += q;
    onlyHeldCov  += Number(r.cov?.toString()||'0');
    onlyHeldUncov += Number(r.unc?.toString()||'0');
  }
});
print(`SUM held>0: qty=${onlyHeldQty.toFixed(8)} cov=${onlyHeldCov.toFixed(8)} uncov=${onlyHeldUncov.toFixed(8)}`);

print('');
print('Subset: AMANWETH only across all wallets/networks');
const amanOnly = allClusters.filter(r => r.sym === 'AMANWETH');
amanOnly.forEach(r => print(`  ${r._id.w} / ${r._id.n} / ${r.sym}: q=${r.qty} c=${r.cov} u=${r.unc} basisUsd=${r.basis}`));
const sQ = amanOnly.reduce((a,r) => a + Number(r.qty?.toString()||'0'), 0);
const sC = amanOnly.reduce((a,r) => a + Number(r.cov?.toString()||'0'), 0);
const sU = amanOnly.reduce((a,r) => a + Number(r.unc?.toString()||'0'), 0);
print(`  AMANWETH-only totals: qty=${sQ.toFixed(8)} cov=${sC.toFixed(8)} uncov=${sU.toFixed(8)}`);

print('');
print('======================================================================');
print('SECTION O. INTERNAL_TRANSFER  between 0x68bc and 0xf03b paired? (the seq 646 break)');
print('======================================================================');
// Look for the corresponding out-side of 0xc7aec... tx hash
db.normalized_transactions.find({
  txHash: '0xc7aeca675c9a8c4dd632ffd65dcc6789abb393271d406261fdefc9396c282520'
}).forEach(t => {
  print(`tx=${t._id} wallet=${t.walletAddress} type=${t.type} cont=${t.continuityCandidate} corr=${t.correlationId} pair=${t.pairLinkId||'-'}`);
  (t.flows||[]).forEach((fl,i) => print(`  flow[${i}] role=${fl.role} sym=${fl.assetSymbol} qty=${decstr(fl.quantityDelta)} cp=${fl.counterpartyAddress} cpType=${fl.counterpartyType}`));
});

print('');
print('======================================================================');
print('SECTION P. WETH ETHEREUM 0x68bc — first uncov LP_EXIT lp-position:ethereum:uniswap:922846 — find its entries');
print('======================================================================');
db.normalized_transactions.find({ correlationId: 'lp-position:ethereum:uniswap:922846' }).sort({ blockTimestamp: 1 }).toArray().forEach(t => {
  print(`tx=${t._id} ts=${t.blockTimestamp} type=${t.type} cont=${t.continuityCandidate} chain=${t.lifecycleChainId||t.correlationId}`);
  (t.flows||[]).forEach((fl,i)=>print(`  flow[${i}] role=${fl.role} sym=${fl.assetSymbol} qty=${decstr(fl.quantityDelta)} cp=${fl.counterpartyAddress}`));
});

print('');
print('======================================================================');
print('SECTION Q. WETH BASE 0x1a87 — first uncov WRAP — surroundings (BASE WETH ledger)');
print('======================================================================');
const wbase = db.asset_ledger_points.find({
  walletAddress: '0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f',
  networkId: 'BASE',
  assetSymbol: 'WETH'
}).sort({ replaySequence: 1 }).limit(30).toArray();
wbase.forEach(h => {
  const ts = h.blockTimestamp && h.blockTimestamp.toISOString ? h.blockTimestamp.toISOString() : String(h.blockTimestamp);
  print([
    decstr(h.replaySequence), ts, h.normalizedType, h.lifecycleKind + '/' + h.lifecycleStage,
    'basisEffect=' + h.basisEffect,
    'qtyDelta=' + decstr(h.quantityDelta),
    'qtyAfter=' + decstr(h.quantityAfter),
    'covAfter=' + decstr(h.basisBackedQuantityAfter),
    'uncovAfter=' + decstr(h.uncoveredQuantityAfter),
    'totalBasisAfter=' + decstr(h.totalCostBasisAfterUsd),
    'tx=' + h.normalizedTransactionId
  ].join(' | '));
});

print('');
print('DIAGNOSTIC SUPPLEMENT COMPLETE');
