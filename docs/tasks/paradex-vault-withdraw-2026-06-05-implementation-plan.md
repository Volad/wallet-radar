# Implementation Plan: B-VAULT-WITHDRAW-ZERO-BASIS — Paradex L1 Core mis-classification

**Date:** 2026-06-05  
**Severity:** P1  
**Estimated financial impact:** ~$953 USDC disposal basis shortfall (BASE wallet `0x1a87f12a`)  
**Stage:** `classification` (earliest failed stage)

---

## 1. Symptom

`VAULT_WITHDRAW REALLOCATE_IN USDC` on Ethereum (`0xc7aa483f...`) returns 1,266 USDC at **$0 cost basis** (correct: $1,266.47 @ $1.00/USDC). This propagates through Avalanche Aave → bridge → BASE wallet causing USDC AVCO $0.22 instead of ~$1.00.

---

## 2. Root Cause

### 2.1 Paradex L1 Core is in registry but ignored
Contract `0xe3cbe3a636ab6a754e9e41b12b09d09ce9e53db3` is catalogued in `protocol-registry.json` with `event_type: PROTOCOL_CUSTODY_WITHDRAW`. Paradex is a StarkEx-based L2 perp DEX that holds USDC in custody — no receipt token is ever minted.

### 2.2 Classification falls through to function-name heuristic
When `protocolResolutionState = TERMINAL_METADATA_ONLY`, the classification pipeline uses the function signature `withdraw(address,uint256,address)` → infers `VAULT_WITHDRAW`. The registry's explicit `event_type` is ignored at this resolution state.

### 2.3 VAULT_WITHDRAW accounting hits empty pool
`VAULT_WITHDRAW` produces a `REALLOCATE_IN` ledger point, which searches for a vault receipt-token position to carry basis from. Since Paradex never issued receipt tokens and no `VAULT_DEPOSIT` exists for this wallet, the pool is empty → `costBasisDeltaUsd = $0`.

---

## 3. Fix

### Fix 1 (classification): respect registry `event_type` at TERMINAL_METADATA_ONLY
**File to identify:** The classification service that resolves protocol event type from `protocolResolutionState`. Look in `backend/**/ingestion/**/classification/**` or `backend/**/ingestion/**/protocol/**`.

When `protocolResolutionState == TERMINAL_METADATA_ONLY` AND the registry entry has an explicit `event_type` field (non-null, non-empty), use the registry `event_type` to set the normalized transaction type. Do NOT fall through to function-name heuristics.

### Fix 2 (accounting): PROTOCOL_CUSTODY_WITHDRAW with no deposit → ACQUIRE at stablecoin price
**File to identify:** The replay or accounting handler for `PROTOCOL_CUSTODY_WITHDRAW` type.

When processing a `PROTOCOL_CUSTODY_WITHDRAW` (or `VAULT_WITHDRAW` with `TERMINAL_METADATA_ONLY`) and no matching vault deposit/receipt-token pool exists:
- If the asset is a known stablecoin (USDC, USDT, DAI, etc.): emit `ACQUIRE` at $1.00/unit price
- Otherwise: emit `ACQUIRE` at spot market price at block timestamp

This handles the general case where a protocol returns principal without having tracked the deposit (either because the deposit predates the backfill window or was mis-classified).

---

## 4. Acceptance Criteria

After prod rebuild:
1. Ethereum `0xc7aa483f...` classified as `PROTOCOL_CUSTODY_WITHDRAW` (not `VAULT_WITHDRAW`)
2. 1,266 USDC ledger point on Ethereum wallet shows `ACQUIRE` with cbDelta ≈ $1,266
3. Avalanche Aave position AVCO ≈ $0.994 (not $0.2158)
4. BASE USDC AVCO ≈ $1.00 (not $0.22)
5. No regression in other vault-related events

---

## 5. Risks

- Function-name heuristics for other protocols (genuine vaults) may rely on TERMINAL_METADATA_ONLY fallback — ensure Fix 1 only applies when registry has explicit `event_type`
- The stablecoin ACQUIRE fallback (Fix 2) should be scoped to PROTOCOL_CUSTODY_WITHDRAW specifically, not all empty-pool REALLOCATE_IN cases
