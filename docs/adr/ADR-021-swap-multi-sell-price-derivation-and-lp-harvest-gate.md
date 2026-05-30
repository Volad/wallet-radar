# ADR-021 — SWAP Multi-Sell Price Derivation and Concentrated-Liquidity Harvest-Only Gate

**Status**: Accepted (amended 2026-05-29 — P-A MasterChef withdraw gate)  
**Date**: 2026-05-29  
**Theme**: ETH AVCO accuracy — pricing correctness, LP classification  
**Supersedes**: none  
**Related**: ADR-018 (LP family materialisation), ADR-020 (bridge late-carry)

---

## Context

After fixing the bridge late-carry ordering bug (ADR-020) and LENDING_LOOP pricing, ETH-family
AVCO improved from $1,721 → $2,631. Two further basis-loss patterns were identified:

### Pattern A — Multi-leg SWAP undervaluation

Aggregator-routed SWAPs (e.g., via Uniswap V3 on BASE with a dust-USDC slippage leg plus a
main USDC amount) produce two `SELL` flows of the same asset (USDBC/USDC) plus one `BUY` flow
(WETH). The existing `SwapDerivedPriceResolver` looped through siblings and **returned on the
first resolved sibling**, deriving:

```
derivedPrice = firstSellValue / buyQty
             = $0.25 / 0.026101 WETH = $9.52/WETH   ← wrong
```

Instead of the correct:

```
derivedPrice = (firstSellValue + secondSellValue) / buyQty
             = ($0.25 + $99.13) / 0.026101 = $3,806/WETH
```

**Impact**: 5 SWAPs undervalued BUY cost by a total of $122 — depressing ETH-family AVCO.

### Pattern B — Concentrated-liquidity harvest phantom receipt burn

PancakeSwap V3 (and compatible) position managers support a multicall:
```
decreaseLiquidity(tokenId, liquidity=0, amount0Min, amount1Min) + collect(tokenId, ...)
```
This is a gas-efficient collect of accrued fees (CAKE rewards) without removing any liquidity.
The `decreaseLiquidity` call with `liquidity=0` is a no-op for principal but triggers the
existing classification branch:

```java
// LpPositionLifecycleSupport.resolvePositionManagerMulticallType
if (decreaseLiquidity && hasInboundNonFeeLeg(movementLegs)) {
    return NormalizedTransactionType.LP_EXIT;  // ← wrong for liquidity=0
}
```

`LpPrincipalCloseEvidence.refineLifecycleType` did not downgrade the type because
`hasPositionReductionEvidence` returned `true` (DECREASE_LIQUIDITY_SELECTOR found in calldata).
`LpNftClFlowMaterializer` then added `LP-RECEIPT:-1` to the flow.

**Consequence in replay**:
1. CAKE-harvest LP_EXIT drains the composite basis bucket → REALLOCATE_IN to CAKE  
2. `drainMaterializedReceiptMarker` called → `recordLpReceiptPrincipalExitEvent` → lifecycle closed  
3. Subsequent real LP_EXIT (WETH + USDC principal) finds lifecycle closed → **UNKNOWN** for all
   principal inflows  

**Affected positions** (BASE, PancakeSwap V3): 445831 (WETH $58), 445752, 448475, 450450,
477096, 938761 — all with stablecoin basis loss and alternating UNKNOWN/REALLOCATE_IN patterns.

---

## Decision

### A — Multi-sell SWAP: accumulate all counterpart SELL flows

`SwapDerivedPriceResolver` must accumulate the **total value of all counterpart-direction siblings**
(all SELL flows when pricing a BUY, all BUY flows when pricing a SELL) before dividing by the
target flow's quantity:

```java
BigDecimal totalCounterpartValue = ZERO;
for each sibling with resolved price {
    if (isCounterpartRole(current.role, sibling.role)) {
        totalCounterpartValue += sibling.qty.abs() * sibling.price;
    }
}
derivedPrice = totalCounterpartValue / current.qty.abs();
```

The existing `hasMultipleSameCanonicalFlows` guard (bail out when the *current* flow's symbol
appears multiple times) is preserved as-is — it correctly prevents circular derivation.

`isCounterpartRole` definition:
- BUY → SELL is counterpart
- SELL → BUY is counterpart
- TRANSFER → any non-FEE (existing behaviour, no regression)

### B — Concentrated-liquidity harvest-only gate

`LpPrincipalCloseEvidence.refineLifecycleType` must apply `isHarvestOnlyRewardPattern(movementLegs)`
**after** the existing `hasPositionReductionEvidence` check:

```java
if (!hasPositionReductionEvidence(view)) {
    return LP_FEE_CLAIM;
}
// Even with decreaseLiquidity in calldata, if all inflows are fee-only,
// no principal was returned — treat as fee claim.
if (isHarvestOnlyRewardPattern(movementLegs)) {
    return LP_FEE_CLAIM;
}
return type;
```

`isHarvestOnlyRewardPattern` qualifies as harvest-only when every non-fee inbound leg is either:
- CAKE (DEX reward token for PancakeSwap V3), OR
- Any single stablecoin leg ≤ $100 dust threshold

The gate is intentionally conservative: mixed WETH+CAKE or USDC+ETH exits are not harvest-only
and keep `LP_EXIT` classification.

---

## Consequences

### Positive

- 5 SWAPs correctly priced → $122 additional ETH-family basis attributed
- PancakeSwap V3 fee-harvest transactions reclassified as `LP_FEE_CLAIM`:
  - No phantom `LP-RECEIPT:-1` emitted by `LpNftClFlowMaterializer`
  - Composite basis bucket intact for subsequent real principal exit
  - WETH and USDC principal correctly restored with `REALLOCATE_IN`
- Pattern applies to all V3-compatible position managers (Aerodrome CL, Velodrome CL, etc.)
  once their reward tokens are added to the harvest-only symbol list

### Risks and mitigations

| Risk | Mitigation |
|------|-----------|
| Aggregator with genuinely split BUY flows (partial fill + rebate as separate BUY legs) accumulates incorrect total | `hasMultipleSameCanonicalFlows` already bails when BUY leg appears twice; both would need resolved prices to accumulate |
| Harvest-only gate fires on a CAKE single-asset LP where only CAKE is returned as principal | The discriminant checks if `decreaseLiquidity` is in calldata; a CAKE-only LP would use `burn` selector — handled by a different branch that correctly returns `LP_EXIT` |
| Protocols using CAKE-like tokens for principal (not fees) | Scope: `isHarvestOnlyRewardPattern` uses a curated symbol list; unknown tokens return `false` → safe fallback to `LP_EXIT` |

---

## Also fixed (related, same plan)

`PriceableFlowPolicy.requiresInboundShortfallSpotPricing` extended to include
`LP_EXIT_SETTLEMENT` and `LP_ENTRY_SETTLEMENT` — these types return principal via `TRANSFER`
flows that must be market-priced (same pattern as `LP_EXIT`, `LENDING_WITHDRAW`). Affected:
GMX V2 settlement WETH flows on ARBITRUM (Feb 2026) losing $422 basis.

---

## Amendment — P-A: PancakeSwap MasterChef `withdraw` harvest gate (2026-05-29)

**Trigger**: After deploying the original B4 gate (Pattern B above), four additional PancakeSwap V3
positions on Arbitrum and BASE were found with UNKNOWN basis on their real LP_EXIT. Root cause: those
positions had been staked in the PancakeSwap V3 MasterChef farm. To collect CAKE rewards without
removing liquidity, the user calls `withdraw(uint256 tokenId, address to)` **directly on the
MasterChef contract** (selector `0x00f714ce`). This call:
- Unstakes the position NFT from the farm
- Distributes accumulated CAKE rewards to `to`
- Does **not** call `decreaseLiquidity` — LP liquidity remains unchanged

The original B4 gate `isHarvestOnlyRewardPattern && hasZeroLiquidityDecrease` failed because
`hasZeroLiquidityDecrease` searches calldata for an embedded `decreaseLiquidity` selector that is
simply not present in a direct MasterChef `withdraw` call.

**Complication**: `0x00f714ce` is the selector for **both** `burn(uint256 tokenId)` on the Uniswap V3
NPM (a genuine LP exit that destroys the position NFT) and `withdraw(uint256 tokenId, address to)` on
the MasterChef (a farm-unstake that returns the NFT). These cannot be distinguished by calldata alone.

**Discriminant**: The ERC721 Transfer event direction in the transaction logs:
- MasterChef `withdraw`: emits Transfer(from=MasterChef, **to=wallet**) — NFT returned
- NPM `burn`: emits Transfer(from=wallet, **to=0x0**) — NFT destroyed

`LpPositionLifecycleSupport.hasAnyErc721TransferToWallet(view)` is `true` only for the MasterChef
case. A new gate `isMasterChefWithdrawDirectCall(view)` is added to `LpPrincipalCloseEvidence`:

```java
private static boolean isMasterChefWithdrawDirectCall(OnChainRawTransactionView view) {
    if (view == null) return false;
    String methodId = normalizeSelector(view.methodId());
    if (!BURN_SELECTOR.equals(methodId)) return false;
    if (!LpPositionLifecycleSupport.hasAnyErc721TransferToWallet(view)) return false;
    String inputData = view.inputData();
    return inputData == null
        || !CalldataDecodingSupport.containsEmbeddedSelector(inputData, DECREASE_LIQUIDITY_SELECTOR);
}
```

Added to `refineLifecycleType()` as a second harvest-only OR-branch:

```java
if (isHarvestOnlyRewardPattern(movementLegs) && isMasterChefWithdrawDirectCall(view)) {
    return NormalizedTransactionType.LP_FEE_CLAIM;
}
```

**Affected positions**: ARB:PANCAKESWAP:196975, 204401, 218217; BASE:PANCAKESWAP:445831  
**Recoverable basis**: ~$2,720 ETH-family  
**Implementation plan**: `docs/tasks/avco-eth-masterchef-harvest-fix-plan.md`

---

## References

- Implementation plan (original): `docs/tasks/avco-eth-lp-swap-fix-plan.md`
- Implementation plan (P-A amendment): `docs/tasks/avco-eth-masterchef-harvest-fix-plan.md`
- `SwapDerivedPriceResolver` — `backend/src/main/java/com/walletradar/pricing/resolver/event/`
- `LpPrincipalCloseEvidence` — `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/support/`
- `PriceableFlowPolicy` — `backend/src/main/java/com/walletradar/pricing/application/`

---

## Amendment — B-NEW-1 guard replacement (2026-05-30)

**Amendment (2026-05-30)**: The `hasMultipleSameCanonicalFlows` guard referenced in the original B-NEW-1 context has been replaced. See ADR-022 for the surgical replacement: `hasCounterpartSameCanonicalFlow` (preserves the circular-derivation guard) + `computeTotalSameDirectionQty` (enables multi-same-direction-leg pricing).
