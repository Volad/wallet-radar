# ADR-077: Velodrome/Aerodrome v2 (fungible) gauge LP identity + on-chain pair resolution

**Status:** Accepted
**Date:** 2026-07-21
**Scope:** LP classification correlation (`LpRegistryClassifier`), LP enrichment routing (`LpPositionRefreshService`, `FungiblePoolReader`)

---

## Context

A Velodrome **v2 (AMM) gauge** stake on Optimism surfaced on the LP page as **"Unknown pair"**.
The transaction `deposit(uint256)` (selector `0xb6b55f25`) to gauge
`0xbc6043a5e50ba0c0213d2f7430a73e4590af97ad` was classified `LP_POSITION_STAKE`, and the registry
mapped that gauge's `underlyingPositionManager` to a **Slipstream NonfungiblePositionManager**
(`0x416b433906b1b72fa758e166e239c43d68dc6f29`). `LpRegistryClassifier.resolveVaultFallbackCorrelationId`
therefore produced `lp-position:optimism:0x416b4339…:vault`, and the snapshot readers could not resolve
a pair for a **non-numeric `:vault` tokenId** (`ConcentratedLiquidityReader` rejects non-numeric ids;
`FungiblePoolReader` required a `FUNGIBLE`/`CURVE`/`BALANCER` family). Status resolved to `unknown`.

On-chain facts (Optimism), verified by grammar (no hardcoding):

- The gauge is a **v2 AMM gauge** — `gauge.nft()` **reverts**, `gauge.pool()` =
  `0x4da46c6afe7322b66efefda1f702605cbe08e0bd` (the staked v2 AMM LP token, itself an ERC-20).
- The staked LP token exposes `token0()` = USD₮0, `token1()` = USDT, plus `getReserves()`,
  `totalSupply()`, `balanceOf()` — a standard Solidly/Uniswap-V2-style pair.
- `deposit(uint256)`'s argument is an **amount**, not a tokenId (`ownerOf`/`positions` revert on the
  mapped NFPM), confirming the static Slipstream-NFPM mapping is wrong for v2 gauges.

A **CL/Slipstream** gauge, by contrast, exposes `nft()` (the NFPM) and its position is a real NFT
tokenId — that path must be preserved.

---

## Decision

### A. Grammar-driven v2-vs-CL gauge detection (`DexGaugePoolResolver`)

A new `DexGaugePoolResolver` (in `classification.support`) distinguishes gauge kinds purely by
on-chain grammar, reusing the existing EVM JSON-RPC infrastructure (`EvmRpcClient` +
`evmRotatorsByNetwork`), never a new HTTP stack:

- `nft()` resolves to a **non-zero** address ⇒ CL/Slipstream gauge ⇒ resolver returns empty (keep the
  NFPM + tokenId path).
- `nft()` reverts **and** `pool()` resolves ⇒ v2 (fungible) gauge ⇒ return `gauge.pool()` (the staked
  AMM LP token).

The `(network, gauge)` result — including negatives — is cached (immutable on-chain mapping) to keep
renormalization cheap. All transport/parse failures fail safe to empty (legacy behavior applies). No
gauge/token/pool address is hardcoded; a gauge qualifies purely by its method grammar.

### B. Identity keyed on the staked LP token, gauge carried for valuation

`LpRegistryClassifier.buildRegistryLpDecision` inserts a v2-gauge correlation resolution **after** the
receipt-keyed and Balancer-V3 identities and **before** the legacy `:vault` NFPM fallback. For a DEX
`STAKE_CONTRACT` gauge that resolves as v2, the correlation is:

```
lp-position:<net>:<stakedLpToken>:vault:<gauge>
```

The **staked LP token** (segment 2) is the stable identity — invariant across stake/unstake and gauge
migrations — while the **gauge** (segment 5) is carried so the read path can value the staked balance.
CL gauges and all other contracts are unaffected (resolver returns empty → legacy fallback).

### C. Fungible-vault enrichment routing + on-chain pair (`FungiblePoolReader`)

`LpPositionRefreshService` routes a `:vault` `lp-position:` correlation to the fungible path:
`inferFamily` returns `FUNGIBLE_LP` for a `:vault` tokenId, `buildContext` sets
`lpTokenContract = <stakedLpToken>` (segment 2) and `poolContract = <gauge>` (segment 5, when present).

`FungiblePoolReader` now:

- `supports(...)` a `:vault` tokenId (with a resolved LP token) in addition to the legacy fungible
  families;
- resolves the two-sided pair from the LP token's `token0()`/`token1()` + `symbol()`/`decimals()` when
  exposed (→ pair "USD₮0/USDT"); a plain ERC-20 that exposes neither keeps the legacy single-/
  explicit-side behavior and fabricates no pair;
- values the **staked** balance from `gauge.balanceOf(wallet)` (the wallet's direct LP balance is 0
  while staked), composing `token0`/`token1` quantities from `getReserves()` × share of
  `totalSupply()`; status is `in_range` (fungible AMM LP has no tick range).

---

## Rationale

| Alternative | Why rejected |
|---|---|
| Keep the static Slipstream-NFPM `:vault` mapping for v2 gauges | Produces an unresolvable NFT `:vault` id → "Unknown pair"; the NFPM is wrong for v2 gauges |
| Hardcode the gauge → LP-token / pair addresses | Prohibited; not general to other Velodrome/Aerodrome v2 gauges |
| Detect v2 by protocol string ("Velodrome") | Fragile and non-general; on-chain `nft()`/`pool()` grammar is authoritative and covers all Solidly forks |
| Key the correlation on the gauge | Gauge can migrate per pool; the staked LP token is the stable identity — the gauge is carried only as valuation metadata |
| Resolve the pair only from tx-flow symbols | The staked-LP deposit flow is often not captured (only a gas leg); the on-chain LP token is authoritative |
| Do the RPC in enrichment only | The wrong `:vault` identity is fixed at the earliest stage (classification), so entry/exit link on the correct staked-token key |

---

## Consequences

- Velodrome/Aerodrome v2 gauge stakes render a real pair (e.g. USD₮0/USDT), `staked=true`, status
  `in_range`, valued from the gauge's staked balance — no longer "Unknown pair".
- CL/Slipstream gauges (expose `nft()`) are untouched and keep the NFPM + tokenId path.
- Classification issues a small, cached, fail-safe on-chain read only for DEX `STAKE_CONTRACT` gauges;
  every other path is unchanged.
- General to any v2 (fungible) gauge by grammar; no address/protocol hardcoding.
- Residual: when neither a gauge (pre-existing 4-segment `:vault` correlations) nor a direct wallet LP
  balance is available, quantities/TVL fall back to the wallet's on-chain LP balance (0 while fully
  staked); the pair label still resolves.

## Regression anchors

- `DexGaugePoolResolverTest` — v2 gauge (`nft()` reverts, `pool()` present) → staked LP token; CL gauge
  (`nft()` present) → empty; unresolvable → empty; per-gauge caching; null/blank inputs.
- `FungiblePoolReaderTest` — AMM LP token exposing `token0()/token1()` → two-sided pair valued from the
  staked gauge balance; plain ERC-20 → no fabricated pair.
- Evidence anchor: Optimism gauge `0xbc6043a5…`, staked LP token `0x4da46c6a…` (USD₮0/USDT).
