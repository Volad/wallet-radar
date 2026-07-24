# ADR-069: Jupiter Lend borrow / loop accounting on Solana

**Status:** Accepted  
**Date:** 2026-07-19  
**Scope:** Solana normalization / classification, cost-basis replay, lending view

---

## Context

An entire OPEN leveraged Jupiter Lend position on Solana (~5.1–5.4 SOL collateral +
~210–233 USDT debt) was invisible in cost basis and the lending view. Root causes, all in the
Solana normalization pipeline (canonical types were already adequate — `BORROW`,
`LENDING_LOOP_OPEN`, `LENDING_DEPOSIT`, `LENDING_WITHDRAW`, `REPAY` all exist):

1. `SolanaTransactionClassifier.resolveLendingType` defaulted **every** Jupiter Lend row to
   `LENDING_DEPOSIT`. Helius returns a generic/empty `type` for Jupiter Lend, so the type
   string is unusable — a borrow was mislabelled a deposit (fee-only).
2. `SolanaNormalizedTransactionBuilder.buildLendingFlows` dropped inbound legs
   (`outbound ? TRANSFER : null`), so the borrow inflow was discarded; loop transactions also
   emitted per-transfer phantom stablecoin `SELL` legs.
3. No SOLANA `borrow_liabilities` rows were ever created, so debt principal was untracked.

This is an `EVIDENCE_PRESENT_UNUSABLE` defect: the raw payload contains the borrow inflow;
the classifier + leg-builder discarded it. Failed stage = `classification`, then
`move_basis`/`cost_basis` via the builder.

---

## Decision

**Classify and account Jupiter Lend by net asset flow, reusing the existing EVM borrow/repay
and lending-continuity replay machinery. No new transaction types and no Solana-specific
replay handler.**

1. **Net-flow classification** (`SolanaTransactionClassifier.resolveJupiterLendType`). Decide
   from the wallet's net per-mint delta (`walletNetByMint`), never the Helius `type`. SOL is
   the collateral, the `SOLANA.usd-stable-contracts` mints (USDC/USDT) are the borrowable
   assets, and the protocol position-receipt token (jl-SOL) is ignored:
   - Jupiter Swap invoked in-tx + net collateral change → `LENDING_LOOP_OPEN`;
   - net-positive stablecoin → `BORROW`; net-negative stablecoin → `REPAY`;
   - net-positive SOL → `LENDING_WITHDRAW`; net-negative SOL → `LENDING_DEPOSIT`.
   - A pure Jupiter Swap without `jupr81…` is never lending (Jupiter Lend is matched first).

2. **Net-flow leg building** (`SolanaNormalizedTransactionBuilder.buildLendingFlows`). Build
   flows from the net per-mint delta restricted to inventory assets (SOL + USD-stable mints);
   the receipt token and other mints are excluded. Netting removes a loop's phantom stablecoin
   `SELL` legs (borrowed principal nets ~0). The inbound borrow leg is emitted as `BUY`; the
   repay leg as `SELL`; deposit/withdraw/loop collateral legs as `TRANSFER` (carry).

3. **Accounting treatment.**
   - `BORROW` → `BorrowReplayHandler`: borrowed principal enters both lanes at
     market-at-borrow basis with a parallel `borrow_liabilities` row (ADR-040 §5; the tracked
     liability offsets net worth). Deposits/withdraws are CARRY, not ACQUIRE/DISPOSE.
   - `REPAY` → `RepayReplayHandler`: reduces/closes the liability, zero realised PnL on the
     liability-matched principal, FX residual realised per policy.
   - `LENDING_DEPOSIT` / `LENDING_WITHDRAW` / `LENDING_LOOP_OPEN` carry basis through the
     shared continuity-transfer path — identical to EVM lending/vault continuity.

4. **SOLANA `borrow_liabilities` id scheme.** The builder stamps a deterministic loan
   `correlationId = solana:jupiter-lend:<debtMint>:<wallet>` on `BORROW`/`REPAY`; the replay
   handlers use it as the `orderId` (`compositeId = <universeId>:<orderId>`). `BorrowLiability`
   has no network column — network is encoded in the order id and `accountRef` = the Solana
   wallet. Repeated borrows aggregate into one liability; a later repay nets against it.

---

## Rationale

| Alternative | Why rejected |
|---|---|
| Trust Helius `type` for Jupiter Lend | Empty/generic for Jupiter Lend — the original defect |
| Per-transfer leg building | Re-introduces the loop's phantom stablecoin SELL legs; net-by-mint cancels them |
| Add new `LENDING_LOOP_*` / borrow types | Canonical types already exist — this is a classifier + leg-builder defect, not a type-model gap |
| Solana-specific borrow/repay/continuity replay handlers | The EVM handlers are network-agnostic; reusing them keeps one accounting policy and zero EVM regressions |
| Post-hoc repair/backfill of stored rows | Fixes the earliest wrong stage (classification + leg building) and rebuilds downstream via rerun instead |

The receipt token and non-SOL/non-stable mints are excluded from lending inventory because a
Jupiter Lend position's economic assets are only the SOL collateral and the stablecoin debt;
the receipt is a protocol position marker.

---

## Consequences

- After renormalization + AVCO replay: Solana `BORROW ≥ 1`; one SOLANA `borrow_liabilities`
  row (USDT, OPEN); the lending view shows one OPEN Jupiter loop; no phantom USDT `SELL` legs.
- Assumption/bound: collateral = SOL, debt = USD-stable mint (RULE 1 scope). A non-SOL /
  non-stable collateral or debt asset is out of scope and would need the value-mint set
  extended. EVM behaviour is unchanged.

## Amendment (2026-07): synthesized SUPPLY (collateral) for receipt-less networks

Jupiter Lend deposits SOL collateral **without minting a fungible receipt token** we snapshot as an
`on_chain_balance`. The lending view built SUPPLY positions exclusively from on-chain receipt
balances, so a live loop read as `supplyUsd = 0`, forced a bogus `healthFactor = 0.0`
("Liquidation risk"), and reported a wrong (borrow-only) net exposure — even though `borrow_liabilities`
correctly surfaced the debt.

`LendingCycleBuilder` now reconstructs an outstanding SUPPLY position from cycle accounting
(`principalIn − principalOut` per asset), symmetric to the existing synthesized-BORROW path
(`:synthetic-supply` marker), and bubbles it into group `supplyUsd` / net exposure. It is **strictly
guarded to non-EVM networks** (`NetworkAddressFormat.isEvm(...) == false`) so EVM protocols, which do
surface aToken/cToken supply balances, are never double-counted, and it skips any asset that already
has a live SUPPLY position. Outstanding collateral is valued at the current market price when
available (else net-deposit USD basis, else deposit-time unit price), and marked
`ACCOUNTING_ESTIMATE`. The health factor is then a genuine estimate
(`supplyUsd × liquidationThreshold ÷ borrowUsd`) rather than 0.

Bound: this is an accounting-derived estimate of collateral, so it drifts from the live on-chain
Jupiter position under protocol rebalances/interest and uncaptured decrease events; an exact
live health factor / collateral still requires a dedicated Solana Jupiter Lend on-chain position
reader (future work).

## Amendment (2026-07-20): live position reader supersedes the estimate + debt true-up (ADR-071)

The "future work" reader now exists: `JupiterLendLivePositionReader` (see **ADR-071**) reads the live
collateral / debt / risk params from the Jupiter Lend Borrow API and becomes the **single authority**
for the position. When a fresh live snapshot exists it supersedes the accounting-derived estimate on
all three surfaces (`on_chain_balances`, lending view SUPPLY, health factor); the synthesized SUPPLY
above is retained only as a clearly-stale fallback when the reader is unreachable.

**Borrow-liability live true-up (WS-4).** For receipt-less (non-EVM) networks the live outstanding
debt **SETs / overrides** `borrow_liabilities.qtyOpen` — it is authoritative and **supersedes** the
classification-derived principal; it is never stacked on top (else `PortfolioConservationGate` would
over-subtract the liability universe-wide). Interest accrual (e.g. 210 → 233 USDT) is a **real
expense**: it raises the outstanding liability and books **no realised income**; stablecoin debt
marks at $1. EVM debt is intentionally skipped — it is already live via the variable/stable
debt-token balances. The true-up runs in the background lending refresh after AVCO replay has rebuilt
the liability book and re-applies on every refresh (idempotent SET). Implemented in
`LendingLiabilityLiveTrueUpService`, matched flow-based (wallet / lending-marker order id + debt
asset), never keyed on a transaction hash.

## Regression anchors

- `SolanaTransactionClassifierTest` (RULE 1 net-flow cases)
- `SolanaNormalizedTransactionBuilderTest` (RULE 1 leg-building cases)
- `JupiterLendBorrowReplayTest` (SOLANA liability create/close + order-id scheme)
- `LendingCycleBuilder` synthesized SUPPLY on receipt-less networks (non-EVM guard; no EVM double-count)
- `LendingLiabilityLiveTrueUpServiceTest` (WS-4 live debt SET/override, not stack; EVM skip)
- `JupiterLendLivePositionReaderTest` / `JupiterLendLockedCollateralProviderTest` (live single authority, ADR-071)
