# Implementation Plan: Equilibria LP Entry Classification Fix

**Slug:** `equilibria-lp-entry-classification-fix`  
**Date:** 2026-07-07  
**Status:** Pending approval

---

## Scope

- **Wallets:** MANTLE network, wallet `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f`
- **Protocols:** Equilibria Finance, Pendle
- **Transactions investigated:**
  - `0x8dd9dbad58e886cd` — Equilibria LP deposit (misclassified as `LP_EXIT Pendle`)
  - `0xf7f8908b455261dc` — Equilibria LP exit (`correlationId=undefined`, wrong AVCO carry)
  - `0xd11e65b07888dbf4` — INIT Capital borrow 100 USDC (not visible in UI)
- **Blocker IDs:** BB-EQB-2, BB-EQB-3, BB-INIT-UI

---

## Root Cause Analysis

### Bug BB-EQB-2: `0x8dd9dbad` misclassified as `LP_EXIT (Pendle)`

**Evidence:**
- Raw tx: `to=0x920873e5b302a619c54c908adfb77a1c4256a3b8`, `functionName=deposit(uint256 _pid, uint256 _amount, bool _stake)`
- Normalized: `type=LP_EXIT, proto=Pendle, classified=HEURISTIC, correlationId=pendle-lp:mantle:pendle-lpt`
- Flows: only `PENDLE-LPT(-0.445041)` outbound — no inbound assets
- Ledger: only `GAS_ONLY` (replay handler rejected the LP_EXIT because PENDLE-LPT went **out**, not in)

**Why misclassified:**
1. `PendleProtocolSemanticClassifier` produces an `LP_EXIT` hint when it sees PENDLE-LPT in the flows
2. `LpSemanticClassifier` (PRE_PROTOCOL_REVIEW, order 151) converts this hint into `LP_EXIT` decision
3. `FunctionNameClassifier` at FINAL_FALLBACK never runs (LpSemanticClassifier wins earlier)
4. `RegistryDirectTypeClassifier` can't find `0x920873e5b302...` (not in registry → wrong address registered)

**Contract discovery:**
- Registry has `0x70f61901658aafb7ae57da0c30695ce4417e72b9` as Equilibria Booster → correct for `zapOutV3SingleToken`
- Actual Equilibria **Depositor** on Mantle: `0x920873e5b302a619c54c908adfb77a1c4256a3b8` → **missing from registry**
- `0x479603de0a8b6d2f4d4eaa1058eea0d7ac9e218d` = Equilibria BaseRewardPool (market-specific, counterparty for PENDLE-LPT transfer)

**Replay impact:**
- PENDLE-LPT LP pool (accumulated from 6 `LP_ENTRY` transactions since April) is referenced by this `LP_EXIT`
- Replay handler sees PENDLE-LPT **outbound** in an LP_EXIT → can't match pattern (LP_EXIT expects LP token **inbound**) → produces `GAS_ONLY`
- Pool may or may not be drained; regardless the pool reference is corrupted

### Bug BB-EQB-3: `0xf7f8908b` Equilibria LP EXIT has `correlationId=undefined`

**Evidence:**
- Normalized: `type=LP_EXIT, proto=Equilibria, correlationId=undefined`
- Flows include `eqbPENDLE-LPT(+0.445041)` and `eqbPENDLE-LPT(-0.445041)` (self-canceling) + `PENDLE(+0.012731)` + `cmETH(+0.862092)`
- CMETH ledger: `REALLOCATE_IN avco=4382.93 netAvco=4382.93` — **market price, not LP cost**

**Expected behavior:**
- cmETH returned from Equilibria represents the Pendle LP position (cmETH→PENDLE-LPT→Equilibria staking)
- cmETH REALLOCATE_IN should carry the Pendle LP cost basis (~$2,155/cmETH from Aug 1 entry)
- Instead, cmETH enters at market price ($4,382.93 in Sep 2025) → **+$2,228/cmETH AVCO spike**

**Why incorrect:**
- `correlationId=undefined` means LP_EXIT handler can't look up the Pendle LP pool (`pendle-lp:mantle:pendle-lpt`)
- The pool has PENDLE-LPT with LP cost basis from `0xfaf8160cd26a` LP_ENTRY (Aug 1): 0.445041 PENDLE-LPT at cmETH net basis ~$2,155.39
- Since `correlationId=undefined`, the handler doesn't find the pool → falls back to acquiring cmETH at market price

### Bug BB-INIT-UI: BORROW 100 USDC not visible in asset ledger UI

**Evidence:**
- DB: `0xd11e65b07888` = `type=BORROW, status=CONFIRMED, proto=INIT Capital`
- Flows: `BUY:USDC(+100)`, `FEE:MNT`
- Ledger: `USDC ACQUIRE qty=100`, `MNT REALLOCATE_OUT`
- UI "init" filter shows only 2 events (Lending Withdraw + Borrow $900), not the $100 borrow

**Hypothesis:** UI groups/merges same-type transactions from same protocol, or the `QTY $` column shows 0 for the primary flow (USDC has no qty delta issue). May also be a UI pagination issue where this event is on a separate page.

---

## Changes Required

### Change 1: Add Equilibria Depositor to protocol-registry.json

**File:** `backend/core/src/main/resources/protocol-registry.json`

Add to MANTLE network:
```json
"0x920873e5b302a619c54c908adfb77a1c4256a3b8": {
    "name": "Equilibria Depositor (Mantle)",
    "network": "MANTLE",
    "family": "YIELD",
    "role": "ROUTER",
    "protocolName": "Equilibria",
    "protocolVersion": "v3",
    "eventType": "STAKING_DEPOSIT"
}
```

### Change 2: Update EquilibriaProtocolSemanticClassifier — detect deposit calls

**File:** `backend/core/src/main/java/com/walletradar/application/normalization/pipeline/classification/onchain/protocol/equilibria/EquilibriaProtocolSemanticClassifier.java`

Add detection for `deposit(uint256 _pid, uint256 _amount, bool _stake)` (methodId `0x43a0d066`) on Equilibria Depositor:

```java
// If toAddress == Equilibria Depositor AND functionName starts with "deposit"
// → produce STAKING_DEPOSIT hint (high confidence)
```

This hint must fire **before** `LpSemanticClassifier` converts the Pendle LP_EXIT hint. Add a `StakingSemanticClassifier` (see Change 3) to convert this hint.

### Change 3: Add StakingSemanticClassifier (PRE_PROTOCOL_REVIEW, order 148)

**File (new):** `backend/core/src/main/java/com/walletradar/application/normalization/pipeline/classification/onchain/family/StakingSemanticClassifier.java`

Mirrors `BorrowSemanticClassifier` but for `STAKING_DEPOSIT` hint type. Runs at PRE_PROTOCOL_REVIEW order 148, before `LpSemanticClassifier` (order 151). This ensures Equilibria deposit correctly wins over Pendle LP_EXIT interpretation.

### Change 4: Assign `pendle-lp:mantle:pendle-lpt` correlationId to Equilibria LP EXIT

**File:** Update `EquilibriaProtocolSemanticClassifier` or add `EquilibriaLpCorrelationSupport`

When `zapOutV3SingleToken` is called on the Equilibria Booster (`0x70f61901`) and cmETH inflow comes from the Pendle cmETH market (`0x2ab88ac7458faec2e952bb79cc1be6577bf63e70`), the transaction should receive `correlationId=pendle-lp:mantle:pendle-lpt`.

This links the Equilibria LP EXIT to the Pendle LP receipt pool, so the replay handler can carry the cmETH LP cost basis (net AVCO ~$2,155 instead of market $4,382).

**Implementation option A:** In `EquilibriaProtocolSemanticClassifier`, produce an `LP_EXIT` hint with Pendle correlation info, or use a `correlationHint` in the semantic hint.

**Implementation option B:** In `ReplayPendingTransferKeyFactory`, detect the Pendle market counterparty in Equilibria LP exits and return the `pendle-lp:mantle:pendle-lpt` key.

**Implementation option C:** Let `0x8dd9dbad` (STAKING_DEPOSIT) produce a `REALLOCATE_OUT` effect that keeps PENDLE-LPT in the LP pool (not a staking corridor), and `0xf7f8908b` Equilibria LP_EXIT with `correlationId=pendle-lp:mantle:pendle-lpt` consumes it.

**Recommended:** Option B or C — add correlation support to `ReplayPendingTransferKeyFactory`.

### Change 4b: Fix `LpReceiptExitReplayHandler.isLpReceiptExit()` to accept `pendle-lp:` prefix (CRITICAL)

**File:** `backend/core/src/main/java/com/walletradar/costbasis/application/replay/handler/LpReceiptExitReplayHandler.java`

Currently `isLpReceiptExit()` only accepts correlation IDs with the `lp-position:` prefix:
```java
private static final String LP_CORR_PREFIX = "lp-position:";
// ...
if (!transaction.getCorrelationId().startsWith(LP_CORR_PREFIX)) {
    return false;
}
```

`0xf7f8908b` (Equilibria LP EXIT) will be assigned `correlationId=pendle-lp:mantle:pendle-lpt` (Change 4). Without this fix, `isLpReceiptExit()` returns `false` → handler never fires → cmETH still acquires at market price. The entry handler `LpReceiptEntryReplayHandler` already accepts both prefixes; the exit handler must be updated to match.

**Fix:**
```java
private static final String LP_CORR_PREFIX = "lp-position:";
private static final String PENDLE_LP_CORR_PREFIX = "pendle-lp:";

// in isLpReceiptExit():
if (!transaction.getCorrelationId().startsWith(LP_CORR_PREFIX)
        && !transaction.getCorrelationId().startsWith(PENDLE_LP_CORR_PREFIX)) {
    return false;
}
```

### Change 5: Verify STAKING_DEPOSIT does not drain `lp_receipt_basis_pools`

**File:** No code change expected — this is a verification step.

`STAKING_DEPOSIT` is processed by `TransferReplayHandler`, **not** `LpReceiptExitReplayHandler`. The `lp_receipt_basis_pools` (MongoDB, keyed by `lpCorrelationId = pendle-lp:mantle:pendle-lpt`) is only drained by `LpReceiptExitReplayHandler.restoreInboundFromPool()`. STAKING_DEPOSIT never calls this — the cmETH basis pool survives untouched automatically.

**Required verification:**  
Confirm that the staking corridor key generated by `ReplayPendingTransferKeyFactory` for PENDLE-LPT outbound under STAKING_DEPOSIT does **not** resolve to `pendle-lp:mantle:pendle-lpt` (which would create a spurious LP exit precondition). If it does, add a guard in `ReplayTransferClassifier` to prevent corridor routing for LP-receipt-marker assets under STAKING_DEPOSIT.

### Change 6 (Frontend — lower priority): Fix 100 USDC borrow display

**File:** `frontend/src/app/features/asset-ledger/`

Investigate why `0xd11e65b07888` (BORROW, 100 USDC, INIT Capital) is not visible with "init" filter. Possible causes:
1. Asset ledger page groups events by type/protocol across pages — check pagination
2. The event may be filtered by a "primary flow" display rule that excludes USDC-only borrows
3. Verify in the API response that the event is returned

---

## Acceptance Criteria

1. `0x8dd9dbad` classified as `STAKING_DEPOSIT` (`proto=Equilibria`) in the UI
2. `0xf7f8908b` LP EXIT shows cmETH REALLOCATE_IN with net AVCO ≈ $2,155 (LP cost), not $4,382 (market)
3. cmETH net AVCO at `0xe2f90520f930` (CARRY_OUT after Equilibria exit) ≈ $2,155 range
4. PENDLE received from Equilibria exit is at $0 net AVCO (reward)
5. UI "init" filter shows 3 INIT Capital events (Lending Withdraw + Borrow $900 + Borrow $100)

---

## Risks

| Risk | Severity | Mitigation |
|------|----------|-----------|
| `LpReceiptExitReplayHandler.isLpReceiptExit()` doesn't accept `pendle-lp:` prefix → Change 4 has zero effect, cmETH still at market price | **CRITICAL** | Change 4b: add `pendle-lp:` prefix to the exit handler (mirrors entry handler) |
| Staking corridor key for PENDLE-LPT coincides with LP pool key `pendle-lp:mantle:pendle-lpt` | MEDIUM | Verify in `ReplayPendingTransferKeyFactory`; add assertion in unit test |
| correlationId assignment to Equilibria LP EXIT may require changes in multiple components | MEDIUM | Test with a single unit test simulating the full 3-tx Pendle→Equilibria→exit cycle |
| Other PENDLE-LPT Pendle LP operations on other networks may be affected by correlationId change | LOW | Scope change to MANTLE network only; add network guard |
| eqbPENDLE-LPT self-canceling flows may confuse LP_EXIT handler | LOW | The handler already sees REALLOCATE_IN for EQBPENDLE-LPT; confirm it correctly falls back to PENDLE-LPT pool lookup |

---

## Ordered Implementation Steps

1. **Add registry entry** — Equilibria Depositor `0x920873e5b302...` (MANTLE) [Change 1]
2. **Add StakingSemanticClassifier** at PRE_PROTOCOL_REVIEW order 148 [Change 3]
3. **Update EquilibriaProtocolSemanticClassifier** — detect deposit calls, produce STAKING_DEPOSIT hint [Change 2]
4. **Fix correlationId for Equilibria LP EXIT** — link to `pendle-lp:mantle:pendle-lpt` pool [Change 4]
5. **Fix `LpReceiptExitReplayHandler.isLpReceiptExit()`** — add `pendle-lp:` prefix support [Change 4b] ← **CRITICAL**
6. **Verify STAKING_DEPOSIT corridor key** — confirm no collision with LP pool key [Change 5]
7. **Frontend fix** — investigate and fix INIT Capital 100 USDC visibility [Change 6]
