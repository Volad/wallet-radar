# Per-Asset AVCO for Staked / Derivative Assets — Definitive Long-Term Model

**Status:** PROPOSED long-term design (Phase 2) — awaiting user approval to implement (stop gate). Existing data is disposable (full renormalization + replay reset assumed).
**Date:** 2026-07-12 (rev. 2026-07-13 — reframed from mitigation to definitive correct model per user directive)
**Slug:** `eth-family-tracking-model`
**Directive:** *"I don't need to mitigate the problem. I need financially correct long-term behaviour. A single valid long-term solution for AVCO calculation per family assets (staked assets which can be independently sold or converted to each other or to independent assets). I don't care about existing data — we can always renormalize."*
**Related ADRs:** ADR-004 (physical quantity vs basis-backed), ADR-017 (timeline AVCO authority — amended), ADR-040 (dual Net/Tax AVCO), ADR-045 (family covered-weighted move-basis series), ADR-031 (AVCO undefined).

> §1–§5 are the diagnostic background (kept for traceability). **§6 onward is the definitive model and the straight instructions to proceed.** Sections about "materiality on current data" are retained only as evidence; per the directive, correctness — not current-data impact — drives the design.

---

## 1. Scope

- **Accounting model under review:** `AccountingAssetFamilySupport.SYMBOL_FAMILIES` — the mapping that collapses ETH + all ETH derivatives into a single `FAMILY:ETH` continuity identity, and the replay handlers that carry basis **1:1 by covered quantity** between family members.
- **Assets actually present in this dataset:** `ETH`, `WETH`, `AWETH`, `AARBWETH`/`AMANWETH` (Aave receipts), `VBETH`, `CMETH`, `METH`. (No `wstETH`/`weETH`/`rETH`/`cbETH`/`rsETH` holdings — but they are in-scope for the model decision because the map claims them.)
- **Wallets:** `0x1a87f12…`, `0xf03b52e8…`, `0x68bc3b81…`, `BYBIT:33625378`, `BYBIT:409666492`.
- **Out of scope:** BTC/USDC/USDT/AVAX/MNT/SOL families (same pattern, but not the reported symptom); the LP-exit fee split (BLOCKER-2) and Balancer V3 (BLOCKER-3) tracked separately in `results/`.

---

## 2. Current model (what exists today)

`AccountingAssetFamilySupport` maps three economically-distinct classes into one `FAMILY:ETH` bucket and the replay engine treats them as **fully fungible 1:1**:

| Class | Members mapped to FAMILY:ETH | Economic relation to ETH | 1:1 valid? |
|---|---|---|---|
| Native / wrapped | ETH, WETH, AWETH, AETHWETH, AARBWETH, ALINWETH, AMANWETH, AZKSWETH, ABASWETH, AOPTWETH, VBETH, YVVBETH | exactly 1:1 (wrap / rebasing aToken) | ✅ yes |
| Rebasing LST | STETH | ~1:1 (balance rebases up) | ⚠️ approx |
| **Value-accruing LST/LRT** | **WSTETH, RETH, CBETH, METH, CMETH, EETH, WEETH, EWEETH, EZETH, RSETH, OSETH** | **redemption rate > 1 and drifting up** | ❌ **no** |

Replay carry path (no exchange-rate normalization):
- `LiquidStakingReplayHandler.applySelected` / `allocateInbound` — STAKING_DEPOSIT/WITHDRAW: removes outbound basis, re-attaches proportionally to inbound **covered quantity**.
- `FamilyEquivalentCustodyReplayHandler` — LENDING/VAULT/wrap custody transfers carry basis across the pair.
- `TransferReplayHandler` carry / corridor logic.
- Read-model rollup: ADR-045 family covered-weighted AVCO series aggregates all spot-family members into one line.

---

## 3. Root cause / mechanism

**The 1:1 fungibility assumption is only valid for wrappers with a fixed 1:1 redemption (WETH↔ETH, rebasing aTokens). It is invalid for value-accruing LST/LRT tokens whose ETH redemption rate drifts above 1.**

When a value-accruing token is carried 1:1:
1. `ETH → wstETH` (e.g. 1.0 ETH → ~0.83 wstETH) removes 1.0 ETH of basis and re-attaches it to 0.83 units → **per-token AVCO inflates**.
2. Because both live in `FAMILY:ETH`, the family covered quantity drops (1.0 → 0.83) while total basis is preserved → the ADR-045 covered-weighted family AVCO **inflates spuriously**; the reverse conversion deflates it.

This is the theoretical mechanism behind "AVCO взлетел / сломался" around wrap/unwrap, staking deposit/withdraw, lending withdraw, and CEX corridor moves of these assets.

---

## 4. Independent findings (parent recon — to be corroborated by auditor)

> These reframe the problem for **the assets actually held here**. Final quantified two-way AVCO reconstruction is pending the `financial-logic-auditor` deliverable (`results/eth_basis.md` refresh).

1. **Exchange-rate drift is small for the held assets.** Real `cmETH/ETH` price ratio in `historical_prices` is **~0.97–1.01** at conversion times (cmETH is a young, ~1:1 Mantle LST). The apparent `0.44` reading was a data-staleness artifact (stale cmETH quote vs fresh ETH quote). ⇒ **Model B (rate normalization) buys little on this dataset**; its value is future-proofing for wstETH/weETH/rETH.

2. **The real AVCO swings come from two other mechanisms:**
   - **Cross-venue basis blending inside one family.** `FAMILY:ETH` blends bases from radically different acquisition contexts into one covered-weighted average — Bybit cmETH staking @ **$3,488** (Jan 2025), on-chain spot ETH @ **~$2,000**, corridor WETH @ **$3,039**. As quantity moves between venues/pools, the family average re-weights → the large jumps. ADR-045 acknowledges this as economically-real, but it is amplified by mixing venue-staking basis with spot basis under one identity. (Auditor prior recon: combined ETH-family AVCO for `0x1a87…` = **~$2,799** blending ETH + AWETH + AMANWETH.)
   - **Dual Tax/Net lane divergence** (ADR-040/ADR-051): cmETH `REWARD_CLAIM` books **zero net cost** (income), dragging Net AVCO down over time — Net **$1,873** vs Tax **$3,945** (a $2,072 gap emerging at the 2025-08-01 cmETH acquisition and persisting to the terminal $3,688).

3. **The 1:1 legs that are genuinely correct:** `aWETH→WETH→ETH` (Aave rebasing aToken + WETH + unwrap) are truly 1:1; per-asset those legs are fine. Their AVCO swing is the family-rollup blending, not a per-asset carry error.

**Reframed decision question:** less "are LSTs fungible 1:1 with ETH?" and more **"should venue-staking-derived / value-accruing ETH-derivative basis blend with on-chain spot ETH basis in one AVCO pool?"**

### 4.1 Terminal materiality (parent DB reconstruction, 2026-07-12)

Terminal `FAMILY:ETH` covered-weighted **Tax AVCO = $2,855.59** / **Net AVCO = $2,768.20** over covered **4.0122 ETH**, basis **$11,457**. Per-bucket:

| Wallet | Symbol | qty | covered | Tax AVCO | Net AVCO | basis | Class |
|---|---|---|---|---|---|---|---|
| `0x1a87…` | AMANWETH | 3.06 | 3.06 | $3,043 | $3,013 | $9,311 | **1:1 wrapper (Aave WETH)** |
| `0x1a87…` | AWETH | 0.75 | 0.748 | $1,829 | $1,733 | $1,367 | 1:1 wrapper (Aave WETH) |
| `BYBIT:33625378` | CMETH | 0.10 | 0.10 | $3,688 | $1,830 | $369 | value-accruing LST |
| `BYBIT:33625378` | ETH | 0.10 | 0.10 | $4,015 | $4,019 | $402 | spot |
| `0x1a87…` | ETH | 0.0035 | 0.0035 | $1,697 | $736 | $6 | spot |
| `0xf03b52…` | ETH | 0.0008 | 0.0008 | $2,760 | $2,771 | $2 | spot (bridge uplift) |

**Critical finding — the single-family 1:1 defect is NOT the material driver on this dataset:**
- The high family AVCO (~$2,855) is **dominated (81% of basis) by AMANWETH 3.06 @ $3,043** — a legitimate **1:1 Aave WETH receipt** funded via a Bybit corridor deposit at ~$3,039. Per-asset this is correct ETH cost basis.
- The only value-accruing member actually held is **cmETH 0.10 ETH ($369 basis)**. Splitting it out changes terminal family AVCO by only ~$21 ($2,855 → ~$2,834) — **immaterial now**.
- Therefore the terminal $2,855 is **defensible, not an error**. The "AVCO взлетел до $4.12k" the user saw is a **transient ADR-045 read-model covered-weighted swing** while quantity moved between heterogeneous sub-pools (cmETH swap → USDT → ETH), amplified by blending venue/corridor-origin basis with on-chain spot in one line — not a terminal basis defect.
- The value-accruing 1:1 carry is a **latent correctness bug**: immaterial today (0.10 cmETH, rate≈1) but would bite hard with a real wstETH position (~1.2× rate) or larger LST holdings.

---

## 5. Candidate models

| # | Model | AVCO pool | ETH↔derivative conversion | Pros | Cons |
|---|---|---|---|---|---|
| **A** | Status quo | one `FAMILY:ETH`, 1:1 | carries basis, no P&L | simple; "it's all ETH"; deterministic | 1:1 wrong for value-accruing; cross-venue blending swings; ADR-045 needles |
| **B** | Single family + rate-normalized ETH-equivalent qty | one `FAMILY:ETH`, quantities scaled by `ethPerToken` at event | carries basis, no P&L | theoretically most correct; keeps "hold ETH" mental model | needs deterministic historical redemption rate at backfill; **small benefit on this dataset (rate≈1)**; complexity |
| **C** | Split families | value-accruing LST/LRT get own identity + AVCO bucket | ETH↔LST = taxable swap (realize P&L at market) | stable spot-ETH AVCO; no cross-venue blend; clean per-asset | treats staking/unstaking as disposal (may mismatch user "still holding ETH"); needs reliable LST market price |
| **Hybrid** | 1:1 wrappers + rebasing stay in FAMILY:ETH; value-accruing LST/LRT split out (Model C for those) | ETH/WETH/aWETH one pool; wstETH/cmETH/etc. own pools | wrappers carry (no P&L); value-accruing convert = swap | targets the actual defect; keeps true wrappers fungible; future-proof | LST market pricing needed; policy call on "wrap = taxable" |

**Secondary lever (independent of A/B/C):** venue-staking vs spot basis blending. Even within Model A, an optional refinement is to **not blend Bybit-earn/staking-origin ETH basis with on-chain spot ETH** in the ADR-045 rollup (separate sub-series), which addresses the biggest observed swings without changing conversion P&L semantics.

---

## 6. Definitive long-term model — "One asset, one cost-basis pool"

The financially-correct model is a single, universal principle:

> **Every distinct economic asset has exactly one AVCO (cost-basis) pool, unified across chains and custody locations. Two representations share a pool ONLY IF they are the same underlying claim, redeemable 1:1 on demand at a fixed rate. Any movement between distinct assets is a disposal + acquisition priced at market, realizing P&L. Any movement within the same asset carries basis with no P&L.**

This resolves all three cases in the directive:
- **Independently sold** (cmETH → USDT, stETH → ETH on a DEX/CEX): the staked asset has its own AVCO; the sale realizes P&L against *its* AVCO at market price. (Today: wrong — it disposes against a blended ETH-family AVCO.)
- **Converted to each other** (cmETH → wstETH, stETH → rETH): dispose the source at market (realize P&L vs source AVCO), acquire the target at market (fresh basis). Two independent pools, cleanly.
- **Converted to independent assets** (weETH → USDC): a normal swap against the weETH pool.

### 6.1 Asset identity classification (the whole model in one table)

| Category | Membership rule | Identity | Conversion accounting |
|---|---|---|---|
| **C1 — Same-asset custody representations** | Same underlying, redeemable **1:1 on demand at fixed rate** | share the underlying's family, e.g. `FAMILY:ETH` | **carry basis, NO P&L** (REALLOCATE) |
| **C2 — Distinct market assets (staked / value-accruing / restaked / yield-vault shares)** | Own market price; NOT fixed-1:1-redeemable; can be independently sold | **own per-token family**, unified across chains, e.g. `FAMILY:WSTETH`, `FAMILY:CMETH`, `FAMILY:STETH` | **disposal + acquisition at market, REALIZE P&L** |
| **C3 — Everything else** | default | own asset identity | normal swap semantics (already correct) |

**Boundary rule to encode (declarative, prevents future misclassification — per BA review):**
- Rebasing receipt where **balance grows and quantity == underlying 1:1** (Aave aTokens) → **C1**.
- Receipt/share where **quantity is fixed and the exchange/redemption rate drifts** (Compound/Venus vTokens, ERC-4626 vault shares, all value-accruing LST/LRT) → **C2**.
- A **receipt of a C2 asset** (Euler/Yearn wrapping an LST/LRT) → **C2**.

**C1 for ETH (share `FAMILY:ETH`, no P&L on move):**
- `ETH ↔ WETH` (canonical wrap — always 1:1, redeem on demand)
- `WETH ↔ aWETH` and all Aave `A*WETH` variants (rebasing aToken, quantity stays 1:1 with WETH; accrued interest = income)
- Same ETH **bridged across chains** (canonical corridor) — same asset, different chain

**C2 for ETH-derivatives (each its OWN `FAMILY:<TOKEN>`, P&L on conversion):**
- Liquid staking: `STETH`, `WSTETH`, `RETH`, `CBETH`, `METH`, `CMETH`, `OSETH`
- Restaking: `EETH`, `WEETH`, `EWEETH` (Euler receipt of weETH — currently mis-mapped to `FAMILY:ETH`, must move out), `EZETH`, `RSETH`
- **`VBETH`** — Venus (Compound-style) vToken: fixed balance, **exchange rate drifts >1** → behaves like an LST → **C2** (default). Only C1 if the pipeline tracks it in underlying-equivalent units with a reliable per-event exchange rate + interest-as-income (no such oracle today ⇒ C2).
- **`YVVBETH`** — Yearn ERC-4626 vault share (value-accruing price-per-share, tradeable) → **C2**.
- Any other value-accruing yield-vault share of ETH (ERC-4626).

> **Note on stETH:** although Lido stETH rebases ≈1:1, it is an **independently-tradable asset with depeg risk and its own market price** → C2 (own pool), not C1. The "≈1:1" property does not make it the same claim as ETH.

> **Same-token custody moves stay carry (no P&L) even for C2.** A C2 token moving between custody locations *as the same token* — e.g. a **cmETH CEX→wallet corridor deposit**, or cmETH bridged across chains — is NOT a conversion; it carries basis with no P&L (it is the same-asset case within that token's own family). P&L is realized only when the asset identity actually changes.

### 6.2 Yield / rewards / rebases (income recognition)

- **Value-accruing (non-rebasing) C2 tokens** (wstETH, cmETH, weETH, rETH…): yield is embedded in price; it materializes as **unrealized gain in the token's own AVCO** and is realized only on disposal. No special handling — correct by construction.
- **Rebasing C2 tokens** (stETH) and **C1 lending receipts** (aWETH interest): the balance grows. Each accrual is **income booked at market value** → `quantity += Δ`, `basis += Δ × marketPrice`, income realized = `Δ × marketPrice`. This keeps AVCO stable (yield is income, not a basis dilution) — the financially correct treatment. (If an accrual event is not individually captured, it is reconciled at the next priced event.)

### 6.3 Why this is the single correct model (vs the alternatives)

- **Not Model A (one family, 1:1):** conflates distinct assets; a cmETH sale realizes P&L against the wrong (ETH-blended) basis; wstETH's 1.2× rate corrupts family quantity. Financially wrong.
- **Not Model B (rate-normalized single family):** requires a deterministic historical redemption-rate oracle per token at backfill time (not reliably available), and still hides genuinely distinct assets (different depeg/price risk) behind one AVCO. Over-engineered and still economically lossy.
- **This model (per-asset pools + market-priced conversions):** matches how the assets actually behave (independently priced & tradable), needs only market USD price at the event (already the pricing chain's job; C2 tokens are priceable because they are tradable), is deterministic, and unifies the three directive cases under one rule.

---

## 7. Consolidated vision of changes (upstream → downstream) — corrected per architect review

> **Architect correction — three identity layers must stay separate** (do NOT re-key positions):
> - **L1 asset identity** (classification / continuity): what governs carry-vs-realize.
> - **L2 cost-basis pool**: keyed per `(wallet, network, contract)` as today — even ETH is not one cross-chain pool. **Leave pool keying unchanged.**
> - **L3 read-model aggregation**: cross-chain / cross-wallet unification is a **read-model rollup** (parallel to ADR-045), NOT a position re-key.
>
> Consequence: **realized P&L falls out for free** — once a C2 token no longer shares `FAMILY:ETH`, an ETH↔C2 move is already a generic DISPOSE+ACQUIRE at the flow's pre-resolved `unitPriceUsd`. **No new replay handler; routing stays pricing-free** (replay reads pre-resolved prices; pricing is not on the replay critical path).

1. **Asset identity (`AccountingAssetFamilySupport`) + single declarative registry.**
   - Remove all C2 symbols from the `FAMILY:ETH` mapping. Each C2 token gets its own L1 identity `FAMILY:<TOKEN>` (its per-token continuity identity). **Cross-chain unification is L3 read-model, not a pool re-key** (§ above).
   - Keep only C1 (ETH, WETH, Aave `A*WETH` receipts, bridged ETH) in `FAMILY:ETH`.
   - Consolidate the C1/C2 boundary into **one declarative registry** (`C1_SAME_ASSET` / `C2_DISTINCT_ASSET`) that pricing, pooling, and read-model all read — with an **ArchTest** so the three can't drift apart.
   - Split `canonicalTokenIdentity()` (contract-first, symbol-fallback, spoof-guarded → the token itself, e.g. CMETH) from `priceIdentity()`.
2. **Conversion routing (replay) — no new handler.**
   - `ReplayTransferClassifier`: a move is **basis-carry** (REALLOCATE, no P&L) **iff both legs share the same canonical-token identity** (C1↔C1 same family, or same-token cross-chain/cross-venue incl. corridor). Otherwise it is the existing generic **disposal+acquisition** at `flow.unitPriceUsd`.
   - `LiquidStakingReplayHandler` / `FamilyEquivalentCustodyReplayHandler`: stop firing for ETH↔C2 (they exist to carry basis 1:1); those now flow through the generic swap/disposal path. Gate carry on **canonical-token identity**, not family membership (clarifies ADR-019/029/043 corridor carry).
3. **Pricing — the load-bearing change (architect: currently silently defeats the model).**
   - **`CanonicalAssetCatalog` actively aliases `CMETH/METH/WEETH → ETH` and flags them `isPeggedNative`; `WSTETH` is priced as stETH.** These MUST be removed/corrected, or C2 "disposal at market" silently uses ETH's price = Model A with extra steps.
   - Each C2 token must resolve its **own** market USD price at each conversion timestamp via the resolver chain (Bybit K-line / DEX-derived / CoinGecko last resort per policy).
   - Rebasing/lending income accrual valued at market at the accrual (or next-priced) event (§6.2).
4. **Read-model (ADR-045 / timeline).**
   - `includeInSpotFamilyTimelineAggregation`: C2 tokens no longer roll into the `FAMILY:ETH` spot line; each C2 renders its **own** per-asset AVCO line. `FAMILY:ETH` shows only true same-asset ETH.
   - Cross-chain/cross-wallet unification for a single C2 token is an L3 aggregation here (not a pool merge).
   - Optional informational "total ETH-denominated exposure" overlay — clearly labelled, never an AVCO source.
5. **Invariants / tests.**
   - ArchTest binding the single C1/C2 registry across pricing/pooling/read-model.
   - Conservation: on every C2 conversion, source pool basis −= disposed cost, realized P&L = proceeds − disposed cost, target basis = proceeds; no stranded basis.
   - Unpriceable C2 event → **replay-time fail-closed** to `AVCO undefined` (ADR-031 extended), never a silent 1:1 carry.

---

## 8. Documentation (ADR list per architect review)

- **New ADR** `docs/adr/ADR-0NN-per-asset-avco-for-staked-derivatives.md`: the "one asset, one pool; C1 carry / C2 realize" rule + the three-layer identity model (L1 identity / L2 pool per (wallet,network,contract) / L3 read-model unification).
- **Supersede** the ADR-017 staked-ETH inclusion clause.
- **Amend ADR-045** (family membership — C2 excluded from the ETH spot series).
- **Amend ADR-031** (extend AVCO-undefined to **replay-time fail-closed** for unpriceable C2 conversions).
- **Clarify ADR-019 / ADR-029 / ADR-043** (corridor carry gated on **canonical-token identity**, so a C2 same-token corridor still carries).
- **Cite ADR-004 / ADR-040** (physical-qty/basis-backed; dual Net/Tax lane interaction).
- Update `docs/pipeline/cost-basis/`, `docs/reference/ledger-points-and-basis-effects.md`, `docs/overview/04-data-model.md`: C1/C2 registry, conversion P&L semantics, income recognition, three-layer identity.

---

## 9. Acceptance (DoD — refined per BA review)

- **[A-1] Identity separation.** For every C2 token, `continuityIdentity()` returns `FAMILY:<TOKEN>` (never `FAMILY:ETH`), asserted via declarative `C1_SAME_ASSET` / `C2_DISTINCT_ASSET` sets. `EWEETH` (and any receipt-of-C2) resolves to a C2 family.
- **[A-2] C1 carry, no P&L.** `ETH↔WETH↔aWETH` and all `A*WETH` cross-chain moves → REALLOCATE, `realizedPnL == 0`, total basis preserved.
- **[A-3] C2 conversion realizes P&L at market.** Every C1↔C2, C2↔C2, C2↔stable/other move realizes P&L = `proceeds − disposedCost` vs the **source** pool; target basis == proceeds.
- **[A-4] Same-asset custody carry (both classes).** Same-token cross-venue/cross-chain moves (incl. **cmETH CEX→wallet corridor**, C2 bridged) carry basis with **no P&L**.
- **[A-5] Basis conservation.** Global `Σ basis_out = Σ basis_in + Σ disposedCost`; no stranded basis. Verified by `PortfolioConservationGate` + per-asset reconciliation for each C2 pool.
- **[A-6] Income invariants** (stETH rebase, aWETH interest):
  - **[I-1]** each accrual in the AVCO-authoritative (Tax) lane: `qty += Δ`, `basis += Δ × market price at accrual/next-priced event`; per-unit AVCO unchanged by a pure accrual (within pricing tolerance).
  - **[I-2]** no accrual path adds quantity at **zero basis** in the AVCO-authoritative lane (forbids the current `REWARD_CLAIM` zero-cost behaviour that caused the §4 Net/Tax $2,072 gap).
  - **[I-3]** if the ADR-040 dual Net/Tax lane is retained, its income-offset is an explicit documented policy keyed off the same priced accrual — never an accidental un-priced artifact.
  - **[I-4]** realized income is reconcilable as income, not as a change in average cost.
- **[A-7] Partial/dust determinism.** Partial conversions dispose proportional basis; residual per-unit AVCO unchanged; dust deterministic, no stranded basis.
- **[A-8] Unpriceable fallback.** A C2 conversion with no obtainable market price → explicit `AVCO undefined` (ADR-031) + flag; **never** a silent 1:1 carry.
- **[A-9] Cross-chain unification.** Same C2 token on ≥2 chains shares exactly one pool.
- **[A-10] Read-model.** `FAMILY:ETH` line contains only C1 same-asset ETH; each C2 renders its own AVCO line; no C2 quantity rolls into the ETH spot series.
- **[A-11] Determinism.** Full reset + rerun reproduces identical pools and realized P&L.
- **[A-12] Non-ETH regression.** Same C1/C2 rule applied to BTC/AVAX/MNT/SOL families (`SAVAX`, `BBSOL` → C2 own pool; `WBTC`/`A*WBTC` → C1 only if 1:1-redeemable).
- **[A-13] VBETH boundary decision recorded** in the ADR (C2 default; underlying-equivalent-units exception stated explicitly).

### Open stop-gate questions (from BA review — need user decision before ADR is written)
1. **VBETH:** confirm Venus exchange-rate vToken → default **C2**? (Or is there a reliable exchange-rate feed to keep it C1-with-income?)
2. **Net lane policy:** keep ADR-040 dual Net/Tax lanes with documented income-offset, or converge on the single market-priced (Tax) treatment?
3. **"Wrap = taxable" confirmation:** `ETH→cmETH` staking realizes P&L in your mental model? (Directive implies yes — staked assets are independently sold/converted — but confirm before the ADR.)

---

## 10. Straight instructions — how to proceed next

1. **(optional, recommended) Phase 3 design review** — `system-architect` (module boundaries, determinism, cross-chain identity, pricing-chain fit) + `business-analyst` (edge cases, C1/C2 boundary list, acceptance/DoD). Skip if the user says "implement now".
2. **Phase 4 — docs first:** write the new ADR + update cost-basis/data-model docs (needs user OK on the ADR since it changes accounting policy).
3. **Phase 5 — implement (`backend-dev`)** in the order of §7 (identity → routing → pricing → read-model → tests).
4. **Phase 6 — verify:** `./scripts/prod-reset-rebuild-backend.sh --clear-pricing-cache --skip-frontend` (pricing policy changes → clear cache), wait for normalization + replay, then reconcile each C2 pool and global conservation against acceptance §9.

---

## 11. Risks

- **Pricing aliases silently defeat the model (architect: top risk).** `CanonicalAssetCatalog` aliases `CMETH/METH/WEETH→ETH` (`isPeggedNative`) and prices `WSTETH` as stETH. If not removed, C2 "disposal at market" uses ETH's price and the whole change is a no-op. This is the first thing to fix and to test.
- **Pricing coverage for C2 at every conversion timestamp** is load-bearing (a missing price blocks P&L realization) → confirm/extend sources; replay-time fail-closed to `AVCO undefined` (ADR-031), never a silent 1:1 carry.
- **Do NOT re-key positions.** Keep L2 pools per (wallet, network, contract); cross-chain unification is L3 read-model only. Re-keying would be an invasive, risky migration for no accounting benefit.
- **Boundary calls** (C1 vs C2) must live in one declarative registry with an ArchTest; wrong placement = wrong P&L. VBETH/YVVBETH default C2 (see §6.1).
- **Full renormalization + replay reset required** (accepted — existing data disposable); `--clear-pricing-cache` needed because pricing aliases change.

---

## 12. Status

- [x] Definitive model specified (§6) + consolidated change vision (§7) + straight instructions (§10).
- [x] Phase 3 design review: **business-analyst APPROVE-with-changes** (C1/C2 list, VBETH/YVVBETH→C2, income invariants, DoD A-1…A-13) and **system-architect APPROVE-with-changes** (3-layer identity, no position re-key, pricing-alias removal, ADR list, single declarative registry) — folded into §6–§8, §11.
- [ ] **STOP GATE:** await user approval (ADR/policy change) before Phase 4 code, plus the 3 open questions in §9.
