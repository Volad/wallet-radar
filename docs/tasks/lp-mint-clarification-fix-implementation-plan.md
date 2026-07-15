# Implementation Plan: LP Mint Clarification Fix (BLOCKER-LP-MINT)

**Date:** 2026-07-15  
**Blocker ID:** BLOCKER-LP-MINT  
**Slug:** lp-mint-clarification-fix  
**Priority:** CRITICAL  
**Financial Impact:** $4,737.09 total basis leaked across 4 LP positions

---

## Scope

- **Wallets:** `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f`, `0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f`
- **Networks:** BASE (3 txs), OPTIMISM (1 tx)
- **Affected transactions:**

| TX Hash | Network | Pool | NFT | Leaked Basis |
|---|---|---|---|---|
| `0x511a01ea...` | BASE | PancakeSwap V3 ETH/USDC | #477096 | $2,013.22 |
| `0xf833b709...` | BASE | PancakeSwap V3 ETH/USDC | #448475 | $1,753.28 |
| `0x2d2a8f1b...` | OPTIMISM | Uniswap V3 USDT0/USDC | #2673992 | $948.62 |
| `0x4f4621f4...` | BASE | PancakeSwap V3 CAKE/USDC | #646414 | $21.97 |

---

## Root Cause

### Failed Stage: `clarification`

All 4 transactions are `mint()` calls on NonfungiblePositionManagers (V3 concentrated liquidity). The NFT tokenId emitted by `mint()` appears only in the **full transaction receipt** as:
1. `ERC-721 Transfer(from=0x0, to=user, tokenId=X)` event
2. `IncreaseLiquidity(tokenId=X, ...)` event

The Blockscout token transfers API returns only ERC-20 transfers — so the NFT tokenId is absent from the initial `rawData.tokenTransfers`. Without the tokenId, no `correlationId` is set, no LP-RECEIPT basis pool is created, and REALLOCATE_OUT fires on input assets with nowhere to REALLOCATE_IN.

**Key evidence:** All 4 transactions have:
```
clarificationEvidence.clarificationAttempts: 3
clarificationEvidence.fullReceiptClarificationAttempts: 0   ← never triggered
clarificationEvidence.lastClarificationFailureReason: "CLARIFICATION_FULL_RECEIPT_UNAVAILABLE"
```

The full receipt fetch code path was never invoked for these `LP_ENTRY` transactions. The clarification service needs to trigger a full receipt fetch when:
- Transaction type is `LP_ENTRY`, AND
- No LP-RECEIPT flow is present in the normalized flows (i.e., no `assetSymbol` starting with `LP-RECEIPT:` with positive quantityDelta), AND
- `correlationId` is not set

---

## Changes — Ordered Upstream First

> **Status: IMPLEMENTED** (2026-07-15)  
> Reviews: [Financial Auditor: APPROVED], [Business Analyst: NEEDS_REVISION → addressed], [System Architect: NEEDS_REVISION → addressed]

### Task 1 (IMPLEMENTED): Clarification Trigger in `ReceiptClarificationEligibilitySupport`

**File:** `backend/core/src/main/java/com/walletradar/application/linking/pipeline/clarification/ReceiptClarificationEligibilitySupport.java`  
**Method:** `isLpPositionCorrelationCandidate()`

Added new early-return path BEFORE the `LP_POSITION_CORRELATION_REQUIRED` gate. This path fires when:
- `type == LP_ENTRY`
- `correlationId` is null or blank
- `fullReceiptClarificationAttempts < 3` (prevents infinite loops)

The existing `LP_POSITION_CORRELATION_REQUIRED` gate and all downstream paths are preserved unchanged. No code change needed in `ReceiptClarificationGateway` — ERC-721 parsing already implemented in `LpPositionLifecycleSupport.extractErc721TokenIdForWallet`.

### Task 2 (EXISTING — no change needed): ERC-721 Transfer Event Parser

**File:** `backend/core/src/main/java/com/walletradar/application/normalization/pipeline/classification/support/LpPositionLifecycleSupport.java`  
**Method:** `extractErc721TokenIdForWallet()`

Already correctly implemented. Discriminates ERC-721 from ERC-20 by checking `topics.size() >= 4`. When `persistedLogs()` contains the full receipt (after Task 1 triggers a fetch), this method extracts the NFT `tokenId`.

Note on `IncreaseLiquidity` event: correct topic hash is `0x3067048beee31b25b2f1681f88dac838c8bba36af25bfb2b7cf7473a5847e35f` (64 hex chars). The ERC-721 Transfer from `address(0)` is the primary and sufficient source; `IncreaseLiquidity` is a secondary fallback.

### Task 3 (NOT NEEDED): DB Reset

No DB reset required. The new condition in `isLpPositionCorrelationCandidate` checks `correlationId == null` and `fullReceiptClarificationAttempts < 3` directly on the `normalized_transactions` document. The 4 affected transactions already satisfy both conditions. No field reset in `raw_transactions.clarificationEvidence.*` is needed (that collection is not read by the query service).

### Task 4 (IMPLEMENTED): Query Service — CONFIRMED LP_ENTRY selector

**File:** `backend/core/src/main/java/com/walletradar/application/linking/pipeline/clarification/PendingReceiptClarificationQueryService.java`  
**Method:** `loadNextBatch()`

Added `lpEntryMissingCorrelationCriteria` — selects LP_ENTRY transactions with status `CONFIRMED` or `PENDING_PRICE` that have `correlationId` null or missing. Added to the `orOperator` of the main query alongside `lpPositionCorrelationRecoveryCriteria`.

This addresses the root query gap: `lpPositionCorrelationRecoveryCriteria` only matched `PENDING_CLARIFICATION` status, which excluded all 4 CONFIRMED transactions.

### Task 5 (VERIFICATION): LP-RECEIPT Flow Synthesis

`correlationId` → LP-RECEIPT flow synthesis is handled automatically by `LpNftClFlowMaterializer.enrich()` during renormalization. No code change required. The `correlationId` format must be lowercase: `lp-position:{networkId}:{nftManagerAddress}:{tokenId}`.

---

## Documentation Updates

1. **`docs/pipeline/normalization/rules/lp-receipt-linking.md`** (create if not exists):
   - Document the LP mint tokenId extraction requirement
   - Specify that ERC-721 Transfer from address(0) in full receipt is the authoritative source for LP NFT tokenId
   - Specify `IncreaseLiquidity` event (correct hash: `...5847e35f`) as secondary source

2. **ADR reference:** Use ADR-058 (ADR-055 was already taken at time of plan)

---

## Acceptance Criteria

After rebuild and pipeline completion:

1. **Clarification retry:** All 4 transactions have `fullReceiptClarificationAttempts ≥ 1` and `correlationId` set
2. **LP-RECEIPT basis:** LP-RECEIPT #477096 total basis = $2,013.22 + $60.50 + $28.47 = **$2,102.19**
3. **ETH AVCO after LP_EXIT:** FAMILY:ETH AVCO after `0x6b57e6...` = **~$3,867/ETH** (not $165)
4. **LP-RECEIPT #448475 basis:** $1,753.28 correctly credited
5. **USDT0/USDC OPTIMISM #2673992 basis:** $948.62 correctly credited (stablecoin, minimal AVCO impact but basis must be present)
6. **LP-RECEIPT #646414 basis:** $21.97 correctly credited
7. **No new LP_ENTRY without correlationId** in normalized_transactions (regression check)
8. `asset_ledger_points` for affected LP_EXIT transactions show **REALLOCATE_IN** (not UNKNOWN) with non-zero basis transfer

---

## Risks

1. **Full receipt availability:** Old transactions (Jun–Aug 2025) may have pruned receipts on archive nodes. If receipts are unavailable, manual `correlationId` injection will be required as a fallback.

2. **Ordering risk:** If the 4 transactions are re-processed before the replay handler is updated to correctly use the newly set `correlationId`, the LP-RECEIPT basis pool may still not be created. Ensure Task 1+2 are deployed before resetting clarification state (Task 3).

3. **False positive trigger:** The full receipt trigger must not fire for LP_ENTRY transactions where the correlationId was correctly set (e.g., via `LP_POSITION_STAKE` intermediate events). Guard with `correlationId == null` check.

4. **REALLOCATE_OUT already applied:** The 4 affected LP_ENTRY transactions already applied REALLOCATE_OUT to the source asset pools (WETH, USDC, CAKE, USDT0). After the fix, the replay will add REALLOCATE_IN to LP-RECEIPT pools. This is correct — the source asset deductions stand, and the LP-RECEIPT now correctly holds the basis. No double-counting risk.

5. **Downstream LP_EXIT effects:** All 4 LP positions are already closed. After fixing the LP-RECEIPT basis, the LP_EXIT replay for each position must re-apply REALLOCATE_OUT (LP-RECEIPT) → REALLOCATE_IN (returned assets). This requires a full `--skip-frontend` rebuild, not just `--linking-only`.

---

## Build Command

```bash
./scripts/prod-reset-rebuild-backend.sh --skip-frontend
```

Run after Tasks 1–3 are deployed. Wait for full normalization + replay before re-audit.
