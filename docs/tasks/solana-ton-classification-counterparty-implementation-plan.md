# Solana / TON Classification & Counterparty — Implementation Plan

Status: **DRAFT v2 — revised after Phase 3 review; awaiting user approval (stop gate)**
Session: `df5e69cc-a0c0-4910-8b7d-74488fa266e2`
Audit basis: `results/solana-ton-blockers.md`, `results/solana-ton-accounting-failure-analysis.md`, `results/solana-ton-required-changes.md`
Phase 3 reviews: financial-logic-auditor, business-analyst, system-architect — all **REVISE**; this v2 incorporates their required changes.
Related: `docs/tasks/external-capital-inflow-acquisition-label-implementation-plan.md`, ADR-009, ADR-010, ADR-014, ADR-063, ADR-064, ADR-065.

---

## 1. Scope

- **Networks:** SOLANA, TON only. **EVM must not change behaviour (byte-for-byte).**
- **Wallets:**
  - SOLANA `9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG` (600 raw)
  - TON `UQAe4Uho4bZdfmCEqiyyuUH8ujmrsGJOwE2124OBDMVbS1Ms`, `UQDcaquhb07rH_df1iy56EF5WUE_XGmq4KhNLPs-m7v9o37O` (117 raw)
- **Blocker IDs:** S1 (495), S2 (185), S3 (~17), S4 (3), S5 (~310 correctness), **S-LP** (LP/lending/vault basis continuity), T1 (117 correctness), T2 (safety net).
- **Explicitly out of scope (bounded):** deep TON DeFi protocol accounting on the "Ton Defi" wallet. **Constraint:** out-of-scope TON DeFi transactions must surface as visible `UNSUPPORTED_SCOPE`/`NEEDS_REVIEW` (never silently confirmed as empty/transfer), and the UI must show a partial-coverage flag for TON. EVM re-audit is out of scope.

## 2. Root cause (Phase 1 audit, code-verified)

The AVCO replay gate (`avcoReady=false`, `blockingNeedsReview≈498`) is **100% a Solana problem**; TON contributes 0 to the gate but silently loses all on-chain value.

- **Solana (gate blocker):** classification and pricing already work. Protocol + counterparty enrichment runs inline only in EVM `OnChainNormalizationService.normalize()` (`enrichCanonicalMetadata`, lines 131/142-156). `SolanaNormalizationService.normalize()` (verified, line 88) calls `builder.build(...)` + `markComplete` and **never enriches** → `counterpartyType` null → `StatValidationService` flags `STAT_COUNTERPARTY_TYPE_MISSING` → `NEEDS_REVIEW` → gate closed. Secondary: `CounterpartyResolutionService` hard-wraps raw in EVM `OnChainRawTransactionView` (line 58); `normalizeAddress` forces `0x…`/42-char; `CounterpartyEnrichmentService.loadRaw` lowercases the case-sensitive base58 key; the **registry loader** also keys via `normalizeAddress` — all EVM-only, cannot read Helius payloads.
- **TON (silent data loss):** `TonNormalizedTransactionBuilder.classify`/`buildNativeTonFlow`/`buildJettonFlow`/`isOwnAddress` compare stored user-friendly wallet (`UQ…`) against TON Center raw form (`0:hex`) via `equalsIgnoreCase` (lines 107-108, 135, 143, 214, 233, 251) → never match → `type=UNKNOWN`, fee-only flow, promoted to `CONFIRMED` as "replay-safe". `TonAddressCanonicalizer` (domain) already exists and is **already wired into `AccountingUniverseService` membership** (lines 198-202) — only the normalizer bypasses it.

## 3. Architecture decisions (from Phase 3 architect review)

1. **Per-`NetworkFamily` `CounterpartyResolver` SPI** — do NOT scatter `switch(NetworkId)` across the six shared services. `CounterpartyEnrichmentService.enrichInPlace` selects a resolver by family:
   - `EvmCounterpartyResolver` — current logic moved **verbatim** (zero EVM regression).
   - `SolanaCounterpartyResolver` — `SolanaRawTransactionView`: `instructions[].programId` → PROTOCOL; `nativeTransfers`/`tokenTransfers` peer → `AccountingUniverseService.classify`.
   - `TonCounterpartyResolver` — `TonRawTransactionView` + `TonAddressCanonicalizer`.
2. **Family-aware `AddressNormalizer`** for the protocol registry — both `ProtocolRegistryLoader` (key-building at load) **and** `ProtocolRegistryService.lookup` must pick the normalizer by the entry's declared `networks`. Guarding `normalizeAddress` "EVM-only" is safe only once the registry key path stops using it for Solana entries. Require `networks: [SOLANA]` on program-ID entries; reject mixed EVM+Solana entries.
3. **No blanket mirror of `enrichCanonicalMetadata`** — extract a shared `CanonicalMetadataEnricher` with **per-family step opt-in**. Solana/TON run protocol-name + counterparty only; EVM receipt-shaped steps (`registryBridgeInboundTypeCorrectionService`, `enrichProtocolFromReceiptIdentity`, `lendingReceiptIdentityService`) do not run on Helius/TON payloads until proven relevant.
4. **`FlowCounterpartySupport`**: keep the view-independent statics shared (`applyTransactionCounterparty`, `syncFlowsFromTransaction`, `flowsMissingCounterparty`, `onChainAccountRef`); add a **parallel Solana peer path** rather than switching inside the EVM-view-bound `enrichOnChainFlows`/`resolvePeerForFlow`.
5. **Jetton decimals**: resolve at normalization (background) time only — never on a read path. Cache (Caffeine + persisted `token-metadata.json`) keyed by jetton master; **seed USDT-TON master in config** (`EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs` = 6). No hardcoded address literals in production logic (address may be a test regression anchor only).
6. **RC-T2 replay-safe guard**: gate the stricter rule on the specific evidence condition ("raw had non-zero value but produced only a FEE flow"); add an EVM-fixture regression assertion so EVM promotion is provably unchanged.
7. **ADR-066** (new): "Per-family counterparty resolution SPI + program-ID registry keying" — amends ADR-009 (ownership via universe) and ADR-063 (Solana Helius normalization). RC-T2 is an amendment note against ADR-014 (conservation gate), not a standalone ADR.

## 4. Changes (ordered — upstream stages first; gate-opening items grouped)

> Hard gate: no code before this plan + doc updates (§5) are approved.

### Phase A / PR1 — Open the gate correctly (Solana counterparty + flow completeness + direction)

> Correction from review: RC-S3, RC-S4 and RC-S5 are gate-affecting and MUST ship together with A. `FLOW_COUNTERPARTY_MISSING` (185) and `STAT_SWAP_MISSING_BUY_LEG/SELL_LEG` (~17) are `StatValidationService` reasons → they keep rows `NEEDS_REVIEW`; RC-S5 must land before the first replay or ~310 native-SOL transfers book with wrong direction.

1. **RC-S2a — Solana program-ID registry.** Add Solana program IDs (RP-S1 list) to `protocol-registry.json` with `networks: [SOLANA]`; add a family-aware `AddressNormalizer`; fix **both** `ProtocolRegistryLoader` key-building and `ProtocolRegistryService.lookup` to accept program-ID keys.
2. **RC-S2b — `SolanaCounterpartyResolver` (SPI).** program-ID → PROTOCOL+protocolName; transfer peer → `AccountingUniverseService.classify` → PERSONAL_WALLET/CEX/UNKNOWN_EOA; case-sensitive base58 preserved. Register behind the family SPI (§3.1); move EVM logic verbatim into `EvmCounterpartyResolver`.
3. **RC-S1 — Wire enrichment into `SolanaNormalizationService`** via the shared `CanonicalMetadataEnricher` with Solana step opt-in (protocol-name + counterparty only).
4. **RC-S3 — Flow-level counterparty on protocol rows** (`FLOW_COUNTERPARTY_MISSING`, 185): set each non-fee `flow.counterpartyAddress` (protocol program/pool or back-fill via shared `applyTransactionCounterparty`) using the new Solana peer path.
5. **RC-S4 — Complete swap legs** (~17): prefer `events.swap`; else reconstruct net per-mint wallet deltas from `accountData[].tokenBalanceChanges` (owner==wallet) + native SOL delta; emit exactly one SELL + one BUY. Handle multi-hop swaps by netting per mint.
6. **RC-S5 — Transfer direction + dust + external-capital (fully specified):**
   - **Direction:** net wallet delta across `nativeTransfers`+`tokenTransfers` → IN (positive) / OUT (negative) / INTERNAL (self→self). Replaces hardcoded `TRANSFER → EXTERNAL_TRANSFER_IN`.
   - **Mixed in+out in one tx:** pick type by net delta of the primary asset; each leg keeps its own sign; the boundary label attaches only to a genuinely inbound-from-external leg.
   - **Inbound taxonomy:** (a) from own/CEX → carry/continuity (no INFLOW stamp); (b) from unknown-external → external-capital `INFLOW` (ACQUIRE at market); (c) dust/scam → ignore economic leg (never drop the fee leg).
   - **On-chain INFLOW stamping mechanism:** add an on-chain boundary stamper analogous to `CexBoundaryContractStamper` so `ReplayDispatcher.resolveExternalCapitalInflowAcquisition` can relabel; **guarantee outbound/internal transfers are never stamped INFLOW.**
   - **Dust threshold:** explicit value per asset class (native SOL vs SPL); documented in the rule doc; must not strip the fee leg.
7. **RC-S6 — Resolve 3 `SOLANA_UNCLASSIFIED`:** inspect residual program IDs; add to registry if known, else drive to proven `UNSUPPORTED_SCOPE`/`IRREDUCIBLE_REMAINDER`.

### Phase B / PR2 — Solana protocol-flow basis correctness (NEW cluster S-LP)

> Added per financial-auditor: counterparty enrichment opens the gate but does NOT make LP/lending/vault **basis** correct.

8. **RC-S-LP — LP / lending / vault move-basis continuity.**
   - **Meteora DLMM** positions are NFT-based (often no fungible LP token). Ensure LP_ENTRY is a non-disposal basis-carrying move (not a bare disposal) and LP_EXIT returns basis with entry↔exit continuity, or explicitly scope specific shapes to `UNSUPPORTED_SCOPE` with proof.
   - **Lending/vault** deposit = non-disposal move carrying basis; withdraw = basis return — not a fresh market acquisition/disposal.
   - Files: `SolanaNormalizedTransactionBuilder` LP/lending/vault builders, replay/move-basis handling for Solana, LP position continuity.

### Phase C / PR3 — TON correctness (non-gating)

9. **RC-T1 — TON address canonicalization + jetton decimals.**
   - Replace all direct `equalsIgnoreCase(walletAddress,…)` in `TonNormalizedTransactionBuilder` with `TonAddressCanonicalizer.lookupKeys` comparisons (canonical `0:hex`) for classify / native / jetton / `isOwnAddress`.
   - **Confirm jetton `source`/`destination` are OWNER addresses, not jetton-wallet contract addresses** (from raw evidence). If they are jetton-wallet addresses, add a jetton-wallet→owner mapping (RP-T1).
   - Jetton decimals/symbol from jetton master via cached RPC (normalization-time only); seed USDT-TON = 6 with a resulting-quantity acceptance check.
   - Confirm TON/jetton inbound legs are priced and get an ACQUIRE/external-capital label; add `TonCounterpartyResolver` via the family SPI; wire enrichment into `TonNormalizationService`.
10. **RC-T2 — Guard `promoteReplaySafeNeedsReview` (review-only, scoped).** Do not treat an on-chain `UNKNOWN` row with non-zero raw value but only a fee flow as replay-safe; add EVM-fixture regression assertion; must not weaken EVM.

## 5. Documentation updates (before code, Phase 4)

- **ADR-066** (new, requires explicit user approval): per-family `CounterpartyResolver` SPI + family-aware program-ID registry keying; amends ADR-009, ADR-063. RC-T2 note appended to ADR-014.
- `docs/pipeline/normalization/rules/` — Solana program-ID → protocol/counterparty rule package; Solana transfer direction/dust/external-capital rule; TON canonical-equality + native/jetton transfer rules; jetton decimals source (USDT-TON=6).
- `docs/pipeline/cost-basis/` — Solana counterparty as a stat prerequisite; Solana LP/lending/vault move-basis continuity; TON native/jetton ledger points.
- `docs/reference/` — Solana program IDs, TON canonical address forms, USDT-TON decimals, TON partial-coverage note.

## 6. Acceptance criteria (measurable / testable DoD)

**Structural (gate):**
- SOLANA `STAT_COUNTERPARTY_TYPE_MISSING` = **0**, `FLOW_COUNTERPARTY_MISSING` = **0**, `STAT_SWAP_MISSING_BUY_LEG` = **0**, `STAT_SWAP_MISSING_SELL_LEG` = **0**, `SOLANA_UNCLASSIFIED` = **0** (resolved or proven-unsupported).
- `avcoReady=true`, `blockingNeedsReview=0`, replay produces `assetLedgerPoints>0`.
- No Solana row `counterpartyType=null` unless proven irreducible.

**AVCO correctness (beyond gate):**
- Solana native-SOL / SPL transfer legs priced → `STAT_FLOW_PRICE_MISSING=0`; each transfer produces a real `basisEffect` (ACQUIRE/DISPOSE) with correct direction.
- Solana LP/lending/vault rows: deposit/withdraw carry basis (no phantom disposal/acquisition); LP entry↔exit continuity holds, or shape proven `UNSUPPORTED_SCOPE`.
- **Numeric reconciliation:** closing SOL/token/TON/jetton quantities reconcile against on-chain balances and the auditor's authoritative reconstruction within tolerance.

**TON:**
- On-chain `UNKNOWN` (real transfers) = **0**; native TON + jetton in/out present as ledger points with correct decimals (USDT-TON quantity check) and counterparties.
- No falsely-`CONFIRMED` empty rows with dropped value; out-of-scope TON DeFi surfaces as `UNSUPPORTED_SCOPE`/`NEEDS_REVIEW` + UI partial-coverage flag.

**No-regression:**
- EVM STAT/counterparty/replay-safe behaviour unchanged (existing EVM tests + explicit EVM fixtures green).

**Sign-off:** `financial-logic-auditor` re-run reconciles DB output vs authoritative reconstruction for SOL/TON wallets.

## 7. Edge cases (from business-analyst review)

- Failed / bounced transactions (Solana `transactionError`; TON `exit_code≠0`, bounces) → not economic transfers.
- Scam / dust SPL tokens and jetton dust → filtered, fee leg preserved.
- Missing jetton metadata → deterministic fallback (no silent 9-decimals error for known masters).
- Self-transfers between own wallets → INTERNAL (both Solana and TON).
- External-capital split for the ~310 Solana transfers (own/CEX vs unknown-external vs dust).
- Multi-hop / aggregator swaps → net per-mint into one BUY + one SELL.

## 8. Risks & sequencing

- **Ordering:** A2 (resolver) before A1 (wiring); RC-S3/S4/S5 in the gate batch (PR1) before first replay; B (basis continuity) then C (TON) last.
- **EVM regression:** per-family SPI + verbatim `EvmCounterpartyResolver` isolates risk; loader/lookup normalizer must stay EVM behaviour for EVM entries; RC-T2 scoped + EVM fixtures.
- **Registry completeness:** unmapped program IDs → `UNKNOWN_EOA`/unclassified (not wrong protocol); RC-S6 handles residuals.
- **Cost:** only jetton-decimals may need RPC — normalization-time + cached + config-seeded; all other rules zero-RPC and deterministic on rerun.
- **Rebuild:** `./scripts/prod-reset-rebuild-backend.sh --skip-frontend` (pricing cache valid; add `--clear-pricing-cache` only if TON/jetton pricing gaps appear after RC-T1).

## 9. Implementation batching (for `backend-dev`, post-approval)

- **PR1 (gate):** RC-S2a, RC-S2b (SPI + `EvmCounterpartyResolver` verbatim), RC-S1, RC-S3, RC-S4, RC-S5, RC-S6 → verify full gate-open + correct direction, no EVM regression.
- **PR2 (basis):** RC-S-LP → verify LP/lending/vault move-basis continuity + numeric reconciliation.
- **PR3 (TON):** RC-T1, RC-T2 → verify TON/jetton ledger points, decimals, partial-coverage flag, EVM replay-safe unchanged.
