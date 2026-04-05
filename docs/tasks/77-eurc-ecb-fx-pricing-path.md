# 77. EURC ECB FX Pricing Path

## Context

`EURC` has weak exchange-market coverage in the current free-source stack:

- no usable `EURCUSDT` or `EURCUSDC` listing on Binance
- no usable `EURCUSDT` or `EURCUSDC` listing on Bybit
- CoinGecko fallback is rate-limited and should not be the primary path

`EURC` is an issuer-backed euro stablecoin, so the correct free-source pricing
path is:

1. `1 EURC = 1 EUR`
2. `EUR/USD` from official ECB reference rates

## Decision

For euro-backed stablecoins such as `EURC` / `EUROC`, pricing order becomes:

1. `ECB`
2. `BYBIT`
3. `BINANCE`
4. `COINGECKO`

For all other assets, pricing order remains:

1. `BYBIT`
2. `BINANCE`
3. `COINGECKO`

## Scope

- add `PriceSource.ECB`
- add official ECB EUR/USD historical adapter
- add euro-stable detection in canonical asset catalog
- prefer ECB for `EURC` / `EUROC`
- keep deterministic cache behavior and fallback order

## Acceptance

- `EURC` prices from ECB before any exchange-market source is attempted
- ECB lookup falls back to the latest available business-day observation
- non-euro assets keep the existing external priority order
- pricing docs explicitly describe the EUR stablecoin FX path
