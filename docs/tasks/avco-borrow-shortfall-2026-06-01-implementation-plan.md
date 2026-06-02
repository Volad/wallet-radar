# AVCO Borrow + Shortfall — Implementation Plan 2026-06-01

**Audit ref:** `docs/tasks/avco-spikes-2026-05-31-audit.md` (new blockers from final audit)  
**Date:** 2026-06-01  
**Blockers:** B-USDC-1, B-SHORTFALL-1, W-1, W-7  
**Predecessor cycle:** avco-spikes-2026-05-31 — CLOSED (all P0/P1 pass)

---

## Summary

| ID | Description | Stage | Priority |
|----|-------------|-------|----------|
| B-USDC-1 | `BorrowReplayHandler` uses `position.perWalletAvco()` instead of market price → 800 USDC borrow recorded with $1,225,570 basis | replay | **P1** |
| B-SHORTFALL-1 | BASE ETH position had $0 basis when 0.799 ETH was bridged to ARBITRUM → CARRY_IN gets $0 | linking/move_basis | **P2 — deferred** |
| W-1 | ETH basis drop $2,589→$1,527 AVCO not fully traced; AVCO is internally consistent but mechanism unclear | audit | **CLOSED** |
| W-7 | BTC cross-UID CARRY_IN gets qty but $0 basis (source `excludedFromAccounting=true`) | replay | **P2** |

---

## Iteration protocol (mandatory after each merge)

1. `./scripts/prod-reset-rebuild-backend.sh --skip-frontend`
2. Wait for normalization + cost-basis replay
3. Run verification queries below
4. Delegate `financial-logic-auditor` (readonly) — new blockers only
5. If blockers remain → append B-N and repeat

---

## B-USDC-1 — BorrowReplayHandler AVCO-priced acquisition

### Root cause

`BorrowReplayHandler.apply()` (line 57):

```java
BigDecimal portfolioAvco = position.perWalletAvco() == null ? BigDecimal.ZERO : position.perWalletAvco();
if (portfolioAvco.signum() == 0
        && flow.getUnitPriceUsd() != null
        && flow.getUnitPriceUsd().signum() > 0) {
    portfolioAvco = flow.getUnitPriceUsd();
}
BigDecimal acquisitionCostUsd = qty.multiply(portfolioAvco, MC);
```

The handler uses the **existing position AVCO** as the price for the borrowed asset. When the USDC position on ARBITRUM had AVCO=$1,532 (accumulated from LP REALLOCATE_IN operations), borrowing 800 USDC was recorded at 800×$1,532 = **$1,225,570** instead of 800×$1 = **$800**.

Market price (`flow.getUnitPriceUsd()`) is ONLY used as a fallback when the position AVCO is zero.

**Financial impact:** USDC corridor CARRY_OUT of $1.2M propagates to ZKSYNC CARRY_IN of $1.2M → USDC AVCO on ZKSYNC ≈ $1,532/USDC (should be $1). Does not affect ETH AVCO.

### Fix

**File:** `BorrowReplayHandler.java`

Replace:
```java
BigDecimal portfolioAvco = position.perWalletAvco() == null ? BigDecimal.ZERO : position.perWalletAvco();
if (portfolioAvco.signum() == 0
        && flow.getUnitPriceUsd() != null
        && flow.getUnitPriceUsd().signum() > 0) {
    portfolioAvco = flow.getUnitPriceUsd();
}
```

With:
```java
// Borrowed assets are fresh acquisitions: always price at market, not at position AVCO.
// Using position AVCO for borrows inflates basis when the existing position AVCO
// diverges from the borrow asset's market price (e.g., USDC position AVCO ≠ $1).
BigDecimal portfolioAvco = flow.getUnitPriceUsd() != null && flow.getUnitPriceUsd().signum() > 0
        ? flow.getUnitPriceUsd()
        : BigDecimal.ZERO;
```

Keep all other logic (liability tracker, `applyBuyWithAcquisitionCost`) unchanged.

### Tests

- `BorrowReplayHandlerTest` (new or existing): BORROW 800 USDC @ market $1 when position has AVCO=$1,532 → `cbD=$800`, not $1,225,570
- Edge case: BORROW when position AVCO=0 and no market price → `cbD=0`
- Edge case: BORROW ETH when ETH market price is missing → `cbD=0`

### Acceptance

```javascript
// USDC position AVCO should be near $1 on all networks after rebuild
db.asset_ledger_points.find({
  assetSymbol: "USDC",
  basisEffect: "ACQUIRE",
  costBasisDeltaUsd: { $gt: NumberDecimal("10000") }
}).count() === 0

// BORROW TX for 0xf299178e must have USDC cbD ≈ $800
db.asset_ledger_points.find({
  normalizedTransactionId: /0xf299178e/,
  assetSymbol: "USDC"
}).forEach(r => print(r.basisEffect, r.costBasisDeltaUsd, r.quantityDelta));
// Expected: ACQUIRE cbD≈800 qty=800
```

---

## W-1 — ETH basis drop $2,589→$1,527 AVCO — CLOSED (expected correction)

### Analysis

The ETH basis dropped from ~$8,085 (at AVCO=$2,589 × 3.12 ETH) to ~$4,767 (at AVCO=$1,527 × 3.12 ETH). Total cbD sum confirmed at **$5,232** (includes gas fees consumed).

| Contributor | Basis removed |
|---|---|
| `0x38d445c4` (0.799 ETH BASE→ARB): was ACQUIRE @ $1,896 market, now CARRY_IN $0 | −$1,514 |
| `0x6cbda0a4` (0.546 ETH BASE→ZKSYNC): was ACQUIRE @ $3,200, now CARRY_IN from $0 source | −$1,747 |
| 3 Across BRIDGE_INs reclassified from ACQUIRE to CARRY_IN (source AVCO < market) | −$200 |
| B-SPIKE-1 spurious CARRY_IN removal | −$100 |
| **Total** | **~$3,561** |

**Verdict:** The drop is a financial correction. Both BRIDGE_INs received ETH from BASE positions that had zero/near-zero basis. Previously they were classified as ACQUIRE at market price, inflating ETH AVCO. The current AVCO=$1,527 is internally consistent (basis/qty = $4,767/3.12 ETH ≈ $1,527). **No action required.**

---

## B-SHORTFALL-1 — BASE LiFi 0.799 ETH zero basis — DEFERRED

### Root cause (confirmed)

`0x4ca0b79e` BRIDGE_OUT on BASE:
- The BASE ETH position at CARRY_OUT time had: `totalCostBasisBefore=$0`, `quantityBefore=0.0000024 ETH`
- The BRIDGE_OUT sent **0.799 ETH** but the tracked position only held **0.0000024 ETH**
- The 0.799 ETH came from a LiFi-internal swap on BASE (converting stablecoins → ETH) that was not tracked as an on-chain acquisition in our session
- CARRY_OUT basis = $0 → BRIDGE_IN on ARBITRUM (0x38d445c4) gets $0

This is the **out-of-session swap** case: the ETH source is a LiFi internal route (same block, separate FROM address) outside the user's tracked wallet addresses, OR it is the dust→ETH swap that isn't indexed as an ACQUIRE.

### Fix direction (deferred)

Deeper on-chain analysis of `0x4ca0b79e` internal calls required to determine if:
(a) The 0.799 ETH was swapped from USDC in the same LiFi route → reclassify as `SWAP` with proper USDC DISPOSE + ETH ACQUIRE
(b) The ETH came from an untracked wallet → mark as `EVIDENCE_MISSING`, accept $0 basis

**Defer to next audit cycle.** Financial impact: 0.799 ETH with $0 basis understates cost by ~$1,514 (at $1,896/ETH). Affects ETH AVCO on ARBITRUM only.

---

## W-7 — BTC cross-UID CARRY_IN $0 basis

### Root cause

BTC cross-UID transfer (BYBIT:516601508 → BYBIT:33625378). The source side is `excludedFromAccounting=true` to prevent double-counting; as a result its outbound carry is never queued. The destination gets qty but $0 basis.

The strict B-BTC-1 acceptance criterion (DISPOSE qty=0 count = 0) PASSES because a second pairing pass was added. But economic accuracy requires the source basis (~$159) to be propagated.

**Fix direction:** When `excludedFromAccounting=true` source emits CARRY_OUT as part of a confirmed cross-UID corridor, the carry basis should still be queued to the destination. This requires modifying the replay dispatcher's exclusion logic to allow carry propagation even for excluded sources.

**Defer to next cycle** unless BTC P&L accuracy is critical. Financial impact: ~$159 basis loss on destination BTC.

---

## Documentation

| Doc | Change |
|-----|--------|
| `docs/03-accounting.md` | Clarify: BORROW basis uses market price, not position AVCO |

---

## Implementation order

1. **B-USDC-1** (P1 — one-liner fix in BorrowReplayHandler)
2. **W-7** (P2 — optional, BTC carry from excluded source)
3. **B-SHORTFALL-1** (P2 — requires on-chain investigation first)

---

## Risks

| Risk | Mitigation |
|------|------------|
| B-USDC-1 fix causes BORROW basis to be $0 when no market price available | Gate on `flow.getUnitPriceUsd() != null && > 0`; cbD=0 fallback is safe |
| W-7 fix breaks exclusion logic for other excluded sources | Scope to cross-UID corridor only (correlationId starts with `bybit-cross-uid-v1:`) |
| Fixing W-7 creates double-counting on source | Source `excludedFromAccounting=true` prevents source ledger points; carry is queued only, no source LP emitted |

---

## Verification commands

```bash
./scripts/prod-reset-rebuild-backend.sh --skip-frontend

# B-USDC-1: no large USDC ACQUIRE
docker exec walletradar-mongodb-prod mongosh walletradar --quiet --eval '
print("USDC large ACQUIRE:", db.asset_ledger_points.countDocuments({
  assetSymbol: "USDC",
  basisEffect: "ACQUIRE",
  costBasisDeltaUsd: { $gt: NumberDecimal("10000") }
}));
'

# BORROW TX check
docker exec walletradar-mongodb-prod mongosh walletradar --quiet --eval '
db.asset_ledger_points.find({ _id: { $regex: "0xf299178e" }, assetSymbol: "USDC" }).forEach(r =>
  print(r.basisEffect, r.costBasisDeltaUsd, r.quantityDelta)
);
'

# ETH AVCO
curl -s "http://127.0.0.1:18086/api/v1/sessions/df5e69cc-a0c0-4910-8b7d-74488fa266e2/asset-ledger?familyIdentity=FAMILY:ETH" \
  | jq '{avco: .current.avcoUsd, unavailable: [.timeline[]|select(.avcoKind=="UNAVAILABLE")]|length}'
```
