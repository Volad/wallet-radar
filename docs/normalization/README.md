# Normalization Rules

This directory contains the explicit rule documents for the current
classification + clarification structure described in:

- [ADR-001](../adr/ADR-001-onchain-classification-strangler-refactor.md)
- [Migration Plan](../tasks/55-adr001-classification-clarification-strangler-migration-plan.md)

These documents are the rule source for migration work. Runtime code must not
invent rules ad hoc when they are not yet written here.

## Three-Layer Protocol Contract

Protocol ownership is intentionally split across three artifacts:

1. `protocol-registry.json`
   - address-level truth
   - answers `what contract is this`
   - owns protocol / version / family / role / confidence
2. `backend/src/main/resources/protocols/*.json`
   - protocol-level runtime profile
   - answers `what stable technical markers does this protocol use`
   - owns protocol-wide selector groups, event-name groups, clarification hints,
     and stable asset markers
3. `docs/normalization/protocols/*.md`
   - human rule docs
   - answers `how and why do we normalize this protocol`
   - owns evidence rules, clarification rules, correlation rules, and disallowed
     fallbacks

Runtime code must not blur these layers back together.

## Rule Source Hierarchy

Rule authority is resolved in this order:

1. accepted domain and accounting contract in:
   - [00-context.md](../00-context.md)
   - [01-domain.md](../01-domain.md)
   - [02-architecture.md](../02-architecture.md)
   - [03-accounting.md](../03-accounting.md)
   - accepted ADRs
2. family and protocol rule documents in this directory
3. current Mongo-backed normalized baseline
4. official protocol docs, repos, and verified contract semantics
5. persisted runtime evidence available through `OnChainRawTransactionView`
6. previously approved baseline behavior only as compatibility input

If two sources disagree, the developer must update the relevant rule document
and record the resulting diff as either:

- baseline parity
- approved semantic fix
- regression

## Directory Layout

### Families

- [Spam](families/spam.md)
- [Non-Economic](families/non-economic.md)
- [Swap](families/swap.md)
- [Bridge](families/bridge.md)
- [LP](families/lp.md)
- [Lending](families/lending.md)
- [Vault](families/vault.md)
- [Staking](families/staking.md)
- [Trading](families/trading.md)
- [Transfer](families/transfer.md)
- [Default](families/default.md)

### Protocols

- [GMX V2](protocols/gmx-v2.md)
- [CoW Swap](protocols/cow-swap.md)
- [Resolv](protocols/resolv.md)
- [Euler](protocols/euler.md)
- [Aave](protocols/aave.md)
- [Uniswap](protocols/uniswap.md)
- [Pendle](protocols/pendle.md)
- [Velodrome](protocols/velodrome.md)
- [LI.FI](protocols/li-fi.md)
- [Bybit](protocols/bybit.md)

## How To Use These Documents

Before implementing a new classifier rule:

1. identify whether the rule is family-owned or protocol-owned
2. update the relevant rule doc first
3. define authoritative evidence from `OnChainRawTransactionView`
4. define clarification requirements
5. define expected normalized output
6. add regression fixtures
7. run row-level baseline diff

No rule is considered valid if it exists only in code.

## Shared Requirements

Every family or protocol rule document should state:

- scope
- owned normalized types
- authoritative evidence sources
- clarification policy
- `correlationId` rules
- `matchedCounterparty` rules
- disallowed fallbacks
- baseline and regression anchors

## Current Runtime Compatibility Notes

- `MANUAL_COMPENSATING` is out of scope for on-chain classifier ownership. It is
  a manual accounting artifact, not a family-classifier output.
- `SpamClassifier` is now a dedicated runtime owner. Any spam-like row that
  still resolves as `UNKNOWN` must be treated as an explicit documented
  compatibility case, not an implicit migration state.
- `ADMIN_CONFIG` remains a compatibility output type in current runtime even
  though the longer-term architectural direction prefers reason-code ownership
  over family-type ownership.
