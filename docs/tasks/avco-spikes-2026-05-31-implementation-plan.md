# AVCO Spikes — Implementation Plan 2026-05-31

**Audit ref:** `docs/tasks/avco-spikes-2026-05-31-audit.md`  
**Date:** 2026-05-31  
**Blockers:** B-SPIKE-1, B-BRIDGE-1, B-BRIDGE-2 (merged into B-SPIKE-2), B-BTC-1, B-SPIKE-2  
**Review status:** CONDITIONAL APPROVE — plan updated post-review  

---

## Summary

| ID | Description | Stage | Priority |
|----|-------------|-------|----------|
| B-SPIKE-1 | **REGRESSION** — `retagPrincipalFlowsForBridgeContinuity()` assigns CARRY_IN to secondary positive ETH flows on BRIDGE_OUTs → AVCO spikes at 4 timeline points | clarification | **P0** |
| B-BRIDGE-1 | 3 Across BRIDGE_INs present in DB but never matched: `MAX_TIME_DELTA=15s` (actual gap 18s) and `MAX_RELATIVE_QTY_DIFF=0.5%` (actual diff 14.75–20.63%) reject valid pairs | linking | **P0** |
| B-SPIKE-2 | 266 SPONSORED_GAS_IN + secondary ETH inflows on linked BRIDGE_INs (incl. B-BRIDGE-2) have `basisEffects=[ACQUIRE]` at $0 → AVCO dilution spikes | pricing/classification | **P1** |
| B-BTC-1 | BTC sale on `BYBIT:33625378` records `qtyDelta=0` — corridor between Bybit accounts 516601508↔33625378 not linked for BTC | linking | **P1** |

> **B-BRIDGE-2 merged into B-SPIKE-2:** DB investigation confirmed that `0x826189` BRIDGE_IN is already correctly linked (`correlationId=bridge:lifi:0x8b471042...`, USD₮0 is REALLOCATE_IN). The issue is a **secondary ETH flow** (0.013689 ETH, ACQUIRE) alongside the primary USD₮0 corridor — identical root cause to B-SPIKE-2. No separate fix needed.

**Out of scope this cycle:** B-UNCOV-1 (USDE), B-UNCOV-2 (GMX GM LP), B-UNCOV-3 (TON), 4 GENUINE_EVIDENCE_MISSING bridge orphans.

---

## Iteration protocol (mandatory after each merge)

1. `./scripts/prod-reset-rebuild-backend.sh --skip-frontend`
2. Wait for normalization + cost-basis replay
3. Re-run API + mongo checks below
4. Delegate `financial-logic-auditor` (readonly) — new blockers only
5. If blockers remain → append B-SPIKE-N and repeat

---

## B-SPIKE-1 — LI.FI BRIDGE_OUT spurious CARRY_IN (REGRESSION)

### Root cause

`BridgePairLinkSupport.retagPrincipalFlowsForBridgeContinuity()` retaggs ALL non-FEE flows on a BRIDGE_OUT to TRANSFER, including **secondary inbound flows** (positive `quantityDelta`) such as fee refunds or intermediate WETH→ETH steps.

For 4 LI.FI multi-flow BRIDGE_OUTs (`0x6c5bd9`, `0x122fa9`, `0xd9d384`, `0x8f3dd8`):
- Primary flow: outgoing principal (−ETH) → must become TRANSFER/CARRY_OUT ✓
- Secondary flow: small incoming ETH (e.g. +2.5e-7 ETH, fee refund) → incorrectly becomes CARRY_IN ✗

Result: `basisEffects=[CARRY_IN, CARRY_OUT, GAS_ONLY]` — spurious CARRY_IN on BRIDGE_OUT.

> **Reviewer note (auditor):** The phantom second fix proposed in the draft plan (guard on `reconcilePairedInboundCounterparty()`) is unnecessary — that method only queries BRIDGE_IN rows by design. **Only the direction filter in `retagPrincipalFlowsForBridgeContinuity()` is needed.**

### Fix

**File:** `BridgePairLinkSupport.java`  
**Method:** `retagPrincipalFlowsForBridgeContinuity(NormalizedTransaction, Instant)`

When `transaction.getType() == BRIDGE_OUT`: only retag flows with `quantityDelta < 0` (outbound) to TRANSFER role. Skip flows with `quantityDelta > 0` — these are secondary/refund flows and must remain as ACQUIRE or FEE.

```java
// Guard to add:
if (tx.getType() == NormalizedTransactionType.BRIDGE_OUT
        && flow.getQuantityDelta() != null
        && flow.getQuantityDelta().signum() > 0) {
    continue; // secondary inbound flow on BRIDGE_OUT — do not retag
}
```

No change needed in `reconcilePairedInboundCounterparty()` or any other method.

### Tests

- `BridgePairLinkSupportTest`: multi-flow BRIDGE_OUT (one outbound + one small inbound) → after retag:
  - Outbound flow (negative qty) → role = TRANSFER
  - Inbound flow (positive qty) → role unchanged (remains ACQUIRE)
- `LiFiBridgePairLinkServiceTest`: paired multi-flow LI.FI BRIDGE_OUT → `basisEffects` must NOT contain CARRY_IN on the OUT leg

### Acceptance

```javascript
// No BRIDGE_OUT rows may have CARRY_IN in their basis effects
["0x6c5bd905","0x122fa957","0xd9d38471","0x8f3dd850"].forEach(h => {
  const rows = db.asset_ledger_points.find({ _id: new RegExp(h) }).toArray();
  rows.forEach(r => { if (r.basisEffect === "CARRY_IN") throw new Error("CARRY_IN on " + h); });
  print(h, "OK", rows.map(r => r.basisEffect));
});
```

---

## B-BRIDGE-1 — 3 Across BRIDGE_INs never matched (threshold regression)

### Root cause (confirmed by DB)

`AcrossBridgePairLinkService` has two hard thresholds that reject valid pairs:

| Pair (OUT→IN) | Actual time delta | `MAX_TIME_DELTA` | Actual qty diff | `MAX_RELATIVE_QTY_DIFF` | Result |
|---|---|---|---|---|---|
| `0xf39e4f...` → `0xa7058b...` (Feb 14) | **18 s** | 15 s | 0.04% | 0.5% | **rejected by time** |
| `0x0c2b2f...` → `0x999fc8...` (Apr 23) | 1 s | 15 s | **14.75%** | 0.5% | **rejected by qty** |
| `0xee474f...` → `0xa20e3f...` (Apr 26) | 2 s | 15 s | **20.63%** | 0.5% | **rejected by qty** |

The qty difference is caused by **Across minimum relay fee** applied on small-amount bridges (<0.001 ETH), which can represent 14–21% of the transferred amount. This is legitimate and must be tolerated.

> **Reviewer note (auditor):** The fix in the previous draft (`destination.setCorrelationId(correlationId)`) was a **no-op** — that code already exists in `materializePair()`. The INs were never passed to `materializePair()` because the matching query rejected them before that point.

### Fix

**File:** `AcrossBridgePairLinkService.java`

Relax both thresholds:

```java
// Before:
private static final Duration MAX_TIME_DELTA = Duration.ofSeconds(15);
private static final BigDecimal MAX_RELATIVE_QTY_DIFF = new BigDecimal("0.005");

// After:
private static final Duration MAX_TIME_DELTA = Duration.ofSeconds(60);
private static final BigDecimal MAX_RELATIVE_QTY_DIFF = new BigDecimal("0.25");
```

`60s` covers the Feb 14 case (18s) with headroom. `25%` covers the Apr 26 worst case (20.63%) with headroom while keeping the guard meaningful (prevents cross-pairing of unrelated transactions >25% apart).

No other changes needed in `materializePair()` for this bug — `setCorrelationId` is already present.

### Tests

- `AcrossBridgePairLinkServiceTest`: add cases for:
  1. Time delta exactly 18s → pair accepted
  2. Qty diff 21% → pair accepted
  3. Time delta 90s → pair rejected (still guards against false positives)
  4. Qty diff 30% → pair rejected

### Acceptance

```javascript
// All 3 Across BRIDGE_INs must now have correlationId set
db.normalized_transactions.find({
  txHash: { $in: [
    /^0xa7058b6f/, /^0x999fc867/, /^0xa20e3fe4/
  ] }
}).forEach(n => print(n.txHash.slice(0,14), n.correlationId||"MISSING"));
// Each row → correlationId = "bridge:across:<source-txHash>"
```

AVCO at the 3 receipt timestamps must carry source wallet AVCO, not market price.

---

## B-SPIKE-2 — SPONSORED_GAS_IN + secondary bridge ETH inflows at $0 ACQUIRE

### Root cause

Two distinct cases share the same effect (zero-basis ACQUIRE diluting AVCO):

**Case A — 266 SPONSORED_GAS_IN rows:**
- `pricingAttempts=0` — pricing bypassed (SPONSORED_GAS_IN is in `NON_PRICEABLE_TYPES` or equivalent)
- `basisEffects=[ACQUIRE]` with `costBasisDeltaUsd=0`
- These are ERC-4337 Paymaster gas rebates — protocol returns tiny ETH to cover user gas. They should be **priced at market** (they represent real ETH inflows, however small).

**Case B — secondary ETH flows on linked BRIDGE_INs (incl. former B-BRIDGE-2 `0x826189`):**
- `0x826189` BRIDGE_IN: primary flow is USD₮0 (REALLOCATE_IN, ✓), secondary flow is ETH 0.013689 (ACQUIRE at $0, ✗)
- The ETH was provided by the LiFi aggregator as a native gas top-up for the receiving chain
- This secondary flow has `pricingAttempts=0` and is treated as a separate ACQUIRE

**Root cause — common:** `PriceableFlowPolicy` does not include `SPONSORED_GAS_IN` in `requiresMarketPrice`, and secondary positive flows on BRIDGE_INs are not priced when their parent is `continuityCandidate=true`.

### Fix

**File:** `PriceableFlowPolicy.java`

> **Reviewer decision (auditor):** Emit `GAS_ONLY` unconditionally for SPONSORED_GAS_IN regardless of quantity. No threshold gating.  
> **BA decision:** Acceptable if SPONSORED_GAS_IN is a pure gas rebate with no economic substance. If any SPONSORED_GAS_IN row has economic substance, price it instead. Check the data first.

**Step 1 — Investigate SPONSORED_GAS_IN quantities** (before coding):
```javascript
db.normalized_transactions.aggregate([
  { $match: { type: "SPONSORED_GAS_IN" } },
  { $unwind: "$flows" },
  { $match: { "flows.assetSymbol": "ETH" } },
  { $group: { _id: null, min: { $min: "$flows.quantityDelta" }, max: { $max: "$flows.quantityDelta" }, avg: { $avg: "$flows.quantityDelta" } } }
])
```

If all quantities are dust (< 0.0001 ETH, < $0.30): use **GAS_ONLY** in replay handler.  
If any row has material amount (> 0.001 ETH): add to **`requiresMarketPrice`** instead.

**Step 2a (if all dust) — ReplayDispatcher route to GAS_ONLY:**
In the replay handler for SPONSORED_GAS_IN, emit `GAS_ONLY` as the basis effect instead of ACQUIRE. No pricing needed.

**Step 2b (if material) — PriceableFlowPolicy:**
```java
// In requiresMarketPrice():
case SPONSORED_GAS_IN -> true;
```

**Case B fix — secondary ETH on BRIDGE_IN:** The secondary positive ETH flow on a `continuityCandidate=true` BRIDGE_IN should be priced at market (it's real ETH the user received). Ensure `requiresInboundShortfallSpotPricing` applies to secondary flows even when the primary flow is a corridor carry.

### Tests

- New test: SPONSORED_GAS_IN with tiny ETH inflow → `basisEffects=[GAS_ONLY]`, AVCO unchanged (if GAS_ONLY path)
- Or: SPONSORED_GAS_IN → status=`PENDING_PRICE`, priced at market, AVCO adjusts correctly (if market-price path)
- Multi-flow BRIDGE_IN (USD₮0 primary + ETH secondary) → ETH secondary must be priced at market

### Acceptance

```javascript
// No SPONSORED_GAS_IN creates zero-price ACQUIRE
db.asset_ledger_points.countDocuments({
  transactionType: "SPONSORED_GAS_IN",
  basisEffect: "ACQUIRE",
  costBasisDeltaUsd: 0
}) === 0

// 0x826189 secondary ETH flow must have non-zero basis
const lp = db.asset_ledger_points.find({ _id: /826189720417ce/ }).toArray();
lp.filter(r => r.assetSymbol === "ETH").forEach(r => print(r.basisEffect, r.costBasisDeltaUsd));
// Expected: either GAS_ONLY (no basis) or ACQUIRE with costBasisDeltaUsd > 0
```

---

## B-BTC-1 — BTC cross-account corridor not linked

### Root cause

BTC accumulated on `BYBIT:516601508` was moved to `BYBIT:33625378` via Bybit universal transfer (INTERNAL_TRANSFER rows). The corridor linking does not establish CARRY_OUT from 516601508 and CARRY_IN to 33625378 for BTC.

When the BTC SWAP SELL runs on 33625378:UTA, the position is empty → `DISPOSE qtyDelta=0`.

**Investigate:**
- Are the INTERNAL_TRANSFER rows between these two account IDs classified correctly?
- Does `BybitTransferContinuityRepairService` cover cross-account (cross-UID) BTC?
- Are the INTERNAL_TRANSFER rows marked `continuityCandidate=true`?

### Fix

**Files:** `BybitTransferContinuityRepairService.java` and/or `BybitInternalTransferPairer.java`

1. Verify the BTC INTERNAL_TRANSFER rows between 516601508 and 33625378 are classified as cross-account moves.
2. Ensure `BybitTransferContinuityRepairService` (or `BybitInternalTransferPairer.CROSS_UID_PAIRER`) covers the BTC flow.
3. If rows are CONFIRMED and paired (`matchedCounterparty` set) but `continuityCandidate=false` → ensure corridor inclusion handles INTERNAL_TRANSFER type.

### Tests

- `BybitInternalTransferPairerTest` or new test: cross-account INTERNAL_TRANSFER BTC pair → CARRY_OUT on 516601508, CARRY_IN on 33625378

### Acceptance

```javascript
// BTC DISPOSE on 33625378 must have non-zero qty
db.asset_ledger_points.countDocuments({
  assetSymbol: "BTC",
  basisEffect: "DISPOSE",
  quantityDelta: 0
}) === 0
// BTC position 516601508 after Dec 12 sale = 0
// BTC position 33625378 after Dec 12 = 0
```

---

## Documentation

| Doc | Change |
|-----|--------|
| `docs/03-accounting.md` | Clarify: BRIDGE_OUT retag applies to outbound (negative qty) flows only; secondary inbound flows on source-side BRIDGE_OUT must not be CARRY_IN |
| No new ADR needed | B-SPIKE-1 and B-BRIDGE-1 are bug fixes, not policy changes |

---

## Implementation order

1. **B-SPIKE-1** (regression — P0, fix `retagPrincipalFlowsForBridgeContinuity()` direction guard)
2. **B-BRIDGE-1** (P0, relax `MAX_TIME_DELTA` and `MAX_RELATIVE_QTY_DIFF` in `AcrossBridgePairLinkService`)
3. **B-SPIKE-2** (P1, investigate SPONSORED_GAS_IN qty then fix; also covers B-BRIDGE-2 secondary ETH)
4. **B-BTC-1** (P1, cross-account corridor)

---

## Risks

| Risk | Mitigation |
|------|------------|
| B-SPIKE-1 fix breaks BRIDGE_OUT retag for single-flow case | Unit test single-flow and multi-flow BRIDGE_OUT after change |
| B-BRIDGE-1 relaxed `MAX_RELATIVE_QTY_DIFF=25%` causes false-positive matches on different assets | Ensure matching query also filters by asset family/symbol; add rejection test at 30% |
| B-BRIDGE-1 relaxed `MAX_TIME_DELTA=60s` pairs wrong transactions in high-frequency wallets | Add test that rejects 90s gap; review logs post-rebuild for spurious matches |
| B-SPIKE-2 GAS_ONLY path drops actual ETH accrual if SPONSORED_GAS_IN qty > dust | Pre-check query above determines path; if any >0.001 ETH, use market-price path |
| B-BTC-1 fix exposes basis leak on other cross-account assets | Run full replay and check all DISPOSE qtyDelta=0 count post-rebuild |

---

## Verification commands

```bash
./scripts/prod-reset-rebuild-backend.sh --skip-frontend

# ETH AVCO
curl -s "http://127.0.0.1:18086/api/v1/sessions/df5e69cc-a0c0-4910-8b7d-74488fa266e2/asset-ledger?familyIdentity=FAMILY:ETH" \
  | jq '{unavailable: [.timeline[]|select(.avcoKind=="UNAVAILABLE")]|length, avco: .current.avcoUsd}'

# Across BRIDGE_IN corr check
docker exec walletradar-mongodb-prod mongosh walletradar --quiet --eval '
db.normalized_transactions.find({ txHash: { $in: [/^0xa7058b6f/, /^0x999fc867/, /^0xa20e3fe4/] } }).forEach(n => print(n.txHash.slice(0,18), n.correlationId||"NULL"));
'

# BRIDGE_OUT must not have CARRY_IN
docker exec walletradar-mongodb-prod mongosh walletradar --quiet --eval '
["0x6c5bd905","0x122fa957","0xd9d38471","0x8f3dd850"].forEach(h => {
  const rows = db.asset_ledger_points.find({ _id: new RegExp(h) }).toArray();
  rows.forEach(r => print(h, r.basisEffect));
});
'

# BTC zero dispose
docker exec walletradar-mongodb-prod mongosh walletradar --quiet --eval '
print("BTC zero dispose:", db.asset_ledger_points.countDocuments({ assetSymbol:"BTC", basisEffect:"DISPOSE", quantityDelta:0 }));
'

# SPONSORED_GAS_IN zero-basis
docker exec walletradar-mongodb-prod mongosh walletradar --quiet --eval '
print("SPONSORED_GAS_IN zero-acquire:", db.asset_ledger_points.countDocuments({ transactionType:"SPONSORED_GAS_IN", basisEffect:"ACQUIRE", costBasisDeltaUsd: 0 }));
'
```
