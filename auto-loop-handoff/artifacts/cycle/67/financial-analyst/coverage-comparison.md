# Cycle 67 coverage comparison

This document compares the current database state to the auditor-derived live basis captured at `2026-04-21T17:12:17.100Z`. Because the archived cycle bundle was captured before the current replay truth, earlier artifacts are historical context only unless recomputed on the same live basis.

## Mandatory reconciliation table

| Surface | Database coverage now | Auditor-derived coverage | Delta to target 0.99 | Exact uncovered remainder | Family uncovered remainder | Terminal state | Remainder explanation |
| --- | ---: | ---: | ---: | ---: | ---: | --- | --- |
| ETH exact | 0.758585565758092328 | 0.758585565758092328 | 0.231414434241907663 | 0.005604525124182205 | n/a | AUTHORITATIVE_RECONSTRUCTION_COMPLETE | Native ETH carry still leaks on Arbitrum and Base after supported bridge/swap chronology. |
| ETH family | 0.995424985292039377 | 0.995424985292039377 | 0 | n/a | 0.014147670984662762 | AUTHORITATIVE_RECONSTRUCTION_COMPLETE | Ratio passes, but `AMANWETH` plus native ETH tails are still not final-clean. |
| BTC family | 0.99998032365402878 | 0.99998032365402878 | 0 | n/a | 8.999999999946551e-08 | AUTHORITATIVE_RECONSTRUCTION_COMPLETE | Exact BTC is clean; family still holds a small `AARBWBTC` receipt-token remainder. |
| MNT family | 0.008451767781545872 | 0.008451767781545872 | 0.981548232218454109 | n/a | 1675.520059541154068938 | GENUINE_EVIDENCE_MISSING_PROVEN | Family remainder is dominated by Bybit reward inventory while raw CEX source collection is absent. |
| AVAX exact | 0.628797411316525223 | 0.628797411316525223 | 0.361202588683474768 | 0.093456895658393546 | n/a | AUTHORITATIVE_RECONSTRUCTION_COMPLETE | Native AVAX basis is not restored after `aAvaWAVAX` and `sAVAX` continuity. |
| AVAX family | 0.587152900232392749 | 0.587152900232392749 | 0.402847099767607242 | n/a | 1.102371884607963981 | AUTHORITATIVE_RECONSTRUCTION_COMPLETE | `sAVAX` plus native AVAX remain materially uncovered inside one supported family. |
| USDC exact | 0.732427143276433323 | 0.732427143276433323 | 0.257572856723566668 | 361.75995199999988472 | n/a | AUTHORITATIVE_RECONSTRUCTION_COMPLETE | `eUSDC-6 -> USDC` and ParaSwap/bridge chronology leave supported USDC carry stranded. |
| USDC family | 0.732427143276433323 | 0.732427143276433323 | 0.257572856723566668 | n/a | 361.759951999999998407 | AUTHORITATIVE_RECONSTRUCTION_COMPLETE | Exact and family fail on the same Arbitrum USDC buckets. |
| USDT exact | 0.000218599015255947 | 0.000218599015255947 | 0.989781400984744097 | 1.234868226418219139 | n/a | AUTHORITATIVE_RECONSTRUCTION_COMPLETE | Supported LP exit still emits `basisEffect=UNKNOWN`, so returned USDT is uncovered. |
| USDT family | 0.000218599015255947 | 0.000218599015255947 | 0.989781400984744097 | n/a | 1.234868226418219139 | AUTHORITATIVE_RECONSTRUCTION_COMPLETE | Same unsupported `LP_EXIT` basis allocation defect affects the family view too. |

## Exact/family surfaces that already pass exact cleanliness

- `BTC exact = 1`
- `MNT exact = 1`

Those exact rows are clean on the current live basis and should not be regressed while fixing the remaining blockers.

## Why archived earlier-cycle metrics are not comparable

- The current scorecard is based on a later live replay truth captured at `2026-04-21T17:12:17.100Z`
- The archived cycle bundle predates the current snapshot and mixes stale row counts with earlier coverage numbers
- Downstream roles should therefore use the cycle-local scorecard written in this run as the authoritative contract
