# ADR-075: Solana concentrated-liquidity full-close residual write-off

Status: Accepted

Date: 2026-07-20

Related: ADR-063 (Solana Helius normalization), ADR-070 (Solana LP enrichment),
ADR-018 (LP protocol family materialization), ADR-074 (network-agnostic
post-normalization invariant), `docs/pipeline/normalization/rules/families/solana.md`
(RC-S-LP), `docs/pipeline/cost-basis/03-basis-pools-and-carry.md`.

## Context

Solana concentrated-liquidity positions (Meteora DLMM, Raydium CLMM) are NFT-based:
no fungible LP-receipt token is minted to the wallet, so entry↔exit basis continuity
is carried per position through `lp_receipt_basis_pools` keyed by the
`lp-position:solana:<protocol>:<positionAccount>` correlation id
(`LpReceiptEntryReplayHandler` deposits the deposited principal basis on `LP_ENTRY`;
`PositionScopedLpExitReplayHandler` restores it on `LP_EXIT`).

The exit handler restores each returned asset from its own per-asset pool and drains
cross-asset pools **proportionally**. That proportional model assumes each asset is
returned in the same ratio it was deposited. Concentrated-liquidity positions violate
that assumption: when the price moves through the range, the pool returns a **different
asset ratio** than deposited (impermanent loss / rebalancing). On a full close one
asset can drain fully (and overflow into sideflow) while a sibling asset pool retains
a **residual `qtyHeld > 0`** that is never withdrawn by any returned flow.

Because a Solana NFT position has no LP-receipt burn leg, the existing
`hasPrincipalCloseEvidence` gate (which looks for a negative LP-RECEIPT / composite
receipt flow) never fires, so `PositionScopedLpExitReplayHandler` never drained the
residual pools. The residuals surfaced as **phantom "open" LP positions** that inflated
the portfolio and left SOL reported as "uncovered" in move-basis, even though every
position was long closed on-chain.

Observed (accounting universe `df5e69cc-…`, wallet `9Grpx4HK…`): 15 residual pools
> $1 (plus dust) totalling ≈ $787 of stuck basis. Two representative shapes, both
confirmed against `raw_transactions`:

- **Rebalancing residual on a fully-closed CLMM position** —
  `raydium-clmm:C5eFEtrq…`: entry 1.0072 SOL + 186.94 USDC; two exits return 0.5546 SOL
  + 296.04 USDC + 0.0237 RAY. USDC drains fully; the SOL pool keeps
  `1.0072 − 0.5546 = 0.4526 SOL` (= the $89 residual). The terminal exit contains a
  Raydium `closePosition` and the position NFT account `C5eFEtrq` is deallocated
  (rent reclaimed) — the position is fully closed.
- **Large unreturned principal on a fully-closed DLMM position** —
  `meteora-dlmm:BZ6bpJ…`: entry 3231.40 COM + 0.0283 SOL; the single terminal
  `REMOVE_LIQUIDITY_BY_RANGE` returns 131.01 COM + 0.0548 SOL and deallocates the
  position PDA `BZ6bpJ…`. The remaining `3231.40 − 131.01 = 3100.40 COM` (= the $369
  residual) was lost inside the pool to IL over the position's life — there is **no**
  second remove/close tx in `raw_transactions` for this position. (The initial "missing
  exit CPI" hypothesis is not supported by raw evidence; both shapes reduce to the same
  mechanism: residual per-asset basis on a fully-closed position.)

## Decision

Treat a concentrated-liquidity remove that **fully closes the position** as a terminal
`LP_EXIT_FINAL`, and on that terminal exit **drain every residual per-asset basis pool
for the position to zero**, writing the leftover basis off as realized LP PnL. A
**partial** remove leaves residual basis parked until the real close.

Full closure is detected from **deterministic on-chain evidence**, mirroring the EVM
position-NFT-burn signal (`LpPrincipalCloseEvidence.isFullPositionClose`):

- **Signal: the resolved position account is deallocated in the same transaction** —
  it appears in `accountData` with a strictly-negative `nativeBalanceChange` (its rent
  lamports are reclaimed). A Meteora DLMM position PDA / Raydium CLMM position NFT
  account is program-owned and never pays fees, so a negative lamport delta on that
  exact account can only be an account close. A partial remove leaves the position
  account open (`nativeBalanceChange == 0`).

Implementation:

1. `SolanaLpPositionResolver.isFullPositionClose(view)` reuses the existing position
   resolution (Meteora DLMM PDA at `accounts[0]` of the largest liquidity leg; Raydium
   CLMM NFT account at `accounts[1]`/`accounts[3]`) and returns `true` iff that account
   is rent-reclaimed in `accountData`.
2. `SolanaNormalizedTransactionBuilder` promotes a resolved `LP_EXIT` to
   `LP_EXIT_FINAL` when `isFullPositionClose` holds.
3. `PositionScopedLpExitReplayHandler.shouldDrainMaterializedReceiptMarker` returns
   `true` for `LP_EXIT_FINAL`, so the existing `drainAllLpReceiptPoolsForCorrelation`
   zeros `qtyHeld` / `basisHeldUsd` / `netBasisHeldUsd` / `uncoveredQtyHeld` on every
   pool sharing the correlation id (and zeros the synthetic receipt position).

The residual write-off is the realized LP loss/gain: the basis that was tied up in the
position but never returned to the wallet is removed from the books, while the assets
that *did* return were already credited at their restored/spot basis by the settlement
pass.

## Alternatives considered

- **Decode a `closePosition` instruction discriminator.** The flattened Helius
  instructions carry only `programId` + `accounts` (no decoded name/data), so a
  discriminator match is not reliably available. Rent-reclaim on the position account
  is the more robust, already-available signal and covers both protocols uniformly.
- **Always drain on any `LP_EXIT`.** Rejected: it would wrongly zero a legitimately
  **partial** remove where the position stays open and residual basis must persist.
- **Post-hoc sweep/repair job over `lp_receipt_basis_pools`.** Rejected per backend
  standards: fix the earliest wrong stage (normalization classification of the terminal
  exit) and rebuild downstream state from rerun, rather than mutating stored canonical
  rows after the fact.

## Consequences

- No dataset-specific keys: the rule is keyed purely on the position account's on-chain
  rent-reclaim, never on a wallet, tx hash, or curated bucket.
- Determinism / rerun-safety preserved: `isFullPositionClose` reads only the Helius
  payload; no read-path RPC.
- EVM behaviour unchanged: EVM `LP_EXIT_FINAL` already drained via
  `hasPrincipalCloseEvidence`; the added `LP_EXIT_FINAL` short-circuit is consistent and
  redundant-safe for EVM.
- Bounded limitation: if a position's terminal close is genuinely absent from
  `raw_transactions` (ingestion gap, no rent-reclaim evidence), its residual is not
  drained — that is an ingestion-completeness concern, not a replay defect.

## Verification

- Unit: `SolanaLpPositionResolverTest` (full-close true on rent-reclaim, false on
  partial / unresolved), `SolanaLpContinuityReplayTest` (Raydium full close drains
  residual; partial preserves it; Meteora full close writes off unreturned principal).
- End-to-end: `./scripts/prod-reset-rebuild-backend.sh --skip-frontend` renormalization,
  then assert no SOLANA `lp_receipt_basis_pools` pool retains `basisHeldUsd > $0.01`.
