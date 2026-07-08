# Normalization Architecture

> **Based on:** raw_transactions (Etherscan explorer payload) · protocol-registry.json · external_ledger_raw (Bybit)
> **Output:** normalized_transactions with flows[] ready for Pricing → AVCO
> **Status:** Draft for architect review
> **Last updated:** 2026-03-28

---

## 1. Data Sources

### 1.1 raw_transactions (on-chain)

One document per transaction per wallet per network.

```
_id:                  txHash:networkId:walletAddress
txHash                string
networkId             string  (ETHEREUM, ARBITRUM, BASE, …)
walletAddress         string  (lowercase 0x…)
blockNumber           long
normalizationStatus   PENDING | COMPLETE
syncMethod            ETHERSCAN | BLOCKSCOUT | RPC

rawData:
  .to                 recipient address (contract or EOA)
  .from               sender  (usually == walletAddress)
  .value              tx-level native ETH/BNB/MNT call value in wei  (string)
                      Must never be populated from token-transfer rows.
  .input              calldata hex
  .methodId           first 4 bytes of input  ("0x????????")
                      may be blank or "0x" in explorer payloads;
                      recover from rawData.input[0:10] before Step 2
  .functionName       decoded signature from Etherscan  ("swap(…)" or "")
  .gasUsed            string decimal
  .gasPrice           string decimal  (wei)
  .isError            "0" | "1"
  .txreceipt_status   "0" | "1"
  .transactionIndex   string decimal   ← critical for deterministic AVCO ordering
                                        If missing, run raw ordering repair
                                        before canonical normalization.
  .timeStamp          string unix epoch

  .explorer.tx                        ← canonical tx-level payload, when available
    .from
    .to
    .value
    .input
    .methodId
    .transactionIndex
    .timeStamp

  .explorer.tokenTransfers[]           ← ERC-20 movements with full metadata
    .from               token sender address
    .to                 token recipient address
    .contractAddress    token contract address
    .tokenSymbol        string
    .tokenDecimal       string decimal
    .value              raw amount  (no decimal adjustment)

  .explorer.internalTransfers[]        ← native ETH via contract calls
    .from               address
    .to                 address
    .value              wei string
    .isError            "0" | "1"

  .logs[]                              ← may be real provider-persisted receipt logs
                                          OR synthetic transfer-derived logs
                                          Synthetic rows carry
                                          __syntheticTransferLog: true
                                          and are never classification evidence.
                                          Real persisted receipt logs may be
                                          consumed only through the raw view /
                                          method-aware handlers.
```

**Critical constraint:** classification trusts only canonical tx-level raw fields,
persisted transfer arrays, and persisted real receipt logs that came from backfill
or from a dedicated clarification receipt-evidence field exposed through the
same raw view.
Human-readable explorer page summaries may assist offline audit only; they are not
classifier evidence. If a source returns transfer-style payload rows, ingestion must
separate them into tx-level fields plus `explorer.tokenTransfers[]` instead of
letting transfer-row `value` or `to` contaminate the top-level tx shape.
Async request / execute families and burn-only unbonding requests must not be
promoted into active priceable rows unless current raw plus persisted
clarification evidence is sufficient to reconstruct the full basis lifecycle.
Container / router subcalls remain decoder output derived from saved
`rawData.input`; they are not materialized as standalone raw transactions.
`GMX` EventEmitter hashed topics are compared against canonical event names;
runtime may not lower-case event names before hashing them.

---

### 1.2 external_ledger_raw (Bybit CEX)

One document per raw CSV row, pre-classified by the Bybit normalizer script.

```
_id               BYBIT:uid:filename:rowIndex
source            "BYBIT"
sourceFileType    fund_asset_changes | uta_derivatives | withdraw_deposit | spot_trades
uid               string
sessionId         string
timeUtc           ISO 8601 string
assetSymbol       string  (USDT, ETH, MNT, …)
quantityRaw       string decimal  (signed: "-1.5" or "2.3")
canonicalType     pre-classification  (see §7)
basisRelevant     boolean
bybitType         original Type field from CSV
bybitDescription  original Description field from CSV

// uta_derivatives only
utaContract       string  (ETHUSDT, MNTUSDT, …)
utaDirection      BUY | SELL | null
filledPrice       string decimal
feePaid           string decimal
cashFlow          string decimal

// withdraw_deposit only
txHash            string | null
networkId         string | null  (ARBITRUM, ETHEREUM, …)
receivedAddress   string
outOfScope        boolean  (true for TON / SOL / HYPEREVM)
onChainCorrelation: { status: PENDING | MATCHED | UNMATCHED }

status            RAW → CONFIRMED
```

---

### 1.3 tracked_wallets (installation-wide wallet universe)

Canonical normalization may use "own wallet" knowledge, but it must not depend on
an individual browser session.

```
tracked_wallets
  address          lowercase wallet address
  refCount         number of active tracking references
  firstSeenAt      Instant
  lastSeenAt       Instant
```

**Critical constraint:** this is an installation-wide projection derived from persisted
tracking state. Normalization must never use a per-session wallet list as classifier input.

---

### 1.4 protocol-registry.json (lookup tables)

Two runtime lookup structures are loaded at startup from the classpath resource
`backend/src/main/resources/protocol-registry.json`.

`event_topics` may remain in the JSON as reference metadata, but the runtime classifier
does not load or use them because event-topic matching is not part of the accepted
runtime contract.

```
contracts     address.toLowerCase() → ProtocolEntry
method_ids    "0x????????"          → description string
```

**ProtocolEntry fields:**

| Field | Values |
|---|---|
| `family` | DEX · LENDING · STAKING · BRIDGE · CUSTODY · AGGREGATOR · YIELD · WRAPPER · PERP |
| `event_type` | SWAP · LP_MINT · LP_BURN · LENDING_DEPOSIT · LENDING_WITHDRAW · LENDING_LOOP_OPEN · LENDING_LOOP_REBALANCE · LENDING_LOOP_DECREASE · LENDING_LOOP_CLOSE · BORROW · REPAY · STAKING_DEPOSIT · STAKING_WITHDRAW · VAULT_DEPOSIT · VAULT_WITHDRAW · BRIDGE_OUT · PROTOCOL_CUSTODY_DEPOSIT · REWARD_CLAIM · null |
| `role` | ROUTER · POOL · POSITION_MANAGER · FACTORY · BRIDGE_ENTRY · BRIDGE_EXIT · STAKE_CONTRACT · VAULT · REWARD_ROUTER · WRAPPER_TOKEN · WRAPPER_CONTRACT · ORDER_VAULT · EXCHANGE_ROUTER · POSITION_ROUTER · ORDER_BOOK |
| `confidence` | HIGH · MEDIUM |
| `decomposeByLegs` | boolean — if true, the contract is not safe to classify by one static `event_type` alone |
| `specialHandler` | null \| `BALANCER_VAULT` \| `GMX_V2_EXCHANGE_ROUTER` \| `PENDLE_ROUTER` \| `MORPHO_BUNDLER` |

**Special-handler routing contract**

`specialHandler` is a registry/discovery hint only. Runtime classification
routes such entries through protocol semantics first and then through the owning
family classifier.

```
ProtocolSemanticClassifier.classify(
  ProtocolSemanticContext(view, discovery, legs)
) -> ProtocolSemanticHint[]

ProtocolSemanticHint:
  protocolKey
  semanticType
  protocolName
  protocolVersion
  correlationId?
  suggestedType
  confidence
```

Rules:

- no RPC calls
- no Mongo reads/writes
- no synthetic `rawData.logs[]`
- deterministic for the same `(view, discovery, legs)` input
- unsupported method/function combination → `UNKNOWN`, `NEEDS_REVIEW`,
  `missingDataReasons += HANDLER_UNSUPPORTED_METHOD`

---

## 2. Output Schema: normalized_transactions

```
_id               txHash:networkId:walletAddress   (on-chain)
                  BYBIT:uid:filename:rowIndex       (CEX)

txHash            string | null  (null for CEX rows without a tx)
networkId         string
walletAddress     string
source            ON_CHAIN | BYBIT

blockTimestamp    Instant  (from rawData.timeStamp or Bybit timeUtc)
transactionIndex  int      (from rawData.transactionIndex; 0 for CEX)
                  ↑ required for deterministic AVCO sort within the same second

type              NormalizedType  (see §3)
confidence        HIGH | MEDIUM | LOW
classifiedBy      PROTOCOL_REGISTRY | METHOD_ID | FUNCTION_NAME | HEURISTIC | MANUAL

flows[]:
  role            BUY | SELL | FEE | TRANSFER
  assetContract   string | null  (null for native assets and CEX)
  assetSymbol     string
  quantityDelta   BigDecimal signed  (+inbound, −outbound)
  unitPriceUsd    BigDecimal | null  ← filled by PricingJob
  priceSource     STABLECOIN | SWAP_DERIVED | WRAPPER | COINGECKO | MANUAL | UNKNOWN | null

missingDataReasons[]  string list
protocolName          string | null
protocolVersion       string | null
correlationId         string | null

status            PENDING_CLARIFICATION | PENDING_RECLASSIFICATION | PENDING_PRICE | PENDING_STAT | CONFIRMED | NEEDS_REVIEW
createdAt         Instant
updatedAt         Instant
```

Blocking `NEEDS_REVIEW` is preferable to silent active-lane corruption for:

- request / execute lifecycle families such as GMX deposit helpers plus
  `executeDeposit(...)`
- burn-only unbonding initiations such as `initiateWithdrawal(...)`
- receipt-log-rich Euler `batch(...)` rows whose full economic lifecycle is not
  yet decoder-complete

When current persisted raw already proves the lifecycle key or clarified
transfer path, those families should leave blocker review and resolve into
explicit canonical async/request or economic types instead of staying
`UNKNOWN`.

For the audited Euler loop family, the canonical model is intentionally
pragmatic:

- the collateral-share position is the economic asset tracked in canonical flows
- stable-like clarified supply / return amounts provide the event-local implied
  share price
- share-to-share Euler restructures are modeled explicitly as
  `LENDING_LOOP_REBALANCE` continuity rows instead of generic LP or UNKNOWN
  fallbacks
- debt-marker evidence remains in raw / clarification evidence and is not
  replayed as a standalone asset lot

---

## 3. Normalized Transaction Types

```
SWAP                        Token A exchanged for token B
LP_ENTRY                    Liquidity provided to a pool
LP_ENTRY_REQUEST            Async LP-entry request / escrow step
LP_ENTRY_SETTLEMENT         Async LP-entry execute / settlement step
LP_EXIT_REQUEST             Async LP-exit request / share-burn step
LP_EXIT_SETTLEMENT          Async LP-exit execute / settlement step
LP_EXIT                     Liquidity withdrawn from a pool
LP_FEE_CLAIM                Accumulated LP fees collected without removing position
LP_POSITION_STAKE           LP NFT position transferred to farm/strategy
LP_POSITION_UNSTAKE         LP NFT position returned from farm/strategy
LENDING_DEPOSIT             Asset supplied to a lending protocol
LENDING_WITHDRAW            Asset withdrawn from a lending protocol
LENDING_LOOP_OPEN           Euler-style borrow-backed loop / multiply opening
LENDING_LOOP_REBALANCE      Euler-style share-to-share rebalance / restructure
LENDING_LOOP_DECREASE       Euler-style partial deleverage / unwind
LENDING_LOOP_CLOSE          Euler-style final unwind into wallet-visible asset
BORROW                      Loan taken from a lending protocol
REPAY                       Loan repaid to a lending protocol
STAKING_DEPOSIT             ETH/token staked  (ETH → stETH / mETH / rETH)
STAKING_WITHDRAW_REQUEST    Burn / cooldown initiation before later unstake claim
STAKING_WITHDRAW            Staked position unwound
VAULT_DEPOSIT               Deposit into a yield vault  (ERC-4626 or similar)
VAULT_WITHDRAW              Withdrawal from a yield vault
BRIDGE_OUT                  Asset sent to another network via bridge
BRIDGE_IN                   Asset received from another network via bridge
DEX_ORDER_REQUEST           Async spot-order request / escrow funding
DEX_ORDER_SETTLEMENT        Async spot-order settlement / payout completion
DERIVATIVE_ORDER_REQUEST    Deterministic derivative order request / collateral lock
DERIVATIVE_ORDER_EXECUTION  Derivative order executed without position-direction decode
DERIVATIVE_ORDER_CANCEL     Derivative order cancelled / unlock path
DERIVATIVE_POSITION_INCREASE Executed derivative increase / open
DERIVATIVE_POSITION_DECREASE Executed derivative decrease / close
PROTOCOL_CUSTODY_DEPOSIT    Asset transferred to protocol custody  (Hyperliquid, GMX perp)
PROTOCOL_CUSTODY_WITHDRAW   Asset returned from protocol custody
REWARD_CLAIM                Rewards received  (fee claim, airdrop, staking yield)
EXTERNAL_TRANSFER_IN        Token/ETH received from an external counterparty
EXTERNAL_TRANSFER_OUT       Token/ETH sent to an unrecognized address
INTERNAL_TRANSFER           Legacy continuity marker; new reruns must derive continuity from metadata instead of persisting this type
APPROVE                     Token spend allowance  (no asset movement, not basis-relevant)
ADMIN_CONFIG                Wallet/protocol config call  (no asset movement, fee-only if gas exists)
WRAP                        Native token → wrapped token  (ETH → WETH)
UNWRAP                      Wrapped token → native token  (WETH → ETH)
UNKNOWN                     Classification failed; requires manual review
```

---

## 4. Classification Algorithm (on-chain)

Applied to every `raw_transactions` document with `normalizationStatus = PENDING`.
Steps execute in strict order. Stop at the first step that produces a result.

---

### Step 0 — Pre-checks

```
PRE-00  Raw ordering prerequisite
        if rawData.transactionIndex is missing after tx-level explorer payload merge
        → send the raw tx to bounded raw ordering repair by txHash
          do NOT guess `transactionIndex`
          do NOT continue into canonical normalization yet

PRE-00A Selector recovery prerequisite
        if rawData.methodId is blank or "0x"
        AND rawData.input length >= 10
        → use rawData.input[0:10].toLowerCase() as recovered method id
          for all remaining classifier steps

PRE-00B Raw tx-shape prerequisite
        if top-level tx fields disagree with canonical tx-level payload
        and the source row is transfer-shaped
        → classification and leg extraction must trust canonical tx-level fields
          from the raw view / explorer.tx
          do NOT treat transfer-row amount as direct native value

PRE-01  Failed transaction
        if rawData.isError == "1" OR rawData.txreceipt_status == "0"
        → type = UNKNOWN
          status = NEEDS_REVIEW
          missingDataReasons += "FAILED_TRANSACTION"
          STOP

PRE-02  No asset movement
        if tokenTransfers.isEmpty()
           AND internalTransfers.isEmpty()
           AND (rawData.value == "0" OR null)
        → likely approve, admin/config, or no-op contract call
           continue to Step 1 (registry may explain)
```

---

### Step 1 — Protocol Registry Lookup   [confidence: HIGH]

```
contract = rawData.to.toLowerCase()
entry    = protocolRegistry.lookup(contract, networkId)

if entry != null AND networkId ∈ entry.networks:

  REG-00  Explicit special handler
          if entry.specialHandler != null:
            hints = protocolSemantics.classify(view, entry, legs)
            if hints produce a family-owned decision:
              classifiedBy = PROTOCOL_REGISTRY
              STOP
            else:
              type = UNKNOWN
              status = NEEDS_REVIEW
              missingDataReasons += HANDLER_UNSUPPORTED_METHOD
              STOP

  REG-01  Wrapper token  (ETH ↔ WETH)
          if entry.family == WRAPPER:
            if contract == wrappedNativeContract(networkId)
               AND selector == 0xd0e30db0                          → WRAP
            if contract == wrappedNativeContract(networkId)
               AND selector == 0x2e1a7d4d                          → UNWRAP
            if native_out > 0 AND WETH_in  → WRAP
            if WETH_out AND native_in > 0  → UNWRAP
            confidence = HIGH
            STOP

  REG-02  Position Manager  (Uniswap V3 NFT PM, Camelot V3)
          if entry.role == POSITION_MANAGER:
            resolve by methodId:
              0x88316456 mint              → LP_ENTRY
              0x4f1eb3d8 increaseLiquidity → LP_ENTRY
              0xac9650d8 multicall         → decode supported inner selectors
                                             and classify as LP_ENTRY / LP_FEE_CLAIM /
                                             LP_EXIT when the inner call path proves it
              modifyLiquidities            → decode supported action set and classify
                                             as LP lifecycle by inner legs / action ids
              0x0c49ccbe decreaseLiquidity →
                if inbound non-fee movement present → LP_EXIT
                if only FEE flows                  → LP_FEE_CLAIM
              0xfc6f7865 collect           → LP_FEE_CLAIM
              0x00f714ce burn              → LP_EXIT
            confidence = HIGH
            STOP

  REG-03  Vault / Balancer / Convex
          if entry.role == VAULT:
            functionName or methodId:
              contains "join" or "deposit"  → VAULT_DEPOSIT or LP_ENTRY
              contains "exit"  or "withdraw"→ VAULT_WITHDRAW or LP_EXIT
              default                       → entry.event_type
            STOP

  REG-04  decomposeByLegs = true without specialHandler
          → registry configuration is incomplete
             type = UNKNOWN
             status = NEEDS_REVIEW
             missingDataReasons += REGISTRY_SPECIAL_HANDLER_REQUIRED
             STOP

  REG-05  All other registry entries
          type       = entry.event_type
          confidence = entry.confidence
          classifiedBy = PROTOCOL_REGISTRY
          STOP
```

---

### Step 2 — Method ID Lookup   [confidence: MEDIUM]

```
methodId = recovered method id from Step 0A / raw view

Swap methods
  0x7ff36ab5  swapExactETHForTokens        → SWAP
  0x18cbafe5  swapExactTokensForETH        → SWAP
  0x38ed1739  swapExactTokensForTokens     → SWAP
  0x414bf389  exactInputSingle  (V3)       → SWAP
  0xc04b8d59  exactInput        (V3)       → SWAP
  0xdb3e2198  exactOutputSingle (V3)       → SWAP

LP methods
  0x88316456  mint                         → LP_ENTRY
  0x4f1eb3d8  increaseLiquidity            → LP_ENTRY
  0x0c49ccbe  decreaseLiquidity            → LP_EXIT  (verify with flows)
  0xfc6f7865  collect                      → LP_FEE_CLAIM

Lending methods
  0x617ba037  supply      (Aave V3)        → LENDING_DEPOSIT
  0xe8eda9df  deposit     (Aave V2)        → LENDING_DEPOSIT
  0x69328dec  withdraw    (Aave V3)        → LENDING_WITHDRAW
  0xa415bcad  borrow      (Aave V3)        → BORROW
  0x573ade81  repay       (Aave V3)        → REPAY
  0x852a12e3  mint        (Compound V2)    → LENDING_DEPOSIT
  0xdb006a75  redeemUnderlying (Compound)  → LENDING_WITHDRAW

Bridge methods
  0x7b939232  depositV3 (Across)           → BRIDGE_OUT
                                             (apply only when contract / registry path
                                              confirms recognized bridge-entry semantics)
  0xa5d4d0cc  send (Stargate)              → BRIDGE_OUT
  0x9fbf10fc  send (Stargate OFT V2)       → BRIDGE_OUT

Bridge settlement methods
  0x2e378115  fillV3Relay (Across)         → BRIDGE_IN
  0xdeff4b24  fillRelay      (Across)      → BRIDGE_IN
  0xe2de2a03  redeemWithFee                → BRIDGE_IN
  0xcfc32570  execute302                   → BRIDGE_IN
  0x6befa3a5  directFulfill                → BRIDGE_IN
  (apply only when contract / registry path confirms bridge-settlement semantics)

GMX methods
  0xec51b4c9  createIncreasePosition       → PROTOCOL_CUSTODY_DEPOSIT
  0x6eba5d0c  createDecreasePosition       → PROTOCOL_CUSTODY_WITHDRAW
  0x0ad58d2f  createOrder        (V2)      → do NOT classify here
                                             use GMX V2 derivative order lifecycle
  0x2e7eff49  createDeposit      (V2)      → LP_ENTRY
  0x87d66368  createWithdrawal   (V2)      → LP_EXIT_REQUEST
  0xc96fea9f  executeWithdrawal  (V2)      → LP_EXIT_SETTLEMENT
  0xb88a802f  claimFees                    → REWARD_CLAIM
  0x9e6b0e50  mintAndStakeGlp              → LP_ENTRY
  0x0f3aa554  unstakeAndRedeemGlp          → LP_EXIT
  note: audited `GMX / GLV` async exit requests may confirm the withdrawal
        subcall family either from decoded `bytes[]` selectors or, when decode
        is incomplete, from saved raw calldata selector fragments together with
        helper-funding selectors, outbound-only movement, and burned share
        principal
  note: when a `GMX / GLV` settlement receipt contains both an intermediate
        deposit-executed key and a higher-scope GLV-executed key, normalization
        must persist the higher-scope GLV key as `correlationId`

Pendle methods
  0x4e7ed11c  swapExactTokenForPt          → SWAP  (buy PT)
  0x7e1fe8c0  swapExactPtForToken          → SWAP  (sell PT)
  0xf7f3d2af  swapExactTokenForYt          → SWAP  (buy YT)
  0x01a5fe2a  swapExactYtForToken          → SWAP  (sell YT)
  0xb0c7e3f8  addLiquiditySingleToken      → LP_ENTRY
  0x1ef4b0d8  removeLiquiditySingleToken   → LP_EXIT
  0x03bef7c5  redeemPyToToken              → SWAP  (redeem expired PT/YT)

Approve
  0x095ea7b3  approve                      → APPROVE  (confidence = HIGH)
  flows = []  status = CONFIRMED immediately

Router / overload methods
  0xac9650d8  multicall                    → do NOT classify here
                                             dispatch by contract + inner selector
  0x0cf79e0a  router/solver overload       → do NOT classify here
                                             contract-specific handler or explicit review
  0x374f435d  bundler / protocol batch     → do NOT classify here
                                             contract-specific handler or explicit review
  0x42842e0e, 0xb88d4fde safeTransferFrom  → do NOT classify here
                                             LP-position custody must be proven by
                                             known position-manager / farm context

Transfer / transferFrom
  0xa9059cbb, 0x23b872dd                   → do NOT classify here
                                             forward to Step 4 (heuristic)

classifiedBy = METHOD_ID
STOP if matched
```

---

### Step 3 — Function Name Keyword Matching   [confidence: LOW]

Applied when Steps 1 and 2 produced no result.
`functionName` is a broad fallback, not a final authority for wrapped-native selectors
or known bridge/router methods. Those cases must be resolved before this step can
assign generic `VAULT_*`, `EXTERNAL_*`, or `SWAP` types.

Reward-like names such as `claim`, `reward`, or `airdrop` are also not sufficient
when token metadata shows obvious promo/phishing markers. Bridge-settlement names
such as `repay`, `redeem`, or `fillRelay` must be resolved by selector / contract
before broad keyword fallback can assign lending or vault semantics.
Bridge-start names such as `swapAndStartBridgeTokensViaMayan`,
`swapAndStartBridgeTokensViaStargate`, and `swapAndStartBridgeTokensViaSquid`
must resolve before broad `swap` keyword fallback can assign `SWAP`.
Those explicit source-chain bridge-start selectors may still require bounded
full-receipt clarification for later pair reconstruction, but that extra
evidence need must not change the owner-agnostic source-side type away from
`BRIDGE_OUT`.

```
fn = rawData.functionName.toLowerCase()

FN-01  fn contains "claim"
         if known reward distributor or claim contract evidence exists
            AND tokenTransfers are all IN (no OUT)                      → REWARD_CLAIM
         if known reward distributor or claim contract evidence exists
            AND tokenTransfers contain OUT                              → SWAP  (claim-and-convert)
         else                                                          → continue

FN-02  fn contains "swap" OR "exchange" OR "trade"
         → SWAP

FN-03  fn contains "deposit" OR "supply" OR "provide"
         check tokenTransfers:
           OUT tokens present                   → LENDING_DEPOSIT or VAULT_DEPOSIT
           IN receipt token (aToken, cToken)    → LENDING_DEPOSIT

FN-04  fn contains "withdraw" OR "redeem" OR "exit"
         check tokenTransfers:
           IN tokens present                    → LENDING_WITHDRAW or VAULT_WITHDRAW

FN-05  fn contains "borrow"                    → BORROW

FN-06  fn contains "repay"
         (only if contract is not a known bridge-settlement contract)
                                               → REPAY

FN-07  fn contains "stake" OR "submit"         → STAKING_DEPOSIT

FN-08  fn contains "unstake"                   → STAKING_WITHDRAW

FN-09  fn contains "bridge"
         (only if contract not already in registry as bridge)
                                               → BRIDGE_OUT

FN-10  fn contains "addLiquidity" OR "mint" (LP context)
                                               → LP_ENTRY
       fn contains "removeLiquidity" OR "burn" (LP context)
                                               → LP_EXIT

classifiedBy = FUNCTION_NAME
STOP if matched
```

---

### Step 4 — Heuristic Pattern Analysis   [confidence: LOW]

Applied when Steps 1–3 produced no result.

**Build asset sets first:**

```
inTokens   = { contractAddress → transfer }  where transfer.to   == walletAddress
outTokens  = { contractAddress → transfer }  where transfer.from == walletAddress
nativeIn   = Σ internalTransfers.value  where to == walletAddress AND isError == "0"
           + direct tx-level native value if canonical tx-level recipient == walletAddress
nativeOut  = direct tx-level native value if canonical tx-level sender == walletAddress
             (gas is NOT included here; handled separately)
```

**Pattern rules:**

```
H-01  WRAP / UNWRAP
      nativeOut > 0 AND inTokens contains WETH contract for networkId  → WRAP
      outTokens contains WETH contract AND nativeIn > 0                → UNWRAP

H-02  SWAP — single token out, single token in, different assets
      |outTokens| == 1 AND |inTokens| == 1
      AND outTokens.keys ∩ inTokens.keys == ∅                         → SWAP

H-03  SWAP — native out, token in  (ETH → token via aggregator)
      nativeOut > 0 AND |inTokens| == 1 AND outTokens.isEmpty()        → SWAP

H-04  SWAP — token out, native in
      |outTokens| == 1 AND nativeIn > 0 AND inTokens.isEmpty()         → SWAP
      Explorer summary text may still say "Transfer"; raw legs remain authoritative.

H-05  LP_ENTRY — multiple tokens out, one receipt token in
      |outTokens| >= 2 AND |inTokens| == 1                             → LP_ENTRY

H-06  LP_EXIT — one token out, multiple tokens in
      |outTokens| == 1 AND |inTokens| >= 2                             → LP_EXIT

H-07  PROMO / SPAM inbound override
      inTokens.notEmpty() AND outTokens.isEmpty() AND nativeOut == 0
      AND token name / symbol contains promo, claim, redeem, visit, URL,
          fake-airdrop, or phishing markers
      AND no known reward distributor evidence exists
      AND no economically meaningful counterflow exists
                                                                       → type = UNKNOWN
                                                                         status = NEEDS_REVIEW
                                                                         missingDataReasons += "PROMO_SPAM_AIRDROP"

H-08  REWARD_CLAIM — only inbound, no outbound
      inTokens.notEmpty() AND outTokens.isEmpty() AND nativeOut == 0
      AND rawData.from NOT IN trackedWalletUniverse
      AND rawData.from IN knownRewardDistributorSet                    → REWARD_CLAIM

H-09  EXTERNAL_TRANSFER_IN — plain inbound default
      inTokens.notEmpty() AND outTokens.isEmpty() AND nativeOut == 0
      AND rawData.from NOT IN trackedWalletUniverse
      AND no reward-like signal exists                                 → EXTERNAL_TRANSFER_IN

H-10  EXTERNAL_TRANSFER_IN — reward-like but unproven
      inTokens.notEmpty() AND outTokens.isEmpty() AND nativeOut == 0
      AND rawData.from NOT IN trackedWalletUniverse
      AND reward-like signal exists
      AND rawData.from NOT IN knownRewardDistributorSet                → EXTERNAL_TRANSFER_IN
      missingDataReasons += "AMBIGUOUS_INBOUND_VS_REWARD"

H-11  EXTERNAL_TRANSFER_OUT — only outbound, no inbound
      outTokens.notEmpty() AND inTokens.isEmpty()
      AND rawData.to NOT IN trackedWalletUniverse
      AND rawData.to NOT IN protocolRegistry                           → EXTERNAL_TRANSFER_OUT

H-11A EXTERNAL_TRANSFER_OUT — routed aggregator send without wallet BUY
      outTokens.notEmpty() AND inTokens.isEmpty()
      AND canonical contract is a known aggregator/router
      AND no higher-priority bridge-start rule matched                 → EXTERNAL_TRANSFER_OUT
      A wallet-local `SWAP` requires both wallet-visible legs.
      Time-window proximity to a later inbound on another chain is not enough
      to promote this family into `BRIDGE_OUT`.

H-12  ZERO_AMOUNT token no-op
      outTokens.notEmpty() AND all outbound token values == 0
      AND inTokens.isEmpty() AND nativeIn == 0 AND nativeOut == 0
      AND internalTransfers.isEmpty()
      AND known setup/admin contract or selector exists                → ADMIN_CONFIG
      else                                                             → type = UNKNOWN
                                                                         status = NEEDS_REVIEW
                                                                         missingDataReasons += "ZERO_AMOUNT_TOKEN_TRANSFER"

H-13  continuity candidate metadata for installation-known counterparties
      rawData.to IN trackedWalletUniverse OR rawData.from IN trackedWalletUniverse
                                                                       → persist owner-agnostic EXTERNAL_TRANSFER_OUT / EXTERNAL_TRANSFER_IN
      continuityCandidate = true
      matchedCounterparty = tracked wallet address
      confidence = HIGH

H-14  LENDING_DEPOSIT — receipt token pattern
      IN token symbol starts with "a", "c", or "s" (aUSDC, cETH, sDAI)
      AND known receipt token address                                  → LENDING_DEPOSIT

H-15  No blanket auto-approve fallback
      inTokens.isEmpty() AND outTokens.isEmpty()
      AND nativeIn == 0 AND nativeOut == 0
      AND rawData.input != "0x"
      AND no known approve/admin selector or function matched          → continue
      Unknown zero-flow contract calls must not auto-collapse into APPROVE.

H-16  UNKNOWN — nothing matched
      → type = UNKNOWN
        confidence = LOW
        status = NEEDS_REVIEW
        missingDataReasons += "CLASSIFICATION_FAILED"
```

### Step 4A — Active-Lane Swap Invariant

Before a candidate row can remain `type = SWAP` in the active pricing lane:

```text
SWAP-INV-01  wallet-boundary swap shape
             if candidate type == SWAP
             AND non-fee BUY legs == 0
             AND a higher-priority bridge-start rule did not match
             AND outbound-only aggregator/router evidence is present
             → demote to EXTERNAL_TRANSFER_OUT

SWAP-INV-02  bridge-start source legs
             if candidate function / selector proves
               swapAndStartBridgeTokensViaMayan(...)
               swapAndStartBridgeTokensViaStargate(...)
               swapAndStartBridgeTokensViaSquid(...)
             AND wallet-visible movement is source-side outbound
             → BRIDGE_OUT
             if persisted full-receipt bridge evidence is still absent
             → missingDataReasons += BRIDGE_PAIR_EVIDENCE_REQUIRED

SWAP-INV-03  unresolved malformed swap shape
             if candidate type == SWAP
             AND (non-fee BUY legs == 0 OR non-fee SELL legs == 0)
             AND no deterministic transfer/bridge demotion applies
             → status = NEEDS_REVIEW
               missingDataReasons += "SWAP_SHAPE_INCOMPLETE"
```

---

## 5. Leg Extraction (on-chain)

Runs after classification. Builds the `flows[]` array.

### 5.1 ERC-20 legs  (source: explorer.tokenTransfers[])

```
for each transfer in rawData.explorer.tokenTransfers:
  if rawData.isError == "1": skip

  decimals = parseInt(transfer.tokenDecimal)
  quantity = BigDecimal(transfer.value) / 10^decimals

  if transfer.to.toLowerCase() == walletAddress:
    flows += Flow {
      assetContract : transfer.contractAddress.toLowerCase()
      assetSymbol   : transfer.tokenSymbol
      quantityDelta : +quantity
      role          : determined by transaction type (see §5.4)
    }

  if transfer.from.toLowerCase() == walletAddress:
    flows += Flow {
      assetContract : transfer.contractAddress.toLowerCase()
      assetSymbol   : transfer.tokenSymbol
      quantityDelta : -quantity
      role          : determined by transaction type
    }
```

### 5.2 Native asset legs  (source: internalTransfers + canonical tx-level value)

```
NATIVE-01  Internal transfers (ETH via contract calls)
           for each it in rawData.explorer.internalTransfers:
             if it.isError == "1": skip
             qty = BigDecimal(it.value) / 1e18

             if it.to.toLowerCase() == walletAddress:
               flows += Flow { assetSymbol: nativeSymbol(networkId), quantityDelta: +qty }
             if it.from.toLowerCase() == walletAddress:
               flows += Flow { assetSymbol: nativeSymbol(networkId), quantityDelta: -qty }

NATIVE-02  Direct native transfer (canonical tx-level value)
           Only when internalTransfers do not already account for the same value.
           value = BigDecimal(txLevelValue ?? "0") / 1e18
           if value > 0 AND NOT already covered by internalTransfers:
             if txLevelTo.toLowerCase() == walletAddress:
               flows += Flow { assetSymbol: nativeSymbol(networkId), quantityDelta: +value }
             if txLevelFrom.toLowerCase() == walletAddress:
               flows += Flow { assetSymbol: nativeSymbol(networkId), quantityDelta: -value }

           If the persisted row is transfer-shaped and tx-level `value` is contaminated
           by a token transfer amount, this rule must not emit a native leg.
```

### 5.3 Gas leg

```
GAS-01  Only when walletAddress is the tx originator (rawData.from == walletAddress)
        gasCostNative = BigDecimal(rawData.gasUsed) * BigDecimal(rawData.gasPrice) / 1e18

        flows += Flow {
          role          : FEE
          assetSymbol   : nativeSymbol(networkId)
          assetContract : null
          quantityDelta : -gasCostNative
          unitPriceUsd  : null  ← PricingJob fills this
        }
```

### 5.4 Role assignment per transaction type

```
SWAP
  OUT flows → SELL
  IN flows  → BUY
  gas       → FEE

LENDING_DEPOSIT
  OUT (principal token)          → TRANSFER
  IN  (receipt: aToken / cToken) → TRANSFER  (receipt is not a tradeable asset)

LENDING_WITHDRAW
  OUT (receipt token)  → TRANSFER
  IN  (principal)      → TRANSFER

BORROW
  IN (borrowed token)  → BUY  (new lot at market price)

REPAY
  OUT (repayment)      → SELL

STAKING_DEPOSIT
  liquid staking (e.g. ETH → stETH)
    OUT (ETH)          → SELL
    IN  (stETH / mETH) → BUY  (separate asset, distinct basis line — not an ETH alias)
  classic stake-contract custody (e.g. Pancake SmartChef deposit(uint256))
    OUT principal      → TRANSFER
    IN  proof marker   → TRANSFER
    IN  harvested reward → BUY

STAKING_WITHDRAW
  liquid staking unwind
    OUT (stETH / mETH) → SELL
    IN  (ETH)          → BUY
  classic stake-contract custody unwind
    OUT proof marker   → TRANSFER
    IN  principal      → TRANSFER
    IN  harvested reward → BUY

LP_ENTRY
  OUT underlying principal flows → TRANSFER
  IN  (LP token / NFT)           → TRANSFER  (LP token is not tracked for basis)

LP_EXIT
  OUT (LP token / NFT)           → TRANSFER
  IN  underlying principal flows → TRANSFER

LP_FEE_CLAIM
  IN flows              → BUY  (new lot at market price)

VAULT_DEPOSIT
  OUT (asset)           → TRANSFER
  IN  (vault shares)    → TRANSFER

VAULT_WITHDRAW
  OUT (vault shares)    → TRANSFER
  IN  (asset)           → TRANSFER

BRIDGE_OUT
  OUT (leaving network) → TRANSFER  (basis moves into bridge continuity state)

BRIDGE_IN
  IN  (arriving)        → TRANSFER
  basis = from matched BRIDGE_OUT if correlation found, else unresolved continuity

PROTOCOL_CUSTODY_DEPOSIT  (Hyperliquid, GMX perp)
  OUT → TRANSFER  (basis suspended in custody)

PROTOCOL_CUSTODY_WITHDRAW
  IN  → TRANSFER  (basis returns)

DEX_ORDER_REQUEST
  request-side sell asset → SELL until replay correlates final settlement
  same-tx gas / protocol fee → FEE
  does not fall back to EXTERNAL_TRANSFER_OUT once the protocol order request
  is proven

DEX_ORDER_SETTLEMENT
  settlement-side received asset → BUY
  same-correlation replay finalizes the async spot swap
  does not fall back to EXTERNAL_TRANSFER_IN once the protocol settlement is
  proven

DERIVATIVE_ORDER_REQUEST / DERIVATIVE_ORDER_EXECUTION /
DERIVATIVE_ORDER_CANCEL / DERIVATIVE_POSITION_INCREASE /
DERIVATIVE_POSITION_DECREASE
  all non-fee flows → TRANSFER
  do not model the market underlying as spot BUY / SELL
  request / execution / settlement accounting must follow collateral, fee, and
  realized payout semantics instead of synthetic spot lots

REWARD_CLAIM
  IN flows → BUY  (new lot at fair market value on claim date)

EXTERNAL_TRANSFER_IN
  IN flows → BUY

EXTERNAL_TRANSFER_OUT
  OUT flows → SELL

INTERNAL_TRANSFER
  legacy only
  new reruns use EXTERNAL_TRANSFER_IN / EXTERNAL_TRANSFER_OUT plus continuity metadata,
  then replay upgrades matched pairs into basis carry-over

BRIDGE_OUT / BRIDGE_IN
  source/destination bridge facts remain owner-agnostic
  same-asset correlated pairs may carry basis during replay
  asset-changing correlated routes are not plain move-basis continuity

WRAP  (ETH → WETH)
  OUT native → TRANSFER
  IN  WETH   → TRANSFER
  (AvcoEngine treats WETH as canonical ETH; basis is preserved)

UNWRAP  (WETH → ETH)
  OUT WETH   → TRANSFER
  IN  native → TRANSFER

APPROVE
  flows = []  (no asset movement)

UNKNOWN
  all flows → TRANSFER  (neutral role pending manual review)
```

### 5.5 Native token symbol per network

```
ETHEREUM, ARBITRUM, OPTIMISM, BASE, UNICHAIN, ZKSYNC, LINEA, KATANA  →  ETH
BSC                                                         →  BNB
POLYGON                                                     →  MATIC
AVALANCHE                                                   →  AVAX
MANTLE                                                      →  MNT
PLASMA                                                      →  XPL
```

---

## 6. Special Rules

### 6.1 WETH = ETH alias  (AvcoEngine only, not in storage)

```
ALIAS-01  Do NOT collapse WETH into ETH during normalization.
          Store as-is: assetSymbol="WETH", assetContract="0xC02…"

ALIAS-02  AvcoEngine applies the alias at read time:
          if assetSymbol in {"WETH"} → canonicalSymbol = "ETH"

ALIAS-03  WETH contract addresses by network
          ETHEREUM  0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2
          ARBITRUM  0x82af49447d8a07e3bd95bd0d56f35241523fbab1
          OPTIMISM  0x4200000000000000000000000000000000000006
          BASE      0x4200000000000000000000000000000000000006
          UNICHAIN  0x4200000000000000000000000000000000000006
          POLYGON   0x7ceb23fd6bc0add59e62ac25578270cff1b9f619
          ZKSYNC    0x5aea5775959fbc2557cc8789bc1bf90a239d9a91
          LINEA     0xe5d7c2a44ffddf6b295a15c148167daaaf5cf34f
          MANTLE    0xdeaddeaddeaddeaddeaddeaddeaddeaddead1111
```

### 6.2 Liquid staking tokens — separate assets, NOT ETH aliases

```
LST-01  The following are independent assets with their own basis lines:
        stETH   wstETH   rETH   cbETH   mETH   frxETH   sfrxETH
        weETH   ezETH   (and any other LST/LRT)

LST-02  On STAKING_DEPOSIT (e.g. ETH → stETH):
        SELL ETH at current ETH price
        BUY  stETH at quantity returned by the protocol
        basis(stETH) = ETH_value_spent / stETH_quantity_received
        exchange_rate is NOT 1:1 due to accumulated rewards

LST-03  stETH is rebasing (balance increases over time).
        wstETH is non-rebasing (exchange rate changes).
        Quantity accounting must respect this distinction.
```

### 6.3 Receipt tokens — not basis assets

```
RECEIPT-01  On LENDING_DEPOSIT:
            OUT principal token → TRANSFER  (basis moves into protocol continuity)
            IN  aToken / cToken → TRANSFER  (receipt, not a tradeable asset)

RECEIPT-02  On LENDING_WITHDRAW:
            OUT aToken          → TRANSFER  (receipt burned)
            IN  principal       → TRANSFER  (basis returns from protocol continuity)

RECEIPT-03  Known receipt token patterns:
            Symbol starts with "a"  (Aave: aUSDC, aETH, aWBTC)
            Symbol starts with "c"  (Compound: cUSDC, cETH)
            Symbol starts with "s"  (some savings tokens)
            Verify by contract address when possible.
```

### 6.4 BRIDGE_OUT / BRIDGE_IN correlation

```
BRIDGE-01  BRIDGE_OUT moves basis into bridge continuity state.
           OUT flow → TRANSFER
           missingDataReasons += "BRIDGE_DESTINATION_UNKNOWN"
           if no matching BRIDGE_IN is found.

BRIDGE-02  BRIDGE_IN restores basis on the destination network.
           IN flow → TRANSFER
           if matching BRIDGE_OUT found:
             carry basis from BRIDGE_OUT correlation
           else:
             missingDataReasons += "BRIDGE_SOURCE_NOT_FOUND"

BRIDGE-03  Matching criteria for correlation:
           same asset · amount within ±0.1% · time window ≤ 2 hours
           bridge protocol must be recognized in protocolRegistry

BRIDGE-04  Destination-side bridge settlement methods such as
           `fillV3Relay`, `fillRelay`, `redeemWithFee`, `execute302`,
           and `directFulfill` on recognized bridge contracts are
           `BRIDGE_IN` continuity events, not `REPAY`, `LENDING_WITHDRAW`,
           or generic `EXTERNAL_TRANSFER_IN`.

BRIDGE-05  Terminal reclassification of a LI.FI/Jumper BRIDGE_OUT whose
           settlement proves a foreign (untracked) destination.
           when a LI.FI/Jumper BRIDGE_OUT's status resolves to status=DONE
           and substatus=COMPLETED with a toAddress that does not match any
           tracked wallet in the active accounting universe, and the
           destination network is fully backfilled for the session,
           reclassify to EXTERNAL_TRANSFER_OUT/SELL with
           counterpartyAddress=toAddress (confidence EXACT, i.e.
           counterpartyResolutionState=RESOLVED_EXACT); do not apply while
           status is not yet DONE+COMPLETED (a DONE+PARTIAL settlement is a
           materially different, out-of-scope case) or while the destination
           network's backfill is incomplete.
           Implemented by LiFiForeignDestinationReclassificationService.
           Note: the missing-data reason cleared on reclassification is the
           reason actually stamped by BRIDGE-01 in code —
           `BRIDGE_ON_CHAIN_LEG_NOT_FOUND` — not the literal string
           "BRIDGE_DESTINATION_UNKNOWN" shown above.
```

### 6.5 LP tokens — not tracked for basis

```
LP-01  LP tokens (Uniswap V2 pair, Balancer BPT, Curve LP token) do not
       have an independent basis line.
       At LP_ENTRY: OUT underlying principal → role = TRANSFER (not SELL)
       At LP_ENTRY: IN LP token              → role = TRANSFER (not BUY)
       At LP_EXIT:  OUT LP token             → role = TRANSFER (not SELL)
       At LP_EXIT:  IN underlying principal  → role = TRANSFER (not BUY)
       Basis is tracked through LP custody continuity, not through LP receipt assets.

LP-02  Uniswap V3 NFT position
       IN/OUT NFT → role = TRANSFER, assetSymbol = "UNI_V3_POSITION"
       Basis tracked through the tokenTransfers at mint/burn.

LP-03  Position-manager `multicall` that adds liquidity and mints a V3 NFT
       is still `LP_ENTRY`.
       The outer selector must not force `UNKNOWN` when the inner call path
       proves liquidity add / position mint semantics.

LP-04  `safeTransferFrom` on a V3 position NFT becomes
       `LP_POSITION_STAKE` or `LP_POSITION_UNSTAKE` only when decoded calldata
       and known counterparties prove custody movement into or out of a
       farm / strategy contract.
```

### 6.6 APPROVE and ADMIN_CONFIG — immediately confirmed, no basis impact

```
APPROVE-01  type = APPROVE
            flows = []
            basisRelevant = false
            status = CONFIRMED  (no pricing step needed)

ADMIN_CONFIG-01  type = ADMIN_CONFIG
                 movement flows = []
                 optional gas FEE flow may remain
                 basisRelevant = false
                 status = CONFIRMED  (no pricing step needed)
```

### 6.7 ScamFilter and promo/phishing boundary

```
SCAM-01  ScamFilter runs before raw persistence and may drop obvious promo/phishing
         inbound only when the signal is composite and backfill-visible:
         promo/claim URL text, fake-airdrop token metadata, suspicious function label,
         spam token cluster, or equivalent deterministic evidence.

SCAM-02  Known legitimate reward-claim routes must not be dropped merely because
         the function name contains "claim" or "reward".
         Legitimate claim distributors require explicit regression fixtures.

SCAM-03  If a promo/phishing tx survives raw persistence, normalization must still
         keep it out of reward ambiguity and out of ordinary EXTERNAL_TRANSFER_IN defaulting.
```

---

## 7. Status Pipeline

```
                ┌───────────────────────────────────────┐
                │            NEEDS_REVIEW               │
                │  UNKNOWN · FAILED_TX · manual audit   │
                └───────────────────────────────────────┘
                              ▲
                              │  unsupported semantic gap after bounded clarification
                              │  OR type = UNKNOWN
                              │  OR SWAP missing paired leg after enrichment
                              │
    ┌───────────────────────────────────────────────────────────────────────────┐
    │ PENDING_                │ PENDING_    │ PENDING_    │                     │
    │ CLARIFICATION  ────────►│ PRICE  ────►│ STAT   ────►│ CONFIRMED           │
    │ (receipt-clarifiable)   │             │             │                     │
    │                         │ priceable   │ all flows   │ basis-ready         │
    │ optional RPC enrichment │ confidence  │ have price  │ triggers AvcoEngine │
    └───────────────────────────────────────────────────────────────────────────┘
```

**Transition rules:**

| From | To | Condition |
|---|---|---|
| initial | PENDING_CLARIFICATION | type known AND receipt-safe fields are missing AND clarification source exists |
| initial | PENDING_PRICE | evidence is sufficient for pricing, including non-FEE movement evidence for economic types, even if confidence remains LOW |
| initial | CONFIRMED | type = APPROVE or ADMIN_CONFIG or INTERNAL_TRANSFER or WRAP/UNWRAP |
| initial | NEEDS_REVIEW | type unknown OR ordering metadata cannot be repaired OR handler/method support is missing |
| PENDING_CLARIFICATION | PENDING_RECLASSIFICATION | clarification evidence was persisted or a bounded evidence attempt was exhausted with raw evidence present |
| PENDING_RECLASSIFICATION | PENDING_PRICE | classifier now has enough persisted evidence for pricing |
| PENDING_RECLASSIFICATION | CONFIRMED | classifier now resolves a non-priceable terminal row |
| PENDING_RECLASSIFICATION | NEEDS_REVIEW | classifier still cannot resolve the row from current raw plus persisted clarification evidence |
| PENDING_RECLASSIFICATION | PENDING_CLARIFICATION | classifier emits a new bounded clarification requirement with remaining attempt budget |
| PENDING_PRICE | PENDING_STAT | all non-FEE flows have `unitPriceUsd` OR `priceSource = UNKNOWN` |
| PENDING_PRICE | NEEDS_REVIEW | pricing input is structurally invalid after clarification or still lacks persisted non-FEE movement evidence for an economic type |
| PENDING_STAT | CONFIRMED | validation passed |
| PENDING_STAT | NEEDS_REVIEW | validation failed (e.g. SWAP has no BUY leg) |

Clarification is intentionally narrow:

- it is not the default destination for every low-confidence row
- it does not solve reward-vs-inbound ambiguity
- it does not repair missing `transactionIndex`
- it does not turn promo/phishing inbound into valid accounting rows
- it does not decide zero-value token no-op semantics
- it does not replace protocol-specific classifier coverage for wrappers, bridges,
  LP position managers, routers, or multicalls
- every clarification-eligible row must carry explicit receipt-safe missing reasons

Clarification may optionally use full receipt enrichment for an allowlisted
review set, but this is not a widening of the base clarification queue:

- it may target only an allowlisted review-family set whose closure is supported
  by the latest audit and by official protocol semantics
- it may fetch full receipt logs, but not traces or explorer UI summaries
- it must fetch clarification evidence from the same source family that produced
  the raw row; cross-source fallback is exceptional and must be documented
- it should persist both the adapted clarification evidence and the raw full
  receipt payload when the source exposes it
- for audited async protocol lifecycles, it may also fetch and persist related
  real txs from the same source family when current protocol evidence already
  proves that a missing keeper / settlement tx belongs to the same lifecycle
- clarification is not complete for a row unless that fetched evidence is
  persisted back onto the raw row in a deterministic shape
- it must keep that new evidence in a dedicated clarification-evidence area,
  never as synthetic `rawData.logs[]`
- it must not re-run classification inline; completed clarification moves the
  normalized row to `PENDING_RECLASSIFICATION`, and the reclassification stage
  runs the normal classifier from canonical raw evidence plus persisted real
  receipt logs
- rows already closable from current raw must stay classifier work, not
  clarification work
- resolved economic rows that still have only fee flow must not advance to
  pricing until raw or persisted clarification evidence closes their movement
  semantics

### 7.1 Pricing resolution contract

Pricing runs after canonical flow construction and before final stat validation.

```
PRICE-01  StablecoinResolver
          Supported stablecoins resolve to 1.00 USD.
          priceSource = STABLECOIN

PRICE-02  SwapDerivedResolver
          Use the priced counterpart leg from the same canonical SWAP.
          priceSource = SWAP_DERIVED

PRICE-03  WrapperResolver
          Wrapped/native assets inherit the underlying native asset price
          for the same timestamp and network pricing context.
          Examples: WETH -> ETH, WBNB -> BNB, WMATIC -> MATIC.
          priceSource = WRAPPER

PRICE-04  CoinGeckoHistoricalResolver
          Last-resort historical lookup by canonical asset identity and date.
          priceSource = COINGECKO

PRICE-05  PRICE_UNKNOWN
          If no resolver can produce a deterministic price:
            - quantityDelta remains unchanged
            - unitPriceUsd = null
            - priceSource = UNKNOWN
            - missingDataReasons += "PRICE_UNRESOLVABLE"
            - replay must set hasIncompleteHistory = true
            - status continues toward CONFIRMED; quantity is not blocked
```

---

## 8. Bybit Normalization (external_ledger_raw → normalized_transactions)

Runs independently of on-chain normalization. Does not depend on raw_transactions state.

### 8.1 Records to skip

```
BYBIT-SKIP-01  basisRelevant = false
               These records stay in external_ledger_raw with status = RAW.
               Covers: INTERNAL_TRANSFER · FUNDING_FEE · APPROVE · UNKNOWN_CEX

BYBIT-SKIP-02  outOfScope = true  (TON · SOL without tracking · HYPEREVM)
               Asset lifecycle ends at Bybit. No normalized_transaction created.

BYBIT-SKIP-03  canonicalType = UNKNOWN_CEX
               Write to review_queue. Do not normalize.
```

### 8.2 UTA TRADE leg pairing

Each `uta_derivatives` TRADE row contains a single leg (BUY or SELL).
A SWAP requires a matched pair.

```
BYBIT-PAIR-01  Pair rows using a sliding ±5 second window.
               Match on:
                 same uid
                 same utaContract
                 abs(timeUtc_buy - timeUtc_sell) <= 5 seconds

               Sort rows by timeUtc ASC and greedily pair unmatched BUY/SELL legs.

               If pair found:
                 type = SWAP
                 flows = [
                   { role: SELL, assetSymbol: base(utaContract),  quantityDelta: -|quantity| }
                   { role: BUY,  assetSymbol: quote(utaContract), quantityDelta: +|cashFlow| }
                 ]

               If pair NOT found (orphan leg):
                 preserve the observed leg as transfer-shaped evidence
                 missingDataReasons += "UTA_TRADE_PAIR_NOT_FOUND"
                 status = NEEDS_REVIEW
                 excludedFromAccounting = true
                 accountingExclusionReason = "UTA_TRADE_PAIR_NOT_FOUND"
                 do not leave the row in the priceable lane unless another
                 official Bybit source reconstructs the missing leg

BYBIT-PAIR-02  Contract symbol parsing
               parseBase("ETHUSDT")  → "ETH"
               parseBase("MNTUSDE")  → "MNT"
               parseQuote("ETHUSDT") → "USDT"
               parseQuote("MNTUSDE") → "USDE"
               Rule: find known stablecoin suffix {USDT, USDC, USDE, BUSD}
                     base  = contract without suffix
                     quote = suffix
```

### 8.3 canonicalType → flows mapping

```
REWARD_CLAIM   (Earn Interest · Launchpool Yield · Airdrop)
  flows = reward BUY legs only
  exact same-asset same-quantity in/out marker pairs are removed from active economics
  unitPriceUsd → PricingJob (CoinGecko by date)

VAULT_DEPOSIT  (Earn Subscription · Launchpool Subscription)
  flows = [{ role: TRANSFER, assetSymbol, quantityDelta: -|quantityRaw| }]
  Semantics: basis transferred into vault; no P&L realization

VAULT_WITHDRAW  (Earn Redemption · Launchpool Withdrawal)
  flows = [{ role: TRANSFER, assetSymbol, quantityDelta: +|quantityRaw| }]
  Semantics: basis returned from vault

EXTERNAL_TRANSFER_IN  (Deposit)
  flows = [{ role: BUY, assetSymbol, quantityDelta: +|quantityRaw| }]
  legacy raw canonicalType `EXTERNAL_INBOUND` must map here

EXTERNAL_TRANSFER_OUT  (Withdraw)
  flows = [{ role: SELL, assetSymbol, quantityDelta: -|quantityRaw| }]
  txHash → bridge correlation if networkId != null  (see §8.4)

STAKING_DEPOSIT  (ETH 2.0 Stake + Mint combined)
  Two Bybit rows merged into one normalized_transaction:
  flows = [
    { role: SELL, assetSymbol: "ETH",  quantityDelta: -ETH_qty  }
    { role: BUY,  assetSymbol: "METH", quantityDelta: +METH_qty }
  ]

BORROW  (Pledge Assets)
  reserve asset principal = BUY
  debt-marker mint legs and execution settlement / refund legs = TRANSFER

REPAY  (Repay Principal)
  reserve asset principal = SELL
  debt-marker burn legs and execution settlement / refund legs = TRANSFER

FEE  (Repay Interest · bonus recollect)
  flows = [{ role: FEE, assetSymbol, quantityDelta: -|quantityRaw| }]
  basisRelevant = false
```

### 8.4 Bridge correlation (withdraw_deposit TxID)

```
BYBIT-BRIDGE-01  For each Withdraw row in withdraw_deposit where txHash != null:
                 Look up raw_transactions by { txHash, networkId: mappedNetworkId }

                 Found:
                   external_ledger_raw.onChainCorrelation.status = MATCHED
                   external_ledger_raw.onChainCorrelation.matchedDocId = raw_tx._id
                   external_ledger_raw.onChainCorrelation.correlationId = generated correlation id
                   Effect:
                     - keep the Bybit row as source evidence
                     - create a BYBIT canonical document with walletAddress = BYBIT:<uid>
                     - create/use the corresponding ON_CHAIN canonical document with the same correlationId
                     - persist owner-agnostic transfer type on both sides
                     - set continuityCandidate = true

BYBIT-BRIDGE-02  For withdraw_deposit inbound rows with canonical inbound
                 semantics:
                   - synthetic normalized rows must materialize as
                     EXTERNAL_TRANSFER_IN
                   - they may not fall through to CONFIRMED UNKNOWN
                   - if mapping fails, emit NEEDS_REVIEW with an explicit
                     unmapped-canonical reason
                     - replay treats the pair as one basis-carry movement when both sides are inside the active accounting universe

                 Not found:
                   onChainCorrelation.status = UNMATCHED
                   missingDataReasons += "BRIDGE_ON_CHAIN_LEG_NOT_FOUND"
                   Likely cause: destination wallet not added to trackedWalletUniverse.

BYBIT-BRIDGE-02  For each Deposit row:
                 Bybit Deposit means the user sent tokens TO the Bybit deposit address
                 on-chain. This is a correlated custody transfer when matched.
                 Look for raw_transactions where walletAddress is the sender
                 and txHash matches. If found:
                   - assign the same correlationId
                   - keep both source sides traceable
                   - persist owner-agnostic transfer type plus continuity metadata
```

### 8.5 Bybit status initialization

Bybit canonical rows do not use the on-chain classification-confidence path.

```
BYBIT-STATUS-01  If the canonical row contains BUY or SELL flows:
                 initial status = PENDING_PRICE
                 then continue through PricingJob -> PENDING_STAT -> CONFIRMED

BYBIT-STATUS-02  If the canonical row contains TRANSFER-only flows:
                 initial status = CONFIRMED after canonical validation
                 because no price lookup is required for basis continuity

BYBIT-STATUS-03  Explicitly unsupported but deterministic review families may
                 remain persisted as:
                   status = NEEDS_REVIEW
                   excludedFromAccounting = true
                 They stay visible for audit, but are outside active pricing
                 and replay scope.

BYBIT-STATUS-03  basisRelevant = false rows do not create normalized_transactions.
                 They remain in external_ledger_raw only.
```

---

## 9. Invariants

```
INV-01  Failed transactions (isError=1) never enter basis calculation.
        Create normalized_transaction with type=UNKNOWN, status=NEEDS_REVIEW.

INV-02  All flows[].quantityDelta must be BigDecimal.
        Never double or float. tokenDecimal from explorer is mandatory.

INV-03  If tokenDecimal is missing:
        quantityDelta = null
        missingDataReasons += "DECIMAL_UNKNOWN"
        status → PENDING_CLARIFICATION

INV-04  synthetic logs[] are not a data source.
        Explorer-derived synthetic logs must not influence classification.
        Persisted real receipt logs may be used only when they are explicitly
        marked as backfill or clarification evidence and consumed through the
        normal raw view.

INV-04a protocol-registry `event_topics` are reference metadata only.
        They are not loaded into the runtime classifier.

INV-04b tx-level fields and transfer-row payloads are separate evidence layers.
        Token transfer rows may populate `explorer.tokenTransfers[]`, but they
        must never overwrite tx-level `from`, `to`, `value`, `input`,
        `methodId`, or `functionName`.

INV-05  Continuity events use role = TRANSFER.
        AvcoEngine moves quantity and carried basis for TRANSFER,
        but does not realize PnL and does not create a new acquisition lot.

INV-05a INTERNAL_TRANSFER detection may use only installation-wide
        trackedWalletUniverse, never a session-local wallet list.

INV-06  Classification steps execute in strict order:
        Registry → MethodId → FunctionName → Heuristic.
        A Registry match is never overridden by a lower-priority step.

INV-06a Registry special-handler entries are resolved by deterministic protocol
        semantics over the already extracted raw legs, then mapped by the
        owning family classifier. Unsupported methods become
        `UNKNOWN -> NEEDS_REVIEW`.

INV-07  APPROVE → flows = [], status = CONFIRMED immediately.
        No PricingJob call needed.
        ADMIN_CONFIG → movement flows = [], optional FEE flow only,
        status = CONFIRMED immediately.

INV-08  Unique key: (txHash, networkId, walletAddress).
        Re-processing the same raw_transaction is idempotent.
        Upsert is allowed when status != CONFIRMED.
        Once CONFIRMED, record is immutable except via explicit RecalcJob.

INV-09  transactionIndex is mandatory in normalized_transactions.
        On-chain: parse from rawData.transactionIndex (string → int).
        CEX (Bybit): transactionIndex = 0.
        AVCO sort order for CONFIRMED docs:
        blockTimestamp ASC, transactionIndex ASC, _id ASC.

INV-10  WETH / WAVAX / WBNB / WMATIC aliases are applied by AvcoEngine
        at read time, never at normalization time.
        Storage always uses the original assetSymbol from the transfer.

INV-11  LST/LRT tokens (stETH, wstETH, rETH, mETH, cbETH, …) are
        independent assets. They are never aliased to their underlying.
```

---

## 10. On-chain Confidence → Status Mapping (summary)

| Classification result | Initial status |
|---|---|
| type = APPROVE | CONFIRMED  (immediately, no pricing needed) |
| type = ADMIN_CONFIG | CONFIRMED  (immediately, no pricing needed) |
| type = INTERNAL_TRANSFER | CONFIRMED  (no pricing needed) |
| type = WRAP or UNWRAP | CONFIRMED  (basis unchanged) |
| type known, confidence = HIGH | PENDING_PRICE |
| type known, confidence = MEDIUM | PENDING_PRICE |
| type known, receipt-clarifiable | PENDING_CLARIFICATION |
| type known, economically coherent, not receipt-clarifiable | PENDING_PRICE |
| type = UNKNOWN | NEEDS_REVIEW |
| isError = 1 | NEEDS_REVIEW |

Replay ordering note: `CONFIRMED` documents replay by `blockTimestamp ASC`,
then `transactionIndex ASC`, then `_id ASC`.
