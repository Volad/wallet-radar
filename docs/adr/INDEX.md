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
| [ADR-017](ADR-017-timeline-avco-authority.md) | Timeline AVCO authority (move-basis read model) | Accepted (amended; chart-source superseded by ADR-045; staked-ETH inclusion superseded by ADR-054) | 2026-05-27 | Cluster E — timeline AVCO |
| [ADR-018](ADR-018-lp-protocol-family-materialization.md) | LP Protocol-Family Flow Materialization | Accepted (amended) | 2026-05-27 | LP families + ETH-C10 |
| [ADR-019](ADR-019-corridor-carry-policy.md) | Corridor carry policy: outbound-AVCO preservation | Accepted | 2026-05-29 | ETH-C1 — corridor AVCO |
| [ADR-020](ADR-020-bridge-late-carry-passthrough-reservation.md) | Bridge late-carry pass-through reservation invariant | Accepted | 2026-05-29 | ETH-V2-C1 — bridge ordering |
| [ADR-021](ADR-021-swap-multi-sell-price-derivation-and-lp-harvest-gate.md) | SWAP multi-sell price derivation and concentrated-liquidity harvest-only gate | Accepted (amended) | 2026-05-29 | ETH AVCO — SWAP pricing, LP harvest |
| [ADR-022](ADR-022-lp-exit-per-asset-attribution-and-swap-multi-leg-pricing.md) | LP_EXIT per-asset cost attribution, SWAP multi-same-direction-leg pricing, LP_ENTRY net-by-asset shape detection | Accepted (amended 2026-05-30) | 2026-05-30 | ETH AVCO — LP exit attribution, SWAP split-leg pricing, router-refund LP_ENTRY |
| [ADR-023](ADR-023-pendle-lpt-receipt-and-bybit-corridor-basis.md) | Pendle LP token receipt detection and Bybit corridor spot-price basis | Accepted (D3 symbol-only Pendle key superseded by ADR-081) | 2026-05-30 | S-4 (Mantle cmETH Pendle LP) + P-B (Bybit corridor basis) |
| [ADR-024](ADR-024-bybit-per-stream-sync-metadata.md) | Bybit per-stream sync metadata (renumbered from ADR-005) | Accepted | 2026-cycle/4 | Bybit ingestion |
| [ADR-025](ADR-025-euler-evk-market-key.md) | Euler EVK market key | Superseded by ADR-036 | 2026-06 | Lending |
| [ADR-026](ADR-026-live-aave-v3-health-factor.md) | Live Aave v3 health factor | Accepted | 2026-06 | Lending |
| [ADR-027](ADR-027-lifi-calldata-destination-discovery.md) | LiFi calldata destination discovery | Accepted | 2026-06 | Ingestion / bridges |
| [ADR-028](ADR-028-value-divergence-leverage-inference.md) | Value divergence leverage inference | Accepted | 2026-06 | AVCO / replay |
| [ADR-029](ADR-029-deterministic-cex-corridor-basis-continuity.md) | Deterministic CEX corridor basis continuity | Accepted | 2026-06 | AVCO / corridor |
| [ADR-030](ADR-030-replay-accumulator-idempotency.md) | Replay accumulator idempotency | Accepted | 2026-06 | AVCO / replay |
| [ADR-031](ADR-031-avco-undefined-representation.md) | AVCO undefined representation | Accepted (amended by ADR-054) | 2026-06 | AVCO |
| [ADR-032](ADR-032-multi-counterparty-fee-exclusion.md) | Multi-counterparty fee exclusion | Accepted | 2026-06 | Replay / fees |
| [ADR-033](ADR-033-bridge-multi-flow-role-alignment.md) | Bridge multi-flow role alignment | Accepted | 2026-06 | Bridge normalization |
| [ADR-034](ADR-034-nec-transaction-scan.md) | NEC computation via direct transaction scan | Accepted | 2026-06-23 | Conservation gate / NEC |
| [ADR-035](ADR-035-lending-receipt-identity-resolver.md) | Lending receipt identity resolver | Accepted | 2026-06-24 | Lending |
| [ADR-036](ADR-036-contract-first-lending-market-key-and-live-debt.md) | Contract-first lending market key + live debt | Accepted | 2026-06-24 | Lending |
| [ADR-037](ADR-037-lp-enrichment-and-earnings-snapshots.md) | LP on-chain enrichment and earnings snapshots | Accepted | 2026-06-24 | Liquidity pools |
| [ADR-038](ADR-038-google-sso-identity-binding.md) | Google SSO identity binding | Accepted | 2026-06 | Auth / identity |
| [ADR-039](ADR-039-async-refresh-status.md) | Async refresh status | Accepted | 2026-06 | Frontend / refresh |
| [ADR-040](ADR-040-dual-cost-basis-net-tax-avco.md) | Dual cost basis — Net AVCO and Market AVCO (Tax→Market rename) | Accepted (amended by ADR-054) | 2026-06-30 | Cost basis / replay / UI |
| [ADR-041](ADR-041-bybit-earn-corridor-pairing-and-fund-carry-symmetry.md) | Bybit Earn corridor pairing and `:FUND` carry symmetry | Accepted | 2026-06-30 | Bybit corridor / linking / replay |
| [ADR-042](ADR-042-honor-flow-accountref-in-replay-position-resolution.md) | Honor `flow.accountRef` in replay position resolution | Accepted | 2026-07-01 | Bybit sub-account / replay |
| [ADR-043](ADR-043-corridor-carry-basis-conservation.md) | Corridor carry basis conservation (continuation of ADR-041) | Accepted | 2026-07-01 | Bybit corridor / linking / replay / pricing |
| [ADR-044](ADR-044-router-agnostic-native-settlement-recovery-and-native-pool-reconciliation-gate.md) | Router-agnostic native settlement recovery and native-pool reconciliation gate | Accepted | 2026-07 | Native settlement / AVCO |
| [ADR-045](ADR-045-family-covered-weighted-move-basis-avco-series.md) | Family covered-weighted move-basis AVCO series (supersedes ADR-017 chart source) | Accepted (amended by ADR-054) | 2026-07-02 | Move-basis chart / read model |
| [ADR-046](ADR-046-init-capital-collateral-borrow-classification.md) | INIT Capital collateral-borrow classification and BORROW net cost rule | Accepted | 2026-07-07 | On-chain classification / Lending / AVCO |
| [ADR-047](ADR-047-equilibria-staking-lp-corridor.md) | Equilibria staking deposit and Pendle LP corridor threading | Accepted (item 4 hard-coded exit corrId amended by ADR-081) | 2026-07-07 | On-chain classification / LP receipt corridor |
| [ADR-048](ADR-048-dzengi-product-scope.md) | Dzengi product scope (streams, exclusions, pipeline stage) | Accepted | 2026-07-08 | CEX integration — Dzengi |
| [ADR-049](ADR-049-venue-agnostic-cex-transfer-linking.md) | Venue-agnostic CEX transfer linking (FA-001 beyond Bybit) | Accepted | 2026-07-08 | Linking — CEX cross-system |
| [ADR-050](ADR-050-dzengi-fiat-fx-pricing.md) | Dzengi fiat FX pricing (BYN via venue klines) | Accepted | 2026-07-08 | Pricing — Dzengi FX |
| [ADR-051](ADR-051-cex-fee-capitalization-net-avco.md) | CEX fee capitalization into Net AVCO (Market = clean price) | Accepted | 2026-07-09 | AVCO / Cost-basis |
| [ADR-052](ADR-052-venue-capability-spi-walletref-normalization-boundary-invariant.md) | Venue Capability SPI, WalletRef, and the Normalization Boundary Invariant | Accepted | 2026-07-10 | CEX abstraction / extensibility |
| [ADR-053](ADR-053-independent-latest-price-refresh-subsystem.md) | Independent Latest-Price Refresh Subsystem | Accepted | 2026-07-12 | Pricing — latest-price refresh / current quotes |
| [ADR-054](ADR-054-per-asset-avco-for-staked-derivatives.md) | Per-asset AVCO for staked / value-accruing derivatives (one asset, one cost-basis pool) | Accepted (§2 amended by ADR-082 NET-lane re-base; §2 realize-at-market superseded for **intra-cluster** conversions by ADR-083 cluster-carry — realize kept for cluster↔non-cluster / cross-cluster) | 2026-07-13 | Cost basis / asset identity / replay / pricing / read model |
| [ADR-055](ADR-055-fh-crypto-loans-source-authority.md) | Funding-History `Crypto Loans` source-authority for BORROW/REPAY | Accepted | 2026-07-13 | Bybit ingestion / extraction authority / loan accounting |
| [ADR-056](ADR-056-onchain-earn-fund-round-trip-continuity.md) | Bybit On-chain Earn — FUND self-round-trip continuity for non-ETH-family assets | Accepted | 2026-07-14 | Bybit normalization / continuity / AVCO |
| [ADR-057](ADR-057-euler-evk-internal-debt-token-exclusion.md) | Euler Finance EVK internal debt-token exclusion (BLOCKER-9) | Accepted | 2026-07-14 | Normalization / inventory hygiene |
| [ADR-058](ADR-058-bybit-bot-compartment-cost-basis.md) | Bybit Trading-Bot per-uid `:BOT` compartment deterministic cost basis (NEW-12 Phase 2 + NEW-12-R per-asset execution cap) | Accepted | 2026-07-16 | Bybit normalization / cost basis / anti-Phase-1 conservation |
| [ADR-059](ADR-059-counterparty-hints-config-plane.md) | Counterparty-hints config plane for network-agnostic bridge/payout/LP addresses (consolidation Wave W2) | Accepted | 2026-07-16 | Config consolidation / normalization / linking / conservation gate |
| [ADR-060](ADR-060-asset-family-registry-consolidation.md) | Accounting asset-family registry consolidation — keep pricing vs accounting separate, dedup SYMBOL_FAMILIES onto the C1/C2 registry (consolidation Wave W9) | Accepted | 2026-07-17 | Config consolidation / cost-basis / AVCO family identity |
| [ADR-061](ADR-061-blended-total-exposure-avco-series.md) | Blended total-exposure move-basis AVCO series (RC-E3; amends ADR-045) | Accepted | 2026-07-17 | Move-basis chart / read model |
| [ADR-062](ADR-062-break-even-effective-cost-metric.md) | Break-even (effective-cost) metric with configurable cross-family PnL attribution | Accepted | 2026-07-18 | Read model / cost basis / dashboard + move-basis UI |
| [ADR-063](ADR-063-solana-helius-normalization.md) | Solana normalization via Helius Enhanced Transactions API (program-ID-first classification) | Accepted | 2026-07-18 | Solana ingestion / normalization / DeFi classification |
| [ADR-064](ADR-064-ton-toncenter-acquisition.md) | TON acquisition via TON Center v3 public API | Accepted | 2026-07-18 | TON ingestion / normalization |
| [ADR-065](ADR-065-non-evm-backfill-enablement.md) | Backfill enablement for non-EVM networks (Solana, TON) | Accepted | 2026-07-18 | Backfill pipeline / ingestion config |
| [ADR-066](ADR-066-per-family-counterparty-resolution-and-program-id-registry.md) | Per-family counterparty resolution SPI and program-ID registry keying | Accepted | 2026-07-18 | On-chain enrichment / counterparty / Solana + TON |
| [ADR-067](ADR-067-non-evm-onchain-balances-and-conservation-scope.md) | Non-EVM on-chain balance providers, family-aware read-path addressing, and SOL/TON conservation scope | Accepted | 2026-07-19 | Portfolio read model / balance refresh / conservation gate |
| [ADR-068](ADR-068-non-evm-latest-price-providers.md) | Non-EVM latest-price providers (Jupiter Price API for Solana SPL mints) | Accepted | 2026-07-19 | Pricing / latest price / Solana |
| [ADR-069](ADR-069-jupiter-lend-borrow-loop-accounting.md) | Jupiter Lend borrow/loop accounting on Solana — net-flow classification, inbound borrow leg, SOLANA borrow_liabilities, loop carry | Accepted | 2026-07-19 | Solana normalization / classification / cost basis / lending |
| [ADR-070](ADR-070-solana-lp-enrichment.md) | Solana LP on-chain enrichment (Meteora DLMM, Raydium CLMM) — family-aware discovery address, terminal LP_EXIT closed-detection, position-account decode + free Meteora/Raydium REST | Accepted | 2026-07-19 | LP position refresh / discovery / on-chain enrichment / Solana |
| [ADR-071](ADR-071-live-lending-position-spi.md) | Live lending position SPIs (LendingLivePositionReader + LendingMarketRateReader) dispatched by supports(); Jupiter Lend Solana reader; single-authority collateral/debt/HF/LTV; snapshot-first; WS-4 live borrow-liability SET/override | Accepted | 2026-07-20 | Lending refresh / balance contribution / borrow liabilities / lending view |
| [ADR-072](ADR-072-external-custody-destinations.md) | External custody destinations ("count on exit") — informational custody ledger, capability flag | Accepted | 2026-07-20 | Ingestion / custody / session settings |
| [ADR-073](ADR-073-unified-token-metadata-resolution.md) | Unified token-metadata resolution (descriptor override → persistent cache → live resolver → unresolved); retires `token-metadata.json` | Accepted | 2026-07-20 | Normalization / token identity / metadata cache / Solana + TON |
| [ADR-074](ADR-074-network-agnostic-post-normalization-invariant.md) | Network-agnostic post-normalization invariant (generalizes ADR-052 to the network axis) — `receiptBearingCollateral` / `lpConcentrated` capability flags; ArchUnit + `NetworkBranchGuardTest` | Accepted | 2026-07-20 | Normalization boundary / network-family capability flags / ArchUnit enforcement |
| [ADR-075](ADR-075-solana-lp-full-close-residual-writeoff.md) | Solana concentrated-liquidity full-close residual write-off — promote a rent-reclaim-closed CLMM/DLMM remove to `LP_EXIT_FINAL` and drain residual `lp_receipt_basis_pools` to zero (realized LP PnL); fixes phantom "open" LP positions / uncovered SOL | Accepted | 2026-07-20 | Solana normalization / cost-basis replay / LP position closure |
| [ADR-076](ADR-076-jupiter-lend-market-rate-reader.md) | Jupiter Lend live market-rate reader (Borrow API supply/borrow rates, basis-point decode, `API_SNAPSHOT`) so Net APY renders; time-weighted average principal as the factual-APR exposure denominator (all protocols), income cost-basis stays total principal | Accepted | 2026-07-21 | Lending market rates / factual APY / Solana |
| [ADR-077](ADR-077-v2-fungible-gauge-lp-identity.md) | Velodrome/Aerodrome v2 (fungible) gauge LP identity via on-chain gauge grammar (`nft()` reverts + `pool()` resolves ⇒ staked AMM LP token), correlation keyed on the staked LP token with the gauge carried for valuation; `FungiblePoolReader` resolves the two-sided pair (`token0()/token1()`) and values the staked balance | Accepted | 2026-07-21 | LP correlation / enrichment |
| [ADR-078](ADR-078-read-model-coverage-guard-and-headline-avco-source.md) | Read-model coverage guard & headline-AVCO source policy — a missing/errored (not authoritative-zero) balance bucket with fresh covered ledger uses ledger-covered qty for the headline covered-qty-weighted AVCO + raises a coverage flag; a real on-chain zero still drops the lot; fixes the transient dropped-`aWETH` ETH-AVCO spike | Accepted | 2026-07-22 | Portfolio read model / balance capture / headline AVCO |
| [ADR-079](ADR-079-telegram-wallet-custodial-operator-registry.md) | Telegram-Wallet custodial-operator registry & TON custody attribution — global config-seeded operator-address registry (deterministic; tonapi/highload = offline discovery only) labels TON peers `EXTERNAL_CUSTODY` "Telegram Wallet" reusing ADR-072; stays `EXTERNAL_TRANSFER` (never `INTERNAL_TRANSFER`), attribution-only (no basis/replay change) | Accepted | 2026-07-22 | TON counterparty resolution / custody attribution / config plane |
| [ADR-080](ADR-080-onchain-balance-lp-closure-cross-check-invariant.md) | On-chain-balance LP closure cross-check invariant — authoritative summed-family zero ⇒ CLOSED (flagged coverage guard, secondary to link-based closure); candidate-universe extension (burned receipt ⇒ explicit zero row, absence≠zero), effective family-sum (wallet + eqb/pnp wrapper + gauge via `ProtocolLockedBalanceProvider`), CL-NFT `ownerOf`/burn per-tokenId keying, freshness gate + dust; miss≠zero (reuses ADR-078); Solana CL stays on ADR-075 | Accepted | 2026-07-22 | LP read model / verification / balance capture |
| [ADR-081](ADR-081-lp-correlation-id-granularity-and-staked-wrapper-linking.md) | LP correlation-id granularity & staked-wrapper receipt linking — per-market+per-wallet Pendle key `pendle-lp:{network}:{marketOrSyAddress}:{wallet}` (**supersedes ADR-023 D3**; **amends ADR-047 item 4**), deterministic market resolution for direct entry AND Equilibria/Penpie wrapped exit, `meteora-damm:{pool}:{wallet}` key, LP-receipt movements are non-priced TRANSFERs, `FAMILY:LP_RECEIPT` identity-driven exclusion, read-model 4-segment key parsing | Accepted | 2026-07-22 | LP classification / linking / cost basis / read model |
| [ADR-082](ADR-082-net-lane-realizing-swap-rebasing.md) | NET-lane realizing-swap re-basing (no basis recycling) — a realizing distinct-canonical swap whose NET realized was **kept** on a lot with **no reward discount** (`net ≈ market`) re-bases the acquired NET basis to market acquisition cost instead of recycling the released net basis (**amends ADR-054 §2** and **ADR-040 "Bug B"**); preserves reward carries (`net ≪ market`), unpriced/pool-deferred disposals, C1↔C1 identity; Market lane byte-identical; `BreakEvenCalculator` cluster-income plausibility guard as defense-in-depth (FB-01: ETH effective cost $2,001.68→≈$2,500, global NET realized $8,039→≈$3,807) | Accepted | 2026-07-24 | Cost basis / replay (NET lane) / break-even read model |
| [ADR-083](ADR-083-cluster-carry-intra-cluster-conversions.md) | Cluster-carry for intra-cluster cross-canonical conversions — ETH/SOL/AVAX staking-cluster form changes (ETH↔mETH, AVAX↔sAVAX, SOL↔mSOL, yvVBETH↔vBETH) carry basis both lanes with realized PnL=0 via the renamed `CLUSTER_CARRY` route (**supersedes ADR-054 §2 and ADR-082/FB-01 for intra-cluster only**; realize kept for cluster↔non-cluster & cross-cluster); contract-first family→cluster resolution shared with normalization; generic-realize gate + mirror dedup guard; **amends ADR-040** (both-lane carry PnL=0, `Net≤Market` cap bypassed on carried lots) and **ADR-062** (no intra-cluster realized attribution) | Accepted | 2026-07-24 | Cost basis / replay (carry-vs-realize) / staking clusters |

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
