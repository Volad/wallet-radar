# Ingestion core (T-004, T-005, T-006, T-007, T-008)

RPC adapters, classifiers, normalizer, event store. No separate user-facing feature; DoD serves as acceptance.

---

## EVM batch block size (eth_getLogs) — product requirement

Batch size for `eth_getLogs` ingestion must be **configurable per EVM network** (or at least different defaults for L1 vs L2), not a single global constant.

| Requirement | Behaviour |
|-------------|-----------|
| **Config** | Batch size is read from configuration per `networkId` (e.g. `walletradar.ingestion.evm.batch-block-size[networkId]=N` or similar). |
| **Global default** | **2000 blocks** when no per-network value is set. |
| **Invalid values** | If configured value is ≤0 or above a reasonable cap: do not apply; use default and **log** (warning). |
| **Unknown networkId** | For unknown or new EVM `networkId`: use **global default**; **do not fail** ingestion. |

**Acceptance criteria (DoD)**
- Batch size is read from config per `networkId`; global default is 2000 blocks.
- Invalid values (≤0 or above cap) are rejected: default is used and a warning is logged.
- Unknown/new EVM `networkId` uses global default and does not cause failure.

**Architecture:** Batch size is resolved by ingestion config or EvmNetworkAdapter by `networkId`; config key e.g. `walletradar.ingestion.evm.batch-block-size[networkId]`. See **ADR-011** and **02-architecture.md** (ingestion adapter).

---

## T-004 — NetworkAdapter interface and EVM adapter

- **Module(s):** ingestion (adapter)
- **Description:** Define `NetworkAdapter` interface (fetch transactions/blocks for a wallet×network, batch size contract). Implement `EvmNetworkAdapter`: `eth_getLogs` (or equivalent) with **per-network configurable batch block size** (see "EVM batch block size" above; global default 2000). RPC endpoint abstraction. Integrate `RpcEndpointRotator` with round-robin and exponential backoff (±20% jitter).
- **Doc refs:** 02-architecture (ingestion/adapter), 00-context (EVM RPC, EVM batch block size), this file (EVM batch block size).
- **DoD:** Interface; EVM implementation with per-network batch size from config (default 2000); validation and logging for invalid values; unknown networkId → global default, no fail; unit tests with mocked RPC; integration test against public RPC or Testcontainers if applicable.
- **Dependencies:** T-001, T-003

**Acceptance criteria**
- NetworkAdapter interface and EvmNetworkAdapter implemented; batch size read from config per networkId; default 2000; invalid values → default + log; unknown networkId → global default, no failure; unit and integration tests pass.

---

## T-005 — Solana network adapter

- **Module(s):** ingestion (adapter)
- **Description:** Implement `SolanaNetworkAdapter`: getSignaturesForAddress + SPL token/balance resolution within batch and rate limits. Reuse retry/backoff and endpoint rotation pattern.
- **Doc refs:** 02-architecture (ingestion/adapter), 00-context (Solana Helius)
- **DoD:** Implementation; unit tests with mocked RPC; integration test where feasible.
- **Dependencies:** T-004

**Acceptance criteria**
- SolanaNetworkAdapter implemented; unit tests (and integration if feasible) pass.

---

## T-006 — Transaction classifiers

- **Module(s):** ingestion (classifier)
- **Description:** Implement `TxClassifier` dispatch and classifiers: `SwapClassifier`, `TransferClassifier`, `StakeClassifier`, `LpClassifier`, `LendClassifier`; `InternalTransferDetector` (counterparty in session); `InternalTransferReclassifier` (retroactive EXTERNAL_INBOUND → INTERNAL_TRANSFER when wallet added). Use `ProtocolRegistry` for protocol names.
- **Doc refs:** 01-domain (Event Types), 02-architecture (ingestion/classifier)
- **DoD:** All classifiers implemented; unit tests per classifier with fixture transactions; InternalTransferReclassifier tested with multi-wallet scenario.
- **Dependencies:** T-001

**Acceptance criteria**
- All classifiers implemented; unit tests per classifier; InternalTransferReclassifier multi-wallet test.

---

## T-007 — Economic event normalizer and gas cost

- **Module(s):** ingestion (normalizer)
- **Description:** Implement `EconomicEventNormalizer`: raw classifier output → `EconomicEvent` (network-agnostic). Implement `GasCostCalculator`: gasUsed × gasPrice × native token price → USD. Set `gasIncludedInBasis` per 03-accounting (BUY default true).
- **Doc refs:** 01-domain (EconomicEvent), 03-accounting (Gas Treatment), 02-architecture (normalizer)
- **DoD:** Normalizer and gas calculator; unit tests with stub price; verify event shape and gas fields.
- **Dependencies:** T-001, T-006

**Acceptance criteria**
- Normalizer and GasCostCalculator implemented; event shape and gas fields verified in tests.

---

## T-008 — IdempotentEventStore

- **Module(s):** ingestion (store)
- **Description:** Implement `IdempotentEventStore`: upsert economic events keyed by `(txHash, networkId)` uniqueness. No duplicate events for same tx; support MANUAL_COMPENSATING (keyed by synthetic id or clientId).
- **Doc refs:** 02-architecture (store), 01-domain INV-11
- **DoD:** Store implementation; integration test: double write same tx → single event; verify indexes.
- **Dependencies:** T-001, T-002

**Acceptance criteria**
- Upsert by (txHash, networkId) uniqueness; double write same tx → single event; MANUAL_COMPENSATING supported (clientId/synthetic id).
