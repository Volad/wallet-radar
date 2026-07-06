# Transfer Basis Matching Fix — Implementation Plan

**Audit date:** 2026-06-14  
**Session:** `df5e69cc-a0c0-4910-8b7d-74488fa266e2`  
**conservationDeltaUsd before:** +$631.61 (BREACHED, threshold $70.60)  
**Expected after:** ≤ $70 (gate passes)

---

## Scope

All EVM on-chain wallets + Bybit CEX (UID 33625378).  
Networks: BASE, ARBITRUM, MANTLE, BSC, LINEA.  
Assets: USDT, USDe, USD₮0, USDC, CAKE, WETH.

---

## Root Causes (from audit — `results/required-changes.md`)

| ID | Stage | Description | USD impact |
|----|-------|-------------|-----------|
| RC-01 | replay | CAKE bridge key uses per-chain contract address → BRIDGE_OUT carry orphaned | $98.90 |
| RC-02 | replay | `isBybitEarnInternalTransfer` lacks `bybit-collapsed-v1:` exclusion → USDT carry split across two queues | $153.63 |
| RC-03 | clarification / replay | Multi-flow BRIDGE_IN (USDe→USD₮0 cross-asset swap) fails `hasSinglePrincipalTransferFlow` → null settlement key → $862.75 carry orphaned | $862.75 |
| RC-04 | normalization | BASE tx `0x884437...` ingested as `type=UNKNOWN, flows=[]` — USDC BRIDGE_IN missing | $3.16 |
| RC-05 | backfill | Destination tx `0x96f0e5...` absent entirely — USDC BRIDGE_IN not fetched | $3.10 |
| RC-06 | pricing / gate | WETH on Mantle (0xdead...1111) has no `unitPriceUsd`; FEE flows are MNT not ETH so cross-flow ETH inference fails | ~$37 NEC gap |

Conservation delta breakdown:
- Orphaned corridor basis (RCA-1, covers RC-01+RC-02+RC-03+RC-04+RC-05): **+$1,121**
- MTM bias SOL/TON (RCA-2): **+$241** (addressed separately — out of scope here)
- MNT liability (RCA-3): **+$237** (addressed separately — out of scope here)
- NEC pricing gap (RC-06): **+$37**
- Structural portfolio deficit offset: **−$1,025**
- Net: **+$631**

This plan addresses RC-01 through RC-06, eliminating RCA-1 (+$1,121) and RC-06 (+$37).

---

## Ordered Changes (upstream first)

### T-01: Fix CAKE bridge carry key — `BridgeAssetFamilySupport`

**File:** `backend/src/main/java/com/walletradar/accounting/support/BridgeAssetFamilySupport.java`

Override `continuityIdentity(NormalizedTransaction.Flow)` to skip the per-chain contract-address fallback. After the family/symbol lookup, fall back to `"SYMBOL:" + normalizedSymbol` rather than contract address. Call `AccountingAssetFamilySupport.continuityIdentity(symbol, null)` (passing `null` for contract) to bypass the contract step.

**Why:** Cross-chain bridges assign different contracts to the same token on each chain. Using the contract produces different bridge pending keys for BASE and BSC CAKE → BRIDGE_IN reads empty queue.

**Test:** Add unit test: CAKE flow on BASE and CAKE flow on BSC produce identical bridge identity `SYMBOL:CAKE`. Verify USDT/USDC/WETH/ETH still produce `FAMILY:*` keys (unchanged).

---

### T-02: Fix EARN carry routing for collapsed pairs — `ReplayPendingTransferKeyFactory`

**File:** `backend/src/main/java/com/walletradar/costbasis/application/replay/support/ReplayPendingTransferKeyFactory.java`

**Method:** `isBybitEarnInternalTransfer(...)`

Add `corrId.startsWith("bybit-collapsed-v1:")` to the early-exit guard, alongside the existing `BYBIT-CORRIDOR:` and `bybit-it-bundle-v1:` exclusions.

```java
if (corrId != null && (corrId.startsWith("BYBIT-CORRIDOR:")
        || corrId.startsWith("bybit-it-bundle-v1:")
        || corrId.startsWith("bybit-collapsed-v1:"))) {   // ADD
    return false;
}
```

**Why:** The EARN credit leg with `bybit-collapsed-v1:` corrId currently routes to `bybit-earn-carry:uid:USDT`. The UTA debit routes to `corr-family:bybit-collapsed-v1:...:USDT`. Different queues → CARRY_OUT orphaned ($153.63).

**Prerequisite:** Verify in DB that the EARN credit leg for `bybit-collapsed-v1:d2bb6a3c` has `continuityCandidate=true`. Without this, excluding from EARN FIFO leaves the leg with no valid carry key. If `continuityCandidate` is null or false, the collapser must set it first.

**Test:** Unit test: `corrId="bybit-collapsed-v1:abc"` → `isBybitEarnInternalTransfer=false`. Existing earn unit tests must still pass.

---

### T-03: Verify + implement `alignDestinationInboundRolesForBridgeSettlement` for multi-flow BRIDGE_IN

**Approach:** The correct fix is role-alignment at the clarification stage. When `LiFiBridgePairLinkService` links a bridge pair, if the destination BRIDGE_IN has multiple TRANSFER-role flows (e.g., USD₮0 + ETH gas refund), align roles so only the primary bridged asset retains `TRANSFER`; secondary flows (gas refunds) are demoted to `BUY`. This makes `hasSinglePrincipalTransferFlow=true` and unlocks the settlement key. No new domain model field needed — `continuityCandidate` already encodes cross-asset.

**Files:**
- `backend/src/main/java/com/walletradar/ingestion/pipeline/clarification/LiFiBridgePairLinkService.java` — check if `alignDestinationInboundRolesForBridgeSettlement` already exists and handles this case. If not, implement it: identify the primary TRANSFER flow (asset symbol matching BRIDGE_OUT's outbound corridor, or largest qty), demote remaining TRANSFER flows to `BUY`.
- Guard condition: only apply role-alignment when the BRIDGE_IN has ≥2 TRANSFER-role flows AND a known `matchedCounterparty` (i.e., it is a confirmed linked pair, not a standalone inbound). Do NOT misfire on standalone BRIDGE_INs.

**Why:** The destination tx (Arbitrum `0x826189`) delivers USD₮0 + ETH in one tx. Both are initially `TRANSFER`-role → `hasSinglePrincipalTransferFlow=false` → `bridgeSettlementKey=null` → BRIDGE_IN cannot consume the $862.75 USDe carry. Role-alignment makes ETH a `BUY` (gas refund), leaving USD₮0 as the sole `TRANSFER`, unlocking the settlement key.

No changes to `NormalizedTransaction.java` (no new field).
No changes to `ReplayPendingTransferKeyFactory.java` for this blocker.

**Acceptance:** `CORRIDOR_BASIS_CONSERVATION_BREACH` for `bridge:lifi:0x8b471042:USDE` absent after rebuild. USD₮0 BRIDGE_IN carry consumed from USDe BRIDGE_OUT queue.

**Test:** Unit test: BRIDGE_IN(USD₮0 TRANSFER + ETH TRANSFER, 2 flows, linked pair) → after role-alignment: USD₮0=TRANSFER, ETH=BUY, `hasSinglePrincipalTransferFlow=true`, settlement key non-null.

**ADR:** Write `docs/adr/ADR-033-bridge-multi-flow-role-alignment.md` documenting that multi-flow LI.FI BRIDGE_IN destinations are role-aligned at clarification (primary = bridged asset, secondary gas = BUY).

---

### T-04: Extend cross-flow ETH price inference for Mantle — `PortfolioConservationGate`

**File:** `backend/src/main/java/com/walletradar/ingestion/wallet/query/PortfolioConservationGate.java`

**Method:** `resolveEthFamilyUnitPrice(flow, allFlows)` (or similar)

Current logic scans sibling flows for ETH-family symbols. On Mantle, FEE flows are `MNT`, not ETH. Extend the inference to also:
1. Check the historical pricing store for `WETH` / `ETH` price at the transaction's `blockTimestamp` directly when no sibling ETH-family flow is found.
2. Alternatively, when the unpriced principal asset is `WETH` and no sibling ETH price is found, fall back to a direct price lookup by symbol + timestamp.

**Why:** WETH on Mantle (`0xdead...1111`) BRIDGE_OUT has no `unitPriceUsd`. Sibling FEE is MNT → inference cannot find ETH price → WETH counted as $0 in NEC outflow → ~$37 NEC gap.

**Test:** Unit test: WETH BRIDGE_OUT flow on Mantle network with MNT FEE sibling → correct ETH unit price resolved via fallback lookup.

---

### T-05: Re-ingest USDC BRIDGE_INs (data repair)

**Approach:** Force re-normalization for the two corrupted/missing destination transactions.

- `0x884437719bfde86c0e77bcbc73915703c8f0f5b0ce723b345229c8d5d4ef8c1c` (BASE) — currently `type=UNKNOWN, flows=[]`. Trigger re-normalization; should classify as BRIDGE_IN (USDC from LI.FI router).
- `0x96f0e52287572a6833804539f53511d3ecbb247f8308468f15e10b56eda3cfa3` — absent. Trigger targeted backfill for the destination chain/wallet.

**No code change required** — re-normalization path already exists. Ensure `status=PENDING` is set on the existing row (T-05a) and that the missing tx is fetched by a targeted refresh (T-05b).

**Test (manual):** After rebuild, verify both txs appear as `type=BRIDGE_IN, status=CONFIRMED` with correct USDC flow.

---

## Documentation

- **ADR**: No new ADR required. Update `docs/pipeline/normalization/rules/protocols/li-fi.md` with cross-asset bridge disposal semantics.
- **Accounting rules**: Update `docs/pipeline/cost-basis/02-avco-rules.md` with note on cross-asset bridge treatment (disposal + fresh acquisition).

---

## Acceptance Criteria

| Check | Target |
|-------|--------|
| `conservationDeltaUsd` | ≤ $70.60 (gate passes) |
| `CORRIDOR_BASIS_CONSERVATION_BREACH` for `bybit-collapsed-v1:d2bb6a3c:USDT` | absent |
| `CORRIDOR_BASIS_CONSERVATION_BREACH` for `bridge:lifi:0x8b471042:USDE` | absent |
| `CORRIDOR_BASIS_CONSERVATION_BREACH` for CAKE bridge pairs (×4) | absent |
| CAKE BRIDGE_IN AVCO = CAKE BRIDGE_OUT AVCO | ✓ |
| EARN USDT AVCO inherits UTA basis | ✓ |
| USDe BRIDGE_OUT triggers P&L realization | ✓ |
| Tests (`:backend:test`) | green, no regression |

---

## Risks

1. **T-03 semantic change**: Treating cross-asset bridges as disposals changes AVCO for the out-asset. Verify no regression on same-asset LI.FI bridges (continuityCandidate=true must be unaffected).
2. **T-01 bridge key change**: Any other cross-chain ERC-20 (non-family) will also switch from contract-based to symbol-based key. Run full bridge matching check post-rebuild.
3. **T-05 data repair**: The missing tx may fail to normalize again if the normalization bug is systemic. If so, add a targeted fix in the classifier.

---

## Implementation Order

1. T-02 (one-liner, safest)
2. T-01 (bridge key, tested in isolation)
3. T-04 (gate fix, no replay impact)
4. T-03 (cross-asset disposal — most complex, requires NormalizedTransaction field addition)
5. T-05 (data repair — handled post-rebuild via forced re-normalization)
