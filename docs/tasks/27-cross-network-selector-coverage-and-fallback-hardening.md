# 27 — Cross-Network Selector Coverage and Fallback Hardening

## T-059 — Extend classifier selector coverage for lending, bridge, swap fallback, and approval paths

- **Module(s):** `ingestion/classifier`, `ingestion/job/classification`, `docs`
- **Roles:** backend-dev
- **Source:** `docs/reviews/2026-03-06-cross-network-selector-coverage-audit.md`
- **Architecture:** `docs/adr/ADR-028-cross-network-selector-coverage-and-conservative-fallbacks.md`

---

## Goal

Reduce cross-network classifier blind spots by expanding conservative selector coverage while preserving deterministic,
flow-driven accounting semantics.

---

## Problem Statement

Current implementation has selector coverage gaps outside ZKsync-specific fixes, leading to avoidable fallback drift:

- lending variants can degrade to `EXTERNAL_*` / `SWAP`,
- relay bridge calls can miss bridge path,
- swap fallback can miss aggregator signatures without function name,
- permission-only calls can leak into review noise.

---

## Product Decisions (Locked)

1. Selector additions are additive and conservative; no accounting model changes.
2. Flow evidence remains authoritative for final economic event in lending/swap paths.
3. Dispatcher overlap priority remains unchanged.
4. No new event types in this task.

---

## Worker Handoff Command

```text
$backend-dev implement docs/tasks/27-cross-network-selector-coverage-and-fallback-hardening.md (T-059) end-to-end with tests
```

---

## Implementation Scope

1. `LendClassifier`
   - Extend selector coverage:
     - repay variants: `0x2dad97d4`, `0xee3e210b`, `0x02c5fcf8`
     - borrow variants: `0xe74f7b85`
     - deposit/withdraw gateway variants: `0x474cf53d`, `0x80500d20`
     - multicall variants: `0x5ae401dc`, `0x1f0464d1`
   - Keep existing synthetic-native filtering and pair resolution logic.
2. `BridgeCallClassifier`
   - Extend bridge selector hints with:
     - `0x84d61c97`, `0xd7a08473`, `0xcfc32570`, `0xdeff4b24`, `0xe2de2a03`
   - Keep ERC20 transfer-log suppression behavior.
3. `TransferClassifier`
   - Extend `isLikelySwapCall(...)` with selector hints:
     - `0x07ed2379`, `0x90411a32`, `0x73fc4457`
4. `ApprovalClassifier`
   - Extend known approval selectors with:
     - `0x5a3b74b9` (`setUserUseReserveAsCollateral`)
     - `0xf3995c67`, `0xc2e3140a` (self-permit variants)
     - `0x30f28b7a`, `0x137c29fe` (Permit2 permit-transfer variants)

---

## Required Tests

1. **LendClassifier**
   - Selector-variant regressions for `deposit/withdraw/borrow/repay` families listed above.
   - Determinism test for repeated classification on selector-variant fixture.
   - Sign invariant assertions: `BORROW > 0`, `REPAY < 0`.
2. **BridgeCallClassifier**
   - Relay selector regression tests for value-carrying no-log calls.
   - Ensure ERC20 transfer-log suppression still prevents bridge no-log path.
3. **TransferClassifier**
   - Selector-based swap-call fallback tests (`0x07ed2379`, `0x90411a32`, `0x73fc4457`).
4. **ApprovalClassifier**
   - New tests for selector recognition with zero-value/no-transfer.
   - Ensure calls with transfer effects are not classified as approval.
5. **Dispatcher conflict**
   - `LEND_*` precedence over `EXTERNAL_*` for same movement key.

---

## Acceptance Criteria (DoD)

1. Added selector families are covered in corresponding classifier contexts with no regressions in existing tests.
2. Lend semantics do not degrade to transfer/swap for flow-valid selector-variant transactions.
3. Bridge relay selector cases classify through bridge path only when conservative guards are satisfied.
4. Permission-only selector-variant calls classify as `APPROVAL` only if no economic transfer effects are present.
5. Targeted test suite passes:
   - `LendClassifierTest`
   - `BridgeCallClassifierTest`
   - `TransferClassifierTest`
   - `ApprovalClassifierTest`
   - `TxClassifierDispatcherTest` (conflict scenario)

