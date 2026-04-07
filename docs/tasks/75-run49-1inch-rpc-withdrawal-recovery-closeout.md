# Task 75: 1inch RPC Withdrawal Recovery Closeout

Status: Done

## Context

`run/49` narrowed the remaining accounting blocker to two active BSC `1inch`
rows:

- `0x4d1b48bc6c3937f3379c59ee6eb048f7078eef9258c62a178b3cdb2d632bf42c`
- `0x6837626582f572bf7dcdc92404ccebcd1c6c2c3f4a1175fbdc6452f54208e341`

Both rows were still normalized as `EXTERNAL_TRANSFER_OUT` with
`ROUTED_AGGREGATOR_OUTBOUND_ONLY`, even though:

- calldata proves `1inch` classic swap selector `0x07ed2379`
- calldata proves `dstToken = native sentinel`
- calldata proves `dstReceiver = tracked wallet`
- clarified receipt logs prove wrapped-native `Withdrawal`

The remaining gap was specific to `RPC` clarification shape on BSC:

- clarified `tokenTransfers` carried the inbound `WBNB -> intermediary` transfer
- clarified logs carried the wrapped-native `Withdrawal`
- no burn-to-zero token transfer row existed

The previous recovery path required a burn transfer and therefore missed the
wallet native buy leg.

## Goal

Recover `1inch` native-output swaps from `RPC`-backed clarified receipt logs
without widening generic routed-aggregator heuristics.

## Scope

- accept wrapped-native `Withdrawal` receipt logs as deterministic settlement
  evidence for `1inch` classic swap native output
- keep existing burn-transfer recovery path intact
- add classifier-level and clarification-workflow regression tests

## Acceptance Criteria

1. The two audited BSC rows classify as `SWAP`, not `EXTERNAL_TRANSFER_OUT`.
2. Their canonical flow shape becomes:
   - `SELL` source token
   - `BUY` native asset
   - `FEE` network gas
3. Generic routed-aggregator outbound demotion still applies to true outbound-
   only rows without deterministic settlement evidence.
4. The fix relies only on production-available evidence:
   - calldata
   - clarified token transfers
   - clarified receipt logs

## Verification

- `OnChainClassifierTest`
- `ReceiptClarificationWorkflowHandlerTest`
- `ClassificationArchitectureArchTest`
- `ModuleDependencyArchTest`
