# ADR-080: On-chain-balance LP closure cross-check invariant

**Status:** Accepted
**Date:** 2026-07-22
**Scope:** LP read model / verification (`SessionLpQueryService.resolveClosed`), on-chain balance
refresh candidate universe (`OnChainBalanceRefreshQueryService`), costbasis-side LP-receipt
on-chain-closure resolver, EVM CL-NFT terminal-close signal.
**Related / cross-references:** ADR-067 (non-EVM on-chain balances) + its 2026-07-22 addendum
(balance-capture hardening: no silent drop, provider fallback, non-destructive per-`_id` upsert,
explicit-zero writes), ADR-078 (read-model coverage guard; missing/errored ≠ authoritative zero),
ADR-075 (Solana concentrated-liquidity full-close residual write-off — the Solana CL closure
authority, unchanged here), ADR-077 (Velodrome/Aerodrome v2 fungible gauge identity + staked-balance
valuation), ADR-054 (`FAMILY:LP_RECEIPT` continuity identity), ADR-081 (LP correlation-id granularity
& staked-wrapper receipt linking — the link-based primary closure this ADR cross-checks).

---

## Context

Open/closed for an LP position is decided today purely from **internal** signals: the Solana
concentrated-liquidity capability + snapshot (ADR-075), a terminal `LP_EXIT_FINAL`, a drained
`lp_receipt_basis_pools` (`fullyExited()` short-circuit), or a net-quantity heuristic. None of these
is anchored to the wallet's **actual on-chain balance** of the LP receipt / position NFT. The
`financial-logic-auditor` LP phantom-open cycle (2026-07-22, universe `df5e69cc-…`) confirmed that
`on_chain_balances` holds **no** LP-receipt rows at all — `PENDLE-LPT`, `eqbPENDLE-LPT`, `MLP`,
Uniswap/Pancake CL NFTs and Velodrome staked gauge LP are all absent — so closure can never be
cross-checked against ground truth. This makes closure fragile-by-construction: it depends on
classification + linking + replay draining the basis pool in the right recompute order.

Two hard problems block a naïve "wallet balance 0 ⇒ closed" rule (all three Phase-3 reviewers):

1. **Absence ≠ zero (capture universe).** The refresh candidate universe
   (`OnChainBalanceRefreshQueryService`) only emits candidates for asset identities with a non-zero
   net flow. A **cleanly burned** LP receipt nets to zero, so it produces **no** candidate and hence
   **no** balance row — indistinguishable from a capture miss. Without an explicit authoritative-zero
   row, "no row" cannot safely mean "closed".
2. **Staked receipts read wallet-zero while live (effective family balance).** A Pendle LP receipt
   staked into Equilibria (`eqbPENDLE-LPT`) / Penpie (`pnpPENDLE-LPT`), or a Velodrome LP token staked
   into a gauge, has wallet `balanceOf == 0` while the position is **live** (the tokens are held by the
   booster / gauge / farm). A naïve "wallet balance 0 ⇒ closed" would **false-close every live staked
   position**.

## Decision

Adopt a **link-based-primary, on-chain-cross-check-secondary** closure strategy (architect §F). The
on-chain-balance rule is a **flagged coverage guard**, **not** the primary authority: it only ever
**closes** a position on an **authoritative summed-family zero**, and otherwise raises a per-position
coverage flag. It never forces a position OPEN and never fabricates an open.

### Invariant (one-directional, top of `resolveClosed`)

At the **top** of `SessionLpQueryService.resolveClosed`, before the snapshot-open and
concentrated-open branches: **an authoritative summed-family on-chain zero ⇒ CLOSED**, overriding
snapshot-open / concentrated-open. This is the durable closure invariant; it never flips a position to
OPEN.

### Effective (summed-family) balance — the critical trap

The closure balance is the **effective summed receipt family**, not the bare wallet balance:

```
effectiveBalance = walletBaseReceiptBalance
                 + stakedWrapperBalance (eqb / pnp)
                 + gauge/farm stakedBalance
```

all merged onto the same `accountingIdentity` via the existing `ProtocolLockedBalanceProvider` SPI
(already used for Jupiter Lend receipt-less collateral, ADR-067 WS-3). A non-zero staked / wrapper /
gauge balance keeps the position **OPEN**.

### Candidate-universe extension (precondition A — absence≠zero)

Extend the balance-refresh candidate set to emit a candidate for **every LP-receipt identity known
from `lp_receipt_basis_pools`** (join on `walletAddress` / `networkId` / `assetIdentity`), independent
of net-flow, so a burned/closed receipt yields an **explicit authoritative-zero row** distinct from
absence. This rides the ADR-067-addendum hardened refresh path (no silent drop, non-destructive
per-`_id` upsert, explicit-zero writes). GET stays **zero-RPC** — the reads run only on the background
refresh; the read path consumes persisted `on_chain_balances`.

### CL-NFT keying (precondition B — no `balanceOf`)

EVM concentrated-liquidity NFT closure does **not** use `balanceOf`; use `ownerOf(tokenId)`
(revert / `0x0` when burned) or `positions(tokenId).liquidity == 0`, keyed by **`(contract,
tokenId)`** to avoid `_id` collision across multiple positions in the same NFPM. The
`Transfer → 0x0` (burn) / `positions(tokenId).liquidity == 0` signal is promoted to a **primary**
terminal `LP_EXIT_FINAL` for CL NFTs — the EVM analogue of ADR-075's Solana rent-reclaim signal (see
ADR-075 for the Solana authority, which stays unchanged). Solana concentrated (DLMM/CLMM) closure
stays entirely on ADR-075.

### Tri-state resolver (costbasis-side layering)

The "authoritative-zero | missing/errored | nonzero (family-summed)" decision lives in a small
**costbasis-side** LP-receipt on-chain-closure resolver keyed via `lp_receipt_basis_pools`
(`lpCorrelationId → {wallet, network, assetIdentity}`). `resolveClosed` consumes the tri-state; the
resolver keeps `resolveClosed` free of Pendle/Meteora/Velo branching (architect C6).

### Miss-vs-zero symmetry (mandatory, reuses ADR-078)

A **missing/errored** family balance must **not** be treated as zero. Missing/errored + fresh ledger ⇒
keep the existing internal determination **and** raise a per-position coverage flag on the LP view /
health (architect C8). Both directions are acceptance-mandatory: a burned receipt closes; a simulated
missing capture does **not** close a genuine open (Uniswap CL `…5527422`, GMX `weth-usdc` stay OPEN)
and raises the flag.

### Freshness gate (zero⇒closed direction only)

The authoritative zero must be at least as fresh as the position's latest LP entry / adjust. A stale
zero captured **before** a re-entry must **not** close a re-opened position (reuses the ADR-078
freshness discipline). Fallback (`captureFallback`) snapshots are treated as missing/errored, never as
authoritative zero.

### Dust tolerance

An effective balance below a sub-dust threshold counts as an authoritative zero ⇒ CLOSED, consistent
with ADR-075's basis-dust handling. Above the threshold ⇒ OPEN (partial exit stays open).

## Consequences

- Closure becomes robust-by-construction for fungible LP receipts (EVM Pendle + Meteora DAMM MLP) and
  EVM CL NFTs, without a naïve balance rule ever false-closing a live staked position.
- A burned receipt now writes an explicit authoritative-zero balance row (distinct from a capture
  miss), so "closed" is anchored to ground truth.
- A transient capture miss can never silently close a genuine open position; it raises a coverage flag
  instead (symmetry with ADR-078).
- `unknown` LP status is eliminated for any position with a captured effective on-chain balance
  (definite open|closed); residual `unknown` only for uncaptured/errored balances, and flagged.
- Cost (architect §E): the candidate-universe extension + per-tokenId `ownerOf`/`positions` reads +
  gauge/wrapper staked reads run on the **background refresh** (batched via the ADR-067 provider chain
  + the ADR-077 `DexGaugePoolResolver` cache), never on GET.
- Simpler-alternative fallback (architect §F): if the candidate-universe + family-sum proves too
  costly/risky, ship the link-based closure (ADR-081 + CL-NFT burn) as the authority and keep this
  cross-check flag-only; the phantom-open cases still close without the new capture universe.

## Alternatives considered

- **Naïve "wallet balanceOf == 0 ⇒ closed".** Rejected: false-closes every live staked position
  (Equilibria/Penpie/gauge) and mis-reads a capture miss as a disposal.
- **Force-close on absence of a balance row.** Rejected: absence ≠ zero; only an explicit
  authoritative zero closes.
- **Make the on-chain rule the primary authority.** Rejected: correct link/quantity draining
  (ADR-081) + terminal `LP_EXIT_FINAL` (ADR-075 / CL-NFT burn) are deterministic and require no new
  capture universe; the on-chain check is the secondary coverage guard.
- **Post-hoc sweep/repair over `lp_receipt_basis_pools`.** Rejected per backend standards: fix the
  earliest wrong stage (classification/linking, ADR-081) and rebuild downstream from rerun.

## Verification

- Unit: LP-receipt on-chain-closure resolver — authoritative-zero ⇒ closed; missing/errored ⇒ keep
  internal + flag; nonzero ⇒ open; staked-family (wallet 0 + staked > 0) ⇒ open; partial (nonzero
  effective) ⇒ open; re-entry after a stale zero ⇒ open (freshness); dust ⇒ closed; per-wallet
  closure (wallet A closed, wallet B open on the same pool).
- Candidate-universe: a burned LP receipt (net flow 0) still emits a candidate from
  `lp_receipt_basis_pools`, producing an explicit zero row.
- End-to-end (Phase 6, `--skip-frontend`): the four reported example txs stay CLOSED; a simulated
  missing capture does not close Uniswap CL `…5527422` / GMX `weth-usdc`; no `unknown` for any
  captured-balance position.
