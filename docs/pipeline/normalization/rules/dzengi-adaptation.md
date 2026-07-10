# Dzengi Adaptation Rules

> **Last updated:** 2026-07-09  
> Three-layer contract for Dzengi CEX evidence: raw API → extracted staging → canonical `normalized_transactions`.

## Layer 1 — Raw acquisition (`integration_raw_events`)

| Field | Value |
|-------|-------|
| `provider` | `DZENGI` |
| `stream` | `LEDGER`, `DEPOSITS`, `WITHDRAWALS`, `MY_TRADES:<symbol>`, `MY_TRADES_V2:<symbol>`, `TRADING_POSITIONS_HISTORY`, `EXCHANGE_INFO` |
| Immutability | Append-only; cursor per segment |

Signed REST client: `DzengiApiClient` (Binance-compatible HMAC, mandatory browser `User-Agent`).

### `MY_TRADES` acquisition constraints

Dzengi `/myTrades` is **not** paginable over history:

| Constraint | Behaviour |
|-----------|-----------|
| `symbol` | Mandatory (`-1102 symbol must not be null` when omitted) |
| `limit` | Hard cap **100** (`-1128 Invalid limit` for `>100`) |
| `startTime` / `endTime` | Removed by Dzengi — not accepted |
| `fromId` | **Ignored** — always returns the latest fills |

Consequence: only the **last 100 fills per symbol** are recoverable. The planner emits **one `MY_TRADES:<symbol>` segment per symbol** (no time windows) covering **all non-leverage spot symbols** (`marketType != LEVERAGE`, not `*_LEVERAGE`, not `*.`), regardless of quote asset, so crypto pairs in BTC/EUR/GBP and fiat FX pairs (e.g. `USD/BYN`) are not missed. `walletradar.integration.dzengi.my-trades-quote-assets` optionally restricts this universe (empty = all). Leverage/CFD activity is captured via `TRADING_POSITIONS_HISTORY`, not `MY_TRADES`.

### `MY_TRADES_V2` — tokenized equities (USD.cx market)

Tokenized stocks (TSLA, AAL, AMZN, NVDA, BABA, SNAP, etc.) trade on Dzengi's USD.cx market and are **not** available via v1 `/myTrades` with dotted symbols (`TSLA.` returns empty or is excluded). v2 `/api/v2/myTrades` accepts clean symbols (`TSLA`, `AAL`) and returns up to 100 latest fills per symbol.

| Constraint | Behaviour |
|-----------|-----------|
| API path | `/api/v2/myTrades` |
| Symbol format | No trailing dot (`TSLA`, not `TSLA.`) |
| `limit` | Hard cap **100** (same as v1) |
| Discovery | v1 `exchangeInfo` LEVERAGE symbols with `assetType` in `EQUITY` / `COMMODITY` / `INDEX` (dot stripped) + dotted symbols from `TRADING_POSITIONS_HISTORY` (e.g. `NVDA.` → `NVDA`) |

The planner emits **one `MY_TRADES_V2:<symbol>` segment per discovered equity symbol**. Extraction resolves metadata via the dotted v1 catalog entry (`TSLA` → `TSLA.`) and strips the trailing dot from the base asset for ledger identity. Symbols absent from v1 `exchangeInfo` (e.g. `NVDA`, `BABA`, `SNAP`) are probed via `walletradar.integration.dzengi.my-trades-v2-additional-symbols` (default: those three tickers).

### `TRADING_POSITIONS_HISTORY` — v2 API

Closed leverage/CFD positions are fetched via `/api/v2/tradingPositionsHistory` (superset of v1; includes equity settlements like `SNAP.`, `NVDA.`). Extraction sets `timeUtc` from `execTimestamp` (fallback `createdTimestamp`) — not from `timestamp`/`time`, which are absent on position payloads.

> **Gap:** Dzengi "swap" (instant-exchange) fills have no dedicated history endpoint and surface only as a USD/quote leg in `LEDGER` (no base-asset leg). Their base-asset cost basis is not recoverable via the current API surface.

## Layer 2 — Extraction (`dzengi_extracted_events`)

`DzengiExtractionService` maps raw payloads to typed staging rows.

### Stream → extracted shape

| Stream | Key source fields | `canonicalType` | `basisRelevant` |
|--------|-------------------|-----------------|-----------------|
| `LEDGER` | `type`, `currency`, `amount` | Mapped per ledger type | Per type |
| `DEPOSITS` | `currency`, `amount`, `blockchainTransactionHash` | `EXTERNAL_TRANSFER_IN` | `true` |
| `WITHDRAWALS` | `currency`, `amount`, `blockchainTransactionHash` | `EXTERNAL_TRANSFER_OUT` | `true` |
| `MY_TRADES:<symbol>` | fill fields + symbol metadata | `BUY` / `SELL` | `true` if spot |
| `MY_TRADES_V2:<symbol>` | v2 fill fields + dotted metadata | `BUY` / `SELL` | `true` |
| `TRADING_POSITIONS_HISTORY` | position settlement fields | `CEX_DERIVATIVE_SETTLEMENT` | `true` |

### Exclusion rules

| Condition | Effect |
|-----------|--------|
| Symbol metadata `leverageOrCfd = true` on `MY_TRADES` | Row marked `outOfScope`; reason `LEVERAGE_FILL_EXCLUDED` |
| `MY_TRADES_V2` fills | Always basis-relevant; metadata resolved via dotted v1 symbol |
| Unknown ledger type | No canonical type; skipped in normalization |
| Missing `userId` on account ref | Extraction uses `DZENGI:` prefix strip from integration `accountRef` |

### Symbol metadata

`DzengiSymbolMetadataCache` hydrates from `EXCHANGE_INFO` segment: base/quote assets, market type, leverage/CFD flag.

## Layer 3 — Canonical builder (`DzengiCanonicalTransactionBuilder`)

### Identity

| Field | Rule |
|-------|------|
| `id` | `dzengi:<extractedRowId>` |
| `source` | `NormalizedTransactionSource.DZENGI` |
| `walletAddress` | `DZENGI:<userId>` |
| `txHash` | From deposit/withdraw `blockchainTransactionHash` when present |

### Trade (spot)

- Type: `SWAP`
- BUY leg: base asset positive qty, `PriceSource.EXECUTION` on buy flow when buyer
- SELL leg: quote asset flow with execution price
- Commission flow: `NormalizedLegRole.FEE` when `commission` + `commissionAsset` present

### Derivative settlement

- Type: `CEX_DERIVATIVE_SETTLEMENT`
- Maps trading position history PnL/settlement lines
- Lifecycle: `DERIVATIVE` in replay (`AssetLedgerSupport`)

### Transfers

- Deposit → `EXTERNAL_TRANSFER_IN`, quantity positive
- Withdrawal → `EXTERNAL_TRANSFER_OUT`, quantity negative (absolute amount negated at extraction)

### Counterparty stamping

`DzengiCanonicalTransactionBuilder` stamps a row-local counterparty on every non-fee flow (and the transaction) so the stat-validation gate does not park rows as `NEEDS_REVIEW` with `STAT_COUNTERPARTY_TYPE_MISSING` / `FLOW_COUNTERPARTY_MISSING` (mirrors Bybit FA-001 P0):

| Type | `counterpartyType` | `counterpartyAddress` |
|------|--------------------|-----------------------|
| `EXTERNAL_TRANSFER_IN` / `EXTERNAL_TRANSFER_OUT` | `UNKNOWN_EOA` | `DZENGI:EXTERNAL:CHAIN` (synthetic; real pairing runs on `txHash` per ADR-049) |
| `SWAP` (spot trade) / `CEX_DERIVATIVE_SETTLEMENT` | `CEX` | `DZENGI:VENUE:<userId>` |
| `FEE` (fee-only rows) | — | exempt (replay-safe fee flows) |

Without this stamping the whole session's AVCO replay stays blocked (`avcoReady = false`), since the replay gate requires zero blocking `NEEDS_REVIEW` rows.

### Stablecoin handling

USD stablecoins on Dzengi rows use `CanonicalAssetCatalog.isUsdStablecoin(..., DZENGI)` for parity pricing eligibility.

## Correlation

Dzengi does not introduce venue-specific correlation families in costbasis. Cross-venue deposit/withdraw linking uses FA-001 `txHash` pairing ([ADR-049](../../../adr/ADR-049-venue-agnostic-cex-transfer-linking.md)).

## Status lifecycle

Extracted row: `RAW` → `CONFIRMED` after successful canonical upsert (same pattern as Bybit extracted events).

## CEX fee capitalization (ADR-051)

Dzengi charges a USD commission on every spot trade (typically 0.05 %). This commission is both:

1. Recorded as a `FEE` leg on the normalized transaction (`commissionAsset = "USD"`) — reduces the USD position at replay.
2. Stored in `Flow.acquisitionFeeUsd` on the **BUY leg** (only when `commissionAsset` is a USD stablecoin) — capitalized into **Net AVCO only** at replay, leaving Market AVCO as the clean fill price.

This means:
- **Market AVCO** = pure fill-price weighted average (matches Dzengi platform "average price").
- **Net AVCO** = fill-price + commissions paid.
- The move-basis "gas paid" header for the bought asset now shows accumulated commissions (was always zero before this change).

### Dividends

Dzengi pays equity dividends, but **no public Dzengi API endpoint exposes dividend transaction history**. The Dzengi `/ledger`, `/deposits`, `/withdrawals`, `/myTrades`, `/myTrades_v2`, and `/transactions` endpoints do not return dividend entries. Dividends therefore cannot be backfilled and have no AVCO impact in the current pipeline. This is parked until a suitable API becomes available.

## Related

- [Dzengi normalization](../04-dzengi-normalization.md)
- [CEX_DERIVATIVE_SETTLEMENT](../../../reference/transaction-types.md#cex-derivative-settlement)
- [Add an integration](../../../reference/extensibility/add-an-integration.md)
- [ADR-051: CEX fee capitalization into Net AVCO](../../../adr/ADR-051-cex-fee-capitalization-net-avco.md)
