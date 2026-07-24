# ADR-079: Telegram-Wallet custodial-operator registry & TON custody attribution

**Status:** Accepted
**Date:** 2026-07-22
**Scope:** TON counterparty resolution (clarification), config-plane operator registry
**Related:** ADR-072 (external custody destinations — the accounting model this reuses), ADR-059
(counterparty hints config plane), ADR-066 (per-family counterparty resolution & program-id registry),
ADR-064 (TON acquisition); plan
`docs/tasks/eth-readmodel-capture-and-ton-telegram-custody-implementation-plan.md` (Cluster B)

---

## Context

A user funded his "Ton Defi" wallet from his own Telegram Wallet balance. On-chain, the inbound
20.548366 TON arrived from `0:DD6FF02C…` and was labeled `EXTERNAL_TRANSFER_IN` / `UNKNOWN_EOA`. The
audit (`results/protocol-rule-pack.md`, Telegram-Wallet rule pack) proved `0:DD6FF02C…` and
`0:023895AE…` are **Telegram-Wallet custodial hot wallets** (`wallet_highload_v3r1`, tonapi name
"Wallet in Telegram", ~208k TON pooled); the app-shown "your TG-Wallet address" `UQDdb_As…` encodes to
exactly `0:DD6FF02C…` (a shared custodial address, not a personal wallet).

ADR-072 already established the correct accounting for such custodial venues (`EXTERNAL_CUSTODY`
counterparty type + `custodialOffChain` capability flag; deposit stays `EXTERNAL_TRANSFER_OUT`,
withdrawal stays `EXTERNAL_TRANSFER_IN`; never promoted to `INTERNAL_TRANSFER`; no phantom balance;
informational custody ledger). But ADR-072 attribution is driven by **per-session user-designated**
`externalCustodyDestinations` — the user must supply the operator address. Telegram-Wallet operator
addresses are shared, well-known, and not something a user knows, so they fall through to
`UNKNOWN_EOA`.

The audit further proved the current accounting is **already financially correct** for these flows
(booked `CARRY_IN` at market ≈ TON pool AVCO $1.509, `$0` phantom P&L); only the **counterparty label**
is wrong. This is a clarification/attribution fix, not a cost-basis change.

## Decision

**Add a maintained, config-seeded, global registry of known custodial-operator addresses (starting
with Telegram Wallet on TON). The TON counterparty resolver consults it to attribute matched peers as
`EXTERNAL_CUSTODY` (reusing ADR-072's model and flag), with a human label. Detection is
registry-deterministic; external label services are discovery aids only, never on the pipeline path.**

1. **Global registry, config-plane.** A config-seeded registry (mirroring `TonProtocolRegistry` /
   ADR-059 counterparty hints), keyed by `TonAddressCanonicalizer`-canonical operator address, mapping
   `address → {provider="Telegram Wallet", type=EXTERNAL_CUSTODY}`. Seeded with `0:DD6FF02C…`,
   `0:023895AE…`. This is distinct from ADR-072's per-session `externalCustodyDestinations` (which
   stays for user-designated venues); the two are consulted together, registry as a global default.

2. **Attribution reuses ADR-072.** On a registry match, the TON `CounterpartyResolver` relabels the
   flow/transaction counterparty as `CounterpartyType.EXTERNAL_CUSTODY` with label "Telegram Wallet"
   and stamps `custodialOffChain`. Because `EXTERNAL_CUSTODY` is not `PERSONAL_WALLET`/`CEX`, the row
   stays `EXTERNAL_TRANSFER_IN/OUT` (never `INTERNAL_TRANSFER`) and keeps its existing `CARRY_IN`/
   `CARRY_OUT` basis effects and `$0` phantom P&L — no cost-basis, replay, or re-normalization change.

3. **Deterministic source of truth; no runtime external label lookup.** The registry is the sole
   runtime authority. tonapi's account `name`/`interfaces` and the `wallet_highload_v3r1` interface are
   **offline discovery aids** used to populate/extend the registry — never queried on the per-row
   pipeline path or in GET handlers (no tonapi client exists today; the stored TonCenter payload
   carries neither peer `interfaces` nor `name`). If a runtime tonapi lookup is ever added, it must be
   Caffeine-cached, long-TTL, and advisory-only (registry always wins).

4. **Conservative defaults & negative cases.** The highload interface alone is **never** sufficient for
   any custodial label. A highload operator neither in the registry nor resolvable offline stays
   `UNKNOWN_EOA` — never guessed as "Telegram". Existing deterministic classifiers keep priority:
   Bybit highload hot wallets → `AccountingUniverseService` `EXCHANGE_ACCOUNT`; TON DEX routers
   (Ston.fi/DeDust/Omniston) → `TonProtocolRegistry` family `DEX`.

5. **Scope boundary.** This covers **on-chain** deposits/withdrawals to/from the Telegram-Wallet pooled
   operator. The **off-chain** Telegram "Доход"/Earn balance (ADR-072 context) remains out of scope —
   it produces no on-chain transaction and is not tracked. The informational custody ledger (ADR-072)
   naturally includes the newly-attributed flows.

## Consequences

- Telegram-Wallet on-chain custody deposits/withdrawals are attributed as `EXTERNAL_CUSTODY`
  "Telegram Wallet" without user configuration; the "unknown EOA" mislabel is gone.
- No AVCO/quantity/P&L change (attribution-only); TON pool AVCO ≈ $1.509 and covered qty (~70.37 TON)
  are invariants of the change; the USDT leg is handled identically.
- Detection is deterministic and offline-safe (registry-only), so re-clarification is idempotent.
- Extending coverage to other custodial operators (other CEX hot wallets, other chains) is a registry
  seed addition, not a code change — provided the negative-case guard (interface alone insufficient)
  holds.

## References
- ADR-072 — external custody destinations (accounting model, `custodialOffChain`, custody ledger).
- ADR-059 — counterparty hints config plane. ADR-066 — per-family counterparty resolution.
- `results/protocol-rule-pack.md` — Telegram-Wallet custodial corridor rule pack (detection + negatives).
