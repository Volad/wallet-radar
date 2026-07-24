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

## Addendum — RC-6: STAKING_DEPOSIT of an LP receipt is a non-realizing wrap

**Status:** Accepted
**Date:** 2026-07-22
**Scope:** basis rule for the LP-receipt leg of an Equilibria/Penpie `STAKING_DEPOSIT` / `STAKING_WITHDRAW`

### Problem

The original decision (above) correctly reclassified `0x8dd9dbad` from `LP_EXIT (Pendle)` to `STAKING_DEPOSIT (Equilibria)` and kept the `pendle-lp:*` basis pool untouched at deposit time. However, the **PENDLE-LPT out-leg itself** was still booked as `role=SELL → basisEffect=DISPOSE`. Because a SELL leg is market-priced, the deposit realized a **phantom loss of −$23.178** (proceeds $3,357.566 vs. `avcoAtSale` $7,596.478) even though staking the receipt into the 1:1 Equilibria booster is a continuation of the same LP position, not a sale. The authoritative basis for the position lives in the `pendle-lp:*` receipt basis pool (deposited at LP entry, restored at the zap-out `LP_EXIT`), so realizing anything on the wrap double-counts P&L.

### Decision

The LP-receipt leg of a `STAKING_DEPOSIT` (and the symmetric `STAKING_WITHDRAW` / zap-out unwrap) is a **non-realizing wrap**:

- **Leg role.** `OnChainClassificationSupport.resolveRole` maps any LP-receipt leg (identity via the durable `lpReceipt` flag, the `FAMILY:LP_RECEIPT` identity, or the deterministic `-LPT`/`-LP` receipt symbol grammar) on a `STAKING_DEPOSIT`/`STAKING_WITHDRAW` to `role=TRANSFER` (never `SELL`/`BUY`). `TRANSFER` legs are excluded from market pricing by `PriceableFlowPolicy` (which prices `FEE`/`BUY`/`SELL` only), eliminating the phantom proceeds.
- **Basis effect.** `ReplayDispatcher.applyFlow` detects the wrap via `LpReceiptStakeWrapSupport.isNonRealizingLpReceiptStakeLeg` (type is `STAKING_DEPOSIT`/`STAKING_WITHDRAW`, leg is an LP receipt, and the transaction has an Equilibria/Penpie `PROTOCOL` counterparty **or** an LP correlation `pendle-lp:*`/`lp-position:*`) and books it via `applyLpReceiptStakeWrap`: outbound → `removeFromPosition` + `CARRY_OUT`; inbound → `applyUnknownTransfer` + `CARRY_IN`. Both set `realisedPnlUsd=null` and `avcoAtTimeOfSale=null`. The `pendle-lp:*` receipt basis pool is **not** touched, so the zap-out `LP_EXIT` REALLOCATE_IN path is unchanged.

### Negative cases (must NOT carry)

- An LP receipt sold into a DEX router/pool for an unrelated asset is classified `SWAP` (never `STAKING_*`) and remains a genuine sale.
- The plain `PENDLE` reward/governance token is not an `-LPT` receipt, so a claimed reward stays income, not basis.

### Flag propagation

Detection reads `type`, `protocolName`, `correlationId`, and the flow's `role`/`lpReceipt`/symbol. These are preserved through every copy-and-replace persistence cycle: `StatValidationService.copy()`, `PricingResultMapper`, and `IdempotentNormalizedTransactionStore` all carry `role`, `lpReceipt`, `correlationId`, `protocolName`, and `receiptBearingCollateral`.

### Consequences

- `0x8dd9` no longer realizes −$23.178; the position basis stays in the receipt pool.
- The `0xf7f8` `zapOutV3SingleToken` `LP_EXIT` continues to REALLOCATE_IN cmETH from the `pendle-lp:*` pool at LP cost — verified by regression test.
- Rule is protocol-generic (Equilibria/Penpie counterparty + LP-receipt identity); no wallet/tx hardcoding.

### Tests

- `ReplayDispatcherLpReceiptStakeWrapTest` — Equilibria `STAKING_DEPOSIT` of PENDLE-LPT → `CARRY_OUT`, realized $0, no `DISPOSE`.
- `StakeWrapLegRoleTest` — LP-receipt staking leg resolves to `TRANSFER` and is not priced; LP-receipt `SWAP` sale stays `SELL`.
- `LpReceiptStakeWrapSupportTest` — positive/negative detection grammar (Equilibria/Penpie, `pendle-lp:` correlation; excludes PENDLE reward, plain liquid-staking receipts, DEX sales, and the `LP_EXIT` path).
- `PositionScopedLpExitReplayHandlerTest.pendleZapOutLpExitStillReallocatesUnderlyingFromReceiptPool` — regression guard that the zap-out still REALLOCATE_INs the underlying from the pool.

---

## References

- Implementation plan: `docs/tasks/equilibria-lp-entry-classification-fix-plan.md`
- Authoritative RC-6 spec: `results/lp-phantom-open-required-changes.md` (RC-6), `results/lp-phantom-open-protocol-rule-pack.md` (Family A rules 11–13)
- Related: ADR-046 (INIT Capital borrow classification)
- Transactions: `0x8dd9dbad` (deposit), `0xf7f8908b` (exit), `0xfaf8160c` (Pendle LP entry)
