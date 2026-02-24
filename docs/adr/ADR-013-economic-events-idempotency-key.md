# ADR-013: Economic Events Idempotency Key — (txHash, networkId, walletAddress, assetContract)

**Date:** 2026-02  
**Status:** Accepted  
**Deciders:** System Architect

---

## Context

Originally, on-chain economic events were upserted by `(txHash, networkId)` — one document per transaction (INV-11). This implied at most one event per tx. Swaps produce two legs (e.g. SWAP_SELL for token A, SWAP_BUY for token B); with a single key the second upsert overwrote the first, losing one leg and breaking correct AVCO for both assets.

## Decision

**Idempotency key for on-chain events is `(txHash, networkId, walletAddress, assetContract)`.** One transaction can have multiple events (e.g. SWAP_SELL and SWAP_BUY). For MANUAL_COMPENSATING, idempotency remains by `clientId`.

## Rationale

- **Correct AVCO:** Each leg of a swap must be stored separately so AVCO replay sees both outflow (sell) and inflow (buy) per asset.
- **Deterministic upsert:** Same (tx, wallet, asset) always updates the same document; re-ingestion is idempotent.
- **Sparse unique index:** Documents with `txHash`/`networkId`/`walletAddress`/`assetContract` present use the new compound unique index; manual events (no txHash) are excluded via sparse index.

## Consequences

- **Positive:** Swaps and other multi-leg txs are stored completely; AVCO and reporting are correct.
- **Positive:** Backfill and incremental sync can re-process the same tx without duplicates; upsert updates the matching leg.
- **Migration:** Existing DBs had unique index on `(txHash, networkId)`. Deploy must create new index `(txHash, networkId, walletAddress, assetContract)` unique sparse, then drop the old one. Existing documents with one event per tx remain valid under the new key.
- **Order within same block:** Events from the same tx share `blockTimestamp`. AVCO replay orders by `blockTimestamp ASC`; if needed for determinism, future work can add `logIndex` and sort by it when timestamps are equal.

## References

- INV-11 in `docs/01-domain.md`
- `EconomicEvent` compound index `txHash_networkId_wallet_asset`
- `IdempotentEventStore`, `EconomicEventRepository.findByTxHashAndNetworkIdAndWalletAddressAndAssetContract`
