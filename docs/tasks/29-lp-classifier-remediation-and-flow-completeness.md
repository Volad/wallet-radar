# 29 — LP Classifier Remediation and Flow Completeness

Source:
- `docs/reviews/2026-03-09-lp-classifier-and-flow-audit.md`
- prior accepted scope:
  - `docs/tasks/18-lp-lifecycle-disambiguation.md`
  - `docs/tasks/19-lp-v3v4-entry-economic-completeness.md`

Operational constraint:
- Historical normalized/session data does not need to be preserved.
- Full wipe and replay is acceptable after rollout.
- Do **not** add `classifierVersion`, `lpEvidenceVersion`, or targeted replay/versioning fields.

## T-077 — Canonical native-aware LP flow completeness

- **Module(s):** `ingestion/classifier`, `ingestion/normalizer`, `costbasis/engine`, `docs`
- **Roles:** `system-architect` + `business-analyst` requirements implemented by `backend-dev`
- **Source:** `LP-AUD-701`

### System Architect decision

LP economic flow assembly must become symmetric for ERC-20 and native assets.

For concentrated-liquidity LP operations, economic legs may be evidenced by:
- ERC-20 `Transfer` logs,
- wrapped-native `Deposit` / `Withdrawal` logs,
- explorer internal native transfers,
- `tx.value` net of refund.

These signals must be normalized into canonical native asset legs and attached to the LP event before downstream accounting.

### Business Analyst acceptance criteria (DoD)

1. `LP_ENTRY` includes all principal outflows, including canonical native leg when native is wrapped inside manager/router.
2. `LP_EXIT_PARTIAL` and `LP_EXIT_FINAL` include all principal inflows, including canonical native leg when payout is delivered through internal transfer or wrapped-native withdrawal.
3. `LP_FEE_CLAIM` includes all fee assets, including canonical native leg when present.
4. Same economic movement cannot appear both as LP flow and as generic transfer/swap flow in the same tx.
5. Position NFT mint/burn/transfer remains evidence only and never becomes an economic priced asset leg.

### Worker implementation scope

- Introduce an internal LP flow-assembly helper dedicated to:
  - ERC-20 principal flows,
  - wrapped-native deposit/withdrawal,
  - internal native payout/refund handling,
  - canonical native asset mapping per network.
- Apply this helper to:
  - `LP_ENTRY`
  - `LP_EXIT_PARTIAL`
  - `LP_EXIT_FINAL`
  - `LP_FEE_CLAIM`
- Keep sign conventions strict:
  - outbound negative,
  - inbound positive.
- Ensure builder/accounting consumes the completed LP flows without extra special-casing.

### Required tests

1. `LpClassifierTest.classify_unichainV3Entry_withNativeAndUsdc_emitsTwoPrincipalLegs`
2. `LpClassifierTest.classify_unichainV3PartialExit_withInternalNativeSweep_emitsUsdcAndEth`
3. `LpClassifierTest.classify_unichainV3FeeClaim_withInternalNativeSweep_emitsUsdcAndEth`
4. `LpAccountingInvariantTest.lpEntryExitClaim_nativeAndErc20_avoidDoubleCount`

### Acceptance Criteria (DoD)

1. `0x29fd1b503c1b08f56de51b0d478554ac2911076883b16cae383b224874ddcd7d` normalizes with `USDC + ETH` entry flows.
2. `0x53eb9d1f31bd083a4105b94111973a2dd406d91b35afb054cd1f507d93360424` normalizes with `USDC + ETH` exit flows.
3. `0xed46dfb93f1f6b969c37d15a004dab3665664be7a952d848ea6fdf6e1124961c` normalizes with `USDC + ETH` claim flows.
4. Current audited missing-native LP cases no longer remain incomplete after full replay.

---

## T-078 — Position-manager lifecycle decision without NFT-mint dependency

- **Module(s):** `ingestion/classifier`, `docs`
- **Roles:** `system-architect` + `business-analyst` requirements implemented by `backend-dev`
- **Source:** `LP-AUD-702`, `LP-AUD-703`

### System Architect decision

Concentrated-liquidity lifecycle detection must not require in-tx NFT mint for LP entry.

Known manager surfaces must support deterministic lifecycle decisions from evidence:
- `IncreaseLiquidity` / pool `Mint` + principal outflows => `LP_ENTRY`
- `DecreaseLiquidity` / `Collect` + principal inflows, no burn => `LP_EXIT_PARTIAL`
- `DecreaseLiquidity` / `Collect` + burn => `LP_EXIT_FINAL`
- `Collect`-only => `LP_FEE_CLAIM`
- custody NFT transfer => `LP_POSITION_STAKE` / `LP_POSITION_UNSTAKE`

### Business Analyst acceptance criteria (DoD)

1. Add-liquidity-to-existing-position tx is classified as `LP_ENTRY` even when no `zero -> wallet` NFT mint happens in the same tx.
2. Known v4 position manager mint tx is not classified as `SWAP`.
3. Position NFT remains evidence only, never swap buy-leg.
4. LP manager tx does not degrade to `EXTERNAL_TRANSFER_OUT` if LP evidence is sufficient.

### Worker implementation scope

- Extend LP decision logic to use known-manager lifecycle evidence, not NFT mint alone.
- Add typed support for v4 position-manager surfaces.
- Add explicit guard that position-NFT mint/burn/transfer cannot create economic `SWAP`/`EXTERNAL_*` interpretation.
- Keep dispatcher precedence deterministic:
  1. LP lifecycle
  2. swap
  3. transfer fallback

### Required tests

1. `LpClassifierTest.classify_knownManagerIncreaseLiquidity_withoutNftMint_emitsLpEntry`
2. `TxClassifierDispatcherTest.conflict_unichainV4PositionManagerMint_lpWinsOverSwap`
3. `LpAccountingInvariantTest.positionNftTransfer_isEvidence_notEconomicAsset`
4. `LpClassifierDeterminismTest.sameReceipt_sameOrderedLpFlows`

### Acceptance Criteria (DoD)

1. `0xcb32ec5c0929033502d10f499cf40deccf18ca3fb88f13d57c591b77f2d4d825` no longer normalizes as `EXTERNAL_TRANSFER_OUT`.
2. `0xbe523b76de68a3c855f950f528b6845aab907c51041046d331bb81c39b504783` and audited siblings no longer normalize as `SWAP`.
3. No duplicate LP + swap/transfer economic meaning for the same tx.

---

## T-079 — Selective receipt enrichment for LP surfaces in explorer-first mode

- **Module(s):** `ingestion/job/classification`, `ingestion/adapter/evm/explorer`, `docs`
- **Roles:** `system-architect` + `business-analyst` requirements implemented by `backend-dev`
- **Source:** `LP-AUD-704`

### System Architect decision

Explorer-first ingestion remains the default, but LP surfaces are allowed to force receipt enrichment before generic fallback when raw payload is too weak.

Receipt enrichment trigger must remain narrow and deterministic to keep costs under control.

### Business Analyst acceptance criteria (DoD)

1. Known LP manager / position-manager surfaces are enriched before generic transfer fallback when raw payload only contains synthetic transfer logs or otherwise lacks LP receipt evidence.
2. Unrelated transactions do not receive broader receipt enrichment because of this feature.
3. Enriched LP tx must preserve determinism and existing classifier precedence.

### Worker implementation scope

- Add LP-surface enrichment trigger based on:
  - known LP manager target,
  - known LP NFT/manager event topics,
  - synthetic-only transfer raw for LP target.
- Invoke selective receipt fetch before `TransferClassifier` fallback path can win.
- Keep existing explorer-first policy unchanged for non-LP transactions.

### Required tests

1. `ClassificationProcessorTest.knownLpManager_explorerOnlyRaw_forcesReceiptEnrichment`
2. `TxClassifierDispatcherTest.conflict_knownLpManager_enrichedReceipt_preventsTransferFallback`
3. Negative test for non-LP tx proving no extra enrichment trigger.

### Acceptance Criteria (DoD)

1. Current `OPTIMISM` and `BASE` audited LP manager misses stop degrading into `EXTERNAL_TRANSFER_OUT` after replay.
2. LP enrichment remains bounded to LP surfaces only.

---

## T-080 — Internal LP subsystem refactor for maintainability and determinism

- **Module(s):** `ingestion/classifier`, `docs`
- **Roles:** `system-architect` requirements implemented by `backend-dev`
- **Source:** `LP-AUD-705`, `LP-AUD-706`, `LP-AUD-707`

### System Architect decision

`LpClassifier` must be reduced to a thin orchestrator.

Internal module split:
- `LpProtocolRegistry`
- `LpEvidenceExtractor`
- `LpDecisionEngine`
- `LpFlowAssembler`

No external API or taxonomy change is required.

### Business Analyst acceptance criteria (DoD)

1. Existing LP taxonomy remains unchanged.
2. Fixture behavior remains stable after refactor.
3. Adding a new LP protocol surface should require:
  - registry/config entry,
  - fixture(s),
  - no large control-flow duplication in classifier.

### Worker implementation scope

- Extract typed LP registry from hardcoded mixed responsibilities.
- Extract evidence parsing from decision logic.
- Extract canonical flow assembly from lifecycle decision logic.
- Keep `LpClassifier` as entry point delegating to the extracted components.
- Preserve current module boundaries; no new service/process split.

### Required tests

1. `LpClassifierDeterminismTest.sameReceipt_sameOrderedLpFlows`
2. Full LP regression suite on audited fixtures:
  - entry,
  - partial exit,
  - final exit,
  - fee claim,
  - position stake,
  - position unstake,
  - v4 position manager mint
3. Conflict tests against `SwapClassifier` and `TransferClassifier`

### Acceptance Criteria (DoD)

1. `LpClassifier.java` no longer contains mixed protocol registry, evidence parsing, lifecycle decision, and flow-assembly responsibilities in one large control block.
2. Full LP fixture corpus passes after refactor.
3. Full replay after wiping normalized/session data produces no regression on audited LP hashes.
