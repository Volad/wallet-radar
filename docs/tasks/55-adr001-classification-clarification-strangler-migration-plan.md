# ADR-001 Classification + Clarification Strangler Migration Plan

Goal:

Implement the future ADR-001 refactor safely, incrementally, and with explicit
parity checks against the current Mongo-backed production classification corpus.
The end state must fully replace the legacy monolithic on-chain
classification/clarification structure with the new orchestrator + protocol
semantics + family-classifier architecture, while preserving or explicitly
improving classification quality.

This document is the execution plan. It does not authorize a big-bang rewrite.

Primary rule-document entry point:

- [docs/normalization/README.md](../normalization/README.md)

---

## SECTION A — Decisions & Assumptions

### Context

The current on-chain classification/clarification path already produces
accounting-usable results for the active audited dataset, but:

- `OnChainClassifier` is too large and mixes unrelated concerns
- protocol-specific selector and lifecycle rules are mixed with generic family
  heuristics
- clarification eligibility and missing-data policy are spread across multiple
  code paths
- rule documentation is incomplete and not mapped one-to-one to runtime
  ownership boundaries

### Goals

1. Replace the monolithic on-chain classification path with a modular,
   strangler-safe architecture.
2. Refactor clarification so protocol/family clarification policy is explicit,
   centralized, and testable.
3. Preserve current production behavior unless a diff is explicitly approved as
   a semantic fix.
4. Ensure every migration phase is self-checking against a Mongo-derived golden
   baseline.
5. Reach a final state where legacy classification code is fully removed.

### Accepted Design Clarifications

1. Global `protocol-registry.json` remains coarse discovery metadata only.
2. Protocol-specific selectors, event topics, ABI fragments, and lifecycle maps
   may live in protocol-local resources such as:
   - `backend/src/main/resources/protocols/gmx-v2.json`
   - `backend/src/main/resources/protocols/cow.json`
   - `backend/src/main/resources/protocols/euler.json`
3. Classification and clarification rules must be documented explicitly:
   - one document per family classifier
   - one document per protocol-owned lifecycle
4. `SPAM` is treated as a dedicated normalized sink type and therefore merits a
   dedicated `SpamClassifier`.
5. `ADMIN_CONFIG` remains a reason code, not a normalized type.

### Assumptions

- Current Mongo raw corpus is the golden source for parity checks.
- No broad backfill is required for this refactor.
- Reads of raw on-chain evidence must go only through
  `OnChainRawTransactionView`.
- Bybit classification remains outside this refactor scope.

### Rule Source Hierarchy

Concrete classification and clarification rules must come from a fixed source
hierarchy. They must not be invented ad hoc during implementation.

Priority order:

1. Accepted accounting/domain contract in:
   - `docs/01-domain.md`
   - `docs/02-architecture.md`
   - `docs/03-accounting.md`
   - accepted ADRs
2. Family and protocol normalization rule documents under:
   - `docs/normalization/families/*.md`
   - `docs/normalization/protocols/*.md`
3. Current Mongo-backed production baseline:
   - `normalized_row_snapshot.ndjson`
   - aggregate baseline artifacts
   - approved semantic fixes already recorded in audit/task docs
4. Official protocol evidence:
   - protocol docs
   - protocol repos
   - verified contract semantics
5. Persisted runtime evidence available through:
   - `OnChainRawTransactionView`
   - persisted clarification evidence
   - persisted full receipt / related lifecycle evidence
6. Existing legacy code behavior
   - only as a migration input
   - never as the final authority when it conflicts with approved docs/audits

Interpretation rule:

- legacy code is a source of current behavior
- Mongo baseline is the source of current persisted output
- docs + accepted audit decisions are the source of intended behavior
- official protocol sources are the source of external truth

When those disagree, the developer must not improvise. The disagreement must be
resolved by updating the rule document and explicitly marking the resulting diff
as an approved semantic fix.

### Out of Scope

- Rewriting pricing or AVCO architecture
- Replacing Mongo collections or ingestion topology
- Genericizing every protocol on day one
- Introducing microservices or external rule engines

---

## SECTION B — Cost-Efficient Architecture Diagram (ASCII)

```text
Mongo raw_transactions / external_ledger_raw
              |
              v
    OnChainRawTransactionView
              |
              v
OnChainClassificationContextFactory
              |
              v
      ProtocolDiscoveryService
              |
              +--------------------------+
              |                          |
              v                          v
 ProtocolSemanticClassifier[]   ClarificationPolicyService
              |                          |
              v                          v
     ProtocolSemanticHints       ClarificationRequirement[]
              |                          |
              +------------+-------------+
                           |
                           v
                FamilyClassifierChain
                           |
                           v
               ClassificationDecisionMapper
                           |
                           v
               OnChainClassificationResult
                           |
                           v
              normalized_transactions

Migration Safety Rail:
  Mongo Baseline Snapshot
      + Golden Master Diff
      + Family/Protocol Fixture Packs
      + Phase Gate Reports
```

---

## SECTION C — Module Breakdown (Spring Boot Packages)

### Target Package Layout

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
  ClarificationDecision
  ClarificationPolicyService
  ClarificationReasonSupport

com.walletradar.ingestion.pipeline.classification.onchain.family
  OnChainFamilyClassifier
  SpamClassifier
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
  GmxProtocolClarificationPolicy
  GmxProtocolSupport

com.walletradar.ingestion.pipeline.classification.onchain.protocol.cow
  CowSwapProtocolSemanticClassifier
  CowSwapProtocolClarificationPolicy
  CowSwapSupport

com.walletradar.ingestion.pipeline.classification.onchain.protocol.euler
  EulerProtocolSemanticClassifier
  EulerProtocolClarificationPolicy
  EulerProtocolSupport
```

### Dependency Rules

- `OnChainClassifier` may orchestrate but may not contain protocol literals.
- Family classifiers may depend on generic classification support only.
- Family classifiers may not read protocol-local resource files directly.
- Protocol semantic classifiers may read protocol-local resources and protocol
  support code.
- Clarification policy must consume structured requirements, not raw string
  reasons.
- `OnChainRawTransactionView` is the only allowed raw read surface.

### Protocol Resource Layout

```text
backend/src/main/resources/protocols/
  gmx-v2.json
  cow.json
  euler.json
  pendle.json
  aave.json
```

Each protocol-local resource may contain:

- selector dictionary
- event topic hashes
- lifecycle event names
- protocol-specific role hints
- clarification lookup hints
- allowed terminal/request link shapes

Each resource must not contain runtime status policy or accounting rules. Those
stay in Java code plus rule docs.

---

## SECTION D — Mongo Baseline Snapshot + Index Strategy

### Collections Used

No new permanent Mongo collection is required for the refactor itself.

Golden baseline artifacts must be produced from:

- `raw_transactions`
- `normalized_transactions`
- `external_ledger_raw`
- `asset_positions`

### Golden Snapshot Strategy

Before Phase 1 starts, the developer must generate an immutable baseline package
from Mongo and store it under:

```text
results/refactor-baseline/adr-001/<timestamp>/
```

The package must include:

1. `type_status_matrix.json`
   - counts by `source`, `type`, `status`, `excludedFromAccounting`
2. `type_status_by_network.json`
   - counts by `networkId`, `type`, `status`
3. `type_status_by_protocol.json`
   - counts by `protocolName`, `protocolVersion`, `type`, `status`
4. `missing_reason_counts.json`
   - counts by `missingDataReasons[]`
5. `clarification_evidence_counts.json`
   - counts of rows carrying `clarificationEvidence`, `fullReceipt`,
     related lifecycle evidence, and protocol-specific enrichment
6. `correlation_shape_matrix.json`
   - counts by `type`, `hasCorrelationId`, `hasMatchedCounterparty`
7. `async_pair_integrity.json`
   - exact pair metrics for:
     - `LP_ENTRY_REQUEST / LP_ENTRY_SETTLEMENT`
     - `LP_EXIT_REQUEST / LP_EXIT_SETTLEMENT`
     - `DEX_ORDER_REQUEST / DEX_ORDER_SETTLEMENT`
     - `DERIVATIVE_ORDER_REQUEST / terminal derivative row`
8. `family_flow_role_matrix.json`
   - counts by `type`, `assetRole`, `direction`
9. `priceable_flow_matrix.json`
   - counts by `type`, priceable flow role, `status`
10. `unknown_review_matrix.json`
   - counts by `source`, `type`, `status`, `missingDataReasons`
11. `top_sample_hashes.json`
   - stable samples per type/family/protocol for manual spot-check
12. `gmx_family_snapshot.json`
13. `cow_family_snapshot.json`
14. `euler_family_snapshot.json`
15. `lp_family_snapshot.json`
16. `bridge_family_snapshot.json`
17. `transfer_family_snapshot.json`
18. `spam_family_snapshot.json`
19. `position_snapshot.json`
   - counts and balances by `assetSymbol`, `networkId`
20. `manifest.json`
   - timestamp, DB name, git commit, query version, SHA256 hashes for artifacts
21. `normalized_row_snapshot.ndjson`
   - one canonical line per normalized transaction row
   - keyed by normalized `_id`
   - must include at minimum:
     - `_id`
     - `txHash`
     - `source`
     - `networkId`
     - `walletAddress`
     - `type`
     - `status`
     - `protocolName`
     - `protocolVersion`
     - `excludedFromAccounting`
     - `clarificationEvidencePresent`
     - `fullReceiptPresent`
     - `missingDataReasons[]`
     - `correlationId`
     - `matchedCounterparty`
     - `flowFingerprint`
     - `priceableFingerprint`
22. `normalized_row_snapshot.csv`
   - human-readable export of the same row-level baseline
   - required for manual diff review by developers and auditors

### Row-Level Baseline Rule

Aggregate counts are necessary but not sufficient.

The authoritative migration baseline is the full row-level snapshot of all
normalized transactions. Every migration phase must therefore validate both:

1. aggregate parity
2. exact normalized-row parity

Row-level parity means:

- the same normalized `_id` set exists
- each row keeps the same type/status pair unless explicitly approved
- the same clarification flags are preserved
- the same correlation and counterparty metadata are preserved
- the same flow fingerprint is preserved unless explicitly approved

Any count-level match that hides row-level drift is considered a failed phase
gate.

### Exact `normalized_row_snapshot.ndjson` Format

Each line must be one canonical JSON object with exactly this top-level shape:

```json
{
  "_id": "txHash:networkId:walletAddress",
  "txHash": "0x...",
  "source": "ON_CHAIN",
  "networkId": "ARBITRUM",
  "walletAddress": "0x...",
  "type": "LP_ENTRY",
  "status": "PENDING_PRICE",
  "protocolName": "GMX",
  "protocolVersion": "V2",
  "excludedFromAccounting": false,
  "clarificationEvidencePresent": true,
  "fullReceiptPresent": true,
  "relatedLifecycleEvidencePresent": true,
  "missingDataReasons": [],
  "correlationId": "0x...",
  "matchedCounterparty": "0x...",
  "classificationFingerprint": "sha256:...",
  "flowFingerprint": "sha256:...",
  "priceableFingerprint": "sha256:...",
  "flowsCanonical": [
    {
      "index": 0,
      "assetSymbol": "USDC",
      "assetAddress": "0xaf88d065e77c8cc2239327c5edb3a432268e5831",
      "networkId": "ARBITRUM",
      "direction": "OUT",
      "assetRole": "TRANSFER",
      "quantity": "8.000000",
      "priceable": false,
      "counterparty": "0x31ef83a530fde1b38ee9a18093a333d8bbbc40d5",
      "continuityGroup": "REQUEST_LOCK",
      "notes": null
    }
  ]
}
```

Field rules:

- `_id` is mandatory and is the primary diff key
- `txHash` may be `null` only for rows that legitimately have no tx hash
- `protocolName` / `protocolVersion` must be explicit `null` when absent
- `correlationId` / `matchedCounterparty` must be explicit `null` when absent
- boolean clarification flags must always be present
- `missingDataReasons` must always be present, even when empty
- `flowsCanonical` must always be present, even when empty

### Exact `normalized_row_snapshot.csv` Columns

The CSV must contain one row per normalized transaction with these columns in
this exact order:

```text
_id,txHash,source,networkId,walletAddress,type,status,protocolName,
protocolVersion,excludedFromAccounting,clarificationEvidencePresent,
fullReceiptPresent,relatedLifecycleEvidencePresent,missingDataReasons,
correlationId,matchedCounterparty,classificationFingerprint,flowFingerprint,
priceableFingerprint,flowCount,priceableFlowCount
```

### Required Mongo Query Coverage

The baseline harness must at minimum query:

- normalized rows by `type`
- normalized rows by `status`
- normalized rows by `protocolName` / `protocolVersion`
- normalized rows with and without `correlationId`
- normalized rows with and without `matchedCounterparty`
- rows by clarification evidence presence
- rows by `missingDataReasons`
- rows by `flow.assetRole`
- rows by `flow.direction`
- asset position counts and symbol balances
- every normalized row by `_id` for full snapshot export

### Row Snapshot Canonicalization

The row-level snapshot exporter must canonicalize arrays and optional fields so
diffs are stable.

Required normalization for the baseline export:

- sort rows by `_id`
- sort `missingDataReasons[]`
- sort flows by deterministic key such as:
  `assetSymbol, direction, assetRole, quantity, counterparty`
- convert absent optional fields to explicit `null`
- include boolean clarification flags even when `false`
- emit stable decimal string formatting

Recommended fingerprint fields:

- `flowFingerprint`
  - deterministic hash of canonicalized flows
- `priceableFingerprint`
  - deterministic hash of only priceable flows
- `classificationFingerprint`
  - deterministic hash of:
    - `type`
    - `status`
    - clarification flags
    - `missingDataReasons`
    - `correlationId`
    - `matchedCounterparty`
    - `protocolName`
    - `protocolVersion`
    - `excludedFromAccounting`

### Canonical Flow Entry Schema

Every `flowsCanonical[]` entry must be normalized into this schema before
sorting and hashing:

```json
{
  "index": 0,
  "assetSymbol": "USDC",
  "assetAddress": "0x...",
  "networkId": "ARBITRUM",
  "direction": "IN|OUT",
  "assetRole": "BUY|SELL|TRANSFER|FEE|UNKNOWN",
  "quantity": "123.456789",
  "priceable": true,
  "counterparty": "0x...",
  "continuityGroup": "optional-string-or-null",
  "notes": "optional-string-or-null"
}
```

Canonicalization rules:

- `index` is the original persisted flow order for audit readability
- sort for hashing by:
  1. `assetSymbol`
  2. `assetAddress`
  3. `networkId`
  4. `direction`
  5. `assetRole`
  6. `quantity`
  7. `priceable`
  8. `counterparty`
  9. `continuityGroup`
  10. `notes`
  11. `index`
- addresses must be lowercased
- absent strings become explicit `null`
- booleans must remain booleans, not `"true"` / `"false"`
- decimal quantities must be emitted as stable plain strings without scientific
  notation

### Fingerprint Algorithms

All fingerprints must use:

- canonical JSON serialization
- UTF-8 bytes
- `SHA-256`
- hex output prefixed with `sha256:`

#### `flowFingerprint`

Input:

- the fully canonicalized and sorted `flowsCanonical[]`

Purpose:

- detect any flow-level drift, including non-priceable continuity drift

#### `priceableFingerprint`

Input:

- only canonical flow entries where `priceable = true`
- sorted with the same ordering rules

Purpose:

- detect drift in pricing-relevant flow semantics independently of continuity
  noise

#### `classificationFingerprint`

Input object:

```json
{
  "type": "LP_ENTRY",
  "status": "PENDING_PRICE",
  "protocolName": "GMX",
  "protocolVersion": "V2",
  "excludedFromAccounting": false,
  "clarificationEvidencePresent": true,
  "fullReceiptPresent": true,
  "relatedLifecycleEvidencePresent": true,
  "missingDataReasons": [],
  "correlationId": "0x...",
  "matchedCounterparty": "0x..."
}
```

Purpose:

- detect any row-level semantic drift even when flows happen to match

### Mandatory Row-Level Diff Outputs

Every phase rerun must emit:

1. `row_diff_summary.json`
   - counts of added / removed / changed rows
2. `row_diff_added.ndjson`
3. `row_diff_removed.ndjson`
4. `row_diff_changed.ndjson`
   - one row per `_id` with field-by-field diff
5. `row_diff_changed_by_field.json`
   - counts of changed rows by field:
     - `type`
     - `status`
     - `clarificationEvidencePresent`
     - `fullReceiptPresent`
     - `missingDataReasons`
     - `correlationId`
     - `matchedCounterparty`
     - `classificationFingerprint`
     - `flowFingerprint`
     - `priceableFingerprint`

Phase gate rule:

- if `row_diff_changed.ndjson` is non-empty, every changed row must be tagged as
  either `approved_semantic_fix` or `regression`
- no phase may proceed with unresolved changed rows

### Index Expectations

No new collection-level indexes are required for the migration plan itself, but
the harness and runtime must rely on existing read-efficient indexes. If gaps
are found, they must be addressed only after proving they are needed for:

- snapshot generation time
- clarification candidate selection
- exact lifecycle pair linking

---

## SECTION E — Data Flow and Migration Flow

### Rule Documentation Lattice (Mandatory)

Before any family extraction starts, create the permanent rule-doc structure:

```text
docs/normalization/families/
  spam.md
  non-economic.md
  swap.md
  bridge.md
  lp.md
  lending.md
  vault.md
  staking.md
  trading.md
  transfer.md
  default.md

docs/normalization/protocols/
  gmx-v2.md
  cow-swap.md
  euler.md
  pendle.md
  aave.md
```

Each family document must define:

- owned normalized types
- accepted evidence from `OnChainRawTransactionView`
- disallowed fallbacks
- interaction with clarification
- flow role expectations
- regression fixture families

Each protocol document must define:

- discovery rules
- protocol-local resources used
- request/settlement/terminal lifecycle states
- required clarification evidence
- correlation rules
- `matchedCounterparty` rules
- family classifier handoff rules

### Developer Workflow for a New or Migrated Rule

Every concrete rule implemented during the migration must follow this order:

1. Identify the rule origin
   - baseline parity rule
   - approved semantic fix from audit
   - protocol-doc-backed lifecycle rule
2. Write or update the relevant rule document first
   - family doc when the rule is family-owned
   - protocol doc when the rule is protocol-owned
3. Define the exact evidence contract
   - which fields from `OnChainRawTransactionView` are authoritative
   - whether clarification evidence is required
   - which fallbacks are forbidden
4. Define expected output
   - normalized type
   - status
   - clarification flags
   - `correlationId`
   - `matchedCounterparty`
   - flow role expectations
5. Add regression fixtures
   - focused unit fixture
   - production-like fixture where relevant
   - row-level sample from baseline for parity validation
6. Implement the runtime rule
   - protocol-local resource changes if needed
   - protocol semantic logic if needed
   - family-classifier logic if needed
7. Run row-level diff against baseline
   - if unchanged, phase proceeds
   - if changed, classify each diff as approved semantic fix or regression

No rule is considered valid if it exists only in code and cannot be traced back
to a rule document plus one of the approved source layers above.

### Phase 0 — Baseline Freeze and Harness

Deliverables:

- Mongo golden snapshot package
- immutable sample corpus list
- diff runner for baseline vs rerun output
- row-level diff runner for `_id -> classification snapshot`
- phase gate checklist
- rule-doc skeletons

Mandatory checks:

- baseline package is reproducible twice from the same DB snapshot
- manifest contains git commit + hash of every artifact
- developer can run one command to produce diff reports
- developer can run one command to produce exact row-level diff reports
- developer can isolate diffs into:
  - expected semantic fixes
  - unexpected regressions

Exit criteria:

- no extraction starts before this baseline exists

### Phase 1 — New Core Contracts, No Behavior Change

Goal:

Introduce the new abstractions without changing classification output.

Deliverables:

- `OnChainClassificationContext`
- `OnChainClassificationContextFactory`
- `ClassificationDecision`
- `ClassificationDecisionMapper`
- `ProtocolDiscoveryResult`
- `ProtocolSemanticResult`
- `ClarificationRequirement`
- `LegacyFallbackClassifier`

Rules:

- all runtime output must still come from legacy logic
- new contracts may wrap old methods, but may not fork behavior yet

Phase gate:

- full aggregate diff must be zero
- full row-level diff must be zero

### Phase 2 — Discovery Layer Extraction

Goal:

Move protocol discovery out of the legacy monolith.

Deliverables:

- `ProtocolDiscoveryService`
- discovery based on global `protocol-registry.json`
- loading of protocol-local resource files under `resources/protocols/*.json`
- no final type changes yet

Rules:

- discovery returns `0..N` protocol matches
- protocol-local resources are optional by protocol, not mandatory globally
- selectors and topics do not leak into family classifiers

Phase gate:

- zero-diff baseline on normalized output
- separate `protocol_discovery_report.json` matches legacy visible protocol tags
- row-level snapshot diff remains zero

### Phase 3 — Clarification Reason Centralization

Goal:

Unify missing-data and clarification policy before extracting families.

Deliverables:

- `ClassificationReasonCode`
- `ClarificationRequirement`
- `ClarificationDecision`
- `ClarificationPolicyService`
- migration of string reasons into centralized enums/constants

Rules:

- protocol/family classifiers emit structured reasons only
- persisted status transitions remain centralized
- clarification eligibility and query services consume structured requirements

Phase gate:

- no drift in `missingDataReasons`
- no drift in `status`
- no new `NEEDS_REVIEW` rows
- no row-level drift outside approved reason-format migration

### Phase 4 — Spam and Non-Economic Extraction

Goal:

Extract the lowest-risk classifiers first.

Deliverables:

- `SpamClassifier`
- `NonEconomicClassifier`
- family rule docs for both

Rules:

- `SPAM` is explicit
- `ADMIN_CONFIG` stays a reason code
- fallback to `UNKNOWN` remains in `DefaultClassifier`, not in `SpamClassifier`

Phase gate:

- exact match on spam counts
- exact match on non-economic counts
- no drift in downstream families
- exact row-level parity for all non-economic and spam rows

### Phase 5 — Swap and Bridge Extraction

Goal:

Extract high-volume but already well-understood generic families.

Deliverables:

- `SwapClassifier`
- `BridgeClassifier`
- bridge and swap rule docs

Rules:

- generic routed transfer fallback remains late
- bridge request/start evidence keeps precedence over transfer fallback
- explicit bridge settlement remains family-owned

Phase gate:

- zero drift on `SWAP`, `WRAP`, `UNWRAP`, `BRIDGE_OUT`, `BRIDGE_IN`
- flow-role parity maintained
- exact row-level parity for all extracted family rows

### Phase 6 — LP, Vault, and Staking Extraction

Goal:

Extract the custody-heavy families before lending/trading.

Deliverables:

- `LpClassifier`
- `VaultClassifier`
- `StakingClassifier`
- shared custody role helpers if needed

Rules:

- `LP_ENTRY/EXIT` principal legs remain continuity `TRANSFER`
- async request/settlement pairs remain exact-link aware
- `VAULT_*` stay separate from `LENDING_*`
- liquid staking remains economic where current contract requires it

Phase gate:

- exact parity for LP principal role matrices
- exact parity for async request/settlement pair counts
- exact parity for `correlationId` and `matchedCounterparty` on LP/Vault/Staking
- exact row-level parity for extracted families except approved semantic fixes

### Phase 7 — Lending Extraction

Goal:

Extract lending families after custody patterns are already isolated.

Deliverables:

- `LendingClassifier`
- lending rule doc
- protocol semantic adapters for Aave/Euler/Pendle/Morpho where needed

Rules:

- debt-marker legs remain continuity where current accounting requires it
- loop lifecycle remains composite and protocol-aware
- unresolved loop bundles may request clarification, not invent fallback swaps

Phase gate:

- exact parity for `BORROW`, `REPAY`, `LENDING_*`, `LENDING_LOOP_*`
- zero drift in loop family spot-check fixtures
- exact row-level parity for extracted lending families except approved fixes

### Phase 8 — Trading Extraction

Goal:

Extract the most protocol-heavy family only after the platform is stable.

Deliverables:

- `TradingClassifier`
- `GmxProtocolSemanticClassifier`
- `CowSwapProtocolSemanticClassifier`
- protocol rule docs for GMX and CoW

Rules:

- generic transfer fallback must never capture keeper-executed trading rows
- GMX request/open/increase/decrease/cancel lifecycle remains protocol-driven
- CoW request/settlement correlation remains protocol-driven
- clarification may fetch related lifecycle rows when current raw evidence
  requires it

Phase gate:

- exact parity or explicitly approved improvement for GMX/CoW lifecycle
- exact parity on wallet-visible cashflow reconstruction
- exact parity on `correlationId` / `matchedCounterparty`
- exact row-level parity for all non-approved trading rows

### Phase 9 — Transfer and Default Extraction

Goal:

Move final fallbacks only after all higher-confidence families are extracted.

Deliverables:

- `TransferClassifier`
- `DefaultClassifier`

Rules:

- `TRANSFER` must remain late fallback
- `UNKNOWN` must be final sink only
- no protocol literals in either classifier

Phase gate:

- zero drift in all active-lane families
- no increase in `UNKNOWN`
- full row-level diff reduced to approved fixes only

### Phase 10 — Remove Legacy Path Completely

Goal:

Delete the monolith and all temporary strangler bridges.

Deliverables:

- remove `LegacyFallbackClassifier`
- remove dead helpers that existed only for migration
- remove old classifier branches from `OnChainClassifier`
- remove deprecated tests tied only to the legacy structure

Mandatory deletion rule:

The legacy path may be deleted only after three consecutive phase-gated reruns
show:

- no unexpected diff vs baseline
- no unexpected row-level diff vs baseline
- no increase in `on-chain NEEDS_REVIEW`
- no regression in audited protocol fixture packs

---

## SECTION F — Scaling Path

### MVP (single host)

- keep modular monolith
- keep Mongo
- keep filesystem baseline artifacts
- keep no extra infrastructure

### Phase 2

If snapshot generation becomes too slow:

- optimize indexes
- add cached aggregate builders if needed
- keep artifacts on disk rather than introducing a new service

### Phase 3

Only if protocol count becomes very large:

- split protocol support packages more aggressively
- introduce generated docs index or rule registry
- still avoid microservices unless runtime ownership boundaries justify them

---

## SECTION G — Cost Analysis

### Infra Estimate

- no new paid infrastructure
- no new managed services
- no new runtime datastore

### Cost Levers

- baseline artifacts are produced from existing Mongo
- protocol-local JSON resources are static files
- golden-master diff runs locally or in CI
- clarification continues to use existing fetch path

### RPC and API Impact

- no broad backfill introduced
- targeted clarification fetches remain bounded and protocol-aware
- migration verification uses persisted Mongo evidence, not repeated RPC replay

---

## SECTION H — Risks & Mitigations

### Risk: Family extraction changes behavior silently

Mitigation:

- golden baseline package is mandatory
- every phase ends with a diff report
- every phase ends with a row-level diff report
- no family progresses without explicit gate pass

### Risk: Protocol-specific rules leak into generic family classifiers again

Mitigation:

- protocol-local resources are mandatory for protocol-owned details
- family classifiers may not import protocol packages directly

### Risk: Clarification behavior drifts from classification behavior

Mitigation:

- centralized `ClarificationRequirement`
- explicit protocol clarification policies
- family and protocol rule docs must describe clarification behavior

### Risk: `SPAM` and `UNKNOWN` semantics diverge inconsistently

Mitigation:

- `SPAM` is explicit and documented
- `UNKNOWN` remains final fallback only
- `ADMIN_CONFIG` stays a reason code

### Risk: Developer stops at partial strangler state

Mitigation:

- final success criteria require legacy path deletion
- migration is not complete until old structure is fully removed

---

## Developer Operating Rules

1. The developer must generate the baseline package before Phase 1.
2. The developer must run a full parity check after every phase.
3. The developer may not start the next phase until the current phase gate is
   green or the diff is explicitly approved as a semantic fix.
4. Every phase must update:
   - family/protocol rule docs
   - regression fixtures
   - migration checkpoint notes
5. No tx-hash hardcodes are allowed anywhere in the migration.
6. Final completion requires:
   - new classification + clarification structure live
   - old structure deleted
   - parity report attached
   - rule docs complete for implemented families/protocols

---

## Final Definition of Done

The migration is complete only when all statements below are true:

1. `OnChainClassifier` is orchestration-only.
2. All protocol-specific selectors/topics/layouts live in protocol support code
   or protocol-local resources.
3. All raw reads go through `OnChainRawTransactionView`.
4. Classification reasons and clarification requirements are centralized.
5. Family ownership is explicit and covered by rule docs.
6. Protocol-owned lifecycles are explicit and covered by rule docs.
7. Mongo golden baseline has been re-run and approved.
8. Full row-level parity is proven for every normalized transaction except
   explicitly approved semantic fixes.
9. The legacy monolithic classification path is deleted from code.
10. On-chain `NEEDS_REVIEW` does not increase because of the refactor.
11. Current accounting readiness for `pricing -> AVCO -> cost basis -> move basis`
    is preserved or explicitly improved.
