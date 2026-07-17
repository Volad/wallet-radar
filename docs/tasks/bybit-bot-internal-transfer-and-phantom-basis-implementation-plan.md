# Implementation Plan — Bybit Trading-Bot Internal Transfers + Phantom-Basis Refund/Top-up (NEW-12 / NEW-13 / NEW-14)

> **Type:** Solution/System-Architecture plan (design only — NO application code in this doc).
> **Source of truth:** `results/flagged-tx-audit-3.md` + `results/blockers.md` (NEW-12/13/14).
> **Universe:** `df5e69cc-a0c0-4910-8b7d-74488fa266e2` — normalized=8065, ledgerPoints=11283.
> **Live-Mongo verified:** all shapes below re-read from `walletradar` prod (MCP `user-mongodb-walletradar`) on Jul 15, 2026.
> **Stack constraint:** Java 21 / Spring Boot / **Gradle** (never Maven). Deterministic, long-term solutions only — no dataset-specific hashes, no amount-only heuristics as the terminal rule.

---

## Section A — Context, Decisions & Assumptions

### A.1 The three confirmed defects (all cluster at earliest stage = classification)

| ID | Severity | Earliest failed stage | Symptom (verified in prod) | Fabricated / leaked |
|---|---|---|---|---|
| **NEW-12** | **HIGH / material** | classification (Bybit normalization) + ingestion (cross-asset bot cost) | Own-account "Trading Bot" FUNDING_HISTORY rows overridden `INTERNAL_TRANSFER → EXTERNAL_TRANSFER_IN/OUT` (ACQUIRE/DISPOSE) | ETH **+$53.48**, BTC **+$126.95** spot ACQUIRE; **−185 USDT** phantom external DISPOSE (member 516601508); plus member 421325298 churn |
| **NEW-13** | LOW | classification (type-model gap) | GMX execution-fee refund stays `EXTERNAL_TRANSFER_IN`/ACQUIRE after fee-refund stamp | ETH **+$0.037** (secondary `0xf03b…`) |
| **NEW-14** | LOW | classification / counterparty coverage | Rabby "Gas Fee Payer" top-up booked ACQUIRE (payer address unregistered) | ETH **+$0.070** (primary `0x1a87…`, BASE) |

### A.2 Anchors (re-verified live)

**NEW-12 (`normalized_transactions._id` = `BYBIT-516601508:FUNDING_HISTORY:f2403…da1e`)**
- `bybit_extracted_events`: `canonicalType="INTERNAL_TRANSFER"`, `bybitType="Bot"`, `bybitDescription="Transfer from Trading Bot"`, `uid=516601508`, `walletRef="BYBIT:516601508:FUND"`, `basisRelevant=true`, `assetSymbol=ETH`, `quantityRaw=0.01374624`.
- `normalized_transactions`: `type=EXTERNAL_TRANSFER_IN`, flow `role=BUY` ETH +0.01374624 @ **$3890.25** `priceSource=BYBIT`, `counterpartyAddress=BYBIT:516601508:UTA`, `counterpartyType=CEX`, `missingDataReasons=[BOT_TRANSFER, BOT_TRANSFER_PENDING_COST]`.
- `asset_ledger_points`: `walletAddress=BYBIT:516601508` (**member-level position — compartment suffix stripped**), `accountingFamilyIdentity=FAMILY:ETH`, `basisEffect=ACQUIRE`, `costBasisDeltaUsd=+53.47631016`, `quantityBefore 0 → 0.01374624`, `avcoAfter=$3890.25`.

**Systematic bot rows (live aggregate over `missingDataReasons:"BOT_TRANSFER"`):**

| Member | Leg | Type booked | Count | Qty | priceSource | Window |
|---|---|---|---|---|---|---|
| 516601508 | USDT → bot | `EXTERNAL_TRANSFER_OUT` (DISPOSE) | 26 | −185 USDT | STABLECOIN | Oct 10 – Nov 21 2025 |
| 516601508 | USDT ← bot | `EXTERNAL_TRANSFER_IN` (ACQUIRE) | 26 | +1.265 USDT | STABLECOIN | Oct 10 – Nov 21 2025 |
| 516601508 | BTC ← bot | `EXTERNAL_TRANSFER_IN` (ACQUIRE) | 2 | +0.001286712 BTC | **BYBIT (spot)** | Oct 21 & Dec 12 |
| 516601508 | ETH ← bot | `EXTERNAL_TRANSFER_IN` (ACQUIRE) | 1 | +0.01374624 ETH | **BYBIT (spot)** | Oct 21 (TX4) |
| 421325298 | USDT → bot | `EXTERNAL_TRANSFER_OUT` (DISPOSE) | 3 | −125 USDT | STABLECOIN | Jan 23 – Jan 30 |
| 421325298 | USDT ← bot | `EXTERNAL_TRANSFER_IN` (ACQUIRE) | 3 | +65.85 USDT | STABLECOIN | Jan 30 – Feb 3 |
| 421325298 | DOGE ← bot | `EXTERNAL_TRANSFER_IN` (ACQUIRE) | 1 | +150.591 DOGE | **BOT_LEDGER (resolved)** | Jan 31 |

> **Key finding:** the DOGE row (member 421325298, a *single-non-stable-asset session*) already got a `BOT_LEDGER` (net-stablecoin) basis — the existing `BybitBotTransferCostBasisService` heuristic fired. The **material ETH+BTC rows (member 516601508, Oct-21) share one 14-day session with TWO non-stable assets**, so the heuristic bails (`nonStableAssetsReturned.size() != 1`) → they fall through to **spot FMV** = the $53.48 + $126.95 fabrication. This is the exact failure mode.

**NEW-13 (`0x17273e5c…dae7:ARBITRUM:0xf03b…`)** — `type=EXTERNAL_TRANSFER_IN`, flow ETH +0.000019472334 @ $1907.80 `role=BUY`, `protocolName="GMX V2"`, `counterpartyType=PROTOCOL`, `counterpartyAddress=0xe68caaacdf…` (**confirmed present in `GmxV2HandlerRegistry` — OrderHandler legacy v2.0, line 27**), `missingDataReasons=[GMX_EXECUTION_FEE_REFUND]`. Already stamped, not promoted to settlement (no matching open `gmx-lp:*` request — NEW-09 guard working), but still ACQUIRE.

**NEW-14 (`0x39dca64e…f3d:BASE:0x1a87…`)** — `raw_transactions.rawData`: `from=0x76dd65529dc6c073c1e0af2a5ecc78434bdbf7d9`, `to=wallet`, `input="0x"`, `methodId="0x"`, `gas=21000`, `value=23691701676293` (0.000023691701676293 ETH), `nonce=65573`. `normalized`: `EXTERNAL_TRANSFER_IN`/ACQUIRE, counterparty `UNKNOWN_EOA`. Address appears **exactly once** in the whole dataset (verified via aggregate). Registry grep confirms `0x76dd65…` is **absent**.
  - **Provenance verified (web):** `0x76dd65529dc6c073c1e0af2a5ecc78434bdbf7d9` is labelled **"Rabby: Gas Fee Payer"** on OP Etherscan, BaseScan, BscScan, GnosisScan; Rabby covers Ethereum, Arbitrum, Avalanche, Base, BNB Chain, Fantom, Linea, Optimism, Polygon, ZetaChain. Safe to register as a `GAS_PAYER`.

### A.3 Decisions (with rationale)

1. **NEW-14 is a pure registry-coverage fix** (one JSON entry). The adequate type (`SPONSORED_GAS_IN`→`GAS_ONLY`) and the whole classification path (`SponsoredGasTopUpSupport.findVerifiedSender` → `HeuristicClassifier`) already exist and the tx already satisfies the shape gate + BASE envelope (0.0000237 < 0.005). Zero code change. Lowest risk, do first.

2. **NEW-13 is a small linking-stage reclassification.** The GMX keeper and the `GMX_EXECUTION_FEE_REFUND` stamp already exist; only a terminal *basis-neutral* type mapping is missing. Reuse the existing `SPONSORED_GAS_IN → GAS_ONLY` replay path (return-of-capital / gas refund), applied **after** `gmxWithdrawalSettlementLinkService` so the NEW-09 settlement-promotion guard runs first and only *residual* refund-only rows are demoted. No new `BasisEffect`, no ADR.

3. **NEW-12 is split into two phases (bot-trade ingestion is a new data source, too large for one cycle):**
   - **Phase 1 (this cycle) — kill fabrication, honor `canonicalType`, honest gap.** Stop the `EXTERNAL` override → keep `INTERNAL_TRANSFER` (basis-neutral member-internal carry); stop spot-FMV fabrication on unresolved cross-asset returns (→ bounded `EVIDENCE_MISSING`, never ACQUIRE). This removes **100% of the fabricated ETH/BTC basis and the phantom USDT external DISPOSE**.
   - **Phase 2 (next cycle) — bot-trade ingestion.** Ingest Bybit bot order / closed-PnL history, model the `BYBIT:<uid>:BOT` compartment as a real execution venue, and materialize the `USDT→crypto` conversions as swaps so returned crypto carries **exact consideration-given basis** and the interim shortfall clears.
   - **Rationale for the split & why literal "INTERNAL_TRANSFER + no DISPOSE + crypto-carries-basis + no-shortfall" cannot all hold in one cycle:** the ledger position is **member-level** (`BYBIT:516601508`, verified — FUND/UTA/BOT all roll up to it). The bot performs a *cross-asset conversion* (USDT in, ETH/BTC out) whose evidence (the trade) is **not currently ingested**. Without that evidence you must either (a) dispose the USDT as swap-consideration and acquire crypto at that consideration (violates "no DISPOSE"), or (b) carry both legs as pure internal transfers, in which case the returned crypto has no source lot and is `EVIDENCE_MISSING` (a shortfall). There is **no third deterministic option** that is not an amount-only heuristic. We therefore preserve the two non-negotiable invariants in Phase 1 — **(i) no fabrication, (ii) no phantom external in/out** — and accept a bounded, flagged `EVIDENCE_MISSING` on cross-asset returns until the trades are ingested in Phase 2. This is exactly the audit's stated position ("absent [bot trades], the returned crypto's basis is `EVIDENCE_MISSING`; spot is only a proxy and must not be booked as an external acquisition").

4. **Long-term target model = Option (i): dedicated `BYBIT:<uid>:BOT` compartment + ingest bot trade-history.** Justification vs the alternatives:
   - Option (ii) "fold bot into the same UTA ledger + rely on ingested trades" — functionally equivalent at the member-level position (compartment suffix is stripped in `asset_ledger_points`), but loses the explicit compartment audit trail and makes the FUND↔BOT transfers indistinguishable from FUND↔UTA. Rejected for observability; a first-class `:BOT` suffix costs almost nothing (mirrors existing `:UTA/:FUND/:EARN`).
   - Option (iii) "deterministic fallback (spot / net-stablecoin) when trades unavailable" — this is the current `BybitBotTransferCostBasisService` (net-stablecoin session). It is an **amount-only heuristic** and, per constraints, is **not acceptable as the terminal rule**. It is retained only as an explicitly-labelled *interim* fallback for single-non-stable-asset sessions and is **removed** once Phase 2 lands. It must **never** be spot-FMV ACQUIRE.
   Only Option (i) derives cross-asset basis from real evidence → satisfies "deterministic, long-term, no amount-only heuristic."

5. **New ADR required for NEW-12 (accounting-policy).** `docs/adr/ADR-0NN-cex-trading-bot-compartment-and-internal-conversion-basis.md`: declares (a) member sub-compartments (`:UTA/:FUND/:EARN/:BOT`) are one accounting entity; same-asset inter-compartment moves are basis-neutral `INTERNAL_TRANSFER`; (b) cross-asset bot conversions are member-internal swaps priced at consideration-given, sourced from ingested bot trades; (c) interim `EVIDENCE_MISSING` policy when trades are absent. Accounting-policy change ⇒ ADR is mandatory. NEW-13/14 need no ADR (reuse existing basis-neutral policy).

### A.4 Assumptions
- The active accounting universe already processes members 516601508 / 409666492 / 421325298 (ledgerPoints=11283; NEW-10 superseded — verified: bot ETH point exists at member key). No universe-membership change needed for Phase 1.
- Genuine external Bybit deposits (`showBusiTypeEn=Deposit` → `EXTERNAL_INBOUND`) and withdrawals (`Withdraw` → `EXTERNAL_TRANSFER_OUT`) are produced by a **different** extractor branch (`mapFundingHistoryCanonicalType`) and are out of scope — the fix keys strictly on `bybitType="Bot"` + same-member counterparty + extractor `canonicalType=INTERNAL_TRANSFER`.
- No pricing-cache invalidation needed (we are *removing* spot pricing, not changing historical prices).

---

## Section B — ASCII Architecture / Data-Flow Diagram

```
                         ┌───────────────────────── NEW-12 (Bybit) ─────────────────────────┐
 integration_raw_events  │  BybitExtractionService.extractFundingHistory                     │
   (FUNDING_HISTORY,      │    normalizeFundingHistoryType("...trading bot") -> "Bot"         │
    showBusiTypeEn="Bot") │    mapFundingHistoryCanonicalType -> canonicalType=INTERNAL_TRANSFER│
            │             └───────────────────────────────┬──────────────────────────────────┘
            ▼                                              ▼
     bybit_extracted_events  ───►  BybitNormalizationService.normalize
     canonicalType=INTERNAL         │  BybitExtractedEventMapper.toLegacyRaw
     bybitType=Bot                  ▼
                              BybitCanonicalTransactionBuilder.buildMappedRow
                                 type = INTERNAL_TRANSFER (mapCanonicalType)  ← honored
                                 [INTERNAL block: correlationId + :BOT counterparty]
                                 finalizeBybitFlows(...)
                          ┌───────────────────────────────────────────────────────┐
              PHASE 1 FIX │  isBotTransfer(row)                                     │
                          │    OLD: reclassifyBotTransfer -> EXTERNAL_IN/OUT+ACQUIRE│  ✗ remove override
                          │    NEW: markBotTransfer      -> keep INTERNAL_TRANSFER  │  ✓ basis-neutral
                          │                              -> counterparty=:BOT       │
                          └───────────────────────────────────────────────────────┘
                                              │
        post-batch  ──►  BybitBotTransferCostBasisService (interim fallback, single-asset only,
                          PriceSource.BOT_LEDGER; multi-asset -> leave EVIDENCE_MISSING, NO spot FMV)
                                              │
                                              ▼
                                    AVCO replay (member-level position BYBIT:<uid>)
              same-asset FUND↔BOT  -> CARRY_OUT + CARRY_IN  (net 0, basis-neutral)
              cross-asset (P1)     -> returned crypto EVIDENCE_MISSING (shortfall, flagged)
              cross-asset (P2)     -> bot-trade swap: USDT DISPOSE @AVCO + crypto ACQUIRE @consideration

  ┌──────────── NEW-13 (GMX fee refund) ───────────┐     ┌──────────── NEW-14 (Rabby gas) ───────────┐
  LinkingBatchProcessor.run():                            Normalization (on-chain):
    gmxV2RefundClassifier      (stamps REFUND)             HeuristicClassifier.classify
    gmxWithdrawalSettlementLink (NEW-09 promote first)       -> SponsoredGasTopUpSupport.findVerifiedSender
    >>> gmxExecutionFeeRefundBasisNeutralService (NEW) <<<     -> ProtocolRegistryService.lookup(BASE, 0x76dd65…)
        residual REFUND-only EXTERNAL_TRANSFER_IN                 -> role=GAS_PAYER  (registry entry ADDED)
        -> type=SPONSORED_GAS_IN, role BUY->TRANSFER             -> shape gate ok + envelope(0.0000237<0.005)
                       │                                          -> type = SPONSORED_GAS_IN
                       ▼                                                    │
             AVCO replay: ReplayDispatcher.isSponsoredGasIn ───────────────┘
                       -> GenericFlowReplayEngine.applySponsoredGasIn -> GAS_ONLY (cost 0)
```

---

## Section C — Module Breakdown (concrete files / methods / registry entries)

### C.1 NEW-14 — Rabby gas-payer registry coverage (config only, no code)

**File:** `backend/core/src/main/resources/protocol-registry.json` — add one entry under the `── AGGREGATORS: Relay Gas Top-Up ──` block (schema identical to `0x91604f59…`):

```json
"0x76dd65529dc6c073c1e0af2a5ecc78434bdbf7d9": {
  "name": "Rabby Gas Fee Payer",
  "protocol": "Rabby",
  "version": "GasAccount",
  "family": "AGGREGATOR",
  "role": "GAS_PAYER",
  "networks": ["ETHEREUM","ARBITRUM","OPTIMISM","BASE","BSC","AVALANCHE","LINEA"],
  "confidence": "HIGH",
  "notes": "Rabby wallet gas-account payer (etherscan/basescan/bscscan label 'Rabby: Gas Fee Payer'). Plain native top-ups (input=0x, 21000 gas) classify as SPONSORED_GAS_IN/GAS_ONLY (basis-neutral). Verified single occurrence 0x39dca64e… BASE."
}
```

- **Consumed by (no change needed):** `ProtocolRegistryLoader.load()` → `ProtocolRegistryService.lookup(NetworkId, addr)` → `SponsoredGasTopUpSupport.findVerifiedSender` (`role==GAS_PAYER` + shape gate + `fitsNetworkEnvelope`) → `HeuristicClassifier.classify` → `SPONSORED_GAS_IN`.
- **Networks rationale:** register only WalletRadar-supported networks that Rabby covers (intersection). BASE is the only one exercised by the dataset; the rest are forward-coverage. `notes` documents provenance (guardrail against dataset-specific coding).
- **Earliest failed stage confirmed:** counterparty coverage — the tx already matches the `SPONSORED_GAS_IN` shape gate and BASE envelope; the *only* reason it fell through to `EXTERNAL_TRANSFER_IN`/ACQUIRE is that `lookup()` returned empty.

### C.2 NEW-13 — GMX execution-fee refund → basis-neutral (new linking service)

**New file:** `backend/core/src/main/java/com/walletradar/application/linking/pipeline/clarification/GmxExecutionFeeRefundBasisNeutralService.java`
**Edited file:** `backend/core/src/main/java/com/walletradar/application/linking/job/LinkingBatchProcessor.java`

- **Candidate query** (mirror `GmxWithdrawalSettlementLinkService.loadCandidates`, but for the *residual*):
  `type = EXTERNAL_TRANSFER_IN` AND `protocolName = "GMX V2"` AND `missingDataReasons` contains `GMX_EXECUTION_FEE_REFUND` AND NOT already reclassified. (These are precisely the rows the NEW-09 settlement service **declined** because no open `gmx-lp:*` `LP_EXIT_REQUEST` matched.)
- **Reclassification method `reclassifyAsBasisNeutralGasRefund(tx, now)`:**
  - `tx.setType(SPONSORED_GAS_IN)`.
  - For each principal (`role=BUY`, native, `qtyDelta>0`) flow: `flow.setRole(TRANSFER)`; clear market pricing (`unitPriceUsd=null`, `valueUsd=null`, keep quantity) — mirrors `GmxWithdrawalSettlementLinkService.reclassifyAsSettlement` stripping market pricing.
  - Keep `missingDataReasons=[GMX_EXECUTION_FEE_REFUND]` as provenance (do not remove — distinguishes from Relay/LiFi sponsored gas).
- **Wiring (`LinkingBatchProcessor.run`, immediately AFTER `gmxWithdrawalSettlementLink`, ~line 247):**
  ```
  processed += timedPass("gmxExecutionFeeRefundBasisNeutral",
      () -> gmxExecutionFeeRefundBasisNeutralService.reclassifyResidualRefunds(batchSize));
  ```
  **Ordering is load-bearing:** `gmxV2RefundClassifier` (stamp) → `gmxWithdrawalSettlementLink` (NEW-09 promote genuine settlements first) → **this** (demote only leftovers). This *preserves the NEW-09 guard*: any refund that DOES have a matching open exit-request is already an `LP_EXIT_SETTLEMENT` before this service runs, so it cannot be candidate-matched here.
- **Replay (no change):** `ReplayDispatcher.isSponsoredGasIn` requires `type=SPONSORED_GAS_IN` + `role=TRANSFER` + `qtyDelta>0` → `GenericFlowReplayEngine.applySponsoredGasIn` → `GAS_ONLY` (cost 0). All satisfied by the reclassification above.

> **Why `SPONSORED_GAS_IN` rather than a new `BasisEffect`:** the audit's reusable rule = "return-of-capital / gas refund → basis-neutral (GAS_ONLY-class), never spot ACQUIRE." `SPONSORED_GAS_IN→GAS_ONLY` is that class and already wired end-to-end. Adding a new enum value would ripple through every `BasisEffect` switch for zero financial benefit.

### C.3 NEW-12 Phase 1 — honor `canonicalType`, kill fabrication (Bybit normalization)

**Edited files:**
1. `…/cex/normalization/venue/bybit/BybitCanonicalFlowCounterpartySupport.java`
   - **Replace `reclassifyBotTransfer` (lines 212–248) with `markBotTransfer(tx, row, now)`** that:
     - **Does NOT** set `type=EXTERNAL_TRANSFER_IN/OUT` and **does NOT** flip flow roles `TRANSFER→BUY/SELL`. The row stays `INTERNAL_TRANSFER` with `role=TRANSFER` (as mapped from `canonicalType`).
     - Keeps `correlationId` / `continuityCandidate` from the INTERNAL block (do **not** null them — they are needed for member-internal carry pairing).
     - Sets counterparty to the same-member **`:BOT`** compartment (see file 3): `counterpartyAddress = BYBIT:<uid>:BOT`, `counterpartyType = CEX`. Direction: from-bot (`sign>0`) → this row is the FUND/UTA credit, counterparty `:BOT`; to-bot (`sign<0`) → debit, counterparty `:BOT`.
     - Adds marker `BOT_TRANSFER` (retain — used by the interim cost service and Phase-2 targeting).
     - Adds `BOT_TRANSFER_PENDING_COST` only for non-stablecoin from-bot legs (unchanged semantics), so the interim service can attempt a single-asset-session basis; **but the terminal fallback must NOT be spot FMV** (see file 2).
2. `…/cex/normalization/venue/bybit/BybitBotTransferCostBasisService.java`
   - Keep the single-non-stable-asset session net-stablecoin computation (it already yields the DOGE-style correct `BOT_LEDGER` basis and must not regress).
   - **Add an explicit terminal guard:** for rows that remain `BOT_TRANSFER_PENDING_COST` after session resolution (multi-asset / ambiguous sessions — e.g. the Oct-21 ETH+BTC of member 516601508), **do not allow the downstream pricing pipeline to assign spot FMV as an ACQUIRE.** Because these rows are now `INTERNAL_TRANSFER` (not `EXTERNAL_TRANSFER_IN`), they will not enter `confirmWhenAllBuySellFlowsPriced` and will not be spot-priced as BUY — but add a defensive assertion / status so they surface as `EVIDENCE_MISSING` (the returned crypto carries no basis lot; replay books shortfall). Retain `BOT_TRANSFER_PENDING_COST` as the provenance flag for Phase-2 resolution.
   - **Interim-fallback consistency note:** under `INTERNAL_TRANSFER` typing, a single-asset session's returned crypto is booked as a member-internal conversion — the net stablecoin consumed is the crypto's carried basis (`BOT_LEDGER`), and the corresponding to-bot stablecoin is consumed by that conversion (not retained). Keep this behaviour ONLY for `nonStableAssetsReturned.size()==1`; it is the explicitly-labelled interim fallback and is deleted in Phase 2.
3. `…/cex/acquisition/venue/bybit/BybitExtractionService.java` (+ `BybitVenueDescriptor`)
   - Add `BOT` to `enum BybitSubAccount { UTA, FUND, EARN, BOT }` and to `BybitVenueDescriptor.accountKindSuffixes()`.
   - Map bot legs to the `:BOT` compartment when stamping counterparty (the row's own `walletRef` stays `:FUND`/`:UTA` from the FUND-perspective funding-history; the *counterparty* is `:BOT`). `BybitCanonicalCorrelationSupport.otherSubAccount` gains a `:BOT ↔ :FUND` mapping for pairing.
4. `…/cex/normalization/venue/bybit/BybitInternalTransferExternalCpReclassifier.java`
   - Remove/relax the `BOT_TRANSFER` **skip guard** (lines 117–119). It was added only to stop the same-uid `EXTERNAL→INTERNAL` demotion from re-touching bot rows *while they were EXTERNAL*. Now that bot rows are already `INTERNAL_TRANSFER`, the guard is unnecessary; ensure the same-uid path treats `:BOT` counterparties as internal (it already keys on same-uid `BYBIT:` prefix).

**No change to:** `BybitCanonicalTransactionBuilder.buildMappedRow` INTERNAL block (lines 211–234) already builds correlationId + counterparty for `INTERNAL_TRANSFER`; the only edit at the call site (line 251–253) is that `isBotTransfer(row)` now calls `markBotTransfer` (non-overriding) instead of `reclassifyBotTransfer`.

### C.4 NEW-12 Phase 2 — bot-trade ingestion (next cycle; scoped here, not built now)

- **New ingestion source:** Bybit bot order / closed-PnL history (public Bybit API — free; no paid indexer) → new `integration_raw_events` stream `BOT_TRADE` (or `CLOSED_PNL`) → `BybitExtractionService.extractBotTrade` → `bybit_extracted_events` with `canonicalType=SWAP/CONVERT`, `walletRef=BYBIT:<uid>:BOT`.
- **Normalization:** build the swaps on the `:BOT` compartment (USDT sell → crypto buy at trade price). Because the member position is shared, this decrements member USDT and increments member crypto with **exact consideration basis**; the funding-history FUND↔BOT transfers become pure basis-neutral `INTERNAL_TRANSFER` no-ops.
- **Cleanup:** delete the interim `BybitBotTransferCostBasisService` net-stablecoin fallback and the `BOT_TRANSFER_PENDING_COST` marker once trades cover the sessions.

---

## Section D — Mongo Collections & Indexes

No schema migration. Collections touched by replay/normalization re-run: `bybit_extracted_events`, `normalized_transactions`, `asset_ledger_points` (rebuilt by `--skip-frontend`).

- **New enum literal in data:** `normalized_transactions.type = SPONSORED_GAS_IN` for the one GMX refund row (NEW-13) and the one Rabby row (NEW-14). Both already valid enum values — no index/schema impact.
- **New wallet-ref value:** counterparty `BYBIT:<uid>:BOT` in `normalized_transactions.counterpartyAddress`. `asset_ledger_points.walletAddress` remains **member-level** (`BYBIT:<uid>`) — confirmed the compartment suffix is stripped before the position key, so `:BOT` does **not** create a new position and does **not** need a new index.
- **Existing indexes suffice.** `GmxExecutionFeeRefundBasisNeutralService` candidate query filters `type` + `protocolName` + `missingDataReasons`; the same triple is already used by `GmxWithdrawalSettlementLinkService` and `GmxV2RefundClassifier` (existing coverage). `BybitBotTransferCostBasisService` continues to query `source=BYBIT` + `missingDataReasons=BOT_TRANSFER`.

---

## Section E — Data Flows & Algorithm Pseudocode

### E.1 NEW-14 (Rabby) — no algorithm; declarative registry lookup
Effect: `HeuristicClassifier` → `SPONSORED_GAS_IN` → replay `GAS_ONLY` (cost 0). Result on anchor: ETH +0.0000237 restored at $0 basis; `costBasisDelta=0`; `avcoAfter=null` (`gasOnlyAvcoAfter`). Fabricated **+$0.070 eliminated**.

### E.2 NEW-13 — residual GMX fee-refund demotion (linking)
```
for tx in candidates(type=EXTERNAL_TRANSFER_IN, protocol="GMX V2",
                     missingDataReasons∋GMX_EXECUTION_FEE_REFUND):
    # NEW-09 guard already ran: any tx with a matching open gmx-lp:* LP_EXIT_REQUEST
    # is now LP_EXIT_SETTLEMENT and is NOT in this candidate set.
    tx.type = SPONSORED_GAS_IN
    for flow in tx.principalFlows(native, qtyDelta>0, role=BUY):
        flow.role = TRANSFER
        flow.unitPriceUsd = null; flow.valueUsd = null   # strip market mark; keep quantity
    keep missingDataReasons (provenance); tx.updatedAt = now; save
# replay: isSponsoredGasIn(tx,flow)==true -> applySponsoredGasIn -> GAS_ONLY (cost 0)
```
Result on `0x17273e5c…`: ETH +0.000019472334 → `GAS_ONLY`, `costBasisDelta=0`. Fabricated **+$0.037 eliminated**. **NEW-09 negative case intact** (not an `LP_EXIT_SETTLEMENT`).

### E.3 NEW-12 Phase 1 — honor canonicalType + no fabrication (normalization)
```
buildMappedRow(row):
    type = mapCanonicalType(row.canonicalType)          # INTERNAL_TRANSFER for Bot rows
    ... INTERNAL block sets correlationId + :BOT counterparty ...
    finalizeBybitFlows(tx, row)
    if isBotTransfer(row):                                # bybitType == "Bot"
        markBotTransfer(tx, row):                         # <-- was reclassifyBotTransfer
            KEEP type = INTERNAL_TRANSFER; KEEP role = TRANSFER
            counterparty = BYBIT:<uid>:BOT ; counterpartyType = CEX
            add BOT_TRANSFER
            if sign>0 and non-stablecoin: add BOT_TRANSFER_PENDING_COST
    # NOTE: type is INTERNAL_TRANSFER -> NOT EXTERNAL_TRANSFER_IN
    #       -> confirmWhenAllBuySellFlowsPriced NOT reached -> NO spot-FMV ACQUIRE

post-batch BybitBotTransferCostBasisService.computeBotCostBasis():
    for session in 14-day sessions of BOT_TRANSFER rows (per member):
        if nonStableAssetsReturned.size() == 1:          # single-asset -> interim fallback OK
            unit = netStablecoinConsumed / totalReturned # BOT_LEDGER (labelled interim)
            price the returned-crypto leg as member-internal conversion basis
            remove BOT_TRANSFER_PENDING_COST
        else:                                            # multi-asset (TX4 ETH+BTC) -> DO NOTHING
            leave BOT_TRANSFER_PENDING_COST              # EVIDENCE_MISSING; NEVER spot FMV
```
Replay outcome (member-level position `BYBIT:<uid>`):
- USDT to/from bot (same asset) → `CARRY_OUT`+`CARRY_IN` net 0 → **no phantom DISPOSE** (−185 USDT churn removed).
- DOGE (single-asset session, member 421325298) → carries `BOT_LEDGER` basis (unchanged, no regression).
- **ETH +0.01374624 / BTC +0.001286712 (Oct-21 multi-asset session, member 516601508)** → returned as member-internal transfer with no covering lot → `EVIDENCE_MISSING` shortfall (bounded ~0.0137 ETH + 0.00129 BTC). **Fabricated $53.48 + $126.95 eliminated; no phantom ACQUIRE.**

### E.4 NEW-12 Phase 2 — bot-trade swaps (next cycle)
```
ingest BOT_TRADE stream -> extract SWAP(USDT->crypto) on walletRef BYBIT:<uid>:BOT
normalize -> member position: USDT DISPOSE @ member-AVCO (realized PnL captured)
                             + crypto ACQUIRE @ consideration-given (USDT value)
funding-history FUND<->BOT transfers -> pure INTERNAL_TRANSFER no-ops (basis-neutral)
=> returned ETH/BTC carry exact basis; Phase-1 shortfall clears; remove interim fallback + PENDING_COST
```

---

## Section F — Phasing, Scaling & Prod-Rebuild Mode

### F.1 Required layers (strict vs optional)

| Defect | Layer | Required? |
|---|---|---|
| NEW-14 | `protocol-registry.json` entry | **Required** (whole fix) |
| NEW-13 | `GmxExecutionFeeRefundBasisNeutralService` + `LinkingBatchProcessor` wiring | **Required** |
| NEW-12 P1 | `markBotTransfer` (stop EXTERNAL override) | **Required** (kills phantom DISPOSE + spot ACQUIRE) |
| NEW-12 P1 | `:BOT` compartment enum/suffix + counterparty | **Required** (correct type + observability) |
| NEW-12 P1 | `BybitBotTransferCostBasisService` terminal guard (no spot FMV) | **Required** (kills fabrication on multi-asset) |
| NEW-12 P1 | `BybitInternalTransferExternalCpReclassifier` guard relax | Required-for-correctness (prevents re-demotion) |
| NEW-12 P1 | ADR-0NN accounting-policy note | **Required** (policy change) |
| NEW-12 P2 | Bot-trade ingestion (`BOT_TRADE` stream, swaps) | Optional-this-cycle / **Required next cycle** to clear the interim shortfall |

### F.2 Interim correctness guarantee (end of Phase 1)
- **No fabricated spot ACQUIRE** on any from-bot crypto (ETH/BTC/DOGE). ✔
- **No phantom external DISPOSE** of to-bot USDT; **no phantom realized PnL**. ✔
- Bot rows are `INTERNAL_TRANSFER` (honor extractor `canonicalType`), counterparty = same-member `:BOT`. ✔
- Single-non-stable-asset bot sessions (DOGE) carry correct net-consideration `BOT_LEDGER` basis (unchanged). ✔
- Cross-asset multi-asset sessions (the flagged TX4 ETH + BTC) carry a **bounded, flagged `EVIDENCE_MISSING` shortfall** (~0.0137 ETH + 0.00129 BTC) — honest, not fabricated; **cleared in Phase 2**. This is a strict improvement over $180 fabricated basis + $185 phantom churn.
- **Genuine external Bybit deposits/withdrawals unchanged** (different extractor branch; fix keys on `bybitType="Bot"` only). ✔

### F.3 Prod-rebuild mode (per `.cursor/rules/prod-rebuild-workflow.mdc`)
All three fixes are normalization / classification / linking / AVCO-replay changes ⇒ full renormalization:
```
./scripts/prod-reset-rebuild-backend.sh --skip-frontend
```
- Do **not** add `--clear-pricing-cache` (we remove spot pricing; historical prices are untouched).
- Run from repo root; wait for completion before reporting verification.

---

## Section G — Cost, Risks & Regression Analysis

### G.1 Cost
- Zero new paid infrastructure. Phase 2 bot-trade ingestion uses the **free Bybit API** (public), consistent with cost-first constraints (no paid indexer/SaaS). No new thread pools; reuses the existing Bybit ingestion cadence + Caffeine caches.

### G.2 Regression risks & mitigations

| Risk | Mitigation |
|---|---|
| **Over-broad Rabby registration** promotes non-gas inbounds to `SPONSORED_GAS_IN` | `SponsoredGasTopUpSupport` shape gate (input=0x, single native inbound, `wallet==to`, no token/internal legs) + per-network envelope already reject anything but tiny plain top-ups. Registered networks limited to Rabby's real coverage. |
| **NEW-13 accidentally demotes a genuine GMX GLV/GM settlement** (NEW-09 regression) | Service runs strictly **after** `gmxWithdrawalSettlementLink`; genuine settlements are already `LP_EXIT_SETTLEMENT` and excluded by the `type=EXTERNAL_TRANSFER_IN` candidate filter. Regression test asserts the two existing `LP_EXIT_SETTLEMENT` rows are untouched. |
| **NEW-12 relaxing the reclassifier guard** re-demotes non-bot rows | Same-uid `EXTERNAL→INTERNAL` only fires for same-member `BYBIT:` counterparties; bot rows are already `INTERNAL_TRANSFER` so they are not candidates. Assert genuine external deposits stay `EXTERNAL_TRANSFER_IN`. |
| **New Phase-1 `EVIDENCE_MISSING` shortfall** (ETH ~0.0137 + BTC ~0.00129) shows in reconciliation | **Expected & documented** delta; bounded, ETH/BTC-family, flagged; cleared in Phase 2. Update `results/reconciliation.md` / `results/warnings.md` to record it as a Phase-1 interim state, not a defect. |
| **Interim net-stablecoin fallback is an amount-only heuristic** | Constrained to `size()==1` sessions, labelled `BOT_LEDGER`, and **deleted in Phase 2**. Never spot-FMV. Documented in the ADR as interim-only. |
| Member-level position assumption wrong | Verified live: `asset_ledger_points.walletAddress=BYBIT:516601508` (suffix stripped). `:BOT` counterparty does not create a new position. |

### G.3 Ranking (value / risk)
1. **NEW-12** — HIGH material ($180 fabricated ETH/BTC + $185 USDT churn); highest value, highest risk/effort (phased). Do the classification-neutral Phase 1 this cycle.
2. **NEW-14** — LOW value ($0.070) but **near-zero risk / effort** (one JSON line). Do first as a warm-up + independent verification of the sponsored-gas path.
3. **NEW-13** — LOW value ($0.037), low risk (small isolated linking service). Do second.

---

## Section H — Test List (keyed to exact anchors) + Ordered Task List

### H.1 Unit / arch tests

**NEW-14 — `OnChainClassifierTest` (add cases) / `ProtocolRegistryLoaderTest`:**
- `rabbyGasPayerNativeTopUpBecomesSponsoredGasIn`: raw `from=0x76dd65…`, `input=0x`, 21000 gas, +0.0000237 ETH, BASE → `type=SPONSORED_GAS_IN`.
- `rabbyPayerRegistryEntryLoadsAsGasPayer`: loader resolves `(BASE, 0x76dd65…)` → `role=GAS_PAYER`, `family=AGGREGATOR`.
- Negative: `oversizedRabbyNativePayoutStaysExternalTransferIn` (> BASE envelope 0.005) → stays `EXTERNAL_TRANSFER_IN`.

**NEW-13 — `GmxExecutionFeeRefundBasisNeutralServiceTest` (new) + `GmxWithdrawalSettlementLinkServiceTest` (regression):**
- `residualGmxFeeRefundBecomesSponsoredGasIn`: `EXTERNAL_TRANSFER_IN` + `GMX V2` + `GMX_EXECUTION_FEE_REFUND`, no open exit-request → `type=SPONSORED_GAS_IN`, principal role `BUY→TRANSFER`, market price stripped.
- `settlementWithOpenExitRequestNotDemoted` (NEW-09 guard): a refund with a matching open `gmx-lp:*` `LP_EXIT_REQUEST` is promoted to `LP_EXIT_SETTLEMENT` by the prior service and is **not** a candidate here.
- Negative: non-GMX `EXTERNAL_TRANSFER_IN` untouched.

**NEW-12 — `BybitCanonicalTransactionBuilderTest`, `BybitInternalTransferExternalCpReclassifierTest`, `BybitBotTransferCostBasisServiceTest`:**
- `botTransferRowKeepsInternalTransferType`: extracted `canonicalType=INTERNAL_TRANSFER`, `bybitType=Bot` → normalized `type=INTERNAL_TRANSFER`, `role=TRANSFER`, counterparty `BYBIT:<uid>:BOT`, `counterpartyType=CEX`. No `EXTERNAL_TRANSFER_IN/OUT`.
- `botTransferNonStablecoinMultiAssetSessionStaysEvidenceMissing`: ETH+BTC in one session → **no** spot-FMV ACQUIRE; `BOT_TRANSFER_PENDING_COST` retained; no `role=BUY` priced leg.
- `botTransferSingleAssetSessionCarriesBotLedgerBasis`: DOGE-only session → `BOT_LEDGER` basis, `PENDING_COST` cleared (no regression).
- `genuineExternalBybitDepositUnchanged`: `bybitType=Deposit` → `EXTERNAL_TRANSFER_IN` (unchanged).

### H.2 E2E / replay assertions (post `--skip-frontend`, keyed to anchors)
- **`BYBIT-516601508:FUNDING_HISTORY:f2403…da1e`** (ETH): `normalized.type=INTERNAL_TRANSFER`; ledger `basisEffect ≠ ACQUIRE`; **no** `costBasisDelta=+53.48`; ETH lot `EVIDENCE_MISSING`/uncovered (Phase 1) → carries consideration basis, uncovered=0 (Phase 2).
- BTC bot rows (member 516601508): no `+$126.95` ACQUIRE; `INTERNAL_TRANSFER`.
- USDT bot rows (members 516601508 / 421325298): **no `EXTERNAL_TRANSFER_OUT`/DISPOSE**; `INTERNAL_TRANSFER` CARRY net 0; member USDT inventory not drained (−185 / −125 phantom dispose gone).
- DOGE row (`…421325298…`): still `BOT_LEDGER` basis (no regression).
- **`0x17273e5c…dae7`** (GMX): `type=SPONSORED_GAS_IN`; ledger `basisEffect=GAS_ONLY`, `costBasisDelta=0`; **not** `LP_EXIT_SETTLEMENT` (NEW-09 intact); the two existing GMX `LP_EXIT_SETTLEMENT` rows unchanged.
- **`0x39dca64e…f3d`** (Rabby): `type=SPONSORED_GAS_IN`; ledger `basisEffect=GAS_ONLY`, `costBasisDelta=0`.
- **Global regression:** `basisEffect` distribution delta = only the expected conversions (ACQUIRE↓ for the bot ETH/BTC + GMX + Rabby; GAS_ONLY↑ by 2; DISPOSE↓ for USDT bot legs; new bounded ETH/BTC shortfall). No new material UNKNOWN; genuine deposits/withdrawals, bridges, LP positions byte-stable.

### H.3 Ordered task list (backend engineer executable)
1. **NEW-14:** add the Rabby `GAS_PAYER` entry to `protocol-registry.json`; add `ProtocolRegistryLoaderTest` + `OnChainClassifierTest` cases.
2. **NEW-13:** create `GmxExecutionFeeRefundBasisNeutralService`; wire into `LinkingBatchProcessor.run()` immediately after `gmxWithdrawalSettlementLink`; add `GmxExecutionFeeRefundBasisNeutralServiceTest` + NEW-09 regression case.
3. **NEW-12 P1a (compartment):** add `BOT` to `BybitSubAccount` enum + `BybitVenueDescriptor.accountKindSuffixes()`; add `:BOT↔:FUND` to `BybitCanonicalCorrelationSupport.otherSubAccount`.
4. **NEW-12 P1b (honor type):** replace `reclassifyBotTransfer` with non-overriding `markBotTransfer` (keep `INTERNAL_TRANSFER`/`role=TRANSFER`, set `:BOT` counterparty); update call site in `BybitCanonicalTransactionBuilder.buildMappedRow`.
5. **NEW-12 P1c (no fabrication):** add the terminal guard in `BybitBotTransferCostBasisService` so unresolved multi-asset sessions stay `EVIDENCE_MISSING` (never spot FMV); keep single-asset `BOT_LEDGER` fallback.
6. **NEW-12 P1d (reclassifier):** relax the `BOT_TRANSFER` skip guard in `BybitInternalTransferExternalCpReclassifier`; add tests in `BybitCanonicalTransactionBuilderTest` + `BybitInternalTransferExternalCpReclassifierTest` + `BybitBotTransferCostBasisServiceTest`.
7. **ADR:** write `docs/adr/ADR-0NN-cex-trading-bot-compartment-and-internal-conversion-basis.md`; update `docs/reference/ledger-points-and-basis-effects.md` (GMX/Rabby → GAS_ONLY; bot internal-transfer policy).
8. **Rebuild & verify:** `./scripts/prod-reset-rebuild-backend.sh --skip-frontend`; run the H.2 assertions via MongoDB MCP; record the Phase-1 interim ETH/BTC shortfall in `results/reconciliation.md` + `results/warnings.md`.
9. **Phase 2 (separate cycle):** scope & build Bybit `BOT_TRADE`/closed-PnL ingestion → `:BOT` swaps → clear interim shortfall → delete net-stablecoin fallback + `BOT_TRANSFER_PENDING_COST`.
