# Lending Audit Follow-up — 2026-06-07

Fixes applied after initial lending-audit implementation:

| Issue | Root cause | Fix |
|-------|-----------|-----|
| Base HF `LIVE_PROTOCOL=0` | Wrong pool address in `application.yml` (`...3922e5` vs official `...93f98d1c5`) | Corrected Base pool address; reject empty RPC payloads |
| Euler cycle-2 APR ~8293% | `withdrawYield` branch ran before `internalReceiptMovement` | Reordered `LendingFactualApyCalculator` to prefer internal-receipt path |
| Euler `evk-account` grouping | `matchedCounterparty` often empty | Derive vault from flow `assetContract`; skip sentinel `0xeee…` |
| Silo PnL UNAVAILABLE | No BUY flows; share-yield inference used first deposit only | Infer yield from `principalOutCash − principalIn` when ratio ≤ 10% |
| Aave phantom PnL (cycle-5) | Same ratio guard excludes 180% phantom | Covered by 10% cap |

## Verification (session `df5e69cc-a0c0-4910-8b7d-74488fa266e2`)

- `aave:avalanche:account-pool:cycle-5` PnL ≈ $0.89 ESTIMATED
- `silo:arbitrum:sousdc:cycle-1` PnL ≈ $1.43 ESTIMATED
- Base/Mantle HF `source=LIVE_PROTOCOL` after pool fix + refresh job
- Euler per-vault keys: `evk-vault-{addr[2..10]}`
