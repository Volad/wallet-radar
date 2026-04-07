# CoW Swap Protocol Rules

Status: Active protocol-owned semantic slice

## Scope

Cover async spot-order lifecycle for CoW Swap, including ETH Flow request and
settlement semantics.

## Runtime Ownership

- Protocol semantic detection:
  [CowSwapProtocolSemanticClassifier.java](/Users/vladislavkondratenko/projects/wallet-radar/backend/src/main/java/com/walletradar/ingestion/pipeline/classification/onchain/protocol/cow/CowSwapProtocolSemanticClassifier.java)
- Family mapping:
  [TradingClassifier.java](/Users/vladislavkondratenko/projects/wallet-radar/backend/src/main/java/com/walletradar/ingestion/pipeline/classification/onchain/family/TradingClassifier.java)

## Protocol-Local Resources

- `backend/src/main/resources/protocols/cow.json`

Current active runtime profile owns:

- `EthFlow` request selector markers
- `GPv2` settlement selector markers
- trade-event topic group used to prove settlement semantics

## Authoritative Evidence

- `OnChainRawTransactionView`
- saved top-level calldata
- persisted receipt logs
- persisted settlement trade evidence available to clarification

## Lifecycle Shapes

- `DEX_ORDER_REQUEST`
- `DEX_ORDER_SETTLEMENT`

Current active semantic hints:

- `dex_order_request`
- `dex_order_settlement`

## Clarification Rules

- clarification may fetch full receipt when current raw evidence proves request
  but not settlement semantics
- settlement must be based on production-available receipt/trade evidence, not
  manual explorer reconstruction

## Correlation Rules

- exact request/settlement pair must share deterministic `correlationId`
- `matchedCounterparty` must be bidirectional for exact pair

## Family Handoff

- protocol semantics hand off to `TradingClassifier`
- protocol semantic classifier now reads `cow.json` before delegating to the
  audited request/settlement decoder

## Disallowed Fallbacks

- do not let `EXTERNAL_TRANSFER_IN` capture order settlement
- do not rely on selector-only matching when calldata shape proves a different
  protocol

## Baseline and Regression Anchors

- request/settlement parity
- correct sell/buy asset reconstruction
- exact pair linking stability
