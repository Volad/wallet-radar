# 2026-03-09 - LP Classifier and Flow Audit

Dataset:
- Mongo DB: `walletradar`
- Collections: `raw_transactions`, `normalized_transactions`
- LP normalized subset: `128`
- LP types in dataset:
  - `LP_ENTRY`: `33`
  - `LP_EXIT`: `10`
  - `LP_EXIT_PARTIAL`: `48`
  - `LP_FEE_CLAIM`: `37`

Reviewed as:
- `tx-classification-auditor`: classification, conflict, determinism, accounting-safe flow modeling
- `system-architect`: durable remediation approach and module boundaries

## 1) Summary

- LP subsystem currently has two independent defect classes:
  - `16` LP transactions with incomplete economic flows (missing native leg),
  - `22` LP-shaped transactions normalized into non-LP types (`18 EXTERNAL_TRANSFER_OUT`, `4 SWAP`).
- The missing-leg defect is not isolated to one tx. It affects `LP_ENTRY`, `LP_EXIT_PARTIAL`, and `LP_FEE_CLAIM`.
- The strongest misclassification cluster is concentrated-liquidity manager activity that does not contain an in-tx NFT mint and therefore degrades to transfer fallback.
- `UNICHAIN` `Uniswap v4 PositionManager` activity currently misclassifies into `SWAP`, which is accounting-critical because the position NFT becomes a priced economic leg.
- Current [LpClassifier.java](/Users/vladislavkondratenko/projects/wallet-radar/backend/src/main/java/com/walletradar/ingestion/classifier/LpClassifier.java) is already nearly `2000` lines and mixes protocol registry, evidence extraction, decision rules, and flow assembly in one class.
- Durable fix should not be another layer of point heuristics. It should split LP handling into typed registry, evidence extractor, deterministic decision engine, and canonical flow assembler.

## 2) Findings table

| ID | Severity | Component/File | Risk | Why it can happen | Suggested fix |
|---|---|---|---|---|---|
| LP-AUD-701 | HIGH | `ingestion/classifier/LpClassifier` | LP flows are economically incomplete; AVCO and realised/unrealised PnL are wrong | Native principal/payout often arrives via `tx.value`, wrapped-native logs, and internal transfers rather than wallet-facing ERC-20 `Transfer` logs | Add a canonical LP flow assembler that resolves native principal/payout for `ENTRY`, `EXIT`, and `CLAIM` |
| LP-AUD-702 | HIGH | `ingestion/classifier/LpClassifier` | `IncreaseLiquidity` / add-liquidity-to-existing-position tx degrade to `EXTERNAL_TRANSFER_OUT` | Current LP entry path depends too heavily on in-tx NFT mint; existing-position adds have no `zero -> wallet` NFT transfer | Add known-manager position-context LP entry rule that does not require NFT mint |
| LP-AUD-703 | HIGH | `ingestion/classifier/LpClassifier` | `Uniswap v4 PositionManager` activity is normalized as `SWAP` | Position manager contract is not treated as typed LP surface; minted position NFT is interpreted as economic asset | Add typed registry support for v4 position managers and ban position-NFT-as-economic-leg behavior |
| LP-AUD-704 | MED | `ingestion/job/classification/ClassificationProcessor` | Explorer-first raw with only synthetic transfer logs loses LP evidence and falls into generic transfer fallback | Receipt enrichment is not forced for all LP surfaces; some networks deliver only synthetic token transfers in raw fetch | Force selective receipt enrichment for known LP surfaces before transfer fallback |
| LP-AUD-705 | MED | `ingestion/classifier/LpClassifier` | Protocol registry drift is hard to maintain and reason about | One allowlist currently mixes v3/v4 managers, custody wrappers, staking surfaces, and wrapped-native helpers | Split registry into typed buckets: managers, custody wrappers, fee claim wrappers, wrapped native |
| LP-AUD-706 | MED | `ingestion/normalizer/NormalizedTransactionBuilder` + `ingestion/job/pricing/NormalizedTransactionStatJob` | Incomplete LP tx can still appear operationally healthy | LP completeness is not validated independently from pricing; missing economic legs are not surfaced as LP-specific invariant break | Add LP completeness invariant at normalized/stat stage |
| LP-AUD-707 | LOW | `ingestion/classifier/*` | Determinism and precedence are increasingly fragile as LP logic grows | Evidence extraction and decision logic are spread across many branches with implicit ordering assumptions | Add deterministic fixture corpus and isolate ordered LP decision engine |

## 3) Representative evidence

### 3.1 Incomplete LP flows

#### `0x29fd1b503c1b08f56de51b0d478554ac2911076883b16cae383b224874ddcd7d` (`UNICHAIN`)

- Current normalized type: `LP_ENTRY`
- Current flows:
  - `-514.771836 USDC`
- Missing economic leg:
  - `-0.207939363511022988 ETH`
- Evidence:
  - `tx.value = 207999999871136881`
  - wrapped-native `Deposit` log on `0x4200000000000000000000000000000000000006`
  - internal refund back to wallet `60636360113893`

#### `0x53eb9d1f31bd083a4105b94111973a2dd406d91b35afb054cd1f507d93360424` (`UNICHAIN`)

- Current normalized type: `LP_EXIT_PARTIAL`
- Current flows:
  - `+489.762126 USDC`
- Missing economic leg:
  - `+0.252982718838557593 ETH`
- Evidence:
  - internal transfer from manager to wallet `252982718838557593`

#### `0xed46dfb93f1f6b969c37d15a004dab3665664be7a952d848ea6fdf6e1124961c` (`UNICHAIN`)

- Current normalized type: `LP_FEE_CLAIM`
- Current flows:
  - `+4.977630 USDC`
- Missing economic leg:
  - `+0.001851514780985365 ETH`
- Evidence:
  - internal transfer from manager to wallet `1851514780985365`

### 3.2 Misclassified LP-shaped transactions

#### `0xcb32ec5c0929033502d10f499cf40deccf18ca3fb88f13d57c591b77f2d4d825` (`UNICHAIN`)

- Current normalized type: `EXTERNAL_TRANSFER_OUT`
- Raw shape:
  - known position manager `0x943e6e07a7e8e791dafc44083e54041d743c46e9`
  - `IncreaseLiquidity` evidence
  - `USDC` outbound
  - wrapped-native deposit and manager outflow
- Expected semantic:
  - `LP_ENTRY`

#### `0xbe523b76de68a3c855f950f528b6845aab907c51041046d331bb81c39b504783` (`UNICHAIN`)

- Current normalized type: `SWAP`
- Current flows:
  - `+1 UNI-V4-POSM`
  - `-801.449990 USD₮0`
- Raw shape:
  - tx target `0x4529a01c7a0410167c5740c487a8de60232617bf`
  - ERC-721 mint to wallet
  - position/liquidity event on `0x1f98400000000000000000000000000000000004`
  - outbound stable principal
  - native refund
- Expected semantic:
  - LP position opening / `LP_ENTRY`
- Risk:
  - position NFT incorrectly receives market price and participates in swap accounting

### 3.3 Aggregated defect counts

Missing native flow by LP type:
- `LP_ENTRY`: `6`
- `LP_EXIT_PARTIAL`: `6`
- `LP_FEE_CLAIM`: `4`

Missing native flow by network:
- `ARBITRUM`: `10`
- `UNICHAIN`: `3`
- `ETHEREUM`: `2`
- `BASE`: `1`

LP-shaped but non-LP normalized by current type:
- `EXTERNAL_TRANSFER_OUT`: `18`
- `SWAP`: `4`

LP-shaped but non-LP normalized by network:
- `OPTIMISM`: `12`
- `UNICHAIN`: `5`
- `BASE`: `3`
- `ETHEREUM`: `2`

## 4) Architectural assessment

### What is structurally wrong

Current LP handling bundles together four responsibilities inside one classifier:

1. contract/protocol surface recognition,
2. receipt/internal/native evidence extraction,
3. LP lifecycle decision,
4. economic flow assembly.

That shape makes each new edge case expensive:
- a new protocol surface requires new branches,
- a new native payout path requires duplicate flow logic,
- a new conflict with transfer/swap fallback requires more local guards.

### Durable architecture recommendation

Keep the monolith and current taxonomy, but split LP logic internally into:

- `LpProtocolRegistry`
  - typed config for:
    - v3 position managers,
    - v4 position managers,
    - custody wrappers / farm staking contracts,
    - wrapped-native by network
- `LpEvidenceExtractor`
  - extracts:
    - NFT mint/burn/transfer evidence,
    - pool/liquidity event evidence,
    - ERC-20 principal flows,
    - wrapped-native deposit/withdrawal,
    - internal native sweep/refund
- `LpDecisionEngine`
  - deterministic precedence:
    1. `LP_EXIT_FINAL`
    2. `LP_EXIT_PARTIAL`
    3. `LP_ENTRY`
    4. `LP_FEE_CLAIM`
    5. `LP_POSITION_STAKE`
    6. `LP_POSITION_UNSTAKE`
- `LpFlowAssembler`
  - builds canonical economic flows
  - NFT evidence never becomes an economic asset leg
  - native principal/payout is normalized to canonical native asset contract

This is not a rewrite of the whole ingestion pipeline. It is an internal refactor of the LP subsystem that preserves:
- explorer-first architecture,
- current normalized transaction taxonomy,
- Mongo collections,
- downstream AVCO/stat/pricing pipeline contract.

## 5) Required tests

- `LpClassifierTest.classify_unichainV3Entry_withNativeAndUsdc_emitsTwoPrincipalLegs`
- `LpClassifierTest.classify_unichainV3PartialExit_withInternalNativeSweep_emitsUsdcAndEth`
- `LpClassifierTest.classify_unichainV3FeeClaim_withInternalNativeSweep_emitsUsdcAndEth`
- `LpClassifierTest.classify_knownManagerIncreaseLiquidity_withoutNftMint_emitsLpEntry`
- `TxClassifierDispatcherTest.conflict_unichainV4PositionManagerMint_lpWinsOverSwap`
- `ClassificationProcessorTest.knownLpManager_explorerOnlyRaw_forcesReceiptEnrichment`
- `LpClassifierDeterminismTest.sameReceipt_sameOrderedLpFlows`
- `LpAccountingInvariantTest.lpEntryExitClaim_nativeAndErc20_avoidDoubleCount`
- `LpAccountingInvariantTest.positionNftTransfer_isEvidence_notEconomicAsset`

## 6) Action items

`TASK-ID: LP-AUD-701`
`Impact:` Fixes accounting-critical missing principal/payout legs in existing LP types.
`What to change:` Implement a canonical native-aware LP flow assembler for `LP_ENTRY`, `LP_EXIT_PARTIAL`, `LP_EXIT_FINAL`, and `LP_FEE_CLAIM`.
`Acceptance criteria:` `0x29fd...`, `0x53eb...`, `0xed46...` normalize with complete USDC + ETH flows.
`Tests to add/update:` first three tests above.

`TASK-ID: LP-AUD-702`
`Impact:` Prevents existing-position add-liquidity tx from degrading to generic transfer.
`What to change:` Add known-manager LP entry detection that does not require in-tx NFT mint.
`Acceptance criteria:` `0xcb32...` and equivalent cases normalize as `LP_ENTRY`.
`Tests to add/update:` `classify_knownManagerIncreaseLiquidity_withoutNftMint_emitsLpEntry`.

`TASK-ID: LP-AUD-703`
`Impact:` Removes high-risk `SWAP` false positives on v4 position-manager tx.
`What to change:` Add typed support for v4 position managers and enforce “position NFT is evidence, not economic asset”.
`Acceptance criteria:` `0xbe523...`, `0xd24d...`, `0x9969...`, `0xdf52...` no longer normalize as `SWAP`.
`Tests to add/update:` dispatcher conflict test + accounting invariant test.

`TASK-ID: LP-AUD-704`
`Impact:` Reduces explorer-first false negatives for LP surfaces.
`What to change:` Force selective receipt enrichment for known LP manager surfaces before transfer fallback.
`Acceptance criteria:` `OPTIMISM`/`BASE` LP manager tx with synthetic-only raw stop degrading to `EXTERNAL_TRANSFER_OUT`.
`Tests to add/update:` `knownLpManager_explorerOnlyRaw_forcesReceiptEnrichment`.

`TASK-ID: LP-AUD-705`
`Impact:` Makes LP classifier maintainable and deterministic as protocol coverage grows.
`What to change:` Split LP logic into registry, evidence extractor, decision engine, and flow assembler.
`Acceptance criteria:` `LpClassifier` becomes a thin orchestrator; LP fixture corpus remains green.
`Tests to add/update:` determinism and full LP regression suite.

Notes:
- Historical normalized data can be fully wiped and replayed; no `classifierVersion` / `lpEvidenceVersion` persistence is required.
- Targeted replay machinery is explicitly out of scope for this remediation.
