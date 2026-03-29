# WalletRadar — Accounting Policy

> **Version:** v3 target
> **Last updated:** 2026-03-28
> **Accounting method:** AVCO

---

## 1. Canonical Accounting Input

WalletRadar computes basis only from canonical documents in:

- `normalized_transactions WHERE status = CONFIRMED`

Canonical documents may originate from:

- `source = ON_CHAIN`
- `source = BYBIT`

Raw collections remain source evidence:

- `raw_transactions`
- `external_ledger_raw`

They are used for reconstruction and audit, but never replayed directly for AVCO.

Rows with `excludedFromAccounting = true` remain persisted for audit and operator
visibility, but are outside the active accounting scope. They never enter pricing
gates or AVCO replay.

Economic meaning is derived from backfill-available raw evidence and canonical
flows, not from human-readable explorer page summaries.

Tx-level native `value` participates in accounting only when it comes from
canonical tx-level raw evidence. Token transfer-row amounts must never be
reinterpreted as direct native movement.

---

## 2. Replay Order

Replay must be deterministic:

1. `blockTimestamp ASC`
2. `transactionIndex ASC`
3. `_id ASC` as final tie-breaker

For Bybit canonical rows, `transactionIndex = 0`.

---

## 3. AVCO Rules

### On BUY

```text
newAvco = (currentAvco * currentQty + priceUsd * deltaQty) / (currentQty + deltaQty)
newQty  = currentQty + deltaQty
```

### On SELL

```text
avcoAtSale   = currentAvco
realisedPnl  = (sellPriceUsd - avcoAtSale) * abs(deltaQty)
newQty       = currentQty - abs(deltaQty)
newAvco      = currentAvco
```

### On TRANSFER

- quantity moves
- basis carries forward
- no new acquisition lot is created
- no realised PnL is created

### On FEE

- `FEE` is stored as a separate canonical flow
- fee quantity reduces the fee asset quantity and contributes to `totalGasPaidUsd`
- when policy capitalizes gas into an acquisition, replay may allocate fee USD into the same transaction's BUY basis
- outside such capitalization, `FEE` does not create a new lot and does not create realised PnL for the target asset being acquired or disposed

### On PRICE_UNKNOWN

- quantity still updates
- price fields remain null
- `hasIncompleteHistory = true`

---

## 4. Basis Continuity Rules

Basis continuity applies when the economic owner did not dispose of the asset:

- `BRIDGE_OUT -> BRIDGE_IN`
  Basis carries across networks only when the bridge pair is correlated and the
  bridged asset identity is preserved across the pair.
- `BRIDGE_OUT(depositV3) -> BRIDGE_IN(fillV3Relay/fillRelay/redeemWithFee/execute302/directFulfill)`
  Destination-side settlement remains bridge continuity, not repay or vault semantics.
- asset-changing bridge routes
  A correlated bridge route may still be objective `BRIDGE_OUT -> BRIDGE_IN`
  semantics without qualifying for plain move-basis continuity. If the source
  asset and destination asset differ, replay must not silently carry the same
  lot across the pair as if it were a same-asset bridge.
- correlated `EXTERNAL_TRANSFER_OUT -> EXTERNAL_TRANSFER_IN`
  Basis carries only when replay confirms that both sides belong to the same accounting universe.
- `Bybit -> on-chain`
  Same-universe continuity is replay-derived from `correlationId`, `continuityCandidate`, and matched counterpart evidence.
- `PROTOCOL_CUSTODY`
  Basis moves into and out of custody without creating BUY/SELL.
- `LENDING_DEPOSIT -> LENDING_WITHDRAW`
  Principal basis moves between spot balance and receipt-token/custody state without realization.
- `VAULT_DEPOSIT -> VAULT_WITHDRAW`
  Basis moves between spot balance and vault-share/custody state without realization.
- Async request / execute lifecycles
  Basis may move only when the runtime can deterministically correlate the
  request-side principal with the later settlement-side execution. Request
  multicalls, burn-only unbonding initiations, or settlement-only receipt mints
  without that correlation remain explicit blocker rows rather than inferred
  continuity.
- `LP_ENTRY_REQUEST -> LP_ENTRY_SETTLEMENT`
  Audited async LP-entry families may carry basis through a request-side escrow
  row and later settlement row when both share the same deterministic
  `correlationId`.
  - if current evidence proves a higher-scope lifecycle key than an
    intermediate helper key, the higher-scope key must be the persisted
    `correlationId`
  - once both rows exist, both rows must expose each other via
    `matchedCounterparty`
- `LP_EXIT_REQUEST -> LP_EXIT_SETTLEMENT`
  Audited async LP-exit families may carry burned-share basis through a
  request-side withdrawal intent and later keeper-side payout settlement when
  both share the same deterministic `correlationId`.
  - once both rows exist, both rows must expose each other via
    `matchedCounterparty`
  - same-asset execution-fee refunds restore their own carry first
  - the remaining burned-share basis is then allocated deterministically across
    the principal settlement payout legs
  - `GMX / GLV` withdrawal intent is determined from request-side multicall
    lifecycle shape and burned share asset, not from a top-level router address
    hit alone
  - when `bytes[]` selector decode is incomplete, the same audited withdrawal
    family may still be confirmed from saved raw calldata selector fragments,
    but only together with audited helper selectors and outbound-only burned
    share movement
- `STAKING_WITHDRAW_REQUEST -> STAKING_WITHDRAW`
  Burn-only unstake / cooldown initiation may move basis into a pending
  lifecycle bucket, and the later claim payout restores that basis into the
  received asset.
- `DEX_ORDER_REQUEST -> DEX_ORDER_SETTLEMENT`
  Audited async spot-order families may hold the sold asset in an open order
  bucket first and finalize the economic swap only when the later settlement
  payout arrives with the same deterministic `correlationId`.
- audited Resolv unstake settlement
  The later `withdraw(...)` payout is continuity restoration, not a fresh
  acquisition. The principal `RESOLV` leg stays `TRANSFER` and restores basis
  from the correlated `STAKING_WITHDRAW_REQUEST`.
- classic `STAKING_DEPOSIT -> STAKING_WITHDRAW`
  Basis moves between spot balance and staking custody without realization when
  the tx is a classic stake-contract deposit/withdraw rather than a liquid
  staking asset conversion.
- `REWARD_CLAIM`
  Requires actual inbound reward movement to the tracked wallet. Claim calls with
  no inbound movement stay explicit non-economic / review rows and must not mint
  synthetic basis.
- `BORROW` / `REPAY`
  Only the reserve-asset principal is economic. Debt-marker mint/burn legs and
  chain-specific execution settlement / refund legs remain continuity-only and
  must not create spot basis lots or realized PnL.
- receipt-log-rich batch openings
  Marker-only debt/share evidence is not enough to claim finalized
  `LENDING_DEPOSIT` / `LENDING_WITHDRAW` semantics. If the batch decoder cannot
  reconstruct the real wallet-boundary economic lifecycle, the row remains
  explicit review and never enters pricing.
- audited Euler loop rows
  WalletRadar models the audited Euler looping family as a share-position
  lifecycle, not as a plain lending deposit/withdraw:
  - `LENDING_LOOP_OPEN` acquires the collateral-share asset with an implied
    event-local unit price derived from the clarified stable-like supply amount
  - `LENDING_LOOP_REBALANCE` moves carried basis between loop-share assets
    without realized PnL; exact same-asset marker churn is ignored, while
    genuine same-asset dust keeps pro-rata basis on the original asset
  - `LENDING_LOOP_DECREASE` partially disposes of that share-position and
    acquires the returned wallet-visible asset
  - `LENDING_LOOP_CLOSE` fully disposes of the remaining share-position into the
    returned wallet-visible asset
  - debt-marker evidence remains source/audit evidence and does not become a
    standalone replay lot
- self-canceling same-tx marker pairs
  Exact same-asset same-quantity inbound and outbound legs inside one tx do not
  create basis by themselves. They are continuity-only marker churn and must
  not remain active `BUY` / `SELL`.

For matched Bybit withdraw/deposit:

- both source sides remain traceable
- canonical replay uses `TRANSFER` semantics only
- no duplicate BUY/SELL is allowed

---

## 5. Asset Identity Rules

- WETH-like wrappers are stored as-is
- wrapper-to-native aliasing is applied only at replay/pricing time when policy allows
- `stETH`, `mETH`, `rETH`, `wstETH`, and `cbETH` remain distinct assets
- LP tokens, receipt tokens, vault shares, and custody markers are not treated as new basis lots unless explicitly modeled as economic principal

---

## 6. Pricing Policy

Historical price resolution order:

1. stablecoin parity
2. exact execution price from canonical source evidence
3. swap-derived price
4. wrapper/native mapping
5. Binance historical market data
6. CoinGecko historical fallback
7. unresolved price flag

Implications:

- pricing failure does not remove quantity from replay
- pricing gaps must be visible as warnings or blockers
- Binance is the primary external market-data source, not the primary overall
  pricing source
- CoinGecko is a limited fallback, not the primary pricing mechanism

Transaction-type pricing contract:

- `SWAP`
  - Price from the executed ratio of the canonical wallet-boundary legs.
  - A canonical `SWAP` is valid only when the row contains both wallet-visible
    `SELL` and `BUY` legs.
  - If one side is stablecoin, that ratio is authoritative for the non-stable
    leg.
  - If neither side is stablecoin but one side already has deterministic market
    price, derive the counterpart from the same executed ratio instead of
    pricing both sides independently.
  - Multi-hop or router internals do not create independent pricing legs; use
    the canonical net in/out legs only.
  - Routed bridge-start selectors such as
    `swapAndStartBridgeTokensViaMayan(...)`,
    `swapAndStartBridgeTokensViaStargate(...)`, and
    `swapAndStartBridgeTokensViaSquid(...)` are not wallet-local `SWAP`; they
    remain `BRIDGE_OUT`.
  - Those bridge-start selectors may still require persisted receipt evidence
    for later destination-side pairing, but that evidence requirement must not
    demote the source row into `SWAP`.
  - Aggregator/router rows that prove only outbound wallet movement and no
    wallet-visible `BUY` leg are not wallet-local `SWAP`; they demote to
    owner-agnostic `EXTERNAL_TRANSFER_OUT` or explicit review according to the
    canonical evidence.
  - Time-window proximity to a later inbound on another chain is not enough to
    promote a generic routed outbound row into `BRIDGE_OUT`; protocol-specific
    bridge evidence must exist in current raw or clarification evidence.
- `BUY` / `SELL`
  - Use the exact execution price when canonical evidence already carries it
    (for example Bybit trade fills).
  - Otherwise use the same event-local pricing rules as `SWAP` when the row
    already carries exact countervalue.
- `EXTERNAL_TRANSFER_IN`, `REWARD_CLAIM`, `LP_FEE_CLAIM`
  - Treat as acquisition at receive-time fair market value.
  - Resolve via external market data only when tx-local execution evidence is
    absent.
- Bybit inbound rows are eligible for this rule only when canonical
  normalization has actually materialized them as `EXTERNAL_TRANSFER_IN`.
  Basis-relevant inbound raw evidence may not remain silently
  `UNKNOWN / CONFIRMED`.
- `EXTERNAL_TRANSFER_OUT`
  - Treat as disposal at event-time fair market value unless the row has
    already been normalized into an explicit continuity or pending-request
    family.
- async request / execute families
  - Request-side escrow / burn rows and execution-side settlement rows are not
    priceable while request correlation remains unresolved.
  - Once current raw proves deterministic correlation, they should resolve into
    explicit async lifecycle types rather than stay `UNKNOWN / NEEDS_REVIEW`.
  - Burn-only unbonding initiations such as `stRESOLV initiateWithdrawal(...)`
    are pending requests, not finalized withdraws.
  - Execution-side receipt/share mints such as GMX
    `executeDeposit(...)` / `executeGlvDeposit(...)` are not standalone
    acquisitions until their request-side principal is correlated.
- `WRAP` / `UNWRAP`
  - Principal is continuity, not a new buy/sell.
  - Use the underlying native asset price for valuation and fee accounting
    only.
- correlated `EXTERNAL_TRANSFER_OUT` / `EXTERNAL_TRANSFER_IN`,
  correlated `BRIDGE_OUT` / `BRIDGE_IN`,
  `PROTOCOL_CUSTODY`, `LENDING_DEPOSIT` / `LENDING_WITHDRAW`,
  `VAULT_DEPOSIT` / `VAULT_WITHDRAW`, `LP_POSITION_STAKE` /
  `LP_POSITION_UNSTAKE`
  - Principal uses carry-over basis only.
  - Market pricing is not required to continue basis.
  - Only explicit fee or reward side flows are priced.
- `LP_ENTRY`
  - Principal tokens move into LP custody with carry-over basis.
  - Underlying principal outflows inside `LP_ENTRY` must persist as
    `TRANSFER`, not `SELL`.
  - Receipt markers such as `LP token`, `BPT`, or CL position NFT stay
    continuity-only markers and do not become new basis lots.
  - The pricing stage must not invent a synthetic sale of token0/token1 or a
    synthetic buy of an LP token just to assign USD.
  - Any explicit swap, fee, or reward side-flow inside the same canonical tx is
    priced separately by its own rules.
- `LP_EXIT`
  - Principal returning from LP custody remains basis continuity.
  - Underlying principal inflows inside `LP_EXIT`, `LP_EXIT_PARTIAL`, and
    `LP_EXIT_FINAL` must persist as `TRANSFER`, not `BUY`.
  - Burned LP receipt markers remain continuity-only and do not create disposal
    accounting on their own.
  - Explicit fee/reward delta above principal continuity is priced as
    `LP_FEE_CLAIM` or `REWARD_CLAIM`.
  - A bundle-aware LP exit may still carry explicit `BUY` reward side-flows in
    the same canonical row when current raw evidence can separate them
    deterministically. Those reward side-flows are priceable; the LP principal
    remains `TRANSFER`.
- `BORROW`
  - Reserve asset received by the wallet is a `BUY`.
  - Debt-marker mint legs and execution-settlement refund legs are `TRANSFER`.
- `LENDING_LOOP_OPEN`
  - The collateral-share asset is the economic acquisition leg.
  - Event-local unit price may already be resolved during normalization from
    clarified stable-like supply evidence.
  - Debt-marker legs are not separate replay lots.
- `LENDING_LOOP_REBALANCE`
  - Outbound old share and inbound replacement share are continuity
    `TRANSFER`, not realized `SELL` / `BUY`.
  - Replay carries remaining basis from the old share asset into the new share
    asset.
  - Exact same-asset same-quantity roundtrip markers are ignored as technical
    churn, not new inventory.
- `LENDING_LOOP_DECREASE` / `LENDING_LOOP_CLOSE`
  - The collateral-share asset is the economic disposal leg.
  - The returned wallet-visible asset is the economic acquisition leg.
  - Event-local ratio between returned asset and share quantity is authoritative
    for the share disposal price when the canonical row already proves it.
- `REPAY`
  - Reserve asset sent by the wallet is a `SELL`.
  - Debt-marker burn legs and execution-settlement refund legs are `TRANSFER`.
- `STAKING_DEPOSIT`
  - Liquid-staking conversions such as `AVAX -> sAVAX` stay economic
    `SELL principal / BUY derivative`.
  - Classic stake-contract deposits such as Pancake SmartChef
    `deposit(uint256)` keep staked principal and proof-token markers as
    `TRANSFER`.
  - Any same-tx harvested reward token remains `BUY`.
- `STAKING_WITHDRAW`
  - Liquid-staking unwind stays economic `SELL derivative / BUY principal`.
  - Classic stake-contract withdraw keeps returned principal and proof-token
    markers as `TRANSFER`.
  - Any same-tx harvested reward token remains `BUY`.
- matched `Bybit withdraw/deposit` plus on-chain leg
  - persisted canonical type stays `EXTERNAL_TRANSFER_OUT` / `EXTERNAL_TRANSFER_IN`
  - same-universe continuity is resolved only during replay
  - do not price the principal twice on both sides once continuity correlation is active
- `UTA_TRADE_PAIR_NOT_FOUND`
  - a residual orphan leg is insufficient evidence, not a priceable acquisition
    or disposal
  - it must remain outside `PENDING_PRICE` unless a second official Bybit
    source reconstructs the missing leg deterministically
  - if the team chooses not to import another official Bybit source, the row
    may remain persisted as `NEEDS_REVIEW + excludedFromAccounting=true`
- pending-request and pending-order families
  - They remain non-priceable until final settlement or cancellation evidence
    exists.
- audited async spot-order families
  - `DEX_ORDER_REQUEST` and `DEX_ORDER_SETTLEMENT` are in-scope canonical rows
    for order-based spot execution such as CoW Eth Flow.
  - request-side principal may not leak into standalone `EXTERNAL_TRANSFER_OUT`.
  - settlement-side payout may not leak into standalone `EXTERNAL_TRANSFER_IN`.
  - replay finalizes the source-asset disposal only when the correlated
    settlement payout exists.
  - once settlement is proved, the request row should expose the terminal tx via
    `matchedCounterparty`.
- GMX V2 derivative order / execution lifecycle
  - `DERIVATIVE_ORDER_REQUEST`, `DERIVATIVE_ORDER_EXECUTION`,
    `DERIVATIVE_ORDER_CANCEL`, `DERIVATIVE_POSITION_INCREASE`, and
    `DERIVATIVE_POSITION_DECREASE` are in-scope canonical lifecycle rows.
  - persisted `GMX` EventEmitter hashed topics are authoritative runtime
    evidence; runtime may not lower-case event names before hashing
  - request-key correlation remains EventEmitter-driven, but same-receipt
    structured lifecycle logs may refine generic terminal execution into
    `DERIVATIVE_POSITION_INCREASE` or `DERIVATIVE_POSITION_DECREASE` once the
    receipt is already proved to belong to `GMX`
  - They must never fall back to `EXTERNAL_TRANSFER_IN`,
    `EXTERNAL_TRANSFER_OUT`, `LP_*`, or spot `SWAP`.
  - They do not represent spot acquisition / disposal of the market underlying.
    AVCO for spot assets must follow collateral, fee, and realized settlement
    flows instead of inventing a synthetic `BUY underlying / SELL underlying`
    path.
  - closing keeper txs that also auto-cancel sibling bracket orders must
    propagate their terminal pairing back onto the affected request rows.
  - the terminal keeper row itself must keep the primary executed request key
    as its own `correlationId`; sibling stop-order requests may keep their own
    request key while still pointing to the terminal tx through
    `matchedCounterparty`
  - Clarification is responsible for fetching order / execution evidence when
    explorer raw omits top-level transaction metadata but full receipt logs can
    still resolve the lifecycle deterministically.
  - Clarification is also responsible for fetching missing real keeper txs when
    current source evidence already proves the audited `GMX` derivative family
    but the terminal keeper tx is absent from Mongo.
  - Keeper execution rows must resolve to the most specific lifecycle that the
    persisted EventEmitter evidence proves. `PositionIncrease` may not remain
    generic `DERIVATIVE_ORDER_EXECUTION`.
  - Sibling stop / bracket requests may stay `DERIVATIVE_ORDER_REQUEST`, but
    the later auto-cancel terminal state must still be persisted once the same
    keeper receipt already proves it.
  - Persisted clarification evidence is authoritative runtime input whenever it
    exists in canonical raw shape; it must not be ignored because an attempt
    counter is stale.

Accounting exclusion policy:

- `excludedFromAccounting = true` is allowed only for deterministic,
  explicitly-documented unsupported families.
- Excluded rows remain visible in Mongo and audit outputs.
- Excluded rows do not block `avcoReady`.
- Excluded rows do not participate in AVCO replay, cost basis, or move-basis carry.
- Current exclusion families:
  - residual Bybit orphan legs with `UTA_TRADE_PAIR_NOT_FOUND`
  - residual Bybit rows with `BYBIT_LOAN_SEMANTICS_UNSUPPORTED`
- Any other `NEEDS_REVIEW` row remains a blocking review item by default.

External market-data source policy:

- Binance is the preferred external source for listed assets because it offers
  free public market data and a public historical archive.
- CoinGecko remains a fallback for assets or historical windows that Binance
  cannot cover, but it must not be treated as the only viable path for a
  two-year DeFi backfill.
- Long-tail DeFi assets should prefer tx-local swap-derived pricing over any
  generic external candle source whenever the canonical tx already proves the
  execution ratio.
- If neither tx-local nor external market data can produce a deterministic
  price, the row remains `PRICE_UNKNOWN` and replay proceeds with incomplete
  history signaling.
- `TRANSFER` flows are never market-priced by themselves, even when they appear
  inside a tx whose canonical type is otherwise priceable or continuity-based.
- Continuity-type rows may still contain explicit `BUY` / `SELL` side-flows.
  Those side-flows are priced normally and replayed as acquisitions /
  disposals, while the `TRANSFER` principal legs continue to carry basis.

---

## 7. Clarification Policy

`Clarification` may enrich receipt-safe metadata:

- execution status
- gas fields
- created contract address

Clarification is allowed only when those receipt-safe fields are actually missing.
Low confidence alone does not move a row into clarification.

Metadata-only clarification may not:

- treat synthetic `rawData.logs[]` as authoritative event evidence
- silently rewrite economic meaning without traceable evidence
- leave clarification-eligible rows without explicit missing receipt-safe reasons
- under-report a currently missing receipt-safe field in `missingDataReasons[]`

Implications:

- ambiguous `EXTERNAL_TRANSFER_IN` vs `REWARD_CLAIM` is not a clarification problem
- plain positive inbound transfer defaults are not a clarification problem
- promo/phishing suppression is not a clarification problem
- wrapped-native `deposit()` / `withdraw(uint256)` is not a clarification problem
- bridge entry / settlement selectors are not a clarification problem
- LP position-manager `multicall` / `modifyLiquidities` routing is not a clarification problem
- missing `transactionIndex` is a raw-repair problem before normalization, not a clarification retry
- `MISSING_CONTRACT_ADDRESS` is not a generic clarification fallback; it is valid
  only for explicit contract-creation rows
- missing `effectiveGasPrice` remains a clarification reason even when the
  tracked wallet is not the tx fee payer; live clarification rows must reflect
  the actual missing receipt-safe tx metadata
- `CLAIM_WITHOUT_MOVEMENT` is a valid per-wallet terminal outcome when the claim
  signer does not receive the reward transfer in persisted raw evidence

Clarification may additionally use full receipt evidence for an allowlisted
residual-review set:

- it may fetch and persist full receipt logs for that allowlisted set
- it should persist both:
  - the adapted clarification evidence used by runtime classification
  - the raw full receipt payload, when the source exposes it
- if a clarification source call already fetched a receipt payload, that
  source-native payload is persisted in full even when the current row uses
  only metadata-safe clarification semantics
- it must persist that clarification evidence in one canonical raw-level
  location that live Mongo audits and runtime classification both read
- clarification is not complete for a row unless that evidence is persisted on
  the raw document in a deterministic shape
- metadata-safe clarification versus receipt-log-backed clarification is a
  semantic policy only; it must not truncate an already fetched receipt before
  persistence
- it must store those fields separately from synthetic `rawData.logs[]`
- runtime classification and normalization access clarification evidence only
  through `OnChainRawTransactionView` / the canonical raw projection
- it may also persist same-source internal transfers for an allowlisted
  native-bridge subset when those internal legs are required for deterministic
  bridge continuity
- it may rerun classification only when official protocol semantics and the
  fetched receipt evidence together make the row deterministic
- it must fetch clarification evidence from the same source family that produced
  the raw row:
  - RPC-backed raw -> RPC clarification
  - Etherscan-family raw -> Etherscan-family clarification
  - Blockscout-backed raw -> Blockscout clarification
- cross-source fallback is allowed only as an explicit documented fallback, not
  as the default clarification path
- it may narrow a row into an explicit non-economic terminal state when receipt
  proves cleanup/admin behavior but not economic movement
- it may not use traces, explorer page labels, or analyst-only notes as runtime
  evidence
- it must not be used for rows that are already closable from current raw
  evidence, such as claim-family no-movement rows or known handler gaps
- safe current-raw non-economic families should leave `NEEDS_REVIEW` before
  clarification is even attempted, including spam / airdrop clusters,
  explicit `CLAIM_WITHOUT_MOVEMENT`, failed transactions, documented admin /
  governance actions, pending-request / pending-order initiation rows, and
  out-of-scope NFT or attestation mints
- if clarification still leaves only weak protocol identity, zero logs, or
  wrapper-only bookkeeping evidence, the row remains in the documented
  irreducible stop-condition set and is not forced into synthetic economic
  semantics

Clarification success does not by itself make the dataset pricing-ready.
Pricing remains blocked while a live post-rerun audit still shows:

- resolved wrapped-native selector rows leaking into `VAULT_DEPOSIT`,
  `VAULT_WITHDRAW`, or `LENDING_WITHDRAW`
- resolved recognized Across `depositV3(...)` rows leaking into `VAULT_DEPOSIT`
- resolved route-tagged bridge-initiation rows leaking into
  `EXTERNAL_TRANSFER_OUT`, including LI.FI / Jumper `callDiamondWith*`
  families and `transferRemote(...)`
- resolved Circle CCTP `redeem(bytes cctpMsg,bytes cctpSigs)` rows leaking into
  `VAULT_WITHDRAW`
- resolved explicit receiver-wallet `claim(...)` / `claimWithSig(...)` payout
  rows leaking into `EXTERNAL_TRANSFER_IN`
- resolved pending `claimSharesAndRequestRedeem(...)` rows leaking into
  priceable `EXTERNAL_TRANSFER_OUT`
- resolved claim-income rows leaking into `EXTERNAL_TRANSFER_IN`, including
  Pancake `harvest(...)` and vesting `release()`
- priceable GMX `createOrder(...)` rows leaking into `EXTERNAL_TRANSFER_OUT`
  before final settlement semantics exist
- GMX V2 `OrderVault + OrderCreated` or `executeOrder(...)` rows leaking into
  LP / custody / spot accounting families instead of the explicit derivative
  exclusion family
- clarification persistence mismatches where normalized clarification counters
  are ahead of persisted raw clarification evidence
- a broad repeatable review-tail family that should already be deterministic
  from current raw or allowlisted clarification evidence

Those are classification-time basis-semantics failures, not clarification-tail
warnings.

Pricing-readiness gate:

- an economic row may move to `PENDING_PRICE` or `CONFIRMED` only when
  persisted raw evidence or persisted clarification evidence proves non-fee
  movement semantics that are sufficient for cost-basis replay
- fee-only rows may remain resolved only when they belong to an explicit
  non-economic family such as `APPROVE`, `ADMIN_CONFIG`, or another documented
  terminal cleanup/admin type
- wrapper continuity, bridge-entry semantics, route-tagged bridge-initiation
  semantics, claim-income semantics, order-initiation demotion, liquidity
  entry/exit semantics, spam / airdrop demotion, and admin/config demotion
  must be correct before pricing/AVCO begins
- pricing/AVCO may start only when the residual review tail has been reduced to
  the documented irreducible stop-condition set; broad repeatable review
  families are still a basis blocker even when clarification is otherwise
  complete
- after `run/13`, normalization/clarification debt is no longer the blocker:
  - `NEEDS_REVIEW = 0`
  - `PENDING_CLARIFICATION = 0`
  - `clarification_persistence_mismatches = 0`
- pricing/AVCO may now begin from the cleaned canonical stream, and the active
  milestone shifts to price resolution and replay rather than further
  clarification closeout
- no new backfill is required to begin pricing; the current raw and normalized
  datasets are sufficient for the next stage

Current audited clarification candidate families for full receipt enrichment:

- Slipstream cleanup-burn, stake-contract, and zero-effect collect families
- Pancake / Infinity concentrated-liquidity exit and `modifyLiquidities`
  families
- selected Euler-style batch rows where full receipt logs reveal real asset
  transfers
- ParaSwap `swapExactAmountOut(...)`
- GMX `executeOrder(...)`
- Katana `routeSingle(...)`
- allowlisted native bridge families whose same-source internal transfers are
  required for deterministic bridge continuity
- Pancake / Infinity CL exit containers that prove collect / withdrawal /
  unwrap continuity from same-source clarification evidence
- Pancake / Infinity `modifyLiquidities(...)` rows that prove positive
  liquidity-add or tracked-wallet funding direction from persisted full receipt
- once those receipt-helpful families are closed, any remaining review row must
  either:
  - be a documented safe stop-condition with no basis impact, or
  - remain an explicit basis blocker that still prevents pricing/AVCO

---

## 8. Supported Accounting Scope

On-chain accounting networks:

- `ETHEREUM`
- `ARBITRUM`
- `OPTIMISM`
- `POLYGON`
- `BASE`
- `BSC`
- `AVALANCHE`
- `MANTLE`
- `LINEA`
- `UNICHAIN`
- `ZKSYNC`
- `KATANA`
- `PLASMA`

CEX accounting source:

- `BYBIT`

Out of scope for this milestone:

- additional CEX providers
- tax reporting
- NFTs
- rebase-token quantity tracking

---

## 9. Outputs

The replay layer must be able to produce:

- per-wallet asset quantity
- per-wallet AVCO
- realised PnL
- incomplete-history flags
- reconciliation status against available balance data

All outputs must be derivable again from the same canonical inputs with identical ordering.

For continuity events, replay must move quantity and carried basis without creating realised PnL.
