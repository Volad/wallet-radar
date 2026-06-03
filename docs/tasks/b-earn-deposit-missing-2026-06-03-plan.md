# Implementation Plan — B-EARN-DEPOSIT-MISSING

**Date:** 2026-06-03  
**Blocker:** B-EARN-DEPOSIT-MISSING (P2, ~$1,551 cbD)  
**Audit refresh:** 9

---

## Problem Summary

Two Bybit "On-chain Earn subscription" FUNDING_HISTORY events drained basis from the main FUND account, but no matching EARN-account receive event exists in the database:

| Normalized TX ID | Asset | Qty | cbD lost | Why |
|---|---|---|---|---|
| `BYBIT-33625378:FUNDING_HISTORY:f140c660...` | ETH | −0.6929746 | −$1,104 | EARN-side FH event absent from DB |
| `BYBIT-33625378:FUNDING_HISTORY:a854608d...` | METH | −0.6686503 | −$448 | EARN-side FH event absent from DB |

Both have `correlationId: ""`, `continuityCandidate: false`, `type: INTERNAL_TRANSFER`.

## Root Cause Analysis

### How other On-chain Earn subscriptions work

The `BybitStreamAuthorityCollapser.unifyOpposingCorrelations` pairs FUND-side and EARN-side
`INTERNAL_TRANSFER` events that have the same `(uid, asset, |qty|)` within a 30-second window.
For BBSOL and Apr-28 ETH subscriptions the EARN-side FUNDING_HISTORY event IS present in the DB,
so pairing succeeds and both sides get a `bybit-collapsed-v1:` corrId.

### Why METH (Mar 12) and ETH (Apr 18) fail

Bybit's API did not emit the EARN-account receive event for these two specific subscriptions.
The DB only contains the FUND-side outflow. The pairer finds no counterpart → corrId stays empty.
The replay engine emits CARRY_OUT from FUND (draining $1,551 cbD) but no CARRY_IN at EARN.

### Comparison with known-working cases

| Event | Date | Outcome | Mechanism |
|---|---|---|---|
| ETH Flexible Savings Subscription | Jan 10 | ✅ Paired | EARN_FLEXIBLE_SAVING counterpart present |
| ETH On-chain Earn subscription | Jan 12 | ✅ Paired | `bybit-earn-principal-v1:` pairer matched LENDING_DEPOSIT |
| BBSOL Flexible Savings Subscription | various | ✅ Paired | EARN_FLEXIBLE_SAVING counterpart present |
| ETH On-chain Earn subscription | Apr 28 | ✅ Paired | `bybit-collapsed-v1:` EARN FUNDING_HISTORY present |
| **ETH On-chain Earn subscription** | **Apr 18** | ❌ Orphan | **EARN FUNDING_HISTORY missing** |
| **METH On-chain Earn subscription** | **Mar 12** | ❌ Orphan | **EARN FUNDING_HISTORY missing** |

---

## Fix Design

### Approach: Synthetic EARN counterpart in a new repair service

Create `BybitOnChainEarnOrphanRepairService` (clarification package). For each FUND
`INTERNAL_TRANSFER` with empty `correlationId` that has no corresponding EARN-side entry,
synthesize a matching EARN `INTERNAL_TRANSFER` and assign a shared deterministic corrId to both.

This mirrors the pattern used by `TurtleVaultBurnRepairService` for missing on-chain burn legs,
and `BridgePairContinuityRepairService` for sealed bridge IN legs.

### Detection Query

```
source = BYBIT
type = INTERNAL_TRANSFER
excludedFromAccounting ≠ true
correlationId is blank
walletAddress ends with :FUND (principal flow qty < 0)
```

For each candidate: verify no existing EARN-side counterpart exists — `INTERNAL_TRANSFER` at
`BYBIT:{uid}:EARN` with same asset family, same `|qty|` (tolerance ≤ 1e-8), within ±6 hours.

### Synthetic transaction spec

```
_id:               "bybit-earn-onchain-synthetic-v1:{sha256(fundTxId)}"
source:            BYBIT
type:              INTERNAL_TRANSFER
status:            CONFIRMED
walletAddress:     "BYBIT:{uid}:EARN"
blockTimestamp:    same as FUND event
flows:             [{assetSymbol: X, quantityDelta: +|qty|, role: TRANSFER, accountRef: "BYBIT:{uid}:EARN"}]
correlationId:     "bybit-earn-onchain-v1:{sha256(fundTxId + "|" + asset + "|" + |qty|)}"
matchedCounterparty: "BYBIT:{uid}:FUND"
continuityCandidate: true
excludedFromAccounting: false
```

### FUND event update

```
correlationId:     same "bybit-earn-onchain-v1:..." as synthetic
matchedCounterparty: "BYBIT:{uid}:EARN"
continuityCandidate: true
```

With `continuityCandidate=true` and a shared corrId, the replay engine routes both legs to the
same `corr-family:{corrId}:{assetIdentity}` queue:
- FUND leg → CARRY_OUT (drains FUND basis, as before)
- EARN leg → CARRY_IN (credits EARN with the same cbD)

### Guard against double-execution (idempotency)

Before synthesizing, check if a synthetic event with the computed `_id` already exists in
`normalized_transactions`. If it does, skip (already repaired on a prior run).

---

## Files to Create / Modify

| File | Change |
|---|---|
| `backend/.../clarification/BybitOnChainEarnOrphanRepairService.java` | **NEW** — repair service |
| `backend/.../job/linking/LinkingBatchProcessor.java` | Inject + call after `bybitStreamAuthorityCollapser` but before `bybitInternalTransferOrphanFallbackService` |
| `backend/.../clarification/BybitOnChainEarnOrphanRepairServiceTest.java` | **NEW** — unit tests |

### Call order in `LinkingBatchProcessor`

```
// existing
processed += bybitStreamAuthorityCollapser.collapseMirrors();        // Cycle/7
heartbeat.run();

// NEW — insert here
processed += bybitOnChainEarnOrphanRepairService.repairOrphans();    // B-EARN-DEPOSIT-MISSING
heartbeat.run();

// existing
bybitInternalTransferPairer.repairAll();                              // Cycle/15 R3
...
processed += bybitInternalTransferOrphanFallbackService.reconcileOrphanInternals(); // Cycle/12
```

Running before `BybitInternalTransferOrphanFallbackService` prevents the FUND leg from being
re-typed to `EXTERNAL_TRANSFER_OUT` (the orphan fallback only processes `continuityCandidate=true`
legs, and the FUND events currently have `continuityCandidate=false`, but after repair they'll have
`continuityCandidate=true`, so placement is important).

---

## Unit Test Plan

| Test | Input | Expected |
|---|---|---|
| `repairOrphans_fundOnlySubscription_createsSyntheticEarnCounterpart` | 1 FUND INTERNAL_TRANSFER ETH -0.69, corrId="" | Creates 1 synthetic EARN INTERNAL_TRANSFER ETH +0.69, both get `bybit-earn-onchain-v1:` corrId |
| `repairOrphans_alreadyPaired_skips` | FUND with non-blank corrId | 0 changes |
| `repairOrphans_earnSideAlreadyExists_skips` | FUND -0.69 + existing EARN +0.69 both corrId="" | Pairs them without creating synthetic |
| `repairOrphans_idempotent_secondRunNoOp` | Synthetic already present from prior run | 0 changes |
| `repairOrphans_differentUid_noFalsePairing` | Two FUND events from different uids | Each paired only within same uid |
| `repairOrphans_excludedFundEvent_skips` | FUND excludedFromAccounting=true | 0 changes |

---

## Acceptance Criteria

After `./scripts/prod-reset-rebuild-backend.sh --skip-frontend`:

1. `BYBIT-33625378:FUNDING_HISTORY:f140c660...` has `correlationId: "bybit-earn-onchain-v1:..."` and `continuityCandidate: true`
2. `BYBIT-33625378:FUNDING_HISTORY:a854608d...` same
3. Synthetic EARN document `bybit-earn-onchain-synthetic-v1:{hash}` exists for each
4. `asset_ledger_points` at `BYBIT:33625378:EARN` has ETH CARRY_IN cbD ≈ +$1,104 and METH CARRY_IN cbD ≈ +$448
5. Total basis at `BYBIT:33625378:EARN` for ETH and METH ≥ $0 (no longer missing)
6. `BybitOnChainEarnOrphanRepairServiceTest` fully green
7. All existing linking tests pass

---

## Estimated Impact

- ETH: +$1,104 cbD recovered at EARN
- METH: +$448 cbD recovered at EARN
- Total: **~$1,551 cbD** no longer lost from accounting universe
- These assets appear to still be subscribed (no redemption events seen) — once redeemed,
  the EARN CARRY_OUT will correctly carry basis back to FUND

---

## Risk

**Low.** The two affected events are identifiable by exact IDs. The repair service:
- Only creates synthetic events where no EARN counterpart exists
- Is idempotent (second run is a no-op)
- Does not modify existing normalized transactions' flows, just assigns corrId
- If the synthetic EARN events cause unexpected replay behavior, they can be excluded
  individually by ID without impacting the rest of the pipeline
