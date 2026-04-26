# Task 150: Cycle 83 Protocol, Counterparty, and Protocol Valuation Closeout

Status: Implemented for clarification/reclassification performance and portfolio snapshot phase split; residual audit blockers documented
Owner: business-analyst + system-architect
Implementation owners: backend-dev, frontend-dev
Audit basis:

- `auto-loop-handoff/artifacts/cycle/83/scorecard.md`
- `auto-loop-handoff/artifacts/cycle/83/financial-analyst/report.md`
- `auto-loop-handoff/artifacts/cycle/83/financial-analyst/required-changes.md`
- `auto-loop-handoff/artifacts/cycle/83/financial-analyst/comprehensive-implementation-package.md`
- `auto-loop-handoff/artifacts/cycle/83/financial-analyst/protocol-counterparty-closeout-package.md`
- `auto-loop-handoff/artifacts/cycle/83/handoffs/backend-dev.md`

## Goal

Close the remaining goal blockers after mandatory AVCO coverage became clean:

- keep the mandatory AVCO blocker surface clean;
- resolve or terminally classify supported protocol/counterparty gaps;
- value material protocol positions in the dashboard or explicitly surface
  unsupported material valuation lanes.

This task must not add AVCO movement repair for mandatory assets. Current
runtime evidence shows `blockingNeedsReview = 0`, `excludedLedgerPoints = 0`,
and mandatory asset/family `blockingUncovered = 0`.

## Business Acceptance Criteria

- `blockingNeedsReview = 0` after rebuild.
- `excludedLedgerPoints = 0` after rebuild.
- mandatory exact and family `blockingUncovered = 0` after rebuild.
- `BYBIT_TRANSFER_SHADOW_ROW` rows stay excluded, audit-visible, and produce no
  ledger points.
- active Bybit `withdraw_deposit` rows stay active `EXTERNAL_TRANSFER_IN/OUT`
  and replay-visible.
- Bybit `BORROW` / `REPAY` rows stay terminal unsupported until liability
  accounting is explicitly scoped.
- Supported protocol gaps close to `recoverableProtocolGaps = 0`.
- Supported counterparty gaps close to `recoverableCounterpartyGaps = 0`.
- Terminal protocol/counterparty gaps are persisted and counted separately by
  evidence state.
- `AMANUSDC` is valued from the current on-chain balance quantity against the
  verified underlying quote and marked as `AAVE_INDEX_ACCRUING`.
- `VARIABLEDEBTMANUSDE` is valued as a negative Aave liability against the
  verified underlying quote and marked as `AAVE_INDEX_ACCRUING`.
- `GM: ETH/USD [WETH-USDC]` is valued from a cached GMX protocol/oracle
  snapshot or remains explicitly unsupported with portfolio parity still open.

## Edge Cases

- In scope: top SWAP, BRIDGE, LENDING, VAULT, and LP contracts/selectors listed
  in the cycle 83 closeout package. Expected: deterministic protocol and
  counterparty attribution without changing canonical type or flows.
- In scope: external transfers. Expected: `counterpartyType` is one of `CEX`,
  `PERSONAL_WALLET`, `PROTOCOL`, `BRIDGE`, `UNKNOWN_EOA`,
  `UNKNOWN_CONTRACT`, or `GENUINE_MISSING_SOURCE`.
- In scope: rows that cannot be resolved after registry, selector, event,
  token-counterparty, venue-correlation, and repeated-address checks. Expected:
  persisted terminal state, not an open recoverable gap.
- In scope: Aave current dashboard valuation. Expected: 1:1 underlying quote is
  allowed only for current on-chain `balanceOf` quantities.
- Out of scope: historical scaled-balance PnL for Aave receipt/debt tokens.
- Out of scope: hash-specific, wallet-specific, or hand-curated production
  exceptions.
- Out of scope: live RPC or market-provider work inside dashboard GET.

## Architecture Decision

```text
raw_transactions
   |
   v
normalization
   |
   v
clarification
   |- claim PENDING_CLARIFICATION rows and active ON_CHAIN NEEDS_REVIEW rows
   |- fetch one full transaction receipt per row in parallel
   |- merge receipt/log evidence into raw_transactions.clarificationEvidence
   |- mark rows PENDING_RECLASSIFICATION
   |
   v
reclassification
   |- run classifier from persisted raw evidence
   |- apply protocol/counterparty enrichment from persisted evidence only
   |- do not run wide related lifecycle discovery inline
   |
   v
pricing / protocol valuation refresh jobs
   |- current_price_quotes
   |- protocol valuation snapshots
   |
   v
dashboard GET
   snapshot-only read model
```

- Keep the modular monolith and MongoDB snapshot-first read model.
- Clarification only collects missing transaction evidence. It does not make a
  final classification decision.
- Reclassification is the classification boundary after receipt evidence has
  been persisted.
- Protocol and counterparty correctness belongs to classification/enrichment
  from persisted evidence, not AVCO replay.
- Dashboard valuation belongs to pricing/read-model services, not cost-basis
  replay.
- Protocol/counterparty resolution state must be produced by deterministic
  classification/enrichment from persisted evidence if the audit acceptance
  keeps requiring it. It is not a separate clarification metadata pass.
- Wide GMX / async lifecycle discovery is not part of the normal
  reclassification hot path. It remains a separate bounded recovery concern.
- Dashboard GET must read existing snapshots only and must not call live RPC or
  market providers.
- No paid indexers, managed databases, Kubernetes, GraphQL, BFF, or microservice
  split is introduced.

## Performance Decision

The clarification and reclassification stages were too slow for the observed
runtime size: about 144 clarification rows took several minutes, then the same
rows spent additional time in reclassification. The bottleneck was not batch
size alone. The previous flow ran multiple passes:

- metadata receipt clarification;
- separate full-receipt / transfer-evidence clarification;
- standalone protocol/counterparty metadata enrichment;
- reclassification with inline wide related lifecycle discovery.

The target flow is:

1. Claim `PENDING_CLARIFICATION` rows and active on-chain `NEEDS_REVIEW` rows
   in the same clarification stage.
2. Fetch one full receipt/log payload per row, in parallel within the batch.
3. Decode ERC20 token transfers directly from receipt logs without explorer
   `tokentx` calls or per-token metadata RPC calls. Existing raw/explorer token
   metadata may be reused when already present.
4. Persist the evidence to raw transaction clarification evidence.
5. Mark rows `PENDING_RECLASSIFICATION`.
6. Reclassify from persisted evidence in the next stage.

No separate metadata pass or second review-tail drain is required for this flow.
If the full receipt cannot be fetched, the row must not wait in
`PENDING_CLARIFICATION` for multi-minute retry windows during the main pipeline;
it is returned to `PENDING_RECLASSIFICATION` with
`CLARIFICATION_FULL_RECEIPT_UNAVAILABLE` so classification can either resolve
from existing evidence or terminalize the unsupported row.
Rows that still require native settlement transfer evidence, LP position
correlation, or Euler batch decoder enrichment after receipt clarification are
not retried in the hot path. They are allowed to continue to pricing/replay
when they already have replayable canonical flows; otherwise they are
terminalized as recovery-required review rows. A separate bounded recovery job
is the right place for native internal-transfer scans and deeper unsupported
protocol decoding. Internal native transfers are not derivable from a normal
transaction receipt.
Expensive related lifecycle discovery with a wide block window must not run
inline for every reclassified row; it should be handled by a future explicit
GMX/async recovery job if needed.

## Portfolio Snapshot Phase Decision

Runtime evidence after the clarification/reclassification fix showed that the
`ACCOUNTING_REPLAY` stage still appeared slow even when cost basis itself had no
remaining queue. The extra time came from live portfolio evidence refresh:

- bounded on-chain balance refresh for about 210 wallet/network/asset buckets;
- current quote refresh for the positive live-balance symbols.

These are dashboard snapshot inputs, not AVCO replay inputs. The architecture
therefore separates replay and live snapshot refresh:

```text
PRICING
   |
   v
ACCOUNTING_REPLAY
   |- stat validation
   |- deterministic AVCO replay
   |- asset_ledger_points persistence
   `- COMPLETE
   |
   v
PORTFOLIO_SNAPSHOT_REFRESH
   |- Ankr-supported networks: keep existing aggregated Ankr path
   |- BlockScout networks: use grouped wallet/network balance endpoint first
   |- remaining explorer networks: use bounded parallel Etherscan-style requests
   |- token decimals: use persisted evidence / previous balance metadata first,
      explorer decimals only as fallback
   |- RPC remains last fallback, not primary strategy
   |- current_price_quotes refresh through existing market adapters only for
      market symbols
   `- COMPLETE
```

The snapshot stage must be idempotent and session-scoped. It may replace
`on_chain_balances` for the session with a fresh bounded snapshot, but it must
not mutate canonical normalized transactions, historical prices,
`asset_ledger_points`, or AVCO state. Dashboard GET endpoints stay snapshot-only
and must not call live RPC, explorers, Ankr, or market providers.

## Runtime Performance Follow-up

The rebuild after unified clarification showed the remaining long phases were
not AVCO replay:

- `LINKING`: about 56 seconds. The dominant cost is sequential LI.FI status
  lookup misses. Rows with no persisted protocol status can wait up to the
  configured 5 second LI.FI timeout one by one, even when a deterministic local
  destination candidate already exists in normalized rows.
- `PORTFOLIO_SNAPSHOT_REFRESH`: about 43 seconds. The stage refreshed 212
  wallet/network/asset buckets, but most were zero-balance evidence. Non-Ankr
  explorer/RPC fallback and current quote lookups dominate the wall clock.

Performance decisions:

1. LI.FI bridge linking must be local-first. The regular `LINKING` sweep should
   try deterministic same-wallet/cross-network destination matching from
   persisted normalized rows before any LI.FI status lookup. Remaining status
   lookups must run with bounded parallelism and persist negative retry state
   for misses. Receiving-transaction discovery is not part of the regular
   linking hot path; the sweep may seed a source anchor from status evidence and
   leave actual destination discovery to explicit recovery/direct link paths.
2. LI.FI status misses must be cached as protocol-status miss evidence with a
   bounded retry timestamp, so repeated rebuilds do not pay the same timeout in
   the hot path.
3. Snapshot balance candidates should represent live-relevant positions, not
   every historical asset ever touched. The first-pass universe is net non-zero
   wallet/network/asset positions from confirmed on-chain flows.
4. Current quote refresh should reuse fresh `current_price_quotes` and resolve
   market symbols with bounded parallelism. Protocol/non-market symbols stay out
   of market providers.

Acceptance targets for the optimized path:

- `LINKING` should no longer scale linearly with LI.FI timeout misses.
- `PORTFOLIO_SNAPSHOT_REFRESH` should process materially fewer balance buckets
  when most historical assets net to zero.
- The final state must remain `blockingNeedsReview = 0`, `pending* = 0`, and
  `PORTFOLIO_SNAPSHOT_REFRESH / COMPLETE`.

Implemented runtime behavior:

- `OnChainClarificationJob` now drains the unified `PENDING_CLARIFICATION`
  receipt workflow only.
- `MetadataClarificationWorkflowHandler` claims both `PENDING_CLARIFICATION`
  rows and active on-chain `NEEDS_REVIEW` rows, then fetches full receipt/log
  evidence in parallel within each batch.
- `PendingClarificationQueryService` no longer excludes
  `NATIVE_SETTLEMENT_TRANSFER_EVIDENCE_REQUIRED` or
  `LP_POSITION_CORRELATION_REQUIRED`; all pending clarification rows are
  eligible for the unified receipt pass subject only to retry/lease limits.
- `OnChainReclassificationJob` no longer drains standalone metadata
  enrichment after reclassification.
- `OnChainReclassificationService` no longer runs related lifecycle discovery
  inline. If reclassification still returns `PENDING_CLARIFICATION` after
  clarification attempts are exhausted, replayable rows with canonical flows
  continue to `PENDING_PRICE` with `CLARIFICATION_ATTEMPTS_EXHAUSTED`; rows
  without replayable flows are terminalized to `NEEDS_REVIEW` instead of
  becoming dead pending rows.

## Backend Tasks

1. Add persisted metadata fields to normalized transactions:
   `protocolResolutionState`, `counterpartyResolutionState`,
   `counterpartyType`, and optional evidence notes.
2. Extend protocol enrichment to set `RESOLVED_EXACT` / `RESOLVED_FAMILY` when
   registry/full-receipt evidence resolves a protocol and terminal states when
   supported rows remain unresolved after evidence checks.
3. Extend counterparty enrichment to set row-local `counterpartyAddress`,
   `counterpartyType`, and `counterpartyResolutionState`; keep
   `matchedCounterparty` separate.
4. Add registry entries for the cycle 83 top contracts/selectors where they are
   not already present.
5. Extend coverage telemetry to report recoverable and terminal protocol /
   counterparty gap counts separately.
6. Add dashboard protocol valuation for Aave aTokens and variable debt tokens
   using current balance quantity and underlying current quote.
7. Add a GMX material unsupported valuation lane unless a cached GMX
   reader/oracle snapshot is available in the current codebase.
8. Preserve replay exclusion and Bybit shadow invariants.
9. Replace the two-pass clarification flow with one parallel full-receipt
   clarification pass over `PENDING_CLARIFICATION`.
10. Keep reclassification as a separate stage, but remove inline wide related
   lifecycle discovery from the hot path.
11. Do not add new runtime telemetry for this performance correction; validate
    with existing stage logs, tests, and post-rebuild acceptance scripts.
12. Add `PORTFOLIO_SNAPSHOT_REFRESH` as a distinct pipeline stage after
    `ACCOUNTING_REPLAY`.
13. Move `onChainBalanceRefreshService.refreshCurrentBalances(...)` and
    `currentPriceQuoteRefreshService.refreshForSessionBalances(...)` from
    `CostBasisReplayJob` into a new portfolio snapshot refresh job triggered by
    accounting replay completion and by the resume watchdog.
14. Keep Ankr-supported balance refresh behavior unchanged.
15. Parallelize non-Ankr explorer balance refresh with bounded lanes. Default
    target: 4 lanes globally, with RPC used only as fallback after explorer
    failure.
16. Keep stage logs sufficient for runtime measurement:
    `costbasis-replay`, `portfolio-snapshot-refresh`, on-chain balance refresh
    outcome, current quote refresh outcome, and total rebuild duration.
17. Remove the second full-receipt review-tail drain from
    `OnChainClarificationJob`; active on-chain `NEEDS_REVIEW` rows are handled
    by the same full-receipt clarification workflow as `PENDING_CLARIFICATION`.
18. Prefer grouped BlockScout balance refresh before Etherscan-style per-token
    explorer calls for BlockScout networks.
19. Persist token decimals on `on_chain_balances` and resolve decimals from
    previous balance metadata or raw clarification/explorer evidence before
    calling explorer token metadata endpoints.
20. Do not call Binance/CoinGecko/current market adapters for protocol position
    display symbols such as `GM: ETH/USD [WETH-USDC]`; these remain protocol
    valuation responsibilities, not market ticker lookups.

## Frontend Tasks

1. Extend strict dashboard DTO/model types with protocol valuation metadata if
   backend exposes it.
2. Surface negative liability values without treating them as missing prices.
3. Keep unsupported material valuation rows visible with explicit issue text.
4. Do not add client-side protocol pricing logic.
5. Add an explicit pipeline label for `PORTFOLIO_SNAPSHOT_REFRESH`, for example
   `Portfolio snapshot`, so users can distinguish AVCO replay from live balance
   and quote refresh.

## Verification

```bash
./gradlew :backend:test
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
scripts/prod-reset-rebuild-backend.sh
scripts/avco/phase-coverage.sh --session-id c584c760-b228-45fc-ae0f-84f7cd7bfd8f
scripts/avco/dashboard-coverage.sh --session-id c584c760-b228-45fc-ae0f-84f7cd7bfd8f
```

Expected post-run for this task:

- `pendingClarification = 0`
- `pendingPrice = 0`
- `pendingStat = 0`
- `excludedLedgerPoints = 0`
- mandatory asset/family `blockingUncovered = 0`
- clarification/reclassification complete within the target runtime budget
- final pipeline state is `PORTFOLIO_SNAPSHOT_REFRESH / COMPLETE`

Actual verification on 2026-04-26 after final `scripts/prod-reset-rebuild-backend.sh`:

- `./gradlew :backend:test` passed.
- `git diff --check` passed.
- `scripts/prod-reset-rebuild-backend.sh` completed and restarted
  `walletradar-backend-prod` / `walletradar-frontend-prod`.
- Runtime stage logs:
  - on-chain normalization: `processed=3183`, `durationMs=3822`
  - Bybit normalization: `processed=2635`, `durationMs=6259`
  - clarification plus review-tail recovery: `processed=146`,
    `durationMs=129944`
  - reclassification: `processed=146`, `durationMs=567`
  - linking: `processed=222`, `durationMs=70065`
  - pricing: `processed=3513`, `durationMs=16234`
  - accounting replay: `processed=5724`, `durationMs=82345`
- `scripts/avco/phase-coverage.sh --session-id c584c760-b228-45fc-ae0f-84f7cd7bfd8f`
  at `2026-04-26T09:03:22.751Z`:
  - `rawPending = 0`
  - `confirmed = 5815`
  - `needsReview = 3`
  - `blockingNeedsReview = 0`
  - `excludedNeedsReview = 3`
  - `pendingClarification = 0`
  - `pendingPrice = 0`
  - `pendingStat = 0`
  - `excludedLedgerPoints = 0`
  - mandatory exact/family `blockingUncovered = 0`
  - `recoverableProtocolGaps = 279`
  - `recoverableCounterpartyGaps = 1318`
  - pipeline state: `ACCOUNTING_REPLAY / COMPLETE`
- Regression root cause and fix:
  - `0x67f4e9e1767850c427920a1238903ed6fc56e6cadd4d3defcacc7a99e1329499`
    on `KATANA` became `NEEDS_REVIEW / CLASSIFICATION_FAILED` when the old
    full-receipt review-tail lane was removed from `OnChainClarificationJob`.
  - The row depends on receipt/log recovery and is covered by the existing
    classifier regression `routeSingle with clarified NFT mint becomes
    LP_ENTRY`.
  - After restoring active on-chain `NEEDS_REVIEW` full-receipt recovery, the
    row is `LP_ENTRY / CONFIRMED` with `fullReceiptClarificationAttempts = 1`
    and no `missingDataReasons`.
- `scripts/avco/dashboard-coverage.sh --session-id c584c760-b228-45fc-ae0f-84f7cd7bfd8f`
  at `2026-04-26T09:03:18.093Z`:
  - `portfolioValueUsd = 8355.522736245553`
  - `marketValueCoverageRatio = 0.8575169048852737`
  - top dashboard problem remains `AMANUSDC` `coverage_gap`

Residual audit blockers not closed by this performance correction:

- `recoverableProtocolGaps = 279` and `recoverableCounterpartyGaps = 1318`
  remain under the existing acceptance script. These are not caused by
  clarification/reclassification runtime and should be resolved either by
  deterministic inline classification/enrichment or by revising the acceptance
  contract if standalone metadata resolution state is no longer required.
- Dashboard valuation coverage is still below full parity because protocol /
  history valuation gaps remain visible after accounting replay completes.

Actual verification on 2026-04-26 after portfolio snapshot split:

- `./gradlew :backend:test` passed.
- `cd frontend && npm test -- --watch=false --browsers=ChromeHeadless`
  passed.
- `git diff --check` passed.
- `scripts/prod-reset-rebuild-backend.sh` reset downstream state and rebuilt
  production containers. Script wall time including Docker rebuild was
  `1:45.17`; pipeline runtime is measured separately from runtime logs.
- Runtime pipeline wall-clock from first normalization stage start
  `2026-04-26T11:44:55.220Z` to portfolio snapshot completion
  `2026-04-26T11:50:17.768Z`: about `322.5s`.
- Runtime stage logs:
  - on-chain normalization: `processed=3183`, `durationMs=6397`
  - Bybit normalization: `processed=2635`, `durationMs=8885`
  - clarification plus review-tail recovery: `processed=146`,
    `durationMs=146717`
  - reclassification: `processed=146`, `durationMs=435`
  - linking after reclassification: `processed=221`, `durationMs=84026`
  - pricing: `processed=3513`, `durationMs=16348`
  - accounting replay: `processed=5724`, `durationMs=2462`
  - portfolio snapshot refresh: `processed=225`, `durationMs=66124`
  - on-chain balance refresh inside snapshot:
    `candidates=210`, `refreshed=210`
  - current quote refresh inside snapshot: `symbols=29`, `refreshed=15`
- The accounting replay phase no longer includes live balance or current quote
  refresh. Combined post-pricing replay/snapshot time changed from the previous
  `accounting replay durationMs=82345` to
  `2462 + 66124 = 68586ms`, while user-visible AVCO replay itself is now
  isolated at `2462ms`.
- `scripts/avco/phase-coverage.sh --session-id c584c760-b228-45fc-ae0f-84f7cd7bfd8f`
  at `2026-04-26T11:50:58.084Z`:
  - `rawPending = 0`
  - `confirmed = 5815`
  - `needsReview = 3`
  - `blockingNeedsReview = 0`
  - `excludedNeedsReview = 3`
  - `pendingClarification = 0`
  - `pendingPrice = 0`
  - `pendingStat = 0`
  - `assetLedgerPoints = 9294`
  - `onChainBalances = 210`
  - `excludedLedgerPoints = 0`
  - mandatory exact/family `blockingUncovered = 0`
  - `recoverableProtocolGaps = 278`
  - `recoverableCounterpartyGaps = 1318`
  - pipeline state: `PORTFOLIO_SNAPSHOT_REFRESH / COMPLETE`
- `scripts/avco/dashboard-coverage.sh --session-id c584c760-b228-45fc-ae0f-84f7cd7bfd8f`
  at `2026-04-26T11:50:56.621Z`:
  - `portfolioValueUsd = 8366.16391718192`
  - `marketValueCoverageRatio = 0.8576351912677138`
  - top dashboard problem remains `AMANUSDC` `coverage_gap`
