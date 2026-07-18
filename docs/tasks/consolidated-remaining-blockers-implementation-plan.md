# Consolidated Remaining Blockers — Implementation Plan

**Date:** 2026-07-14  
**Status:** Phase 3 CONDITIONAL APPROVED (all reviewers) — amendments applied 2026-07-14; ready for Wave 1 implementation  
**Scope:** All open financial-correctness blockers after BLOCKER-6 resolution  
**Source of truth:** `results/blockers.md` (full evidence and root-cause analysis)

---

## Open Blockers Summary


| ID         | Stage                        | Severity   | Reset  | New Mechanism?               |
| ---------- | ---------------------------- | ---------- | ------ | ---------------------------- |
| BLOCKER-5A | replay ordering              | LOW-MEDIUM | Wave 1 | No                           |
| BLOCKER-5B | replay flag lifecycle        | LOW        | Wave 1 | No                           |
| BLOCKER-5C | replay REWARD_CLAIM stamp    | LOW        | Wave 1 | No                           |
| BLOCKER-5F | replay shortfall propagation | LOW        | Wave 1 | No (reuses 5B)               |
| BLOCKER-7  | replay Net-lane carry        | LOW-MEDIUM | Wave 2 | Minimal                      |
| BLOCKER-3  | classification Balancer V3   | MEDIUM     | Wave 3 | Yes (new protocol handler)   |
| BLOCKER-2  | clarification LP fee split   | MEDIUM     | Wave 4 | No (existing decomposer)     |
| BLOCKER-5D | linking TON Earn phantom     | MEDIUM     | Wave 5 | Yes (new continuity pairing) |


Waves are independent `--skip-frontend` resets. Implement and verify in order (Wave 1 first) because 5B fix is prerequisite to 5F verification, and 5A fix removes ordering noise that would mask 5B counting accuracy.

---

## Wave 1 — BLOCKER-5A + 5B + 5C + 5F: Replay flag correctness

**Reset type:** `--skip-frontend`  
**ETH impact:** No (non-ETH assets only)  
**New ADR:** No (operational fix, existing ADR-031 covers flag lifecycle)

### Scope

Wallets/assets affected:

- USDC on `0x1a87…` and `BYBIT:33625378` (5A ordering artifact)
- ARB on `BYBIT:33625378` (5B stuck value + 5C REWARD_CLAIM stamp = ~60 flagged pts, 22.01 stuck shortfall value)
- USDT on `BYBIT:33625378` (5B, ~56 flagged pts)
- MNT on `BYBIT:33625378` (5B, ~9 flagged pts)
- ETH on `0x68bc…` via ARBITRUM (5F stuck-value propagation, 503 points, shortfall 0.852)

### Root causes (summary — full detail in `results/blockers.md`)

**5A — Same-second ordering artifact:**  
`ConfirmedReplayQueryService.REPLAY_ORDER` applies a `corridorContinuityFlowSign` tiebreaker that sorts `BYBIT-CORRIDOR` outbound flows before inbound at the same second. This is correct for corridor CARRY_OUT → CARRY_IN pairs within a single collapsed transfer, but wrong when an external corridor DEPOSIT (CARRY_IN) at the same timestamp funds a subsequent internal CARRY_OUT drain. The drain executes before its funding arrives → transient shortfall never clears.

**5B — Stuck shortfall VALUE:**  
`clearResolvedPositionFlags` (called when `quantityShortfallAfter > 0 && coveredQty > 0` after a recovery) clears the flag bits (`hasIncompleteHistoryAfter`, `hasUnresolvedFlagsAfter`) but **never decrements `quantityShortfallAfter` value**. Result: the flag clears (correct) but `quantityShortfallAfter=22.0088` persists indefinitely on all downstream ARB points. This is the primary reason 503 ETH points on `0x68bc` are flagged — a 0.852 ETH shortfall introduced at seq 1719 propagates as stuck value through every subsequent ETH point.

**5C — ARB REWARD_CLAIM zero-delta stamp:**  
A `REWARD_CLAIM` at seq 8661 executes against a 0-balance BYBIT umbrella ARB position (`qBefore=0`). The engine books `quantityShortfallDelta = 22.0088` at that point, matching the prior stake-to-EARN that removed inventory from the umbrella key. The EARN sub-account holds the 58.44 ARB correctly and is unflagged. This is a cross-key accounting boundary defect — the REWARD_CLAIM should be keyed to or aware of the EARN balance, or gated to prevent shortfall stamping when the cross-key balance is conserved.

**5F — ETH `0x68bc` 503 points:**  
Not dust. Root is 5B: an ARBITRUM `EXTERNAL_TRANSFER_OUT` of 0.852 ETH at seq 1719 (2025-04-18) against a 0-balance ARBITRUM ETH position stamps a shortfall that propagates to all 503 downstream ETH ledger points on that wallet via stuck-value. The 5B fix (decrement stuck value on attributable recovery) will progressively clear these. If the 0-balance disposal is due to missing inbound ETH history on ARBITRUM, a residual shortfall may remain — investigate during acceptance.

### Ordered changes

**Change 1 — 5A: Ordering tiebreaker inversion (`ConfirmedReplayQueryService`)**  
In `corridorContinuityFlowSign`: scope the outbound-first ordering to `isBybitCollapsed` transactions only (internal FUND↔UTA collapses, `bybit-collapsed-v1`). For `isOnChainCorridor` (BYBIT-CORRIDOR deposits), **invert the sign** so CARRY_IN returns `-1` (sorted first) and CARRY_OUT returns `+1`. Do NOT simply remove the corridor branch — without inversion, `transactionIndex` ordering may still rank the drain before the deposit if its index is lower. Must not regress the BLOCKER-4 fix (collapsed-self-transfer continuity test `ReplayDispatcherBybitCollapsedSelfTransferTest`).

**Change 2 — 5B: Stuck shortfall value zero (`clearResolvedPositionFlags` in `GenericFlowReplayEngine`)**  
Add a single line inside `clearResolvedPositionFlags` after the existing flag-clear calls:

```java
position.setQuantityShortfall(BigDecimal.ZERO);
```

The existing guard (`quantity > 0 && uncoveredQty == 0`) is already the correct and sufficient gate for "attributable recovery." No additional origin-type tracking is needed or correct (no field exists to track shortfall origin; the flag-state gate already ensures genuine gaps with `uncoveredQty > 0` never reach this path).

**Change 3 — 5C: DROPPED (resolved by 5B)**  
DB verification confirmed: `quantityShortfallDelta = 0` at the problematic REWARD_CLAIM event (seq 8671). The 22.009 ARB shortfall is a **carry-forward** of the stuck PositionState value, not a fresh stamp. Once Change 2 zeros `PositionState.quantityShortfall` at the recovery point (seq 8663), the REWARD_CLAIM at seq 8671 inherits 0 shortfall. No separate REWARD_CLAIM guard needed.

**Note on 5F (ETH `0x68bc` 503 points)**  
DB verification confirmed: the ETH shortfall on `0x68bc` ARBITRUM started at seq 900 (first disposal from 0-balance). The position has **never had a positive balance** — there are no attributable recovery events. The 503 flagged points represent **genuine missing inbound ETH history on ARBITRUM**, not stuck-value. Change 2 (5B) will NOT close these. They will remain flagged until the missing ARBITRUM ETH history is supplied. Tracked as a separate data-gap note; NOT a Wave 1 acceptance criterion.

### Tests

- Unit: `ConfirmedReplayQueryService` — CARRY_IN ordered before CARRY_OUT for BYBIT-CORRIDOR at same timestamp; `bybit-collapsed-v1` still outbound-first (5A)
- Unit: `clearResolvedPositionFlags` — `quantityShortfall` is zero after flag-clear recovery (5B)
- Integration: `ReplayDispatcherBybitCollapsedSelfTransferTest` — no regression (5A fix must pass)

### Acceptance (post `--skip-frontend` reset)

- USDC flagged points on `BYBIT:33625378` from seq 1099 (shortfall ~10.88) and seq 1891 (shortfall ~901.96): cleared (5A)
- ARB flagged count: 60 → 0; `quantityShortfallAfter` value on all previously-stuck ARB points → 0 (5B resolves 5C as downstream symptom)
- USDT flagged from seq 1: 56 → 0 or minimal residual for USDT events with no attributable recovery (5B)
- MNT flagged: 9 → 0 or minimal (5B)
- ETH `0x68bc` flagged 503 points: **NOT expected to clear** — genuine missing ARBITRUM ETH inbound history (separate data-gap, do not regress); count unchanged is acceptable
- Total `hasIncompleteHistoryAfter` on BYBIT: baseline − (ARB 60 + USDC ~2 + USDT ~56 + MNT ~9); no new flags on other assets
- No regression: CMETH, MNT BYBIT-CORRIDOR, TON counts unchanged

### Risks

- 5A tiebreaker inversion: run full DB regression count of `hasIncompleteHistoryAfter` on BYBIT before/after as a mandatory pre-merge gate (not just a risk note).
- 5B change is minimal (one line) but touches a hot path; verify with integration test that genuine gaps (USDT EXTERNAL_TRANSFER_OUT with no recovery) remain flagged.

---

## Wave 2 — BLOCKER-7: sAVAX Net AVCO — cross-canonical Net-basis carry

**Reset type:** `--skip-frontend`  
**ETH impact:** No  
**New ADR:** Amendment to ADR-040 (Net-lane carry for cross-canonical staking) OR inline in ADR-054 §2

### Scope

Asset: `SAVAX` (and any future C1→C2 `STAKING_DEPOSIT` / `VAULT_DEPOSIT` pair)  
Wallet: `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f` (AVALANCHE)  
Transactions: seq 8790 (2025-12-12 stake), seq 9118 (2026-01-05 stake)

### Root cause

`ReplayDispatcher.replayGenericFlowsSkipping` initializes `swapNetRef` (the net-basis carry accumulator, ADR-040 Bug B) **only for `NormalizedTransactionType.SWAP`**:

```java
BigDecimal[] swapNetRef = transaction.getType() == NormalizedTransactionType.SWAP
    ? new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO}
    : null;
```

For `STAKING_DEPOSIT` (AVAX→sAVAX), `swapNetRef = null` → `applyBuyWithOptionalPool` receives no net-carry context → sAVAX BUY always books market price in **both** Market and Net lanes. The Net lane should instead receive the net basis released by the AVAX SELL leg.

Compounding: flow ordering within the tx is inbound-first (sAVAX ACQUIRE at flow index 0, AVAX SELL at flow index 1), so even with a fixed `swapNetRef`, the SELL must be processed before the BUY to populate the accumulator.

**Correct values (DB-reconstructed):**

- Stake 1: sAVAX net AVCO should be **$10.59** (not $13.09, delta $2.50/token)
- Stake 2 (cumulative): sAVAX net AVCO should be **$9.08** (not $13.315, delta **$4.24**/token, ~$10.25 total overstatement)

### Ordered changes

**Change 1 — Cross-canonical net-carry accumulator**  
In `replayGenericFlowsSkipping` (`ReplayDispatcher`), extend the `swapNetRef` initialization condition using the **existing** `AccountingAssetClassificationSupport.hasCrossCanonicalIdentityPrincipalPair(transaction)` (do NOT create a new function):

```java
boolean isCrossCanonicalStaking = (type == STAKING_DEPOSIT || type == STAKING_WITHDRAW
    || type == VAULT_DEPOSIT || type == VAULT_WITHDRAW)
    && AccountingAssetClassificationSupport.hasCrossCanonicalIdentityPrincipalPair(transaction);
BigDecimal[] swapNetRef = (type == SWAP || isCrossCanonicalStaking)
    ? new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO}
    : null;
```

**Change 1a — Peg-floor cap on inherited Net basis (critical ADR-040 invariant)**  
In `applyBuyWithOptionalPool`, after reading `swapNetRef[0]` as `netBasisReleased`, apply a floor cap:

```java
BigDecimal effectiveNetBasis = netBasisReleased.min(marketBasis); // Net AVCO ≤ Market AVCO invariant
```

This protects against future scenarios where AVAX net AVCO exceeds sAVAX market price at an unfavorable exchange ratio, which would violate ADR-040's global `Net ≤ Market` constraint.

**Change 2 — Outbound-before-inbound ordering for cross-canonical txs**  
Extend `compositeAwareFlowOrder` in `ReplayDispatcher` by adding a branch for cross-canonical staking (reuse existing outbound/inbound classifier logic, guarded by `isCrossCanonicalStaking` condition). Do NOT use "or per-tx flow sort" — commit to `compositeAwareFlowOrder` specifically to reuse existing infrastructure.  
Guard: only fire when `isCrossCanonicalStaking = true`; standard same-C1 STAKING_DEPOSIT (ETH→WETH) must not be reordered.

**Change 3 — ADR amendment**  
Add a paragraph to ADR-054 §2 documenting the Net-lane carry rule for C1→C2 conversions: "The Net lane for the C2 acquisition inherits the net basis released by the C1 disposal (capped at the C2 market price — `min(inherited, market)` — to preserve `Net ≤ Market` globally). Only the Market lane receives fresh market price."

### Tests

- Unit: `ReplayDispatcherCrossCanonicalStakingTest` — add Net-lane assertions: AVAX net AVCO $8.604 staked to sAVAX → sAVAX `netAvcoAfterUsd ≈ $10.59`; Market lane unchanged at $13.09.
- Unit: peg-floor cap — when AVAX net AVCO > sAVAX market price → sAVAX net AVCO = sAVAX market price (not above).
- Unit: flow ordering — outbound SELL processed before inbound BUY for cross-canonical STAKING_DEPOSIT.
- Regression: same-C1 STAKING_DEPOSIT (ETH→WETH) — basis carry unchanged, `swapNetRef` NOT initialized for these.

### Acceptance (post reset)

- `FAMILY:SAVAX` seq 8790: `netTotalCostBasisAfterUsd ≈ 21.355` (±$0.10) (was 26.397)
- `FAMILY:SAVAX` seq 8790: `netAvcoAfterUsd ≈ 10.59` (±$0.01) (was 13.09)
- `FAMILY:SAVAX` seq 9118 (cumulative): `netAvcoAfterUsd ≈ 9.08` (±$0.01) (was 13.315)
- `avcoAfterUsd` (Market lane) unchanged at 13.09/13.315 ✓
- Regression: same-C1 STAKING_DEPOSIT (ETH→WETH or equivalent) — basis carry unchanged, no P&L, `netAvcoAfterUsd` not affected by swap-net-ref path

### Risks

- Flow reordering for cross-canonical txs must not affect same-asset C1 REALLOCATE path (guarded by `isCrossCanonicalStaking` condition).
- `hasCrossCanonicalPairInFlows` must be cheap (lookup, no DB call) — use the existing `AccountingAssetClassificationSupport` in-memory registry.

---

## Wave 3 — BLOCKER-3: Balancer V3 Avalanche misclassification

**Reset type:** `--skip-frontend`  
**ETH impact:** No (stablecoin only: USDC/GHO/USDt)  
**New ADR:** No (protocol-registry pattern, existing ADR-013/018 cover LP accounting)

### Scope

5 transactions on AVALANCHE, `0xf03b52e8686b962e051a6075a06b96cb8a663021`:

- `0x983f7940…` — misclassified LP_EXIT, should be **LP_ENTRY** (~$2,150 stablecoin basis)
- `0x3993a8d5…` — misclassified LP_EXIT, should be **LP_POSITION_UNSTAKE**
- `0xe84d0c43…` — correct LP_EXIT, **missing correlationId**
- `0xdf5ca234…` — correct LP_EXIT, **missing correlationId**
- `0x13d0771…` — correct LP_POSITION_STAKE ✓ (gauge already registered)

Net accounting error: **~$4,300** stablecoin basis double-counted.

### Root cause

Balancer V3 vault/router `0xba1333333333a1ba1108e8412f11850a5c319ba9` (Avalanche) is not registered in `protocol-registry.json`. The gauge contract IS registered (causing LP_POSITION_STAKE to classify correctly). LP_ENTRY/LP_EXIT interactions via the vault fall through to HEURISTIC and misclassify.

### Ordered changes

**Change 1 — Register Balancer V3 vault in protocol registry**  
Add to `protocol-registry.json` for AVALANCHE:

```json
{
  "address": "0xba1333333333a1ba1108e8412f11850a5c319ba9",
  "protocol": "BALANCER_V3",
  "specialHandler": "BALANCER_V3"
}
```

**Change 2 — Implement BALANCER_V3 handler in `OnChainClassifier`**  
Detection rule — use **BPT sign as the primary axis** (not individual stablecoin directions, which can have positive change amounts alongside negative outflows):

- BPT `quantityDelta > 0` → `LP_ENTRY` (stablecoins deposited, BPT received)
- BPT `quantityDelta < 0` AND stablecoins positive → `LP_EXIT`
- Gauge token `quantityDelta < 0` AND BPT `quantityDelta > 0` → `LP_POSITION_UNSTAKE`
- Gauge token `quantityDelta > 0` AND BPT `quantityDelta < 0` → `LP_POSITION_STAKE`

Vault address must be **chain-qualified**: only trigger `BALANCER_V3` handler when `network = AVALANCHE`. If the same vault address appears on other chains, this handler must not fire for them.

**Change 3 — Assign correlationId to all 5 transactions**  
`correlationId = "lp-position:avalanche:balancerv3:0xfcec3c8d86329defb548202fe1b86ff2188603a8"` (the pool BPT address, consistent with the LP-receipt-pool keying convention).

### Tests

- Unit: BALANCER_V3 handler — BPT-in + stablecoins-out → LP_ENTRY; BPT-out + stablecoins-in → LP_EXIT; gauge-out + BPT-in → LP_POSITION_UNSTAKE
- Integration: correlation assigned to the 4 not-yet-correlated txs

### Acceptance (post reset)

- `0x983f7940…`: type = LP_ENTRY, correlationId = `lp-position:avalanche:balancerv3:0xfcec3c8d…` ✓
- `0x3993a8d5…`: type = LP_POSITION_UNSTAKE, correlationId set ✓
- `0xe84d0c43…`, `0xdf5ca234…`: correlationId set ✓
- `0x13d0771…`: correlationId confirmed set (if not already present, add in this wave)
- LP receipt pool basis for BPT `0xfcec3c8d…`: **$0** after all LP_EXIT events drain it (not "correctly flows")
- Gauge LP receipt pool (`0x8e8c3d…`): **$0** after UNSTAKE fully processes
- Stablecoin basis error of ~$4,300 eliminated

### Risks

- The BPT-detection heuristic must not collide with other Balancer pool interactions. Scope the `BALANCER_V3` handler to registered vault addresses only.

---

## Wave 4 — BLOCKER-2: Native ETH LP fee split

**Reset type:** `--skip-frontend`  
**ETH impact:** YES — $9.58 ETH basis overstatement per affected exit  
**New ADR:** No (existing LP-exit fee-decomposition policy)

### Scope

LP positions where pool token is WETH but actual exit receives native ETH (via `unwrapWETH9`). Confirmed: Pool 477096 (BASE, Uniswap V3 ETH/USDC). Likely systemic for any ETH/X Uniswap V3 position on any EVM chain.

### Root cause

`LpExitFeeDecomposer` matches the ERC-20 `Collect` log (which shows WETH) to outbound flow (which shows native ETH). When pool token = WETH but outbound = native ETH, the match fails → ETH receives the full LP receipt basis instead of principal-only. USDC works because it's a standard ERC-20 with no wrapping step.

### Ordered changes

**Change 1 — WETH→native-ETH mapping in `LpExitFeeDecomposer`**  
When the LP receipt pool's registered token is WETH (determined from **LP receipt pool registry metadata**, not from log inference) AND the outbound flow asset is native ETH, map `Collect(WETH, qty) ↔ ETH transfer(qty)` for fee decomposition. The fee portion (difference between Collect qty and principal) remains zero-cost in both lanes. Fallback: if `|WETH Collect qty - native ETH transfer qty| ≤ 0.0001 ETH` (dust tolerance), apply the mapping even without explicit `unwrapWETH9` detection.

### Tests

- Unit: `LpExitFeeDecomposer` — WETH-pool with native ETH exit → correct principal/fee split; USDC component unchanged
- Integration: Pool 477096 exit — ETH fee component gets zero-cost basis

### Acceptance (post reset)

- Pool 477096 ETH exit: `LP_FEE_INCOME` portion = 0.002849993 ETH with zero-cost basis ✓
- ETH `REALLOCATE_IN` basis ≈ $2,083 (not $2,093)
- No regression: WETH-exit LP positions unaffected

### Risks

- Must only apply when the LP pool's token is WETH AND the outbound is native ETH; do not treat all WETH as native ETH.
- If `unwrapWETH9` is not always the unwrapping mechanism, add a fallback: if ETH transfer qty ≈ WETH Collect qty (within tolerance), apply the mapping.

---

## Wave 5 — BLOCKER-5D: TON On-chain Earn same-asset FUND self-round-trip

**Reset type:** `--skip-frontend`  
**ETH impact:** No  
**New ADR:** New — `ADR-056-onchain-earn-fund-round-trip-continuity.md`  
**Risk:** HIGH — new accounting/continuity mechanism. Do not co-land with other waves.

### Scope

Asset: TON on `BYBIT:33625378:FUND`  
Symptoms: `uncoveredQuantityAfter = 40.55`, 133 flagged ledger points  
Root: Bybit `bybitType="Earn"` `On-chain Earn subscription` / `On-chain Earn redemption` emit two `:FUND` legs (subscribe-out and redeem-in) for the same asset, with no `EARN_FLEXIBLE_SAVING` `:EARN` counterpart. The existing `BybitEarnPrincipalTransferPairer` requires one `:EARN` + one non-earn leg → structurally cannot pair two `:FUND` legs → redeem-inbound replays as an uncovered UNKNOWN inflow.

### Root cause (confirmed DB evidence)

`bybit_extracted_events` for TON confirms:

- `9e5280fe…` (seq 1122): `On-chain Earn redemption +32.393` on `BYBIT:33625378:FUND`, `canonicalType=INTERNAL_TRANSFER`
- `d371339984…`: `On-chain Earn subscription −32.393` ALSO on `BYBIT:33625378:FUND`
- No `EARN_FLEXIBLE_SAVING` stream counterpart for these events (confirmed absent)

### Ordered changes

**Change 0 — Normalization path fix for non-clustered On-chain Earn assets (`BybitNormalizationService` / `normalizeLiquidStakingRow`)**  
**[ARCHITECT CRITICAL — prerequisite for Changes 1–3]**  
Currently, `isLiquidStakingRow()` catches On-chain Earn subscription events for TON (since `bybitDescription` contains "Earn") → routes to `normalizeLiquidStakingRow` → "pair not found" → `buildNeedsReviewRow(BYBIT_LIQUID_STAKING_PAIR_NOT_FOUND)`. Status=NEEDS_REVIEW means: not replayed, not visible to the new pairer.  
Fix: in `normalizeLiquidStakingRow`, after the "pair not found" branch, check whether `normalizationClusterForSymbol(row.assetSymbol()) == null` (non-clustered asset like TON). If so, **fall through to `buildMappedRow`** (CONFIRMED INTERNAL_TRANSFER) instead of NEEDS_REVIEW.  
Guard: ETH-family assets (`METH`, `CMETH`, `BBSOL`) have a non-null cluster → still route to NEEDS_REVIEW when counter-leg absent → handled by `BybitOnChainEarnOrphanRepairService`. This guard must not regress that path. Also verify `BybitOnChainEarnOrphanRepairService` does NOT attempt EARN synthesis for non-clustered assets (would create spurious EARN credit for TON).

**Change 1 — New ADR-056**  
Document: Bybit `On-chain Earn` is a same-asset FUND→(off-chain)→FUND round-trip (principal preserved). Unlike `Flexible Savings` (which emits a matching `EARN_FLEXIBLE_SAVING` stream on `:EARN`), `On-chain Earn` emits only `:FUND` legs. The subscribe-out basis must carry into the redeem-in with no P&L. Partial redemption is **out of scope** (Bybit On-chain Earn products do not support partial redemption per product evidence; confirmed subscribe qty = redeem qty in all observed events). The 400-day hold window is justified by the maximum published On-chain Earn lock period. Known limitation: redemptions >400 days after subscription are unmatched (documented as accepted risk).

**Change 2 — Same-asset FUND self-round-trip pairer (new `BybitOnChainEarnFundPairer`)**  
Key subscribe-out ↔ redeem-in on `{uid, asset, |qty|}` (FIFO, hold window ≤400 days, temporal ordering: subscribe-out timestamp < redeem-in timestamp). When a matching pair is found, mark the redeem-in as `continuityId=<subscribe-out id>` (carry basis, no uncovered quantity). Invoked AFTER `BybitEarnPrincipalTransferPairer` in `BybitNormalizationService.processNextBatch` (step 3+). Confirm `bybitType="On-chain Earn"` is distinct from `bybitType="Flexible Savings"` in extracted events before finalizing the pairer filter.

Guards (MUST NOT regress):

1. Both legs must be on `:FUND` (not one `:EARN`).
2. Same asset symbol (exclude cross-asset conversions).
3. Exclude ETH-family liquid-staking path (CMETH/METH/BBSOL On-chain Earn land inbound on `:EARN` as a distinct receipt asset — different walletRef — so the guard is naturally satisfied by rule 1).
4. Must not interfere with `EARN_FLEXIBLE_SAVING` pairing (different `bybitType`: those have `:EARN` mirror, these don't).
5. Must not break `BybitEarnSubPoolConservationGuard`.

**Change 3 — Update `BybitNormalizationService`**  
Call the new pairer after existing earn-principal pairing step.

**Change 4 — Unit tests**  

- Pair matched: subscribe-out + redeem-in same asset/qty → redeem-in gets carry basis, no uncoveredQty
- Non-pair (different qty): not paired
- ETH C2 path: CMETH subscribe-out (`:FUND`) + CMETH redeem-in (`:EARN`) → NOT paired by this pairer (rule 1 blocks it)
- No regression: `EARN_FLEXIBLE_SAVING` pairs unchanged

### Acceptance (post reset)

- TON `BYBIT:33625378`: the two confirmed On-chain Earn pairs (~32.393 + ~32.439 TON) are matched; `uncoveredQuantityAfter` attributable to these pairs → 0 (expected total reduction: ~64.83 TON; residual `unresolvedPrice=13` pricing gaps tracked separately)
- TON flagged points: 133 → ≤70 (residual = points with no matchable subscribe-out, e.g. seq-970 cross-uid cluster)
- No new flags on CMETH/METH/ETH family assets (Change 0 guard passes)
- `BybitEarnSubPoolConservationGuard`: still passes
- Status=NEEDS_REVIEW count for TON On-chain Earn events: 0 (Change 0 converts them to CONFIRMED INTERNAL_TRANSFER)

### Risks

- Matching on `{uid, asset, |qty|}` may over-pair if two subscribe/redeem events have identical quantities by coincidence. Mitigate: also require temporal ordering (subscribe-out < redeem-in) and the hold-window upper bound.
- New pairer must be invoked in the right pipeline position (after collapsed-transfer pairing, before normalization stat job).

---

## Dependency Graph

```
Wave 1 (5A+5B+5C+5F)  →  independent, start now
Wave 2 (BLOCKER-7)     →  independent of Wave 1 (different stage/asset)
Wave 3 (BLOCKER-3)     →  independent
Wave 4 (BLOCKER-2)     →  independent
Wave 5 (BLOCKER-5D)    →  independent; highest risk, ship last
```

Waves 1–4 can be planned and implemented in parallel (different PRs, different reset windows). Wave 5 must be isolated and reviewed separately due to new-mechanism risk.

---

## Recommended Sequence

1. **Wave 1 now** — no new mechanisms, clears the most flags, reduces noise for subsequent audits
2. **Wave 2 concurrently with Wave 1 planning** — small replay change, one pair of new conditions
3. **Wave 3** — protocol-registry PR, moderate complexity
4. **Wave 4** — ETH impact, needs careful testing
5. **Wave 5** — after all others, ADR-056 first, full review cycle before implement

---

## Docs Required Per Wave


| Wave   | Docs                                                                                                              |
| ------ | ----------------------------------------------------------------------------------------------------------------- |
| Wave 1 | `results/blockers.md` status updates (5A/5B/5C/5F → RESOLVED)                                                     |
| Wave 2 | ADR-054 §2 amendment (Net lane for C1→C2) OR ADR-040 amendment; `docs/pipeline/cost-basis/02-avco-rules.md`       |
| Wave 3 | `docs/pipeline/normalization/rules/` Balancer V3 protocol entry                                                   |
| Wave 4 | `docs/pipeline/cost-basis/03-basis-pools-and-carry.md` (LP fee note for native ETH)                               |
| Wave 5 | New `ADR-056-onchain-earn-fund-round-trip-continuity.md`; `docs/pipeline/normalization/rules/` Bybit Earn section |


