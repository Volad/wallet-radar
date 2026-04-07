# Task 74: 1inch Clarified Transfer Merge Closeout

Status: Done

## Context

`run/48` narrowed the remaining accounting blocker to five `1inch`
native-settlement rows:

- three `BASE` rows already promoted to `SWAP`, but lost the wallet sell leg and
  stayed `NEEDS_REVIEW`
- two `BSC` rows still remained `EXTERNAL_TRANSFER_OUT`, even though calldata and
  clarified receipt evidence prove `dstToken = native` and `dstReceiver = tracked
  wallet`

The root cause was not bridge continuity or `Bybit`. The blocker sat in the
clarified raw view:

- `OnChainRawTransactionView` replaced original explorer transfers with
  clarification transfers instead of merging them
- `1inch` native-settlement recovery depends on both:
  - the original wallet outbound sell leg
  - clarified wrapped-native unwrap / native payout evidence

Replacing instead of merging dropped one of those two evidence sources.

## Goal

Preserve the full wallet-boundary swap shape for clarified `1inch`
native-settlement rows by merging original explorer movement evidence with
clarified receipt transfers.

## Scope

- merge explorer token/internal transfers with clarification transfer evidence
- deduplicate semantically equivalent entries so clarified enrichment does not
  double-count movement
- keep clarified receipt/full-receipt logs behavior unchanged
- tighten regression tests for `1inch` rows so they require full
  `SELL + BUY + FEE` shape

## Acceptance Criteria

1. `OnChainRawTransactionView` no longer drops original explorer transfer legs
   when clarification transfers exist.
2. Clarified `1inch` native-output rows preserve the wallet sell leg and the
   native buy leg together.
3. `BASE` `1inch` rows no longer remain `SWAP / NEEDS_REVIEW` because of missing
   sell shape caused by clarified transfer replacement.
4. `BSC` `1inch swap(...)` rows with deterministic wrapped-native unwrap
   evidence classify as `SWAP`, not `EXTERNAL_TRANSFER_OUT`.
5. Generic routed-aggregator outbound demotion still applies to truly outbound-
   only rows.

## Verification

- `OnChainClassifierTest`
- `ReceiptClarificationWorkflowHandlerTest`
- `ClassificationArchitectureArchTest`
- `ModuleDependencyArchTest`
