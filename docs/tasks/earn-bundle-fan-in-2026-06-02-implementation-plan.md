# Implementation Plan — B-EARN-BUNDLE: Multi-Leg Bundle Fan-In Defect

**Date**: 2026-06-02  
**Blocker ID**: B-EARN-BUNDLE  
**Severity**: P2  
**Estimated shortfall**: ~$359 (historical; all EARN positions now null — see instance table for per-asset breakdown)

---

## Scope

- **Wallets**: BYBIT:33625378 (all sub-accounts: UTA, FUND, EARN)
- **Networks**: Bybit internal (off-chain)
- **Assets**: ONDO, LDO, TON, LINK, ARB (any asset in Bybit EARN FLEXIBLE_SAVING)
- **Blocker IDs**: B-EARN-BUNDLE

---

## Root Cause

### Bundle structure

A Bybit Earn subscription event produces three normalized transactions in time order:

| Leg | walletAddress | qty sign | blockTimestamp | corrId |
|---|---|---|---|---|
| EARN inbound | BYBIT:33625378:EARN | + (e.g., +8.341 ONDO) | T | `bybit-it-bundle-v1:…` |
| FUND outbound | BYBIT:33625378:FUND | − (e.g., −0.016 ONDO) | T+1s | same corrId |
| UTA outbound | BYBIT:33625378:UTA | − (e.g., −8.325 ONDO) | T+1.033s | same corrId |

### Replay sequence (current, broken)

1. **EARN inbound (T)**: `applyBybitMultiLegBundleTransfer` positive-qty path. Queue (`corr-family:bybit-it-bundle-v1:…:ONDO`) is empty → 8.341 ONDO materialized provisionally → `pendingInbound(qty=8.341)` enqueued.

2. **FUND outbound (T+1s)**: negative-qty path. `findUniqueBridgeQueueIndex(queue, true)` finds `pendingInbound(8.341)`. **Removes it from queue.** Calls `attachLateCarryToPendingInbound(pendingInbound, fundCarry)`.
   - `internalAccountInboundCarry(fundCarry, pendingInbound.quantity()=8.341, ONDO)`:
     - `sliceQuantity = min(0.016, 8.341) = 0.016`
     - `allocatedBasis = 0.015 × (0.016/0.016) = $0.015`
   - `effectiveCarry = (qty=0.016, cbD=$0.015)`
   - `applyAuthoritativeLateInboundCarryBasis(destination, provisional, $0.015)` → EARN position's basis set to **$0.015** for 8.341 ONDO
   - CARRY_IN emitted on FUND outbound transaction — **basis underflow: $0.015 instead of ~$8.04**

3. **UTA outbound (T+1.033s)**: negative-qty path. Queue **is empty** (FUND already consumed the pending inbound). `findUniqueBridgeQueueIndex(queue, true)` returns −1. UTA carry ($8.04 for 8.325 ONDO) is pushed as `pendingOutbound`. **UTA carry is orphaned — never consumed.**

### Why the FIFO fallback (B-ONDO-CARRY-1 fix) doesn't fix this

The FIFO fallback in the **positive-qty** (inbound) path tries to drain from `bybit-earn-carry:uid:asset` after the primary `corr-family:` queue is exhausted. But in B-EARN-BUNDLE, the EARN inbound arrives FIRST and the queue is empty → it enqueues a pending inbound. The FIFO fallback is not reached because `remaining > 0` is only true if no carries were found. The EARN inbound found nothing and enqueued itself — then FUND consumed it. The FIFO fallback never triggers for EARN.

### Instance table (observed)

| asset | EARN qty | FUND qty | FUND cbD | UTA qty | UTA cbD | EARN cbD (actual) | EARN cbD (expected) |
|---|---|---|---|---|---|---|---|
| ONDO | 8.341 | 0.016 | $0.015 | 8.325 | $8.038 | $0.015 | ~$8.05 |
| ONDO | 78.942 | 0.054 | $0.054 | 78.888 | $85.26 | $85.26 ✓ | ~$85.3 |
| ONDO | 22.986 | 0.016 | $0.004 | 22.970 | $21.60 | $0.004 | ~$21.60 |
| ONDO | 14.990 | 0.016 | $0.004 | 14.974 | $13.25 | $0.004 | ~$13.25 |
| LDO | 14.973 | ~0 | ~$0 | ~14.97 | ~$15 | ~$0 | ~$15 |
| LDO | 102.4 | ~0 | ~$0 | ~102.4 | ~$102 | ~$0 | ~$102 |
| TON | 32.403 | ~0 | ~$0 | ~32.4 | ~$97 | ~$0.01 | ~$97 |
| LINK | 6.894 | ~0 | ~$0 | ~6.89 | ~$97 | ~$0 | ~$97 |
| ARB | 14.424 | ~0 | ~$0 | ~14.4 | ~$5 | ~$0 | ~$5 |

Note: When FUND and UTA have identical second-resolution timestamps, sort order varies. In some bundles UTA arrives before FUND, so UTA attaches and FUND's tiny carry is orphaned (negligible impact).

**Failed stage**: `move_basis` — `applyBybitMultiLegBundleTransfer` negative-qty path consumes the single pending inbound on the first outbound leg, preventing subsequent legs from attaching.

---

## Proposed Fix

### Strategy: partial carry attachment with re-enqueue

When the negative-qty (outbound) path finds a pending inbound and `carry.quantity() < pendingInbound.quantity()`, do NOT fully consume the pending inbound. Instead:

1. Apply the outbound's partial carry to the EARN position (for the carry's own qty).
2. Create a reduced pending inbound: `qty = pendingInbound.qty − carry.qty`.
3. Re-enqueue the reduced pending inbound at the **front** of the queue.
4. Emit a CARRY_IN attributed to the current outbound transaction (for carry.qty).

When the next (larger) outbound leg arrives, it finds the reduced pending inbound. If its carry qty >= reduced pending qty → full consume → emit final CARRY_IN.

### Changes

#### 1. `CarryTransfer.java` — add `withReducedQuantityAndProvisional` factory

**Do NOT add a plain `withReducedQuantity(qty)` factory** — it would preserve the full `provisionalBasisUsd` and reproduce the existing bug when `applyAuthoritativeLateInboundCarryBasis` subtracts the full provisional for a partial-leg attachment.

```java
/**
 * Creates a new pending-inbound carry with reduced quantity and proportionally scaled
 * provisional basis. Used when a partial outbound leg attaches carry to an N-leg bundle
 * pending inbound: the remaining pending inbound is re-enqueued with the leftover qty and
 * the proportional share of the original provisional that has not yet been replaced.
 *
 * @param newQty        the remaining uncovered quantity after the partial leg
 * @param newProvisional the remaining provisional basis (original − portion already replaced)
 */
public CarryTransfer withReducedQuantityAndProvisional(BigDecimal newQty, BigDecimal newProvisional) {
    return new CarryTransfer(
            newQty,
            BigDecimal.ZERO,   // coveredQuantity = 0 (still fully uncovered)
            newQty,            // uncoveredQuantity = newQty
            costBasisUsd,      // 0 for pending inbounds
            avco,
            pendingInbound,
            assetKey,
            newProvisional,
            sourceFlowRef
    );
}
```

#### 2. `TransferReplayHandler.java` — modify `applyBybitMultiLegBundleTransfer` outbound path

**Current** (lines ~538–554):
```java
int pendingInboundIndex = matcher.findUniqueBridgeQueueIndex(queue, true);
if (pendingInboundIndex >= 0) {
    CarryTransfer pendingInbound = matcher.removeQueueElement(queue, pendingInboundIndex);
    attachLateCarryToPendingInbound(
            transaction, flow, flowIndex, replayState.positions(),
            pendingInbound, carry, replayState.ledgerPointCollector()
    );
    if (queue.isEmpty()) {
        replayState.pendingTransfers().remove(transferKey);
    }
    return flowSupport.continuityBasisEffect(transaction, flow);
}
```

**Proposed**:
```java
// Named constant at class level (not inline BigDecimal literal):
private static final BigDecimal EARN_BUNDLE_PARTIAL_LEG_THRESHOLD = new BigDecimal("0.001");

// Inside applyBybitMultiLegBundleTransfer, negative-qty path:
int pendingInboundIndex = matcher.findUniqueBridgeQueueIndex(queue, true);
if (pendingInboundIndex >= 0) {
    CarryTransfer pendingInbound = matcher.removeQueueElement(queue, pendingInboundIndex);
    boolean isPartialLeg = carry.quantity() != null
            && pendingInbound.quantity() != null
            && carry.quantity().compareTo(pendingInbound.quantity()) < 0
            && carry.quantity().compareTo(EARN_BUNDLE_PARTIAL_LEG_THRESHOLD) > 0;

    if (isPartialLeg) {
        // Partial outbound leg (e.g., FUND in a UTA+FUND+EARN bundle): attach this leg's
        // carry for its own qty slice and re-enqueue the remaining pending inbound so the
        // next (larger) outbound leg can also attach. This implements N-outbound-to-1-inbound
        // fan-in without losing UTA's basis to premature pending-inbound consumption.
        //
        // provisionalBasisUsd must be partitioned proportionally to avoid double-subtracting
        // the full provisional when attachLateCarryToPendingInbound is called for each leg.
        BigDecimal fullProvisional = pendingInbound.provisionalBasisUsd() != null
                ? pendingInbound.provisionalBasisUsd() : BigDecimal.ZERO;
        BigDecimal scaledProvisional = pendingInbound.quantity().signum() > 0
                ? fullProvisional.multiply(carry.quantity().divide(pendingInbound.quantity(), MC), MC)
                : BigDecimal.ZERO;
        BigDecimal remainingProvisional = fullProvisional.subtract(scaledProvisional, MC);

        // slicedForAttach represents "the portion of the pending inbound covered by this leg"
        CarryTransfer slicedForAttach = new CarryTransfer(
                carry.quantity(), BigDecimal.ZERO, carry.quantity(),
                BigDecimal.ZERO, null, true,
                pendingInbound.assetKey(), scaledProvisional, pendingInbound.sourceFlowRef()
        );
        attachLateCarryToPendingInbound(
                transaction, flow, flowIndex, replayState.positions(),
                slicedForAttach, carry, replayState.ledgerPointCollector()
        );

        // Re-enqueue the remaining pending inbound at front so the next outbound leg finds it.
        BigDecimal remainingQty = pendingInbound.quantity().subtract(carry.quantity(), MC);
        queue.addFirst(pendingInbound.withReducedQuantityAndProvisional(remainingQty, remainingProvisional));
    } else {
        // Full (or near-full) outbound leg: consume pending inbound and emit as before.
        attachLateCarryToPendingInbound(
                transaction, flow, flowIndex, replayState.positions(),
                pendingInbound, carry, replayState.ledgerPointCollector()
        );
    }
    if (queue.isEmpty()) {
        replayState.pendingTransfers().remove(transferKey);
    }
    return flowSupport.continuityBasisEffect(transaction, flow);
}
```

#### 3. `attachLateCarryToPendingInbound` — verify `pendingInbound.quantity()` usage

When called with `slicedForAttach` (qty = carry.qty, provisionalBasisUsd = scaledProvisional):
- `internalAccountInboundCarry(carry, slicedForAttach.qty, assetKey)`:
  - `sliceQuantity = min(carry.qty, carry.qty) = carry.qty` ✓
  - `allocatedBasis = carry.cbD × 1.0 = carry.cbD` ✓
- `applyAuthoritativeLateInboundCarryBasis(destination, scaledProvisional, carry.cbD)`:
  - Replaces only `scaledProvisional` portion of EARN basis with `carry.cbD` ✓
  - EARN position after FUND leg: `basis ≈ fullProvisional - scaledProvisional_fund + fundCbD` (near unchanged from spot) ✓
- After UTA full-consume leg: `basis = prevBasis - remainingProvisional + utaCbD ≈ fundCbD + utaCbD` ✓

No double-subtraction of full provisional. Math is sound.

---

## Required Documentation Updates

- `docs/03-accounting.md` — add note under "Bybit EARN Subscription": multi-leg N-to-1 bundle, FUND is a minor leg (housekeeping), UTA carries the principal basis. Document that EARN CARRY_IN attribution is split across outbound legs.
- No new ADR required (this is a bug fix, not a policy change). The existing ADR for corridor proportional basis (ADR-019) remains valid.

---

## Acceptance Criteria

**AC-1**: All `bybit-it-bundle-v1:` CARRY_INs at `BYBIT:33625378:EARN` have `costBasisDeltaUsd > 0` for ONDO, LDO, TON, LINK, ARB subscriptions.

**AC-2**: Total ONDO EARN cbD ≥ $650 (up from current $344; the `bybit-collapsed-v1:` deficit is a separate issue not addressed by this plan — do not count it toward this AC).

**AC-3**: Total LDO EARN cbD > $160 (was ~$0, expected ~$163).

**AC-4**: Total TON EARN cbD > $90 (was ~$0, expected ~$118).

**AC-5**: Total LINK EARN cbD > $90 (was ~$0, expected ~$97).

**AC-6**: No regression on BTC/ETH `bybit-collapsed-v1:` or `bybit-cross-uid-v1:` CARRY_INs (AC from prior fixes still pass).

**AC-7**: Pipeline PENDING=0 after rebuild.

**AC-8** (regression guard): For bundles where UTA already attached correctly (the ONDO cases with `cbD=$85.26`), verify the new cbD is at least as large as before (no regression from the partial path).

**AC-9**: Total ARB EARN cbD > $4 (was ~$0; expected ~$5 for 14.424 ARB).

**AC-10**: After rebuild, verify that EARN redemption events (LENDING_WITHDRAW / CARRY_OUT on BYBIT:33625378:EARN) carry the recovered cbD to the destination. Check at least one ONDO and one LDO redemption: the DISPOSE or CARRY_OUT on the redemption side must have `costBasisDeltaUsd` reflecting the corrected basis (not the prior near-zero basis). This is where the actual P&L correction manifests.

**AC-11**: No `BYBIT:33625378:EARN` position has `totalCostBasisAfterUsd < 0` at any replay sequence point. The proportional provisional scaling must prevent negative-basis interim states.

**AC-12**: Unit test (in `AvcoReplayServiceTest` or a new `EarnBundleFanInTest`) with a mock `[EARN inbound, FUND outbound, UTA outbound]` sequence verifying:
- EARN position cbD after FUND = approximately provisional (no large drop)
- EARN position cbD after UTA ≈ fundCbD + utaCbD
- Two CARRY_IN ledger points emitted (one from FUND, one from UTA)

**AC-13**: `bybit-earn-carry:BYBIT-33625378:*` FIFO queues are empty after full replay (no orphaned UTA carries remain unmatched in the FIFO fallback queue).

---

## Risks

1. **Double-subtraction of provisional basis**: If `applyAuthoritativeLateInboundCarryBasis` subtracts the full provisional when only a partial carry is applied, the EARN position basis may go negative temporarily. Mitigated by scaling `provisionalBasisUsd` in the sliced pending inbound.

2. **Threshold tuning**: `EARN_BUNDLE_PARTIAL_LEG_THRESHOLD` must be small enough to exclude meaningful FUND legs but large enough to exclude dust. Value `0.001` is conservative.

3. **Ordering assumption**: The fix assumes FUND always arrives before UTA (i.e., FUND is the "small" leg). If UTA arrives first and FUND second, FUND has qty < UTA qty → FUND is treated as partial, UTA was already consumed. This is acceptable (FUND's tiny carry applies to its partial qty, EARN already got UTA's full cbD).

4. **Existing FIFO fallback interaction**: The FIFO fallback (B-ONDO-CARRY-1 fix) in the positive-qty path may interfere if a prior bundle's orphaned UTA carry ends up in the FIFO queue and gets consumed by a later EARN inbound. With this fix, UTA carries should no longer be orphaned, so the FIFO queue for Earn subscriptions should remain empty (no stale carries).

5. **Scope**: All EARN positions are now null (redeemed). The fix corrects historical P&L only (EARN had low AVCO → redemptions used low basis → inflated P&L on disposal).
