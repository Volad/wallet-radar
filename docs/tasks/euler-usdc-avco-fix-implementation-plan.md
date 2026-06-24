# Implementation Plan: Euler V2 eUSDC-2 Phantom Gains + USDC AVCO Inflation Fix

**Audit source:** [residual-audit-p1](46a7b814-0584-4279-9070-1c6090d7edd1)  
**Date:** 2026-06-21  
**Status:** Approved after Phase 3 review (all three reviewers)  
**Reviewed by:** [financial-auditor](22c9b5c9-e44c-4ae1-9983-1b194960724d), [system-architect](bf81ae6f-8b58-4ed3-99e8-6f24cd321f22), [business-analyst](60e34d27-992d-419a-a3a2-430f58f0d2cf)

---

## Scope

- **Session:** `df5e69cc-a0c0-4910-8b7d-74488fa266e2`
- **Networks:** Avalanche, Arbitrum, ZkSync, Mantle (downstream)
- **Assets:** eUSDC-2, eUSDT-2 (Euler V2 vault shares), USDC
- **Conservation delta before:** `+$544.91` (threshold `$70.21`)

---

## Root Cause Summary (revised after review)

| ID | Stage | Delta impact | Description |
|---|---|---|---|
| RC-1 | classification | +$3,685 | Euler EVC Avalanche txs misclassified as EXTERNAL_TRANSFER_IN → 5,267 eUSDC-2 added at $0 basis + `isShareLikeSymbol` 'e' prefix bug |
| RC-2 | replay / corridor | +$2,951 basis destroyed | LENDING_DEPOSIT corridor dispatch weighted by raw token quantity; ERC20:c9afac has 4.5B raw units → absorbs all outgoing eUSDC-2 basis; fix: distribute by USD value |
| RC-3 | replay / vault | −$2,712 total | VAULT_DEPOSIT/WITHDRAW proportional carry: USDC AVCO drifts upward per cycle ($1.249→$1.516) on Arbitrum; also propagates to Mantle via USDC bridge (the −$640 Mantle phantom loss is a **downstream** effect, not an independent LP issue) |

**~~RC-3 LP exit~~**: REMOVED. Financial auditor confirmed no LP exit on Mantle returns USDC. The Mantle phantom loss (−$640) is downstream of vault cycling AVCO inflation on Arbitrum. Fixing RC-3 (vault carry) automatically eliminates it.

**⚠️ Critical coupling:** RC-1/RC-2 remove `+$3,685` phantom gains; RC-3 (vault) removes `−$2,712` phantom losses. All three must be applied together. Partial application will worsen the delta.

**Dependency order:** T-04 (no deps) → T-01 → T-02 (depends on T-01).

**Expected delta after all fixes:** `+$544.91 − $3,685 + $2,712 ≈ −$428` (residual −$428 from minor unquantified effects; tracked as RC-5, requires follow-up audit).

---

## Tasks

### T-01: Create `EulerEvcClassifier` + fix `isShareLikeSymbol` 'e' prefix bug (RC-1)

**Stage:** classification  
**Priority:** implement after T-04  
**New file:**
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/onchain/protocol/euler/EulerEvcClassifier.java`
  - Inserted at **STANDARD** classification point (not FINAL_FALLBACK)
  - Detection rule: `network == AVALANCHE` AND `toAddress == "0xddcbe30a761edd2e19bba930a977475265f36fa1"` AND `methodId == "0xc16ae7a4"` → classify as `LENDING_LOOP_REBALANCE`
  - The replay route `LENDING_LOOP_REBALANCE → EulerLoopReplayHandler` is already wired; it handles basis carry for Euler loop operations

**Existing file:**
- `backend/src/main/resources/protocol-registry.json` — add entry: `"0xddcbe30a761edd2e19bba930a977475265f36fa1"` → `"Euler V2"` on `AVALANCHE`
- Fix `isShareLikeSymbol` 'e' prefix bug: ensure eUSDC-2 and eUSDT-2 tokens are recognized as Euler V2 vault share receipts (NOT as EXTERNAL_TRANSFER_IN assets). The 'e' prefix detection must require a known Euler vault contract, not just any symbol starting with 'e'.

**Why:** The three Avalanche txs (`0x305f37`, `0x1e0c42`, `0x233c2b`) call Euler EVC with method `0xc16ae7a4`, delivering eUSDC-2 vault shares within a leveraged loop. Classifying as `LENDING_LOOP_REBALANCE` routes them to `EulerLoopReplayHandler` which already performs basis carry — removes 5,267 eUSDC-2 tokens added at $0.

**Acceptance:** After rebuild:  
```
db.normalized_transactions.find(
  {txHash: {$in: ['0x305f37a6...','0x1e0c4295...','0x233c2b95...']}},
  {type:1}
)
```
→ All show `type == LENDING_LOOP_REBALANCE` (not `EXTERNAL_TRANSFER_IN`).  
No eUSDC-2 ledger point with AVCO below $0.90.

**Test:** Add `EulerEvcClassifierTest`: AVALANCHE + EVC address + methodId `0xc16ae7a4` → `LENDING_LOOP_REBALANCE`. Test that known-non-Euler eUSDC symbols on other networks do NOT trigger this classifier.

---

### T-02: Fix LENDING_DEPOSIT corridor basis dispatch — distribute by USD value (RC-2)

**Stage:** replay / corridor  
**Priority:** implement after T-01 (depends on T-01 fixing the misclassification)  
**File:** `backend/src/main/java/com/walletradar/costbasis/application/replay/handler/TransferReplayHandler.java`  
**Method:** `applyTransfer()` → `isBucketInbound` branch

**Change:** When dispatching outgoing carry basis across REALLOCATE_IN legs in a LENDING_DEPOSIT corridor batch:

1. For each candidate REALLOCATE_IN leg, compute its `usdWeight = qty × unitPriceUsd` (0 if unpriced)
2. Distribute outgoing basis proportionally to `usdWeight`, NOT to raw token quantity
3. **Fallback (all-unpriced):** If ALL REALLOCATE_IN legs have `unitPriceUsd == null` or `unitPriceUsd == 0`, do NOT dispatch basis — leave it in the source pool (do not silently destroy it). Log a warning.

**Scope guard:** Only apply this inside the `isBucketInbound` branch when `classifier.isCorridorTransfer(transaction) && transaction.getType() == LENDING_DEPOSIT`.

**Why:** Txs `0x08e6af7e` and `0xa548b357` destroy $1,108 + $1,842 = $2,951 of eUSDC-2 basis. Root cause: ERC20:c9afac has ~4.5 billion raw units, dominating the raw-quantity-weighted dispatch and receiving nearly all outgoing basis. Distributing by USD value (ERC20:c9afac is unpriced → $0 weight) ensures eUSDC-2 REALLOCATE_IN receives full carry.

**Acceptance:** After rebuild:  
`asset_ledger_points` for eUSDC-2 at txs `0x46177d31` and `0xe48503f1`:  
`realizedGainLossUsd < 50` (was $1,379 and $1,496).  
No LENDING_DEPOSIT produces AVCO below $0.90 for eUSDC-2.

**Test:** Unit test: LENDING_DEPOSIT corridor — 1 priced eUSDC-2 REALLOCATE_IN (1,000 tokens @ $1.00) + 1 unpriced ERC20 REALLOCATE_IN (4,500,000,000 raw units @ $0) → full $1,000 basis goes to eUSDC-2; unpriced leg receives $0.  
Unit test (all-unpriced fallback): LENDING_DEPOSIT with all REALLOCATE_IN legs unpriced → basis stays in source pool, warning logged.

---

### T-04: Fix VAULT_WITHDRAW proportional carry — `TransferReplayHandler.restoreFullBucket()` (RC-3)

**Stage:** replay / vault  
**Priority:** implement FIRST (no dependencies)  
**File:** `backend/src/main/java/com/walletradar/costbasis/application/replay/handler/TransferReplayHandler.java`  
**Method:** `restoreFullBucket()` (lines ~796–812)

**Change:** When a VAULT_WITHDRAW returns `qty_returned` tokens from a deposit carry of `qty_deposited` with `basis_deposited`:

```java
BigDecimal ratio = qtyReturned.divide(carry.quantity(), MathContext.DECIMAL64)
                              .min(BigDecimal.ONE); // cap at 1.0 for yield case
BigDecimal proportionalBasis = carry.costBasisUsd().multiply(ratio);
```

- **If `qty_returned < qty_deposited`** (vault fee/rounding): basis = `basis_deposited × qty_returned/qty_deposited` → prevents AVCO drift upward
- **If `qty_returned > qty_deposited`** (yield earned): cap ratio at 1.0 → basis = `basis_deposited`; excess qty enters via existing BUY/income path (do NOT inflate carry basis)
- **Rounding tolerance:** if `|qty_returned − qty_deposited| / qty_deposited < 0.0001` (0.01%), treat as same-day round-trip and use full carry (no drift for dust differences)

**Why:** VAULT_DEPOSIT of 1,000 USDC at AVCO $1.249 carries $1,249 basis. On VAULT_WITHDRAW returning 998 USDC (vault fee), current code carries back full $1,249 → AVCO = $1.251. Over 3 cycles: $1.249 → $1.258 → $1.271 → $1.516. Final REPAY at $1.516 AVCO → −$932.83 phantom loss. Proportional carry: $1,249 × (998/1000) = $1,246.50 → AVCO ≈ $1.249 (stable). Also fixes the Mantle phantom loss (−$640) which is downstream of this AVCO inflation.

**Acceptance:** After rebuild:  
ARBITRUM USDC AVCO ≤ $1.05 at all `asset_ledger_points`.  
MANTLE USDC AVCO ≤ $1.05 at all `asset_ledger_points` (was $1.99 — downstream fix).  
`0x0399f820` (REPAY) `realizedGainLossUsd` < $10 (was −$932.83).  
`0xeac040af` (SWAP, Mantle) `realizedGainLossUsd` < $10 (was −$640.90).

**Test:**  
Unit test (fee/loss): VAULT_DEPOSIT(1,000 USDC, basis=$1,249) → VAULT_WITHDRAW(998 USDC) → returned basis = $1,246.50, AVCO ≈ $1.249.  
Unit test (yield/gain): VAULT_DEPOSIT(1,000 USDC, basis=$1,249) → VAULT_WITHDRAW(1,010 USDC) → carry basis = $1,249 (capped), excess 10 USDC enters BUY path.  
Unit test (round-trip tolerance): VAULT_WITHDRAW(1,000.0001 USDC) from deposit of 1,000 USDC → treated as full carry (0.00001% < 0.01% tolerance).

---

## Implementation Order

1. **T-04** — no dependencies; implement first
2. **T-01** — requires new classifier + protocol-registry entry + isShareLikeSymbol fix
3. **T-02** — depends on T-01 being deployed first (T-01 fixes the misclassification that feeds the corridor)

All three must be applied in a **single rebuild**. Do not do partial rebuilds between tasks.

---

## Acceptance Criteria

**Phase 1 gate (this cycle):**
- Conservation delta within ±$500 (explicitly accepted as intermediate)
- `conservationBreached = true` is acceptable at this phase
- No eUSDC-2 AVCO below $0.90
- USDC AVCO ≤ $1.10 on all networks
- Corridor breaches ≤ 2 (pre-existing USDC data-repair breaches)
- All existing tests pass (2046+)

**Phase 2 gate (follow-up RC-5 audit):**
- `conservationBreached = false` (delta ≤ threshold $70.21)
- Tracked as RC-5 ticket; residual ~−$428 requires separate investigation

**Rollback trigger:** If post-rebuild corridor breach count > 2, revert all three changes together.

---

## Risks

- **RC-2 scope risk:** USD-value-weighted dispatch must not break other LENDING_DEPOSIT batches where all legs ARE priced. The fallback (leave basis in source) ensures no silent destruction.
- **T-04 scope risk:** Proportional carry affects ALL VAULT_WITHDRAW events across all networks. Must verify ZKSYNC vault cycling is also corrected (observed $1.322 and $1.955 AVCO there).
- **Residual delta (RC-5):** After all fixes, delta expected ~−$428. This represents unquantified minor effects (MNT, TON, other carry adjustments). A follow-up audit is required to close within threshold.
- **All-at-once deployment:** Do NOT apply T-04 alone before T-01/T-02 — doing so removes the USDC phantom losses (−$2,712) without removing the eUSDC-2 phantom gains (+$3,685), temporarily worsening the delta to ~+$3,229.
