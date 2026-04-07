# Velodrome Protocol Rules

Status: Active protocol rule slice

## Scope

Cover audited `Velodrome Slipstream` concentrated-liquidity position-manager
semantics that are relevant to:

- `APPROVE`
- `LP_ENTRY`
- `LP_EXIT`
- `LP_POSITION_STAKE`
- `LP_POSITION_UNSTAKE`

## Runtime Ownership

- Position-manager lifecycle helpers:
  [LpPositionLifecycleSupport.java](/Users/vladislavkondratenko/projects/wallet-radar/backend/src/main/java/com/walletradar/ingestion/pipeline/classification/support/LpPositionLifecycleSupport.java)
- Family mapping:
  [LpRegistryClassifier.java](/Users/vladislavkondratenko/projects/wallet-radar/backend/src/main/java/com/walletradar/ingestion/pipeline/classification/onchain/family/LpRegistryClassifier.java)
  [MethodIdClassifier.java](/Users/vladislavkondratenko/projects/wallet-radar/backend/src/main/java/com/walletradar/ingestion/pipeline/classification/onchain/family/MethodIdClassifier.java)

## Authoritative Evidence

- `OnChainRawTransactionView`
- recovered selector from `rawData.methodId` or `rawData.input[0:10]`
- persisted token/internal/native movement
- persisted receipt logs when position-manager lifecycle needs ERC-721 mint or
  modify-liquidity confirmation
- trusted contract identity from
  `backend/src/main/resources/protocol-registry.json`

## Classification Rules

### Approve

- recovered selector `0x095ea7b3` with:
  - `value = 0`
  - no token transfers
  - no internal transfers
  remains `APPROVE`
- this is true even when the target contract is a trusted position manager

### LP entry / exit

- direct or multicall liquidity-add selectors with real outbound principal
  movement remain `LP_ENTRY`
- ERC-721 mint or positive modify-liquidity receipt evidence may also confirm
  `LP_ENTRY`
- decrease / burn / collect paths with real inbound principal remain `LP_EXIT`
  or `LP_FEE_CLAIM` according to wallet-boundary movement

## Clarification Rules

- clarification may be used only when receipt evidence can actually close LP
  lifecycle meaning, such as:
  - position NFT mint
  - modify-liquidity log deltas
- clarification is not required for plain approve semantics when saved calldata
  already proves `approve(address,uint256)`

## Disallowed Fallbacks

- do not let trusted position-manager identity alone emit `LP_ENTRY`
- do not demote recovered `approve` selector into accounting-bearing LP
  semantics when economic movement is absent

## Regression Anchors

- `0x0c24997c61ef140fa5fdfdfaccbbc4da7ce658035a82981cfa3726f177970403`
- `0xbea1ddd320653adc3ba0b122d623f21f3101b2c5c6b8d741ef392b3e03366690`

Both audited rows are zero-movement approve calls to trusted
`Velodrome Slipstream Position Manager`
`0x416b433906b1b72fa758e166e239c43d68dc6f29` and must remain `APPROVE`.
