# ADR-070: Solana LP on-chain enrichment (Meteora DLMM, Raydium CLMM)

**Status:** Accepted (amended 2026-07-21 — Meteora DLMM correlation is a **position PDA**; its LbPair
pool is now **captured at normalization** and persisted, with the on-chain PDA decode kept only as a
legacy fallback; see "Meteora DLMM" and the 2026-07-21 amendment below)  
**Date:** 2026-07-19  
**Scope:** Liquidity-pool position refresh (discovery, open/closed detection, on-chain enrichment)

---

## Context

~50 `SOLANA` rows in `lp_position_snapshots` were stuck as shell snapshots (`status: unknown`,
no protocol/pair, `unavailableReason: "On-chain enrichment unavailable"`). Three defects, all on
the LP read/enrichment path (the normalization pipeline and `SolanaLpPositionResolver` already
produce correct position-scoped correlation ids):

1. **A2 — address corruption.** `LpPositionRefreshService.normalizeAddress` lowercased every
   wallet (`trim().toLowerCase()`). Solana/TON addresses are case-sensitive base58 / base64url, so
   the discovery `walletAddress $in` query never matched the case-preserved normalized LP
   transactions — Solana positions were invisible to discovery.
2. **A4 — open/closed detection.** Closure was recognized only on `LP_EXIT_FINAL`. Solana
   DLMM/CLMM full removals are recorded as `LP_EXIT`, so genuinely closed Solana positions looked
   open indefinitely.
3. **C2 — no Solana reader.** No `LpPositionReader` supported Solana correlation schemes, so every
   Solana position fell through to a shell snapshot.

Constraints: free public APIs only, no paid indexers, zero EVM regressions, reuse existing
Solana infrastructure (`WalletAddressReadScope`, `SolanaRpcClient` + `solanaRotatorsByNetwork`,
`JupiterSplTokenMetadataResolver`).

The legacy `dlmm-api.meteora.ag` REST API is decommissioned (returns 404); the only working free
keyless endpoint (≈30 RPS) is `GET https://dlmm.datapi.meteora.ag/pools/{poolAddress}`. The
`meteora-dlmm` correlation pubkey emitted by `SolanaLpPositionResolver` is the **position PDA**
(`accounts[0]` of the largest DLMM liquidity instruction), **not** the LbPair pool — so the reader
must decode it on-chain to the pool before fetching pool metadata (see "Meteora DLMM" below).
Raydium CLMM likewise resolves its pool id through an on-chain lookup from the position NFT account.

> **Amendment (2026-07-21).** An earlier revision of this ADR claimed the `meteora-dlmm` correlation
> pubkey was the LbPair pool address itself and removed the per-position decode. That was wrong: the
> resolver emits the **position PDA**, so `fetchPoolResult(<positionPda>)` 404'd for **every** Meteora
> position and all Solana DLMM snapshots fell back to a closed/shell state. The reader now decodes the
> PDA to its LbPair pool (below).

---

## Decision

**A2 — family-aware read scope.** `normalizeAddress` delegates to `WalletAddressReadScope.normalize`
(EVM/CEX lowercased, Solana base58 case-preserved, TON preferred member ref). Applied to both the
discovery wallet `$in` list and `buildContext`.

**A4 — Solana terminal `LP_EXIT`.** Closed-detection additionally treats an `LP_EXIT` on a
`lp-position:solana:*` correlation id as terminal **when no position-scoped basis pool remains**.
When a basis pool exists, the pre-existing `qtyHeld<=0` signal (`closeExitedSnapshots` +
`excludedByBasisPool`) and the on-chain reader govern closure. EVM logic is untouched: EVM partial
exits (`LP_EXIT_PARTIAL`) stay open until `LP_EXIT_FINAL`, and a bare EVM `LP_EXIT` never closes.

**C2 — `SolanaLpPositionReader`.** A single `LpPositionReader` bean handling both Solana CL schemes:

- **Meteora DLMM** (`lp-position:solana:meteora-dlmm:<positionPda>`): the correlation pubkey is the
  **`PositionV2` position PDA**, **not** the LbPair pool. The LbPair pool address is resolved in
  preference order (see the 2026-07-21 amendment): (1) the **LbPair captured at normalization** and
  carried on `LpPositionContext.lpPoolAddress` (preferred — no read-path RPC, works for closed
  positions); (2) legacy fallback for open positions only — decode the PDA on-chain:
  `getAccountInfo(<positionPda>)` → the Anchor `PositionV2` layout carries `lbPair: Pubkey` at
  **byte offset 8** (immediately after the 8-byte discriminator; `owner` follows at offset 40);
  `data[8..40]` is base58-encoded (case-preserved, never lowercased) to the LbPair pool address. Pool
  metadata is fetched from `GET {meteora}/pools/{lbPair}` (`token_x`/`token_y` → `address`, `symbol`,
  `decimals`; `name` pair label; `current_price`). Behavior (fallback path):
  `getAccountInfo` **empty** ⇒ the position account was reclaimed ⇒ **closed** snapshot; data too
  short / decode failure ⇒ `Optional.empty()` (keep shell, transient); pool resolvable ⇒ the position
  is **open** with best-effort `in_range` (the pool alone does not expose the user's bin range/amounts,
  so per-position size/TVL stays unset); pool-not-found / HTTP error ⇒ closed snapshot. When the LbPair
  is captured and the position is **closed**, the reader still fetches the pool and emits a **closed
  snapshot carrying the resolved pair** (TVL/fees zero) so the pair label survives closure. The
  non-flowing SPL leg symbol resolves via `resolveSymbol(mint, fallback)` → `JupiterSplTokenMetadataResolver`
  (a pump.fun mint resolves through the Jupiter tokens metadata path, else falls back to the mint).
- **Raydium CLMM** (`lp-position:solana:raydium-clmm:<positionNftAccount>`):
  `getAccountInfo(nftAccount)` yields the position NFT mint; a targeted
  `getProgramAccounts` memcmp on that mint (offset 9 of `PersonalPositionState`) resolves the state
  account, from which pool id (offset 41) and tick range (73/77) are decoded. Pool mints/symbols
  from `GET {raydium}/pools/info/ids?ids={poolId}`.

Symbols resolve via `JupiterSplTokenMetadataResolver` first, then the API-provided symbol, then the
raw mint. Token quantities are left unpriced by the reader; TVL/unclaimed-fee USD pricing is applied
downstream by `LpPositionRefreshService.applyMarks`. All REST/RPC failures resolve to
`Optional.empty()` so the caller keeps the prior stale/shell snapshot — the reader never throws.

Config: `walletradar.liquidity-pools.solana.*` (`enabled`, `meteora-base-url`, `raydium-base-url`,
`timeout-ms`).

---

## Consequences

- Solana LP positions are discovered, marked open/closed correctly, and show real pair + protocol.
  Open Meteora positions now resolve their pair from the pool address (e.g. `Agp2NtFd…` ⇒ COM-USDT)
  and report `in_range`; closed positions are filtered out upstream by A4 (terminal `LP_EXIT`) and
  the `qtyHeld<=0` signal before the reader runs.
- Best-effort limitations (documented, not blocking): per-position **token quantities/TVL** are not
  reconstructed from free REST (would require DLMM bin-array / CLMM tick math), so a position shows
  pair + protocol without a size until pricing has amounts; Meteora status is a best-effort
  `in_range` (the pool endpoint does not expose the user's bin range); Raydium in-range likewise
  defaults to `in_range` because the v3 pool-info endpoint does not expose the live tick.
- `getProgramAccounts` is used only for Raydium and only as a single memcmp-filtered lookup on a
  unique NFT mint (bounded, one small account); it runs only for open Raydium positions.
- Zero EVM impact: EVM readers and EVM open/closed semantics are unchanged; the Meteora fixed-layout
  offsets are guarded by an owner-program check and a minimum account length.

Two fixed-layout decodes remain, both anchored to the current Anchor layout and both degrading
gracefully to empty (keep stale) rather than mis-decoding, because a wrong pool id 404s at the venue
API: the Meteora `PositionV2` (`lbPair` @8, per the 2026-07-21 amendment) and the Raydium
`PersonalPositionState` (pool id @41, tick range @73/77).

## Amendment (2026-07): Meteora 404 ⇒ closed (entry-only ghost pools)

Positions entered before reliable Solana LP-exit ingestion can have **only `LP_ENTRY` events and no
captured `LP_EXIT`**. Upstream A4 close detection (terminal `LP_EXIT` / `qtyHeld<=0`) therefore never
fires, so their entry-only basis pool keeps them "open" forever, and the Meteora data API returns
**HTTP 404 (pool not found)** because the position no longer exists on-chain. Previously the reader
mapped both 404 and transient errors to `Optional.empty()`, leaving a permanent `unknown/stale`
"ghost" pool on the LP page.

`MeteoraDlmmApiClient.fetchPoolResult` now distinguishes three outcomes — `RESOLVED`, `NOT_FOUND`
(durable 404), and `UNAVAILABLE` (timeout/5xx/parse). `SolanaLpPositionReader.readMeteora` treats a
`NOT_FOUND` as a **closed** snapshot (mirroring the Raydium reclaimed-NFT-account ⇒ closed rule),
while a transient `UNAVAILABLE` still keeps the existing shell (no false closure). This is the only
reliable closed signal for entry-only positions whose exit was never ingested. Raydium already had
an equivalent on-chain closed signal, so it is unchanged; EVM is unaffected.

## Amendment (2026-07-21): capture the Meteora LbPair at normalization (closed single-sided pairs)

**Problem.** The PDA→LbPair on-chain decode above only works for **open** positions: a closed
Meteora position's `PositionV2` PDA is deallocated and its rent reclaimed, so `getAccountInfo`
returns empty and the LbPair is unrecoverable at read time. For **single-sided SOL deposits** the
second pool token never appears in any wallet flow either, so both the reader and the flow-derived
pair yield only `SOL` → the position displays as "Unknown pair". ~15 closed Meteora positions were
affected (e.g. correlation `…meteora-dlmm:7pDAFc…`), while two-sided positions already resolved.

**Key on-chain fact.** The Meteora **LbPair pool account is shared and persists on-chain** even
after an individual user's position PDA is closed. It is therefore the only reliable pair source for
a closed position — and it must be captured at **normalization time** from the raw tx, because it
cannot be recovered later.

**Decision.**

1. **Capture at normalization.** `SolanaLpPositionResolver.resolveLpPoolAddress` reads the LbPair
   from **`accounts[1]`** of the same largest Meteora DLMM liquidity instruction whose `accounts[0]`
   is the position PDA (layout `[position, lbPair, …]` for `addLiquidityByStrategy*` /
   `removeLiquidityByRange*`). Plausibility guards mirror the position-PDA guard (never a routed
   program / system / mint account) plus a distinctness guard (must differ from the position PDA).
   Hawksight-wrapped and Raydium interactions capture nothing.
2. **Persist.** The captured address is stamped on `NormalizedTransaction.lpPoolAddress` for Solana
   Meteora LP rows. **The correlation id is unchanged** (still keyed on the position PDA), so
   basis-pool continuity is unaffected — this is auxiliary enrichment metadata only. No new index
   (read via the already-loaded per-correlation rows).
3. **Prefer at enrichment.** `SolanaLpPositionReader.readMeteora` prefers
   `LpPositionContext.lpPoolAddress` over decoding the PDA — removing a read-path RPC for open
   positions and enabling closed ones. When the LbPair is present it fetches the pool and sets
   `token0`/`token1` + symbols even for a **closed** position (status closed, TVL/fees zero).
4. **Discover closed positions once.** `LpPositionRefreshService` emits a **closed-marked context**
   (carrying the captured LbPair) for closed Meteora correlations whose snapshot does not yet have a
   two-sided pair, so the reader writes a closed snapshot with the pair exactly once (idempotent,
   bounded). `LpOnChainEnrichmentService`/`refreshPosition` let such a context through because the
   reader opts in via `supports()` for a closed Meteora context with an LbPair; every other closed
   context is still a no-op.
5. **Display.** `SessionLpQueryService` prefers a **two-sided** pair: when the flow-derived
   accumulator pair is a lone symbol (single-sided SOL deposit) the enriched snapshot's `SOL/<SPL>`
   pair wins, so the closed position finally shows the correct pair.

All failure paths degrade gracefully (the reader never throws; a missing/implausible LbPair simply
falls back to the legacy decode or an unresolved pair). EVM and Raydium behavior is unchanged.
