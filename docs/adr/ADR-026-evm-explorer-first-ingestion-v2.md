# ADR-026: EVM Explorer-First Ingestion (Etherscan V2-Compatible) for MVP v2

**Date:** 2026-02-28  
**Status:** Accepted  
**Deciders:** Product Owner, Business Analyst, System Architect

---

## Context

Current EVM backfill based on public JSON-RPC `eth_getLogs` windows is too slow on public endpoints for heavy wallets.
For MVP v2, product priority is faster delivery of wallet monitoring UX with controlled infrastructure cost.

At the same time, we keep canonical accounting on `normalized_transactions` and do not need backward compatibility with legacy ingestion state for this migration.

---

## Decision

Adopt **Explorer-first ingestion** for EVM networks using **Etherscan V2-compatible APIs** with provider adapters.

### Provider contract

Introduce `ExplorerProvider` interface:

- `getTransactions(...)` (`txlist`)
- `getTokenTransfers(...)` (`tokentx`)
- `getInternalTransfers(...)` (`txlistinternal`)
- `getReceipt(...)` (proxy receipt endpoint)

All network providers implement this contract (single reusable Etherscan-like base implementation + network config).

### MVP v2 network providers

- Arbitrum: `api.arbiscan.io`
- Mantle: `api.mantlescan.xyz`
- Linea: `api.lineascan.build`
- Avalanche: `api.snowtrace.io`
- BNB: `api.bscscan.com`
- Polygon: `api.polygonscan.com`
- Base: `api.basescan.org`
- Optimism: `api-optimistic.etherscan.io`
- Ethereum fallback: `api.etherscan.io`

### Fetch strategy

- Keep backfill window: ~2 years per network.
- Use block-window scan + paged fetch (`page`, `offset`).
- Default `offset=5000` (configurable).
- Merge raw payload from `txlist + tokentx + txlistinternal` into one `raw_transactions` document per `(txHash, networkId, walletAddress)`.
- Store NFT streams in raw only (no accounting impact in MVP v2).

### Raw processing status

Rename raw status field:

- `classificationStatus` -> `normalizationStatus`

Allowed values in MVP v2:

- `PENDING`
- `COMPLETE`

Retry/error metadata is stored separately (`retryCount`, `lastError`, `nextRetryAt`), while unresolved records remain `PENDING`.

### Normalization

- `RawTxNormalizationJob` processes only `raw_transactions.normalizationStatus=PENDING`.
- Output target: one `normalized_transactions` document per tx (`1 doc = 1 tx`) with `flows[]`.
- `confidence` is numeric in range `[0..1]`.
- Heuristic classifier groups (Transfer/Swap/LP/Lend) determine `NormalizedTransactionType`.
- If confidence is below threshold, call `getReceipt(...)` for targeted enrichment and re-score.

### Pricing and accounting pipeline

- Keep canonical status pipeline in `normalized_transactions` (`PENDING_CLARIFICATION -> PENDING_PRICE -> PENDING_STAT -> CONFIRMED`).
- `NormalizedTransactionPricingJob` resolves prices only for records needing pricing.
- AVCO replay uses only `CONFIRMED` normalized transactions.

### Scope constraints

- Solana ingestion is out of scope for MVP v2.
- No historical data migration/backward compatibility guarantees are required for this change.

---

## Consequences

### Positive

- Faster backfill startup for EVM on public/free infrastructure.
- Lower operational complexity than wide raw RPC scan on public endpoints.
- Consistent multi-chain ingestion API via `ExplorerProvider`.
- Selective receipt enrichment keeps cost controlled while preserving resolution quality for ambiguous transactions.

### Trade-offs

- Explorer API quality/coverage can vary by network and provider.
- Requires robust retries, throttling, and page/window dedupe logic.
- NFT and internal-transfer semantics remain intentionally simplified in MVP v2.

### Risks and mitigations

- **Rate-limit/timeouts:** per-provider throttling + jitter backoff + retry metadata on raw.
- **Partial indexing on explorer side:** keep fallback provider chain and receipt enrichment path.
- **Duplicate/missing records in pagination:** enforce idempotent merge key `(txHash, networkId, walletAddress)` and block-window checkpointing.

---

## Implementation Notes

- `BackfillJobRunner` orchestration remains; segment execution model is reused.
- `BackfillSegment` moves to page/window-oriented progress (segment over network+block range+page cursor).
- Default segment parallelism for MVP v2 is reduced to 2.
- Default normalization parallelism (virtual threads) is 2, configurable via `application.yml`.

---

## References

- ADR-020 (split raw fetch vs classification)
- ADR-021 (classifier as separate process)
- ADR-025 (canonical normalized transaction pipeline)
