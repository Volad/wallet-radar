# ADR-013 — CEX Cross-System Linking via FA-001 (no hot-wallet registry)

**Status:** Proposed
**Date:** 2026-05-16
**Inputs:** `cycle-autorun/cycle-data/cycle/5/results/n19-defect-catalog.md` (D-BYBIT-HOT-WALLET), `n19-implementation-plan.md` §A (hot wallets seed — superseded), §G; user decisions 2026-05-16 (no `bybit-hot-wallets.json`; FA-001 / `txHash` is the source of truth for chain ↔ CEX linkage; failed links manifest as a pool imbalance and fire the conservation gate)

> Supersedes (and renames) `ADR-013-bybit-hot-wallet-registry.md`. The proposed `bybit-hot-wallets.json` resource file and `OWN_BYBIT_BRIDGE` enum value are dropped. Bybit hot wallets are not OWN — they are EXTERNAL addresses on the chain side, deliberately. Cross-system custody continuity is established via the FA-001 corridor of [ADR-003](ADR-003-transfer-links-fa001.md), keyed on the on-chain `txHash` already present on both the chain row and the Bybit deposit / withdrawal record.

## Context

Bybit operates a small set of deterministic hot wallet addresses per network (`ETHEREUM`, `ARBITRUM`, `MANTLE`, `AVALANCHE`, `SOLANA`, `TON`). Every on-chain deposit from a user's EVM / Solana / TON wallet to Bybit lands on one of these addresses; every withdrawal from Bybit originates from one. The same value transfer therefore appears twice in WalletRadar's view:

1. **On-chain side**: user EVM wallet → Bybit hot wallet → today classified as `EXTERNAL_TRANSFER_OUT` (capital flight).
2. **Bybit side**: `bybit-deposit-onchain` row → credits the master UID FUND ledger as `EXTERNAL_TRANSFER_IN` (capital injection per [ADR-006 §N17](ADR-006-cycle5-bybit-stream-authority-and-earn-subaccount.md)).

If both legs are treated as EXTERNAL, NEC double-counts (D-BYBIT-HOT-WALLET, estimated $500–$1,000).

### Why not promote hot wallets to OWN?

A prior draft of this ADR proposed maintaining a `bybit-hot-wallets.json` resource file and admitting those addresses into `AccountingUniverse` as `EXCHANGE_BRIDGE_WALLET` / `OWN_BYBIT_BRIDGE`. User decision 2026-05-16 rejected this approach:

- **Hot wallets are not the user's accounts.** They are Bybit's infrastructure, shared across all users. Admitting them as OWN inverts the meaning of the `AccountingUniverse` collection (which is supposed to enumerate the user's own custodial endpoints).
- **A static list is a maintenance liability.** Bybit can and does rotate hot wallets; new chains require new entries; the file becomes dataset-specific production logic keyed by real addresses (forbidden by the System-Architect cost-first guardrail).
- **The information already exists in the source data.** Every Bybit `deposit-record` row carries the chain-side `txHash`. Every chain-side transfer to the hot wallet carries the same `txHash`. The cross-system identity is mechanically derivable, not configuration.

The correct mechanism is the FA-001 corridor of [ADR-003](ADR-003-transfer-links-fa001.md): persist `txHash` on both sides, link via the `transfer_links` collection, downgrade the on-chain leg from priced `EXTERNAL_TRANSFER_*` to `INTERNAL_TRANSFER` post-link, and let cost-basis replay treat the linked pair as a single same-owner custody move.

### How this composes with the per-counterparty basis pool

When FA-001 succeeds, the chain leg becomes `INTERNAL_TRANSFER` and basis flows along the per-counterparty pool just like any other OWN ↔ OWN move (the counterparty here is `BYBIT:<masterUid>` on the chain side, the user's EVM wallet on the Bybit side).

When FA-001 fails (missing `txHash`, mismatch on quantity / timing, namespace mismatch on Solana / TON), the chain leg remains `EXTERNAL_TRANSFER_OUT` with `counterpartyType = UNKNOWN_EOA` (the hot wallet has EOA shape) and the Bybit leg remains `EXTERNAL_TRANSFER_IN`. Both push into their respective per-counterparty pools ([ADR-015](ADR-015-per-counterparty-basis-pool.md)):

- Chain pool keyed on the hot wallet address: lifetimeOut grows, lifetimeIn does not.
- Bybit pool keyed on the user's EVM wallet (the `fromAddress` on the Bybit deposit row): lifetimeIn grows, lifetimeOut does not.

These two pool imbalances are the self-diagnostic signature of a failed cross-link. The conservation gate ([ADR-014](ADR-014-portfolio-conservation-gate.md)) detects the resulting NEC inflation and surfaces the imbalance on the dashboard. There is no silent miscount: the gate fires.

This is the design intent. The system does NOT need a hardcoded registry to be correct in the happy path, and it does not silently degrade in the unhappy path.

## Decision

### D1. No hot-wallet registry, no new enum values

- No `backend/src/main/resources/bybit-hot-wallets.json`.
- No `BybitHotWalletRegistry` bean.
- No `EXCHANGE_BRIDGE_WALLET` `MemberType`.
- No `OWN_BYBIT_BRIDGE` `CounterpartyType`.

Bybit hot wallets are classified by the standard chain-side classifier: they are not members of `AccountingUniverse`, they have EOA shape, no protocol-registry hit, so they resolve to `CounterpartyType.UNKNOWN_EOA` (per [ADR-009](ADR-009-ownership-classification-via-universe.md) §D2 step 5).

### D2. `txHash` is mandatory on both sides

For the FA-001 link to be deterministic, the on-chain `txHash` MUST be present on every Bybit-side row that anchors a chain-bridge flow:

| Source | Field | Status |
|---|---|---|
| On-chain extractor → `NormalizedTransaction` (chain leg) | `txHash` | Already persisted (top-level). |
| Bybit `funding-history` Deposit / Withdrawal anchor row (per [ADR-006 §N17](ADR-006-cycle5-bybit-stream-authority-and-earn-subaccount.md)) | `txHash` | MUST be persisted on the canonical row by [BybitCanonicalTransactionBuilder](backend/src/main/java/com/walletradar/ingestion/pipeline/bybit/BybitCanonicalTransactionBuilder.java). Source: `txID` field on `/v5/asset/deposit/query-record` / `/v5/asset/withdraw/query-record`. |
| `bybit-deposit-onchain` extraction stream | `txHash` | Already present (raw Bybit `txID`). |

Implementation requirement: any Bybit canonical row whose `bybitOriginalType ∈ {Deposit, Withdraw}` and whose source supplies a `txID` MUST carry that hash on the persisted `NormalizedTransaction`. A row missing `txHash` on either side cannot participate in the FA-001 link and falls through to the pool-imbalance / conservation-gate path of D3.

For Solana and TON, the on-chain identifier namespace differs (signature, not `0x…64hex`). The link operates on whatever string the source provides; the chain normalizer and the Bybit normalizer MUST agree on namespace per network. The `transfer_links` collection key is `(network, chainSideTxHash, bybitSideTxHash)` — exact string equality after lowercasing for EVM, raw for non-EVM.

### D3. Linking mechanism: FA-001 / `transfer_links` (per ADR-003)

[ADR-003](ADR-003-transfer-links-fa001.md) defines the `transfer_links` collection and the corridor D2 contract: post-classification, the linker pairs a chain leg with a Bybit anchor when `txHash` matches and quantity / timing are within tolerance, then strips priced fields from the chain leg and rewrites it as `INTERNAL_TRANSFER` with `counterpartyType = CEX` (the Bybit master is a `CEX` member per [ADR-009](ADR-009-ownership-classification-via-universe.md) §D2).

Steps codified here:

1. Chain extractor produces a `NormalizedTransaction` with `txHash`, `counterpartyAddress = <hot wallet addr>`, `counterpartyType = UNKNOWN_EOA` (chain-side classifier did its best with the data available).
2. Bybit normalizer produces the anchor row with `txHash`, `counterpartyAddress = <user's EVM wallet>`, `counterpartyType = PERSONAL_WALLET` (the user's wallet IS in `AccountingUniverse`).
3. FA-001 link service ([BybitTransferContinuityRepairService](backend/src/main/java/com/walletradar/ingestion/pipeline/clarification/BybitTransferContinuityRepairService.java) → [ADR-003](ADR-003-transfer-links-fa001.md) D2) finds the match, writes the `transfer_links` row, and rewrites the chain leg:
   - `type = INTERNAL_TRANSFER`
   - `counterpartyAddress = BYBIT:<masterUid>` (canonical Bybit ref)
   - `counterpartyType = CEX`
   - priced fields cleared (no Realised PnL on the chain leg)
4. Cost-basis replay reads the linked pair and treats it as a single OWN ↔ OWN move; basis carries via the per-counterparty basis pool ([ADR-015](ADR-015-per-counterparty-basis-pool.md)).

### D4. Failure mode: pool imbalance + conservation gate

When FA-001 cannot link (no `txHash` on one side, qty mismatch beyond tolerance, namespace mismatch), the two legs remain separately recorded:

| Leg | `counterpartyAddress` (recorded on `Flow`) | `counterpartyType` | Pool key |
|---|---|---|---|
| Chain leg (OWN EVM → hot wallet) | hot wallet address (e.g. `0x2ea8cb6f…`) | `UNKNOWN_EOA` | `(0x2ea8cb6f…, ETHEREUM, STABLE_USD)` |
| Bybit leg (deposit credited to master UID) | user's EVM wallet address (`0x1a87…`, from `fromAddress` on Bybit deposit-record) | `PERSONAL_WALLET` | `(0x1a87…, BYBIT, STABLE_USD)` |

Each side pushes into its own pool. The chain pool's `lifetimeOut` grows; the Bybit pool's `lifetimeIn` grows; neither pool sees the corresponding pop. The NEC computation of [ADR-009](ADR-009-ownership-classification-via-universe.md) §D4 sees TWO non-OWN entries (the chain pool for the hot wallet, the Bybit pool for the user's own EVM wallet — but the Bybit pool's counterparty IS in the universe, so it doesn't contribute to NEC); the chain pool contributes a phantom NEC delta equal to the failed-link USD value.

The conservation gate ([ADR-014](ADR-014-portfolio-conservation-gate.md)) then flags `conservationBreached = true` with delta ≥ unified threshold (`max($50, 1% of MtM)`). The dashboard surfaces the breach and the operator can investigate which `(txHash, chain)` pair failed.

This is the design: **failed cross-system linking is loud, not silent.**

### D5. Persistence requirement on both normalizers

Hard contract for cycle/5 sprints (this ADR is the requirement; implementation lives in the linked sources):

| Component | Required field | Source |
|---|---|---|
| [BybitCanonicalTransactionBuilder](backend/src/main/java/com/walletradar/ingestion/pipeline/bybit/BybitCanonicalTransactionBuilder.java) | `txHash` on every Deposit/Withdraw canonical row | `funding-history.txID` for FH-anchor rows; `bybit-deposit-onchain.txID` / `bybit-withdraw.txID` for OnChain-anchor rows |
| [OnChainNormalizationService](backend/src/main/java/com/walletradar/ingestion/job/normalization/OnChainNormalizationService.java) | `txHash` on every `NormalizedTransaction` (already present for EVM; verify for Solana / TON when those normalizers exist) | RPC / explorer payload |
| `transfer_links` writer (per [ADR-003](ADR-003-transfer-links-fa001.md)) | row keyed by `(network, chainSideTxHash, bybitSideTxHash)` with idempotent upsert | runtime linker |

If either normalizer cannot supply `txHash`, the canonical row carries `missingDataReasons += "TX_HASH_MISSING_BYBIT_CORRIDOR"` and the FA-001 link skips it. The flow is then handled by the pool-imbalance path (D4) which surfaces via the conservation gate.

### D6. Operational principle: rely on cross-system identity, not infrastructure inventory

The system MUST NOT maintain or auto-discover a list of Bybit hot wallets. The `transfer_links` corridor is the canonical mechanism; if a future Bybit chain doesn't supply `txHash` on deposits/withdrawals, the gap is upstream (Bybit API) and the conservation gate makes it visible. Adding addresses to a maintained list is forbidden — it re-creates the dataset-specific production-logic anti-pattern the System-Architect guardrail prohibits.

The only valid response to a recurring failed-link pattern is: improve the FA-001 linker (e.g. fuzzy match on `(timestamp ± 5m, qty, asset)` when `txHash` is missing) or fix the upstream extraction (`bybit-deposit-onchain.txID` already covers what's needed).

## Consequences

### Positive
- D-BYBIT-HOT-WALLET closes when FA-001 succeeds (the happy path); the basis flows correctly via the standard OWN ↔ OWN linked-transfer mechanism.
- When FA-001 fails, the failure is loud (conservation gate fires), not silent (silent NEC double-counting).
- No new resource files, no new enum values, no new in-memory registry beans. Zero ongoing maintenance burden as Bybit infrastructure evolves.
- Pattern generalises to other CEX integrations (Binance, OKX) without code changes — same `transfer_links` corridor, same `txHash` join key.
- Removes the "OWN_BYBIT_BRIDGE" conceptual oddity: hot wallets are correctly classified as EXTERNAL on the chain side (because they are not the user's).

### Negative
- The FA-001 corridor is the single point of correctness. If it has a bug (mis-matched pair, missing tolerance), the chain leg stays priced as `EXTERNAL_TRANSFER_OUT` and the Bybit leg stays priced as `EXTERNAL_TRANSFER_IN` — both contribute to NEC twice. Mitigated by: (a) the conservation gate of [ADR-014](ADR-014-portfolio-conservation-gate.md); (b) regression tests asserting linkage rate.
- Solana / TON cross-links require namespace-aware `txHash` matching. The existing FA-001 linker only handles EVM hashes; Solana signatures and TON message hashes need adapter coverage. Open work item for the sprint that lands Solana / TON ingestion (today: both have `backfillEnabled=false`, so this is deferred but tracked).
- The "$500–$1,000 D-BYBIT-HOT-WALLET impact" depends on FA-001 success rate. The conservation gate's threshold (`max($50, 1% MtM)`) must be honoured; routinely breached gate = operator must investigate, not be ignored.

### Migration
1. Verify `BybitCanonicalTransactionBuilder` writes `txHash` on every Deposit/Withdraw canonical row. Backfill missing `txHash` on existing canonical rows by re-extracting from `bybit_extracted_events` (raw rows already carry `txID`).
2. Verify [BybitTransferContinuityRepairService](backend/src/main/java/com/walletradar/ingestion/pipeline/clarification/BybitTransferContinuityRepairService.java) (or whichever component implements the FA-001 D2 link per [ADR-003](ADR-003-transfer-links-fa001.md)) actually pairs by `txHash` and writes the `transfer_links` row.
3. Remove any in-flight references to `bybit-hot-wallets.json`, `BybitHotWalletRegistry`, `EXCHANGE_BRIDGE_WALLET`, `OWN_BYBIT_BRIDGE` (none yet land in main as of 2026-05-16).
4. Run [scripts/prod-reset-rebuild-backend.sh](scripts/prod-reset-rebuild-backend.sh). On rebuild, chain ↔ Bybit deposits / withdrawals are paired via the corridor; failed pairs surface via the conservation gate.

## Acceptance criteria

| # | Assertion | Test |
|---|---|---|
| AC-013-1 | `BybitHotWalletRegistry` does NOT exist as a Spring bean and `bybit-hot-wallets.json` does NOT exist on the classpath | `ApplicationContextTest` (absence assertion) |
| AC-013-2 | Every `NormalizedTransaction` whose `source = bybit` and `bybitOriginalType ∈ {Deposit, Withdraw}` carries non-null `txHash`, except those with `missingDataReasons` containing `TX_HASH_MISSING_BYBIT_CORRIDOR` | `BybitCanonicalTransactionBuilderTest` + `N19FullPipelineRebuildTest` |
| AC-013-3 | For every successfully-linked `(chainSideTxHash, bybitSideTxHash)` pair in `transfer_links`, the chain leg's `type == INTERNAL_TRANSFER` and `counterpartyType == CEX` after replay | `N19FullPipelineRebuildTest` |
| AC-013-4 | Chain-side address `0x2ea8cb6f614a3c579d1d09474573387d3c16ac6d` classifies as `CounterpartyType.UNKNOWN_EOA` when present standalone (not promoted to OWN by any registry) | `AccountUniverseClassifierTest` |
| AC-013-5 | Across the N19 rebuild, every chain-side ERC-20 transfer from any OWN EVM wallet to a Bybit hot wallet that also has a corresponding `bybit-deposit-onchain` row with the same `txID` ends up as `INTERNAL_TRANSFER` after replay | `N19FullPipelineRebuildTest` |
| AC-013-6 | A synthetic failed-link pair (chain leg present, Bybit anchor stripped of `txHash`) produces: chain pool with `lifetimeOutBasisUsd > 0` and `lifetimeInBasisUsd = 0`; conservation gate flags `conservationBreached = true` once delta exceeds `max($50, 1% MtM)` | `BybitCexCrossLinkFailureTest` |
| AC-013-7 | Realised PnL on chain ↔ Bybit deposit pairs that successfully link trends to 0 (basis carried via the linked pair); failed-link pairs do NOT silently produce phantom PnL — they surface via the conservation gate | `N19FullPipelineRebuildTest` |
| AC-013-8 | No code path inspects a static list of hot-wallet addresses; the absence is enforced by a unit test that scans `backend/src/main` for the substring `bybit-hot-wallets` and asserts zero matches | `RegistryAbsenceTest` |

## References

- [cycle-autorun/cycle-data/cycle/5/results/n19-defect-catalog.md](cycle-autorun/cycle-data/cycle/5/results/n19-defect-catalog.md) — D-BYBIT-HOT-WALLET (re-scoped to FA-001 corridor).
- [cycle-autorun/cycle-data/cycle/5/results/n19-implementation-plan.md](cycle-autorun/cycle-data/cycle/5/results/n19-implementation-plan.md) §G.
- [cycle-autorun/cycle-data/cycle/5/results/n19-account-universe.json](cycle-autorun/cycle-data/cycle/5/results/n19-account-universe.json) — `bybit_hot_wallets` block is informational only (evidence pointers, not a runtime registry).
- [ADR-003](ADR-003-transfer-links-fa001.md) — FA-001 corridor and `transfer_links` collection (the linking mechanism this ADR depends on).
- [ADR-007](ADR-007-mandatory-accounting-universe-membership.md) — `AccountingUniverse` membership; hot wallets are NOT members.
- [ADR-009](ADR-009-ownership-classification-via-universe.md) — chain-side classifier resolves hot wallets to `UNKNOWN_EOA`.
- [ADR-010](ADR-010-flow-level-counterparty.md) — per-flow counterparty persistence (carries `txHash` and `counterpartyAddress`).
- [ADR-014](ADR-014-portfolio-conservation-gate.md) — failed-link self-diagnostic via NEC delta breach.
- [ADR-015](ADR-015-per-counterparty-basis-pool.md) — pool semantics for unlinked legs (each leg pushes into its own pool).
- [docs/adr/ADR-006-cycle5-bybit-stream-authority-and-earn-subaccount.md](ADR-006-cycle5-bybit-stream-authority-and-earn-subaccount.md) §N17, §N18 — anchor-side semantics for Bybit Deposit/Withdraw rows.
- [backend/src/main/java/com/walletradar/ingestion/pipeline/clarification/BybitTransferContinuityRepairService.java](backend/src/main/java/com/walletradar/ingestion/pipeline/clarification/BybitTransferContinuityRepairService.java) — FA-001 linker implementation surface.
- [backend/src/main/java/com/walletradar/ingestion/pipeline/bybit/BybitCanonicalTransactionBuilder.java](backend/src/main/java/com/walletradar/ingestion/pipeline/bybit/BybitCanonicalTransactionBuilder.java) — persists `txHash` on the canonical row.
