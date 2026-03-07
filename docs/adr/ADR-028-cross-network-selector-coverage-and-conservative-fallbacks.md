# ADR-028: Cross-Network Selector Coverage and Conservative Fallbacks

**Date:** 2026-03-06  
**Status:** Accepted  
**Deciders:** Product Owner, Business Analyst, System Architect, Tx Classification Auditor

---

## Context

WalletRadar classification is flow-first, but selector/function context is required for several no-log and ambiguous-flow paths:

- lending variants (Aave V3 + gateway paths),
- bridge/relay intent paths,
- swap fallback in aggregator-like calls,
- permission-only calls (`permit`/delegation).

Recent audit found that selector coverage is uneven across supported EVM networks, which increases risk of:

- semantic drift (`LEND_*`/`BORROW`/`REPAY` -> `EXTERNAL_*`),
- avoidable `NEEDS_REVIEW` noise for non-economic approval calls,
- inconsistent fallback behavior in no-log/aggregator transactions.

---

## Decision

1. Expand selector coverage in classifiers where selector context is explicitly used:
   - `LendClassifier` (Aave V3/gateway + multicall variants),
   - `BridgeCallClassifier` (relay/intent selector hints),
   - `TransferClassifier` (`isLikelySwapCall` selector hints),
   - `ApprovalClassifier` (permit/delegation selector variants).
2. Keep classification conservative:
   - selector hint is necessary context, but flow evidence remains authoritative for final economic type.
3. Preserve overlap safety:
   - dispatcher event priority remains unchanged (`LEND_* > SWAP_* > EXTERNAL_*`),
   - no new event type is introduced.
4. Add regression tests per selector family and mandatory invariants:
   - determinism,
   - sign rules,
   - no synthetic-native lend-underlying selection.

---

## Consequences

### Positive

- Better multi-network semantic consistency for lending and bridge/relay transactions.
- Lower classification noise and fewer false fallbacks.
- Minimal blast radius: additive selector coverage, no accounting model change.

### Trade-offs

- Selector lists require maintenance as protocols evolve.
- Over-broad selector lists can increase false positives if not guarded by flow predicates.

---

## Implementation Notes

- Source audit: `docs/reviews/2026-03-06-cross-network-selector-coverage-audit.md`
- Delivery task: `docs/tasks/27-cross-network-selector-coverage-and-fallback-hardening.md`
- Apply with test-first regression fixtures where possible.

---

## References

- ADR-019 (heuristic swap detection)
- ADR-026 (EVM explorer-first ingestion v2)
- ADR-027 (configurable synthetic native contracts)
