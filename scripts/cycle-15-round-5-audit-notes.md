# Cycle 15 — Round 5 Phase A audit (working notes)

Generated 2026-05-22 against prod MongoDB `walletradar`.

Diagnostic scripts archived (and removed after this audit):
- `scripts/tmp-r5-a1-active-clusters-v2.mongosh.js`
- `scripts/tmp-r5-a2-lineage.mongosh.js`
- `scripts/tmp-r5-a2b-anomalies.mongosh.js`
- `scripts/tmp-r5-a2c-invariants.mongosh.js`

---

## A4. Audit table — top uncov clusters with root-cause classification

| # | Wallet (8c) | Network | Asset | qty | backed | uncov | UI uncov USD | Shadow uncov USD | Root cause tag |
|---|---|---|---|---:|---:|---:|---:|---:|---|
| 1 | `0x1a87…693f` | MANTLE | AMANWETH | 3.060 | 0.634 | 2.426 | **$5 278** | $5 278 | **R-upstream-ARB-WETH** — corridor faithfully carried 20% ratio; root is ARB ETH/WETH historically 80% uncov (Round 4 confirmed) |
| 2 | `0x1a87…693f` | AVALANCHE | AAVE GHO/USDT/USDC-GAUGE | 2144.92 | 0 | 2144.92 | ~**$2 145** | ~$2 145 | **R-wrapper-asymmetry** — `isBucketInbound` is false for `LENDING_DEPOSIT` → gauge inbound never reads from `wrapper:` bucket. Confirmed via classifier code. |
| 3 | `BYBIT:33625378:FUND` | — | CMETH | 0.862 | 0 | 0.862 | **$1 876** | $1 876 | **R-N7-CMETH-unpriced-inbound** — multiple uncov-creating CARRY_IN over time (selfTransfer/Funding); receiver flow `priceSrc=undefined` and sender UTA pool also had no basis |
| 4 | `0xf03b…3021` | AVALANCHE | AAVAUSDC | 1667.94 | 401.47 | 1266.47 | **$1 266** | $1 266 | **R-bridge-orphan-USDC** — pre-deposit USDC arrived to `0xf03b/AVAX` via `CARRY_IN` with `uncov=qty` (seq=7300 tx=`0x9e477a8…`). Aave deposit faithfully carried 0 basis. |
| 5 | `BYBIT:33625378:FUND` | — | ETH | 0.459 | 0.032 | 0.428 | **$930** | $930 | **R-upstream-bridge-corridor** — multiple corridor inbound legs from ARB with `priceSrc=undefined` (`BYBIT-CORRIDOR:ARBITRUM:...`). Sister of R-upstream-ARB-WETH. |
| 6 | `BYBIT:33625378:EARN` | — | LINK, LDO, ONDO, LTC | various | partial | various | ~**$330** | ~$330 | **R-N3-N4-earn-pairing** — EARN subscriptions via `bybit-it-bundle-v1:` corr; receiver `priceSrc=undefined`; sender UTA/FUND had no basis for these reward assets |
| 7 | `0xf03b…3021` | ARBITRUM | ETH | 0.000262 | 0 | 8.000 | $0.57 | **$17 406** | **R-uncov-overflow** — single LP_EXIT tx `0x293cf2…` (Pancakeswap NFT) injected `uncovDelta=+8.647` on a `qtyDelta=+0.622` inflow. Math invariant `uncov ≤ qty` violated. |
| 8 | `0x68bc…b7f` | ETHEREUM | WETH (zombie) | 0 | 0 | 152.49 | $0 | $552 (priced) | **R-zombie-uncov** — `quantityAfter=0` but `uncoveredQuantityAfter>0`. UI filters. |
| 9 | `0x1a87…693f` | BASE | WETH (zombie) | 0 | 0 | 121.84 | $0 | $441 (priced) | Same R-zombie-uncov |
| 10 | various LP-RECEIPT NFTs (Uniswap V3, Pancakeswap) | various | LP-RECEIPT:* | various | partial | various | unpriceable | various | **R-LP-NFT-pricing-out-of-scope** — concentrated-LP NFTs without spot price source; out of MVP scope per requirements |

### Aggregate by root-cause group

| Root cause | UI uncov USD | Shadow uncov USD | Round 5 candidate? |
|---|---:|---:|---|
| R-wrapper-asymmetry | ~$2 145 | ~$2 145 | **YES — high-confidence fix** |
| R-uncov-overflow | $0.57 | $17 406 | **YES — invariant clamp** |
| R-zombie-uncov | $0 | ~$993 | **YES — hygiene** |
| R-N7-CMETH-unpriced-inbound | $1 876 | $1 876 | YES (conditional) |
| R-upstream-ARB-WETH (AMANWETH MANTLE + FUND ETH corridor + AAVAUSDC bridge) | ~$7 474 | ~$7 474 | **DEFER R6** — architectural (months of upstream basis loss); proposed fix = corridor-matched zero-basis receiver spot-pricing fallback |
| R-rewards-genuine-uncov (EARN) | ~$330 | ~$330 | NO — Bybit rewards never had acquisition cost |
| R-LP-NFT-pricing-out-of-scope | unpriceable | n/a | NO — out of MVP scope |

**Total UI-visible priced uncov in scope:** ~$11 825
**Round 5 addressable (F1+F2+F3):** ~$4 022 UI USD + $17 k+ shadow cleanup
**Deferred to Round 6:** ~$7 474 UI USD (upstream basis loss)

---

## Confirmed code-level root cause: R-wrapper-asymmetry

In [backend/src/main/java/com/walletradar/costbasis/application/replay/support/ReplayTransferClassifier.java](backend/src/main/java/com/walletradar/costbasis/application/replay/support/ReplayTransferClassifier.java):

- `isBucketOutbound` (lines 86–108) lists `LENDING_DEPOSIT, STAKING_DEPOSIT, PROTOCOL_CUSTODY_DEPOSIT, VAULT_DEPOSIT, LP_*, VAULT_WITHDRAW`. Missing: `LENDING_WITHDRAW, STAKING_WITHDRAW, PROTOCOL_CUSTODY_WITHDRAW`.
- `isBucketInbound` (lines 110–131) lists `LENDING_WITHDRAW, STAKING_WITHDRAW, PROTOCOL_CUSTODY_WITHDRAW, VAULT_WITHDRAW, VAULT_DEPOSIT, LP_*`. Missing: `LENDING_DEPOSIT, STAKING_DEPOSIT, PROTOCOL_CUSTODY_DEPOSIT`.

Effect: for any wrapper-shape transaction whose type is `LENDING_DEPOSIT` / `STAKING_DEPOSIT` / `PROTOCOL_CUSTODY_DEPOSIT` (the AVAX Curve LP→gauge stake), the LP outbound deposits basis to the `wrapper:<gauge>` bucket but the gauge inbound cannot read from it. Symmetric break for the reverse `*_WITHDRAW` unstake. `VAULT_DEPOSIT/WITHDRAW` were special-cased earlier but the other receipt-style deposits were forgotten.

Fix: add the missing types to both lists. For non-composite single-asset cases (e.g. plain Aave USDC→aUSDC), the family-equivalent custody guard at `TransferReplayHandler` line 65 fires first, so the bucket path is bypassed — no regression risk.

---

## Confirmed code-level root cause: R-uncov-overflow

For tx `0x293cf2289fcbf131…ARBITRUM:0xf03b…3021` (Pancakeswap LP_EXIT, `lp-position:arbitrum:pancakeswap:196975`):

- Flows: TRANSFER Cake +0.508, TRANSFER ETH +0.622, FEE ETH small.
- ETH ledger point (seq=774): `qtyDelta=+0.622, uncovDelta=+8.647, uncovAfter=8.730, qtyAfter=0.707`.
- CAKE ledger point (seq=776): `qtyDelta=+0.508, uncovDelta=+0.508, uncovAfter=0.508, qtyAfter=0.508` — proportional.
- The LP-RECEIPT NFT itself had `uncov=8.65` at exit time.

Conclusion: the LP composite-bucket inbound restoration dumps the full LP-receipt-side uncov onto a single underlying leg instead of distributing proportionally. The session-wide invariant `uncoveredQuantityAfter ≤ quantityAfter` is violated for exactly 1 cluster (confirmed via Q1 in `tmp-r5-a2c-invariants.mongosh.js`).

Simplest fix without unrolling the slicing logic: clamp at write time in `GenericFlowReplayEngine` / `flowSupport.materializePendingInbound` and bucket restoration paths. Math invariant: `uncoveredQuantityAfter = min(uncoveredQuantityAfter, quantityAfter)`. When `quantityAfter <= dust`, force `uncoveredQuantityAfter = 0` (this also covers zombies).

---

## Confirmed code-level root cause: R-zombie-uncov

For BASE WETH (`0x1a87`) and ETHEREUM WETH (`0x68bc`): `quantityAfter == 0` but `uncoveredQuantityAfter > 0`. UI filters via `qty > 0`, so no visible impact, but reports are noisy. Same invariant clamp from R-uncov-overflow resolves this naturally.

---

## R-N7-CMETH-unpriced-inbound — conditional Round 5 candidate

Primary uncov root: tx `BYBIT-33625378:INTERNAL_TRANSFER:selfTransfer_a28d…` on 2025-02-06, UTA→FUND `bybit-collapsed-v1:c573f850…` cont=true. Receiver flow: `role=TRANSFER sym=CMETH qty=0.144 priceSrc=undefined`. Sender (UTA CMETH) pool also had `backed=0` at that time.

Why CMETH on UTA had no basis: CMETH was deposited from on-chain. The on-chain deposit goes through `EXTERNAL_TRANSFER_IN`. Per [CanonicalAssetCatalog](backend/src/main/java/com/walletradar/pricing/domain/CanonicalAssetCatalog.java) lines 120–123, CMETH is aliased to ETH for **market pricing**. But the basis-acquisition pricing path for EXTERNAL_TRANSFER_IN does not exercise this alias — it only uses event-local resolvers (Stablecoin, Execution, SwapDerived, Wrapper). `WrapperPriceResolver` requires a sibling flow with the canonical symbol in the same tx — there isn't one for a standalone deposit.

Fix shape: add CMETH (and other 1:1 pegged LST receipts: METH, WEETH) to a new event-local resolver that prices the asset at the underlying spot when no sibling provides a price — i.e. extend `WrapperPriceResolver` to permit "implicit wrapper" alias (CMETH → ETH spot) even without a sibling ETH leg. Or add a separate `PegNativeResolver`.

Risk: adds market basis (not carry basis) for these specific symbols, which is semantically valid only for true 1:1 pegs. Need to be conservative on the alias whitelist.

Decision: **defer F3 to optional R5 stretch** — include only if F1 + F2 are clean.

---

## Phase B fix matrix (final)

| # | Fix | Files (primary) | UI USD impact | R5 / R6 | Risk |
|---|---|---|---:|---|---|
| **F1** | Wrapper carry symmetry (add missing types to `isBucketInbound`/`isBucketOutbound`) | [ReplayTransferClassifier.java](backend/src/main/java/com/walletradar/costbasis/application/replay/support/ReplayTransferClassifier.java) | $1 740 (AVAX gauge) | **R5** | Low — family-equivalent path takes precedence for single-asset cases |
| **F2** | Math invariant clamp (uncov ≤ qty, uncov=0 when qty=0) | [GenericFlowReplayEngine.java](backend/src/main/java/com/walletradar/costbasis/application/replay/support/GenericFlowReplayEngine.java) + adjacent flow support | $0.57 UI + $17 k shadow + zombies | **R5** | Low — defensive invariant, no semantic change |
| F3 (stretch) | CMETH/METH/WEETH pegged-native event-local price resolver | [WrapperPriceResolver.java](backend/src/main/java/com/walletradar/pricing/resolver/event/WrapperPriceResolver.java) or new resolver | $1 876 (Bybit FUND CMETH) | R5 stretch | Medium — adds market basis for 1:1 LST pegs; whitelist required |
| F4 | Upstream basis loss (ARB ETH/WETH → AMANWETH MANTLE, AVAX USDC → AAVAUSDC, FUND ETH corridor) — proposed solution: spot-pricing fallback on corridor-matched zero-basis receivers OR backfill bridge linker | (TBD architecture) | $7 474 | **R6** | High — alters carry semantics |
| F5 | Bybit EARN reward genuine uncov | n/a | $330 | Accept | n/a |
| F6 | LP-RECEIPT NFT (Uniswap V3, Pancakeswap concentrated) pricing | new pricing module | unpriceable | R7+ | Out of MVP |

**Round 5 commit:** F1 + F2 (high confidence, low risk). F3 conditional.
**Round 6 backlog:** F4 architectural fix.

---

## Acceptance projections after R5 commit (F1 + F2)

| Cluster | Pre-R5 cov% | Post-R5 cov% (projection) |
|---|---:|---:|
| AVAX Curve LP-GAUGE | 0% | ~80% (matches LP coverage; gauge inherits) |
| AVAX Curve LP itself | 80% | ~80% (no change; F1 doesn't affect entry which was already OK) |
| 0xf03b ARB ETH | 0% (qty=0.0003, uncov=8.0 — invariant violation) | 0% (qty=0.0003, uncov=0.0003 — invariant restored; cov%=0 of dust position is acceptable) |
| BASE/ETH WETH zombies | qty=0 uncov>0 | qty=0 uncov=0 (purged) |
| AMANWETH MANTLE | 20.7% | 20.7% (unchanged — F4/R6) |
| Bybit FUND CMETH | 0% | 0% unless F3 included → ~100% with F3 |
| Bybit FUND ETH | 6.9% | 6.9% (R6) |
| 0xf03b AAVAUSDC | 24.1% | 24.1% (R6) |

**DoD reassessment**: "fully proven coverage" per the plan's §0 ("`backed/qty ≥ 99%` for every active cluster") **cannot be achieved in R5 alone**. R6 is required for the AMANWETH/FUND ETH/AAVAUSDC clusters because their root cause is months-old upstream basis loss propagated correctly through the engine.

**Realistic R5 DoD:**
- F1 closes AVAX gauge: gauge ≥ 80% covered (matches LP)
- F2 eliminates math invariant violations + zombies (engine correctness)
- Portfolio total backed increases by ~$1.74k (gauge basis recovered) plus ~$0–$1.88k if F3 included
- All R6 candidates are documented with USD impact in `final-report` artifact

If user requires strict "≥99% per cluster", R5 must be extended with F4 (architectural) and F3 — this is at least 1–2 additional rounds of work.

---

## Round 6 verification (2026-05-23)

Full re-norm: `./scripts/prod-reset-rebuild-backend.sh --clear-pricing-cache --skip-frontend`

### Pipeline stage timings (poll 5s, `user_sessions.pipelineState`)

| Stage | Duration |
|---|---:|
| ON_CHAIN_NORMALIZATION | 5s |
| ON_CHAIN_CLARIFICATION | 33s |
| LINKING | 27s |
| PRICING | 1m 26s |
| ACCOUNTING_REPLAY | 5s |
| PORTFOLIO_SNAPSHOT_REFRESH | ~16s |
| **Total** | **~2m 53s** |

Note: BYBIT_NORMALIZATION / ON_CHAIN_RECLASSIFICATION did not appear as separate pipeline stages (no pending work at resume time). 967 `bybit_extracted_events` remain `RAW` (non-basis / already collapsed).

### Post-R6 acceptance

| Check | Result |
|---|---|
| NEEDS_REVIEW | 0 |
| Ledger points | 10 082 |
| Zombie clusters | 0 |
| uncov>qty violations | 0 |
| Portfolio backed (rough spot) | ~$12 641 |

| Cluster | Pre-R6 | Post-R6 |
|---|---:|---:|
| AMANWETH MANTLE cov% | 20.7% (uncov 2.43) | **100%** (uncov 0) |
| ETH-family aggregate cov% | ~36% (uncov ~2.86) | **91.3%** (uncov 0.392 ≈ $853) |
| Move basis ETH (UI aggregate) | backed ~0.675 / uncov ~2.448 | backed ~4.09 / uncov ~0.39 |
| BYBIT FUND ETH cov% | ~7% (uncov 0.43) | **14.7%** (uncov 0.392 ≈ $852) — unchanged baseline from historical Bybit selfTransfer corridor, not the 3.06 Mantle leg |
| BYBIT FUND CMETH | 0% | **100%** (F3 + R6 pricing) |

R6 `REPLAY_INBOUND_SPOT_FALLBACK` fired for on-chain ETH/WETH LP_EXIT and bridge corridor legs (logs confirm WETH 0.546 @ $3202, ETH 0.799 @ $1896, etc.).

## Round 7 verification (2026-05-23)

### Code (R7)

| Fix | Status |
|---|---|
| P0 `effectiveRemovalAvco` + orphan basis purge + full-dispose basis drain | Implemented; seq≈7581 zombie still reproduces in prod replay — under investigation |
| P0b `wrapper:` bucket key preference (gauge↔LP) | **Verified** — AAVE GHO/USDT/USDC-GAUGE cov 100% |
| P1 Euler eToken symbol fallback | **Verified** — 0 empty-symbol clusters with qty>0 |
| P2 cmETH redemption ratio | Deferred |
| P3 Bybit continuity INTERNAL_TRANSFER pricing | Not effective yet — FUND ETH cov 14.7% unchanged |

### Re-normalization prep + run

```bash
./scripts/prod-reset-rebuild-backend.sh --clear-pricing-cache --skip-frontend
```

Mongo after reset: 3223 raw PENDING, 5262 bybit RAW, 0 normalized / ledger / prices.

Backend rebuilt with `--no-cache`.

### Pipeline timings (full re-norm)

| Stage | Duration |
|---|---:|
| ON_CHAIN_NORMALIZATION | ~5s |
| ON_CHAIN_CLARIFICATION | ~33s |
| LINKING | ~27s |
| PRICING | ~1m 31s |
| ACCOUNTING_REPLAY | ~5s |
| PORTFOLIO_SNAPSHOT_REFRESH | ~16s |
| **Total** | **~2m 51s** |

Replay-only after P0 drain patch: **~33s** (normalization skipped).

### Post-R7 acceptance

| Check | Result |
|---|---|
| NEEDS_REVIEW | 0 |
| Ledger points | 10 083 |
| Zombie clusters | 0 |
| uncov>qty violations | 0 |
| AAVE GHO/USDT/USDC-GAUGE (AVAX) | **100%** cov (was 0%) |
| AMANWETH MANTLE | AVCO **$3300**, cov 100% — target $2200–2800 **not met** |
| BYBIT FUND ETH | **14.7%** cov, uncov 0.392 — unchanged |
| Move basis ETH (UI) | backed ~3.06, AVCO ~$3284 |

### Root cause still open (AMANWETH)

zkSync WETH REALLOCATE_OUT tx `0xcfe0fd4d…` (replay seq≈7585): `basisDelta=0` while qty→0; aZksWETH bucket inbound receives qty without basis. Orphan basis cascades through WRAP → Mantle AMANWETH deposit.

Unit tests for `removeFromPosition` pass; prod path for wrapper composite `LENDING_DEPOSIT` still needs integration-level fix (R7b).

### Remaining tails

- BYBIT FUND ETH 0.392 uncov (~$852) — historical selfTransfer corridor; P3 pricing rule did not attach quotes on normalized flows
- BYBIT EARN rewards (~$330) — genuine uncov
- BYBIT UTA MNT / EARN USDC — minor
