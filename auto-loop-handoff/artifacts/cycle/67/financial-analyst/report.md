# Financial analyst report, cycle 67

## Scope and dataset basis

Fresh full-role audit was rebuilt from the current live Mongo state, not from the archived cycle bundle. The authoritative dataset basis for this package is:

- Mongo database: `walletradar`
- Live capture time: `2026-04-21T17:12:17.100Z`
- Accounting universe / session id: `c584c760-b228-45fc-ae0f-84f7cd7bfd8f`
- Pipeline state during capture: `ACCOUNTING_REPLAY / COMPLETE` at `2026-04-21T15:42:11.946Z`
- Wallets in scope: `Metamask:0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f`, `TWT:0xf03b52e8686b962e051a6075a06b96cb8a663021`, `Uni:0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f`, `Bybit onchain:0xa0dd42c626b002778f93e1ab42cbed5f31c117b2`
- Integrations in scope: `BYBIT:BYBIT:33625378`

Critical evidence boundary:

- `raw_transactions` is present and fully normalized for the on-chain scope
- `external_ledger_raw = 0`, so authoritative raw-first Bybit/CEX reconstruction is not fully available in this live DB snapshot
- `bybit_extracted_events = 4557` is available as derived venue evidence, but it is not a substitute for the missing raw CEX source collection

## Fresh source counts

- `raw_transactions = 3173`
- `raw_transactions(normalizationStatus=PENDING) = 0`
- `normalized_transactions = 5805`
- `normalized_transactions(status=CONFIRMED) = 5679`
- `normalized_transactions(status=NEEDS_REVIEW) = 126`
- `unknown confirmed normalized rows = 223`
- `bybit_extracted_events(status=RAW) = 1286`
- `bybit_extracted_events(status=CONFIRMED) = 3271`
- `asset_ledger_points = 9249`
- `on_chain_balances = 213`
- `unmatchedBridgeOut = 27`
- `unmatchedBridgeIn = 9`

Current `NEEDS_REVIEW` inventory is concentrated in explicit Bybit unsupported or shadow lanes:

- `BYBIT / EXTERNAL_TRANSFER_OUT / BYBIT_TRANSFER_SHADOW_ROW = 59`
- `BYBIT / EXTERNAL_TRANSFER_IN / BYBIT_TRANSFER_SHADOW_ROW = 32`
- `BYBIT / EXTERNAL_TRANSFER_OUT / EXTERNAL_CUSTODY_UNTRACKED_VENUE = 17`
- `BYBIT / EXTERNAL_TRANSFER_IN / EXTERNAL_CUSTODY_UNTRACKED_VENUE = 15`
- `BYBIT / REPAY / BYBIT_LOAN_SEMANTICS_UNSUPPORTED = 2`
- `BYBIT / BORROW / BYBIT_LOAN_SEMANTICS_UNSUPPORTED = 1`

## Mandatory reference-set verdict

| Asset | Exact coverage | Exact uncovered | Family coverage | Family uncovered | Exact final-clean | Family final-clean |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| ETH | 0.758585565758092328 | 0.005604525124182205 | 0.995424985292039377 | 0.014147670984662762 | no | no |
| BTC | 1 | 0 | 0.99998032365402878 | 8.999999999946551e-08 | yes | no |
| MNT | 1 | 0 | 0.008451767781545872 | 1675.520059541154068938 | yes | no |
| AVAX | 0.628797411316525223 | 0.093456895658393546 | 0.587152900232392749 | 1.102371884607963981 | no | no |
| USDC | 0.732427143276433323 | 361.75995199999988472 | 0.732427143276433323 | 361.759951999999998407 | no | no |
| USDT | 0.000218599015255947 | 1.234868226418219139 | 0.000218599015255947 | 1.234868226418219139 | no | no |

High-signal conclusions from the current live basis:

- Exact reference assets clean now: `BTC`, `MNT`
- Exact reference assets still failing now: `ETH`, `AVAX`, `USDC`, `USDT`
- Family surfaces still failing final-clean now: all six mandatory families, with the largest remainder on `FAMILY:MNT`
- The highest supported on-chain uncovered exact quantity is `USDC = 361.75995199999988472`
- The highest broader-goal family remainder is `FAMILY:MNT = 1675.520059541154068938`, but that lane is blocked by missing raw CEX source evidence in this live DB snapshot

## Material findings

### FA67-B1 ETH exact carry leak and ETH-family receipt-token continuity are not final-clean

- Severity: `high`
- Surfaces: `ETH exact`, `ETH family`
- Current database truth: ETH exact coverage is 0.758585565758092328 with uncovered 0.005604525124182205 across 3 materially dirty exact buckets. ETH family coverage is 0.995424985292039377 but still has uncovered 0.014147670984662762 and 19 dirty buckets.
- Auditor-derived financially correct state: Same-family bridge, wrapper, and lending receipt-token principal must carry basis end to end. Only explicit positive excess over the carried principal should become acquisition.
- First failed stage: `normalization + move_basis`
- Evidence diagnosis: EVIDENCE_PRESENT_UNLINKED for native bridge/native dust buckets; EVIDENCE_PRESENT_UNUSABLE for receipt-token rows that still hide yield inside one coarse continuity leg.
- Type adequacy: Current canonical split is semantically lossy for receipt-token and rebasing-family exits because principal and yield are not separated strongly enough before replay.
- Remediation class: normalization, move_basis, replay
- Pipeline correction point: Normalize principal-vs-yield split on supported receipt-token rows before move-basis replay, then rerun continuity replay.
- Terminal audit state: `AUTHORITATIVE_RECONSTRUCTION_COMPLETE`
- Evidence anchors: `0x101c297d1a67fc0a30fc96c1aa5cc8786d630bbb00d274c9dc7bbced4c6dccd7`, `0xda6537107ce79f1adca4abeeef7abc49da76cf4b23de3f17ee8c43cbfbb8c9b2`, `0x3b8592a7465ce33bd64b266e11d1f665954cc3735be6e1590dffd226ac8664bd`

### FA67-B2 AVAX native and family continuity still lose basis across receipt-token and staking-wrapper transitions

- Severity: `high`
- Surfaces: `AVAX exact`, `AVAX family`
- Current database truth: AVAX exact coverage is 0.628797411316525223 with uncovered 0.093456895658393546. AVAX family coverage is 0.587152900232392749 with uncovered 1.102371884607963981, dominated by `sAVAX` plus native AVAX.
- Auditor-derived financially correct state: Aave-style receipt-token exits and native staking-wrapper transitions are continuity. Principal basis should move from `aAvaWAVAX` and native AVAX into the returned AVAX or `sAVAX` position; only net excess should be acquisition.
- First failed stage: `move_basis`
- Evidence diagnosis: EVIDENCE_PRESENT_UNLINKED
- Type adequacy: The type model is adequate, but the move-basis engine is not carrying native-family basis across supported AVAX family reallocations.
- Remediation class: move_basis, cost_basis, replay
- Pipeline correction point: Carry basis across `aAvaWAVAX -> AVAX` and `AVAX -> sAVAX` continuity before AVCO and replay consume the rows.
- Terminal audit state: `AUTHORITATIVE_RECONSTRUCTION_COMPLETE`
- Evidence anchors: `0xfbbfd2293154db9a2cd678596eae84f8f8ed9b140d9a9115f055f6c47c9ac931`, `0x682992de7690b96b0710f5817481994d0288f8ac4a4677e116372b38640a4cb4`, `0x5e30c5086e680e2c6313074d796af64051338d9e1b0a27882c1ce1f436f18543`

### FA67-B3 USDC exact and family continuity remain broken on Arbitrum vault and bridge corridors

- Severity: `high`
- Surfaces: `USDC exact`, `USDC family`
- Current database truth: USDC exact coverage is 0.732427143276433323 with uncovered 361.75995199999988472. Two Arbitrum buckets explain nearly all of it: 351.488204999999993561 on `0x0765...` and 10.271747000000004846 on `0x101c...`.
- Auditor-derived financially correct state: Bridge carry, vault receipt-token carry, and supported swap chronology are all present. Basis should flow from bridge source rows into Arbitrum USDC and from `eUSDC-6` back into underlying USDC, leaving only explicit vault yield or fee effects as acquisition/disposal.
- First failed stage: `normalization + move_basis`
- Evidence diagnosis: EVIDENCE_PRESENT_UNLINKED on supported bridge/vault/swap chronology and EVIDENCE_PRESENT_UNUSABLE where vault receipt-token exits remain too coarse.
- Type adequacy: Current canonical representation is too coarse for `eUSDC-6 -> USDC` principal-vs-yield decomposition and for preserving exact/family continuity after bridge corridors.
- Remediation class: normalization, linking, move_basis, replay
- Pipeline correction point: Split vault withdraw principal from yield, persist protocol/counterparty metadata for the vault, then replay carry through bridge and swap chronology.
- Terminal audit state: `AUTHORITATIVE_RECONSTRUCTION_COMPLETE`
- Evidence anchors: `0x0765f4b17d9961c3cd7e63b4dc1e69c948954d51816807a4ba51a8ac4709f977`, `0x9f6983d00441ed13bf45e1b7ac34e94540fb61f58e4a9a2189826b1e761a2f7f`, `0x7d8c79a327637fda080bcfa9204181359de791ccff95dd4b2f1b020b8af0b678`, `0x101c297d1a67fc0a30fc96c1aa5cc8786d630bbb00d274c9dc7bbced4c6dccd7`

### FA67-B4 Supported LP exits still surface as `basisEffect=UNKNOWN`, breaking exact USDT and broader LP accounting

- Severity: `high`
- Surfaces: `USDT exact`, `USDT family`
- Current database truth: USDT exact/family coverage is 0.000218599015255947 with uncovered 1.234868226418219139, entirely anchored at PancakeSwap Infinity LP exit `0x091e...`.
- Auditor-derived financially correct state: A supported LP exit should close the carried LP-position basis, then reallocate that basis onto the returned assets proportionally. Returned assets must not remain fully uncovered only because the exit row is still tagged `UNKNOWN`.
- First failed stage: `normalization`
- Evidence diagnosis: EVIDENCE_PRESENT_UNUSABLE
- Type adequacy: The current LP-exit canonical output is missing a deterministic principal-basis allocation rule, so move basis and AVCO have no authoritative input.
- Remediation class: normalization, move_basis, cost_basis, avco
- Pipeline correction point: Emit deterministic LP-exit basis allocation from the carried LP position into returned assets before replay and AVCO.
- Terminal audit state: `AUTHORITATIVE_RECONSTRUCTION_COMPLETE`
- Evidence anchors: `0x091e356020745e6555732067a025cee9243dee6848c47dd8eb97259293735e70`, `0x8cd845033478862d78ae2214fa63e822a9dd217fab4c428801285eb1bb40d2e1`

### FA67-B5 Protocol detection is materially incomplete on rows where the protocol is already provable from current raw evidence

- Severity: `medium`
- Surfaces: `Protocol detection`
- Current database truth: WRAP=343, UNWRAP=336, SWAP=130, BRIDGE_IN=99, BRIDGE_OUT=98, VAULT_DEPOSIT=19, VAULT_WITHDRAW=18, REWARD_CLAIM=15, LP_ENTRY=12, LENDING_DEPOSIT=10, LP_EXIT=10, LP_FEE_CLAIM=6, LENDING_WITHDRAW=4, STAKING_DEPOSIT=2 currently lack `protocolName`. The biggest clusters are wrap/unwrap, swap, and bridge rows.
- Auditor-derived financially correct state: Protocol labels are best-effort metadata, but they should still be deterministically attached whenever the interacted contract, canonical selector, or audited lifecycle pairing already proves the protocol brand.
- First failed stage: `protocol enrichment`
- Evidence diagnosis: EVIDENCE_PRESENT_UNLINKED
- Type adequacy: The metadata model is adequate; the defect is missing registry coverage plus missing clarification-time enrichment on already-canonical rows.
- Remediation class: registry coverage, clarification, repair sweep
- Pipeline correction point: Expand registry/enrichment rules and backfill `protocolName` without changing already-correct economics.
- Terminal audit state: `AUTHORITATIVE_RECONSTRUCTION_COMPLETE`
- Evidence anchors: `0x0765f4b17d9961c3cd7e63b4dc1e69c948954d51816807a4ba51a8ac4709f977`, `0xc0ca8c4022bbfbb8bfd0660155e4857dd80c0cf5c521b8ad5f61ab4738fc0cab`, `0x9f6983d00441ed13bf45e1b7ac34e94540fb61f58e4a9a2189826b1e761a2f7f`, `0x7d8c79a327637fda080bcfa9204181359de791ccff95dd4b2f1b020b8af0b678`

### FA67-B6 Counterparty construction is incomplete on core bridge, swap, lending, and vault families

- Severity: `medium`
- Surfaces: `Counterparty construction`
- Current database truth: SWAP=268, EXTERNAL_TRANSFER_IN=186, BRIDGE_OUT=138, EXTERNAL_TRANSFER_OUT=127, BRIDGE_IN=117, LP_ENTRY=90, LENDING_DEPOSIT=87, LP_EXIT=62, LENDING_WITHDRAW=44, VAULT_WITHDRAW=26, VAULT_DEPOSIT=26, BORROW=21, REPAY=16, STAKING_DEPOSIT=6, STAKING_WITHDRAW=1 currently lack `counterpartyAddress`. Gaps are concentrated on swaps, external transfers, bridge rows, lending, and vault operations.
- Auditor-derived financially correct state: For on-chain protocol rows, `counterpartyAddress` should point to the interacted contract or deterministic peer. Lifecycle pairing belongs in `correlationId` and `matchedCounterparty`, not inside `counterpartyAddress`.
- First failed stage: `clarification + linking`
- Evidence diagnosis: EVIDENCE_PRESENT_UNLINKED
- Type adequacy: The model is adequate if `counterpartyAddress`, `correlationId`, and `matchedCounterparty` are kept distinct. The current defect is incomplete population of those fields.
- Remediation class: clarification, linking, repair sweep
- Pipeline correction point: Populate `counterpartyAddress` from deterministic row-local evidence and persist reciprocal lifecycle links only through `correlationId` and `matchedCounterparty`.
- Terminal audit state: `AUTHORITATIVE_RECONSTRUCTION_COMPLETE`
- Evidence anchors: `0x101c297d1a67fc0a30fc96c1aa5cc8786d630bbb00d274c9dc7bbced4c6dccd7`, `0x0765f4b17d9961c3cd7e63b4dc1e69c948954d51816807a4ba51a8ac4709f977`, `0x9f6983d00441ed13bf45e1b7ac34e94540fb61f58e4a9a2189826b1e761a2f7f`, `0x7d8c79a327637fda080bcfa9204181359de791ccff95dd4b2f1b020b8af0b678`

### FA67-B7 Bybit family reconstruction remains broader-goal blocked because the live DB snapshot has no raw CEX source collection

- Severity: `medium`
- Surfaces: `MNT family`, `Bybit broader-goal coverage`
- Current database truth: MNT exact is clean, but MNT family coverage is only 0.008451767781545872 with uncovered 1675.520059541154068938 dominated by Bybit reward inventory. At the same time `external_ledger_raw = 0`.
- Auditor-derived financially correct state: A full raw-first CEX family reconstruction requires raw venue rows. `bybit_extracted_events` is useful context, but it is not the source-of-truth collection specified for authoritative Bybit replay.
- First failed stage: `source availability`
- Evidence diagnosis: GENUINE_EVIDENCE_MISSING
- Type adequacy: The family metric is broader-goal valid, but this live snapshot does not contain the raw Bybit source collection required for authoritative CEX reconstruction.
- Remediation class: source ingestion, broader-goal replay
- Pipeline correction point: Restore raw Bybit source availability or document the explicit broader-goal limitation; do not present the current family remainder as a normal supported on-chain normalization defect.
- Terminal audit state: `GENUINE_EVIDENCE_MISSING_PROVEN`
- Evidence anchors: `BYBIT:33625378`, `SYMBOL:MNT`, `FAMILY:MNT`

## Protocol and counterparty diagnosis

The fresh live snapshot shows that metadata quality is not a cosmetic issue. It directly affects auditability and rule authoring:

- Missing `protocolName` is concentrated in deterministic families that already have reusable raw signatures: `WRAP=343`, `UNWRAP=336`, `SWAP=130`, `BRIDGE_IN=99`, `BRIDGE_OUT=98`, `VAULT_DEPOSIT=19`, `VAULT_WITHDRAW=18`, `REWARD_CLAIM=15`
- Missing `counterpartyAddress` is concentrated in lifecycle-heavy families where row-local interacted contracts and lifecycle pairs must stay separate: `SWAP=268`, `EXTERNAL_TRANSFER_IN=186`, `BRIDGE_OUT=138`, `EXTERNAL_TRANSFER_OUT=127`, `BRIDGE_IN=117`, `LP_ENTRY=90`, `LENDING_DEPOSIT=87`, `LP_EXIT=62`
- Several top protocol-gap targets already exist in `protocol-registry.json`, which proves the problem is not only missing registry entries but also missing enrichment materialization on current rows

Top protocol-gap targets from the live snapshot:

| Network | Type | Interacted address | Method | Count | Address already in registry | Change package implication |
| --- | --- | --- | --- | ---: | --- | --- |
| BASE | WRAP | 0x4200000000000000000000000000000000000006 | 0x | 130 | yes | Existing registry coverage is not reaching final row enrichment. |
| BASE | UNWRAP | 0x4200000000000000000000000000000000000006 | 0x | 129 | yes | Existing registry coverage is not reaching final row enrichment. |
| UNICHAIN | WRAP | 0x4200000000000000000000000000000000000006 | 0xd0e30db0 | 113 | yes | Existing registry coverage is not reaching final row enrichment. |
| OPTIMISM | UNWRAP | 0x4200000000000000000000000000000000000006 | 0x | 100 | yes | Existing registry coverage is not reaching final row enrichment. |
| OPTIMISM | WRAP | 0x4200000000000000000000000000000000000006 | 0x | 100 | yes | Existing registry coverage is not reaching final row enrichment. |
| UNICHAIN | UNWRAP | 0x4200000000000000000000000000000000000006 | 0x2e1a7d4d | 92 | yes | Existing registry coverage is not reaching final row enrichment. |
| BASE | BRIDGE_OUT | 0x89c6340b1a1f4b25d36cd8b063d49045caf3f818 | 0x | 18 | no | Add registry or audited enrichment coverage for this target. |
| ARBITRUM | BRIDGE_OUT | 0x89c6340b1a1f4b25d36cd8b063d49045caf3f818 | 0xd7a08473 | 16 | no | Add registry or audited enrichment coverage for this target. |
| AVALANCHE | SWAP | 0x45a62b090df48243f12a21897e7ed91863e2c86b | 0xf1910f70 | 15 | yes | Existing registry coverage is not reaching final row enrichment. |
| ARBITRUM | SWAP | 0x0000000000001ff3684f28c67538d4d072c22734 | 0x2213bc0b | 9 | no | Add registry or audited enrichment coverage for this target. |
| KATANA | SWAP | 0xac4c6e212a361c968f1725b4d055b47e63f80b75 | 0x5f3bd1c8 | 8 | no | Add registry or audited enrichment coverage for this target. |
| AVALANCHE | UNWRAP | 0xb31f66aa3c1e785363f0875a1b74e27b85fd66c7 | 0x2e1a7d4d | 8 | yes | Existing registry coverage is not reaching final row enrichment. |

Current counterparty-gap priorities:

| Type | Missing `counterpartyAddress` rows | Construction rule needed |
| --- | ---: | --- |
| SWAP | 268 | Use interacted router/aggregator contract from raw tx as row-local counterparty. |
| EXTERNAL_TRANSFER_IN | 186 | Use unique external sender from transfer evidence; do not synthesize same-wallet peers. |
| BRIDGE_OUT | 138 | Use source bridge contract as counterparty; pair lifecycle with `matchedCounterparty`. |
| EXTERNAL_TRANSFER_OUT | 127 | Use unique external recipient from transfer evidence. |
| BRIDGE_IN | 117 | Use destination settlement contract when unique; pair lifecycle with the source row. |
| LP_ENTRY | 90 | Use pool/position manager contract, not the LP token itself. |
| LENDING_DEPOSIT | 87 | Use lending pool/vault contract proven by the interacted address or receipt token. |
| LP_EXIT | 62 | Use pool/position manager contract, then allocate basis from the carried LP position. |
| LENDING_WITHDRAW | 44 | Use lending pool/vault contract proven by the interacted address or receipt token. |
| VAULT_WITHDRAW | 26 | Use vault contract proven by interacted address. |
| VAULT_DEPOSIT | 26 | Use vault contract proven by interacted address. |
| BORROW | 21 | Use debt/pool contract when selector plus debt markers prove the protocol. |
| REPAY | 16 | Use debt/pool contract when selector plus debt markers prove the protocol. |
| STAKING_DEPOSIT | 6 | Use staking contract; keep continuity in family carry. |
| STAKING_WITHDRAW | 1 | Use staking contract; keep lifecycle pairing separate from counterparty. |

## Fresh-cycle conclusion

Cycle 67 fails the mandatory financial correctness surface on the current live basis. The change package prepared in this run is therefore split into:

1. Supported on-chain normalization and continuity fixes for ETH, AVAX, USDC, and LP-exit USDT
2. Protocol detection and counterparty-construction rules that can be backfilled without redefining already-correct economics
3. A broader-goal CEX evidence boundary note explaining why `FAMILY:MNT` cannot be treated as a normal supported on-chain blocker on this DB snapshot
