# Implementation Plan — Bybit Earn Corridor + FUND Replay-Routing Fix

**Slug:** `bybit-earn-corridor-pairing`
**Date:** 2026-06-30 (rev. 2 — post Phase 3 review)
**Blocker:** B-6 (MNT phantom pool ~70×) + broader Bybit `:FUND` inflation class
**Audit reference:** `results/blockers.md` → "## Reward & Fee Impact — All Assets (2026-06-30)" and "### Phase 1 — Corridor pairing financial spec (2026-06-30)"
**Phase 3 reviewers:** financial-logic-auditor (CONDITIONAL), business-analyst (CONDITIONAL), system-architect (CONDITIONAL)
**Relationship:** Phase 0 prerequisite for the Dual Cost Basis (Net/Tax AVCO) plan. MNT dual-basis numbers are meaningless until this is fixed.

---

## Symptom

Multiple Bybit cost-basis pools are inflated vs live balances (ledger FUND+EARN sum vs live umbrella):

- **MNT** 5,825 → live 109.87 (**53×**)
- **USDT** 1,819 → 61.44 (**29.6×**)
- **LDO** 1,245 → 338.03 (**3.7×**)
- **ONDO** 1,216 → 401.17 (**3.0×**)
- **LINK** 63.3 → 17.14 (**3.7×**)
- **XRP** 12.37 → 4.05 (**3.1× — ZERO Earn activity**)
- **ARB** 50.97 → 36.55 (1.4×), **LTC** 2.02 → 1.26 (1.6×)
- **DOGE** 511 → 661 (understated, opposite direction)
- Clean controls: **XPL** 3.704 (exact), **USDC** ~0

---

## Display vs Internal Pool (what the user actually sees)

The phantom pools are **not shown directly** on the dashboard. The quantity column comes from `currentStateView` (`AssetLedgerQueryService` / `SessionDashboardQueryService`), which is anchored to **live balances** (`bybit_live_balances` + `on_chain_balances`), not the raw replay pool in `asset_ledger_points`. For most assets the displayed quantity therefore matches the exchange exactly:

- ONDO display 401.169 = live 401.17 (pool 1,216 hidden)
- LINK display 17.143 = live 17.14 (pool 63.3 hidden)
- LDO display 338.032 = live 338.03 (pool 1,245 hidden)
- **MNT display 160.683 ≠ live 116.75** (umbrella 109.87 + on-chain 6.88; `FAMILY:MNT` also holds receipt-token AMANWMNT 29.22 @ ledger covered) — for MNT the live-capping does **not** fully neutralize the `:FUND` phantom, so ~44 MNT still leak into the display. This is a downstream symptom of the same RC-2 `:FUND` asymmetry.

**Two distinct user-visible effects:**
1. **Quantity / market value** — primary visible distortion. MNT shows 160.683 (value $68.56 = 160.683 × $0.43 market) instead of ~117. This is what the corridor fix (RC-1 + RC-2) directly corrects.
2. **AVCO** — computed from the ledger pool (`totalCostBasis / coveredQty` across buckets), so it **is** contaminated by the phantom `:FUND` bucket ($5,629 basis @ $0.984). But because AVCO is a ratio and the phantom bucket sits near the real pool's average, the *displayed* MNT AVCO ($0.90) is only modestly off the clean target (~$0.95–1.05).

**Scope note (priority confirmed with user):** this plan corrects quantity/value/cost-basis as the foundation; AVCO values shift as a consequence but the AVCO **methodology** is unchanged. Changing the methodology (Net AVCO with rewards at $0 vs Tax AVCO at FMV) is the separate `dual_cost_basis_net_tax_avco` plan, which remains paused behind this fix.

---

## Two Root Causes (reviewers reconciled)

The architect (code trace) and auditor (data evidence) are not in conflict — they describe **two distinct defects on different subsets**:

### RC-1 — Earn-corridor classification asymmetry (MNT Flexible-Savings)

`BybitCanonicalTransactionBuilder.resolveEarnLifecycleCanonicalType` (`L543–570`) has branches for launchpool/fixed/flexible *redemption* but **no `flexible + subscription` branch** → the subscribe leg falls through to `INTERNAL_TRANSFER`. Consequences (all existing code):
1. `BybitStreamAuthorityCollapser.collapseMirrors` queries only `INTERNAL_TRANSFER` (`L107`) → one-sidedly demotes the subscribe leg (`bybit-collapsed-v1`, `excludedFromAccounting=true`).
2. `BybitEarnPrincipalTransferPairer` queries only `LENDING_*`/`EARN_FLEXIBLE_SAVING` and `excluded != true` (`L57–62`) → can never pair the subscribe → redeem leg is orphaned, FUND phantom.

**Decisive EARN proof (auditor-verified to the penny):** EARN redeem −3,238 + open subscribe +108.776 + re-included excluded subscribe +3,233 = **+103.776 = live EARN balance**. Confirms include-both-and-net is correct.

### RC-2 — `:FUND` position-key asymmetry in replay (CONFIRMED — XRP/LDO/USDT/residual FUND)

**Confirmed to the penny via XRP** (zero Earn legs, clean isolation from RC-1). Normalization is correct — XRP's six FUND↔UTA `INTERNAL_TRANSFER` round-trips are all `excluded=false` and net to **4.0697 ≈ live 4.0533**. The damage is entirely in **replay**, which splits XRP into two pools:

- `BYBIT:33625378` (umbrella) = **4.0697** — correct, ≈ live.
- `BYBIT:33625378:FUND` = **12.37** — **100% phantom** (live `fundQty` = 0). This pool has `CARRY_IN +24.92` and **zero `CARRY_OUT`**.

**Mechanism:** FUND *inbound* legs attach to the `:FUND` position key, but their matching FUND *outbound* legs are either **dropped from the ledger** (XRP legs `7bfbda038` −12.55, `5a329cb6` −12.37) or **stripped to the umbrella** key; UTA legs always strip to umbrella. So inbound credits `:FUND` while the offsetting outbound drains a *different* pool → they never cancel; `:FUND` becomes inbound-only and accumulates phantom qty.

**Code loci (two candidates; backend-dev confirms which fires per corrId):**
- `AccountingAssetIdentitySupport` `isBybitCollapsedFundSide` asymmetry — collapsed FUND *inbound* preserved on `:FUND`, collapsed FUND *outbound* stripped to umbrella (`L152–178`).
- `TransferReplayHandler.resolveCarrySourcePosition` R-1 FUND redirect (`L1441–1454`) — drains `BYBIT:{uid}:FUND` while inbound `restoreToPosition` always credits the umbrella key; compounded by `ReplayDispatcher.isBybitSelfTransfer` (`L554–587`) skipping counterparty-paired legs while orphan stream-mirror singletons replay individually.
- **NOT** `ReplayFlowSupport.continuityBasisEffect` — verified symmetric (`CARRY_IN`/`CARRY_OUT`), do not change.

**Invariant to enforce:** a Bybit `:FUND` position must be carry-symmetric — if inbound legs attach to `:FUND`, the offsetting outbound must drain `:FUND` (and vice-versa); a FUND↔umbrella round-trip must net to zero on a single position key.

**Generalization:** LDO (3.7×) is **pure RC-2** (`:FUND` inbound-only → 906.93 phantom; EARN reconciles 338.26 vs 337.0). USDT (29.6×) = RC-2 **plus** RC-1 (non-reconciling Earn pools) replicated across 4 sub-accounts.

**FUND arithmetic:** removing the ~3,233 MNT earn phantom (RC-1) leaves FUND ≈ 2,487, **not** 6.10. Closing FUND→6.10 **requires RC-2**. RC-1 alone is insufficient.

---

## Changes (ordered, upstream first)

### Change 1 — RC-1: add `flexible + subscription` classification branch (architect minimal fix)

In `resolveEarnLifecycleCanonicalType`, classify Flexible-Savings *subscription* as the same custody type as redemption (`EARN_FLEXIBLE_SAVING`, direction by flow sign). This single branch:
- removes the subscribe leg from the collapser's `INTERNAL_TRANSFER` query (the one-sided exclusion disappears at source — old Change 2 becomes automatic),
- admits it to `BybitEarnPrincipalTransferPairer` scope,
- lets `BybitPrincipalEventExclusivityService` demote any residual IT mirror (its designed job).
- **Constraint (auditor):** match *principal* subscriptions only, not auto-compounded interest/reward rows (those stay `REWARD_CLAIM`).

**No pipeline reorder** (keep collapse → earn-pair → exclusivity). Do **not** add a new correlation pass.

### Change 2 — Running-balance pairing model (replaces equal-qty key)

The original `principalQty`-equality key is **rejected**: Bybit allows 1 subscribe : N partial redeems (verified: MNT 108.776 subscribed, only 5.0 redeemed → 103.776 open; USDT/LDO/ONDO/LINK net ≠ 0). Adopt:
- **Corridor key:** `{universe, uid, asset, earnProduct}` modelled as a **running principal balance** (subscribe increments EARN principal, redeem decrements, FIFO carry).
- **Disambiguator:** redeem consumes the earliest open principal (FIFO/time-ordered) so same-qty same-day cycles cannot mis-attribute basis (latent collision: TON 32.4212 twice).
- `principalQty` equality kept only as a **closed-cycle assertion** inside the conservation guard.
- **Determinism (architect):** correlation key derived solely from `{uid, family, |qty|, blockTimestamp.epochSecond}` — **never** from Mongo `_id` or import order (cf. `collapsedCorrelationId` `_id`-hash non-determinism at `L849–855`).

### Change 3 — RC-2: `:FUND` carry-symmetry fix (co-equal core fix, root-cause CONFIRMED)

Load-bearing for XRP/LDO/USDT and for closing FUND→6.10. Enforce the RC-2 invariant: inbound and outbound legs of a FUND↔umbrella round-trip must resolve to the **same position key** so they net to zero (no inbound-only `:FUND` accumulation).
- **Primary locus:** `TransferReplayHandler.resolveCarrySourcePosition` R-1 redirect (`L1441–1454`) — scope the `:FUND` drain redirect to collapsed/corridor contexts only; for plain `INTERNAL_TRANSFER`, drain the same umbrella key that inbound `restoreToPosition` credits. Reconcile with `AccountingAssetIdentitySupport.isBybitCollapsedFundSide` inbound/outbound asymmetry (`L152–178`) so both halves agree on `:FUND` vs umbrella.
- **Do NOT change** `ReplayFlowSupport.continuityBasisEffect` (verified symmetric).
- **Do NOT touch** the earn-principal-paired, `BYBIT-CORRIDOR`, or collapsed-corridor branches (clean controls XPL/USDC must not regress).
- For the Earn-paired case (RC-1), the existing `bybit-earn-principal-v1:` routing is reused unchanged — verify net-to-zero rather than adding new routing.
- Backend-dev confirms, per XRP's actual corrId (collapsed vs plain), which of the two candidate loci fires before editing.

### Change 4 — Conservation guard (WARN-first, no new index)

Read-only canary over existing collections:
1. Round-trip net per corridor ≈ open principal.
2. **Sub-pool reconciliation:** ledger `:FUND` and `:EARN` each reconcile to `bybit_live_balances.{fundQty,earnQty}` (not just umbrella sum).
3. Exclusion symmetry: a leg excluded only if its pair is excluded.
4. Idempotency: recycling the same principal cannot increase quantity.
WARN-only first, gate later (consistent with `reconciliation.md` baseline).

---

## Resolved: B-4 borrow-direction contradiction

The audit artifacts contradicted themselves. **Resolution (auditor-endorsed):** BORROW injected +3,532 MNT at ~$0.72 (below the high-priced spot pool), which **diluted** AVCO down; treating borrows as liabilities (removing that cheap basis) therefore moves AVCO **up**. The "→ $0.90" note in classification-errors #2 is wrong; Phase-1 §3 ("→ up, $1.05–1.15") is correct. Corrected central AVCO estimate after fix: **~$0.95–1.05** (replay-derived; informational, not a hard gate).

---

## Documentation (before code)

- **New protocol rule** `docs/pipeline/normalization/rules/protocols/bybit-earn.md`: Flexible-Savings subscribe↔redeem are symmetric custody legs; running-balance pairing; conservation invariant.
- **ADR** (architect): "Bybit Flexible-Savings subscription is a custody type symmetric with redemption; **an Earn principal leg may be excluded only if its paired leg is excluded (no one-sided exclusion)**; corridor fixed at classification, downstream reused." Plus record the RC-2 FUND↔spot routing decision.
- Update `docs/reference/ledger-points-and-basis-effects.md` if RC-2 changes CARRY/REALLOCATE routing.
- Cross-reference B-4 (resolved direction) and B-01 (untracked deposits, out of scope).

---

## Scope (business-analyst)

- **In scope:** Flexible-Savings (RC-1) + FUND↔spot/UTA `INTERNAL_TRANSFER` routing (RC-2).
- **Fixed / Auto-Earn — RESOLVED (in scope, no extra work):** data trace found no `FIXED_*_REDEMPTION`/Auto-Earn type; Fixed (MNT −83.073, 1 event) and ETH-2.0 auto-staking (ETH −0.709→METH +0.669) are immaterial and already `INTERNAL_TRANSFER`, so the generic RC-2 fix covers them — **no dedicated corridor logic needed.**
- **Explicitly OUT:** Dual-Asset / asset-switch Earn products (break the equal-asset assumption), B-4 borrow policy, B-01 untracked deposits, AVAX (not buggy).

---

## Acceptance Criteria

**Reconcile to live (per-asset, after full replay):**
- MNT → 116.7 combined (FUND 6.10 + EARN 103.8 + on-chain 6.88); USDT → 61.44; LDO → 338.03; ONDO → 401.17; LINK → 17.14; XRP → 4.05; ARB → 36.55; LTC → 1.26.
- **Clean controls must NOT change:** XPL → 3.704; USDC → ~0.
- DOGE understatement investigated (opposite-direction symptom).

**Behavioral DoD (BA test scenarios):**
- AC-T1 closed subscribe→redeem cycle nets to 0 in the pool.
- AC-T2 open position (subscribed, not redeemed) leaves exactly the open principal (MNT 108.776→5.0 = 103.776 open).
- AC-T3 interest/reward legs (`REWARD_CLAIM`) NOT swallowed by principal pairing.
- AC-T4 same-day identical principalQty does not mis-pair (FIFO).
- AC-T5 a non-MNT asset cycled through Earn reconciles.
- AC-T6 idempotency: re-replay produces identical correlationIds/pools.
- AC-T7 conservation guard negative-test fires on an injected imbalance.
- AC-T8 pre/post numeric snapshot per affected asset.
- AC-T9 portfolio value / PnL reconciliation.

**Hard gates:**
- **Sub-pool split** (`:FUND` and `:EARN` individually), not just umbrella sum.
- `quantityShortfall` → 0 on closed cycles.
- **FUND→6.10 shown arithmetically closed** (analogous to the EARN penny-check), not asserted.
- No new `conservationBreached` warnings universe-wide.
- financial-logic-auditor sign-off vs authoritative reconstruction.

---

## Risks

- **Two defects, broad blast radius:** RC-2 touches shared replay routing → can shift many pools. Mitigate: per-asset live reconciliation + clean controls (XPL/USDC) + WARN-only guard first.
- **RC-2 locus:** confirmed at data + code level; narrowed to `resolveCarrySourcePosition` R-1 redirect + `isBybitCollapsedFundSide` asymmetry. Backend-dev confirms which fires per XRP corrId before editing.
- **Determinism:** correlation key must avoid `_id`/import-order inputs.
- **Missing tests:** no unit test for `BybitEarnPrincipalTransferPairer`; no e2e for Flex subscribe+redeem or partial redemption. Required.
- **Full replay required.**
- **User-facing correction:** MNT UI drops ~6,205 → ~117 (98%), ~$3,000 phantom unrealized-loss reversal, portfolio-value shift — must be communicated with a per-asset before/after enumeration as a deliverable.

---

## Open Decisions (for user before Phase 4)

1. Confirm user-facing communication of the MNT 6,205→117 (and USDT/LDO/ONDO/LINK/XRP) corrections.
2. Approve ADR (accounting/corridor policy change) + entry to Phase 4 (docs) and Phase 5 (backend-dev implementation).

*(Resolved: Fixed/Auto-Earn scope — covered by generic RC-2 fix, no extra work. RC-2 root-cause — confirmed at data + code level, locus narrowed. B-4 borrow direction — resolved: AVCO moves up.)*

---

## Regression Annex — 2026-06-30 (working-tree effort B vs HEAD `e748023`)

**Trigger:** user reported (1) combined ETH dashboard AVCO jumped ~$2.2k → ~$2.6k, (2) LTC move-basis
chart line ~$18k–23k while header shows ~$104. A regression audit pass (`results/blockers.md` →
"REGRESSION PASS — 2026-06-30", ids `B-REG-01..04`; `results/reconciliation.md` → "REGRESSION PASS —
Bybit ledger vs live-balance conservation") confirms these are **the same RC-1 + RC-2 corridor defects
this plan targets** — surfaced (and partly worsened) by an **incomplete in-flight attempt** at effort B
currently sitting uncommitted in the working tree.

### What the regression pass establishes
- **Effort A (dual Net/Tax cost basis) is NOT responsible.** Tax operators in `GenericFlowReplayEngine`
  (`applySell`/`applyFee`/`removeFromPosition`/`removeProportional`/`restoreToPosition`/`consume`/`purge`)
  are byte-for-byte unchanged; all edits are net-lane-additive. The only tax-touching change is a
  `recomputePerWalletAvco` added to `applyAuthoritativeLateInboundCarryBasis` (B-REG-04) — correctness
  improvement, creates/removes no quantity → **not** the driver. **Do not revert effort A.**
- **ETH $2.2k→$2.5k (B-REG-01)** = phantom Bybit ETH-family inventory: umbrella ETH 1.149 @ $3818 +
  EARN ETH 0.693 @ $1593 + METH 0.669 + CMETH 0.054, against raw net ETH change ≈ 0 and **absent** live
  balance. Removing the phantom basis reproduces the user's pre-change ~$2.23k. Root cause: the in-flight
  effort B set `AccountingAssetIdentitySupport.isBybitCollapsedFundSide()` to **always `false`** and gated
  the `:FUND` drain in `TransferReplayHandler` — i.e. it **relocated** the RC-2 inbound-credited /
  outbound-not-drained asymmetry onto the umbrella ETH key instead of fixing it. Earliest stage: `replay`.
- **LTC $18,731 chart (B-REG-02)** = effort B's RC-1 reclassification routes the LTC Flexible-Savings
  principal subscribe into the corridor, and replay books **+$41.54 basis with quantityDelta=0** onto
  `:EARN` while the 0.75 LTC quantity lands on the umbrella → basis stranded on 0.0023 LTC dust →
  avco $17,920. Header **$104 is correct** = ($37.41+$41.66)/(0.7592+0.0023). Basis is real, split onto
  the wrong key. Earliest stage: `replay` (basis must follow quantity onto one key).
- **B-REG-03 (downstream, irreducible):** `TimelineAvcoAuthority` median-relative outlier rejection cannot
  rescue LTC because 142/151 LTC points are the polluted `:EARN` series → the median itself ≈ $33k. The
  only correct fix is upstream (B-REG-02); chart authority changes (effort C) merely surface it.
- **Scope: NOT "all assets".** Spot-checks sane: BTC $79k, AVAX $19.63, SUSHI $0.55, MNT avco ~$1
  (despite phantom *quantity*), on-chain AMANWETH $2829 / AWETH $1827. AVCO-quality breakage is localized
  to (i) Bybit Earn sub-pools and (ii) Bybit ETH-family phantom inventory.

### Corrective ordering (supersedes a from-scratch build — the working tree already half-implements B)
1. **Treat the current working-tree effort B as a draft of Changes 1 & 3, not as a baseline.** It must be
   completed/corrected to satisfy the RC-2 invariant (inbound and outbound legs of a FUND↔umbrella /
   FUND↔EARN move resolve to the **same** position key and net to zero; net per-uid quantity must equal
   raw net), not merely flip `isBybitCollapsedFundSide()` to `false`.
2. Fixing RC-2 routing (Change 3) resolves **both** ETH (B-REG-01) and the LTC quantity-on-umbrella
   half of B-REG-02. Fixing RC-1 basis/quantity co-movement (Change 1 + Change 2) removes the qd=0
   `:EARN` basis ghost (B-REG-02).
3. **Tier-A read-model / chart changes (effort C) stay**, but are validated only after 1–2 land
   (B-REG-03 is irreducible until upstream is fixed). Optionally clamp AVCO authority against market so a
   single polluted pool cannot move the median.
4. **Fallback only if a fast symptom-kill is needed before the corridor fix lands:** revert the
   **effort-B working-tree pieces** (`AccountingAssetIdentitySupport` RC-2 collapse flip,
   `TransferReplayHandler` `:FUND`-drain gating + pegged-native outbound avco,
   `BybitCanonicalTransactionBuilder` RC-1 subscription reclass) — never effort A.

### Additional acceptance criteria (regression-specific)
- **ETH:** combined `FAMILY:ETH` covered-weighted AVCO returns to ~$2.2k; Bybit ETH/METH/CMETH ledger
  quantity reconciles to live (≈ 0); raw net ETH per uid ≈ ledger net.
- **LTC:** no `:EARN` point with `quantityDelta=0` + `costBasisDelta>0`; LTC `:EARN` reconciles to live
  (umbrella+earn ≈ 1.26); move-basis chart line tracks ~$50–110 band, not $18k; header and chart agree.
- **Regression guard:** the Change 4 conservation canary must hard-flag the ETH/LTC phantom states that
  the working-tree effort B currently produces.

---

## Phase-2 verification update — 2026-07-03

Runtime rerun executed via `./scripts/prod-reset-rebuild-backend.sh --linking-only --skip-frontend` and
completed through `PORTFOLIO_SNAPSHOT_REFRESH`.

### What the rerun proved

- **Stage-1 primary remediation landed at the accepted earliest wrong stage (`LINKING`).**
  All 22 historical same-event same-asset multi-source bundles now receive deterministic
  `bybit-earn-principal-v1:*` correlation ids across `FUND out + UTA out -> EARN in`.
- **Spread of linked bundles after rerun:** `22/22` bundles correlated, matching the expected asset split:
  `ONDO 8`, `LDO 7`, `LINK 3`, `TON 2`, `ARB 1`, `LTC 1`.
- **Read-model clamp behaved as intended:** when Bybit live quantity exceeds ledger quantity, the live excess
  stays uncovered and no longer scales covered quantity / total basis / net basis upward.

### What remains open after rerun

- **Replay materialization is still not fully converged for all 22 bundles.**
  Correlation/linking now exists, but the replay output is mixed:
  - some bundles emit the expected inbound `REALLOCATE_IN` / `CARRY_IN` row,
  - some emit only outbound rows (`REALLOCATE_OUT` + `CARRY_OUT`),
  - the LTC anchor bundle is still in the failing set.
- **LTC anchor remains blocking.**
  The rerun stamped shared deterministic correlation on the three anchor rows, but replay still persisted only:
  - FUND `REALLOCATE_OUT`
  - UTA `CARRY_OUT`
  and **did not persist an inbound EARN restore/materialization row** for
  `BYBIT-33625378:EARN_FLEXIBLE_SAVING:1942b2ee-925e-47d9-803f-6d7c29b9d3b9`.
- **Guard status remains WARN, not clean-pass.**
  `BybitEarnSubPoolConservationGuard` still reports mismatches, including LTC `internalQtyDelta=-0.51096338`.

### Boundary conclusions from phase-2 review

- `BybitOnChainEarnOrphanRepairService` does **not** currently need to take ownership of these
  evidence-present-unlinked bundles; the accepted primary owner remains the upstream pairer.
  Its scope should stay limited to genuinely missing / non-real-credit cases.
- `AccountingAssetIdentitySupport` does **not** need additional routing changes for the new correlation
  behavior beyond the already-landed stage-1 work.
- `principalFlow()` is **safe for the currently observed Bybit rows** (all verified bundle members are
  single-principal-flow rows apart from optional fees), but it remains a documented residual risk if Bybit
  ever emits multi-principal non-fee rows for the same product shape.

## Phase Gate

Phase 3 + phase-2 verification together show the fix is **not yet acceptance-ready**. Upstream linking is
now correct for the targeted evidence-present-unlinked defect, but replay/read-model convergence is only
partial and the LTC runtime anchor still fails. Status remains **CONDITIONAL** until replay emits a
materialized inbound EARN restore for the anchor and the 22-bundle spread no longer leaves outbound-only
residues.
