const db = db.getSiblingDB('walletradar');
function decstr(v) { if (v == null) return '0'; try { return v.toString(); } catch (e) { return String(v); } }
function dec(v) { if (v==null) return 0; try { return Number(v.toString())||0; } catch(e) { return 0; } }

print('======================================================================');
print('SECTION R. Mantle WETH INTERNAL_TRANSFER seq=9380 tx 0xa5e755... — sides');
print('======================================================================');
const txHash = '0xa5e755a68349c9956b51ced38575733278b40467971ca4b9f9f40937fd5d2920';
db.normalized_transactions.find({ txHash }).forEach(t => {
  print(`tx=${t._id} wallet=${t.walletAddress} type=${t.type} cont=${t.continuityCandidate} corr=${t.correlationId} pair=${t.pairLinkId||'-'} chainId=${t.lifecycleChainId||'-'}`);
  (t.flows||[]).forEach((fl,i) => print(`  flow[${i}] role=${fl.role} sym=${fl.assetSymbol} qty=${decstr(fl.quantityDelta)} cp=${fl.counterpartyAddress} cpType=${fl.counterpartyType} unitUsd=${decstr(fl.unitPriceUsd)} valueUsd=${decstr(fl.valueUsd)} priceSource=${fl.priceSource}`));
});

print('');
print('======================================================================');
print('SECTION S. Source-side wallet ledger before/after txhash 0xa5e755 (WETH/ETH on MANTLE)');
print('======================================================================');
// Look up the wallet on the OTHER side of this internal transfer
const srcWallets = ['0xf03b52e8686b962e051a6075a06b96cb8a663021', '0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f', '0xa0dd42c626b002778f93e1ab42cbed5f31c117b2'];
srcWallets.forEach(w => {
  const pts = db.asset_ledger_points.find({ walletAddress: w, networkId: 'MANTLE', accountingFamilyIdentity: 'FAMILY:ETH' }).sort({ replaySequence: 1 }).toArray();
  if (pts.length === 0) return;
  print(`-- wallet ${w} MANTLE ETH-family (${pts.length} points)`);
  pts.forEach(h => {
    const ts = h.blockTimestamp && h.blockTimestamp.toISOString ? h.blockTimestamp.toISOString() : String(h.blockTimestamp);
    print([decstr(h.replaySequence), ts, h.assetSymbol, h.normalizedType, 'basis='+h.basisEffect, 'qD='+decstr(h.quantityDelta),'qA='+decstr(h.quantityAfter),'cA='+decstr(h.basisBackedQuantityAfter),'uA='+decstr(h.uncoveredQuantityAfter),'tx='+h.normalizedTransactionId].join(' | '));
  });
});

print('');
print('======================================================================');
print('SECTION T. All wallets where this 3.06 WETH may have originated (find paired tx by amount/time)');
print('======================================================================');
const cutoffStart = new Date('2026-02-18T00:00:00Z');
const cutoffEnd   = new Date('2026-02-20T00:00:00Z');
const candidates = db.normalized_transactions.find({
  blockTimestamp: { $gte: cutoffStart, $lte: cutoffEnd },
  $or: [
    { 'flows.assetSymbol': { $regex: /^(WETH|ETH|WMNT|MNT)$/i } },
    { 'flows.familyDisplaySymbol': 'ETH' }
  ]
}).toArray();
candidates.forEach(t => {
  const ethFlow = (t.flows||[]).find(fl => /^(WETH|ETH)$/i.test(fl.assetSymbol||''));
  if (!ethFlow) return;
  const q = dec(ethFlow.quantityDelta);
  if (Math.abs(Math.abs(q) - 3.06) < 0.01) {
    print(`MATCH tx=${t._id} ts=${t.blockTimestamp} type=${t.type} wallet=${t.walletAddress} net=${t.networkId} qty=${q} cp=${ethFlow.counterpartyAddress} cont=${t.continuityCandidate} corr=${t.correlationId} pair=${t.pairLinkId||'-'}`);
  }
});

print('');
print('======================================================================');
print('SECTION U. ALL ETH-family uncov clusters (rewritten safe)');
print('======================================================================');
const all = db.asset_ledger_points.aggregate([
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
// Sort safely (extract numeric first, avoid Decimal128 .bytes deref by sort)
const safe = all.map(r => ({ ...r, qN: dec(r.qty), cN: dec(r.cov), uN: dec(r.unc), bN: dec(r.basis) }));
safe.sort((a,b) => b.uN - a.uN);
print('-- clusters with qty > 0 (active holdings, what UI likely sums) --');
let activeQ=0,activeC=0,activeU=0;
safe.forEach(r => { if (r.qN > 1e-12) {
  activeQ += r.qN; activeC += r.cN; activeU += r.uN;
  print(`  ${r._id.w} | ${r._id.n} | ${r.sym} | qty=${r.qN.toFixed(8)} cov=${r.cN.toFixed(8)} unc=${r.uN.toFixed(8)} basisUsd=${r.bN.toFixed(2)}`);
} });
print(`-- TOTAL active (qty>0): qty=${activeQ.toFixed(8)} cov=${activeC.toFixed(8)} unc=${activeU.toFixed(8)}`);

print('');
print('-- "stuck" clusters with qty == 0 but unc > 0 (legacy uncov flags) --');
let zomQ=0,zomC=0,zomU=0;
safe.forEach(r => { if (r.qN <= 1e-12 && r.uN > 1e-12) {
  zomQ += r.qN; zomC += r.cN; zomU += r.uN;
  print(`  ${r._id.w} | ${r._id.n} | ${r.sym} | qty=${r.qN.toFixed(8)} cov=${r.cN.toFixed(8)} unc=${r.uN.toFixed(8)} basisUsd=${r.bN.toFixed(2)}`);
} });
print(`-- TOTAL stuck (qty=0, unc>0): qty=${zomQ.toFixed(8)} cov=${zomC.toFixed(8)} unc=${zomU.toFixed(8)}`);

print('');
print('-- ONLY MANTLE active (likely page filter) --');
let mQ=0,mC=0,mU=0;
safe.forEach(r => { if (r.qN > 1e-12 && r._id.n === 'MANTLE') {
  mQ += r.qN; mC += r.cN; mU += r.uN;
  print(`  ${r._id.w} | ${r._id.n} | ${r.sym} | qty=${r.qN.toFixed(8)} cov=${r.cN.toFixed(8)} unc=${r.uN.toFixed(8)} basisUsd=${r.bN.toFixed(2)}`);
} });
print(`-- TOTAL MANTLE active: qty=${mQ.toFixed(8)} cov=${mC.toFixed(8)} unc=${mU.toFixed(8)}`);

print('');
print('======================================================================');
print('SECTION V. Are 2.448/0.665 matched by AMANWETH MANTLE + any other small ETH on MANTLE?');
print('======================================================================');
const mantle = safe.filter(r => r._id.n === 'MANTLE' && r.qN > 1e-12);
const sumC = mantle.reduce((a,b)=>a+b.cN,0);
const sumU = mantle.reduce((a,b)=>a+b.uN,0);
const sumQ = mantle.reduce((a,b)=>a+b.qN,0);
print(`MANTLE active ETH-family: qty=${sumQ.toFixed(8)} cov=${sumC.toFixed(8)} uncov=${sumU.toFixed(8)} basis=${mantle.reduce((a,b)=>a+b.bN,0).toFixed(2)}`);
print(`Expected from UI: 0.665 cov / 2.448 uncov / total 3.113`);
