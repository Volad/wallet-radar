# Borrow Net-Basis Phantom-Income Fix — Implementation Plan

- **Status:** Approved (compliance with existing ADR-040 §5; user-reported defect, standing autonomy)
- **Date:** 2026-07-18
- **Audit source:** `results/repay-transferout-phantom-income-audit.md` (financial-logic-auditor)
- **Governing decision:** ADR-040 §5 — *"BORROW / REPAY: treated identically in both lanes (not zero-cost in Net)."*

## Scope

- **Universe:** `df5e69cc-a0c0-4910-8b7d-74488fa266e2` (all wallets; borrowed assets observed: MNT, TON, DOGS, USDC, USDe, GHO, USD₮0, AWETH).
- **Stage:** cost-basis replay (Net-lane basis assignment on `BORROW`).
- **Blocker IDs:** D1 (Critical), D2 (High, defense-in-depth), D5 (High-adjacent, SWAP — auto-resolved by D1), D6 (operational replay-gate blocker). D3/D4 (internal-transfer linking) are tracked as a separate follow-up.

## Root cause

`BorrowReplayHandler.apply` books borrowed assets with **Net acquisition cost = `BigDecimal.ZERO`** (line 119) while Market cost = market-at-borrow. This violates ADR-040 §5. Because the borrowed principal enters the Net lane at $0 basis and nothing offsets it over the loan lifecycle, **any disposal of borrowed units** (`REPAY`, `EXTERNAL_TRANSFER_OUT`, `SWAP`) books phantom Net-lane realized income ≈ `marketAvco × qty`.

`RepayReplayHandler` already forces Market-lane realized ≈ $0 on a full liability match and subtracts `priorRealised` (the *Market* realized) from **both** lanes — but the Net-lane delta is `(marketAvco − netAvco)·qty`, which stays non-zero precisely because `netAvco ≈ 0`. Fixing the borrow Net basis makes `netAvco ≈ marketAvco`, so the existing correction zeroes both lanes correctly.

**Prior-run magnitude:** proven phantom ≥ $6,669.72 (REPAY $3,442.84 ≈100%; TRANSFER_OUT ≥ $3,226.88 ≈93%); SWAP $10,089 shares the same root cause and is expected to shrink materially after the fix. MNT `3f19df6d…` REPAY: $1,733.43 → true ≈ $0.

## Changes (ordered)

1. **D1 — `BorrowReplayHandler.apply`:** replace the hardcoded `BigDecimal.ZERO` net acquisition cost with `acquisitionCostUsd` (market-at-borrow), i.e. `applyBuyWithExplicitNetCost(flow, position, acquisitionCostUsd, acquisitionCostUsd)`. Update the stale comment (currently cites ADR-012 §D2 "Net AVCO is $0") to cite ADR-040 §5 and explain that the liability — not a $0 asset basis — is what offsets net worth.
2. **D2 — `RepayReplayHandler` (defense-in-depth):** on a full liability match, after the existing correction, assert/clamp the Net-lane realized delta for the matched principal to ≈ $0 as well (mirror the Market-lane zeroing explicitly rather than relying on `netAvco == marketAvco`). Keep the residual (over-repay beyond liability) priced at position AVCO in both lanes.
3. **Comment/doc hygiene:** remove/repoint any remaining "borrow = net $0" reasoning to ADR-040 §5.

## Tests (backend-dev)

- Update existing `BorrowReplayHandlerTest` / `RepayReplayHandlerTest` expectations that encode net-$0 borrow basis (they encode the defect).
- Add: **borrow → repay** round-trip (no price move) ⇒ `Σ netRealisedPnlDelta ≈ 0` **and** `Σ marketRealisedPnlDelta ≈ 0` (only interest/fees survive).
- Add: **borrow → transfer-out** of borrowed principal ⇒ Net realized ≈ $0 (regression for the zkSync USDC / TON / MNT cases).
- Add: **borrow → swap** of borrowed principal ⇒ no phantom Net income on the sell leg; acquired asset carries market net basis (D5 regression).
- Preserve: genuine `REWARD_CLAIM` / `LP_FEE_CLAIM` remain net-$0 (unchanged) and still book income on eventual disposal.
- Net-carry invariant (ADR-040 amendment): `Net AVCO ≤ Market AVCO`, `Net AVCO ≥ 0` for borrowed pools (now equal).

## Docs

- **ADR-040 §5** — add a dated amendment noting the code was non-compliant (net-$0) and is corrected to market-at-borrow in both lanes; supersedes the ADR-012 §D2 "basis-neutral / net-$0 borrow" phrasing.
- `docs/pipeline/cost-basis/` — note borrow/repay net-lane conservation in the borrow/repay section if present.

## Acceptance

- Prod rebuild (`--clear-pricing-cache --skip-frontend`) completes; replay gate clears (D6): `avcoReady=true`, `assetLedgerPoints > 0`.
- Re-audit (`financial-logic-auditor`): REPAY Net income ≈ $0; TRANSFER_OUT of borrowed principal ≈ $0; SWAP income re-measured; corrected true zero-basis income reported.
- Specific: MNT `3f19df6d…` REPAY Net realized ≈ $0; zkSync USDC transfers-out ≈ $0; TON `93e7330b…` ≈ $0.
- No regression: closed-liability conservation `Σ netRealisedPnlDelta ≈ 0 (±interest)` for every closed loan.

## Risks

- **Blast radius:** portfolio-wide Net-lane realized P&L and `incomeReceivedUsd` change (the ~$17k income figure drops substantially). This is the intended correction, not a regression.
- **D6 gate:** if the replay gate stays blocked after re-resolving prices (498 NEEDS_REVIEW / 35 unresolved prices), a separate investigation is required before acceptance can be verified.
- **Downstream:** ADR-062 effective-cost `incomeReceivedUsd` must not be surfaced as trustworthy until this fix lands and SWAP is re-scrubbed. The effective-cost `offsetLane` decision remains deferred until the corrected income number is known.
