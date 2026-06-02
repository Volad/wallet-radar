# Implementation Plan — B-CROSS-UID TON + B-BRIDGE-ORPHAN-2 + B-GMX-LP-SETTLEMENT

**Date**: 2026-06-02  
**Blocker IDs**: B-CROSS-UID-TON, B-BRIDGE-ORPHAN-2, B-GMX-LP-SETTLEMENT-UNKNOWN  
**Severity**: P2 / P2 / P3  
**Estimated shortfall**: ~$97 + ~$3,979 + TBD

---

## Scope

- **Wallets**: BYBIT:409666492 (TON cross-UID), 0xf03b52 ARBITRUM (USDC bridge orphans), 0x1a87f12 ARBITRUM/LINEA (ETH bridge orphan), GMX v2 LP positions ARBITRUM
- **Networks**: Bybit internal; Arbitrum; Linea; Katana
- **Blocker IDs**: B-CROSS-UID-TON, B-BRIDGE-ORPHAN-2, B-BRIDGE-ORPHAN-1 (LINEA), B-GMX-LP-SETTLEMENT-UNKNOWN

---

## Blocker 1: B-CROSS-UID TON — RESOLVED (no implementation needed)

**Pre-fix audit confirmed**: `uni_trans_6b956290` TON CARRY_IN at `BYBIT:33625378` already shows `costBasisDeltaUsd = $147.61`. All three hypotheses (A/B/C) are false — the carry was fixed by a prior deployment. **Remove from implementation scope.**

---

## Blocker 1 (old numbering kept for reference): B-CROSS-UID TON (~$97) — RESOLVED

### Root cause

`pairCrossUidUniversalTransfers()` second pass successfully fixed BTC FUND-sourced carries from `BYBIT:516601508:FUND` but TON from `BYBIT:409666492:FUND` remains unlinked.

**Hypothesis A — `findExcludedCrossUidPartner` returns null**: The excluded UNIVERSAL_TRANSFER outbound for `uni_trans_6b956290` at `BYBIT:409666492` does not have `excludedFromAccounting=true`, so `findExcludedCrossUidPartner` query doesn't find it.

**Hypothesis B — timestamp mismatch**: The FUNDING_HISTORY outbound at `BYBIT:409666492:FUND` for 32.442 TON has a timestamp more than 60s away from the UNIVERSAL_TRANSFER inbound at `BYBIT:33625378`.

**Hypothesis C — no FUNDING_HISTORY record**: The TON outbound uses a different record type (not FUNDING_HISTORY) on UID 409666492.

### Required pre-fix investigation

Before writing code, query MongoDB:
```javascript
// Check uni_trans_6b956290 state
db.normalized_transactions.findOne({ _id: { $regex: "uni_trans_6b956290" } },
  { _id:1, correlationId:1, continuityCandidate:1, walletAddress:1, excludedFromAccounting:1, type:1, "flows.quantity":1, "flows.assetSymbol":1, blockTimestamp:1 })

// Check excluded partner on UID 409666492
db.normalized_transactions.find({ 
  _id: { $regex: "uni_trans_6b956290" }, 
  walletAddress: { $regex: "BYBIT:409666492" }
}, { _id:1, excludedFromAccounting:1, correlationId:1, blockTimestamp:1 })

// Check FUNDING_HISTORY records on UID 409666492 for TON
db.normalized_transactions.find({
  walletAddress: { $regex: "BYBIT:409666492" },
  "flows.assetSymbol": "TON",
  source: "BYBIT"
}, { _id:1, type:1, correlationId:1, blockTimestamp:1, "flows.quantity":1, "flows.assetSymbol":1 }).sort({ blockTimestamp: 1 })
```

### Proposed fix (conditional on investigation)

**If Hypothesis A**: The excluded partner for UID 409666492 is not marked `excludedFromAccounting=true`. Check `findExcludedCrossUidPartner` — it queries `excludedFromAccounting: true`. If the partner is excluded via a different mechanism, update the query.

**If Hypothesis B**: Expand the 60s timestamp tolerance to 300s for FUNDING_HISTORY matching in `findFundingHistoryOutbound`. Add a configurable constant `FUNDING_HISTORY_MAX_OFFSET_SECONDS`.

**If Hypothesis C**: Check what record type the TON outbound uses on UID 409666492. Update `loadFundingHistoryCandidates` to include that type.

### Changes (tentative)

- `BybitInternalTransferPairer.java`: in `findFundingHistoryOutbound`, increase timestamp tolerance from 60s to 300s; OR add fallback query for non-FUNDING_HISTORY outbound types.
- No changes to other files.

### Acceptance Criteria

**AC-1**: `uni_trans_6b956290` TON CARRY_IN at `BYBIT:33625378` has `costBasisDeltaUsd > $90`.
**AC-2**: No regression on BTC/ETH cross-UID carries previously fixed (BTC carries `bybit-cross-uid-v1:866903d7` and `bybit-cross-uid-v1:a893b645` still have cbD > $0).
**AC-3**: `BYBIT:33625378` TON position AVCO is plausible (non-zero, market-consistent) after the carry.
**AC-4** (false-positive guard, applies if timestamp tolerance expanded): No new false-positive FUNDING_HISTORY matches — verify that only the single expected TON outbound is matched for UID 409666492 (query all newly assigned corrIds for that UID).

---

## Blocker 2: B-BRIDGE-ORPHAN-2 — NEEDS FURTHER INVESTIGATION (deferred from this plan)

### Status: DEFERRED — pre-fix audit rejected this plan section

**Financial auditor findings (2026-06-02)**:
- A **5th unmatched BRIDGE_OUT** was found (plan missed it)
- Transaction `0xb1e9f65d` (2,829 USDC) has a same-chain USDC return **19 minutes later** from the same contract `0xf5f93d26` — this is **Sub-hypothesis C** (same-chain protocol interaction, not a cross-chain bridge). The $3,979 shortfall is overstated by ~$2,829.
- Contract `0xf5f93d26229482adca3e42f84d08d549cf131658` must be identified on Arbiscan before any code change.

**Required before implementation**:
1. Look up contract `0xf5f93d26229482adca3e42f84d08d549cf131658` on Arbiscan to identify the protocol
2. Determine if `0xb1e9f65d` is actually a same-chain round-trip (not a bridge)
3. Re-audit: revised shortfall is ~$1,150 (not $3,979)
4. Update this plan with confirmed root cause and corrected instance table

**This section is NOT ready for implementation.**

---

### Problem (original, pending revision)

4 BRIDGE_OUT transactions from wallet `0xf03b52` ARBITRUM to contract `0xf5f93d26229482adca3e42f84d08d549cf131658` have no `correlationId` and no matching BRIDGE_IN in the universe. ~$3,979 USDC basis was drained with no destination credit.

| txHash | qty USDC | BRIDGE_OUT corrId | BRIDGE_IN found |
|---|---|---|---|
| `0xb1e9f65d` | 2,829.12 | ❌ | No |
| `0x4a2eb3ee` | 1,050.58 | ❌ | No |
| `0x5ca14340` | 50 | ❌ | No |
| `0x360988904f` | 50 | ❌ | No |

### Root cause hypothesis

Contract `0xf5f93d26229482adca3e42f84d08d549cf131658` on ARBITRUM is an unrecognized bridge endpoint. Neither `AcrossBridgePairLinkService` nor `LiFiBridgePairLinkService` recognize it, so no `corrId` is assigned to these BRIDGE_OUTs.

**Sub-hypothesis A — Unknown bridge protocol**: The contract is a Connext, Hop, or other bridge relayer not registered in the pipeline's bridge detection logic.

**Sub-hypothesis B — Destination wallet not tracked**: The USDC arrived on a different chain/wallet that is not in the universe.

**Sub-hypothesis C — Misclassification**: These transactions are not actually bridge transactions but DEX swaps or vault deposits, misclassified as BRIDGE_OUT.

### Required pre-fix investigation

```javascript
// Check the 4 normalized transactions
["0xb1e9f65d","0x4a2eb3ee","0x5ca14340","0x360988904f"].forEach(h => {
  let tx = db.normalized_transactions.findOne({ txHash: { $regex: h } });
  if (tx) print(JSON.stringify({
    id: tx._id, type: tx.type, wallet: tx.walletAddress,
    corrId: tx.correlationId, flows: tx.flows, status: tx.status
  }));
});

// Check if any BRIDGE_IN exists for wallet 0xf03b52 around these dates
db.normalized_transactions.find({
  walletAddress: { $regex: "0xf03b52" },
  type: "BRIDGE_IN"
}, { txHash:1, type:1, blockTimestamp:1, correlationId:1, "flows.assetSymbol":1, "flows.quantity":1 }).sort({ blockTimestamp: 1 })
```

Also check on-chain: contract `0xf5f93d26229482adca3e42f84d08d549cf131658` — identify protocol using Etherscan/4bytes decoder.

### Proposed fix (conditional)

**If Sub-hypothesis A (unknown bridge)**:
- Identify the bridge protocol via on-chain analysis
- If it's a supported bridge (Connext/Hop): add the contract address to the existing bridge pair detection service
- If it's a new protocol: add a new `BridgePairLinkService` implementation
- If `0xb1e9f65d` is a same-chain return (Sub-hypothesis C confirmed): reclassify as non-bridge, mark as DISPOSE or INTERNAL_TRANSFER accordingly

**If Sub-hypothesis B (destination not tracked)**:
- No code fix possible without data ingestion
- Mark these BRIDGE_OUTs with a `GENUINE_EVIDENCE_MISSING_PROVEN` flag
- Apply `UnmatchedBridgeInboundPricingFallbackService` logic: since the USDC basis is $1/USDC and these are stablecoins, the shortfall-fallback would correctly price them at market ($1)

**If Sub-hypothesis C (misclassification)**:
- Reclassify the transactions to their correct type
- Update the classification rule for contract `0xf5f93d26`

### Changes (conditional on investigation)

Option A: Add contract to bridge detection → `AcrossBridgePairLinkService` or new `BridgePairLinkService`
Option B: Apply existing shortfall-fallback mechanism → `UnmatchedBridgeInboundPricingFallbackService` extended
Option C: Update classification rule → `TransactionClassifier` or equivalent

### Acceptance Criteria

**AC-1** (if Hypothesis A — bridge fixed): All 4 BRIDGE_OUTs have `corrId` assigned and matching BRIDGE_IN in the universe.
**AC-2** (if Hypothesis B — destination untracked): All 4 BRIDGE_OUTs are assigned `basisEffect = CONTINUITY_OUT` with `genuineEvidenceMissingProven = true` (or equivalent "evidence missing" flag), not left as raw BRIDGE_OUTs with zero-corrId. The $3,979 shortfall is documented as a data gap, not a code bug.
**AC-3**: `0xf03b52` USDC position AVCO = $1.00 after fix (regardless of hypothesis path).
**AC-4** (B-BRIDGE-ORPHAN-1 LINEA ETH `0xd8b6e516`): Either assign a corrId and matching BRIDGE_IN, OR explicitly mark as `genuineEvidenceMissingProven` (~$0.83 impact). This is in scope for this plan.

---

## Blocker 3: B-GMX-LP-SETTLEMENT-UNKNOWN (P3, TBD)

### Problem

All 5 GMX v2 `LP_ENTRY_SETTLEMENT` events emit `UNKNOWN` basisEffect for:
- LP tokens received (GM/GLV tokens)
- ETH execution fee refunds

### Current behavior

- `LP_ENTRY_REQUEST` (before settlement): REALLOCATE_OUT correctly drains USDC/ETH basis to the LP position
- `LP_ENTRY_SETTLEMENT`: GM/GLV tokens arrive with `UNKNOWN` basisEffect (cbD=$0), ETH refund also `UNKNOWN`

### Failed stage

`cost_basis` — the `LP_ENTRY_SETTLEMENT` replay handler does not emit the correct basisEffect for LP tokens (should be `REALLOCATE_IN` carrying the LP position basis) and ETH refunds (should be `ACQUIRE` at market price or CARRY_IN).

### Root cause hypothesis

`LP_ENTRY_SETTLEMENT` is classified but its replay handler falls through to `UNKNOWN` because:
- The handler for LP_ENTRY_SETTLEMENT exists but does not match the GMX v2 flow structure, OR
- GMX v2 settlements use a different flow structure than other protocols, and the handler doesn't recognize them

### Required investigation

1. Check the normalized flow structure for `0x1aa3438d` (LP_ENTRY_SETTLEMENT GMX):
   ```javascript
   db.normalized_transactions.findOne({ txHash: { $regex: "0x1aa3438d" } })
   ```
2. Find the LP_ENTRY_SETTLEMENT replay handler in the codebase
3. Check if the GM token is recognized as an LP receipt token

### Proposed fix (conditional on investigation)

- If the GM/GLV token is not recognized as LP receipt: add it to the LP receipt token registry
- If the replay handler doesn't handle settlement: implement the `LP_ENTRY_SETTLEMENT` case to emit `REALLOCATE_IN` for the LP token (with the LP corridor basis)

### Severity note

Before implementing, run the following basis-sum query to determine if GMX LP basis already in the corridor justifies P2 elevation:
```javascript
db.asset_ledger_points.aggregate([
  { $match: { basisEffect: "REALLOCATE_OUT", correlationId: { $regex: "gmx-lp:" } } },
  { $group: { _id: null, total: { $sum: { $toDouble: "$costBasisDeltaUsd" } }, count: { $sum: 1 } } }
])
```
If `|total| > $2,000`, elevate B-GMX-LP-SETTLEMENT to **P2** (the defect is prospective — every future LP exit will return zero basis for this accumulated amount).

### Acceptance Criteria

**AC-1**: All 5 GMX LP_ENTRY_SETTLEMENT events emit `REALLOCATE_IN` for **GM and GLV tokens** (both variants) with `costBasisDeltaUsd > 0`. The expected value for `0x1aa3438d` is approximately the sum of the USDC and ETH REALLOCATE_OUT from the paired LP_ENTRY_REQUEST (verify via LP corridor corrId). The fix must handle both GM (market-making token) and GLV (vault token) variants.
**AC-2**: ETH execution fee refund (`0x1aa3438d` refund flow) gets `ACQUIRE` basisEffect with `costBasisDeltaUsd` at market price (not `UNKNOWN`).
**AC-3**: Pipeline PENDING=0 after rebuild.
**AC-4** (regression): No existing LP protocols (PancakeSwap, Uniswap, Aave) have their LP_ENTRY_SETTLEMENT basisEffect changed — confirm all other LP settlements still emit expected effects.

---

## Execution Order

Given that B-CROSS-UID TON, B-BRIDGE-ORPHAN-2, and B-GMX all require pre-fix investigation, the implementation order is:

1. **Pre-fix audit** (financial-logic-auditor): run the MongoDB queries above for each blocker and return the root-cause hypothesis verdict.
2. **Implement** (backend-dev): apply fixes once hypotheses are confirmed.
3. **Prod rebuild + verify**.

**Do not implement** before the pre-fix audit confirms the root-cause hypothesis.

---

## Risks

1. **B-BRIDGE-ORPHAN-2**: If destination wallet is genuinely untracked, no code fix is possible. The $3,979 shortfall remains as a data gap.
2. **B-GMX-LP-SETTLEMENT**: If GM/GLV tokens are LP receipts, fixing settlement may affect existing LP corridor logic for other protocols. Needs regression test.
3. **B-CROSS-UID TON**: Timestamp expansion may match incorrect FUNDING_HISTORY records if multiple TON outbounds exist within 300s. Asset + qty filter reduces risk.
