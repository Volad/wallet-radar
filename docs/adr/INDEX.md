# ADR Index

Architecture Decision Records for WalletRadar. Each ADR captures a single decision, with status, context, decision, consequences, and traceable references. Filename convention: `ADR-NNN-<slug>.md`.

## All ADRs

| ADR | Title | Status | Date | Theme |
|---|---|---|---|---|
| [ADR-001](ADR-001-onchain-classification-strangler-refactor.md) | On-chain classification strangler refactor | Accepted | 2025 | On-chain pipeline |
| [ADR-002](ADR-002-clarification-reclassification-stage-contract.md) | Clarification / reclassification stage contract | Accepted | 2025 | Pipeline contracts |
| [ADR-003](ADR-003-transfer-links-fa001.md) | Transfer links (FA001) | Accepted | 2025 | Transfer linking |
| [ADR-004](ADR-004-physical-quantity-vs-basis-backed.md) | Physical quantity vs basis-backed | Accepted | 2025 | Cost basis model |
| [ADR-005-pipeline](ADR-005-cycle4-bybit-pipeline.md) | Cycle 4 Bybit pipeline | Accepted | 2026-cycle/4 | Bybit ingestion |
| [ADR-005-stream](ADR-005-bybit-per-stream-sync-metadata.md) | Bybit per-stream sync metadata | Accepted | 2026-cycle/4 | Bybit ingestion |
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
