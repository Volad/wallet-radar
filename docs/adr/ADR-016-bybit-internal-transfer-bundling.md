# ADR-016 — Bybit multi-stream internal-transfer bundling and round-trip pairing

**Status:** Accepted  
**Date:** 2026-05-20  
**Context:** Cycle 12 — Bybit internal-transfer coverage cluster (post Cycle 11)

## Problem

After Cycle 11, Net Inflow and bridge repricing were corrected, but **14 Bybit asset families** remained below 99% cost-basis coverage. Mongo inspection showed three distinct failure modes for `INTERNAL_TRANSFER` legs with `continuityCandidate=true`:

1. **N-way near-zero clusters (60s)** — Bybit publishes one logical move as three streams (UTA + FUND + EARN) with slightly different quantities (e.g. `UTA -27.85`, `FUND -0.05`, `EARN +27.90`). The existing 1:1 pairer (`BybitInternalTransferPairer.repairSingletonPairs`) requires exact opposite `abs(qty)` and never matches these legs.

2. **Same-wallet Earn round-trips** — Flexible-savings subscribe/unsubscribe on FUND: `FUND -68.665` out, then `FUND +68.665` in days later. Again no 1:1 exact-qty partner across sub-accounts.

3. **Truly-unpaired tail** — After clustering, a small set of legs (notably USDT, MNT) still have singleton `correlationId`s with no peer within pairing windows.

AVCO replay routes standard `INTERNAL_TRANSFER` through `TransferReplayHandler` pending queues keyed per wallet. Multi-leg bundles that never share a `correlationId` leave inbound legs with `basisBackedQuantityAfter=0` while physical quantity accumulates on EARN.

## Decision

### 1. Extend `BybitInternalTransferPairer` with two post-passes

Run after each normalization batch (via `repairAll()`):

| Pass | Method | Rule |
|------|--------|------|
| Existing | `repairSingletonPairs()` | 1:1 opposite exact qty, ≤2h |
| **Bundle** | `pairBundles()` | ≥3 singletons, same `(uid, family)`, 60s window, `\|sum(qty)\|/max(abs(qty)) < 1%`, both signs |
| **Round-trip** | `pairSameWalletRoundTrips()` | Same `(uid, family, wallet)`, opposite qty within 0.1%, ≤14 days |

Rewritten `correlationId` prefixes:

- `bybit-it-bundle-v1:<sorted-doc-ids>`
- `bybit-it-roundtrip-v1:<low-id>|<high-id>`

Configuration: `walletradar.ingestion.bybit.internal-transfer.*` in `application.yml`.

### 2. Replay: corr-family pending queue with multi-carry drain for bundles

Bundle and round-trip legs keep `continuityCandidate=true` and share a rewritten `correlationId`, so `ReplayPendingTransferKeyFactory.transferKey()` places them on the existing `corr-family:<correlationId>:<asset>` pending queue (cross-wallet).

`TransferReplayHandler.applyBybitMultiLegBundleTransfer()` handles `bybit-it-bundle-v1:*` inbounds by draining **all** outbound carries from that queue (qty-agnostic bridge match), which preserves basis when EARN inbound timestamps precede UTA/FUND outflows. Round-trips use the standard single-carry matcher (exact opposite qty on the same sub-account).

Residual qty on inbound (IN > sum of OUT) remains uncovered per existing `materializePendingInbound`.

### 3. Orphan demote fallback (linking pass)

`BybitInternalTransferOrphanFallbackService` runs in `LinkingBatchProcessor` before `UnmatchedExternalTransferInPricingFallbackService`:

- Singleton inbound → `EXTERNAL_TRANSFER_IN` + `BUY` + `PENDING_PRICE`
- Singleton outbound → `EXTERNAL_TRANSFER_OUT` + `SELL` + `PENDING_PRICE`

Demoted rows are excluded from Net Inflow (FUND-scope ETI only counts true external fiat corridor).

## Consequences

- **Positive:** LDO/LINK/ONDO/ARB and most USDT/MNT internal corridors recover basis without manual overrides; idempotent re-run on already-bundled docs is a no-op (correlation id no longer singleton).
- **Negative:** Orphan demote uses market pricing (CoinGecko/Bybit chain) — acceptable for true orphans only; bundle/roundtrip pairing should absorb ~90%+ of prior singletons.
- **Out of scope:** CUDIS/PAWS launchpad tokens, on-chain ETH Mantle, AAVE LP composite on Avalanche.

## Verification

Post `prod-reset-rebuild-backend.sh`:

- Mongo: `correlationId` matching `^bybit-it-bundle-v1:` and `^bybit-it-roundtrip-v1:` counts > 0 for affected assets.
- `asset_ledger_points` family rollup: target ≥99% `basisBacked/qty` for LDO, LINK, ONDO, ARB, TON, USDT, MNT, USDC, DOGE, ZORA on Bybit.
- `lifetimeExternalInflowUsd` unchanged (~$14,773 ±2% from Cycle 11).
