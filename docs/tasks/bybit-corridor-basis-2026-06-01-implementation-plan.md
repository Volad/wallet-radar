# Implementation Plan — B-BYBIT-CORRIDOR-2: Bybit Corridor FUND Zero-Basis Carry

**Slug:** `bybit-corridor-basis-2026-06-01`  
**Date:** 2026-06-01  
**Severity:** P1 ACTIVE  
**Blocker ID:** B-BYBIT-CORRIDOR-2

---

## Scope

- **Assets:** USDC, USDe, ETH (cmETH), MNT, ETH (native), WBTC — all assets withdrawn via Bybit corridor
- **Networks:** MANTLE, BASE, AVALANCHE, ARBITRUM
- **Wallets:** All wallets with Bybit→on-chain withdrawals via `:FUND` sub-account
- **Instances (this plan):** 23 sub-pattern A instances (GROUP A, `BYBIT-CORRIDOR:*`, FUND position has qty=0 at outbound time), ~$6,300 total cbD shortfall
- **Out of scope:** 2 sub-pattern B instances (USDe, ~$2,125) where FUND position has qty>0 but AVCO=0 due to UNIVERSAL_TRANSFER inbound with missing basis — requires a separate fix to the UNIVERSAL_TRANSFER inbound carry path

---

## Root Cause

### Pipeline stage: `cost_basis → replay → carry`

Bybit's API does not expose the internal UTA→FUND transfer that precedes every on-chain
withdrawal. As a result, the `BYBIT:UID:FUND` position has **zero inventory** at withdrawal
time (Bybit's FUND CARRY_OUT records `qtyBefore=0`, `cbD=0`).

### Exact failure in `TransferReplayHandler.applyTransfer()` (line ~144):

```java
// CURRENT (broken):
BigDecimal corridorOutboundSliceAvco = corridorTransfer ? position.perWalletAvco() : null;
```

When the `:FUND` corridor outbound fires:
1. `position` = `BYBIT:UID:FUND` position → `perWalletAvco()` = null (empty position)
2. `corridorOutboundSliceAvco` = null → the corridor override block at lines 173–191 never fires
3. `removeTransferCarry()` drains from an empty FUND position → carry with `cbD=0`
4. Zero-basis carry is parked in the pending transfer queue
5. On-chain CARRY_IN finds this carry → `cbD=0` written to ledger point → AVCO collapses

### Why the existing `:EARN` fix doesn't cover `:FUND`

`earnPrincipalCarrySourcePosition()` (line 145) only activates for
`EARN_PRINCIPAL_CORRELATION_PREFIX`. Corridor transfers use `BYBIT-CORRIDOR:*` and are not
covered by the earn fallback.

---

## Changes

### 1. `TransferReplayHandler.java` — extend carry source resolution + wire it

**Reviewer consensus (architect + financial auditor):** `resolveCorridorOutboundAvco()` is
redundant. After extending `earnPrincipalCarrySourcePosition()` to return the umbrella as
`carrySource` for empty-FUND corridor outbounds, line 144 simply reads
`derivePositionAvco(carrySource)` — no separate method needed.

**Change lines 144–145:**

```java
// BEFORE:
BigDecimal corridorOutboundSliceAvco = corridorTransfer ? position.perWalletAvco() : null;
PositionState carrySource = earnPrincipalCarrySourcePosition(transaction, position, replayState);

// AFTER:
// Resolve carry source: for :FUND corridor outbounds with zero inventory, fall back to
// umbrella BYBIT:UID so the corridor proportional-carry override fires correctly.
PositionState carrySource = resolveCarrySourcePosition(
        transaction, position, replayState, corridorTransfer);
BigDecimal corridorOutboundSliceAvco = corridorTransfer
        ? derivePositionAvco(carrySource)
        : null;
```

**Rename `earnPrincipalCarrySourcePosition()` → `resolveCarrySourcePosition()`** and add
the FUND corridor case. Pass `boolean isCorridorTransfer` as a parameter (keeps method
static and testable without coupling to `classifier`):

```java
private static PositionState resolveCarrySourcePosition(
        NormalizedTransaction transaction,
        PositionState flowPosition,
        ReplayExecutionState replayState,
        boolean isCorridorTransfer
) {
    if (transaction == null || flowPosition == null || replayState == null) {
        return flowPosition;
    }
    String wallet = transaction.getWalletAddress();
    if (wallet == null) {
        return flowPosition;
    }
    String walletUpper = wallet.toUpperCase(Locale.ROOT);

    // Existing :EARN path (unchanged)
    String correlationId = transaction.getCorrelationId();
    if (correlationId != null
            && correlationId.startsWith(BybitEarnPrincipalTransferPairer.EARN_PRINCIPAL_CORRELATION_PREFIX)
            && walletUpper.endsWith(":EARN")) {
        if (hasEarnPrincipalCarryBasis(flowPosition)) {
            return flowPosition;
        }
        String umbrellaWallet = wallet.substring(0, wallet.length() - ":EARN".length());
        return replayState.position(umbrellaKeyFor(flowPosition.assetKey(), umbrellaWallet));
    }

    // New :FUND corridor path
    // Bybit's API does not expose the UTA→FUND internal transfer that precedes every
    // on-chain withdrawal. When FUND position has zero inventory (qty=0), fall back to the
    // umbrella BYBIT:UID position so the corridor proportional-carry override fires correctly.
    if (isCorridorTransfer && walletUpper.endsWith(":FUND")
            && !hasFundCarryInventory(flowPosition)) {
        String umbrellaWallet = wallet.substring(0, wallet.length() - ":FUND".length());
        return replayState.position(umbrellaKeyFor(flowPosition.assetKey(), umbrellaWallet));
    }

    return flowPosition;
}

private static boolean hasFundCarryInventory(PositionState position) {
    // True only when FUND position has non-zero quantity — mirrors hasEarnPrincipalCarryBasis
    // but only checks qty (not basis), since FUND can have qty>0 with cbD=0 (sub-pattern B).
    // Sub-pattern B (FUND qty>0, AVCO=0) is OUT OF SCOPE for this fix.
    if (position == null) return false;
    BigDecimal qty = position.quantity();
    return qty != null && qty.signum() > 0;
}

private static AssetKey umbrellaKeyFor(AssetKey flowKey, String umbrellaWallet) {
    return new AssetKey(umbrellaWallet, flowKey.networkId(), flowKey.assetContract(),
            flowKey.assetSymbol(), flowKey.assetIdentity());
}
```

> **Sub-pattern B explicitly excluded:** When FUND has qty>0 but AVCO=0 (UNIVERSAL_TRANSFER
> inbound with missing basis), `hasFundCarryInventory()` returns `true` → no fallback →
> corridor outbound uses FUND AVCO=0 → override doesn't fire → zero carry preserved.
> Fixing sub-pattern B requires a separate fix to the UNIVERSAL_TRANSFER inbound carry path.

### 2. `TransferReplayHandler.java` — update all call sites of `earnPrincipalCarrySourcePosition`

The old method is used in:
- `applyTransfer()` (outbound branch, line 145) → replaced by `resolveCarrySourcePosition()`
- `normalizeBybitEarnProductCarry()` (line ~913) → keep calling old logic or inline

Extract the EARN-only logic into a private helper so `normalizeBybitEarnProductCarry` is
unchanged.

### 3. Tests — `TransferReplayHandlerTest` (new test class or `AvcoReplayServiceTest`)

Add at minimum 4 tests (architect: 3+1 EARN guard):

| Test | Scenario | Expected |
|------|----------|----------|
| `corridorFundOutboundUsesUmbrellaAvcoWhenFundIsEmpty` | FUND qty=0, umbrella AVCO=$3705 | carry cbD = movedQty × $3705 |
| `corridorFundOutboundUsesFundAvcoWhenFundHasBasis` | FUND qty>0, AVCO>0 | carry cbD from FUND (unchanged behavior) |
| `corridorFundSubPatternBNotFixed` | FUND qty>0, AVCO=0 (sub-pattern B) | carry cbD=0 (sub-pattern B explicitly out of scope) |
| `earnPrincipalPathUnaffectedByFundChange` | EARN corridor, not FUND | EARN path unchanged (regression guard) |

---

## Documentation Updates

- **`docs/adr/ADR-020-bybit-corridor-carry.md`** (new ADR): document the UTA→FUND API gap,
  the FUND-empty fallback rule, and the carry override semantics.
- **`docs/03-accounting.md`**: add section on Bybit corridor FUND carry semantics.

---

## Acceptance Criteria

1. All **23 sub-pattern A** GROUP A corridor instances produce `cbD > 0` on their on-chain CARRY_IN ledger points.
2. ETH family AVCO does not drop to ~$0 between `0xbeba82fc` and `0x717463d2`.
3. USDC AVALANCHE AVCO does not collapse to $0.000001 after vault withdraw `0xe6b02813`.
4. Total `CARRY_IN cbD` for the 23 sub-pattern A instances ≥ **$6,000** (vs. current $0; sub-pattern B USDe ~$2,125 excluded from this plan).
5. `financial-logic-auditor` re-audit: no new P0/P1 blockers introduced.
6. All existing EARN-principal tests pass (regression guard).
6. All existing tests pass.

---

## Risks

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| Draining umbrella when it has already been debited by a UNIVERSAL_TRANSFER CARRY_OUT | Low | Check pre-drain qty > 0 before using umbrella as carrySource |
| Umbrella AVCO reflects different asset pricing context (multi-asset positions) | Low | AVCO is per-asset-key; umbrella key uses same assetContract + assetSymbol |
| Regression in EARN `:EARN` carry path | Very low | `earnPrincipalCarrySourcePosition` guard added; existing EARN tests cover this |
| GROUP C (bybit-cross-uid-v1) not covered | Confirmed | GROUP C is a different pattern (separate fix, ~$279 shortfall, acceptable P2) |

---

## Ordered Tasks

1. Rename `earnPrincipalCarrySourcePosition()` → `resolveCarrySourcePosition(boolean isCorridorTransfer)`, add `:FUND` empty-inventory branch + `hasFundCarryInventory()` + `umbrellaKeyFor()` helpers.
2. Update `applyTransfer()` lines 144–145: call `resolveCarrySourcePosition()`, then `derivePositionAvco(carrySource)` — no `resolveCorridorOutboundAvco()` method.
3. Verify `normalizeBybitEarnProductCarry()` still works with the renamed method (pass `false` for `isCorridorTransfer` or keep its internal EARN logic via a private helper).
4. Write 4 unit tests (see table above).
5. Write `docs/adr/ADR-020-bybit-corridor-fund-carry.md` + update `docs/03-accounting.md`.
6. Prod rebuild (`--skip-frontend`) + `financial-logic-auditor` verification.
