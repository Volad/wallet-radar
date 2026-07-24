# ADR-014 — Portfolio conservation gate at dashboard publication

**Status:** Proposed (§D2 NEC formula superseded by [ADR-034](ADR-034-nec-transaction-scan.md))
**Date:** 2026-05-16
**Inputs:** `cycle-autorun/cycle-data/cycle/5/results/n19-cross-validation.md` §3, `n19-implementation-plan.md` §H; user decisions 2026-05-16 (uniform NEC formula keyed on `isMember`; unified gate threshold; no `EXTERNAL_PASSTHROUGH` / `EXTERNAL_TERMINAL` taxonomy).

> Supersedes the earlier draft of this ADR that distinguished `EXTERNAL_PASSTHROUGH` vs `EXTERNAL_TERMINAL` pools and used a conditional NEC formula. Both terms are dropped per [ADR-009](ADR-009-ownership-classification-via-universe.md). The gate now uses a single uniform NEC formula over all non-member counterparties (per [ADR-015 §D9](ADR-015-per-counterparty-basis-pool.md)) and a single threshold `max($50, 1% × MtM)`.

## Context

Cycle/5 N19 produced an independent simulation that proved the conservation invariant from raw evidence:

| Quantity | USD |
|---|---:|
| Net External Capital (independently derived) | $14,029.88 |
| Live Portfolio (Bybit live API + EVM + Solana + TON) | $11,797 |
| **Implied Total PnL = MtM − NEC** | **−$2,233** (universe) / −$2,618 (in-scope only) |

The dashboard reported across recent builds:

| Build | Reported PnL | Truth | Error |
|---|---:|---:|---:|
| Cycle/4 close-out | Realised +$700, Total ≈ +$700 | −$2,343 to −$2,820 | $3,000–3,500 phantom gain |
| Cycle/5 mid-build | Realised +$1,500, Total ≈ +$1,500 | same | $3,800 phantom gain |
| Cycle/5 final build (post N1-N18) | Realised −$348, Total ≈ −$1,500 | same | $800–1,300 under-reported loss |

Each iteration moved the needle by hundreds of dollars but never crossed below the conservation truth. With no automatic invariant check, the team relied on manual cross-validation cycles. The structural problem is that **the AVCO engine produces a self-consistent number that may still be off by thousands of dollars**, and there is no in-band signal that catches it.

The conservation invariant is mechanically derivable in O(1) at dashboard read time. It MUST be computed every time the dashboard publishes; a breach MUST surface to the user, not to the audit pipeline.

## Decision

### D1. New service `PortfolioConservationGate`

Add `backend/src/main/java/com/walletradar/ingestion/wallet/query/PortfolioConservationGate.java` invoked by [SessionDashboardQueryService](backend/src/main/java/com/walletradar/ingestion/wallet/query/SessionDashboardQueryService.java) after building the standard `SummaryView`.

### D2. Computation — uniform formula, no taxonomy branches

> **Note:** The NEC computation in D2 (pool-delta formula) was superseded by [ADR-034](ADR-034-nec-transaction-scan.md) which uses a direct transaction scan with per-flow guards. The MtM formula, threshold, response fields, and breach logging in D3–D8 remain in effect.

For a given session at request time:

```
nec  = Σ_{c : AccountingUniverse.isMember(c) = false}
         (pool[c].lifetimeInBasisUsd − pool[c].lifetimeOutBasisUsd)
                                                              // see ADR-015 §D9; no EXTERNAL_PASSTHROUGH /
                                                              // EXTERNAL_TERMINAL split — every non-member
                                                              // counterparty contributes its lifetime delta.

mtm  = Σ position.qty × position.currentPrice                 // OWN ledgers for every member with
        for member m where m.backfillEnabled = true           // backfillEnabled=true (existing per-wallet
                                                              // AVCO ledger output; unchanged path)
     + Σ pool[m].qtyHeld × pool[m].avcoUsd                    // for every member m where backfillEnabled=false
        for member m where m.backfillEnabled = false          // (e.g. SOLANA / TON wallets) — pool is the
                                                              // canonical state holder per ADR-015 §D2
     + Σ snapshot.valueUsd                                    // optional externally provided live snapshot
        for any member m with snapshot present                // (Bybit live API, etc.); replaces the
                                                              // pool/AVCO contribution for that member when
                                                              // present and trusted

reportedPnL       = totalRealisedPnlUsd + totalUnrealisedPnlUsd     // existing dashboard math
expectedPnL       = mtm − nec
conservationDelta = reportedPnL − expectedPnL
threshold         = max($50, 0.01 × mtm)
conservationBreached = |conservationDelta| > threshold
```

The formula has **no taxonomy branches**. Every non-member counterparty contributes via its pool's lifetime delta — fiat onramps, unknown EOAs, protocol contracts, bridge contracts, hot wallets that fail to link. The "terminal vs passthrough" distinction emerges from the data (a one-sided pool yields a non-zero delta; a balanced pool yields zero) per [ADR-015 §D2](ADR-015-per-counterparty-basis-pool.md). No `treatAsTerminal` flag, no `EXTERNAL_*` enum constant, no per-counterparty configuration.

`isMember(c)` is determined by [AccountingUniverseService](backend/src/main/java/com/walletradar/session/application/AccountingUniverseService.java): a counterparty address counts as OWN iff it is registered in the session's `AccountingUniverse` (per [ADR-007](ADR-007-mandatory-accounting-universe-membership.md) §D2). The `Member.backfillEnabled` flag does NOT affect membership; it only steers which MtM source feeds that member (per [ADR-009 §D1](ADR-009-ownership-classification-via-universe.md)).

### D3. MtM source priority per member

| Member shape | Primary MtM source | Fallback |
|---|---|---|
| `backfillEnabled = true` (EVM hot wallets, Bybit master) | Existing per-`accountRef` AVCO ledger and position snapshots | If a live-snapshot is available (e.g. Bybit `/v5/account/wallet-balance`), it replaces the AVCO `qty` for the snapshot's listed assets while AVCO still supplies `unitPriceUsd` for valuation. This is the existing "live qty + AVCO basis" pattern from [ADR-006](ADR-006-cycle5-bybit-stream-authority-and-earn-subaccount.md). |
| `backfillEnabled = false` (Solana, TON, future) | Per-counterparty pool: `pool[m].qtyHeld × pool[m].avcoUsd` (per [ADR-015 §D2](ADR-015-per-counterparty-basis-pool.md)) | If a live snapshot is supplied via the session config (operator-provided check value, or external indexer integration when one is added), it replaces the pool-derived qty for the snapshot's listed assets. |

The fallback path is symmetric: any externally provided live snapshot can override the per-asset qty for the member it covers, regardless of the `backfillEnabled` value. This is what allows the gate to consume Bybit's live wallet balance and the user-reported Solana / TON balances side-by-side without taxonomy branches.

### D4. Threshold rationale

| Threshold component | Why |
|---|---|
| `$50 absolute floor` | Rounding noise and CoinGecko fixture jitter on small portfolios — a $11,797 portfolio at ±0.4% per-asset price drift is ≈ $50 cumulative. |
| `1 % of mtm relative` | Scales for larger portfolios. A $1M portfolio cannot reasonably claim 0.01 % accuracy across protocols. |

The pre-fix audit shows the dashboard was off by ~$800–$3,500 (8–30 % of MtM); the threshold catches anything > 1 % which is well below the historical regression band but above mechanical noise.

The same `max($50, 1% × MtM)` threshold drives the **frontend banner trigger** — there is no separate, looser frontend threshold. Backend and frontend agree on a single boolean `conservationBreached`.

### D5. Response fields

The dashboard JSON response gains:

```jsonc
{
  "summary": {
    "totalRealisedPnlUsd": ...,
    "totalUnrealisedPnlUsd": ...,
    // NEW:
    "netExternalCapitalUsd":     14029.88,
    "markToMarketUsd":           11797.00,
    "expectedPnlUsd":            -2233.00,
    "reportedPnlUsd":            -1500.00,
    "conservationDeltaUsd":        733.00,
    "conservationThresholdUsd":    117.97,
    "conservationBreached":         true
  }
}
```

Frontend renders a non-blocking warning banner when `conservationBreached=true` (per `n19-implementation-plan.md` §Phase 4). The dashboard MUST still publish — the gate is informational, not blocking — because gating reads on a backend bug would render the app useless during incidents. Exception: empty universe (per [ADR-007](ADR-007-mandatory-accounting-universe-membership.md) §D3) is a separate hard fail.

### D6. Snapshot-first principle preserved

`PortfolioConservationGate` MUST NOT introduce new RPC calls or heavy Mongo aggregations. All input quantities are already computed for the existing summary view or available from the bounded pool collection:

- `totalRealisedPnlUsd`, `totalUnrealisedPnlUsd` — existing pre-aggregated fields on `ledger_summary_snapshots`.
- `nec` — single bounded find on `counterparty_basis_pools` filtered by `universeId` and `isMemberAtLastTouch=false`; result is summed in code.
- `mtm` — sum of existing position snapshots (for `backfillEnabled=true` members) plus the same bounded pool find filtered to `isMemberAtLastTouch=true, qtyHeld > 0` for `backfillEnabled=false` members. Both reads share the same pool fetch; one round-trip.
- Externally provided live snapshots — already in memory from the session settings / cached integration calls (no additional RPC).

Total cost: one additional bounded `find` over `counterparty_basis_pools` per dashboard request. Cached per session with TTL aligned with the dashboard's existing summary cache (per [SessionDashboardQueryService](backend/src/main/java/com/walletradar/ingestion/wallet/query/SessionDashboardQueryService.java)).

### D7. Diagnostic role — primary self-diagnostic for upstream defects

The gate is the in-band signal that replaces post-hoc audit cycles. A breach now functions as the **primary self-diagnostic** for three structural failure modes:

1. **Failed FA-001 cross-system links** (per [ADR-013 §D4](ADR-013-cex-cross-system-linking.md)). When a chain ↔ Bybit deposit pair fails to link by `txHash`, the chain leg pushes onto the hot-wallet pool (`UNKNOWN_EOA`, non-member → contributes to NEC) while the Bybit leg pushes onto the user's own EVM-wallet pool (`PERSONAL_WALLET`, member → does NOT contribute to NEC). Net effect: NEC inflates by the failed-link USD value; the gate fires.
2. **Missing `AccountingUniverse` members**. When the user owns a wallet WalletRadar has not been told about (Solana / TON before D-MISSING-NETWORKS / D-MISSING-TON were closed; any future undiscovered EOA), the unrecognised address resolves to `UNKNOWN_EOA` / `UNKNOWN_CONTRACT` and its pool contributes to NEC even though the value never economically left the user. The gate fires until the universe is amended.
3. **Pool-side bugs** ([ADR-015](ADR-015-per-counterparty-basis-pool.md)). A bug in the pool replay (sign error on push/pop, asset-family resolver miss, ordering violation) corrupts either NEC or per-member MtM; the conservation identity breaks; the gate fires.

Beyond these three, any plumbing-level miscount (priced fields not stripped on a linked transfer, AVCO replay running over stale flows, dashboard summary cache drifting) shows up as a delta. The gate cannot tell WHY the delta exists, but it makes the existence loud and bounded; the diagnostic loop then descends into the breakdown logging (D8).

### D8. Conservation-breach logging

When `conservationBreached=true`, the gate emits a structured warn-level log entry with:

- `conservationDelta`, `expectedPnl`, `reportedPnl`, `nec`, `mtm`, `threshold`
- `topNonMemberPoolsByNetCapitalDelta` — the N counterparty pools (non-member) sorted by `|netCapitalDeltaUsd|` desc, each with `counterpartyAddress`, `networkId`, `assetFamily`, `lifetimeInBasisUsd`, `lifetimeOutBasisUsd`, `lastTouchedAt`. Surfaces failed-link hot wallets, undiscovered EOAs, and undersolved fiat onramps.
- `topMemberPoolsByQtyHeld` — for members with `backfillEnabled=false`, the N pools by `qtyHeld × avcoUsd` desc. Surfaces "ghost MtM" coming from a stale pool when an OWN wallet ought to be backfilled.
- `pendingPositions` — top N positions by `unrealisedPnlUsd` (to flag stale pricing).

This is the in-band signal that replaces post-hoc audit cycles. The breakdown is sized to ≤ 20 entries per category — bounded, free.

### D9. Cycle/5 acceptance gate

For cycle/5 close-out, the rebuild is considered passing iff `|conservationDelta| < $500`. Per `n19-implementation-plan.md` Phase 3 acceptance: target `−$2,500 ± $500`. The gate makes this assertable in CI: [N19FullPipelineRebuildTest](backend/src/test/java/com/walletradar/cycle5_n19) asserts `Math.abs(dashboard.summary.conservationDeltaUsd) < 500`.

### D10. Interaction with [ADR-012](ADR-012-borrow-liability-tracker.md) open liabilities

Open BORROW liabilities (qty borrowed but not yet repaid) inflate MtM by the borrowed notional but the user does not own the underlying asset outright. Conservation handles this naturally as long as `totalLiabilityUsd` is treated correctly:

```
adjustedMtm = mtm − totalLiabilityUsd
expectedPnL = adjustedMtm − nec
```

For the cycle/5 audit window all loans are closed (per `n19-defect-catalog.md` D-LOAN-ROUNDTRIP), so `totalLiabilityUsd = 0` and the simple formula in D2 suffices. The adjusted formula is what the gate MUST use when D2 ships alongside [ADR-012](ADR-012-borrow-liability-tracker.md).

### D11. RC-T2 amendment — replay-safe promotion must not confirm dropped on-chain value (ADR-066)

The replay-safe promotion path (`StatValidationService.promoteReplaySafeNeedsReview`
and the fee-only branch of `validate`) previously confirmed any `NEEDS_REVIEW` row
whose only non-zero flows were FEE legs, on the assumption that a fee-only row
carries no economic value. That assumption fails for a non-EVM on-chain row whose
**real economic value was observed but could not be booked** (e.g. a TON
jetton/DeFi transfer with unavailable owner-addressed evidence): the row would be
silently `CONFIRMED` as empty while its value is dropped, understating NEC/MtM and
defeating the conservation gate's diagnostic role (§D7).

RC-T2 gates the promotion strictly on that evidence condition: the TON normalizer
stamps `TON_ONCHAIN_UNRESOLVED_VALUE` (`TonNormalizedTransactionBuilder`) when a
non-zero raw value collapses to a fee-only / empty flow set. `StatValidationService`
treats an `ON_CHAIN` row carrying that marker as **not** replay-safe and keeps it
`NEEDS_REVIEW` (surfacing it for out-of-scope-DeFi review / partial-coverage flag).
EVM rows never carry this marker, so EVM replay-safe promotion is byte-for-byte
unchanged (regression asserted in `StatValidationServiceTest`
`replaySafePromotionRejectsOnChainDroppedValueButStillPromotesEvmFeeOnly`). This is
an amendment note to this ADR, not a standalone ADR; see ADR-066 for the per-family
resolution SPI it ships with.

## Consequences

### Positive
- Every dashboard read carries its own self-test. Phantom PnL of the magnitude seen in cycles 4/5 surfaces immediately rather than requiring a manual audit cycle.
- Single uniform NEC formula — no taxonomy branches, no `treatAsTerminal` flag, no per-counterparty configuration — so a labelling mistake cannot hide an NEC contribution.
- Backend and frontend share one threshold and one boolean — no drift between "what backend flags" and "what frontend banners".
- Forces consistent labelling discipline: an address is either a member of `AccountingUniverse` (OWN, not in NEC) or it isn't (EXTERNAL, in NEC). Confusion in the universe surfaces as a conservation delta.
- The gate becomes the canonical signal for FA-001 link failures, pool bugs, and undiscovered OWN wallets — failure modes that previously required offline audits.

### Negative
- The gate cannot tell WHY the delta exists — only that it does. The breakdown logging (D8) helps but the diagnostic loop is still manual.
- Pricing drift between MtM and NEC pricing sources can produce small false-positive deltas. Mitigated by the 1 % relative threshold (D4).
- For very small portfolios (e.g. $200), the $50 floor is wide. Acceptable — the gate is designed for the realistic cycle/5 portfolio scale.

### Migration
1. Add `PortfolioConservationGate` and wire into `SessionDashboardQueryService`.
2. Extend the dashboard DTO with the new fields (per D5).
3. Frontend: render the banner when `conservationBreached == true` (per [n19-implementation-plan.md §Phase 4](cycle-autorun/cycle-data/cycle/5/results/n19-implementation-plan.md)).
4. Add `N19FullPipelineRebuildTest` assertion `|conservationDelta| < 500` to gate cycle/5 close-out.

## Acceptance criteria

| # | Assertion | Test |
|---|---|---|
| AC-014-1 | `GET /api/v1/sessions/{id}/dashboard` response includes `summary.conservationDeltaUsd`, `summary.conservationBreached`, `summary.netExternalCapitalUsd`, `summary.markToMarketUsd`, `summary.expectedPnlUsd`, `summary.reportedPnlUsd`, `summary.conservationThresholdUsd` | `SessionDashboardQueryServiceTest` |
| AC-014-2 | After full rebuild on N19 raw bundle, `Math.abs(dashboard.summary.conservationDeltaUsd) ≤ 500` and `Math.abs(dashboard.summary.conservationDeltaUsd) ≤ max(50, 0.01 × mtm)` | `N19FullPipelineRebuildTest` |
| AC-014-3 | `expectedPnlUsd == mtm − nec` exactly (BigDecimal arithmetic, no rounding) | `PortfolioConservationGateTest` |
| AC-014-4 | `nec` is computed as `Σ_{c : isMember(c)=false} (pool[c].lifetimeInBasisUsd − pool[c].lifetimeOutBasisUsd)` over `counterparty_basis_pools`; switching `isMember` for a counterparty (universe upsert / removal) re-classifies its pool's contribution on the next read with no replay | `PortfolioConservationGateTest` |
| AC-014-5 | When a member has `backfillEnabled=false` and `qtyHeld > 0` on its per-counterparty pool, `mtm` includes `qtyHeld × avcoUsd` for that member; when an externally provided live snapshot is present for the same member, the snapshot's qty replaces the pool-derived qty for the snapshot's listed assets | `PortfolioConservationGateTest` |
| AC-014-6 | When a member has `backfillEnabled=true`, `mtm` includes the existing per-`accountRef` AVCO ledger contribution unchanged; the bounded pool find still occurs but its qty does NOT double-count those assets | `PortfolioConservationGateTest` |
| AC-014-7 | `conservationBreached=true` produces a structured warn log with `topNonMemberPoolsByNetCapitalDelta`, `topMemberPoolsByQtyHeld`, and `pendingPositions` | `PortfolioConservationGateTest` |
| AC-014-8 | When [ADR-012](ADR-012-borrow-liability-tracker.md) is deployed and `totalLiabilityUsd > 0`, `mtm` is replaced by `adjustedMtm = mtm − totalLiabilityUsd` in the conservation check | `PortfolioConservationGateTest` |
| AC-014-9 | A synthetic failed FA-001 link (per [ADR-013 §D4](ADR-013-cex-cross-system-linking.md)) inflates NEC by exactly the failed-link USD value and trips `conservationBreached` once that delta exceeds `max($50, 1% × MtM)` | `BybitCexCrossLinkFailureTest` |
| AC-014-10 | The gate performs no RPC calls and only the bounded reads enumerated in D6 (verified via Mongo / HTTP listener spies) | `SessionDashboardQueryServiceTest` (perf assertion) |

## References

- [ADR-034](ADR-034-nec-transaction-scan.md) — NEC transaction-scan algorithm (supersedes §D2 pool-delta formula).
- [cycle-autorun/cycle-data/cycle/5/results/n19-cross-validation.md](cycle-autorun/cycle-data/cycle/5/results/n19-cross-validation.md) §3.
- [cycle-autorun/cycle-data/cycle/5/results/n19-implementation-plan.md](cycle-autorun/cycle-data/cycle/5/results/n19-implementation-plan.md) §H.
- [cycle-autorun/cycle-data/cycle/5/results/external-truth.md](cycle-autorun/cycle-data/cycle/5/results/external-truth.md) — authoritative balance ground truth used by the gate during cycle/5 acceptance.
- [ADR-007](ADR-007-mandatory-accounting-universe-membership.md) — `AccountingUniverse` and `Member.backfillEnabled`; empty-universe hard-fail.
- [ADR-009](ADR-009-ownership-classification-via-universe.md) — ownership / indexing dimensions; uniform classification feeding the gate.
- [ADR-012](ADR-012-borrow-liability-tracker.md) — open-liability adjustment to MtM.
- [ADR-013](ADR-013-cex-cross-system-linking.md) — failed FA-001 links surface here as the primary self-diagnostic.
- [ADR-015](ADR-015-per-counterparty-basis-pool.md) §D2, §D9 — per-counterparty pool semantics and the conservation identity this gate enforces.
- [backend/src/main/java/com/walletradar/ingestion/wallet/query/SessionDashboardQueryService.java](backend/src/main/java/com/walletradar/ingestion/wallet/query/SessionDashboardQueryService.java).
- [frontend/src/app/features/dashboard/dashboard.component.ts](frontend/src/app/features/dashboard/dashboard.component.ts) — banner rendering location.
