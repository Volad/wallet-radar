# ADR Index

Architecture Decision Records for WalletRadar. Each ADR captures a single decision, with status, context, decision, consequences, and traceable references. Filename convention: `ADR-NNN-<slug>.md`.

## All ADRs

| ADR | Title | Status | Date | Theme |
|---|---|---|---|---|
| [ADR-001](ADR-001-onchain-classification-strangler-refactor.md) | On-chain classification strangler refactor | Accepted | 2025 | On-chain pipeline |
| [ADR-002](ADR-002-clarification-reclassification-stage-contract.md) | Clarification / reclassification stage contract | Accepted | 2025 | Pipeline contracts |
| [ADR-003](ADR-003-transfer-links-fa001.md) | Transfer links (FA001) | Accepted | 2025 | Transfer linking |
| [ADR-004](ADR-004-physical-quantity-vs-basis-backed.md) | Physical quantity vs basis-backed | Accepted | 2025 | Cost basis model |
| [ADR-005](ADR-005-cycle4-bybit-pipeline.md) | Cycle 4 Bybit pipeline | Accepted | 2026-cycle/4 | Bybit ingestion |
| [ADR-006](ADR-006-cycle5-bybit-stream-authority-and-earn-subaccount.md) | Cycle 5 Bybit stream authority matrix and EARN as sub-account | Accepted | 2026-05-11 | Bybit ingestion |
| [ADR-007](ADR-007-mandatory-accounting-universe-membership.md) | Mandatory Accounting Universe membership for OWN classification | Proposed | 2026-05-16 | N19 — universe |
| [ADR-008](ADR-008-bybit-subaccount-discovery.md) | Bybit sub-account discovery on backfill bootstrap | Proposed | 2026-05-16 | N19 — universe |
| [ADR-009](ADR-009-ownership-classification-via-universe.md) | Ownership Classification via AccountingUniverse | Proposed | 2026-05-16 | N19 — ownership |
| [ADR-010](ADR-010-flow-level-counterparty.md) | Flow-level counterparty on `NormalizedTransaction.Flow` | Proposed | 2026-05-16 | N19 — schema |
| [ADR-011](ADR-011-bybit-fiat-p2p-external.md) | Bybit Fiat P2P Purchase as `EXTERNAL_TRANSFER_IN` terminal | Proposed | 2026-05-16 | N19 — Bybit |
| [ADR-012](ADR-012-borrow-liability-tracker.md) | Borrow liability tracker | Proposed | 2026-05-16 | N19 — liabilities |
| [ADR-013](ADR-013-cex-cross-system-linking.md) | CEX Cross-System Linking via FA-001 (no hot-wallet registry) | Proposed | 2026-05-16 | N19 — Bybit / FA-001 |
| [ADR-014](ADR-014-portfolio-conservation-gate.md) | Portfolio conservation gate at dashboard publication | Proposed | 2026-05-16 | N19 — invariants |
| [ADR-015](ADR-015-per-counterparty-basis-pool.md) | Per-counterparty AVCO basis pool | Proposed | 2026-05-16 | N19 — basis engine |
| [ADR-016](ADR-016-bybit-internal-transfer-bundling.md) | Bybit multi-stream internal-transfer bundling and round-trip pairing | Accepted | 2026-05-20 | Cycle 12 — Bybit coverage |
| [ADR-017](ADR-017-timeline-avco-authority.md) | Timeline AVCO authority (move-basis read model) | Accepted (amended; chart-source superseded by ADR-045) | 2026-05-27 | Cluster E — timeline AVCO |
| [ADR-018](ADR-018-lp-protocol-family-materialization.md) | LP Protocol-Family Flow Materialization | Accepted (amended) | 2026-05-27 | LP families + ETH-C10 |
| [ADR-019](ADR-019-corridor-carry-policy.md) | Corridor carry policy: outbound-AVCO preservation | Accepted | 2026-05-29 | ETH-C1 — corridor AVCO |
| [ADR-020](ADR-020-bridge-late-carry-passthrough-reservation.md) | Bridge late-carry pass-through reservation invariant | Accepted | 2026-05-29 | ETH-V2-C1 — bridge ordering |
| [ADR-021](ADR-021-swap-multi-sell-price-derivation-and-lp-harvest-gate.md) | SWAP multi-sell price derivation and concentrated-liquidity harvest-only gate | Accepted (amended) | 2026-05-29 | ETH AVCO — SWAP pricing, LP harvest |
| [ADR-022](ADR-022-lp-exit-per-asset-attribution-and-swap-multi-leg-pricing.md) | LP_EXIT per-asset cost attribution, SWAP multi-same-direction-leg pricing, LP_ENTRY net-by-asset shape detection | Accepted (amended 2026-05-30) | 2026-05-30 | ETH AVCO — LP exit attribution, SWAP split-leg pricing, router-refund LP_ENTRY |
| [ADR-023](ADR-023-pendle-lpt-receipt-and-bybit-corridor-basis.md) | Pendle LP token receipt detection and Bybit corridor spot-price basis | Accepted | 2026-05-30 | S-4 (Mantle cmETH Pendle LP) + P-B (Bybit corridor basis) |
| [ADR-024](ADR-024-bybit-per-stream-sync-metadata.md) | Bybit per-stream sync metadata (renumbered from ADR-005) | Accepted | 2026-cycle/4 | Bybit ingestion |
| [ADR-025](ADR-025-euler-evk-market-key.md) | Euler EVK market key | Superseded by ADR-036 | 2026-06 | Lending |
| [ADR-026](ADR-026-live-aave-v3-health-factor.md) | Live Aave v3 health factor | Accepted | 2026-06 | Lending |
| [ADR-027](ADR-027-lifi-calldata-destination-discovery.md) | LiFi calldata destination discovery | Accepted | 2026-06 | Ingestion / bridges |
| [ADR-028](ADR-028-value-divergence-leverage-inference.md) | Value divergence leverage inference | Accepted | 2026-06 | AVCO / replay |
| [ADR-029](ADR-029-deterministic-cex-corridor-basis-continuity.md) | Deterministic CEX corridor basis continuity | Accepted | 2026-06 | AVCO / corridor |
| [ADR-030](ADR-030-replay-accumulator-idempotency.md) | Replay accumulator idempotency | Accepted | 2026-06 | AVCO / replay |
| [ADR-031](ADR-031-avco-undefined-representation.md) | AVCO undefined representation | Accepted | 2026-06 | AVCO |
| [ADR-032](ADR-032-multi-counterparty-fee-exclusion.md) | Multi-counterparty fee exclusion | Accepted | 2026-06 | Replay / fees |
| [ADR-033](ADR-033-bridge-multi-flow-role-alignment.md) | Bridge multi-flow role alignment | Accepted | 2026-06 | Bridge normalization |
| [ADR-034](ADR-034-nec-transaction-scan.md) | NEC computation via direct transaction scan | Accepted | 2026-06-23 | Conservation gate / NEC |
| [ADR-035](ADR-035-lending-receipt-identity-resolver.md) | Lending receipt identity resolver | Accepted | 2026-06-24 | Lending |
| [ADR-036](ADR-036-contract-first-lending-market-key-and-live-debt.md) | Contract-first lending market key + live debt | Accepted | 2026-06-24 | Lending |
| [ADR-037](ADR-037-lp-enrichment-and-earnings-snapshots.md) | LP on-chain enrichment and earnings snapshots | Accepted | 2026-06-24 | Liquidity pools |
| [ADR-040](ADR-040-dual-cost-basis-net-tax-avco.md) | Dual cost basis — Net AVCO and Tax AVCO | Accepted | 2026-06-30 | Cost basis / replay / UI |
| [ADR-041](ADR-041-bybit-earn-corridor-pairing-and-fund-carry-symmetry.md) | Bybit Earn corridor pairing and `:FUND` carry symmetry | Accepted | 2026-06-30 | Bybit corridor / linking / replay |
| [ADR-042](ADR-042-honor-flow-accountref-in-replay-position-resolution.md) | Honor `flow.accountRef` in replay position resolution | Accepted | 2026-07-01 | Bybit sub-account / replay |
| [ADR-043](ADR-043-corridor-carry-basis-conservation.md) | Corridor carry basis conservation (continuation of ADR-041) | Accepted | 2026-07-01 | Bybit corridor / linking / replay / pricing |
| [ADR-045](ADR-045-family-covered-weighted-move-basis-avco-series.md) | Family covered-weighted move-basis AVCO series (supersedes ADR-017 chart source) | Accepted | 2026-07-02 | Move-basis chart / read model |

## Cycle/5 N19 cluster — implementation plan mapping

Map of the 9 N19 ADRs to sections of `cycle-autorun/cycle-data/cycle/5/results/n19-implementation-plan.md` and defects from `n19-defect-catalog.md`.

| ADR | Plan section | Defects closed |
|---|---|---|
| [ADR-007](ADR-007-mandatory-accounting-universe-membership.md) | §A — Account Universe expansion | D-ROOT-1, D-COUNTERPARTY-1 (structural), D-MISSING-NETWORKS, D-MISSING-TON |
| [ADR-008](ADR-008-bybit-subaccount-discovery.md) | §A change 3 — sub-UID merge | D-ROOT-2 |
| [ADR-009](ADR-009-ownership-classification-via-universe.md) | §B Network registration, §C Counterparty resolver v2 | D-ROUNDTRIP-BASIS (label part), D-MISSING-NETWORKS, D-MISSING-TON |
| [ADR-010](ADR-010-flow-level-counterparty.md) | §D Flow-level counterparty | D-FLOWS-1, D-COUNTERPARTY-2 |
| [ADR-011](ADR-011-bybit-fiat-p2p-external.md) | §E Bybit Fiat P2P | D-FIAT-P2P |
| [ADR-012](ADR-012-borrow-liability-tracker.md) | §F BorrowLiability tracker | D-LOAN-ROUNDTRIP |
| [ADR-013](ADR-013-cex-cross-system-linking.md) | §G CEX cross-system linking via FA-001 (`txHash`) | D-BYBIT-HOT-WALLET |
| [ADR-014](ADR-014-portfolio-conservation-gate.md) | §H Conservation gate | Cross-cutting regression gate |
| [ADR-015](ADR-015-per-counterparty-basis-pool.md) | §K Per-counterparty basis pool | D-ROUNDTRIP-BASIS (mechanism), D-MISSING-NETWORKS / D-MISSING-TON (MtM contribution) |

Plan sections G (Convert / spot dedup), I (Airdrop / Reward policy), J (migration), the test suite, frontend deltas, and sequencing remain implementation details tracked in `n19-implementation-plan.md` and `docs/tasks/15X-cycle5-*.md` — not ADR-grade decisions.

## Reading order for N19 cluster

For a reviewer new to the cycle/5 N19 work, follow:

1. `cycle-autorun/cycle-data/cycle/5/results/n19-README.md` — context.
2. `n19-cross-validation.md` — proves the conservation numbers.
3. `n19-defect-catalog.md` — 17 defects.
4. [ADR-007](ADR-007-mandatory-accounting-universe-membership.md) → [ADR-008](ADR-008-bybit-subaccount-discovery.md) → [ADR-009](ADR-009-ownership-classification-via-universe.md) — universe and ownership classification.
5. [ADR-010](ADR-010-flow-level-counterparty.md) — schema enabler.
6. [ADR-015](ADR-015-per-counterparty-basis-pool.md) — universal basis mechanism.
7. [ADR-011](ADR-011-bybit-fiat-p2p-external.md), [ADR-012](ADR-012-borrow-liability-tracker.md), [ADR-013](ADR-013-cex-cross-system-linking.md) — targeted defect fixes that ride on the above.
8. [ADR-014](ADR-014-portfolio-conservation-gate.md) — regression gate.
