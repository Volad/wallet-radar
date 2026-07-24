# ADR-083: Cluster-carry for intra-cluster cross-canonical conversions

**Status:** Accepted
**Date:** 2026-07-24
**Scope:** AVCO replay carry-vs-realize decision for conversions **within** a staking cluster
(ETH / SOL / AVAX). Routing (`ReplayTransactionRouter` → `CLUSTER_CARRY` route,
`ReplayRouteHandlerAdapters`), carry engine (`LiquidStakingReplayHandler` generalized from
same-`FAMILY` to same-`CLUSTER` selection), cluster resolution + predicate
(`AccountingAssetClassificationSupport.clusterForFamilyIdentity` / `isIntraClusterConversion`),
generic realize gate (`ReplayDispatcher.replayGenericFlowsSkipping`).
**Amends / supersedes (intra-cluster conversions only):**
ADR-054 **§2** (per-asset realize-at-market on any distinct-canonical movement) and
ADR-082 / FB-01 (NET-lane re-base on realizing swaps) — both are **superseded** for conversions where
every principal leg is in the same staking cluster; they remain in force for cluster↔non-cluster
realizing swaps.
**Related:** ADR-040 §5 (dual-lane invariants — carry conserves both lanes, PnL = 0),
ADR-062 (break-even offset — intra-cluster conversions no longer contribute realized P&L).
**Inputs:** `results/cross-canonical-conversion-phantom-pnl-audit.md`,
`docs/tasks/cluster-carry-intra-family-conversion-implementation-plan.md`.

---

## Context

Per ADR-054 §2, replay treated **any** movement between two distinct canonical assets as a disposal +
acquisition priced at market (realize). A user's staked-ETH holdings move continuously between
canonical forms — ETH → mETH → cmETH → PT-cmETH → back — without ever selling to USD/fiat. Under the
realize model each such internal conversion booked realized P&L and re-based the acquired leg at
market, **destroying the disposed basis** on down-conversions.

Concrete (Bybit, 2025-03-12, `FUNDING_HISTORY 9bc8c8a2…|…d552dbd4`):

```
ETH  DISPOSE  −0.709   basis −$1,889.54   realized −$555.93   (ETH marked at $1,881)
METH ACQUIRE  +0.66865 basis +$1,333.20   realized  0.00      (fresh market $1,993.86)
```

No sale to USD occurred, yet a **−$556 phantom loss** was booked and mETH re-based at its (lower)
market. Across all intra-cluster conversions (ETH 55 / SOL 19 / AVAX 7 + the PT-cmETH round-trip and
SOL/mSOL rotations) this injected phantom realized P&L into each cluster. The phantom cluster loss
then broke the ADR-062 effective-cost read model: a cumulative cluster loss divided by a tiny early
covered denominator exploded effective cost to ~$51k/ETH (masked only by the T3 display guard). This
also contradicted the user's decided model (`convert_semantics = carry`) and the "one asset, one
pool" spirit of ADR-054.

Root cause: **`cost_basis` / `replay`, by design.** Cluster membership was already computed but
scoped "normalization-only"; replay carry-vs-realize deliberately used per-asset
`canonicalTokenIdentity`. `LiquidStakingReplayHandler` already implemented the correct carry
(REALLOCATE_OUT / REALLOCATE_IN, both-lane proportional basis, PnL = 0) but only for same-`FAMILY`
legs sharing a canonical identity, so cross-family same-cluster moves (ETH↔mETH) fell through to the
generic realize path.

## Decision

**Cluster-carry.** A conversion whose **every principal leg resolves (contract-first) to the same
non-null staking cluster**, and which touches **no** non-cluster principal, **carries basis** (Market
**and** Net lanes) from the disposed leg(s) onto the acquired leg(s) via REALLOCATE, with
**realized PnL = 0**. Non-1:1 quantity conversions re-average the total carried basis onto the
destination quantity. Realized P&L is booked **only** when a non-cluster principal (USDT/USDC/DAI,
fiat, BTC/WBTC, or a *different* cluster) is present — i.e. only on a true exit.

**Cluster membership (authoritative for carry).**

| Cluster | Members |
|---|---|
| `CLUSTER:ETH_STAKING` | ETH, WETH, vbETH, mETH/METH, cmETH/CMETH, weETH/WEETH, wstETH/WSTETH, stETH, rETH/RETH, cbETH, yvVBETH, **PT-cmETH**, **PT-ETH** |
| `CLUSTER:SOL_STAKING` | SOL, mSOL, vSOL, bSOL, bbSOL, jitoSOL |
| `CLUSTER:AVAX_STAKING` | AVAX, WAVAX, sAVAX, aAvaSAVAX (AAVASAVAX) |

Resolution is **contract-first** via `AccountingAssetFamilySupport.continuityFamilyIdentity(symbol,
contract)` → family → `clusterForFamilyIdentity(...)`, preserving LP-receipt discrimination and
confusable-symbol safety. **Fail-safe:** any token that resolves to a null cluster (e.g. GMX GM
ETH/USD `0x70d9…`, and any unmapped instrument) → **realize** (never carried).

**Conversion types in scope:** `STAKING_DEPOSIT`, `STAKING_WITHDRAW`, `VAULT_DEPOSIT`,
`VAULT_WITHDRAW`, and `SWAP` whose principals are entirely intra-cluster.

**SWAP same-token discriminator (RC-1).** A `SWAP` is excluded from cluster-carry **only** when it is a
*provably same-canonical-token* identity move — every principal leg resolves via the C1/C2
`canonicalTokenIdentity` to one and the same non-null identity (WETH↔ETH, WAVAX↔AVAX), which belongs
on the same-family swap path. The earlier pre-gate used `hasCrossCanonicalIdentityPrincipalPair`
(C1/C2-only), which returned `false` for any leg unknown to the registry (Pendle PT, Solana SPL LSTs,
Aave aTokens) and thereby *realized* genuine intra-cluster conversions (`cmETH↔PT-cmETH`, `SOL↔mSOL`)
before the cluster test could run. When a leg's canonical identity is unknown, we cannot prove a
same-token move, so the contract-first cluster loop decides.

**Pendle maturity-suffix resolution (RC-2).** Live Pendle symbols carry a maturity suffix
(`PT-CMETH-18SEP2025`) that `classificationSymbol` does not strip, so the `SUPPLEMENTAL_CLUSTER_BY_SYMBOL`
key (`PT-CMETH`) missed and `stakingClusterForFlow` fell to the fail-safe realize. `stakingClusterForFlow`
now retries the supplemental lookup on a maturity-stripped base (trailing `-DDMONYYYY`), scoped to
cluster resolution only so accounting-family / pool identity stays maturity-specific.

**Engine & routing.** The former `LIQUID_STAKING` route becomes `CLUSTER_CARRY` (a superset): the
same-family carry is the degenerate single-cluster case. Selection groups principals by cluster
identity and pools all outbound basis proportionally onto all inbound (both lanes, final-covered
remainder exactness), reusing `LiquidStakingReplayHandler.allocateInbound` and the `:FUND`/accountRef
drain redirects. The carry runs at the route level **before** the generic realize path.

**Generic realize gate (required).** Routing alone does not silence the generic cross-canonical arm:
`replayGenericFlowsSkipping` recomputes `isCrossCanonicalStaking` and
`stampCrossCanonicalRedemptionProceedsFromInbound` iterates all flows ignoring the skip set. The arm
is therefore gated:
`isCrossCanonicalStaking = (type checks) && hasCrossCanonicalIdentityPrincipalPair(tx) && !isIntraClusterConversion(tx)`,
so the ADR-082 `swapNetRef` re-base, the D1 `applyUnknownTransfer` fail-closed, and the redemption
proceeds stamp go dormant for carried txs while remaining live for cluster↔non-cluster realizing
swaps.

## Invariants

- **Both lanes carried; PnL = 0.** Σ(carry-out basis) == Σ(carry-in basis) per lane (±dust); no basis
  is created or destroyed on a covered conversion (ADR-040 net conservation holds).
- **`Net ≤ Market` may equalize** on a carried lot (the carry writes basis directly via
  `restoreToPosition`, bypassing the generic `min(market)` cap **by design**) — this is expected and
  no invariant gate rejects a carried lot.
- **Down-conversions preserve basis** (no market write-down); the real loss is deferred and realized
  at the eventual exit to a non-cluster asset. No economic loss is hidden because no sale occurred.
- **Uncovered outbound** falls back to `applyFallbackSettlementFlow` (market-priced inbound) — the
  PnL = 0 carry guarantee holds for the covered portion.

## Consequences

- The 2025-03-12 ETH→mETH conversion: ETH REALLOCATE_OUT (PnL 0), mETH REALLOCATE_IN inheriting
  $1,889.54 (avco ≈ $2,826/mETH). The −$556 phantom loss is gone.
- Cluster phantom realized removed at the source (audit: ETH, SOL; AVAX cluster realized → ≈0). The
  ADR-062 AC-8 child-offset unfloor, the T3 over-sliver guard, and the ADR-082 NET re-base become
  **inert** for these flows (kept as defense-in-depth).
- The `cmETH↔PT-cmETH` round-trip (FB-01) now carries → its +$2,037.84 Market / NET realized becomes
  0, **superseding** ADR-082's NET re-base for that flow.
- **Average (market) cost and effective cost shift** from the ADR-082 figures because intra-cluster
  realized P&L (both phantom losses and the FB-01 gain) is removed rather than re-based. Post-fix
  terminal values are recomputed and pinned by the re-audit (plan CC-AC-3 / CC-AC-7).
- Requires a full renormalization + replay (`--skip-frontend`, no pricing-cache clear).

## Alternatives considered

- **Keep realize (ADR-054 §2 / ADR-082) + read-model floor of intra-cluster losses:** a read-model
  band-aid that leaves phantom realized P&L in the ledger and cannot express "12 ETH at price X".
  Rejected.
- **Symbol-only cluster resolution:** contract-blind, regresses LP-receipt / confusable-symbol
  identity. Rejected in favor of contract-first `continuityFamilyIdentity`.
- **Dedicated new `ClusterCarryReplayHandler`:** rejected in favor of generalizing the proven
  `LiquidStakingReplayHandler` selection (same-family = degenerate case) to avoid selector overlap and
  duplicated carry math.
