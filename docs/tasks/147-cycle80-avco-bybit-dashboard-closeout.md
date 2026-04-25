# Task 147: Cycle 80 AVCO, Bybit, Metadata, and Dashboard Valuation Closeout

Status: Ready for implementation
Owner: business-analyst + system-architect
Implementation owners: backend-dev, frontend-dev
Audit basis:

- `auto-loop-handoff/artifacts/cycle/80/scorecard.md`
- `auto-loop-handoff/artifacts/cycle/80/financial-analyst/accounting-failure-analysis.md`
- `auto-loop-handoff/artifacts/cycle/80/financial-analyst/required-changes.md`
- `auto-loop-handoff/artifacts/cycle/80/handoffs/backend-dev.md`

## Goal

Close the cycle 80 audit blockers by fixing the earliest failed supported pipeline
stage, then prove the result with a full production reset/rebuild rerun.

The audit is accepted as a root-cause diagnosis. Evidence already exists for the
main failures, so this task is not a generic data-gap cleanup and must not be
implemented as a repair sweep over already-normalized rows.

## Business Acceptance Criteria

- [ ] `ETH exact` uncovered decreases from `0.005586019294422211`, target `0` or terminal explained remainder.
- [ ] `ETH family` uncovered decreases from `0.014925683276063645`, target `0` or terminal explained yield; family is final-clean.
- [ ] `AVAX exact` uncovered decreases from `0.09345689565839355`, target `0` or terminal explained remainder.
- [ ] `AVAX family` uncovered decreases from `1.1014390815719546`, target `0` or terminal explained remainder.
- [ ] `USDC exact/family` uncovered decreases from `10.271747000000005`, target `0` or terminal explained residual.
- [ ] `BTC exact` remains clean.
- [ ] `BTC family` dust `0.00000008999999999946551` is deterministically carried or terminal-classified by explicit dust policy.
- [ ] `MNT` and `USDT` exact/family surfaces remain clean.
- [ ] Protocol gaps decrease materially from `362` without changing economic type or pricing semantics.
- [ ] Counterparty gaps decrease materially from `1183`; `counterpartyAddress` remains row-local and separate from `matchedCounterparty`.
- [ ] Unmatched bridge counts do not regress from `BRIDGE_OUT=27`, `BRIDGE_IN=9`.
- [ ] Bybit active `NEEDS_REVIEW=94` decreases to zero or every remaining Bybit row has a terminal source/product reason.
- [ ] Dashboard market value is decomposable into token rows whose sum equals `summary.portfolioValueUsd`.
- [ ] Dashboard token rows expose `priceUsd`, `priceSource`, `pricedAt`, `stalenessSeconds`, `isLiveQuote`, and price issue state.
- [ ] A fresh `scripts/prod-reset-rebuild-backend.sh` run completes, followed by a fresh scorecard and dashboard valuation capture.

## Failure Translation

| ID | First failed stage | Evidence state | Type adequacy | Required correction |
| --- | --- | --- | --- | --- |
| FA80-B1 ETH | `normalization + move_basis` | `EVIDENCE_PRESENT_UNLINKED`, `EVIDENCE_PRESENT_UNUSABLE` | too coarse for receipt principal/excess | Split receipt principal/excess before replay and carry same-family principal. |
| FA80-B2 AVAX | `move_basis` | `EVIDENCE_PRESENT_UNLINKED` | adequate | Carry `aAvaWAVAX -> AVAX -> sAVAX` family basis through replay. |
| FA80-B3 USDC | `normalization + linking + move_basis` | `EVIDENCE_PRESENT_UNLINKED`, `EVIDENCE_PRESENT_UNUSABLE` | too coarse for vault principal/excess | Split EVK/ERC-4626 principal/excess, preserve bridge and downstream swap carry. |
| FA80-B4 BTC | `move_basis / verification` | `EVIDENCE_PRESENT_UNLINKED` | adequate | Close dust by deterministic carry or explicit immateriality policy. |
| FA80-B5 Protocol | `clarification / protocol enrichment` | `EVIDENCE_PRESENT_UNLINKED` | adequate | Drain deterministic protocol enrichment in normal clarification. |
| FA80-B6 Counterparty | `clarification + linking` | `EVIDENCE_PRESENT_UNLINKED` | adequate if fields stay separate | Assign row-local peers/contracts without overwriting lifecycle fields. |
| FA80-B7 Bybit | `source availability / venue normalization policy` | `EVIDENCE_PRESENT_UNUSABLE` | venue policy incomplete | Accept `bybit_extracted_events` as v3 source and terminal-classify venue rows. |
| FA80-B8 Dashboard | `pricing / read-model valuation` | `EVIDENCE_PRESENT_UNUSABLE` | current valuation abstraction missing | Separate current quote read model from historical AVCO price cache. |

## Edge Cases

- Case: receipt/vault/lending withdraw returns more underlying than carried principal. Scope: In. Expected: principal remains continuity `TRANSFER`; positive excess is explicit acquisition/yield.
- Case: receipt/vault/lending withdraw returns less underlying than source share quantity. Scope: In. Expected: covered principal basis follows returned quantity; no synthetic realized PnL.
- Case: `aAvaWAVAX -> AVAX -> sAVAX` occurs across Aave withdraw then BENQI staking. Scope: In. Expected: same-family principal basis survives both legs.
- Case: bridge destination quantity differs due to fee/slippage. Scope: In. Expected: linked bridge carry preserves source covered/uncovered ratio on received asset.
- Case: Bybit external transfer has tx hash and network. Scope: In. Expected: link only to exact matching on-chain leg; no amount/time-only inference.
- Case: Bybit borrow/repay. Scope: In as terminal unsupported unless liability accounting is explicitly added. Expected: excluded from AVCO inventory with reason.
- Case: dashboard has no current quote. Scope: In. Expected: row remains visible with explicit missing/stale issue; total remains sum of priced rows.
- Case: arbitrary transaction hash exception. Scope: Out. Expected: no runtime logic keyed by real tx hash, wallet address, or hand-curated bucket.

## Architecture Decision

### A. Decisions and Assumptions

- Keep the modular monolith, Docker deployment, MongoDB, and snapshot-first GET reads.
- Fix supported-flow correctness at the earliest failed stage:
  - normalization for principal/excess canonical shape;
  - clarification/linking for deterministic metadata and lifecycle evidence;
  - replay move-basis for deterministic carry consumption.
- Treat `bybit_extracted_events` as the active v3 Bybit raw source. `external_ledger_raw` remains legacy/audit-only unless restored by a separate source-contract task.
- Add a datastore-backed current-quote snapshot surface for dashboard valuation. Dashboard GET reads Mongo only and never calls live market/RPC providers.
- Keep exact asset coverage, family coverage, and final-clean/proof-clean as separate acceptance surfaces.

### B. Context Diagram

```text
Browser SPA
   |
   | REST, snapshot-first
   v
Spring Boot modular monolith
   |- ingestion/backfill        raw_transactions, integration_raw_events
   |- normalization             canonical rows + principal/excess shape
   |- clarification/linking     protocolName, counterpartyAddress, lifecycle links
   |- pricing                   historical AVCO prices + current quote snapshots
   |- costbasis                 deterministic AVCO replay + asset ledger points
   |- wallet/query              dashboard valuation decomposition
   |
   v
MongoDB
   raw_transactions
   bybit_extracted_events
   normalized_transactions
   historical_prices
   current_price_quotes
   asset_ledger_points
   on_chain_balances
```

### C. Module Boundaries

- `ingestion.pipeline.classification`: canonical type and flow shape only.
- `ingestion.pipeline.clarification`: deterministic metadata enrichment only; no economic reclassification except through `PENDING_RECLASSIFICATION`.
- `ingestion.job.linking`: exact lifecycle links, including Bybit/on-chain custody links.
- `pricing`: historical AVCO price resolution plus current quote snapshot persistence.
- `costbasis.application.replay`: deterministic carry, AVCO, and ledger point materialization.
- `ingestion.wallet.query`: dashboard read model and valuation decomposition.
- `frontend`: display API fields only; no pricing assumptions.

Allowed dependencies remain inward from API/query/job layers to domain services.
No paid indexer, managed database, BFF, GraphQL, or microservice is introduced.

### D. Mongo Collections and Indexes

- Existing:
  - `normalized_transactions`: keep indexes for source/status/type/wallet/network/txHash.
  - `asset_ledger_points`: keep universe/family/order and exact bucket read indexes.
  - `historical_prices`: remains deterministic event-time AVCO price cache.
- New:
  - `current_price_quotes`
    - unique `{symbol: 1, source: 1}`
    - read `{symbol: 1, pricedAt: -1, fetchedAt: -1}`

The dashboard may fall back to latest historical prices only as a stale
diagnostic source and must mark such rows as non-live.

### E. Data Flows

Initial backfill and rerun:

1. raw on-chain and Bybit evidence is loaded.
2. normalization emits supported canonical rows with correct principal/excess flow roles.
3. clarification enriches protocol and row-local counterparty metadata.
4. linking correlates exact bridge and Bybit/on-chain custody pairs.
5. pricing resolves historical AVCO prices and refreshes current quote snapshots when available.
6. replay consumes confirmed canonical rows and writes `asset_ledger_points`.
7. dashboard reads `on_chain_balances`, latest ledger points, and current quote snapshots.

Incremental sync follows the same order over bounded changed sources.

Manual overrides/compensating transactions stay unchanged: they enter replay as
explicit canonical rows and do not mutate historical normalized rows.

### F. Scaling and Cost

- MVP: single VPS, Docker Compose, MongoDB, Caffeine/cache-in-process. Estimated added cost: `$0`.
- Phase 2: Redis only if multiple backend instances need shared current quote locks/cache.
- Phase 3: split pricing/ingestion services only when runtime load proves the boundary.

### G. Risks and Mitigations

- Risk: protocol/counterparty gaps require more registry addresses. Mitigation: generalized resolver paths plus next audit evidence pack; no hash-specific runtime logic.
- Risk: dashboard quote freshness depends on provider availability. Mitigation: expose `pricedAt`, `stalenessSeconds`, `isLiveQuote`, and row issue state.
- Risk: Bybit liabilities need product/accounting model. Mitigation: terminal unsupported exclusion until explicit liability accounting is scoped.
- Risk: full rerun is slow or environment-bound. Mitigation: focused tests first, one production reset/rebuild at the end.

### H. Developer Handoff

Backend:

1. Add/update focused regression tests for cycle 80 exact/family carry surfaces.
2. Harden same-family custody and liquid-staking replay for legacy BUY/SELL principal roles while leaving explicit excess as priced acquisition.
3. Preserve linked bridge carry and prevent amount/time-only Bybit linkage.
4. Terminal-classify Bybit borrow/repay/shadow/source-missing rows with explicit exclusion reasons.
5. Add `current_price_quotes` read model and dashboard DTO fields for quote metadata/staleness/issues.
6. Keep protocol/counterparty enrichment deterministic, row-local, and idempotent.
7. Run backend tests and `scripts/prod-reset-rebuild-backend.sh`.

Frontend:

1. Extend strict dashboard DTOs with quote metadata and covered quantity.
2. Map price source/timestamp/staleness/issue into dashboard token rows.
3. Display price source and stale/missing state without hiding token quantity.
4. Preserve existing Angular routing, REST-only API usage, and component structure.

## Verification

Run:

```bash
./gradlew :backend:test
cd frontend && npm test -- --watch=false
scripts/prod-reset-rebuild-backend.sh
scripts/avco/phase-coverage.sh --session-id c584c760-b228-45fc-ae0f-84f7cd7bfd8f
```

Capture:

- fresh phase coverage scorecard
- Bybit review breakdown
- protocol/counterparty gap totals
- unmatched bridge counts
- dashboard token valuation table with quote source/timestamp/staleness
