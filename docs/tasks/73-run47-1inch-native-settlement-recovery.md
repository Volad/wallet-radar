# Task 73: 1inch Native Settlement Recovery

Status: Done

## Context

`run/47` left a narrow `1inch` tail where routed outbound-only rows remained in
active accounting as `EXTERNAL_TRANSFER_OUT + ROUTED_AGGREGATOR_OUTBOUND_ONLY`.
Audit evidence showed that these rows are not generic wallet sends:

- four `swap(...)` transactions already prove `dstToken = native` and
  `dstReceiver = tracked wallet` directly in calldata
- `BASE` explorer evidence confirms native payout from the `1inch` router to the
  tracked wallet
- `BSC` `RPC` rows still miss explicit internal transfers, so native settlement
  must be recovered from calldata plus wrapped-native unwrap traces

If left unresolved, pricing/replay can misread them as realized disposals.

## Goal

Recover only the proven `1inch` native-settlement rows into `SWAP`, while
keeping the generic routed-aggregator outbound demotion for truly outbound-only
rows.

## Scope

- allow full-receipt clarification for `1inch` rows with
  `ROUTED_AGGREGATOR_OUTBOUND_ONLY`
- recover wallet native inbound for `1inch swap(...)` rows when:
  - calldata proves `dstToken = native`
  - calldata proves `dstReceiver = tracked wallet`
  - wrapped-native unwrap traces prove the settlement quantity
- keep generic aggregator fallback unchanged

## Acceptance Criteria

1. `1inch` routed outbound-only rows are selected by full-receipt clarification.
2. `BLOCKSCOUT` clarified rows with confirmed internal native payout reclassify
   to `SWAP`.
3. `RPC` `1inch swap(...)` rows can reclassify to `SWAP` from deterministic
   wrapped-native unwrap evidence even if explorer internal transfers are absent.
4. Generic outbound-only routed aggregator sends still demote to
   `EXTERNAL_TRANSFER_OUT`.
5. No broad transfer/accounting policy changes are introduced.

## Verification

- `OnChainClassifierTest`
- `ReceiptClarificationWorkflowHandlerTest`
