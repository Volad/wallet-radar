# ADR-055: Funding-History `Crypto Loans` source-authority for BORROW/REPAY

| Field | Value |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-07-13 |
| **Theme** | Bybit ingestion / extraction authority / loan accounting |
| **Cites** | ADR-012 (borrow liability tracker), ADR-028 (value-divergence leverage inference), ADR-054 (per-asset AVCO); BLOCKER-4 (corridor inbound umbrella-collapse), BLOCKER-6 |
| **Plan** | `docs/tasks/bybit-crypto-loans-extraction-and-earn-reallocation-dedup-implementation-plan.md` |

## Context

Bybit reports crypto-loan lifecycle events on **two** streams:

- **UTA `TRANSACTION_LOG`** — `LOANS_BORROW_FUNDS` / `LOANS_REPAY_FUNDS` (and `LOANS_INTEREST`,
  `LOANS_PLEDGE_ASSET`, `LOANS_ASSET_REDEMPTION`). These are the authoritative unified-account loan
  records (`basisRelevant=true`, already ingested; e.g. MNT/DOGS early-2025 loans).
- **Funding-History (FH)** — FUND-wallet rows typed `Crypto Loans` (`Borrow Released` /
  `Borrow Repayment`) or `Loans` (`Borrow Funds` / `Repay Principal` / `Repay Interest` /
  `Pledge Assets`).

`BybitExtractionService.extractFundingHistory` historically demoted **all** FH loan rows
(`Crypto Loans` **and** `Loans`, canonical `BORROW`/`REPAY`/`FEE`) to `basisRelevant=false` under the
assumption that UTA `TRANSACTION_LOG` is always authoritative (`fhLoanShadow`). Normalization only
intakes `basisRelevant=true` rows, so those FH loan events stayed `status=RAW` and were never
materialized.

That assumption is wrong for loans that exist **only** on the FH stream with **no** UTA
`TRANSACTION_LOG` counterpart. In the audited data these are the **TON 2025-08** and
**MNT 2025-09→2026-02** `Crypto Loans` cycles. A borrow that funds a later withdrawal became
invisible, producing a phantom `quantityShortfall` (TON seq 5472 = 15.17) and omitting the loan
liability entirely (`borrow_liabilities` = 0 for TON). See BLOCKER-6 / BLOCKER-5 §5E in
`results/blockers.md`.

The downstream loan machinery already exists and is source-stream agnostic
(`BybitCanonicalMappedRowSupport` maps `BORROW → BUY` / `REPAY → SELL`;
`BybitPledgeLoanCorrelationSupport` keys `bybit-pledge:<uid>:<asset>` off `source=BYBIT` +
`type ∈ {BORROW, REPAY}`; `BorrowReplayHandler` / `RepayReplayHandler` / `BorrowLiabilityTracker`
consume it; market-at-borrow basis + net-$0 dual lane per ADR-012/028). The defect is therefore an
**extraction-authority** bug only — no new normalized type, replay handler, liability schema, AVCO
path, or Mongo index is required.

## Decision

A Funding-History loan row is promoted to `basisRelevant=true` (materialized as a loan lifecycle
event) **only** when **all** of the following hold:

1. **Product = `Crypto Loans`.** `Loans`-product rows (`Borrow Funds`, `Repay Principal`,
   `Asset Redemption`, `Pledge Assets`) and **all** loan `FEE`/interest rows (`Repay Interest`) remain
   `basisRelevant=false` — they shadow the authoritative UTA `TRANSACTION_LOG` `LOANS_*` stream.
2. **Canonical type ∈ {`BORROW`, `REPAY`}** (loan principal lifecycle only).
3. **Directional sign consistency.** A `BORROW` must be a **positive** inventory inflow and a `REPAY` a
   **negative** outflow. A negative "borrow" is a **collateral lock / pledge OUTFLOW**, not a borrowed-
   inventory inflow — e.g. the USDT `Loans / Pledge Assets` −523 event must never create a phantom
   BORROW inflow.
4. **No UTA `TRANSACTION_LOG` coverage.** No `LOANS_BORROW_FUNDS` / `LOANS_REPAY_FUNDS` row exists for
   the same `(uid, asset)` within a 30-minute window of the FH event (defense-in-depth against
   double-counting a loan represented on both streams; today the two streams are disjoint by
   product/time).

The rule lives in a single shared helper
(`BybitExtractionService.isAuthoritativeFundingHistoryCryptoLoan`) invoked from
`extractFundingHistory`. Because `refreshBasisRelevantFromRaw` re-runs `extract(...)`, the same rule is
re-derived on every re-run — a single source of truth that is idempotent.

### Loan-credit accounting invariants (unchanged from ADR-012/028, restated for FH loans)

- The FH `Crypto Loans` BORROW credit is a FUND-wallet inflow. Per BLOCKER-4, an **inbound** loan
  credit collapses `:FUND`/`:UTA` → the unified umbrella spot key (it must be reachable by later
  umbrella-level disposals such as a withdrawal); it must never strand on `:FUND`.
- Borrowed units carry **market-at-borrow** basis (no USD acquisition cost fabricated beyond market).
- A matched `REPAY` nets the opening `BORROW` on the `bybit-pledge:<uid>:<asset>` revolving key and
  realizes **$0** on the principal; any repay **residual** (repay quantity − borrowed principal) is
  loan **interest / P&L**, not a spot disposal.

## Consequences

- FH-only `Crypto Loans` BORROW/REPAY (TON, MNT) are materialized; the TON seq-5472 withdrawal is
  funded by the borrowed inventory, driving the phantom shortfall to 0 and opening/closing
  `borrow_liabilities` (`bybit-pledge:33625378:TON`).
- Early-2025 MNT UTA `TRANSACTION_LOG` loans are **not** duplicated (FH `Loans` product stays demoted;
  the coverage guard is belt-and-braces).
- The USDT `Loans / Pledge Assets` −523 collateral lock does not become a BORROW inflow.
- No schema or downstream change; behavior verified via `--skip-frontend` reset + re-audit.

## Related but out of scope

- **BLOCKER-5 §5D (TON on-chain-earn reallocation phantom)** co-lands in the same reset but is a
  **separate** classification/pairing defect (see `results/blockers.md`): Bybit `On-chain Earn`
  subscribe/redeem is a same-asset FUND→(off-chain)→FUND round trip with **no** `EARN_FLEXIBLE_SAVING`
  counterpart, so both legs land on `:FUND` and the existing FUND↔EARN earn sub-pool pairer cannot
  pair them. Its fix is a distinct same-asset FUND self-round-trip continuity pairing and is tracked
  separately; it is **not** governed by this ADR.
