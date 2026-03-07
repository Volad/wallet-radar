# ADR-029: Classification/Pricing Status Split and Native Wrap Taxonomy

**Date:** 2026-03-07  
**Status:** Accepted  
**Deciders:** Product Owner, Business Analyst, System Architect, Tx Classification Auditor

---

## Context

Audit `2026-03-07-raw-vs-normalized-mongo-audit` found two structural issues:

1. `normalized_transactions.status=NEEDS_REVIEW` is overloaded:
   - true classification uncertainty,
   - pricing-only unresolved cases.
2. Native wrap/unwrap (`deposit()`/`withdraw(uint256)` on wrapped-native contracts) is represented as `SWAP`,
   which can create accounting side-effects in AVCO/PnL semantics.

Additionally, Aave gateway native lend paths and protocol-overlap cases require conservative precedence rules.

---

## Decision

1. Keep legacy orchestration field `status` for backward compatibility.
2. Add two independent diagnostic fields:
   - `classificationStatus` (`CONFIRMED`, `NEEDS_REVIEW`)
   - `pricingStatus` (`NOT_REQUIRED`, `PENDING`, `RESOLVED`, `UNRESOLVED`)
3. Add explicit wrap taxonomy:
   - economic event level: `WRAP`, `UNWRAP`
   - normalized type level: `WRAP`, `UNWRAP`
4. For wrap/unwrap:
   - represent as conversion, not market swap;
   - do not require swap invariants;
   - do not produce realised PnL side-effects.
5. Preserve conservative classifier precedence:
   - lend-context guard must suppress LP fallback for known lend selectors/protocols.

---

## Consequences

### Positive

- Review signal quality improves: pricing issues no longer counted as classification uncertainty.
- Accounting semantics for native wrap/unwrap become explicit and safer.
- Backward compatibility preserved through existing `status`.

### Trade-offs

- Additional enum fields and migration/replay logic required in pipeline jobs and APIs.
- Reporting clients must switch to new status fields for precise review metrics.

---

## Implementation Notes

- Requirement source: `docs/tasks/28-raw-vs-normalized-audit-remediation.md`
- Related tasks: `T-060..T-066`
- Existing status pipeline ADR remains valid for orchestration: `ADR-025`.

---

## References

- `docs/reviews/2026-03-07-raw-vs-normalized-mongo-audit.md`
- `ADR-025-normalized-transactions-status-pipeline.md`
- `ADR-028-cross-network-selector-coverage-and-conservative-fallbacks.md`
