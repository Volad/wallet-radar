# 28 — Raw vs Normalized Audit Remediation (2026-03-07)

## T-060 — Aave gateway native lend semantics (depositETH/withdrawETH)

- **Module(s):** `ingestion/classifier`, `ingestion/job/classification`, `docs`
- **Roles:** backend-dev
- **Source:** `docs/reviews/2026-03-07-raw-vs-normalized-mongo-audit.md` (`AUD-F-301`, `AUD-F-302`)

---

## Goal

Restore correct lend semantics for Aave WrappedTokenGateway flows where one leg is native and ERC20-style transfer logs are incomplete.

---

## Product Decisions (Locked)

1. `depositETH (0x474cf53d)` must normalize as `LEND_DEPOSIT`, not `SWAP`.
2. `withdrawETH (0x80500d20)` must normalize as `LEND_WITHDRAWAL`, not `EXTERNAL_TRANSFER_OUT`.
3. Detection must remain conservative:
   - selector + wallet sender + receipt-token mint/burn evidence;
   - optional protocol target checks (gateway/pool known addresses).

---

## Implementation Scope

1. Extend `LendClassifier` with explicit native-gateway paths:
   - deposit: native outflow from tx `value` + receipt token mint to wallet;
   - withdraw: receipt token burn from wallet + native inbound from internal transfers (or strict fallback if present).
2. Ensure transfer/swap fallback does not override these lend events.
3. Keep deterministic event ordering and sign conventions.

---

## Required Tests

1. `LendClassifierTest.classify_depositEthSelector_emitsLendDepositNativePlusAToken`
2. `LendClassifierTest.classify_withdrawEthSelector_emitsLendWithdrawalATokenPlusNative`
3. `NormalizationPipelineTest.aaveGatewayDepositWithdraw_preservesNonDisposalInvariant`

---

## Acceptance Criteria (DoD)

1. Aave gateway `depositETH` and `withdrawETH` fixtures produce only `LEND_*` semantics.
2. No fallback `SWAP`/`EXTERNAL_TRANSFER_OUT` for these cases.
3. All related classifier and pipeline tests pass.

---

## T-061 — Lend-vs-LP overlap guard for Aave-style withdraw selectors

- **Module(s):** `ingestion/classifier`, `docs`
- **Roles:** backend-dev
- **Source:** `docs/reviews/2026-03-07-raw-vs-normalized-mongo-audit.md` (`AUD-F-304`)

## Goal

Prevent LP heuristics from classifying Aave withdraw-like lending transactions as LP exits.

## Implementation Scope

1. Add selector/protocol guardrails in LP classifier entry/exit detection:
   - if lend selector/protocol context is present, LP classifier must abstain.
2. Keep existing LP behavior unchanged for non-lend contexts.

## Required Tests

1. `TxClassifierDispatcherTest.conflict_aaveWithdrawSelector_vs_lpPattern_lendWins`
2. `LpClassifierTest.classify_aaveWithdrawSelector_doesNotEmitLpExit`

## Acceptance Criteria (DoD)

1. Tx `0x4d2e85b7b8cc9a249da39a3008ed153cd7dc7d6d6994eaecf60237e035f26dae` does not normalize as `LP_*`.
2. Existing LP fixtures remain green.

---

## T-062 — Approval stale-row replay policy for selector-family updates

- **Module(s):** `ingestion/job/classification`, `ingestion/classifier`, `docs`
- **Roles:** backend-dev
- **Source:** `docs/reviews/2026-03-07-raw-vs-normalized-mongo-audit.md` (`AUD-F-303`)

## Goal

Ensure selector coverage changes (e.g. `0x5a3b74b9`) can be applied to already normalized rows without full pipeline reset.

## Implementation Scope

1. Add a targeted replay/reset command/service path for selector-bound subsets.
2. Keep current strict approval guard (`no transfer effects`, `value == 0`).
3. Document operational runbook command for selector replay.

## Required Tests

1. `ApprovalClassifierTest.classify_setUserUseReserveAsCollateral_noTransfers_emitsApproval`
2. Service/integration test for selector-targeted replay query/update.

## Acceptance Criteria (DoD)

1. Stale `UNCLASSIFIED` records with selector `0x5a3b74b9` can be reprocessed without full data wipe.
2. Replay does not affect unrelated selectors.

---

## T-063 — Protocol-aware one-leg lend allowlist (Unichain supply)

- **Module(s):** `ingestion/classifier`, `ingestion/config`, `docs`
- **Roles:** backend-dev
- **Source:** `docs/reviews/2026-03-07-raw-vs-normalized-mongo-audit.md` (`AUD-F-305`)

## Goal

Classify known lend deposit calls that have one-leg transfer evidence and no explicit receipt-token mint logs.

## Product Decisions (Locked)

1. Allowlist is **network-scoped** and **contract+selector scoped**.
2. One-leg path emits `LEND_DEPOSIT` with conservative confidence.
3. No global heuristic broadening outside allowlist.

## Implementation Scope

1. Add config for one-leg lend rules (`network -> contract -> selectors`).
2. Extend `LendClassifier` to emit one-leg `LEND_DEPOSIT` when allowlist rule and flow constraints match.
3. Keep false-positive protections: wallet must be sender; quantity must be outbound.

## Required Tests

1. `ProtocolAwareLendClassifierTest.classify_unichainSupplySelector_allowlistedContract_emitsLendDeposit`
2. Negative tests: same selector on unknown contract must not classify as lend.

## Acceptance Criteria (DoD)

1. Observed Unichain supply hashes from audit no longer degrade to `EXTERNAL_TRANSFER_OUT`.
2. No regression on existing lend/lp/swap suites.

---

## T-064 — Native wrap/unwrap taxonomy and accounting-safe semantics

- **Module(s):** `domain/transaction/normalized`, `ingestion/classifier`, `ingestion/normalizer`, `ingestion/job/pricing`, `docs`
- **Roles:** backend-dev
- **Source:** `docs/reviews/2026-03-07-raw-vs-normalized-mongo-audit.md` (`AUD-F-306`)
- **Architecture:** `docs/adr/ADR-029-classification-pricing-status-and-native-wrap-taxonomy.md`

## Goal

Stop classifying native wrap/unwrap conversions as taxable swaps.

## Product Decisions (Locked)

1. Introduce explicit operation family for wrap/unwrap.
2. Wrap/unwrap is non-disposal conversion and must not produce realised PnL side-effects.
3. Pricing is optional for wrap/unwrap in the normalized pipeline.

## Implementation Scope

1. Add event/type support for wrap/unwrap.
2. Update native classifier to emit wrap/unwrap events instead of `SWAP_*`.
3. Update normalizer/pricing/stat stages to treat wrap/unwrap as conversion, not swap.

## Required Tests

1. `WrapClassifierTest.classify_wethDepositWithdraw_emitsWrapUnwrap_notSwap`
2. Accounting invariant tests ensuring no realised PnL on wrap/unwrap.

## Acceptance Criteria (DoD)

1. `0xd0e30db0` / `0x2e1a7d4d` normalize as wrap/unwrap (not `SWAP`).
2. Wrap/unwrap records do not fail pricing/stat due to swap-only invariants.

---

## T-065 — Split classification signal from pricing signal

- **Module(s):** `domain/transaction/normalized`, `ingestion/job/classification`, `ingestion/job/pricing`, `ingestion/job/stat`, `api`, `docs`
- **Roles:** backend-dev
- **Source:** `docs/reviews/2026-03-07-raw-vs-normalized-mongo-audit.md` (`AUD-F-307`)
- **Architecture:** `docs/adr/ADR-029-classification-pricing-status-and-native-wrap-taxonomy.md`

## Goal

Decouple classification certainty from price availability, so pricing-only failures do not pollute classification review queues.

## Product Decisions (Locked)

1. Keep existing pipeline `status` for orchestration/backward compatibility.
2. Add explicit independent fields:
   - `classificationStatus`
   - `pricingStatus`
3. `NEEDS_REVIEW` in `status` may still be used operationally, but classification dashboards must rely on `classificationStatus`.

## Implementation Scope

1. Add new domain enums/fields and persistence mapping.
2. Set/update statuses in classification + pricing + stat jobs.
3. Expose new statuses in API DTOs where normalized tx is returned.

## Required Tests

1. `NormalizedTransactionStatusPolicyTest.classificationConfirmed_priceUnresolved_notClassificationReview`
2. Integration test covering transition `PENDING_PRICE -> NEEDS_REVIEW` with `classificationStatus=CONFIRMED`.

## Acceptance Criteria (DoD)

1. Pricing-only unresolved rows are distinguishable from true classification uncertainty.
2. Existing consumers remain backward-compatible with legacy `status`.

---

## T-066 — Low-evidence enrichment hardening (selector/receipt hints)

- **Module(s):** `ingestion/job/classification`, `ingestion/adapter/evm/explorer`, `docs`
- **Roles:** backend-dev
- **Source:** `docs/reviews/2026-03-07-raw-vs-normalized-mongo-audit.md` (`AUD-F-308`)

## Goal

Reduce true low-evidence `UNCLASSIFIED` cases via deterministic enrichment hints without increasing false positives.

## Implementation Scope

1. Extend low-evidence enrichment with deterministic selector dictionaries and safe receipt/detail hints.
2. Keep conservative confidence gating and deterministic ordering.
3. Add regression fixtures for previously unclassified audited hashes.

## Required Tests

1. `LowEvidenceEnrichmentTest.unclassifiedBucket_selectorAndTraceHint_upgradesConfidence`
2. False-positive guard tests for unrelated tx categories.

## Acceptance Criteria (DoD)

1. Low-evidence bucket decreases on replay dataset.
2. No regression in existing classifier fixtures.

---

## T-074 — Native/stable pricing coverage for canonical assets

- **Module(s):** `common`, `pricing`, `docs`
- **Roles:** `backend-dev`
- **Source:** audit follow-up on `AUD-501`

## Goal

Remove avoidable `PENDING_PRICE` backlog for canonical native assets and known stablecoins that already have deterministic pricing policy.

## Product Decisions (Locked)

1. Canonical native contracts already mapped to CoinGecko ids must continue resolving without protocol-specific heuristics.
2. `EURC` is treated as stablecoin and must resolve to `$1.00`.
3. Do not broaden stablecoin policy beyond explicit contract allowlist.

## Implementation Scope

1. Extend `StablecoinRegistry` with audited `EURC` contract coverage.
2. Add focused resolver tests proving:
   - synthetic native `0xeeee...` path still resolves through historical chain,
   - `EURC` resolves through stablecoin path.
3. Keep resolver order unchanged (`STABLECOIN` before CoinGecko).

## Required Tests

1. `StablecoinRegistryTest.eurcContractsAreStablecoins`
2. `HistoricalPriceResolverChainTest.stablecoinResolverHandlesEurc`

## Acceptance Criteria (DoD)

1. `EURC` no longer waits on CoinGecko and resolves via `PriceSource.STABLECOIN`.
2. Existing native-resolution tests remain green.

---

## T-075 — Receipt/position asset pricing policy hardening

- **Module(s):** `domain/transaction/normalized`, `ingestion/normalizer`, `ingestion/job/pricing`, `docs`
- **Roles:** `backend-dev`
- **Source:** audit follow-up on `AUD-503`

## Goal

Stop requiring direct market pricing for receipt-like legs whose economic meaning is already encoded by transaction type.

## Product Decisions (Locked)

1. `LEND_DEPOSIT` positive receipt legs are **not** direct price targets.
2. `LP_ENTRY` positive receipt / position legs are **not** direct price targets.
3. This change must stay conservative and type-driven; do not add broad symbol-based heuristics in pricing policy.

## Implementation Scope

1. Extend normalized pricing policy so `LEND_DEPOSIT` positive legs and `LP_ENTRY` positive legs skip direct pricing.
2. Ensure builder/pricing job transition such rows from `PENDING_PRICE` to `PENDING_STAT` without resolver calls.
3. Keep pricing behavior unchanged for:
   - `LEND_WITHDRAWAL` underlying inbound,
   - `BORROW` underlying inbound,
   - `LP_FEE_CLAIM`,
   - `SWAP`.

## Required Tests

1. `NormalizedTransactionBuilderTest.lendDepositSkipsPricingForReceiptLeg`
2. `NormalizedTransactionBuilderTest.lpEntrySkipsPricingForReceiptLeg`
3. `NormalizedTransactionPipelineJobsTest.lendDepositReceiptSkipsPricingResolver`
4. `NormalizedTransactionPipelineJobsTest.lpEntryReceiptSkipsPricingResolver`

## Acceptance Criteria (DoD)

1. Canonical `LEND_DEPOSIT` and `LP_ENTRY` receipt legs no longer enter resolver-dependent `PENDING_PRICE`.
2. Existing pricing/stat transitions remain unchanged for economic inbound assets.

---

## T-076 — Conservative transfer fallback guard for lend-like selector context

- **Module(s):** `ingestion/classifier`, `docs`
- **Roles:** `backend-dev`
- **Source:** audit follow-up on `AUD-505`

## Goal

Prevent generic transfer/swap heuristics from classifying known lend-like selector contexts as `SWAP` or `EXTERNAL_*` when lend evidence is incomplete.

## Product Decisions (Locked)

1. When selector context is strongly lend-like but classifier evidence is incomplete, conservative abstention is preferred over false-positive transfer/swap classification.
2. Guard must be limited to known lend selector families and wallet-sender context.
3. No architecture changes or new transaction statuses.

## Implementation Scope

1. Add conservative lend-context detection usable by `TransferClassifier`.
2. Make `TransferClassifier` abstain for wallet-sender tx with known lend selector context and wallet-relevant mint/burn or bidirectional transfer evidence.
3. Keep existing transfer behavior unchanged for non-lend selector contexts.

## Required Tests

1. `TransferClassifierTest.classify_borrowSelectorDebtMintOnly_returnsEmptyForTransferClassifier`
2. `TransferClassifierTest.classify_depositSelectorReceiptTransferOnly_returnsEmptyForTransferClassifier`
3. Negative test proving ordinary two-leg swap heuristic still works outside lend selector context.

## Acceptance Criteria (DoD)

1. Known lend selector context no longer degrades to generic `SWAP` or `EXTERNAL_*` when receipt/debt evidence is partial.
2. Existing swap and transfer fixtures remain green.

---

## T-067 — Net-flow swap resolution for selector-led swaps with refund legs

- **Module(s):** `ingestion/classifier`, `docs`
- **Roles:** backend-dev
- **Source:** `docs/reviews/2026-03-07-raw-vs-normalized-mongo-audit.md` (`AUD-F-401`)

## Goal

Avoid false `EXTERNAL_TRANSFER_OUT` on known swap calls where wallet gets refund in the sold token and buy leg in another token.

## Product Decisions (Locked)

1. For tx identified as swap-call context (`selector/functionName`), classifier may use **net by token contract**.
2. When net result is exactly one negative asset and one positive asset, normalize as `SWAP_SELL` + `SWAP_BUY`.
3. Preserve conservative fallback: if net pattern is ambiguous, keep external transfer behavior.

## Implementation Scope

1. Add net-flow aggregation path in `TransferClassifier` before default external-transfer fallback.
2. Keep existing mint/burn protections and native wrap exclusions.
3. Ensure no overlap regression with explicit `SwapClassifier` path.

## Required Tests

1. `TransferClassifierTest.classify_swapSelectorWithRefund_sameAssetInOut_emitsNetSwap`
2. `TxClassifierDispatcherTest.conflict_swapCallWithRefundLegs_swapWinsOverTransfer`

## Acceptance Criteria (DoD)

1. `0x92d0d928...`, `0x352c00cf...`, `0x1dddb938...` normalize as `SWAP` (not `EXTERNAL_TRANSFER_OUT`).
2. Existing transfer classifier tests remain green.

---

## T-068 — One-leg lend withdrawal fallback for incomplete receipt/native evidence

- **Module(s):** `ingestion/classifier`, `docs`
- **Roles:** backend-dev
- **Source:** `docs/reviews/2026-03-07-raw-vs-normalized-mongo-audit.md` (`AUD-F-402`, `AUD-F-403`, `AUD-F-405`)

## Goal

Classify known lend-withdraw calls correctly when explorer payload lacks one of strict withdrawal legs.

## Product Decisions (Locked)

1. Fallback is allowed only for known withdraw selectors (`0x80500d20`, `0xba087652`, `0xb460af94`, `0x69328dec`).
2. Fallback is conservative and selector-scoped:
   - wallet must be tx sender;
   - must have either net receipt-token outflow or single clear underlying inflow.
3. Fallback emits `LEND_WITHDRAWAL` with reduced confidence (normalization policy handles confidence/review).

## Implementation Scope

1. Extend `LendClassifier` after strict withdrawal paths with selector-bound one-leg fallback.
2. Keep strict 2-leg path unchanged and preferred when available.
3. Prevent fallback from triggering on unrelated selectors.

## Required Tests

1. `LendClassifierTest.classify_withdrawEthSelector_withoutInternalNative_emitsOneLegWithdrawal`
2. `LendClassifierTest.classify_redeemSelector_withoutReceiptBurn_emitsOneLegWithdrawal`
3. `LendClassifierTest.classify_withdrawSelector_edgeCase_69328dec_notUnclassified`

## Acceptance Criteria (DoD)

1. `0xfbbfd229...`, `0xc8b94615...`, and `0x4d2e85b7...` no longer end as `EXTERNAL_TRANSFER_OUT`/`UNCLASSIFIED`.
2. Strict withdrawal fixtures still emit two-leg `LEND_WITHDRAWAL`.

---

## T-069 — LP zap-out no-burn support (selector `0x8b284b0e`)

- **Module(s):** `ingestion/classifier`, `docs`
- **Roles:** backend-dev
- **Source:** `docs/reviews/2026-03-07-raw-vs-normalized-mongo-audit.md` (`AUD-F-404`)

## Goal

Handle LP zap-out exits that do not include explicit burn-to-zero LP evidence and can contain LP roundtrip transfers.

## Product Decisions (Locked)

1. Support is selector-scoped (`0x8b284b0e`) and router/protocol-context constrained.
2. LP roundtrip (same contract in+out) is allowed; classification uses **net proceeds**.
3. Ambiguous patterns remain on conservative fallback path.

## Implementation Scope

1. Extend LP no-burn exit context to include zap-out selector.
2. Update LP no-burn exit classifier to tolerate roundtrip outflow/inflow and emit net positive LP-exit proceeds.
3. Keep existing LFJ/Pancake/Uniswap LP behavior unchanged.

## Required Tests

1. `LpClassifierTest.classify_zapOutSelector_withLpRoundtrip_emitsLpExitPartialProceeds`
2. Regression test confirming existing no-burn exit path still works.

## Acceptance Criteria (DoD)

1. `0xf7f8908b...` normalizes as LP exit class (per policy), not `EXTERNAL_TRANSFER_OUT`.
2. No regressions in existing LP classifier fixtures.

---

## T-070 — Selector-first lend withdrawal fallback hardening (`0x69328dec`, `0x80500d20`)

- **Module(s):** `ingestion/classifier`, `docs`
- **Roles:** backend-dev
- **Source:** `docs/reviews/2026-03-07-raw-vs-normalized-mongo-audit.md` (`AUD-R2-501`, `AUD-R2-502`)

## Goal

Close the remaining high-severity lend-withdraw misclassifications where explorer payload has synthetic/missing legs.

## Product Decisions (Locked)

1. Known withdraw selectors (`0x69328dec`, `0x80500d20`, `0xba087652`, `0xb460af94`) are treated as trusted lend intent when wallet is tx sender.
2. For incomplete evidence, one-leg fallback is allowed if there is a single clear underlying inbound OR net receipt-token outflow.
3. For these selectors, lend semantic must win over generic transfer fallback.

## Implementation Scope

1. Harden `LendClassifier` one-leg fallback for synthetic receipt mint noise cases.
2. Add selector-priority overlap rule in `TxClassifierDispatcher` so lend-withdraw beats `EXTERNAL_TRANSFER_OUT` on identical movement key.
3. Keep conservative abort for ambiguous multi-asset patterns.

## Required Tests

1. `LendClassifierTest.classify_withdrawSelector_69328dec_withSyntheticMint_noiseStillLendWithdrawal`
2. `TxClassifierDispatcherTest.classify_withdrawEthSelector_prefersLendOverTransfer`
3. Update existing `0x80500d20` fixture assertion to expect lend-withdraw semantics.

## Acceptance Criteria (DoD)

1. `0x4d2e85b7...` no longer ends as `UNCLASSIFIED`.
2. `0xfbbfd229...` no longer ends as `EXTERNAL_TRANSFER_OUT`.
3. Existing borrow/repay/lp/swap suites remain green.

---

## T-071 — Approval no-legs terminal policy (no clarification loop)

- **Module(s):** `ingestion/normalizer`, `ingestion/job/classification`, `docs`
- **Roles:** backend-dev
- **Source:** `docs/reviews/2026-03-07-raw-vs-normalized-mongo-audit.md` (`AUD-R2-503`)

## Goal

Prevent approval-only transactions from getting stuck in `PENDING_CLARIFICATION` when no economic legs exist by design.

## Product Decisions (Locked)

1. `APPROVAL` is non-economic and may have zero flows.
2. Zero-flow approvals are terminal for clarification and should proceed without enrichment.
3. Pricing remains `NOT_REQUIRED` for approval.

## Implementation Scope

1. Update normalization missing-data/status policy so `APPROVAL` with empty flows does not emit `MISSING_LEGS`.
2. Ensure initial status for approval is not `PENDING_CLARIFICATION`.
3. Keep UNCLASSIFIED empty-flow behavior unchanged.

## Required Tests

1. `NormalizedTransactionBuilderTest.approvalWithoutLegs_doesNotEnterClarification`
2. Regression test for `UNCLASSIFIED` empty-flow still producing `NO_CLASSIFICATION_EVIDENCE`.

## Acceptance Criteria (DoD)

1. No `APPROVAL` records remain in `PENDING_CLARIFICATION` after replay.
2. Approval normalization remains accounting-neutral (no legs, no PnL impact).

---

## T-072 — Zero-effect admin call classification to reduce review noise

- **Module(s):** `ingestion/classifier`, `docs`
- **Roles:** backend-dev
- **Source:** `docs/reviews/2026-03-07-raw-vs-normalized-mongo-audit.md` (`AUD-R2-504`)

## Goal

Reduce `UNCLASSIFIED` backlog caused by permission/admin calls with zero economic effect.

## Product Decisions (Locked)

1. For known admin selectors/contracts, classify as `APPROVAL` only when there is no non-zero economic movement.
2. Zero-value tokenTransfer artifacts must not be treated as economic transfer effects.
3. Conservative rule: if any non-zero transfer/internal/log evidence exists, classifier must abstain.

## Implementation Scope

1. Extend `ApprovalClassifier` selector/function coverage for observed admin families (`lockdown`, `setRelayerApproval`, `setMinterApproval`, etc.).
2. Refine economic-effect guard to ignore zero-value explorer transfer artifacts.
3. Keep sender and `value == 0` constraints.

## Required Tests

1. `ApprovalClassifierTest.classify_lockdownWithoutEconomicEffects_emitsApproval`
2. `ApprovalClassifierTest.classify_setRelayerApprovalWithoutEconomicEffects_emitsApproval`
3. `ApprovalClassifierTest.classify_zeroValueExplorerTransfer_stillApproval`
4. Negative test with non-zero transfer remains non-approval.

## Acceptance Criteria (DoD)

1. Review backlog decreases on replay (`NEEDS_REVIEW` reduced versus current baseline).
2. No new false positives in lend/swap/lp regression fixtures.

---

## T-073 — Deterministic replay contract for classifier overlap paths

- **Module(s):** `ingestion/classifier`, `ingestion/normalizer`, `docs`
- **Roles:** backend-dev
- **Source:** `docs/reviews/2026-03-07-raw-vs-normalized-mongo-audit.md` (`AUD-R2-505`)

## Goal

Guarantee deterministic classification output ordering when multiple classifiers overlap.

## Product Decisions (Locked)

1. Same input must always produce identical ordered output events.
2. Determinism test must cover overlap priorities (lend vs transfer, swap vs transfer).
3. This task adds tests/contracts, not heuristic broadening.

## Implementation Scope

1. Add replay determinism test around dispatcher output ordering.
2. Add assertion for stable event ordering/selection under repeated execution.
3. Keep current deterministic key strategy unless tests reveal regression.

## Required Tests

1. `TxClassifierDispatcherTest.classify_sameInputManyTimes_isDeterministic`
2. Conflict determinism regression with lend-vs-transfer overlap.

## Acceptance Criteria (DoD)

1. Repeated execution on identical fixture yields byte-identical type + event ordering.
2. Determinism tests are part of backend CI suite.

---

## Architecture Notes (System-Architect)

1. No new storage model or API contract required for T-067..T-073.
2. Changes remain classifier/normalizer-local and keep modular-monolith boundaries intact.
3. Determinism and conservative fallback rules remain mandatory (do not broaden global heuristics).
4. Cost profile unchanged: no additional RPC/indexer dependencies.

---

## Worker Handoff Command

```text
$backend-dev implement docs/tasks/28-raw-vs-normalized-audit-remediation.md (T-070..T-073) end-to-end with tests
```
