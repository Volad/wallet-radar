# Cycle 67 scorecard

## Header

- Cycle: `67`
- Status: `fail`
- Owner: `financial-analyst`
- Dataset basis: fresh live Mongo capture from `walletradar` at `2026-04-21T19:37:12.784Z`, accounting universe `c584c760-b228-45fc-ae0f-84f7cd7bfd8f`, using `raw_transactions`, `normalized_transactions`, `bybit_extracted_events`, `asset_ledger_points`, and `on_chain_balances`; note that `external_ledger_raw = 0`
- Comparison basis: archived earlier-cycle artifacts are historical context only; this scorecard is the authoritative post-rerun live metric contract for cycle 67

## Metric basis

- `final asset` means the exact current holding for one canonical asset identity as reconstructed from the live replay truth in `asset_ledger_points` and reconciled to the current bucket in `on_chain_balances`
- `family` means the current holding grouped by `accountingFamilyIdentity`, kept separate from exact-asset rows
- `coverage ratio` means `basis-backed current quantity / current quantity` on the same exact or family basis
- `final-clean` means uncovered quantity is zero within rounding tolerance and no dirty bucket remains for that exact or family surface
- historical context only means archived `cycle.zip` snapshots and earlier cycle notes that were captured before this fresh live rerun

## Mandatory reference set scorecard

| Asset | Final asset qty | Final asset basis-backed qty | Final asset uncovered qty | Final asset coverage ratio | Final asset final-clean | Family current qty | Family covered qty | Family uncovered qty | Family coverage ratio | Family final-clean | Dirty buckets | Current blocking reason | Prior-cycle comparability | Prior-cycle delta note |
| --- | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | --- | ---: | --- | --- | --- |
| ETH | 0.023215368798396828 | 0.017610843674214623 | 0.005604525124182205 | 0.758585565758092328 | no | 3.092411984900850008 | 3.078229487944582843 | 0.014182496956267075 | 0.995413775064410755 | no | 19 | Exact native ETH carry still leaks on Arbitrum and Base; family ratio is above 0.99 but `AMANWETH` and native ETH buckets are still not final-clean. | NOT COMPARABLE | Fresh live replay truth captured at `2026-04-21T17:12:17.100Z` supersedes archived earlier-cycle snapshots. |
| BTC | 0.00030593 | 0.00030593 | 0 | 1 | yes | 0.00457402 | 0.00457393 | 8.999999999946551e-08 | 0.99998032365402878 | no | 1 | Exact BTC is clean; family remains not final-clean because `AARBWBTC` still carries a small uncovered receipt-token remainder. | NOT COMPARABLE | Fresh live replay truth captured at `2026-04-21T17:12:17.100Z` supersedes archived earlier-cycle snapshots. |
| MNT | 5.859322669926309679 | 5.859322669926309679 | 0 | 1 | yes | 1689.801872564893983508 | 14.281813023739854174 | 1675.520059541154068938 | 0.008451767781545872 | no | 3 | Exact MNT is clean; family is dominated by Bybit `MNT` reward inventory while `external_ledger_raw` is empty on this live DB snapshot. | NOT COMPARABLE | Fresh live replay truth captured at `2026-04-21T17:12:17.100Z` supersedes archived earlier-cycle snapshots. |
| AVAX | 0.251767898467120999 | 0.158311002808727452 | 0.093456895658393546 | 0.628797411316525223 | no | 2.670169864893060296 | 1.568730783321105937 | 1.101439081571954581 | 0.58750224244027005 | no | 2 | Native AVAX basis is not restored across `aAvaWAVAX` and `sAVAX` continuity, leaving both exact and family AVAX materially uncovered. | NOT COMPARABLE | Fresh live replay truth captured at `2026-04-21T17:12:17.100Z` supersedes archived earlier-cycle snapshots. |
| USDC | 1352.005417999999963286 | 1021.147054000000025553 | 330.858363999999937732 | 0.755283255824940825 | no | 1352.005417999999963286 | 1021.147054000000025553 | 330.858364000000051419 | 0.755283255824940825 | no | 4 | Arbitrum `eUSDC-6 -> USDC` and the earlier ParaSwap/bridge chronology strand `361.759952` USDC with evidence present but continuity not carried. | NOT COMPARABLE | Fresh live replay truth captured at `2026-04-21T17:12:17.100Z` supersedes archived earlier-cycle snapshots. |
| USDT | 1.235138226418219132 | 0.00027 | 1.234868226418219139 | 0.000218599015255947 | no | 1.235138226418219132 | 0.00027 | 1.234868226418219139 | 0.000218599015255947 | no | 1 | PancakeSwap Infinity `LP_EXIT` still lands as `basisEffect=UNKNOWN`, so returned USDT remains almost fully uncovered. | NOT COMPARABLE | Fresh live replay truth captured at `2026-04-21T17:12:17.100Z` supersedes archived earlier-cycle snapshots. |

## Material non-reference blockers

- `broader-goal-blocker` Protocol label coverage remains incomplete on current canonical rows: `WRAP=343`, `UNWRAP=336`, `SWAP=130`, `BRIDGE_IN=98`, `BRIDGE_OUT=98`, `VAULT_DEPOSIT=19`
- `broader-goal-blocker` Counterparty construction remains incomplete on current canonical rows: `SWAP=268`, `EXTERNAL_TRANSFER_IN=187`, `BRIDGE_OUT=138`, `EXTERNAL_TRANSFER_OUT=127`, `BRIDGE_IN=116`, `LP_ENTRY=90`
- `excluded-non-primary` Bybit unsupported review tails remain explicit scope-policy exclusions: `EXTERNAL_TRANSFER_OUT/BYBIT_TRANSFER_SHADOW_ROW=59`, `EXTERNAL_TRANSFER_IN/BYBIT_TRANSFER_SHADOW_ROW=32`, `EXTERNAL_TRANSFER_OUT/EXTERNAL_CUSTODY_UNTRACKED_VENUE=17`, `EXTERNAL_TRANSFER_IN/EXTERNAL_CUSTODY_UNTRACKED_VENUE=15`, `REPAY/BYBIT_LOAN_SEMANTICS_UNSUPPORTED=2`, `BORROW/BYBIT_LOAN_SEMANTICS_UNSUPPORTED=1`

## Prior-cycle delta

- Fresh live rerun data was captured after the archived earlier-cycle artifacts. Numeric deltas are therefore `NOT COMPARABLE` unless explicitly recomputed on the same replay truth.
- The cycle 67 scorecard should be treated as the only authoritative acceptance surface for downstream roles.

## Role conformance notes

- Downstream roles must preserve exact-asset rows and family rows as separate acceptance surfaces.
- A coverage pass is not the same thing as a final-clean pass. ETH family and BTC family are examples where ratio is above `0.99` but final-clean is still `no`.
- Supported on-chain failures and broader-goal Bybit raw-evidence gaps must remain separated in requirements and implementation planning.
