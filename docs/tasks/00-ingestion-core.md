# Ingestion core (T-004, T-005, T-006, T-007, T-008)

RPC adapters, classifiers, normalizer, event store. No separate user-facing feature; DoD serves as acceptance.

---

## T-004 — NetworkAdapter interface and EVM adapter

- **Module(s):** ingestion (adapter)
- **Description:** Define `NetworkAdapter` interface (fetch transactions/blocks for a wallet×network, batch size contract). Implement `EvmNetworkAdapter`: `eth_getLogs` (or equivalent) in batches of 2000 blocks, RPC endpoint abstraction. Integrate `RpcEndpointRotator` with round-robin and exponential backoff (±20% jitter).
- **Doc refs:** 02-architecture (ingestion/adapter), 00-context (EVM RPC)
- **DoD:** Interface; EVM implementation; unit tests with mocked RPC; integration test against public RPC or Testcontainers if applicable.
- **Dependencies:** T-001, T-003

**Acceptance criteria**
- NetworkAdapter interface and EvmNetworkAdapter implemented; unit and integration tests pass.

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
