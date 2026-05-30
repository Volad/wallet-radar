# Implementation Plan: Pendle LPT Receipt Pool & Bybit Corridor Basis

_Blockers: S-4A, S-4B, P-B_
_Date: 2026-05-30_

---

## Scope

- **Networks**: MANTLE (S-4), ARBITRUM (P-B)
- **Assets**: cmETH (MANTLE), ETH (Bybit corridor on ARBITRUM / MANTLE)
- **Blockers addressed**:
  - **S-4A** — Pendle LP_ENTRY does not create LP receipt pool because `isLpReceiptMarker("PENDLE-LPT")` = false
  - **S-4B** — Equilibria LP_EXIT uses `eqbPENDLE-LPT` → wrong correlationId → pool not found
  - **P-B** — Bybit corridor CARRY_IN receives zero basis (no on-chain CARRY_OUT from CEX)

---

## Root causes (stage by stage)

### S-4A — replay stage (LpReceiptEntryReplayHandler)

`hasOnlyOutboundPrincipalFlows()` uses `isLpReceiptMarker()` to filter out LP receipt tokens before computing net-by-asset flows. PENDLE-LPT tokens are NOT recognized as LP receipt markers (method only checks `LP-RECEIPT:` prefix, `-LP-`, `-LP` suffix). Result: PENDLE-LPT counted as net-inbound principal → method returns `false` → LP receipt pool never created for any Pendle LP_ENTRY.

```java
// Current (broken for Pendle)
private static boolean isLpReceiptMarker(NormalizedTransaction.Flow flow) {
    String sym = flow.getAssetSymbol().trim().toUpperCase();
    return sym.startsWith("LP-RECEIPT:") || sym.contains("-LP-") || sym.endsWith("-LP");
    // "PENDLE-LPT" → false ← BUG
}
```

### S-4B — classification stage (PendleLpCorrelationSupport)

`marketIdFromSymbol("eqbPENDLE-LPT")` returns `eqbpendle-lpt` (after slugify). The LP_ENTRY used `PENDLE-LPT` → `pendle-lpt`. The slugs differ → `correlationId` of LP_EXIT does not match the LP receipt pool key created by LP_ENTRY.

```java
// Current (produces wrong marketId for Equilibria-wrapped tokens)
public static String marketIdFromSymbol(String assetSymbol) {
    String normalized = assetSymbol.trim().toUpperCase(Locale.ROOT);
    if (!normalized.contains("PENDLE") && !normalized.contains("-LPT")) return null;
    return slugify(normalized);
    // "EQBPENDLE-LPT" → slugify → "eqbpendle-lpt" ← different from "pendle-lpt"
}
```

### P-B — move_basis stage (BybitVenueInternalReplayHandler / TransferReplayHandler)

`BYBIT-CORRIDOR:NETWORK:txHash` corrId tags on-chain INTERNAL_TRANSFER from Bybit exchange to user wallet. Replay attempts to `takeFromBucket(corrId)` but the bucket was never filled (no on-chain CARRY_OUT from Bybit). Result: `CarryTransfer = null` → 0 basis applied to the inbound ETH.

---

## Ordered changes (upstream first)

### Change 1 — S-4B: PendleLpCorrelationSupport — strip Equilibria/staking prefix

**File**: `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/lp/PendleLpCorrelationSupport.java`

**Change**: In `marketIdFromSymbol()`, before applying the Pendle detection, strip known staking/wrapper prefixes (`EQB`) to produce a canonical market ID:

```java
public static String marketIdFromSymbol(String assetSymbol) {
    if (assetSymbol == null || assetSymbol.isBlank()) {
        return null;
    }
    String normalized = assetSymbol.trim().toUpperCase(Locale.ROOT);
    // Strip Equilibria staking wrapper prefix so eqbPENDLE-LPT maps to same ID as PENDLE-LPT
    if (normalized.startsWith("EQB") && (normalized.contains("PENDLE") || normalized.contains("-LPT"))) {
        normalized = normalized.substring(3);
    }
    if (!normalized.contains("PENDLE") && !normalized.contains("-LPT")) {
        return null;
    }
    return slugify(normalized);
}
```

**Result**:
- `"PENDLE-LPT"` → `"pendle-lpt"` (unchanged)
- `"eqbPENDLE-LPT"` → strip `EQB` → `"PENDLE-LPT"` → `"pendle-lpt"` ✓
- `"SOME-OTHER-TOKEN"` → null (unchanged)

**Tests to add** (`PendleLpCorrelationSupportTest.java`):
- `T1_eqbPendleLptMapsToSameMarketIdAsPendleLpt()`
- `T2_regularPendleLptUnchanged()`
- `T3_nonPendleTokenReturnsNull()`

---

### Change 2 — S-4A: LpReceiptEntryReplayHandler — recognize Pendle LP tokens as LP receipt markers

**File**: `backend/src/main/java/com/walletradar/costbasis/application/replay/handler/LpReceiptEntryReplayHandler.java`

**Change**: Extend `isLpReceiptMarker()` to delegate to `PendleLpCorrelationSupport.marketIdFromSymbol()`:

```java
private static boolean isLpReceiptMarker(NormalizedTransaction.Flow flow) {
    if (flow == null || flow.getAssetSymbol() == null) {
        return false;
    }
    String sym = flow.getAssetSymbol().trim().toUpperCase();
    // Existing patterns: LP-RECEIPT: prefix, -LP- substring, -LP suffix
    if (sym.startsWith("LP-RECEIPT:") || sym.contains("-LP-") || sym.endsWith("-LP")) {
        return true;
    }
    // Pendle LP tokens (PENDLE-LPT, eqbPENDLE-LPT, etc.) are LP receipt markers for Pendle LP positions
    return PendleLpCorrelationSupport.marketIdFromSymbol(flow.getAssetSymbol()) != null;
}
```

**Note**: Change 1 must land first so `PendleLpCorrelationSupport.marketIdFromSymbol("eqbPENDLE-LPT")` returns non-null.

**Impact on `hasOnlyOutboundPrincipalFlows()`**:
- `PENDLE-LPT +0.445` → `isLpReceiptMarker=true` → EXCLUDED from netByAsset
- `cmETH -0.861` → netByAsset: cmETH = -0.861 (negative only)
- Returns `true` → LP receipt pool CREATED ✓

**Tests to add** (`LpReceiptEntryReplayHandlerTest.java`):
- `T6_pendleLptInboundTreatedAsReceiptNotPrincipal()` — LP_ENTRY with cmETH out and PENDLE-LPT in → `hasOnlyOutboundPrincipalFlows()=true`
- `T7_eqbPendleLptInboundTreatedAsReceiptNotPrincipal()` — same with `eqbPENDLE-LPT`

---

### Change 3 — S-4B (supplemental): Verify LP_EXIT classification calls PendleLpCorrelationSupport for outbound Pendle flows

**File**: `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/lp/LpClassificationFlowSupport.java` (and/or `GmxLpClassifier`, `LpSemanticClassifier`)

**Investigate**: Confirm `LpClassificationFlowSupport` or the Pendle LP classifier calls `PendleLpCorrelationSupport.correlationIdFromMovementLegs()` for LP_EXIT transactions. The early LP_EXIT on 2025-04-18 has empty corrId despite having `PENDLE-LPT -0.337` outbound flow with contract set.

**Expected**: If the LP_EXIT classification code path already calls `PendleLpCorrelationSupport`, the empty corrId is a stale normalization artifact that will be fixed by re-normalization. If not, add the call.

**Action**: Read `LpClassificationFlowSupport.java` and check where correlationId is assigned for LP_EXIT. If PendleLpCorrelationSupport is not called for LP_EXIT, add it.

---

### Change 4 — P-B: BybitVenueInternalReplayHandler — spot price fallback for BYBIT-CORRIDOR CARRY_IN

**File**: `backend/src/main/java/com/walletradar/costbasis/application/replay/handler/BybitVenueInternalReplayHandler.java`

**Background**: `BYBIT-CORRIDOR:NETWORK:txHash` tagged INTERNAL_TRANSFERs represent ETH/USDC/other withdrawals from Bybit exchange to user's on-chain wallet. The replay tries to find a carry in the continuity bucket but Bybit is a CEX — no on-chain CARRY_OUT is recorded. Result: 0 basis.

**Fix**: When carry bucket lookup returns null for a BYBIT-CORRIDOR transfer, fall back to spot-price ACQUIRE (same as `SPONSORED_GAS_IN` treatment):

```java
// In BybitVenueInternalReplayHandler.handleBybitCorridorInbound():
CarryTransfer carry = continuityCarryService.takeFromBucket(bucket, inboundQty, assetKey);
if (carry == null || carry.quantity().signum() == 0) {
    // CEX withdrawal — no on-chain carry; use spot price as acquisition cost
    carry = continuityCarryService.buildExplicitCarryTransfer(
            inboundQty, 
            replayMarketAuthority.spotCostBasis(assetKey, transaction),
            assetKey
    );
    basisEffect = AssetLedgerPoint.BasisEffect.ACQUIRE;
} else {
    basisEffect = AssetLedgerPoint.BasisEffect.CARRY_IN;
}
```

**Affected transactions** (5 BYBIT-CORRIDOR events with zero basis):
- ARBITRUM: 0x6ac6fc60... (0.0039528 ETH → ~$11)
- MANTLE: 0xc5db3499..., 0xecbec41e..., 0xc6a03abc..., 0x5067b0e1... (small ETH amounts)

**Tests to add** (`BybitVenueInternalReplayHandlerTest.java`):
- `T1_bybitCorridorWithEmptyBucketFallsBackToSpotPrice()` — corridor CARRY_IN with no prior CARRY_OUT → ACQUIRE at spot
- `T2_bybitCorridorWithExistingBucketUsesCarryBasis()` — if bucket exists (Bybit bot transfer), use it

---

## Documentation changes

### `docs/03-accounting.md`

Add policy section:

```markdown
### Bybit corridor CARRY_IN fallback (2026-05-30)

INTERNAL_TRANSFER classified with `BYBIT-CORRIDOR:` correlationId represents an on-chain
withdrawal from Bybit CEX. Since Bybit is an external custodian, no on-chain CARRY_OUT is
recorded at the source. When the continuity carry bucket is empty for a BYBIT-CORRIDOR
transfer, the replay uses spot price as the acquisition cost (basisEffect = ACQUIRE).

### Pendle LP token receipt markers (2026-05-30)

`PENDLE-LPT` and Equilibria-wrapped variants (`eqbPENDLE-LPT`) are LP receipt markers,
not principal assets, in the context of a Pendle LP_ENTRY. `hasOnlyOutboundPrincipalFlows()`
excludes these tokens from the net-by-asset computation, allowing receipt-pool routing for
Pendle LP_ENTRYs on any network.

`PendleLpCorrelationSupport.marketIdFromSymbol()` strips the `EQB` staking prefix before
computing the market slug, ensuring LP_ENTRY and LP_EXIT (via Equilibria) share the same
`pendle-lp:NETWORK:pendle-lpt` correlationId.
```

### `docs/adr/ADR-023-pendle-lpt-receipt-and-bybit-corridor-basis.md`

New ADR covering:
- Decision to treat PENDLE-LPT and eqbPENDLE-LPT as LP receipt markers
- Decision to use spot price for BYBIT-CORRIDOR CARRY_IN when carry bucket is empty
- Consequences and risks

---

## Acceptance criteria

| Check | Pass condition |
|-------|----------------|
| A1 | `PendleLpCorrelationSupport.marketIdFromSymbol("eqbPENDLE-LPT")` returns `"pendle-lpt"` |
| A2 | `hasOnlyOutboundPrincipalFlows()` returns `true` for LP_ENTRY with `cmETH -` and `PENDLE-LPT +` flows |
| A3 | `isLpReceiptEntry()` returns `true` for LP_ENTRY with `correlationId="pendle-lp:mantle:pendle-lpt"` and cmETH-out/PENDLE-LPT-in flows |
| A4 | `lp_receipt_basis_pools` contains entry for `pendle-lp:mantle:pendle-lpt` after rebuild |
| A5 | LP_EXIT on 2025-09-10 MANTLE (txHash: 0xf7f8908b...) changes from `REALLOCATE_IN @ $3,778 (inflated spot fallback)` to `REALLOCATE_IN @ ~$3,158` (original cmETH acquisition cost from 2025-08-01 SWAP) |
| A6 | LP_EXIT on 2025-06-17 MANTLE (txHash: 0x89b5f24e..., empty corrId, PENDLE-LPT outbound) gets `correlationId="pendle-lp:mantle:pendle-lpt"` after renormalization |
| A7 | BYBIT-CORRIDOR CARRY_IN events change from `costBasisDelta=0` to `ACQUIRE` with spot price |
| A8 | ETH AVCO graph no longer shows anomalous near-zero drops from Bybit corridor arrivals |
| A9 | All existing `LpReceiptEntryReplayHandlerTest` tests pass (T1–T5 unchanged) |
| A10 | New T6 and T7 tests pass |
| A11 | All existing `PendleLpCorrelationSupportTest` tests pass |

## Critical architectural corrections (post-review)

1. **S-4A gate**: `LpReceiptEntryReplayHandler.isLpReceiptEntry()` required `correlationId.startsWith("lp-position:")`. Pendle LP_ENTRYs use `"pendle-lp:"` → were routed to GENERIC handler, not LP_RECEIPT_ENTRY. Fix: extend `isLpReceiptEntry()` to also accept `"pendle-lp:"` prefix.

2. **S-4 current state**: LP_EXIT on 2025-09-10 is NOT UNKNOWN — it's `REALLOCATE_IN @ $3,778` (inflated spot fallback from `PositionScopedLpExitReplayHandler` when no receipt pool is found). After fix, the actual acquisition cost (~$3,158) from the 2025-08-01 SWAP will be used.

3. **P-B target class**: Fix is in `TransferReplayHandler.applyTransfer()`, not `BybitVenueInternalReplayHandler` (which only handles `bybit-earn-principal-v1:*`).

4. **No Arbitrum regression**: MongoDB confirms zero Pendle LP positions outside MANTLE. B3 concern is moot.

---

## Ordering and dependencies

```
Change 1 (PendleLpCorrelationSupport marketIdFromSymbol) 
    ↓
Change 2 (isLpReceiptMarker uses Change 1)
    ↓
Change 3 (LP_EXIT classification for outbound Pendle — investigate + fix)
    ↓  [independent]
Change 4 (BybitVenueInternalReplayHandler spot price fallback)
```

Changes 3 and 4 are independent. All four changes feed into the prod rebuild.

---

## Risks

| Risk | Mitigation |
|------|------------|
| EQB prefix strip affects other EQB-prefixed tokens (non-Pendle) | Guard requires `PENDLE` or `-LPT` in remainder after strip |
| `isLpReceiptMarker` Pendle extension causes false-positive receipt detection | Only fires when `marketIdFromSymbol != null`; Pendle detection is narrow (PENDLE + LPT keywords) |
| Bybit corridor spot-price fallback re-prices historical Bybit withdrawals | Acceptable: this is the best approximation we have for CEX withdrawals without user-provided Bybit AVCO |
| Renormalization needed for stale LP_EXIT corrId | Full `prod-reset-rebuild-backend.sh` handles renormalization |

---

## Rebuild command

```bash
./scripts/prod-reset-rebuild-backend.sh --skip-frontend
```

No pricing cache clear needed (cost basis logic only, not pricing policy).
