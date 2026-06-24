# ADR-032 — MULTI Counterparty FEE Exclusion

**Status:** Accepted  
**Date:** 2026-06-13  
**Amends:** ADR-010 (Flow-Level Counterparty)  
**Workstream:** WS-3a (multi-counterparty bridge coverage / MULTI root cause)

---

## Context

`FlowCounterpartySupport.applyTransactionCounterparty` derives `transaction.counterpartyAddress`
by collecting distinct `counterpartyAddress` values from all flows. When more than one distinct
address was found, it stamped `MULTI`.

**The defect:** FEE legs (`NormalizedLegRole.FEE`) carry a synthetic `UNKNOWN:NETWORK_FEE`
pseudoparty. Before this ADR, FEE legs were included in the distinct-address set. A plain
single-recipient ERC20 send with a gas-fee leg therefore produced two distinct counterparty
values (`0xrecipient` + `UNKNOWN:NETWORK_FEE`), triggering MULTI even though only one real
counterparty exists.

This was the root cause of 44 own-wallet transfers and 14 external-recipient transfers carrying
`counterpartyAddress=MULTI` instead of their concrete recipient address.

---

## Decision

Amend `applyTransactionCounterparty` to **exclude** the following from the distinct-address set
before the size-2 → MULTI check:

1. **FEE legs** (`flow.getRole() == NormalizedLegRole.FEE`)
2. **Synthetic placeholders** (any `counterpartyAddress` starting with `UNKNOWN:`, case-insensitive)

### Invariants after ADR-032

| Transaction shape | Result |
|---|---|
| Single-recipient transfer + FEE leg | Concrete recipient address (not MULTI) |
| Genuine multi-recipient swap (2+ real counterparties) | MULTI |
| All flows synthetic/FEE only | No `counterpartyAddress` set |

**MULTI = genuine multi-principal only** — two or more distinct, non-synthetic, non-fee
counterparty addresses in the same transaction.

---

## Consequences

- **Root cause fix:** forward-going normalization no longer produces false MULTI on single-recipient
  transfers.
- **Legacy repair (WS-3b):** already-normalized rows with MULTI from FEE-contamination are repaired
  by idempotent clarification passes in `LinkingBatchProcessor` (see
  `04-clarification-reclassification.md` WS-3b section).
- **Conservation-neutral:** counterparty is metadata only; no cost basis, PnL, or conservation gate
  values change from this amendment.
- **No double-count risk:** the WS-3b passes are idempotent (query only matches on the "wrong"
  pre-repair state).

---

## Alternatives considered

- **Filter only FEE legs, keep UNKNOWN:* addresses:** rejected because `UNKNOWN:*` placeholders
  are equally synthetic and equally disqualify a row from having a known counterparty. Including
  them in the MULTI set would still produce false MULTI for transactions where the only
  non-synthetic address is from a bridge router.
- **Keep MULTI and repair downstream:** rejected — the root cause is in normalization time; fixing
  it at the source is cheaper and avoids cascading repair logic.
