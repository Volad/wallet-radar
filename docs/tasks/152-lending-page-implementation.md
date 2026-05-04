# Task 152: Lending Page Implementation

Status: In progress - lifecycle audit remediation
Owner: business-analyst + system-architect
Implementation owners: backend-dev, frontend-dev

## Goal

Implement a dedicated lending workspace that follows the
`walletradar-lending-lp.jsx` lending mockup while excluding LP implementation
from this task.

The page must show open and closed lending positions across the protocol
families present in the user's history:

- Aave
- Euler
- Morpho
- Fluid
- Compound
- other lending history rows as unsupported snapshot rows when accounting
  evidence exists but no protocol metric adapter is available

## Scope

In scope:

- dedicated `/lending` route using the same workspace-mode pattern as settings
  and asset-ledger
- lending-only summary, filters, protocol cards, expandable details, supply and
  borrow rows, transaction history, loop grouping, health factor, and APY
- backend `lending` module with a snapshot-first read API
- health/APY estimation or protocol snapshot metadata that does not mutate
  normalization, pricing, AVCO, or ledger state

Out of scope:

- LP page/detail implementation
- changing portfolio totals to subtract borrow liabilities
- live RPC, explorer, or protocol calls from the GET endpoint
- tax semantics

## Acceptance Criteria

- `GET /api/v1/sessions/{sessionId}/lending` returns lending summary, protocol
  groups, positions, and history from Mongo snapshots/read models.
- The endpoint returns no LP rows.
- The endpoint performs no live RPC, explorer, or protocol calls.
- Health factor is always renderable for groups with borrow exposure:
  protocol snapshot value when available, otherwise an explicit accounting-based
  estimate with source/status metadata.
- APY is always renderable for supply and borrow rows:
  protocol snapshot value when available, otherwise an explicit protocol/asset
  estimate with source/status metadata.
- Open position detection uses current positive receipt/debt balance.
- Closed position detection uses existing lending history with no current
  positive receipt/debt balance for that group.
- `LENDING_LOOP_*` rows are visually grouped with loop markers like the mockup.
- Frontend keeps the existing app shell, but the lending workspace layout,
  density, cards, history chips, and health visual treatment follow the mockup.
- LP section and LP code paths are not modified except for routing/nav
  interactions required by the new `/lending` workspace.

## Architecture Decision

```text
normalized_transactions     asset_ledger_points      on_chain_balances
          |                         |                       |
          +-----------+-------------+-----------+-----------+
                      |                         |
                      v                         v
              lending application module  current quote snapshots
                      |
                      v
        GET /api/v1/sessions/{sessionId}/lending
                      |
                      v
              Angular lending workspace
```

Rules:

- Accounting truth comes from confirmed canonical rows and ledger replay output.
- The lending workspace may include read-model-only lending-like vault receipt
  rows, such as Fluid `f*` markets, and reward claims for known lending
  protocols. This does not relabel canonical accounting rows; it only keeps the
  product workspace complete for protocols whose on-chain interface is
  vault-style or reward-only in the observed history.
- Lending attribution is protocol-entrypoint driven. Receipt-like asset symbols
  such as `syrupUSDC` are supporting asset evidence only after a protocol
  target, decoded nested calldata, or full receipt logs prove a lending
  interaction. A plain swap that buys or sells `syrupUSDC` remains `SWAP`.
- Normalization must classify Fluid vault `operate` / `operatePerfect` calls as
  lending lifecycle rows when token evidence proves supply, withdraw, borrow,
  repay, or collateral-backed borrow movement. These rows must not fall through
  to generic `SWAP` or `EXTERNAL_TRANSFER_*`.
- Normalization must also detect Fluid operations wrapped by supported
  Instadapp/DSA calldata surfaces, including `cast(tuple[] actions_)` and
  ERC-721 `safeTransferFrom(address,address,uint256,bytes)` payloads that embed
  a registry-owned Fluid vault.
- Plasma Euler EVK `batch(tuple[] items)` through
  `0x7bdbd0a7114aa42ca957f292145f6a931a345583` is a supported lending surface.
  Collateral transfers into known EVK vault contracts must be normalized as
  Euler lending deposits, and receipt-only stable/collateral EVK mint batches
  must be normalized as Euler loop opens instead of generic external inbound
  rows.
- Compound V3 Comet markets must be registered per network before read-model
  grouping. On Unichain, `0x2c7118c4c88b9841fcf839074c26ae8f035f2921` is the
  `cUSDCv3` market; `withdraw(address,uint256)` is borrow only when the asset is
  the market base asset, otherwise it remains a lending withdraw.
- Historical lending rows may construct lifecycle history, but current open
  supply or debt requires current-state evidence. Vault/NFT/account-based debt
  protocols such as Fluid and Compound must not synthesize current open debt
  from `BORROW - REPAY` history alone. Tokenized debt protocols may show open
  debt only when the current debt-token balance is visible.
- Health factor and APY are lending analytics, not accounting inputs.
- Health/APY metadata must expose its source and status so the UI does not
  present estimates as protocol-authoritative values.
- The endpoint is read-only and snapshot-first.

## Backend Tasks

1. Add a separate `com.walletradar.lending` module.
2. Add `SessionLendingQueryService` to aggregate lending groups from confirmed
   lending/loop canonical rows, lending-like vault receipt rows, lending
   protocol reward claims, ledger points, current balances, and current price
   quotes.
3. Add a small lending market metric estimator/adapter seam for Aave, Euler,
   Morpho, Fluid, and Compound.
4. Add `SessionLendingResponse` DTO and `LendingController`.
5. Keep query classes below oversized class thresholds by separating symbol and
   metric support from the aggregation service.
6. Add focused backend tests for open/closed grouping, loop history grouping,
   and metric fallback metadata.

## Frontend Tasks

1. Add strict REST DTOs and `getSessionLending`.
2. Add `LendingPageComponent` as a standalone Angular workspace component.
3. Add `/lending` route and dashboard workspace-mode rendering.
4. Implement mockup-inspired summary, filters, expandable cards, health bar,
   supply/borrow rows, history filter chips, and loop grouping.
5. Preserve existing dashboard/filter styling where it improves consistency.
6. Add frontend tests for route-mode rendering and lending data states.

## Verification

```bash
./gradlew :backend:test
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
```

Latest verification:

- `./gradlew :backend:test` passes.
- `npm test -- --watch=false --browsers=ChromeHeadless` passes.

## Cycle 93 Lending Valuation Backlog

The cycle 93 audit source is `auto-loop-handoff/artifacts/cycle/93`. It
identifies a read-model valuation gap after lifecycle reconstruction, not a
canonical accounting pricing or AVCO defect.

### Business Acceptance Criteria

- The Lending API exposes a dedicated total valuation surface for every cycle:
  `principalInUsd`, `principalOutUsd`, `borrowedUsd`, `repaidUsd`,
  `rewardsUsd`, `feesUsd`, `gasUsd`, `totalUsdPnl`, `currentUsdValue`,
  `unrealizedTotalUsdPnl`, `totalUsdPnlPrecision`, `yieldOnlyPnl`,
  `yieldOnlyPnlPrecision`, `valuationMethod`, and `unavailableReason`.
- `totalUsdPnl` is broader than lending-yield attribution. It may use
  deterministic or policy-approved event-time/current valuation for
  cycle-attached lending economic legs.
- `yieldOnlyPnl` remains separately gated and must stay `UNAVAILABLE` when
  wrapper/share-rate, underlying conversion, or unresolved lifecycle evidence
  prevents deterministic yield attribution.
- `EURC` total USD valuation uses cached ECB EUR/USD historical quotes when
  available. It must not silently fall back to USD stablecoin parity.
- `deUSD` remains stable-like for total valuation unless transaction-local
  execution evidence proves a material parity break.
- `wstETH` may use wrapper market valuation for `totalUsdPnl`; deterministic
  share-rate conversion is required only for strict yield-only attribution.
- `wstUSR` may use cached market valuation for `totalUsdPnl`, but must not
  unlock `yieldOnlyPnl` without deterministic wrapper/share-rate attribution.
- Gas reduces total valuation only when timestamped native USD fee valuation is
  available. If native gas quantity exists but USD gas valuation is missing, the
  API keeps `gasByAsset`, leaves gas valuation unresolved, and degrades total
  precision instead of fabricating zero gas cost.
- Unresolved lifecycle truth blocks total valuation even if historical prices
  exist.
- GET `/api/v1/sessions/{sessionId}/lending` remains snapshot-first and
  performs no live RPC, explorer, or price-provider calls.

### Architecture Decisions

```text
normalized_transactions + cached prices + current balances
        |
        v
lending lifecycle reconstruction
        |
        v
lending valuation sub-phase
  |- total cycle cashflow valuation
  |- current open-position valuation
  |- separate yield-only PnL status
        |
        v
persisted/snapshot-first lending read model API
        |
        v
Angular lending workspace
```

- Lending valuation is a lending read-model concern. It must not mutate
  normalized transaction pricing, replay, AVCO, move basis, or accounting truth.
- The first implementation slice stores valuation as a cycle-attached API/read
  model sub-document. A separate Mongo snapshot collection can be introduced
  later if runtime profiling proves the current rebuild path needs independent
  persistence.
- Valuation precision values are `EXACT`, `ESTIMATED`, and `UNAVAILABLE`.
- Existing `pnlBreakdown` keeps the yield-only contract. New total valuation
  fields explain total cycle USD result separately.

### Backend Tasks

1. Add a cycle-level lending total valuation model/calculator fed by lifecycle
   economic buckets and current cycle positions.
2. Extend `SessionLendingResponse.Cycle` with the total valuation sub-document.
3. Prevent lending read-model price fallback from treating `EURC` as USD parity;
   use cached historical/current ECB-backed quotes when present.
4. Track missing event valuation and missing gas USD valuation separately so
   precision can degrade to `UNAVAILABLE` or `ESTIMATED` with a stable reason.
5. Keep existing yield-only `pnlBreakdown` behavior and expose it through
   `yieldOnlyPnl` / `yieldOnlyPnlPrecision` in the total valuation contract.
6. Add regression tests for stable-like total valuation, EURC no-parity
   fallback, wstUSR total-vs-yield split, missing gas valuation precision, and
   unresolved lifecycle blocking total valuation.

### Frontend Tasks

1. Extend strict Lending REST DTOs and view models for cycle `totalValuation`.
2. Use total valuation for closed/running cycle and group P&L summaries.
3. Keep yield-only warnings readable and avoid rendering raw unavailable reason
   codes as primary labels.
4. Keep the main lending workspace layout unchanged except for the valuation
   fields required by this backlog.

### Edge Cases

- In scope: closed stable cycles with all event values present.
- In scope: EURC cycles with cached ECB quotes.
- In scope: wrapper cycles where total valuation is possible but yield-only PnL
  is not.
- In scope: open cycles with current position value and realized cashflow
  history.
- Out of scope: live price fetches from the Lending GET endpoint.
- Out of scope: using lending valuation as accounting replay input.

### Risk Notes

- If historical prices are missing for a non-stable leg, total valuation must be
  explicitly unavailable rather than zero-valued.
- API clients must distinguish total valuation from yield-only PnL; mixing them
  would reintroduce misleading lending results.
- A future persisted collection may be needed if cycle reconstruction becomes
  too expensive, but the current implementation must still preserve
  snapshot-first reads and avoid live network calls.

## Cycle Workspace Refinement Backlog

The lending workspace must be cycle-first rather than event-first. The
`walletradar-lending-cycles.jsx` mockup is the visual and interaction reference
for the lending working area. The existing application filter panel may remain
consistent with the rest of WalletRadar, but the main lending content must match
the mockup density, protocol cards, vault sections, cycle cards, collapsed loop
groups, PnL blocks, and left navigation active-state treatment.

### Cycle Business Rules

- A cycle opens only on the first `Supply` / `Deposit` event in the context.
- Aave parent context is `protocol + network + wallet`, but the read model may
  expose concurrent strategy cycles inside that account-pool when independent
  supply-only collateral and borrow/repay loops overlap. Borrow/repay events
  attach to the matching active debt strategy; supply/withdraw events attach to
  the matching collateral strategy.
- Fluid and Morpho context is `protocol + network + wallet + vault/account/market`.
- Euler and Compound context is `protocol + network + wallet + market/account`.
- `Borrow`, `Repay`, `Withdraw`, and `Reward` must not open a cycle by
  themselves. If no opening supply/deposit is present, the row remains an
  orphan/unresolved protocol event instead of a clean cycle.
- A close-side event for an asset that is not currently open in the cycle must
  not be attached to that cycle. It remains an unresolved/orphan event unless
  earlier evidence proves the missing entry leg.
- A cycle closes only when net supply is zero and net debt is zero within the
  configured dust tolerance. `Repay` closes debt only; it does not close a
  cycle while collateral/supply remains.
- Lifecycle state uses canonical lending assets for closure checks. Native
  wrapped aliases such as `ETH/WETH`, bridged stable aliases such as
  `USDT0/USD₮0`, and Aave receipt/debt prefixes are normalized before checking
  whether supply and debt are fully exited.
- Cycle `assetDeltas` use the same lifecycle asset normalization. Debt-token
  rows such as `variableDebtAvaGHO` must be displayed and included in PnL as
  `GHO`, not as a separate protocol receipt asset.
- Accrued interest must not prevent closure. A cycle is flat when there is no
  positive remaining supply or debt in the lifecycle state; over-withdraw or
  over-repay deltas caused by interest are treated as closed, not as an open
  negative balance.
- Looping is a read-model grouping: repeated `Borrow -> Supply/Deposit` actions
  within 24 hours in the same cycle/context are rendered as one collapsed loop
  transaction group.
- Collapsed loop labels must show all unique borrowed and supplied lifecycle
  assets in the group, so mixed loops such as EURC plus GHO do not appear as a
  single-asset loop.
- Fluid vault cycles must use the resolved vault/account counterparty as their
  market lane when classifier evidence provides it. A single generic
  `VAULT-ACCOUNT` lane is diagnostic fallback only.
- Full historical event evidence remains available through the API, but the UI
  default view must show grouped business timeline rows rather than raw
  accounting deltas.

### PnL Rules

- Cycle PnL is lending yield, not collateral price movement.
- `netPnlUsd = interestEarnedUsd - interestPaidUsd - gasUsd`.
- PnL must be `UNAVAILABLE` when a cycle is unresolved, has a missing principal
  exit, or contains non-stable collateral whose yield cannot be separated from
  price movement with available share-rate/valuation evidence.
- `interestEarnedUsd` is supply-side interest/reward value that can be separated
  from principal.
- `interestPaidUsd` is repaid debt value above borrowed principal.
- `gasUsd` is the sum of confirmed fee flows in the cycle.
- Lending transfer rows without persisted `valueUsd` use cached
  `historical_prices` on the read path before falling back to current/stable
  prices. The lending page must not trigger live price-provider calls.
- For share/vault assets, historical USD PnL is `UNAVAILABLE` unless the
  read-model has share-rate plus historical price evidence sufficient to split
  yield from price movement.
- Open cycles may show estimated running PnL when the current receipt/debt
  evidence supports covered-principal comparison. Otherwise they show
  `UNAVAILABLE` with a reason.

### API Contract Additions

Each cycle must expose UI-ready fields:

- `pnlBreakdown`: `interestEarnedUsd`, `interestPaidUsd`, `gasUsd`,
  `netPnlUsd`, `precision`, `method`, and `reason`.
- `pnlAssetBreakdown`: `supplyIncomeByAsset`, `borrowCostByAsset`,
  `rewardsByAsset`, `gasByAsset`, `netIncomeByAsset`, `precisionByAsset`, and
  `reasonByAsset`. `gasByAsset` is native asset quantity, not USD. USD gas
  stays in `pnlBreakdown.gasUsd`.
- `peakSupplyUsd` and `peakBorrowUsd`.
- `durationDays`.
- `txGroups`: grouped timeline rows with type `open`, `borrow`, `loop`, `mid`,
  `close`, or `reward`.
- Loop groups expose `loopSteps`, `loopAssetIn`, `loopAssetOut`, and collapsed
  child `items`.

The existing `events` and `assetDeltas` fields remain available for diagnostics,
but they are not the primary UI surface.

### Frontend Acceptance Criteria

- Protocol cards match the mockup structure: compact protocol header, active
  and closed cycle counts, optional health bar, and PnL summary.
- Vault/account protocols render nested vault/account sections.
- Each Fluid/Morpho vault/account section owns its own cycle list; cycles from
  different vault/market keys must not be rendered under one shared header.
- Cycle headers show date range, duration, active/closed tag, interest
  breakdown, and net PnL.
- Active cycles are expanded by default. Closed cycles are collapsed by default.
- Loop transaction groups are collapsed by default and expand in place.
- The visible timeline uses grouped rows, not raw event rows.
- `AMBIGUOUS_NEEDS_REVIEW` orphan rows remain available as API diagnostics but
  are excluded from the primary lending workspace so repay/withdraw/reward-only
  fragments do not appear as clean user cycles.
- The left navigation active lending state includes the mockup-style colored
  background, border, and left active indicator.
- The filter panel can keep the application-wide style, but it must not distort
  the main lending workspace density.

## Cycle 86 Lifecycle Backlog

The cycle 86 financial audit changed the acceptance target from a flat lending
overview to deterministic lifecycle reconstruction. The accepted audit source is
`auto-loop-handoff/artifacts/cycle/86/financial-analyst/report.md`, clarified by
`auto-loop-handoff/artifacts/cycle/86/financial-analyst/clarification.md`.

### Business Acceptance Criteria

- The Lending API exposes lifecycle cycles under each protocol/network/wallet
  group and market lane.
- Grouping is deterministic by `protocol + network + wallet + marketKey +
  cycleId`.
- Aave Base `borrowETH(address,uint256,uint16)` rows are canonical `BORROW`
  events with protocol `Aave`.
- Aave `repayWithATokens(address,uint256,uint256)` is canonical `REPAY` with
  subtype metadata `REPAY_WITH_ATOKENS`; it must not be shown as deposit.
- Morpho `gtUSDCc` and `syrupUSDC` are separate market/cycle lanes under the
  Morpho protocol.
- Linea Euler-like rows are grouped under top-level protocol `Euler`; `Canonical`
  may remain only as route/counterparty metadata.
- Cycles close on exact zero first, or deterministic dust tolerance
  `10^-min(decimals, 12)` / `1e-12` for unknown decimals with a `$0.01` cap when
  priced.
- Rewards attach to a cycle only with direct market/cycle evidence or when
  exactly one matching market cycle is open. Ambiguous rewards remain standalone
  protocol-level reward events.
- MVP PnL exposes exact asset-level deltas and precision flags. Historical USD
  realized PnL is `EXACT`, `ESTIMATED`, or `UNAVAILABLE`; no unlabelled USD PnL
  is allowed.

### Backend Tasks

1. Extend `SessionLendingResponse` with cycle DTOs, market keys, event subtype,
   asset-level delta buckets, and PnL precision fields.
2. Replace flat history-only rendering contract with protocol group -> market
   cycle -> event timeline while keeping backward-compatible `history` rows for
   existing consumers.
3. Implement deterministic cycle building in the lending application module.
4. Fix Aave gateway/pool classification for `borrowETH` and
   `repayWithATokens` at the classifier stage.
5. Include protocol-backed Morpho vault rows in lending read-model filtering
   without restoring symbol-only proof.
6. Resolve Linea Euler-like protocol split before grouping.
7. Add regression tests for Aave Base borrow, Aave repay-with-aTokens, Morpho
   separated market lanes, cycle separation, dust close behavior, standalone
   rewards, and Linea Euler grouping.

### Frontend Tasks

1. Update strict REST DTOs and mapped view models for `cycles`, `marketKey`,
   lifecycle status, PnL precision labels, and event subtype.
2. Render protocol/network/wallet groups as market sections containing compact
   cycle rows/cards.
3. Render event timelines inside each cycle instead of one flat transaction
   list per protocol group.
4. Add market and cycle-status filters while keeping existing protocol/network
   and closed-position controls.
5. Display `exact`, `estimated`, and `unavailable` labels for PnL/valuation
   fields.
6. Render standalone rewards outside a cycle when backend returns no cycle
   attachment.

### Cycle 86 Acceptance Metrics

After implementation and rebuild:

| Metric | Target |
|---|---:|
| High severity lending mismatches | 0 |
| Aave Base `borrowETH` missing from API | 0 |
| Aave `repayWithATokens` misclassified | 0 |
| Morpho `gtUSDCc` missing rows | 0 of 14 |
| Morpho `syrupUSDC` missing rows | 0 of 1 |
| API cycles exposed | 48 or justified deterministic equivalent |
| API open cycles | 4 |
| API closed cycles | 44 |

Verification commands:

```bash
./gradlew :backend:test
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
scripts/prod-reset-rebuild-backend.sh
```

## Cycle 94 Wrapper Valuation And Cash Principal-Out Closeout

The cycle 94 audit changes the acceptance target for lending valuation from a
USD-only yield line to a cycle surface that separates total wallet-cash result,
strict lending-yield attribution, asset-denominated result, and internal
receipt/share evidence.

Accepted decisions:

- Historical `wstETH` and `wstUSR` prices already exist in runtime and must be
  consumed by the lending read model for `totalUsdPnl`.
- Wrapper total valuation may use the nearest cached wrapper quote inside a
  bounded snapshot-only wrapper window when no quote exists in the normal
  event-time window. This keeps the endpoint read-only and avoids live price
  calls; precision remains `ESTIMATED`.
- Wrapper market valuation may unlock estimated total USD valuation, but must
  not unlock `yieldOnlyPnl` until deterministic wrapper/share-rate attribution
  exists.
- Morpho Bundler3 continuity uses a default `15 minutes` window. An extended
  `24 hours` window is valid only when receipt/share balance continuity proves
  the chain; same-day timestamp proximity alone is not proof.
- Non-stable and staked-stable cycles must expose first-class asset-denominated
  PnL in addition to USD valuation.
- Large negative closed-cycle total PnL must expose `largePnlReasons[]` and
  `primaryLargePnlReason`.
- Euler EVK/EVC account cycles must separate wallet-cash principal exits from
  protocol-internal receipt/share/account movements.

Backend tasks:

1. Apply cached historical wrapper prices to every cycle-attached lending
   economic leg for total valuation, including lowercase symbol cache rows such
   as `wstETH` and `wstUSR`.
2. Keep wrapper `yieldOnlyPnl` gated by deterministic share-rate/conversion
   evidence.
3. Add Morpho Bundler3 migration/redeployment continuity linking and preserve
   linked multi-asset outputs.
4. Add first-class API fields:
   - `assetDenominatedPnlByAsset`
   - `assetDenominatedPrecisionByAsset`
   - `assetDenominatedReasonByAsset`
   - `primaryAssetPnlSummary`
   - `largePnlReasons`
   - `primaryLargePnlReason`
   - `principalOutCashByAsset`
   - `internalReceiptMovementByAsset`
5. For Euler Avalanche EVK cycle-2, expose wallet-cash clean total PnL:
   - `principalInByAsset.USDC = 2595.231191`
   - `principalOutCashByAsset.USDC = 2152.278542`
   - `totalUsdPnl ~= -443.042155` after gas
   - `largePnlReasons = ["SHARE_RATE_EFFECT", "GAS_COST"]`
   - `primaryLargePnlReason = "SHARE_RATE_EFFECT"`
   - internal EVK/account/share movements remain evidence and must not inflate
     clean cash principal-out.

Frontend tasks:

1. Display asset-denominated PnL for non-stable and staked-stable cycles.
2. Separate open-cycle current/unrealized value from closed realized PnL.
3. Show wrapper total USD estimates separately from yield-only unavailable
   state.
4. Render large-PnL reasons through user-facing labels; raw backend reason codes
   must not leak into the primary UI.
5. Do not show internal receipt/share/account movements as wallet-cash exits in
   the main cycle PnL summary.

Acceptance:

- `./gradlew :backend:test` passes.
- `scripts/prod-reset-rebuild-backend.sh` completes.
- `GET /api/v1/sessions/{sessionId}/lending` remains snapshot-first and
  performs no live RPC, explorer, or external price-provider calls.
- Morpho `gtUSDCc` cycle-4 reports linked `wstUSR` and `wstETH` outputs.
- Euler EVK cycle-2 separates cash principal out from internal receipt
  movement.
- Frontend displays asset-denominated PnL and large-PnL reasons with readable
  labels.

## Cycle 92 Morpho Bundler3 and Lending UI Closeout

### Audit Summary

Cycle 92 identified a proof-critical upstream classification defect, not a UI
cleanup issue. Morpho Bundler3 transactions on Arbitrum can atomically combine
collateral transfer, borrow/repay, ERC-4626 share mint/burn, and route-side
adapter activity. When a Bundler3 transaction has lending/vault evidence, it
must not fall through to generic `SWAP` classification.

The first failed stage is classification for the Morpho WSTETH lifecycle:

- `0x7eb876...cf65` is collateral add plus borrow/loop evidence, not a WSTETH
  sale.
- `0x8d142...7810` is a Morpho vault deposit / loop continuation into MCUSDC,
  not a generic swap.
- `0xf767...fc2d` is collateral/vault-share evidence, not a WSTETH sale priced
  from `0.001119 USDC` dust.

### Acceptance Criteria

After implementation and rebuild:

| Surface | Target |
|---|---|
| Morpho WSTETH principal in | `0.058333940222974617 WSTETH` |
| Morpho WSTETH principal out | `0.058333940222974617 WSTETH` |
| Morpho WSTETH principal net | `0` |
| `0x7eb876...cf65` | not `SWAP`; no WSTETH sale PnL |
| `0xf767...fc2d` | not `SWAP`; no dust-derived WSTETH price |
| `0x8d142...7810` | Morpho vault deposit / loop continuation |
| Fluid Arbitrum repay display | one economic `1808.868212 USDC` repay item |
| Reward-only lifecycle cards | hidden from default lending cycles |
| Raw UI labels | no `WALLET_VISIBLE_TRANSFER`, `closed/current-state-zero`, or raw `pnl_unavailable_*` primary labels |
| Loop copy | `Loop group - N step(s)`, not multiplier wording |

### Backend Tasks

1. Fix `MorphoProtocolSemanticClassifier` so Bundler3 collateral/borrow and
   vault-share mint shapes are classified before generic swap fallback.
2. Persist protocol child-leg metadata for Morpho Bundler3 rows so one
   normalized transaction can expose collateral input, borrow output, route
   input, receipt share output, and raw evidence references to the lending read
   model.
3. Prevent Morpho Bundler3 collateral/share-mint rows from using stablecoin dust
   as swap-derived collateral pricing.
4. Rebuild Morpho WSTETH lifecycle grouping from child legs or linked parent
   lifecycle metadata so the cycle includes all three WSTETH collateral inputs.
5. De-duplicate Fluid same-transaction repay evidence in summary/timeline
   display while preserving source evidence internally.
6. Keep reward-only rows out of default cycle cards unless they attach to a
   known parent lending lifecycle.

### Frontend Tasks

1. Humanize evidence/status/reason codes before rendering:
   `WALLET_VISIBLE_TRANSFER`, `DECODED_PROTOCOL_EVENT`,
   `RECEIPT_LOG`, `closed/current-state-zero`, and
   `pnl_unavailable_missing_wrapper_conversion_or_underlying_price_policy`.
2. Rename loop timeline copy to step count wording. Do not show a leverage
   multiplier unless the backend provides a separate `leverageRatio`.
3. Render health factor only for open debt-bearing groups; closed groups show
   closed/current-state labels instead of liquidation-risk numbers.
4. Keep reward-only unresolved rows out of the primary cycle list.

### Verification

```bash
./gradlew :backend:test
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
scripts/prod-reset-rebuild-backend.sh
```

## Cycle 90 Fluid Evidence And PnL Backlog

The cycle 90 audit source is
`auto-loop-handoff/artifacts/cycle/90/financial-analyst/report.md`, clarified by
`auto-loop-handoff/artifacts/cycle/90/DECISIONS.md`.

### Business Acceptance Criteria

- Fluid Arbitrum vault `0x3e11b9ae...` exposes the proven debt child leg:
  `borrowedByAsset.USDC = 1800`, `repaidByAsset.USDC = 1808.868212`, and
  `borrowCostByAsset.USDC = 8.868212`.
- Fluid Plasma current positions remain zero/open positions remain absent when
  resolver/current-state evidence proves NFT `1604` and NFT `1151` have
  `supply=0`, `borrow=0`, and `dustBorrow=0`.
- Full receipt/log evidence for Fluid Plasma wrapper/vault calls is recoverable
  through RPC receipt enrichment. `pnl_unavailable_missing_full_receipt_logs`
  is valid only before that evidence is persisted.
- After receipt/log enrichment, Fluid Plasma `b4f3` / NFT `1151` may expose
  exact asset-level result `+3.383316 USDT0` before gas and borrow cost
  `10.728139 USDT`.
- After receipt/log enrichment, Fluid Plasma `f2c8` / NFT `1604` exposes exact
  borrow cost `4.020693 USDT` and observed wallet cash flows, but USD/yield PnL
  stays unavailable until historical `wstUSR -> stUSR/USR` conversion,
  share-rate, and underlying price policy exists.
- Plasma event, transaction-history, and display evidence preserve `USDT0`.
  Aggregate lifecycle/accounting maps may use canonical `USDT` only where the
  backend intentionally aliases `USDT0` into the USDT family.
- When PnL is unavailable, wallet-visible deltas are not reported in
  authoritative `pnlAssetBreakdown` income maps. They are exposed as
  `observedFlowsByAsset` or an equivalent cycle-level evidence field with
  `isAuthoritativeForPnl=false`.

### Architecture Decisions

```text
known Fluid normalized row
        |
        v
full-receipt clarification
  |- Fluid LogOperate logs
  |- Fluid vault NFT Transfer logs
  |- all ERC-20 transfer logs
        |
        v
reclassification / normalized evidence
  |- metadata.vaultAddress / nftId / marketKey / wrapperKind
  |- metadata.positionKey or lifecycleKey
  |- clarificationEvidence receipt/log references
        |
        v
lending read model
  |- parent Fluid cycle by vault/NFT/account
  |- child legs for supply, borrow, repay, withdraw
  |- observed flows separate from authoritative PnL
        |
        v
Angular lending page
```

- No new required typed `lifecycle` object is needed on normalized rows for this
  backlog. Existing `metadata` and `clarificationEvidence` are sufficient when
  structured and stable.
- `cycleId` remains a read-model identifier derived from deterministic market
  and position keys; normalized rows persist `positionKey` or `lifecycleKey`.
- Full receipt recovery must be idempotent and bounded to supported Fluid rows;
  GET `/lending` remains snapshot-first and performs no RPC calls.
- Some Fluid rows become confidently identified as `Fluid` only after
  reclassification. The pipeline runs a bounded post-reclassification Fluid
  receipt recovery for canonical Fluid lifecycle rows that still lack
  `metadata.evidenceCompleteness=FULL_LOGS_PRESENT`, including rows still in
  pre-pricing statuses, then reclassifies those recovered rows before linking,
  pricing, and accounting proceed.
- `wstUSR` must not be priced as a generic standalone asset for lending-yield
  PnL. The future policy is wrapper/share-rate conversion to `stUSR/USR`
  underlying at the event block and then underlying USD pricing.

### Backend Tasks

1. Add full-receipt recovery selection for confirmed Fluid Plasma/Arbitrum
   lifecycle rows that lack persisted full receipt/log evidence.
2. Persist structured Fluid metadata and clarification evidence on rebuilt
   normalized rows: vault address, NFT id when known, market key, wrapper kind,
   evidence completeness, deterministic position/lifecycle key, decoded
   collateral/debt intent, and receipt/log references.
3. Decode Fluid `LogOperate`, Fluid vault NFT `Transfer`, and ERC-20 transfer
   logs from full receipts enough to materialize lending child legs.
4. Generalize/mirror Compound child-leg expansion for Fluid loop rows in
   `SessionLendingQueryService`.
5. Add `observedFlowsByAsset` or equivalent to the Lending API cycle contract
   for unavailable PnL evidence.
6. Keep Plasma `USDT0` in transaction/history display symbols while canonical
   aggregate maps may key stable debt/cost by `USDT`.
7. Add regression tests from `cycle/90/handoffs/backend-dev.md`.

### Frontend Tasks

1. Extend strict Lending DTOs/models for `observedFlowsByAsset`.
2. Render observed flows as evidence/history for unavailable cycles, not as
   earned PnL.
3. Keep unavailable PnL visually unavailable for Fluid Plasma until the backend
   returns authoritative PnL precision.
4. Verify Arbitrum Fluid shows borrow cost `8.868212 USDC` after backend
   child-leg expansion.

### Cycle 90 Acceptance Metrics

After implementation and rebuild:

| Metric | Target |
|---|---:|
| Fluid Plasma open positions | 0 |
| Fluid Arbitrum `borrowedByAsset.USDC` | `1800` |
| Fluid Arbitrum `borrowCostByAsset.USDC` | `8.868212` |
| Fluid Plasma `b4f3` exact asset result after full logs | `+3.383316 USDT0` before gas |
| Fluid Plasma `f2c8` borrow cost after full logs | `4.020693 USDT` |
| Fluid Plasma unavailable PnL deltas inside `supplyIncomeByAsset` | 0 |
| Fluid observed wallet flows outside authoritative PnL maps | present |

Verification commands:

```bash
./gradlew :backend:test
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
scripts/prod-reset-rebuild-backend.sh
```

## Cycle 91 Fluid Read Model And wstUSR Pricing Backlog

The cycle 91 audit source is
`auto-loop-handoff/artifacts/cycle/91/financial-analyst/report.md`, with
implementation instructions in
`auto-loop-handoff/artifacts/cycle/91/financial-analyst/results/required-changes.md`
and `auto-loop-handoff/artifacts/cycle/91/handoffs/backend-dev.md`.

### Business Acceptance Criteria

- Fluid Plasma `b4f3` / NFT `1151` remains a closed exact same-asset cycle:
  borrowed `1052.119325 USDT0`, repaid `1062.847464 USDT0`, supply income
  `3.383316 USDT0`, borrow cost `10.728139 USDT0`, and net asset result
  `-7.344823 USDT0` before gas.
- Fluid Plasma `f2c8` / NFT `1604` remains closed with exact debt evidence:
  borrowed `2573.316594 USDT0`, repaid `2577.337287 USDT0`, and borrow cost
  `4.020693 USDT0`.
- Fluid Plasma `f2c8` lending-yield-only PnL remains `UNAVAILABLE` until a
  deterministic historical `wstUSR -> stUSR/USR` conversion/share-rate source
  and underlying price policy are implemented.
- The terminal unavailable reason for Fluid Plasma `f2c8` is
  `pnl_unavailable_missing_wrapper_conversion_or_underlying_price_policy`, not
  `pnl_unavailable_missing_full_receipt_logs`, once full receipt/log evidence is
  persisted.
- Fluid Arbitrum `vault-3e11b9ae` aggregates one economic repay for same-tx
  wallet-visible and decoded `FLUID_LOG_OPERATE_REPAY` evidence:
  borrowed `1800 USDC`, repaid `1808.868212 USDC`, and borrow cost
  `8.868212 USDC`.
- UI/evidence rows may expose both the wallet-visible repay and decoded Fluid
  repay evidence, but only one is authoritative for debt/PnL aggregation.
- Plasma event/history display keeps `USDT0`; aggregate accounting maps may use
  canonical `USDT`.
- Historical `wstUSR` total USD valuation may use CoinGecko id
  `resolv-wstusr` after the price is cached, but this must not enable
  lending-yield-only PnL.

### Architecture Decisions

```text
normalized Fluid row
        |
        v
lending read model
  |- preserves evidence rows
  |- de-duplicates same-tx wallet-visible + LogOperate repay for aggregates
        |
        v
cycle asset deltas / PnL
  |- exact same-asset stable cycles
  |- wrapper cycles marked unavailable for yield-only PnL
        |
        v
pricing chain
  |- maps WSTUSR -> resolv-wstusr for historical total valuation
  |- does not synthesize wrapper share-rate/yield attribution
```

- The first failed stage for Arbitrum duplicate repay is the lending read model,
  not normalization or AVCO replay.
- The first failed stage for `wstUSR` total USD valuation is pricing mapping /
  valuation policy. Evidence for a CoinGecko market id exists, but deterministic
  wrapper conversion/share-rate evidence is still missing.
- GET `/lending` remains snapshot-first and performs no live RPC, explorer, or
  CoinGecko calls. It reads already persisted `historical_prices` and
  normalized evidence only.
- Do not add transaction-hash or wallet-specific production branches. Audit
  hashes are regression anchors only.

### Backend Tasks

1. In `SessionLendingQueryService`, de-duplicate Fluid same-tx repay evidence
   when wallet-visible and decoded `FLUID_LOG_OPERATE_REPAY` legs have the same
   market, asset, and quantity.
2. Keep duplicate evidence visible as non-authoritative observed flow when PnL
   is unavailable or in event diagnostics.
3. In `OnChainNormalizedTransactionBuilder`, make
   `erc20TransferLogReferences(...)` persist only non-NFT ERC-20 transfer
   references and explicitly exclude FluidVaultNFT transfers.
4. Add CoinGecko mapping `WSTUSR -> resolv-wstusr` so historical pricing can
   cache total USD valuation for `wstUSR` flows.
5. Keep `f2c8` yield-only PnL unavailable until wrapper conversion/share-rate
   and underlying price policy are implemented.
6. Add regression tests for Fluid Arbitrum repay de-duplication, Fluid ERC-20
   transfer reference persistence, and `WSTUSR` CoinGecko mapping.

### Frontend Tasks

1. No required layout/API contract change for cycle 91 if backend field names
   remain unchanged.
2. Keep rendering `observedFlowsByAsset` as evidence-only rows with
   `isAuthoritativeForPnl=false`.
3. Keep unavailable PnL labels for wrapper cycles when backend returns
   `pnl_unavailable_missing_wrapper_conversion_or_underlying_price_policy`.

### Cycle 91 Acceptance Metrics

After implementation and rebuild:

| Metric | Target |
|---|---:|
| Fluid Arbitrum `borrowedByAsset.USDC` | `1800` |
| Fluid Arbitrum `repaidByAsset.USDC` | `1808.868212` |
| Fluid Arbitrum `borrowCostByAsset.USDC` | `8.868212` |
| Fluid Plasma `b4f3` borrow cost | `10.728139 USDT` |
| Fluid Plasma `f2c8` borrow cost | `4.020693 USDT` |
| Fluid Plasma `f2c8` warning reason | `pnl_unavailable_missing_wrapper_conversion_or_underlying_price_policy` |
| Fluid Plasma `f2c8` authoritative supply-income map | empty |
| `WSTUSR` CoinGecko id | `resolv-wstusr` |
| Blocking needs review after rebuild | `0` |

Verification commands:

```bash
./gradlew :backend:test
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
scripts/prod-reset-rebuild-backend.sh
```

## Cycle 88 Lending Lifecycle Audit Backlog

The cycle 88 audit source is
`auto-loop-handoff/artifacts/cycle/88/financial-analyst/report.md`, clarified
by `auto-loop-handoff/artifacts/cycle/88/financial-analyst/results/required-changes.md`.

Scope boundary:

- Keep this remediation focused on Aave, Euler, Morpho, Fluid, Compound, Silo,
  Paradex false-lending cleanup, and LiFi exact bridge destination
  materialization.
- Do not include Lendle Rewards Station or MEV Capital in the core Lending page
  backlog. They may remain normalized vault/yield rows outside the Lending page
  until a later explicit product decision expands scope.

### Business Acceptance Criteria

- Selector `0x69328dec` is not globally sufficient for
  `LENDING_WITHDRAW`. It can resolve to lending only with verified lending
  pool/gateway/vault/comet/EVK evidence or receipt/debt-token evidence for a
  supported lending protocol.
- Paradex L1 Core withdrawal
  `0xc7aa483f0805a3548ff61a250209059ae8a91e28d172fcf0e8daf8f55d8a68ee`
  is not present in the Lending API and does not create an `Unknown lending`
  group.
- Exact LiFi destination tx
  `0x3518dca13ea3475fec65a1094710a1d787c184545542d94773b31c6a81e7ed84`
  is materialized as `BRIDGE_IN` and reciprocally linked to source tx
  `0x6abeed57c5eb32377a2da88f19fd8cf72362484dedafa665405bb73d5a132da3`.
- A lending cycle closes only on full position exit: zero supply/collateral,
  zero debt, no active vault/account position, and zero receipt/debt tokens or
  resolver-proven absence. `REPAY` closes debt only and must not close the
  whole cycle while collateral remains.
- Aave Mantle wallet `0x1a87...693f` has one open account-pool cycle starting
  at tx `0x3b8592a7465ce33bd64b266e11d1f665954cc3735be6e1590dffd226ac8664bd`
  and containing deposit, borrow, repay, and reward claim while current
  `AMANWETH` balance remains non-zero.
- Reward claims attach to the matching active protocol/network/wallet/market
  cycle. If no matching active cycle exists, the row is standalone protocol
  reward history, not a new lending position lifecycle.
- Fluid vault/NFT accounts, Euler EVK/EVC loops, and Compound Comet accounts
  use parent account/vault/market grouping rather than asset-symbol-only
  cycles. Token legs remain visible as sub-events.
- Vault/account-based cycles that close only through current-state-zero proof
  must carry `unresolved principal exit` when the historical principal exit row
  is missing; they must not appear as fully reconstructed clean closes.
- Compound Unichain cycle starts at first USDC supply. If current Comet state
  proves zero but the principal exit row is not reconstructed, the cycle is
  shown as `closed/current-state-zero` with an `unresolved principal exit`
  warning/reason.
- Aave Avalanche current `AAVAUSDC=1667.939099` must not display the whole
  current value as earnings when covered principal is `1667.897152 USDC`.
- MVP PnL exposes exact asset deltas first. USD PnL is `EXACT` only when all
  historical prices/share rates are available; share/vault USD PnL remains
  `UNAVAILABLE` with a reason until share-rate and price evidence exists.

### Architecture Decisions

```text
raw tx / receipt evidence
        |
        v
classification
  |- selector-only lending guard
  |- Paradex custody attribution
        |
        v
bridge linking
  |- exact LiFi receivingTxHash -> BRIDGE_IN
        |
        v
lending read model
  |- account/vault parent cycle key
  |- supply/debt state closes cycles
  |- current positions from current-state evidence only
        |
        v
Angular lending workspace
  |- parent cycles
  |- sub-events by role
  |- unavailable PnL / unresolved exit reasons
```

- The earliest failed stage must be fixed: classification for Paradex selector
  collision, linking for exact LiFi destination, and read-model cycle grouping
  for Aave/Fluid/Euler/Compound lifecycle issues.
- Do not add transaction-hash or wallet-specific production branches. Hashes in
  this section are regression anchors only.
- Lending GET endpoints remain snapshot-first and perform no live RPC,
  explorer, or protocol calls.
- Historical lifecycle rows may build cycle history. Current `positions[]`
  require current balances, debt-token balances, or protocol resolver/current
  state snapshots.

### Backend Tasks

1. Guard selector-only lending classification for `0x69328dec`; Aave Linea and
   other compatible rows remain lending only when verified by registry/resource
   or receipt-token evidence.
2. Add Paradex L1 Core attribution as custody/bridge withdrawal and keep it out
   of the Lending read model.
3. Materialize exact LiFi `receivingTxHash` destinations as `BRIDGE_IN` with
   reciprocal link fields and bridge/counterparty metadata.
4. Rework `SessionLendingQueryService` cycle builder so supply/collateral and
   debt state are tracked separately; `REPAY` alone does not close a cycle.
5. Attach rewards to the single matching active cycle; otherwise keep them as
   standalone reward history.
6. Add parent market grouping for Fluid vault/NFT, Euler EVK/EVC, and Compound
   Comet lifecycles.
7. Carry covered principal into open-position PnL so current value is not
   reported as earned value.
8. Expose explicit unavailable/warning reasons for unresolved principal exits
   and missing share-rate or price evidence.
9. Add focused tests named in
   `auto-loop-handoff/artifacts/cycle/88/handoffs/backend-dev.md`.

### Frontend Tasks

1. Make closed cycles discoverable by default through a visible closed count and
   clear toggle state.
2. Fix the summary card label/value mismatch: display actual protocol count or
   label the value as open groups.
3. Reload `/lending` after refresh/rebuild completion and preserve useful
   expanded context when possible.
4. Render parent lifecycle cycles first and group sub-events by role: supply,
   borrow, route/swap, repay, withdraw, reward.
5. Show backend reason text for `AMBIGUOUS_NEEDS_REVIEW`, unresolved principal
   exits, and unavailable PnL instead of numeric PnL.

### Cycle 88 Acceptance Metrics

After implementation and rebuild:

| Metric | Target |
|---|---:|
| `Unknown lending` groups | 0 |
| Paradex withdrawals shown in Lending API | 0 |
| Exact LiFi receiving tx left as `EXTERNAL_TRANSFER_IN` | 0 |
| Aave Mantle reward-only open cycle | 0 |
| Fluid Plasma standalone open `wstUSR` cycle | 0 |
| Euler Plasma token-level split of the audited loop | 0 |
| Compound Unichain reward-start lifecycle | 0 |
| Aave Avalanche current value reported entirely as earned value | 0 |

Verification commands:

```bash
./gradlew :backend:test
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
scripts/prod-reset-rebuild-backend.sh
```

## Cycle 89 Lending Lifecycle And PnL Backlog

The cycle 89 audit source is
`auto-loop-handoff/artifacts/cycle/89/financial-analyst/report.md`, clarified
by `auto-loop-handoff/artifacts/cycle/89/financial-analyst/clarification.md`.

### Business Acceptance Criteria

- Paradex L1 Core withdrawal
  `0xc7aa483f0805a3548ff61a250209059ae8a91e28d172fcf0e8daf8f55d8a68ee`
  is canonical `PROTOCOL_CUSTODY_WITHDRAW` when Paradex identity is proven,
  with positive wallet-side USDC flow and no Lending API presence. If Paradex
  identity is not proven, fallback is `EXTERNAL_TRANSFER_IN`.
- Fluid Plasma has no synthetic current open position unless Fluid vault
  resolver/account-state proof or a complete decoded close/open sequence proves
  non-zero supply or debt.
- Fluid lifecycle grouping requires vault/account/NFT identity and collateral
  plus debt deltas from decoded calldata, logs, or resolver evidence. Token
  symbols alone are never proof.
- Euler Plasma EVK/EVC rows require parent continuity through account,
  sub-account, controller, collateral vault, or batch/call context. When a
  coherent strategy window exists but account proof is incomplete, show one
  `AMBIGUOUS_NEEDS_REVIEW` parent candidate with child rows, not clean
  token-level cycles.
- Compound Comet current-state zero requires direct account reads:
  `borrowBalanceOf(account) == 0`, all relevant `collateralBalanceOf == 0`,
  and base supply state zero via `userBasic(account).principal` or equivalent.
  Historical principal exit still requires Comet calldata/log evidence.
- Compound Unichain is no longer an unresolved-principal-exit case after the
  cycle 89 raw-first recheck. It must reconstruct one closed Comet borrow
  lifecycle opened by the Bulker tx `0xcb8483...0b94`: collateral supply
  `ETH 0.919170497571836978`, borrow `USDC 2050`, repayments
  `USDC 2067.598567`, rewards `COMP 0.22549`, loop decrease/close bundles,
  and final WETH dust exit. ETH collateral price movement is not lending yield.
- Morpho markets remain separate by vault/market id. `gtUSDCc`, `syrupUSDC`,
  `wstUSR`, and `wstETH` merge only when same-tx or decoded bundler migration
  evidence proves share burn/redeem and new share mint/deposit continuity.
- PnL is asset-first. The API must expose derived historical income by asset in
  `pnlAssetBreakdown` and keep USD valuation summary in `pnlBreakdown`.
- `pnlAssetBreakdown.gasByAsset` is native gas quantity by asset, for example
  `ETH` or `MNT`; it is not `USD`. USD gas remains
  `pnlBreakdown.gasUsd`.
- For open cycles, `supplyIncomeByAsset` is an estimate derived from current
  position quantity minus covered principal quantity. It must not be computed
  as `withdrawn - supplied`, because open principal has not exited yet.
- USD PnL is `UNAVAILABLE` whenever principal exit, share-rate, historical
  price, or current-state evidence is missing. Share/vault USD PnL requires
  ERC-4626 share-rate or equivalent historical conversion evidence.

### Architecture Decisions

```text
normalized lending rows + current snapshots + historical prices
        |
        v
lending read model
  |- proof-gated protocol/vault/account lifecycle keys
  |- current-state evidence for open positions
  |- derived asset-level income
        |
        v
GET /api/v1/sessions/{sessionId}/lending
  |- assetDeltas: source movement buckets
  |- pnlAssetBreakdown: asset-level income contract
  |- pnlBreakdown: USD valuation contract
        |
        v
Angular lending workspace
```

- Fix supported flows at the earliest failed stage: classification for Paradex
  selector collision, Compound Bulker and Fluid decoded-intent paths,
  clarification for protocol account keys, and lending read-model grouping for
  parent lifecycles.
- Do not add transaction-hash or wallet-specific production branches. Audit
  hashes are regression anchors only.
- Lending GET remains snapshot-first and must not perform live RPC, explorer,
  protocol, or price-provider calls.
- `assetDeltas` stay as source movement buckets. `pnlAssetBreakdown` is the
  derived product contract for historical income in assets.

### Backend Tasks

1. Keep selector-only lending/vault classification guarded by verified protocol
   evidence; Paradex L1 Core becomes `PROTOCOL_CUSTODY_WITHDRAW`.
2. Add Compound V3 Bulker support for verified router
   `invoke(bytes32[],bytes[])` bundles. `ACTION_SUPPLY_NATIVE_TOKEN` plus
   `ACTION_WITHDRAW_ASSET` is `LENDING_LOOP_OPEN`; `ACTION_SUPPLY_ASSET` plus
   `ACTION_WITHDRAW_NATIVE_TOKEN` is loop decrease/close. The open row remains
   a single canonical `LENDING_LOOP_OPEN` with child display legs, not multiple
   top-level normalized transactions.
3. Treat Compound Comet base-asset `supply(...)` rows inside an active borrow
   lifecycle as repayments in the lending read model, while preserving the
   canonical normalized row.
4. Classify Fluid before transfer-direction heuristics. Decoded `newDebt`
   negative/INT_MIN means repay/payback intent; wrapper rows may carry only
   wallet-visible movements until full logs/internal transfers make economic
   quantities complete.
5. Preserve Fluid, Euler, and Morpho unresolved account/vault evidence
   as explicit `AMBIGUOUS_NEEDS_REVIEW` or warning state instead of synthetic
   clean cycles.
6. Add `pnlAssetBreakdown` to `SessionLendingResponse` and the lending view.
7. Compute `supplyIncomeByAsset = principalOutByAsset - principalInByAsset`.
8. Compute `borrowCostByAsset = repaidByAsset - borrowedByAsset`.
9. Copy rewards into `rewardsByAsset`.
10. Compute `gasByAsset` from native `FEE` flow quantities, while keeping
   `pnlBreakdown.gasUsd` in USD.
11. Compute `netIncomeByAsset = supplyIncome + rewards - borrowCost - gas`.
12. Populate `precisionByAsset` and `reasonByAsset` for unresolved lifecycle,
   missing principal exit, and missing share-rate evidence.
13. Use stable reason codes such as `unresolved_principal_exit` and
    `pnl_unavailable_missing_full_receipt_logs`; do not add a
    `CLOSED_WITH_WARNING` status enum.
14. Add focused tests for Compound Bulker open/decrease/close, Fluid
    decoded-intent direction override, stable asset income, debt/receipt symbol
    canonicalization, and native gas quantity separation.

### Frontend Tasks

1. Extend strict REST DTOs and lending models with `pnlAssetBreakdown`.
2. Map nullable backend records defensively while preserving strict UI types.
3. Render asset-level net income and precision/reason in expanded cycle detail.
4. Continue to show `pnlBreakdown` as USD summary and backend warning/status
   text for unavailable or unresolved cycles.

### Cycle 89 Acceptance Metrics

After implementation and rebuild:

| Metric | Target |
|---|---:|
| Paradex withdrawals shown in Lending API | 0 |
| Paradex L1 Core canonical lending/vault type | 0 |
| Fluid Plasma current open positions | 0 unless resolver/account state proves non-zero |
| Aave open cycles | 4 unless current-state evidence changes |
| Compound Unichain unresolved principal exit warning | 0 |
| Compound Unichain borrow cost by asset | `USDC 17.598567` |
| Compound Unichain rewards by asset | `COMP 0.22549` |
| Compound Unichain realized USD PnL | `ESTIMATED` |
| Euler Plasma token-level clean cycle without parent proof | 0 |
| Share/vault cycles without share-rate evidence USD PnL | `UNAVAILABLE` |
| Cycles exposing `pnlAssetBreakdown` | all cycles |

Verification commands:

```bash
./gradlew :backend:test
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
scripts/prod-reset-rebuild-backend.sh
```

## Cycle 87 Runtime Audit Backlog

The cycle 87 audit source is
`auto-loop-handoff/artifacts/cycle/87/financial-analyst/report.md`, clarified by
`auto-loop-handoff/artifacts/cycle/87/financial-analyst/clarification.md`.

### Business Acceptance Criteria

- Destination-side bridge settlement `expressExecuteWithToken(...)` is
  `BRIDGE_IN`, not `EXTERNAL_TRANSFER_IN`, only when verified
  bridge/router/settlement evidence exists and the non-fee wallet movement is
  inbound-only.
- Bridge source/destination continuity uses existing bridge-specific windows
  and quantity tolerances. No transaction-specific tolerance is allowed.
- The Mantle -> Arbitrum bridge sequence keeps carry basis; destination
  settlement rows must not create a new `BUY` acquisition.
- Aave Avalanche Pool `multicall(bytes[])` supply rows are
  `LENDING_DEPOSIT` when raw receipt evidence proves underlying outflow to an
  aToken contract and matching aToken mint to the tracked wallet.
- Fluid Plasma does not show an open `USDT0` debt when current Fluid resolver
  or current protocol-state evidence proves `borrow=0`, `supply=0`, and
  `dustBorrow=0`.
- Resolver/current-state unavailable is not enough to show an open vault/NFT
  debt. The API may keep historical cycles visible, but current `positions[]`
  must be absent or explicitly unavailable unless current debt evidence exists.
- Frontend renders current positions only from backend `positions[]` and renders
  lifecycle history from backend `cycles[]`.

### Architecture Decisions

```text
raw receipt evidence
        |
        v
classification
  |- verified bridge settlement selector + inbound-only movement -> BRIDGE_IN
  |- Aave Pool multicall + underlying out + aToken mint -> LENDING_DEPOSIT
        |
        v
linking / carry
  |- existing LI.FI / Across / Mayan bridge tolerances
        |
        v
lending read model
  |- historical events -> lifecycle cycles
  |- current open positions -> current-state evidence only
        |
        v
Angular lending workspace
```

- `expressExecuteWithToken` selector or function name alone is not sufficient
  proof. A verified bridge/router/settlement contract must be resolved through
  registry, receipt evidence, or bridge status evidence.
- Existing bridge tolerances remain the source of truth:

| Bridge lane | Window | Quantity delta |
|---|---:|---:|
| LI.FI trusted routed fallback | `180s` | `15%` |
| LI.FI generic fallback | `90s` | `2%` |
| Across | `15s` | `0.5%` |

- Fee-bearing bridge deltas are valid carry continuity only when destination
  quantity is less than or equal to source quantity and remains inside the
  selected lane tolerance.
- Current open lending positions are read-model facts backed by current
  balances/protocol snapshots. Historical rows are lifecycle facts and must not
  override current zero-state evidence.

### Backend Tasks

1. Add `expressExecuteWithToken` to bridge settlement selector support, guarded
   by verified bridge/router/settlement evidence and inbound-only movement.
2. Register the audited Arbitrum bridge settlement contract used by the cycle 87
   destination leg as bridge settlement evidence.
3. Ensure the destination leg `0x7f1786fb...62dcd8` class is `BRIDGE_IN`, has
   transfer flows rather than `BUY`, and can link/carry from the source leg
   using existing bridge tolerances.
4. Add Aave Pool multicall receipt-shape classification for underlying outflow
   plus aToken mint.
5. Remove history-derived current borrow synthesis for Fluid, Compound, and
   other vault/NFT/account-based lending protocols unless current-state evidence
   is present.
6. Add regression tests for verified `expressExecuteWithToken`, unverified
   selector non-promotion, Aave Avalanche multicall supply, and Fluid historical
   borrow rows not producing current open positions.

### Frontend Tasks

1. Keep current positions sourced strictly from backend `positions[]`.
2. Keep lifecycle events sourced from backend `cycles[]`.
3. Do not infer or synthesize Fluid/Compound open debt client-side from history.
4. Verify closed Fluid historical cycles remain renderable after backend removes
   the synthetic current borrow position.

### Cycle 87 Acceptance Metrics

After implementation and rebuild:

| Metric | Target |
|---|---:|
| Recent `expressExecuteWithToken` bridge destination as external buy | 0 |
| Aave Avalanche multicall supply rows as `UNKNOWN` | 0 |
| Fluid Plasma synthetic open `USDT0` debt when resolver/current state is zero | 0 |
| Blocking Lending API/page findings from cycle 87 | 0 |

Verification commands:

```bash
./gradlew :backend:test
cd frontend && npm test -- --watch=false --browsers=ChromeHeadless
scripts/prod-reset-rebuild-backend.sh
```
