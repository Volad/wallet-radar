# 26 — ZKsync Lend/Borrow/Repay Synthetic-Log Classification Fix

## T-058 — Restore LEND/BORROW/REPAY classification on synthetic logs with native pseudo-token legs

- **Module(s):** `ingestion/classifier`, `ingestion/job/classification`, `docs`
- **Roles:** backend-dev
- **Source:** `docs/reviews/2026-03-06-zksync-lend-borrow-repay-audit.md`

---

## Goal

Ensure ZKsync lending transactions (deposit/withdraw/borrow/repay) are classified into canonical lending types
when raw evidence is synthetic transfer logs and includes native pseudo-token legs
(`0x000000000000000000000000000000000000800a`).

---

## Problem Statement

Current normalized results on ZKsync show:

- `LEND_DEPOSIT/LEND_WITHDRAWAL/BORROW/REPAY = 0`
- deposit/withdraw selectors map to `SWAP`
- borrow/repay selectors map to `EXTERNAL_TRANSFER_OUT`

This breaks accounting semantics for lending flows.

---

## Product Decisions (Locked)

1. For networks with configured `synthetic-native-contracts`, these contracts must be ignored in lend disambiguation
   for **all** lend paths: deposit, withdrawal, borrow, repay.
2. Selector-driven lend context must continue to work even when `methodId` is `0x` and selector comes from calldata input.
3. `TransferClassifier` fallback must not override/replace valid lend semantics for these transactions.
4. Keep deterministic behavior and no double-counting.

---

## Worker Handoff Command

```text
$backend-dev implement docs/tasks/26-zksync-lend-borrow-repay-synthetic-log-classification-fix.md (T-058) end-to-end with tests
```

---

## Implementation Scope

1. Update `LendClassifier`:
   - apply synthetic-native filtering in `BORROW/REPAY` candidate selection and `isLikely*` heuristics.
   - keep existing deposit/withdraw filtering and verify parity.
2. Verify selector fallback path:
   - borrow/repay/deposit/withdraw selector detection from calldata input must remain stable with `methodId=0x`.
3. Ensure overlap policy:
   - for lend-like tx, transfer fallback must not produce effective type drift to `SWAP`/`EXTERNAL_*`.
4. Add regression fixtures/tests based on observed ZKsync patterns:
   - deposit + extra native pseudo-legs
   - withdraw + extra native pseudo-legs
   - borrow + extra native pseudo-legs
   - repay + extra native pseudo-legs

---

## Required Tests

1. **LendClassifier**
   - `classify_zkSyncBorrowWithExtraNativeLegs_emitsBorrow`
   - `classify_zkSyncRepayWithExtraNativeLegs_emitsRepay`
   - `classify_zkSyncDepositWithExtraNativeLegs_emitsLendDeposit` (regression)
   - `classify_zkSyncWithdrawWithExtraNativeLegs_emitsLendWithdrawal` (regression)
2. **Transfer/Lend overlap**
   - verify transfer fallback does not produce final non-lend semantics when lend should match.
3. **Determinism**
   - same fixture classified repeatedly produces identical event set/order.
4. **Accounting invariants**
   - borrow quantity positive on underlying asset.
   - repay quantity negative on underlying asset.
   - synthetic native contract is not chosen as underlying lend asset.

---

## Acceptance Criteria (DoD)

1. For ZKsync selector families:
   - `0x02c205f0` -> `LEND_DEPOSIT`
   - `0x69328dec` -> `LEND_WITHDRAWAL`
   - `0xa415bcad` -> `BORROW`
   - `0x573ade81` -> `REPAY`
2. No affected tx from audit report is normalized as `SWAP` or `EXTERNAL_TRANSFER_OUT` when lending semantics are present.
3. Existing non-lending swap/transfer regression tests remain green.
4. Tests pass under `./gradlew :backend:test`.
