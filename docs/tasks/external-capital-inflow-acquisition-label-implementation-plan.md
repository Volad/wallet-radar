# Implementation Plan — External-capital inflow ACQUIRE label (NEW-02)

## Scope

- **Blocker:** NEW-02 (MEDIUM, label-only — no AVCO/cost-basis distortion) — Dzengi fiat
  `EXTERNAL_TRANSFER_IN` deposits are recorded with `basisEffect=UNKNOWN` instead of `ACQUIRE`.
- **Wallet:** `DZENGI:1023141508`. **Assets:** USD, BYN. **Points:** 16, ≈ $11,503 notional.
- **Out of scope:** any change to AVCO/cost-basis numbers (already correct), crypto continuity carries,
  pegged-native spot fallback (CMETH/METH/etc.), Bybit external deposits (already ACQUIRE/carry).

## Root cause

`DzengiCanonicalTransactionBuilder.buildTransfer` emits the inbound leg as `NormalizedLegRole.TRANSFER`
with `externalCapitalBoundary=INFLOW` (stamped by `CexBoundaryContractStamper`). At replay, with no
pending/continuity match, `TransferReplayHandler.applyTransfer` falls to
`applyUnknownTransfer` and returns `UNKNOWN`. The dispatcher then runs
`applyInboundShortfallSpotFallback`, which assigns the correct market/spot basis (USD peg for USD,
`HISTORICAL_CACHE` BYN→USD for BYN) — so **AVCO is correct** — but the recorded `basisEffect` stays
`UNKNOWN`. Semantically an unpaired external-capital `INFLOW` booked at market value **is an
acquisition**, so the label should be `ACQUIRE`.

DB confirmation (active universe): the ONLY `EXTERNAL_TRANSFER_IN` inbound `UNKNOWN` points are USD/BYN
(Dzengi). There are **no** crypto `EXTERNAL_TRANSFER_IN` `UNKNOWN` points, so the corrected scope
affects exactly these fiat acquisitions and nothing else.

## Change (single, label-only)

File: `backend/core/src/main/java/com/walletradar/application/costbasis/application/replay/dispatch/ReplayDispatcher.java`

Add a private helper `resolveExternalCapitalInflowAcquisition(transaction, flow, position, currentEffect)`
and apply it to the recorded `basisEffect` **after** `applyInboundShortfallSpotFallback` in the two
inbound TRANSFER record sites (the continuity-transfer path and the non-continuity TRANSFER path).

The helper returns `ACQUIRE` iff ALL hold, else returns `currentEffect` unchanged:
- `currentEffect == UNKNOWN` (never downgrade a resolved carry/dispose), AND
- `transaction.getType() == EXTERNAL_TRANSFER_IN`, AND
- `transaction.getExternalCapitalBoundary() == ExternalCapitalBoundary.INFLOW`, AND
- inbound flow (`quantityDelta > 0`), AND
- position fully basis-backed after fallback (`position.uncoveredQuantity()` is null/zero) — i.e. the
  spot fallback actually priced the external capital. If it could not be priced (uncovered remains),
  keep `UNKNOWN` (never claim an acquisition without basis).

No change to quantities, cost basis, net lane, or AVCO — only the emitted `basisEffect` label.

## Docs

- `docs/reference/ledger-points-and-basis-effects.md`: note that external-capital `INFLOW` deposits
  priced via spot fallback are labelled `ACQUIRE` (not `UNKNOWN`).

## Acceptance

After `./scripts/prod-reset-rebuild-backend.sh --skip-frontend`:
1. All 16 Dzengi USD/BYN `EXTERNAL_TRANSFER_IN` inbound points → `basisEffect=ACQUIRE`;
   `avcoAfterUsd` / `totalCostBasisAfterUsd` **unchanged** vs pre-fix.
2. Zero `EXTERNAL_TRANSFER_IN` inbound `UNKNOWN` points remain.
3. No other basisEffect changes (LP_EXIT/INTERNAL_TRANSFER UNKNOWN counts unchanged); no AVCO deltas
   anywhere else.
4. Unit tests in `ReplayDispatcher` test scope: EXTERNAL_TRANSFER_IN+INFLOW+priced → ACQUIRE;
   EXTERNAL_TRANSFER_IN+INFLOW+unpriced (uncovered>0) → UNKNOWN; non-EXTERNAL_TRANSFER_IN UNKNOWN
   transfer → unchanged; outbound → unchanged.
5. `financial-logic-auditor` re-run: NEW-02 closed; no new Medium+ defect; no regression.

## Risks

- **Over-broad relabel:** mitigated by the four-way gate (type + boundary + inbound + fully-covered) and
  by the DB fact that only fiat currently qualifies.
- **Determinism:** pure post-hoc label resolution over already-computed position state; no ordering or
  numeric impact.
