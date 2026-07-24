# FB-01 — NET-lane basis-recycling fix (ETH effective cost) — Architecture Decision + Implementation Plan

- **Status:** Proposed (design only — no application code changed by this document)
- **Date:** 2026-07-23
- **Owner role:** system-architect
- **Severity:** HIGH (financial-correctness, headline ETH effective/break-even cost)
- **Inputs:** `results/unmatched-bridges-and-effective-cost-audit.md` §B/§C, `results/required-changes.md` §1–§3,
  ADR-040 (dual Net/Market AVCO + Bug B swap net-carry), ADR-054 (per-asset AVCO / cross-canonical conversion),
  ADR-062 (break-even / `offsetLane`), `docs/tasks/eth-family-effective-cost-and-basis-leak-implementation-plan.md` (D1–D9 / AC-*).
- **Coordinates with:** the parallel `financial-logic-auditor` "FB-01 code-path localization" appendix (exact `file:line`).
  The anchors cited below were independently localized during this design pass; reconcile against the auditor appendix on merge.

---

## 0. TL;DR recommendation

**Recommend (b) — the root-cause NET-lane fix in `cost_basis`/`replay`, implemented as "re-base on realize" (Model A), scoped to realizing swaps between DISTINCT canonical priced instruments, with a lightweight (a)-style read-model invariant guard as defense-in-depth.**

- **Why (b) over (a):** the user's two standing rules are jointly binding — *"no temporary fixes/workarounds, only reliable long-term solutions"* **and** *"rewards must reduce cost for free (`offsetLane=NET`)."* A pure read-model (a) can only satisfy the second rule by *separately measuring genuine zero-basis income*, which is **not** derivable from the current read model (both genuine reward income and the swap artifact land in the same `netRealisedPnlDeltaUsd`). The only clean read-model (a) that ships today is `offsetLane=MARKET`, which **drops** genuine reward write-down → violates rule 2. So (a) is either a workaround (violates rule 1) or needs new ledger instrumentation anyway (loses its "read-model only" advantage). (b) makes `netRealized` correct at the source, after which `offsetLane=NET` credits genuine rewards and excludes the artifact **by construction**, and simultaneously fixes SOL/AVAX (FB-02), the latent PT-cmETH mirror (FB-03), and the portfolio-wide **+$5,704** NET inflation.
- **Why Model A ("re-base on realize") and not Model B ("carry + defer"):** the audit's own phrasing is *"re-bases to actual net acquisition cost instead of recycling pre-loop basis"* = Model A. Model A is **NET-lane-only**: it never touches the Market lane, never touches realized-PnL on any disposal leg, never marks anything `uncovered`, and never flips a `DISPOSE` into a `PnL=0` carry — i.e. it structurally avoids the exact failure signature that forced the **AC-11 / D7 revert** (`docs/tasks/eth-family-effective-cost-and-basis-leak-implementation-plan.md` AC-11). Model B would require suppressing net-realized on the disposal (the `undoSellRealisedPnl` pattern) plus proportional discount carry — strictly more invasive and closer to the AC-11 danger zone.
- **Defense-in-depth (a):** add a read-model invariant that fails a family's NET offset **closed to the Market lane** when the credited income (`netRealized − marketRealized`) exceeds a plausibility bound relative to tracked zero-cost income. This would have caught FB-01 at the read boundary; it is a guard, not the fix.
- **Verification:** `./scripts/prod-reset-rebuild-backend.sh --skip-frontend` (full renorm + replay; **no** `--clear-pricing-cache` — pricing is unchanged), then re-run the Phase-1 audit reconstruction.

---

## A. Root-cause mechanics (confirmed against code)

### A.1 The defect is a realize-**and**-carry hybrid on the NET lane

Two facts combine to double-count:

1. **The disposal leg always realizes NET PnL.** `GenericFlowReplayEngine.applySell` (lines 165–200) computes
   `netRealised = (unitPrice − netAvco) × soldQty` and adds it to `totalNetRealisedPnlUsd` whenever the leg is priced —
   unconditionally, for SWAP and cross-canonical staking disposals alike.

```187:188:backend/core/src/main/java/com/walletradar/application/costbasis/application/replay/support/GenericFlowReplayEngine.java
                BigDecimal netRealised = flow.getUnitPriceUsd().subtract(netAvco, MC).multiply(soldCoveredQuantity, MC);
                position.setTotalNetRealisedPnlUsd(position.totalNetRealisedPnlUsd().add(netRealised));
```

2. **The acquisition leg then re-seeds the NET basis from the *absolute* released basis** (ADR-040 Bug B `swapNetRef`).
   `ReplayDispatcher.applySellWithOptionalPool` (lines 922–949) accumulates the disposed absolute net/market basis into
   `swapNetRef[0]/[1]`; `applyBuyWithOptionalPool` (lines 843–895) then seeds the acquired lot with
   `effectiveNetBasis = min(netBasisReleased, marketCost)` via `applyBuyWithExplicitNetCost` (engine lines 150–163).

```860:864:backend/core/src/main/java/com/walletradar/application/costbasis/application/replay/dispatch/ReplayDispatcher.java
        if (swapNetRef != null && swapNetRef[1].signum() > 0) {
            BigDecimal netBasisReleased = swapNetRef[0];
            if (poolAcquisitionCost != null) {
                BigDecimal effectiveNetBasis = netBasisReleased.min(poolAcquisitionCost);
                flowSupport.applyBuyWithExplicitNetCost(flow, position, poolAcquisitionCost, effectiveNetBasis);
```

Because the disposal **already realized** the reward discount into `netRealised`, carrying the *absolute* pre-loop net
basis onto the freshly-acquired (higher market-value) lot **fabricates a new, larger discount** that is then realized
**again** on the next disposal. On an N-hop round trip the discount is realized N times.

### A.2 The cmETH → PT-cmETH → cmETH trace (from audit §B.2)

| seq | leg | Market cbΔ | NET cbΔ | discount (mkt−net) after leg | Market realized | NET realized | net−mkt income |
|---|---|---|---|---|---|---|---|
| 5882 | cmETH SELL → PT | −1,504.38 | −1,499.23 | (disposed) | +2,037.84 | +2,042.98 | **+$5** (genuine) |
| 5883 | PT-cmETH BUY | +3,557.75 | **+1,499.23** | **$2,058 fabricated** | — | — | — |
| 5969 | PT SELL → cmETH | −3,557.75 | −1,499.23 | (disposed) | −159.49 | **+1,899.03** | **+$2,058 artifact** |
| 5970 | cmETH BUY | +3,380.74 | **+1,499.23** | **$1,881 fabricated** | — | — | — |
| 6614+ | cmETH SELL (final) | (avco $3,925) | (avco **$1,739**) | — | +608.92 | **+2,493.39** | **+$1,884 artifact** |

The genuine per-unit discount before the loop was only **~$5** (`1,504.38 − 1,499.23`). The recycle balloons it to
**+$1,894** of fabricated FAMILY:METH "income", which ADR-062 `offsetLane=NET` credits into the ETH offset →
effective cost understated ~$500/ETH.

### A.3 Why Model A is correct and self-consistent

When a disposal **realizes** the NET gain (which already banks the genuine discount as income), the acquired lot is a
**fresh market purchase** and must carry **no** discount: `netAcquisitionCost = marketAcquisitionCost`. Re-tracing:

| seq | NET realized (fixed) | acquired NET basis (fixed) | net−mkt income |
|---|---|---|---|
| 5882 | +2,042.98 | PT-cmETH net = market $3,557.75 | +$5 (genuine, banked once) |
| 5969 | = market −159.49 | cmETH net = market $3,380.74 | $0 |
| 6614+ | = market +608.92 | — | $0 |

FAMILY:METH net−market income collapses to **≈$5** (genuine) → offset ≈ Market cluster realized **$2,003** →
**effective cost ≈ $2,500/ETH**. The Market lane is **byte-identical** throughout (only NET basis seeding changed).

### A.4 The discriminator (realizing distinct-canonical swap vs. deferred/identity carry)

Re-base **only** when the released net basis came from a disposal whose NET realized PnL was **kept** (banked):

- **Re-base (Model A):** the disposal leg realized NET PnL that was **not** undone — a genuine on-chain SWAP or a
  cross-canonical STAKING/VAULT conversion between DISTINCT canonicals (`hasCrossCanonicalIdentityPrincipalPair`).
- **Preserve carry (unchanged):**
  - **Counterparty-pool / CEX-corridor swaps** — `applySellWithOptionalPool` already calls
    `CounterpartyBasisPoolReplayHook.undoSellRealisedPnl` (lines 120–136), zeroing NET realized. Nothing was banked →
    carrying the basis is correct (income deferred). These must keep inheriting.
  - **Unpriced disposals** — `applySell` took the `else`/`markUnresolved` branch, realized nothing → carry preserved.
  - **C1↔C1 identity moves** (ETH↔WETH) — `isCrossCanonicalStaking=false` → `swapNetRef=null`, never reach this branch
    (regression-guarded by `ethToWethSameC1StakingDepositDoesNotInheritNetBasis`).
  - **Same-token corridor / bridge carries** — routed through `TransferReplayHandler`/`ContinuityCarryService`
    (`CARRY_IN`/`REALLOCATE_IN`), not `swapNetRef`.
  - **Genuine zero-cost rewards** (`REWARD_CLAIM`, `LP_FEE_CLAIM` via `ZeroCostAcquisitionSupport`) — booked net=$0 on a
    separate path; untouched, so "rewards reduce cost for free" is preserved.

**Signal:** the disposal realized-and-kept NET PnL. Implement by threading a "net-realized-kept" marker alongside the
`swapNetRef` accumulator (see §D).

---

## B. Decision: (a) vs (b), evaluated against the required criteria

| Criterion | (a) read-model (BreakEvenCalculator) | (b) root-cause NET-lane re-base (Model A) — **RECOMMENDED** |
|---|---|---|
| **Correctness of ETH effective cost** | Reaches ~$2,500 **only** if genuine income is separable; `offsetLane=MARKET` reaches it but at cost of rule 2 | ~$2,500 by construction; `netRealized` becomes economically real |
| **Fixes FB-02 (SOL/AVAX), FB-03 (PT-cmETH), global +$5,704** | **No** — read-model only re-attributes ETH; other clusters/global NET stay inflated | **Yes** — one mechanism, all clusters |
| **Preserves "rewards reduce cost for free" (`offsetLane=NET`)** | Only with new zero-cost-income instrumentation (i.e. a ledger change anyway) | **Yes** — genuine reward write-down still flows through `netRealized`; artifact removed |
| **Preserves genuine yield-driven write-down** | At risk (conflated with artifact) | **Yes** — `ZeroCostAcquisition` path untouched |
| **Preserves cmETH↔ETH realize-at-market (D1/D3, ADR-054 §2)** | Yes (no ledger change) | **Yes** — Market lane untouched; realized-PnL on disposals untouched; D3 −$197.74 stable |
| **Avoids AC-11 regression signature (uncovered/DISPOSE→PnL=0 flip)** | Yes | **Yes** — no `uncovered` marking, no consumption change, no realized-PnL change |
| **Blast radius** | Small code, but **incomplete** correctness | Contained to NET-basis **seeding** of one branch; needs renorm |
| **"No workaround" rule** | Fails unless instrumented | **Satisfies** |
| **Requires renorm** | No | Yes (`--skip-frontend`) |

**Decision: (b) Model A, discriminator-scoped, + (a) read-model guard as defense-in-depth.**

---

## C. Blast-radius analysis

### C.1 Replay paths touched
- **In scope (behavior changes, NET lane only):** `ReplayDispatcher.applyBuyWithOptionalPool` /
  `applySellWithOptionalPool` (the `swapNetRef` branch); `GenericFlowReplayEngine.applyBuyWithExplicitNetCost` (semantics
  of the `net` argument for realizing swaps). Transaction types that reach the branch: `SWAP`, and cross-canonical
  `STAKING_DEPOSIT`/`STAKING_WITHDRAW`/`VAULT_DEPOSIT`/`VAULT_WITHDRAW` with `hasCrossCanonicalIdentityPrincipalPair`.
- **Explicitly NOT touched:** counterparty-pool/CEX corridor swaps (`undoSellRealisedPnl` path), continuity/bridge
  carries (`TransferReplayHandler`, `ContinuityCarryService`, `LinkedBridgeTransferReplaySupport`), LP receipt
  entry/exit and settlement allocators (`ReplaySettlementAllocator` — its net-aware restores are carry-conserving and
  correct), BORROW/REPAY (ADR-040 §5 borrow-basis compliance), async lifecycle handlers, GMX/Pendle fold (disclosed-excluded).

### C.2 Families affected
- **FAMILY:METH** (mETH/cmETH/PT-cmETH): net−market income $1,894 → ≈$0 (FB-01/FB-03).
- **FAMILY:SOL / FAMILY:AVAX / SAVAX** (FB-02): CLUSTER offsets corrected (~$73 combined).
- **Any family with realizing distinct-canonical swaps** contributing to the global NET total ($8,039 → ≈ Market $2,334 + genuine income).
- **Unaffected:** stablecoins, no-reward assets (Net≈Market already), C1-only ETH spot.

### C.3 Prior ADR fixes / work items — conflict check
- **ADR-054 §2 (Net lane for C1→C2 conversions):** the "inherit min(inherited, market)" clause is the *proximate*
  source of the recycle → **requires amendment** (see §E). This is the one accepted-ADR change.
- **ADR-040 Bug B (swapNetRef):** semantics narrowed to non-realizing/deferred disposals → **amendment** (see §E).
  ADR-040 §5 borrow compliance and the net-carry conservation invariant (WRAP↔UNWRAP / spot↔receipt / REALLOCATE) are
  **unaffected** — those govern CARRY legs, not realizing DISPOSE+ACQUIRE.
- **ADR-062 (`offsetLane=NET`):** no code change required for the primary fix; its input becomes correct. Add the DiD
  guard (§D.4) and a clarifying note. AC-8 (intra-cluster loss carve-out) and AC-7 (rate-adjusted denominator) unchanged.
- **D1 (unpriced mETH leg → $0):** fail-closed path preserved; the priced-inbound acceptance value changes
  (mETH net now = market, not inherited ETH net) — test update, not a regression.
- **D2 (earn-corridor carry):** continuity-carry path, not touched.
- **D3 (cmETH→ETH convert realizes −$197.74):** Market realized unchanged; **must not regress to PnL=0** — Model A
  guarantees this because it never touches realized PnL.
- **AC-11 / D7 (reverted residual-dust):** Model A avoids the failure mode (no `uncovered`, no consumption change).
- **Custody round-trip (`CustodyRoundTripReplaySupport`, envelope):** carry-conserving; not `swapNetRef`; unaffected.
- **Convert semantics (`LinkedBridgeTransferReplaySupport.applyAssetConvertingSettlementOutbound`):** proceeds=destination
  FMV, Market lane; unaffected.

**ADR amendment required:** yes — a new ADR that amends **ADR-054 §2** and **ADR-040 Bug B**, referenced by ADR-062.

---

## D. Ordered implementation plan (upstream-first)

> All code work is delegated to `backend-dev`. This section is the design contract.

### D.1 Step 1 — thread a "net-realized-kept" signal into the swap accumulator
- **File:** `ReplayDispatcher.applySellWithOptionalPool` (lines 922–949).
- Capture the NET realized delta produced by `applySell` (`position.totalNetRealisedPnlUsd()` after − before).
  Record whether it was **kept** (i.e. the counterparty-pool branch did **not** run `undoSellRealisedPnl`).
- Extend the accumulator from `BigDecimal[2]` to carry a third signal (e.g. `swapNetRef[2]` = kept-net-realized
  magnitude, or a parallel `boolean[1]`). Keep the existing `[0]=net`, `[1]=market` slots.

### D.2 Step 2 — re-base the acquired NET basis on a realizing swap
- **File:** `ReplayDispatcher.applyBuyWithOptionalPool` (lines 843–895), the `swapNetRef[1].signum() > 0` branch.
- **Discriminator:** if the disposal's NET realized was **kept** (`swapNetRef[2] > dust`):
  seed `netAcquisitionCost = marketAcquisitionCost` (i.e. `poolAcquisitionCost` if present, else `computeMarketCost`),
  via `applyBuyWithExplicitNetCost(flow, position, marketCost, marketCost)`. **No `min(released, market)`.**
- **Else (kept ≈ 0):** preserve today's behavior exactly — `effectiveNetBasis = min(netBasisReleased, marketCost)`.
- Keep the D1 fail-closed branch (`isCrossCanonicalStakingPrincipalAcquisition` → `applyUnknownTransfer`) unchanged for
  the unpriced-acquisition case.
- **Invariant preserved:** `net ≤ market` (net=market satisfies it); Market lane untouched.

### D.3 Step 3 — engine doc/contract
- **File:** `GenericFlowReplayEngine.applyBuyWithExplicitNetCost` (lines 150–163). No logic change; update the Javadoc to
  state that for realizing distinct-canonical swaps callers pass `net == market` (discount already realized), and the
  inherited-net path is reserved for deferred/pool/unpriced carries.

### D.4 Step 4 — defense-in-depth read-model guard (ADR-062)
- **File:** `BreakEvenCalculator.compute` (income derivation, lines 76–77 / offset lines 127–135).
- Add a per-family plausibility clamp: when `income = netRealized − marketRealized` is positive and exceeds a bound
  derived from tracked zero-cost income (or, until such tracking exists, a conservative multiple of `|marketRealized|`
  or an absolute sanity threshold), **fail the family's NET offset closed to the Market lane** and emit a diagnostic
  flag. This is a guard that would have caught FB-01; it must be a no-op once (b) lands.
- Do **not** change AC-7/AC-8 semantics.

### D.5 Tests
- **Update (semantics corrected, expected):**
  - `ReplayDispatcherCrossCanonicalStakingTest.bybitEthToMethStakingDepositPricedInboundLegBooksInheritedBasisNotZero`
    → mETH NET basis now = market (~$1,889.54), not inherited $1,833.89; Market basis + realized (−$20.33) unchanged.
  - `avaxToSavaxCrossCanonicalStakingDepositInheritsSellNetBasis` / `...PegFloorCapClampsNetAtMarket` — the current
    fixtures leave the AVAX disposal **unpriced** (market authority empty) → NET realized is **not** kept → carry is
    **preserved**, so these should **still pass unchanged**. Add explicit assertion of the discriminator by adding a
    **priced** AVAX→sAVAX variant that expects re-base to market.
- **Add (regression anchors, generalized — no tx-hash keying):**
  1. **Round-trip no-recycle:** C2→C2→C2 realizing round trip (cmETH→PT→cmETH pattern) asserts FAMILY net−market income
     ≈ genuine pre-loop discount only (≈$0), and each mid-loop disposal has NET realized == Market realized.
  2. **Genuine reward preserved:** reward acquired net=$0 then swapped once realizes income exactly once; effective cost
     still reduced.
  3. **Counterparty-pool carry preserved:** pooled/CEX-corridor swap still inherits (deferred), NET realized 0 on the swap.
  4. **Market-lane invariance:** Market basis/realized byte-identical before/after the fix on a realizing swap.
- **Read-model:** `BreakEvenCalculator` guard unit test (fabricated inflated `netRealized` → offset falls back to Market).

### D.6 ADR updates
- **New ADR (next free number, e.g. ADR-082) — "NET-lane realizing-swap re-basing (no basis recycling)":** amends
  **ADR-054 §2** (realizing C1↔C2 / C2↔C2 conversions re-base the acquired NET basis to market acquisition cost; the
  discount is realized once at the conversion, never recycled) and **ADR-040 Bug B** (swap net-carry applies only to
  non-realizing/deferred disposals). Cross-reference from ADR-062 (its `offsetLane=NET` input is now artifact-free) and
  add the amendment note to ADR-054 §2 and ADR-040.

### D.7 Verification (per `prod-rebuild-workflow` rule)
- Pipeline/accounting change → `./scripts/prod-reset-rebuild-backend.sh --skip-frontend`
  (**no** `--clear-pricing-cache`; pricing/aliases unchanged). Then re-run the Phase-1 audit reconstruction and check §E.

---

## E. Numeric acceptance criteria

| Metric | Before | Target |
|---|---|---|
| ETH effective / break-even cost | $2,001.68/ETH | **$2,494–$2,501/ETH (≈$2,500)** |
| ETH average (market) cost | $3,027.30/ETH | **≈$3,025/ETH, unchanged (±1%)** |
| Denominator (ETH-equivalent) | — | **3.82–3.85** (not 4.03) |
| FAMILY:METH net−market income | +$1,894 | **≈$0** (genuine pre-loop discount ~$5 + dust rewards) |
| FAMILY:SOL / AVAX offsets (FB-02) | net > market by ~$73 | **net ≈ market** (corrected) |
| PT-cmETH mirror (FB-03) | latent +$1,899 | **≈$0** (re-based) |
| Global NET realized | $8,038.77 | **≈ Market realized $2,334 + genuine income** |
| D3 cmETH→ETH convert (Market realized) | −$197.74 | **−$197.74, unchanged (no PnL=0 regression)** |
| Conservation invariants (`\|Σ netCostBasisDelta\|` on closed round-trips; `Net AVCO ≤ Market AVCO`; `Net ≥ 0`) | hold | **still hold** |
| D1 (cmETH↔ETH realize-at-market) / D2 / D3 / cmETH↔ETH carry | verified | **no regression** |

---

## F. Risks & mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| Replay-hot-path regression (AC-11 signature) | Low | Model A never marks `uncovered`, never changes consumption or realized-PnL; Market-lane invariance test |
| Discriminator misfires on pool/unpriced carries → loses genuine deferred discount | Low | Discriminator keyed on "net-realized-kept"; pool path already zeroes it; explicit tests (D.5.3/4) |
| Genuine reward write-down accidentally dropped | Low | `ZeroCostAcquisition` path untouched; test D.5.2 |
| ADR-054 §2 amendment scope creep | Med | New ADR strictly amends the NET-lane clause; Market/§2 disposal boundary unchanged |
| Over-broad renorm cost | Low | `--skip-frontend` only; no pricing-cache clear |
| DiD guard threshold too tight → false fallback | Low | Guard is a no-op after (b); tune threshold generously; diagnostic-flag only |

---

## G. Architecture (data flow, ASCII)

```
                        realizing SWAP / cross-canonical STAKING (distinct canonicals)
                                              │
        ┌─────────────────────────────────────┴─────────────────────────────────────┐
        │ ReplayDispatcher.replayGenericFlowsSkipping  (swapNetRef accumulator)       │
        │                                                                             │
        │  outbound-first order                                                       │
        │   ├─ applySellWithOptionalPool ── applySell ── realizes NET PnL             │
        │   │        │                                                                │
        │   │        ├─ pool swap?  ──yes──► undoSellRealisedPnl (NET realized = 0)   │
        │   │        │                        swapNetRef[2]=kept:0  → CARRY (defer)   │
        │   │        └─ non-pool realizing ─► swapNetRef[2]=kept:>0 → RE-BASE         │
        │   │                                 swapNetRef[0]=net, [1]=market released  │
        │   └─ applyBuyWithOptionalPool ─────► if kept>0 : netAcq = marketAcq  (FIX)  │
        │                                      else       : netAcq = min(net,market)  │
        └──────────────────────────────┬──────────────────────────────┬─────────────┘
                     Market lane (unchanged)                 NET lane (corrected)
                             │                                       │
                        AssetLedgerPoint (netRealisedPnlDeltaUsd now artifact-free)
                             │                                       │
             AssetLedgerReconciliation / QueryService ──► FamilyBreakEvenInput
                             │
                     BreakEvenCalculator (offsetLane=NET; DiD plausibility guard)
                             │
                     ETH effective cost ≈ $2,500/ETH   (avg cost $3,025 unchanged)
```

---

## H. Code anchors (reconcile with `financial-logic-auditor` FB-01 localization appendix)

| Concern | File | Lines |
|---|---|---|
| Swap net-basis inheritance (BUY) | `.../replay/dispatch/ReplayDispatcher.java` (`applyBuyWithOptionalPool`) | 843–895 (branch 860–887) |
| Swap net/market accumulation (SELL) | `.../replay/dispatch/ReplayDispatcher.java` (`applySellWithOptionalPool`) | 922–949 |
| NET realized computation | `.../replay/support/GenericFlowReplayEngine.java` (`applySell`) | 165–200 (187–188) |
| Explicit net-cost seeding | `.../replay/support/GenericFlowReplayEngine.java` (`applyBuyWithExplicitNetCost`) | 150–163 |
| Pool realized-undo (carry path) | `.../replay/support/CounterpartyBasisPoolReplayHook.java` (`undoSellRealisedPnl`) | 120–136 |
| Read-model offset consumption | `.../costbasis/breakeven/BreakEvenCalculator.java` | 76–77, 127–135 |
| Cross-canonical gate | `.../replay/dispatch/ReplayDispatcher.java` (`hasCrossCanonicalIdentityPrincipalPair` usage) | 253–268 |
| ADR §to amend | `docs/adr/ADR-054-...md` §2 (Net lane C1→C2); `docs/adr/ADR-040-...md` (Bug B) | — |
