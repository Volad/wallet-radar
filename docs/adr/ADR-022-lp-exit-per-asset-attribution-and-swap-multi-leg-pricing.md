# ADR-022 — LP_EXIT Per-Asset Cost Attribution and SWAP Multi-Same-Direction-Leg Pricing

**Status**: Accepted  
**Date**: 2026-05-30  
**Theme**: ETH AVCO accuracy — LP exit basis attribution, SWAP multi-leg pricing  
**Supersedes**: none  
**Related**: ADR-021 (SWAP multi-sell price derivation, LP harvest gate)

---

## Context

Two distinct accounting defects were identified after the ADR-021 fixes:

### S-1 — Multi-asset CL LP exit AVCO spike

Concentrated-liquidity LP exits that return **two assets simultaneously** (e.g., WETH + USDC on
position 445831, BASE, PancakeSwap V3) were misattributing USDC cost basis to the WETH bucket via
the cross-pool proportion drain in `PositionScopedLpExitReplayHandler.restoreInboundFromLpReceiptPool()`.

The cross-pool logic was designed for **one-sided exits** (price out of range: all liquidity
converts to a single token, so the other token's receipt pool has unused cost that should carry
forward). However, when both tokens are returned simultaneously, the drain incorrectly attributed
USDC pool cost to the WETH position, causing ETH AVCO to spike to **$23,372** (≈ 7× market rate
of ~$3,220).

### B-NEW-1 — Aggregator SWAP with split output legs

KyberSwap, 1inch, and similar aggregators sometimes route a SWAP such that the output token
arrives via **two separate BUY transfer legs** sharing the same exact symbol (e.g., two BUY legs
of `aArbWBTC`). The original `hasMultipleSameCanonicalFlows` guard in `SwapDerivedPriceResolver`
fired for any flow whose symbol appeared more than once among same-direction flows — including
these legitimate split-output cases — and bailed from price derivation entirely. This caused the
transaction `0xdef59c37` (`aArbWBTC` on Arbitrum) to remain `PRICE_UNKNOWN`, blocking $74,078/token
correct pricing.

Note: the guard was intentionally conservative to prevent circular derivation (e.g., ETH BUY
priced against ETH SELL in a wash-trade or round-trip scenario). The fix must preserve that
protection while enabling the split-leg case.

---

## Decision

### S-1 — LP_EXIT per-asset basis attribution

`PositionScopedLpExitReplayHandler.restoreInboundFromLpReceiptPool()` now collects the asset
identities of **all other inbound `TRANSFER` flows** present in the same transaction before
executing the cross-pool loop. Any cross-pool entry whose `assetIdentity` appears in that set
is skipped.

Effect:
- For **two-asset exits** (WETH + USDC returned in one tx): the USDC cross-pool is suppressed
  when iterating from WETH's perspective, and vice versa. Each asset draws basis only from its
  own receipt pool.
- For **one-sided exits** (only one token returned): the other asset's cross-pool drain proceeds
  unchanged, since that asset is not in the direct-return set.

This preserves the original one-sided-exit recovery intent while eliminating cross-contamination
in simultaneous multi-asset returns.

**Affected class**: `PositionScopedLpExitReplayHandler.java`

### B-NEW-1 — SWAP multi-same-direction-leg pricing

`SwapDerivedPriceResolver` replaces `hasMultipleSameCanonicalFlows` with two targeted methods:

- **`hasCounterpartSameCanonicalFlow`**: fires `true` only when a *counterpart-role* sibling
  (opposite BUY/SELL direction) shares the same canonical symbol as the flow being priced.
  This is the circular/wash-trade guard and is preserved without change in semantics.

- **`computeTotalSameDirectionQty`**: when the flow being priced has same-direction siblings
  that share the **same exact symbol** (not just the same canonical family), aggregates their
  quantities into a combined denominator. Each matching leg receives the same derived unit
  price: `totalCounterpartValue / totalSameDirectionQty`.

Exact-symbol matching (not canonical-family matching) prevents merging quantities of
distinct wrapped tokens that happen to share a family (e.g., `aArbWBTC` vs `WBTC`).

**Affected class**: `SwapDerivedPriceResolver.java`

---

## Consequences

### Positive

- ETH AVCO spike at LP position 445831 exit eliminated: $23,372 → ~$3,220 (market rate).
- `aArbWBTC` SWAP `0xdef59c37` priced correctly at $74,078/token.
- Circular derivation guard (ETH BUY vs ETH SELL wash-trade protection) fully preserved.
- One-sided LP exits continue to drain cross-pool basis unchanged.

### Risks and mitigations

| Risk | Mitigation |
|------|-----------|
| A future LP exit returns asset X twice (e.g., two WETH inbound legs from separate pool hops in one tx) — one leg suppressed | Highly unlikely in CL position design; if it occurs, same-asset deduplication at the flow materialization layer handles it before replay |
| Aggregator split output with different wrapped tokens (e.g., `WBTC` + `cbBTC`) accidentally merged by `computeTotalSameDirectionQty` | Exact-symbol matching prevents this; `WBTC ≠ cbBTC` |
| `hasCounterpartSameCanonicalFlow` too narrow — misses a circular case where canonical families differ | Canonical guard targets provably circular cases (same token on both sides); a family-level guard would be overly conservative and was already shown to block legitimate aggregator routes |

---

---

## Amendment: P-C — LP_ENTRY Net-by-Asset Shape Detection (2026-05-30)

**Status**: Accepted (amended)

### Problem

`LpReceiptEntryReplayHandler.hasOnlyOutboundPrincipalFlows()` evaluated each TRANSFER flow independently.
Uniswap V3/V4 routers frequently return excess deposited tokens (a "refund") in the same transaction.
A small positive inbound flow of a deposited asset caused the method to set `hasInboundPrincipal = true`
and return `false`, routing the LP_ENTRY through the async lifecycle bucket instead of the receipt-pool path.

Result: `lp_receipt_basis_pools` was never created for these positions → LP_EXIT found no pool →
`basisEffect = UNKNOWN` → $0 cost basis returned (total missed basis: **$6,214.69** across 14 positions
on ETHEREUM, ARBITRUM, and UNICHAIN).

### Decision

Change `hasOnlyOutboundPrincipalFlows()` to aggregate **net flow per asset symbol** instead of checking
each flow individually. A Uniswap router refund reduces the outbound amount but keeps the net negative.
Curve/Balancer shapes (which return a DIFFERENT asset entirely) produce net positive flows for that asset
and are still correctly rejected.

### Consequences

- 13/14 LP_EXIT UNKNOWN events resolved to `REALLOCATE_IN` with correct basis
- The remaining 1 (`lp-position:unichain:uniswap:44472`, $163 ETH) is a data-quality issue:
  the original LP_ENTRY for this position is missing from the database (only a small add-liquidity
  was captured), which is not addressable by code change
- The refund flow is correctly handled inside `apply()` via `applyInboundReceipt()`,
  which withdraws the refunded amount from the pool after the outbound is deposited

### Affected files

- `LpReceiptEntryReplayHandler.java` — `hasOnlyOutboundPrincipalFlows()` (net-by-asset logic)
- `LpReceiptEntryReplayHandlerTest.java` — T1–T5 tests (refund, dust refund, standard, Curve/Balancer, refund-exceeds-deposit)

---

## Affected files

- `PositionScopedLpExitReplayHandler.java` — `restoreInboundFromLpReceiptPool()` direct-return suppression
- `SwapDerivedPriceResolver.java` — `hasCounterpartSameCanonicalFlow` + `computeTotalSameDirectionQty`
- `LpReceiptEntryReplayHandler.java` — `hasOnlyOutboundPrincipalFlows()` (net-by-asset, P-C fix)

---

## References

- ADR-021: `docs/adr/ADR-021-swap-multi-sell-price-derivation-and-lp-harvest-gate.md`
- Accounting policy: `docs/03-accounting.md` §6 (Pricing Policy), LP_EXIT section
- Implementation plan (corridor/LP fixes): `docs/tasks/avco-eth-corridor-collision-fix-plan.md`
- `PositionScopedLpExitReplayHandler` — `backend/src/main/java/com/walletradar/accounting/replay/lp/`
- `SwapDerivedPriceResolver` — `backend/src/main/java/com/walletradar/pricing/resolver/event/`
