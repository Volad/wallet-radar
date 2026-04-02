# GMX V2 Protocol Rules

Status: Active protocol-owned semantic slice

## Scope

Cover `GMX V2` pool lifecycle and derivative lifecycle, including:

- `GM` pool request / settlement
- `GLV` pool request / settlement
- derivative order request / execution / cancel
- derivative position increase / decrease

## Runtime Ownership

- Protocol semantic detection:
  [GmxProtocolSemanticClassifier.java](/Users/vladislavkondratenko/projects/wallet-radar/backend/src/main/java/com/walletradar/ingestion/pipeline/classification/onchain/protocol/gmx/GmxProtocolSemanticClassifier.java)
- Family mapping:
  [TradingClassifier.java](/Users/vladislavkondratenko/projects/wallet-radar/backend/src/main/java/com/walletradar/ingestion/pipeline/classification/onchain/family/TradingClassifier.java)
  [GmxLpClassifier.java](/Users/vladislavkondratenko/projects/wallet-radar/backend/src/main/java/com/walletradar/ingestion/pipeline/classification/onchain/family/GmxLpClassifier.java)

## Protocol-Local Resources

- `backend/src/main/resources/protocols/gmx-v2.json`

Current active runtime profile owns:

- top-level selectors for request / keeper / settlement paths
- subcall selectors for helper funding and async request creation
- event-emitter topic group and lifecycle event groups
- share-symbol prefixes for `GM` / `GLV` assets

## Authoritative Evidence

- `OnChainRawTransactionView`
- registry discovery for GMX contracts and roles
- subcall decode derived from top-level calldata
- persisted `fullReceipt` logs
- persisted related lifecycle rows discovered by clarification

## Lifecycle Shapes

### Pool lifecycle

- request side:
  - `LP_ENTRY_REQUEST`
  - `LP_EXIT_REQUEST`
- settlement side:
  - `LP_ENTRY_SETTLEMENT`
  - `LP_EXIT_SETTLEMENT`

### Derivative lifecycle

- request side:
  - `DERIVATIVE_ORDER_REQUEST`
- terminal side:
  - `DERIVATIVE_ORDER_EXECUTION`
  - `DERIVATIVE_ORDER_CANCEL`
- `DERIVATIVE_POSITION_INCREASE`
- `DERIVATIVE_POSITION_DECREASE`

Current active semantic hints:

- `derivative_order_request`
- `derivative_order_execution`
- `derivative_order_cancel`
- `derivative_position_increase`
- `derivative_position_decrease`
- `lp_entry_request`
- `lp_entry_settlement`
- `lp_exit_request`
- `lp_exit_settlement`

## Clarification Rules

- clarification may fetch full receipts and related lifecycle rows for:
  - keeper executions
  - async pool settlements
  - missing terminal derivative lifecycle rows
- clarification remains bounded and protocol-aware

## Correlation Rules

- pool and derivative families require deterministic `correlationId`
- highest-scope lifecycle key wins over intermediate keys
- exact async request/settlement pairs must get bidirectional
  `matchedCounterparty`
- accepted sibling stop-order asymmetry must remain documented, not hidden

## Family Handoff

- derivative semantics hand off to `TradingClassifier`
- pool lifecycle semantics hand off to `LpClassifier`
- generic transfer fallback must never capture proven GMX lifecycles
- protocol semantic classifier now reads `gmx-v2.json` for selector/topic/asset
  markers before fallbacking to compatibility constants

## Disallowed Fallbacks

- no hash hardcodes
- no generic `EXTERNAL_TRANSFER_*` for request/settlement/keeper rows once GMX
  evidence is available
- no LP typing for derivative order families

## Baseline and Regression Anchors

- terminal type parity
- exact async pair linking
- wallet-visible cashflow reconstruction
- pool share-balance continuity
