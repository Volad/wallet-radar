# Feature 16: EVM Explorer-First Ingestion V2 (ADR-026)

**Tasks:** T-039, T-040, T-041, T-042, T-043, T-044

---

## Worker Handoff Command

Use worker skill for this feature implementation:

```text
$worker implement docs/tasks/16-evm-explorer-ingestion-v2.md (T-039..T-044) end-to-end with tests
```

---

## T-039 — Explorer provider abstraction and per-network adapters (Etherscan V2)

- **Module(s):** ingestion (adapter), config
- **Description:** Introduce `ExplorerProvider` contract for EVM ingestion:
  - `getTransactions(...)` (`txlist`)
  - `getTokenTransfers(...)` (`tokentx`)
  - `getInternalTransfers(...)` (`txlistinternal`)
  - `getReceipt(...)` (proxy endpoint)
- **Implementation:** Create reusable `EtherscanLikeProvider` base and network adapters for ARBITRUM, OPTIMISM, BASE, BSC, POLYGON, AVALANCHE, MANTLE, LINEA + ETHEREUM fallback.
- **Config:** Add `walletradar.ingestion.explorer` per network: `base-url`, `api-key`, `offset`, `rate-limit`, retry/backoff settings.
- **Rule:** Use API V2-compatible endpoints; `offset` default is `5000` (configurable).
- **DoD:** provider contract + adapters + configuration binding + unit tests (URL assembly, auth params, paging params, error mapping).
- **Dependencies:** T-004 (adapter foundations), ADR-026

## T-040 — Raw fetch merge from explorer streams and idempotent upsert

- **Module(s):** ingestion (backfill/store), domain
- **Description:** Replace EVM raw fetch source with explorer streams:
  - merge `txlist + tokentx + txlistinternal` into one raw record per `(txHash, networkId, walletAddress)`;
  - include NFT streams in raw payload only (no accounting classification in this feature).
- **Raw model changes:**
  - rename `classificationStatus` -> `normalizationStatus`;
  - status values in V2: `PENDING`, `COMPLETE`;
  - add retry metadata: `retryCount`, `lastError`, `nextRetryAt`.
- **Idempotency key:** `(txHash, networkId, walletAddress)` unique.
- **DoD:** model/repository/index updates + migration script (field rename/backfill defaults) + integration test for merge/dedupe and idempotent upsert.
- **Dependencies:** T-039, T-002, ADR-026

## T-041 — Backfill segmentation refactor for page/window explorer fetch

- **Module(s):** ingestion (job/backfill), domain
- **Description:** Keep current `BackfillJobRunner` orchestration but refactor segment execution for explorer paging:
  - segment tracks `network + block window + page cursor`;
  - supports restart-safe continuation from saved cursor.
- **Defaults:**
  - `parallel-segments` default to `2`;
  - `parallel-segment-workers` default to `2`.
- **Rule:** use block-window + page traversal, not unbounded single-range scan.
- **DoD:** `BackfillSegment` and executor logic updated; stale recovery and retry behavior preserved; tests for resume from cursor and convergence to COMPLETE.
- **Dependencies:** T-040, T-009, T-030, ADR-024/026

## T-042 — RawTxNormalizationJob and heuristic classifier groups

- **Module(s):** ingestion (job/classification, classifier, pipeline), domain
- **Description:** Use `RawTxNormalizationJob` as the normalization entrypoint:
  - process only raw with `normalizationStatus=PENDING`;
  - build `normalized_transactions` as `1 doc = 1 tx`;
  - produce `flows[]` canonical movement model;
  - use classifier groups: `TRANSFER`, `SWAP`, `LP`, `LEND`;
  - `INTERNAL_TRANSFER` is not part of MVP v2 classification.
- **Confidence:** numeric `[0..1]`.
- **Threading:** normalization parallelism via configurable virtual threads; default `2`.
- **DoD:** new job + grouped classifiers + idempotent normalized upsert + unit/integration tests for each classifier group.
- **Dependencies:** T-040, T-041, T-034, ADR-025/026

## T-043 — Selective receipt enrichment for low-confidence normalization

- **Module(s):** ingestion (classification/enrichment)
- **Description:** If normalization confidence is below threshold, call `ExplorerProvider.getReceipt(...)` to enrich/resolve ambiguous tx.
- **Recommended thresholds:**
  - `>= 0.85` no enrichment;
  - `0.60..0.85` receipt enrichment and re-score;
  - `< 0.60` enrichment + fallback to `NEEDS_REVIEW` when unresolved after retries.
- **Retry policy:** transient failures are retried (backoff + jitter); unresolved tx remains in processing pipeline with reason metadata.
- **DoD:** selective enrichment only (no receipt call for high-confidence tx), retry behavior tests, and NEEDS_REVIEW path tests.
- **Dependencies:** T-042, T-036, ADR-025/026

## T-044 — Pricing pipeline alignment and rollout constraints

- **Module(s):** pricing, ingestion (job/pricing), api
- **Description:** Keep canonical pricing/status flow on normalized transactions:
  - `NormalizedTransactionPricingJob` handles records requiring pricing;
  - `StatJob` finalizes into `CONFIRMED` or `NEEDS_REVIEW`.
- **Rollout constraints:**
  - no backward compatibility requirement for historical v1 data;
  - no Solana support in MVP v2;
  - API read path remains snapshot-first and based on canonical normalized status.
- **DoD:** end-to-end tests for v2 ingestion -> normalization -> pricing -> confirmed visibility; cutover checklist documented.
- **Dependencies:** T-042, T-043, T-037, ADR-026

---

## Acceptance Criteria (BA)

- EVM backfill uses explorer APIs (V2-compatible) as primary source for raw ingestion.
- Raw status field is `normalizationStatus` and processing flow is `PENDING -> COMPLETE`.
- `normalized_transactions` stores one operation document per tx with `flows[]` and `confidence` in `[0..1]`.
- Receipt enrichment is called only for low-confidence normalization cases.
- NFT data is captured in raw payload but excluded from accounting normalization in MVP v2.
- Solana ingestion is not executed in MVP v2.

## Edge Cases

- Explorer returns empty page in the middle of a large window: segment cursor must continue safely without duplicate inserts.
- Multi-token aggregator tx: low-confidence path triggers receipt enrichment and re-scoring.
- Provider rate-limit/timeouts: retry metadata updated; tx remains processable.
- Duplicate tx across merged streams (`txlist` and `tokentx`): single raw document remains after upsert.
- Receipt unavailable after retries: tx ends in `NEEDS_REVIEW` with reason metadata.

## Supported vs Unsupported (MVP v2)

- Supported: EVM explorer-first ingestion, receipt enrichment, heuristic normalized classification groups (TRANSFER/SWAP/LP/LEND), canonical normalized status pipeline.
- Unsupported: Solana ingestion, internal-transfer reclassification in V2 classifier scope, NFT accounting normalization.
