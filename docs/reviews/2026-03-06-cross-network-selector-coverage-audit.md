# 2026-03-06 — Cross-Network Selector Coverage Audit

## 1) Summary (3–6 bullets): what changed + main risks.

- Static audit of classifier selector coverage across supported EVM networks identified high-impact gaps outside ZKsync-only paths.
- Primary risk is semantic degradation from `LEND_* / BORROW / REPAY` to `EXTERNAL_*` or `SWAP` when legitimate protocol variants use uncovered selectors.
- Bridge/relay no-log selectors are underrepresented, causing avoidable `NEEDS_REVIEW` or unstable fallback behavior.
- Multicall selector variants are partially covered; nested protocol calls can lose context and drop to generic classification.
- Existing dispatcher priority model is sufficient, but requires broader selector entry points to avoid false fallback routing.

## 2) Findings table:

`ID | Severity (HIGH/MED/LOW) | Component/File | Risk | Why it can happen | Suggested fix`

| ID | Severity | Component/File | Risk | Why it can happen | Suggested fix |
|---|---|---|---|---|---|
| AUD-F-001 | HIGH | `LendClassifier` | Borrow/repay/deposit/withdraw variants on non-ZK networks can be misclassified as `EXTERNAL_*` or `SWAP` | Selector coverage is narrow (`0x02c205f0/0x69328dec/0xa415bcad/0x573ade81` only) | Add known Aave V3/WETH gateway selector variants and keep flow-driven checks authoritative |
| AUD-F-002 | HIGH | `BridgeCallClassifier` | Bridge/relay tx can miss bridge classification path | Known bridge method set does not include modern relay/diamond/intent selectors | Add selector hints for observed relay families while keeping ERC20-transfer guard |
| AUD-F-003 | MED | `TransferClassifier` | Swap fallback can under-detect aggregator swap calls without reliable function name | `isLikelySwapCall` relies only on `functionName contains "swap"` | Add selector-hint fallback for known swap-router signatures |
| AUD-F-004 | MED | `ApprovalClassifier` | Permit/delegation-only calls can go to review path | Selector coverage does not include self-permit and Permit2 permit-transfer families | Extend approval selector set for no-economic-effect permission operations |
| AUD-F-005 | MED | `LendClassifier` | Nested multicall lend operations can lose lend context | Only one multicall selector is treated as lend context hint | Add common multicall selector variants (`0x5ae401dc`, `0x1f0464d1`) |

## 3) Required tests:

- `conflict test`:
  - `TxClassifierDispatcherTest.conflict_lend_vs_transfer_lend_wins_same_movement`
  - Asserts that when both lend and transfer events produce same movement key, lend semantic survives.
- `determinism test`:
  - `LendClassifierTest.classify_selectorVariantBorrow_isDeterministic`
  - Asserts identical ordered output across repeated classification of same fixture.
- `accounting invariant test`:
  - `LendClassifierTest.classify_selectorVariantRepay_preservesSignInvariant`
  - Asserts `BORROW` quantity positive, `REPAY` quantity negative, and synthetic native contract never chosen as lend underlying.
- Additional coverage tests:
  - `LendClassifierTest` for `repayWithATokens`, `repayWithPermit`, `depositETH`, `withdrawETH`, `borrowETH`, `repayETH`.
  - `BridgeCallClassifierTest` for relay selector hints.
  - `TransferClassifierTest` for selector-based swap-call hint path.
  - `ApprovalClassifierTest` for self-permit / Permit2 selector recognition without transfer effects.

## 4) Action items (TASK format):

`TASK-ID: AUD-210`  
`Impact:` Prevents lend semantic loss on non-ZK networks.  
`What to change:` Extend `LendClassifier` selector coverage for known Aave V3/gateway variants and multicall variants, keeping flow checks authoritative.  
`Acceptance criteria:` Covered selector families classify as `LEND_* / BORROW / REPAY` when flow evidence matches and do not over-classify when evidence is absent.  
`Tests to add/update:` Lend selector-variant tests + conflict/determinism/invariant tests.  
`Notes (optional):` Do not introduce protocol-specific hardcoded contract dependency.

`TASK-ID: AUD-211`  
`Impact:` Stabilizes bridge no-log classification for modern relay paths.  
`What to change:` Add conservative bridge selector hints in `BridgeCallClassifier` with existing ERC20-transfer suppression guard retained.  
`Acceptance criteria:` Value-carrying relay calls hit bridge path; tx with ERC20 transfer logs remain excluded from bridge no-log classifier path.  
`Tests to add/update:` `BridgeCallClassifierTest` selector regression cases.  
`Notes (optional):` Keep false-positive risk low by requiring native value > 0 and sender wallet checks.

`TASK-ID: AUD-212`  
`Impact:` Reduces swap fallback misses on aggregator signatures.  
`What to change:` Extend `TransferClassifier#isLikelySwapCall` with explicit selector hints for known swap-router selectors.  
`Acceptance criteria:` One-leg token-out + native internal-in swap flows are recognized via selector hints even when functionName is absent.  
`Tests to add/update:` `TransferClassifierTest` selector-based swap-call tests.  
`Notes (optional):` Keep fallback conservative.

`TASK-ID: AUD-213`  
`Impact:` Moves permission-only calls out of review noise.  
`What to change:` Extend `ApprovalClassifier` selector list with self-permit and Permit2 transfer-permit variants.  
`Acceptance criteria:` No-transfer/no-value permission calls classify as `APPROVAL`; calls with transfer effects remain excluded.  
`Tests to add/update:` New `ApprovalClassifierTest`.  
`Notes (optional):` Preserve economic-effect guard.
