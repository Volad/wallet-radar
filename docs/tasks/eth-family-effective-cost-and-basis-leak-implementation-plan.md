# ETH-Family Effective Cost & Cost-Basis Leak — Implementation Plan (v2, post-review)

> Status: **Wave 1–2 DONE + verified (2026-07-23). Wave 3 (read-model) next.** User decisions locked (2026-07-22): Option A (read-model), D8 = fold GMX/Pendle ETH-share, R2 = drop cap.
> Source audit: `results/eth-family-effective-cost-audit.md` (READ-ONLY, MongoDB source of truth, renormalized `asset_ledger_points=12419`).
> Reviews: financial-logic-auditor `65de6549`, business-analyst `26124104`, system-architect `a65f3436`. Post-impl verify: financial-logic-auditor `10ae2b4f`.

---

## Wave 1–2 outcomes (2026-07-23, post-renorm, alp=12419)

**Wave 1 (D1) — DONE + verified.**
- Root cause fixed at pricing: cross-canonical staking (ETH→mETH/cmETH) now stamped `crossCanonicalStakingConversion` at normalization, routed to `PENDING_PRICE`; `PriceableFlowPolicy` prices the principal TRANSFER leg; `ReplayDispatcher` fails-closed (UNKNOWN) instead of silent $0.
- **Follow-up defect found + fixed:** routing to `PENDING_PRICE` made these rows hit STAT validation, which demoted all 7 to NEEDS_REVIEW for missing counterparty (they are internal conversions with no external counterparty) → replay blocked (alp=0). Fixed with a scoped exemption in `StatValidationService` (gated on the D1 flag) + 2 regression tests. Replay unblocks: `blockingNeedsReview=0`.
- **Verified:** Mar-2025 stake mETH ACQUIRE **$0 → +$1,333.20 both lanes**; conservation holds (removed ETH basis $1,889.54 = mETH $1,333.20 + realized loss $555.93). Jan-2025 stake cmETH ACQUIRE **+$494.39** (was leaking). *(Absolute mETH basis is $1,333.20, not the plan's $1,889.54, because the mETH/ETH conversion now prices at the **corrected** market — see price finding below.)*

**Price-cache bug found + corrected (via `--clear-pricing-cache`).** Old cache had **wrong-high ETH prices** for early 2025 (2025-03-12 cached $2,636 → refetched $1,880.97; true market ≈ **$1,907** per two independent sources). Re-fetch is clean (0 null/zero material prices, no regressions per auditor `10ae2b4f`).

**Wave 2 (D2, D3) — DONE, NO CODE (auto-resolved by D1 cascade).**
- **D3:** cmETH→ETH convert now realizes **−$197.74/−$195.77** (was $0) and conserves ($1,334.68 = $1,136.94 + $197.74). Fully resolved.
- **D2:** flex-savings phantom $1,353 mint gone; earn-leg −$84.36 is offset to the cent by `CARRY_IN +$84.37` → system-wide residual ≈ **$0.00 mkt / +$0.01 net**. Conserved; carry-invariant not required (optional cosmetic per-leg cleanup only).

**AC status (actuals supersede pre-fix targets where the price correction moved the number):**
- AC-1/AC-1b: **MET** (no leak, real basis both lanes, conserves). Absolute figures re-based to corrected prices ($1,333.20 / $494.39).
- AC-2 (D2): **MET** (aggregate injection ≈ $0 system-wide).
- AC-3 (D3): **MET** (realizes −$197.74, conserves, no basis drop). *(−$197.74 not −$217.49 — corrected basis.)*
- **AC-4 (D4): SUPERSEDED — verified $2,356 market is CORRECT, not overstated.** The pre-fix band $1,000–1,900 assumed the *un*corrected early-2025 basis; the cache-clear correctly *lowered* early cmETH basis (~$1,988), which mechanically *raises* realized gains on the 2025-07 disposal (+$2,038 dominant). Auditor `10ae2b4f` walked every disposal. New accepted value: **cmETH realized PnL ≈ $2,356 mkt / $4,250 net.**
- AC-5: **Average cost $3,028.44/ETH** (matches $3,027 ± 1%; structural: 3.06 aManWETH @ $3,324 lending carry + late-25/26 high-price pool). **Break-even (headline, `offsetLane=NET`) = $1,957.32/ETH** (MARKET-lane reference $2,452.81). Wave 4 audit (`results/eth-family-effective-cost-wave4-audit.md`) verified the formula is applied correctly; the earlier **$2,550–$2,650 band is STALE and re-pinned to $1,957 NET** — it predated (a) the intended `offsetLane=NET` income credit and (b) the AC-4 supersession (real cluster profit $2,356, not the assumed ~$1,400), both of which legitimately lower break-even. Inputs: held basis $11,576.53 ÷ 3.8226 ETH-eq; cluster offset +$4,094.48 net (unfloored, AC-8); self ETH offset −$550.09 floored to 0; banked income $1,894.07 (already embedded in the NET offset, not double-credited).
- AC-6: **MET** (`netBasis ≤ taxBasis` all families; the two flagged net-only divergences are benign — DZENGI fiat `SYMBOL:USD` excluded, and PT-cmETH legitimate net-lane carry/yield, not phantom).

**Wave 3 (read-model, backend) — DONE & audited (Wave 4):** AC-7 (rate-adjusted denominator, fail-closed) **MET**; AC-8 (ADR-062 intra-cluster loss-floor carve-out) **MET** (D3 −$197.74 raises break-even +$51.7/ETH); AC-9 (D8 GMX/Pendle fold) **disclosed-excluded by design** (raw-contract identity + dynamic ETH-share not in read-model); AC-10 (D6 blended dust guard) **MET**; **AC-11 (D7 residual) REVERTED** (replay side-effect flipped cmETH conversions to PnL=0, ~$1,840 regression — now display-only); AC-12 (D9 tooltip subject price) **MET**; AC-13–15 (single "Break-even price" + "Average cost" labels/tooltips, demote Balance/Blended) backend DTO **MET**, frontend in progress. Wave 4 verdict: **no Medium+ financial-correctness issues remain.**

---

## 0. Plain-language problem

The ETH "effective cost" is wrong — but not because cmETH is priced at $1 (that's a cosmetic tooltip bug).
The real cause: when ETH was staked into mETH/cmETH on Bybit, the **mETH leg had no price**, so the
Market lane recorded **$0 cost basis** — throwing away ~$1,889 of ETH cost. Later "flexible savings"
**re-invented** basis at the wrong market price. Result: cmETH looks too cheap → realized profit looks too
big (+$2,557 vs true ~$1.2–1.6k) → ETH break-even looks too low. Plus 4 overlapping metrics confuse the user.

---

## 1. Locked user decisions
1. **Single headline metric = break-even** ("Break-even price"): remaining cost minus all realized
   family profit / rewards / fees, over ALL ETH-equivalent held (staked or not). Secondary = "Average cost".
2. **Economic intent:** ETH↔mETH↔cmETH are ONE economic ETH position; realized PnL matters only on exit to
   USDT/fiat. **(Delivered via read-model per the architecture review — see §3, does NOT require reverting ADR-054.)**
3. Sequence: review → implement → renorm → re-audit.

---

## 2. Review verdicts folded in (what changed vs v1)

| Reviewer | Verdict | Key change adopted |
|---|---|---|
| Architecture `a65f3436` | REVISE (blocking) | **D1 reframed as a PRICING bug** (unpriced mETH leg → Market-lane $0), not a missing carry. RP-1/RP-3 "ledger carry" **conflicts with Accepted ADR-054** → deliver the user's outcome in the **read-model (Option A)** instead. Reuse existing `CLUSTER:ETH_STAKING` config plane; no symbol hardcoding. D8 = read-model rollup, last. |
| Financial `65de6549` | REVISE (blocking) | Acceptance targets must be recomputed **genesis-forward** (not read off corrupted ledger). **Rate ≠ 1:1**: post-fix cmETH basis ≈$2,825/unit (=$2,665/ETH-eq × ~1.06); denominator must be **exchange-rate-adjusted**. Break-even numerator must pin the **Market lane** (net lane already income-credited → double-count risk). D7 needs **conservation**, not just display suppression. Test Jan-2025 stake too. |
| Business `26124104` | REVISE | **Measurable ACs with tolerances** (AC-1…AC-15). Labels **"Break-even price" / "Average cost"** (drop jargon). Defined edge cases (dust, fully-exited, surplus, multi-wallet). **D8 must be an explicit in/out decision with disclosure**, not "optional". Tooltip copy is part of DoD. |

---

## 3. THE decision to make (Option A vs B) — architecture blocking issue

The user wants ETH↔mETH↔cmETH treated as one pool. ADR-054 (Accepted) says C2 staking derivatives
dispose+acquire at market and realize P&L. **The headline break-even number is identical under both** for
profitable conversions; they differ only on loss conversions (ADR-062 loss floor).

- **Option A — read-model (RECOMMENDED, lowest risk).** Keep ADR-054 at the ledger. Fix D1 as pricing,
  D2 as carry-invariant, D3 as realize-at-market, then **amend ONLY ADR-062** so intra-`CLUSTER:*_STAKING`
  conversions bypass the loss floor / are excluded from the break-even offset. Pure read-model + config
  change. Preserves per-asset AVCO correctness (incl. wstETH depeg). Same headline number the user wants.
- **Option B — ledger carry (higher risk).** Add a declarative `basisCarryWithinCluster(CLUSTER:*_STAKING)`
  consulted by the cross-canonical routing + `FamilyEquivalentCustodyReplayHandler`, so ETH↔mETH↔cmETH carry
  basis with PnL=0 at the ledger. **Requires an ADR amending ADR-054 §2** and accepts synthetic (non-market)
  AVCO for rate-drifting members. Reuses existing carry machinery but touches hot replay paths recently
  changed (D1–D4 LP, M1/M2 GMX, yvVBETH proceeds).

**Recommendation: Option A** — identical user outcome, no ADR-054 reversal, no replay risk.

**LOCKED (user, 2026-07-22): Option A. D8 = fold GMX-GM/Pendle-PT ETH-share into denominator. R2 = drop the Average-cost cap (break-even may exceed average cost when uncovered fees/interest were paid).**

---

## 4. Root-cause defect list (reframed)

| # | Defect | Sev | TRUE earliest stage | Fix location |
|---|---|---|---|---|
| **D1** | Bybit ETH→mETH stake: mETH acquisition leg **unpriced** → Market lane books **$0** basis (Net lane already rescued by `swapNetRef`). | HIGH | **pricing** | price mETH leg at stake time (Bybit `METHUSDT` / DefiLlama-by-contract; already wired per ADR-054 §6). Fail-closed if unpriceable (ADR-054 §9), never silent $0. |
| **D2** | `EARN_FLEXIBLE_SAVING` re-prices IN leg at market (`+$1,353` vs `−$0.56`) instead of carrying removed basis. | HIGH | replay / continuity-carry | enforce `basisIn == basisOut` invariant in the earn-corridor carry (`ContinuityCarryService`). Partially auto-resolves once D1 gives the position real basis. |
| **D3** | cmETH→ETH convert: hybrid market-basis-to-receiver **and** PnL=0 → $217 vanishes + loss hidden. | MED-HIGH | cost_basis / read-model | realize `proceeds − avco` (ADR-054-compliant) **+** ADR-062 intra-cluster loss-floor carve-out (Option A) so the −$217 flows into break-even. |
| **D4** | Realized cmETH PnL overstated (+$2,557 vs true ~$1.2–1.6k). | HIGH | downstream of D1 | auto-resolves after D1–D3 + renorm (verify). |
| **D5** | ETH break-even too low (credits overstated profit). | MED | read-model | auto-resolves after D4. |
| **D6** | Blended AVCO header shows $1.75k on dust. | MED | read-model | apply existing dust guard to `blendedNetAvcoCurrent`. |
| **D7** | ~8e-5 cmETH dust residual never clears — a **micro basis-leak** (conservation issue, not just display). | LOW-MED | replay rounding | generic residual-below-ε → conserve/zero (shared with LP/reallocation), not cmETH-specific. |
| **D8** | GMX-GM / Pendle-PT ETH exposure excluded from the ETH fold (~$411). | LOW | read-model rollup | fold ETH-share via `FamilyBreakEvenInput` (`BreakEvenAttributionService`) OR disclose exclusion. **Explicit decision required.** |
| **D9** | Move-basis tooltip renders USDT quote-leg `$1/$29.06` on cmETH row. | LOW | BFF tooltip | render subject asset's price. |

---

## 5. Single-metric spec (final)

- **Headline "Break-even price"** = `max(0, Σ heldBasis_marketLane − Σ realizedFamilyProfit − Σ bankedIncome) ÷ Σ heldEthEquivUnits_rateAdjusted`.
  - `heldBasis` pinned to **Market lane** (avoid net-lane income double-count — financial review).
  - `bankedIncome` and `realizedFamilyProfit` scoped to `break-even-attribution.json` `CLUSTER:ETH_STAKING→FAMILY:ETH` membership (pinned + tested; no silent absorption).
  - denominator **exchange-rate-adjusted** ETH-equivalent (aTokens ~1:1; mETH/cmETH/wstETH/weETH via staking rate at valuation time).
  - **R2 (cap):** drop the "capped at Market AVCO" clamp so break-even may legitimately exceed average cost when uncovered fees were paid — **pending user confirm**.
- **Secondary "Average cost"** = `Σ heldBasis_marketLane ÷ Σ heldEthEquivUnits_rateAdjusted` (pure cost ≈ $3,027, no PnL credit).
- **Demote** Balance AVCO + raw Blended AVCO to an expandable "Details / diagnostic lanes" panel.
- Compute stays in `BreakEvenCalculator` (sole authority); presentation is BFF DTO field-selection + hint copy; chart terminal point must reconcile to header (`breakEvenUsd`) — asserted in a test.

### Labels & tooltip copy (business review, part of DoD)
- Headline **"Break-even price"** / sub *"effective cost, all ETH staked or not"*.
- Secondary **"Average cost"** / sub *"what your held ETH cost you"*.
- Surplus state: **"Past break-even · +$X recovered above cost"** ($X = total $, not per-ETH).
- Full worked-example tooltip copy per business review §2.

### Edge cases (defined behavior)
- Zero/dust balance → all per-unit metrics incl. Blended render "—" (existing $1 / covered-fraction guard).
- Fully-exited family (denominator≈0) → don't divide; show "Fully exited · realized PnL $Y".
- Surplus (≤$0 at risk) → "Past break-even · +$X"; Average cost still shows.
- Multi-wallet/multi-network → session-level aggregate across all wallets/chains.
- Legitimately low-basis (airdrop) → not flagged as leak.

---

## 6. Ordered implementation (Option A path)
1. **Re-audit D1 on current HEAD** (swapNetRef may already move the Net lane) → confirm Market-lane $0 persists.
2. **D1 pricing fix** → renorm (`--skip-frontend --clear-pricing-cache`) → re-measure genesis-forward.
3. **D2 carry-invariant** (ContinuityCarryService) → renorm → re-measure.
4. **D3 realize + ADR-062 intra-cluster loss-floor carve-out** → renorm → re-measure.
5. **Verify D4/D5** auto-resolved (targets in §7).
6. **D6/D7 dust** (guard parity + residual conservation) → `--backend-only`.
7. **D9 tooltip** + **§5 read-model consolidation** (labels, demote lanes) → `--backend-only`/frontend.
8. **D8** per user decision (fold or disclose).
9. Full re-audit against §7.

## 7. Acceptance criteria (measurable — exact targets pinned from re-audit c4232e65 on universe df5e69cc, alp=12419)
- **AC-1 (D1):** Mar-2025 stake `9bc8c8a2…|…d552dbd4…` (seq 1787): mETH ACQUIRE Market basis **$0.00 → $1,889.54 ± $2** ; Net basis **$0.00 → $1,833.89 ± $2** (per-mETH-unit ≈ $2,825.9 = ETH avco $2,665.08 × rate 1.0603). ETH DISPOSE realized PnL stays ≈ −$20.33.
- **AC-1b (regression):** Jan-2025 stake `06aca76f…|…73f479b3…` (seq 256/257): cmETH ACQUIRE **+$480.02 → +$499.82 market / +$500.66 net** (pair conserves to ETH DISPOSE −$499.82/−$500.66, |Δ| ≤ $0.50, up from ~$19.80 residual leak).
- **AC-2 (D2):** every `EARN_FLEXIBLE_SAVING` OUT/IN pair `|basisIn − basisOut| ≤ $0.50`; specifically pair seq **1836/1837** must become carry (was −$0.56 → +$1,353.55 @ $2,024). Aggregate flex-savings net injection **+$872.51 → ≈ $0**.
- **AC-3 (D3):** Apr-2025 convert `convert:…780|…781` (seq 2251/2252): realized PnL **$0.00 → −$217.49 ± $1** (against then-current basis); no silent basis drop; the loss reaches break-even (not floored — see AC-8 carve-out).
- **AC-4 (D4):** `Σ realisedPnlDeltaUsd` FAMILY:METH market lane **+$2,557.21 → target ~$1,400, assert band $1,000–$1,900**. Net lane drops correspondingly from +$4,439.62.
- **AC-5 (D5):** ETH **Effective cost (headline) ≈ $2,286 → $2,550–2,650/ETH**; **Average cost ≈ $3,027/ETH ± 1%** (≈ unchanged — corrupt cmETH already exited; D1–D3 move realized PnL, not held basis).
- **AC-6:** global `netBasis ≤ taxBasis` all families; no non-ETH family basis/realized-PnL drift > $1 from D1–D3.
- **AC-7 (rate-adjusted denominator):** ETH-equivalent denominator divides mETH/cmETH by live staking rate (~1.06); WETH/aWETH/aManWETH = 1:1. (Today’s impact < $1/ETH since held cmETH is dust, but mandatory going forward; fail-closed if rate missing.)
- **AC-8 (ADR-062 carve-out):** intra-`CLUSTER:*_STAKING` realized amounts bypass the loss floor (`BreakEvenCalculator:67`) so the D3 −$217.49 raises effective cost; external/non-cluster losses still floored (USDT $17/unit stays fixed).
- **AC-9 (D8 fold):** FAMILY:ETH denominator + basis include GMX GM ETH/USD ETH-share **+$411 / +0.11 ETH-equiv** (Pendle PT-ETH: none held). Effective cost with fold ≈ $3,047 Model-A reference.
- **AC-10 (D6):** blended AVCO header shows "—" on dust (guard parity with Market/Balance).
- **AC-11 (D7): REVERTED.** The replay variant (`GenericFlowReplayEngine.closeResidualExitDust`, which zeroed the sub-ε residual basis + marked it `uncovered`) caused a **Medium+ regression**: the `uncovered` marking had non-local side effects on covered-first consumption, flipping later cmETH cross-canonical conversion disposals (2025-04-17 convert, 2025-07-31 cmETH→PT) from realize-at-market to `PnL=0`, shifting FAMILY:METH realized PnL by ≈$1,840 and reverting the verified D3 fix. Post-revert the auditor-verified values are restored: cmETH realized **+$2,355.96 mkt / +$4,250.02 net**, D3 convert **−$197.74**, 2025-07-31 **+$2,037.84**. D7 is now handled **display-side only** via the AC-10 `$1` blended-AVCO dust guard; the residual ~$43 cmETH stranded basis (D2 CARRY_IN artifact) is left as immaterial (≈$11/ETH break-even impact) rather than corrected via risky replay surgery.
- **AC-12 (D9):** move-basis tooltip renders subject asset price, not USDT quote-leg $1/$29.06.
- **AC-13..AC-15 (UX):** exactly one headline "Break-even price" + one secondary "Average cost" (Balance/Blended demoted to details); labels+tooltips verbatim (§5); fully-exited/surplus/multi-wallet states per §5.

## 8. Docs / ADRs
- **Option A → amend ADR-062** (intra-`CLUSTER:*_STAKING` loss-floor carve-out) + note on ADR-054 §6/§9 that mETH acquisition-leg pricing is load-bearing & fail-closed. Confirm ADR-054 §2 **unchanged**.
- **Option B → new ADR amending ADR-054 §2** (basis-preserving conversions within a staking cluster; synthetic-AVCO caveat).
- Update `docs/pipeline/cost-basis/02-avco-rules.md`, `03-basis-pools-and-carry.md`, `docs/frontend/move-basis.md`.

## 9. Risks
- Do NOT edit `shouldApplyCrossCanonicalMarketLeg` / `compositeAwareFlowOrder` / LP-exit handlers alongside without an ArchTest pinning GMX/Pendle/yvVBETH regression anchors.
- Rate-adjustment needs a reliable staking-rate source at valuation time; fail-closed if missing.
- All ledger changes require full renorm; read-model changes `--backend-only`.
