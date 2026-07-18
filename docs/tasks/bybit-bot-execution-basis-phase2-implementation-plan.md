# NEW-12 Phase 2 — Deterministic Cost Basis for Bybit Trading-Bot Cross-Asset Conversions

**Status:** Implementation-ready design (design only — no application code in this document)
**Defect:** NEW-12 (`results/blockers.md`, `results/flagged-tx-audit-3.md`)
**Companion ADR:** [ADR-058: Bybit Trading-Bot Compartment Cost Basis](../adr/ADR-058-bybit-bot-compartment-cost-basis.md)
**Constraints:** Java 21 / Spring Boot / Gradle; deterministic long-term (no dataset-specific hashes, no amount-only heuristics); free / already-configured Bybit v5 API only.

> **ADR numbering note (must-read for the engineer / owner):** the task brief asked for
> `ADR-055-bybit-bot-compartment-cost-basis.md`, but `ADR-055` is **already taken**
> (`ADR-055-fh-crypto-loans-source-authority.md`). Per the one-decision-per-file ADR
> convention we must not overwrite it. `ADR-056` and `ADR-057` are also taken. This plan
> therefore ships the new ADR as **ADR-058**. If the owner wants a different number,
> rename the file and update the two cross-references (this plan + the ADR header).

---

## 0. TL;DR — what actually changed vs. the brief's assumptions

The reconnaissance was mostly correct, but two facts discovered while designing change the
shape of the work. They are load-bearing and the engineer must internalise them:

1. **The `EXECUTION_SPOT` stream already exists end-to-end.** `BybitIntegrationStream.EXECUTION_SPOT`
   is defined, planned in both `planInitialBackfill` and `planIncrementalBackfill`
   (`BybitBackfillSegmentPlanner`, 7-day windows), fetched by `BybitBackfillSegmentExecutor`,
   and extracted by `BybitExtractionService.extractExecution(...)` into two
   `bybit_extracted_events` rows (base + quote leg, `canonicalType="SWAP"`, `basisRelevant=true`,
   `walletRef` forced to `:UTA`, `tradeOrderId` captured). So **requirement #1 "add the
   EXECUTION stream" is already satisfied**; the remaining ingestion work is a *targeted
   re-backfill* + attribution, not a new stream.

2. **NEW-12 is a fabrication defect, not (currently) a conservation-abort defect.** The
   *current* production code (`BybitCanonicalFlowCounterpartySupport.reclassifyBotTransfer`)
   sets `correlationId=null`, `continuityCandidate=false`, and re-types bot legs to
   `EXTERNAL_TRANSFER_IN/OUT`. Those legs therefore **never enter a guarded continuity queue**
   (`corr-family:` / `bridge*:` / `bybit-earn-carry:`) and never trip
   `CorridorBasisConservationGuard`. What they *do* is leave multi-asset crypto returns at
   `PENDING_PRICE` → the generic pricing pipeline stamps **FMV**, fabricating ~$53 ETH + ~$127
   BTC ≈ the $185 consideration. **Phase 1 broke this** by re-typing the to-bot USDT to a
   continuity `INTERNAL_TRANSFER` (guarded carry) while leaving the crypto return
   `EVIDENCE_MISSING` → 28 orphaned `CARRY_OUT` ≈ $280 → `CorridorBasisConservationException`
   → whole replay aborted.

   **Design consequence:** Phase 2 keeps bot legs on the **non-guarded standalone path**
   (exactly like today's single-asset `BOT_LEDGER` path), so conservation is satisfied *by
   construction* — we never mint a bot continuity carry. We fix only the *basis value* of the
   returns, deterministically, from ingested executions. The sanctioned
   `isOutOfScopeCarry` / `isCrossAssetCorridorSwap` extension points are therefore **not
   required** for the happy path; they remain the escape hatch only if a future variant routes
   bot legs through a guarded queue (Section E documents when/how).

3. **The Bybit `/v5/execution/list` data is genuinely incomplete for member `516601508`.**
   Ingested spot executions (Nov 3 – Dec 1) bought **0.01811 ETH** and **0.000362 BTC** for
   **91.79 USDT total**, but the bot **returned 0.01374624 ETH and 0.001286712 BTC** and the
   member sent **−185 USDT**. Executions over-cover ETH quantity, under-cover BTC quantity, and
   account for only ~half the USDT. So a *pure execution-consideration* model (basis = Σ
   execValue) **cannot** reproduce the owner's stated expectation ("ETH+BTC together consume the
   $185 consideration"). The design below therefore uses executions for the **deterministic
   per-asset split (unit prices)** and uses the **net stablecoin actually consumed** as the
   authoritative **total** basis. This is deterministic, conserves real consideration, and
   generalises the existing single-asset `BOT_LEDGER` rule exactly.

---

## A. Decisions & assumptions (rationale anchored in correctness + cost)

| # | Decision | Rationale |
|---|----------|-----------|
| A1 | **Model the bot as a per-session `:BOT` compartment, resolved in the normalization stage** (extend `BybitBotTransferCostBasisService`), not in replay. | Basis authority must be decided *before* replay so we never double-count (execution SWAP *and* transfer both booking the same crypto). Replay changes are the highest-risk surface; a normalization-stage resolver is testable in isolation and idempotent. |
| A2 | **Bot transfer legs stay on the non-guarded standalone path** (`EXTERNAL_TRANSFER_IN/OUT`, `correlationId=null`, `continuityCandidate=false`). | Guarantees `CorridorBasisConservationGuard` cannot fire on bot legs — this is the anti-Phase-1 invariant. Same mechanics as today's working single-asset DOGE path (no regression). |
| A3 | **Total basis of a bot session = net stablecoin consumed** (`Σ to-bot stable − Σ from-bot stable dust`). | This is the exact USD value that physically left the user's spendable stable inventory. Using it as the total conserves real consideration and matches the owner expectation ("ETH+BTC together = $185"). No FMV, no fabrication. |
| A4 | **Per-asset split of that total = ingested `EXECUTION_SPOT` unit prices** (`avgExecPrice(asset)` = Σ execValue / Σ execQty over BUY fills of that asset in the session window). | Deterministic, execId-keyed, sourced from the exchange's own fills. No amount-only heuristic. Uses executions for *relative* pricing only, so quantity gaps in the fills don't corrupt the total. |
| A5 | **Single-crypto sessions with no usable executions keep the existing `BOT_LEDGER` net/qty rule unchanged.** | Preserves member `421325298` (DOGE) exactly — the split rule in A4 collapses to `net/qty` when there is one asset, so single-asset is a special case of the general rule; where even a unit price is unavailable, the legacy path is retained verbatim. |
| A6 | **A returned crypto with no execution unit price in a *multi-crypto* session is flagged bounded `EVIDENCE_MISSING`** (ADR-031 undefined AVCO), never FMV. | Honest treatment of genuine data gaps; bounded and non-fabricating. This is the one place we still surface a residual (Section E, and the open decision O1). |
| A7 | **`:BOT` accountRef routing is observability-only and collapses to the `BYBIT:<uid>` umbrella position** (via `CexUmbrellaSupport`), like `:FUND`/`:UTA`/`:EARN`. | Gives a clean audit compartment in the ledger/frontend without introducing a *new* replay position key (and thus no new conservation surface). Deterministic window rule attributes executions to `:BOT` only inside a detected bot session. |
| A8 | **Bot execution attribution is by deterministic session window** (uid + `[firstToBot, lastFromBot]` from `bybitType=Bot` FUNDING_HISTORY), not by amount. | Members whose only spot activity is the bot (`516601508`, `421325298`) → all fills are bot fills. Mixed accounts (`33625378`) → only in-window fills route to `:BOT`; the rest stay `:UTA` (unchanged). |

**Assumptions requiring no owner sign-off:** stablecoin set reuses the existing
`STABLECOIN_SYMBOLS`; session gap stays 14 days (existing `SESSION_GAP`); `execPrice` is quoted
in the stable quote asset (true for all spot pairs `splitSymbol` accepts).

---

## B. ASCII data-flow

```
 Bybit v5 REST (already-configured keys)
 ┌──────────────────────────────────────────────────────────────────────────┐
 │  /v5/account/transaction-log   (FUNDING_HISTORY, incl. bybitType="Bot")    │
 │  /v5/execution/list?category=spot  (EXECUTION_SPOT: execId,execPrice,...)  │  ← already wired
 └───────────────┬──────────────────────────────────────────┬───────────────┘
                 │ BybitBackfillSegmentExecutor (7-day windows, cursor)       │
                 ▼                                            ▼
        integration_raw_events  (idempotent by providerEventKey / execId)
                 │ BybitExtractionService.extract(...)                        │
                 ▼                                            ▼
   bybit_extracted_events                          bybit_extracted_events
   (FUNDING_HISTORY, bybitType=Bot,                (EXECUTION_SPOT base+quote,
    canonicalType=INTERNAL_TRANSFER)                canonicalType=SWAP, execPrice)
                 │                                            │
                 │  BybitCanonicalTransactionBuilder + FlowCounterpartySupport │
                 ▼                                            ▼
        normalized_transactions                     normalized_transactions
        (bot transfers: EXTERNAL_TRANSFER_IN/OUT,   (SWAP; accountRef → :BOT
         BOT_TRANSFER marker, accountRef → :BOT)     when inside a bot window)
                 │                                            │
                 └──────────────┬─────────────────────────────┘
                                ▼
          ★ BybitBotCompartmentCostBasisService  (Phase 2 — extends the
             existing BybitBotTransferCostBasisService, normalization stage)
             per session (uid, 14-day gap):
               net = Σ to-bot stable − Σ from-bot stable dust        (A3 total)
               avgExecPrice(asset) from in-window EXECUTION_SPOT BUY (A4 split)
               basis(asset) = returnedQty·avgExecPrice, scaled so Σ = net
               → CONFIRMED, PriceSource=BOT_LEDGER (dust → STABLECOIN peg)
               → uncovered multi-crypto asset → EVIDENCE_MISSING (A6)
                                │
                                ▼
                        Replay (unchanged engine)
             bot legs are standalone (no correlationId, no continuityCandidate)
             → NO guarded corr-family/bybit-earn-carry carry is ever created
             → CorridorBasisConservationGuard cannot fire on bot legs  ✔
                                │
                                ▼
                   asset_ledger_points  →  portfolio snapshot
```

---

## C. Concrete files, methods, collections, config

### C.1 Ingestion (confirm + targeted re-backfill; mostly already present)

| Component | File | Change |
|---|---|---|
| Stream enum | `backend/core/.../cex/acquisition/venue/bybit/BybitIntegrationStream.java` | **No change.** `EXECUTION_SPOT` (`category="spot"`, path `/v5/execution/list`) already present. |
| API client | `.../bybit/BybitApiClient.java` | **No change.** `fetchStream` + `pathFor` already map `EXECUTION_SPOT → /v5/execution/list`; cursor pagination handled. Confirm `execId` is persisted on `providerEventKey` for idempotent dedup (add if the current key is coarser than `execId`). |
| Backfill planner | `.../bybit/BybitBackfillSegmentPlanner.java` | **No change** for correctness. `EXECUTION_SPOT` planned initial + incremental at 7-day windows. |
| Backfill executor | `.../bybit/BybitBackfillSegmentExecutor.java` | **No change.** Executes `EXECUTION_SPOT` segments → `bybitExtractionService.extract`. |
| Raw dedup | `integration_raw_events` collection | Verify unique index covers `(integrationId, stream, providerEventKey)` where `providerEventKey` embeds `execId` for spot fills. Add compound index if missing (Section H). |

> **Why "targeted re-backfill" and not `--skip-frontend`:** `reset-derived.sh` (invoked by
> `--skip-frontend`) deletes normalized + derived collections and re-runs
> normalization/linking/pricing/replay, but it **does NOT re-fetch from the Bybit API and does
> NOT re-run extraction** into `bybit_extracted_events`. The Nov executions are already present,
> but to maximise coverage of the bot window (and to make the flow reproducible on a fresh DB)
> we must re-plan/re-run the `EXECUTION_SPOT` backfill segments. See Section F.

### C.2 Extraction / `:BOT` attribution

| Component | File | Change |
|---|---|---|
| Execution extraction | `.../bybit/BybitExtractionService.java` `extractExecution(...)` | Keep base+quote SWAP extraction. **Add** deterministic `:BOT` tagging: when the fill's `timeUtc` falls inside a detected bot-session window for the uid, set `walletRef` suffix to `:BOT` instead of `:UTA` (helper `applyBybitSubAccountWalletRef(event, BOT)` — add `BOT` to the `BybitSubAccount` enum). Window set is derived from `bybit_extracted_events` FUNDING_HISTORY rows with `bybitType=Bot` for the same `(integrationId, uid)`. Attribution is observability-only (A7). |
| Extracted model | `backend/domain/.../transaction/bybit/BybitExtractedEvent.java` | **No new field required.** `sourceStream`, `tradeOrderId`, `walletRef`, `timeUtc`, `uid`, `basisRelevant` suffice. (Optional: `botSessionId` for audit — see O2.) |
| Sub-account enum | `.../bybit/BybitExtractionService.BybitSubAccount` | Add `BOT` value. |
| WalletRef | `backend/domain/.../wallet/WalletRef.java` | Confirm `:BOT` suffix parses and `umbrellaKey()` strips it to `BYBIT:<uid>` (same treatment as `:FUND/:UTA/:EARN`). Add `:BOT` to any suffix allow-list if one exists. |

### C.3 Normalization — canonical builder & counterparty

| Component | File | Change |
|---|---|---|
| Bot transfer detect | `.../bybit/BybitCanonicalFlowCounterpartySupport.java` `isBotTransfer` / `reclassifyBotTransfer` | **Keep** `correlationId=null`, `continuityCandidate=false` (A2). **Change** `accountRef` to `BYBIT:<uid>:BOT` on the transfer flow (was `:FUND`) so the ledger shows the compartment. Keep `BOT_TRANSFER` marker; keep `BOT_TRANSFER_PENDING_COST` on non-stable returns (the resolver clears it). |
| Descriptor suffixes | `.../bybit/BybitVenueDescriptor.java` `accountKindSuffixes()` | Add `:BOT` so umbrella expansion / `ledgerMatchesUmbrella` treat it like other Bybit sub-accounts. |

### C.4 Cost-basis resolver (the core Phase 2 unit)

| Component | File | Change |
|---|---|---|
| **New/renamed service** | `.../cex/normalization/venue/bybit/BybitBotTransferCostBasisService.java` → generalise into **`BybitBotCompartmentCostBasisService`** (keep the old class name as a thin delegate to avoid touching wiring, or rename + update the caller in `BybitNormalizationService.processNextBatch`). | Implements the Section D algorithm. Reads bot docs by `missingDataReasons=BOT_TRANSFER`; joins in-window `EXECUTION_SPOT` rows from `bybit_extracted_events` by `(integrationId, uid, timeUtc ∈ session, sourceStream=EXECUTION_SPOT)`. |
| Price source | `backend/domain/.../common/PriceSource.java` | Reuse `BOT_LEDGER`. (Optional new `BOT_EXECUTION` value only if the owner wants to distinguish execution-split from net/qty in the UI — see O3. Default: reuse `BOT_LEDGER`.) |
| Caller | `.../cex/normalization/venue/bybit/BybitNormalizationService.java` | Ensure the resolver runs **after** all bot FUNDING_HISTORY + EXECUTION_SPOT rows are normalized/extracted for the batch (it already runs `computeBotCostBasis()` late; keep ordering). |

### C.5 Config (`BybitIntegrationProperties` / `application.yml`)

No new required config. `executionWindowDays` already gates the 7-day spot window. Optional
knobs (default off): `bybit.bot.evidence-missing-on-unpriced-return` (bool, A6 behaviour),
`bybit.bot.session-gap-days` (default 14, matches `SESSION_GAP`).

---

## D. Accounting model & algorithm (pseudocode)

### D.1 Session assembly (unchanged grouping)

```
loadBotDocs()  := normalized_transactions where source=BYBIT and missingDataReasons contains "BOT_TRANSFER"
sort by blockTimestamp
sessions := splitIntoSessions(gap = 14 days)          // existing logic
```

### D.2 Per-session resolution (the new deterministic core)

```
resolveSession(session):
    # ---- 1. Totals from the transfer legs (authoritative consideration) ----
    stableOut  := Σ |qty| for to-bot   stablecoin legs (qty < 0)      # e.g. 185 USDT
    stableIn   := Σ  qty  for from-bot stablecoin dust legs (qty > 0) # e.g. 1.2649693 USDT
    netConsumed := stableOut − stableIn                               # A3 total basis pool
    if netConsumed <= 0: return 0                                     # nothing to allocate

    returns := { asset -> Σ returnedQty } for from-bot NON-stable legs (qty > 0)
    if returns is empty: return 0

    # ---- 2. Per-asset unit price from ingested executions (deterministic split) ----
    window := [session.firstTs, session.lastTs]
    fills  := bybit_extracted_events where sourceStream=EXECUTION_SPOT
                and integrationId,uid match and timeUtc ∈ window and side=BUY
    avgExecPrice(asset) := Σ execValue(asset) / Σ execQty(asset)      # A4 (quote=stable)

    priced   := { asset ∈ returns : avgExecPrice(asset) is defined and > 0 }
    unpriced := returns.keys − priced

    # ---- 3. Allocation ----
    if unpriced is non-empty AND |returns| >= 2:
        # A6: cannot deterministically split; flag unpriced asset(s) EVIDENCE_MISSING,
        #     allocate netConsumed across the PRICED assets only (bounded residual).
        markEvidenceMissing(unpriced)                # ADR-031 undefined AVCO, no FMV
    if priced is empty:
        # single-crypto legacy fallback (A5): exact existing BOT_LEDGER behaviour
        if |returns| == 1:
            asset := the single return
            unitPrice := netConsumed / returns[asset]
            applyBasis(asset, unitPrice, PriceSource.BOT_LEDGER)
            return
        else:
            markEvidenceMissing(returns.keys); return  # no unit price anywhere, multi-asset

    rawBasis(asset) := returns[asset] * avgExecPrice(asset)  for asset ∈ priced
    scale := netConsumed / Σ rawBasis(asset)                 # conserve real consideration
    for asset ∈ priced:
        basis(asset)     := rawBasis(asset) * scale
        unitPrice(asset) := basis(asset) / returns[asset]
        applyBasis(asset, unitPrice, PriceSource.BOT_LEDGER)

applyBasis(asset, unitPrice, source):
    for each from-bot return leg of `asset` with BOT_TRANSFER_PENDING_COST:
        flow.unitPriceUsd := unitPrice
        flow.valueUsd     := |qty| * unitPrice
        flow.priceSource  := source
        tx.status         := CONFIRMED; remove BOT_TRANSFER_PENDING_COST
    # to-bot stable legs stay STABLECOIN $1 peg (already applied); PnL on them ≈ 0
```

### D.3 Worked example — member `516601508` (ETH+BTC, the NEW-12 case)

```
stableOut  = 185 USDT ; stableIn = 1.2649693 USDT ; netConsumed = 183.735 USDT
returns    = { ETH: 0.01374624, BTC: 0.001286712 }
avgExecPrice(ETH) = Σ execValue_ETH / Σ execQty_ETH   (from Nov 3–Dec 1 BUY fills)
avgExecPrice(BTC) = Σ execValue_BTC / Σ execQty_BTC
rawBasis(ETH) = 0.01374624 · avgExecPrice(ETH)
rawBasis(BTC) = 0.001286712 · avgExecPrice(BTC)
scale = 183.735 / (rawBasis(ETH) + rawBasis(BTC))
basis(ETH) + basis(BTC) = 183.735  USDT   ← ETH+BTC together consume the consideration ✔
per-asset split follows the exchange's own fill prices (deterministic, no FMV)
```

> If the owner insists the total must be **185.00** (gross, ignoring the 1.26 dust return), use
> `stableOut` instead of `netConsumed` — this is open decision **O1**. Default = `netConsumed`
> (economically correct: dust came back at $1).

### D.4 Worked example — member `421325298` (DOGE, single-asset regression guard)

```
returns = { DOGE: 150.591 } ; netConsumed = 125 − 65.85 = 59.15 USDT
priced  = {} (only MNT fill exists; no DOGE execPrice) → priced empty, |returns| == 1
→ legacy A5 fallback: unitPrice = 59.15 / 150.591, PriceSource=BOT_LEDGER  (UNCHANGED)
```

Result is **bit-identical** to today's `BybitBotTransferCostBasisService` output → no
regression. (The refactor must keep this exact arithmetic path.)

### D.5 Fees, dust, partial/open sessions

- **Fees:** spot `execFee` is already folded into `execQty/execValue` by `extractExecution`
  (`change = qty + feePaid`). Because we price returns from `avgExecPrice` scaled to
  `netConsumed`, fees are captured implicitly in the consideration; no separate fee leg is
  synthesised. Bot transfer legs carry no fee.
- **Dust USDT returns:** each from-bot stable leg is priced at the $1 peg (`STABLECOIN`) and
  reduces `netConsumed`. They never create carries.
- **Partial / still-open bot at end of data:** if `Σ from-bot returns` < `Σ to-bot` (bot still
  holds inventory), `netConsumed` simply reflects only what was actually consumed *and returned*;
  the unreturned USDT remains in the `:BOT` compartment as a normal positive USDT inventory on
  the umbrella (it never left as a disposal). No carry, no abort. When the bot later returns it
  (next incremental sync), the next session resolves it. **Never aborts replay.**

---

## E. Conservation edge cases (how the guard stays green)

| Scenario | Handling | Why the guard cannot fire |
|---|---|---|
| Multi-asset bot return (516601508) | Basis resolved in normalization (D.2); legs stay `EXTERNAL_TRANSFER_IN/OUT` standalone. | No `correlationId`/`continuityCandidate` → `ReplayPendingTransferKeyFactory.transferKey` never enqueues a `CARRY_OUT` on `corr-family:`/`bybit-earn-carry:`. Guard sweeps only guarded queues. |
| Uncovered crypto (no execPrice, multi-asset) | Asset flagged `EVIDENCE_MISSING` (ADR-031 undefined AVCO); acquires undefined/0 basis, no fabricated FMV. | An undefined-basis standalone acquire creates no carry-out; nothing to orphan. |
| Bot still open at cutoff | Unreturned USDT stays as umbrella inventory (never disposed). | No transfer carry minted; conservation trivially holds. |
| Genuine external Bybit deposit/withdraw (non-bot) | Untouched — `isBotTransfer` is false; FA-001 corridor path (ADR-013/ADR-049) unchanged. | Not a bot leg. |
| **Future variant** that *does* route bot legs through a guarded queue | Only then: add a `bybit-bot-carry:<uid>:<asset>` prefix to `GUARDED_QUEUE_PREFIXES` **and** carve open-session residuals via `CorridorBasisConservationGuard.isOutOfScopeCarry` (or `isCrossAssetCorridorSwap` for the USDT→ETH/BTC leg). | **Not recommended for Phase 2.** Documented so the sanctioned extension points are used correctly if ever needed. |

**Anti-Phase-1 invariant (must hold in code review):** *No Phase 2 change may set
`continuityCandidate=true` or a non-null `correlationId` on a `BOT_TRANSFER` row.* An arch test
enforces this (Section G).

---

## F. Rebuild & API re-backfill procedure (exact)

Bot basis logic lives in normalization, and the executions must be present in
`bybit_extracted_events`. Two cases:

### F.1 Executions already ingested (current prod) — logic-only change

```bash
# 1. Deploy the code (resolver + attribution). Then renormalize + replay:
./scripts/prod-reset-rebuild-backend.sh --skip-frontend
```
`--skip-frontend` runs `reset-derived.sh` (wipes normalized/ledger/snapshots, **keeps**
`integration_raw_events` and `bybit_extracted_events`) and re-runs normalization → the new
resolver runs against the already-ingested Nov executions.

### F.2 Fresh DB, or to maximise bot-window coverage — full API re-backfill

`--skip-frontend` does **NOT** re-fetch from Bybit or re-extract. To pull execution history:

```bash
# 1. (fresh integration) initial backfill re-plans EXECUTION_SPOT 7-day segments over
#    the full history window and re-extracts into bybit_extracted_events automatically.
#    For an existing integration, trigger an incremental backfill covering the bot window:
#      re-plan segments via the backfill admin path (BybitBackfillSegmentPlanner
#      .planIncrementalBackfill from = firstToBot, to = lastFromBot) OR reset the
#      integration's backfill cursor so EXECUTION_SPOT segments re-run.
# 2. Wait for EXECUTION_SPOT segments to reach COMPLETE (extraction populates
#    bybit_extracted_events with execId-keyed rows; dedup is idempotent).
# 3. Then renormalize + replay:
./scripts/prod-reset-rebuild-backend.sh --skip-frontend
```

> **Retention caveat (must tell the owner):** `/v5/execution/list` retains ~2 years but the
> earliest bot fills for `516601508` may already be gone; the ingested Nov fills give unit
> prices for **both** ETH and BTC, which is all D.2 needs (the *total* comes from the transfer
> legs, which are fully retained in FUNDING_HISTORY). So a re-backfill improves audit coverage
> but is **not** required to resolve NEW-12 for this member.

**Mode selection recap (per `prod-rebuild-workflow` rule):** this is a
normalization/accounting change → `--skip-frontend` (full derived reset), optionally preceded
by an API re-backfill (F.2). Do **not** use `--backend-only` (it skips renormalization).

---

## G. Test plan (keyed to members `516601508` and `421325298`)

### G.1 Unit — `BybitBotCompartmentCostBasisService`

| Test | Setup | Expected |
|---|---|---|
| `multiAsset_ethBtc_splitFromExecutions_totalsNetConsumed` | 516601508 session: −185 USDT (26), +1.2649693 USDT dust (26), +0.01374624 ETH, +0.001286712 BTC; in-window EXECUTION_SPOT BUY fills for ETH & BTC. | `basis(ETH)+basis(BTC) == 183.735 USDT` (±1e-6); per-asset unit price ∝ `avgExecPrice`; both txs `CONFIRMED`, `PriceSource=BOT_LEDGER`, `BOT_TRANSFER_PENDING_COST` cleared. |
| `multiAsset_totalUsesNetNotGross` | as above | total == `stableOut − stableIn`, not `stableOut` (guards O1 default). |
| `singleAsset_doge_legacyPathUnchanged` | 421325298 session: −125 USDT, +65.85 USDT, +150.591 DOGE, no DOGE execution. | `unitPrice(DOGE) == 59.15/150.591`, `PriceSource=BOT_LEDGER` — identical to pre-Phase-2 golden. |
| `multiAsset_oneUnpriced_flagsEvidenceMissing` | ETH has execPrice, a second crypto has none. | unpriced asset → `EVIDENCE_MISSING`; ETH basis = full `netConsumed` scaled among priced; no FMV. |
| `openSession_unreturnedUsdt_noError` | to-bot 185, returns only 100 crypto-equivalent. | resolves priced returns; no exception; unreturned USDT untouched. |
| `dust_pricedAtPeg` | dust USDT returns. | each dust leg `STABLECOIN` $1; excluded from crypto allocation. |
| `idempotent_rerun` | run resolver twice. | second run resolves 0 (all `CONFIRMED`); no double write. |
| `nonBot_sessionsIgnored` | non-bot Bybit rows present. | untouched. |

### G.2 Unit — attribution / extraction

| Test | Expected |
|---|---|
| `execution_inBotWindow_taggedBot` | 516601508 Nov fill → `walletRef` ends `:BOT`. |
| `execution_outsideWindow_staysUta` | mixed account fill outside window → `:UTA` (33625378 manual trades unaffected). |
| `walletRef_bot_collapsesToUmbrella` | `WalletRef.parse("BYBIT:516601508:BOT").umbrellaKey() == "BYBIT:516601508"`. |
| `execId_dedup_idempotent` | re-extract same raw fill → single `bybit_extracted_events` row. |

### G.3 Architecture tests (invariants)

| Test | Assertion |
|---|---|
| `botTransfers_neverContinuityCandidate` | No `BOT_TRANSFER` normalized row has `continuityCandidate==true` or non-null `correlationId`. **(anti-Phase-1)** |
| `botLegs_neverOnGuardedQueue` | `ReplayPendingTransferKeyFactory.transferKey` returns null (or non-guarded) for any `BOT_TRANSFER` flow. |
| `guard_prefixes_unchanged` | `GUARDED_QUEUE_PREFIXES` not modified by Phase 2 (unless O-future variant chosen). |
| `priceSource_botReturns_isBotLedger` | resolved bot crypto returns use `BOT_LEDGER` (or `BOT_EXECUTION` if O3). |

### G.4 Integration (end-to-end replay)

| Test | Expected |
|---|---|
| `replay_516601508_conserved_and_noFmv` | full pipeline on the 516601508 fixture → replay completes (no `CorridorBasisConservationException`); ETH+BTC basis == 183.735 total; **no** `FMV`/`MARKET` price source on bot returns. |
| `replay_421325298_doge_unchanged` | DOGE basis + ledger points bit-identical to pre-Phase-2 snapshot. |
| `replay_wholeUniverse_conservationGreen` | full prod-like replay → `CorridorBasisConservationResult.empty()` (no orphaned carries introduced). |
| `replay_genuineExternalBybit_unchanged` | a real non-bot Bybit deposit/withdraw keeps FA-001 corridor behaviour (ADR-013/049). |

Golden fixtures: derive from Mongo `bybit_extracted_events` for the two uids (no real tx
hashes in assertions — key on uid + asset + qty + expected basis, per repo rules).

---

## H. Mongo collections & indexes

| Collection | Query added by Phase 2 | Index |
|---|---|---|
| `bybit_extracted_events` | in-window fills: `(integrationId, uid, sourceStream=EXECUTION_SPOT, timeUtc)` | Compound `{integrationId:1, uid:1, sourceStream:1, timeUtc:1}` (extend existing if present). |
| `bybit_extracted_events` | bot windows: `(integrationId, uid, bybitType=Bot)` on FUNDING_HISTORY | Covered by the same/compound `{integrationId:1, uid:1, sourceStream:1, timeUtc:1}` + existing `bybitType`. |
| `integration_raw_events` | idempotent execId dedup | Unique `{integrationId:1, stream:1, providerEventKey:1}` (verify `providerEventKey` embeds `execId`). |
| `normalized_transactions` | bot doc load | Existing `{source:1, missingDataReasons:1}` (used by current service). |

All reads are background/normalization-stage; GET/snapshot read path is untouched (zero RPC).

---

## I. Regression risks & mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| **Reintroducing the Phase-1 abort** (someone re-adds continuity to bot legs). | Med | Arch test `botTransfers_neverContinuityCandidate` (G.3) + explicit A2/E invariant in ADR-058. |
| **Single-asset DOGE regression** during refactor of `BybitBotTransferCostBasisService`. | Med | Keep the exact `net/qty` arithmetic path (A5/D.4); golden test `singleAsset_doge_legacyPathUnchanged`. |
| **`:BOT` attribution mis-tags a mixed account's manual trades** (33625378), changing its AVCO. | Med | Window rule is uid-scoped and observability-only (A7/A8); `execution_outsideWindow_staysUta` test; `:BOT` collapses to umbrella so even a mis-tag doesn't move the position key. |
| **Double-count** (execution SWAP *and* transfer both book the crypto). | High if naive | Authority resolved in normalization *before* replay (A1); returns priced from transfers, executions used only for the split unit price — the SWAP legs already dispose USDT/acquire crypto on the umbrella; the transfer legs are basis-neutral moves of the *same* umbrella inventory. Integration test asserts umbrella crypto qty is not inflated. |
| **`execValue` currency ≠ USD** for an exotic quote. | Low | `splitSymbol` only accepts stable quotes (`STABLE_QUOTES`); non-stable-quote fills are ignored for pricing → asset falls to A6/A5. |
| **Owner expects gross $185, not net** | Low | O1 flagged; single-line switch (`stableOut` vs `netConsumed`). |

---

## J. Ordered task list (a backend engineer can execute top-to-bottom)

1. **Confirm ingestion** — verify `EXECUTION_SPOT` raw dedup embeds `execId`; add/adjust the
   `integration_raw_events` unique index if not (Section H, C.1).
2. **WalletRef `:BOT`** — add `:BOT` handling to `WalletRef` (parse + `umbrellaKey()` strip) and
   to `BybitVenueDescriptor.accountKindSuffixes()`. Unit test `walletRef_bot_collapsesToUmbrella`.
3. **Bot window helper** — add a deterministic bot-session-window resolver (uid → list of
   `[firstToBot, lastFromBot]`) reading FUNDING_HISTORY `bybitType=Bot` rows.
4. **Extraction attribution** — add `BOT` to `BybitSubAccount`; in `extractExecution`, tag
   in-window spot fills `:BOT` (else `:UTA`). Tests G.2.
5. **Counterparty accountRef** — in `BybitCanonicalFlowCounterpartySupport.reclassifyBotTransfer`,
   set flow `accountRef = BYBIT:<uid>:BOT` (keep `correlationId=null`, `continuityCandidate=false`).
6. **Resolver** — generalise `BybitBotTransferCostBasisService` into
   `BybitBotCompartmentCostBasisService` implementing D.2 (net-consumed total + execution-split);
   keep the single-asset path bit-identical. Wire into `BybitNormalizationService` (same call
   site). Unit tests G.1.
7. **Arch tests** — add G.3 invariants (anti-Phase-1, no guarded queue, guard prefixes intact).
8. **Integration tests** — add G.4 fixtures for `516601508` and `421325298`; assert
   conservation-green + no FMV on bot returns + DOGE unchanged.
9. **Index** — add the compound `bybit_extracted_events` index (Section H) via the standard
   index-init path.
10. **Rebuild** — run `./scripts/prod-reset-rebuild-backend.sh --skip-frontend` (F.1); if a fresh
    DB / coverage improvement is wanted, run the F.2 re-backfill first.
11. **Verify** — check `results/reconciliation.md` + blockers: NEW-12 resolved, ETH+BTC basis
    ≈ $184 total, DOGE unchanged, `CorridorBasisConservationResult.empty()`.
12. **Docs** — flip ADR-058 status to Accepted once the owner signs off on O1–O3; update
    `results/blockers.md` (NEW-12 → resolved) and `docs/reference/ledger-points-and-basis-effects.md`.

---

## K. Open product / accounting decisions (need owner)

- **O1 — Total basis basis: net vs gross.** Default = **net stablecoin consumed** (`stableOut −
  stableIn`, economically correct; 516601508 → 183.735). Owner may prefer **gross** (`stableOut`
  = 185.00) if dust returns should not reduce cost. One-line switch.
- **O2 — Persist `botSessionId`** on `bybit_extracted_events` / normalized rows for auditability?
  Not required for correctness; nice for the frontend compartment view. Default = no.
- **O3 — New `PriceSource.BOT_EXECUTION`** vs reuse `BOT_LEDGER`. Reuse keeps the UI/enum stable;
  a new value distinguishes "execution-split" from "net/qty" in reporting. Default = reuse
  `BOT_LEDGER`.
- **O4 — Uncovered multi-crypto remainder policy (A6).** Default = flag the *unpriced* asset
  `EVIDENCE_MISSING` and allocate `netConsumed` across the priced assets. Alternative: reserve a
  pro-rata slice for the unpriced asset (still no FMV, but a chosen split). Only matters if a
  future bot session returns an asset with zero ingested fills.
- **O5 — ADR number.** Confirm **ADR-058** (055–057 already taken) or specify an alternative.
