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

### v2 (fungible) gauge staking

Velodrome/Aerodrome ship **two** gauge kinds; distinguish them by on-chain grammar
(`DexGaugePoolResolver`), never by address or protocol string:

- **CL / Slipstream gauge** — exposes `nft()` (the NonfungiblePositionManager). Its stake is a real
  ERC-721 position; keep the registry `underlyingPositionManager` + numeric `tokenId` correlation path.
- **v2 (AMM) gauge** — `nft()` **reverts**, `pool()` returns the staked v2 AMM LP token (an ERC-20
  pair). A `deposit(uint256)` / `withdraw(uint256)` argument is an **amount**, not a tokenId.

For a v2 gauge `LP_POSITION_STAKE`/`LP_POSITION_UNSTAKE`, `LpRegistryClassifier` keys the correlation on
the on-chain staked LP token (`gauge.pool()`), carrying the gauge for valuation:

```
lp-position:<net>:<stakedLpToken>:vault:<gauge>
```

Enrichment (`FungiblePoolReader`) resolves the pair from the staked LP token's `token0()`/`token1()` +
`symbol()`/`decimals()` and values the staked balance via `gauge.balanceOf(wallet)` (the wallet's direct
LP balance is 0 while staked); status is `in_range`. This replaces the previous unresolvable
`:vault`-on-NFPM correlation that surfaced as **"Unknown pair"**. See
[ADR-077](/Users/vladislav/projects/wallet-radar/docs/adr/ADR-077-v2-fungible-gauge-lp-identity.md).

Evidence anchor (Optimism): gauge `0xbc6043a5…`, `pool()` = `0x4da46c6a…` (USD₮0/USDT), rewards in VELO.

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
