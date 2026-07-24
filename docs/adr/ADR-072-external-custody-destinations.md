# ADR-072: External custody destinations ("count on exit")

**Status:** Accepted
**Date:** 2026-07-20
**Scope:** Ingestion counterparty resolution (TON/Solana), normalized model capability flag, session
settings, informational custody ledger read + endpoint
**Related:** Plan `solana-ton-live-positions-metadata-boundaries` WS-5; blocker B5; ADR-052
(post-normalization capability-flag invariant), ADR-066 (per-family counterparty resolution),
ADR-067 (non-EVM conservation scope)

---

## Context

The TON "bonus program" the user reported is **Telegram Wallet "Доход" (Earn)** — a custodial,
off-chain program. Deposits are pooled to an operator wallet; the balance, daily accrual, and APY
live in the Wallet backend, keyed to the user's Telegram identity, with **no public / unauthenticated
readout** (further proven by the USDT-in / USDe-denominated-balance mismatch — the venue keeps its own
independent ledger). It therefore **cannot** be solved by an on-chain reader, and fabricating a live
position or a manual portfolio balance was rejected.

Existing mechanisms do not fit:

- **`AccountingUniverse` `EXTERNAL_VENUE` member** (`SessionSettings.externalVenues`) makes the address
  a **universe member** — excluded from Net External Capital and carrying AVCO through a
  `CounterpartyBasisPool`. That is the *wrong* semantic here: we explicitly want capital to **leave
  tracked scope** on deposit and **re-enter at market** on withdrawal.
- An on-chain balance contribution or a synthesized lending group would invent physics we cannot see.

## Decision

**Treat a user-designated custodial venue as a labeled EXTERNAL CUSTODY DESTINATION — an exchange /
vault we cannot see into. It is NOT part of the accounting universe, NOT shown in portfolio
quantity / AVCO, and its physics (balance / APY) are not tracked. We record only "put X in, took Y
out" and realize yield on exit.**

1. **User designation, never hardcoded.** The operator/pool address is supplied through
   `UserSession.SessionSettings.externalCustodyDestinations` (`{address, provider, label, networks}`),
   exposed via the session-settings PUT/GET DTOs. These entries are **deliberately excluded** from
   `AccountingUniverseSyncService` and from the universe-resync trigger (`sourceKeys`) — they are
   labeled counterparties only, never members.

2. **Attribution (ingestion).** `ExternalCustodyDestinationRegistry` resolves a counterparty peer
   against the bound session's designated destinations (family-aware: EVM lowercased, Solana
   case-preserved, TON matched on every friendly/raw canonical form). The per-family
   `CounterpartyResolver`s (TON, Solana) relabel the flow/transaction counterparty as
   `CounterpartyType.EXTERNAL_CUSTODY` and stamp the venue-neutral `custodialOffChain` capability flag
   on the normalized record.

3. **Standard external-transfer accounting.**
   - **Deposit** (funds sent to the venue) stays `EXTERNAL_TRANSFER_OUT` → capital leaves tracked
     scope, standard AVCO. No phantom balance appears.
   - **Withdrawal ("on exit")** stays `EXTERNAL_TRANSFER_IN` → new capital at market at return time;
     yield `Y − X` materializes naturally via normal external-in accounting.
   - Because `EXTERNAL_CUSTODY` is not `PERSONAL_WALLET`, the row is **never** promoted to
     `INTERNAL_TRANSFER`, and because the destination is not a universe member it produces **no**
     phantom balance — the `PortfolioConservationGate` is unaffected (a custody flow is just a normal
     external transfer; TON/SOL are additionally out of the EVM NEC scope per ADR-067).

4. **Informational custody ledger (read-only).** `CustodyLedgerQueryService` tallies actual on-chain
   in/out flows per venue and asset — `{asset, depositedQty, withdrawnQty, netQty, depositedUsd,
   withdrawnUsd}` — identified purely by the `custodialOffChain` flag (no network branching). It is
   surfaced at `GET /api/v1/sessions/{sessionId}/custody-ledger`. It is **strictly informational**:
   never included in portfolio totals, AVCO, dashboard quantity, or the accounting universe.

5. **Cross-asset is not reconciled.** The venue may return a different asset than deposited (USDT in →
   USDe out). The ledger reports raw per-asset flows only; no quantity reconciliation is attempted.

## Capability flag (WS-8 dovetail)

`custodialOffChain` is a primitive `Boolean` on the normalized record — an open semantic capability
flag stamped at normalization time (ADR-052 invariant), never a registry/resolver type on the domain
model. Read paths (custody ledger) consume the flag; they do not re-derive venue/network semantics.

## Consequences

- No on-chain reader, no manual portfolio balance, no synthesized lending group, no universe member.
- Deposits/withdrawals to the designated venue are attributed and visible in the informational ledger;
  yield is realized on exit via external-in at market.
- The mechanism is family-generic; TON and Solana resolvers are wired now (the concrete Telegram Earn
  case is TON). EVM custody destinations can be wired into `EvmCounterpartyResolver` later using the
  same registry/flag if a use case arises — EVM currently only has the *universe-member*
  `externalVenues` path, which is a different semantic.
- A sparse index (`normalized_custodial_offchain_idx`) keeps the ledger read off a collection scan.
