# Implementation Plan: B-USDC-BYBIT-CORRIDOR-BASIS-CONTAMINATION — bybit-collapsed-v1 FUND CARRY_IN skipped

**Date:** 2026-06-04  
**Severity:** P1  
**Estimated financial impact:** ~$318–849 USDC cost basis understated (BASE wallet `0x1a87f12a`)  
**Stage:** `cost_basis` replay — `ReplayDispatcher.isBybitSelfTransfer()` unconditionally skips both sides of `bybit-collapsed-v1:` pairs

---

## 1. Symptom

USDC AVCO on wallet `0x1a87f12a` (BASE) = **$0.22** instead of ~$1.00. July 19, 2025 Bybit-corridor CARRY_IN brought 900.51 USDC with only $51.74 cost basis (AVCO $0.0574).

---

## 2. Root Cause

### 2.1 What `BybitStreamAuthorityCollapser` does
Assigns `bybit-collapsed-v1:<sha256>` correlation IDs to paired UTA↔FUND INTERNAL_TRANSFER documents (sender + receiver legs).

### 2.2 What `isBybitSelfTransfer()` does (the bug)
`ReplayDispatcher.isBybitSelfTransfer()` normalizes both wallet and counterparty via `positionWalletAddress()`:
- `BYBIT:33625378:UTA` → strips `:UTA` → `BYBIT:33625378`
- `BYBIT:33625378:FUND` → strips `:FUND` → `BYBIT:33625378`

Both normalize identically → method returns `true` → **entire transaction is skipped** (line 113: `return`).

### 2.3 Why skipping is wrong for UTA→FUND transfers
- **UTA side skip is correct**: UTA is collapsed into the main wallet; the SWAP that acquired USDC was already recorded on `BYBIT:33625378`. No double-count needed.  
- **FUND CARRY_IN skip is WRONG**: `BYBIT:33625378:FUND` is a real sub-account that corridors out to BASE independently. Without crediting FUND, it has no basis. When the corridor CARRY_OUT fires from FUND, it carries only its tiny pre-existing balance ($51.74 instead of $901).

### 2.4 July 19, 2025 sequence
| seq | Event | Position | USDC qty | cb |
|-----|-------|----------|----------|-----|
| 4347 | SWAP: buy USDC | BYBIT:33625378 | +901 | +$901 |
| 4348 | UTA→FUND selfTransfer (SKIPPED) | — | — | — |
| 4349 | FUND corridor CARRY_OUT | BYBIT:33625378:FUND | -52 | -$52 (only old balance) |
| 4350 | BASE CARRY_IN | 0x1a87f12a | +900 | +$52 → **AVCO $0.057** |

7 total `bybit-collapsed-v1:` selfTransfers were skipped (~$3,153 USDC basis never flowed to FUND).

---

## 3. Fix (3 changes, analogous to `bybit-earn-onchain-fund-v1:` pattern)

### Change 1: `ReplayDispatcher.isBybitSelfTransfer()` — exclude `bybit-collapsed-v1:`
**File:** `backend/src/main/java/com/walletradar/costbasis/application/replay/dispatch/ReplayDispatcher.java`

Add a guard after the `CROSS_UID_CORRELATION_PREFIX` check:
```java
// bybit-collapsed-v1: pairs a UTA CARRY_OUT with a FUND CARRY_IN.
// Only the UTA side is a no-op; FUND must receive the basis for corridor carry.
// Exclude from self-transfer detection when one wallet ends with :FUND.
if (corrId != null && corrId.startsWith(BybitStreamAuthorityCollapser.COLLAPSED_CORR_PREFIX)) {
    return false;
}
```

Or more precisely, check if one side is `:FUND`:
```java
if (corrId != null && corrId.startsWith(BybitStreamAuthorityCollapser.COLLAPSED_CORR_PREFIX)) {
    String wallet = transaction.getWalletAddress();
    if (wallet != null && (wallet.toUpperCase().endsWith(":FUND")
            || (counterparty != null && counterparty.toUpperCase().endsWith(":FUND")))) {
        return false;
    }
}
```

Make `COLLAPSED_CORR_PREFIX` `public static final String` in `BybitStreamAuthorityCollapser` if it isn't already.

### Change 2: `AccountingAssetIdentitySupport.replayPositionWalletAddress()` — preserve `:FUND` for `bybit-collapsed-v1:`
**File:** `backend/src/main/java/com/walletradar/accounting/support/AccountingAssetIdentitySupport.java`

Add a private constant and extend `isEarnPrincipalPaired()` (or add a new predicate `isBybitCollapsedFundSide()`):

```java
private static final String BYBIT_COLLAPSED_CORRELATION_PREFIX = "bybit-collapsed-v1:";

private static boolean isBybitCollapsedFundSide(NormalizedTransaction transaction) {
    if (transaction == null) return false;
    String corrId = transaction.getCorrelationId();
    if (corrId == null || !corrId.startsWith(BYBIT_COLLAPSED_CORRELATION_PREFIX)) return false;
    String wallet = transaction.getWalletAddress();
    return wallet != null && wallet.toUpperCase(Locale.ROOT).endsWith(":FUND");
}
```

In `replayPositionWalletAddress()`, add before `return positionWalletAddress(transaction)`:
```java
if (isBybitCollapsedFundSide(transaction)) {
    String wallet = transaction.getWalletAddress();
    if (wallet != null && !wallet.isBlank()) {
        return wallet.trim();
    }
}
```

This ensures the FUND CARRY_IN credits `BYBIT:33625378:FUND` (not stripped `BYBIT:33625378`).

### Change 3: `ReplayPendingTransferKeyFactory` — ensure `bybit-collapsed-v1:` uses `corr-family:` key
**File:** `backend/src/main/java/com/walletradar/costbasis/application/replay/support/ReplayPendingTransferKeyFactory.java`

Verify (and add if missing) that `bybit-collapsed-v1:` correlation IDs route to the `corr-family:<corrId>:<assetKey>` bucket. This ensures the UTA CARRY_OUT and FUND CARRY_IN are matched as a pair.

Check the existing `isBybitEarnPrincipalPairedTransfer()` method — if it only checks specific earn prefixes, add `COLLAPSED_CORR_PREFIX` to the list or add a separate check for `bybit-collapsed-v1:`.

### Change 4: `BybitVenueInternalReplayHandler` — optionally add `bybit-collapsed-v1:` to `applies()`
**File:** `backend/src/main/java/com/walletradar/costbasis/application/replay/handler/BybitVenueInternalReplayHandler.java`

Check if this handler should process `bybit-collapsed-v1:` pairs (similar to how it handles `bybit-earn-onchain-fund-v1:`). If `TransferReplayHandler` already handles it generically via `corr-family:`, this may not be needed.

---

## 4. Acceptance Criteria

After prod rebuild:
1. All 7 `bybit-collapsed-v1:` UTA→FUND USDC selfTransfers generate ledger points on `BYBIT:33625378:FUND`
2. `BYBIT:33625378:FUND` USDC position has correct basis before each corridor event
3. `0x1a87f12a` (BASE) USDC AVCO ≈ $1.00 (not $0.22)
4. No regression in existing UTA SWAP/EARN accounting

Verification query:
```javascript
// Check FUND USDC ledger points
db.asset_ledger_points.find(
  {walletAddress: {$regex: "BYBIT.*FUND", $options:"i"}, assetKey: {$regex:"USDC"}},
  {seq:1, pointType:1, quantityDelta:1, costBasisDelta:1, cbAfterUsd:1, avcoAfterUsd:1}
).sort({seq:1}).toArray()

// Check BASE USDC AVCO
db.asset_ledger_points.find(
  {walletAddress: {$regex: "0x1a87f12a"}, assetKey: {$regex:"USDC"}},
  {seq:1, avcoAfterUsd:1}
).sort({seq:-1}).limit(3).toArray()
```

---

## 5. Risks

- The UTA side of `bybit-collapsed-v1:` must continue to use `positionWalletAddress()` (stripped, main wallet). Only the FUND side should use full address. Verify by checking that UTA CARRY_OUT still routes to `BYBIT:33625378`.
- May affect non-USDC assets — all assets in UTA→FUND collapsed pairs need the same treatment. The fix is generic (based on `:FUND` suffix + `bybit-collapsed-v1:` prefix), so it covers all assets.
- Pre-existing `isBybitCorridorFromFund()` already handles corridor transactions from FUND; ensure no conflict.
