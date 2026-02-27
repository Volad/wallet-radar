# Feature 15: Canonical normalized transactions pipeline (ADR-025)

**Tasks:** T-034, T-035, T-036, T-037, T-038

---

## Worker Handoff Command

Use worker skill for this feature implementation:

```text
$worker implement docs/tasks/15-normalized-transactions-pipeline.md (T-034..T-038) end-to-end with tests
```

---

## T-034 — `normalized_transactions` domain model, repository, indexes

- **Module(s):** domain, config
- **Description:** Introduce canonical operation model `NormalizedTransaction` with `legs[]` and status workflow.
- **Required fields:** `txHash`, `networkId`, `walletAddress`, `blockTimestamp`, `type`, `status`, `legs[]`, `missingDataReasons`, `createdAt`, `updatedAt`, `confirmedAt`.
- **Leg fields:** `role`, `assetContract`, `assetSymbol`, `quantityDelta`, `unitPriceUsd`, `valueUsd`, `priceSource`, `isInferred`, `inferenceReason`, `confidence`.
- **Indexes:** unique `(txHash, networkId, walletAddress)`; `(walletAddress, networkId, status, blockTimestamp)`; multikey `legs.assetContract`.
- **DoD:** model + repository + index test + serialization test.
- **Dependencies:** T-002

## T-035 — Initial normalization from raw transactions

- **Module(s):** ingestion (pipeline/classification)
- **Description:** Extend classification pipeline to produce `normalized_transactions` from `raw_transactions`.
- **Rules:**
  - classify operation `type` from tx shape,
  - build `legs[]` from observable flows,
  - set initial status:
    - complete legs -> `PENDING_PRICE`,
    - missing leg/data -> `PENDING_CLARIFICATION`.
- **DoD:** classification writes normalized records idempotently; unit tests for SWAP/TRANSFER/internal cases.
- **Dependencies:** T-034, T-031, T-032

## T-036 — Clarification job (selective extra RPC only)

- **Module(s):** ingestion (job, adapter)
- **Description:** Implement `ClarificationJob` for `PENDING_CLARIFICATION` only.
- **Rules:**
  - fetch extra RPC data only for records in `PENDING_CLARIFICATION`,
  - recover missing native leg (trace/internal transfer or equivalent provider capability),
  - set `isInferred/inferenceReason/confidence` on inferred legs,
  - transition to `PENDING_PRICE` on success,
  - transition to `NEEDS_REVIEW` if unresolved after retries.
- **DoD:** selective-query integration test proves no extra RPC calls for non-clarification statuses.
- **Dependencies:** T-035

## T-037 — Pricing and stat transitions

- **Module(s):** pricing, ingestion (job)
- **Description:**
  - `PricingJob`: `PENDING_PRICE -> PENDING_STAT` with leg-level pricing.
  - `StatJob`: `PENDING_STAT -> CONFIRMED` with consistency checks.
- **Rules:**
  - pricing priority: stablecoin -> swap-derived -> CoinGecko,
  - unresolved pricing keeps record in pending/review state with reason.
- **DoD:** end-to-end status transition tests for happy path and unresolved price path.
- **Dependencies:** T-036, T-019, T-020

## T-038 — AVCO + API cutover to confirmed normalized transactions

- **Module(s):** costbasis, api, snapshot
- **Description:**
  - AVCO input source switches to confirmed normalized legs,
  - transaction history API returns only `CONFIRMED` by default,
  - derived positions/snapshots rebuilt from new canonical source.
- **Assumption:** Existing `economic_events` state is not required for migration continuity.
- **DoD:** integration tests for AVCO/PnL consistency on representative wallets; API visibility rule tests.
- **Dependencies:** T-037, T-015, T-021, T-022, T-025

---

## Acceptance Criteria (BA)

- Each transaction appears in pipeline with explicit status and timestamps.
- A swap is considered `CONFIRMED` only when both inbound and outbound legs are available and consistent.
- Additional RPC clarification is executed exclusively for `PENDING_CLARIFICATION` items.
- User-facing transaction history excludes non-confirmed records by default.
- AVCO replay uses only confirmed canonical legs.
- Ambiguous records are not silently confirmed; they end in `NEEDS_REVIEW` with reason.

## Edge Cases

- Native unwrap payout: ERC-20 sell leg exists, buy leg recovered from clarification source.
- Multi-hop/split route: multiple logs but one net out + one net in for wallet still produces one confirmed swap operation.
- Partial data from RPC: record remains pending/review; no corrupted AVCO update.
- Duplicate processing: idempotent upsert keeps one canonical operation per `(txHash, networkId, walletAddress)`.
