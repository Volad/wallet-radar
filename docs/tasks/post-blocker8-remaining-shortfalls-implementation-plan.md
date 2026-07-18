# Post-BLOCKER-8 Remaining Shortfalls — Consolidated Implementation Plan

**Date:** 2026-07-14  
**Scope:** All remaining `hasIncompleteHistoryAfter` / `quantityShortfallAfter` issues after BLOCKER-1 through BLOCKER-8 resolution.  
**Primary wallet:** `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f`  
**Pipeline reset type:** `--skip-frontend` after each Wave.

---

## Summary Table

| Blocker | Issue | USD Impact | Priority | Wave |
|---|---|---|---|---|
| BLOCKER-9 | Euler Finance flash-loan inventory pollution (eUSDC-2 + ERC20 debt tokens) | ~$2,454 | HIGH | 1 |
| BLOCKER-3 | Balancer V3 Avalanche AAVE GHO pool misclassification | ~$2,150 | HIGH | 1 |
| BLOCKER-12 | Aave debt-swap misclassified as SWAP | ~$393 | MEDIUM | 2 |
| BLOCKER-10 | Silo/Aave dual-flow receipt token double-accounting | ~$655 | MEDIUM | 2 |
| BLOCKER-11 | Spoof ARB ERC-20 token not excluded from accounting | ~$5 | LOW | 3 |

**Genuine data gaps (no fix needed):** XYZ (~$1.48), WAVAX (~$4 pre-backfill), WSTUSR (PLASMA bridge), BAL (pre-backfill), WLKN (Bybit residual), aAvaUSDC/aAvaSAVAX (dust), USD Dzengi (fiat CEX).

---

## Wave 1 — BLOCKER-9 + BLOCKER-3 (High Impact, ~$4,600 combined)

### BLOCKER-9: Euler Finance EVK Flash-Loan Inventory Pollution

**Root cause:** `LENDING_LOOP_REBALANCE` transactions produce `CARRY_IN` flows for:
- `eUSDC-2` (within-transaction flash-loan sentinel counterparty `UNKNOWN:<txHash>:...`)
- `ERC20:0x2eb15b5e...` (Euler EVK variable-debt token — internal to Euler, never a user asset)
- `ERC20:0x1d45674e...` (Euler EVK variable-debt token — same)

These are NOT user acquisitions. Crediting them to inventory creates phantom balances that cannot be burned when subsequent exits route them back → shortfall.

**Required changes (in `backend/core`):**

1. **Flow-level exclusion for flash-loan within-tx sentinel counterparties:**
   - In `OnChainClassificationSupport` or `FlowBuilder`: when a flow's `counterpartyAddress` matches the within-tx sentinel pattern `UNKNOWN:<txHash>:NETWORK:WALLET:TRANSFER:ASSET:INDEX`, exclude that flow from inventory accounting (set `excludedFromInventory = true` or suppress the BUY effect).
   - This pattern appears exclusively on `LENDING_LOOP_REBALANCE` flash-loan inflows.

2. **Euler EVK debt token registry exclusion:**
   - Register `0x2eb15b5e4e5749bdd46a8cca48c500f69bd0df5d` and `0x1d45674ec811f8a33c97616790bc5a81d4c9afac` in `protocol-registry.json` as Euler EVK internal debt tokens.
   - Any positive `quantityDelta` flow from these contracts → `excludedFromAccounting = true`, reason `EULER_EVK_INTERNAL_DEBT_TOKEN`.
   - (Negative delta = legitimate debt repayment — should remain in scope.)

**Acceptance criteria:**
- `eUSDC-2` `lastShortfall` → 0, `shortfallCount` → 0
- `ERC20:D0DF5D` `lastShortfall` → 0
- `ERC20:C9AFAC` `lastShortfall` → 0
- No regression on legitimate Euler lending deposit/withdraw flows

---

### BLOCKER-3 (revisited): Balancer V3 AAVE Pool — Still Open

**Root cause (re-confirmed):** Although BLOCKER-3 was marked "resolved" (the generic Balancer V3 handler was wired), the specific AAVE GHO/USDT/USDC pool (`0xfcec3c8d86329defb548202fe1b86ff2188603a8` on AVALANCHE) has remaining `lastShortfall = 2144.92` on both `LP-RECEIPT:AVALANCHE:BALANCERV3:...` and `AAVE GHO/USDT/USDC`. Investigation needed on why this specific pool still has shortfall despite the handler existing.

**Investigation required before implementation:**
1. Query `normalized_transactions` for txHashes touching this pool address to confirm classification
2. Verify that `LP_ENTRY` / `LP_EXIT` correlation IDs are correctly assigned for this pool
3. Check if the AAVE GHO pool's BPT token address is registered in `protocol-registry.json`

**Required changes (pending investigation):**
- If pool not registered: add `0xfcec3c8d86329defb548202fe1b86ff2188603a8` to the Balancer V3 pool registry
- If LP_EXIT not linked: verify `LP-RECEIPT` correlation ID matching for this pool's BPT token

**Acceptance criteria:**
- `LP-RECEIPT:AVALANCHE:BALANCERV3:0xfcec3c8d...` `lastShortfall` → 0
- `AAVE GHO/USDT/USDC` `lastShortfall` → 0 (or reduced to dust)
- BAL shortfall partially reduces

---

## Wave 2 — BLOCKER-12 + BLOCKER-10 (Medium Impact, ~$1,048 combined)

### BLOCKER-12: Aave v3 Debt-Swap Misclassified as SWAP

**Root cause:** When user swaps debt on Aave v3 (via `DebtSwapAdapter` or `ParaSwapDebtSwapAdapter`), the `variableDebt` token is burned. The transaction is classified as `SWAP` instead of `REPAY`. `RepayReplayHandler` does not process `SWAP`-typed transactions → debt token burn not matched to liability → inventory shortfall.

Affected transactions on AVALANCHE:
- `0xbc191e1f...` (2025-10-29): `variableDebtAvaUSDT -390.000001`
- `0xde66ef17...` (2025-11-27): `variableDebtAvaEURC -2.523579`

**Required changes:**

1. **Protocol registry:** Register Aave periphery adapter contracts on AVALANCHE:
   - `ParaSwapDebtSwapAdapterV3` and/or `AaveDebtSwapAdapter` contract addresses (verify from Aave AVALANCHE deployment)

2. **Classifier:** Detect debt-swap pattern — when principal leg is `variableDebt` token burn (to `0x0`) wrapped in a swap interaction:
   - Classify as `REPAY` type
   - Price the debt token at par ($1 for USDT, EUR market price for EURC)

3. **Alternative approach (simpler):** Extend `RepayReplayHandler` to process `SWAP`-typed transactions that contain a `variableDebt` token flow with negative delta (burn to `0x0`).

**Acceptance criteria:**
- `variableDebtAvaUSDT` `lastShortfall` → 0
- `variableDebtAvaEURC` `lastShortfall` → 0
- Legitimate debt-neutral SWAP transactions not reclassified

---

### BLOCKER-10: Silo / Aave Dual-Flow Receipt Token Double-Accounting

**Root cause:** `LENDING_DEPOSIT` for Silo Finance (ARBITRUM `soUSDC`) and Aave Mantle (`aManUSDC`) emits **two flows** for the same receipt token (TRANSFER role + BUY role). `LENDING_WITHDRAW` emits only one burn flow (TRANSFER role). The BUY-role accumulated quantity is never burned → phantom shortfall.

Affected assets:
- `soUSDC` (Silo Finance, ARBITRUM) on `0x1a87f`: ~$649 phantom shortfall
- `aManUSDC` (Aave Mantle, MANTLE) on `0xf03b`: ~$6 phantom shortfall

**Required changes:**

1. **For `soUSDC` (Silo Finance ARBITRUM):** Investigate why `LENDING_DEPOSIT` normalizer emits two flows for `soUSDC`. If two separate on-chain Transfer events fire (principal + shares), merge them into a single `CARRY_IN` flow (summed quantity) so `LENDING_WITHDRAW` single-burn can consume the full balance.

2. **For `aManUSDC` (Aave Mantle):** The `LENDING_DEPOSIT` BUY-role (+6.077877) represents accrued interest credited separately. Options:
   - Merge BUY-role interest into the TRANSFER-role inventory pool (both treated as single position)
   - OR emit a matching BUY-role burn in `LENDING_WITHDRAW`

**Acceptance criteria:**
- `soUSDC` `lastShortfall` → 0 on `0x1a87f` (ARBITRUM)
- `aManUSDC` `lastShortfall` → 0 on `0xf03b` (MANTLE)

---

## Wave 3 — BLOCKER-11 (Low Impact, ~$5, but clean fix with broad reuse)

### BLOCKER-11: Spoof ARB ERC-20 Token Not Excluded

**Root cause:** Contract `0xd01a2c474f998ff7b402e81041576592d879577c` on ARBITRUM uses symbol `"ARB"` to impersonate canonical ARB (`0x912ce59144191c1204e64559fe8253a0e49e6548`). The `SPOOF_TOKEN_NATIVE_SYMBOL_IMPERSONATION` reason code was reserved in BLOCKER-8 but detection not yet implemented in `SpoofTokenClassifier`.

This is the same pattern as BLOCKER-8 (fake `ETH` ERC-20) but for ARB. It causes a 22.008 ARB phantom disposal.

**Required changes:**

1. **`SpoofTokenClassifier`:** Implement SF-1(c) check:
   - Maintain per-network registry of governance/protocol tokens that are commonly impersonated (at minimum: `ARB` on ARBITRUM, `OP` on OPTIMISM, `AVAX`/`WAVAX` on AVALANCHE)
   - When a non-canonical ERC-20 has `assetSymbol` exactly matching a registered canonical token symbol AND `assetContract != canonical contract`, classify as `SPOOF_TOKEN_NATIVE_SYMBOL_IMPERSONATION` → `excludedFromAccounting = true`

2. **Use `CanonicalAssetCatalog` and `NativeAssetSymbolResolver`** as reference for canonical contracts — avoids false positives on legitimate multi-chain deployments.

3. **Guard:** Only apply on networks where the canonical contract is registered. Don't flag the token on networks where it's the real deployment.

**Acceptance criteria:**
- `ARB` shortfall on `0x1a87f` (ARBITRUM) → 0
- Transaction `0x44670803f...` has `excludedFromAccounting = true`, reason `SPOOF_TOKEN_NATIVE_SYMBOL_IMPERSONATION`
- Real ARB (`0x912ce594...`) flows unaffected
- No regression on zkSync, Avalanche, or Optimism native tokens

---

## Genuine Data Gaps (no pipeline fix required)

| Asset | Gap | Action |
|---|---|---|
| XYZ (BSC, 73,999.99) | CEX/airdrop buy history outside tracked scope | WARN-8: accept as genuine gap. Token effectively worthless (~$1.48). |
| WAVAX (0.284) | Pre-backfill initial stock | WARN-11: accept. $4 impact. |
| WSTUSR (1.942, PLASMA) | Missing PLASMA bridge acquisition | WARN-9: accept. $2.18 impact. |
| BAL (1.028) | Pre-backfill gauge rewards; partially resolved with BLOCKER-3 | WARN-10 |
| USD Dzengi (408.59) | Fiat local CEX not modeled | WARN-13: low priority |
| WLKN BYBIT (75.82) | Bybit residual (BLOCKER-5B) | WARN-15: negligible value |
| aAvaUSDC, aAvaSAVAX | Dust residuals | WARN-12: ignore |
| LP-RECEIPT=1 | LP share rounding dust | WARN-14: ignore |
| ETH 0x1a87f (~0.009 ETH) | BLOCKER-2 accumulation from LP fee events | Residual of already-resolved BLOCKER-2 |

---

## Risks and Notes

1. **BLOCKER-9 flash-loan sentinel:** The within-tx sentinel counterparty pattern must be carefully scoped to avoid excluding legitimate same-tx counterparties. Test against Uniswap V3 flash swaps and other protocols using similar intra-tx patterns.

2. **BLOCKER-3 investigation first:** Before implementing, query DB to understand why the existing Balancer V3 handler didn't resolve this pool. May be a registry gap (pool not registered) rather than a handler gap.

3. **BLOCKER-11 false positives:** The SF-1(c) check previously caused test failures (zkSync native alias, Avalanche WAVAX). Implement with a conservative per-network canonical-symbol allowlist rather than a generic "any native symbol" check.

4. **Wave ordering:** BLOCKER-9 and BLOCKER-3 are independent. BLOCKER-12 and BLOCKER-10 are independent of each other. BLOCKER-11 builds on the existing `SpoofTokenClassifier` framework.

---

## ADR Requirements

- **BLOCKER-9:** New ADR for Euler EVK flash-loan flow exclusion rule
- **BLOCKER-11:** Update `SpoofTokenQuarantineSupport` javadoc (SF-1(c) now fully implemented)
- **BLOCKER-3:** If new pool registration, update `docs/pipeline/normalization/rules/`
