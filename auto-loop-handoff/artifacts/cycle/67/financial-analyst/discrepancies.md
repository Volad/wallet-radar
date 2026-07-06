# Cycle 67 discrepancies

## Material mismatches between database truth and auditor truth

| Surface | Database truth now | Auditor-derived financially correct state | First failed stage | Terminal status |
| --- | --- | --- | --- | --- |
| ETH exact | Leaves 0.005604525124182205 uncovered across native ETH buckets. | Supported bridge/native/receipt continuity should carry full principal basis; only explicit excess yield should remain acquisition. | normalization + move_basis | AUTHORITATIVE_RECONSTRUCTION_COMPLETE |
| ETH family | Coverage ratio passes at 0.995413775064410755 but family is not final-clean because `AMANWETH` and native ETH buckets are still dirty. | Family should become final-clean after principal-vs-yield split and carry restoration. | normalization + move_basis | AUTHORITATIVE_RECONSTRUCTION_COMPLETE |
| BTC family | Tiny uncovered `AARBWBTC` remainder still prevents final-clean family state. | Receipt-token continuity should absorb the residual dust instead of leaving a family remainder. | move_basis | AUTHORITATIVE_RECONSTRUCTION_COMPLETE |
| MNT family | Current family shows 1675.520059541154068938 uncovered in Bybit reward inventory. | Cannot be authoritatively reconstructed from raw on this DB snapshot because `external_ledger_raw` is empty. | source availability | GENUINE_EVIDENCE_MISSING_PROVEN |
| AVAX exact | Native AVAX leaves 0.093456895658393546 uncovered. | `aAvaWAVAX` and `sAVAX` continuity should carry basis inside the AVAX family. | move_basis | AUTHORITATIVE_RECONSTRUCTION_COMPLETE |
| AVAX family | Family leaves 1.101439081571954581 uncovered, mostly in `sAVAX`. | Family continuity should reallocate basis between native AVAX and staking wrapper without disposal. | move_basis | AUTHORITATIVE_RECONSTRUCTION_COMPLETE |
| USDC exact | Leaves 330.858363999999937732 uncovered, mostly on two Arbitrum buckets. | Bridge and vault receipt-token continuity should leave only explicit yield uncovered. | normalization + move_basis | AUTHORITATIVE_RECONSTRUCTION_COMPLETE |
| USDC family | Fails on the same two Arbitrum USDC buckets as exact. | Family should inherit the same corrected continuity once exact canonical rows are fixed. | normalization + move_basis | AUTHORITATIVE_RECONSTRUCTION_COMPLETE |
| USDT exact | Leaves 1.234868226418219139 uncovered on one PancakeSwap Infinity LP exit. | LP-position basis should be reallocated onto the returned USDT and peer asset. | normalization | AUTHORITATIVE_RECONSTRUCTION_COMPLETE |
| USDT family | Same LP-exit defect leaves the family uncovered. | Family should also become clean once LP-exit basis allocation is emitted deterministically. | normalization | AUTHORITATIVE_RECONSTRUCTION_COMPLETE |

## Protocol and counterparty mismatches

- Database truth still has 1101 rows without `protocolName` across deterministic families; auditor truth is that many of those rows are already protocol-provable from current raw evidence.
- Database truth still has 1215 rows without `counterpartyAddress`; auditor truth is that the majority can be filled from row-local contract evidence or deterministic lifecycle pairing.

## Terminal status note

Every still-material surface above is in one explicit terminal audit state for this run:

- `AUTHORITATIVE_RECONSTRUCTION_COMPLETE` for supported on-chain normalization and accounting blockers
- `GENUINE_EVIDENCE_MISSING_PROVEN` for broader-goal Bybit family reconstruction that lacks the required raw source collection in this DB snapshot
