# 2026-03-07 - Raw vs Normalized Mongo Audit (Re-run #2)

Dataset:
- Mongo DB: `walletradar`
- Collections: `raw_transactions`, `normalized_transactions`
- Snapshot size: `2373 raw / 2373 normalized`
- Parity by `(txHash, walletAddress, networkId)`: no gaps (`rawOnly=0`, `normalizedOnly=0`)
- Networks: ARBITRUM, AVALANCHE, BASE, BSC, ETHEREUM, LINEA, MANTLE, OPTIMISM, POLYGON, UNICHAIN, ZKSYNC

## 1) Summary (3-6 bullets): what changed + main risks.

- Coverage is complete: all raw rows have normalized counterparts after replay (`2373 -> 2373`).
- Classification quality improved but tail remains: `classificationStatus=CONFIRMED 2300`, `NEEDS_REVIEW 73`.
- `NEEDS_REVIEW` is still fully `UNCLASSIFIED` and mostly low-signal/no-value traffic: `72/73` are zero-effect calls (`value=0`, no non-zero logs/transfers).
- One accounting-critical miss remains: Aave withdraw selector `0x69328dec` has one tx still `UNCLASSIFIED` with non-zero transfers (`0x4d2e85b7...`).
- Another semantic miss remains: selector `0x80500d20` (`withdrawETH`) has one tx typed `EXTERNAL_TRANSFER_OUT` instead of lend-withdraw (`0xfbbfd229...`).
- Pipeline friction remains: `2` `APPROVAL` tx are stuck in `PENDING_CLARIFICATION` with `MISSING_LEGS` despite having no economic legs.

Current distributions:
- `type` (top): EXTERNAL_INBOUND 709, EXTERNAL_TRANSFER_OUT 361, WRAP 348, UNWRAP 339, SWAP 224, LEND_DEPOSIT 84, UNCLASSIFIED 73, LEND_WITHDRAWAL 59.
- `status`: PENDING_PRICE 2259, PENDING_STAT 39, NEEDS_REVIEW 73, PENDING_CLARIFICATION 2.
- `classificationStatus`: CONFIRMED 2300, NEEDS_REVIEW 73.

UNCLASSIFIED selector clusters:
- `0x` -> 15
- `0xa9059cbb` -> 11
- `0x0cf79e0a` -> 10
- `0x12514bba` -> 8
- `0xcc53287f` -> 6
- `0xc16ae7a4` -> 5
- `0x71ee95c0` -> 4
- long tail selectors -> 14

## 2) Findings table:

`ID | Severity (HIGH/MED/LOW) | Component/File | Risk | Why it can happen | Suggested fix`

| ID | Severity | Component/File | Risk | Why it can happen | Suggested fix |
|---|---|---|---|---|---|
| AUD-R2-501 | HIGH | `LendClassifier` | Real lend-withdraw appears as `UNCLASSIFIED` (`0x4d2e85b7...`, `0x69328dec`) | Explorer payload has incomplete/contradictory legs (minted receipt + underlying inbound, missing explicit burn/internal) so strict gate fails | Add selector-first one-leg fallback for known withdraw selectors when underlying inbound exists; ignore synthetic receipt mint noise and downgrade confidence conservatively |
| AUD-R2-502 | HIGH | `LendClassifier` vs `TransferClassifier` conflict | `withdrawETH` path can be classified as `EXTERNAL_TRANSFER_OUT` (`0xfbbfd229...`, `0x80500d20`) causing accounting type drift | Mixed token-transfer legs (burn/mint artifacts) allow transfer classifier to win over lend semantics | In dispatcher conflict policy, prefer lend-withdraw for trusted withdraw selectors before generic transfer fallback |
| AUD-R2-503 | MED | `NormalizedTransactionBuilder` / clarification stage | `APPROVAL` tx get stuck in `PENDING_CLARIFICATION` (`2` docs) and never add value | `APPROVAL` with empty legs is flagged as `MISSING_LEGS` though clarification cannot add economic legs | Mark approval-like no-value tx as terminal (skip clarification) and set `pricingStatus=NOT_REQUIRED` |
| AUD-R2-504 | MED | `ApprovalClassifier` / `TransferClassifier` (low-signal path) | Review queue polluted by zero-effect admin/no-op calls (`72/73` UNCLASSIFIED) | Calls like `lockdown`, `setRelayerApproval`, zero-value `transfer` create no economic movement but remain unresolved | Add conservative `NO_VALUE_ADMIN` policy (or map to `APPROVAL` with explicit reason) so classificationStatus becomes CONFIRMED without impacting PnL |
| AUD-R2-505 | LOW | `TxClassifierDispatcher` / normalization ordering | Deterministic replay contract not explicitly proven for mixed fallback paths | New selector-first and netting heuristics increase overlap risk between classifiers | Add deterministic replay test (`same input -> same ordered flows/types`) and conflict regression fixtures |

## 3) Required tests:

- conflict test
  - `TxClassifierDispatcherTest.classify_withdrawEthSelector_prefersLendOverTransfer`
  - Assert selector `0x80500d20` yields `LEND_WITHDRAWAL`, not `EXTERNAL_TRANSFER_OUT`.
- determinism test
  - `TxClassifierDispatcherTest.classify_sameRawInput_isDeterministic`
  - Execute classification N times on identical fixture and assert identical ordered event list.
- accounting invariant test
  - `LendClassifierTest.classify_withdrawSelector_69328dec_withSyntheticMint_noiseStillLendWithdrawal`
  - Assert at least one positive underlying leg and no invented net outflow for the same underlying asset.
- additional regressions
  - `NormalizedTransactionBuilderTest.approvalWithoutLegs_doesNotEnterClarification`
  - `ApprovalClassifierTest.classify_lockdownOrRelayerApproval_zeroEffect_markedNoValue`
  - `TransferClassifierTest.classify_zeroValueErc20Transfer_doesNotCreateEconomicLeg`

## 4) Action items (TASK format):

`TASK-ID: AUD-R2-501`
`Impact:` Fixes accounting-critical lend-withdraw miss for known Aave withdraw selectors.
`What to change:` Strengthen selector-first one-leg lend withdrawal fallback in `LendClassifier` for `0x69328dec`, `0x80500d20`, `0xba087652`, `0xb460af94` when strict evidence is incomplete.
`Acceptance criteria:` `0x4d2e85b7...` and `0xfbbfd229...` are no longer `UNCLASSIFIED/EXTERNAL_TRANSFER_OUT`; both classified as `LEND_WITHDRAWAL`.
`Tests to add/update:` `LendClassifierTest.classify_withdrawSelector_69328dec_withSyntheticMint_noiseStillLendWithdrawal`; dispatcher conflict test for `0x80500d20`.

`TASK-ID: AUD-R2-502`
`Impact:` Removes approval pipeline dead-end and reduces noisy retries.
`What to change:` In normalization/clarification policy, make `APPROVAL` with empty flows terminal (not `PENDING_CLARIFICATION`).
`Acceptance criteria:` No `APPROVAL` remains in `PENDING_CLARIFICATION` after replay.
`Tests to add/update:` `NormalizedTransactionBuilderTest.approvalWithoutLegs_doesNotEnterClarification`.

`TASK-ID: AUD-R2-503`
`Impact:` Reduces false review load without changing accounting balances.
`What to change:` Add conservative no-value admin handling for selectors with zero economic signal (`0xcc53287f`, `0xfa6e671d`, `0x0de54ba0`, zero-value `0xa9059cbb` variants).
`Acceptance criteria:` `NEEDS_REVIEW` count drops materially (target: from `73` to `<25`) with no increase in false positive SWAP/LEND/LP types.
`Tests to add/update:` positive/negative fixtures for each selector family; accounting invariant assertions that total asset deltas remain unchanged.

`TASK-ID: AUD-R2-504`
`Impact:` Guarantees replay determinism while classifier overlap grows.
`What to change:` Add deterministic replay contract test around dispatcher + builder for mixed selector/fallback cases.
`Acceptance criteria:` Repeated classification on same fixture yields byte-identical ordered flows and transaction type.
`Tests to add/update:` `TxClassifierDispatcherTest.classify_sameRawInput_isDeterministic`.

Confidence:
- Medium-high for counts and clusters (direct Mongo evidence).
- Medium for protocol semantics in low-signal admin/no-op calls where explorer payload carries zero-value transfers.

## 5) Evidence Snapshot (Re-run #2)

High-priority sample hashes:

| Tx Hash | Network | Current Type | Expected Semantic | Why |
|---|---|---|---|---|
| `0x4d2e85b7b8cc9a249da39a3008ed153cd7dc7d6d6994eaecf60237e035f26dae` | AVALANCHE | `UNCLASSIFIED` | `LEND_WITHDRAWAL` | Selector `0x69328dec`, wallet sender, non-zero inbound underlying present |
| `0xfbbfd2293154db9a2cd678596eae84f8f8ed9b140d9a9115f055f6c47c9ac931` | AVALANCHE | `EXTERNAL_TRANSFER_OUT` | `LEND_WITHDRAWAL` | Selector `0x80500d20` withdrawETH; transfer fallback currently wins |
| `0xfd83bcfb82fd7eed732554fd6df5e881cd1f470d03881c7b6b5ff2bfc01baf99` | AVALANCHE | `UNCLASSIFIED` | `APPROVAL`/no-value admin | `lockdown(...)`, zero economic movement |
| `0x9d433634d94f01558d2eec9b410edbbcd22aa70e25b09f6f34349077dc9734b9` | AVALANCHE | `UNCLASSIFIED` | `APPROVAL`/no-value admin | `setRelayerApproval(...)`, zero economic movement |

Pipeline-state anomalies:

- `APPROVAL` records in `PENDING_CLARIFICATION`: `2`  
  - `0xb6b3ac27afac284fcd0ef13463719c34925dd37cfcfb52dcb2cc894a5271d566` (BASE)  
  - `0x34be246995e967c03b1dd3dcc16b90f42646af5d94b41ddd92e21bf29f6ff2d7` (MANTLE)

Queue composition:

- `UNCLASSIFIED total: 73`
- `zero-effect/no non-zero movement: 72`
- `non-zero economic unresolved: 1` (the `0x4d2e85...` lend withdraw edge)
