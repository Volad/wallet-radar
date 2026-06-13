# Implementation Plan: Fix Null-Priced FEE Flows on WRAP/UNWRAP/LP_POSITION_STAKE/UNSTAKE

**Date:** 2026-06-10  
**Symptom:** Sawtooth AVCO pattern on ETH across all chains — AVCO inflates on every gas payment for WRAP/UNWRAP and LP_POSITION_STAKE/UNSTAKE transactions.  
**Severity:** High — 718 affected transactions, avg +$51-68 AVCO inflation per WRAP/UNWRAP event on BASE.

---

## Root Cause

### Pipeline bypass

`OnChainClassificationSupport.initialStatus()` routes WRAP/UNWRAP/LP_POSITION_STAKE/LP_POSITION_UNSTAKE directly to `CONFIRMED` status. `PricingJob` processes only `PENDING_PRICE` transactions. Result: FEE flows on these transaction types are **never priced** — `unitPriceUsd = null`.

```
WRAP/UNWRAP classified → status=CONFIRMED → skips PricingJob → FEE flow price=null
VAULT_WITHDRAW classified → status=PENDING_PRICE → PricingJob runs → FEE flow price=$3860.93
```

`WrappedNativeClassifier` further hardcodes `NormalizedTransactionStatus.CONFIRMED` instead of calling `OnChainClassificationSupport.initialStatus()`.

### Replay consequence

`GenericFlowReplayEngine.applyFee()`:
- Calls `hasKnownPrice(flow)` → false when `unitPriceUsd=null`
- ETH **quantity** decreases (gas consumed) but **basis is not reduced** (falls through to `markUnresolved`)
- Result: `totalCostBasisUsd` unchanged, `quantity` smaller → **AVCO = basis/qty increases**

Ledger point: `basisEffect=GAS_ONLY`, `costBasisDeltaUsd=$0.00000000`

This creates a permanent upward bias in ETH AVCO that grows with each WRAP/UNWRAP event.

### Affected transactions (existing data)

| Type | Count in DB |
|------|------------|
| WRAP | 349 |
| UNWRAP | 331 |
| LP_POSITION_STAKE | 24 |
| LP_POSITION_UNSTAKE | 14 |
| **Total** | **718** |

High-impact networks: BASE (244 events, avg +$51-68/event), ARBITRUM (6 events), OPTIMISM (7 events).

---

## Related findings (correct behavior, not bugs)

These AVCO changes from the audit are **expected** — no fix needed:

| Pattern | Why correct |
|---------|-------------|
| `LP_EXIT` returning ETH at low AVCO (e.g. $151/ETH seq 9084) | LP basis tracks actual cost paid ($120 for the LP position); large profit is realised later when ETH sold |
| `BRIDGE_IN` carrying low AVCO across chains (e.g. seq 9090 ARB $1452→$152) | Directly inherits BASE ETH AVCO which was low from the LP exit carry — correct |
| `SPONSORED_GAS_IN` diluting AVCO | Free ETH received at $0 cost — correct |
| Zero-qty `CARRY_IN` adjustments | Late carry settlement mechanism — expected |
| Multi-asset `BRIDGE_IN` (seq 6321 USD₮0 + ETH at $4113) | Both assets priced at market; ETH is a separate bridge-related transfer — correct |

---

## Proposed Changes

### Change 1: `OnChainClassificationSupport.initialStatus()` — remove WRAP/UNWRAP/LP_POSITION from CONFIRMED fast-path

**File:** `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/support/OnChainClassificationSupport.java`

```java
// BEFORE (lines 263-271):
if (type == NormalizedTransactionType.APPROVE
        || type == NormalizedTransactionType.ADMIN_CONFIG
        || type == NormalizedTransactionType.INTERNAL_TRANSFER
        || type == NormalizedTransactionType.SPONSORED_GAS_IN
        || type == NormalizedTransactionType.LP_POSITION_STAKE
        || type == NormalizedTransactionType.LP_POSITION_UNSTAKE
        || type == NormalizedTransactionType.WRAP
        || type == NormalizedTransactionType.UNWRAP) {
    return NormalizedTransactionStatus.CONFIRMED;
}

// AFTER:
if (type == NormalizedTransactionType.APPROVE
        || type == NormalizedTransactionType.ADMIN_CONFIG
        || type == NormalizedTransactionType.INTERNAL_TRANSFER
        || type == NormalizedTransactionType.SPONSORED_GAS_IN) {
    return NormalizedTransactionStatus.CONFIRMED;
}
// WRAP/UNWRAP/LP_POSITION_STAKE/LP_POSITION_UNSTAKE fall through to PENDING_PRICE
// because they have FEE flows that must be priced to maintain AVCO correctness.
```

Removing WRAP/UNWRAP/LP_POSITION_STAKE/LP_POSITION_UNSTAKE causes them to fall through to the `ClarificationEligibilitySupport.requiresClarification(...)` check at line 282, which will return `PENDING_PRICE` for on-chain transactions without clarification needs.

### Change 2: `WrappedNativeClassifier` — remove hardcoded `CONFIRMED`

**File:** `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/onchain/family/WrappedNativeClassifier.java`

Replace both `NormalizedTransactionStatus.CONFIRMED` instances with `OnChainClassificationSupport.initialStatus(context.view(), wrappedType.get(), ConfidenceLevel.HIGH)`.

```java
// BEFORE:
return Optional.of(FamilyDecisionSupport.build(
        NormalizedTransactionType.WRAP,
        NormalizedTransactionStatus.CONFIRMED,
        ...
));

// AFTER:
return Optional.of(FamilyDecisionSupport.build(
        NormalizedTransactionType.WRAP,
        OnChainClassificationSupport.initialStatus(context.view(), NormalizedTransactionType.WRAP, ConfidenceLevel.HIGH),
        ...
));
```

### What gets priced after the fix

`PriceableFlowPolicy.requiresMarketPrice()` for WRAP/UNWRAP:
- TRANSFER flows: `isContinuityPrincipal()` = false (WRAP/UNWRAP not in that set), falls through to `role == FEE` check = **false** → TRANSFER flows NOT priced ✓ (REALLOCATE semantics, no market price needed)
- FEE flows: `role == FEE` = **true** → **priced** ✓

TRANSFER flows on WRAP/UNWRAP correctly stay unpriced (they are reallocations, not market transactions).

---

## Backfill for Existing Data

After deploying the code fix, run a MongoDB repair to reprice all existing null-FEE transactions:

```javascript
// 1. Reset affected CONFIRMED transactions to PENDING_PRICE
db.normalized_transactions.updateMany(
  {
    type: { $in: ["WRAP", "UNWRAP", "LP_POSITION_STAKE", "LP_POSITION_UNSTAKE"] },
    normalizationStatus: "CONFIRMED",
    "flows": { $elemMatch: { "role": "FEE", "unitPriceUsd": null } }
  },
  { $set: { normalizationStatus: "PENDING_PRICE", lastError: null } }
);

// 2. Verify count
db.normalized_transactions.countDocuments({
  type: { $in: ["WRAP", "UNWRAP", "LP_POSITION_STAKE", "LP_POSITION_UNSTAKE"] },
  normalizationStatus: "PENDING_PRICE"
});
```

Then wait for `PricingJob` to process all affected transactions (~718 total).

---

## Verification Steps

After rebuild and replay:

1. Verify WRAP/UNWRAP FEE flows have non-null `unitPriceUsd`:
```javascript
db.normalized_transactions.countDocuments({
  type: { $in: ["WRAP", "UNWRAP"] },
  normalizationStatus: "CONFIRMED",
  "flows": { $elemMatch: { "role": "FEE", "unitPriceUsd": null } }
}); // Expected: 0
```

2. Verify GAS_ONLY events on WRAP/UNWRAP no longer inflate AVCO:
```javascript
db.asset_ledger_points.aggregate([
  { $match: { walletAddress: "...", basisEffect: "GAS_ONLY", assetSymbol: "ETH",
              normalizedType: { $in: ["WRAP", "UNWRAP"] } } },
  { $project: { qty: { $toDouble: "$quantityDelta" }, basisDelta: { $toDouble: "$costBasisDeltaUsd" } } },
  { $match: { basisDelta: 0, $expr: { $lt: ["$qty", 0] } } },
  { $count: "remaining_zero_basis_events" }
]); // Expected: 0
```

3. Check BASE ETH AVCO chart no longer shows sawtooth pattern from WRAP/UNWRAP.

---

## Acceptance Criteria

- [ ] WRAP/UNWRAP FEE flows have `unitPriceUsd != null` after pricing job runs
- [ ] GAS_ONLY events from WRAP/UNWRAP reduce `costBasisDeltaUsd` proportionally (basis = qty × AVCO)
- [ ] BASE ETH AVCO no longer inflates on each WRAP/UNWRAP gas payment
- [ ] ARBITRUM ETH AVCO chart shows no sawtooth caused by WRAP/UNWRAP
- [ ] No regressions in LP_ENTRY/EXIT, BRIDGE_IN/OUT accounting

---

## Risks

- **Low:** WRAP/UNWRAP TRANSFER flows are 1:1 REALLOCATE — they are not priced (confirmed by `PriceableFlowPolicy` logic). No risk of spurious market pricing on the principal legs.
- **Low:** Backfill reset to `PENDING_PRICE` will cause pricing job to run on 718 transactions. Each is a simple native-asset ETH price lookup with well-established historical data.
- **None:** LP_POSITION_STAKE/UNSTAKE change: same analysis applies — only FEE flows get priced, TRANSFER flows are staking receipt reallocations.
