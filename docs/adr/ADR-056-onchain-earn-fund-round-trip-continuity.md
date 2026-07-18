# ADR-056: Bybit On-chain Earn — FUND Self-Round-Trip Continuity

**Status:** ACCEPTED  
**Date:** 2026-07-14  
**Scope:** Bybit normalization — On-chain Earn subscription/redemption for non-ETH-family assets

## Context

Bybit `On-chain Earn` (`bybitType="Earn"`, `bybitDescription="On-chain Earn subscription"` /
`"On-chain Earn redemption"`) emits **two `:FUND` legs on the same sub-account** for non-ETH-family
assets (e.g. TON), with **no `EARN_FLEXIBLE_SAVING` `:EARN` counterpart**. This is structurally
distinct from Flexible Savings (which always has an `:EARN` mirror leg).

The subscribe-out and redeem-in form a **same-asset FUND→(off-chain hold)→FUND round trip**: the
asset is temporarily held by Bybit's On-chain Earn product and returned at principal parity. Cost
basis must carry from subscribe-out to redeem-in with no P&L event.

**Evidence (TON `BYBIT:33625378`):**
- `9e5280fe…` (seq 1122): `On-chain Earn redemption +32.393` on `BYBIT:33625378:FUND`,
  `canonicalType=INTERNAL_TRANSFER`
- `d371339984…`: `On-chain Earn subscription −32.393` also on `BYBIT:33625378:FUND`
- No `EARN_FLEXIBLE_SAVING` stream counterpart (confirmed absent)
- Result before fix: `uncoveredQuantityAfter = 40.55`, 133 flagged ledger points

## Problem with Previous Approach

`BybitNormalizationService.isLiquidStakingRow` correctly detected the subscribe-out row.  
`normalizeLiquidStakingRow` called `findLiquidStakingCounterLeg`, which requires a **different
asset symbol** (`assetSymbol.ne(row.getAssetSymbol())` in `BybitTradePairer`). Since both TON legs
use the same symbol, no counter-leg is ever found, and the subscribe-out was emitted as
`NEEDS_REVIEW` with reason `BYBIT_LIQUID_STAKING_PAIR_NOT_FOUND`. The orphan repair
(`BybitOnChainEarnOrphanRepairService`) then attempted to synthesize an EARN counterpart — which
doesn't exist for TON — producing a phantom EARN credit. The redeem-in replayed as an uncovered
UNKNOWN inflow.

## Decision

### Normalization (Change 0)

In both overloads of `normalizeLiquidStakingRow`, when `findLiquidStakingCounterLeg` returns empty:

- **Non-clustered asset** (`AccountingAssetClassificationSupport.normalizationClusterForSymbol(symbol) == null`,
  e.g. TON, LDO, MNT when no staking cluster exists):
  Emit the subscribe-out as a **CONFIRMED `INTERNAL_TRANSFER`** with a pending marker
  `correlationId = "bybit-earn-self-rt-v1:subscribe-pending"`. The marker is non-blank, so
  `BybitOnChainEarnOrphanRepairService.loadFundOrphans` (which filters for blank/null corrId) skips
  it cleanly.

- **ETH/AVAX/SOL-family clustered asset** (CMETH, METH, BBSOL, SAVAX):
  Keep the existing `NEEDS_REVIEW / BYBIT_LIQUID_STAKING_PAIR_NOT_FOUND` path — handled by
  `BybitOnChainEarnOrphanRepairService`.

### Pairer (`BybitOnChainEarnFundPairer`)

A new `@Service` runs immediately after `BybitEarnPrincipalTransferPairer` in
`BybitNormalizationService.processNextBatch`:

**Subscribe-out query:** `type=INTERNAL_TRANSFER, correlationId="bybit-earn-self-rt-v1:subscribe-pending"`

**Redeem-in candidates:** uncorrelated (`correlationId` null/blank) `INTERNAL_TRANSFER` rows on the
same wallet addresses as the found subscriptions, with positive `quantityDelta`.

**Match key:** `{walletAddress, assetSymbol, |qty| ± 1e-8, redeemTs > subscribeTs, hold ≤ 400 days}`

**FIFO:** the earliest eligible redeem-in is selected for each subscribe-out.

**On match:** both rows receive a shared deterministic
`bybit-earn-self-rt-v1:<sha256(walletAddress|asset|qty|subscribeEpoch)>` correlationId and
`continuityCandidate = true`. The replay engine routes the subscribe-out as `CARRY_OUT` and the
redeem-in as `CARRY_IN` on the UID-umbrella position (`BYBIT:uid`), preserving cost basis with no
P&L event.

## Consequences

- TON (and any future non-ETH-family On-chain Earn asset) uncovered-quantity flags are eliminated
  when subscribe/redeem pairs are present.
- ETH-family assets (CMETH, METH, BBSOL) continue to route through
  `BybitOnChainEarnOrphanRepairService` (unchanged).
- `BybitEarnSubPoolConservationGuard` is unaffected: the new `bybit-earn-self-rt-v1:` prefix is
  distinct from all existing prefixes.
- `AccountingAssetIdentitySupport.isEarnPrincipalPaired` returns `false` for the new prefix,
  so the position key correctly collapses `:FUND` to the UID umbrella for both legs.

## Scope Limitations

- **Partial redemption:** out of scope. Bybit On-chain Earn does not support partial redemption in
  observed data; subscribe qty always equals redeem qty. Equal-quantity matching is enforced.
- **Hold window:** 400 days maximum. Redemptions more than 400 days after subscription are
  unmatched (accepted risk, logged at WARN). This exceeds the published maximum lock period for any
  Bybit On-chain Earn product.
- **ETH-family assets with same-symbol FUND round trips:** not handled here (they go through the
  orphan repair service). The guard is the `normalizationClusterForSymbol` check, not the asset
  symbol per se.
