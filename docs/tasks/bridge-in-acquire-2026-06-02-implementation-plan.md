# Implementation Plan: B-BRIDGE-IN-ACQUIRE — Linked BRIDGE_INs classified as ACQUIRE instead of CARRY_IN

**Date:** 2026-06-02  
**Severity:** P1 CRITICAL  
**Estimated cbD shortfall:** Systemic — 122 BRIDGE_INs, ~$222/ETH AVCO overstatement per affected bridge  
**Stage:** Clarification (repair) + Replay (downstream)

---

## 1. Symptom

107 of 122 linked BRIDGE_IN transactions (those with `correlationId` matching `bridge:lifi:*` or
`bridge:across:*`) are replayed as **ACQUIRE at market price** instead of **CARRY_IN at source-chain
AVCO**. This causes:

- AVCO spikes on the destination wallet immediately after each cross-chain bridge.
- Inflated cost basis on the receiving side.
- The corresponding BRIDGE_OUT correctly produces `CARRY_OUT` (cost basis preserved on outbound).

**Example:** Cluster A  
- BRIDGE_OUT `0x3fd2c709` (Arbitrum, ETH, avco=$2,112.93) → `CARRY_OUT cbD: -$2.647`
- BRIDGE_IN `0x74b417` (Ethereum, ETH) → `ACQUIRE cbD: +$2.333` at market $2,335/ETH
- AVCO discontinuity: **+$222/ETH** on destination wallet

---

## 2. Root Cause

### 2.1 What `retagPrincipalFlowsForBridgeContinuity` does

`BridgePairLinkSupport.retagPrincipalFlowsForBridgeContinuity(tx)` demotes a flow's role from
`BUY` → `TRANSFER` and clears `unitPriceUsd`, `valueUsd`, `priceSource`, `avcoAtTimeOfSale`,
`realisedPnlUsd`. For BRIDGE_IN, this converts the inbound ETH flow from a market-priced ACQUIRE
to a price-less TRANSFER that the replay engine carries basis into.

This method is called **inside an `if (continuityCandidate)` guard** in every link service:
`LiFiBridgePairLinkService`, `AcrossBridgePairLinkService`, `MayanCctpBridgePairLinkService`,
`CrossNetworkBridgePairFallbackService`.

### 2.2 State mismatch in DB after fresh rebuild

Queried after fresh `prod-reset-rebuild-backend.sh`:

| Field | BRIDGE_OUT (106 rows) | BRIDGE_IN (122 rows) |
|---|---|---|
| `continuityCandidate` | `true` | `false` (all 122) |
| Principal flow role | `TRANSFER` | `BUY` |
| `unitPriceUsd` | `null` | market price |
| `matchedCounterparty` | set | set |
| `correlationId` | `bridge:lifi:...` | `bridge:lifi:...` |
| `status` | `CONFIRMED` | `CONFIRMED` |

All 122 BRIDGE_INs with a `bridge:` corrId have `continuityCandidate=false` and retain `BUY` role.
The replay engine sees `BUY` → ACQUIRE. No CARRY_IN is produced.

### 2.3 Why no existing repair path covers this case

**`BridgePairContinuityRepairService.reconcileLegacySealedPairs`** — processes BRIDGE_OUTs with
`continuityCandidate=false`. All our BRIDGE_OUTs already have `continuityCandidate=true`. Skipped.

**`BridgePairContinuityRepairService.reconcileInboundCounterparty`** — processes BRIDGE_INs with
`status IN (NEEDS_REVIEW, PENDING_STAT, PENDING_PRICE)`. All our BRIDGE_INs have
`status=CONFIRMED`. Skipped.

**Gap:** No repair path exists for:
- type=BRIDGE_IN
- `correlationId` matches `^bridge:`
- `continuityCandidate != true`
- `matchedCounterparty` set (already linked)
- `status = CONFIRMED`

---

## 3. Proposed Fix

### 3.1 New method: `BridgePairContinuityRepairService.reconcileLegacySealedInbounds`

Add a new public method alongside the existing `reconcileLegacySealedPairs`:

```java
/**
 * Cycle/N: repairs BRIDGE_IN rows that were linked (correlationId + matchedCounterparty set)
 * but left with continuityCandidate=false — causing ACQUIRE instead of CARRY_IN in replay.
 *
 * This happens when a BRIDGE_OUT was processed first and reached continuityCandidate=true,
 * but the BRIDGE_IN was never re-evaluated after the BRIDGE_OUT was confirmed (because all
 * existing repair paths gate on continuityCandidate=false for the outbound, or on
 * non-CONFIRMED status for the inbound).
 */
public int reconcileLegacySealedInbounds(int batchSize) {
    List<NormalizedTransaction> batch = loadLegacySealedInbounds(batchSize);
    if (batch.isEmpty()) {
        return 0;
    }
    Instant now = Instant.now();
    List<NormalizedTransaction> dirty = new ArrayList<>();
    int repaired = 0;
    for (NormalizedTransaction inbound : batch) {
        if (repairInboundFromSealedPair(inbound, now, dirty)) {
            repaired++;
        }
    }
    if (!dirty.isEmpty()) {
        normalizedTransactionRepository.saveAll(deduplicateById(dirty));
        log.info("BRIDGE_IN_SEALED_REPAIR batch={} repaired={} saved={}", batch.size(), repaired, dirty.size());
    }
    return repaired;
}
```

### 3.2 New helper: `loadLegacySealedInbounds`

```java
private List<NormalizedTransaction> loadLegacySealedInbounds(int batchSize) {
    Query query = Query.query(new Criteria().andOperator(
            Criteria.where("type").is(NormalizedTransactionType.BRIDGE_IN),
            Criteria.where("correlationId").regex(BRIDGE_CORRELATION_PREFIX),   // "^bridge:"
            Criteria.where("continuityCandidate").ne(true),
            Criteria.where("matchedCounterparty").exists(true).ne(null).ne("")
    ));
    query.with(Sort.by(
            Sort.Order.asc("blockTimestamp"),
            Sort.Order.asc("transactionIndex"),
            Sort.Order.asc("_id")
    ));
    query.limit(Math.max(1, batchSize));
    return mongoOperations.find(query, NormalizedTransaction.class);
}
```

### 3.3 New helper: `repairInboundFromSealedPair`

**Critical constraint (from architect review):** Do NOT call `applyContinuityRepair(outbound, inbound)`.
That method also calls `statusAfterContinuityRetag(outbound)`, which would demote all 106
already-correct BRIDGE_OUTs from `CONFIRMED → PENDING_STAT`. Use the **inbound-only** pattern
from the existing `repairInboundCounterparty` method — only the inbound is modified and saved.

```java
private boolean repairInboundFromSealedPair(
        NormalizedTransaction inbound,
        Instant now,
        List<NormalizedTransaction> dirty
) {
    if (inbound == null || Boolean.TRUE.equals(inbound.getContinuityCandidate())) {
        return false;
    }
    NormalizedTransaction outbound = findPairedOutbound(inbound);
    if (outbound == null) {
        return false;
    }
    if (!BridgePairLinkSupport.supportsPlainMoveBasis(outbound, inbound)) {
        return false;
    }
    // Inbound-only repair: do NOT call applyContinuityRepair (touches outbound status).
    return repairInboundCounterparty(outbound, inbound, now, dirty);
}
```

`repairInboundCounterparty` (already exists, lines 83–117):
1. `applyLinkedBridgeCounterparty(outbound, inbound, now)` — stamps inbound flow counterparty
2. `inbound.setContinuityCandidate(true)` — inbound only
3. `retagPrincipalFlowsForBridgeContinuity(inbound, now)` — inbound only (BUY→TRANSFER, price cleared)
4. Clears `COUNTERPARTY_TYPE_MISSING_REASON` / `FLOW_COUNTERPARTY_MISSING_REASON`
5. `PriceableFlowPolicy.statusAfterContinuityRetag(inbound)` → `PENDING_STAT` — inbound only
6. Adds only **inbound** to `dirty` — outbound never touched

### 3.4 Integration: `LinkingBatchProcessor`

In `LinkingBatchProcessor`, call `reconcileLegacySealedInbounds` as a pipeline step. The call
should run after `reconcileLegacySealedPairs` and before the pipeline completes. Pattern:

```java
// After reconcileLegacySealedPairs(...)
int sealedInboundRepaired = bridgePairContinuityRepairService.reconcileLegacySealedInbounds(batchSize);
```

The call belongs **after `reconcilePairedInboundCounterparty`** in `LinkingBatchProcessor`. The
method is idempotent: once `continuityCandidate=true` is set on the inbound, the query will no
longer load the row.

---

## 4. Status transition after repair

When `retagPrincipalFlowsForBridgeContinuity(inbound)` runs:
- ETH BUY flow: `role` → `TRANSFER`, `unitPriceUsd` → `null`
- `PriceableFlowPolicy.statusAfterContinuityRetag(inbound)` → determines new status

For a BRIDGE_IN with a TRANSFER role and no price, this should produce `PENDING_STAT` (waiting for
replay to assign CARRY_IN). The prod-reset-rebuild pipeline will then replay it correctly as
CARRY_IN with source-chain AVCO.

---

## 5. B-LP-UNSTAKE-ETH-MISS — Assessment

**Auditor finding:** LP_POSITION_UNSTAKE on Base doesn't transfer ETH basis before bridging.

**Re-investigation result:** The 0.799 ETH bridged out (`0x4ca0b79e`) on Base has zero cost basis
because it accumulated from `SPONSORED_GAS_IN` receipts (zero-basis by design) on that wallet.
The `LP_ENTRY 0xbdf8cee9` only deposited 0.00129 ETH. The `LP_POSITION_UNSTAKE 0xe66f9242` has no
ETH outflow in its normalized flows (only fee).

**Conclusion:** B-LP-UNSTAKE-ETH-MISS is likely a **data characteristic**, not a code bug. The
ETH on Base has $0 basis because it was received as SPONSORED_GAS_IN (zero-cost). The AVCO spike
on Arbitrum after the BRIDGE_IN is caused entirely by B-BRIDGE-IN-ACQUIRE. Once B-BRIDGE-IN-ACQUIRE
is fixed, the BRIDGE_IN on Arbitrum will carry the zero basis from Base, and the AVCO will not
inflate.

**Action:** Defer B-LP-UNSTAKE-ETH-MISS pending re-verification after B-BRIDGE-IN-ACQUIRE fix.

---

## 6. Acceptance Criteria

**AC-1** (Systemic coverage): After prod rebuild, count of BRIDGE_INs with `correlationId regex
^bridge:` AND `continuityCandidate != true` = **0**.

**AC-2** (Flow retag): All BRIDGE_INs with `bridge:lifi:`, `bridge:across:`, or `bridge:mayan:`
corrId have principal inbound flow `role = TRANSFER` and `unitPriceUsd = null`. The fix applies
to all protocols sharing the `^bridge:` prefix — not only LiFi.

**AC-3** (Ledger effect): BRIDGE_IN `0x74b417` (Cluster A) produces `CARRY_IN` in `asset_ledger_points`
with `cbD` within **±$0.10** (or ±5%) of the paired BRIDGE_OUT `CARRY_OUT cbD` ($2.647).
No ACQUIRE entry for this tx. (Note: ±$50 tolerance is 1,890% of the expected value and would
pass even if still producing ACQUIRE — use tight tolerance.)

**AC-4** (AVCO continuity Cluster A — same wallet, cross-chain): After `0x3fd2c709` BRIDGE_OUT and
`0x74b417` BRIDGE_IN, ETH AVCO on destination wallet `0xf03b52e8` equals source-chain AVCO
(within ±$5/ETH). No +$200 spike.

**AC-5** (AVCO continuity — all assets): Count of BRIDGE_INs for ANY asset (ETH, USDC, WBTC, ARB,
etc.) with `ACQUIRE` basisEffect in `asset_ledger_points` where `correlationId regex ^bridge:` =
**0** (was 107 linked BRIDGE_INs with ACQUIRE). All 122 targeted BRIDGE_INs must be repaired:
the remaining 15 not producing ACQUIRE today may still have `continuityCandidate=false` and BUY
flow roles; after repair their status transitions to PENDING_STAT and replay assigns CARRY_IN.
The fix is asset-agnostic; verify across all asset symbols.

**AC-6** (Regression: BRIDGE_OUTs): BRIDGE_OUT `CARRY_OUT` counts unchanged. No regression in
existing `continuityCandidate=true` BRIDGE_OUTs.

**AC-7** (Regression: non-bridge transfers): INTERNAL_TRANSFER, EXTERNAL_TRANSFER_IN CARRY_IN
counts unchanged.

**AC-8** (Status transition): After repair, repaired BRIDGE_INs have status = `PENDING_STAT`
(not CONFIRMED) if their flows were modified. After full prod rebuild, count of BRIDGE_INs with
`correlationId regex ^bridge:` AND `status IN (PENDING_STAT, PENDING_PRICE, NEEDS_REVIEW)` = 0
(all re-processed to CONFIRMED).

**AC-9** (Cross-wallet bridge): For bridges where BRIDGE_OUT and BRIDGE_IN are on **different
wallets** (e.g., source wallet `0x1a87f12a` on Arbitrum, destination wallet `0xf03b52e8` on
Ethereum): the repair correctly links and retaggs both sides. `findPairedOutbound` uses
`correlationId` only (no wallet filter) and handles the cross-wallet case.

---

## 7. Files Modified

| File | Change |
|---|---|
| `BridgePairContinuityRepairService.java` | + `reconcileLegacySealedInbounds`, `loadLegacySealedInbounds`, `repairInboundFromSealedPair` |
| `LinkingBatchProcessor.java` | + call to `reconcileLegacySealedInbounds` |
| `BridgePairContinuityRepairServiceTest.java` | + tests for new method |

No changes to replay handlers, classification, or schema.

---

## 8. Risks

| Risk | Mitigation |
|---|---|
| False-positive retag: BRIDGE_IN that legitimately should be ACQUIRE | `supportsPlainMoveBasis` guard prevents retagging mismatched asset pairs (e.g., ETH out, USDC in) |
| BRIDGE_OUT has no paired BRIDGE_IN (orphan outbound) | `findPairedOutbound` returns null → skipped |
| Already-priced BRIDGE_IN loses its price after retag | Correct behavior: TRANSFER flows do not need market price; basis carried via replay CARRY_IN |
| Re-processing loop | Idempotent: after `continuityCandidate=true` set, query excludes the row |
| Ambiguous `findPairedOutbound`: multiple BRIDGE_OUTs share the same `correlationId` | `findPairedOutbound` already has non-deterministic fallback: prefers the BRIDGE_OUT whose `txHash` matches `inbound.matchedCounterparty`. If `matchedCounterparty` is empty, uses `matches.getFirst()`. Since `matchedCounterparty` was set during initial linking, this is deterministic. No code change needed; document in Javadoc. |
| ADR-020 late-carry ordering: replay processes the repaired BRIDGE_IN before the BRIDGE_OUT's pending transfer is available | Not a risk in the prod-reset-rebuild flow because all transactions are replayed in chronological order from scratch. The BRIDGE_OUT CARRY_OUT populates the pending transfer queue before the BRIDGE_IN CARRY_IN is processed. |
