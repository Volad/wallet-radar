# Confirmed Blockers — AVCO Audit 2026-06-02 (full audit cycle, refresh 5)

Universe: `df5e69cc-a0c0-4910-8b7d-74488fa266e2`
Pipeline state: CONFIRMED=7338 | PENDING=0 | PENDING_PRICE=0 | PENDING_CLARIFICATION=0 | ledger=9702

---

## RANKED ACTIVE BLOCKER LIST (refresh 5)

**Audit verdict: NO ACTIVE AVCO SPIKES. All code-fixable blockers resolved except one normalization defect (0xc8b94615).**

| Rank | ID | Severity | Description | Est. cbD shortfall | Active AVCO broken? | Fixable? |
|---|---|---|---|---|---|---|
| 1 | B-VAULT-WITHDRAW `0xc8b94615` | P2 FIXABLE | Turtle Finance USDC vault AVALANCHE: vault token burn missing from flows — normalization defect | ~$2,815 | No (USDC disposed) | ✅ Normalization fix needed |
| 2 | B-BYBIT-CORRIDOR-2 (USDe) | DATA GAP | 2115 USDe MANTLE: FUND had $10 at withdrawal; SPOT acq $2,115 exists but FUNDING_HISTORY transfer missing from raw export | ~$2,105 | Yes (USDe avco≈$0.005) | ❌ Genuine evidence missing |
| 3 | B-VAULT-WITHDRAW `0x971c8464` + `0xb47d87fa` | DATA GAP | MCUSDC upstream shortfall + wrapper-less vault no prior deposit in DB | ~$4,608 (hist.) | No (USDC disposed/masked) | ❌ Irreducible upstream gaps |
| 4 | B-BYBIT-CORRIDOR-2 (cmETH) | DATA GAP | cmETH MANTLE GENUINE_EVIDENCE_MISSING | ~$212 | Yes (cmETH position) | ❌ Evidence missing |
| 5 | B-BRIDGE-ORPHAN-2 | DATA GAP | 5 BRIDGE_OUTs to 0xf5f93d26; 1 failed/refunded ($0.81 fee), 4 correct exits to untracked chain | ~$0.81 net | No (USDC AVCO unaffected) | ❌ Trivial / correct exits |
| 6 | B-BRIDGE-ORPHAN-1 | DATA GAP | 4 orphan BRIDGE_OUTs (LiFi USDC + Across ETH) | ~$7 | No | ❌ Cross-chain gap |

---

## STATUS UPDATE — B-EARN-BUNDLE RESOLVED (refresh 5)

**B-EARN-BUNDLE** is **RESOLVED** as of refresh 5 (fan-in fix deployed).

Verified post-rebuild AC (CARRY_IN cbD by asset, all EARN accounts):

| asset | total cbD | entry count | verdict |
|---|---|---|---|
| LDO | $449.99 | 22 | ✅ Previously-zero bundles now have Phase-2 TRANSACTION_LOG/FUNDING_HISTORY carries |
| TON | $656.60 | 10 | ✅ |
| LINK | $355.66 | 10 | ✅ |
| ARB | $10.73 | 5 | ✅ |

Confirmed pattern: Phase-1 EARN_FLEXIBLE_SAVING entry carries qty with cbD=$0; Phase-2 TRANSACTION_LOG / FUNDING_HISTORY entry carries cbD>0 with qty=0. Both-phase carry correctly populates EARN basis.

Previously-zero LDO bundles now have Phase-2 entries:
- 17.806 LDO: Phase-2 cbD=$21.04 (TRANSACTION_LOG seq 5753)
- 102.4 LDO: Phase-2 cbD=$34.998 (FUNDING_HISTORY seq 6412)
- 14.973 LDO: Phase-2 cbD=$12.514 (TRANSACTION_LOG seq 6468)
- 27.899 LDO: Phase-2 cbD=$19.963 (TRANSACTION_LOG seq 7207)

**B-EARN-BUNDLE: PASS ✅**

---

## STATUS UPDATE — B-GMX-LP-SETTLEMENT RESOLVED (refresh 5)

**B-GMX-LP-SETTLEMENT** is **RESOLVED** as of refresh 5 (LP_ENTRY_SETTLEMENT REALLOCATE_IN fix deployed).

All 5 LP_ENTRY_SETTLEMENT events now emit REALLOCATE_IN with cbD > 0:

| txHash | LP token | LP cbD | ETH refund cbD |
|---|---|---|---|
| `0x9fab1650` | GLV [WETH-USDC] 40.35 | $68.83 | $0.014 |
| `0x3ad60ac2` | GM: ETH/USD [WETH-USDC] 529.62 | $1,001.00 | $0.054 |
| `0x61c1272c` | GM: ETH/USD [WETH-USDC] 21.60 | $40.25 | $0.128 |
| `0x1aa3438d` | GM: ETH/USD [WETH-USDC] 97.96 | $149.91 | $0.092 |
| `0x52924cd8` | GM: ETH/USD [WETH-USDC] 4.63 | $7.27 | $0.094 |

Elevation check (LP_ENTRY_REQUEST REALLOCATE_OUTs with GMX corrId): total = **-$1,267.65** across 10 rows — consistent with sum of above settlement REALLOCATE_INs ($1,267.38). Rounding difference negligible.

**B-GMX-LP-SETTLEMENT: PASS ✅**

---

## STATUS UPDATE — B-VAULT-WITHDRAW `0xc8b94615` — NORMALIZATION DEFECT IDENTIFIED (refresh 5)

**New finding (refresh 5 investigation):** `0xc8b94615` is NOT a wrapper-less vault — it is a **Turtle Finance USDC vault** on Avalanche with a normalization defect. The vault token (`turtleAvalancheUSDC`) burn is missing from the VAULT_WITHDRAW flows.

### Protocol identification

- Vault contract: `0x3048925b3ea5a8c12eecccb8810f5f7544db54af` (Avalanche)
- Vault token: `turtleAvalancheUSDC` (same contract address, ERC4626-style)
- Deposit: `0xf49217e3...` (2025-12-12) — classified as **SWAP**: SELL 2,815.03 USDC → BUY 2,787.57 turtleAvalancheUSDC (cbD=+$2,815 ACQUIRE)
- Withdrawal: `0xc8b94615` (2026-01-12) — classified as **VAULT_WITHDRAW**: TRANSFER USDC +2,831.2 only

### Root cause

The VAULT_WITHDRAW flows contain only `TRANSFER USDC +2,831.199` (inbound). The turtleAvalancheUSDC burn (which happens on-chain when the user calls `redeem` or equivalent) is NOT captured in the normalized flows. Without the turtleAvalancheUSDC REALLOCATE_OUT flow, the Bug A wrapper bucket mechanism cannot activate.

The turtleAvalancheUSDC position remains PHANTOM in the database: 2,787.57 units with $2,815 basis (ACQUIRE from SWAP, never drained). The VAULT_WITHDRAW handler issued REALLOCATE_IN USDC cbD=$0 because no wrapper was found.

Classifier test (`OnChainClassifierTest.java` line 6739-6756) shows the classifier knows `claimSharesAndRequestRedeem` on `0x3048925b` and classifies it as UNKNOWN/PENDING_REDEEM_REQUEST. The actual `0xc8b94615` is likely the subsequent `claim` or `redeem` step which returns USDC without the visible vault token burn.

### Evidence state

`EVIDENCE_PRESENT_UNUSABLE` — turtleAvalancheUSDC position holds $2,815 basis from the SWAP ACQUIRE. The VAULT_WITHDRAW handler does not find or drain it.

### Phantom position

| asset | qty | cbD | avco | status |
|---|---|---|---|---|
| TURTLEAVALANCHEUSDC | 2,787.57 | $2,815.03 | $1.009850 | PHANTOM — already redeemed on-chain, not drained in DB |

### Failed stage hypothesis

`normalization` — the vault token burn (turtleAvalancheUSDC sent to vault/burn address) is not captured in the VAULT_WITHDRAW flows. The Bug A basis-carry mechanism requires an explicit REALLOCATE_OUT on the vault token in the same tx flows.

### Remediation

Two options:
1. **Normalization enrichment**: When normalizing `0xc8b94615` (and future turtleAvalancheUSDC redemptions), add the vault token burn as an outbound flow (`TRANSFER turtleAvalancheUSDC -2787.57` from wallet to vault/0x0), enabling the existing Bug A wrapper bucket carry. Requires identifying the vault token burn event in the raw transaction token transfers.
2. **VAULT_WITHDRAW handler enhancement**: When handling VAULT_WITHDRAW from a known vault contract with no wrapper token in flows, look up the wallet's position in the vault token and carry its proportional basis — even without an explicit REALLOCATE_OUT in the current tx.

Option 1 is simpler and consistent with the existing Bug A mechanism. The classifier already recognizes `claimSharesAndRequestRedeem` (PENDING_REDEEM_REQUEST) but needs to link the follow-up claim step and include the vault token redemption in the normalized flows.

### cbD shortfall

~$2,815 (the original USDC deposited in the SWAP → stuck in phantom TURTLEAVALANCHEUSDC position)

---

## STATUS UPDATE — B-VAULT-WITHDRAW `0x971c8464` + `0xb47d87fa` — UPSTREAM SHORTFALLS CONFIRMED (refresh 5)

**`0x971c8464` (ARBITRUM, 1,783 USDC, MCUSDC wrapper):**
- REALLOCATE_OUT MCUSDC cbD = -$0.001119 (≈$0) → wrapper bucket was essentially empty
- REALLOCATE_IN USDC cbD = +$0.001119 (≈$0) → basis not carried
- ACQUIRE USDC +33.92 cbD = +$33.92 (yield interest portion, correctly priced at market)
- Confirmed: 2nd and 3rd MCUSDC deposits brought near-zero basis USDC → wrapper accumulated nearly no cost basis → VAULT_WITHDRAW correctly reflects $0 wrapper bucket
- **IRREDUCIBLE_REMAINDER_PROVEN** — upstream shortfall at deposit time. No fix available.

**`0xb47d87fa` (ARBITRUM, 2,825 USDC, vault `0x6a2abff960b663462cbc46a2cfcf85063fe8ae14`):**
- Only 1 ledger point: REALLOCATE_IN USDC +2825.31, cbD=$0, avcoAfter=$1
- avcoAfter=$1 is correct — the large pre-existing USDC position ($1 avco) absorbed the zero-basis addition with negligible AVCO distortion
- No VAULT_DEPOSIT for vault `0x6a2abff9` found anywhere in `normalized_transactions`
- The USDC deposit happened before the ingestion scope or was never tracked
- **IRREDUCIBLE_REMAINDER_PROVEN** — pre-history deposit. No fix available.

---

## STATUS UPDATE — B-CORRIDOR-2 USDe — GENUINE_EVIDENCE_MISSING CONFIRMED (refresh 5)

Root cause chain confirmed via `bybit_extracted_events` direct query:

1. `BYBIT-33625378:EXECUTION_SPOT:2230000000735733889` acquired 2,115 USDe at **$2,115 cbD** at 2025-08-30T09:22:33
2. `bybit_extracted_events` query for USDe on 2025-08-30: **EMPTY** — no FUNDING_HISTORY records for USDe exist in the raw Bybit export for this date
3. FUND sub-account received only 10 USDe (from a separate small FUNDING_HISTORY record `ce163d1b...` at 09:38:19)
4. Corridor CARRY_IN `0x79d17a8d`: 2,115 USDe with only **$10 cbD** (FUND had $10 at withdrawal)

The SPOT→FUND transfer of 2,115 USDe is MISSING from `bybit_extracted_events`. The Bybit raw export did not include the FUNDING_HISTORY record for this transfer. Without it, the carry chain is: SPOT has $2,115 (isolated) → FUND has $10 → corridor carries only $10 → Mantle receives 2,115 USDe at $10 basis.

**GENUINE_EVIDENCE_MISSING_PROVEN** — the Bybit FUNDING_HISTORY for the 2,115 USDe SPOT→FUND transfer was never exported into `bybit_extracted_events`. Shortfall ~$2,105 is irreducible on the current dataset. Re-fetching Bybit FUNDING_HISTORY for this date could potentially recover this basis.

---

## STATUS UPDATE — B-BRIDGE-ORPHAN-2 — FINAL CLASSIFICATION (refresh 5)

All 5 BRIDGE_OUTs from `0xf03b52e8` ARBITRUM to `0xf5f93d26229482adca3e42f84d08d549cf131658`:

| txHash | Date | USDC sent | ETH forwarded | Return found | Classification | Net shortfall |
|---|---|---|---|---|---|---|
| `0xb1e9f65d` | 2026-01-12 | 2,829.12 | 0.000064 | YES: 2,828.31 USDC from same contract in 19min | FAILED BRIDGE → refunded | $0.81 (protocol fee) |
| `0x4a2eb3ee` | 2026-01-13 | 1,050.00 | 0.000064 | NO return in dataset | Successful bridge → untracked chain | $0 accounting error |
| `0x5ca14340` | 2026-01-15 | 50.00 | 0.000064 | NO return in dataset | Successful bridge → untracked chain | $0 accounting error |
| `0x87967a7a` | 2026-02-02 | 99.67 | 0.000068 | NO return in dataset | Successful bridge → untracked chain | $0 accounting error |
| `0x360988904f` | 2026-02-10 | 50.00 | 0.000068 | NO return in dataset | Successful bridge → untracked chain | $0 accounting error |

All USDC inflow-only from `0xf5f93d26` in entire dataset = 1 record (`0x7f6ccd24ba`, 2828.31 USDC, 19min after `0xb1e9f65d`). No ETH or USDC returns exist for the other 4.

**The 4 "successful" bridges carry USDC to an untracked destination chain. CARRY_OUT cbD is correctly drained at source. No BRIDGE_IN expected. No accounting error.**

`0xb1e9f65d`: failed bridge refunded via EXTERNAL_TRANSFER_IN. CARRY_OUT drained $2,829.12; EXTERNAL_TRANSFER_IN ACQUIRE created $2,828.31 fresh basis. Net: -$0.81 (bridge fee). No AVCO distortion since USDC ≈ $1 throughout.

**Total real B-BRIDGE-ORPHAN-2 accounting shortfall: $0.81** (failed-bridge fee only).

Prior estimate of ~$3,979 was incorrect — based on assumption that destination was tracked but no BRIDGE_IN found. Actual finding: 4/5 are correct exits to an untracked chain.

**Terminal state: IRREDUCIBLE_REMAINDER_PROVEN** for all 5 (USDC ≈ $1, no AVCO correction warranted).

### IRREDUCIBLE DATA GAPS (no code fix possible)
- SYRUPUSDC LENDING_WITHDRAW `0xb8d6c7` — 1887 syrupUSDC, corrId=null, no paired deposit → upstream shortfall
- WRAP/UNWRAP zero cbD — 104 WETH + 98 ETH entries, all valueUsd=$0 → no pricing available, trivial
- LP_EXIT UNKNOWN LP-RECEIPTs — 58 entries, totalValueUsd=$0 → receipt tokens have no tracked value
| — | B-MNT-CARRY-1 | **RESOLVED** | MNT MANTLE corrId fix | ✅ $69 recovered | — |
| — | B-ONDO-CARRY-1 | **RESOLVED** | ONDO bybit-collapsed FIFO fallback fix | ✅ EARN cbD=$344 | — |
| — | B-CROSS-UID (Defect 1) | **RESOLVED** | FUND-sourced FUNDING_HISTORY corrId | ✅ BTC+TON ~$259 recovered | — |

---

## STATUS UPDATE — B-MNT-CARRY-1 RESOLVED

**B-MNT-CARRY-1** (MNT MANTLE INTERNAL_TRANSFERs missing corrId → zero-basis CARRY_IN) is **RESOLVED** as of refresh 3.

`OnChainInternalTransferPairRepairService` fix (removed `continuityCandidate=false` filter) correctly assigns `internal-tx:mantle:txHash` corrIds to all 5 MNT transactions.

Verified post-rebuild (all 5 txHashes emit two-phase CARRY_IN: qty+cbD entry + qty=0 basis-carry entry):

| txHash | MNT qty | phase-1 cbD | phase-2 cbD | total cbD | verdict |
|---|---|---|---|---|---|
| `0xffc959c2` | 0.8 | $0.531 | $0.029 | **$0.560** | ✅ |
| `0x3c011394` | 25.0 | $18.77 | $2.564 | **$21.33** | ✅ |
| `0xe2bf4c4f` | 23.3 | $15.86 | $4.094 | **$19.95** | ✅ |
| `0x4fa1f2a2` | 31.1 | $20.22 | $6.419 | **$26.64** | ✅ |
| `0x7e5e7443` | 0.584 | $0.443 | $0.012 | **$0.455** | ✅ |
| **Total** | **80.78** | | | **~$68.94** | ✅ PASS |

---

## STATUS UPDATE — B-ONDO-CARRY-1 RESOLVED

**B-ONDO-CARRY-1** (4 ONDO CARRY_OUT with `bybit-collapsed-v1:` corrId, no matching CARRY_IN) is **RESOLVED** as of refresh 3.

`ReplayPendingTransferKeyFactory` FIFO check reordering + `TransferReplayHandler.applyBybitMultiLegBundleTransfer()` FIFO fallback (`earnCarryFifoKey`) now recovers orphaned collapsed-v1 carries.

Verified:
- ONDO CARRY_IN cbD on `BYBIT:33625378:EARN`: **$344.01** (24 CARRY_INs total, was ~$0 for the 4 bybit-collapsed-v1 orphans)
- ONDO CARRY_IN cbD on `BYBIT:33625378` main: **$27.42** (73.21 ONDO, 1 entry)
- Both ONDO positions are now null (all redeemed) — no current active AVCO gap

---

## STATUS UPDATE — B-CROSS-UID Defect 1 RESOLVED

**B-CROSS-UID Defect 1** (FUNDING_HISTORY outbound on `BYBIT:516601508:FUND` missing corrId → zero-cbD CARRY_IN on destination) is **RESOLVED** as of refresh 3.

FUND outbound records for BTC and TON now carry `bybit-cross-uid-v1:` corrIds, enabling `isBybitSelfTransfer()` guard to permit CARRY_OUT and correctly propagate basis.

Verified:

| corrId (short) | asset | qty | cbD (post-fix) | verdict |
|---|---|---|---|---|
| `bybit-cross-uid-v1:866903d7` | BTC | 0.000797 | **$76.855** | ✅ (was $0) |
| `bybit-cross-uid-v1:a893b645` | BTC | 0.000362 | **$34.890** | ✅ (was $0) |
| `bybit-cross-uid-v1:6b956290` | TON | 32.442 | **$147.611** | ✅ (was $0) |

**BTC DISPOSE event** (`BYBIT:33625378`, swap 2025-12-12): now correctly removes `cbD=−$111.745` (was $0). Historical P&L corrected.

Remaining B-CROSS-UID residuals after Defect 1 fix:
- MNT `47eaa702` from UID 409666492: ~$0.29 (negligible)
- USDT `9a0ae038` from UID 409666492: ~$0.002 (negligible)
- ETH `ebf90bee`: $0 cbD confirmed **legitimate** — source UID had no ETH at transfer time

**B-CROSS-UID total cbD recovered (Defect 1):** ~$259 ($112 BTC + $148 TON). Blocker now **FULLY RESOLVED** for all material items.

---

## STATUS UPDATE — ONDO REALLOCATE_OUT spike: NOT A NEW BLOCKER

The `REALLOCATE_OUT bybit-earn-principal-v1:0a0566f8` ONDO AVCO drop (EARN $0.203 → $0.005) is **not a new independent blocker**. It is a downstream symptom of B-EARN-BUNDLE.

Evidence:
- `BYBIT:33625378:EARN` LENDING_WITHDRAW `bybit-earn-principal-v1:0a0566f8` at 2026-01-20T06:36:02Z
- EARN drains 300.127 ONDO: CARRY_OUT cbD=−$5.058 (EARN had only $5.06 total basis for 300 ONDO)
- P0-A lot carry override fires ($5.06 < $100 dust threshold): FUND CARRY_IN gets cbD=+$103.30 (market price × qty)
- EARN AVCO drops from $0.203 → $0.005 because EARN accumulated ONDO at near-zero basis (B-EARN-BUNDLE subscription path deficit)

Root: EARN accumulated 1,642.5 ONDO via subscriptions but only received $344 total basis (avg $0.21/ONDO). Expected ~$0.97/ONDO acquisition cost. The deficit comes from B-EARN-BUNDLE multi-leg timing (see below).

P0-A override partially corrects the redemption side (FUND gets market basis), but does not fix the EARN accumulation deficit. ONDO EARN position is now null (all redeemed) — no ongoing active impact.

---

## B-EARN-BUNDLE — P2 ACTIVE — Multi-leg bundle timing defect (root cause refined)

**Severity**: P2
**Status**: ACTIVE — root cause identified as multi-leg timing defect, not simple "no carry source"
**Reported**: 2026-06-02 (zero-basis CARRY_IN scan)
**Revised**: 2026-06-02 (refresh 3 — direction corrected, root cause refined)

### Direction correction

Previous description stated "CARRY_INs for Bybit Earn redemptions arrive at BYBIT:33625378 main account with cbD=0." This was incorrect. The zero-cbD CARRY_INs are at **`BYBIT:33625378:EARN`** (EARN sub-account). These are **EARN subscriptions** (main → EARN), not redemptions.

Direction: `BYBIT:33625378:UTA` (−qty) + `BYBIT:33625378:FUND` (−tiny qty) → `BYBIT:33625378:EARN` (+qty, cbD=0)

### Root cause: multi-leg outbound timing — FUND steals pending inbound

For each bundle event, Bybit emits timestamps as:
- `EARN` inbound: `blockTimestamp T`
- `FUND` outbound: `blockTimestamp T+1s` (FUND leg)
- `UTA` outbound: `blockTimestamp T+1.033s` (UTA leg)

Replay processes in time order: EARN inbound arrives first → queue is empty → EARN is enqueued as `pendingInbound` in `corr-family:bybit-it-bundle-v1:...:assetKey` queue.

FUND outbound arrives 1 second later → `applyBybitMultiLegBundleTransfer` negative-qty path:
- calls `matcher.findUniqueBridgeQueueIndex(queue, true)` → finds the EARN pending inbound
- **removes** it from queue → calls `attachLateCarryToPendingInbound` with FUND's tiny carry (e.g., $0.015 for 0.016 ONDO)
- EARN position updated with FUND's tiny basis ✓ but small

UTA outbound arrives 33ms later → `findUniqueBridgeQueueIndex(queue, true)` → **queue is empty** (FUND already consumed the pending inbound) → UTA's large carry ($8.04 for 8.325 ONDO) is pushed to queue as orphan. It is never consumed by a matching EARN inbound.

### Why FIFO fallback doesn't fully solve it

`earnCarryFifoKey` fallback in `applyBybitMultiLegBundleTransfer` inbound path is used when the corr-family queue is empty. A SUBSEQUENT bundle's EARN inbound may pick up a prior bundle's orphaned UTA carry — but this FIFO-matches the WRONG bundle's carry to the WRONG inbound, producing mismatched basis.

### Observed impact

| asset | EARN total qty received | EARN total cbD | avg cbD/unit | expected cbD/unit | shortfall |
|---|---|---|---|---|---|
| SYMBOL:ONDO | 1,642.5 | $344.01 | $0.21 | ~$0.97 | ~$1,249 |
| SYMBOL:LDO | 163.1 | — (see below) | — | ~$1.00 | ~$163 |
| SYMBOL:TON | 39.4 | — | — | ~$3.00 | ~$118 |
| SYMBOL:LINK | 6.89 | — | — | ~$14.00 | ~$97 |
| SYMBOL:ARB | 14.4 | — | — | ~$0.35 | ~$5 |

All EARN positions are now null (assets redeemed). No current AVCO gap. Historical P&L distortion from low-basis EARN positions flowing through earn-principal redemptions.

### Instance table (13 zero-cbD initial CARRY_INs — initial pending materializations)

| asset | qty | initial cbD | late FUND carry cbD | late UTA carry cbD | UTA orphaned? |
|---|---|---|---|---|---|
| SYMBOL:ONDO | 8.340887 | $0 | $0.015 (attached) | $8.038 (**orphaned**) | ✅ |
| SYMBOL:ONDO | 78.942416 | $0 | $0.054 | $85.26 (attached!) | ✗ (UTA attached) |
| SYMBOL:ONDO | 22.986403 | $0 | $0.004 | $21.60 (**orphaned**) | ✅ |
| SYMBOL:ONDO | 14.989694 | $0 | $0.004 | $13.25 (**orphaned**) | ✅ |
| SYMBOL:ONDO | 20.982496 | $0.002 | $0.051 | $13.61 (attached!) | ✗ |
| SYMBOL:ONDO | 39.945087 | $0.032 | $0.032 | $19.90 (attached!) | ✗ |
| SYMBOL:LDO | 17.806 | $0 | — | — | — |
| SYMBOL:LDO | 102.400 | $0 | — | — | — |
| SYMBOL:LDO | 14.973 | $0.00008 | — | — | — |
| SYMBOL:LDO | 27.899 | $0 | — | — | — |
| SYMBOL:LINK | 6.894 | $0 | — | — | — |
| SYMBOL:TON | 6.994 | $0 | — | — | — |
| SYMBOL:TON | 32.403 | $0.010 | — | — | — |
| SYMBOL:ARB | 14.424 | $0 | — | — | — |

Note: For bundles where EARN timestamp = T and UTA timestamp = T+1 and FUND timestamp = T+1 (same second as UTA), timing may vary. When FUND and UTA timestamps are identical (seconds-resolution), sort order determines which one attaches first. If UTA sorts before FUND, UTA's larger carry IS attached and FUND's tiny carry is orphaned (negligible impact).

### Failed stage hypothesis

`move_basis` — `applyBybitMultiLegBundleTransfer` negative-qty path removes the pending inbound on first outbound leg arrival, preventing subsequent outbound legs from attaching their carries. This is a multi-leg ordering defect in the bundle handler.

### Evidence state

`EVIDENCE_PRESENT_UNUSABLE` — main account basis is correctly drained (CARRY_OUT cbD > 0 on BYBIT:33625378), but the CARRY_IN to EARN only receives the first outbound leg's carry. Subsequent legs' carries are orphaned in the pending-transfer queue.

### Remediation sketch

`applyBybitMultiLegBundleTransfer` outbound path must NOT remove the pending inbound on first attachment. Options:
1. Accumulate all outbound carries (total cbD) before attaching to the single EARN pending inbound — requires knowing how many outbound legs are expected (N-leg bundle awareness), OR
2. Re-insert an updated pending inbound after each late-carry attachment with the residual uncovered quantity, OR
3. Change `attachLateCarryToPendingInbound` to UPDATE the pending carry in-place (additive, not replace) without removing from queue; remove only after all expected outbounds have been processed

### Type adequacy

The `CARRY_OUT` / `CARRY_IN` pair is semantically adequate. The defect is in the multi-leg queue management (N-outbound-to-1-inbound fan-in), not the type model.

---

---

## STATUS UPDATE — B-SHORTFALL-1 RESOLVED

**B-SHORTFALL-1** (zero-basis BRIDGE_IN sources generating zero cbD at destination) is **RESOLVED** as of the post-B-SHORTFALL-1 cycle.

`UnmatchedBridgeInboundPricingFallbackService` now detects paired BRIDGE_IN transactions whose source had zero basis (shortfall) and reprices them at market (`continuityCandidate=false`, role `BUY`, `status→CONFIRMED`).

Verified post-rebuild:
- `0x38d445c4` ETH ARBITRUM: `cbD=$1,515.67` (qty=0.799 × $1,896.96) ✅ (was ~$0)
- `0xe11ab436` USDC ARBITRUM: `cbD=$427.95` (qty=427.946 × $1.00) ✅ (was $0.000439)
- ETH family AVCO restored to **$2,539** (was $1,527 pre-fix, baseline $2,589) ✅
- W-1 (unexplained ETH AVCO drop) is now **explained and resolved** by this fix.

---

## STATUS UPDATE — W-1 RESOLVED (B-SHORTFALL-1 explains the drop)

**W-1** ("ETH AVCO drop from $2,589 → $1,527 not fully traced") is **RESOLVED**.

---

## STATUS UPDATE — B-USDC-1 RESOLVED

**B-USDC-1** (BORROW using `perWalletAvco()` instead of market price for cbD) is **RESOLVED**.

- BORROW `0xf299178e` (ARBITRUM, 800 USDC): `costBasisDeltaUsd`=$800 ✅ (was $1,225,570)

---

## B-BYBIT-CORRIDOR-2 — P1 PARTIALLY RESOLVED — Bybit Corridor CARRY_IN zero-basis (all assets)

**Severity**: P1
**Status**: PARTIALLY RESOLVED — Sub-pattern A 21/23 fixed; 2 residual cmETH + USDe partial shortfall remain

### Post-fix residuals (as of 2026-06-02)

**BYBIT_CORRIDOR group (1 remaining with qty > 0.001):**
- `0x5067b0e1` cmETH MANTLE: qty=0.10528, cbD=$0 → GENUINE_EVIDENCE_MISSING_PROVEN

**Near-zero cbD residuals (qty ≤ 0.001, filtered from zero-carry scan):**
- `0xc6a03abc` cmETH MANTLE: qty=0.001, cbD=$0 → GENUINE_EVIDENCE_MISSING_PROVEN
Total cmETH shortfall: ~$212

**Sub-pattern B USDe partial shortfall:**
Instance 13 (`0x79d17a8d`, 2115 USDe MANTLE): `cbD=$10` because FUND held only 10 USDe at withdrawal time. The remaining 2105 USDe had zero umbrella basis coverage. Shortfall: **~$2,105**

**GROUP C (cross-UID) remaining:** 4 confirmed + 1 new USDT (negligible)

| ntId (short) | asset | qty | from UID | est. shortfall |
|---|---|---|---|---|
| `uni_trans_47eaa702` | MNT | 0.293 | 409666492 | ~$0.29 |
| `uni_trans_6b956290` | TON | 32.442 | 409666492 | ~$130 |
| `uni_trans_9a0ae038` | USDT | 0.002 | 409666492 | ~$0.002 |
| `uni_trans_ebf90bee` | ETH | 0.01375 | 516601508 | ~$34 |
| `uni_trans_866903d7` | BTC | 0.000797 | 516601508 | ~$79 |
| `uni_trans_a893b645` | BTC | 0.000362 | 516601508 | ~$36 |

Note: BTC entries (qty=0.000797 and qty=0.000362) filtered from the zero-cbD CARRY_IN scan by the `quantityDelta > 0.001` threshold but confirmed still zero-cbD in direct query. Combined ~$115 BTC shortfall.

**GROUP C total: ~$279 (6 entries)**

**GROUP B (bybit-it-pair-v1):** SOL 0.3 qty, ~$0.30 shortfall (previously documented as MNT 0.3 — now shows SYMBOL:SOL).

**Total B-BYBIT-CORRIDOR-2 active shortfall: ~$2,596**

---

## B-VAULT-WITHDRAW — P1 — VAULT_WITHDRAW REALLOCATE_IN zero/near-zero cbD

**Severity**: P1
**Status**: PARTIALLY RESOLVED — Bug A (MEV Capital mevUSDC wrapper) fixed; 3 remaining cases explained; see post-fix residual table below
**Reported**: 2026-06-02 (AVCO spike scan + zero-basis CARRY_IN scan)
**Verified**: 2026-06-02

### Problem

`VAULT_WITHDRAW` REALLOCATE_IN events return stablecoin assets (USDC) to the user's wallet with near-zero or zero cost basis, even when the deposited USDC had full basis ($1/USDC). The vault position accumulated the USDC basis via REALLOCATE_OUT at deposit time, but the VAULT_WITHDRAW handler does not carry that vault position basis back to the returned USDC.

### Post-fix instance table (2026-06-02 verification)

**Fixed by Bug A (wrapper bucket mechanism):**

| ntId (short) | asset | network | qty | cbD (pre-fix) | cbD (post-fix) | status |
|---|---|---|---|---|---|---|
| `0x4e4740e3` ★ | USDC AVALANCHE | mevUSDC | 1,628 | ~$0.002 | **$1,623** | ✅ FIXED |
| `0xff65de51` ★ | USDC AVALANCHE | mevUSDC | 1,014 | ~$0.001 | **$1,001** | ✅ FIXED |
| `0xe6b02813` ★ | USDC AVALANCHE | mevUSDC | 1,000 | ~$0.001 | **$993** | ✅ FIXED (just under $1k) |
| `0x4737a9c2` | USDC ARBITRUM | MCUSDC | 1,689 | ~$0 | **$2,148** | ✅ FIXED |
| `0x6343bac5` | USDC ARBITRUM | syrupUSDC | 1,710 | ~$0 | **$2,136** | ✅ FIXED |
| (+ 20 more rows across all vault types) | | | | | $12,927 total | ✅ FIXED |

★ = MEV Capital mevUSDC wrapper on AVALANCHE (original Bug A scope)

**Total VAULT_WITHDRAW REALLOCATE_IN cbD recovered: $19,118 (28 rows, 25 with cbD>$0)**

**Remaining cbD≈$0 cases (3) — not a Bug A regression:**

| ntId (short) | network | qty | cbD | root cause |
|---|---|---|---|---|
| `0x971c8464` | ARBITRUM | 1,783 USDC | $0.001 | MCUSDC wrapper: 2nd and 3rd deposits brought USDC with cbD≈$0 (upstream shortfall at deposit time). Wrapper correctly reflects what was deposited. **IRREDUCIBLE** (refresh 5) |
| `0xc8b94615` | AVALANCHE | 2,831 USDC | $0 | **[REVISED refresh 5] Turtle Finance USDC vault** (turtleAvalancheUSDC `0x3048925b`). Deposit SWAP acquired 2,787.57 vault tokens at $2,815 basis. Vault token burn NOT in VAULT_WITHDRAW flows → basis stuck in phantom position. **FIXABLE — normalization defect.** See STATUS UPDATE section above. |
| `0xb47d87fa` | ARBITRUM | 2,825 USDC | $0 | Vault `0x6a2abff960b663462cbc46a2cfcf85063fe8ae14` — no VAULT_DEPOSIT in dataset (pre-history gap). Single REALLOCATE_IN cbD=$0; avcoAfter=$1 (existing USDC position masked AVCO impact). **IRREDUCIBLE** (refresh 5) |

Note: `0xc7aa483f` ETHEREUM (1266 USDC) still has cbD=$0 but was already excluded (avco=$1 in current position from subsequent acquisition).

### Cascade context

The AVALANCHE instances include the previously documented MEV Capital vault cascade (`0xe6b02813`). All 4 AVALANCHE instances follow the same pattern: USDC deposited via REALLOCATE_OUT (basis correctly drained) → vault generates MEVUSDC/vault shares → VAULT_WITHDRAW redeems shares → REALLOCATE_IN returns USDC at cbD≈$0.001 or $0.

### Current AVCO impact

**Current active stable positions: 0 broken.** All USDC/USDT positions currently show avco≈$1.00. The zero-basis USDC from vault withdrawals was subsequently DISPOSED or CARRY_OUT transferred, and replacement ACQUIRE events restored correct basis. The AVCO spikes (avco dropped to $0.000001) visible in ledger history were transient.

**Historical P&L impact (material):**
When the ~11,082 zero-basis USDC was later disposed or bridged out, the disposal was recorded as $0 cost basis removal instead of ~$1/USDC. This inflated apparent P&L gains (or reduced apparent losses) by ~$11,082 for those events.

### Failed stage hypothesis

`cost_basis` / `REALLOCATE_IN basis carry` — the VAULT_WITHDRAW replay handler emits REALLOCATE_IN for the returned stable with zero cbD, instead of reading the vault token position's `totalCostBasisAfterUsd` and proportionally carrying it to the returned USDC.

### Evidence state

`EVIDENCE_PRESENT_UNUSABLE` — the vault position holds accumulated basis from the REALLOCATE_OUT deposit events. The VAULT_WITHDRAW handler does not use it.

### Type adequacy

The `REALLOCATE_OUT` / `REALLOCATE_IN` pair is semantically adequate. The defect is in the basis-carry computation at VAULT_WITHDRAW time, not the type model.

### Remediation

The VAULT_WITHDRAW replay handler must:
1. At REALLOCATE_OUT (vault deposit): carry USDC basis to vault token position via REALLOCATE_IN on the vault token side.
2. At VAULT_WITHDRAW (vault redemption): perform REALLOCATE_OUT on vault token position (draining proportional basis), carry that basis to the returned USDC via REALLOCATE_IN with positive cbD.

This is the same pattern as the LP/EARN principal carry, applied to vault token positions.

---

## B-EARN-BUNDLE — P2 NEW — Bybit Earn bundle CARRY_IN zero cbD

**Severity**: P2
**Status**: ACTIVE
**Reported**: 2026-06-02 (zero-basis CARRY_IN scan)

### Problem

`bybit-it-bundle-v1` correlation CARRY_IN events for Bybit Earn position redemptions arrive at the BYBIT:33625378 main account with `costBasisDeltaUsd=0`. The Earn position (BYBIT:EARN) holds the asset with cost basis from prior acquisition, but when the asset is redeemed (transferred back from EARN to main account), the basis is not carried.

### Complete instance table (21 entries)

| asset | qty | cbD | est. missing cbD |
|---|---|---|---|
| SYMBOL:LDO | 14.97 + 102.40 + 17.81 + 27.90 = **163.08 total** | ≈$0 | ~$163 |
| SYMBOL:TON | 32.40 + 6.99 = **39.39 total** | ≈$0 | ~$118 |
| SYMBOL:LINK | 6.89 | $0 | ~$97 |
| SYMBOL:ONDO | 8.34 + 78.94 + 22.99 + 14.99 + 20.98 = **146.25 total** | ≈$0 | ~$12 |
| SYMBOL:ARB | 14.42 | $0 | ~$5 |

**Total estimated shortfall: ~$395**

Note: estimated at current market prices (LDO~$1, TON~$3, LINK~$14, ONDO~$0.08, ARB~$0.35)

### Root cause

The `bybit-it-bundle-v1` corridor path for EARN_FLEXIBLE_SAVING redemptions uses INTERNAL_TRANSFER events. The CARRY_IN to the main account from the EARN sub-account does not invoke the carry-basis logic that would propagate the EARN position's AVCO to the main account.

### Failed stage hypothesis

`move_basis` — EARN-to-main bundle transfers lack the same earnPrincipalCarrySourcePosition fallback that the EARN withdrawal path (via FUNDING_HISTORY) implements.

### Evidence state

`EVIDENCE_PRESENT_UNUSABLE` — the BYBIT:EARN position holds cost basis from prior acquisitions; the bundle transfer handler does not read it.

---

## AVCO Spike Summary (Check 1 — updated refresh 2)

**Total spikes >20% found: ~190+** (across all positions, all basisEffect types)

After triage, spikes cluster into the following classes:

| Class | Count (approx) | Example | Action required |
|---|---|---|---|
| First-acquire from zero position (artificial) | ~40 | 0xb97ef9ef avco $0→$1 (98M%) | None — expected |
| LP/vault receipt token REALLOCATE_IN | ~30 | LP-RECEIPT PANCAKESWAP spike | None — expected for LP receipts |
| Historical vault cascade artifacts (now superseded) | ~25 | USDC ARBITRUM $0.02→$49.78→$1531 | See B-VAULT-WITHDRAW |
| Bybit Earn position AVCO volatility | ~20 | ONDO EARN $0.01→$0.07 | Review with B-EARN-BUNDLE |
| Natural market price moves (ACQUIRE after dip) | ~30 | NATIVE:ARBITRUM $1179→$4090 | None — expected |
| WBTC/WETH LENDING_DEPOSIT + UNWRAP sawtooth | ~41 | WBTC $92k→null then fresh ACQUIRE, WETH→ETH blends | See note below |
| ONDO CARRY_OUT without destination CARRY_IN | ~4 | ONDO BYBIT:33625378 CARRY_OUT, corrId orphan | See B-ONDO-CARRY-1 |
| Residual corridor artifacts | ~5 | cmETH MANTLE 78.7% drop | See B-BYBIT-CORRIDOR-2 |
| Active broken positions | 2 | SYMBOL:BTC BYBIT avco=$0 | See B-CROSS-UID below |
| Other minor | ~35 | various | Low priority |

**WBTC/WETH sawtooth note (visual artifact, not accounting bug):**
WBTC is repeatedly bought via SWAP then deposited to Aave ARBITRUM (LENDING_DEPOSIT). Each deposit fully empties the wallet WBTC position (quantityAfter=0, avcoAfterUsd=null). The basis correctly moves to `AARBWBTC` (Aave receipt token). On the next SWAP, a fresh WBTC position is created at market price. This creates a sawtooth WBTC AVCO chart: repeated drops to null followed by jumps to fresh market price. 14 LENDING_DEPOSIT occurrences, 22 SWAP ACQUIREs, 0.00427 WBTC total deposited, $362 total basis correctly tracked in AARBWBTC. **No fix needed for AVCO chart; consider UI annotation for positions temporarily moved to lending.**

WETH UNWRAP (41 cases on 0x1a87f12 + 0x68bc): WETH→ETH REALLOCATE_OUT/REALLOCATE_IN blends AVCO. Jumps occur when WETH AVCO > ETH AVCO. Drops occur when WETH position empties. Accounting is correct.

**Active broken AVCO in current positions (>20% unexplained):**
- `SYMBOL:BTC` `BYBIT:33625378`: **RESOLVED** — position disposed 2025-12-12, DISPOSE cbD now -$111.74 at avBef=$96,406 ✅. P&L correctly computed.

---

## B-CROSS-UID — P2 — Cross-UID basis propagation (Bybit multi-UID scope)

**Severity**: P2
**Status**: PARTIALLY RESOLVED — UTA-sourced cross-UID carries work; BTC FUND-sourced carries now fixed; TON FUND-sourced carry still broken
**Verified**: 2026-06-02; Defect 1 BTC RESOLVED confirmed 2026-06-02 targeted audit

### Post-fix state

**Working (Defect 2 fixed — `isBybitSelfTransfer` correlationId guard active):**

| correlationId (short) | asset | source | qty | cbD | status |
|---|---|---|---|---|---|
| `bybit-cross-uid-v1:a20cadab…` | ETH | BYBIT:516601508:UTA | 0.01146 | **$42.46** | ✅ FIXED |
| `bybit-cross-uid-v1:a6fd39ab…` | ETH | BYBIT:516601508:UTA | 0.00663 | **$23.03** | ✅ FIXED |

Works because source UNIVERSAL_TRANSFER has `accountRef: 'BYBIT:516601508:UTA'` and `bybit-cross-uid-v1:` correlationId → `isBybitSelfTransfer()` returns false → CARRY_OUT emitted with correct cbD.

**Defect 1 BTC — RESOLVED (2026-06-02 targeted audit):**

| correlationId (short) | asset | source sub-acct | FUNDING_HISTORY corrId | cbD | status |
|---|---|---|---|---|---|
| `bybit-cross-uid-v1:866903d7…` | BTC | BYBIT:516601508:FUND | ✅ assigned | **-$76.86** | ✅ FIXED |
| `bybit-cross-uid-v1:a893b645…` | BTC | BYBIT:516601508:FUND | ✅ assigned | **-$34.89** | ✅ FIXED |

FUNDING_HISTORY records now have `correlationId` assigned; CARRY_OUT cbD>0 emitted correctly.

**Still broken (Defect 1 — TON only):**

| correlationId (short) | asset | source sub-acct | FUNDING_HISTORY corrId | cbD | status |
|---|---|---|---|---|---|
| `bybit-cross-uid-v1:6b956290…` | TON | BYBIT:409666492:FUND | ❌ none | **$0** | ❌ FAIL |

TON FUNDING_HISTORY outbound still lacks `correlationId`. ~$97 shortfall remains.

**Not a pipeline defect (legitimate zero):**

| correlationId (short) | asset | reason |
|---|---|---|
| `bybit-cross-uid-v1:ebf90bee…` | ETH | Source BYBIT:516601508 had no ETH in ledger before Nov 1, 2025. First ETH acquisition was Nov 3 via SWAP. Zero cost basis at source at transfer time — correct. |

### Current position impact

`SYMBOL:BTC BYBIT:33625378`: position was fully DISPOSED (SWAP) on 2025-12-12. **Now RESOLVED**: CARRY_OUT cbD correctly emitted on source (BYBIT:516601508:FUND); CARRY_IN cbD correctly applied at destination (BYBIT:33625378:FUND); SPOT DISPOSE cbD=-$111.74 correctly computed at avBef=$96,406. No remaining P&L impact.

### Remaining shortfall (FUND-sourced Defect 1)

| asset | qty | est. missing cbD | status |
|---|---|---|---|
| BTC (×2 carries) | 0.001159 | ~$112 | ✅ RESOLVED — cbD now flowing |
| TON | 32.442 | ~$97 (at $3/TON) | ❌ still broken |
| **Total active** | | **~$97** | |

### Required fix (Defect 1)

In `BybitInternalTransferPairer.pairCrossUidUniversalTransfers()` second pass: after assigning `bybit-cross-uid-v1:<uuid>` correlationId to the loner inbound, also find the FUNDING_HISTORY outbound on the source UID and assign the same correlationId. This enables the `isBybitSelfTransfer()` correlationId guard to unblock the CARRY_OUT on that record.

---

---

## B-ONDO-CARRY-1 — P2 NEW — ONDO bybit-collapsed-v1 CARRY_OUT without matching CARRY_IN

**Severity**: P2
**Status**: ACTIVE
**Reported**: 2026-06-02 (AVCO spike scan, fresh audit cycle refresh 2)

### Problem

4 ONDO CARRY_OUT events on `BYBIT:33625378` (UTA) have `bybit-collapsed-v1:` correlationId but no matching CARRY_IN exists in `asset_ledger_points`. The ONDO basis is drained from the UTA wallet with no credit issued on any destination wallet.

### Instance table

| corrId (short) | qty drained | blockTimestamp | est. missing cbD |
|---|---|---|---|
| `bybit-collapsed-v1:4f7b4cdf…` | 30.76 ONDO | 2025-02-22 | ~$37.8 |
| `bybit-collapsed-v1:7f22913f…` | 2.05 ONDO | 2025-06-22 | ~$2.5 |
| `bybit-collapsed-v1:0c7df416…` | 43.70 ONDO | 2025-10-10 | ~$53.7 |
| `bybit-collapsed-v1:49002ea9…` | 19.96 ONDO | 2025-10-10 | ~$24.5 |
| **Total** | **96.47 ONDO** | | **~$118.5** |

ONDO CARRY_OUT balance: 28 CARRY_OUTs, 24 CARRY_INs on BYBIT:33625378:EARN (4 unmatched).

### Root cause hypothesis

`bybit-collapsed-v1` corrIDs are generated for Bybit Earn staking/unstaking events collapsed from multiple source records. The matching CARRY_IN on `BYBIT:33625378:EARN` may have been generated under a different corrId or absorbed into a later bundle. The ONDO on the EARN side shows 24 CARRY_INs with 1,642.5 ONDO received vs. 472.8 ONDO sent via CARRY_OUT from UTA — suggesting EARN receives ONDO from sources other than these specific UTA CARRY_OUTs (e.g., Bybit EARN interest). However, the 4 unmatched UTA CARRY_OUTs represent real basis drain with no credit.

### Failed stage hypothesis

`move_basis` — `bybit-collapsed-v1` bundle matching logic does not ensure every CARRY_OUT has a corresponding CARRY_IN at the destination sub-account.

### Evidence state

`EVIDENCE_PRESENT_UNUSABLE` — the corrId exists but the matching CARRY_IN record is absent.

### Remediation

Verify whether the 4 ONDO CARRY_OUT quantities arrived in BYBIT:33625378:EARN under a different corrId. If yes, update the corrId to link them. If no matching EARN record exists, create the CARRY_IN with basis propagated from the CARRY_OUT's avcoBeforeUsd.

---

## B-MNT-CARRY-1 — P2 NEW — MNT INTERNAL_TRANSFER on MANTLE missing corrId → zero-basis CARRY_IN

**Severity**: P2
**Status**: ACTIVE
**Reported**: 2026-06-02 (zero-basis CARRY_IN scan, fresh audit cycle refresh 2)

### Problem

5 MANTLE INTERNAL_TRANSFER transactions moving MNT from wallet `0xa0dd42c626b002778f93e1ab42cbed5f31c117b2` to wallet `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f` have no `correlationId` on the `normalized_transactions` record. Without a corrId, the replay cannot match the CARRY_OUT (source wallet has correct basis) to the CARRY_IN (destination wallet gets 0 basis).

### Instance table

| txHash (short) | MNT qty received | Missing cbD | corrId on NT |
|---|---|---|---|
| `0xffc959c2` | 0.8 | ~$0.68 | ❌ none |
| `0x3c011394` | 25.0 | ~$21.3 | ❌ none |
| `0xe2bf4c4f` | 23.3 | ~$20.0 | ❌ none |
| `0x4fa1f2a2` | 31.1 | ~$26.6 | ❌ none |
| `0x7e5e7443` | 0.58 | ~$0.46 | ❌ none |
| **Total** | **80.78 MNT** | **~$69** | |

### Contrast — same-wallet CMETH works

CMETH INTERNAL_TRANSFER `0x2723a876` on MANTLE has `correlationId: "internal-tx:mantle:0x2723…"` and correctly emits two CARRY_IN ledger points: a qty-movement entry (seq 1328, cbD=$0) followed by a basis-transfer entry (seq 1329, cbD=+$2.13). The net effect after both entries is correct AVCO.

MNT txs lack the `internal-tx:mantle:` corrId, so neither step fires.

### Root cause

The clarification/linking stage that assigns `internal-tx:mantle:txHash` corrIds to same-universe INTERNAL_TRANSFERs on MANTLE was not applied to these 5 MNT transactions. The txs were likely processed before the corrId assignment was implemented or are missing a re-clarification run.

### Failed stage hypothesis

`linking` — the MANTLE INTERNAL_TRANSFER clarification step does not assign `internal-tx:mantle:` corrId consistently to all tracked-wallet-to-tracked-wallet transfers.

### Evidence state

`EVIDENCE_PRESENT_UNLINKED` — the CARRY_OUT on `0xa0dd42c` has correct costBasisDeltaUsd (non-zero). The CARRY_IN on `0x1a87f12` has qty but no basis because the link is missing.

### Remediation

In the MANTLE INTERNAL_TRANSFER clarification pipeline, ensure all INTERNAL_TRANSFERs between any two tracked wallets in the same accounting universe receive `correlationId = "internal-tx:mantle:<txHash>"`. Run a selective re-clarification for these 5 txHashes, then replay.

---

## B-BRIDGE-ORPHAN-1 — P3 — 4 BRIDGE_OUTs with no matching BRIDGE_IN

**Severity**: P3 (small USD impact)
**Status**: ACTIVE
**Reported**: 2026-06-02 (bridge pairing scan, fresh audit cycle refresh 2)

### Problem

4 BRIDGE_OUT transactions have a `correlationId` assigned but no matching `BRIDGE_IN` exists in `normalized_transactions`. The bridge funds reached the destination chain but the BRIDGE_IN record is absent (destination chain transaction either not in `raw_transactions` or misclassified).

### Instance table

| corrId (short) | asset | qty | source | dest (likely) | USD impact |
|---|---|---|---|---|---|
| `bridge:lifi:0xdd83df3d…` | USDC | 3.1 | ARBITRUM | unknown | ~$3.1 |
| `bridge:lifi:0x8b40041f…` | USDC | 3.14 | ARBITRUM | unknown | ~$3.1 |
| `bridge:across:0x266c0258…` | ETH | ~small | OPTIMISM | unknown | <$1 |
| `bridge:across:0x258ed5c3…` | ETH | 0.0003 | ARBITRUM | unknown | <$1 |
| **Total** | | | | | **~$7** |

### Root cause

Destination chain transactions are absent from `raw_transactions` (destination wallet not ingested for that chain/time), or the BRIDGE_IN was classified as `EXTERNAL_TRANSFER_IN` without a bridge correlationId.

For the LiFi USDC bridges (3.1 and 3.14 USDC), the counterparty address `0x89c6340b1a1f4b25d36cd8b063d49045caf3f818` on ARBITRUM is a LiFi bridge relayer. The destination chain is unknown. Check whether USDC arrived on BASE/ETHEREUM/OPTIMISM around those dates for wallet `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f`.

### Failed stage hypothesis

`linking` — cross-chain BRIDGE_IN record absent or unlinked. The `AcrossBridgePairLinkService` / `LiFiBridgePairLinkService` could not find the destination tx.

### Evidence state

`EVIDENCE_MISSING` (destination chain transaction not in raw_transactions).

### Remediation

Verify on-chain that the USDC/ETH arrived on the destination chain. If the destination transaction is tracked, inspect why it was not classified as `BRIDGE_IN` with the matching corrId. If not tracked, add the destination chain wallet to the ingestion scope.

---

## No Other Active P0 Blockers

Pipeline state:
- 0 PENDING_PRICE ✅
- 0 NEEDS_REVIEW ✅
- ETH family AVCO: $2,079–$3,822 (market-plausible, Bybit ETH at $3,822)
- USDT family AVCO: $1.00 ✅
- USDC family AVCO: $1.00 (all current positions) ✅

---

---

## Targeted Tx Audit — BTC + ETH Cluster — 2026-06-02

Universe: `df5e69cc-a0c0-4910-8b7d-74488fa266e2`  
Pipeline state at audit time: CONFIRMED=7338 | PENDING=0

### Per-Transaction Analysis Table

| ntId (short) | type | classification | corrId | cbD | AVCO before | AVCO after | cluster |
|---|---|---|---|---|---|---|---|
| `BYBIT:33625378:FUND:uni_trans_a893b645` | INTERNAL_TRANSFER | ✅ CARRY_IN | ✅ `bybit-cross-uid-v1:a893b645…` | ✅ +$34.89 | $96,406 | $96,406 | RESOLVED |
| `BYBIT:33625378:FUND:uni_trans_866903d7` | INTERNAL_TRANSFER | ✅ CARRY_IN | ✅ `bybit-cross-uid-v1:866903d7…` | ✅ +$76.86 | $96,406 | $96,406 | RESOLVED |
| `BYBIT:33625378:EXECUTION_SPOT:2290000000967318296` | SWAP | ✅ DISPOSE BTC | N/A | ✅ -$111.74 | $96,406 | $0 (fully disposed) | RESOLVED |
| `0x87503b88` MANTLE BRIDGE_OUT WBTC | BRIDGE_OUT | ✅ | ✅ `bridge:lifi:0x87503b88…` | ✅ -$19.91 | $87,869 | $87,869 | OK |
| `0xe77ad6d0` ARBITRUM SWAP USD₮0→WBTC | SWAP | ✅ | N/A | ✅ DISPOSE -$19.91 / ACQUIRE +$19.81 | $1.005 | $87,429 | OK |
| `0xa5e755a6` MANTLE CARRY_IN WETH | INTERNAL_TRANSFER | ✅ CARRY_IN | ✅ `BYBIT-CORRIDOR:MANTLE:0xa5e755a6…` | ✅ +$7,845 | N/A | $2,563 | OK |
| `BYBIT:516601508:TXN_LOG:uni_trans_a6fd39ab` | INTERNAL_TRANSFER | ✅ CARRY_OUT | ✅ `bybit-cross-uid-v1:a6fd39ab…` | ✅ -$19.80 | $2,985 | N/A | OK (prev. fixed) |
| `0xf2155c12` ARBITRUM SWAP USDC→ETH | SWAP | ✅ | N/A | ✅ DISPOSE -$20 / ACQUIRE +$20 | $1.00 | $1,926 | OK |
| `0x9c6c4c68` ARBITRUM LP_ENTRY_REQUEST GMX | LP_ENTRY_REQUEST | ✅ | ✅ `0x4e731ed5…` | ✅ REALLOCATE_OUT -$7.08 USDC / -$0.29 ETH | $1 / $1,749 | N/A | OK |
| `0x1aa3438d` ARBITRUM LP_ENTRY_SETTLEMENT GMX | LP_ENTRY_SETTLEMENT | ✅ | ✅ `gmx-lp:arbitrum:weth-usdc` | ❌ UNKNOWN / $0 for ETH+GM token | $1,749 | $1,749 | C — GMX settlement |
| `0xe06740b6` BASE LP_ENTRY PancakeSwap | LP_ENTRY | ✅ | ✅ `lp-position:base:pancakeswap:477096` | ✅ REALLOCATE_OUT -$14.62 USDC | $1 | N/A | OK |
| `0xb1e9f65d` ARBITRUM BRIDGE_OUT USDC 2,829 | BRIDGE_OUT | ✅ | ❌ MISSING | CARRY_OUT ✅ -$2,829 but no BRIDGE_IN | $1.00 | $1.00 | D — orphan |
| `0x4a2eb3ee` ARBITRUM BRIDGE_OUT USDC 1,050 | BRIDGE_OUT | ✅ | ❌ MISSING | CARRY_OUT ✅ -$1,050 but no BRIDGE_IN | $1.00 | $1.00 | D — orphan |
| `0x5ca14340` ARBITRUM BRIDGE_OUT USDC 50 | BRIDGE_OUT | ✅ | ❌ MISSING | CARRY_OUT ✅ -$50 but no BRIDGE_IN | $1.00 | $1.00 | D — orphan |
| `0x360988904f` ARBITRUM BRIDGE_OUT USDC 50 | BRIDGE_OUT | ✅ | ❌ MISSING | CARRY_OUT ✅ -$50 but no BRIDGE_IN | $1.00 | $1.00 | D — orphan |
| `0xd8b6e516` LINEA BRIDGE_OUT ETH 0.000241 | BRIDGE_OUT | ✅ | ❌ MISSING | CARRY_OUT ✅ -$0.83 but no BRIDGE_IN | $3,432 | $3,432 | D — orphan |
| `0xc69ef119` KATANA BRIDGE_IN weETH+ETH | BRIDGE_IN | ✅ | ❌ MISSING | CARRY_IN market $402 weETH + $794 ETH, no source | $3,603 ETH | $2,790 ETH | B — unlinked BRIDGE_IN |

### Cluster Table

| Cluster | Transactions | Root cause | Stage | Est. $ impact |
|---|---|---|---|---|
| **RESOLVED — B-CROSS-UID Defect 1** | `uni_trans_a893b645`, `uni_trans_866903d7`, `SPOT:2290000000967318296` | FUNDING_HISTORY corrId now assigned; both CARRY_OUTs emit cbD; SPOT DISPOSE uses correct avBef | `linking` → `replay` | ~$112 BTC basis now flowing ✅ |
| **OK — ETH corridor + Bybit ETH cross-UID** | `0xa5e755a6`, `BYBIT:516601508:TXN_LOG:a6fd39ab` | Correctly paired, cbD flowing | — | — |
| **OK — WBTC bridge pair + SWAP** | `0x87503b88`, `0x59cbe774` ARBITRUM USD₮0 BRIDGE_IN, `0xe77ad6d0` SWAP | BRIDGE_OUT/IN correctly paired via `bridge:lifi:0x87503b88…`; USD₮0 receives $19.91 carried basis | — | — |
| **OK — LP entries (GMX request + PancakeSwap)** | `0x9c6c4c68`, `0xe06740b6` | REALLOCATE_OUT correct | — | — |
| **C — GMX LP_ENTRY_SETTLEMENT UNKNOWN** | `0x1aa3438d` + 4 other settlements | All 5 GMX settlements emit UNKNOWN for ETH refund + LP token with cbD=0; systematic | `cost_basis` | ETH dust refunds; GM/GLV tokens unaccounted |
| **D — USDC bridge orphans from 0xf03b52** | `0xb1e9f65d`, `0x4a2eb3ee`, `0x5ca14340`, `0x360988904f` | All 4 BRIDGE_OUT to contract `0xf5f93d26…` ARBITRUM with no corrId; no matching BRIDGE_IN in universe | `linking` | ~$3,979 USDC missing at destination |
| **D — LINEA ETH orphan** | `0xd8b6e516` | BRIDGE_OUT ETH LINEA no corrId; coincident AVALANCHE AVAX BRIDGE_IN (`0xce1ad77f`) is independent (different asset, continuityCandidate=false) | `linking` | ~$0.83 drained with no destination |
| **B — KATANA BRIDGE_IN unlinked** | `0xc69ef119` | BRIDGE_IN weETH 0.144 + ETH 0.285 on KATANA no corrId; no BRIDGE_OUT found; basis $1,196 created at market under shortfall-fallback | `linking` | ~$1,196 uncorrelated basis arrival |

### ETH Bridge Orphans Detail

| corrId | asset | qty | source wallet | destination | USD impact | status |
|---|---|---|---|---|---|---|
| ❌ none | USDC | 2,829.12 | `0xf03b52` ARBITRUM | contract `0xf5f93d26…` ARBITRUM | ~$2,829 | no BRIDGE_IN |
| ❌ none | USDC | 1,050.58 | `0xf03b52` ARBITRUM | contract `0xf5f93d26…` ARBITRUM | ~$1,051 | no BRIDGE_IN |
| ❌ none | USDC | 50 | `0xf03b52` ARBITRUM | contract `0xf5f93d26…` ARBITRUM | ~$50 | no BRIDGE_IN |
| ❌ none | USDC | 50 | `0xf03b52` ARBITRUM | contract `0xf5f93d26…` ARBITRUM | ~$50 | no BRIDGE_IN |
| ❌ none | ETH | 0.000241 | `0x1a87f12` LINEA | unknown | ~$0.83 | no BRIDGE_IN |
| ❌ none (source) | weETH + ETH | 0.144 + 0.285 | KATANA (unknown source) | `0x1a87f12` KATANA | ~$1,196 created | BRIDGE_IN without source |

Bridge contract `0xf5f93d26229482adca3e42f84d08d549cf131658` on ARBITRUM: all 4 USDC BRIDGE_OUTs from `0xf03b52` call this contract. Likely a LiFi or Connext bridge endpoint. Destination chain and wallet unknown from current dataset.

### BTC AVCO Timeline at BYBIT:33625378

**B-CROSS-UID Defect 1 is NOW RESOLVED for both BTC entries** (queried 2026-06-02):

| event | corrId | source CARRY_OUT cbD | destination CARRY_IN cbD | status |
|---|---|---|---|---|
| `uni_trans_866903d7` 2025-12-12T10:15 | `bybit-cross-uid-v1:866903d7…` | ✅ -$76.86 (`BYBIT:516601508:FUND:FUNDING_HISTORY:56fcc99b…`) | ✅ +$76.86 (`BYBIT:33625378:FUND:UNIVERSAL_TRANSFER`) | **FIXED** |
| `uni_trans_a893b645` 2025-12-12T10:16 | `bybit-cross-uid-v1:a893b645…` | ✅ -$34.89 (`BYBIT:516601508:FUND:FUNDING_HISTORY:296e10c2…`) | ✅ +$34.89 (`BYBIT:33625378:FUND:UNIVERSAL_TRANSFER`) | **FIXED** |
| `SPOT:2290000000967318296` 2025-12-12T10:22 | — | — | DISPOSE BTC qty=0.0011591, cbD=-$111.74, avBef=$96,406 | ✅ correct |

Combined BTC CARRY_IN basis: $34.89 + $76.86 = **$111.75 ≈ SPOT DISPOSE $111.74** ✅ (rounding).  
BTC position at BYBIT:33625378 is **fully disposed** (qty=0 since 2025-12-12). Current AVCO = N/A (no open position). Historical P&L correctly reflects ~$112 cost basis.

---

## STATUS UPDATE — B-CROSS-UID Defect 1 RESOLVED (BTC)

**B-CROSS-UID** FUND-sourced Defect 1 is **RESOLVED** for the two BTC transfers (`uni_trans_866903d7` and `uni_trans_a893b645`).

Verified 2026-06-02:
- `bybit-cross-uid-v1:866903d7…` FUNDING_HISTORY now has corrId; CARRY_OUT cbD=-$76.86 ✅
- `bybit-cross-uid-v1:a893b645…` FUNDING_HISTORY now has corrId; CARRY_OUT cbD=-$34.89 ✅
- Combined $111.74 basis correctly consumed by SPOT DISPOSE at BYBIT:33625378 ✅

Remaining Defect 1 scope: TON `bybit-cross-uid-v1:6b956290…` (qty=32.442, ~$97 shortfall) — not verified in this session.

---

## B-BRIDGE-ORPHAN-2 — P2 NEW — 4 USDC BRIDGE_OUTs from 0xf03b52 ARBITRUM with no BRIDGE_IN

**Severity**: P2
**Status**: ACTIVE
**Reported**: 2026-06-02 (targeted tx audit)

### Problem

4 `BRIDGE_OUT` transactions on ARBITRUM from wallet `0xf03b52e8686b962e051a6075a06b96cb8a663021` all target contract `0xf5f93d26229482adca3e42f84d08d549cf131658` with USDC as the main bridged asset. None have a `correlationId`. None have a matching `BRIDGE_IN` in any tracked wallet. The CARRY_OUT basis is correctly drained at the source (cbD>0), but the basis is never credited at the destination.

### Instance table

| txHash (short) | network | asset | qty | CARRY_OUT cbD | date | BRIDGE_IN found |
|---|---|---|---|---|---|---|
| `0xb1e9f65d` | ARBITRUM | USDC | 2,829.12 | -$2,829.12 | 2026-01-12 | ❌ none |
| `0x4a2eb3ee` | ARBITRUM | USDC | 1,050.58 | -$1,050.58 | 2026-01-13 | ❌ none |
| `0x5ca14340` | ARBITRUM | USDC | 50 | -$50.01 | 2026-01-15 | ❌ none |
| `0x360988904f` | ARBITRUM | USDC | 50 | -$50.00 | 2026-02-10 | ❌ none |
| **Total** | | | **3,979.70** | **-$3,979.71** | | |

Each BRIDGE_OUT also carries out a small ETH amount (~0.000064 ETH, cbD≈$0.19) — likely a native gas forward. ETH CARRY_OUTs are also unmatched.

### Root cause

The bridge linking service did not assign a `correlationId` to these BRIDGE_OUTs. The destination chain transactions are either:
1. On a chain not tracked in this universe, or
2. On a tracked chain but not classified as `BRIDGE_IN` with matching corrId

### Failed stage hypothesis

`linking` — `LiFiBridgePairLinkService` (or equivalent) did not produce corrIds for these txs. The destination chain/wallet is not in `raw_transactions`.

### Evidence state

`EVIDENCE_MISSING` — destination chain transactions absent from dataset. Contract `0xf5f93d26229482adca3e42f84d08d549cf131658` on ARBITRUM requires identification to determine destination chain.

### Remediation

1. Identify bridge contract `0xf5f93d26229482adca3e42f84d08d549cf131658` on ARBITRUM (likely LiFi or Connext router).
2. Look up the 4 destination transactions on-chain for wallet `0xf03b52e8686b962e051a6075a06b96cb8a663021` around the dates above.
3. If destination wallet is tracked: re-run bridge pairing with corrId assignment.
4. If destination wallet is external (Bybit deposit or external wallet): these exits are correct — CARRY_OUT drains basis, destination is outside the universe.

---

## B-BRIDGE-ORPHAN-1 — EXTENSION — LINEA ETH orphan added

(Extends existing B-BRIDGE-ORPHAN-1 entry.)

**New instance:**

| corrId | asset | qty | source | dest | USD impact |
|---|---|---|---|---|---|
| ❌ none | ETH | 0.000241 | `0x1a87f12` LINEA | unknown | ~$0.83 |

`0xd8b6e516c96c923ed30d8d66ec2886e48828efdd84dca05dfb1aafb700dd6c83` — BRIDGE_OUT ETH on LINEA, CARRY_OUT cbD=-$0.83, no corrId.

A coincident AVALANCHE AVAX BRIDGE_IN (`0xce1ad77f`, +2 sec, same wallet) is **NOT** the counterpart: it receives AVAX (different asset), has `continuityCandidate=false`, and its own CARRY_IN cbD=$0.67 from existing AVCO. These are independent events. The LINEA ETH basis of $0.83 has no destination credit in the dataset.

---

## B-KATANA-BRIDGE-IN-1 — P3 NEW — KATANA BRIDGE_IN weETH+ETH without matched source

**Severity**: P3
**Status**: ACTIVE
**Reported**: 2026-06-02 (targeted tx audit)

### Problem

`0xc69ef119e3658ad023b85e0866231b31cbc08aebe0c4b2eed62df47ea00b7be` is classified as `BRIDGE_IN` on KATANA for wallet `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f`. It receives weETH (0.144, cbD=+$402) and ETH (0.285, cbD=+$794). No `correlationId` is set; no matching BRIDGE_OUT found. The basis was assigned at market price under the unmatched-BRIDGE_IN fallback (B-SHORTFALL-1 pattern).

Raw data: `to=0x223ec22d67716fca620aee72b25ffe4ece436f25` — contract identity on KATANA not established in current dataset.

The nearby BRIDGE_OUT `0x826f66b6` (ARBITRUM ETH, 2025-11-21T06:48, 0.07 ETH) is **already correctly matched** to a ZKSYNC BRIDGE_IN (`0xfba1a0df`) via `bridge:lifi:0x826f66b6…` and is NOT the source of this KATANA inflow.

### Evidence state

`EVIDENCE_MISSING` — source chain transaction not in `raw_transactions`. No BRIDGE_OUT with matching corrId or amounts found.

### Accounting impact

$1,196 of basis ($402 weETH + $794 ETH) was created at market price. If a corresponding BRIDGE_OUT exists on an untracked source chain (basis correctly drained there), the net accounting effect is: source basis lost externally, destination basis fresh at market → acceptable under unmatched BRIDGE_IN repricing policy. If no source BRIDGE_OUT exists, the CARRY_IN was a fresh acquisition on KATANA and market-price basis is correct.

### Remediation

Identify `0x223ec22d67716fca620aee72b25ffe4ece436f25` on KATANA. Determine whether a matching BRIDGE_OUT exists on any tracked chain around 2025-11-21T07:02. If untracked source exists and matters for carry, add source wallet/chain to ingestion scope.

---

## B-GMX-LP-SETTLEMENT-UNKNOWN — P3 NEW — GMX v2 LP_ENTRY_SETTLEMENT UNKNOWN basisEffect

**Severity**: P3
**Status**: ACTIVE
**Reported**: 2026-06-02 (targeted tx audit)

### Problem

All 5 `LP_ENTRY_SETTLEMENT` events under `corrId=gmx-lp:arbitrum:weth-usdc` on ARBITRUM emit `basisEffect=UNKNOWN` and `costBasisDeltaUsd=0` for **both** the ETH execution-fee refund and the GM/GLV LP token received. This is a systematic pattern across all settlements in this position's history.

### Instance table

| txHash (short) | date | ETH qty received | LP token | LP token qty | cbD ETH | cbD LP token |
|---|---|---|---|---|---|---|
| `0x9fab1650` | 2025-09-04 | 0.0000033 ETH | GLV [WETH-USDC] | 40.35 | $0 UNKNOWN | $0 UNKNOWN |
| `0x3ad60ac2` | 2025-12-16 | 0.0000194 ETH | GM: ETH/USD [WETH-USDC] | 529.62 | $0 UNKNOWN | $0 UNKNOWN |
| `0x61c1272c` | 2026-01-29 | 0.000046 ETH | GM: ETH/USD [WETH-USDC] | 21.60 | $0 UNKNOWN | $0 UNKNOWN |
| `0x1aa3438d` | 2026-02-06 07:32 | 0.0000528 ETH | GM: ETH/USD [WETH-USDC] | 97.96 | $0 UNKNOWN | $0 UNKNOWN |
| `0x52924cd8` | 2026-02-06 07:53 | 0.0000535 ETH | GM: ETH/USD [WETH-USDC] | 4.63 | $0 UNKNOWN | $0 UNKNOWN |

The `LP_ENTRY_REQUEST` events correctly emit `REALLOCATE_OUT` (USDC and ETH drained with cbD>0). The LP position's principal basis is therefore correctly established at deposit time. The settlement step then records receipt of the LP token under `UNKNOWN`.

### Analysis

In WalletRadar's LP accounting model, the LP token (GM/GLV) is a receipt marker; the actual economic basis lives in the LP position corridor account (not in the token). The REALLOCATE_OUT at LP_ENTRY_REQUEST already moved the user's USDC+ETH basis to the LP position. The settlement receiving GM tokens with UNKNOWN/cbD=0 may be intentional (LP token is not the basis carrier).

However, the ETH execution-fee refund (tiny amounts, max 0.0000535 ETH ≈ $0.09) with UNKNOWN also has cbD=0 and AVCO unchanged — consistent with it being treated as a negligible gas-return noise event.

### Risk

If an LP_EXIT or LP_EXIT_SETTLEMENT handler returns basis via the GM token balance (rather than via the LP position corridor), the UNKNOWN basisEffect on the GM token would cause a zero-basis exit and inflated gains. If the LP exit correctly reads the corridor position's accumulated basis, UNKNOWN on GM token is harmless.

### Failed stage hypothesis

`cost_basis` / `LP_ENTRY_SETTLEMENT` handler — the handler emits UNKNOWN for GM/GLV receipts and ETH refunds. Whether this is intentional (LP token = receipt only) or a gap in the settlement path requires verification against LP_EXIT accounting.

### Evidence state

`EVIDENCE_PRESENT_UNUSABLE` — the LP position corridor holds basis from REALLOCATE_OUT; settlement does not carry it to the LP token.

### Remediation

Verify: when this GMX position is eventually exited (`LP_EXIT_SETTLEMENT`), does the exit handler emit a REALLOCATE_IN on USDC/ETH from the LP position corridor with cbD>0? If yes, UNKNOWN at settlement is acceptable and no fix needed. If not, the LP position basis is silently lost at exit.

---

## Previously Resolved

| ID | Fix | Status |
|----|-----|--------|
| B-SPIKE-1 | BRIDGE_OUT secondary positive flow retagging regression | **RESOLVED** |
| B-BRIDGE-1 | Across threshold relaxation | **RESOLVED** |
| B-SPIKE-2 | SPONSORED_GAS_IN GAS_ONLY | **RESOLVED** |
| B-BTC-1 | BTC cross-UID corridor zero-qty DISPOSE | **RESOLVED** |
| B-CORRIDOR-1 | "BRIDGE_IN cbD=0 basis loss" | **RETRACTED** |
| B-USDC-1 | BORROW using perWalletAvco instead of market price | **RESOLVED** |
| B-SHORTFALL-1 | Zero-basis BRIDGE_INs repriced at market | **RESOLVED** |
| B-BYBIT-CORRIDOR-2 sub-A | 21/23 corridor CARRY_INs now have cbD>0 | **PARTIALLY RESOLVED** |
| B-VAULT-WITHDRAW Bug A | mevUSDC wrapper bucket mechanism implemented; $19,118 cbD recovered | **PARTIALLY RESOLVED** |
| B-CROSS-UID (Defect 2) | isBybitSelfTransfer correlationId guard working for UTA-sourced carries | **PARTIALLY RESOLVED** |
| B-CROSS-UID (Defect 1 BTC) | FUNDING_HISTORY corrId now assigned; both BTC CARRY_OUTs emit cbD>0; SPOT DISPOSE uses correct avBef=$96,406 | **RESOLVED** |
