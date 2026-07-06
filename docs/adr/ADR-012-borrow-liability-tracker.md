# ADR-012 — Borrow liability tracker with deterministic zero-PnL on closed roundtrip

**Status:** Proposed
**Date:** 2026-05-16
**Inputs:** `cycle-autorun/cycle-data/cycle/5/results/n19-defect-catalog.md` (D-LOAN-ROUNDTRIP), `n19-implementation-plan.md` §F, `docs/adr/ADR-006-cycle5-bybit-stream-authority-and-earn-subaccount.md` §N18 "Known follow-ups" (BORROW → ACQUIRE without liability tracking)

## Context

The Bybit Crypto Loan product produces paired BORROW + REPAY rows in `funding-history`, correlated by `orderId`. The audit window contains several closed roundtrips:

| Asset | Borrowed | Repaid | Net qty | `orderId` evidence |
|---|---:|---:|---:|---|
| MNT  | 1050 + 199 | 1050 + 200 | ≈ 0 | 2 distinct orderIds |
| DOGS | (varies)   | (varies)   | ≈ 0 | 1 orderId |
| TON  | (varies)   | (varies)   | ≈ 0 | 1 orderId |

The current backend produces:

- `db.normalized_transactions.count({type:"BORROW", walletAddress:/BYBIT/}) == 9`
- `db.normalized_transactions.count({type:"REPAY",  walletAddress:/BYBIT/}) == 8`

The mathematical relationship between BORROW and REPAY rows is unenforced; the AVCO engine treats BORROW as a zero-basis inflow (qty in at $0) and REPAY as a market-price outflow (qty out at current price). Net Realised PnL per roundtrip:

```
realisedPnlPerRoundtrip ≈ qtyBorrowed * priceAtRepay
                         − qtyBorrowed * 0   (== priceAtRepay × qtyBorrowed; pure phantom)
```

For MNT alone (1050 MNT × ≈ $0.65 ≈ $683 + 199 MNT × ≈ $0.70 ≈ $139) ≈ **$800 of phantom Realised PnL**. Catalog estimate across MNT + DOGS + TON: **$1,000-1,500**.

Beyond the audit window, [ADR-006 §N18 "Known follow-ups"](ADR-006-cycle5-bybit-stream-authority-and-earn-subaccount.md) explicitly flagged this:

> BORROW → ACQUIRE without liability tracking. Borrows from Aave / Bybit margin still appear as BUY events crediting the asset pool without an offsetting liability row, so a *un-repaid* loan inflates equity by the borrowed notional.

The cycle/5 audited session has all loans closed, so today's MtM is unaffected by open positions — but the Realised PnL line is contaminated, and any future session with outstanding loans will inflate equity by the borrowed notional.

The fix requires a small persistent ledger that lives alongside the cost-basis ledger.

## Decision

### D1. New domain class and collection

Add [backend/src/main/java/com/walletradar/costbasis/domain/BorrowLiability.java](backend/src/main/java/com/walletradar/costbasis/domain/BorrowLiability.java):

```java
@Document("borrow_liabilities")
@CompoundIndex(name = "borrow_liability_universe_asset_open_idx",
               def = "{'universeId': 1, 'asset': 1, 'qtyOpen': 1}")
class BorrowLiability {
    @Id
    String compositeId;          // "<universeId>:<orderId>"

    String universeId;
    String accountRef;           // e.g. "BYBIT:33625378:UTA" (where the loan landed)
    String orderId;              // Bybit crypto-loan order id

    String asset;                // borrowed asset (e.g. "MNT")
    BigDecimal qtyBorrowed;      // immutable, total borrowed under this orderId
    BigDecimal qtyOpen;          // running open amount (qtyBorrowed - sum of repays)

    // Basis tagging — see D2
    BigDecimal portfolioAvcoAtOpen;   // AVCO of the asset in the account ledger at the moment of first BORROW
    PriceSource portfolioAvcoSource;  // STABLE_PEG / COINGECKO / etc.

    Instant openedAt;
    Instant lastTouchedAt;
    Instant closedAt;            // set when qtyOpen reaches 0

    String status;               // OPEN | CLOSED | PARTIAL
    String accountingExclusionReason;  // null when active; otherwise migration / replay history
}
```

The collection is small (one row per distinct loan order; bounded by Bybit-side loan history). The compound index supports the "open positions per asset / universe" query used by the dashboard.

### D2. Basis-neutral BORROW

When a `BORROW` flow arrives, the AVCO engine assigns it a basis equal to the **current portfolio AVCO** of the asset on `Flow.accountRef`. This makes the qty inflow basis-neutral: it enters the pool at the running AVCO, so the average price does not move. Two consequences:

- Subsequent disposals of any quantity from that pool produce realised PnL relative to the unmodified AVCO. Phantom PnL on the loan principal is eliminated.
- The liability is tracked separately in `borrow_liabilities`; the asset-side ledger remains "physical quantity unchanged after BORROW".

If the asset ledger has zero balance at the moment of first BORROW (the user borrows an asset they don't already hold), the BORROW inflow basis is `0` — but the liability tracker records the borrow at market price for future reference. The matching REPAY then disposes at market price; under D3, the realisedPnl on the REPAY for the qty equal to `qtyBorrowed` is zero by construction, regardless of the price path.

### D3. Deterministic zero-PnL on matched REPAY

When a `REPAY` flow arrives:

1. Look up `BorrowLiability` by `(universeId, orderId)`.
2. Match `qty` (signed positive) against `qtyOpen`:
   - `matchedQty = min(qty, qtyOpen)`.
   - `residualQty = qty - matchedQty` (excess repay — rare; treated as ordinary SELL at market).
3. For `matchedQty`:
   - Override `Flow.unitPriceUsd = liability.portfolioAvcoAtOpen` (the basis basis the BORROW carried).
   - Override `Flow.avcoAtTimeOfSale = liability.portfolioAvcoAtOpen`.
   - Override `Flow.realisedPnlUsd = 0` (matched roundtrip — no realised PnL).
   - Emit annotation `Flow.priceSource = LIABILITY_MATCH`.
4. For `residualQty` (if any):
   - Standard SELL handling — `unitPriceUsd = market`, `realisedPnlUsd = (market − currentAvco) × residualQty`.
5. Update `qtyOpen -= matchedQty`; if `qtyOpen <= 0` then set `status=CLOSED` and `closedAt = now`.

### D4. Open liability surfaces on dashboard

[backend/src/main/java/com/walletradar/ingestion/wallet/query/SessionDashboardQueryService.java](backend/src/main/java/com/walletradar/ingestion/wallet/query/SessionDashboardQueryService.java) reads `borrow_liabilities` (single query per dashboard request, bounded by universe), aggregates by `asset`, and emits a new `openLiabilities[]` block:

```json
{
  "openLiabilities": [
    { "asset": "MNT", "qty": 199.0, "marketValueUsd": 145.27, "accountRef": "BYBIT:33625378:UTA",
      "orderId": "1234567", "openedAt": "2025-..." }
  ],
  "totalLiabilityUsd": 145.27
}
```

The frontend renders this as a separate "Outstanding loans" line — it is **not** subtracted from MtM in the conservation gate ([ADR-014](ADR-014-portfolio-conservation-gate.md) §D2) but it is shown distinctly so the user understands a portion of their MtM is borrowed.

### D5. Replay handler routing

In [backend/src/main/java/com/walletradar/costbasis/application/replay/dispatch/ReplayDispatcher.java](backend/src/main/java/com/walletradar/costbasis/application/replay/handler), wire `NormalizedTransactionType.BORROW` and `REPAY` to new handlers:

- `backend/src/main/java/com/walletradar/costbasis/application/replay/handler/BorrowReplayHandler.java`
- `backend/src/main/java/com/walletradar/costbasis/application/replay/handler/RepayReplayHandler.java`

Each handler consumes the per-flow data, mutates the in-memory `BorrowLiabilityBook` (a per-replay-session map of `compositeId → BorrowLiability`), and emits the modified flow to the AVCO engine. Persistence to `borrow_liabilities` happens once at the end of the replay run (consistent with the "in-memory mutate, persist once" pattern of [ADR-015](ADR-015-per-counterparty-basis-pool.md)).

### D6. Required correlationId on BORROW/REPAY rows

[BybitCanonicalTransactionBuilder](backend/src/main/java/com/walletradar/ingestion/pipeline/bybit/BybitCanonicalTransactionBuilder.java) MUST carry the Bybit `orderId` into `correlationId` for BORROW and REPAY rows (already done partially for crypto-loan stream per `n19-implementation-plan.md` §F — verify under acceptance gate AC-012-7). The compositeId rule is then `compositeId = universeId + ":" + correlationId`.

### D7. Failure modes

| Scenario | Behaviour |
|---|---|
| REPAY arrives without a matching BORROW (data ingestion missed the open) | Liability created on REPAY with `qtyBorrowed = qty, status=OPEN_FROM_REPAY` reverse — the REPAY produces standard market disposal; missing data reason logged. |
| Multiple BORROW rows for same orderId | Liability merges qty additively. `portfolioAvcoAtOpen` recomputed as weighted average. |
| BORROW + interest then REPAY (with interest as separate row) | Interest row is `REWARD_CLAIM` / `INTEREST` (separate type — not REPAY); BORROW/REPAY pair handles principal only. Interest realises as ordinary income per [ADR-009](ADR-009-ownership-classification-via-universe.md) basis policy. |
| Open liability at session close (user never repaid) | `qtyOpen > 0` persists; dashboard shows open loan; NEC and MtM untouched; future REPAY closes it deterministically. |

### D8. Generalisation to non-Bybit borrow sources

The same model applies to Aave / Compound / Solana Jupiter borrowing (out of cycle/5 scope but worth fixing the abstraction). The `orderId` is replaced by the chain-side `loanId` / `positionKey`. The `accountRef` becomes `evm:<address>:<protocol>`. No schema change is required to extend the model later — the abstraction is universal.

**Activation for explicit on-chain Aave loans (F-3/F-4).** On-chain `BORROW`/`REPAY` rows carry an Aave-style `variableDebt`/`stableDebt` marker leg. [`AaveDebtLoanCorrelationSupport.syntheticLoanCorrelationId`](../../backend/src/main/java/com/walletradar/ingestion/pipeline/classification/support/AaveDebtLoanCorrelationSupport.java) derives a deterministic `evm:<network>:<debtContract>:<wallet>` key so the same revolving debt position matches its borrow and repay. The debt-marker leg itself is never booked as an asset (the double-subtract guard in `BorrowReplayHandler`/`RepayReplayHandler`).

**Activation for inferred on-chain leverage (ADR-028).** A *leveraged buy* embeds the borrow inside a SWAP and has **no** explicit debt-marker leg — the collateral simply arrives worth more than the consideration paid. The same `BorrowLiability` model is reused with no schema change: a synthetic USD liability of size `marketValue(collateral) − consideration` is recorded against a distinct `evm-lev:<network>:<collateralContract>:<wallet>` key (see [ADR-028](ADR-028-value-divergence-leverage-inference.md)). The leverage path is guarded to skip whenever a debt-marker mint is present, so F-4 and the leverage inference never both own the same liability.

## Consequences

### Positive
- D-LOAN-ROUNDTRIP closes: phantom Realised PnL of $1,000-1,500 on MNT / DOGS / TON loan roundtrips is eliminated deterministically. Realised PnL drops by ~$1,000-$1,500 as predicted in `n19-defect-catalog.md`.
- Open liabilities become observable on the dashboard. The user can see "you owe 199 MNT to Bybit" without it inflating equity.
- The model extends naturally to Aave / Compound / Jupiter (per D8).
- ADR-006 §N18 "Known follow-up" closes.

### Negative
- One new collection (`borrow_liabilities`); bounded size; one new index.
- BORROW / REPAY replay handlers add ~150 lines of code with thorough test coverage.
- Open liabilities currently appear only on Bybit Crypto Loan rows; if the user has an open Aave borrow that we don't yet ingest, the dashboard shows incomplete liability data. Acceptable for cycle/5; tracked for follow-up.

### Migration
1. Add `BorrowLiability` domain class + repository.
2. Wire replay handlers per D5.
3. Add `db.borrow_liabilities.deleteMany({})` to [scripts/avco/reset-derived.sh](scripts/avco/reset-derived.sh).
4. Update [scripts/prod-reset-rebuild-backend.sh](scripts/prod-reset-rebuild-backend.sh) to call the new reset.
5. Run a full rebuild; replay reconstructs `borrow_liabilities` chronologically from BORROW/REPAY flows.

## Acceptance criteria

| # | Assertion | Test |
|---|---|---|
| AC-012-1 | Round-trip test: `BORROW(1050 MNT)` + `BORROW(199 MNT)` + `REPAY(200 MNT)` + `REPAY(1050 MNT)` ⇒ each REPAY flow has `realisedPnlUsd = 0`; final `qtyOpen` for that orderId pair is `≤ 0.000001` | `BorrowLiabilityTrackerTest` |
| AC-012-2 | DOGS roundtrip from `n19-raw/bybit-funding-history.jsonl` (single orderId) ⇒ aggregate REPAY `realisedPnlUsd ≈ 0` | `N19FullPipelineRebuildTest` |
| AC-012-3 | TON roundtrip from `n19-raw/bybit-funding-history.jsonl` ⇒ aggregate REPAY `realisedPnlUsd ≈ 0` | `N19FullPipelineRebuildTest` |
| AC-012-4 | After full rebuild, total Realised PnL on Bybit umbrella drops by `≥ 1000 ≤ 1500` USD vs the pre-fix baseline (matches catalog estimate) | `N19FullPipelineRebuildTest` |
| AC-012-5 | `db.borrow_liabilities.find({status:"OPEN"})` returns no rows if all loans are closed in the audit window | `N19FullPipelineRebuildTest` |
| AC-012-6 | Dashboard query exposes `openLiabilities[]` and `totalLiabilityUsd` fields; both are 0 / empty when all loans are closed | `SessionDashboardQueryServiceTest` |
| AC-012-7 | `BybitCanonicalTransactionBuilder` emits `correlationId = <orderId>` for BORROW and REPAY rows | `BybitCanonicalTransactionBuilderTest` |
| AC-012-8 | Partial REPAY (qty < qtyOpen) ⇒ `realisedPnlUsd=0` on the matched portion; `qtyOpen -= matchedQty` persists | `BorrowLiabilityTrackerTest` |
| AC-012-9 | Excess REPAY (qty > qtyOpen) ⇒ matched portion = qtyOpen with `realisedPnlUsd=0`; residual realised at market in the same REPAY transaction | `BorrowLiabilityTrackerTest` |

## References

- [cycle-autorun/cycle-data/cycle/5/results/n19-defect-catalog.md](cycle-autorun/cycle-data/cycle/5/results/n19-defect-catalog.md) — D-LOAN-ROUNDTRIP.
- [cycle-autorun/cycle-data/cycle/5/results/n19-implementation-plan.md](cycle-autorun/cycle-data/cycle/5/results/n19-implementation-plan.md) §F.
- [cycle-autorun/cycle-data/cycle/5/results/n19-raw/bybit-funding-history.jsonl](cycle-autorun/cycle-data/cycle/5/results/n19-raw/bybit-funding-history.jsonl) — BORROW / REPAY evidence.
- [docs/adr/ADR-006-cycle5-bybit-stream-authority-and-earn-subaccount.md](ADR-006-cycle5-bybit-stream-authority-and-earn-subaccount.md) §N18 — original follow-up requirement.
- [ADR-009](ADR-009-ownership-classification-via-universe.md) — counterparty taxonomy. BORROW/REPAY rows classify the loan venue as `CounterpartyType.PROTOCOL` (existing seven-value enum); the LOAN-specific lifecycle is encoded by `NormalizedTransactionType.BORROW` / `REPAY` and the `BorrowLiability` document of this ADR, not by a counterparty-type variant.
- [ADR-010](ADR-010-flow-level-counterparty.md) — `accountRef` per flow.
- [ADR-014](ADR-014-portfolio-conservation-gate.md) — open liabilities treated separately from MtM.
- [ADR-015](ADR-015-per-counterparty-basis-pool.md) — "in-memory mutate, persist once" replay pattern (mirrored here).
- [backend/src/main/java/com/walletradar/ingestion/pipeline/bybit/BybitCanonicalTransactionBuilder.java](backend/src/main/java/com/walletradar/ingestion/pipeline/bybit/BybitCanonicalTransactionBuilder.java) — correlation id emission.
- [backend/src/main/java/com/walletradar/costbasis/application/replay/dispatch](backend/src/main/java/com/walletradar/costbasis/application/replay) — handler wiring.
- [backend/src/main/java/com/walletradar/ingestion/wallet/query/SessionDashboardQueryService.java](backend/src/main/java/com/walletradar/ingestion/wallet/query/SessionDashboardQueryService.java) — dashboard exposure.
