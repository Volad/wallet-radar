# ADR-078: Read-model coverage guard & headline-AVCO source policy

**Status:** Accepted
**Date:** 2026-07-22
**Scope:** Portfolio read model, on-chain balance refresh (`OnChainBalanceRefreshService`), dashboard
headline AVCO / covered-quantity weighting
**Related:** ADR-017 (AVCO), ADR-045, ADR-061 (blended total-exposure AVCO series), ADR-067 (non-EVM
on-chain balances & conservation scope); plan
`docs/tasks/eth-readmodel-capture-and-ton-telegram-custody-implementation-plan.md` (Cluster A)

---

## Context

A user reported that ETH average-cost (AVCO net/market/effective/blended) rose after Solana/TON wallets
were added. An independent financial audit (`results/eth_basis.md`, `results/accounting-failure-analysis.md`)
proved this was **not** cross-asset contamination: the `FAMILY:ETH` accounting ledger was intact
(net AVCO $3,015.56, covered 3.8206 ETH; zero non-EVM wallets/contracts/symbols in the ETH pool).

The defect was in the **read model**. The dashboard headline AVCO is **covered-quantity-weighted off
`on_chain_balances`**, and the balance-refresh path (ADR-067) has three fragilities:

1. It **silently skips** a candidate on a transient `decimals()`/`balanceOf` error, and
2. it uses a **destructive `deleteBalances` → `saveAll` lifecycle** (a partial/failed fetch can erase
   survivor rows), and
3. `requiresLiveBalanceOf` candidates (forced-live lending-receipt/debt tokens) bypass the
   Ankr→BlockScout→Etherscan fallback chain and hit a **single live-RPC path with no fallback**.

The observed symptom: a still-held Base `aWETH` lot (0.7478 ETH @ ~$1,830 — the cheapest lot)
transiently vanished from `on_chain_balances`, and because the headline AVCO silently drops buckets
with no balance row, the covered-qty-weighted average jumped to ≈$3,304 (matching the reported
~$3,307/$3,324). It self-corrected on the next good capture, but the fragility is latent.

The refresh-hardening (silent-drop, provider fallback, non-destructive upsert) is internal and covered
by an ADR-067 addendum. **This ADR records the one policy change:** what the headline covered-qty
weighting does when a balance bucket is missing/errored — which changes the read-model source of truth
and therefore warrants its own decision.

## Decision

**When a ledger bucket has covered basis but its `on_chain_balances` row is missing/errored (not an
authoritative zero) and the ledger is not staler than the last good capture, the headline
covered-quantity-weighted AVCO uses the ledger-covered quantity for that bucket and raises a coverage
health flag. A single missing/errored balance row must never silently move a headline AVCO.**

1. **Missing/errored ≠ authoritative zero.** After the ADR-067-addendum hardening (no silent drop,
   fallback chain, explicit-zero writes), a bucket that *resolved* an on-chain **zero** (genuine full
   withdrawal/disposal) is distinguishable from a bucket whose balance is *missing/errored*. A real
   disposal produces a ledger DISPOSE (covered ≈ 0), so the bucket correctly drops out of holdings and
   AVCO weighting — the guard **must not** retain a genuinely sold lot.

2. **Coverage guard (missing/errored + fresh ledger).** For a bucket with covered
   `basisBackedQuantityAfter > 0` but no usable balance row, prefer the ledger-covered quantity for the
   headline covered-qty-weighted AVCO **and** raise a coverage/health flag. The guard adjusts AVCO
   *weighting* and flags only; it must **not** override the displayed on-chain quantity/valuation in
   the other direction (never overstate holdings when balance < ledger for a legitimate reason).

3. **Freshness gate.** The guard applies only when ingestion is current relative to the bucket (the
   ledger is not staler than the last good capture). If a real disposal has occurred on-chain but is
   not yet ingested, the balance is correctly 0 while the ledger still shows covered basis — the
   freshness gate prevents the guard from masking that. Fallback snapshots carry a **max-age bound**; a
   stale-beyond-bound fallback raises a flag rather than silently backfilling.

## Consequences

- A transient balance-capture miss can no longer silently move a headline AVCO; the value stays
  consistent with the accounting ledger and a health flag surfaces the coverage gap.
- A genuine full withdrawal/disposal still flows through correctly (no ghost lot, no false flag).
- The headline covered-qty weighting's source of truth for a missing/errored-but-fresh bucket shifts
  from live-balance-sourced to ledger-sourced; the accounting engine (cost basis/AVCO/replay) is
  unchanged. No re-normalization or replay is required — only the read-model refresh path.
- Symmetric acceptance is mandatory: prove both the miss case (dropped lot must not move AVCO) and the
  false-positive case (real on-chain zero must still drop the lot).

## References
- ADR-067 — non-EVM on-chain balances; addendum (same date) hardens the refresh path (A1–A3).
- ADR-061 — blended total-exposure AVCO series (headline weighting consumer).
- `results/eth_basis.md`, `results/accounting-failure-analysis.md` — Cluster A reconstruction.
