# Cycle 17 R7 — rebuild checklist

## Code changes (R7)

| Fix | Area | Expected effect |
|---|---|---|
| P0 | `effectiveRemovalAvco` ignores stale `perWalletAvco=0` when basis exists | WETH→aZksWETH wrapper REALLOCATE moves basis; AMANWETH AVCO drops from ~$3300 toward market |
| P0 | `purgeOrphanBasisWhenEmpty` after REALLOCATE_OUT | No zombie basis on qty=0 positions |
| P0 | `ContinuityBucket.take` returns `appliedQuantity` | Partial bucket takes stay proportional |
| P0b | `continuityKey` prefers `wrapper:` when both lp+wrapper composite | AAVE GHO gauge↔LP round-trip recovers ~$2145 basis |
| P1 | `TokenSymbolFallbackSupport` | Euler eToken empty symbol → `eUSDC-2` / `ERC20:xxxxxx` |
| P3 | Bybit continuity `INTERNAL_TRANSFER` pricing | FUND ETH selfTransfer corridor gets spot quotes |

## Re-normalization command

```bash
./scripts/prod-reset-rebuild-backend.sh --clear-pricing-cache --skip-frontend
```

## Post-rebuild acceptance (mongosh)

```bash
. scripts/avco/common.sh
run_mongosh "$(resolve_mongo_uri)" < scripts/tmp-r5-e-acceptance.mongosh.js
```

### R7-specific checks

1. **AMANWETH MANTLE** — AVCO/u ≈ $2200–2800 (not $3300+); cov ≥ 99%
2. **Move basis ETH UI** — backed ~3.1, uncov < 0.05, AVCO ~$2400–2700
3. **AAVE GHO/USDT/USDC AVAX** — cov ≥ 95% (was 0%)
4. **BYBIT FUND ETH** — cov ≥ 95% (was 14.7%)
5. **Euler eToken** — no empty `assetSymbol` clusters with qty>0
6. `NEEDS_REVIEW = 0`, replay completes

## Timing monitor

```bash
./scripts/tmp-r6-pipeline-timing.sh 2>&1 | tee scripts/tmp-r7-pipeline-timing.log
```

Expected total pipeline: ~3–5 min.
