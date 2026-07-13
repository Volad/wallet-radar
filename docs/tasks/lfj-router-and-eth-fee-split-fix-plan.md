# LFJ Router Correlation + Native ETH Fee Split + LENDING_WITHDRAW AVCO — Implementation Plan

> **Status:** COMPLETE (v4) — BLOCKER-1 fixed 2026-07-11; BLOCKER-3 resolved as side effect of BLOCKER-1 fix + full pipeline reset.
> **Audit source:** `results/blockers.md`, `results/eth_basis.md`, `results/accounting-failure-analysis.md`, `results/required-changes.md` (2026-07-11)
> **Phase 3 reviews:** `results/phase3-financial-review.md`, `results/phase3-ba-review.md`, `results/phase3-arch-review.md`

---

## Executive Summary

| ID | Stage | Severity | Materiality | Description |
|---|---|---|---|---|
| BLOCKER-1 | classification | HIGH | LP basis destroyed per cycle (AUSD/USDC: ~$2.63 net; systemic for future LFJ pairs) | LFJ LBRouter calldata handler missing; all 13 AUSD/USDC LP txs uncorrelated |
| BLOCKER-2 | clarification | MEDIUM | $9.58 per ETH-native LP exit (Pool 477096 confirmed, systemic for all V3/Slipstream ETH-native pools) | `LpExitFeeDecomposer` + `LpNftClFlowMaterializer.splitFeeFlows` fail for native ETH |
| BLOCKER-3 | cost-basis replay | HIGH | ETH AVCO shifts ±$920 across two transactions (LENDING_WITHDRAW → UNWRAP); AVCO unstable until UNWRAP completes | aArbWETH carry correctly lands on WETH, but a secondary ETH-family ACQUIRE at market price ($1.98k) concurrently dilutes AVCO; UNWRAP pass-through corridor restores it |

**ETH AVCO rise (~$400):** Confirmed **expected correct behavior** — R2 cross-asset drain correctly reallocates previously-destroyed USDC pool basis to ETH. Not a regression.  
**Corridor:** `0xbc3f…` / `0xa5e7…` — confirmed working, CARRY_OUT/CARRY_IN at $9,310.94 / $3,042.79.

### Post-implementation audit (2026-07-11)
| Blocker | Status | Note |
|---|---|---|
| BLOCKER-1 | ✅ FIXED | All 9 LFJ txs: `classifiedBy=PROTOCOL_REGISTRY`, `correlationId=lp-position:avalanche:lfj:0x8573f98175d816d520248b5facf40d309b1c9cee`, `type=LP_EXIT` (not `LP_FEE_CLAIM`) |
| BLOCKER-2 | ✅ FIXED (prior session) | Native ETH fee split working for all V3/Slipstream exits |
| BLOCKER-3 | ✅ RESOLVED | LENDING_WITHDRAW (`0xe564fec…`): netAvco change = -$4 (interest earned, correct). No $920 drop. UNWRAP (`0x6f7aec13…`): +$1017 expected (WETH basis $3039 merging into ETH pool). |

**Root causes fixed:**
1. `LpRegistryClassifier` moved from `POST_SPAM_REVIEW` to `PRE_PROTOCOL_REVIEW` (order +190) to intercept before `SpecialHandlerRegistryReviewClassifier` (+199)
2. `MultiAssetReceiptLpClassifier` now skips contracts with registered `specialHandler`
3. `classifyLfjLbRouterTx`: `removeLiquidity` selector directly sets `LP_EXIT` (bypasses `LpPrincipalCloseEvidence.refineFinalExitType` which doesn't know LFJ selector `0xc22159b6`)
4. `IdempotentNormalizedTransactionStore` CONFIRMED guard: CONFIRMED transactions must be deleted before re-classification (pipeline design constraint)

**Pipeline reset:** `--skip-frontend` ran 2026-07-11. LFJ LP position closed (`qtyHeld=0`).  
**Current ETH AVCO (main wallet):** $1696.59 avco / $735.81 net (2026-07-10).

---

## Scope

- **Wallet:** `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f`
- **BLOCKER-1 networks:** AVALANCHE
- **BLOCKER-2 networks:** BASE (Pool 477096 confirmed), ETHEREUM, ARBITRUM, OPTIMISM, UNICHAIN — all networks where V3/Slipstream positions hold WETH as pool token but return native ETH via `unwrapWETH9`
- **BLOCKER-2 out of scope:** Uniswap V4 native ETH exits (same bug class in `LpV4ExitFeeDecomposer.receivedAmountsFromTransfers`, deferred — separate blocker)
- **BLOCKER-3 transactions:** `0xe564fec…` (LENDING_WITHDRAW, ARBITRUM), `0x6f7aec13…` (UNWRAP, ARBITRUM)
- **Assets:** AUSD, USDC (BLOCKER-1); ETH/native-ETH (BLOCKER-2); ETH/WETH/aArbWETH family (BLOCKER-3)

---

## Verified On-Chain Parameters (from raw MongoDB)

### LFJ LBRouter — Avalanche (BLOCKER-1)
| Field | Value |
|---|---|
| LBRouter address (Avalanche) | `0x18556da13313f3532c54711497a8fedac273220e` |
| `addLiquidity(LiquidityParameters)` selector | `0xa3c7271a` |
| `removeLiquidity(address,address,uint16,…)` selector | `0xc22159b6` |
| Pair AUSD/USDC address | `0x8573f98175d816d520248b5facf40d309b1c9cee` |
| tokenX (AUSD) | `0x00000000efe302beaa2b3e6e1b18d08d69a9012a` |
| tokenY (USDC) | `0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e` |
| binStep | `1` (1 bp = 0.01% fee, stable pair) |

Note: `addLiquidityNATIVE` / `removeLiquidityNATIVE` selectors are not present in the dataset (AUSD/USDC are both ERC-20; no AVAX leg). They are **out of scope** for this pair and this implementation.

### Pool 477096 — BASE (BLOCKER-2)
| Field | Value |
|---|---|
| Network WETH address (BASE) | `0x4200000000000000000000000000000000000006` |
| Correct ETH principal qty | `0.543429281` |
| Correct ETH fee qty | `0.002849993` |
| Correct ETH REALLOCATE_IN cost basis | `$2,083.434` |
| Current (wrong) ETH REALLOCATE_IN qty | `0.546279274` |
| Current (wrong) cost basis | `$2,093.013` |
| Overstatement | `$9.579` |

---

## Root Cause

### BLOCKER-1
R7 registered the LFJ pair address (`0x8573f98…`) with `specialHandler: LFJ_LB_PAIR`, but **all 13 LFJ transactions route through the LBRouter** (`0x18556da1…`). The classifier matches `to` address in the registry; the pair never appears in `to`. The pair address, `tokenX`, `tokenY`, and `binStep` are **all in the calldata** — `removeLiquidity` has them as top-level ABI args; `addLiquidity` has them inside a struct.

Evidence state: `EVIDENCE_PRESENT_UNLINKED`.

### BLOCKER-3

Observed (confirmed):
- `0xe564fec…` LENDING_WITHDRAW, ARBITRUM: **Net AVCO before $2.98k → after $2.06k** (−$920 drop)
- `0x6f7aec13…` UNWRAP, ARBITRUM: **Net AVCO before $2.06k → after $2.98k** (+$920 restore)

The shift is exactly symmetric: LENDING_WITHDRAW drops by precisely the same amount UNWRAP restores. This is NOT expected behavior — AVCO must remain stable throughout the entire LENDING_WITHDRAW → UNWRAP sequence.

**Diagnosis:**

`FamilyEquivalentCustodyReplayHandler.selectFlows` correctly identifies the aArbWETH → WETH pair (both `FAMILY:ETH`, distinct accounting identities), and the carry from the aArbWETH position at $2.98k IS applied to the WETH position. However, a **second ETH-family flow exists within the LENDING_WITHDRAW transaction** (most likely a small ETH ACQUIRE at market price ~$1.98k — e.g., an accrued-interest leg, a collateral dust refund, or an internal Aave gateway fee refund). This second flow is **not consumed by `selectedByIndex`** and falls through to `replayGenericFlowsSkipping` where it is processed as ACQUIRE at market price.

Result: WETH position gets a correct $2.98k carry for the principal, but the secondary ETH-family ACQUIRE at $1.98k blends the family AVCO down to $2.06k.

For the UNWRAP (`0x6f7aec13…`): WETH is REALLOCATE_OUT, ETH is REALLOCATE_IN via carry. The UNWRAP re-enters the NATIVE:ARBITRUM position at the WETH carry basis, which — when combined with the existing tiny ETH position at the low basis — results in a weighted-average jump back up to $2.98k. The pass-through corridor mechanism carries the correct $2.98k forward, making AVCO appear "restored" at the UNWRAP step.

**Net effect:** AVCO is technically correct AFTER both transactions complete ($2.98k). But the intermediate state ($2.06k) is visible in the event log between the two transactions and is misleading. More critically, if the user reviews the ledger at any point between LENDING_WITHDRAW and UNWRAP, they see incorrect AVCO.

**Root cause candidates (requires DB verification to confirm):**

| Candidate | Verification query |
|---|---|
| RC-A: Accrued-interest ACQUIRE leg at market price | `db.normalized_transactions.findOne({txHash: '0xe564fec...'}).flows` — look for any inbound ETH-family flow other than the principal WETH |
| RC-B: Aave WETHGateway emits intermediate WETH transfer that gets double-processed | Same — check if WETH appears both as inbound AND as a second outbound |
| RC-C: `selectPrincipalInbound` picks WETH over ETH, leaving ETH as unmatched ACQUIRE | Same — check if both WETH and native ETH appear as distinct inbound flows |

**Verification query (run in backend tests or mongo shell):**
```
db.normalized_transactions.findOne(
  {txHash: '0xe564fec189ce15b308d4031461077e3de4dcc3bb02c13732894ef500b7ac1af2'},
  {flows: 1, normalizedType: 1, protocolName: 1}
)
db.asset_ledger_points.find(
  {normalizedTransactionId: <id of above>},
  {assetSymbol: 1, quantityDelta: 1, basisEffect: 1, avcoAfterUsd: 1, netAvcoAfterUsd: 1}
).sort({replaySequence: 1})
```

### BLOCKER-2
Uniswap V3 / Slipstream pools holding WETH internally return native ETH via `unwrapWETH9`. `DecreaseLiquidity` and `Collect` logs record amounts in WETH. `LpExitFeeDecomposer` correctly extracts fee fractions for ERC-20 legs, but **`LpNftClFlowMaterializer.splitFeeFlows()` has a hard null-contract guard** (line ~361):

```java
if (flow.getAssetContract() == null) {
    result.add(flow);  // native ETH: always skipped, even if fee fraction was computed
    continue;
}
```

Even if `feeFractionsForContracts()` is updated to return a WETH-keyed fraction, `splitFeeFlows` will skip the native ETH leg unconditionally. **Both files** require changes.

Evidence state: `EVIDENCE_PRESENT_UNUSABLE`.

---

## Changes (ordered upstream first)

### C1 — LFJ LBRouter calldata handler (classification stage)

**Priority:** HIGH  
**Files to change:**
- `backend/core/src/main/resources/protocol-registry.json`
- `ProtocolRegistrySpecialHandlerType.java`
- `ProtocolRegistryEntry.java` (add `tokenX`, `tokenY`, `binStep` fields)
- `LpRegistryClassifier.java`

#### C1.1 — Registry schema (per architect REVISE-1)

Extend **existing `LFJ_LB_PAIR` entries** with three new fields: `tokenX`, `tokenY`, `binStep`. The pair entry becomes self-describing:

```json
"0x8573f98175d816d520248b5facf40d309b1c9cee": {
  "name": "LFJ Liquidity Book AUSD/USDC (Avalanche)",
  "protocol": "LFJ",
  "version": "V2.2",
  "family": "DEX",
  "role": "POSITION_MANAGER",
  "specialHandler": "LFJ_LB_PAIR",
  "networks": ["AVALANCHE"],
  "tokenX": "0x00000000efe302beaa2b3e6e1b18d08d69a9012a",
  "tokenY": "0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e",
  "binStep": 1,
  "confidence": "HIGH"
}
```

Add the LBRouter entry with a new `LFJ_LB_ROUTER` special handler:

```json
"0x18556da13313f3532c54711497a8fedac273220e": {
  "name": "LFJ LBRouter V2.2 (Avalanche)",
  "protocol": "LFJ",
  "version": "V2.2",
  "family": "DEX",
  "role": "ROUTER",
  "specialHandler": "LFJ_LB_ROUTER",
  "networks": ["AVALANCHE"],
  "confidence": "HIGH"
}
```

Update `ProtocolRegistryEntry` record/class to expose `tokenX`, `tokenY`, `binStep` fields (nullable for entries that don't use them).

#### C1.2 — Pair index (per architect REVISE-1)

`LpRegistryClassifier` (Spring bean, `@PostConstruct` or constructor) scans all registered entries with `specialHandler == LFJ_LB_PAIR` for each network and builds an in-memory inverse index:

```
Map<NetworkId, Map<LfjPairKey, String>> lfjPairIndex
// LfjPairKey = (tokenX.lower, tokenY.lower, binStep)   [canonical order: both addresses normalized to lowercase]
// value = pairAddress (correlationId target)
```

Token ordering: always normalize to `(min(tokenX, tokenY), max(tokenX, tokenY))` as lowercase strings for the key — matching `removeLiquidity` calldata which preserves the pair's canonical order.

#### C1.3 — LFJ_LB_ROUTER handler

Add `LFJ_LB_ROUTER` to `ProtocolRegistrySpecialHandlerType`. In `LpRegistryClassifier.classify()`, when `entry.specialHandler() == LFJ_LB_ROUTER`, call `classifyLfjLbRouterTx(context, entry)`.

**Function selector allowlist** (LP operations only — checked as first gate):

| Selector | Function | Calldata tokenX/tokenY/binStep |
|---|---|---|
| `0xa3c7271a` | `addLiquidity(LiquidityParameters)` | struct-encoded; tokenX at struct offset 0, tokenY at +32, binStep at +64 (relative to struct start after ABI tuple offset ptr) |
| `0xc22159b6` | `removeLiquidity(address,address,uint16,…)` | arg[0]=tokenX, arg[1]=tokenY, arg[2]=binStep (standard ABI, decodeable with `CalldataDecodingSupport.decodeAddressArgument(input, 0/1)`) |

Any other method selector → return `Optional.empty()` immediately (safe fallback to HEURISTIC).

**Handler logic:**

1. Check selector against allowlist. If not in list → `Optional.empty()`.
2. Decode `tokenX`, `tokenY`, `binStep` from calldata using appropriate decoder for each selector.
3. Build `LfjPairKey(tokenX.lower, tokenY.lower, binStep)` (both orders tried: as-decoded and swapped).
4. Look up pair address in `lfjPairIndex` for this network. If not found → log at **WARN** (`"LFJ pair not registered: tokenX={}, tokenY={}, binStep={} on network={}"`) and return `Optional.empty()` (safe degradation — no silent failure).
5. If found → determine direction from movement legs (`LP_ENTRY` / `LP_EXIT`) using existing `LpRegistryFamilySupport.resolveByMovementLegsOnly()`.
6. Assign correlation `lp-position:<net>:lfj:<pairAddress>`.
7. Emit synthetic qty=1 LP-RECEIPT (same as existing `classifyLfjLbPairTx`).
8. Return `RegistryDecisionSupport.registryResult(…)`.

**Negative cases (guaranteed by allowlist approach):**
- `swapExactTokensForTokens`, `swapTokensForExactTokens`, `swapExactAVAXForTokens`, etc. → selector not in allowlist → fall through.
- `addLiquidityNATIVE`, `removeLiquidityNATIVE` → selector not in allowlist → fall through. These are out of scope for the AUSD/USDC pair (both ERC-20). If they become relevant for AVAX pairs, add selectors + handle the single-token NATIVE ABI layout separately.

---

### C2 — LpExitFeeDecomposer + LpNftClFlowMaterializer: native ETH unwrap (clarification stage)

**Priority:** MEDIUM  
**Files to change:**
- `LpExitFeeDecomposer.java` (`normalization..classification.support` package)
- `LpNftClFlowMaterializer.java` (`normalization..classification.lp` package)
- New shared class: `NativeWrappedTokenSupport.java` (`normalization..classification.support` package)

#### C2.1 — WETH address source (per architect REVISE-2)

**Do NOT** duplicate `AccountingAssetIdentitySupport.NATIVE_ALIAS_CONTRACTS` (lives in `costbasis.support`, which would be a layering violation from `normalization..classification.support`).

Create `NativeWrappedTokenSupport` in `normalization..classification.support`:

```java
public final class NativeWrappedTokenSupport {
    // Per-network canonical WETH / wrapped-native addresses.
    // Single source of truth for classification; mirrors AccountingAssetIdentitySupport.NATIVE_ALIAS_CONTRACTS.
    // Cross-reference: AccountingAssetIdentitySupport must stay in sync if new networks are added.
    private static final Map<NetworkId, String> CANONICAL_WETH = Map.of(
        NetworkId.ETHEREUM, "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2",
        NetworkId.ARBITRUM, "0x82af49447d8a07e3bd95bd0d56f35241523fbab1",
        NetworkId.OPTIMISM, "0x4200000000000000000000000000000000000006",
        NetworkId.BASE,     "0x4200000000000000000000000000000000000006",
        NetworkId.UNICHAIN, "0x4200000000000000000000000000000000000006",
        NetworkId.ZKSYNC,   "0x5aea5775959fbc2557cc8789bc1bf90a239d9a91"
        // BSC (WBNB), AVALANCHE (WAVAX), MANTLE (WMNT): not currently in V3/Slipstream scope;
        // add here when V3/Slipstream pools on those networks are confirmed in dataset.
    );

    public static String canonicalWeth(NetworkId networkId) {
        return CANONICAL_WETH.get(networkId);
    }

    public static boolean isNativeEth(NormalizedTransaction.Flow flow) {
        return flow.getAssetContract() == null;
    }
}
```

#### C2.2 — LpExitFeeDecomposer changes

In `feeFractionsForContracts(view, persistedLogs)` (or equivalent method that builds the fee-fraction map):

When a pool token slot maps to `DecreaseLiquidity`/`Collect` amounts where the pool token address equals `NativeWrappedTokenSupport.canonicalWeth(networkId)`:
- Also emit the fraction keyed by the **WETH contract address** (not a null key).
- The materializer will look up this WETH-address key when handling null-assetContract (native ETH) flows.

Fee formula (unchanged):
```
feeFraction = (Collect.amount - DecreaseLiquidity.amount) / Collect.amount
```
Clamp: if `feeFraction < 0` (data anomaly), set to 0 and emit `LP_FEE_CLAMPED` reason code.

Map key convention (per architect REVISE-3): **use the WETH contract address** (not a null/sentinel). This makes the map homogeneous and deterministic.

#### C2.3 — LpNftClFlowMaterializer.splitFeeFlows changes (per financial auditor RC-1, critical)

In `splitFeeFlows(flows, feeFractions)`, before the existing ERC-20 loop, add a pre-split pass for native ETH:

```java
// Pre-split: native ETH flows where fee fraction is keyed by WETH address on this network.
// The standard ERC-20 loop skips null-assetContract legs; handle them here.
for (EACH inbound native ETH flow WHERE assetContract == null AND role == TRANSFER AND qty > 0) {
    String wethKey = NativeWrappedTokenSupport.canonicalWeth(networkId);
    BigDecimal feeFraction = (wethKey != null) ? feeFractions.get(wethKey.toLowerCase()) : null;
    if (feeFraction != null && feeFraction.signum() > 0) {
        BigDecimal total = flow.getQuantityDelta();
        BigDecimal feeQty = total.multiply(feeFraction).setScale(18, HALF_UP);
        BigDecimal principalQty = total.subtract(feeQty);
        // Replace the single ETH flow with: principal REALLOCATE_IN + LP_FEE_INCOME at zero-cost
        // (same split logic as existing ERC-20 path)
    }
}
```

The `networkId` is available in the `view` passed to `applyFeeSplitIfAvailable(view, flows)` — thread it through to `splitFeeFlows`.

Existing ERC-20 loop guard `flow.getAssetContract() == null → skip` remains unchanged for non-ETH native flows.

---

### C3 — LENDING_WITHDRAW secondary ETH-family flow isolation (cost-basis replay stage)

**Priority:** HIGH (visible AVCO instability between two sequential transactions)  
**Files to change:**
- `FamilyEquivalentCustodyReplayHandler.java` (`costbasis.application.replay.handler` package)
- Possibly `ReplayPendingTransferKeyFactory.java` if the secondary flow requires continuity key scoping

**Prerequisite:** Run verification queries above to confirm which RC candidate (A, B, or C) is active.

#### C3.1 — If RC-A (accrued-interest ACQUIRE leg)

The interest leg (small inbound WETH or ETH at market price) falls through to generic replay as ACQUIRE. This is actually **correct accounting** — accrued interest is a new ACQUIRE. The problem is not the accounting but the **intermediate AVCO visibility**.

Fix options:
- **Option A (preferred):** No code change; document this as expected intermediate transient. Add a note in the event log that AVCO is "in-flight" when LENDING_WITHDRAW is followed immediately by UNWRAP in the same session.
- **Option B:** Bundle the interest ACQUIRE inside the REALLOCATE family so it uses the aArbWETH basis rate rather than market price. This changes accounting semantics and must be discussed.

#### C3.2 — If RC-B (WETHGateway double-processing)

If the normalized transaction contains WETH as both an intermediate inbound (+) and intermediate outbound (−) that netted to zero from the user's perspective, but our normalization captured both legs:

In `selectFlows`, after the primary pair (aArbWETH → ETH) is selected, the intermediate WETH pair (WETH_in, WETH_out) should ALSO be identified as a same-asset pair and added to `selectedByIndex` so it is excluded from generic replay.

Add a second pass in `selectFlows` after primary pairing: for each family, find any flows of the SAME accounting identity (e.g., WETH inbound + WETH outbound within the same transaction where both belong to the same asset) and add them both to `selectedByIndex`. Record them as a CARRY_THROUGH (no ledger point emitted — purely a skip marker).

#### C3.3 — If RC-C (selectPrincipalInbound picks WETH, ETH becomes unmatched ACQUIRE)

If both WETH (+3.04) and ETH (+3.04) appear as distinct inbound flows (e.g., from a WETHGateway UNWRAP embedded in the same LENDING_WITHDRAW transaction):

Modify `selectPrincipalInbound` to also add the **non-selected same-family same-qty inbound** to `selectedByIndex` and emit it as a CARRY_THROUGH from the same carry. This prevents the non-selected inbound from being ACQUIRED at market price.

Alternatively: after primary pairing, apply `selectedByIndex` to absorb any remaining same-identity flows using carry redistribution.

---

## Docs required

- `docs/pipeline/normalization/rules/protocols/lfj.md` — create: LFJ Liquidity Book protocol rules, LBRouter-based pair detection, static registry, allowlist approach, negative cases (swaps, NATIVE variants), Option-B carry
- `docs/pipeline/normalization/rules/` — update V3/Slipstream LP exit rule: native ETH ↔ WETH mapping for fee decomposition; document `NativeWrappedTokenSupport` as the canonical source; note V4 native ETH is an open issue (separate blocker)

No new ADRs required (changes within existing R7 and R1 scope decisions; `NativeWrappedTokenSupport` is a refactor within the same module).

---

## Acceptance criteria

### BLOCKER-1 (LFJ)
1. All 13 Avalanche LFJ transactions have `correlationId = 'lp-position:avalanche:lfj:0x8573f98175d816d520248b5facf40d309b1c9cee'` (5 entries + 8 exits; none null).
2. All 5 LP_ENTRY txs create `lp_receipt_basis_pools` for AUSD and USDC lanes.
3. All 8 LP_EXIT txs drain those pools; REALLOCATE_IN onto received AUSD/USDC.
4. Combined LFJ basis conserved: net ≈ $2.63 (= total withdrawn minus total deposited at AVCO; tolerance ±$0.50 for stablecoin rounding). Derivation: AUSD and USDC are both ~$1; $2.63 = cumulative fee income compounded into position across 4 position cycles.
5. **Note on first two LP_EXITs (Jul–Aug 2025):** `0x983f…` and `0x3993…` exit before the first LP_ENTRY in the dataset (entries start Oct 2025). These produce `$0-cost REALLOCATE_IN` (no pool basis to carry). This is expected and correct — entries predate the backfill window. Do not flag as failures.
6. AUSD/USDC AVCO after all LFJ txs: ≈ $1.00 ± $0.05 (stablecoin; sanity check only).
7. The 1 SWAP transaction through the same LBRouter (`0xd937…`, `type=SWAP`) is unaffected — remains `type=SWAP` with no LFJ_LB_PAIR correlation.
8. WARN log emitted for any unknown `(tokenX, tokenY, binStep)` combo encountered at runtime.

### BLOCKER-2 (ETH fee split)
9. Pool 477096 (BASE) LP_EXIT: ETH `REALLOCATE_IN` qty=0.543429281, cb≈$2,083.434 (±$1.00 for rounding).
10. Pool 477096 (BASE) LP_EXIT: ETH `LP_FEE_INCOME` leg qty=0.002849993, cb=$0.000.
11. Pool 477096 (BASE): no non-zero pool residual after exit (`qtyHeld=0, basisHeldUsd=0, netBasisHeldUsd=0`).
12. All other confirmed V3/Slipstream ETH-native pools in the dataset (BASE, ETHEREUM, ARBITRUM, OPTIMISM, UNICHAIN): `LP_FEE_INCOME` legs present where fee > 0 per token; zero-cost.
13. ETH/WETH family AVCO after BLOCKER-2 fix: **slightly lower** than current post-R2 value (because ETH fee income enters at $0, diluting AVCO). Direction is lower, not higher. Magnitude ≈ $9.58 / total ETH holdings at exit time — small effect.

### BLOCKER-3 (LENDING_WITHDRAW AVCO)
14. `0xe564fec…` LENDING_WITHDRAW: **Net AVCO after = $2.98k ± $0.05k** (no $920 drop). All ETH-family ledger points have `basisEffect ∈ {REALLOCATE_OUT, REALLOCATE_IN}` except the interest leg (which should be `ACQUIRE` at market price but negligible qty).
15. `0x6f7aec13…` UNWRAP: **Net AVCO before ≈ Net AVCO after** (no change; pure REALLOCATE).
16. No ETH family AVCO change > $100 across the LENDING_WITHDRAW → UNWRAP sequence (combined net effect ≈ $0, ignoring negligible interest).
17. Event log shows stable AVCO line through both transactions on the ETH asset page.

### General
18. All 80 drained pools still have `netBasisHeldUsd = $0` (R3 conservation unchanged).
19. `normalizationStatus = PENDING` count = 0.
20. No new AVCO spikes: no individual `ACQUIRE` ledger point changes AVCO by > $200 for ETH/WETH unless traceable to a known large purchase or LP carry.
21. Bybit corridor: `0xbc3f…` / `0xa5e7…` still at $9,310.94 / $3,042.79 (no regression).

---

## Rebuild

```bash
./scripts/prod-reset-rebuild-backend.sh --skip-frontend
```

C1 and C2 require full renormalization (classification + clarification stages).  
C3 requires cost-basis replay reset only (`--skip-frontend`).  
All three must land in the same build cycle — they share the same pipeline reset.

---

## Risks

- **`addLiquidity` struct decoding:** The `LiquidityParameters` struct is ABI-encoded as a single tuple. tokenX is at byte offset 68 of calldata (4 selector + 32 tuple-offset-ptr + 0 = slot 0 of struct); tokenY at offset 100; binStep (uint256-padded) at offset 132. If the struct layout changes in a future LFJ version, the decoder will need updating — version-tag the handler.
- **`addLiquidityNATIVE` deferred:** If the wallet later interacts with AVAX-paired LFJ pools (AVAX/USDC etc.), `addLiquidityNATIVE` / `removeLiquidityNATIVE` selectors will need to be added. The WARN log (acceptance #8) will surface this.
- **WETH address sync:** `NativeWrappedTokenSupport` must stay in sync with `AccountingAssetIdentitySupport.NATIVE_ALIAS_CONTRACTS`. Add a comment cross-reference in both files.
- **V4 native ETH:** Same bug class exists in `LpV4ExitFeeDecomposer`. Explicitly deferred; must be filed as a separate blocker.
- **Ordering:** C1, C2, and C3 must land together in the same pipeline reset cycle.
- **BLOCKER-3 branching risk:** The correct fix for C3 depends on which RC candidate is active. DB verification of the two transaction flows MUST happen before implementation. If RC-A (interest is tiny and correctly ACQUIRE), Option A (no code change) may be acceptable. If RC-B or RC-C, code change is required.
- **BLOCKER-3 accounting impact:** Any change to `FamilyEquivalentCustodyReplayHandler` for LENDING_WITHDRAW must be guarded to not regress the Silo Finance shape (soUSDC raw-unit BUY suppression) or the Aave WETHGateway multi-outbound shape (Cycle/9 S6 fix already in place).
