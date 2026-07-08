# ADR-047: Equilibria Staking Deposit and Pendle LP Corridor Threading

**Status:** Accepted  
**Date:** 2026-07-07  
**Theme:** On-chain classification / LP receipt basis corridor

---

## Context

Equilibria Finance is a Pendle yield optimizer that wraps Pendle LP tokens (PENDLE-LPT) into its own staking receipt (eqbPENDLE-LPT). The user's workflow is:

1. **Pendle LP_ENTRY** — contribute cmETH → receive PENDLE-LPT (LP receipt stored in `lp_receipt_basis_pools`, key `pendle-lp:mantle:pendle-lpt`)
2. **Equilibria deposit** — transfer PENDLE-LPT to Equilibria Booster (`0x920873e5b302...`, `deposit(uint256 _pid, uint256 _amount, bool _stake)`) — eqbPENDLE-LPT is minted internally (no ERC-20 transfer event)
3. **Equilibria combined exit** (`zapOutV3SingleToken`) — burn eqbPENDLE-LPT, redeem underlying PENDLE-LPT from Pendle, receive cmETH + PENDLE rewards in one transaction

### Problems

**BB-EQB-2:** The Equilibria deposit (`0x8dd9dbad`) was misclassified as `LP_EXIT (Pendle, HEURISTIC)` because:
- `PendleProtocolSemanticClassifier` sees PENDLE-LPT outflow → produces LP_EXIT hint
- `LpSemanticClassifier` (PRE_PROTOCOL_REVIEW order 151) converts hint to LP_EXIT decision before `FunctionNameClassifier` (FINAL_FALLBACK) can classify as `deposit()`
- Equilibria Depositor `0x920873e5b302...` was absent from the protocol registry
- Result: `lp_receipt_basis_pools` entry is corrupted; Equilibria LP EXIT receives cmETH at market price instead of LP cost basis

**BB-EQB-3:** The Equilibria combined exit (`0xf7f8908b`) had `correlationId=undefined` because:
- eqbPENDLE-LPT (the LP receipt) self-cancels in flows (+0.445041 / −0.445041)
- No PENDLE-LPT movement → no `pendle-lp:` correlation key assigned
- `LpReceiptExitReplayHandler.isLpReceiptExit()` only accepted `lp-position:` prefix, not `pendle-lp:`
- Result: LP receipt exit handler never fires; cmETH acquired at market price ($4,382) instead of LP cost (~$2,155), causing a +$2,228/cmETH net AVCO spike

---

## Decision

### Classification

1. Add `0x920873e5b302a619c54c908adfb77a1c4256a3b8` (Equilibria Depositor, MANTLE) to `protocol-registry.json` with `family=YIELD, role=ROUTER, eventType=STAKING_DEPOSIT`.

2. Update `EquilibriaProtocolSemanticClassifier` to also detect `deposit(uint256 _pid, uint256 _amount, bool _stake)` calls (methodId `0x43a0d066`) on registered Equilibria Depositor contracts → produce `STAKING_DEPOSIT` hint.

3. Add `StakingSemanticClassifier` (PRE_PROTOCOL_REVIEW, order 148) to convert `STAKING_DEPOSIT` protocol-semantic hints into classification decisions, firing **before** `LpSemanticClassifier` (order 151). This prevents Pendle's LP_EXIT hint from winning for Equilibria staking deposits.

4. Assign `correlationId=pendle-lp:mantle:pendle-lpt` to the Equilibria combined exit (`zapOutV3SingleToken`) when cmETH inflow is detected from the Pendle cmETH market (`0x2ab88ac7458faec2e952bb79cc1be6577bf63e70`).

5. Update `LpReceiptExitReplayHandler.isLpReceiptExit()` to also accept the `pendle-lp:` correlation prefix (mirroring the existing entry handler):
   ```java
   private static final String PENDLE_LP_CORR_PREFIX = "pendle-lp:";
   // ...
   if (!corrId.startsWith(LP_CORR_PREFIX) && !corrId.startsWith(PENDLE_LP_CORR_PREFIX)) {
       return false;
   }
   ```

### Replay

- STAKING_DEPOSIT is processed by `TransferReplayHandler`, **not** `LpReceiptExitReplayHandler`. The `lp_receipt_basis_pools` MongoDB collection (keyed by `lpCorrelationId`) is only drained by `LpReceiptExitReplayHandler.restoreInboundFromPool()`. STAKING_DEPOSIT leaves the cmETH basis pool untouched.
- The Equilibria combined exit, once assigned `correlationId=pendle-lp:mantle:pendle-lpt`, will be handled by `LpReceiptExitReplayHandler` which drains the Pendle LP basis pool → cmETH REALLOCATE_IN at LP cost basis; PENDLE REALLOCATE_IN as zero-cost reward.

---

## Consequences

- cmETH net AVCO after Equilibria staking cycle reflects LP cost basis (~$2,155), not market price ($4,382)
- Equilibria LP deposit visible in UI as `STAKING_DEPOSIT (Equilibria)` instead of `LP_EXIT (Pendle)`
- Equilibria LP exit correctly shows LP basis carry (REALLOCATE_IN) for cmETH and zero-cost reward for PENDLE
- `LpReceiptExitReplayHandler` now accepts both `lp-position:` and `pendle-lp:` correlation prefixes — all existing Pendle LP operations continue to work unchanged

---

## References

- Implementation plan: `docs/tasks/equilibria-lp-entry-classification-fix-plan.md`
- Related: ADR-046 (INIT Capital borrow classification)
- Transactions: `0x8dd9dbad` (deposit), `0xf7f8908b` (exit), `0xfaf8160c` (Pendle LP entry)
