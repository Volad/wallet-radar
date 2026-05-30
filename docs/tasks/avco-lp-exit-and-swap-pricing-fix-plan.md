# AVCO Fix Plan: LP_EXIT Cross-Pool Attribution + Dual-BUY SWAP Pricing

**Slug**: `avco-lp-exit-and-swap-pricing`  
**Date**: 2026-05-30  
**Audit sources**: `results/avco-spike-audit/`, `results/avco-eth-remaining-audit/`  
**Status**: Draft

---

## Blocker Summary

| ID | Severity | Stage | Impact | Fix complexity |
|----|----------|-------|--------|----------------|
| **S-1** | CRITICAL | `cost_basis` — LP_EXIT replay | ETH AVCO spikes to $23,372 (7× market) | Medium — 1 method change |
| **B-NEW-1** | MEDIUM | `pricing` — SwapDerivedPriceResolver | aArbWBTC gets zero cost basis (~$15 BTC family) | Low — 1 method change |
| P-B | — | `cost_basis` — upstream gap | ~$3,024 ETH missing basis | Not fixable without new evidence |
| P-C | — | Pre-normalization gap | ~$3,186 ETH missing basis | New pipeline feature required |

P-B and P-C are **deferred** — not code defects. See out-of-scope section.

---

## Scope

- **Wallets**: All (S-1 affects any multi-asset CL LP position; B-NEW-1 affects `0x1a87f12`)
- **Networks**: BASE, Arbitrum (confirmed); any network with multi-asset PancakeSwap/Uniswap V3 CL LPs (S-1)
- **Families**: ETH (S-1), BTC (B-NEW-1)

---

## S-1 — LP_EXIT Cross-Pool Cost Attribution Bug

### Root Cause

**File**: `PositionScopedLpExitReplayHandler.java`, method `restoreInboundFromLpReceiptPool()` (lines 548–575)

For each inbound TRANSFER flow at LP_EXIT, the handler:
1. Withdraws from the same-asset pool (correct)
2. **Drains cross-asset pools proportionally** — taking `proportion = withdrawn_same / held_same`

For LP 445831 exit (WETH + USDC returned):

```
// Processing WETH flow first:
proportion = 0.021934 WETH_withdrawn / 0.042975 WETH_held = 51.04%

// Cross-pool drain hits USDC pool:
crossWithdrawQty = 636.16 USDC_held × 51.04% = 324.77 USDC
crossWithdrawBasis = $869.34 × 51.04% = $443.71

// totalBasis attributed to WETH:
= $72.58 (WETH pool) + $443.71 (USDC cross-drain) = $516.29  ← WRONG
// Correct: $72.58 (WETH pool only)

// AVCO after: $516.29 / 0.02211 ETH = $23,372  ← spike
```

The cross-pool drain is **designed for single-asset exits** (e.g., only WETH returned, USDC stayed in pool = impermanent loss). When USDC is ALSO being returned in the same transaction, the USDC pool is drained twice: once via WETH cross-drain, once via USDC's own processing.

### Fix

In `restoreInboundFromLpReceiptPool()`, before the cross-pool loop, collect the asset identities of all OTHER inbound TRANSFER flows in the same transaction. Skip any cross-pool whose `assetIdentity` matches a directly-returned asset.

```java
// NEW: collect asset identities of other inbound TRANSFER flows in this tx.
// These pools will be drained when their own flow is processed — do not
// cross-drain them here.
Set<String> directlyReturnedIdentities = new java.util.HashSet<>();
for (NormalizedTransaction.Flow f : transaction.getFlows()) {
    if (f == null || f == flow || f.getRole() != NormalizedLegRole.TRANSFER
            || f.getQuantityDelta() == null || f.getQuantityDelta().signum() <= 0) {
        continue;
    }
    String id = assetSupport.continuityIdentity(transaction, f);
    if (id != null) {
        directlyReturnedIdentities.add(id);
    }
}

// MODIFIED cross-pool loop — add guard:
for (var entry : poolContext.pools().entrySet()) {
    LpReceiptBasisPoolKey key = entry.getKey();
    if (!corrId.equals(key.lpCorrelationId()) || sameKey.equals(key)) {
        continue;
    }
    // NEW: skip cross-pools whose asset is directly returned in this transaction
    if (directlyReturnedIdentities.contains(key.assetIdentity())) {
        continue;
    }
    // ... (rest of existing drain logic unchanged)
}
```

**Effect for LP 445831:**
- WETH processing: cross-pool loop skips USDC pool (USDC has direct inbound flow)
- WETH gets only WETH pool basis: `$72.58 / 0.02193 WETH = $3,309/ETH` ✓
- USDC processing: cross-pool loop skips WETH pool (WETH has direct inbound flow)
- USDC gets only USDC pool basis: `$869.34 → USDC REALLOCATE_IN` ✓

**Effect for single-asset exit (WETH only, no USDC returned):**
- `directlyReturnedIdentities` is empty (no other inbound TRANSFER)
- USDC cross-drain proceeds as today (correct — impermanent loss carries to WETH)

### Test cases required

| # | Scenario | Expected |
|---|----------|----------|
| T1 | LP_EXIT: WETH + USDC both returned, existing test with two-pool position | WETH gets only WETH pool basis; USDC gets only USDC pool basis |
| T2 | LP_EXIT: WETH only returned (USDC retained in pool, out of range) | WETH gets WETH pool + USDC cross-drain basis (unchanged behavior) |
| T3 | LP_EXIT: USDC only returned (WETH retained, out of range) | USDC gets USDC pool + WETH cross-drain basis |
| T4 | LP_EXIT: WETH + USDC returned, position 445831 exact values | WETH AVCO ~$3,220 (not $23,372) |

### Acceptance Criteria

| Check | Expected |
|-------|---------|
| A1 | LP_EXIT `0x457b9d30` ledger point for WETH: `costBasisDeltaUsd ≈ $72.58` (not $516.29) |
| A2 | ETH AVCO at seq 4343 ≈ $3,200–3,400 (not $23,372) |
| A3 | ETH AVCO spike no longer visible on the graph (max AVCO < 2× current market price) |
| A4 | USDC REALLOCATE_IN for same tx: `costBasisDeltaUsd ≈ $869.34` (not $495) |
| A5 | All existing `PositionScopedLpExitReplayHandlerTest` tests pass |
| A6 | Single-asset-exit LP positions (WETH-only or USDC-only): cross-drain still works (AVCO unchanged) |

---

## B-NEW-1 — `SwapDerivedPriceResolver` Dual-BUY-Leg Guard Too Aggressive

### Root Cause

**File**: `SwapDerivedPriceResolver.java`, `resolve()` method (line 34–36) and `hasMultipleSameCanonicalFlows()` (lines 93–113)

When two BUY flows share the same canonical symbol (e.g., two `aArbWBTC` BUY legs), `hasMultipleSameCanonicalFlows` returns `true` → `resolve()` bails out → no price derived.

For `0xdef59c37` (KyberSwap, Arbitrum):
- SELL: USDC −15 @ $1 (priced)
- BUY: aArbWBTC +5.3e-7 (price=null)
- BUY: aArbWBTC +0.00020196 (price=null)

Correct derived price: `$15 / (5.3e-7 + 0.00020196) = $74,077/token`

### Fix

`hasMultipleSameCanonicalFlows` serves **two distinct purposes** that must be handled separately:

| Case | Old behavior | Correct behavior |
|------|-------------|-----------------|
| 2× aArbWBTC BUY + USDC SELL (multi-same-side) | Bail (too aggressive — the bug) | Aggregate both BUY legs, derive price |
| ETH BUY + ETH SELL + WETH SELL (circular) | Bail (correct) | Bail — counterpart shares same canonical |

The fix replaces `hasMultipleSameCanonicalFlows` with two targeted methods:

1. **`hasCounterpartSameCanonicalFlow`** — fires only when a *counterpart-role* sibling shares the same canonical (circular case). Preserves the existing guard.
2. **`computeTotalSameDirectionQty`** — sums quantities of same-direction same-*exact-symbol* flows (not canonical-family, to avoid merging e.g. `aArbWBTC` + `cbBTC`).

```java
@Override
public Optional<PriceQuote> resolve(PriceResolutionContext context) {
    // ... (existing type/role/qty checks unchanged)

    // Guard: bail if any counterpart-role sibling shares the same canonical
    // (circular derivation: ETH BUY priced by ETH SELL would be self-referential).
    if (hasCounterpartSameCanonicalFlow(context)) {
        return Optional.empty();
    }
    // Aggregate denominator: sum all same-direction same-exact-symbol legs.
    // When only one leg exists this equals flow qty (single-leg behavior preserved).
    BigDecimal totalSameDirQty = computeTotalSameDirectionQty(context);

    BigDecimal totalCounterpartValue = BigDecimal.ZERO;
    PriceQuote firstQuote = null;
    int contributingCount = 0;

    for (int siblingIndex = 0; siblingIndex < context.flows().size(); siblingIndex++) {
        if (siblingIndex == context.flowIndex()) continue;
        // ... (existing sibling loop, filter, isCounterpartRole, accumulate unchanged)
    }

    if (firstQuote == null || totalCounterpartValue.signum() == 0) {
        return Optional.empty();
    }
    BigDecimal derivedPrice = totalCounterpartValue
            .divide(totalSameDirQty, DIVISION_CONTEXT);
    return Optional.of(new PriceQuote(
            derivedPrice,
            PriceSource.SWAP_DERIVED,
            context.transaction().getBlockTimestamp(),
            firstQuote.quoteSymbol(),
            "swap-derived-multi:" + contributingCount
    ));
}

/** Bail out when a counterpart-role sibling shares the same canonical symbol
 *  (e.g. ETH BUY priced against ETH SELL — circular / wash trade). */
private boolean hasCounterpartSameCanonicalFlow(PriceResolutionContext context) {
    for (NormalizedTransaction.Flow sibling : context.flows()) {
        if (sibling == null || sibling.getRole() == NormalizedLegRole.FEE
                || sibling.getQuantityDelta() == null || sibling.getQuantityDelta().signum() == 0) {
            continue;
        }
        if (!isCounterpartRole(context.flow(), sibling)) continue;
        if (CanonicalAssetCatalog.sameCanonicalSymbol(
                context.flow().getAssetSymbol(), sibling.getAssetSymbol())) {
            return true;
        }
    }
    return false;
}

/** Sum quantities of all same-direction same-exact-symbol flows (including self).
 *  Uses exact symbol (not canonical family) to prevent merging different wrapped tokens. */
private BigDecimal computeTotalSameDirectionQty(PriceResolutionContext context) {
    BigDecimal total = BigDecimal.ZERO;
    NormalizedLegRole currentRole = context.flow().getRole();
    int currentSignum = context.flow().getQuantityDelta().signum();
    String currentSymbol = context.flow().getAssetSymbol();
    for (NormalizedTransaction.Flow f : context.flows()) {
        if (f == null || f.getRole() == NormalizedLegRole.FEE
                || f.getQuantityDelta() == null || f.getQuantityDelta().signum() == 0) {
            continue;
        }
        boolean sameDir = (currentRole == NormalizedLegRole.BUY || currentRole == NormalizedLegRole.SELL)
                ? f.getRole() == currentRole
                : f.getQuantityDelta().signum() == currentSignum;
        if (!sameDir) continue;
        if (!currentSymbol.equalsIgnoreCase(f.getAssetSymbol())) continue;
        total = total.add(f.getQuantityDelta().abs());
    }
    return total.signum() > 0 ? total : context.flow().getQuantityDelta().abs();
}
```

**Remove** `hasMultipleSameCanonicalFlows()` entirely (superseded by `hasCounterpartSameCanonicalFlow`).

**Conservation check**: `$74,077 × 5.3e-7 + $74,077 × 0.00020196 = $15.00 ✓`

### Test cases required

| # | Scenario | Expected |
|---|----------|----------|
| T1 | Single BUY + single SELL: behavior unchanged | Same price as current |
| T2 | 2× aArbWBTC BUY + 1 USDC SELL (@$1): derived price = $15 / total_aArbWBTC_qty | Both BUY legs get price $74,078 |
| T3 | 2× aArbWBTC BUY + 1 USDC SELL, no USDC price: no derivation | `Optional.empty()` |
| T4 | Existing multi-SELL test (P0): 2× USDC SELL + 1 ETH BUY | ETH price = sum(all SELL values) / ETH qty (unchanged) |
| T5 | ETH BUY + ETH SELL + WETH SELL (circular/wash): guard fires | `Optional.empty()` (existing test `wethSellPlusEthSellPlusEthBuy_guardFires_returnsEmpty` — **update assertion comment, behavior unchanged**) |

### Acceptance Criteria

| Check | Expected |
|-------|---------|
| A1 | `0xdef59c37` flows: both aArbWBTC BUY legs have `priceSource=SWAP_DERIVED`, `unitPriceUsd ≈ $74,078` |
| A2 | aArbWBTC `costBasisDeltaUsd ≈ $15.00` total across both legs |
| A3 | All existing `SwapDerivedPriceResolverTest` tests pass |

---

## Documentation Updates (Phase 4)

1. **`docs/03-accounting.md`** — **replace** (not supplement) the paragraph in §6 that says "if the same canonical asset appears multiple times in one SWAP, tx-local ratio pricing must be skipped." Replace with:
   > SWAP leg pricing bails only when a counterpart-role sibling shares the same canonical symbol (circular derivation risk). When multiple same-direction legs share the same canonical (e.g. two BUY legs of the same wrapped token from an aggregator route), their quantities are aggregated as the price denominator and each leg receives the same derived price.

2. **`docs/03-accounting.md`** — add LP_EXIT policy note:
   > At LP_EXIT, each returned asset's basis is drawn exclusively from its own per-asset LP receipt pool. Cross-pool basis carry (from pools of assets NOT directly returned in this transaction) applies only when a CL position exits with only one asset (price out of range / impermanent loss). When multiple assets are returned simultaneously, cross-pool carry is suppressed for each asset that has its own direct return flow.

3. **`docs/adr/ADR-022-lp-exit-per-asset-attribution-and-swap-multi-leg-pricing.md`** — new ADR covering both fixes.

4. **`docs/adr/ADR-021-swap-multi-sell-price-derivation-and-lp-harvest-gate.md`** — amend §B-NEW-1 guard to reflect that `hasMultipleSameCanonicalFlows` is replaced by `hasCounterpartSameCanonicalFlow`.

5. **`docs/adr/INDEX.md`** — add ADR-022 entry; mark ADR-021 as `Accepted (amended)`.

---

## Out-of-Scope Items

| Blocker | Reason for deferral |
|---------|---------------------|
| **S-2** LP 445752 $6.02 cost leak | One-sided exit (0 WETH returned): $6.02 unallocated (impermanent loss gap). Dollar impact immaterial; requires a separate realized-P&L feature for IL recognition. Defer. |
| **P-B** (~$3,024 ETH) | Null-AVCO `CARRY_IN` for wallet `0x68bc3b81` — root is in the lending position that returned ETH without a known entry price. Requires tracing the upstream lending position (or applying retrospective spot pricing). Not a classification or replay defect. |
| **P-C** (~$3,186 ETH) | Unichain Uniswap V4 positions where the original NFT mint is not in the history (ERC721 transferred from another wallet). Requires new pipeline feature: ERC721 `Transfer` → synthetic LP_ENTRY. Not implementable without a broader feature. |
| **S-4** (cmETH $4,382 AVCO) | `correlationId=undefined` on Mantle Equilibria LP exit — requires further investigation to determine if it's the same cross-pool bug or a different pricing issue for cmETH. Investigate AFTER S-1 is deployed (may resolve automatically). |
| **BSC-1** (PancakeSwap 643922 double LP_EXIT) | Non-ETH family (XYZ/USDT). Partial-exit LP-RECEIPT model gap — each `decreaseLiquidity` on the same NFT burns the same LP-RECEIPT. Requires LP lifecycle model change (out of ETH AVCO scope). |

---

## Implementation Order

1. **S-1 code change** — `PositionScopedLpExitReplayHandler.restoreInboundFromLpReceiptPool()`
2. **S-1 tests** — T1–T4 in `PositionScopedLpExitReplayHandlerTest`
3. **B-NEW-1 code change** — `SwapDerivedPriceResolver`: replace `hasMultipleSameCanonicalFlows` with `hasCounterpartSameCanonicalFlow` + add `computeTotalSameDirectionQty()`; update call site in `resolve()`
4. **B-NEW-1 tests** — T1–T5 in `SwapDerivedPriceResolverTest` (T5 = update existing wash-trade test comment; behavior unchanged)
5. **Run full test suite** — `./gradlew :backend:test`
6. **Documentation** — `docs/03-accounting.md` (replace §6 paragraph) + ADR-022 (new) + ADR-021 (amend) + INDEX
7. **Rebuild** — `./scripts/prod-reset-rebuild-backend.sh --skip-frontend`
8. **Verify** — acceptance checks A1–A6 (S-1) and A1–A3 (B-NEW-1)

---

## Rebuild Command

```bash
./scripts/prod-reset-rebuild-backend.sh --skip-frontend
```

No pricing cache clear needed — S-1 is a replay-level fix; B-NEW-1 changes SWAP pricing but only for `aArbWBTC` which has no cached CoinGecko price.
