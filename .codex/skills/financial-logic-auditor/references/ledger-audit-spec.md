# Ledger Audit Spec

## Table of Contents

1. Operating Goal
2. Ground Rules
3. Data Sources
4. Reference Files
5. Classification Rules
6. AVCO Computation
7. Supported Networks
8. Known Open Blockers
9. Analysis Workflow
10. Output
11. Priorities

## Operating Goal

Reconstruct the user's complete ledger history from raw data, classify every transaction, and compute authoritative cost basis and AVCO for all tracked assets.

Work directly with MongoDB and the filesystem. Write scripts when needed. Do not implement application code.

## Ground Rules

1. Always start from raw sources:
   - `raw_transactions` for on-chain data
   - `bybit_extracted_events` for Bybit CEX data
2. `normalized_transactions` with `status=CONFIRMED` may be used as input only if the current pipeline is trustworthy. When in doubt, recompute from raw.
3. `asset_positions` is for reconciliation only. Never use it as a reconstruction starting point.
4. Current on-chain balances may only be used for final reconciliation, never as a starting point.
5. Process events genesis-forward in chronological order:
   - `blockTimestamp ASC`
   - `transactionIndex ASC`
6. When a blocker appears, record it with an ID and status (`OPEN`, `RESOLVED`, `DEFERRED`) and continue. Do not stop the run.
7. When validating Mongo findings against outside evidence, prefer sources in this order:
   - Etherscan-compatible explorers
   - Blockscout-compatible explorers
   - Routescan-compatible explorers
   - direct RPC only when explorer evidence is insufficient
8. Classification audits may rely only on evidence that exists at backfill time and is available to the production normalization path.
9. Clarification audits may rely only on evidence that the production clarification stage can actually pull and use.

## Data Sources

### MongoDB database: `walletradar`

#### `raw_transactions`

Ground truth for on-chain movements. One document per `(txHash, networkId, walletAddress)`.

Use these fields:

- `txHash`, `networkId`, `walletAddress`, `blockNumber`
- `normalizationStatus`
- `rawData.to`
- `rawData.from`
- `rawData.value`
- `rawData.methodId`
- `rawData.functionName`
- `rawData.isError`
- `rawData.transactionIndex`
- `rawData.timeStamp`
- `rawData.explorer.tokenTransfers[]`
- `rawData.explorer.internalTransfers[]`

Do not use `rawData.logs[]` as a source. These logs are synthetic and contain only generated `Transfer` entries.

#### `normalized_transactions`

Classification output. Use it only when needed and only trust `status=CONFIRMED` as AVCO input.

Relevant fields:

- `txHash`, `networkId`, `walletAddress`
- `source`
- `blockTimestamp`, `transactionIndex`
- `type`
- `confidence`
- `classifiedBy`
- `status`
- `flows[]`
- `missingDataReasons[]`

AVCO input rule:

- Use `status=CONFIRMED` only.
- Inspect `status=NEEDS_REVIEW` for classification problems before basis computation.

#### `bybit_extracted_events`

Bybit CEX source of truth. One document per extracted event row.

Relevant fields:

- `_id`
- `status`
- `eventType`
- `assetSymbol`, `quantityRaw`, `timeUtc`
- `canonicalType`
- `basisRelevant`
- `outOfScope`
- `txHash`, `networkId`
- `onChainCorrelation.status`
- venue-side identifiers or grouping fields used by the current extractor when present

Process only rows where:

- `basisRelevant = true`
- `outOfScope = false`

## Reference Files

- `protocol-registry.json`
  - Contract address to protocol metadata
  - Use for identifying unknown contracts, verifying classifications, and adding missing entries
- `normalization-architecture-en.md`
  - Complete classification rules and leg extraction specification
  - Read when you need to verify how a transaction should be classified

## Classification Rules

Apply steps in strict order and stop at the first match.

Evidence boundary:

- If an explorer page shows extra decoded metadata that is not present in `raw_transactions`
  and would not be available during backfill, do not use it to redefine canonical
  classification.
- External explorer validation is for confirmation, gap detection, and recommendations,
  not for silently widening the production evidence contract.

### 1. Protocol Registry

Look up `rawData.to` in `protocol-registry.json` by address and `networkId`.

- Confidence: `HIGH`

### 2. Method ID

Look up `rawData.methodId` in the registry method ID table.

- Confidence: `MEDIUM`

### 3. Function Name Keywords

Use `rawData.functionName` from explorer decoding:

- `claim` -> `REWARD_CLAIM`
- `swap` -> `SWAP`
- `deposit` -> `LENDING_DEPOSIT` or `VAULT_DEPOSIT`
- `withdraw` -> `LENDING_WITHDRAW` or `VAULT_WITHDRAW`
- `borrow` -> `BORROW`
- `repay` -> `REPAY`
- `stake` -> `STAKING_DEPOSIT`
- `bridge` -> `BRIDGE_OUT`
- `addLiquidity` -> `LP_ENTRY`

- Confidence: `LOW`

### 4. Token Transfer Patterns

Define:

- `inTokens` = token transfers where `to == walletAddress`
- `outTokens` = token transfers where `from == walletAddress`

Pattern rules:

- `1 out + 1 in` with different assets -> `SWAP`
- only inbound tokens, no outbound -> `REWARD_CLAIM` or `EXTERNAL_INBOUND`
- only outbound tokens, no inbound -> `EXTERNAL_TRANSFER_OUT`
- multiple outbound + 1 inbound -> `LP_ENTRY`
- 1 outbound + multiple inbound -> `LP_EXIT`
- to/from tracked wallets -> `INTERNAL_TRANSFER`

- Confidence: `LOW`

### Flow Role Assignment

- `BUY` -> new cost basis lot
- `SELL` -> close lot and compute realized PnL
- `FEE` -> gas cost; added to basis on buy events
- `TRANSFER` -> no basis event

Use `TRANSFER` for:

- all flows in `INTERNAL_TRANSFER`
- LP tokens
- receipt tokens such as `aToken`, `cToken`
- `WETH` on wrap or unwrap
- NFT LP positions

### Hard Rules

- `isError = "1"` -> `NEEDS_REVIEW`; never enters basis
- synthetic logs -> ignore completely
- `WETH` -> keep as `WETH`; alias to `ETH` only in AVCO replay
- `stETH`, `mETH`, `rETH`, `wstETH`, `cbETH` -> independent assets; never alias to `ETH`

## AVCO Computation

Sort all `CONFIRMED` events by:

1. `blockTimestamp ASC`
2. `transactionIndex ASC`

For CEX events, use `transactionIndex = 0`.

### On BUY

- `new_avco = (avco * qty + price * deltaQty) / (qty + deltaQty)`
- `qty += deltaQty`

### On SELL

- `avco_at_sale = current avco` before deduction
- `realised_pnl = (price - avco_at_sale) * abs(deltaQty)`
- `qty -= abs(deltaQty)`
- `avco` remains unchanged

### On PRICE_UNKNOWN

- update quantity
- keep price fields null
- set `hasIncompleteHistory = true`

### Basis Continuity Rules

- `INTERNAL_TRANSFER` -> basis does not change
- `BRIDGE_OUT -> BRIDGE_IN`
  - same asset
  - quantity tolerance `+-0.1%`
  - within `<= 2h`
  - known protocol
  - basis carries across networks
- `Bybit -> on-chain`
  - same accounting universe
  - correlate via `txHash`
- `PROTOCOL_CUSTODY`
  - for protocols such as Hyperliquid or GMX perps
  - basis moves into custody on deposit and returns on withdraw

## Supported Networks

### On-chain

- `ETHEREUM`
- `ARBITRUM`
- `BASE`
- `OPTIMISM`
- `POLYGON`
- `BSC`
- `AVALANCHE`
- `MANTLE`
- `ZKSYNC`
- `LINEA`
- `KATANA`
- `PLASMA`

### CEX

- `BYBIT` with `uid: 33625378`

### Out of Scope

Asset lifecycle ends at Bybit:

- `TON`
- `SOLANA`
- `HYPEREVM`

## Known Open Blockers

Start each session aware of these and update status as you work.

### B-01 Bridge unmatched

- Status: `OPEN`
- Scope: 35 events
- Issue: Bybit withdrawal/deposit TxIDs not found in `raw_transactions`
- Candidate wallet missing from tracking:
  - `0x1A87f12aC07E9746e9B053B8D7EF1d45270D693f`

### B-02 UTA trade orphan legs

- Status: `OPEN`
- Scope: 72 events
- Issue: single `BUY` or `SELL` legs without a matched pair
- Action: rerun pairing with `+-5 sec` window

### B-03 Fund blank rows

- Status: `OPEN`
- Scope: 89 events, 83 unmatched
- Issue: empty `Type` and `Description` in `fund_asset_changes`
- Example: `ETH +0.9189` on `2025-09-12`
- Action: try matching `UTA TRANSFER_IN` by date and amount within `+-24h`

### B-04 UTA contract backlog

- Status: `OPEN`
- Scope: 32 contracts
- Examples:
  - `TONUSDT`
  - `SOLUSDT`
  - `ONDOUSDT`
  - `LDOUSDT`
  - `XRPUSDT`
  - `LINKUSDT`
  - `FLOCKUSDT`
  - `USDCUSDT`
  - `MNTUSDE`
  - `ALCHUSDT`
  - `DOGEUSDT`
  - `USDEUSDT`

### B-05 Missing registry entry

- Status: `OPEN`
- Address: `0x3ef3d8ba38ebe18db133cec108f4d14ce00dd9ae`
- Network: `ETHEREUM`
- Meaning: Morpho Universal Rewards Distributor
- Current problem: misclassified as `EXTERNAL_INBOUND`
- Expected classification:
  - `family=YIELD`
  - `event_type=REWARD_CLAIM`

## Analysis Workflow

When starting an audit session:

1. Count source documents:
   - `db.raw_transactions.countDocuments()`
   - `db.raw_transactions.countDocuments({ normalizationStatus: "PENDING" })`
   - `db.normalized_transactions.countDocuments({ status: "CONFIRMED" })`
   - `db.normalized_transactions.countDocuments({ status: "NEEDS_REVIEW" })`
   - `db.bybit_extracted_events.countDocuments({ basisRelevant: true, outOfScope: false })`
2. Identify coverage gaps:
   - wallets and networks with data
   - covered time range
   - pending raw transactions
3. Resolve `NEEDS_REVIEW` before computing basis:
   - inspect each case
   - classify using the rules above
   - add missing contracts to `protocol-registry.json`
   - document unresolvable cases as blockers
4. Reconstruct full movement coverage:
   - on-chain flows and Bybit flows interleaved by timestamp
   - correlate bridge and CEX events to avoid double-counting
5. Compute AVCO only after movement coverage is complete
6. Reconcile:
   - derived quantity from replay
   - on-chain quantity from `asset_positions`
   - absolute tolerance `epsilon = 1e-8`

## Output

Write these files:

- `results/blockers.md`
  - columns: `ID | status | description | evidence`
- `results/warnings.md`
  - price gaps, unknown events, incomplete history flags
- `results/reconciliation.md`
  - per asset: derived qty, on-chain qty, delta, status
- `results/eth_basis.md`
  - final ETH cost basis and AVCO summary per wallet and cross-wallet

Also keep intermediate datasets under:

- `data/derived/`
  - legs
  - events
  - basis lots
  - state snapshots

## Priorities

1. Financial correctness
2. Deterministic output
3. No double-counting
4. Correct DeFi semantics
5. Complete traceable audit trail
