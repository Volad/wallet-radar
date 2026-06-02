# Implementation Plan — B-VAULT-WITHDRAW Bug A: Wrapper Bucket Denomination Mismatch

**Slug:** `vault-withdraw-basis-2026-06-02`
**Date:** 2026-06-02
**Severity:** P1
**Blocker ID:** B-VAULT-WITHDRAW (Bug A only)

---

## Scope

- **Assets:** USDC returned by MEV Capital vault withdrawals
- **Networks:** AVALANCHE (3 txs), ARBITRUM (implicitly eligible)
- **Instances:** 3 confirmed MEV Capital VAULT_WITHDRAW transactions, ~$3,618 total missing cbD
- **Out of scope (Bug B):** 3 VAULT_WITHDRAW txs with missing vault token burn leg in normalized flows (`0xc8b94615`, `0xb47d87fa`, `0xc7aa483f`) — requires normalization investigation
- **Out of scope (Morpho):** Morpho MCUSDC shortfall originates at VAULT_DEPOSIT level (upstream basis gap), not at VAULT_WITHDRAW

---

## Root Cause

### Pipeline stage: `cost_basis → replay → bucket restore`

**Denomination mismatch in `restoreFromContinuityBucket`.**

MEV Capital vault uses a receipt token (`mevUSDC`) with a massive share denominator (~1,598,068,583 shares per deposit). The `wrapper:<mevUSDC>` continuity bucket is populated with a carry whose `quantity` is in **mevUSDC shares** (e.g., 1,598,068,583). When `VAULT_WITHDRAW` fires:

1. `mevUSDC` outbound (negative qty) → `isBucketOutbound` → carry drained from `mevUSDC` position → `moveToBucket` with mevUSDC qty + basis ($1,623)
2. `USDC` inbound (positive qty = 1628) → `isBucketInbound` → `restoreFromContinuityBucket`
3. `takeFromBucket(bucket, qty=1628, ...)` does proportional slice: `1628 / 1,598,068,583 × $1,623 ≈ $0.001`

The proportional slice is correct for same-denomination buckets (LP token burn → underlying return). It's wrong for wrapper buckets where deposit and withdrawal receipts have different scales.

### Exact code location

`TransferReplayHandler.restoreFromContinuityBucket()` (line ~616):
```java
CarryTransfer carry = continuityCarryService.takeFromBucket(
        bucket, flow.getQuantityDelta().abs(), position.assetKey());
```

---

## Changes

### 1. `TransferReplayHandler.java` — drain full wrapper bucket on inbound restore

**Change `restoreFromContinuityBucket` call site** (inside `isBucketInbound` branch, line ~121):

```java
// BEFORE:
if (classifier.isBucketInbound(transaction, flow)) {
    restoreFromContinuityBucket(
            flow,
            position,
            replayState.continuity().bucket(keyFactory.continuityKey(transaction, flow))
    );
    return flowSupport.continuityBasisEffect(transaction, flow);
}

// AFTER:
if (classifier.isBucketInbound(transaction, flow)) {
    ContinuityBucket bucket = replayState.continuity().bucket(keyFactory.continuityKey(transaction, flow));
    boolean wrapperBucket = keyFactory.usesWrapperCompositeBucket(transaction);
    if (wrapperBucket) {
        restoreFullBucket(flow, position, bucket);
    } else {
        restoreFromContinuityBucket(flow, position, bucket);
    }
    return flowSupport.continuityBasisEffect(transaction, flow);
}
```

**New `restoreFullBucket()` private method:**

```java
/**
 * For wrapper-composite buckets (VAULT_WITHDRAW, STAKING_WITHDRAW returning a
 * different-denomination receipt), drain the ENTIRE bucket carry instead of a
 * proportional slice. The receipt token (e.g., mevUSDC shares) and the returned asset
 * (USDC) have incompatible quantity scales — proportional slicing yields ~$0 basis.
 */
private void restoreFullBucket(
        NormalizedTransaction.Flow flow,
        PositionState position,
        ContinuityBucket bucket
) {
    CarryTransfer carry = continuityCarryService.drainFullBucket(bucket, position.assetKey());
    if (carry == null) {
        flowSupport.applyUnknownTransfer(flow, position);
        return;
    }
    flowSupport.restoreToPosition(
            flow.getQuantityDelta().abs(),
            position,
            carry.costBasisUsd(),
            carry.uncoveredQuantity(),
            carry.avco()
    );
}
```

### 2. `ReplayPendingTransferKeyFactory.java` — expose `usesWrapperCompositeBucket()`

`wrapperCompositeBucketIdentity()` is already private. Expose a public boolean:

```java
public boolean usesWrapperCompositeBucket(NormalizedTransaction transaction) {
    return wrapperCompositeBucketIdentity(transaction) != null;
}
```

### 3. `ContinuityCarryService.java` — add `drainFullBucket()`

```java
/**
 * Drains the complete carry from the bucket regardless of quantity — used when the
 * outbound receipt token has a different denomination from the inbound return asset
 * (e.g., mevUSDC shares vs. USDC units).
 */
public CarryTransfer drainFullBucket(ContinuityBucket bucket, AssetKey assetKey) {
    if (bucket == null || bucket.totalQuantity().signum() == 0) {
        return null;
    }
    return bucket.drainAll(assetKey);
}
```

**`ContinuityBucket` — add two methods** (both don't currently exist):

```java
/** Returns the total quantity held across all parked carries. */
public BigDecimal totalQuantity() { /* sum carry.quantity() */ }

/**
 * Drains the entire bucket as a single carry. Delegates to {@code take(totalQty, assetKey)}
 * so that cost basis and avco are computed via the standard weighted-average logic.
 * Do NOT implement "max avco" — use the standard take path.
 */
public CarryTransfer drainAll(AssetKey assetKey) {
    BigDecimal total = totalQuantity();
    if (total.signum() <= 0) return null;
    return take(total, assetKey);
}
```

---

## Tests

Add to `AvcoReplayServiceTest.java` or a new `VaultWithdrawReplayTest`:

| Test | Scenario | Expected |
|------|----------|----------|
| `vaultWithdrawWrapperBucketRestoresFullBasis` | VAULT_DEPOSIT 1628 USDC → mevUSDC receipt → VAULT_WITHDRAW mevUSDC → USDC 1628 returned | USDC cbD ≈ $1,628 (not $0.001) |
| `vaultWithdrawNonWrapperBucketSlicesProportionally` | Standard LP_EXIT (same denomination) | Proportional slice unchanged |
| `vaultWithdrawPartialReturnUsesFullBucket` | Partial withdrawal (vault returns less than deposited) | cbD = full bucket basis |

---

## Acceptance Criteria

1. The 3 MEV Capital VAULT_WITHDRAW transactions return USDC with `cbD > $1,000` each.
2. Total recovered cbD for the 3 txs ≥ $3,500.
3. LENDING_WITHDRAW with non-FAMILY receipt tokens (e.g., aUSDC, gauge tokens) also benefit: proportional slice is replaced by full drain — this is a fix, not a regression. Verify with existing AAVE gauge test cases.
4. Partial VAULT_WITHDRAW: a 50% withdrawal (returning 814 USDC from a 1628 USDC deposit) recovers ≈ 50% of the deposit cbD (not 100% and not ~$0). Verified by test `vaultWithdrawPartialReturnUsesFullBucket`.
5. All existing tests pass (including LP_EXIT with FAMILY: receipt — proportional path unchanged).

---

## Risks

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| `drainAll` double-drains if inbound restore is called twice | Low | Bucket is cleared on drain; second call returns null |
| Other wrapper protocols (STAKING, LENDING) with same denomination — fix incorrectly drains full bucket | Medium | `usesWrapperCompositeBucket` is only true for non-FAMILY receipt tokens; if deposit+withdraw are same asset, slicing and draining produce identical results |
| Bug B transactions remain zero-cbD | Confirmed | Bug B requires normalization fix — separate plan |

---

## Ordered Tasks

1. Expose `usesWrapperCompositeBucket()` in `ReplayPendingTransferKeyFactory`.
2. Add `totalQuantity()` and `drainAll(AssetKey)` to `ContinuityBucket` (drainAll delegates to `take(totalQty, assetKey)`).
3. Add `drainFullBucket()` to `ContinuityCarryService`.
4. Add `restoreFullBucket()` to `TransferReplayHandler` and wire in the `isBucketInbound` branch with the `wrapperBucket` guard.
5. Write 3 tests (including partial withdrawal test promoted to named AC).
6. Prod rebuild + `financial-logic-auditor` acceptance check.
