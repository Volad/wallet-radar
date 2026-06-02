# AVCO Shortfall Bridge-IN Repricing — Implementation Plan 2026-06-01

**Audit ref:** `docs/tasks/avco-borrow-shortfall-2026-06-01-implementation-plan.md` (B-SHORTFALL-1 deferred, now promoted)  
**Date:** 2026-06-01  
**Blockers:** B-SHORTFALL-1 (ETH + USDC shortfall BRIDGE_INs)

---

## Summary

| ID | Description | Stage | Priority |
|----|-------------|-------|----------|
| B-SHORTFALL-1 | Paired BRIDGE_INs receive qty with $0 basis when source wallet had no tracked cost. Missing ~$1,944 basis (0.799 ETH + 428 USDC). | linking | **P2** |

---

## Root Cause (confirmed)

### Case A — ETH: `0x4ca0b79e` (BASE) → `0x38d445c4` (ARBITRUM)

- `0x4ca0b79e` BRIDGE_OUT on BASE: principal flow `qty=-0.799 ETH, role=TRANSFER, price=undefined`
- BASE ETH position at time: `totalCostBasisBefore=$0, quantityBefore=0.0000024` (dust only)
- No upstream ETH acquisitions on BASE within lookback window (0 BASE transactions found)
- CARRY_OUT: `cbD=$0` → BRIDGE_IN gets `CARRY_IN cbD=$0, qty=+0.799 ETH`
- Missing basis: ~$1,516 (0.799 × $1,897)

### Case B — USDC: `0x425b32c1` (AVALANCHE) → `0xe11ab436` (ARBITRUM)

- `0x425b32c1` BRIDGE_OUT on AVALANCHE: `qty=-428 USDC, role=TRANSFER, price=undefined`
- AVALANCHE USDC position: `totalCostBasisBefore=$0.000439` (effectively $0), `quantityBefore=428`
- No priced upstream USDC inflow on AVALANCHE
- CARRY_OUT: `cbD≈$0` → BRIDGE_IN gets `CARRY_IN cbD≈$0, qty=+427.946 USDC`
- Missing basis: ~$428 (428 × $1)

### Why `reconcileUnsupportedOutbounds` doesn't fix this

`UnmatchedBridgeInboundPricingFallbackService.reconcileUnsupportedOutbounds()` processes BRIDGE_OUTs with no priced upstream inflow — BUT skips them immediately when `hasPairedMoveBasisInbound(outbound) == true` (both cases have paired BRIDGE_INs). The shortfall path is never reached.

```java
if (hasPairedMoveBasisInbound(outbound)) {
    pairedMoveBasisSkipped++;
    continue;  // ← skips shortfall detection entirely
}
```

---

## Fix

**File:** `UnmatchedBridgeInboundPricingFallbackService.java`

### Logic change

When `hasPairedMoveBasisInbound=true` AND no priced upstream inflow exists on the source wallet: reprice the paired **BRIDGE_IN** (not the BRIDGE_OUT) to market price. The BRIDGE_OUT correctly carries $0 (the source had nothing); the BRIDGE_IN should be treated as an "orphan acquisition" at market price.

### Implementation

**Step 1:** Refactor `hasPairedMoveBasisInbound` to return the paired inbound transaction (instead of bool), or add a parallel `loadPairedMoveBasisInbound` method.

**Step 2:** In `reconcileUnsupportedOutbounds`, after the `hasPairedMoveBasisInbound` check, additionally check upstream:

```java
if (hasPairedMoveBasisInbound(outbound)) {
    // Paired correctly — but if source had no upstream basis, the BRIDGE_IN
    // received qty with $0 carry. Treat as shortfall: reprice the BRIDGE_IN.
    if (!hasPricedUpstreamInflow(outbound, principal, lookback)) {
        NormalizedTransaction pairedInbound = loadPairedMoveBasisInbound(outbound);
        if (pairedInbound != null && clearContinuityAndRepriceInbound(pairedInbound, now)) {
            dirty.add(pairedInbound);
            log.info("BRIDGE_SHORTFALL_INBOUND_REPRICE outbound={} inbound={}",
                     outbound.getId(), pairedInbound.getId());
        }
    }
    pairedMoveBasisSkipped++;
    continue;
}
```

**Step 3:** Add `loadPairedMoveBasisInbound` (extract from `hasPairedMoveBasisInbound`):

```java
NormalizedTransaction loadPairedMoveBasisInbound(NormalizedTransaction outbound) {
    // query same as hasPairedMoveBasisInbound but return the NormalizedTransaction
    // Return only if supportsPlainMoveBasis(outbound, inbound) is true
}
```

### Guarding against false positives

The new path only triggers when ALL of:
1. BRIDGE_OUT has `continuityCandidate=true` and bridge correlationId
2. BRIDGE_OUT principal flow has **no resolved price** (i.e., `price=undefined`)
3. Paired BRIDGE_IN exists and `supportsPlainMoveBasis=true`
4. **No priced upstream inflow** found within lookback window (default 24h) on source wallet/network

If any condition fails → original behavior (skip, carry $0).

---

## Tests

**File:** `UnmatchedBridgeInboundPricingFallbackServiceTest.java` (new test case)

- Scenario: BRIDGE_OUT on CHAIN_A with no upstream inflow, paired with BRIDGE_IN on CHAIN_B → BRIDGE_IN repriced to `continuityCandidate=false`, flows changed TRANSFER→BUY, status→`PENDING_PRICE`
- Regression: BRIDGE_OUT with priced upstream inflow + paired BRIDGE_IN → BRIDGE_IN NOT repriced (current skipped behavior preserved)
- Regression: BRIDGE_OUT with no pair → current `clearContinuityAndRepriceOutbound` path unchanged

---

## Acceptance

```javascript
// ETH: 0x38d445c4 BRIDGE_IN on ARBITRUM — must be repriced to market
docker exec walletradar-mongodb-prod mongosh walletradar --quiet --eval '
const inbound = db.normalized_transactions.findOne({ _id: { $regex: "0x38d445c4" } });
print("continuityCandidate:", inbound.continuityCandidate);  // expected: false
print("status:", inbound.status);  // expected: PENDING_PRICE → then CONFIRMED after pricing
inbound.flows.forEach((f,i) => print("f["+i+"] role="+f.role, "qty="+f.quantityDelta, "price="+f.unitPriceUsd));
// expected: role=BUY, price=<market>
'

// USDC: 0xe11ab436 BRIDGE_IN on ARBITRUM — same
docker exec walletradar-mongodb-prod mongosh walletradar --quiet --eval '
const inbound = db.normalized_transactions.findOne({ _id: { $regex: "0xe11ab436" } });
print("continuityCandidate:", inbound.continuityCandidate, "status:", inbound.status);
'

// ETH AVCO post-fix (should be higher — 0.799 ETH now has $1,897 basis)
curl -s "http://127.0.0.1:18086/api/v1/sessions/df5e69cc-a0c0-4910-8b7d-74488fa266e2/asset-ledger?familyIdentity=FAMILY:ETH" \
  | jq '{avco: .current.avcoUsd, spikes: [.timeline[]|select(.avcoKind != "UNAVAILABLE")] | length}'

// 0x38d445c4 ledger: must have ACQUIRE with cbD > 1000 (0.799 ETH × ~$1,897)
docker exec walletradar-mongodb-prod mongosh walletradar --quiet --eval '
db.asset_ledger_points.find({ _id: { $regex: "0x38d445c4" } }).forEach(r =>
  print(r.basisEffect, "cbD="+r.costBasisDeltaUsd, "qty="+r.quantityDelta, "net="+r.networkId)
);
// expected: ACQUIRE cbD≈$1,516
'
```

---

## Risks

| Risk | Mitigation |
|------|------------|
| False-positive repricing when source has basis (priced inflow exists) | `hasPricedUpstreamInflow` gate ensures this only fires when upstream is empty |
| Large lookback window finds irrelevant inflows | Lookback is bounded (default 24h); configurable via `pricingProperties.bridgeOut.upstreamLookbackHours` |
| BRIDGE_OUT and BRIDGE_IN on same network/wallet (self-bridge) treated as shortfall | `hasPairedMoveBasisInbound` already handles this; `supportsPlainMoveBasis` guards asset compatibility |
| ETH AVCO spikes introduced by BRIDGE_IN now acquiring at market price | ETH AVCO increases from $1,527 toward $1,600+ (adding $1,516 basis for 0.799 ETH is correct) |

---

## Implementation order

1. `UnmatchedBridgeInboundPricingFallbackService.java` — refactor + add shortfall path
2. `UnmatchedBridgeInboundPricingFallbackServiceTest.java` — new test cases
3. Prod rebuild + acceptance verification
