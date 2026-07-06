# ETH AVCO — LP/SWAP Basis Recovery Implementation Plan

**Slug**: `avco-eth-lp-swap-fix`  
**Date**: 2026-05-29  
**Status**: Draft — awaiting Phase 3 review  
**Audit session**: `df5e69cc-a0c0-4910-8b7d-74488fa266e2`  
**Wallet**: `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f`  
**Current ETH-family AVCO**: ~$2,631  
**Expected ETH-family AVCO after fix**: ~$2,660–$2,700  

---

## 0. Executive Summary

After the previous round of fixes (bridge corridor P0, LENDING_LOOP pricing), ETH-family AVCO
improved from $1,721 → $2,631. Four new blockers remain:

| ID | Stage | Description | ETH-family Basis Lost |
|----|-------|-------------|----------------------|
| **B1** | `pricing` | Multi-leg SWAP: only first SELL flow contributes to BUY cost | **$122** |
| **B2** | `pricing` | `LP_EXIT_SETTLEMENT` WETH inflows unpriced → basis UNKNOWN | **$422** |
| ~~B3~~ | `pricing` | `LP_ENTRY_SETTLEMENT` ETH fee refunds unpriced | ~~$1~~ **DEFERRED** |
| **B4** | `classification` | PancakeSwap V3 CAKE-harvest cycles misclassified as LP_EXIT → phantom receipt burn destroys subsequent exits | **$58 WETH direct** + stablecoin basis loss |

**Total addressable ETH basis gap: ~$603.**  
B2+B3 share the same code path; B4 requires re-normalization (classification fix).

---

## 1. Scope

| Dimension | Value |
|-----------|-------|
| Session | `df5e69cc-a0c0-4910-8b7d-74488fa266e2` |
| Affected networks | BASE (B4), ARBITRUM (B2), multiple (B1) |
| Affected assets | WETH, ETH (B1/B2/B3), PancakeSwap V3 USDC positions (B4 collateral damage) |
| No changes needed | frontend, linking/clarification, TransferReplayHandler |

---

## 2. Blocker Detail

### B1 — Multi-leg SWAP: `SwapDerivedPriceResolver` uses only first SELL flow

**Evidence** (4 confirmed affected SWAPs):

| txHash | SELL pattern | First SELL value | All SELLs total | Missed ETH basis |
|--------|-------------|-----------------|-----------------|-----------------|
| `0x4b1646937d` (BASE Jul 21) | 2× USDBC SELL → WETH BUY | $0.25 | $99.38 | **$99.13** |
| `0x5552ee1eb4` (Jan 21) | 2× USDC SELL → ETH BUY | $0.05 | $20.00 | **$19.95** |
| `0xfcfc81d19b` (Jan 29) | 2× USDC SELL → ETH BUY | $0.01 | $3.00 | **$2.99** |
| `0x4ce9ca5507` (Jan 22) | 2× LINEA SELL → ETH BUY | $0.001 | $0.235 | $0.23 |

**Not affected** (guard fires correctly): `0x42742709be` has WETH SELL + ETH BUY + ETH SELL.
`hasMultipleSameCanonicalFlows` detects that ETH/WETH (canonical equivalents) appear on both BUY
and SELL sides → returns empty → no derivation. The $0.03 delta is not missed.

**Root cause**: `SwapDerivedPriceResolver.resolve()` loops through siblings and **returns on the
first sibling** with a resolved price. For `ETH BUY ← 2× USDC SELL`, it finds `USDC[0]`
(value $0.05) and derives `price = $0.05 / 0.006908 ETH = $7.24/ETH` instead of the correct
`$20.00 / 0.006908 = $2,895/ETH`.

**Existing guard** (`hasMultipleSameCanonicalFlows`) fires only when the *current* flow's canonical
symbol appears multiple times — not when a counterpart SELL symbol repeats. So the guard does not
help for homogeneous multi-SELL patterns (2× USDC, 2× USDBC, 2× LINEA).

**Stage**: `pricing` — no re-normalization needed; requires `--clear-pricing-cache`.

---

### B2 — `LP_EXIT_SETTLEMENT` WETH inflows unpriced

**Evidence** (1 event, 2026-02-06 ARBITRUM):

| Flow | qty | unitPriceUsd | basis effect |
|------|-----|-------------|--------------|
| WETH inflow #1 | +0.0800 | null | UNKNOWN |
| WETH inflow #2 | +0.0805 | null | UNKNOWN |
| ETH fee | -0.0001 | null | — |

Estimated missed basis: **~$422** at current AVCO $2,631.

**Root cause**: `PriceableFlowPolicy.requiresMarketPrice()` does not include `TRANSFER` inflows
for `LP_EXIT_SETTLEMENT` in its pricing gate. The same pattern was previously fixed for
`LENDING_LOOP_DECREASE`/`LENDING_LOOP_CLOSE` in the prior round.

**Stage**: `pricing` — same fix pattern; requires `--clear-pricing-cache`.

---

### B3 — `LP_ENTRY_SETTLEMENT` ETH fee refunds unpriced

**Evidence** (5 events, ETH keeper refunds ~$0.46 total):

Each `LP_ENTRY_SETTLEMENT` carries TWO TRANSFER inflows: a tiny ETH keeper refund (~$0.01–$0.14)
AND a large GMX GM/GLV LP token (`GM: ETH/USD [WETH-USDC]`, `GLV [WETH-USDC]`) with no market price.

**Decision: B3 DEFERRED**. Adding `LP_ENTRY_SETTLEMENT` to the pricing gate would also attempt
to price GM/GLV LP tokens, which have no external market data → pricing fails or creates incorrect
basis effects. The $0.46 total impact does not justify the risk. B3 is explicitly out of scope
for this plan. A dedicated follow-up plan with asset-symbol scoping would be needed to address it.

---

### B4 — PancakeSwap V3 CAKE-harvest phantom receipt burn

**Affected positions** (BASE, PancakeSwap V3):

| Position | Entries | LP_EXIT events | Real closes | UNKNOWN exits |
|----------|---------|----------------|-------------|---------------|
| 445831 | 1 | 2 | 1 | **1 (WETH $58)** |
| 445752 | 1 | 2 | 1 | 1 (USDC $636) |
| 448475 | 2 | 3 | 1 | 1 (USDC $1,770) |
| 450450 | 1 | 4 | 1 | 3 (USDC $2,110) |
| 477096 | 2 | 4 | 1 | 2 (USDC $24.5) |
| 938761 | 2 | 2 | 1 | 1 (USDC $2.6) |
| 445752 (BSC) | 1 | 2 | 1 | 1 |

**Protocol mechanics**: PancakeSwap V3 positions are NFTs. "Collect rewards" is a separate
transaction that calls `collect` (or a multicall with `decreaseLiquidity(liquidity=0)` + `collect`).
This returns only CAKE (the LP commission token) — no underlying principal moves.

**Classification error**: The collector uses the `MULTICALL_SELECTOR` path in
`LpPositionLifecycleSupport.resolvePositionManagerMulticallType()`. Because `decreaseLiquidity`
appears in the multicall calldata, it takes the first branch:
```java
if (decreaseLiquidity && hasInboundNonFeeLeg(movementLegs)) {
    return NormalizedTransactionType.LP_EXIT;  // ← wrong for liquidity=0 decrease
}
```
This yields `LP_EXIT`. Then `LpPrincipalCloseEvidence.refineLifecycleType()` checks
`hasPositionReductionEvidence(view)` which returns `true` because `DECREASE_LIQUIDITY_SELECTOR`
is in the calldata — so the type is NOT downgraded to `LP_FEE_CLAIM`. Finally,
`LpNftClFlowMaterializer.enrichPrincipalExit()` adds `LP-RECEIPT:-1` to the flow.

**Replay consequence**:

1. CAKE-harvest LP_EXIT drains LP basis pool (basis → CAKE REALLOCATE_IN)  
2. `drainMaterializedReceiptMarker` called → `recordLpReceiptPrincipalExitEvent` → lifecycle closed  
3. Subsequent real LP_EXIT (WETH+USDC return) finds lifecycle closed → all inflows → **UNKNOWN**

**Missing basis for ETH-family**: 0.022 WETH ($58) from position 445831 is the only direct
ETH-family impact. However, USDC basis loss across 5+ positions distorts portfolio P&L tracking.

**Root cause method**: `LpPrincipalCloseEvidence.refineLifecycleType()` does not call
`isHarvestOnlyRewardPattern(movementLegs)` when `hasPositionReductionEvidence` returns true.

**Stage**: `classification` — requires re-normalization of affected transactions.

---

## 3. Required Changes (Upstream First)

### P0 — Fix `SwapDerivedPriceResolver`: sum ALL counterpart SELL flows (B1)

**File**: `backend/src/main/java/com/walletradar/pricing/resolver/event/SwapDerivedPriceResolver.java`

**Current logic** (line 38–63): loops siblings, returns on the first sibling with a resolved price.

**Fix**: Accumulate total value from all same-direction counterpart siblings before dividing:

```java
@Override
public Optional<PriceQuote> resolve(PriceResolutionContext context) {
    if (context.transaction().getType() != NormalizedTransactionType.SWAP
            || context.flow().getRole() == NormalizedLegRole.FEE
            || context.flow().getQuantityDelta() == null
            || context.flow().getQuantityDelta().signum() == 0) {
        return Optional.empty();
    }
    if (hasMultipleSameCanonicalFlows(context)) {
        return Optional.empty();
    }

    BigDecimal totalCounterpartValue = BigDecimal.ZERO;
    PriceQuote firstQuote = null;

    for (int siblingIndex = 0; siblingIndex < context.flows().size(); siblingIndex++) {
        if (siblingIndex == context.flowIndex()) continue;
        NormalizedTransaction.Flow sibling = context.flows().get(siblingIndex);
        if (sibling == null
                || sibling.getRole() == NormalizedLegRole.FEE
                || sibling.getQuantityDelta() == null
                || sibling.getQuantityDelta().signum() == 0) {
            continue;
        }
        // Only accumulate flows that are in the opposite direction
        if (!isCounterpartRole(context.flow().getRole(), sibling.getRole())) {
            continue;
        }
        Optional<PriceQuote> siblingQuote = context.resolvedQuote(siblingIndex);
        if (siblingQuote.isEmpty()) continue;
        BigDecimal siblingValue = sibling.getQuantityDelta().abs()
                .multiply(siblingQuote.orElseThrow().unitPriceUsd());
        totalCounterpartValue = totalCounterpartValue.add(siblingValue);
        if (firstQuote == null) {
            firstQuote = siblingQuote.orElseThrow();
        }
    }

    if (firstQuote == null || totalCounterpartValue.signum() == 0) {
        return Optional.empty();
    }
    BigDecimal derivedPrice = totalCounterpartValue
            .divide(context.flow().getQuantityDelta().abs(), DIVISION_CONTEXT);
    return Optional.of(new PriceQuote(
            derivedPrice,
            PriceSource.SWAP_DERIVED,
            context.transaction().getBlockTimestamp(),
            firstQuote.quoteSymbol(),
            "swap-derived-multi:" + contributingCount
    ));
}

private static boolean isCounterpartRole(NormalizedLegRole current, NormalizedLegRole sibling) {
    if (current == NormalizedLegRole.BUY)  return sibling == NormalizedLegRole.SELL;
    if (current == NormalizedLegRole.SELL) return sibling == NormalizedLegRole.BUY;
    // TRANSFER-role: accumulate siblings moving in the OPPOSITE direction (different sign)
    // Using quantityDelta sign parity — see caller for context access
    return sibling != NormalizedLegRole.FEE;
    // NOTE: For TRANSFER flows, the caller must additionally check that
    // sibling.getQuantityDelta().signum() != context.flow().getQuantityDelta().signum()
    // to avoid accumulating same-direction TRANSFER siblings.
}
```

**Key invariant preserved**: `hasMultipleSameCanonicalFlows` guard still fires when the *current*
flow's symbol appears on multiple sides (prevents circular derivation).

**Requires**: `--clear-pricing-cache` on rebuild.

---

### P1 — Fix `LpPrincipalCloseEvidence`: harvest-only gate with zero-liquidity guard (B4)

**File**: `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/support/LpPrincipalCloseEvidence.java`

**Critical false-positive**: Real CAKE-only LP exits exist (positions 646414, 472497 — CL pools
drifted 100% to CAKE range, using `decreaseLiquidity(liquidity>0)` + `burn`). The guard must NOT
fire for these; only for harvest-only collects with `decreaseLiquidity(liquidity=0)`.

**Fix — step 1**: Add `hasZeroLiquidityDecrease` to `refineLifecycleType`:
```java
public static NormalizedTransactionType refineLifecycleType(
        OnChainRawTransactionView view,
        List<RawLeg> movementLegs,
        NormalizedTransactionType type
) {
    if (type == null || !isPrincipalExitCandidate(type)) {
        return type;
    }
    if (!hasPositionReductionEvidence(view)) {
        return NormalizedTransactionType.LP_FEE_CLAIM;
    }
    // Only downgrade when liquidity=0 in the embedded decreaseLiquidity call
    // (no-op position reduction) AND all inflows are fee-only.
    // Prevents false-positives on genuine CAKE-only exits from out-of-range pools.
    if (isHarvestOnlyRewardPattern(movementLegs) && hasZeroLiquidityDecrease(view)) {
        return NormalizedTransactionType.LP_FEE_CLAIM;
    }
    return type;
}
```

**Fix — step 2**: Add new `hasZeroLiquidityDecrease(OnChainRawTransactionView view)` private method.

Decodes the `liquidity` argument from an embedded `decreaseLiquidity` calldata. The struct layout
immediately after the 4-byte selector (`0x0c49ccbe`) is:
```
bytes  0–31: tokenId   (uint256 = 64 hex chars)
bytes 32–63: liquidity (uint128, zero-padded to 32 bytes = 64 hex chars)
```

```java
private static boolean hasZeroLiquidityDecrease(OnChainRawTransactionView view) {
    if (view == null) return false;
    String inputData = view.inputData();
    if (inputData == null || inputData.isBlank()) return false;
    String data = inputData.startsWith("0x") ? inputData.substring(2) : inputData;
    String selectorHex = DECREASE_LIQUIDITY_SELECTOR.startsWith("0x")
            ? DECREASE_LIQUIDITY_SELECTOR.substring(2) : DECREASE_LIQUIDITY_SELECTOR;
    int idx = data.indexOf(selectorHex);
    if (idx < 0) return false;
    int liquidityStart = idx + 8 + 64; // skip 4-byte selector + tokenId
    int liquidityEnd   = liquidityStart + 64;
    if (data.length() < liquidityEnd) return false;
    return data.substring(liquidityStart, liquidityEnd).matches("0{64}");
}
```

**Effect**: Transactions with `decreaseLiquidity(0)` + CAKE-only inflows → `LP_FEE_CLAIM`.
Transactions with `decreaseLiquidity(N>0)` + CAKE-only inflows (real exits) → stay `LP_EXIT`.

**Requires**: Full re-normalization (pipeline re-run) + `--clear-pricing-cache`.

**Tests**: Add unit tests in `LpNftClFlowMaterializerTest` or create `LpPrincipalCloseEvidenceTest`:
1. Multicall with `decreaseLiquidity(liquidity=0)` in calldata, all inflows CAKE → `LP_FEE_CLAIM`
2. Multicall with `decreaseLiquidity(liquidity>0)` in calldata, CAKE-only inflows → stays `LP_EXIT`
3. `decreaseLiquidity(liquidity=0)` but inflows include WETH + CAKE → stays `LP_EXIT`
4. Direct `burn` selector, CAKE-only inflows → stays `LP_EXIT` (no zero-liquidity decrease)

---

### P2 — Fix `PriceableFlowPolicy`: add LP_EXIT/ENTRY_SETTLEMENT to inbound shortfall spot pricing (B2+B3)

**File**: `backend/src/main/java/com/walletradar/pricing/application/PriceableFlowPolicy.java`

**Current `requiresInboundShortfallSpotPricing`** (line 221–226):
```java
if (type == NormalizedTransactionType.LP_EXIT
        || type == NormalizedTransactionType.LP_EXIT_PARTIAL
        || type == NormalizedTransactionType.LP_EXIT_FINAL
        || type == NormalizedTransactionType.LENDING_WITHDRAW
        || type == NormalizedTransactionType.BRIDGE_IN) {
    return true;
}
```

`LP_EXIT_SETTLEMENT` and `LP_ENTRY_SETTLEMENT` are absent. The existing `isLendingLoopPrincipalInflowType`
gate handles LENDING_LOOP types separately because they're strictly principal inflows, while settlement
types are a broader pattern — they belong in `requiresInboundShortfallSpotPricing`.

**Fix**: Add two types to the existing condition:
```java
if (type == NormalizedTransactionType.LP_EXIT
        || type == NormalizedTransactionType.LP_EXIT_PARTIAL
        || type == NormalizedTransactionType.LP_EXIT_FINAL
        || type == NormalizedTransactionType.LP_EXIT_SETTLEMENT   // ← ADD (B2)
        || type == NormalizedTransactionType.LP_ENTRY_SETTLEMENT  // ← ADD (B3)
        || type == NormalizedTransactionType.LENDING_WITHDRAW
        || type == NormalizedTransactionType.BRIDGE_IN) {
    return true;
}
```

**Note**: No rename of `isLendingLoopPrincipalInflowType` needed. The LENDING_LOOP gate stays
separate (it handles principal inflows, while settlement flows are shortfall spot flows).

**Requires**: `--clear-pricing-cache` on rebuild.

---

## 4. Documentation Updates

| Document | Change |
|----------|--------|
| `docs/03-accounting.md` | Add rule: "SWAP with multiple SELL flows: the derived price for the BUY leg is computed from the SUM of all SELL flow values, not just the first one." |
| `docs/03-accounting.md` | Add rule: "PancakeSwap V3 CAKE-harvest multicalls that contain `decreaseLiquidity(liquidity=0)` are classified as `LP_FEE_CLAIM`, not `LP_EXIT`, when all inflows are fee-only." |
| `docs/adr/ADR-021-swap-multi-sell-price-derivation.md` | **NEW ADR** — documents multi-leg SWAP price derivation policy. |
| `docs/adr/INDEX.md` | Add ADR-021 entry. |

**No ADR needed for B2/B3** — same pattern already documented via LENDING_LOOP precedent.

---

## 5. Acceptance Criteria

| ID | Criterion | Current → Target |
|----|-----------|-----------------|
| A1 | `fullSessionCurrent.avcoUsd` for FAMILY:ETH ≥ $2,650 | $2,631 → ≥ $2,650 |
| A2 | tx `0x4b1646937d` (WETH BUY): `unitPriceUsd` for WETH flow ≥ $3,500 | $9.52 → ≥ $3,500 |
| A3 | tx `0x5552ee1eb4` (WETH BUY): `unitPriceUsd` for WETH flow ≥ $3,000 | ~$3 → ≥ $3,000 |
| A4 | LP_EXIT_SETTLEMENT (Feb 06 2026): WETH inflows → `basisEffect = REALLOCATE_IN` (not UNKNOWN) | UNKNOWN → REALLOCATE_IN |
| A5 | LP_EXIT_SETTLEMENT (Feb 06 2026): WETH `unitPriceUsd` ≠ null | null → priced |
| A6 | PancakeSwap position 477096: all 4 LP_EXIT events → `basisEffect = REALLOCATE_IN` | 2 UNKNOWN → REALLOCATE_IN |
| A7 | PancakeSwap position 445831 (Jul 19 2025): WETH LP_EXIT → `basisEffect = REALLOCATE_IN` | UNKNOWN → REALLOCATE_IN |
| A8 | PancakeSwap positions 445752/448475/450450/938761/938761 (BSC 643922): no `basisEffect = UNKNOWN` on principal inflows | present → absent |
| A12 | B3 explicitly deferred: `LP_ENTRY_SETTLEMENT` ETH refunds ($0.46) remain UNKNOWN — documented as known limitation | N/A |
| A9 | `./gradlew :backend:test` BUILD SUCCESSFUL | pass |
| A10 | Regression: WETH BUY in simple 1-SELL:1-BUY SWAP — `unitPriceUsd` unchanged | no regression |
| A11 | `LP_FEE_CLAIM` classification for CAKE-only PancakeSwap V3 collects — verified in `normalized_transactions` | new |

---

## 6. Risks

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| `isCounterpartRole` incorrectly accumulates TRANSFER siblings in SWAP context | LOW | `hasMultipleSameCanonicalFlows` guard still prevents self-referential derivation; TRANSFER flows in SWAPs are rare and have no `SELL` opposite |
| `isHarvestOnlyRewardPattern` too broad — misclassifies legitimate LP_EXIT returning CAKE principal | LOW | Pattern requires ALL inflows to be CAKE or dust-stablecoin; mixed WETH+CAKE exits are not harvest-only |
| B4 reclassification changes correlationId or misroutes downstream clarification steps | LOW | `correlationId` is set from position NFT ID before type refinement; `refineLifecycleType` does not touch correlationId |
| `LP_ENTRY_SETTLEMENT` TRANSFER pricing breaks non-ETH assets (e.g., stablecoin refunds) | LOW | `requiresMarketPrice` only applies when `quantityDelta.signum() > 0`; stablecoins already have market price; resolver falls back to catalog if no historical price found |
| Re-normalization changes ordering of PancakeSwap LP transactions | LOW | Normalization is deterministic; only the type changes for CAKE-harvest transactions |

---

## 7. Implementation Order

1. **P0** — `SwapDerivedPriceResolver` (no re-normalization, just cache clear)
2. **P1** — `LpPrincipalCloseEvidence.refineLifecycleType` (classification → requires full re-normalization)
3. **P2** — `PriceableFlowPolicy` B2+B3 extension (same rebuild as P1, just add cache clear flag)

All three can be implemented in a single `backend-dev` delegation and verified in one `--clear-pricing-cache` rebuild.

---

## 8. Verification Queries

```javascript
// A2 — SWAP multi-sell WETH price corrected
db.normalized_transactions.findOne(
  { txHash: /0x4b1646937d/i, walletAddress: /0x1a87f12/i },
  { "flows.assetSymbol": 1, "flows.unitPriceUsd": 1, "flows.role": 1 }
)

// A4 — LP_EXIT_SETTLEMENT WETH basis
db.asset_ledger_points.find(
  { walletAddress: /0x1a87f12/i, assetSymbol: "WETH",
    blockTimestamp: { $gte: new Date("2026-02-06T07:25:00Z"), $lte: new Date("2026-02-06T07:30:00Z") } },
  { basisEffect: 1, avcoAfterUsd: 1, costBasisDeltaUsd: 1 }
)

// A6 — position 477096 all exits
db.asset_ledger_points.find(
  { walletAddress: /0x1a87f12/i, assetSymbol: "USDC", networkId: "BASE",
    blockTimestamp: { $gte: new Date("2025-09-01"), $lte: new Date("2025-11-18") } },
  { blockTimestamp: 1, basisEffect: 1, quantityDelta: 1 }
).sort({ blockTimestamp: 1 })

// A7 — position 445831 WETH LP_EXIT
db.asset_ledger_points.find(
  { walletAddress: /0x1a87f12/i, assetSymbol: "WETH",
    blockTimestamp: { $gte: new Date("2025-07-19T10:15:00Z"), $lte: new Date("2025-07-19T10:18:00Z") } },
  { basisEffect: 1, costBasisDeltaUsd: 1, avcoAfterUsd: 1 }
)

// A11 — confirm CAKE-harvest reclassified as LP_FEE_CLAIM
db.normalized_transactions.find(
  { walletAddress: /0x1a87f12/i, correlationId: /lp-position:base:pancakeswap/,
    type: "LP_FEE_CLAIM" }
).count()
// Should be > 0 after fix (was 0 before)

// A1 — ETH-family AVCO
db.asset_ledger_points.find(
  { walletAddress: /0x1a87f12/i, assetFamily: "ETH" }
).sort({ blockTimestamp: -1 }).limit(1)
```

---

## 9. Review Log

- [x] `financial-logic-auditor`: REQUEST_CHANGES → B1 evidence corrected (`0x42742709be` removed, WETH/ETH guard verified); B3 deferred (GM/GLV risk); B4 false-positive documented → v2 addresses all blockers
- [x] `business-analyst`: REQUEST_CHANGES → A8 includes BSC position; A12 added for deferred B3; user impact note added
- [x] `system-architect`: REQUEST_CHANGES → `hasZeroLiquidityDecrease` guard added to P1; `isCounterpartRole` TRANSFER direction fix added; audit label includes contributing count
- [x] User approved ("продолжай") → proceeding to Phase 5 implementation (v2 plan)
