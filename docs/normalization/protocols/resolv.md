# Resolv

## Scope

- Protocol-owned semantic detection for `Resolv` unstake lifecycle.
- Current covered lifecycle:
  - `initiateWithdrawal(...)`
  - `withdraw(...)`

## Runtime Ownership

- Protocol semantic detection:
  [ResolvProtocolSemanticClassifier.java](/Users/vladislavkondratenko/projects/wallet-radar/backend/src/main/java/com/walletradar/ingestion/pipeline/classification/onchain/protocol/resolv/ResolvProtocolSemanticClassifier.java)
- Family mapping:
  [StakingClassifier.java](/Users/vladislavkondratenko/projects/wallet-radar/backend/src/main/java/com/walletradar/ingestion/pipeline/classification/onchain/family/StakingClassifier.java)

## Rules

### Unstake Request

- Method identity:
  `0x12edde5e` or normalized function key `initiatewithdrawal`
- Economic shape:
  only outbound non-fee movement
- Additional protocol evidence:
  burn-to-zero transfer of `stRESOLV`
- Final family type:
  `STAKING_WITHDRAW_REQUEST`

### Unstake Settlement

- Method identity:
  `0xe1e13847` or normalized function key `withdraw`
- Economic shape:
  only inbound non-fee movement
- Additional protocol evidence:
  inbound `RESOLV`
- Final family type:
  `STAKING_WITHDRAW`

## Correlation

- Correlation id contract:
  `resolv-unstake:<wallet>:<absolute_quantity>`
- The protocol semantic layer owns correlation derivation.

## Clarification

- No additional clarification rule is required for the currently covered `Resolv`
  unstake lifecycle.
- If future `Resolv` families require receipt-only evidence, extend this
  document before adding runtime logic.
