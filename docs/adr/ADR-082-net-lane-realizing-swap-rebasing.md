# ADR-082: NET-lane realizing-swap re-basing (no basis recycling)

**Status:** Accepted
**Date:** 2026-07-24
**Scope:** AVCO replay NET-lane basis seeding for realizing swaps / cross-canonical conversions
(`ReplayDispatcher.applySellWithOptionalPool` / `applyBuyWithOptionalPool`,
`GenericFlowReplayEngine.applyBuyWithExplicitNetCost`), read-model defense-in-depth
(`BreakEvenCalculator`).
**Amends:** ADR-054 **§2** (Net lane for C1→C2 / distinct-canonical conversions — the unconditional
`min(inherited, market)` net-basis carry), ADR-040 **"Bug B"** (SWAP `swapNetRef` net-basis
propagation).
**Related / references:** ADR-062 (`offsetLane=NET` break-even offset — its `netRealizedPnlUsd` input
becomes artifact-free), ADR-040 §5 (dual-lane invariants `0 ≤ Net AVCO ≤ Market AVCO`,
`|Σ netCostBasisDelta| ≤ dust` on closed carry round-trips — unaffected).
**Inputs:** `results/unmatched-bridges-and-effective-cost-audit.md` §B / §C / "FB-01 code-path
localization", `docs/tasks/net-lane-basis-recycling-fix-implementation-plan.md`.

---

## Context

The ADR-040 "Bug B" SWAP net-cost propagation carries the net basis released by a SELL leg onto the
paired BUY leg (`swapNetRef[0]=net released`, `[1]=market released`; acquired net basis
`= min(released, market)`), and ADR-054 §2 reuses the same mechanism for C1→C2 conversions. This
carry fired **unconditionally** on every priced swap.

A financial-correctness audit (FB-01, HIGH) proved this double-counts appreciation on a same-asset
round-trip through a **distinct** priced instrument. On Mantle, `cmETH → PT-cmETH-18SEP2025 → cmETH`:

| seq | leg | Market cbΔ | NET cbΔ | Market realized | NET realized |
|---|---|---|---|---|---|
| 5882 | cmETH DISPOSE → PT | −1,504.38 | −1,499.23 | +2,037.84 | +2,042.98 |
| 5883 | PT-cmETH ACQUIRE | +3,557.75 | **+1,499.23** | — | — |
| 5969 | PT-cmETH DISPOSE → cmETH | −3,557.75 | −1,499.23 | −159.49 | **+1,899.03** |
| 5970 | cmETH ACQUIRE | +3,380.74 | **+1,499.23** | — | — |
| 6614+ | cmETH DISPOSE (final) | avco $3,925 | avco **$1,739** | +608.92 | **+2,493.39** |

The Market lane re-bases each acquisition to its actual cost (correct). The NET lane **realizes** the
gain at each disposal **and** recycles the stale pre-loop net basis `$1,499.23` onto the freshly
priced acquisition — so the same appreciation is realized ~3×. The disposed cmETH lot carried **no**
reward discount (net avco $1,740.16 vs market $1,746.14, a 0.34% rounding residual), i.e.
`swapNetRef[0] ≈ swapNetRef[1]`. This fabricated **+$1,894** of FAMILY:METH "income" that
`offsetLane=NET` (ADR-062) folded into the ETH break-even offset (effective cost understated
~$500/ETH: displayed $2,001.68 vs correct ≈$2,500). The same pattern inflates NET realized
portfolio-wide by ≈ **+$5,704** (SOL/AVAX/SAVAX/WEETH offsets, the latent PT-cmETH mirror).

Root cause: **`cost_basis`/`replay` NET lane.** The canonical types are correct; the NET-lane
basis-carry semantics for a realizing round-trip through a distinct instrument are the defect.

## Decision

**"Re-base on realize" (NET lane only).** On a **realizing swap / cross-canonical conversion between
distinct canonical instruments** where the disposal **realized-and-kept** its NET P&L on a lot that
carried **no reward discount**, seed the acquired lot's NET basis to the **market acquisition cost**
(`net = market`) instead of recycling the released net basis. The pre-loop discount is banked once at
the disposal; it is never re-planted.

**Discriminator (single, local, deterministic).** Re-base when **both** hold:

1. **NET realized was kept** — the paired SELL's NET realized delta was not reversed by the
   counterparty-basis-pool undo path (`CounterpartyBasisPoolReplayHook.undoSellRealisedPnl`). Threaded
   as `swapNetRef[2]` (accumulated kept-NET-realized magnitude), measured **after** the pool branch so
   an undo yields zero.
2. **No reward discount on the disposed lot** — released net basis is not materially below released
   market basis (`swapNetRef[1] − swapNetRef[0] ≤ 1%` of market). A tiny relative gap is AVCO/rounding
   dust (the 0.34% cmETH residual), not a reward.

Otherwise **preserve** the existing `min(released, market)` carry. Preserved cases (must not regress):

- **Genuine reward / yield carries** (`net ≪ market`, e.g. `REWARD_CLAIM` / `ZeroCostAcquisition`
  discount) — keep inheriting the low net basis so "rewards reduce cost for free".
- **Unpriced disposals** — the SELL realized nothing (`markUnresolved`) → not kept → carry preserved.
- **Counterparty-pool / CEX-corridor disposals** — `undoSellRealisedPnl` reversed the NET realized →
  nothing banked → carry preserved (deferred income). (By transaction type these never arm
  `swapNetRef`, so they are additionally out of the changed branch.)
- **C1↔C1 identity moves** (ETH↔WETH) — `swapNetRef` is `null` (not a distinct-canonical pair).
- **Same-token corridor / bridge carries** — routed through continuity carry, not `swapNetRef`.
- **ADR-054 cross-canonical identity-carry** (proceeds = inbound FMV, realized ≈ 0) — not kept →
  preserved.

**Invariants.** NET lane only; the **Market lane is byte-identical** and realized-PnL on every
disposal leg is unchanged (`net = market` trivially satisfies `0 ≤ net ≤ market`). Nothing is marked
`uncovered`; no `DISPOSE` is flipped to a `PnL = 0` carry — structurally avoiding the AC-11 / D7
revert signature.

**Defense-in-depth (read model).** `BreakEvenCalculator` fails a target's **cluster-attributed** NET
offset **closed to the Market lane** when credited income (`net − market`) is implausibly large (a
recycling-regression signature). It is a coarse safety net (absolute floor **and** a generous
multiple of market realized), a strict no-op on correct post-fix inputs, and never touches standalone
(non-cluster) reward families — so genuine `offsetLane=NET` reward credit is preserved. Precise
separation of genuine zero-cost income from artifact income requires dedicated ledger instrumentation
(future work); the ledger re-base above is the primary fix.

## Consequences

- FAMILY:METH net−market income **+$1,894 → ≈ $0**; ETH cluster offset **$3,911 → ≈ $2,003**; ETH
  effective/break-even cost **$2,001.68 → ≈ $2,500/ETH**. Average (market) cost **$3,025.45 unchanged**;
  ETH-equivalent denominator **3.8154 unchanged**.
- Global NET realized **$8,039 → ≈ $3,807** (removes the ≈$4,231 recycling subset; **keeps** genuine
  zero-cost reward income — does **not** collapse to Market $2,334). FB-02 (SOL/AVAX) and the latent
  FB-03 (PT-cmETH mirror) are corrected by the same mechanism.
- D3 cmETH→ETH realized **−$197.74 unchanged** (Market lane untouched; realized-PnL untouched).
- Requires a full renormalization + replay (`--skip-frontend`, no pricing-cache clear) to take effect.

## Alternatives considered

- **(a) Read-model only** (credit Market-lane realized + only genuine zero-cost income): cannot
  separate genuine reward income from the artifact without new ledger instrumentation, and
  `offsetLane=MARKET` would drop genuine reward write-down. Kept only as the defense-in-depth guard.
- **Model B ("carry + defer")**: suppress NET realized on the disposal plus proportional discount
  carry — strictly more invasive, closer to the AC-11 danger zone. Rejected.
