# Audit — Coverage `quantityShortfall`, per-pool correctness, LTC earn gap, ETH move-basis AVCO

Independent, raw-evidence audit. READ-ONLY (no application code changed). Wallet
`0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f` + Bybit UID `33625378` + L2s/Solana.

## TL;DR

- `quantityShortfallAfter` is a **monotonic, lifetime-cumulative** counter (only ever *added* to,
  never decremented — `GenericFlowReplayEngine.recordQuantityShortfall`, line 808). It is **not** a
  correctness signal for final holdings. Final quantity/avco on the dashboard come from
  `on_chain_balances` (+ Bybit live balances), reconciled against the *latest* ledger point — the
  cumulative shortfall never feeds that math (`AssetLedgerQueryService` current-state builder).
- The "USDC ≈ 482K / 648M" figure is **not real**. 648,486,371 of it is ONE mis-scaled soUSDC (Silo)
  `LENDING_WITHDRAW` leg (raw 6-dec integer used as display units). Excluding receipt-token scaling
  artifacts, real USDC-family cumulative shortfall ≈ 5,811 over 230,061 of lifetime inflow.
- Final quantities are economically **correct** for all supported spot families (anchored to
  on-chain / exchange balances). The shortfall counter reflects **basis-coverage order artifacts +
  normalization scaling bugs**, not lost coins.
- LTC 0.511 gap = a `:EARN` subscribe funded by an `INTERNAL_TRANSFER` (UTA→EARN) that the earn
  pairer never scans, compounded by equal-principal matching that cannot represent a
  multi-source subscribe. Confirmed from raw evidence.
- The chart plots **Net AVCO as primary** (solid cyan, filled) and Tax AVCO as secondary (dashed
  faint). Verified in `asset-ledger-page.component.ts`.
- ADR-044 (native-ETH inflow recovery) is **necessary but NOT sufficient**: the ERC-20 / Bybit
  coverage gaps have *different* root causes (Bybit earn coverage + receipt-token decimals), not the
  native-ETH router leg-drop.

---

## (B) What `quantityShortfallAfter` / `hasIncompleteHistoryAfter` really mean

Mechanic (`GenericFlowReplayEngine`):
- On any removal, `externalShortfallQuantity = max(requestedRemoval − availableQuantity, 0)`
  (`consumeQuantity*`, lines 812-878).
- `recordQuantityShortfall` does `quantityShortfall += externalShortfall` and calls `markUnresolved`
  → sets `hasIncompleteHistory = true` (lines 798-810). **It is never subtracted back** when a later
  inbound arrives.
- `AssetLedgerQueryService.familyShortfallSources` sums *positive* `quantityShortfallDelta` per
  wallet/tx (top-20 diagnostic list) — again a running sum, never netted.

Therefore `quantityShortfallAfter` accumulates three unrelated things into one number:
1. **Normalization scaling bugs** — a leg quantity delivered in raw integer units vs display units
   (soUSDC 648.5M, spam ERC20 1.0e21). Huge, meaningless.
2. **Replay-order artifacts** — an outflow processed before its linked inflow (sub-account internal
   transfers, bridge legs). Self-heals in *net holdings* but permanently scars the counter.
3. **Genuinely uncredited inflows** — a real inbound whose basis (and sometimes quantity) was never
   materialized (Bybit earn subscribes funded via internal transfer).

**Verdict:** `quantityShortfallAfter` is a *misleading* correctness signal and must **not** be used
by a reconciliation gate. `hasIncompleteHistoryAfter` is a sticky boolean set by the same path, so it
over-reports (any historical order artifact flips it true forever for that identity).

**Durable metric to replace it:** per-pool terminal reconciliation
`Δ = terminalLedgerQuantityAfter − authoritativeBalance`, where `authoritativeBalance` =
`on_chain_balances` for on-chain pools and Bybit/venue-reported balance for venue pools, evaluated
**at the terminal point only** (not cumulative). A pool is healthy iff `|Δ| ≤ dust` regardless of how
much shortfall accrued mid-history. Basis-coverage health is a separate, second metric:
`uncoveredQuantityAfter / quantityAfter` at the terminal point.

---

## (A) Per-asset coverage verdict table

`shortfall` = family cumulative positive `quantityShortfallDelta`. `final-qty correct?` = terminal
quantity reconciles to on-chain / exchange authoritative balance.

| Asset | Cum. shortfall | inflow | Final-qty correct? | Category | Root cause | Durable fix + seam |
|---|---|---|---|---|---|---|
| USDC | 648,492,183 | 230,061 | Yes (holds ~3,433 real) | (a) scaling + (b) order | 648,486,371 is ONE soUSDC Silo `LENDING_WITHDRAW` leg in raw 6-dec units (tx `0x6468e36f`); rest ≈5,811 are order artifacts | Receipt-token decimals in normalization leg extractor; verify soUSDC decimals in registry |
| USDT | 2,250 | 98,585 | Yes | (b) order + (a) | Bybit sub-account `INTERNAL_TRANSFER` / `SWAP` processed before inbound credited | Bybit sub-account continuity (see F) |
| MNT | 351 | 26,996 | Yes | (b) order | Bybit `INTERNAL_TRANSFER` UTA↔FUND before credit | Bybit sub-account continuity |
| SOL | 7.57 | 51.8 | Yes (native SOL 0; bbSOL held) | (b) order | Solana venue transfer ordering | Bybit/Solana continuity; SOL native out-of-scope for basis |
| LINK | 16.8 | 146.7 | Yes (Bybit-held) | (a) genuine uncredited | Bybit `EARN_FLEXIBLE_SAVING` redeem with tiny `quantityBefore` → principal never subscribed-credited | Earn pairer fix (see C) |
| LDO | 319.6 | 2,910 | Yes (Bybit-held) | (a) genuine uncredited | Same Bybit-earn class as LTC; redeems > available | Earn pairer fix (see C) |
| ONDO | 272.9 | 4,096 | Yes (Bybit-held) | (a) genuine uncredited | Same Bybit-earn class | Earn pairer fix (see C) |
| ARB | 36.4 | 177.9 | Yes (holds ~0.12) | (b) order + one (a) | Bybit `INTERNAL_TRANSFER` order + one on-chain `EXTERNAL_TRANSFER_OUT` (22) with no credited inbound | Bybit continuity + external-inbound attribution |
| ETH | ~0.105 | (family conserved) | Yes (3.92 conserved) | native-ETH under-credit | BASE native ETH avco=0 (router native output dropped) | ADR-044 (in progress) |
| soUSDC | 648,486,371 | — | Yes (net 0) | (a) scaling | Silo receipt token raw-units leg | Receipt-token decimals (see F) |
| spam ERC20 (D0DF5D, C9AFAC, XYZ) | 1e21 / 1.1e9 / 74k | — | N/A (junk) | (a) scaling / junk | Unknown 18-dec spam, raw units | Exclude unpriceable/unknown junk from shortfall metric |

No supported family is category **(c) real economic under-count**: every supported terminal
quantity reconciles to authoritative balances. All "loss" numbers are (a) scaling artifacts or
(b) monotonic-counter order artifacts.

---

## (C) LTC durable fix

**Raw evidence** (Bybit UID 33625378, LTC), subscribe cluster `2026-06-27 06:46:57`:

```
EARN_FLEXIBLE_SAVING  :EARN  +0.51096338  corr=NULL   <- subscribe INTO earn
EARN_FLEXIBLE_SAVING  :FUND  -0.01146338  corr=NULL   <- FUND funds only the dust/interest slice
INTERNAL_TRANSFER     :UTA   -0.49950000  corr=NULL cc=false  <- MAIN principal funded from UTA
```

The prior UTA swap (2026-06-24, `SWAP BUY LTC 0.4995`) is the real basis source. The subscribe of
0.51096338 is funded by **two sources of unequal size**: 0.4995 from `:UTA` (typed
`INTERNAL_TRANSFER`) + 0.01146338 from `:FUND` (typed `EARN_FLEXIBLE_SAVING`).

**Why it fails** — `BybitEarnPrincipalTransferPairer.pairEarnPrincipalTransfers()`:
1. Candidate query (lines 76-83) selects only `LENDING_DEPOSIT | LENDING_WITHDRAW |
   EARN_FLEXIBLE_SAVING`. The `:UTA -0.4995` leg is `INTERNAL_TRANSFER` → **invisible** to the pairer.
2. `matchesEqualPrincipal` (lines 279-293) requires `|subQty − redeemQty| ≤ 1e-8`. The visible
   `:FUND -0.01146338` ≠ 0.51096338 → rejected. The subscribe stays `corr=NULL`, `continuityCandidate`
   unset → replay has no carry source → the 0.4995 principal is uncovered / not materialized into the
   EARN pool. Terminal LTC 0.7501 vs Bybit authoritative ~1.26 (missing 0.511).

**Durable fix (two parts, both required):**
- **Widen the funding universe.** The earn corridor's funding leg is *any* same-UID/same-family
  sub-account balance movement into/out of `:EARN` at the subscribe/redeem timestamp — including
  `INTERNAL_TRANSFER`. Add `INTERNAL_TRANSFER` (UTA/FUND↔EARN) to the candidate set, gated to legs
  whose counter-account is `:EARN`.
- **Replace equal-principal single-leg matching with conservation-based multi-leg aggregation.** An
  `:EARN` subscribe of `+Q` is covered by the *set* of co-event (same timestamp ± `CO_EVENT_MAX_SKEW`),
  same-UID, same-family, opposite-sign sibling legs across `:FUND`/`:UTA` whose **signed sum = −Q**
  within tolerance (here −0.4995 + −0.01146338 = −0.51096338). This is the only model that represents a
  multi-source subscribe and is protocol-faithful (Bybit consolidation-netting).

**Exact seam:** `ingestion/pipeline/bybit/BybitEarnPrincipalTransferPairer.java` — candidate query
(`pairEarnPrincipalTransfers`, L74-84), `pairCoEventSiblings` (L148-196), `matchesEqualPrincipal`
(L279-293). This same fix closes the LDO/ONDO/LINK Bybit-earn shortfalls (identical class).

**Verify:**
```
// after fix: no :EARN subscribe leg should remain corr=NULL when a same-ts opposite-sign
// sibling set sums to its principal
db.normalized_transactions.find({ source:"BYBIT", type:"EARN_FLEXIBLE_SAVING",
  walletAddress:/:EARN$/, correlationId:null }).count()  // expect 0 for closed cycles
// terminal LTC reconciles to Bybit authoritative
```

---

## (D) ETH move-basis inflection ledger (justified vs artifact)

The chart series is one authoritative point per grouped event (`TimelineAvcoAuthority.selectAuthoritativePoint`
+ outlier gating). Interleaving all per-network native-ETH identities (as a raw query does) produces
false "sharp drops" that the authority suppresses. Classified:

**Justified (real economic buy/sell/exit changing avco):**
- 2025-01-10 SWAP +0.1511 ETH cbΔ 500 → avco 3306.
- 2025-11-17 LP_EXIT +0.2273 ETH cbΔ 901 → 4157 (`0x6b57e6439d`).
- 2025-11-21 SWAP +0.071 @2295 (`0xabbd2bc64e`) + BRIDGE_IN +0.285 KATANA cbΔ 794 (`0xc69ef1199e`).
- 2025-12-04 LENDING_WITHDRAW +0.3994 zkSync cbΔ 1309 (`0x2df84c4d03`).
- 2026-02-06 LP_EXIT +0.7963 cbΔ 2065 (`0x0a757aeeb5`).
- 2026-02-19 LENDING_WITHDRAW +3.0459 WETH cbΔ 8653 (`0xe564fec189`) — big aWETH unwind.
- 2026-06-03 SWAP +0.5471 @1828 cbΔ 1000 (`0x10dab47f2a`); 2026-06-26 LP_ENTRY −0.21 WETH (`0xa2e9b986da`).

**By-design Net-lane steps (ADR-040, NOT artifacts):** Net avco drops on `$0`-cost rewards while Tax
avco holds — e.g. 2025-11-01 LP_FEE_CLAIM ACQUIRE +0.0063 ETH TAX 3940 / NET 578 (`0x950ccd0a02`);
2025-12-18 TAX 3588 / NET 1682. Correct dual-lane behavior.

**Artifacts (economically wrong at the per-network identity, largely suppressed by the authority):**
- **BASE native ETH avco = 0** — router native-ETH output not credited with basis. e.g. 2025-07-21
  UNWRAP ETH@BASE avco 0; 2025-07-28 BRIDGE_OUT ETH@BASE avco 0; 2025-08-01 REWARD_CLAIM ETH@BASE 0.
  → This is exactly the **ADR-044 native-ETH under-crediting** class.
- **Sponsored-gas / L2 native $0-basis dilution** — ARBITRUM native ETH avco excursions to
  860–1707 from `SPONSORED_GAS_IN` adding tiny $0-basis ETH; null-avco (`TAX="-"`, NET 0) points.
- **Per-network identity interleaving** — the visual 3194→860 "cliffs" are identity switches, not
  economic events; `TimelineAvcoAuthority.isTaxAvcoOutlier` / `capCarriedAvco` (OUTLIER_MULTIPLIER=10,
  LOW_COVERAGE_RATIO=0.01) gate these out of the chart line.

---

## (E) Net-AVCO charting confirmation (file/line evidence)

`frontend/.../asset-ledger/asset-ledger-page.component.ts`:
- **Net AVCO = primary line**: `netAvcoPoints` drawn `strokeStyle rgba(34,211,238,.85)`, `lineWidth
  1.75`, solid, **with gradient area fill** (L3182–3215).
- **Tax AVCO = secondary**: `taxAvcoPoints` drawn `rgba(255,255,255,.28)`, `lineWidth 1.25`,
  **dashed** `[5,4]` (L3163–3180).
- Markers/price-comparison anchor `primaryAvcoAfter = marker.netAvcoAfterUsd ?? marker.avcoAfterUsd`
  (L3217) — Net first, Tax only as fallback when Net is null.
- Backend `TimelineAvcoAuthority.Resolution` carries both lanes; `netAvcoOnPoint` falls back to Tax
  only when `netAvcoAfterUsd == null` (L352-360).

**Confirmed: primary plotted line is Net AVCO.** Two flags (not lane-swaps, but masking to note):
1. `reconcileMarkerAvcoSeries` (L2170-2211) carries the previous avco forward when a marker's after
   value is null on `CARRIED_FORWARD / LP_ENTRY / SPONSORED_GAS_IN / REALLOCATE_OUT / CARRY_OUT` —
   a **frontend cosmetic carry-forward** that can hide a genuine drop-to-null. Prefer fixing the
   upstream null (basis coverage) over carrying forward in the view.
2. `primaryAvcoAfter` fallback to Tax at a single null-net marker briefly mixes lanes on that point.

---

## (F) Prioritized durable root-cause fixes

1. **Retire `quantityShortfallAfter` as a correctness/gate signal.** Replace with terminal per-pool
   reconciliation to authoritative balances (see B). Seam: `AssetLedgerQueryService` current-state +
   any `NativePoolReconciliationGate`. Do not gate on a monotonic counter.
2. **Bybit earn coverage** (LTC/LDO/ONDO/LINK): widen earn-pairer candidate set to `INTERNAL_TRANSFER`
   and switch to conservation-based multi-leg sibling aggregation. Seam:
   `BybitEarnPrincipalTransferPairer`. Highest-value: closes the largest genuine (a)-class gaps.
3. **Receipt-token decimals** (soUSDC/Silo, eUSDC/Euler, spam ERC20): the leg extractor must scale
   receipt-token quantities by the token's own decimals before replay. Seam: normalization leg
   extraction for `LENDING_DEPOSIT/WITHDRAW` receipt legs + protocol/token registry decimals. This
   removes the 648M artifact.
4. **ADR-044 native-ETH inflow recovery** — keep; it fixes BASE native ETH avco=0. Scope note: this
   does **not** address (2) or (3), so it is necessary but not sufficient for the broader coverage.
5. **External-inbound counterparty/basis attribution** for on-chain `EXTERNAL_TRANSFER_OUT` with no
   credited inbound (ARB 22, TON 80 [TON out-of-scope]).

**Fragile/heuristic logic to replace:**
- Monotonic `quantityShortfall` accumulation + sticky `hasIncompleteHistory` (over-reports forever).
- Equal-principal single-leg earn matching (cannot model multi-source subscribes).
- Frontend `reconcileMarkerAvcoSeries` carry-forward (masks upstream null-avco).
- `zombie-uncov clamp` / `purgeOrphanBasisWhenEmpty` (compensations that hide the ordering defect
  rather than fixing linking order).

---

## (G) mongosh queries used

1. Terminal ledger point per `accountingAssetIdentity` (qty/shortfall/avco), sorted by shortfall.
2. Per-family cumulative positive `quantityShortfallDelta`, shortfall event count, lifetime
   inflow/outflow (USDC/USDT/MNT/SOL/LINK/LDO/ONDO/ARB).
3. `on_chain_balances` USDC-family + soUSDC identity point timeline (`0x2514...`).
4. `normalized_transactions` flows for soUSDC withdraw `0x6468e36f` (confirmed raw-unit leg
   −648,487,021.67).
5. Top-25 `quantityShortfallDelta` events across all points (pattern classification).
6. Bybit LTC normalized legs timeline (confirmed INTERNAL_TRANSFER-funded subscribe).
7. ETH-family spot avco progression (inflection detection).
8. `on_chain_balances` for USDT/MNT/LINK/LDO/ONDO/ARB/SOL/LTC + Bybit-venue presence check
   (confirmed venue assets absent from on_chain_balances → reconciled via Bybit live API).
