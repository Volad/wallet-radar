# 76. Bybit Price Source Fallback

## Context

Live pricing exposed a real gap for canonical `BYBIT` rows:

- `MNT` rows had no deterministic Binance symbol coverage
- CoinGecko fallback returned `401`
- pricing therefore stalled even though the rows originated from Bybit itself

## Decision

External pricing order becomes:

- `BYBIT -> BINANCE -> COINGECKO`

This does not change the higher-priority pricing layers:

1. stablecoin parity
2. exact execution price from canonical evidence
3. swap-derived price
4. wrapper/native mapping

## Scope

- add `PriceSource.BYBIT`
- add Bybit spot kline adapter/client
- add deterministic Bybit symbol mapping
- make external source orchestration prefer Bybit globally before Binance/CoinGecko
- keep cache keys deterministic and source-scoped

## Acceptance

- rows try Bybit market data before Binance/CoinGecko
- Bybit trade rows with exact execution price still reuse tx-local price and do not call external market data
- historical cache remains deterministic by `assetKey + bucket + source`
