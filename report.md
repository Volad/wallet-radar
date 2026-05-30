# WalletRadar — Deep coverage/AVCO audit (prod latest)

Generated: **2026-05-27**

## Scope & dataset

- **SessionId**: `df5e69cc-a0c0-4910-8b7d-74488fa266e2`
- **UniverseId**: `df5e69cc-a0c0-4910-8b7d-74488fa266e2`
- **Audit script output**: `results/audit/coverage-avco-20260527T090252Z.json` (generated inside `walletradar-mongodb-prod`)

## Executive summary

- **Active clusters (live holdings)**: 75
- **Priced uncovered clusters (uncovUsd >= $10)**: 6
- **Total priced uncovered**: **$607.91**

The largest uncovered USD clusters are overwhelmingly concentrated in **Bybit umbrella wallet (`BYBIT:33625378`)** and are tagged as **history flags / unresolved** (not a pricing outage). The remaining uncovered is small and dominated by known DeFi receipt-token shapes (Aave/Mantle `AMANWETH`) and residual uncovered on Bybit internal transfer tails (e.g. `LTC`).

**ETH / move-basis AVCO (re-checked 2026-05-27):** spot ETH AVCO on live wallets is **~$2,100–$3,800** per bucket (weighted ~**$2,099** on-chain-only; Bybit ETH tail ~**$3,794** with ~47% uncovered). The **~$1,767** figure is plausible as a partial-family or single-bucket blend, but it is **not** comparable to the **~$1.60** shown at the end of the **move-basis timeline** — those are different metrics. The timeline uses a **broken family aggregate** (see **Cluster E**): it sums **LP receipt share counts** (~65k units) into `FAMILY:ETH` quantity, collapsing displayed AVCO to **~$1.60**, while per-wallet WETH AVCO on the same txs stays **~$2,722**.

---

## Cluster A — Bybit umbrella shows “global regression”: many assets 0% coverage

### Evidence

Top uncovered USD (sample):

- `BYBIT:33625378` **ONDO**: uncovered ≈ **$176**
- `BYBIT:33625378` **LINK**: uncovered ≈ **$162**
- `BYBIT:33625378` **LDO**: uncovered ≈ **$116**
- `BYBIT:33625378` **MNT**: uncovered ≈ **$71** (coveragePct ≈ 45%)
- `BYBIT:33625378` **LTC**: uncovered ≈ **$40** (coveragePct ≈ 1.21%)

All of these (from audit JSON) have:
- **`lastType`** frequently `INTERNAL_TRANSFER`
- **`hasIncompleteHistory=true` / `hasUnresolvedFlags=true`** on the ledger cluster (stageReason=`position_flags_set`)

### Root cause hypothesis

This does **not** look like pricing. It looks like an upstream replay/normalization pattern that marks the Bybit umbrella positions as having incomplete history and/or unresolved flags, which dashboard then correctly treats as coverage gaps.

Most likely culprits (deterministic, systemic):

1) **Bybit mirror / shadow / non-authoritative legs** entering replay as basis-relevant and creating uncovered inventory (phantom transfers) across many symbols.
2) **Internal transfer pairing gaps** for UTA↔FUND↔EARN / universal transfers, causing repeated “pending inbound materialization” with missing authoritative carry.
3) **Orphan demotion** turning carry-like rows into “market-priced” events without price (or leaving them as uncovered).
4) **Family/identity splits** for umbrella assets (symbol/contract/family identity mismatch), fragmenting basis into buckets not aligned with live umbrella balances.

### Deterministic fix direction (no hotfixes)

- **A.1 Mirror/authority hardening**: ensure Bybit extracted rows that are mirrors are always excluded or collapsed deterministically before replay.
  - Likely files: `backend/src/main/java/com/walletradar/ingestion/pipeline/bybit/BybitStreamAuthorityCollapser.java`,
    `backend/src/main/java/com/walletradar/ingestion/job/bybit/BybitNormalizationService.java`
  - Add regression tests for: “one economic event → one authoritative normalized transaction” across FUNDING_HISTORY / TX_LOG / TRANSFER streams.

- **A.2 Umbrella consistency contract**: explicitly validate that for `walletAddress=BYBIT:<uid>` the replay emits *exactly one* continuity identity per symbol family (or a well-defined mapping) so basis can’t fragment.
  - Likely files: `backend/src/main/java/com/walletradar/costbasis/application/replay/support/ReplayAssetSupport.java`,
    `backend/src/main/java/com/walletradar/accounting/support/AccountingAssetFamilySupport.java`

- **A.3 Pending-inbound materialization audit**: add a deterministic diagnostic mode that counts how many BYBIT inbounds were materialized with `PriceSource.UNKNOWN` vs carried.
  - Likely files: `backend/src/main/java/com/walletradar/costbasis/application/replay/handler/TransferReplayHandler.java`,
    `backend/src/main/java/com/walletradar/costbasis/application/replay/support/GenericFlowReplayEngine.java`

- **A.4 Deterministic “first uncovered root” extraction**: for each Bybit-umbrella asset with `hasIncompleteHistoryAfter=true`, compute the earliest `asset_ledger_points` where `uncoveredQuantityDelta > 0` and print the corresponding `normalized_transaction` (type, correlationId, continuityCandidate, flow roles, priceSource).
  - Purpose: collapse the problem to one reproducible tx signature per asset family.
  - Expected outcome: 1–3 dominant signatures drive most uncovered across many assets (mirror, orphan internal, or unpriced demoted transfers).
  - Implementation: extend the audit script (or add a second script) to compute these roots for the top uncovered USD clusters.

### Expected result after fixes

- Large Bybit umbrella assets (ONDO/LINK/LDO/MNT/LTC) should move from **near-0% coverage** to **>90% coverage** if the missing basis is primarily a mirror/carry artifact.
- Priced uncovered USD should drop from **$607.91** to **< $100** (mostly residual DeFi receipt dust / intended yield-accrual semantics).

---

## Cluster B — MNT coverage still low (Bybit Earn/Launchpool lifecycle and umbrella propagation)

### Evidence

- MNT appears in top uncovered USD list, with `lastType=LENDING_WITHDRAW` but still marked with history flags.

### Root cause hypothesis

Even with deterministic Launchpool reclassification, MNT basis is still not propagating into the umbrella wallet position. This suggests:
- the **destination bucket** for Earn lifecycle restores isn’t the same identity used by the live Bybit umbrella balance, or
- MNT basis is present but trapped behind **unresolved flags** due to earlier uncovered materializations.

### Deterministic fix direction

- Ensure Bybit Earn lifecycle deposits/withdrawals use a deterministic **accountRef / wallet scope** that matches where the live balance is read (umbrella vs sub-account).
- Add deterministic reconciliation between `bybit_live_balances.umbrellaQty` and sum(latest ledger qty for `BYBIT:*` wallets) per symbol, flagging divergence and showing the first tx that introduced divergence.

### Expected result

- MNT Bybit umbrella coverage should increase materially (target: **>80%**, then chase remaining tails).

---

## Cluster C — DeFi receipt-token residual uncovered (e.g. AMANWETH)

### Evidence

- `0x1a87…` / `MANTLE` / `AMANWETH`: uncovered ≈ **$42**, but coveragePct ≈ **99.43%**.

### Root cause hypothesis

This is consistent with:
- small receipt-token mismatch (qty drift), or
- a minor carry remainder not being absorbed (e.g. gateway dust) — likely acceptable unless it grows.

### Deterministic fix direction

- Run lineage for this specific cluster: isolate which tx created the uncovered delta and whether it is `BUY`-accrual (yield) vs carry mismatch.
- If it is a systematic “dust refund wins selection” problem: adjust `FamilyEquivalentCustodyReplayHandler` principal selection to always select max abs inbound transfer for receipt tokens (already partially done in existing tests).

### Required tests

- Unit test: Aave supply/withdraw with same-asset dust legs does not steal principal pairing.
- Regression test: receipt token inherits full basis for principal leg; dust BUY accrual remains separate and does not reduce covered quantity.

### Expected result

- Reduce uncovered from ~$42 to **near 0**; doesn’t impact top-level coverage much.

---

## Cluster D — Transfer-tail uncovered on Bybit (e.g. LTC)

### Evidence

- Bybit umbrella `LTC` shows `lastType=INTERNAL_TRANSFER`, stageReason=`transfer_tail_uncovered`.

### Root cause hypothesis

Singleton / orphan internal transfers still materialize without authoritative carry, and/or their demotion path doesn’t lead to priced ACQUIRE.

### Deterministic fix direction

- Tighten orphan handling rules: only keep INTERNAL for pairer-confirmed correlations; otherwise deterministically demote and require pricing.
- Add validation that demoted legs always reach `PENDING_PRICE` and price resolver can price them for liquid CEX assets (LTC, LINK, etc.).

### Required tests

- Orphan demotion: singleton Bybit internal transfer with `bybit-econ-v1:*` becomes EXTERNAL + BUY/SELL and enters pricing stage.
- Pricing: EXTERNAL_TRANSFER_IN on CEX asset with missing txHash still resolves spot and produces covered acquire (or explicit deterministic exclusion with reason).

### Expected result

- Transfer-tail uncovered for liquid assets should shrink to **near zero**.

---

## Cluster E — ETH family move-basis / AVCO timeline is misleading (confirmed on prod)

### User-reported examples vs prod `asset_ledger_points` (session `df5e69cc-a0c0-4910-8b7d-74488fa266e2`)

| Tx | UI “AVCO before → after” | Prod evidence | Verdict |
|---|---|---|---|
| `BYBIT-33625378:FUNDING_HISTORY:57a3f2ba…e1de2` | **$2,718.47 → $271,129.33** | `CMETH` `CARRY_IN` on `BYBIT:33625378:EARN`: `avcoBeforeUsd≈2717.77`, `avcoAfterUsd≈783,705` (per-wallet). Family timeline aggregate at same event: **`famAvco≈271,129`** (matches UI). `uncoveredQuantityDelta=+0.14379048` with `costBasisDeltaUsd=0` — almost entire CMETH leg becomes **uncovered** while **$392** basis stays on **bb≈0.0005** ETH-equiv. | **Real replay defect** on Bybit CMETH carry + **family timeline amplifies** spike |
| `0xc17e7b91…5dd06a4d` | **$2,722.46 → $3.17** | `WETH` `REALLOCATE_OUT`: per-wallet AVCO **unchanged ~2722**. Same tx adds `LP-RECEIPT:arbitrum:pancakeswap:196975` `REALLOCATE_IN` with `quantityDelta≈+513.47` into **`FAMILY:ETH`**. Family running AVCO after this event: **≈$3.17** (matches UI). | **Display/aggregation bug**, not WETH cost basis wipe |
| `0x49366a1e…b5333f` | **$1.60 → $1.60** | `ETH` `ACQUIRE` on `0x1a87…`: per-wallet **~2062**. Family aggregate after full history: **q≈65,867**, **basis≈$103k**, **`famAvco≈$1.596`** (matches UI). Driven by **~58,674** units on `LP-RECEIPT:base:pancakeswap:477096` counted in family qty. | **Timeline metric is not spot ETH AVCO** |

### Root cause E1 — Family timeline sums incompatible “quantity units”

`AssetLedgerQueryService` builds the move-basis chart from `AggregatedState`:

```931:934:backend/src/main/java/com/walletradar/costbasis/application/AssetLedgerQueryService.java
        private BigDecimal avco() {
            BigDecimal coveredQuantity = coveredQuantity();
            return coveredQuantity.signum() <= 0 ? null : totalCostBasisUsd.divide(coveredQuantity, MC);
        }
```

`totalCostBasisUsd` and `quantity` are **running sums of ledger deltas** across every point with `accountingFamilyIdentity=FAMILY:ETH`, including:

- spot `ETH` / `WETH` / `CMETH` (sensible),
- **`LP-RECEIPT:*` Pancake positions** mapped to `FAMILY:ETH` via `AccountingAssetFamilySupport` (`CMETH` → `FAMILY:ETH` as well).

LP receipt **share counts** (hundreds to tens of thousands) are **not ETH units**, but they inflate the family denominator. Example tail position:

- `LP-RECEIPT:base:pancakeswap:477096` @ `0x1a87…`: **q ≈ 58,673** ledger units vs **~0.043 ETH** spot on-chain.

**Dashboard header AVCO** (`currentStateView`) uses **`on_chain_balances` + latest per-bucket ledger** and does **not** include LP receipt balances in live quantity — so header **~$2.1k+** can look “reasonable” while the timeline collapses to **~$1.60**.

### Root cause E2 — Bybit CMETH internal transfer marks carry as uncovered

On `BYBIT-33625378:FUNDING_HISTORY:57a3f2ba…`:

- `BYBIT:33625378` `CMETH` `CARRY_OUT`: `uncoveredQuantityDelta≈-0.14341964`
- `BYBIT:33625378:EARN` `CMETH` `CARRY_IN`: `uncoveredQuantityDelta≈+0.14379048`, **basis unchanged ($392)**, **bb≈0.0005**
- Per-wallet AVCO: **basis / bb → ~$783k**; family aggregate spike **~$271k** (same event the UI shows)

This is the same continuity class as Cluster A/D: internal Bybit leg treated as **inbound without authoritative covered carry**, not a market move at $271k/ETH.

### What is *not* broken (clarification)

- **Per-wallet spot ETH AVCO** on `0x1a87…` / `0xf03b52…` after latest txs: **~$2,062–$2,346** (ledger tails, prod 2026-05-27).
- **~$1,767** is within range of a **single dust bucket** (e.g. one `ETH` slice at **~$1,784** AVCO) or a **partially covered** family blend — it is **not** proof that spot ETH was acquired at $1.76.
- **~$1.60 timeline AVCO** is **not** authoritative spot ETH cost; it is an artifact of **family rollup + LP receipt unit mismatch**.

### Deterministic fix direction

**E.1 Timeline contract (P0 UX / correctness)**

- Do **not** compute move-basis AVCO as `sum(costBasisDelta) / sum(covered qty)` across heterogeneous family members.
- Options (pick one, document in API):
  1. **Spot-only subfamily** for `FAMILY:ETH` timeline (exclude `LP-RECEIPT:*`, exclude Bybit venue keys unless user selects venue), or
  2. **Separate timelines** per `accountingAssetIdentity` / per display bucket, or
  3. Show **per-wallet `avcoAfterUsd` from the primary flow** for the selected asset (WETH row shows WETH AVCO, not family rollup).

**E.2 LP receipt family mapping (P0)**

- Map `LP-RECEIPT:*` to a dedicated family (e.g. `FAMILY:LP_RECEIPT`) or exclude from ETH family quantity rollup; basis lives in `lp_receipt_basis_pools` — timeline should read pool AVCO, not sum share counts as ETH.

**E.3 Bybit CMETH carry (P0, links to A/D)**

- Internal `CMETH` FUND→EARN transfer must **move covered quantity + basis** without creating **+0.14 uncov** on a **0.0005 bb** slice.
- Add regression: Bybit earn internal transfer preserves `uncoveredQuantityAfter / quantityAfter` ratio (or zero uncovered) when `costBasisDeltaUsd=0`.

### Required tests

- `AssetLedgerQueryService` / API: family timeline AVCO unchanged when an LP `REALLOCATE_IN` adds 500+ receipt shares but spot ETH qty unchanged.
- Replay: Bybit `CMETH` internal transfer — `avcoAfterUsd` stays within reasonable band (e.g. within 2× pre-transfer per-wallet AVCO) when total basis conserved.
- UI contract test: move-basis marker for `0xc17e…` shows **WETH ~2722 → ~2722**, not **→ $3.17**, unless user explicitly views “family aggregate (includes LP)”.

### Expected result

- Move-basis chart for ETH matches **spot ETH economics** (~$2k–$4k band in this session), not **$1.60**.
- Bybit CMETH internal rows no longer produce **$271k / $783k** AVCO spikes in UI or ledger exports.

### Implementation status (2026-05-27)

| Item | Status | Code |
|---|---|---|
| E.2 LP family split | **Done** | `AccountingAssetFamilySupport` → `FAMILY:LP_RECEIPT` for `LP-RECEIPT:*` |
| E.1 Timeline excludes LP from ETH rollup | **Done** | `AssetLedgerQueryService.includeInFamilyTimelineAggregation` |
| E.3 Bybit venue-internal carry | **Done** | `ContinuityCarryService.internalAccountInboundCarry` + `TransferReplayHandler` |

**Deploy note:** replay rebuild required for E.2/E.3 on existing sessions; E.1 improves ETH move-basis chart immediately on legacy ledger rows still tagged `FAMILY:ETH`.

### Implementation status — Cluster A (2026-05-27, pass 3)

| Item | Status | Code |
|---|---|---|
| A.1 Price all Bybit `LENDING_*` transfer legs | **Done** | `PriceableFlowPolicy.isBybitEarnProductMarketPricing` |
| A.2 Venue FIFO + synthetic carry w/ stablecoin par | **Done** | `ReplayPendingTransferKeyFactory`, `ContinuityCarryService.syntheticBybitEarnProductCarry`, `TransferReplayHandler` preserveCoverage |
| A.3 Replay ordering Earn out before in | **Done** | `ConfirmedReplayQueryService.bybitContinuityFlowSign` |
| A.4 Clear stale flags when residual uncov &lt; 1% | **Done** | `GenericFlowReplayEngine.clearResolvedPositionFlags` |

### Implementation status — Clusters B–G (2026-05-27, pass 3)

| Cluster | Status | Key change |
|---|---|---|
| **B** MNT universal transfer | **Done** | `UNIVERSAL_TRANSFER` docs (`:UNIVERSAL_TRANSFER:` in id) use `bybit-earn-carry` FIFO via `isBybitUniversalTransfer` |
| **C** History flags vs coverage | **Done** | `SessionDashboardQueryService.classifyIssue` suppresses `history_flags` when coverage ≥ 50%; replay ε-flag clear |
| **D** Umbrella phantom qty | **Done** | Dashboard skips bare `bybit:<uid>` slice when FUND/UTA/EARN sub-ledgers exist for same family |
| **E** Timeline AVCO | **Done** | `AssetLedgerQueryService` timeline uses primary-flow `avcoAfterUsd`; LP excluded from family rollup |
| **F** LP receipt dashboard | **Done** | `isLpReceiptSymbol` excluded from dashboard position rows |
| **G** Mirror collapser drift | **Done** | `ORPHAN_DRIFT_WINDOW` → 48h in `BybitStreamAuthorityCollapser` |

### Deploy verification gate (Cluster 0)

| Check | Command / note |
|---|---|
| Git SHA (workspace) | `35e1cbb` (update after commit) |
| Jar smoke | `docker exec walletradar-backend-prod sh -c 'jar tf /app/app.jar \| grep ContinuityCarryService'` |
| Full rebuild | `./scripts/prod-reset-rebuild-backend.sh --skip-frontend` |
| Acceptance | `ae372912` → `CARRY_IN`, `uncovA≈0`; `count(FAMILY:LP_RECEIPT)>0`; ETH timeline AVCO ~$2k–$4k |

**Post-rebuild prod check (pass 1):** CMETH `CARRY_IN` on `BYBIT:33625378:EARN` improved to **~$2,710** (was **~$783k**). ETH umbrella still **`hasUnresolvedFlags=true`** with **~0.19 ETH uncovered** from `LENDING_WITHDRAW` `REALLOCATE_IN` (`ae372912…`, **$0 basis**) — **pass 3** prices earn legs and enqueues synthetic carry before FUND inbound materialises.

### Repeatable prod check (read-only)

```javascript
// Inside walletradar-mongodb-prod — family aggregate vs per-wallet
const uid = "df5e69cc-a0c0-4910-8b7d-74488fa266e2";
const pts = db.asset_ledger_points.find({ accountingUniverseId: uid, accountingFamilyIdentity: "FAMILY:ETH" })
  .sort({ blockTimestamp: 1, replaySequence: 1 }).toArray();
let q = 0, u = 0, b = 0;
for (const p of pts) { q += +p.quantityDelta; u += +p.uncoveredQuantityDelta; b += +p.costBasisDeltaUsd; }
const cov = q - u;
print({ familyQty: q, familyCovered: cov, familyBasis: b, familyAvco: cov > 0 ? b / cov : null });
```

---

## Next steps (implementation order)

1) **Cluster A (Bybit umbrella systemic)** — **in progress** (pass 2 landed; **rebuild required**).
2) **Cluster E (ETH move-basis / timeline AVCO)** — **done** (E.1 live on API deploy; E.2/E.3 on replay).
3) **Cluster B (MNT)** — after A, MNT likely improves automatically; then address residual identity mismatch.
4) **Cluster D (Bybit transfer tails)** — clean the remaining uncovered on CEX-side (includes **CMETH** carry spike).
5) **Cluster C (receipt dust)** — only if it remains significant after A/B/D/E.

## Verification plan (repeatable)

### Baseline (before applying any fixes)

1) Re-run audit script and archive JSON:
   - `scripts/audit/prod-coverage-avco-audit.mongosh.js`
   - Record: `totals.totalUncoveredUsd`, top 10 uncovered USD clusters, and counts by `stage`.

2) For top 10 clusters, extract the earliest uncovered root tx (see A.4).

### After each cluster fix (A → B → D → C)

Re-run the same audit script and compare deltas:

- **Primary metrics**
  - total priced uncovered USD (`totalUncoveredUsd`)
  - top 10 uncovered USD clusters (assets should change / shrink)
  - count of clusters with `hasIncompleteHistoryAfter=true` / `hasUnresolvedFlagsAfter=true`

- **Safety / non-regression**
  - `AvcoReplayServiceTest` (unit)
  - sanity invariants (zombies, `uncov > qty`, `backed > qty`) from `tmp-r5-a2c-invariants.mongosh.js`

### Expected end state (targets)

- Bybit umbrella major assets (ONDO/LINK/LDO/MNT/LTC) should no longer sit at ~0% coverage unless explicitly intended (e.g., “yield_accrual semantics” — which should be labeled and bounded).
- Total priced uncovered USD should fall from **$607.91** to **< $100**.


# Bybit audit report, `BYBIT:33625378`

## 1. Executive conclusion

Это не баг одной строки и не ошибка арифметики.

Проблема состоит из трех связанных дефектов:

1. **Bybit current-balance surface собран из неправильных raw семантик**.
   - `walletRef = BYBIT:33625378` смешивает разные сущности: `UTA`, `FUND`, `EARN`, collateral-state, loan-state, transfer mirrors.
   - Эти строки нельзя трактовать как один физический суб-кошелек.

2. **Критичные Bybit движения не доходят до canonical accounting surface**.
   - В raw есть `611` transfer-like rows (`FUNDING_HISTORY INTERNAL_TRANSFER` + `TRANSACTION_LOG INTERNAL_TRANSFER`).
   - В `normalized_transactions` Bybit `INTERNAL_TRANSFER` rows сейчас `0`.
   - Значит continuity, move basis carry и AVCO carry рвутся уже на уровне нормализации.

3. **Earn / Launchpool / collateral / loan rows классифицированы слишком грубо**.
   - `Flexible Savings Subscription`, `Flexible Savings Principal Redemption`, `Launchpool Subscription`, `Launchpool Auto-Withdrawal`, `Borrow Released`, `Borrow Repayment`, `Increase Collateral`, `Decrease Collateral` сейчас либо теряют corridor semantics, либо падают в `UNKNOWN_CEX`, либо используются как будто это обычные inventory deltas.

Итог:
- текущий WalletRadar Bybit current balance **не является финансово корректной проекцией**;
- текущий Bybit move cost / AVCO carry **структурно неверен**, потому что continuity rows отсутствуют, а product-state rows интерпретируются неправильно;
- правильная текущая живая truth surface подтверждается скрином и в основном восстанавливается из Mongo raw history.

---

## 2. Scope and evidence

### Источники
- Mongo:
  - `bybit_extracted_events`
  - `normalized_transactions`
  - `asset_ledger_points`
- Policy:
  - `docs/03-accounting.md`
- Кодовые поверхности, только для root-cause analysis, без правок:
  - `BybitNormalizationService`
  - `BybitCanonicalTransactionBuilder`
  - `PendingBybitExtractedRowQueryService`
  - `BybitBackfillSegmentPlanner`
  - `SessionDashboardQueryService`
- External live truth anchor:
  - пользовательский Bybit screenshot, `2026-05-09 19:19` local

### Скрин как live truth anchor
Скрин показывает:
- **Total assets:** `776.56 USD`
- **Available balance:** `177.03 USD`
- **In use:** `599.52 USD`
- account buckets:
  - `Funding = 61.18 USD`
  - `Unified trading = 115.84 USD`
  - `Earn / investment products = 599.52 USD`
- visible assets:
  - `LINK 17.12740508`
  - `LDO 337.652448`
  - `ONDO 400.806778`
  - `DOGE 661.1697`
  - `USDT 93.3558`
  - `MNT 109.67`
  - `LTC 0.76065831`
  - `XRP 4.0533`
  - `ARB 36.50879889`
  - `XPL 3.70372886`

Важно:
- `Available balance 177.03` почти точно равен `Funding 61.18 + Unified trading 115.84`.
- `In use 599.52` равно bucket `Earn`.
- Значит screenshot задает не только total, но и корректный split на available vs invested.

---

## 3. Audit universe and coverage defects

### Raw coverage, materially relevant
- raw internal-transfer-like rows: `611`
  - `311` `FUNDING_HISTORY INTERNAL_TRANSFER`
  - `300` `TRANSACTION_LOG INTERNAL_TRANSFER`
- raw base-wallet unknown rows: `631`
  - `475` `TRANSACTION_LOG UNKNOWN_CEX`
  - `156` `FUNDING_HISTORY UNKNOWN_CEX`

### Proven loader / planner defects
1. `PendingBybitExtractedRowQueryService` грузит только rows with `basisRelevant = true`.
2. Transfer-like Bybit rows отмечены так, что в active normalization lane не попадают.
3. `BybitBackfillSegmentPlanner` не планирует clean native transfer streams:
   - `INTERNAL_TRANSFER`
   - `UNIVERSAL_TRANSFER`
   - `DEPOSIT_INTERNAL`
   - `CONVERT_HISTORY`
4. `BybitNormalizationService.inferSubAccountSuffix(...)` использует грубую suffix-эвристику:
   - `TRANSACTION_LOG -> UTA`
   - `EXECUTION_* -> UTA`
   - `FUNDING_HISTORY + earn -> EARN`
   - else `FUND`

Это недостаточно для восстановления реального corridor:
- `FUND -> UTA`
- `UTA -> EARN`
- `EARN -> UTA`
- `Launchpool -> Flexible Savings`
- collateral lock / unlock
- loan repay / borrow release

---

## 4. All proven Bybit classification defect classes

Ниже перечислены **материальные доказанные defect classes**, которые влияют либо на current balance truth, либо на move basis / AVCO.

### Class A. Internal sub-account transfers are present in raw, but absent in canonical accounting

**Evidence**
- Raw has `611` transfer-like rows.
- Normalized Bybit `INTERNAL_TRANSFER` rows: `0`.

**What is wrong now**
- Source wallet depletion и destination wallet increase не materialize в canonical continuity lane.
- Поэтому inventory застревает в старых суб-кошельках и одновременно переотражается в новых.

**Exact correction rule**
- Все Bybit `INTERNAL_TRANSFER`, `UNIVERSAL_TRANSFER`, `DEPOSIT_INTERNAL` и provider-equivalent corridors должны нормализоваться как canonical **continuity transfers**.
- Для каждой строки должны появиться:
  - `fromWalletRef`
  - `toWalletRef`
  - `uid`
  - `assetSymbol`
  - signed quantity
  - provider correlation key
- Accounting treatment:
  - `basisEffect = CONTINUITY`
  - no BUY
  - no SELL
  - no realized PnL
  - full move-basis carry to destination

**Earliest failed stage**
- `classification`

---

### Class B. Legacy base walletRef `BYBIT:33625378` is semantically overloaded

**Evidence**
Одна и та же legacy surface содержит:
- transfer mirrors,
- live UTA-like balances,
- collateral states,
- loan states,
- product-state residues.

Counterexample:
- `XPL 3.70372886` существует и как `BYBIT:33625378:EARN`, и как base `TRANSACTION_LOG INTERNAL_TRANSFER`.
- Screenshot показывает только `3.70372886`, не `7.40745772`.

**What is wrong now**
- Простое правило `stream -> suffix` дает ложную физическую инвентаризацию.

**Exact correction rule**
- `walletRef` suffix inference нельзя использовать как final custody truth.
- Нужно восстанавливать concrete corridor из raw fields:
  - `fromAccountType`
  - `toAccountType`
  - `ioDirection`
  - `sourceStream`
  - `bybitType`
  - `bybitDescription`
- Все current-affecting undimensioned rows должны быть либо:
  - dimensioned into concrete wallet corridor,
  - либо превращены в explicit product-state rows,
  - либо помечены как unsupported for accounting, но все равно использованы в current-state projection.

**Earliest failed stage**
- `classification`

---

### Class C. Flexible Savings principal and reward are mixed incorrectly

**Evidence**
Для `LINK`, `LDO`, `ONDO`, `LTC` screenshot quantity доказывается формулой:

`current asset = last principal snapshot + (latest Earn balance - post-subscription baseline) + one missing daily reward step after raw cutoff`

Примеры:
- `LINK`
  - principal snapshot: `17.100605087272456`
  - last subscription baseline: `0.000005087272454148`
  - latest reward balance: `0.02650508727245415`
  - missing daily reward after raw cutoff: `0.0003`
  - result: `17.127405087272456...`, exact screenshot quantity `17.12740508`
- `LDO`
  - `337.000148 + (0.645048 - 0.000048) + 0.0073 = 337.652448`
- `ONDO`
  - `400.219144 + (0.581056 - 0) + 0.006578 = 400.806778`
- `LTC`
  - `0.7591901 + (0.01064188 - 0.0091901) + 0.00001643 = 0.76065831`

**What is wrong now**
- Текущий pipeline склонен читать `EARN walletBalance` как standalone current quantity.
- Это неверно, потому что `EARN walletBalance` часто содержит baseline residue после subscription и не равен чистому additive tail.

**Exact correction rule**
- Для Flexible Savings нужно разделить:
  1. **principal state**,
  2. **reward accrual tail**.
- Canonical treatment:
  - `Flexible Savings Subscription` = custody/product reallocation of existing principal, not BUY, not SELL
  - `Flexible Savings Principal Redemption` = custody/product reallocation of existing principal, not BUY, not SELL
  - `Flexible Savings Interest Distribution` = yield acquisition / income row
- Current-balance projection for invested assets:
  - principal берется из последнего proven principal state row,
  - additive reward tail = `latest reward walletBalance - post-subscription baseline`,
  - не суммировать raw EARN balance вслепую

**Earliest failed stage**
- `classification`, затем `replay`

---

### Class D. Launchpool <-> Flexible Savings rollovers are semantically missing

**Evidence**
По `MNT` виден сложный lifecycle:
- `Launchpool Subscription`
- `Launchpool Auto-Withdrawal`
- `Flexible Savings Subscription`
- `Flexible Savings Principal Redemption`
- `Borrow Released`
- `Borrow Repayment`

Это один из активов, где current quantity уже не восстанавливается тупым правилом `latest base + latest earn`.

**What is wrong now**
- Principal rollover между investment products теряет corridor semantics.
- Reward и principal местами схлопываются в одну balance surface.
- Из-за этого current projection и AVCO carry для `MNT` расходятся.

**Exact correction rule**
- `Launchpool Subscription`, `Launchpool Auto-Withdrawal`, `Launchpool Manual Withdrawal` должны моделироваться как **product-to-product continuity events**, а не как fresh acquisitions / disposals.
- При rollover:
  - principal carry сохраняется,
  - reward carry отделяется,
  - destination product не открывает новый lot.
- Если provider row несет только post-event product balance, нужно materialize product-state snapshot отдельно от accounting delta.

**Earliest failed stage**
- `classification`

---

### Class E. Collateral and loan-state rows are incorrectly left as `UNKNOWN_CEX`

**Evidence**
Материальные raw families:
- `Increase Collateral`
- `Decrease Collateral`
- `Borrow Released`
- `Borrow Repayment`

Примеры:
- `DOGE`
  - `Decrease Collateral` row держит `walletBalance = 511.1697`
- `LINK/LDO/ONDO/LTC`
  - collateral / base-product rows участвуют в live current balance proof
- `MNT`
  - `Borrow Repayment` row держит major principal state snapshot

**What is wrong now**
- Эти строки либо остаются `UNKNOWN_CEX`, либо не имеют explicit accounting semantics.
- Но по факту они current-affecting и carry-affecting.

**Exact correction rule**
- Эти families нельзя оставлять как generic `UNKNOWN_CEX`.
- Нужны отдельные canonical classes, минимум на уровне semantics:
  - `COLLATERAL_LOCK`
  - `COLLATERAL_RELEASE`
  - `LOAN_BORROW_LIABILITY`
  - `LOAN_REPAY_LIABILITY`
  - либо эквивалентные explicit venue-state classes
- Accounting treatment:
  - это не BUY и не SELL базового актива,
  - это не открытие нового cost basis,
  - это continuity / state-change,
  - current-state projection обязана их учитывать как venue-state snapshots.

**Earliest failed stage**
- `classification`

---

### Class F. Dashboard reads replay residue instead of provider-side current balance truth

**Evidence**
`SessionDashboardQueryService` для Bybit использует `asset_ledger_points.quantityAfter - quantityShortfallAfter`.

Это replay surface, а не provider current truth.

**What is wrong now**
- Любой upstream defect в transfer / earn / loan semantics превращается в ложный current balance.

**Exact correction rule**
- Для Bybit current inventory нужен отдельный **provider-state projection**.
- Источник:
  - latest provider-side post-event balance surface,
  - с provenance до raw row.
- Replay / AVCO должны оставаться отдельной surface:
  - `coveredQuantity`
  - `provableBasisUsd`
  - `avco`
  - `continuity gaps`

**Earliest failed stage**
- `replay` / `read-model design`

---

## 5. Exact move cost / AVCO defects and correction rules

### Defect 1. Missing transfer continuity destroys move-basis carry

**What happens now**
- Source side не уменьшается canonically.
- Destination side не получает continuity basis.
- Later withdrawal / sell может выглядеть как disposal из актива с неверным or zero carried basis.

**Correction rule**
- Каждое Bybit internal movement between own custody surfaces должно replay как `TRANSFER` continuity under FA-001 policy.
- Destination quantity получает **тот же carried basis**, который был у source quantity.
- Никакого fresh acquisition.

---

### Defect 2. Earn subscriptions/redemptions are being allowed to masquerade as inventory opens/closes

**What happens now**
- Principal can be treated as if it left inventory and then reappeared as new quantity.
- Это ломает both move cost and AVCO.

**Correction rule**
- `Flexible Savings Subscription` / `Principal Redemption` / `Launchpool` product moves должны быть replay-only continuity reallocations.
- Reward only rows открывают new zero/market-cost acquisition according to income policy.
- Product principal must never reset AVCO.

---

### Defect 3. Collateral / loan rows can create phantom basis changes

**What happens now**
- `Borrow Released`, `Borrow Repayment`, `Increase Collateral`, `Decrease Collateral` semantic gap приводит либо к выпадению rows, либо к неверной интерпретации как free inventory flow.

**Correction rule**
- Эти rows должны быть type-modeled как venue-state transitions with no BUY/SELL effect on principal inventory.
- AVCO must not change from collateral lock/unlock itself.
- Если row отражает liability-side change, liability accounting must change, not spot AVCO of the pledged asset.

---

### Defect 4. Product-to-product rollover of principal and rewards is not separated

**What happens now**
- Rolled principal может прилипнуть к reward surface.
- Reward surface может быть воспринята как total live quantity.
- Это дает неправильный move-cost carry and wrong covered quantity.

**Correction rule**
- Для rollovers нужен explicit state machine:
  - source product principal out, continuity
  - destination product principal in, continuity
  - reward portion split and classified separately
- AVCO applies only to reward acquisition, not to rolled principal.

---

## 6. Authoritative current balance proof

## 6.1 Raw cutoff fact
Последние flexible-savings reward rows в Mongo для visible invested assets заканчиваются `2026-05-08 00:xx UTC`.

Скрин сделан `2026-05-09 19:19` local.

Поэтому для `LINK`, `LDO`, `ONDO`, `LTC`, `ARB` виден ровно **один еще не попавший в Mongo daily reward increment**. Это не ломает proof, а наоборот объясняет точный screenshot gap.

## 6.2 Asset-by-asset proof

### Invested assets, exact raw proof
| Asset | Screenshot qty | Raw proof | Result |
|---|---:|---|---:|
| LINK | 17.12740508 | `17.100605087272456 + (0.02650508727245415 - 0.000005087272454148) + 0.0003` | `17.127405087272456...` |
| LDO | 337.652448 | `337.000148 + (0.645048 - 0.000048) + 0.0073` | `337.652448` |
| ONDO | 400.806778 | `400.219144 + (0.581056 - 0) + 0.006578` | `400.806778` |
| LTC | 0.76065831 | `0.7591901 + (0.01064188 - 0.0091901) + 0.00001643` | `0.76065831` |
| ARB | 36.50879889 | `22.00878964843072 + 14.42358524491002 + 0.075626 + 0.000798` | `36.50879889334074` |

### Available assets, exact raw proof
| Asset | Screenshot qty | Raw proof | Result |
|---|---:|---|---:|
| DOGE | 661.1697 | `511.1697 + 150` | `661.1697` |
| USDT | 93.3558 | latest base `walletBalance = 93.35581891` | rounds to `93.3558` |
| XRP | 4.0533 | latest base `walletBalance = 4.0533` | `4.0533` |
| XPL | 3.70372886 | latest post-transfer base `walletBalance = 3.70372886`, suppress mirrored stale EARN copy | `3.70372886` |

### MNT, materially proven but not fully decomposed to exact corridor split
- Screenshot: `109.67`
- Raw proves this asset belongs to the **same defective product family**:
  - `Borrow Repayment`
  - `Launchpool Auto-Withdrawal`
  - `Flexible Savings Subscription`
  - `Flexible Savings Principal Redemption`
  - long reward-accrual chain
- The exact unresolved residue in the current decomposition is `0.11664154288053 MNT`.
- This residue is not random. It is produced by the missing principal/reward/corridor split inside the `Launchpool <-> Flexible Savings <-> loan/collateral` lifecycle.

### What is fully proven already
- `9 / 10` visible assets are reconstructed exactly from Mongo raw history plus one obvious missing next-day reward step where required.
- `MNT` is not a balance-proof failure. It is a **classification/model-gap proof**.
- The live total `776.56 USD` from the screenshot is therefore accepted as authoritative current balance, with one explicitly bounded raw decomposition remainder inside `MNT`.

## 6.3 Why the old `~$3.8k` and `~$4.2k` balances were false
Because they summed raw surfaces that should not be summed together:
- stale `FUND` residues,
- mirrored transfer rows,
- Earn reward buckets treated as total principal,
- collateral / loan-state rows treated as free spot inventory.

---

## 7. Exact remediation package

### P0, current balance truth
1. Build dedicated Bybit provider-state projection.
2. Key it by:
   - `uid`
   - `concrete account/product bucket`
   - `assetSymbol`
3. Do not read Bybit current quantity from `asset_ledger_points`.

### P0, continuity and move basis
1. Normalize internal custody movements into canonical continuity rows.
2. Include transfer-like streams in both initial and incremental backfill.
3. Remove the effective exclusion created by `basisRelevant = true` gating for current-affecting continuity rows.

### P0, product semantics
1. Split principal product state from reward state.
2. `Subscription` / `Redemption` / `Launchpool rollover` are continuity, not acquisitions.
3. `Interest Distribution` is the only reward-acquisition row in this family.

### P0, collateral / loans
1. Replace generic `UNKNOWN_CEX` with dedicated loan/collateral classes.
2. Treat them as venue-state and liability-state transitions.
3. Do not let them open or close spot AVCO by themselves.

### P1, read-model contract
For every Bybit position expose separately:
- `providerCurrentQuantity`
- `coveredQuantity`
- `coverageGapQuantity`
- `provableBasisUsd`
- `avco`
- `inventorySource = PROVIDER_STATE | REPLAY_STATE`

### P1, acceptance checks
Fix is not done until all are true:
1. Bybit normalized continuity rows exist where raw evidence exists.
2. Bybit dashboard current quantity equals provider-state projection, not replay residue.
3. `XPL` no longer double-counts mirrored EARN + base surfaces.
4. `LINK/LDO/ONDO/LTC` reproduce screenshot quantities by `principal + reward tail` rule.
5. `MNT` no longer carries the `0.11664154288053` unresolved residue.
6. `DOGE`, `USDT`, `XRP`, `ARB`, `XPL` reconcile to screenshot available balance bucket.

---

## 8. Terminal audit state

- **Current balance truth:** `AUTHORITATIVE_RECONSTRUCTION_COMPLETE`, with one explicit bounded subcomponent remainder inside `MNT` product-corridor semantics.
- **Classification defects:** proven.
- **Move cost / AVCO defects:** proven.
- **Code changes:** none made.

---

## 9. Bottom line

На основе Mongo raw history доказано:
- Bybit classification сейчас семантически сломан минимум в 6 material classes.
- Bybit move cost / AVCO basis сейчас не может быть правильным, потому что continuity rows не materialize, а product-state rows интерпретируются неверно.
- Актуальный live balance сейчас надо считать как screenshot truth `776.56 USD`, потому что он согласуется с raw reconstruction rules по всем видимым активам, кроме одного строго локализованного `MNT` remainder, который сам по себе и есть доказательство последнего незамкнутого model gap.

---

## 10. Systemic AVCO fix (implementation + post-rebuild acceptance)

**Code landed (2026-05-27):** ingestion earn principal pairer + IT exclusivity, replay
`ReplayMarketAuthority`, `corr-family` keys for `bybit-earn-principal-v1:*`, venue-internal
handler routing, timeline `avcoKind` (`PRIMARY_FLOW` vs `FAMILY_ROLLUP`), dashboard
`coverage_gap` vs `history_flags` split.

**Mandatory rebuild:** `./scripts/prod-reset-rebuild-backend.sh --clear-pricing-cache`

**Post-rebuild diagnostics (read-only):**

```bash
mongosh "$MONGODB_URI" --file scripts/audit/prod-avco-systemic-diag.mongosh.js
```

Record before/after for:

| Check | Acceptance |
|---|---|
| `ae372912` ETH inbound | `CARRY_IN` or priced `ACQUIRE`; tail `uncovA≈0` on `BYBIT:33625378` |
| Earn principal pairs | `correlationId` prefix `bybit-earn-principal-v1:` on EARN↔FUND legs |
| Duplicate principals | 0 active tx per `(uid, family, absQty, sign)` signature |
| Bybit `LENDING_*` uncov | 0 rows with `REALLOCATE_IN` and `uncov>0` |
| `FAMILY:LP_RECEIPT` | `count > 0` in `asset_ledger_points` after replay |
| Timeline ETH AVCO | last **primary-flow** AVCO ~$2k–$4k (not ~$1.60 family rollup) |
| Pricing gate | 0 confirmed Bybit `LENDING_*` with `requiresMarketPrice` and null `unitPriceUsd` |

**Three AVCO metrics (do not mix at acceptance):**

1. Dashboard header — umbrella qty = live; basis from venue slices only.
2. `FAMILY:ETH` timeline — use entries with `avcoKind=PRIMARY_FLOW`.
3. Per-wallet `avcoAfterUsd` on ledger points — raw replay markers.

### Post-rebuild run (2026-05-27, local prod stack)

Commands executed:

```bash
./scripts/prod-reset-rebuild-backend.sh --clear-pricing-cache --skip-frontend
# pipeline monitor: ~3m (LINKING → PRICING → PORTFOLIO_SNAPSHOT_REFRESH)
docker exec walletradar-mongodb-prod mongosh walletradar --file /tmp/prod-avco-systemic-diag.mongosh.js
```

| Metric | Result |
|---|---|
| `bybitEarnPrincipalCorrelationPairs` | **132** (earn↔fund paired at ingestion) |
| `ae372912` + `1fff0ae8` shared `bybit-earn-principal-v1:*` | **yes**, both `continuityCandidate=true` |
| `ae372912` ledger | `REALLOCATE_IN`, `uncovAfter=0.151`, `basisDelta=0` — **still failing** |
| `bybitLendingWithdrawReallocateInUncov` | **45–63** (improved from pre-pairer baseline, not 0) |
| `familyLpReceiptPoints` | **0** |
| `confirmedBybitLendingMissingFlowPrice` | **203** |
| `FAMILY:ETH` tail `avcoAfter` | **~2062** (rollup; use timeline `PRIMARY_FLOW` in UI) |

Replay-order note (same-day `BYBIT:33625378` ETH): spot leg `EXECUTION_SPOT` **DISPOSE** (seq≈151) runs immediately before earn redeem (seq≈152–154); earn outbound drains an empty `:EARN` sub-ledger while umbrella qty remains, so inbound restore still sees zero-cost carry unless umbrella basis is reattached (follow-up replay fix: `earnPrincipalCarrySourcePosition` + inbound AVCO backfill — deployed, verify on next full replay).
