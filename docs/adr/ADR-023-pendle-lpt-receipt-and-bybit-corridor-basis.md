# ADR-023 — Pendle LP Token Receipt Detection and Bybit Corridor Spot-Price Basis

**Status**: Accepted  
**Date**: 2026-05-30  
**Theme**: S-4 (Mantle cmETH Pendle LP) + P-B (Bybit CEX corridor basis)

---

## Context

### S-4: Pendle LP positions on Mantle produced inflated AVCO

Pendle LP_ENTRY transactions use the `pendle-lp:NETWORK:MARKET` correlationId (produced by
`PendleLpCorrelationSupport`). The replay dispatcher routed these to the `GENERIC` handler
because `LpReceiptEntryReplayHandler.isLpReceiptEntry()` only accepted `lp-position:` prefixed
correlationIds. As a result:

1. No `lp_receipt_basis_pools` record was ever created for the deposited cmETH.
2. On LP_EXIT, `PositionScopedLpExitReplayHandler` fell back to spot-price as a cost estimate
   (e.g. $3,778 for the 2025-09-10 Mantle cmETH exit) instead of the actual acquisition cost
   (~$3,158 from the 2025-08-01 SWAP).

Additionally, Equilibria-wrapped Pendle LP tokens (`eqbPENDLE-LPT`) have a different symbol
from the original (`PENDLE-LPT`), causing `PendleLpCorrelationSupport.marketIdFromSymbol()` to
produce `eqbpendle-lpt` instead of `pendle-lpt`. This prevented LP_EXIT transactions via
Equilibria from matching the LP receipt pool created at LP_ENTRY.

LP_EXIT transactions lacking a `correlationId` (empty string) were routed to `GENERIC` by
`PositionScopedLpExitReplayHandler.isPositionScopedLpExit()` (which requires a non-blank corrId).
The Equilibria LP_EXIT and the early Pendle-direct LP_EXIT on Mantle both had empty corrId.

### P-B: Bybit corridor CARRY_IN received zero basis

On-chain `INTERNAL_TRANSFER` transactions with `BYBIT-CORRIDOR:NETWORK:txHash` correlationIds
represent ETH/asset withdrawals from Bybit exchange to user wallets. Bybit is a CEX — there is
no on-chain CARRY_OUT that the pending-transfer mechanism could match. When the inbound transfer
was processed by `TransferReplayHandler`, it queued a pending inbound that was never resolved,
leaving the position's cost basis at zero.

---

## Decisions

### D1 — Extend `LpReceiptEntryReplayHandler` to accept `pendle-lp:` correlationIds

`isLpReceiptEntry()` now accepts both `lp-position:` and `pendle-lp:` prefixes. This routes
Pendle LP_ENTRYs through the receipt-pool path, creating a proper `lp_receipt_basis_pools`
record keyed by the Pendle market correlationId.

### D2 — Recognize Pendle LP tokens as LP receipt markers

`isLpReceiptMarker()` in `LpReceiptEntryReplayHandler` was extended to treat tokens matching
`sym.endsWith("-LPT") && (sym.contains("PENDLE") || sym.startsWith("EQB"))` as LP receipt
markers (not principal assets). This ensures `hasOnlyOutboundPrincipalFlows()` returns `true`
for cmETH-out + PENDLE-LPT-in LP_ENTRY shapes, enabling receipt-pool routing.

No cross-layer import was introduced; the detection is an inline predicate in the replay handler.

### D3 — Strip Equilibria prefix in `PendleLpCorrelationSupport.marketIdFromSymbol()`

The `EQB` staking-wrapper prefix is stripped before the Pendle detection and slugification.
Result: `eqbPENDLE-LPT` → `pendle-lpt` (same slug as `PENDLE-LPT`). Both LP_ENTRY and LP_EXIT
tokens now produce the same `pendle-lp:NETWORK:pendle-lpt` correlationId, enabling receipt-pool
lookup at LP_EXIT.

### D4 — Fallback correlationId derivation in `LpSemanticClassifier`

When a classified LP_ENTRY or LP_EXIT has no correlationId from the protocol registry (e.g.,
Equilibria is not registered), `PendleLpCorrelationSupport.correlationIdFromMovementLegs()` is
called as a fallback. Combined with D3, this assigns `pendle-lp:mantle:pendle-lpt` to Equilibria
LP_EXIT transactions with `eqbPENDLE-LPT` flows.

### D5 — BYBIT-CORRIDOR inbound: immediate spot-price acquisition

`ReplayTransferClassifier.isBybitCexCorridor()` identifies transfers with `BYBIT-CORRIDOR:`
correlationIds. In `TransferReplayHandler.applyTransfer()`, inbound BYBIT-CORRIDOR flows that
find no matching carry in the pending-transfer queue bypass the pending mechanism and immediately
apply spot-price acquisition (`BasisEffect.ACQUIRE`). This is consistent with the treatment of
`SPONSORED_GAS_IN` and `REWARD_CLAIM` transactions (both use spot price).

---

## Consequences

- Pendle LP_ENTRY on Mantle (6 transactions, 2025-04-18 to 2025-08-01) will create receipt
  pools on the next full rebuild.
- LP_EXIT on 2025-09-10 via Equilibria: basis changes from $3,778 (inflated spot) to ~$3,158
  (actual cmETH acquisition cost from SWAP on 2025-08-01).
- LP_EXIT on 2025-06-17 (direct PENDLE-LPT exit): corrId assigned → receipt-pool lookup.
- LP_EXIT on 2025-04-18 (PENDLE-LPT exit, early): same treatment.
- 5 Bybit corridor CARRY_IN events (ARBITRUM + MANTLE) change from 0 basis to spot-price
  ACQUIRE, eliminating anomalous AVCO drops visible in the graph.
- No Arbitrum regression risk: zero Pendle LP positions exist outside MANTLE (confirmed via
  `lp_receipt_basis_pools` and `normalized_transactions` queries).

---

## Affected files

| File | Change |
|------|--------|
| `PendleLpCorrelationSupport.java` | D3: strip EQB prefix in `marketIdFromSymbol()` |
| `LpReceiptEntryReplayHandler.java` | D1: `pendle-lp:` prefix in `isLpReceiptEntry()`; D2: `isLpReceiptMarker()` extension |
| `LpSemanticClassifier.java` | D4: fallback `correlationIdFromMovementLegs()` call |
| `ReplayTransferClassifier.java` | D5: `isBybitCexCorridor()` method |
| `TransferReplayHandler.java` | D5: spot-price acquisition for BYBIT-CORRIDOR inbound |
