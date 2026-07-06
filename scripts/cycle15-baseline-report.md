<!-- DEBUG / Cycle 15 — remove after coverage acceptance -->

# Cycle 15 — ETH family basis recovery (Stage A baseline)

Read-only audit baseline confirming cluster sizes before Z1/Z2/Z3 fixes.  
Run: `mongosh <uri> --file scripts/cycle15-eth-supply-audit.mongosh.js`

## ZKSYNC / UNICHAIN (not a gap)

| Network   | Backfill segments | Normalized tx | ETH+WETH net |
|-----------|-------------------|---------------|--------------|
| ZKSYNC    | COMPLETE          | ~63           | ~0           |
| UNICHAIN  | COMPLETE          | ~400          | ~0           |

Transit pattern: `BRIDGE_IN → LENDING_LOOP → BRIDGE_OUT`. No additional backfill required.

## ETH-family lifetime net (all networks)

| Wallet   | In (ETH) | Out (ETH) | Net      |
|----------|----------|-----------|----------|
| 0x1a87   | 22.17    | 23.70     | -1.525   |
| 0xf03b   | 2.50     | 3.21      | -0.709   |
| 0x68bc   | 10.09    | 10.93     | -0.843   |
| **Σ**    |          |           | **-3.08**|

~3.06 ETH currently on Mantle — basis not lost on-chain, hidden in receipt/LP/aToken positions.

## Cluster Z1 — Bybit subaccount `continuityCandidate=false`

- Scope: `BYBIT:33625378` FUND/UTA/EARN
- ~114 internal transfers with `continuityCandidate=false`
- Root cause: `bybit-econ-v1` corrId minute-bucket drift between OUT/IN legs from different streams
- Fix: `BybitInternalTransferPairer.pairByEconomicFingerprint()` + `bybit-rekeyed-v1:` corrId
- Acceptance: `cont=false` with `bybit-econ-v1` corrId **< 5** after linking re-run

## Cluster Z2 — BYBIT-CORRIDOR carry shortfall

- Event: 2026-02-19 FUND → Mantle `0x1a87`, 3.06 ETH `EXTERNAL_TRANSFER_OUT`, `BYBIT-CORRIDOR:MANTLE:…`
- Symptom: `CARRY_OUT` only -1.53 basis-backed (FUND pool depleted by Z1)
- Fix: Z1 re-pair + full replay; defensive `accounting_shortfall_audit` on replay
- Acceptance: `CARRY_OUT.quantityDelta` = **-3.06**; Mantle WETH `basisBackedQuantityAfter` > 0.95× AVCO value

## Cluster Z3 — BASE LP receipt (0x1a87)

- `LP_ENTRY` ~0.987 ETH+WETH vs `LP_EXIT` ~0.022 → **~0.965 ETH** without receipt basis
- Fix: `LpReceiptBasisPool` + `LpReceiptEntryReplayHandler` + pool-aware `PositionScopedLpExitReplayHandler`
- Acceptance: BASE 0x1a87 ETH+WETH coverage **≥ 99%**; global ETH family **≥ 99%**

## Post-fix verification (Stage 4)

```bash
./scripts/prod-reset-rebuild-backend.sh   # or linking + replay only in dev
mongosh <uri> --file scripts/cycle15-verify-acceptance.mongosh.js
```

Dashboard: ETH/WETH/aArbWETH/aManWETH coverage ≥ 99%, Net Inflow **$14,150 ± 2%**.
