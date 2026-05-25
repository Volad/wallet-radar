# WalletRadar — Architecture

> **Version:** SAD v3 target summary
> **Last updated:** 2026-04-09
> **Style:** Modular monolith (Spring Boot)

This document is the concise architecture summary for the v3 accounting rewrite.
The full target design, decision log, data-flow details, and cost analysis are in
[architecture-v3.md](architecture-v3.md).

---

## 1. Scope

The current target extends v3 toward an integration-first control plane:

- keep existing raw collection (`raw_transactions`, `sync_status`, `backfill_segments`)
- keep persisted `user_sessions`
- keep an installation-wide tracked wallet universe for canonical on-chain normalization
- rebuild normalization on top of raw evidence
- rebuild pricing on top of canonical normalized flows
- replay AVCO and reconciliation from canonical confirmed events
- add session-owned external integrations starting with Bybit API
- generalize `backfill_segments` so the same backfill infrastructure can drive on-chain and integration acquisition

Not part of this milestone:

- new onboarding/control-plane redesign
- current-balance polling redesign
- portfolio snapshot/chart redesign
- additional CEX providers beyond Bybit

---

## 2. High-Level Context

```text
Browser/API Client
    |
    v
Spring Boot
    |- api/session + api/wallet
    |- session/               persisted user_sessions + integrations + settings
    |- wallet-universe/       tracked_wallets projection
    |- ingestion/backfill/    on-chain + integration segment execution
    |- integration/           provider connectors + raw-event ingestion
    |- normalization/         on-chain + provider canonicalization
    |- pricing/               historical USD resolution
    |- costbasis/             AVCO replay + asset ledger + reconciliation
    |
    +--> MongoDB
         user_sessions
         sync_status / backfill_segments
         tracked_wallets
         raw_transactions
         integration_raw_events
         bybit_extracted_events
         normalized_transactions
         asset_ledger_points
         on_chain_balances
         transfer_links   (planned — FA-001 / ADR-003; not required for continuity replay once metadata+repair paths align)
```

---

## 3. Core Pipeline

**Bybit integration:** cycle/4 epic and rebuild contract — [`docs/tasks/157-cycle4-bybit-ledger-audit-closeout.md`](tasks/157-cycle4-bybit-ledger-audit-closeout.md), [`docs/adr/ADR-005-cycle4-bybit-pipeline.md`](adr/ADR-005-cycle4-bybit-pipeline.md). **Cycle/5** (stream authority N1/N5, transfer basis carry N2/N3, EARN sub-account N4, on-chain N6/N7): [`docs/tasks/158-cycle5-portfolio-phantom-closeout.md`](tasks/158-cycle5-portfolio-phantom-closeout.md), [`docs/adr/ADR-006-cycle5-bybit-stream-authority-and-earn-subaccount.md`](adr/ADR-006-cycle5-bybit-stream-authority-and-earn-subaccount.md); audit artifacts under `cycle-autorun/cycle-data/cycle/5/results/`.

Live-session orchestration is event-driven:

- raw backfill completion is the primary trigger
- stage schedulers are not used for live-session orchestration
- one session-level watchdog may re-emit raw backfill completion when durable
  `sync_status` proves the session is already complete but the original
  completion event was missed
- the approved live-session order is:
  1. raw backfill
  2. on-chain normalization
  3. on-chain clarification
     - receipt enrichment
     - clarification-safe metadata enrichment
     - deterministic protocol-name enrichment
     - deterministic row-local counterparty enrichment
     - lifecycle linking
     - reciprocal internal-transfer pair promotion
     - evidence-only transition to `PENDING_RECLASSIFICATION`
  4. on-chain reclassification
     - same `OnChainClassifier` over canonical raw plus persisted
       `clarificationEvidence`
     - the only stage allowed to change economic type, flows, or pricing
       semantics after clarification
     - if this pass promotes supported Fluid vault/wrapper rows to canonical
       Fluid lifecycle rows without durable full-log evidence, including rows
       still waiting for pricing/stat validation, it runs a bounded
       post-reclassification Fluid receipt recovery before publishing final
       reclassification completion; recovered rows return through
       `PENDING_RECLASSIFICATION` once more
  5. external-integration normalization
  6. exact custody / bridge rematch
  7. pricing
  8. accounting replay

Lending read-model invariant:

- historical lending events construct lifecycle cycle history
- clean lending cycles open only on the first supply/deposit event in context;
  borrow, repay, withdraw, and reward rows cannot start a clean cycle by
  themselves
- current open supply/debt positions require current-state evidence from
  balance snapshots, debt-token balances, or protocol-state snapshots
- vault/NFT/account-based debt protocols such as Fluid and Compound must not
  derive current open debt from historical `BORROW - REPAY` rows alone
- lifecycle close requires full position exit: zero supply/collateral, zero
  debt, no active vault/account position, and zero receipt/debt tokens or
  resolver-proven absence
- lifecycle close is evaluated on canonical lending assets, so wrapper aliases
  and protocol receipt/debt symbols map back to the same lifecycle asset before
  state is tested
- accrued interest may produce over-withdraw or over-repay deltas; the read
  model treats a lifecycle as closed when no positive supply/debt remainder is
  left, not when every asset accumulator is exactly zero
- Fluid vault/NFT accounts, Euler EVK/EVC loops, and Compound Comet accounts
  are grouped by parent account/vault/market lifecycle; asset symbols are
  child legs, not standalone cycle keys
- Compound V3 Bulker rows are classified before generic swap heuristics. A
  supported `invoke(bytes32[],bytes[])` bundle remains one canonical row, while
  the lending read model may expose child display legs such as
  `SUPPLY_COLLATERAL`, `BORROW`, `REPAY`, and `WITHDRAW_COLLATERAL`.
- Fluid vault/wrapper rows are classified from decoded intent before visible
  transfer direction. Until full logs/internal transfers prove exact economic
  quantities, decoded debt/collateral intent is metadata for grouping and
  status, not speculative accounting flow materialization.
- Fluid full-receipt recovery is allowed for supported vault/wrapper lifecycle
  rows that are already known to normalization but lack durable log evidence.
  The recovery persists receipt/log references and structured Fluid metadata on
  normalized rows, then reruns reclassification; it is not a read-time RPC path.
- Fluid normalized row metadata uses the existing structured `metadata` and
  `clarificationEvidence` fields. Required evidence includes vault address,
  NFT id when known, wrapper kind, evidence completeness, deterministic
  position/lifecycle key, decoded collateral/debt intent, `LogOperate` log
  references, NFT `Transfer` log references, and ERC-20 transfer log references.
- Same-transaction Fluid wallet-visible repay and decoded
  `FLUID_LOG_OPERATE_REPAY` evidence represent one economic repayment when
  market, asset, and quantity match. The read model may keep both evidence rows
  visible, but aggregate debt and PnL maps count only one authoritative repay.
- Plasma Fluid transaction evidence preserves `USDT0` in event/display rows.
  Aggregate accounting maps may use canonical `USDT` only at intentional alias
  boundaries.
- Aave cycles keep `protocol + network + wallet` as the parent context but may
  expose multiple concurrent account-pool strategy cycles when independent
  supply-only collateral and borrow/repay loops overlap; Fluid/Morpho cycles
  use `protocol + network + wallet + vault/account/market`; Euler/Compound
  cycles use `protocol + network + wallet + market/account`
- repeated `Borrow -> Supply/Deposit` events within 24 hours in the same
  cycle/context are rendered as one collapsed loop group in the read model
- reward claims attach to an active matching lending lifecycle; they must not
  create position lifecycles by themselves
- close-side events without a matching open supply/debt leg stay as unresolved
  lifecycle fragments instead of being attached to unrelated clean cycles
- cycle asset deltas are normalized to lifecycle assets before display and PnL;
  protocol receipt/debt tokens such as Aave variable debt symbols are not
  exposed as separate economic assets in cycle totals
- cycle PnL has two separate read-model contracts: `pnlAssetBreakdown` for
  derived income in actual assets, and `pnlBreakdown` for USD valuation.
  `pnlAssetBreakdown.gasByAsset` stores native gas quantity by asset, while
  `pnlBreakdown.gasUsd` stores the USD valuation
- When PnL is unavailable, wallet-visible deltas are exposed as observed
  evidence outside authoritative PnL maps. They must not be labeled as
  `supplyIncomeByAsset` or included in `netIncomeByAsset` unless required
  valuation/conversion evidence is complete.
- unresolved closed lifecycles may be shown only with an explicit stable reason
  code, for example `closed/current-state-zero` plus
  `unresolved_principal_exit` or `pnl_unavailable_missing_full_receipt_logs`
- cycle PnL is lending yield only:
  `interest earned on supply - interest paid on borrow - gas`; when yield cannot
  be separated from price movement or the lifecycle is unresolved, PnL remains
  unavailable with a reason
- `wstUSR` lending-yield PnL requires historical wrapper/share-rate conversion
  to `stUSR/USR` underlying and underlying USD pricing. A direct generic token
  price is not sufficient unless it is proven to encode wrapper exchange rate at
  the event block.
- Historical `wstUSR` total USD valuation may use cached external market prices
  through CoinGecko id `resolv-wstusr`. This is a total valuation input only and
  must not be treated as lending-yield attribution without wrapper conversion
  and underlying price evidence.
- lending valuation is a read-model sub-phase after lifecycle reconstruction:
  it computes cycle-attached total USD valuation for proven lending economic
  legs while keeping yield-only PnL separately gated. This phase must not mutate
  canonical pricing, AVCO, move basis, replay, or normalized accounting truth.
- total lending valuation may price `principalIn`, `principalOut`, `borrowed`,
  `repaid`, `rewards`, `fees`, gas, and current open positions from cached
  historical/current sources. `EURC` uses cached ECB EUR/USD policy rather than
  USD stablecoin parity; `deUSD` is stable-like unless transaction-local
  evidence proves a material parity break; wrapper assets such as `wstETH` and
  `wstUSR` may have total valuation without unlocking yield-only PnL.
- lending read models may read cached historical prices for timestamped
  transfer valuation, but they must not perform live price-provider calls
- session lending GET endpoints remain snapshot-first and must not perform live
  RPC or explorer calls

Important accounting note:

- `move basis` is not a separate ingestion stage
- it is evaluated inside accounting replay together with `AVCO`, realized cost
  basis, and reconciliation
- live-session progress is persisted in `user_sessions.pipelineState`, while
  wallet×network raw ingestion progress remains in `sync_status`

Cycle 79 closeout architecture:

- protocol and counterparty enrichment are normal clarification responsibilities,
  not restart repair jobs
- enrichment may update metadata on already-normalized canonical rows during the
  clarification stage, but it must not change economic type, flows, or pricing
  semantics
- clarification itself does not decide pricing or review after evidence fetch;
  it moves rows to `PENDING_RECLASSIFICATION` and lets the same classifier make
  the next canonical status decision
- supported-flow financial correctness still belongs to the earliest wrong
  stage:
  - canonical principal/excess shape in classification/normalization
  - deterministic lifecycle evidence in clarification/linking
  - deterministic principal carry in replay move-basis
- exact asset coverage, family coverage, and final-clean/proof-clean remain
  separate acceptance surfaces

Async replay guardrail:

- protocol-owned request / settlement families may use transaction-level replay
  handlers when a generic per-flow async bucket is not expressive enough
- current approved example: `GMX V2 LP_ENTRY_REQUEST / LP_ENTRY_SETTLEMENT`
  keeps request principal carries separate from the native execution-fee reserve
  so settlement refund and share-token mint can be allocated deterministically

### 3.4 Replay pass-through corridors

Accounting replay may build deterministic `pass-through corridors` before the
forward AVCO loop starts.

The corridor planner is part of replay, not normalization.

Responsibilities:

- scan already-confirmed canonical rows in replay order
- detect one unique proven continuity inbound
- reserve its exact carry for one later unique proven outbound/custody source
  leg
- keep that reserved carry out of unrelated pooled inventory consumption
- repair already-proven correlated `CARRY_OUT -> CARRY_IN` rows when provider
  rounding changed only the destination quantity precision

The first approved corridor families are:

- `EXTERNAL_TRANSFER_IN/BRIDGE_IN` on `BYBIT:<uid>` followed by one later
  `EXTERNAL_TRANSFER_OUT`
- same-wallet same-network `BRIDGE_IN` followed by one later custody-source
  outflow such as:
  - `LENDING_DEPOSIT`
  - `VAULT_DEPOSIT`
  - `STAKING_DEPOSIT`
  - `PROTOCOL_CUSTODY_DEPOSIT`
  - `LP_ENTRY`

Determinism requirements:

- one unique open inbound candidate in scope
- exact replay bucket identity must match
- the reservation survives only until the first later principal-affecting row
  in the same scope and asset bucket
- if that first later principal-affecting row is not the paired outbound /
  custody-source leg, the open reservation must be discarded before replay
  starts
- no competing same-bucket negative principal flow may appear before the paired
  outbound
- quantity drift must stay inside a bounded tolerance
- correlated carry-ingress repair may only use:
  - shared deterministic `correlationId`
  - `continuityCandidate = true`
  - same continuity identity / audited family
  - a tiny provider-rounding tolerance
  - one unique compatible carry candidate

If any of the above fails, replay falls back to the existing pooled AVCO path.

### 3.5 Request-scoped async reserves

Some async families need more than one replay reservation lane inside the same
`correlationId`.

Approved audited example:

- `GMX V2 LP_ENTRY_REQUEST / LP_ENTRY_SETTLEMENT`

Replay requirements:

- non-native request outbounds are principal carries
- native request outbound is an execution-fee reserve
- settlement native refund releases that reserve
- settlement `GM` / `GLV` share inflow receives the remaining principal basis
- uncovered execution-fee reserve must not block covered principal allocation

Important guardrail:

- bridge continuity and pass-through reservation are separate mechanisms
- `BRIDGE_OUT -> BRIDGE_IN` cross-network carry still uses the dedicated bridge
  path
- same-wallet same-network `BRIDGE_IN -> ... -> BRIDGE_OUT` must not keep a
  stale reserved inbound slice once a local acquisition or other principal
  touch has already mixed that bucket

### 3.3 Session scope

- `user_sessions` is the user-owned workspace aggregate
- session scope is derived from:
  - `wallets[].address`
  - `integrations[].accountRef`
- custodial refs such as `BYBIT:<uid>` therefore belong directly to the
  session integration
- the new target design no longer relies on a separate persisted
  `accounting_universes` control-plane aggregate

### 3.1 Raw collection

- EVM ingestion may be explorer-first or provider-first by network, with bounded native RPC repair where configured.
- `ScamFilter` stays pre-persistence.
- `ScamFilter` must use composite promo/phishing signals and preserve known
  legitimate reward-claim routes that do not carry promo markers.
- Tx-level fields must be canonicalized before persistence. Transfer-style payload
  rows may populate `explorer.tokenTransfers[]`, but must not overwrite top-level
  tx fields such as `from`, `to`, `value`, `input`, or `methodId`.
- Raw docs land in `raw_transactions` with `normalizationStatus=PENDING`.
- external provider payloads land in `integration_raw_events`
- provider-side fetch, hydration, and stitching must happen during `BACKFILL`
- Bybit backfill also materializes `bybit_extracted_events` as a rebuildable
  provider-owned staging layer before canonical normalization
- normalization must not call external provider APIs

### 3.1.1 External integration backfill

- `backfill_segments` is the single orchestration collection for:
  - `sourceKind=ONCHAIN`
  - `sourceKind=INTEGRATION`
- `BackfillJobRunner` is the single orchestration entrypoint for shared
  `backfill_segments`
- shared integration planning owns segment replacement and persistence
- provider-specific planners only return segment specs for their provider
- provider-specific implementations do not own their own schedulers; they only
  implement segment execution
- on-chain segments remain `BLOCK_RANGE`
- external segments are typically `TIME_RANGE`, and may use `CURSOR_PAGE` only
  when the provider stream truly requires persisted cursor checkpoints
- Bybit acquisition is multi-stream (each stream is planned as its own
  `backfill_segments` lane; see `BybitIntegrationStream`):
  - `TRANSACTION_LOG`
  - `EXECUTION_*` by category (`linear`, `inverse`, `spot`, `option`)
  - `FUNDING_HISTORY`
  - `EARN_FLEXIBLE_SAVING`
  - `INTERNAL_TRANSFER`, `UNIVERSAL_TRANSFER`, `DEPOSIT_INTERNAL`, `CONVERT_HISTORY`
  - `DEPOSIT_ONCHAIN`, `WITHDRAWAL`
- Session settings (`GET /sessions/{id}/settings`) exposes per-stream freshness for
  Bybit (`streamSync`: last completed segment time and newest stored extracted event
  per stream); see ADR-005.
- **Cycle/3 Bybit audit (financial proof):** normative clarifications and acceptance
  live under `cycle-autorun/cycle-data/cycle/3/results/` (`qa-clarifications.md`,
  `required-changes.md`, `reconciliation.md`). Implementation backlog:
  `docs/tasks/156-cycle3-bybit-audit-backend-closeout.md`.
- provider enrichment for Bybit is split into:
  - immutable `integration_raw_events`
  - rebuildable `bybit_extracted_events`
  - canonical `normalized_transactions`
- audited Bybit transaction-log currency converts must be resolved during this
  deterministic normalization path, not deferred into clarification:
  - `TRANSACTION_LOG / CURRENCY_BUY`
  - `TRANSACTION_LOG / CURRENCY_SELL`
  remain provider-native convert evidence even when the payload omits a
  directional `side` and explicit `tradePrice`
- the extracted stage must preserve provider business-family labels that later
  deterministic pairers depend on; audited funding-history liquid-staking rows
  such as `ETH 2.0 / Stake` and `ETH 2.0 / Mint` may not be flattened into
  description-only semantics
- session-level raw completion requires both:
  - wallet×network on-chain completion in `sync_status`
  - enabled integration segment completion in `backfill_segments`
- if a provider event remains ambiguous after the full provider backfill pass,
  it must not enter a later provider-clarification loop

### 3.1.2 Manual incremental refresh

WalletRadar should support a user-triggered `Refresh` action after the initial
2-year backfill is complete.

Target policy:

- `sync_status` remains one stable source-level row per:
  - on-chain `walletAddress + networkId`
  - integration source identity
- the manual refresh creates a new bounded refresh cycle against that same
  source row; it does not create a second active `sync_status` document
- `backfill_segments` remains the single orchestration model; refresh creates
  only the new segments needed for the delta window
- segment count must scale with the requested delta range, not with the full
  original 2-year history window
- already persisted raw and canonical data outside the affected delta / linkage
  window must remain untouched
- existing canonical rows may be patched only for deterministic linkage fields
  exposed by the new evidence, such as:
  - `correlationId`
  - `matchedCounterparty`
  - other bounded continuity metadata

Recommended v1 execution model:

1. user clicks `Refresh`
2. backend resolves the current upper bound for every in-scope source
3. backend computes the incremental lower bound from the last completed source
   checkpoint
4. backend creates only the delta `backfill_segments`
5. raw acquisition runs only for that delta
6. normalization / clarification run only for new or directly impacted rows
7. pricing and accounting replay rerun from the downstream pipeline entrypoint
   after refresh scheduling

Rationale:

- this avoids a destructive full raw / normalization reset
- preserves deterministic canonical history
- keeps the current source-status read model intact
- minimizes segment count and explorer/API calls for short refresh windows

Important guardrails:

- `Refresh` is rejected while the same session already has an active raw
  backfill or downstream pipeline run
- if every in-scope source is already up to date, the refresh call is a
  no-op and must not create empty segment batches
- any bounded rematch to older canonical rows must be explicit and
  deterministic; refresh is not a blanket re-normalization of historical rows

Control-plane ownership:

- `AccountUniverseChangedEvent` is the topology trigger for session-owned
  source reconciliation; it replaces the old wallet-only event path
- `AccountUniverseChangedEventHandler` owns:
  - universe diff reconciliation
  - clearing derived session-scoped accounting outputs
  - delegating sync-window planning to `SourceSyncPlanner`
  - delegating segment creation to `BackfillJobPlanner`
- `SourceSyncPlanner` owns only source windows and `sync_status` mutation:
  - choose `from/to`
  - write window metadata into `sync_status`
  - preserve stable checkpoints such as `lastBlockSynced` / `lastSyncedAt`
  - set `status=PENDING`
- `BackfillJobPlanner` owns only segment creation from the prepared window
- `BackfillJobRunner` and `BackfillNetworkExecutor` are execution-only; they
  must not compute historical windows or invent missing segments at runtime

### 3.2 Normalization

- Start only after raw backfill for the relevant source scope is complete.
- For a live session, normalization must start from session-level backfill
  completion, not from the next cron tick.
- Bybit normalization drains `bybit_extracted_events` first; legacy
  `external_ledger_raw` remains migration-only fallback for older sessions
  until the old import path is removed.
- If tx-level ordering metadata is incomplete, run raw ordering repair before canonical normalization.
- If current raw already proves a simple same-universe direct native transfer
  but only one wallet-local raw row exists, run raw peer repair before
  canonical normalization so the missing sender/recipient wallet-local row can
  normalize through the ordinary pipeline.
- Internal-transfer heuristics use the installation-wide tracked wallet universe,
  never an individual session payload.
- A tracked-wallet hint alone is not enough to persist `INTERNAL_TRANSFER`.
  Clarification may promote a pair from `EXTERNAL_TRANSFER_IN/OUT` into
  `INTERNAL_TRANSFER` only when both reciprocal canonical rows already exist for
  the same `txHash + networkId` and prove a simple wallet-to-wallet continuity
  move. Both wallets must share one `accounting_universe`. One-sided
  tracked-counterparty rows remain conservative and stay external unless raw
  peer repair has first materialized the missing wallet-local raw row.
- audited rebasing `Aave` WETH receipt rows must split principal continuity from
  accrued balance growth during normalization itself:
  - matched principal quantity stays `TRANSFER`
  - tx-local receipt/underlying excess becomes a separate positive acquisition
    leg
  - this avoids pushing rebasing drift into replay as synthetic uncovered
    principal
- Process in chronological order:
  - `rawData.timeStamp ASC`
  - `rawData.transactionIndex ASC`
  - `txHash ASC`
- `txHash` is the raw-stage tie-breaker because canonical `_id` does not exist yet during normalization.
- Classification order:
  - protocol registry
  - method id
  - function name
  - transfer-pattern heuristics
- `rawData.methodId` must be recovered from `rawData.input[0:10]` when the stored
  selector is blank or `"0x"`.
- recovered selector-level non-economic evidence such as
  `approve(address,uint256)` must outrank address-only protocol family fallback
  when economic movement is absent.
- Router/container subcalls are derived from the saved top-level
  `rawData.input` only. They are decoder/projection output, not separate raw tx
  documents.
- Known wrapper selectors and known bridge/router methods must be resolved before generic `deposit` / `withdraw` / `multicall` name fallbacks can capture them.
- Plain positive inbound transfer legs default to `EXTERNAL_TRANSFER_IN` unless
  contract-aware reward or bridge evidence exists.
- Protocol-funded native gas assistance must narrow before generic inbound
  transfer fallback can win:
  - wallet-boundary inbound native transfer
  - empty input / `methodId == 0x`
  - no token transfers
  - no internal transfers
  - sender resolves to registry-backed `GAS_PAYER`
  - quantity fits audited per-network gas-topup envelope
  - canonical type is `SPONSORED_GAS_IN`, not priced `EXTERNAL_TRANSFER_IN`
- Promo/phishing inbound patterns must be removed from reward ambiguity handling before generic inbound defaults are applied.
- Repeated self-promotional inbound families such as Base `multicall(bytes[] data)` token-drop rows, Plasma selector `0x1939c1ff`, and repeated selector `0xeec4378e` must narrow before generic `EXTERNAL_TRANSFER_IN` can win.
- Economic meaning follows backfill-available raw legs and contract identity.
  Human-readable explorer page summaries are audit-only and never override
  canonical classifier output.
- Known bridge-entry selectors such as Across `depositV3` must resolve to
  `BRIDGE_OUT` before generic vault/lending fallback can capture them.
- Bridge-settlement selectors such as `fillV3Relay`, `fillRelay`, `redeemWithFee`, `execute302`, and `directFulfill` must be resolved as bridge continuity before broad `repay` / `redeem` / `withdraw` keyword fallback.
- Known LP position-manager router containers such as `multicall` and
  Uniswap-v4-style `modifyLiquidities` must be dispatched by contract-aware
  inner method rules, not by broad router keywords.
- For direct or multicall-embedded Uniswap V4 `modifyLiquidities`, the
  normalization layer must decode nested `unlockData` action params and recover
  the existing-position `tokenId` before replay. Missing that token id is a
  normalization defect because replay position buckets depend on
  `lp-position:<network>:<protocol-slug>:<tokenId>`.
- LP lifecycle type resolution and LP principal role assignment are separate
  concerns. `LP_ENTRY` / `LP_EXIT` stay canonical LP families, but underlying
  principal legs inside those rows must persist as continuity `TRANSFER`
  flows, not as synthetic `BUY` / `SELL`.
- `STAKING_DEPOSIT` / `STAKING_WITHDRAW` are also role-sensitive.
  Liquid-staking mint/redeem paths such as `AVAX -> sAVAX` and audited Bybit
  `ETH -> METH` / `ETH -> CMETH` staking rows keep principal and derivative
  legs in continuity `TRANSFER` inside the same base-asset family. Classic
  stake-contract custody paths such as Pancake SmartChef `deposit(uint256)` also keep
  principal/proof-token legs in continuity `TRANSFER`, while only explicit
  harvested reward side-flows remain economic `BUY`.
- Audited Bybit liquid-staking pairers are bounded but not same-minute only.
  Receipt-wrapper pairs such as `METH -> CMETH` may settle hours apart inside
  the same official `On-chain Earn subscription` lifecycle. Runtime pairing
  therefore uses a bounded multi-hour window plus:
  - same user/account
  - same Bybit lifecycle description when present
  - opposite transfer direction
  - same audited accounting family
  - exact or nearest quantity match
  This remains deterministic normalization-time evidence, not operator
  clarification.
- Audited funding-history `ETH 2.0` liquid-staking pairs are a provider-family
  subtype of this rule. They may pair on shared `bybitType = ETH 2.0` even
  when the human-readable descriptions differ between `Stake` and `Mint`.
- Aave-style `BORROW` / `REPAY` families may contain debt-marker mint/burn legs
  and chain-specific execution-settlement refund legs in the same tx. Those
  marker / settlement legs must persist as continuity `TRANSFER`, while the
  reserve-asset principal remains the only economic `BUY` / `SELL` leg.
- Address-level protocol registry remains the primary protocol handoff for Aave
  lending pools. A generic selector fallback may attach `protocolName = Aave`
  only for `BORROW` / `REPAY` when the same tx also proves
  `variableDebt*` / `stableDebt*` continuity. That narrow fallback must not
  infer exact pool address or `protocolVersion`.
- `protocolName` itself is clarification-adjacent canonical metadata, not an
  economic stage gate. When initial normalization has no direct high-confidence
  hit, clarification may fill `protocolName` / `protocolVersion` later from
  persisted receipt / metadata evidence without changing canonical `type`,
  `status`, or flows.
- Protocol enrichment must prefer the actual interacted tx recipient when that
  address is present in raw tx payloads, even if explorer data also emits
  transfer-backed top-level projections that would otherwise suppress `to`
  during economic classification. This keeps `protocolName` accurate without
  contaminating type/flow inference.
- Registry growth should therefore require only clarification-time repair for
  historical canonical rows whose economics are already correct. Full
  normalization reruns are reserved for cases where protocol evidence changes
  type/flow semantics.
- The protocol registry must support cross-network address reuse. When the same
  contract address belongs to different branded protocols on different
  networks, registry entries may use unique JSON keys plus an explicit
  normalized `address` field so runtime lookup still keys by `network + address`
  without silent JSON-key overwrite.
  classification semantics, not just labels.
- `REWARD_CLAIM` rows may contain self-canceling wrapper / marker pairs inside
  the same tx. Exact same-asset same-quantity in/out pairs must not persist as
  economic `BUY` / `SELL`; they are continuity-only no-op evidence.
- Some real protocol bundles may legitimately mix continuity and reward legs in
  one canonical row. Pendle `zapOutV3SingleToken(...)` on the Mantle reward
  distributor is currently such a bundle: LP marker churn must not remain
  `BUY` / `SELL`, the principal output must remain continuity, and only the
  true reward leg may stay economic.
- Method-aware protocol bundles such as Morpho Bundler3 must be classified by
  contract-scoped dispatch before generic `multicall` / `bundle` fallback. If a
  Bundler3 call has wallet collateral outbound plus Morpho-side loan asset
  inbound, or wallet route/collateral outbound plus vault-share mint evidence,
  the row is lending/vault lifecycle evidence and must not be classified as a
  generic `SWAP`. The normalized row may remain a single parent transaction,
  but it must persist child-leg metadata for the lending read model to
  reconstruct collateral, borrow, route, and receipt-share lanes without a
  post-factum repair job.
- Zero-amount token transfers without economic counterflow must never create `BUY` / `SELL` legs. Known setup/admin calls may resolve to `ADMIN_CONFIG`; unknown cases remain explicit review items.
- Protocol-registry runtime data is loaded from `backend/src/main/resources/protocol-registry.json` only.
- `event_topics` remain reference-only metadata and are ignored by the runtime classifier.
- Registry entries marked with `specialHandler` route into deterministic
  protocol semantics and then into family-owned final type mapping.
- If a special handler cannot support the observed method/function combination, the tx becomes `UNKNOWN -> NEEDS_REVIEW` with an explicit missing-data reason.
- Pricing hygiene runs before replay-gate evaluation. Rows that still carry
  `PRICE_UNRESOLVABLE` from an older pass but no longer have any replay-relevant
  unpriced flow must be repaired before the pricing stage publishes its
  completion event and before downstream replay decides whether the session is
  AVCO-ready.
- Evidence sources:
  - canonical tx-level fields from the raw view / `explorer.tx`
  - `rawData.explorer.tokenTransfers[]`
  - `rawData.explorer.internalTransfers[]`
  - direct native tx value only when it is canonical tx-level evidence
  - persisted real receipt logs when a method-aware handler explicitly needs them
  - dedicated clarification receipt-log evidence when that evidence is
    persisted into canonical raw shape and exposed through the same raw view
- synthetic `rawData.logs[]` remain out of bounds.
- Classification rules should follow protocol-source semantics when official
  contracts or protocol docs are available, not explorer UI labels.
- Wrapped-native continuity must be resolved before generic vault/lending
  fallback:
  - `deposit()` on a known wrapper plus wrapped-token mint -> `WRAP`
  - `withdraw(uint256)` on a known wrapper plus wrapped-token burn and native
    continuity -> `UNWRAP`
- Recognized bridge-entry methods such as Across `depositV3(...)` must resolve
  before generic deposit / vault fallback.
- Route-tagged bridge-initiation families such as LI.FI / Jumper
  `callDiamondWith*` paths and `transferRemote(...)` must resolve before
  generic `EXTERNAL_TRANSFER_OUT` fallback can win.
- Source-chain bridge-start selectors such as
  `swapAndStartBridgeTokensViaMayan(...)`,
  `swapAndStartBridgeTokensViaStargate(...)`, and
  `swapAndStartBridgeTokensViaSquid(...)` must resolve to `BRIDGE_OUT` before
  generic `SWAP` or `EXTERNAL_TRANSFER_OUT` fallback can win.
- Those explicit source-chain bridge-start selectors are objective bridge facts
  even when the eventual destination asset differs from the source asset. They
  must remain `BRIDGE_OUT`, but later replay may treat the correlated route as
  plain basis continuity only when the bridged asset identity is preserved.
- Explicit source-chain bridge-start rows must remain eligible for bounded
  full-receipt clarification so WalletRadar can persist receipt logs and
  transfer evidence for later protocol-aware bridge-pair reconstruction.
- For audited LI.FI / Jumper bridge starts, clarification may also persist the
  official receiving tx hash so the runtime can later materialize the
  destination-side `BRIDGE_IN` row without timestamp-only heuristics.
- Route-proven LI.FI source bridge starts may also be revisited by a bounded
  post-clarification protocol-owned sweep so destination-side `BRIDGE_IN`
  materialization does not depend on accidental clarification order.
- Destination-side passive bridge settlements with empty input / blank
  function name may still resolve to `BRIDGE_IN` when current raw proves a
  registry-backed bridge sender through persisted internal-transfer evidence.
  This is a protocol-bounded settlement fact, not a generic inbound heuristic.
- Routed bridge entries may keep the objective entry `protocolName`
  (`MetaMask Bridge`, `LI.FI`, etc.) while clarification separately uses route
  evidence plus protocol-owned settlement proof to materialize the destination
  pair. Provider inference must not overwrite the source entry label just to
  make pairing work.
- For audited routed `MetaMask Bridge -> LI.FI adapter -> Across settlement`
  corridors, clarification may materialize the pair without official LI.FI
  status only when current canonical evidence proves:
  - one source `BRIDGE_OUT`
  - one same-wallet cross-network inbound destination
  - same principal asset family
  - bounded time window
  - bounded quantity drift
  - destination settlement sender resolved to verified `Across` bridge
    infrastructure
- Routed `LI.FI / Jumper` bridge starts may also materialize a destination
  `BRIDGE_IN` from a unique same-wallet Relay payout when:
  - the source row is already route-proven on a known `LI.FI Diamond`
  - the destination top-level payout sender is registry-backed `Relay`
    infrastructure
  - the destination inbound asset family matches the source bridge family
  - bounded audited time and quantity drift still hold
  - no competing destination candidate exists
- Generic routed aggregator outbound-only rows, including 1inch-style router
  sends, must not be promoted to `BRIDGE_OUT` from time-window proximity or
  destination-wallet heuristics alone. Without production-available bridge
  evidence they remain owner-agnostic `EXTERNAL_TRANSFER_OUT`.
- Route-tagged LI.FI / Jumper bridge initiations remain bridge semantics even
  when top-level `methodId` is blank, as long as the selector is recoverable
  from saved calldata and the calldata still carries official route-tag
  evidence.
- the same route-tagged `LI.FI / Jumper` bridge-start rule also applies when
  the recovered selector is outside the current narrow explicit selector list,
  as long as current raw still proves:
  - a known bridge-router contract
  - bridge-route provider tags
  - positive native funding or equivalent outbound movement
- Circle CCTP destination-side `redeem(bytes cctpMsg,bytes cctpSigs)` rows
  with persisted bridged payout movement must resolve to `BRIDGE_IN` before
  generic `VAULT_WITHDRAW` fallback can win.
- Claim-income families such as `harvest(...)` and `release()` must resolve to
  explicit claim / income semantics before generic `EXTERNAL_TRANSFER_IN` fallback
  can win.
- Explicit receiver-wallet `claim(...)` / `claimWithSig(...)` payout rows must
  resolve to `REWARD_CLAIM` before generic `EXTERNAL_TRANSFER_IN` fallback can win.
- Method-aware swap routing must cover both `DEX` and `AGGREGATOR` router
  families when current raw already proves one real outbound principal asset
  and one real inbound principal asset. `Velora/ParaSwap` Augustus router
  overloads such as `swapOnAugustusRFQTryBatchFill(...)` are objective swap
  facts under that rule and must not remain
  `UNKNOWN / ROUTER_METHOD_OVERLOAD_UNSUPPORTED` merely because the router
  entry is stored as `family = AGGREGATOR`.
- Request-initiation families such as
  `claimSharesAndRequestRedeem(uint256 sharesToRedeem)` are not finalized
  disposals and may not remain priceable `EXTERNAL_TRANSFER_OUT` rows until
  persisted evidence proves settlement or cancellation.
- GMX `createOrder(...)` and similar order-initiation rows are not finalized
  disposals and may not become priceable `EXTERNAL_TRANSFER_OUT` rows until
  persisted evidence proves final settlement.
- GMX V2 `OrderVault + OrderCreated` families are not LP-entry semantics.
  They must resolve to explicit derivative order lifecycle types inside the
  active accounting scope.
- GMX V2 `executeOrder(...)` families must be classified from persisted
  EventEmitter receipt logs:
  - `PositionIncrease` → `DERIVATIVE_POSITION_INCREASE`
  - `PositionDecrease` → `DERIVATIVE_POSITION_DECREASE`
  - `OrderExecuted` without position evidence → `DERIVATIVE_ORDER_EXECUTION`
  - `OrderCancelled` without execution → `DERIVATIVE_ORDER_CANCEL`
- `GMX` terminal keeper typing may not depend only on EventEmitter-decoded
  human-readable labels. Once the same persisted receipt already contains
  authoritative `GMX` EventEmitter evidence, additional same-receipt structured
  lifecycle logs may refine the generic terminal type into
  `DERIVATIVE_POSITION_INCREASE` or `DERIVATIVE_POSITION_DECREASE`.
- `GMX` EventEmitter topic identity is canonical and case-sensitive. Runtime may
  not lower-case event names before hashing them for topic comparison.
- GMX keeper / execution rows may not fall back to `EXTERNAL_TRANSFER_IN` /
  `EXTERNAL_TRANSFER_OUT` just because the explorer source omitted top-level
  `from/to/methodId/functionName`. Clarification must fetch and persist the full
  receipt, then the classifier must resolve the derivative lifecycle from
  EventEmitter logs.
- GMX helper `multicall(bytes[])` order requests must be decoded from saved
  `rawData.input`. Inner helper subcalls remain projection-only evidence; they
  must not be materialized as separate raw documents.
- Selector collisions must be resolved by contract + calldata shape, not by the
  selector alone. The audited `0x322bba21` family proves this: it may be
  `GMX createOrder(tuple)` or `CoW Swap ETH Flow createOrder(EthFlowOrder.Data)`
  depending on the target contract and payload layout.
- Audited CoW async spot-order families must resolve into explicit lifecycle
  semantics instead of leaking into generic transfers:
  - request-side `createOrder(EthFlowOrder.Data)` → `DEX_ORDER_REQUEST`
  - settlement-side `GPv2Settlement` trade completion → `DEX_ORDER_SETTLEMENT`
- CoW request-side correlation must come from deterministic protocol evidence
  available at normalization time from saved calldata. Settlement-side
  correlation must come from persisted `Trade(...)` receipt logs. If those logs
  are missing in the initial raw shape, clarification must fetch the full
  receipt from the same source family.
- Persisted clarification evidence is runtime-authoritative whenever it exists
  in canonical raw shape. A stale or missing clarification-attempt counter may
  not hide already persisted `fullReceipt` / `receipt` / `transfers` evidence
  from classification.
- GMX async request / execute families such as helper `multicall(bytes[] data)`
  deposit requests plus `executeDeposit(...)` / `executeGlvDeposit(...)`
  settlement may not remain active priceable rows unless the runtime can
  deterministically correlate the request-side principal with the later
  execution-side settlement from current raw plus persisted clarification
  evidence. When that key already exists in persisted logs, the rows should
  resolve into explicit async lifecycle semantics (`LP_ENTRY_REQUEST` /
  `LP_ENTRY_SETTLEMENT`) instead of blocker review.
- The same GMX / GLV rule applies to additional request / settlement families:
  if request-side or keeper-side evidence is missing in the initial raw shape,
  clarification must fetch it from the same source family before the tx is
  allowed to stay unresolved in on-chain review.
  - helper `multicall(bytes[])` + outbound `GM/GLV` share burn →
    `LP_EXIT_REQUEST`
  - keeper `executeWithdrawal(...)` or clarified `WithdrawalExecuted` /
    `GlvWithdrawalExecuted` tx → `LP_EXIT_SETTLEMENT`
- For audited `GMX / GLV` async exits, request-side classification is
  lifecycle-shape-driven. The audited helper selector family plus audited
  withdrawal-request subcall family and burned share asset are sufficient even
  when the top-level router address is not in the registry.
- For audited `GMX / GLV` async exits, the runtime should first use decoded
  `bytes[]` subcall selectors and then fall back to saved raw calldata selector
  fragments only when the decoded path is incomplete. The fallback remains
  valid only together with audited helper selectors, outbound-only movement,
  and burned `GM/GLV` share principal.
- For audited `GMX` derivative lifecycles, clarification may also fetch
  additional real keeper txs when bounded same-source explorer transfer scans
  prove that the wallet participated in a later execution or cancellation tx
  that is still absent from `raw_transactions`.
- Those discovered keeper txs remain ordinary raw docs. They must be persisted
  and normalized, not represented as synthetic child steps on the original row.
- For audited `GMX` derivative lifecycles, terminal sibling stop / bracket
  state must also be persisted once the closing keeper receipt already proves
  `OrderCancelled(AUTO_CANCEL)` for the sibling request key.
- Once that terminal state is proved, the affected request rows must also gain
  a visible `matchedCounterparty` link to the keeper tx; they may not remain
  operationally orphaned.
- Request / settlement / terminal linking is a shared runtime concern. Once a
  row has deterministic `correlationId`, normalization, clarification, and
  related-lifecycle discovery must all use the same linker to materialize
  `matchedCounterparty` on every same-wallet same-network counterpart row.
- For exact async request/settlement pairs, the linker must materialize
  bidirectional `matchedCounterparty` on both rows. Only accepted asymmetric
  terminal derivative cases may remain one-terminal-to-many-request linkage.
- `correlationId` selection must follow protocol lifecycle scope, not raw
  receipt-log encounter order. When a `GMX / GLV` settlement receipt contains
  both an intermediate deposit key and a higher-scope GLV key, runtime must
  persist the higher-scope GLV key.
- Concentrated-liquidity positions are another high-scope lifecycle. Their
  deterministic correlation must use the position token id, not wallet-level
  asset-family continuity:
  - `lp-position:<network>:<protocol-slug>:<tokenId>`
  - direct increase / decrease / collect / burn selectors may decode token id
    immediately
  - mint-like rows without token id must wait for full receipt clarification so
    the NFT mint log can recover the same deterministic key
- CoW settlement detection may not depend solely on top-level explorer
  `to/from/functionName` fields. If persisted `fullReceipt.logs` already prove
  the GPv2 `Trade(...)` digest, the settlement row must resolve to
  `DEX_ORDER_SETTLEMENT`.
- If a CoW settlement or GMX pool-exit settlement first lands in the active
  lane as a generic inbound transfer because the receipt was missing, receipt
  clarification must be allowed to fetch the same-source receipt and reclassify
  the row in place.
- Burn-only unbonding / redeem-initiation rows such as
  `initiateWithdrawal(uint256)` may not collapse into finalized
  `LENDING_WITHDRAW` / `VAULT_WITHDRAW` semantics while the eventual receive-side
  settlement is still deferred. They should resolve into explicit request
  semantics (for the audited Resolv family: `STAKING_WITHDRAW_REQUEST`) and
  later claim payout rows should carry the same `correlationId`.
- For the audited Resolv family, the later `withdraw(...)` claim payout is
  continuity settlement, not a fresh economic acquisition. It stays
  `STAKING_WITHDRAW`, but its principal payout leg must persist as `TRANSFER`,
  not as `BUY`.
- Trader Joe `LBRouter.addLiquidity(...)` is LP-entry semantics, not lending.
- Approval/configuration families such as `setMinterApproval(...)` are
  non-economic and must not resolve to LP or vault types from fee-only flow.
- Economic rows must not proceed to pricing unless canonical raw evidence or
  persisted clarification evidence proves non-fee movement semantics that are
  sufficient for later basis replay.
- Receipt-log-rich batch families such as audited Euler `batch(...)` rows may
  not auto-upgrade from debt/share marker evidence alone. Without a financially
  complete decoder they remain explicit blocker rows, not active
  `LENDING_DEPOSIT` / `LENDING_WITHDRAW`. The audited Euler rows close only when
  clarification already proves the borrow / transfer / swap / supply path on
  current persisted evidence.
- Audited Euler native EVK deposits may close as `LENDING_DEPOSIT` even when
  the wallet-visible principal outbound leg is native tx `value` rather than a
  token transfer, but only when clarification proves:
  - positive native tx funding
  - one share mint to the tracked wallet
  - one protocol-local fungible hop inside the clarified receipt
  - no share burn / debt-close shape that would imply withdraw or loop
- Euler protocol ownership is split into two runtime lanes:
  - simple vault lane:
    `stable -> share` = `LENDING_DEPOSIT`,
    `share -> stable` = `LENDING_WITHDRAW`,
    but only when receipt-backed clarification proves the wallet-boundary
    lifecycle
  - audited loop-router lane:
    `LENDING_LOOP_OPEN`, `LENDING_LOOP_REBALANCE`,
    `LENDING_LOOP_DECREASE`, `LENDING_LOOP_CLOSE`
- The loop lane must not be inferred from `share burn -> stable return` alone.
  It remains reserved for the audited Euler loop router path and related
  protocol evidence. Non-loop-router batch rows must stay on the simple lending
  lane only when clarification proves that lifecycle; otherwise they remain
  `UNKNOWN / PENDING_CLARIFICATION` rows and enter the receipt-clarification
  queue.
- For the audited Euler leverage / looping family, WalletRadar uses a pragmatic
  canonical share-position model:
  - `LENDING_LOOP_OPEN` records acquisition of the collateral-share position
    using event-local implied price from the clarified stable-like supply leg
  - `LENDING_LOOP_REBALANCE` records share-to-share restructure / migration
    inside the same Euler loop family with continuity-only basis carry and no
    realized exit
  - `LENDING_LOOP_DECREASE` records partial unwind as disposal of collateral
    shares against wallet-visible returned value
  - `LENDING_LOOP_CLOSE` records final unwind as terminal disposal of the
    remaining collateral-share position into wallet-visible assets
  - debt-marker evidence remains in raw / clarification evidence and is not
    persisted as a standalone canonical asset-lot flow
- Pricing must separate:
  - tx-local price evidence such as stablecoin parity, exact source execution
    price, swap-derived ratio, and wrapper/native aliasing
  - external market-data fallback such as Binance and then CoinGecko
- Audited Euler loop rows may carry pre-resolved event-local prices directly in
  canonical flows when current clarification evidence already proves the
  stable-like supply / return amount behind the share-position move. Those
  prices belong to normalization output, not to external pricing fallback.
- Pricing must treat `TRANSFER` as non-priceable regardless of the parent tx
  type. Continuity-type rows may still carry explicit `BUY` / `SELL` side flows
  (for example reward or fee side legs inside an LP exit bundle), and those
  economic side flows must remain priceable.
- Binance is the primary external market-data source for pricing, but not the
  primary overall pricing source; tx-local execution semantics win whenever the
  canonical row already proves them.
- Bybit historical market data is preferred before Binance when tx-local
  execution evidence is absent.
- Euro-backed stablecoins such as `EURC` should prefer official `ECB` EUR/USD
  FX data before exchange-market fallbacks.
- CoinGecko remains limited fallback coverage and must not be the only assumed
  path for long-tail two-year DeFi pricing.
- Bybit ledger data is not required to price on-chain rows themselves, but it
  is required before final AVCO/replay when WalletRadar computes one accounting
  universe across on-chain and Bybit evidence.
- Persisted canonical transfer types must stay owner-agnostic:
  - wallet / Bybit / wallet-to-wallet movement facts persist as
    `EXTERNAL_TRANSFER_OUT` / `EXTERNAL_TRANSFER_IN`
  - bridge facts persist as `BRIDGE_OUT` / `BRIDGE_IN`
  - same-universe wallet-to-wallet direct pairs may later promote to
    `INTERNAL_TRANSFER` only when both wallet-local canonical rows already
    exist
  - wallet-to-Bybit corridors stay external canonical transfer rows even after
    exact rematch
  - ownership-aware continuity is carried by `correlationId`,
    `continuityCandidate`, and `matchedCounterparty`, then resolved during replay
- Wallet-local `SWAP` requires both wallet-visible legs:
  - at least one non-fee `SELL`
  - at least one non-fee `BUY`
  If a known aggregator/router row proves only outbound wallet movement, it
  must leave the `SWAP` lane before pricing.
- Recognized aggregator/router routes with outbound-only wallet movement and no
  wallet-visible `BUY` leg demote to owner-agnostic `EXTERNAL_TRANSFER_OUT`
  unless bridge semantics are proven earlier.
- Matched Bybit deposit/withdraw plus on-chain bridge/custody legs use
  continuity pricing rules and may not create duplicated principal pricing on
  both sides.
- After an on-chain-only rerun, clarification post-processing must be able to
  re-attach exact `wallet <-> BYBIT` continuity from the already normalized
  Bybit leg, rather than waiting for a full Bybit renormalization pass.
- Replay continuity is family-aware for audited custody-equivalent wrappers.
  The current audited family includes `ETH`, network `WETH`, `Aave WETH`
  receipt wrappers, `vbETH`, `yvvbETH`, `mETH`, and `cmETH`, so
  `Bybit ETH -> on-chain WETH -> aManWETH` and upstream `yvvbETH -> vbETH`
  wrapper restores move basis without synthetic disposal or reacquisition.
- Audited Aave wrapped-token gateway selectors are a narrow method-aware
  exception owned by normalization, not by replay:
  - `zkSync`
    - `0x80500d20` `withdrawETH(...)` -> `LENDING_WITHDRAW`
    - `0x02c205f0` `supplyWithPermit(...)` -> `LENDING_DEPOSIT`
    - `0x474cf53d` `depositETH(...)` -> `LENDING_DEPOSIT`
  - `Base`
    - `0x474cf53d` `depositETH(...)` -> `LENDING_DEPOSIT`
  The override must also prove the expected supported-network wallet-boundary
  shape before generic unwrap / LP / heuristic lanes run:
  - `zkSync`: `ETH/WETH/aZksWETH`
  - `Base`: `ETH/AWETH`
- Exchange-side `fund_asset_changes` withdrawal shadow rows that duplicate a
  matched chain-aware `withdraw_deposit` leg remain persisted for audit, but
  they are excluded from accounting and pricing lanes.
- Basis-relevant Bybit inbound families may not silently normalize into
  `UNKNOWN / CONFIRMED`:
  - raw `fund_asset_changes` rows with inbound canonical semantics must
    materialize as `EXTERNAL_TRANSFER_IN`
  - synthetic `withdraw_deposit` inbound rows must also materialize as
    `EXTERNAL_TRANSFER_IN`
  - if a basis-relevant Bybit raw row has canonical semantics that the
    normalizer cannot map, the row must go to `NEEDS_REVIEW`, not to silent
    `CONFIRMED UNKNOWN`
- Residual Bybit `uta_derivatives` orphan legs with
  `UTA_TRADE_PAIR_NOT_FOUND` are insufficient-evidence rows and may not remain
  priceable. They must leave the `PENDING_PRICE` lane unless another official
  Bybit source reconstructs the missing counter-leg.
- Deterministically known but unsupported / incomplete Bybit rows may stay
  persisted as `NEEDS_REVIEW` only when they also carry:
  - `excludedFromAccounting = true`
  - `accountingExclusionReason = <explicit reason>`
  This keeps the audit trail visible while removing the row from active pricing
  and replay gates.
- Current exclusion families are intentionally narrow:
  - residual `UTA_TRADE_PAIR_NOT_FOUND` orphan legs
  - `BYBIT_LOAN_SEMANTICS_UNSUPPORTED` rows
- Pricing and replay readiness must count only blocking review rows
  (`status = NEEDS_REVIEW AND excludedFromAccounting != true`).
- Operational telemetry must expose both:
  - blocking review rows
  - excluded review rows
- Active priceable `SWAP` rows must also satisfy the wallet-boundary swap-shape
  invariant:
  - at least one `BUY` leg
  - at least one `SELL` leg
- Pricing readiness is a live-data gate, not a code-complete claim: successful
  tests or reruns are insufficient if the post-rerun Mongo audit still finds
  any of:
  - active async request / settlement lifecycle leaks such as GMX deposit
    request multicalls or `executeDeposit(...)` settlement rows in priceable
    families
  - burn-only unbonding / redeem-initiation rows still modeled as finalized
    withdraws
  - audited Euler batch rows silently reopened into active lending families
  resolved wrapped-native continuity leaking into `VAULT_*` /
  `LENDING_WITHDRAW`, recognized bridge-entry rows leaking into
  `VAULT_DEPOSIT`, route-tagged bridge initiations leaking into
  `EXTERNAL_TRANSFER_OUT`, Circle CCTP `redeem(...)` rows leaking into
  `VAULT_WITHDRAW`, explicit receiver-wallet claim payout rows leaking into
  `EXTERNAL_TRANSFER_IN`, pending redeem-request initiation rows leaking into
  priceable `EXTERNAL_TRANSFER_OUT`, claim-income rows leaking into
  `EXTERNAL_TRANSFER_IN`, priceable GMX order-initiation rows leaking into
  `EXTERNAL_TRANSFER_OUT`, basis-relevant Bybit inbound raw rows leaking into
  `CONFIRMED UNKNOWN`, priceable Bybit `UTA_TRADE_PAIR_NOT_FOUND` rows,
  active `SWAP` rows leaking through without a wallet-visible `BUY` or `SELL`
  leg, known bridge-start selectors leaking into plain `SWAP`,
  clarification persistence mismatches between raw and normalized state, or a
  broad repeatable review family that should have become deterministic from
  current raw or allowlisted clarification evidence.
- The post-`run/13` live audit has cleared the normalization/clarification gate:
  - `PENDING_CLARIFICATION = 0`
  - `NEEDS_REVIEW = 0`
  - `clarification_persistence_mismatches = 0`
  - `confirmed resolved misclassifications = 0`
- The next active milestone is pricing, not additional clarification-tail
  cleanup.
- The post-`run/16` blocker set is no longer a silent semantic mismatch in
  resolved rows; it is an explicit Bybit exclusion-tail policy decision.
- Current raw is already sufficient for this closeout because the audited
  method families, token identity, sender shape, and wallet-history isolation
  for the affected asset contracts are all provable from persisted raw and
  Mongo state.
- No new backfill is required for the current blocker set; a normalization
  rerun is sufficient after the classifier demotion slice, although a standard
  `normalization + clarification` rerun remains operationally safe.

### 3.3 Clarification

- `Clarification` is the on-chain receipt-enrichment stage.
- It is only for receipt-clarifiable records.
- Low confidence alone is not enough to enter `Clarification`.
- Base clarification enrichment allowed:
  - execution status
  - gas
  - created contract address
- Clarification reasons must match the actual missing receipt-safe fields.
  `MISSING_CONTRACT_ADDRESS` is valid only when contract-creation intent is
  explicitly evidenced by the tx-shaped raw payload.
- Missing `effectiveGasPrice` is a clarification reason even when legacy
  `gasPrice` is still usable for provisional fee math.
- Low-confidence rows that are already economically coherent must proceed directly to `PENDING_PRICE`.
- Unsupported semantic gaps must move directly to `NEEDS_REVIEW`.
- Clarification must not treat synthetic logs as first-class classification input.
- Metadata-safe clarification usage alone is not used to decide promo/phishing inbound, bridge-settlement continuity, LP position-manager multicalls, or zero-value no-op token calls.
- Metadata-safe clarification usage alone is not used to upgrade per-wallet `CLAIM_WITHOUT_MOVEMENT` rows
  into `REWARD_CLAIM`.
- Clarification is not the place to repair route-tagged LI.FI / Jumper bridge
  initiation leaks, Circle CCTP `redeem(...)` destination-side bridge-in
  semantics, explicit receiver-wallet claim payout semantics, or pending
  redeem-request initiation semantics when those rows are already closable from
  current raw plus persisted clarification evidence.
- Clarification rows must carry explicit missing receipt-safe reasons.
- Clarification may additionally fetch full receipt evidence only for an
  allowlisted set of review families where the audit and official protocol
  semantics show that receipt evidence can materially close the gap.
- When full receipt evidence is fetched, clarification should persist both:
  - the adapted clarification-evidence fields used by runtime classification
  - the raw full receipt payload when the source makes it available
- If a clarification source call already returns a receipt payload, the system
  persists that source-native receipt payload in full even when the current
  clarification path will use only metadata-safe fields semantically.
- Clarification is not complete for a row unless the fetched clarification
  evidence is actually persisted back to the raw row in a deterministic shape.
- Clarification persistence must use one canonical raw-level evidence contract
  that is visible to both runtime classification and live Mongo audits.
  Writing only scalar receipt-safe fields into `rawData.*` is not sufficient
  when normalized counters claim clarification already happened.
- The distinction between metadata-safe clarification and receipt-log-backed
  clarification is a semantic-usage policy, not a storage-truncation policy.
- Runtime pipeline code may consume clarification evidence only through
  `OnChainRawTransactionView` / the canonical raw projection.
- When canonical top-level `clarificationEvidence` is present and runtime reads
  only through the canonical raw projection, any leftover nested
  `rawData.clarificationEvidence` shape is a data-shape warning, not a pricing
  blocker by itself.
- Clarification telemetry must stay live-parity safe: persisted clarification
  evidence on raw rows must not silently drift away from normalized
  clarification attempt counters.
- Full-receipt clarification may also persist same-source internal transfers
  for an allowlisted native-bridge subset when those internal legs are needed
  to reconstruct bridge continuity and the producing source family exposes
  them.
- The same rule applies to audited `BLOCKSCOUT` concentrated-liquidity exits:
  when wallet-scoped explorer pages miss native settlement legs but tx-level
  `/internal-transactions` already exposes them, classification must route the
  row into receipt clarification instead of freezing an incomplete
  `LP_EXIT` / `LP_FEE_CLAIM`.
  These rows are full-receipt-only candidates and must bypass metadata-only
  clarification so the tx-level `internalTransfers` are actually persisted.
- full receipt payload must remain separate from synthetic `rawData.logs[]` and
  from the adapted canonical evidence fields.
- Clarification may rerun classification only from canonical raw evidence plus
  production-fetchable full receipt evidence.
- Concentrated-liquidity clarification must still prove economic direction:
  - Base Pancake / Infinity exit containers need persisted collect /
    withdrawal / unwrap continuity before they become `LP_EXIT`
  - BSC `modifyLiquidities(...)` needs persisted positive liquidity-add or
    tracked-wallet funding evidence before it becomes `LP_ENTRY`
- Clarification enrichment must follow source lineage:
  - RPC-backed raw -> RPC clarification
  - Etherscan-family raw -> Etherscan-family clarification
  - Blockscout-backed raw -> Blockscout clarification
- Cross-source clarification fallback is allowed only when the configured
  lineage-consistent source fails and that fallback path is explicitly
  documented.
- Clarification must not use traces, explorer UI summaries, or manual audit
  notes as runtime evidence.
- Families already closable from current raw, such as claim-family no-movement
  rows and known Morpho handler gaps, must be fixed in classification rather
  than deferred to clarification enrichment.
- Safe non-economic review-tail families that are already deterministic from
  current raw must leave `NEEDS_REVIEW` without clarification. This includes
  spam / airdrop clusters, explicit `CLAIM_WITHOUT_MOVEMENT`, failed
  transactions, documented admin / governance actions, pending-request /
  pending-order initiation rows, and out-of-scope NFT or attestation mints.
- Clarification is reserved for receipt-closeable residuals such as
  Slipstream cleanup/stake families, Pancake / Infinity CL lifecycle families,
  Euler `batch(...)`, ParaSwap `swapExactAmountOut(...)`, GMX
  `executeOrder(...)`, and Katana `routeSingle(...)` when persisted
  full-receipt evidence actually closes them.
- ParaSwap exact-out native-settlement rows leave that clarification lane once
  current calldata already proves destination native output and exact unwrap
  quantity. Those rows must close in normalization and must not wait for
  receipt enrichment just to recover a native leg that calldata already proves.
- If clarification still leaves only weak protocol identity, zero logs, or
  wrapper-only bookkeeping evidence, the row stays in an explicit documented
  stop-condition set instead of being forced into synthetic economic
  semantics.
- Under-evidenced economic rows may be promoted only when persisted
  clarification evidence closes the missing movement semantics; otherwise they
  must stay review/clarification and may not move into pricing.

### 3.4 Bybit normalization

- For a live session, `Bybit` normalization starts only after on-chain
  clarification has completed.
- A late exact rematch pass may then materialize `Bybit <-> on-chain`
  continuity when the exact on-chain leg is now present in persisted raw and
  canonical state.
- `Bybit` normalization is started only from the session pipeline event chain,
  not from a stage scheduler.

- `external_ledger_raw` remains immutable source evidence.
- `BybitTradePairer` uses a sliding `±5 sec` window for UTA trade pairing.
- Matched Bybit withdraw/deposit and on-chain movements share a `correlationId`.
- Unmatched Bybit withdraw/deposit rows with
  `BRIDGE_ON_CHAIN_LEG_NOT_FOUND` remain explicit continuity blockers until the
  runtime can materialize both `correlationId` and `matchedCounterparty`.
- If the unmatched venue address is outside the tracked wallet universe and no
  exact tracked-universe on-chain leg exists, the row must resolve to explicit
  external-custody policy rather than pretending to be a missing tracked bridge
  leg.
- Canonical accounting docs still land in `normalized_transactions` for both `ON_CHAIN` and `BYBIT`.
- Double-counting is prevented by `TRANSFER` semantics and replay carry-over rules, not by discarding one side of the source trail.

### 3.5 Pricing and replay

- Pricing order:
  - stablecoin parity
  - swap-derived ratio
  - wrapper/native mapping
  - Binance historical market data
  - CoinGecko historical fallback
  - `PRICE_UNKNOWN`
- AVCO replay input is `normalized_transactions WHERE status=CONFIRMED`.
- Pricing / AVCO may not start until the post-rerun live audit shows:
  - zero resolved wrapped-native selector leaks into `VAULT_DEPOSIT`,
    `VAULT_WITHDRAW`, or `LENDING_WITHDRAW`
  - zero resolved recognized Across `depositV3(...)` leaks into `VAULT_DEPOSIT`
  - zero resolved route-tagged bridge-initiation leaks into
    `EXTERNAL_TRANSFER_OUT`, including LI.FI / Jumper `callDiamondWith*`
    families and `transferRemote(...)`
  - zero resolved Circle CCTP `redeem(...)` leaks into `VAULT_WITHDRAW`
  - zero resolved explicit receiver-wallet claim payout leaks into
    `EXTERNAL_TRANSFER_IN`, including merkle `claim(...)` and signed
    `claimWithSig(...)`
  - zero resolved pending redeem-request initiation leaks into priceable
    `EXTERNAL_TRANSFER_OUT`, including
    `claimSharesAndRequestRedeem(...)`
  - zero resolved claim-income leaks into `EXTERNAL_TRANSFER_IN`, including
    Pancake `harvest(...)` and vesting `release()`
  - zero priceable GMX `createOrder(...)` rows without finalized settlement
    semantics
  - zero active `SWAP` rows without both wallet-visible `BUY` and `SELL` legs
  - zero routed bridge-start selectors
    `swapAndStartBridgeTokensViaMayan(...)`,
    `swapAndStartBridgeTokensViaStargate(...)`, and
    `swapAndStartBridgeTokensViaSquid(...)` leaking into `SWAP`
- Replay order is deterministic:
  - `blockTimestamp ASC`
  - `transactionIndex ASC`
  - `_id ASC`
- `_id` is the canonical-stage tie-breaker after normalized documents have been materialized.

---

## 4. Key Collections

- `user_sessions`
  Persisted wallet sets and selected networks keyed by client-generated `sessionId`.
- `tracked_wallets`
  Installation-wide wallet universe used by canonical normalization and transfer detection.
- `sync_status`
  Backfill lifecycle per wallet×network.
- `backfill_segments`
  Persistent segment orchestration and retries.
- `raw_transactions`
  Immutable on-chain evidence with controlled enrichment.
- `external_ledger_raw`
  Immutable Bybit import layer.
- `normalized_transactions`
  Canonical accounting stream for both on-chain and Bybit sources.
- `asset_ledger_points`
  Immutable replay trace per wallet-network-asset bucket. Source of truth for
  asset history, accounting debug, and session-level asset AVCO/cost-basis
  timeline reads.
- `on_chain_balances`
  Latest observed current-balance evidence produced by the bounded post-replay
  refresh pass and used by reconciliation.

---

## 5. Design Rules

- GET endpoints remain datastore-only.
- Raw collections are source evidence; canonical accounting consumes normalized documents only.
- `backend/src/main/resources/protocol-registry.json` is the only authoritative protocol-registry source for runtime classification.
- Tx-level native value must never be reconstructed from token transfer-row amounts.
- Tx-level native value must not duplicate a wallet-boundary movement already
  proven by an audited native-token alias transfer on the same network.
- On native-alias networks, an alias transfer that exactly equals the audited
  tx gas fee and targets the audited system fee sink must be materialized once
  as fee evidence; normalization must not emit both that transfer and a second
  explicit fee leg.
- On `zkSync`, wallet `<-> 0x0000000000000000000000000000000000008001`
  native-alias movements are fee lifecycle evidence, including refunds. They
  must be netted out of principal movement extraction for fee-paying wallets.
- `KATANA` and `PLASMA` are supported EVM networks in the v3 accounting layer.
- WETH aliasing happens at replay time only.
- Basis continuity applies to:
  - wallet-internal transfers
  - bridge matches
  - lending and vault custody movements
  - LP principal moving into and out of LP custody
  - correlated Bybit <-> on-chain custody movements
- LP receipt markers (`LP token`, `BPT`, position NFT) are continuity markers
  only and must not open independent basis lines during replay.
- Concentrated-liquidity replay must not treat principal continuity as a plain
  wallet-level same-asset bucket. Once deterministic LP position correlation is
  available, replay must use a position-scoped multi-asset bucket keyed by that
  `correlationId`.
- Position-scoped `LP_EXIT*` replay keeps a two-lane residual model:
  - same-family residual principal remains first-class allocation input
  - cross-asset residual transfer legs remain deferred candidates and are
    promoted only when they are the only remaining deterministic
    principal-return path
- A reward-only or out-of-bucket sideflow exit must not clear a live
  position-scoped LP bucket by itself. If a given `LP_EXIT*` row touches no
  eligible principal-return identity, replay records that sideflow as
  `UNKNOWN` and preserves the remaining bucket for later matched exits in the
  same position correlation.
- Replay-known-value allocation may reuse deterministic USD-stable parity for
  on-chain transfer legs even when canonical transfer flows did not persist an
  explicit `unitPriceUsd`; this is a replay-local allocation aid, not a
  normalization-time price mutation.
- `asset_ledger_points` is the immutable replay truth:
  - replay writes it directly while applying accounting consequences
  - UI history reads use it directly
  - audit/debug starts from it, not from terminal snapshots
- Continuity replay must be chronology-safe:
  - inbound same-universe transfer quantity becomes immediately available when
    the inbound row is encountered
  - if the matched source carry arrives later in ordered replay, it attaches
    basis to the already-materialized destination position
  - late source carry must not create quantity a second time
- session-level AVCO/cost-basis history is computed on read from wallet-level
  `asset_ledger_points`; cross-wallet AVCO remains unstored.
- replay-derived persistence is owner-aware:
  - `asset_ledger_points` rows carry `accountingUniverseId`
  - `on_chain_balances` rows carry `sessionId`
  - unrelated sessions must not share derived replay or live-balance evidence
- `on_chain_balances` refresh is bounded by the current session wallet subset
  plus the confirmed on-chain canonical asset universe for those wallets. It is
  not an unbounded wallet scanner.
- provider-native current balances must normalize zero-address contract payloads
  (`0x0000000000000000000000000000000000000000`) into native accounting
  identity rather than persisting them as synthetic token contracts.
- The replay and portfolio snapshot pipeline order is:
  - canonical replay
  - `asset_ledger_points` persistence
  - `PORTFOLIO_SNAPSHOT_REFRESH`
    - bounded `on_chain_balances` refresh for the current session wallet subset
    - current quote refresh for positive live-balance symbols
  `ACCOUNTING_REPLAY` must complete before live portfolio evidence is fetched.
  Live balances and current quotes are dashboard snapshot inputs, not AVCO
  replay inputs.
- user-facing asset detail history APIs must read from `asset_ledger_points`
  filtered to the requested session `accountingUniverseId` and asset family.
- user-facing current holdings must be derived on read from
  `asset_ledger_points` plus `on_chain_balances`.
- `PORTFOLIO_SNAPSHOT_REFRESH` must remain a background stage. Dashboard GET
  endpoints read persisted `on_chain_balances` and `current_price_quotes` only;
  they must not call RPC, explorers, Ankr, Bybit, Binance, or CoinGecko.
- canonical classification may still use installation-wide tracked-wallet
  discovery, but replay-time carry eligibility must remain bounded by the
  session's accounting universe. Cross-owner transfers must therefore degrade
  conservatively to uncovered inbound rather than inherit basis from an
  unrelated session.
- replay must persist quantity deficits explicitly:
  - when an outbound replay step consumes more quantity than the current replay
    bucket contains, that deficit must survive as `quantityShortfall`
  - the deficit must not disappear behind floor-to-zero quantity alone
- replay must persist current uncovered quantity separately from lifetime
  deficit:
  - uncovered quantity must move with continuity carry into the destination
    bucket when the economic quantity is still held
  - later covered acquisitions must not be zeroed out by historical lifetime
    shortfall from already-spent quantity
- current-holdings reads must expose conservative basis-provability fields:
  - `basisBackedDerivedQuantity`
  - `currentCoveredQuantity`
  - `currentUncoveredQuantity`
  - `currentCostBasisProvable`
- product and audit consumers must distinguish:
  - live current quantity
  - current quantity that is still basis-backed
  - current quantity that is live-positive but not yet provable from replayed
    basis
- `SWAP_DERIVED` pricing is allowed only when the priced canonical asset appears
  once among non-fee swap flows. Multi-leg same-canonical swap rows must fall
  back to safer pricing sources.
- when a current holding still mismatches live on-chain state, repair
  deterministic upstream normalization semantics before widening replay rules;
  run 12 `zkSync` Aave gateway remediation follows that contract.
- same-wallet `Across` continuity may be source-led:
  - audited source `BRIDGE_OUT / Across` may link to a unique bounded inbound
    row that is still typed `EXTERNAL_TRANSFER_IN`
  - source-side `BRIDGE_OUT / Across` typing may rely on stored calldata route
    parameters plus wallet-boundary funding when explorer transfer lists do not
    retain intermediate helper / settlement hops
  - when linked, that inbound row may be promoted to `BRIDGE_IN` and receive
    `correlationId`, `matchedCounterparty`, and `continuityCandidate`
  - bounded matching must remain wallet-equal, cross-network, quantity-close,
    and time-windowed
- hash-specific stop-conditions are allowed only for rows whose wallet-boundary
  principal meaning is still genuinely unverified; once current raw evidence
  proves deterministic outbound principal movement, normalization must preserve
  that movement through an explicit canonical fallback rather than suppress it
  behind `UNKNOWN`.
- replay bootstrap and stale-heal checks must be session-scoped:
  - unrelated `asset_ledger_points` rows must not satisfy replay bootstrap for
    the current session
  - unrelated `on_chain_balances` rows must not satisfy
    `PORTFOLIO_SNAPSHOT_REFRESH` completion for the current session

### 5.1 Asset Ledger Timeline Contract

Linking model reference:

- canonical row identity, lifecycle linking, replay carry linkage, display
  grouping, and `protocolName` enrichment are separate layers
- use [05-linking-and-protocol-name.md](05-linking-and-protocol-name.md) as the
  authoritative explanation of those layers and their boundaries

`asset_ledger_points` must support two read modes without extra RPC:

1. exact-bucket debug
   - keyed by `(walletAddress, networkId, accountingAssetIdentity)`
   - shows the exact replay state transitions that produced the final bucket
2. session-family history
   - filtered by session wallet set plus `accountingFamilyIdentity`
   - aggregated on read into one AVCO/cost-basis curve for the asset detail page

Each immutable point must carry:

- exact bucket identity: `accountingAssetIdentity`
- continuity family identity: `accountingFamilyIdentity`
- lifecycle grouping: `normalizedType`, `lifecycleKind`, `lifecycleStage`
- basis semantics: `basisEffect`
- deterministic order fields
- before/after state
- explicit unresolved / shortfall diagnostics

Correlated external-ingress rule:

- replay transfer matching may use a replay-local canonical symbol alias key
  when:
  - the row is already continuity-proven by `correlationId` and
    `continuityCandidate`
  - the default continuity identity is not already a `FAMILY:*` identity
- this alias key exists only to repair carry continuity across contract-identified
  on-chain rows and symbol-identified venue rows
- it must not silently broaden global family aggregation for portfolio reads

Linked bridge carry rule:

- already-linked same-family `BRIDGE_OUT -> BRIDGE_IN` rows must not reuse the
  generic correlated-transfer quantity matcher
- replay must treat them as a bridge-specific carry lane keyed by
  `correlationId + bridge family`
- when destination quantity is lower than source quantity, the full source cost
  basis stays on the destination leg and the quantity delta is interpreted as
  bridge / settlement cost embedded in the carry
- when destination quantity is higher than source quantity, replay carries the
  full source cost basis and leaves only the excess destination quantity
  uncovered
- ambiguity-safe guard remains: if the bridge key resolves to more than one
  pending opposite-side candidate, replay falls back to unresolved inbound
  materialization instead of guessing

Linked asset-changing bridge settlement rule:

- already-linked `BRIDGE_OUT -> BRIDGE_IN` route pairs with
  `continuityCandidate = false` must not reuse the generic same-asset transfer
  matcher
- replay treats them as a dedicated route-settlement lane keyed by
  deterministic `correlationId`
- first approved slice requires:
  - one principal transfer out on the source row
  - one principal transfer in on the destination row
  - unique correlated pair with no competing pending candidate
- source-side native tx-value route funding on routed bridge starts such as
  `swapAndStartBridgeTokensViaSquid(...)` is not a second principal transfer leg
  when:
  - exactly one token outbound principal leg exists on the source row
  - one native outbound leg exactly matches tx `value`
  - the bridge-start route is already proven by audited routed bridge semantics
- replay reallocates source carried cost basis into the destination asset
  instead of leaving the destination fully uncovered
- destination covered quantity is derived from the source covered-share ratio,
  not from source/destination quantity equality
- this lane is intentionally conservative:
  - it preserves acquisition basis on the received asset
  - it does not yet synthesize explicit realized PnL for non-stable source
    asset disposal
- if ambiguity appears, replay must fall back to unresolved inbound
  materialization

Order-insensitive family custody rule:

- simple audited family-equivalent custody transactions with one principal
  outbound transfer and one receipt inbound transfer must replay as an atomic
  carry pair
- this same replay rule also applies to canonical `WRAP` / `UNWRAP` rows such
  as `ETH <-> WETH`; wrapped/native continuity is not allowed to materialize as
  a new uncovered acquisition just because the normalized flow order is
  inbound-first
- replay resolves that pair by audited family identity and direction, not by
  the raw normalized flow order inside the transaction
- inbound-first normalized flow order must not materialize the receipt leg as
  uncovered before the paired principal outflow is processed
- minor quantity drift between principal and receipt is handled with the same
  carry principle as linked bridges:
  - full source cost basis remains on the destination leg
  - only the unmatched excess destination quantity remains uncovered

This contract lets the UI:

- draw the AVCO/cost-basis curve for one asset inside one session
- overlay economic events as markers from the same ordered trace
- open the exact point where replay first became incomplete or suspicious
- replay gate stops are not successful completion:
  - if active `NEEDS_REVIEW` or pending stat rows still block replay, session
    state must persist as `ACCOUNTING_REPLAY / BLOCKED`
  - `COMPLETE` is reserved for paths that actually reached replay and
    post-replay materialization
- stale `ACCOUNTING_REPLAY / RUNNING` state may be healed only by the resume
  watchdog and only when:
  - the session has no pending upstream work
  - `asset_ledger_points` already exist
  - the replay trace exists for the same `accountingUniverseId`
- stale `PORTFOLIO_SNAPSHOT_REFRESH / RUNNING` state may be retried by the
  resume watchdog only after `ACCOUNTING_REPLAY` replay outputs exist for the
  same accounting universe.
- current asset-ledger family reads must expose which live uncovered buckets
  come from:
  - no covered ledger carry at all
  - partial carry only
  - missing current ledger bucket
  This diagnostic is a read-model aid; it does not invent basis.
- the same read path should also expose top historical family shortfall sources
  derived from positive `quantityShortfallDelta` rows so operators can see the
  first transactions that introduced today's unresolved coverage debt
- that `shortfallSources` surface is explanatory only:
  - it must not mutate replay state
  - it must not synthesize basis
  - it is not a guaranteed exact lineage map from one current uncovered bucket
    to one historical source tx

---

## 6. Deferred Work

Deferred until after v3 core is stable:

- portfolio snapshots
- user-facing holdings and portfolio APIs on top of derived
  `asset_ledger_points + on_chain_balances`
- unmatched live-balance discrepancy model
- Solana balance refresh
- broader transaction history projections
- extra CEX connectors
- microservice extraction
