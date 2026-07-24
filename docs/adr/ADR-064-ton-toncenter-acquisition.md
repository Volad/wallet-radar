# ADR-064: TON acquisition via TON Center v3 public API

**Status:** Accepted  
**Date:** 2026-07-18  
**Scope:** TON ingestion, normalization, address handling

---

## Context

Two TON wallets (standard Telegram Wallet, EQ/UQ friendly form) needed to be tracked for native TON + Jetton transfers. TON's architecture differs from EVM:
- Accounts have message-centric transactions (in_msg + out_msgs); no gas-fee-receipts in EVM style.
- Token standard is TEP-74 (Jetton): ownership via "jetton wallet" contracts, identity via "jetton master" contract address.
- Addresses are case-sensitive; the same contract can have multiple representations (UQ/EQ friendly, raw workchain:hex). The existing `TonAddressCanonicalizer` handles this.
- There is no paid indexer in budget; TON Center v3 public REST API is free.

---

## Decision

**Use TON Center v3 public API (`https://toncenter.com/api/v3/`) for TON transaction ingestion.**

1. `TonNetworkAdapter` calls `GET /transactions?account={address}&limit=100` (offset-based pagination) and `GET /jetton/transfers?owner_address={address}` for token transfers.
2. Full toncenter response stored in `rawData.transaction` (BSON); associated jetton transfers in `rawData.jettonTransfers[]`.
3. `TonBlockHeightResolver` returns masterchain seqno via `GET /masterchainInfo`.
4. `TonBlockTimestampResolver` returns `Instant.now()` (timestamps come from per-tx `now` field, not from seqno lookup).
5. `TonNormalizedTransactionBuilder` classifies as EXTERNAL_TRANSFER_IN/OUT, INTERNAL_TRANSFER, FEE. No LP/lending expected (Telegram Wallet only).
6. TON address case is preserved everywhere (never lowercased).

---

## Rationale

| Alternative | Why rejected |
|---|---|
| tonapi.io (paid) | Not in budget; no API key present |
| TON RPC ADNL/LITESERVER | Very complex binary protocol; toncenter REST is far simpler |
| TON RPC v2 | Deprecated, removed endpoints |

TON Center v3 provides reliable transaction history and Jetton transfer indexing at no cost within standard rate limits. An optional `TON_API_KEY` env var can be provided to increase rate limits.

---

## Address handling

- TON addresses are stored and displayed exactly as provided (UQ/EQ friendly form, case-sensitive).
- `TonAddressCanonicalizer` converts between friendly and raw forms for universe membership matching.
- `TrackedWalletProjectionService` was fixed to not lowercase TON addresses (it now uses domain detection: only EVM → lowercase).

---

## Jetton asset identity

- Jetton asset `assetContract = jetton_master` address (canonical form from toncenter).
- DefiLlama coin ID: `ton:<jetton_master_address_lowercase>` for pricing.
- Native TON: `assetContract = "TONCOIN"`, DefiLlama: `coingecko:toncoin`.

---

## Consequences

- TON raw transactions land in `raw_transactions` with `rawData.source = "TONCENTER_V3"`.
- Normalization is intentionally lightweight (transfer-only); add LP/lending classification when Jetton-based DeFi positions are observed.
- Rate limits on free toncenter tier apply; requests are sequential per wallet during backfill.
- TON `fullIndex: true` in `application.yml` enables backfill once adapter is wired.
