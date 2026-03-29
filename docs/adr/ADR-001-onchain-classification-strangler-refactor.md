# ADR-001: Refactor On-Chain Classification into Orchestrator, Protocol Semantics, and Family Classifiers

Status: Proposed
Date: 2026-03-28
Owners: Architecture proposal only, not yet accepted for implementation

## Context

The current on-chain classifier has grown into a monolithic class with protocol-specific,
family-specific, and fallback logic mixed together. This creates several risks:

- low change safety because unrelated protocol changes touch the same file
- poor readability and weak extension path for new protocols
- duplication risk between generic family rules and protocol-owned lifecycle rules
- increased chance of accidental regression during ongoing audit-driven normalization work

The current architecture still has strong assets that should be preserved:

- all on-chain reads can already be normalized through `OnChainRawTransactionView`
- `protocol-registry.json` already provides contract-level discovery metadata
- clarification remains a separate bounded stage
- telemetry and pipeline status transitions already exist and should not be rewritten

The system also has hard business constraints:

- no regression in existing classification quality is acceptable
- on-chain `NEEDS_REVIEW` is not an acceptable steady-state for in-scope protocols
- GMX/GLV pools and GMX derivatives are in accounting scope
- protocol-specific selectors and event topics must not leak into generic family classifiers

## Decision

Adopt a future strangler refactor of the on-chain classification layer with three explicit layers:

1. `OnChainClassifier` becomes a thin orchestrator only.
2. Protocol-specific semantics move into protocol semantic classifiers.
3. Final normalized type selection moves into family classifiers.

This ADR is a proposal for future implementation. It does not authorize a big-bang rewrite.
Implementation must be incremental and golden-master guarded.

## Target Architecture

```text
RawTransaction
  -> OnChainRawTransactionView
  -> OnChainClassificationContextFactory
  -> ProtocolDiscoveryService
  -> ProtocolSemanticClassifiers (0..N hints)
  -> FamilyClassifier chain
  -> ClassificationDecisionMapper
  -> OnChainClassificationResult
```

### Layer Responsibilities

#### 1. OnChainClassifier

`OnChainClassifier` stays as the public entry point but becomes orchestration-only.

Responsibilities:

- build classification context from `RawTransaction` through `OnChainRawTransactionView`
- call protocol discovery
- collect protocol semantic hints
- execute ordered family classifier chain
- map internal decision into `OnChainClassificationResult`

Non-responsibilities:

- direct protocol selector matching
- protocol-specific event-topic parsing
- long family-specific branching trees

Target size: below 300 lines, excluding wiring and trivial helpers.

#### 2. ProtocolDiscoveryService

`ProtocolDiscoveryService` must return a structured discovery result, not only a boolean.

Required output:

- matched protocol contracts
- protocol name and version candidates
- roles and capabilities from registry
- decoded calldata hints when deterministic and cheap

Important rule:

- a transaction may match more than one protocol signal
- discovery must therefore return `0..N` matches, not a single winner

#### 3. ProtocolSemanticClassifier

Protocol semantic classifiers are responsible for protocol-owned lifecycle meaning.

Examples:

- `GmxProtocolSemanticClassifier`
- `CowSwapProtocolSemanticClassifier`
- `EulerProtocolSemanticClassifier`
- `PendleProtocolSemanticClassifier`
- `MorphoProtocolSemanticClassifier`

Responsibilities:

- decode protocol-owned lifecycle signals
- derive correlation ids, terminal/request links, and protocol lifecycle hints
- request clarification through structured requirements when evidence is insufficient
- optionally return a final decision only when the protocol fully owns the lifecycle meaning

Default expectation:

- protocol classifiers should usually produce semantic hints
- family classifiers should usually produce the final normalized type

This avoids moving the monolith from one class into one giant protocol service.

#### 4. Family Classifiers

Family classifiers own normalized type selection at the accounting family level.

Planned families:

- `NonEconomicClassifier`
- `SwapClassifier`
- `BridgeClassifier`
- `LpClassifier`
- `LendingClassifier`
- `VaultClassifier`
- `StakingClassifier`
- `TradingClassifier`
- `TransferClassifier`
- `DefaultClassifier`

Recommended chain order:

1. `NonEconomicClassifier`
2. `TradingClassifier`
3. `LpClassifier`
4. `LendingClassifier`
5. `VaultClassifier`
6. `StakingClassifier`
7. `BridgeClassifier`
8. `SwapClassifier`
9. `TransferClassifier`
10. `DefaultClassifier`

Rationale:

- generic `SWAP` and `TRANSFER` fallbacks must run late
- protocol-owned async and keeper lifecycles must preempt generic transfer heuristics

## Recommended Package Layout

```text
com.walletradar.ingestion.pipeline.classification
  OnChainClassifier
  OnChainClassificationContext
  OnChainClassificationContextFactory
  OnChainClassificationResult
  ClassificationDecision
  ClassificationDecisionMapper

com.walletradar.ingestion.pipeline.classification.reason
  ClassificationReasonCode
  ClarificationRequirement
  ClassificationReasonSupport

com.walletradar.ingestion.pipeline.classification.onchain.family
  OnChainFamilyClassifier
  NonEconomicClassifier
  SwapClassifier
  BridgeClassifier
  LpClassifier
  LendingClassifier
  VaultClassifier
  StakingClassifier
  TradingClassifier
  TransferClassifier
  DefaultClassifier

com.walletradar.ingestion.pipeline.classification.onchain.protocol
  ProtocolDiscoveryService
  ProtocolDiscoveryResult
  ProtocolMatch
  ProtocolSemanticClassifier
  ProtocolSemanticResult

com.walletradar.ingestion.pipeline.classification.onchain.protocol.gmx
  GmxProtocolSemanticClassifier
  GmxProtocolSupport

com.walletradar.ingestion.pipeline.classification.onchain.protocol.cow
  CowSwapProtocolSemanticClassifier
  CowSwapProtocolSupport

com.walletradar.ingestion.pipeline.classification.onchain.protocol.euler
  EulerProtocolSemanticClassifier
  EulerProtocolSupport
```

Bybit remains separate and must not be mixed into on-chain classification orchestration:

```text
com.walletradar.ingestion.pipeline.bybit.classification
  BybitLedgerClassifier
```

## Classifier Boundaries

### Family Ownership

Recommended family ownership:

- `SwapClassifier`
  - `SWAP`
  - `WRAP`
  - `UNWRAP`

- `StakingClassifier`
  - `STAKING_DEPOSIT`
  - `STAKING_WITHDRAW_REQUEST`
  - `STAKING_WITHDRAW`

- `LpClassifier`
  - `LP_ENTRY_REQUEST`
  - `LP_ENTRY_SETTLEMENT`
  - `LP_ENTRY`
  - `LP_EXIT_REQUEST`
  - `LP_EXIT_SETTLEMENT`
  - `LP_EXIT`
  - `LP_EXIT_PARTIAL`
  - `LP_EXIT_FINAL`
  - `LP_ADJUST`
  - `LP_POSITION_STAKE`
  - `LP_POSITION_UNSTAKE`
  - `LP_FEE_CLAIM`

- `LendingClassifier`
  - `LENDING_DEPOSIT`
  - `LENDING_LOOP_OPEN`
  - `LENDING_LOOP_REBALANCE`
  - `LENDING_LOOP_DECREASE`
  - `LENDING_LOOP_CLOSE`
  - `LENDING_WITHDRAW`
  - `BORROW`
  - `REPAY`

- `VaultClassifier`
  - `VAULT_DEPOSIT`
  - `VAULT_WITHDRAW`
  - `PROTOCOL_CUSTODY_DEPOSIT`
  - `PROTOCOL_CUSTODY_WITHDRAW`

- `BridgeClassifier`
  - `BRIDGE_OUT`
  - `BRIDGE_IN`

- `TradingClassifier`
  - `DERIVATIVE_ORDER_REQUEST`
  - `DERIVATIVE_ORDER_EXECUTION`
  - `DERIVATIVE_ORDER_CANCEL`
  - `DERIVATIVE_POSITION_INCREASE`
  - `DERIVATIVE_POSITION_DECREASE`
  - `DEX_ORDER_REQUEST`
  - `DEX_ORDER_SETTLEMENT`

- `TransferClassifier`
  - `EXTERNAL_TRANSFER_OUT`
  - `EXTERNAL_TRANSFER_IN`
  - `INTERNAL_TRANSFER` only if still needed in canonical on-chain scope
  - optionally `REWARD_CLAIM`

- `NonEconomicClassifier`
  - `APPROVE`
  - `ADMIN_CONFIG`
  - failed or non-economic stop conditions
  - spam or phishing sink to `UNKNOWN` with explicit reason code

- `DefaultClassifier`
  - `UNKNOWN`

### Important Scope Notes

- `ADMIN_CONFIG` must not be grouped under spam semantics.
- `PROTOCOL_CUSTODY_*` must not be lost between lending and transfer families.
- `VAULT_*` should remain separate from lending unless a future accepted ADR explicitly merges them.
- `REWARD_CLAIM` may stay inside `TransferClassifier` for pragmatism, but a future `IncomeClassifier` would be semantically cleaner.

## Protocol Registry Rules

`protocol-registry.json` remains a discovery and capability layer, not a full ABI database.

It should continue to store:

- contract address
- protocol name
- version
- family
- role
- confidence

It may be extended with:

- `capabilities`
- `lifecycle_model`
- `clarification_hints`

Example:

```json
{
  "protocol": "GMX",
  "version": "V2",
  "family": "DEX",
  "role": "ROUTER",
  "capabilities": [
    "ASYNC_REQUEST_SETTLEMENT",
    "KEEPER_EXECUTION",
    "DERIVATIVES",
    "POOL_LIFECYCLE",
    "MULTICALL_SUBCALLS"
  ],
  "lifecycle_model": "REQUEST_EXECUTION",
  "clarification_hints": [
    "FULL_RECEIPT",
    "RELATED_TX_BY_CORRELATION"
  ]
}
```

The following must not be forced into the global registry:

- large selector tables
- event topic hashes
- protocol-owned ABI decode layouts

Those belong in protocol-specific support code or protocol-local resources such as:

- `resources/protocols/gmx-v2.json`
- `resources/protocols/cow.json`
- `resources/protocols/euler.json`

## Missing Data Reasons and Clarification

`missingDataReasons` must be centralized.

Introduce:

- `ClassificationReasonCode`
- `ClarificationRequirement`

Protocol and family classifiers must not write raw string reasons inline.

Internal classifier outcomes should be:

- `FINAL`
- `PASS`
- `NEEDS_CLARIFICATION`

The final mapper converts those to persisted statuses such as:

- `PENDING_CLARIFICATION`
- `PENDING_PRICE`
- `UNKNOWN`

This keeps clarification policy centralized and prevents protocol-specific status drift.

## Raw Access Rule

All classifier reads of raw transaction data must go through `OnChainRawTransactionView`.

Rules:

- family classifiers must not read `RawTransaction` directly
- protocol semantic classifiers must not read `RawTransaction` directly
- only the context factory may adapt `RawTransaction` into the classification context

This rule is mandatory for refactor acceptance.

## Logging Rules

Classifier logging must be structured and volume-safe.

Allowed levels:

- `DEBUG` when a classifier positively recognizes a transaction family
- `DEBUG` for the final classifier decision
- `WARN` or `ERROR` with stacktrace for system failures
- `INFO` only for stage-level telemetry and job summaries

Per-transaction final decisions must not be emitted at `INFO` during backfill or steady-state processing.

Recommended structured fields:

- `txHash`
- `walletAddress`
- `networkId`
- `classifier`
- `protocol`
- `decisionType`
- `decisionStatus`
- `correlationId`
- `reasonCodes`

## Migration Strategy

This ADR requires strangler migration only.

Implementation phases:

1. Introduce the new contracts and orchestration context.
2. Keep the current monolith behind a `LegacyFallbackClassifier`.
3. Extract one family at a time.
4. Run full golden-master regression after every extraction.
5. Remove legacy logic only when the extracted family reaches parity.

Recommended extraction order:

1. `NonEconomicClassifier`
2. `SwapClassifier`
3. `BridgeClassifier`
4. `LpClassifier`
5. `LendingClassifier`
6. `VaultClassifier`
7. `StakingClassifier`
8. `TradingClassifier`
9. `TransferClassifier`
10. `DefaultClassifier`

## Regression Safety Requirements

This refactor must not change classification output silently.

Required validation:

- golden-master regression over the full production raw corpus
- equality checks for:
  - `type`
  - `status`
  - `flows`
  - `missingDataReasons`
  - `correlationId`
  - `matchedCounterparty`
  - `protocolName`
  - `protocolVersion`
  - `excludedFromAccounting`
- protocol-focused fixture coverage for:
  - GMX pool lifecycle
  - GMX derivative lifecycle
  - CoW async spot orders
  - Euler loop lifecycle
  - Pendle lifecycle bundles

Any diff must be either:

- an explicit approved semantic fix
- or a regression and therefore rejected

## Acceptance Criteria for Future Adoption

This ADR may be considered implemented only when all of the following are true:

- `OnChainClassifier` is orchestrator-only
- protocol literals do not exist in generic family classifiers
- raw reads occur only through `OnChainRawTransactionView`
- missing-data reasons are centralized
- current production classification is preserved unless explicitly approved
- on-chain `NEEDS_REVIEW` does not increase because of the refactor
- GMX, GLV, CoW, Euler, and other audited protocol lifecycles retain parity

## Consequences

### Positive

- safer extension path for new protocols
- smaller and reviewable change sets
- clearer boundary between generic accounting families and protocol-owned semantics
- better reuse of registry metadata without turning registry into code

### Negative

- more classes and interfaces
- higher up-front design cost
- mandatory golden-master harness before significant extraction work

### Neutral

- telemetry, pricing, and replay do not need architectural redesign because of this ADR
- clarification remains the same stage but receives more structured reasons and discovery hooks

## Alternatives Considered

### 1. Keep the monolith and only split helper methods

Rejected because it improves readability locally but does not solve ownership boundaries or protocol sprawl.

### 2. Move everything into protocol classifiers only

Rejected because this would duplicate family logic and create another monolith at the protocol layer.

### 3. Push all selectors and event topics into `protocol-registry.json`

Rejected because it would turn the registry into an ABI and lifecycle rules database, making it hard to validate and easy to corrupt.

## Follow-Up

If this ADR is accepted in the future, the next step should be a separate execution plan with:

- a golden-master harness task
- phase-by-phase extraction tasks
- parity checkpoints after every family extraction
