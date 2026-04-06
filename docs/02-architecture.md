# WalletRadar — Architecture

> **Version:** SAD v3 target summary
> **Last updated:** 2026-04-05
> **Style:** Modular monolith (Spring Boot)

This document is the concise architecture summary for the v3 accounting rewrite.
The full target design, decision log, data-flow details, and cost analysis are in
[architecture-v3.md](architecture-v3.md).

---

## 1. Scope

The v3 milestone is intentionally narrow:

- keep existing raw collection (`raw_transactions`, `sync_status`, `backfill_segments`)
- keep persisted `user_sessions`
- keep an installation-wide tracked wallet universe for canonical normalization
- rebuild normalization on top of raw evidence
- rebuild pricing on top of canonical normalized flows
- replay AVCO and reconciliation from canonical confirmed events

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
    |- session/               persisted user_sessions
    |- accounting-universe/   stable owner scope for asset-ledger reads
    |- wallet-universe/       tracked_wallets projection
    |- ingestion/backfill/    existing raw collection
    |- normalization/         on-chain + Bybit canonicalization
    |- pricing/               historical USD resolution
    |- costbasis/             AVCO replay + asset ledger + reconciliation
    |
    +--> MongoDB
         user_sessions
         accounting_universes
         sync_status / backfill_segments
         tracked_wallets
         raw_transactions
         external_ledger_raw
         normalized_transactions
         asset_ledger_points
         on_chain_balances
```

---

## 3. Core Pipeline

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
  4. Bybit normalization
  5. exact `Bybit <-> on-chain` rematch
  6. pricing
  7. accounting replay

Important accounting note:

- `move basis` is not a separate ingestion stage
- it is evaluated inside accounting replay together with `AVCO`, realized cost
  basis, and reconciliation
- live-session progress is persisted in `user_sessions.pipelineState`, while
  wallet×network raw ingestion progress remains in `sync_status`

### 3.3 Session scope vs accounting universe

- `user_sessions.wallets` is the current UI-selected on-chain wallet subset
- `user_sessions.accountingUniverseId` points to a stable additive
  `accounting_universes` document
- universe members may include:
  - on-chain wallet addresses
  - custodial refs such as `BYBIT:<uid>`
- read models that explain cost basis and AVCO history must use the accounting
  universe, not the current wallet subset only
- this keeps the same historical ETH acquisition path visible even when current
  session composition changes later

### 3.1 Raw collection

- EVM ingestion may be explorer-first or provider-first by network, with bounded native RPC repair where configured.
- `ScamFilter` stays pre-persistence.
- `ScamFilter` must use composite promo/phishing signals and preserve known
  legitimate reward-claim routes that do not carry promo markers.
- Tx-level fields must be canonicalized before persistence. Transfer-style payload
  rows may populate `explorer.tokenTransfers[]`, but must not overwrite top-level
  tx fields such as `from`, `to`, `value`, `input`, or `methodId`.
- Raw docs land in `raw_transactions` with `normalizationStatus=PENDING`.

### 3.2 On-chain normalization

- Start only after raw backfill for wallet×network is complete.
- For a live session, the on-chain normalization drain must start from
  session-level backfill completion, not from the next cron tick.
- If tx-level ordering metadata is incomplete, run raw ordering repair before canonical normalization.
- Internal-transfer heuristics use the installation-wide tracked wallet universe, never an individual session payload.
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
- LP lifecycle type resolution and LP principal role assignment are separate
  concerns. `LP_ENTRY` / `LP_EXIT` stay canonical LP families, but underlying
  principal legs inside those rows must persist as continuity `TRANSFER`
  flows, not as synthetic `BUY` / `SELL`.
- `STAKING_DEPOSIT` / `STAKING_WITHDRAW` are also role-sensitive.
  Liquid-staking mint/redeem paths such as `AVAX -> sAVAX` and audited Bybit
  `ETH -> METH` staking rows keep principal and derivative legs in continuity
  `TRANSFER` inside the same base-asset family. Classic stake-contract custody
  paths such as Pancake SmartChef `deposit(uint256)` also keep
  principal/proof-token legs in continuity `TRANSFER`, while only explicit
  harvested reward side-flows remain economic `BUY`.
- Aave-style `BORROW` / `REPAY` families may contain debt-marker mint/burn legs
  and chain-specific execution-settlement refund legs in the same tx. Those
  marker / settlement legs must persist as continuity `TRANSFER`, while the
  reserve-asset principal remains the only economic `BUY` / `SELL` leg.
- `REWARD_CLAIM` rows may contain self-canceling wrapper / marker pairs inside
  the same tx. Exact same-asset same-quantity in/out pairs must not persist as
  economic `BUY` / `SELL`; they are continuity-only no-op evidence.
- Some real protocol bundles may legitimately mix continuity and reward legs in
  one canonical row. Pendle `zapOutV3SingleToken(...)` on the Mantle reward
  distributor is currently such a bundle: LP marker churn must not remain
  `BUY` / `SELL`, the principal output must remain continuity, and only the
  true reward leg may stay economic.
- Method-aware protocol bundles such as Morpho Bundler3 must be classified by
  contract-scoped dispatch before generic `multicall` / `bundle` fallback.
- Zero-amount token transfers without economic counterflow must never create `BUY` / `SELL` legs. Known setup/admin calls may resolve to `ADMIN_CONFIG`; unknown cases remain explicit review items.
- Protocol-registry runtime data is loaded from `backend/src/main/resources/protocol-registry.json` only.
- `event_topics` remain reference-only metadata and are ignored by the runtime classifier.
- Registry entries marked with `specialHandler` route into deterministic
  protocol semantics and then into family-owned final type mapping.
- If a special handler cannot support the observed method/function combination, the tx becomes `UNKNOWN -> NEEDS_REVIEW` with an explicit missing-data reason.
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
- Replay continuity is family-aware for audited custody-equivalent wrappers.
  The current audited family includes `ETH`, network `WETH`, `Aave WETH`
  receipt wrappers, and `vbETH`, so `Bybit ETH -> on-chain WETH -> aManWETH`
  moves basis without synthetic disposal or reacquisition.
- `zkSync` Aave gateway selectors are a narrow method-aware exception owned by
  normalization, not by replay:
  - `0x80500d20` `withdrawETH(...)` -> `LENDING_WITHDRAW`
  - `0x02c205f0` `supplyWithPermit(...)` -> `LENDING_DEPOSIT`
  - `0x474cf53d` `depositETH(...)` -> `LENDING_DEPOSIT`
  The override must also prove the expected `ETH/WETH/aZksWETH`
  wallet-boundary shape before generic unwrap / LP / heuristic lanes run.
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
- `on_chain_balances` refresh is bounded by tracked wallets plus the confirmed
  on-chain canonical asset universe. It is not an unbounded wallet scanner.
- provider-native current balances must normalize zero-address contract payloads
  (`0x0000000000000000000000000000000000000000`) into native accounting
  identity rather than persisting them as synthetic token contracts.
- The replay pipeline order is:
  - canonical replay
  - `asset_ledger_points` persistence
  - `on_chain_balances` refresh
- user-facing asset detail history APIs must read from `asset_ledger_points`
  filtered to the requested session wallet set and asset family.
- user-facing current holdings must be derived on read from
  `asset_ledger_points` plus `on_chain_balances`.
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
  - unrelated `on_chain_balances` rows must not heal a stale
    `ACCOUNTING_REPLAY / RUNNING` into `COMPLETE`

### 5.1 Asset Ledger Timeline Contract

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
  - `asset_ledger_points` and `on_chain_balances` already exist
  - replay is currently a global derived-state lane and no stronger
    `sessionId`-scoped completion evidence exists yet

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
