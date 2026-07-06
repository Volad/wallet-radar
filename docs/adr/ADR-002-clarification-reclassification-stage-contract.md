# ADR-002: Clarification Evidence Stage and Explicit Reclassification

Status: Accepted
Date: 2026-04-25
Owners: Accepted architecture

## Context

The on-chain clarification stage previously mixed two responsibilities:

- fetching and persisting bounded clarification evidence
- rebuilding canonical normalized rows by invoking classification from inside clarification workflows

That made the pipeline harder to audit because economic semantics could change inside a stage that is documented as evidence enrichment. The accepted v3 architecture requires production classification to use only canonical raw evidence and persisted receipt-safe clarification evidence. It also keeps `PENDING_CLARIFICATION` narrow: only rows that can benefit from bounded evidence acquisition should enter it.

## Decision

Clarification becomes an evidence-acquisition stage only.

After successful or exhausted clarification for a row with raw evidence present, the row moves to:

```text
PENDING_RECLASSIFICATION
```

An explicit on-chain reclassification stage then runs the same `OnChainClassifier` over the same `RawTransaction` contract. The classifier now sees persisted `clarificationEvidence` through `OnChainRawTransactionView` and remains the sole owner of canonical semantic decisions.

## Target Flow

```text
RAW_BACKFILL
  -> ON_CHAIN_CLASSIFICATION
  -> ON_CHAIN_CLARIFICATION
  -> ON_CHAIN_RECLASSIFICATION
  -> CANONICAL_METADATA_ENRICHMENT
  -> LINKING
  -> PRICING
  -> STAT_VALIDATION
  -> ACCOUNTING_REPLAY
```

Status flow:

```text
raw PENDING
  -> Classification
       PENDING_CLARIFICATION
       PENDING_PRICE
       CONFIRMED
       NEEDS_REVIEW

PENDING_CLARIFICATION
  -> Clarification
       PENDING_RECLASSIFICATION
       PENDING_CLARIFICATION on transient retryable failure

PENDING_RECLASSIFICATION
  -> Classification
       PENDING_PRICE
       CONFIRMED
       NEEDS_REVIEW
       PENDING_CLARIFICATION only for a new bounded requirement
```

## Responsibility Boundaries

Classification owns:

- `type`
- `flows`
- status decision after evidence is available
- `correlationId`
- `continuityCandidate`
- `matchedCounterparty`
- pricing semantics
- final `NEEDS_REVIEW` decisions for supported classification gaps

Clarification owns:

- receipt metadata fetch
- allowlisted full receipt fetch
- allowlisted related real transaction or protocol evidence fetch
- persistence of `raw_transactions.clarificationEvidence`
- clarification attempt counters and retry timing
- transition to `PENDING_RECLASSIFICATION`

Clarification must not change economic type, canonical flows, or pricing semantics.

## Concurrency Policy

The default clarification worker model is:

```text
threads = 2
batchSize = 30
```

Both values must be configurable under `walletradar.normalization.clarification`.

Workers must claim batches with a lease before fetching evidence so two lanes do not process the same row. Linking and pricing may start only when both queues are empty:

```text
PENDING_CLARIFICATION = 0
PENDING_RECLASSIFICATION = 0
```

## Consequences

- `OnChainClarificationJob` publishes completion to reclassification, not directly to linking.
- `LinkingJob` starts after reclassification and Bybit normalization gates are satisfied.
- Watchdogs and gates must count `PENDING_RECLASSIFICATION` as active classification work.
- Pricing and AVCO readiness must block on both clarification and reclassification queues.
- Existing `raw.normalizationStatus=PENDING` remains the initial classification queue. A separate `PENDING_CLASSIFICATION` normalized status is not required.

## Rejected Alternatives

- Let clarification decide `PENDING_PRICE` or `NEEDS_REVIEW`.
  Rejected because it makes clarification a second semantic classifier.

- Reuse `raw.normalizationStatus=PENDING` for reclassification.
  Rejected because it conflates initial raw normalization with canonical rebuild after persisted clarification evidence.
