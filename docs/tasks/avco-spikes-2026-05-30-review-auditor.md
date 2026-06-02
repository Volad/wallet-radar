# AVCO Spikes — Auditor Review of Implementation Plan
**Plan ref**: `docs/tasks/avco-spikes-2026-05-30-implementation-plan.md`  
**Audit ref**: `docs/tasks/avco-spikes-2026-05-30-audit.md`  
**Review date**: 2026-05-30  
**Reviewer**: financial-logic-auditor  
**Verdict**: **CONDITIONAL APPROVE** — B-3 and B-2 fixes are financially correct; B-4 diagnosis contains a material error that must be corrected before normalization work begins.

---

## 1. B-3 Fix Validation — Proportional Carry Basis

### Math correctness

**Confirmed correct** via direct ledger inspection.

Live ledger data for the Sep 10 2025 Mantle→Bybit cmETH corridor (seq 5120–5124):

| Field | Value |
|-------|-------|
| `totalQty` (seq 5120) | 0.862092260317885 cmETH |
| `uncoveredQty` (seq 5120) | 0.211806055174743... cmETH |
| `coveredQty` | 0.650286205143141... cmETH |
| `totalCostBasis` (seq 5120) | $1,898.792723725966... |
| `avcoAfter` (seq 5120) | $2,919.933882... (= $1,898.79 / 0.650286) — **inflated** |
| CARRY_OUT cbDelta (seq 5122) | -$1,898.79 (correct — full basis drained) |
| CARRY_IN cbDelta **now** (seq 5124) | **+$2,517.252400...** (wrong — inflated) |

The CARRY_IN at seq 5124 is the Bybit FUND `BYBIT-33625378:FUNDING_HISTORY:f9cfb4ebdc862397c694c42eccd2329bba20bea9aa83a588357ce3a56d03d3a0`.

**Scenario: when uncoveredQty = 0 (clean position)**

`coveredQty = totalQty`, so `perWalletAvco = totalBasis / totalQty`.  
Old formula: `movedQty × (totalBasis / totalQty)` = `totalBasis × (movedQty / totalQty)` — **same as new formula**. No regression.

**Scenario: when uncoveredQty > 0 (partial-shortfall position)**

Old: `movedQty × perWalletAvco = movedQty × (totalBasis / coveredQty) > totalBasis × (movedQty / totalQty)` — inflated.  
New: `totalBasis × (movedQty / totalQty)` — correct proportional slice. ✓

**Scenario: partial position move (`movedQty < totalQty`)**

New formula: `totalBasis × (movedQty / totalQty)` — correctly proportional. For example, with `movedQty = 0.5 × totalQty`, carry basis = 50% of totalBasis.  
Old formula with uncovered qty: `0.5 × totalQty × (totalBasis / coveredQty)` > 50% of totalBasis — over-inflates. ✓ Fix is correct.

### Acceptance criteria math check (Question 4)

Confirmed: seq 5124 currently records `costBasisDeltaUsd = $2,517.252400...` (matches plan's stated value).

Fix formula: `$1,898.792723... × (0.862092260317885 / 0.862092260317885) = $1,898.792723...`

The move is a full position drain (`movedQty = totalQty = 0.862092`), so the ratio is exactly 1.0.  
Result: **$1,898.79** ✓ — matches acceptance criterion within $0.01 tolerance.

### Risk: missing guard for movedQty > preDrainTotalQty

**CONCERN — action required before merge.**

The plan's fix code:
```java
corridorCarryBasis = preDrainTotalBasis
        .multiply(movedQty, MC)
        .divide(preDrainTotalQty, MC);
```

does NOT add a guard that caps `corridorCarryBasis` at `preDrainTotalBasis` when `movedQty > preDrainTotalQty`. The plan's own risk question ("Edge case: movedQty > totalQty — does the formula work?") is answered: **it does not guard**. If `movedQty > preDrainTotalQty` due to any rounding or race condition, the carry would exceed the total position basis, which is worse than the original bug.

**Required addition** after the division:
```java
// Clamp: carry basis cannot exceed the total position basis being drained
if (corridorCarryBasis.compareTo(preDrainTotalBasis) > 0) {
    corridorCarryBasis = preDrainTotalBasis;
}
```

This is a defensive guard for a case that "shouldn't happen" but must not silently corrupt basis if it does.

---

## 2. B-2 Fix Validation — INTERNAL_TRANSFER Corridor Ordering

### Guard scope analysis

The proposed guard:
```java
boolean isOnChainCorridor = !isBybit
        && Boolean.TRUE.equals(tx.getContinuityCandidate())
        && tx.getType() == NormalizedTransactionType.INTERNAL_TRANSFER
        && tx.getCorrelationId() != null
        && !tx.getCorrelationId().isBlank();
```

**Confirmed correctly scoped.** Verified against live data:

- **Seq 1332** (CARRY_OUT, CMETH, Mantle INTERNAL_TRANSFER): `correlationId = 'internal-tx:mantle:0xd7c7736b...'`, `continuityCandidate = true`. This is the exact B-2 case. The guard correctly captures it.
- **Seq 836** (CARRY_OUT, METH, Bybit INTERNAL_TRANSFER): `continuityCandidate = false`. This will NOT match the guard — correct, because Bybit-side INTERNAL_TRANSFERs without `continuityCandidate` are not corridor transfers requiring the ordering fix.
- All non-corridor INTERNAL_TRANSFERs in the dataset have `continuityCandidate = false` or `correlationId = null`, so they are not affected.

The triple guard (`continuityCandidate=true AND correlationId not blank AND type=INTERNAL_TRANSFER`) is the correct compound predicate. No false positives found in the ETH-family dataset.

Existing Bybit flow (`isBybit=true`) is unchanged — the new branch only runs when `!isBybit`. ✓

---

## 3. B-1 / B-4 Investigation — UNKNOWN Transactions

### B-1 (Feb 6 2026 UNKNOWN)

**Full `_id`**: `0x0a757aeeb58667c545017cd8e5cd60dc994a8945ed810c60ea2aed18688f4f7a:BASE:0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f`

**Confirmed as PancakeSwap V3 multicall:**
- `classifiedBy: 'PROTOCOL_REGISTRY'`, `protocolName: 'PancakeSwap'`, `protocolVersion: 'V3'`
- `missingDataReasons: ['ROUTER_METHOD_OVERLOAD_UNSUPPORTED']`
- Raw `methodId: 0xac9650d8` = `multicall(bytes[])` on NonfungiblePositionManager (`0x46a15b0b27311cedf172ab29e4f4766fbe7f4364`)
- Inner calls decoded from `rawData.input`:
  - `0x0c49ccbe` = `decreaseLiquidity()`
  - `0xfc6f7865` = `collect()`
  - `0x49404b7c` = `unwrapWETH9()`
  - `0xdf2ab5bb` = `sweepToken(USDC)`

This is a full LP position exit on PancakeSwap V3 BASE with ETH unwrapping. The plan's diagnosis is correct. The normalizer must be extended to handle the `multicall` + `decreaseLiquidity` + `collect` + `unwrapWETH9` pattern as `LP_EXIT` with ETH and USDC inflows.

**Flows that are missing from accounting**: `+0.799 ETH` (unwrapped from WETH) and USDC sweep. Without this inflow, the BASE ETH position has near-zero balance → B-1 shortfall confirmed.

### B-4 (Nov 17 2025 UNKNOWN) — CRITICAL PLAN ERROR

**Full `_id`**: `0x47cf19b77ea9ee2e4e266ddfcf6be3b566e64e198ba85a08a62a9c550ea0fc3c:BASE:0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f`

**The plan's hypothesis is factually incorrect.**

The plan states: *"Nov 17 UNKNOWN at 08:02:35 is a PancakeSwap LP_EXIT or COLLECT returning 0.546 ETH"* and *"Same normalization fix as B-1".*

**Evidence against this:**

1. **Classification**: `classifiedBy: 'HEURISTIC'`, `missingDataReasons: ['PROMO_SPAM_PHISHING']`. The normalizer correctly identified this as spam/phishing — not a protocol registry miss.

2. **Raw input**: `methodId: 0xa06c1a33` — this is NOT a PancakeSwap V3 selector. The input payload encodes `0xc8 = 200` as the first parameter, followed by a list of exactly 200 wallet addresses (one of which is `0x1a87...`). This is a batch distribution contract calling a multi-recipient function, consistent with an airdrop or token distribution event. It has NO resemblance to `decreaseLiquidity`, `collect`, or any LP exit function.

3. **Timeline context**: The Nov 17 cluster for wallet `0x1a87...` on BASE from 07:57 to 08:08:
   ```
   07:57:41  REWARD_CLAIM    0xeff4...  receives CAKE (no ETH inflow)
   07:57:57  LP_POSITION_UNSTAKE 0x7e31...  (no ETH inflow)
   07:58:53  LP_EXIT         0x6b57...  receives USDC 9.948876 only, NO ETH
   08:02:35  UNKNOWN (spam)  0x47cf...  batch distribution, 200 addresses, no ETH
   08:05:53  WRAP            0xbdf2...  ETH→WETH 0.546 (shortfall — source unknown)
   ```
   The LP_EXIT at 07:58:53 (`0x6b57...`) is correctly normalized and returns only USDC from a PancakeSwap USDC/ETH position (the LP receipt `LP-RECEIPT:BASE:PANCAKESWAP:477096` is burned). The ETH side of that LP position was not captured.

4. **True source of 0.546 ETH**: The WRAP `0xbdf26819...` at 08:05:53 wraps 0.546 ETH. The ETH for this WRAP is NOT from the Nov 17 UNKNOWN. The source must be sought in the LP_EXIT at 07:58:53 (which may have returned ETH from a concentrated LP position that the normalizer didn't capture) or in prior untracked BASE ETH history.

**Impact of plan error**: If the implementation team treats the Nov 17 UNKNOWN as a PancakeSwap LP_EXIT requiring normalization fix, they will waste effort on an UNKNOWN that is correctly classified as spam. The real missing ETH inflow for B-4 requires a separate investigation into:
- Whether `0x6b57...` LP_EXIT at 07:58:53 should have returned ETH in addition to USDC (concentrated LP range may have been fully in USDC), OR
- Whether there is a prior BASE ETH acquisition missing from backfill history (same root as B-1)

**Revised B-4 diagnosis**:  
The Nov 17 UNKNOWN is irrelevant to B-4. B-4 root cause is the same as B-1: BASE ETH acquisition history is missing for wallet `0x1a87...`. Once B-1 normalization fix backfills the full BASE ETH position history (including the Feb 6 2026 PancakeSwap exit), the baseline ETH position going into Nov 17 will be correct, and whether the 0.546 WRAP shortfall resolves depends on whether earlier BASE ETH acquisitions exist that cover that balance.

---

## 4. Acceptance Criteria — Confirmed

| Criterion | Status | Evidence |
|-----------|--------|----------|
| B-3: seq 5124 changes from $2,517.25 → $1,898.79 | ✅ Math confirmed | DB shows $2,517.252400... now; fix gives $1,898.792723... |
| B-2: source CARRY_OUT seq < destination CARRY_IN seq for `0xd7c7736b...` | ✅ Ordering fix correct | Seq 1330 (destination CARRY_IN) must become > new CARRY_OUT seq |
| B-1: BRIDGE_IN `0x38d445c4...ARB` basis equals carry-out, not spot-inflated $1,515 | ✅ Requires B-1 normalization fix | Confirmed by shortfall in seq for BRIDGE_OUT on BASE |
| B-4: no -19%/+23% swing around `0xbdf26819...BASE` WRAP | Depends — must re-investigate | Nov 17 UNKNOWN is not the fix; B-4 requires separate ETH-source investigation |

---

## 5. Unaddressed Risks — Other ETH-Family CARRY_OUTs with Uncovered Qty

Scanned all ETH-family `CARRY_OUT` entries with `uncoveredQuantityDelta < 0` and non-null `correlationId`.

**Found 2 material cases beyond the plan:**

### Case A — seq 617 (ETH, Across bridge, wallet `0x68bc3b...`)

| Field | Value |
|-------|-------|
| `_id` | `0xf79c1678...ARBITRUM:0x68bc3b81...` |
| `correlationId` | `bridge:across:0xf79c1678...` |
| `quantityDelta` | -0.004 ETH |
| `uncoveredQuantityDelta` | -0.001221... (30% of position) |
| `costBasisDeltaUsd` (CARRY_OUT) | -$7.68 |
| CARRY_IN at seq 619 `costBasisDeltaUsd` | **+$11.06** |
| Inflation | **+$3.38** |

The B-3 bug IS present here. The CARRY_IN on the destination side (`0xc0c9c582...UNICHAIN:0x68bc3b...`, seq 619) records $11.06 instead of $7.68. Impact is small (~$3.38) and below the 10% spike threshold, but is financially incorrect. The B-3 fix in `TransferReplayHandler` will correct this automatically when the Across bridge corridor CARRY_OUT passes through the same code path.

**Action**: Verify after the B-3 fix is applied that seq 619 also changes to $7.68. Add to acceptance criteria.

### Case B — seq 1332 (CMETH, Mantle on-chain INTERNAL_TRANSFER, wallet `0xa0dd42...`)

This is the B-2 source transaction. The `uncoveredQuantityDelta = -0.196235...` is present, but:
- This is an **on-chain INTERNAL_TRANSFER** (not a Bybit FUND corridor)
- The `corridorOutboundSliceAvco` logic applies to Bybit FUND corridors specifically; whether it applies to on-chain INTERNAL_TRANSFER depends on the `corridorTransfer` flag in the code
- The B-2 fix correctly addresses the ordering issue for this transaction

No further B-3 action required for seq 1332 beyond what B-2 already addresses.

### Other cases (confirmed non-issues)

- seq 623, 6791, 7220, 7232: all have `costBasisDeltaUsd = 0` (full shortfall, $0 carry). The P0-C formula on $0 totalBasis gives $0 carry. No inflation possible. ✓
- seq 836 (METH, `continuityCandidate=false`): Not a corridor transfer, P0-C override does not fire. ✓
- seq 5916 (VBETH, `uncoveredQuantityDelta = -6.57E-16`): floating-point artifact, negligible. ✓

---

## 6. Summary — Issues Requiring Resolution Before Merge

| # | Issue | Severity | Required Action |
|---|-------|----------|-----------------|
| R-1 | Missing guard: `movedQty > preDrainTotalQty` in B-3 fix | **Medium** | Add cap: `corridorCarryBasis = min(corridorCarryBasis, preDrainTotalBasis)` |
| R-2 | B-4 diagnosis is wrong: Nov 17 UNKNOWN is spam, not LP_EXIT | **High** | Remove "fix the Nov 17 UNKNOWN" from the normalization plan. Investigate actual ETH source separately. |
| R-3 | seq 619 (Across bridge CARRY_IN) also inflated by B-3 bug (+$3.38) | Low | Accept as auto-fixed by B-3 code change; verify in acceptance criteria. |

---

## 7. Overall Verdict

**B-3 code fix**: APPROVE (with guard addition at R-1)  
**B-2 code fix**: APPROVE  
**B-1 normalization fix (Feb 6 PancakeSwap multicall)**: APPROVE  
**B-4 normalization fix (Nov 17 UNKNOWN)**: **REJECT as specified.** The Nov 17 UNKNOWN (`0x47cf19b...`) is correctly classified as PROMO_SPAM_PHISHING. It returns no ETH. The plan must be revised to investigate the actual missing ETH source before any normalization work is done for B-4.
