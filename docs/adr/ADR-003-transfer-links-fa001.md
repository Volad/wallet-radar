# ADR-003 — Transfer links and FA-001 accounting corridor (target state)

## Status

Accepted (target). **Incremental implementation:** session-scoped internal transfer pairing, Bybit corridor repair hardening, registry-backed bridge inbound correction, and dashboard inclusion of `BYBIT:*` positions are implemented first; the dedicated `transfer_links` Mongo collection remains the **documented contract** for the next increment.

## Context

Financial audit **FA-001 R3** (`cycle-autorun/cycle-data/cycle/1/results/financial_audit_report.md`) identifies three deterministic defects:

- **D1** — Same-session cross-wallet transfers classified as priced `EXTERNAL_TRANSFER_*` (SELL/BUY) instead of continuity transfer.
- **D2** — On-chain ↔ Bybit corridor: on-chain leg remains SELL/BUY while a Bybit shadow row (or extract event) proves same-owner custody move.
- **D3** — Hyperlane / LI.FI–routed USDC delivery misclassified as `VAULT_WITHDRAW` when the interacted contract is a known bridge endpoint.

The audit prescribes a reversible **`transfer_links`** collection and cost-basis joins on it. The codebase already had **continuity replay** (`shouldTreatAsContinuityTransfer` + `TransferReplayHandler`) and **pair promotion** (`InternalTransferPairLinkService`, `BybitTransferContinuityRepairService`); the first implementation increment aligns those paths with FA-001 (session wallet authority, tighter Bybit pairing, registry bridge promotion, dashboard scope) before introducing persisted `transfer_links`.

## Decision

1. **Authoritative D1 wallet set (FA-001):** `user_sessions.wallets[].address` must be sufficient to admit same-tx internal pairing **without** requiring a row in `accounting_universes` when both addresses belong to the same session.
2. **D2:** Keep shadow-row primary pairing; tighten quantity tolerance toward FA-001 (**≤ 5×10⁻⁴** relative); after a successful pair, **clear priced fields** on the on-chain principal flow and align roles with continuity semantics so replay does not book phantom realised PnL.
3. **D3:** Register known bridge endpoints in `protocol-registry.json` and apply a **deterministic post-classification correction** when the classifier yields `VAULT_WITHDRAW` (or similar) but the interacted contract is registry-marked as **BRIDGE_IN**.
4. **`transfer_links` (next increment):** Persist links per audit §4.3 with idempotent writers after counterparty resolution; cost-basis reads `transfer_links` for effective continuity. Until then, continuity remains carried by normalized metadata (`INTERNAL_TRANSFER`, correlation, `continuityCandidate`) plus replay coercion — **not** by hash-keyed production exceptions.

## Consequences

- Full audit §4 reversibility requires the new collection and replay changes in a follow-up task.
- GET dashboard remains snapshot-only; Bybit positions are derived from **ledger state** when `on_chain_balances` has no `BYBIT:*` row.

## References

- FA-001 R3: `cycle-autorun/cycle-data/cycle/1/results/financial_audit_report.md`
- Linking layers: `docs/05-linking-and-protocol-name.md`
