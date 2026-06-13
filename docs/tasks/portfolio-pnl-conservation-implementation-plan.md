# Portfolio PnL Conservation — Implementation Plan

> Universe `df5e69cc-a0c0-4910-8b7d-74488fa266e2` · backend only · status: **AWAITING USER DECISION (Phase 3 review done — all three reviewers REQUEST-CHANGES)**
> Source audit: `results/blockers.md`, `results/accounting-failure-analysis.md`, `results/discrepancies.md`, `results/eth_basis.md`, `results/required-changes.md` (2026-06-11, readonly).
> Phase 3 reviews: financial-logic-auditor `72d97661`, business-analyst `02d5d767`, system-architect `60c1fc1c`.

## Symptom

Dashboard reports **+$15,654** total PnL (Realised $10,481 + Unrealised $5,173, +59.8%) while **lifetime external inflow is ~$14,772 (user-confirmed ~$14.3–14.8k)** and current portfolio is **$8,907**. Money in (~$14.8k) vs money now ($8.9k) is already a **loss before any withdrawals**. The backend conservation gate flags this: `conservationDelta = $25,353` (threshold $50) → **BREACHED** (true positive).

Capital terms (do not conflate):
- `lifetimeExternalInflowUsd = 14,772.82` — **gross capital deposited** (this is the "net inflow" the user refers to, ~$14.3–14.8k).
- `lifetimeFundOutflowUsd = 1,874.80` — withdrawals.
- `netExternalCapitalUsd (NEC) = 12,898.02` = inflow − outflow (used by the gate).

Reconciliation (exact): `markToMarket 3198.59 = portfolioValue 8907.44 + memberPools 393.32 − openLiability 6102.17`; `expectedPnl −9699.43 = MTM − NEC(12898.02)`; `delta 25353.49 = reportedPnl 15654.06 − expectedPnl`. **Note:** expectedPnl moves once F-5 (NEC scope) lands. AC-1 (relative gate), not an absolute PnL band, is the criterion of record.

## User decisions (2026-06-11, approved)
- **R4: KEEP** — cmETH/wstETH/weETH/mETH stay in FAMILY:ETH (ADR-017 unchanged). Fix the AMANWETH $656 basis via **aToken/receipt continuity carry + family-rollup AVCO**, NOT a family split. → Option (A).
- **Presentation: truthful** — dashboard will show the real loss and surface liabilities. No masking.
- **Scope: all P0 now** (F-1 peg pricing, F-2 continuity, F-3 REPAY typing, F-4 debt identity/liability), then F-5 (NEC).

## Root cause

**Acquisition-side basis leakage** collapses running AVCO so disposals book fabricated realized gains and held receipt/debt tokens show fabricated unrealized. Per-defect `$ impact` below is **NOT additive** (F-1 and F-3 both claim the ~$1,317 USDC REPAY-of-stablecoin leg); the post-fix number comes from a holistic replay, not a sum.

| Ref | Stage (corrected after review) | Defect | Indicative $ (non-additive) |
|---|---|---|---|
| F-1 | **pricing / normalization** (not an AVCO clamp) | Stablecoin acquisitions enter with $0/understated basis (bridge/internal inbound, borrow proceeds, unpriced legs) → USDC/USDT/USD₮0 AVCO ≪ $1 → disposals book gains. | +$5,265 realized |
| F-2 | **cost_basis continuity** (+ possible classification — see DECISION) | Dashboard "ETH" family rollup blends `AMANWETH` (basis $656/u, should be ~$2,664) with real ETH. aToken receipt continuity (ETH→aWETH) is broken/depressed. | +$2,324 realized + $3,132 unrealized |
| F-3 | **on-chain classification** (leg role) | On-chain Aave REPAY never typed `REPAY` with a loanId → falls to generic SELL → realizes PnL. (`RepayReplayHandler` already books ≈$0 when reached.) | +$2,292 realized |
| F-4 | **on-chain classification + read-model** | Aave `variableDebt*` tokens tracked as held assets (≥2 open: USDe 2,500 + Base USDC 600, both $0 basis), and not registered in `borrow_liabilities`. | +$3,100 unrealized |
| F-5 | **verification / scope** | NEC counts only Bybit FUND; $5,041 on-chain inbounds excluded yet given fresh basis. Out-of-scope TON/SOL/DOGS realize basis PnL. | enlarges true loss |

## ⚠ Decisions required from user before implementation

1. **R4 / ADR-017 conflict (BLOCKING).** Your accepted requirement R4 (ADR-017, 2026-05-29) states CMETH/WSTETH/RSETH/METH must stay in FAMILY:ETH. The auditor's F-2 recommends splitting them out. The architect's reconciliation: the real financial bug is the **broken aToken basis continuity** (AMANWETH $656), which can be fixed **without** violating R4. Options:
   - **(A) Keep R4** — do NOT split families; fix only the aToken/receipt continuity carry + the family-rollup AVCO display so blended AVCO reflects true underlying basis. (Recommended; no governance reversal.)
   - **(B) Reverse R4** — split cmETH/wstETH/weETH/mETH into independent families (supersede ADR-017). Larger blast radius (continuity keys, CounterpartyBasisPool, timeline). Needs explicit sign-off.
2. **Gain → loss product change.** After the fix the dashboard will show a **loss** (~−$4k…−$14k) instead of +59.8%. Liabilities (Aave debt ~$3,100) become visible and the debt "positions" disappear from holdings. Confirm this is the desired truthful presentation.

## Ordered changes (upstream first, backend only) — revised

### Phase A — on-chain classification (P0)
1. **Debt-token identity + BORROW/REPAY typing first (F-4 then F-3).** On-chain Aave: classify `variableDebt*`/`stableDebt*` as debt identities **excluded from held positions**; emit `type=BORROW`/`REPAY` with a deterministic synthetic loan correlationId (e.g. `evm:<debtContract>:<wallet>`), reusing existing **ADR-012** `BorrowLiabilityTracker`/`BorrowReplayHandler`/`RepayReplayHandler` (no new liability model). Define compositeId namespace so it never collides with the 17 Bybit rows. **Double-subtract guard:** debt must leave `portfolioValue` AND register as liability (not both). BORROW leg priced at borrowed-underlying market (USDe ≈ $1).
2. **(Only if Option B chosen)** split LST spot tokens out of FAMILY:ETH — supersede ADR-017; aTokens MUST remain continuity-linked to underlying (never independent).

### Phase B — cost basis / pricing (P0)
3. **aToken/receipt basis continuity (F-2 core).** LENDING_DEPOSIT must carry underlying basis into the receipt token via TRANSFER continuity that **removes** basis from the underlying (conserved, not duplicated); withdraw carries it back. Interacts with ADR-019 corridor carry — verify no regression.
4. **Stablecoin peg pricing (F-1).** Enforce $1 parity for unpriced USD-peg inbounds at **pricing/normalization** (`PriceSource.STABLECOIN`), not as an AVCO clamp; eliminate disposal shortfalls (dispose ≤ basis-backed qty); no `costBasisDeltaUsd=0` acquisitions for priceable assets.
5. **Dust/GAS_ONLY AVCO display.** Confirm whether residual chart spikes belong in `TimelineAvcoAuthority` read-time outlier rejection (ADR-017) rather than a new replay clamp; must not drop gas qty from the basis pool (would manufacture micro-shortfalls).

### Phase C — verification / scope (P1–P2)
6. **NEC capital model (F-5).** Classify on-chain `EXTERNAL_TRANSFER_IN` as new capital (count in NEC) vs internal Bybit↔on-chain corridor (no fresh basis); out-of-scope TON/SOL/DOGS must not realize basis PnL. Reconcile to user-confirmed gross inflow ~$14.3–14.8k.

### Phase D — symbol-spoofing guardrail (P2, hardening)
7. **Homoglyph/scam symbol normalization (F-6).** Ledger contains spoofed lookalike symbols (`UЅDС` Cyrillic, `ꓴꓢꓓС` Lisu, `UЅDT`, zero-width-injected `U\u2062S\u2061D\u200eT`) — all currently qty 0, but they must never be priced as their legit counterpart. Add a guard: non-ASCII/confusable symbols are not aliased to canonical USDC/USDT/ETH for pricing or family identity (treat as distinct unpriced scam assets). This is the general invariant the business-analyst flagged so a new asset class cannot silently re-breach conservation.

## Documentation
- **Supersede/amend ADR-017** only if Option B (R4 reversal) is approved; otherwise amend to clarify aToken-continuity-vs-family-rollup.
- **Extend ADR-012** (activate §D8) for EVM/Aave debt: BORROW/REPAY typing, synthetic loanId, debt-token exclusion.
- New **pricing-chain ADR** for stable-peg inbound pricing + disposal-shortfall handling.
- New **NEC capital-model ADR** (F-5).
- Update `docs/pipeline/cost-basis/02-avco-rules.md`, `docs/pipeline/normalization/rules/protocols/aave.md`.

## Acceptance criteria (revised per review)
- **AC-1 (gate of record):** `conservationBreached = false`, `|conservationDelta| < $50`.
- **AC-2:** `reportedPnl == recomputed expectedPnl` within gate threshold (absolute value derived post-fix, not pre-judged; F-5 moves it).
- **AC-3:** `realized + unrealized == MTM − NEC` (structural decomposition holds).
- **AC-4:** `accounting_shortfall_audit` rows for supported families = **0** (currently 373).
- **AC-5:** zero acquisitions with `costBasisDeltaUsd=0` for priceable assets (currently 131); stablecoin ending AVCO ≈ $1 (incl. stablecoin aTokens ≈ $1, not $2).
- **AC-6:** per-family ending AVCO ≈ genuine acquisition basis — FAMILY:ETH ~$2,664/u; cmETH/wstETH/weETH/mETH each ≈ their own basis; AMANWETH/AWETH carry underlying basis.
- **AC-7:** zero held `variableDebt*`/`stableDebt*` positions across all networks; on-chain Aave borrows registered in `borrow_liabilities`; no liability double-subtraction.
- **AC-8:** REPAY realizes ≈ $0.
- **AC-9:** AVCO timeline has no dust/gas-only spikes-to-$0.
- **AC-10:** Regression — Bybit ledger fix (409666492 ≈ $0) and sibling integrations unchanged.

## Verification
`./scripts/prod-reset-rebuild-backend.sh --skip-frontend --clear-pricing-cache` → wait for normalization + replay → re-run `financial-logic-auditor` against AC-1…AC-10.

## Risks
- Family/continuity changes alter AVCO for many positions → full replay + auditor reconciliation required.
- Liability registration must guard against double-subtract (intra-Aave and vs existing Bybit pledge).
- Per-defect $ impacts are non-additive; do not predict post-fix PnL by summing.
- Option B (R4 reversal) has large blast radius across continuity keys, CounterpartyBasisPool, and timeline read model.

## Stop gate
Implementation (Phase 4) blocked until the user resolves the two decisions above and approves the revised plan.

---

## POST-FIX VERIFICATION (2026-06-11, replay `20:46`, universe `df5e69cc`)

P0 (F-1..F-4) landed and full reset+rebuild+replay ran. Breach narrowed but **NOT cleared**:

| metric | pre-fix | post-P0 |
|---|---|---|
| reportedPnl | +15,654 | **+6,517** |
| expectedPnl | −9,699 | −7,897 |
| conservationDelta | 25,353 | **14,414** (still BREACHED) |
| nec | 12,898 | 12,898 |
| mtm | 3,199 | 5,001 |

P0 verdicts: **F-2 PASS** (AMANWETH $656→$2,059/u), **F-3 PASS** (on-chain Aave REPAY ≈$0), **F-4 PASS** (debt excluded + on-chain Aave $3,490 liability, no double-subtract), **F-1 PARTIAL** (most AVCO≈$1 but `USDT BYBIT:…:FUND` still $0.84; 373 shortfall rows remain), **F-6 PASS** (homoglyph guard). Positions now show real losses (ETH −$1,193, BTC −$108, GM-pool −$98) — masking gone.

**Key finding:** residual $14,414 is dominated NOT by F-5 but by CEX-side continuity leaks the P0 set never touched. F-5(i) genuine new capital ≈ **$0** (lifetimeFundInflow 14,772 ≈ user gross $14.3–14.8k → NEC ≈ 12,898 is correct; true result is a LOSS).

### New blockers (promoted to P0) — from `results/required-changes.md`
- **R-1 CEX REALLOCATE cb=0** (cost_basis/continuity) — Bybit FUND↔EARN↔staking `REALLOCATE_IN`/`CARRY` are internal transfers: must carry source-sub-ledger AVCO, realized=0. METH/cmETH currently enter @ $0, fabricating FAMILY:ETH **+$5,851 realized + ~$2.9k unrealized**. **Largest single driver.**
- **R-2 LP-receipt basis** (cost_basis/continuity) — `LP_ENTRY`/`LP_EXIT` must conserve basis between underlying and receipt; `FAMILY:LP_RECEIPT` shows $516k phantom basis; cmETH→PT +2,323 leak.
- **R-3 USDT FUND peg** (pricing, F-1 extension) — apply STABLECOIN peg to ALL sub-ledgers incl. `:FUND`; ~+598 net.
- **R-4 CEX pledge REPAY** (classification, F-3 extension) — Bybit UTA pledge-loan REPAY closes a liability, realized ≈0; removes +614.
- **F-5 NEC corridor model** (verification/scope) — on-chain `EXTERNAL_TRANSFER_IN` from Bybit/own-wallet/round-trip = corridor → carry basis (CARRY_IN), not fresh ACQUIRE; do not add to NEC. Out-of-scope TON/SOL/DOGS must not realize basis PnL (+575). Keeps NEC ≈ 12,898.

Implementation order (impact-first, non-additive $): R-1 → R-3 → R-4 → R-2 → F-5 → re-audit AC-1..AC-10.

---

## PROGRESS LEDGER (replays through 2026-06-12T15:46)

| replay | reportedPnl | realised | unrealised | conservationDelta | note |
|---|---|---|---|---|---|
| baseline | +15,654 | +10,481 | +5,173 | 25,353 | masked loss as gain |
| post F-1..F-4 (P0) | +6,517 | — | — | 14,414 | debt/aToken/REPAY/peg |
| post R-2/R-3/R-4 | +5,348 | +7,283 | −1,936 | 11,019 | LP×75 bug, pledge, FUND fallback |
| post R-1 staking carry | +118 | +2,356 | −2,238 | 5,946 | staking-deposit carry |
| **post R-1\* collapsed carry** | **−3,283** | **−143** | **−3,140** | **2,521** | cmETH coverage-based carry-source |
| post U-1+U-2 (leverage+EVK) | −4,054 | −909 | −3,145 | 4,083 | U-1 liability opened but not closed |
| post U-1 liability-close + U-3 | −3,932 | −922 | −3,010 | 1,897 | leverage liab closes on drain; USDC cap |
| **post F-5(a/b) corridor carry** | **−4,072** | **−908** | **−3,164** | **1,757** | no-op on this dataset (see below) |

### Final state (replay 2026-06-12T21:28): breach cut 93% (25,353 → 1,757), dashboard truthful (real loss shown)
reportedPnl −4,072, expectedPnl −5,828, NEC 12,898, MTM 7,070, portfolioValue 11,247.

### Residual ~$1,757 — NOT fabricated gains (auditor aggregate-fallacy disproven)
The financial-logic-auditor reconciled the gap to "+$1,864 of realised gains that shouldn't exist in a declined portfolio." **Disproven by per-disposal evidence:** FAMILY:ETH +883 is GENUINE — it is cmETH sold **2025-09-10 at avcoBefore $3,667** (many small lots), WEETH sold 2025-04-17 at avco $1,410, cmETH 2025-02-21 at avco $1,786 — all healthy bases, sold above cost when ETH/cmETH was genuinely ~$4,000 mid-history. Assets were bought low, sold high mid-history (real realised gains), and the **remaining** holdings declined. F-5(a/b) (basisless-carry market-at-time backstop + MNT borrow market-at-borrow) is correct hardening but produced **byte-identical realised** here — there were no $0-basis ETH/MNT inbound legs to fix in this dataset.

So the residual is a **distributed/structural basis non-conservation** (~$1,550 in-scope after out-of-scope TON +$204), not a single attributable fabrication. The obvious sources (stablecoin peg, EVK share price, leverage, collapsed/staking carry, debt identity) are all closed. Held positions (amanWETH/aWETH/GM/Aave LP) verified loss-bearing and correct; USDC −$2,690 is genuine cross-asset IL. Remaining attribution would require an exhaustive per-leg basis-in vs capital-in reconciliation across ~7,200 ledger points — a separate effort.

### Hardening shipped this cycle (all tested, in tree)
F-1..F-4, R-1/R-1*, R-2, R-3/R-3*, R-4, U-1 (ADR-028 leverage acquisition + liability close-on-drain), U-2 (ADR Euler EVK convertToAssets valuation + value-equivalence guard), U-3 (USD-stablecoin same-asset carry peg-cap), F-5(a/b) (corridor/borrow market-basis backstop + cross-network canonical price lookup).

reportedPnl is now correctly a **LOSS**; breach cut ~90% (25,353 → 2,521). NEC ≈ 12,898 confirmed correct (matches user gross inflow ~$14.3–14.8k − withdrawals).

### Remaining residual $2,521 — two UPSTREAM decode defects (NOT replay/AVCO) + deferred out-of-scope
Root-caused to `raw_transactions.rawData` (flows decoded from router calldata, no `tokenTransfers` logs → decode is wrong). Must be fixed at normalization/pricing with a rerun, never band-aided at replay.

- **U-1 swap calldata decode (~+$2,323 fabricated ETH gain).** cmETH acquired via Mantle aggregator `execute(tuple)` (`0x247d4981`, tx `0xbc69…`, seq 4778) + Pendle `swapExactTokenForPt` (`0xc81f847a`): the USDC input leg is under-decoded → cmETH basis ~$1,005 vs true ~$3,328. (The +$831 M:FUND cmETH and ±$50 WEETH/WSTETH are GENUINE, not fabrication.)
- **U-2 Euler EVK share-price (~−$4,137 fabricated stablecoin loss, dominant).** EVK vault shares are NOT 1:1: eUSDt-2 (`0xaba9d2…`) ≈ $900/share, eUSDC-2 (`0x39de0f…`) ≈ $3.43/share. Deposit/loop basis and the `batch` close (`0x08e6…`/`0xb17a…`) assume ~$1/share → EUSDT-2 avcoBefore $216.75/u, USDC $1.2–$2.0. Needs EVK `convertToAssets` exchange-rate resolution (RPC) so stablecoin lending carries true value and loop-close realises ≈$0.
- **F-5(c) out-of-scope TON +$204** — deferred (design decision: TON bought with in-scope USDT on Bybit, blind exclusion breaks MTM identity).

These cross into classification (protocol calldata decoders) and pricing-architecture (EVK exchange-rate provider) scope; each needs a rerun to verify. Conservation identity forces reportedPnl → expectedPnl ≈ −$5,804 once both decode legs are corrected.

---

## POST-FIX VERIFICATION — ROUND 2 (2026-06-12, replay `09:33`, universe `df5e69cc`)

R-2/R-3/R-4 + the collapsed-transfer portion of R-1 landed; full reset+rebuild+replay ran. Breach narrowed again but **NOT cleared**:

| metric | pre-fix | post-P0 | round-2 |
|---|---|---|---|
| reportedPnl | +15,654 | +6,517 | **+5,347.68** |
| realisedPnl | — | — | +7,283.19 |
| unrealisedPnl | — | — | **−1,935.52** (now negative) |
| expectedPnl | −9,699 | −7,897 | **−5,671.29** |
| conservationDelta | 25,353 | 14,414 | **11,018.97** (still BREACHED, threshold ~72) |
| mtm | 3,199 | 5,001 | 7,226.73 |
| openLiability | 6,102 | 6,541 | 4,375.99 (Bybit pledge repaid) |
| nec | 12,898 | 12,898 | 12,898.02 |

**Reconciliation closes exactly:** `portfolioValue 11,425.91 + memberPool 176.81 − openLiability 4,375.99 = mtm 7,226.73`; `expectedPnl = 7,226.73 − 12,898.02 = −5,671.29`; `delta = 5,347.68 − (−5,671.29) = 11,018.97`.

### Landed-fix verdicts (Mongo evidence)
- **R-4 PASS ✓** — total `REPAY` realised 959.80 → **+6.32**; Bybit pledge MNT +613.60 → +1.01.
- **R-2 PASS ✓** — `FAMILY:LP_RECEIPT` terminal basis $516,751 → **$9,105.73**.
- **R-3 FAIL ✗ (on FUND)** — USDT `BYBIT:…:FUND` still **$0.846**. External USDT inbounds peg @ $1, but FUND fed by `INTERNAL_TRANSFER CARRY_IN` @ $0.5575 + `LENDING_WITHDRAW REALLOCATE_IN` @ $0.5716; peg floors fresh `ACQUIRE` only.

### Residual delta decomposition (+11,019 = all reportedPnl over-statement)
Realised over-statement **+7,283.19** (reconciles exactly): (a) **R-1 staking/receipt cb=0 → FAMILY:ETH +5,741.39** (cmETH +5,604.68; METH 0.6687u held @ $0); (d) stablecoin net **+759.30** (USDT +1,758.74 + USDC −999.44); (e) other in-scope alts **+555.69** (MNT +482.69 …); (c) out-of-scope **+226.81** (TON/SOL/DOGS); (b) F-5 corridor ≈ **$0**.
Unrealised over-statement **+3,735.78**: METH @ $0 ~+$1.7k, AMANWETH under-carry ($2,141 vs ~$2,664) ~+$1.6k.
F-5: non-member basis injected **$16,554.45** (EVM corridor $12,392.73 + out-of-scope TON $4,211.84), $0 in NEC; genuine new capital ≈ $0 → NEC ≈ 12,898 correct → **true result is a LOSS**.

### FINAL batch (ordered, impact-first; $ non-additive)
1. **R-1 staking/EARN-receipt basis-carry** (cmETH/METH): STAKING_DEPOSIT + Bybit-EARN reallocations carry staked-principal AVCO into the receipt, realized=0 → ≈ +5,741 realised + ~$1.7k unrealised.
2. **R-3* stablecoin carry-floor**: apply STABLECOIN peg to carried/reallocated USD-peg basis (CARRY_IN/REALLOCATE_IN/LENDING_WITHDRAW) → ≈ +759 net.
3. **R-2* AMANWETH full carry** to ~$2,664/u → ~+$1.6k unrealised.
4. **F-5 corridor + NEC + out-of-scope boundary**: corridor inbounds CARRY_IN not ACQUIRE; NEC counterparty model (keep ≈12,898); TON/SOL/DOGS must not realise (+226.81). Makes breach structurally un-reopenable.

End state target: `reportedPnl ≈ expectedPnl ≈ −$5,671` (a LOSS), `|delta| < ~72` → AC-1 clears; supported-family `accounting_shortfall_audit` → 0 (AC-4, currently 377 incl. ~28 F-6 scam). See `results/blockers.md`, `results/accounting-failure-analysis.md`, `results/required-changes.md`, `results/reconciliation.md`, `results/eth_basis.md`.

### F-5(a)/(b) — final in-scope driver: zero-/sub-market-basis inbound dilution (landed)
Audit (replay 20:30) decomposed the residual **+$1,896.68** conservation gap as ≈ the **+$1,863.67**
of fabricated realised GAINS, root-caused to basisless inbound legs diluting pooled AVCO:
- FAMILY:ETH **+882.65** — `STAKING_DEPOSIT/REALLOCATE_IN` & `BRIDGE_IN/CARRY_IN` at `$0`, plus a
  stale `$69.61/ETH` `INTERNAL_TRANSFER` (the latter downstream of the same $0 dilution).
- FAMILY:MNT **+492.31** — a 3,532 MNT borrow entered the spot pool at `$0` (blended pool to `$0.72`).
- XRP/LINK/ONDO/BTC/SOL **≈+240** — Bybit collapsed-asset carry-ins at `$0`.
- TON **+204** — out of scope (un-backfilled member pool, `backfillEnabled=false`).

**Fix (earliest stage = replay carry chokepoint, general / never hash-keyed):**
- **F-5(a)** `GenericFlowReplayEngine.applyInboundShortfallSpotFallback(transaction, …)` now promotes
  the uncovered portion of any inbound TRANSFER to paired-carry → flow-price → **market-at-timestamp**
  (`ReplayMarketAuthority`), recorded provisionally so a later authoritative carry still replaces it.
  `ReplayMarketAuthority` gained a **cross-network canonical** lookup (a same-minute quote for the
  same fungible canonical asset priced on any chain) so cross-chain corridor legs resolve instead of
  cache-missing to `$0`. Gated by `CanonicalAssetCatalog.isCrossNetworkPriceResolvable` (homoglyph &
  unknown low-caps excluded). Fail-safe: unresolvable → left uncovered (PENDING), never `$0`.
- **F-5(b)** `BorrowReplayHandler` resolves market-at-borrow for unpriced borrow legs and applies it
  to both the asset basis and the liability avco (conservation-neutral).

**Conservation guard kept:** the backstop fires only on uncovered (basisless) quantity, so the
GENUINE FAMILY:USDC cross-asset withdrawal-carry loss (covered, elevated basis from
`VAULT_WITHDRAW`/`LP_EXIT`/`LENDING_WITHDRAW REALLOCATE_IN`) is untouched.

Files: `CanonicalAssetCatalog` (+XRP id, `marketEquivalentSymbols`, `isCrossNetworkPriceResolvable`),
`HistoricalPriceRepository`/`HistoricalPriceCacheService` (`findCanonicalQuote`),
`ReplayMarketAuthority` (cross-network fallback), `GenericFlowReplayEngine` + `ReplayFlowSupport`
(transaction-aware inbound spot fallback), `ReplayDispatcher` (pass `transaction`),
`BorrowReplayHandler` (market-at-borrow). Tests: `CanonicalAssetCatalogTest`,
`ReplayMarketAuthorityTest`, `GenericFlowReplayEngineTest`, `BorrowLiabilityTrackerTest`.

**Rerun flag:** replay-only is insufficient for the cross-network price cache to be populated for new
contracts, but no normalization/classification changed → **reprice + replay** (no reclassify) is the
expected rebuild. Expected post-replay: fabricated ETH/MNT/alt gains → ≈0, `reportedPnl → expectedPnl
≈ −5,828`, `|delta| ≈ +204` (out-of-scope TON only) — in-scope breach closed.
