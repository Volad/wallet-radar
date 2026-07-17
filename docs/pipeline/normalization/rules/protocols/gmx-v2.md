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
  [GmxProtocolSemanticClassifier.java](../../../../../backend/core/src/main/java/com/walletradar/application/normalization/pipeline/classification/onchain/protocol/gmx/GmxProtocolSemanticClassifier.java)
- Family mapping:
  [TradingClassifier.java](../../../../../backend/core/src/main/java/com/walletradar/application/normalization/pipeline/classification/onchain/family/TradingClassifier.java)
  [GmxLpClassifier.java](../../../../../backend/core/src/main/java/com/walletradar/application/normalization/pipeline/classification/onchain/family/GmxLpClassifier.java)
- Linking-stage settlement / refund passes:
  [GmxV2RefundClassifier.java](../../../../../backend/core/src/main/java/com/walletradar/application/linking/pipeline/clarification/GmxV2RefundClassifier.java),
  [GmxWithdrawalSettlementLinkService.java](../../../../../backend/core/src/main/java/com/walletradar/application/linking/pipeline/clarification/GmxWithdrawalSettlementLinkService.java),
  [GmxExecutionFeeRefundBasisNeutralService.java](../../../../../backend/core/src/main/java/com/walletradar/application/linking/pipeline/clarification/GmxExecutionFeeRefundBasisNeutralService.java)

## Protocol-Local Resources

- `backend/core/src/main/resources/protocols/gmx-v2.json`

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

## Keeper withdrawal settlement (linking stage)

GMX GLV/GM withdrawals settle as an **internal-transfer-only** native ETH payout from a GMX
handler/keeper — there is no wallet-signed settlement transaction, so the payout arrives as a bare
native inflow. The linking stage resolves these in a strict ordered chain (see
[linking rules & repairs](../../../linking/02-rules-and-repairs.md)):

1. **`GmxV2RefundClassifier`** — stamps the keeper native inflow with the `GMX_EXECUTION_FEE_REFUND`
   attribution.
2. **`GmxWithdrawalSettlementLinkService` (NEW-09)** — runs **immediately after** the refund
   classifier. When a stamped inflow matches an open `gmx-lp:*` `LP_EXIT_REQUEST`, it reclassifies the
   inflow to `LP_EXIT_SETTLEMENT`, sets the shared `correlationId`, and reshapes `BUY`→`TRANSFER` so
   replay reuses the async REALLOCATE carry (basis carried from the exit request, not a fabricated
   market ACQUIRE).
3. **`GmxExecutionFeeRefundBasisNeutralService` (NEW-13)** — runs **strictly after** the settlement
   link. Any residual refund with **no** matching open `LP_EXIT_REQUEST` is genuine return-of-capital
   execution-fee gas dust and is demoted to a basis-neutral `SPONSORED_GAS_IN` (`GAS_ONLY`). Ordering
   is load-bearing: genuine GLV/GM settlements are already `LP_EXIT_SETTLEMENT` by step 2 and are
   therefore excluded here.

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

## Current Dashboard Valuation

- GM and GLV market-token dashboard valuation is a portfolio snapshot concern,
  not an AVCO replay concern.
- Snapshot refresh may build a cached protocol quote from public GMX market
  information, oracle token prices, pool token amounts, and market-token total
  supply.
- The persisted dashboard quote uses `current_price_quotes.source =
  PROTOCOL_SNAPSHOT` and is read by dashboard GET without live protocol calls.
- If the snapshot cannot be built, dashboard rows must remain visible with
  `unsupported_protocol_valuation`; silent zero value is not accepted for a
  material GMX position.

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
