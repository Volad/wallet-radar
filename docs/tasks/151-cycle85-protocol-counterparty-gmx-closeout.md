# Task 151: Cycle 85 Protocol, Counterparty, and GMX Valuation Closeout

Status: Complete
Owner: business-analyst + system-architect
Implementation owners: backend-dev, frontend-dev
Audit basis:

- `auto-loop-handoff/artifacts/cycle/85/financial-analyst/report.md`
- `auto-loop-handoff/artifacts/cycle/85/financial-analyst/required-changes.md`
- `auto-loop-handoff/artifacts/cycle/85/financial-analyst/protocol-rule-pack.md`
- `auto-loop-handoff/artifacts/cycle/85/handoffs/backend-dev.md`

## Goal

Close the remaining full-goal blockers after the mandatory AVCO movement
surface became clean.

The current mandatory AVCO acceptance surface must be preserved:

- `blockingNeedsReview = 0`
- `excludedLedgerPoints = 0`
- mandatory exact and family `blockingUncovered = 0`
- Bybit transfer shadow rows remain excluded audit rows and produce no ledger
  points
- Bybit loan rows remain terminal unsupported and excluded until liability
  accounting is explicitly designed

The cycle 85 blockers are:

- `recoverableProtocolGaps = 279`
- `recoverableCounterpartyGaps = 1324`
- material `GM: ETH/USD [WETH-USDC]` dashboard valuation remains zero with
  `GMX_UNSUPPORTED_PROTOCOL_VALUATION`

## Acceptance Criteria

- After rebuild, `recoverableProtocolGaps = 0`.
- After rebuild, `recoverableCounterpartyGaps = 0`.
- Protocol and counterparty terminal states are allowed only when persisted as
  explicit evidence-backed states such as `TERMINAL_METADATA_ONLY`,
  `IRREDUCIBLE_EVIDENCE_MISSING`, or `UNSUPPORTED_SCOPE`.
- Resolved protocol/counterparty rows keep current taxonomy only:
  `CEX`, `PERSONAL_WALLET`, `PROTOCOL`, `BRIDGE`, `UNKNOWN_EOA`,
  `UNKNOWN_CONTRACT`, `GENUINE_MISSING_SOURCE`.
- `ROUTER` is not persisted as a `counterpartyType`; router identity stays in
  protocol/label/registry metadata.
- `OTHER` is not introduced.
- `GM: ETH/USD [WETH-USDC]` receives a non-zero dashboard valuation from a
  cached protocol snapshot source.
- Dashboard GET remains snapshot-only and does not call GMX, RPC, explorers,
  Ankr, Binance, CoinGecko, or Bybit.
- Existing Aave `AAVE_INDEX_ACCRUING` valuation for receipt/debt tokens remains
  unchanged.
- Existing Bybit shadow and unsupported-loan invariants remain unchanged.

## Architecture Decision

```text
normalization / reclassification
   |
   |- protocolName enrichment
   |  `- registry, selector, receipt, provider-status evidence
   |
   |- row-local counterparty enrichment
   |  `- raw from/to, token/internal transfers, registry, bridge/CEX evidence
   |
   v
pricing
   |
   v
accounting replay
   |
   v
portfolio snapshot refresh
   |- current on-chain balances
   |- current market quotes for market symbols
   |- GMX protocol snapshot quotes for GM/GLV symbols
   `- dashboard-ready snapshots
```

- Protocol/counterparty correctness is a normalization/reclassification
  metadata contract, not an AVCO replay rule.
- Metadata enrichment may terminalize rows only after deterministic evidence
  checks have run. This converts open recoverable gaps into auditable terminal
  states; it must not hide rows by deleting evidence or changing canonical
  transaction economics.
- GMX market-token valuation belongs to portfolio snapshot refresh. It must
  persist a `current_price_quotes` snapshot with `source = PROTOCOL_SNAPSHOT`
  so dashboard reads stay pure Mongo reads.
- The GMX MVP valuation uses public GMX REST market snapshots plus a bounded
  total-supply read for the market token. If the protocol snapshot cannot be
  built, the dashboard must keep the explicit unsupported valuation issue.

## Backend Tasks

1. Preserve `protocolResolutionState`, `counterpartyResolutionState`,
   `counterpartyType`, and evidence fields on normalized rows produced by
   normalization and reclassification.
2. Ensure protocol enrichment assigns `RESOLVED_EXACT` / `RESOLVED_FAMILY`
   for registry/provider evidence and explicit terminal states for checked but
   unresolved rows.
3. Ensure counterparty enrichment assigns row-local `counterpartyAddress`,
   current taxonomy `counterpartyType`, `counterpartyResolutionState`, and
   evidence. Keep `matchedCounterparty` as lifecycle metadata.
4. Add `PROTOCOL_SNAPSHOT` as a backend price source for dashboard current
   quotes.
5. Add GMX market-token snapshot valuation for Arbitrum/Avalanche GM symbols:
   resolve market info, token prices, token decimals, pool amounts, and market
   token total supply; persist a current quote under the GM symbol.
6. Dashboard valuation for GMX rows must expose supported valuation metadata
   when a protocol snapshot quote exists and retain
   `unsupported_protocol_valuation` only when the quote is absent.
7. Add focused tests for GMX current quote refresh and dashboard valuation
   metadata.

## Frontend Tasks

1. No client-side protocol pricing logic.
2. Keep strict DTO support for `valuationModel`, `priceSource`,
   `priceIssue`, `pricedAt`, and `unsupportedValuationReason`.
3. Render `PROTOCOL_SNAPSHOT` as a normal supported price source, while still
   surfacing `unsupported_protocol_valuation` if backend reports it.

## Verification

```bash
./gradlew :backend:test
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
scripts/prod-reset-rebuild-backend.sh
scripts/avco/phase-coverage.sh --session-id c584c760-b228-45fc-ae0f-84f7cd7bfd8f
scripts/avco/dashboard-coverage.sh --session-id c584c760-b228-45fc-ae0f-84f7cd7bfd8f
```

Expected post-run:

- `blockingNeedsReview = 0`
- `excludedLedgerPoints = 0`
- mandatory exact/family `blockingUncovered = 0`
- `recoverableProtocolGaps = 0`
- `recoverableCounterpartyGaps = 0`
- material GMX position has non-zero value from `PROTOCOL_SNAPSHOT`
- final pipeline state is `PORTFOLIO_SNAPSHOT_REFRESH / COMPLETE`

Actual post-run on 2026-04-26:

- backend regression suite: `./gradlew :backend:test` passed
- frontend regression suite: `npm test -- --watch=false --browsers=ChromeHeadless`
  passed with 44 specs
- `scripts/prod-reset-rebuild-backend.sh` completed and rebuilt prod backend
  and frontend containers
- pipeline state: `PORTFOLIO_SNAPSHOT_REFRESH / COMPLETE`
- normalized rows: `CONFIRMED = 5815`, excluded `NEEDS_REVIEW = 3`, active
  blocking `NEEDS_REVIEW = 0`
- pending rows: clarification `0`, reclassification `0`, price `0`, stat `0`
- ledger points: `9296`
- on-chain balances: `82`
- Bybit shadow rows: `91`; Bybit shadow ledger points: `0`
- `recoverableProtocolGaps = 0`; terminal protocol gaps remain explicit
  terminal states
- `recoverableCounterpartyGaps = 0`; terminal counterparty gaps remain
  explicit terminal states
- GMX dashboard valuation: `GM: ETH/USD [WETH-USDC]` has non-zero value
  `826.3183347122387` with `valuationModel =
  GMX_MARKET_TOKEN_SNAPSHOT` and no unsupported valuation reason
- GMX current quote: `source = PROTOCOL_SNAPSHOT`, `priceUsd =
  1.820832197375011717440589299457765`
