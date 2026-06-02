# Implementation Plan — B-CROSS-UID: Cross-UID FUNDING_HISTORY Outbound Skipped as Self-Transfer

**Slug:** `cross-uid-basis-2026-06-02`
**Date:** 2026-06-02
**Severity:** P2
**Blocker ID:** B-CROSS-UID

---

## Scope

- **Assets:** BTC (~$112), TON (~$148), ETH (~$28–53) — currently at avco=$0 or wrong
- **Pattern:** `bybit-cross-uid-v1:*` correlationId — cross-UID transfers between Bybit accounts (e.g., BYBIT:516601508 → BYBIT:33625378)
- **Mechanism:** FUNDING_HISTORY outbound on source UID is skipped as Bybit self-transfer → no carry ever reaches the pending queue → destination CARRY_IN gets cbD=0

---

## Root Cause

### Pipeline stage: `linking` + `replay → carry`

**Two compounding defects:**

**Defect 1 — Linking stage:** `BybitInternalTransferPairer.pairCrossUidUniversalTransfers()` second pass (line ~164) handles the case where the paired UNIVERSAL_TRANSFER outbound is excluded from accounting (to avoid double-counting with FUNDING_HISTORY). It correctly sets `continuityCandidate=true` on the orphaned inbound (destination wallet). However, it does NOT also assign the same `bybit-cross-uid-v1:<uuid>` correlationId to the **FUNDING_HISTORY outbound** on the source UID. Without this, the FUNDING_HISTORY outbound has no correlationId pairing it to the inbound — carry is never pushed to the cross-UID pending queue.

**Defect 2 — Replay stage:** `ReplayDispatcher.isBybitSelfTransfer()` (line ~533) detects Bybit UTA↔FUND transfers (same UID normalized wallet) and skips them as no-ops. `AccountingAssetIdentitySupport.positionWalletAddress()` normalizes both `BYBIT:516601508:FUND` → `BYBIT:516601508` and `BYBIT:33625378` → `BYBIT:33625378`. These are DIFFERENT UIDs, so they should NOT match. However, if the FUNDING_HISTORY outbound has `matchedCounterparty` pointing to a same-UID wallet variant, `isBybitSelfTransfer` incorrectly returns true and the CARRY_OUT is skipped entirely.

The result: no carry in the pending queue → destination CARRY_IN gets cbD=0 → avco=$0.

---

## Changes

### 1. `BybitInternalTransferPairer.java` — link FUNDING_HISTORY outbound in second pass

In `pairCrossUidUniversalTransfers()`, second pass (line ~164), after setting correlationId on the loner inbound, **also find the FUNDING_HISTORY outbound** on the source UID and assign it the same correlationId:

```java
// After linking the loner inbound:
String corrId = CROSS_UID_CORRELATION_PREFIX + uuid;
loner.setCorrelationId(corrId);
loner.setContinuityCandidate(true);
loner.setMatchedCounterparty(excludedPartner.getWalletAddress());
loner.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
demoteBuySellToTransfer(loner);
loner.setUpdatedAt(now);
dirty.add(loner);
rewrites++;

// NEW: also link the FUNDING_HISTORY outbound on the source UID so
// isBybitSelfTransfer's corrId guard unblocks it in replay.
// NOTE: do NOT set continuityCandidate=true here — that flag is inbound-only.
// NOTE: excludedFromAccounting=true on FUNDING_HISTORY is NOT cleared; it only
// gates balance-refresh queries, not replay. The corrId guard in isBybitSelfTransfer
// is sufficient to let this record emit a CARRY_OUT during replay.
NormalizedTransaction fundingOutbound = findFundingHistoryOutbound(
        loner, excludedPartner, fundingByUid);
if (fundingOutbound != null
        && (fundingOutbound.getCorrelationId() == null
            || fundingOutbound.getCorrelationId().isBlank())) {
    fundingOutbound.setCorrelationId(corrId);
    fundingOutbound.setMatchedCounterparty(loner.getWalletAddress());
    fundingOutbound.setType(NormalizedTransactionType.INTERNAL_TRANSFER);
    demoteBuySellToTransfer(fundingOutbound);
    fundingOutbound.setUpdatedAt(now);
    dirty.add(fundingOutbound);
    rewrites++;
}
```

**Pre-load FUNDING_HISTORY candidates once per `repairAll()` run** (not per-orphan query):

```java
// At top of pairCrossUidUniversalTransfers(), after building `candidates`:
Set<String> involvedUids = candidates.stream()
        .map(tx -> extractBybitUid(tx.getWalletAddress()))
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
Map<String, List<NormalizedTransaction>> fundingByUid =
        loadFundingHistoryCandidates(involvedUids);
```

**New private methods:**

```java
/** Load unpaired FUNDING_HISTORY outbounds for all involved UIDs in one query. */
private Map<String, List<NormalizedTransaction>> loadFundingHistoryCandidates(
        Set<String> uids
) {
    if (uids.isEmpty()) return Collections.emptyMap();
    List<String> walletPatterns = uids.stream()
            .map(uid -> "BYBIT:" + uid)
            .collect(Collectors.toList());
    Query query = Query.query(new Criteria().andOperator(
            Criteria.where("source").is(NormalizedTransactionSource.BYBIT),
            Criteria.where("walletAddress").in(walletPatterns),
            Criteria.where("_id").not().regex("uni_trans_"),
            new Criteria().orOperator(
                    Criteria.where("correlationId").exists(false),
                    Criteria.where("correlationId").is(null),
                    Criteria.where("correlationId").is("")
            )
    ));
    return mongoOperations.find(query, NormalizedTransaction.class).stream()
            .collect(Collectors.groupingBy(
                    tx -> Objects.requireNonNullElse(extractBybitUid(tx.getWalletAddress()), "")));
}

/**
 * Find the FUNDING_HISTORY outbound matching the given inbound from pre-loaded candidates.
 * Matches on: source UID, same principal asset symbol, qty within 5%, timestamp ±60s.
 */
private NormalizedTransaction findFundingHistoryOutbound(
        NormalizedTransaction inbound,
        NormalizedTransaction excludedPartner,
        Map<String, List<NormalizedTransaction>> fundingByUid
) {
    String sourceUid = extractBybitUid(excludedPartner.getWalletAddress());
    if (sourceUid == null) return null;
    Instant ts = inbound.getTimestamp();
    BigDecimal inboundQty = principalQuantity(inbound);
    String assetSymbol = principalAssetSymbol(inbound); // MANDATORY: prevents cross-asset false positives
    if (ts == null || inboundQty == null || inboundQty.signum() <= 0 || assetSymbol == null) {
        return null;
    }
    List<NormalizedTransaction> candidates =
            fundingByUid.getOrDefault(sourceUid, Collections.emptyList());
    return candidates.stream()
            .filter(tx -> principalQuantitySign(tx) < 0)
            .filter(tx -> assetSymbol.equals(principalAssetSymbol(tx)))
            .filter(tx -> {
                BigDecimal qty = principalQuantity(tx);
                return qty != null && inboundQty.subtract(qty.abs()).abs()
                        .compareTo(inboundQty.multiply(new BigDecimal("0.05"))) <= 0;
            })
            .filter(tx -> {
                Instant txTs = tx.getTimestamp();
                return txTs != null
                        && !txTs.isBefore(ts.minusSeconds(60))
                        && !txTs.isAfter(ts.plusSeconds(60));
            })
            .findFirst()
            .orElse(null);
}
```

### 2. `ReplayDispatcher.java` — guard `isBybitSelfTransfer` for cross-UID correlationIds

Add early return in `isBybitSelfTransfer()`:

```java
private boolean isBybitSelfTransfer(NormalizedTransaction transaction) {
    if (transaction == null
            || transaction.getSource() != NormalizedTransactionSource.BYBIT
            || transaction.getType() != NormalizedTransactionType.INTERNAL_TRANSFER) {
        return false;
    }
    // Cross-UID transfers (bybit-cross-uid-v1:) must NEVER be treated as self-transfers —
    // they move basis between different Bybit UIDs and require carry propagation.
    String corrId = transaction.getCorrelationId();
    if (corrId != null && corrId.startsWith(BybitInternalTransferPairer.CROSS_UID_CORRELATION_PREFIX)) {
        return false;
    }
    // ... rest of existing logic
```

---

## Tests

Add to `BybitInternalTransferPairerTest.java`:

| Test | Scenario | Expected |
|------|----------|----------|
| `crossUidFundingHistoryOutboundLinkedWhenUniversalTransferExcluded` | Orphaned inbound + excluded UNIVERSAL_TRANSFER + FUNDING_HISTORY outbound | FUNDING_HISTORY outbound gets same corrId + continuityCandidate=true |
| `crossUidSelfTransferGuardPreventsFalsePositive` | INTERNAL_TRANSFER with `bybit-cross-uid-v1:` corrId | `isBybitSelfTransfer` returns false |
| `crossUidSelfTransferStillCatchesUTAFundSameUID` | UTA↔FUND same UID, no cross-uid corrId | `isBybitSelfTransfer` returns true (unchanged) |

---

## Acceptance Criteria

1. `SYMBOL:BTC BYBIT:33625378` avco recovers from $0 to market-plausible (~$96,406 or proportional).
2. TON and ETH cross-UID CARRY_INs receive `cbD > 0`.
3. BTC SWAP after the cross-UID carry correctly shows cost basis (P&L no longer wrong).
4. No regression in existing cross-UID pairs that currently work.
5. `isBybitSelfTransfer` still correctly skips same-UID UTA↔FUND transfers.

---

## Risks

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| `findFundingHistoryOutbound` finds wrong tx (wrong asset, different user) | Low | Qty match within 5% + timestamp ±60s + source wallet UID filter |
| FUNDING_HISTORY outbound was already excluded; re-including it causes double-count | Low | The outbound is only linked (corrId assigned), not re-included in accounting; `isBybitSelfTransfer=false` guard makes replay process it once as CARRY_OUT |
| MNT cross-UID ($0 basis, genuine) affected | No | MNT source had $0 basis legitimately — carry will still be $0 |

---

## Ordered Tasks

1. Add `CROSS_UID_CORRELATION_PREFIX` reference import to `ReplayDispatcher`.
2. Add guard at top of `isBybitSelfTransfer()` for `bybit-cross-uid-v1:` prefixed corrIds.
3. Add `findFundingHistoryOutbound()` to `BybitInternalTransferPairer`.
4. Wire the new method in the second pass of `pairCrossUidUniversalTransfers()`.
5. Write 3 tests in `BybitInternalTransferPairerTest`.
6. Prod rebuild + `financial-logic-auditor` acceptance check.
