# LP phantom-open closure hardening — implementation plan

Status: DRAFT v2 (awaiting user approval — do not implement)
Date: 2026-07-22
Audit: `financial-logic-auditor` cycle 2026-07-22 (phantom-open LP). Artifacts:
`results/lp-phantom-open-reconstruction.md`, `results/lp-phantom-open-accounting-failure-analysis.md`,
`results/lp-phantom-open-protocol-rule-pack.md`, `results/lp-phantom-open-required-changes.md`,
`results/blockers.md` (LP1–LP4).
Phase-3 review (2026-07-22): financial-logic-auditor + business-analyst + system-architect all returned
REVISE; v2 folds in their required changes (see "Phase-3 review resolutions" at end).

## Scope

- **Symptom:** long-closed LP positions surface as OPEN (or `unknown`) in the LP list / dashboard, and
  some non-LP / receipt-transfer actions are mis-tagged. Reported examples: EVM Pendle `PENDLE-LPT`
  (`0xc79e…9931`); Solana Meteora `39g1…4WP`, `43oo…o1hT` (DAMM adds), `3yac…TeDj` (DLMM remove); "other
  Meteora assets".
- **Networks / protocols:** EVM Pendle (+ Equilibria `eqb`/Penpie `pnp` staked wrappers) on Mantle;
  Solana Meteora DAMM (MLP receipt) and DLMM (concentrated); EVM Velodrome staked gauge (Optimism
  `unknown`-pair artifact); EVM Uniswap/Pancake CL NFTs; the generic LP open/closed determination.
- **Wallets:** all LP-active wallets (EVM `0xa0dd…`, `0x1a87…`; Solana `9Grp…`).
- **Blocker IDs:** LP1 (Meteora DAMM), LP2 (Pendle id-collision + wrapper exit), LP3 (no on-chain closure
  anchor), LP4 (Meteora DLMM linking hygiene), LP5 (Velodrome Optimism `unknown` snapshot — observed
  during synthesis).
- **In scope but note the boundary (BA #2):** the AVCO/cost-basis **math engine** is unchanged. But
  classification *inputs* that feed it — MLP leg **roles** (SELL/BUY → non-priced TRANSFER), pricing
  exclusion, and LP correlation keys — **do** change and will move realized-P&L tagging. This is
  deliberate and pinned in Acceptance.
- **Explicitly NOT in scope:** the AVCO math engine internals; re-pricing policy; frontend redesign
  (closed positions already drop from the OPEN list via existing `status=closed` UI behaviour).

## Current state (measured, prod `walletradar`, post-rebuild)

- All 4 reported example txs currently resolve **CLOSED**. Provable phantom-open **right now = 0**.
- The LP read model holds **4** snapshots: `lp-position:base:…:5527422` (Uniswap CL, `in_range`, genuine
  open $588); `gmx-lp:arbitrum:weth-usdc` (`in_range`, genuine open); `gmx-lp:arbitrum:glv-weth-usdc`
  (`closed`, correct); `lp-position:optimism:0x416b…:vault` (Velodrome, status **`unknown`**).
- The user's "still open" observation is consistent with a **pre-rebuild read-model state** — closure
  paths (drained basis pool / ghost-snapshot close) recompute on rebuild.

**Conclusion:** this is not an emergency data repair; it is **fragility hardening** so closure is
robust-by-construction instead of depending on fragile symbol-based correlation + replay draining the
pool. Two upstream classification defects (Pendle id collision, Meteora DAMM surfacing/receipt typing)
are real and removed at the source.

## Root cause (by cluster, earliest failed stage first)

### LP1 — Solana Meteora DAMM (MLP receipt) — `classification`
- DAMM liquidity adds (`39g1…`, `43oo…`) are typed `LP_ENTRY` but carry **no `correlationId`**, so
  `SessionLpQueryService` (correlationId-regex gated) never treats them as LP positions — invisible to the
  LP list and to `lp_receipt_basis_pools`.
- MLP farm movements are typed `LP_POSITION_STAKE`→role **SELL** and `LP_POSITION_UNSTAKE`→role **BUY**,
  i.e. the non-priced LP receipt is disposed/acquired as if it were tradable inventory. Measured on-ledger
  (`asset_ledger_points`, `assetSymbol=MLP`): STAKE realized **−$12.456** / basisΔ −$166.26; UNSTAKE
  realized $0 / basisΔ +$35.36; ENTRY realized $0 / basisΔ +$264.00. So this is an **AVCO/realized-P&L
  mis-tag**, not P&L-neutral (auditor R1).
- Evidence state: `EVIDENCE_PRESENT_UNUSABLE`. Blast radius: 0 phantom-open (net MLP 0); ≈ +$12.46
  realized-P&L correction + ≈ $133 basis re-route to underlying legs.

### LP2 — EVM Pendle (id collision + wrapper exit) — `classification` → `linking`
- `correlationId = pendle-lp:{network}:{lpTokenSymbol}` keys on **symbol only** (ADR-023 D3 deliberately
  strips the `EQB` prefix so `eqbPENDLE-LPT → pendle-lpt`) → collapses two wallets × multiple Pendle
  markets into one aggregate. Symbol-net `PENDLE-LPT` = **+0.4450 > 0** because the terminal exit `0xf7f8`
  (Equilibria `zapOutV3SingleToken`) burns **`eqbPENDLE-LPT`** and returns cmETH — never linked back.
- Closure only holds today because replay drained the cmETH `LP-RECEIPT` basis pool
  (`fullyExited()` short-circuit). **Closure is load-bearing on replay draining the pool, not on a correct
  exit link** → latent phantom-open for any wrapped/zapped exit replay can't net.
- **cmETH regression risk (auditor R3 / architect B4):** re-keying can break the ADR-047 exit link that
  drains cmETH at **LP cost (~$2,155)** rather than spot (~$4,382); if broken, ADR-047's +$2,228 cmETH
  spike returns. cmETH is a **separate accounting family** ($2,557 realized P&L) NOT covered by the
  `FAMILY:ETH ≈ $3,015.56` guard — must be pinned separately.
- Evidence state: `EVIDENCE_PRESENT_UNLINKED`. Blast radius: 0 now (masked); latent.

### LP3 — No on-chain anchor for closure — `verification` (structural)
- Open/closed is decided purely from internal signals (concentrated flag + snapshot, `LP_EXIT_FINAL`,
  drained basis pool, net-qty heuristic). `on_chain_balances` holds **no LP-receipt rows** (PENDLE-LPT,
  eqbPENDLE-LPT, MLP, Uniswap/Pancake CL NFTs, Velo staked LP all absent). Two hard problems (architect
  B1/B2, auditor R2, BA #1): (i) the refresh candidate universe only emits `netQuantity != 0` positive
  holdings, so a cleanly burned receipt produces **no row** — indistinguishable from a capture miss; and
  (ii) **staked** receipts read wallet `balanceOf == 0` while the position is **live** (tokens held by the
  gauge/booster/farm), so a naive "balance 0 ⇒ closed" would **false-close every live staked position**.
- Evidence state: `EVIDENCE_PRESENT_UNUSABLE`. Blast radius: structural; 0 currently escaping.

### LP4 — Solana Meteora DLMM — `verification` (cosmetic)
- Concentrated (`lpConcentrated=true`) + no snapshot ⇒ closed. Correct. Some entry legs uncorrelated /
  empty-flow (linking noise) but do not affect closure. Low priority; already covered by ADR-075
  rent-reclaim anchor.

### LP5 — Velodrome Optimism `unknown` pair — `linking`/`verification` (observed)
- `lp-position:optimism:0x416b…:vault` (Velodrome) sits at status **`unknown`** — the "Unknown Pair" Velo
  gauge stake/unstake. Needs ground-truth resolution: genuinely open staked v2 gauge LP (ADR-077 fungible
  gauge grammar, valued via `gauge.balanceOf(wallet)`) vs a closed/misclassified stake artifact.

### LP6 — LP/lending receipts leak onto the dashboard holdings surface as priced spot assets — `classification`/read-model (NEW, user-reported 2026-07-22)
- **Distinct surface from LP1–LP5:** the reported LP-*position* snapshots resolve closed, but the LP
  **receipt tokens** are orphaned in the holdings ledger (`asset_ledger_points`) with covered qty > 0 and
  get **priced as spot assets on the dashboard**. Measured (latest `asset_ledger_points`):
  - `PENDLE-LPT` on `0xa0dd…` (Mantle): `quantityAfter = basisBackedQuantityAfter = 0.33782` (dashboard
    0.338, ~$3.27k market) — **a live phantom right now**. On `0x1a87…`: 0 (correctly drained).
  - `MLP` on Solana `9Grp…`: 0.30960 (dashboard shows two MLP rows, 0.397 + 0.208 across pools).
  - `AZKSZK` (Aave `aZksZK` lending receipt) on zkSync `0x1a87…`: 160.02 — shown as spot, not lending.
  - `SSE` on Solana `9Grp…`: 3.74 — a **genuine** small SPL token received from the `3yac…` DLMM exit
    (an actual residual holding, not a receipt; likely legitimate dust, not a phantom — treat separately).
  - Nothing for these is in `on_chain_balances` (confirms the LP3 no-on-chain-anchor gap).
- **Root cause:** `SessionDashboardQueryService` DOES filter LP receipts (lines 183/230/678 via
  `AccountingAssetFamilySupport.isLpReceiptSymbol`), but that predicate only matches `LP-RECEIPT:`,
  `-LP-`, or a `-LP` suffix. `PENDLE-LPT` (ends `-LPT`), `eqbPENDLE-LPT`, and `MLP` (no pattern) are **not
  recognized**, fall through to generic families (`SYMBOL:PENDLE-LPT`, `SYMBOL:MLP`), get priced, and
  render as spot holdings. Broadening the symbol-suffix heuristic is the WRONG (fragile, hardcoded) fix.
- **Reconciliation with "0 phantom now":** the Phase-1 auditor's "0 provable phantom-open" was measured on
  the `lp_position_snapshots` surface only; the **dashboard holdings surface** has live phantoms
  (PENDLE-LPT 0.33782 on `0xa0dd…`, MLP 0.30960). Both surfaces must be fixed.
- Evidence state: `EVIDENCE_PRESENT_UNUSABLE`. Blast radius: user-visible inflated portfolio value
  (~$3.27k PENDLE-LPT + ~$416 MLP mispriced as spot); plus the aZksZK lending-receipt-as-spot case.

## Closure strategy (revised per architect §F — link-based primary, on-chain cross-check secondary)

**Primary closure = correct link/quantity draining** (deterministic, no new capture universe, no
staked-zero false-close risk):
- C1/C2 make `lp_receipt_basis_pools.qtyHeld` (and net receipt qty) drain to 0 **by correct exit link**;
  `fullyExited()` already trusts `totalLpReceiptQtyHeld == 0`.
- **C4 (EVM CL NFT burn `Transfer → 0x0` / `positions(tokenId).liquidity == 0`)** is the EVM analogue of
  ADR-075's Solana rent-reclaim `LP_EXIT_FINAL` signal — **promoted from optional to a required primary
  closure signal for CL NFTs** (not "subsumed by C3").
- Solana concentrated (DLMM/CLMM) stays on the existing ADR-075 rent-reclaim anchor.

**Secondary = RC-1 effective-on-chain-balance closure cross-check**, a *flagged coverage guard*, NOT the
primary authority (architect §F, B1–B2; auditor R2). It only ever **closes** a position on an
**authoritative summed-family zero** and otherwise raises a coverage flag — never force-closes on absence.

## Changes (ordered — upstream classification first, then closure signals, then the cross-check)

### C1 — RC-3: Meteora DAMM surfacing + receipt-transfer typing (LP1) — normalization/classification
- Mint `correlationId = lp-position:solana:meteora-damm:{poolAddress}:{wallet}` on DAMM `LP_ENTRY`
  (**per-pool + per-wallet** to avoid the multi-wallet collapse C2 fixes for Pendle — architect C5); the
  terminal `remove_liquidity` links as `LP_EXIT_FINAL` (MLP net → 0).
- Classify MLP receipt movements (mint, farm stake/unstake, burn) as **TRANSFER of a non-priced LP
  receipt** (role neither SELL nor BUY); keep MLP **unpriced**. Basis lives on the underlying SOL/mSOL/bSOL
  legs / the new DAMM basis pool.
- Correction points: `SolanaTransactionClassifier` / Solana correlation-id assignment; leg role
  extraction; pricing exclusion for the MLP receipt.

### C2 — RC-2: Pendle correlationId granularity + wrapper linking (LP2) — classification/linking
- Change LP correlation key from `pendle-lp:{network}:{lpTokenSymbol}` to
  `pendle-lp:{network}:{marketOrSyAddress}:{walletLower}` (per market + per canonicalized wallet).
- **Deterministic market-address resolution (auditor R3 / architect B4):** specify that both the direct
  entry and the wrapped `zapOutV3SingleToken` exit resolve the **same** `marketOrSyAddress` — via an
  `eqb/pnp`-wrapper → Pendle-market registry (or market-from-cmETH-source recovery as ADR-047 did).
  Without identical keys the "close by link" fails.
- Map staked wrappers `eqb<X>` / `pnp<X>` (Equilibria / Penpie) to the base LP receipt `<X>` for
  net-quantity, effective-balance, and exit linking, so `zapOutV3SingleToken`-style exits net the receipt
  to 0 and close **by link**, not by relying on the basis pool draining.
- **Read-model parsing (auditor R7 / architect B4):** update `SessionLpQueryService.derivePairFromCorrelationId`
  and `PositionAccumulator` for the new 4-segment `pendle-lp:` key (currently `split(":",3)` folds the
  address+wallet into the pair label).
- Correction points: correlationId assignment; LP-receipt symbol normalization + wrapper→market registry;
  exit linking; read-model key parsing.

### C3 — RC-1: effective-on-chain-balance closure cross-check (LP3) — read-model / verification (flagged guard)
- **Precondition A — candidate universe (architect B1):** extend the balance-refresh candidate set to emit
  a candidate for **every LP-receipt identity known from `lp_receipt_basis_pools`** (join on
  `walletAddress`/`networkId`/`assetIdentity`), independent of net-flow, so a burned/closed receipt yields
  an **explicit authoritative-zero row** distinct from absence. Uses the ADR-067-addendum/ADR-078 hardened
  refresh path (no silent drop, non-destructive per-`_id` upsert, explicit-zero writes).
- **Precondition B — effective family balance (all three reviewers; the critical trap):** the closure
  balance is the **summed receipt family** = wallet base-receipt balance **+ staked-wrapper balance
  (`eqb`/`pnp`) + gauge/farm staked balance**, merged onto the same `accountingIdentity` via the existing
  `ProtocolLockedBalanceProvider` SPI (already used for Jupiter Lend). A non-zero staked/wrapper/gauge
  balance keeps the position **OPEN**.
- **CL NFT keying (architect B3):** EVM CL NFT closure does **not** use `balanceOf`; use
  `ownerOf(tokenId)` (revert/`0x0` when burned) or `positions(tokenId).liquidity == 0`, keyed by
  `(contract, tokenId)` to avoid `_id` collision across multiple positions in the same NFPM. This is C4
  (primary), captured here as its on-chain form.
- **Invariant (auditor R4):** at the **top** of `SessionLpQueryService.resolveClosed`, one-directional —
  an **authoritative summed-family zero ⇒ CLOSED**, overriding the snapshot-open and concentrated-open
  branches. It never forces OPEN and never fabricates an open.
- **Miss-vs-zero symmetry (mandatory, reuses ADR-078):** a **missing/errored** family balance must NOT be
  treated as zero. Missing/errored + fresh ledger ⇒ fall back to the existing internal determination +
  raise a **per-position coverage flag** on `LpPositionView`/health (architect B8).
- **Freshness gate on the zero→closed direction (auditor R5):** the authoritative zero must be at least as
  fresh as the position's latest LP entry/adjust; a stale zero captured before a re-entry must not close a
  re-opened position.
- **Dust tolerance (BA #6):** define the numeric zero threshold (sub-dust effective balance counts as
  authoritative zero ⇒ CLOSED), consistent with ADR-075's basis-dust handling.
- **Layering (architect C6):** put the "authoritative-zero vs missing vs nonzero (family-summed)"
  tri-state in a small **costbasis-side** LP-receipt on-chain-closure resolver keyed via
  `lp_receipt_basis_pools` (`lpCorrelationId → {wallet, network, assetIdentity}`); `resolveClosed` just
  consumes the tri-state, keeping it free of Pendle/Meteora/Velo branching. **GET stays zero-RPC** (reads
  persisted `on_chain_balances` only; all probes run on the background refresh).

### C4 — EVM CL NFT burn as primary closure signal (promoted) — linking/verification
- Treat CL NFT `Transfer → 0x0` (burn) / `decreaseLiquidity`-to-zero as a terminal `LP_EXIT_FINAL` signal
  — the EVM analogue of ADR-075. Primary for CL NFTs; its on-chain form is the `ownerOf`/`positions`
  probe in C3.

### C5 — LP5: Velodrome Optimism `unknown` resolution — linking/verification
- Resolve `0x416b…:vault` to a definite open|closed using ADR-077 fungible-gauge pair resolution +
  effective staked-gauge balance (C3 Precondition B). Ground-truth expectation to be confirmed from the
  staked gauge balance during Phase 4/6 (open-with-pair-label if staked balance > 0, else closed).
  RC-1 alone only closes-on-zero; the open+pair-label resolution comes from ADR-077 + snapshot
  (auditor R6). If the snapshot stays absent/errored, it remains `unknown` + flagged (never fabricated
  open).

### C6 — RC-5: Meteora DLMM linking hygiene (LP4, low) — linking
- Ensure DLMM entry legs are correlated (some empty-flow/uncorrelated). Cosmetic; does not affect closure.
  Optional; may be deferred.

### C7 — LP6: receipt-driven exclusion from the dashboard holdings surface (NEW) — classification + read-model
- **Robust (non-heuristic) recognition:** stop deciding "is this an LP receipt" from symbol spelling.
  Recognize LP-receipt identities from the **classification/capability layer** the entry already produces:
  the `FAMILY:LP_RECEIPT` continuity identity and/or a receipt capability flag (`receiptBearingCollateral`
  / the LP correlation membership), set when the `LP_ENTRY` is classified. `PENDLE-LPT`, `eqbPENDLE-LPT`,
  and `MLP` must resolve to LP-receipt identity via C1/C2 (correlationId + `LpReceiptSymbolSupport`
  normalization), NOT via a broadened `-LP*` suffix match.
- **Dashboard exclusion driven by identity/flag:** `SessionDashboardQueryService`'s three receipt filters
  (currently `isLpReceiptSymbol` at lines 183/230/678) consult the persisted LP-receipt family / flag so
  any classified LP receipt is excluded from the priced spot-asset surface regardless of symbol spelling.
  The economic value lives in the LP position (open) or is realized on exit (closed) — never double-shown
  as a standalone priced asset.
- **Orphaned-receipt drain (depends on C2/C1):** the PENDLE-LPT 0.33782 orphaned on `0xa0dd…` must drain
  to 0 once the Equilibria wrapper exit links (C2), so it leaves holdings entirely (not merely hidden).
  Hiding without draining would still distort covered-qty/AVCO — the drain is the correctness fix; the
  filter is the display guard.
- **aZksZK (Aave lending receipt):** classify `aZksZK` as a lending receipt (extend
  `LendingAssetSymbolSupport` / the C1/C2 registry) so it surfaces as a **lending position** (folded into
  the ZK family per existing lending-receipt handling), not a spot asset. Deterministic registry entry,
  not a hardcoded display hack.
- **SSE (genuine SPL residual):** confirm SSE is an actual held token from the `3yac…` DLMM exit; if so it
  is a legitimate (dust) spot holding and should remain — do NOT suppress genuine assets. Only flag if
  on-chain balance is 0 (disposed) via the C3 anchor.
- Correction points: `AccountingAssetFamilySupport.isLpReceiptSymbol` → identity/flag-driven predicate;
  `LpReceiptSymbolSupport`; `SessionDashboardQueryService` receipt filters; `LendingAssetSymbolSupport`
  (aZksZK); C1/C2 for the drain.

## Edge cases (BA §B — explicit expected outcomes)

| Case | Expected outcome |
|------|------------------|
| **Partial LP exit** (reduced, not closed) | Non-zero effective family balance ⇒ stays **OPEN**; zero-close invariant must NOT fire (mirrors ADR-075 residual). |
| **Re-entry after full exit** | Per-market+wallet / per-pool+wallet key reopens the **same** correlation → previously closed → **OPEN again**; freshness gate prevents a stale prior zero from closing it. |
| **Dust residual balance** | Effective balance below the C3 dust threshold ⇒ **CLOSED**; above ⇒ **OPEN**. |
| **Receipt staked in farm/gauge** (balance in a different contract) | **OPEN** while staked (effective = wallet + staked); **CLOSED** only when the summed family is authoritative zero. |
| **Multi-wallet same pool** | Per-wallet: wallet A fully exited + wallet B still in ⇒ A **CLOSED**, B **OPEN** (per-wallet keys + per-wallet on-chain check). |
| **Stale / not-yet-ingested disposal** | On-chain zero from a not-yet-normalized disposal: LP closure follows the ADR-078 freshness gate — do not close ahead of ingestion if the ledger is stale-vs-capture; flag instead. |

## Per-example user-visible outcome (BA §D)

| Reported example | After fix |
|------------------|-----------|
| Pendle `PENDLE-LPT` `0xc79e…9931` | **CLOSED** by exit-link (per market+wallet); realized LP P&L shown; drops from OPEN list; cmETH basis stays at LP cost (no +$2,228 spike). |
| Meteora DAMM `39g1…4WP`, `43oo…o1hT` | **Surface** as `lp-position:solana:meteora-damm:{pool}:{wallet}`, then **CLOSED** after terminal remove; MLP shows **no** SELL/BUY trade tag. |
| Meteora DLMM `3yac…TeDj` | **CLOSED** (ADR-075 rent-reclaim). |
| Velodrome `0x416b…:vault` | Resolves to a **definite** open (with pair label, staked in_range) or **CLOSED** — committed from the effective staked balance; no lingering `unknown`. |
| Controls: Uniswap CL `…5527422`, GMX weth-usdc | **Remain OPEN**. |
| **Dashboard holdings (LP6):** `PENDLE-LPT`, `MLP`, `eqbPENDLE-LPT` | **Removed** from the spot-asset list (drained to 0 by exit-link for closed positions; excluded as non-priced receipt for open ones); portfolio total value drops by the ~$3.27k PENDLE-LPT + ~$416 MLP mispricing. |
| **Dashboard holdings:** `aZksZK` | Shown as a **lending** position (ZK family), not a spot asset. |
| **Dashboard holdings:** `SSE` | Remains as a genuine (dust) SPL holding if on-chain balance > 0; closed only if disposed. |

## Docs to update (Phase 4, before code)
- **ADR (new): "On-chain-balance LP closure cross-check invariant"** — RC-1/C3/C4: authoritative
  summed-family zero ⇒ CLOSED (flagged coverage guard, not primary authority); covers **fungible
  receipts** (EVM + Meteora DAMM MLP), **EVM CL NFTs via ownerOf/burn**, while **Solana CL** stays on
  ADR-075; requires the candidate-universe extension + effective family-sum + freshness + dust threshold;
  cross-refs ADR-067 addendum, ADR-078, ADR-075, ADR-077. **Requires explicit user approval.**
- **ADR (new): "LP correlation-id granularity & staked-wrapper receipt linking"** — RC-2/RC-3; must be
  worded as **superseding ADR-023 D3** and **amending ADR-047 item 4** (`marketIdFromSymbol`); specify the
  deterministic market/SY-address resolution for wrapped exits; `meteora-damm:{pool}:{wallet}` family key;
  LP-receipt movements are non-priced TRANSFERs. **Requires explicit user approval.**
- `docs/pipeline/normalization/rules/` — Meteora DAMM (MLP receipt) rule pack + negatives; Pendle
  wrapper-exit rule; cross-ref `results/lp-phantom-open-protocol-rule-pack.md`.
- `docs/pipeline/cost-basis/` — LP receipts are non-priced; basis lives in underlying legs /
  `lp_receipt_basis_pools`.
- ADR INDEX update.

## Acceptance (rerun `--skip-frontend`, then re-audit)
- **Link-based closure (C1/C2/C4):** the 4 example txs stay CLOSED; Pendle `0xc79e…9931` closes **by link
  with the basis-pool short-circuit disabled** (proves the invariant, not the mask); Velodrome no longer
  `unknown`.
- **On-chain cross-check (C3), miss-vs-zero symmetry (mandatory both directions):**
  - a burned receipt → explicit authoritative-zero row → **CLOSED** (positive zero-closes test);
  - a simulated missing/errored capture does **NOT** close a genuine open (Uniswap CL `…5527422`, GMX
    weth-usdc stay OPEN) and raises a coverage flag;
  - a **staked** receipt with wallet balance 0 but staked balance > 0 stays **OPEN** (staked-family test);
  - a **partial exit** (non-zero effective balance) stays **OPEN**;
  - a **re-entry after zero** stays/returns **OPEN** (freshness).
- **`unknown` elimination:** any position with a captured effective on-chain balance resolves a definite
  **open|closed** (never `unknown`); residual `unknown` only for uncaptured/errored balance, and flagged.
- **Accounting deltas pinned (not "unchanged"):**
  - **MLP mis-tag removed:** portfolio realized P&L changes by **≈ +$12.46** (elimination of the MLP
    STAKE loss); assert the new value is correct, not that it's unchanged (auditor R1 / BA #12).
  - **SOL family:** measure covered-qty + realized P&L before/after; expected delta ≈ 0 beyond the MLP
    re-route (net MLP = 0); pin the baseline number.
  - **cmETH family (auditor R3):** pin cmETH net AVCO at ADR-047 **LP-cost** basis (no spot spike); assert
    no +$2,228 regression.
  - **Unaffected families:** ETH ≈ $3,015.56, TON pool ≈ $1.506 unchanged; no new uncovered/shortfall.
- **Idempotent rerun:** re-running normalization/linking yields the same open/closed set; deterministic
  keys; no duplicate snapshots.
- **Dashboard holdings surface (LP6):** after renorm, `asset_ledger_points` shows `PENDLE-LPT` on
  `0xa0dd…` drained to 0 (exit linked via the `eqb` wrapper); no LP-receipt symbol (`PENDLE-LPT`,
  `eqbPENDLE-LPT`, `MLP`) appears in the dashboard spot-asset list; `aZksZK` appears under lending, not
  spot; `SSE` remains only if genuinely held (on-chain > 0). Portfolio total value no longer includes the
  ~$3.27k PENDLE-LPT / ~$416 MLP receipt mispricing. Receipt exclusion is driven by LP-receipt
  identity/flag, not symbol spelling (verify a receipt with a novel symbol is still excluded).

## Risks / notes
- **Renormalization required** (C1/C2 change classification + correlation) → `--skip-frontend` full reset;
  C3 also needs a balance-refresh pass that captures LP-receipt/NFT + staked rows.
- **Cost (architect §E):** the candidate-universe extension + per-tokenId `ownerOf`/`positions` reads +
  gauge/wrapper staked reads run on the **background refresh** (batched via Ankr→BlockScout→Etherscan→RPC
  + the ADR-077 `DexGaugePoolResolver` cache), never on GET. Quantify added candidate count vs
  lane/rate budget before implementing.
- **Simpler-alternative fallback (architect §F):** if C3's candidate-universe + family-sum proves too
  costly/risky, ship C1/C2 + C4 (link-based) as the closure authority and keep RC-1 as a flag-only
  cross-check; the phantom-open cases close without the new capture universe. The plan already treats RC-1
  as secondary for this reason.
- Determinism: correlation keys stable across reruns (canonical addresses; per market/pool + per wallet).
- LP4/C6 is cosmetic and may be deferred.

## Phase-3 review resolutions (2026-07-22)
- **financial-logic-auditor** (REVISE→folded): R1 pin ±$12.46/SOL/basis deltas (not "unchanged"); R2
  RC-1 needs targeted per-receipt/NFT probe + candidate-universe extension (absence ≠ zero); R3 cmETH
  spot-spike guard + market-address resolution + separate cmETH acceptance; R4 zero-check at top of
  `resolveClosed`, one-directional; R5 freshness gate on zero→closed; R6 C5 open-resolution via ADR-077
  (not RC-1); R7 read-model key parsing for the new segment.
- **business-analyst** (REVISE→folded): #1 effective (wallet+staked) balance false-close fix; #2
  engine-vs-inputs boundary wording; #3 GMX control clarity; #4–#9 explicit edge-case table; #10–#13 DoD
  precision (dust tolerance, non-zero stays-open, SOL baseline, `unknown` scope); #14–#15 per-example
  outcome table + closed-list UI semantics.
- **system-architect** (REVISE→folded): B1 candidate universe from `lp_receipt_basis_pools`; B2 staked
  family-sum via `ProtocolLockedBalanceProvider` (false-close fix); B3 CL NFT `ownerOf`/burn + per-tokenId
  keying (C4 primary); B4 ADR-023 D3 supersession + ADR-047 amend + deterministic market resolution +
  pair-label parsing; C5 per-wallet DAMM key; C6 costbasis-side tri-state resolver + zero-RPC GET; C7
  ADR-075 scoping; C8 per-position coverage flag; §E cost on background refresh; §F link-based-primary
  strategy adopted.
