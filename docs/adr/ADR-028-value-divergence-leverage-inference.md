# ADR-028 — Value-divergence leverage inference for on-chain leveraged buys

**Status:** Accepted
**Date:** 2026-06-12
**Inputs:** `docs/tasks/portfolio-pnl-conservation-implementation-plan.md` (U-1), [ADR-012](ADR-012-borrow-liability-tracker.md) §D2–D3/D8 (borrow-liability model), system-architect leverage-acquisition design.

## Context

Some on-chain acquisitions are **leveraged**: the wallet receives collateral worth materially more than the consideration it pays out, because the difference is funded by a borrow opened inside the same call (an Aave borrow, an ERC-3156 flash loan, or a leverage/aggregator router that opens the debt atomically). The canonical evidence anchor is tx `0xbc69…` (universe `df5e69cc…`, seq 4778): **cmETH ~$2,845 received for USDC ~$1,005 paid → a ~$1,840 value gap**.

Unlike an explicit Aave `BORROW` (which carries a `variableDebt`/`stableDebt` marker leg handled by F-3/F-4), a leveraged buy has **no debt-marker leg**. The swap therefore prices the received collateral from the consideration (`SwapDerivedPriceResolver`: $1,005 / 0.86155 cmETH ≈ $1,167/cmETH), landing the collateral at a depressed basis. When the collateral is later disposed at its true value, the engine books a **fabricated gain** (~+$2,323 on this position) — pure accounting artefact, not real PnL.

## Decision

Model a leveraged buy as **`collateral BUY @ market spot` + a synthetic `BORROW` of the value gap**, reusing the existing [ADR-012](ADR-012-borrow-liability-tracker.md) `BorrowLiabilityTracker` with **no new collection or schema**.

```
borrowPrincipalUsd = marketValue(receivedCollateral) − consideration(paidOut)
```

No RPC is needed to *size* the liability — both legs are independently priceable; on-chain logs/selectors only *corroborate* leverage.

### D1. Detection at classification ([`LeverageAcquisitionDetector`](../../backend/src/main/java/com/walletradar/ingestion/pipeline/classification/support/LeverageAcquisitionDetector.java))

For a SWAP with an acquisition shape (one priceable non-stable collateral received + a consideration paid; covers plain swaps and Pendle PT-zaps):

- **borrowEvidence** = any of: an Aave `Borrow(...)` log topic, an ERC-3156 / Aave / Balancer `FlashLoan(...)` log topic, or a known leverage/aggregator router selector family (selector-level, protocol-general — never a tx hash).
- **F-4 guard:** if a `variableDebt*`/`stableDebt*` mint leg is present, **skip** — F-4 already owns that liability (avoid double-subtraction).
- **shape + evidence + usable correlation key** → annotate `metadata.leverage` (candidate, evidence kind, `loanCorrelationId`, collateral contract/symbol). Type stays `SWAP`.
- **shape + evidence + no usable correlation key** (e.g. native-token collateral with no contract) → route to `PENDING_CLARIFICATION` with reason `LEVERAGE_BORROW_INFERENCE_REQUIRED`. **Never fabricate** a liability that cannot be keyed deterministically.
- otherwise → ordinary swap, unchanged.

The value-divergence magnitude is **not** decided at classification (no price oracle there); the pure decision truth table — `decide(receivedMarketUsd, considerationUsd, borrowEvidence)` → `ORDINARY | LEVERAGED | PENDING_INFERENCE` — is shared with the replay hook and applied where market prices exist.

### D2. Pricing — collateral resolves to market spot

[`SwapDerivedPriceResolver`](../../backend/src/main/java/com/walletradar/pricing/resolver/event/SwapDerivedPriceResolver.java) **skips** the received collateral leg of a `metadata.leverage` candidate, so the external market source prices it at canonical spot instead of the depressed swap-implied ratio. This is narrow: only candidates with confirmed borrow evidence are affected; all other swaps keep swap-derived pricing. A Pendle PT receipt acquired this way inherits the market basis via the standard continuity carry.

### D3. Correlation key ([`AaveDebtLoanCorrelationSupport.leverageLoanCorrelationId`](../../backend/src/main/java/com/walletradar/ingestion/pipeline/classification/support/AaveDebtLoanCorrelationSupport.java))

`compositeId = universeId + ":" + leverageLoanCorrelationId`:

- if a debt contract is exposed: `evm:<network>:<debtContract>:<wallet>` (reuses the F-4 namespace so a later on-chain repay can match);
- else synthetic gap: `evm-lev:<network>:<collateralContract>:<wallet>`, derived from the acquired collateral. The `evm-lev:` prefix is disjoint from Bybit numeric orderIds and the `evm:` Aave debt namespace.

The synthetic borrow is denominated in USD principal (`asset="USD", qty=gap, avco=$1`). It has **no on-chain REPAY row of its own type** (the real close is frequently a different shape — a sale to cash, a corridor transfer to a CEX, or a protocol `batch`), so `RepayReplayHandler` can never match it. Its lifecycle is therefore settled by the end-of-ledger collateral-drain rule in §D5.

### D4. Replay ([`LeverageBorrowReplayHook`](../../backend/src/main/java/com/walletradar/costbasis/application/replay/support/LeverageBorrowReplayHook.java))

A thin peer of `CounterpartyBasisPoolReplayHook`, wired into the `ReplayDispatcher` GENERIC route. After the collateral BUY leg is applied at market spot, the hook computes `gap = marketValue(collateral) − consideration`; if divergent it calls `BorrowLiabilityTracker.recordBorrow(compositeId, "USD", gap, $1)` **exactly once** and adds **no asset lot**. Non-divergent acquisitions (gap collapsed once both legs are market-priced) record nothing.

### D5. End-of-ledger settlement — close on collateral drain (`LeverageBorrowReplayHook.closeDrainedLeverageLiabilities`)

A synthetic `evm-lev:` borrow has no on-chain REPAY of its own type, so after a fully round-tripped leveraged trade the collateral is gone but the liability would otherwise stay OPEN forever, permanently depressing `expectedPnl` with no offsetting `reportedPnl` entry (architect risk H, **not** conservation-neutral once the position round-trips).

After the replay loop completes — when every position is final — `AvcoReplayService` calls `ReplayDispatcher.closeDrainedLeverageLiabilities`, which for each OPEN `evm-lev:` liability:

- parses `<network>:<collateralContract>:<wallet>` from the order id;
- looks up the **specific collateral contract position at the leverage wallet** (not the whole asset family, which may hold unrelated lots);
- if that position has fully drained (|qty| ≤ 1e-6, or no position exists) → `recordRepay` the full open principal so the liability **CLOSES**;
- if the collateral is still held → **leave OPEN** (genuinely outstanding leverage). 

The trigger is the collateral end-state, never a transaction hash. It is conservation-safe and idempotent on a clean rebuild. Explicit Aave debt-contract keys (`evm:` prefix, F-4) are untouched — they keep closing via their on-chain REPAY.

## Conservation invariant (must hold)

At the leveraged buy:
- USDC SELL @ $1 → realised ≈ 0;
- cmETH BUY @ market → basis `+gap+cash`, unrealised 0;
- `recordBorrow(gap)` → open liability `+gap`.

`ΔadjustedMtm = ΔMTM − ΔopenLiab = (gap) − (gap) = 0` → **no fabricated gain**. On a fully round-tripped trade, the collateral disposes at proceeds and realises true PnL `≈ (proceeds − own-capital-in)` against the market basis, and §D5 closes the synthetic liability so **no residual open liability** remains. A debt-contract-keyed (`evm:`) repay still closes ≈$0 on the matched principal via the existing `RepayReplayHandler`.

## Consequences

### Positive
- The +$2,323 fabricated gain on the cmETH/PT family is eliminated; the collateral carries true market basis.
- While the leveraged position is open, an OPEN `borrow_liabilities` row makes the embedded leverage observable; once the collateral round-trips, the liability closes so it no longer carries a phantom MtM offset into `expectedPnl`.
- No schema change, no new collection; reuses ADR-012 end to end.
- General, protocol-level rule keyed on shape + selector/log evidence and collateral end-state — no per-tx or per-wallet logic.

### Negative
- Inference uses a value-divergence heuristic (abs floor $100, 30% of consideration). Genuinely divergent acquisitions without borrow evidence and without a keyable collateral contract are held in `PENDING_CLARIFICATION` rather than auto-resolved.
- The synthetic USD borrow is denominated in USD principal rather than the borrowed underlying; underlying-qty denomination from borrow-log decoding is a possible follow-up (not required for conservation).
- §D5 closes on drain of the **specific** collateral contract. If a leveraged collateral were permanently converted into a *different* contract held at end-of-ledger (e.g. a one-way zap with no return leg), the borrow would close while value is still held. In practice such a zap is itself an acquisition shape that re-keys leverage on the new contract; the canonical cmETH↔PT-cmETH round-trip returns to the original contract before exit, so the original key drains correctly.

## Acceptance criteria

| # | Assertion | Test |
|---|---|---|
| AC-028-1 | `decide` truth table: divergent∧evidence→LEVERAGED, divergent∧¬evidence→PENDING, ¬divergent→ORDINARY (abs floor + relative threshold) | `LeverageAcquisitionDetectorTest` |
| AC-028-2 | Leveraged buy annotated with `evm-lev:` synthetic-gap correlation key; ordinary swap not annotated | `LeverageAcquisitionDetectorTest` |
| AC-028-3 | F-4 guard: debt-marker mint present ⇒ no leverage annotation | `LeverageAcquisitionDetectorTest` |
| AC-028-4 | Native collateral with no contract ⇒ PENDING annotation (no fabricated liability) | `LeverageAcquisitionDetectorTest` |
| AC-028-5 | Collateral BUY leg skips swap-derived pricing for a leverage candidate | `SwapDerivedPriceResolverTest` |
| AC-028-6 | Replay hook registers exactly one synthetic USD liability of size `gap` and adds no asset lot | `LeverageBorrowReplayHookTest` |
| AC-028-7 | Non-divergent acquisition records no borrow | `LeverageBorrowReplayHookTest` |
| AC-028-8 | End-of-ledger: synthetic `evm-lev:` liability CLOSES when collateral contract drained (qty 0 or absent), leaving unrelated family lots untouched | `LeverageBorrowReplayHookTest` |
| AC-028-9 | End-of-ledger: synthetic `evm-lev:` liability stays OPEN while collateral still held; explicit `evm:` Aave debt never closed by the drain rule | `LeverageBorrowReplayHookTest` |

## References

- [ADR-012](ADR-012-borrow-liability-tracker.md) — borrow-liability model (reused; see §D8 activation).
- [ADR-014](ADR-014-portfolio-conservation-gate.md) — open liabilities held separately from MtM.
- [docs/pipeline/cost-basis/04-borrow-liability.md](../pipeline/cost-basis/04-borrow-liability.md) — borrow-liability pipeline doc.
- [docs/pipeline/cost-basis/02-avco-rules.md](../pipeline/cost-basis/02-avco-rules.md) — AVCO basis rules.
