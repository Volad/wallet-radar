# Implementation Plan — GMX V2 GLV/GM exit-settlement carry (NEW-09) + Relay inbound classification (NEW-11), with Bybit sub-account scoping (NEW-10)

**Author:** system-architect
**Date:** 2026-07-15
**Universe:** `df5e69cc-a0c0-4910-8b7d-74488fa266e2` · **Primary wallet:** `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f`
**Stack:** Java 21 · Spring Boot · Gradle (never Maven) · MongoDB
**Source findings:** `results/flagged-tx-audit-2.md`, `results/blockers.md` (NEW-09/10/11), `results/reconciliation.md` (GLV/Relay/Bybit tables)
**Anchor txs:** GLV settlement `0xf3581fb98799bb1d55ec08a72dfb6668ae4009f219434e734e8a9db0388ec374`; GLV exit-request `0x806ccd26c2f11ab6180c8f1f0448df2fda3917ffcc65dc7d661c1ae8d86426ec`; Relay 0.5 ETH `0x99d91f594e29a3cfe64b9e72921662e2780ca9fa75a7ebf62ed5132a2f369f7d`

---

## A. Decisions & Assumptions (with rationale)

### A0. Ground-truth verification (live DB, this cycle)

Before designing, the following were verified directly against prod Mongo (not assumed from the audit prose):

| Fact | Verified value | Consequence |
|---|---|---|
| `0xf3581f` normalized | `type=EXTERNAL_TRANSFER_IN`, `protocolName="GMX V2"`, `counterpartyType=PROTOCOL`, `missingDataReasons=[COUNTERPARTY_ADDRESS_INFERRED, GMX_EXECUTION_FEE_REFUND]`, 3× `BUY` ETH legs (0.009864 + 0.009996 + 0.001006 ≈ 0.020866 ETH), all `counterpartyAddress=UNKNOWN:…` composite | It is a **market ACQUIRE**, not linked; existing `GmxExitSettlementLinkService` cannot see it (wrong type) nor join it (no real market CP on inbound legs) |
| `0xf3581f` raw | `rawData.explorer.tokenTransfers=[]`; only 3 native-ETH `internalTransfers` **from GMX V2 handlers** `0x63dc80ee…` (WithdrawalHandler, older), `0x70d95587…` (DepositHandler, older), `0x1eea01a3…` (OrderVault, older); **no `methodId`/`functionName`/`input`/logs persisted** | Selector `0x0910f3f9`, functionName `executeGlvWithdrawal`, and `GlvWithdrawalExecuted` event are **not available** in stored raw → selector/event-based on-chain classification is structurally impossible for this tx. Only signal: native-ETH inbound from a GMX handler. |
| `0x806ccd26` (exit-request) | `type=LP_EXIT_REQUEST`, `correlationId=gmx-lp:arbitrum:glv-weth-usdc`, outbound GLV token `0x528a5bac…` (CP=GLV vault `0x393053b5…`, −40.348), outbound ETH −0.0011539 (CP `0x7eadee2c…`), FEE ETH −0.0000207 | Exit-request already released carry into the async bucket; correlated correctly. |
| `0x99d91f` raw | `tokenTransfers=[]`; single internal ETH transfer **from `0x1619de6b6b20ed217a58d00f37b9d47c7663feca`** → wallet, 0.500447 ETH | Registering that exact address as a Relay payout makes the existing `resolveRelayPayoutInboundEntry` path fire → `BRIDGE_IN`. |
| Relay solver `0x91604f59…` registry | `family=AGGREGATOR, role=GAS_PAYER`, `networks[]` **omits `ZKSYNC`** | ZKSYNC dust receipts miss registry lookup → fall to `EXTERNAL_TRANSFER_IN`. |
| **Bybit sub-accounts (NEW-10)** | `asset_ledger_points` for `BYBIT:409666492`=168, `BYBIT:516601508`=94, `BYBIT:421325298`=22 (+2 EARN) = **286 points**; 246 normalized txs; flagged `…FUNDING_HISTORY:f240349…` **is** normalized (`BYBIT:516601508:FUND`, `EXTERNAL_TRANSFER_IN`, CONFIRMED) | **NEW-10 does NOT reproduce** in current DB. Sub-accounts ARE consolidated. See §E.3 — recommend no code change this cycle. |

### A1. NEW-09 is a linking + secondary-classification defect; the AVCO carry machinery already exists

`LP_EXIT_SETTLEMENT` already routes to `ReplayRoute.ASYNC_LP_EXIT_SETTLEMENT` →
`GenericAsyncLifecycleReplayHandler.applyAsyncLpExitSettlement(...)`, which drains
`replayState.asyncLifecycleBucket(correlationId)` onto the returned asset(s) via
`takeSameAssetCarry` + `allocateIndexedSettlementByQuantity` (same-asset) /
`allocateIndexedSettlementByKnownValue` (cross-asset), producing `REALLOCATE_IN` and
**no** market ACQUIRE. The exit-request `0x806ccd26` already deposited the carry (GLV
$47.23 + returned-ETH-fee $3.24 ≈ $50.47) into that bucket.

**Decision:** Do **not** invent new accounting. The only missing steps are (a) typing
`0xf3581f` as `LP_EXIT_SETTLEMENT`, (b) stamping it with the correct
`gmx-lp:arbitrum:glv-weth-usdc` `correlationId`, and (c) reshaping its inbound ETH legs
from `BUY` → `TRANSFER` so the existing async settlement carry fires. This mirrors how
NEW-08 reused the bridge REALLOCATE path and how `GmxEntryRequestLinkService` reuses
time-window pairing.

### A2. Earliest wrong stage = linking (cross-tx), realised as a reclassification pass

The tx is mis-typed at classification, but the *correct* type is only decidable with
cross-tx context (the open `LP_EXIT_REQUEST` for the same position), which the per-tx
on-chain classifier does not have — especially since this settlement was ingested
**only** as an internal-transactions record (no selector/function/logs). Therefore the
deterministic fix lives at the **linking** stage, exactly like `GmxEntryRequestLinkService`,
`LiFiBridgePairLinkService`, and `KnownBridgeRouterExternalTypeCorrectionService`. This
respects the audit-driven rule "fix the earliest wrong stage and rely on rerun" without
resorting to a post-factum sweep over historical canonical rows.

### A3. Deterministic pairing key (no amount heuristics)

Link the keeper settlement to the **unique open `LP_EXIT_REQUEST`** with a `gmx-lp:*`
`correlationId` for the **same wallet** whose `blockTimestamp ≤ settlement.blockTimestamp`
and within a bounded window (GMX keepers settle in seconds; use a conservative window
equal to `GmxEntryRequestLinkService.SETTLEMENT_WINDOW_SECONDS = 600`, applied backwards).
Determinism guards:
- Candidate settlement must be a **GMX V2 handler-originated native inflow** (already
  stamped `protocolName="GMX V2"` + `GMX_EXECUTION_FEE_REFUND` by
  `GmxV2RefundClassifier`), i.e. `internalTransfers[].from ∈ GmxV2HandlerRegistry`.
- Match the **nearest-preceding** open exit-request (max `blockTimestamp ≤ settlement`).
- The exit-request must be **unsettled** (no other `LP_EXIT_SETTLEMENT` already carries its
  `correlationId`).
- If **>1** open exit-request is in-window and the market cannot be disambiguated (no
  full-receipt `GlvWithdrawalExecuted` market evidence), **skip** (leave as fee refund) —
  never guess.
- Genuine standalone execution-fee refunds (no matching open exit-request) **stay**
  `EXTERNAL_TRANSFER_IN` + `GMX_EXECUTION_FEE_REFUND` (the audit's required negative case).

This is the same class of deterministic lifecycle+ordering pairing the codebase already
accepts for GMX entries; it uses position id + receiver(wallet) + protocol + ordering,
**not** amounts.

### A4. NEW-11 is AVCO-neutral: type/counterparty + registry coverage only

For every Relay inbound the source leg is outside the tracked universe
(`GENUINE_EVIDENCE_MISSING`), so the market ACQUIRE is the correct basis and must be
preserved. Typing as `BRIDGE_IN` and demoting the orphan to a market-priced ACQUIRE
(`UnmatchedBridgeInboundPricingFallbackService.reconcileOrphanInbounds`) yields the
**identical** basis as today's `EXTERNAL_TRANSFER_IN` → market ACQUIRE
(`UnmatchedExternalTransferInPricingFallbackService`). **Decision:** registry coverage +
reuse the existing `RelayBridgeClassificationSupport.resolveRelayPayoutInboundEntry`
`GAS_PAYER→BRIDGE_IN` path; no accounting change, no fabricated correlation.

### A5. NEW-10 is out of this cycle (not reproducible)

Live DB shows sub-accounts consolidated (§A0). **Decision:** ship no NEW-10 code; provide
a scoping section (§E.3) recommending a separate, low-priority continuity re-verification.

### Assumptions

- The convergent linking passes run repeatedly to convergence each rebuild (confirmed by
  `LinkingBatchProcessor` loop contract), so a new pass placed after `gmxV2RefundClassifier`
  will see freshly-stamped candidates in the same cycle.
- `--skip-frontend` triggers full renormalization + full AVCO replay (per
  `.cursor/rules/prod-rebuild-workflow.mdc`), which is required for reclassification and
  bucket-carry effects to materialize. No pricing-policy change ⇒ no cache clear.

---

## B. Architecture / ASCII data-flow

```
                          NORMALIZATION (on-chain, per-tx)                         LINKING (cross-tx passes, converge to 0)                    REPLAY (AVCO)
                          ─────────────────────────────────                        ────────────────────────────────────────                   ─────────────

 NEW-09  raw 0xf3581f  ─▶ GmxLpClassifier / GmxProtocolSemanticClassifier    ┌─▶ gmxV2RefundClassifier  (stamps GMX V2 + GMX_EXECUTION_FEE_REFUND)
 (GLV     (only ETH       └ isGmxWithdrawalSettlement() CANNOT fire:          │        │  population: EXTERNAL_TRANSFER_IN, protocol "GMX V2"
  exit     internal          no selector/functionName/logs in raw            │        ▼
  settle)  traces)        ⇒ EXTERNAL_TRANSFER_IN (market ACQUIRE)  ───────────┘   *** NEW: GmxWithdrawalSettlementLinkService ***
                                                                                   • candidate = GMX-handler native inflow (fee-refund stamp)
              0x806ccd26 LP_EXIT_REQUEST                                            • find unique open LP_EXIT_REQUEST (gmx-lp:*, same wallet,
              corrId gmx-lp:arbitrum:glv-weth-usdc  ─── releases carry ──┐            nearest-preceding, unsettled, in 600s window)
              (GLV $47.23 + ETH $3.24 → async bucket)                    │          • RECLASSIFY  type→LP_EXIT_SETTLEMENT
                                                                         │          • SET correlationId = request.correlationId (gmx-lp:…)
                                                                         │          • RESHAPE inbound ETH legs BUY→TRANSFER (drop market px)
                                                                         │          • DROP GMX_EXECUTION_FEE_REFUND
                                                                         │                       │
                                                                         │                       ▼
                                                                         │        ReplayTransactionRouter: type==LP_EXIT_SETTLEMENT
                                                                         │           ⇒ ReplayRoute.ASYNC_LP_EXIT_SETTLEMENT
                                                                         └────────────▶ GenericAsyncLifecycleReplayHandler
                                                                                        .applyAsyncLpExitSettlement()
                                                                                        • takeSameAssetCarry(ETH) → REALLOCATE_IN $3.24
                                                                                        • allocate remaining bucket ($47.23 GLV) → ETH
                                                                                          REALLOCATE_IN ; bucket drained; NO ACQUIRE
                                                                                        ⇒ ETH lot basis ≈ carried, not $58.79


 NEW-11  raw 0x99d91f  ─▶ HeuristicClassifier (onlyInbound)                        (registry-only fix — no new pass)
 (Relay   ETH from        RelayBridgeClassificationSupport.resolveRelayPayoutInboundEntry()
  inbound  0x1619de6b)       • sender registered as Relay GAS_PAYER on this network?
  mistype)                     NO  ⇒ EXTERNAL_TRANSFER_IN ── market ACQUIRE (orphan)
                              *** FIX: register 0x1619de6b (ARBITRUM) + add ZKSYNC to 0x91604f59 ***
                                     YES ⇒ BRIDGE_IN ── orphan (no source) ── market ACQUIRE   (SAME basis, AVCO-neutral)
```

---

## C. Module breakdown & concrete change set

### C.1 NEW-09 — GMX GLV/GM exit-settlement pairing (PRIMARY, REQUIRED)

**New file (linking pass):**
`backend/core/src/main/java/com/walletradar/application/linking/pipeline/clarification/GmxWithdrawalSettlementLinkService.java`

Responsibilities (single class, `@Service`, batch method `int linkOutstandingWithdrawalSettlements(int batchSize)`):

1. `loadCandidates(batchSize)` — Mongo query on `normalized_transactions`:
   - `type = EXTERNAL_TRANSFER_IN`
   - `protocolName = "GMX V2"`
   - `missingDataReasons` contains `GMX_EXECUTION_FEE_REFUND`
   - native-asset inbound only (`flows` has a positive-delta ETH/native leg; no outbound principal)
   - (ordering) sort by `blockTimestamp` ASC.
2. For each candidate, `findMatchingOpenExitRequest(wallet, settlementTs)`:
   - Query `LP_EXIT_REQUEST` where `walletAddress == wallet`,
     `correlationId` matches `^gmx-lp:`, `blockTimestamp` in
     `[settlementTs − 600s, settlementTs]`, sort `blockTimestamp` DESC, and filter to
     those **not already settled** (no `LP_EXIT_SETTLEMENT` doc exists with that
     `correlationId` for the wallet).
   - Return the single nearest-preceding match; if the in-window candidate set has
     ≥2 distinct `correlationId`s that cannot be disambiguated, return `null` (skip).
3. `reclassifyAsSettlement(settlement, request, now)`:
   - `settlement.setType(LP_EXIT_SETTLEMENT)`
   - `settlement.setCorrelationId(request.getCorrelationId())`
   - For each inbound principal flow: `flow.setRole(NormalizedLegRole.TRANSFER)`,
     `flow.setUnitPriceUsd(null)`, `flow.setValueUsd(null)`, `flow.setPriceSource(null)`,
     `flow.setRealisedPnlUsd(null)` (carry basis comes from the async bucket, not market).
   - Remove `GMX_EXECUTION_FEE_REFUND` from `missingDataReasons`; keep `protocolName="GMX"`
     (align with `0x806ccd26` which uses `"GMX"`), `counterpartyType="PROTOCOL"`.
   - `settlement.setUpdatedAt(now)`; collect dirty; `saveAll`.
   - Log `GMX_WITHDRAWAL_SETTLEMENT_LINK linked=…`.

**Determinism helpers** (reuse existing): use `GmxV2HandlerRegistry.isKnownGmxV2Handler`
to (optionally, defensively) re-confirm the internal sender is a GMX handler if the
`RawTransaction` is loaded; the `protocolName="GMX V2"` + fee-refund stamp already implies
it, so a Mongo-only path is acceptable and cheaper.

**Wire-up:** `LinkingBatchProcessor`
- add field `private final GmxWithdrawalSettlementLinkService gmxWithdrawalSettlementLinkService;`
- add a `timedPass("gmxWithdrawalSettlementLink", () -> …linkOutstandingWithdrawalSettlements(batchSize))`
  **immediately after** the existing `gmxV2RefundClassifier` pass (~line 236–238), so the
  fee-refund population is already stamped this cycle. Placing it after ensures the
  candidate set exists; the convergent loop guarantees the reclassified rows are picked up
  by downstream passes on the next iteration (none needed here).

**Secondary classification hardening (defense-in-depth, low value, optional):**
`backend/core/src/main/resources/protocols/gmx-v2.json`
- add `"0x0910f3f9"` to `markers.methodSelectors.withdrawalSettlement`;
- add `"executeglvwithdrawal"`, `"executewithdrawal"` under `markers.functionNames` (new
  group `withdrawalSettlement`) and wire `GmxProtocolSemanticClassifier.isGmxWithdrawalSettlement`
  to also consult that functionName group.
This only helps future txs ingested **with** top-level input/selector; it does **not**
fix `0xf3581f` (no selector in raw). Mark REQUIRED-for-completeness but not sufficient
alone.

### C.2 NEW-11 — Relay inbound registry coverage (BUNDLE, REQUIRED, AVCO-neutral)

**File:** `backend/core/src/main/resources/protocol-registry.json` — data-only changes.

1. **ZKSYNC coverage** for the solver that delivered the ZKSYNC dust
   (`0x91604f590d66ace8975eed6bd16cf55647d1c499`): add `"ZKSYNC"` to its `networks[]`.
   (Optionally mirror on the other GAS_PAYER solvers `0xcad97616…`, `0x7ff8bbf9…` — do
   only if regression sweep shows ZKSYNC receipts from them; not required by evidence.)
2. **Register the ARBITRUM `relay()` settlement receiver** `0x1619de6b6b20ed217a58d00f37b9d47c7663feca`
   as a new entry mirroring the existing solver payouts:
   ```json
   "0x1619de6b6b20ed217a58d00f37b9d47c7663feca": {
     "name": "Relay Solver Payout",
     "protocol": "Relay",
     "version": "Solver",
     "family": "AGGREGATOR",
     "role": "GAS_PAYER",
     "networks": ["ARBITRUM"],
     "confidence": "HIGH",
     "notes": "relay() 0xcdd1b25d settlement receiver; delivers destination-chain funds (WETH unwrap → native ETH). Inbound receipts classify as BRIDGE_IN; no tracked source leg ⇒ market ACQUIRE fallback (AVCO-neutral)."
   }
   ```
   This makes `RelayBridgeClassificationSupport.resolveRelayPayoutInboundEntry` return a
   single candidate (verified: the internal ETH sender to the wallet is exactly this
   address) ⇒ `HeuristicClassifier` (onlyInbound branch) emits `BRIDGE_IN`.

**No code change** is required for the primary NEW-11 items — the `GAS_PAYER→BRIDGE_IN`
promotion path (`HeuristicClassifier` lines ~215–232) already exists and fires once the
registry resolves the sender on the correct network.

**Lower-priority sub-item (OPTIONAL):** BASE dust `0x851437` (`0xf70da978…`, $0.19) does
not promote today (BASE is already in `networks[]`), so it is intercepted **before** the
relay-payout branch — most likely by `SponsoredGasTopUpSupport.findVerifiedSender` or a
reward heuristic. Recommendation: verify with a unit test; if confirmed, either accept as
sub-$1 dust (AVCO-neutral) or move the `resolveRelayPayoutInboundEntry` check ahead of the
sponsored-gas/reward heuristics **for registered Relay solvers only**. Do not expand scope
for $0.19 unless the reviewer requests it.

### C.3 NEW-10 — no code (scope only). See §E.3.

### Allowed dependencies

- `GmxWithdrawalSettlementLinkService` → `MongoOperations`, `NormalizedTransactionRepository`,
  `GmxV2HandlerRegistry` (support), `NormalizedTransaction*` domain. No replay/pricing deps.
  Same dependency shape as `GmxExitSettlementLinkService`.
- No new module boundaries; all changes are inside `application/linking/pipeline/clarification`
  (+ `resources`). Replay and pricing are untouched (reuse only).

---

## D. Data model / Mongo

No schema migration. Collections touched at rebuild time:

| Collection | Change | Index need |
|---|---|---|
| `normalized_transactions` | NEW-09: candidate rows re-typed `LP_EXIT_SETTLEMENT`, `correlationId` set, flow roles reshaped. NEW-11: rows re-typed `BRIDGE_IN`. | Existing compound indexes on `(type, protocolName)`, `(walletAddress, type, correlationId)`, `(type, correlationId)`, `blockTimestamp` cover the new pass. Verify an index supports `{type:LP_EXIT_REQUEST, walletAddress, correlationId, blockTimestamp}`; if absent, add compound `(type, walletAddress, blockTimestamp)`. |
| `asset_ledger_points` | Rebuilt by replay: NEW-09 GLV ETH lot flips ACQUIRE→REALLOCATE_IN; bucket drained. | none |
| `lp_receipt_basis_pools` | unaffected for GLV (GLV uses async lifecycle bucket, not receipt pools) | none |
| `protocol-registry.json` (resource) | NEW-11 data entries | n/a |

**Query cost:** the new pass runs one `find` for candidates (indexed on `type+protocolName`)
plus, per candidate, one bounded `find` over `LP_EXIT_REQUEST` for the wallet within a 600s
window (indexed). GMX withdrawal settlements are rare (single-digit for this wallet), so the
pass is O(candidates) with tiny constant — negligible vs the existing 30+ convergent passes.

---

## E. Data flows

### E.1 NEW-09 end-to-end (anchor `0xf3581f` ↔ `0x806ccd26`)

1. **Normalization:** `0x806ccd26` → `LP_EXIT_REQUEST`, `correlationId=gmx-lp:arbitrum:glv-weth-usdc`
   (already correct). `0xf3581f` → `EXTERNAL_TRANSFER_IN` (no usable selector/logs).
2. **Linking (existing):** `gmxV2RefundClassifier` stamps `0xf3581f` as `GMX V2` +
   `GMX_EXECUTION_FEE_REFUND` (internal senders are GMX handlers).
3. **Linking (NEW pass):** `GmxWithdrawalSettlementLinkService` finds the unique open
   `gmx-lp:arbitrum:glv-weth-usdc` `LP_EXIT_REQUEST` for the wallet, ~4s before the
   settlement; unsettled ⇒ reclassify `0xf3581f` → `LP_EXIT_SETTLEMENT`, set that
   `correlationId`, reshape 3 ETH `BUY` legs → `TRANSFER` (drop market pricing), drop the
   fee-refund reason.
4. **Replay:** router sends `LP_EXIT_SETTLEMENT` → `ASYNC_LP_EXIT_SETTLEMENT` →
   `applyAsyncLpExitSettlement`. The bucket for `gmx-lp:arbitrum:glv-weth-usdc` holds the
   released carry (ETH $3.24 same-asset + GLV $47.23 cross-asset). Returned ETH:
   `takeSameAssetCarry(ETH)` restores $3.24 (REALLOCATE_IN), then remaining bucket cost
   basis ($47.23) is allocated by quantity across the residual ETH (all-same-asset) →
   REALLOCATE_IN. Bucket removed; **no market ACQUIRE**.
5. **Outcome:** the returned ETH lot carries the drained bucket basis (~$47–50, core GLV
   carry $47.23 plus the re-entering $3.24 ETH-fee carry ≈ $50.47) instead of the
   fabricated $58.79. ETH lot basis drops **≈$8–12** (≈$11.56 vs the $47.23 GLV core;
   ≈$8.3 if the $3.24 ETH-fee carry lands on the same lot). Bucket residue = 0 (no
   stranded carry). Exact figure verified post-replay (see AC-2).

**Partial fills / multi-leg / WETH→ETH unwrap:** the async settlement handler already
accepts multiple inbound principal legs and both ETH and USDC (same-asset carry + cross-asset
value/quantity allocation), so a keeper settling WETH-then-unwrap-to-ETH, or returning
ETH+USDC across one or more settlement txs, is handled without new code. Each settlement tx
independently drains its share of the bucket for the shared `correlationId`.

### E.2 NEW-11 end-to-end (anchor `0x99d91f` + ZKSYNC dust)

1. **Normalization:** `HeuristicClassifier` onlyInbound branch calls
   `resolveRelayPayoutInboundEntry`. Before fix: sender `0x1619de6b…` (ARB) unregistered,
   or ZKSYNC solver lookup misses ⇒ `EXTERNAL_TRANSFER_IN`.
2. **After registry fix:** sender resolves to a Relay `GAS_PAYER` on the correct network ⇒
   `BRIDGE_IN` (protocol=Relay).
3. **Linking/terminal:** no source leg on any tracked wallet ⇒ orphan `BRIDGE_IN` →
   `UnmatchedBridgeInboundPricingFallbackService.reconcileOrphanInbounds` demotes to
   market-priced ACQUIRE. **Basis identical** to the current `EXTERNAL_TRANSFER_IN` →
   market ACQUIRE path. AVCO unchanged; only `type`/`counterpartyType`/`protocolName`
   corrected.
4. **Guardrail:** promotion requires an exact registry address match on the delivering
   sender; scam/dust/EOA inbounds without a registered Relay sender remain
   `EXTERNAL_TRANSFER_IN` (no over-correlation, non-regressive to NEW-08).

### E.3 NEW-10 scoping (SCOPE ONLY — recommend split / no-op this cycle)

**Materiality (live DB):**
- Raw events: 342 across the three sub-account integrationIds
  (`BYBIT-516601508`=114, `BYBIT-409666492`=196, `BYBIT-421325298`=32), streams:
  FUNDING_HISTORY, TRANSACTION_LOG, EXECUTION_SPOT, UNIVERSAL_TRANSFER, INTERNAL_TRANSFER,
  EXECUTION_LINEAR (6 — perp/derivative, out of spot-AVCO scope).
- EXECUTION_SPOT trades are small (~$20 notional each; e.g. ETHUSDT Buy 0.0055 @ $3630).
  In-scope ETH/BTC/USDT exposure is minor; SOL/TON/XRP/DOGE terminate at Bybit per spec
  (out of scope).

**Earliest broken stage — re-diagnosed:** The Phase-1 audit's premise ("uid `516601508`
produces ZERO ledger points; 313 events unprocessed") **does not reproduce** in the current
DB. Verified this cycle:
- `asset_ledger_points`: `BYBIT:409666492`=168, `BYBIT:516601508`=94, `BYBIT:421325298`=22
  (+ 2 EARN) = **286 points**.
- `normalized_transactions`: 246 rows reference these uids (spot `SWAP`,
  `EXTERNAL_TRANSFER_IN`, etc.).
- The exact flagged event `BYBIT-516601508:FUNDING_HISTORY:f240349…` **is** normalized
  (`walletAddress=BYBIT:516601508:FUND`, `EXTERNAL_TRANSFER_IN`, CONFIRMED).

The audit's "0 ledger points" was almost certainly a **false negative** from searching by
`txHash ~ /516601508/` (ledger points key the uid in `walletAddress`, not `txHash`), or was
taken against an earlier pre-consolidation snapshot.

**Recommendation:** **Do not** design or ship a NEW-10 fix in this cycle — there is no
reproducible defect and a code change risks regressing already-correct consolidation. If
the reviewer wants closure, open a **separate, LOW-priority PR** whose only task is a
read-only continuity re-audit: confirm every basis-bearing asset moved between main
`33625378` and each sub-account has matched CARRY_IN/CARRY_OUT (no strand/fabrication).
Effort: ~0.5 day (query + reconcile), risk: LOW (read-only). This keeps NEW-10 cleanly
scoped out of the correctness-critical NEW-09/NEW-11 change.

---

## F. Rollout & prod-rebuild mode

Per `.cursor/rules/prod-rebuild-workflow.mdc`, the changes touch **classification/linking**
(NEW-11 registry + NEW-09 reclassification pass) and **AVCO replay** effects (NEW-09
bucket carry). These require full renormalization + replay:

```bash
./scripts/prod-reset-rebuild-backend.sh --skip-frontend
```

- **No** `--clear-pricing-cache`: no pricing-policy change (NEW-11 keeps market fallback;
  NEW-09 uses carried basis from the bucket, not new prices).
- **No** frontend change.
- Do not use `--backend-only` (it skips renormalization, so reclassification/replay effects
  would not materialize).

---

## G. Performance / cost

- New linking pass: 1 indexed candidate query + ≤N bounded per-candidate request queries,
  where N = number of GMX withdrawal fee-refund inbounds (single digits for this wallet;
  bounded even at scale by the rarity of GMX GLV/GM exits). Negligible relative to the
  existing convergent-pass suite; well under the 500 ms slow-pass log threshold.
- Registry additions are O(1) map entries; no lookup-path cost change.
- No new RPC, no new external calls, no snapshot/read-path impact (GET endpoints unaffected).

---

## H. Risks & mitigations

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| H1 | Mislinking an unrelated GMX inflow to the wrong open exit-request | Med | Deterministic guards (§A3): GMX-handler-origin gate, nearest-preceding, single-open-request requirement, 600s window, skip-on-ambiguity. Regression assert (T-09e). |
| H2 | Promoting a genuine execution-fee refund to a settlement | Med | Only reclassify candidates that match an **open** exit-request; standalone refunds (no match) stay `EXTERNAL_TRANSFER_IN` + `GMX_EXECUTION_FEE_REFUND`. Explicit negative test (T-09d). |
| H3 | Reshaping legs to `TRANSFER` but leaving market pricing ⇒ double basis | Med | Null out `unitPriceUsd/valueUsd/priceSource/realisedPnlUsd` on reshaped principal legs; async carry supplies basis. Assert bucket-drained + no ACQUIRE (T-09b). |
| H4 | NEW-11 over-correlation (promote scam/dust/EOA) | Low | Exact-address registry match on the delivering Relay sender only; unregistered senders stay external. NEW-08 over-correlation regression sweep (T-11c). |
| H5 | NEW-11 accidentally changes AVCO | Low | Orphan `BRIDGE_IN` → market ACQUIRE == prior basis; assert byte-identical `avcoAfterUsd`/`totalCostBasisAfterUsd` on all NEW-11 anchors (T-11b). |
| H6 | Pass-ordering: candidates not stamped before the new pass runs | Low | Place new pass immediately after `gmxV2RefundClassifier`; convergent loop re-runs to fixpoint. |
| H7 | Multiple settlement legs / partial fill drains bucket twice | Low | Bucket is per-`correlationId` and decremented atomically; each settlement leg carries only its share; existing async handler already covers multi-leg. Add multi-leg test (T-09f). |
| H8 | NEW-10 "fix" regresses already-correct consolidation | Med | Explicitly **out of scope** this cycle (§E.3). |

---

## Test list (unit + arch + e2e), keyed to anchor txs

### NEW-09 (`GmxWithdrawalSettlementLinkServiceTest` + replay + e2e)
- **T-09a (unit, link):** candidate `0xf3581f` (GMX V2, fee-refund, ETH inbound) + open
  `LP_EXIT_REQUEST` `0x806ccd26` (`gmx-lp:arbitrum:glv-weth-usdc`, ~4s earlier) ⇒
  reclassified `LP_EXIT_SETTLEMENT`, `correlationId=gmx-lp:arbitrum:glv-weth-usdc`, ETH legs
  role `TRANSFER`, no `unitPriceUsd`, `GMX_EXECUTION_FEE_REFUND` removed.
- **T-09b (replay/e2e):** after `--skip-frontend`, `0xf3581f` ETH ledger point(s) are
  `REALLOCATE_IN` (not ACQUIRE), no `EXTERNAL_TRANSFER_IN`/ACQUIRE for this tx, async bucket
  for the corrId fully drained (no stranded carry), returned-ETH lot cost basis ≈ carried
  (~$47–50), **not** $58.79; ETH lot basis materially lower (≈$8–12; assert < $52 and >
  $46). Record exact post-replay figure.
- **T-09c (arch, router):** `ReplayTransactionRouterTest` — `LP_EXIT_SETTLEMENT` with a
  `gmx-lp:` corrId routes to `ASYNC_LP_EXIT_SETTLEMENT`.
- **T-09d (negative):** GMX-handler ETH inbound with **no** matching open exit-request stays
  `EXTERNAL_TRANSFER_IN` + `GMX_EXECUTION_FEE_REFUND` (unchanged).
- **T-09e (guardrail):** two open `gmx-lp:*` exit-requests in-window, indistinct market ⇒
  settlement left unlinked (skip), no mislink.
- **T-09f (multi-leg):** synthetic settlement returning ETH **and** USDC for one corrId ⇒
  both carry from the bucket (same-asset + cross-asset), bucket drained, no ACQUIRE.
- **T-09g (regression, GMX entry):** existing `0x9800006e`/`0x9fab1650` entry lifecycle and
  `0x806ccd26` REALLOCATE_OUT are unchanged (no regression to GMX entry/exit-request).

### NEW-11 (`OnChainClassifierTest` / `HeuristicClassifier` + registry + e2e)
- **T-11a (classification):** `0x99d91f` (ARB, internal ETH from `0x1619de6b…`) ⇒
  `BRIDGE_IN`, `protocolName=Relay`, `counterpartyType=BRIDGE`. ZKSYNC dust
  (`0xb5e18d…/0xeb4c98…/0xa509b7…/0xe4d4e0…/0xd4e5bc…`, sender `0x91604f59…`) ⇒ `BRIDGE_IN`.
- **T-11b (AVCO-neutral, e2e):** after rebuild, each NEW-11 anchor is `BRIDGE_IN` yet the
  ETH `asset_ledger_points` basis (`costBasisDeltaUsd`, `avcoAfterUsd`,
  `totalCostBasisAfterUsd`) is **identical** to the pre-fix market-ACQUIRE values;
  no shortfall introduced.
- **T-11c (over-correlation regression):** NEW-08 anchors intact (KATANA/UNICHAIN cross-asset
  bridge still correlated); scam SELLs / scam airdrops / big unregistered stablecoin inbounds
  (`0x7f6ccd24…`, `0x50679186…`) stay `EXTERNAL_TRANSFER_IN`; LiFi relayer dust stays external.
- **T-11d (registry):** `ProtocolRegistryLoaderTest` loads the new `0x1619de6b…` entry and
  the ZKSYNC-augmented `0x91604f59…` `networks[]` without error.

### Same-asset & scam paths (both defects)
- **T-X1:** same-asset GMX/bridge carries (BRIDGE_IN CARRY_IN, GMX entry REALLOCATE)
  unchanged vs baseline distribution.
- **T-X2:** address-poisoning / spoof-token inbounds remain excluded/external (no promotion).

---

## Layer ranking (value / risk / required)

| Rank | Layer | Value | Risk | Required? |
|---|---|---|---|---|
| 1 | NEW-09 `GmxWithdrawalSettlementLinkService` + `LinkingBatchProcessor` wire-up | **High** (fixes the only genuine basis-fabrication defect; systemic for all GMX GLV/GM 2-step withdrawals) | Med (mitigated by §A3 guards) | **YES — REQUIRED** |
| 2 | NEW-11 registry: register `0x1619de6b…` + ZKSYNC on `0x91604f59…` | Med (hygiene; $1,569 visible, AVCO-neutral) | Low | **YES — REQUIRED** |
| 3 | NEW-09 registry hardening (`0x0910f3f9` + functionName markers) | Low (future-proofing; does not fix `0xf3581f`) | Low | Recommended, not sufficient alone |
| 4 | NEW-11 BASE-dust GAS_PAYER ordering (`0x851437`, $0.19) | Very low | Low | OPTIONAL (verify-only) |
| 5 | NEW-10 sub-account continuity re-audit | Low (not reproducible) | Low (read-only) | **Split to separate PR / out of scope** |

---

## Ordered task list (backend engineer)

1. **Add** `GmxWithdrawalSettlementLinkService` in
   `application/linking/pipeline/clarification` (candidate query, deterministic
   open-exit-request matcher with the §A3 guards, reclassify+reshape method). Mirror
   structure/style of `GmxExitSettlementLinkService` and `GmxEntryRequestLinkService`.
2. **Wire** it into `LinkingBatchProcessor` (constructor field + `timedPass` immediately
   after `gmxV2RefundClassifier`).
3. **Optional hardening:** add `0x0910f3f9` + `executeglvwithdrawal`/`executewithdrawal`
   markers to `protocols/gmx-v2.json` and consult the functionName group in
   `GmxProtocolSemanticClassifier.isGmxWithdrawalSettlement`.
4. **Edit** `protocol-registry.json`: add the `0x1619de6b…` Relay GAS_PAYER (ARBITRUM)
   entry; add `"ZKSYNC"` to `0x91604f590d66ace8975eed6bd16cf55647d1c499` `networks[]`.
5. **Verify** an index supports the exit-request matcher query
   `(type, walletAddress, blockTimestamp)`; add compound index if missing.
6. **Write tests** T-09a…g, T-11a…d, T-X1/X2 (see test list).
7. **Rebuild:** `./scripts/prod-reset-rebuild-backend.sh --skip-frontend`.
8. **Verify in Mongo:** NEW-09 acceptance (T-09b), NEW-11 AVCO-neutrality (T-11b),
   NEW-08/scam non-regression (T-11c), UNKNOWN/ACQUIRE distribution deltas bounded to the
   intended rows only.
9. **Do NOT** implement NEW-10; if requested, open a separate read-only continuity-audit PR
   (§E.3).
10. **Docs:** note the GMX GLV/GM keeper-settlement pairing rule in
    `docs/pipeline/normalization/rules/protocols/gmx-v2.md` and the Relay coverage entries
    in the linking rules doc; update `results/blockers.md` terminal states after verification.

### Acceptance summary
- **AC-1 (NEW-09):** `0xf3581f` = `LP_EXIT_SETTLEMENT`, `correlationId=gmx-lp:arbitrum:glv-weth-usdc`,
  ETH `REALLOCATE_IN` (no ACQUIRE), async bucket drained to 0.
- **AC-2 (NEW-09):** returned-ETH lot cost basis ≈ carried bucket value (~$47–50), **not**
  $58.79 — ETH basis ≈$8–12 lower; no stranded carry; GMX entry/exit-request rows unchanged.
- **AC-3 (NEW-11):** all Relay inbound anchors = `BRIDGE_IN` with Relay counterparty, and
  identical AVCO/basis vs pre-fix (market ACQUIRE fallback preserved).
- **AC-4 (non-regression):** NEW-01/NEW-02/NEW-08/BLOCKER-LP-MINT intact; scam/dust/EOA and
  same-asset carries unchanged.
- **AC-5 (NEW-10):** no code change; scoping recorded; optional split-PR recommendation.
```
