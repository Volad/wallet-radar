# ADR-009: Reconciliation Tolerance and 2-Year Rule

**Date:** 2025  
**Status:** Accepted  
**Deciders:** Solution Architect

---

## Context

Reconciliation compares **on-chain balance** (from `on_chain_balances`) with **derived quantity** (from economic events / `asset_positions.quantity`) per (wallet, network, asset). We need:

1. A **tolerance** to decide when quantities are considered equal (MATCH vs MISMATCH).
2. A rule for when to show **showReconciliationWarning** (suggest adding manual compensating transaction) — only for "young" wallets where we expect the 2-year history to explain the full balance.

---

## Decision

### 1. Tolerance ε (absolute only in MVP)

- **Type:** **Absolute** difference only (no relative tolerance in MVP).
- **Default value:** `ε = 10^-8` (0.00000001). Same units as quantity (e.g. 18 decimals for typical ERC-20).
- **Config:** e.g. `walletradar.reconciliation.tolerance=1e-8` (or equivalent in code as `BigDecimal`).
- **Comparison rules:**
  - **MATCH** ⟺ on-chain data exists for (wallet, network, asset) and `|onChainQuantity − derivedQuantity| ≤ ε`.
  - **MISMATCH** ⟺ on-chain data exists and `|onChainQuantity − derivedQuantity| > ε`.
  - **NOT_APPLICABLE** ⟺ no row in `on_chain_balances` for (wallet, network, asset).

Backend and tests must use the same ε from config or default.

### 2. “Young” history (within 2 years)

- **Definition:** For (walletAddress, networkId), history is **young** iff:
  - There is at least one event in `economic_events` for that pair, and
  - **min(blockTimestamp)** over those events **≥ now − 2 years** (oldest event is not older than 2 years).
- **showReconciliationWarning:**
  - `showReconciliationWarning === true` ⟺ `reconciliationStatus === MISMATCH` **and** history for (wallet, network) is young.
  - Otherwise `showReconciliationWarning === false` (no prompt to correct).
- **Special cases:**
  - No on-chain balance for the position → NOT_APPLICABLE, no warning.
  - History not young (oldest event &lt; now − 2 years) → status remains MATCH or MISMATCH by ε, but **showReconciliationWarning = false** (correction not required).
  - No events at all for (wallet, network) → treat as not young; **showReconciliationWarning = false**.

---

## Consequences

- **01-domain.md** and **03-accounting.md** (or Reconciliation section) reference this ADR and state ε (absolute, default 1e-8) and the young-history rule.
- Implementation and tests use a single config/key for ε and the same formula for “young” so behaviour is consistent.
