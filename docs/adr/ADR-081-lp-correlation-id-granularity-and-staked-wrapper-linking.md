# ADR-081: LP correlation-id granularity & staked-wrapper receipt linking

**Status:** Accepted
**Date:** 2026-07-22
**Scope:** LP correlation-id assignment (Pendle + Meteora DAMM), staked-wrapper → base-receipt
linking, LP-receipt movement typing (non-priced TRANSFER), LP read-model key parsing
(`SessionLpQueryService.derivePairFromCorrelationId` + `PositionAccumulator`).
**Supersedes:** ADR-023 **D3** (symbol-only Pendle key + `EQB`-prefix stripping into one
`pendle-lp:{network}:pendle-lpt` slug).
**Amends:** ADR-047 **item 4** (the hard-coded `correlationId=pendle-lp:mantle:pendle-lpt` assignment
on the Equilibria `zapOutV3SingleToken` exit).
**Related:** ADR-018 (LP protocol-family materialization), ADR-054 (`FAMILY:LP_RECEIPT` continuity
identity; per-asset AVCO), ADR-070/ADR-075 (Solana LP enrichment / concentrated full-close),
ADR-077 (v2 fungible gauge identity), ADR-080 (on-chain-balance closure cross-check).

---

## Context

The `financial-logic-auditor` LP phantom-open cycle (2026-07-22, universe `df5e69cc-…`) traced two
upstream classification/linking defects that make LP closure fragile:

### LP2 — EVM Pendle id collision + wrapper exit

ADR-023 D3 deliberately strips the Equilibria `EQB` prefix so `eqbPENDLE-LPT → pendle-lpt`, and keys
the correlation on the **symbol only**: `pendle-lp:{network}:{lpTokenSymbol}`. This collapses **two
wallets × multiple Pendle markets** into a single aggregate id. Symbol-net `PENDLE-LPT` is
`+0.4450 > 0` because the terminal exit (`0xf7f8…`, Equilibria `zapOutV3SingleToken`) burns
**`eqbPENDLE-LPT`** and returns cmETH — the base `PENDLE-LPT` symbol never appears in the exit, so the
exit is never linked back to the entry. Closure holds today **only** because replay drains the cmETH
`LP-RECEIPT` basis pool (`fullyExited()` short-circuit) — closure is load-bearing on the pool draining,
not on a correct exit link. Any wrapped/zapped exit that replay cannot net is a latent phantom-open.

**cmETH regression risk (auditor R3 / architect B4):** re-keying must preserve the ADR-047 exit link
that drains cmETH at **LP cost (~$2,155)** rather than spot (~$4,382). If broken, the ADR-047
+$2,228/cmETH spot spike returns. cmETH is a **separate accounting family** ($2,557 realized P&L),
**not** covered by the `FAMILY:ETH` guard — it must be pinned separately.

### LP1 — Solana Meteora DAMM (MLP) surfacing + receipt typing

DAMM liquidity adds carry **no** `correlationId`, so the correlationId-regex-gated read path never
treats them as LP positions (invisible to the LP list and to `lp_receipt_basis_pools`). And MLP farm
movements are typed `LP_POSITION_STAKE → role SELL` / `LP_POSITION_UNSTAKE → role BUY`, i.e. the
non-priced LP receipt is disposed/acquired as tradable inventory — a realized-P&L mis-tag (measured on
`asset_ledger_points`: STAKE realized **−$12.456** / basisΔ −$166.26).

## Decision

### 1. Pendle correlation key — per market + per wallet (supersedes ADR-023 D3)

Change the Pendle LP correlation key from `pendle-lp:{network}:{lpTokenSymbol}` to:

```
pendle-lp:{network}:{marketOrSyAddress}:{walletLower}
```

- **`marketOrSyAddress`** is the Pendle market (or SY) **address**, resolved **deterministically and
  identically** for the direct entry **and** the wrapped `zapOutV3SingleToken` exit (see item 3). Bare
  symbols are abandoned as the key: they collide across wallets and markets.
- **`walletLower`** is the tracked wallet address, **canonicalized** (lower-cased for EVM). Per-wallet
  keys prevent the multi-wallet collapse and enable per-wallet closure (wallet A closed, wallet B open
  on the same market).

### 2. Staked-wrapper → base-receipt mapping

Map the staked wrappers `eqb<X>` (Equilibria) / `pnp<X>` (Penpie) to the **base** LP receipt `<X>` for
net-quantity, effective-balance, and exit linking, so a `zapOutV3SingleToken`-style exit nets the base
receipt to 0 and closes **by link** — not by relying on the basis pool draining. LP-receipt movements
(mint, stake, unstake, burn) are **non-priced TRANSFERs** (role neither SELL nor BUY); the receipt is
never priced. Basis lives on the underlying leg(s) / `lp_receipt_basis_pools`.

### 3. Deterministic market/SY-address resolution (entry ≡ wrapped exit)

Both paths **must** resolve the **same** `marketOrSyAddress`, or "close by link" fails:

- **Direct entry:** the Pendle market/SY address is the LP receipt token's own contract (the
  `PENDLE-LPT` ERC-20), read from the movement leg.
- **Wrapped `zapOutV3SingleToken` exit:** the base `PENDLE-LPT` contract is absent (only
  `eqbPENDLE-LPT` + returned cmETH appear), so resolve the market via an **`eqb`/`pnp`-wrapper →
  Pendle-market registry** (config-plane, per ADR-059), or the market-from-cmETH-source recovery ADR-047
  already performs (the cmETH market `0x2ab8…`). No tx-hash / wallet-specific runtime keys.

### 4. Meteora DAMM correlation key + non-priced MLP (LP1)

Mint `correlationId = lp-position:solana:meteora-damm:{poolAddress}:{walletLower}` on the DAMM
`LP_ENTRY` (**per-pool + per-wallet**, mirroring the Pendle granularity — architect C5). The terminal
`remove_liquidity` links as `LP_EXIT_FINAL` (MLP net → 0). Classify all MLP receipt movements (mint,
farm stake/unstake, burn) as **non-priced TRANSFER** (never role SELL/BUY); keep MLP **unpriced**.
Basis lives on the underlying SOL / mSOL / bSOL legs + the new DAMM basis pool. Expected accounting
deltas (pinned): portfolio realized P&L changes by **≈ +$12.46** (removal of the mis-tagged MLP STAKE
loss); ≈ $133 basis re-routes onto the SOL/mSOL/bSOL legs; net MLP = 0.

### 5. LP-receipt continuity identity (display + aggregation)

Pendle LP tokens (`-LPT`, incl. `eqb`/`pnp` wrappers) and the Meteora DAMM MLP receipt resolve to the
`FAMILY:LP_RECEIPT` continuity identity (ADR-054), so they are excluded from the priced spot-asset
surface and from spot family move-basis aggregation — the economic value lives in the LP position (open)
or is realized on exit (closed), never double-shown as a standalone priced asset. Recognition is
**identity/flag-driven** (continuity identity + LP correlationId membership), not a broadened symbol
suffix heuristic keyed on real symbol spelling.

### 6. Read-model key parsing (auditor R7 / architect B4)

Update `SessionLpQueryService.derivePairFromCorrelationId` and `PositionAccumulator` to parse the new
**4-segment** `pendle-lp:{network}:{market}:{wallet}` key (the legacy `split(":", 3)` folds the
address + wallet into the pair label) and the `lp-position:solana:meteora-damm:{pool}:{wallet}` key.
The pair label is derived from the snapshot / flows, never from the raw market/pool address segment.

## Consequences

- Two distinct Pendle markets (or two wallets on the same market) never share a correlationId; a
  wrapped Equilibria/Penpie exit nets the base receipt to 0 and closes **by link**, independent of the
  basis-pool short-circuit.
- cmETH continues to drain at LP cost (ADR-047 preserved via deterministic market resolution): **no**
  +$2,228 spot spike. cmETH is pinned separately from `FAMILY:ETH`.
- Meteora DAMM positions surface with a basis pool; MLP shows **no** SELL/BUY trade tag; realized P&L
  corrects by ≈ +$12.46; basis re-routes to the underlying legs.
- LP receipts (`PENDLE-LPT`, `eqbPENDLE-LPT`, `MLP`) leave the dashboard spot-asset surface (drained to
  0 on exit for closed positions; excluded as non-priced receipts for open ones).
- Renormalization required (classification + correlation change) → `--skip-frontend` full reset.
- Determinism / rerun-safety: keys are canonical addresses, per market/pool + per wallet; no dataset-,
  tx-hash-, or wallet-specific runtime decision keys.

## Alternatives considered

- **Keep the symbol-only Pendle key (ADR-023 D3).** Rejected: collides across wallets/markets; masks a
  latent phantom-open; can only close via the basis-pool short-circuit.
- **Broaden the `-LP*` symbol suffix heuristic to catch `PENDLE-LPT`/`MLP`.** Rejected: fragile,
  spelling-keyed; recognition must come from the classification identity/correlationId membership.
- **Hardcode the Equilibria exit corrId (ADR-047 item 4).** Amended: replaced by deterministic
  wrapper→market resolution so re-entries and other markets generalize.
