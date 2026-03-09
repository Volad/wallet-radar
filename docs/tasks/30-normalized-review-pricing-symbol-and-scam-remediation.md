# T-081..T-085 - Normalized Review, Pricing, Symbol, and Scam Remediation

- **Owner:** backend-dev
- **Source:** Mongo audit of `UNCLASSIFIED`, `PENDING_PRICE`, `NEEDS_REVIEW` normalized transactions on 2026-03-09
- **Related docs:**
  - `docs/adr/ADR-025-normalized-transactions-status-pipeline.md`
  - `docs/adr/ADR-029-classification-pricing-status-and-native-wrap-taxonomy.md`
  - `docs/tasks/28-raw-vs-normalized-audit-remediation.md`

## Locked Product Decisions

1. `USDC`, `USD₮0`, `GHO`, and `USDC.e` are treated as `$1.00` stablecoins on every supported network where their audited contracts are observed.
2. `UNCLASSIFIED` transactions are classification-review workload, not price-resolution workload.
3. `APPROVAL` transactions are non-economic and may legally have zero flows.
4. Same-asset refund or change legs inside `EXTERNAL_TRANSFER_OUT` do not require separate market price lookup.
5. High-confidence promo/phishing inbound spam must be dropped upstream before normalization and pricing.
6. Symbol propagation must be deterministic: if raw explorer metadata contains token symbol/name for a classified leg, normalized flow should preserve symbol whenever possible.

## Scope

This task bundle closes the deterministic, low-risk remediation items from the audit:

- false `NEEDS_REVIEW` for `APPROVAL`
- `pricingStatus=PENDING` on `UNCLASSIFIED`
- missing `$1` stable treatment for audited stable aliases
- missing native/wrapped-native historical price alias resolution
- empty `assetSymbol` on synthetic and explorer-backed flows
- pointless pricing of same-asset refund legs
- upstream hard-drop for newly observed inbound promo/phishing spam

Protocol-family classifier broadening for remaining non-spam `UNCLASSIFIED` families stays in follow-up scope and is intentionally not part of this task file.

---

## T-081 - Approval and UNCLASSIFIED status semantics cleanup

- **Module(s):** `ingestion/normalizer`, `ingestion/job/pricing`
- **Roles:** backend-dev

### Goal

Stop treating non-economic approvals and unresolved classification rows as pricing workload.

### Implementation Scope

1. `APPROVAL` with zero flows must not fail stat validation with `MISSING_LEGS`.
2. `UNCLASSIFIED` must keep `classificationStatus=NEEDS_REVIEW`, but start with `pricingStatus=NOT_REQUIRED`.
3. `UNCLASSIFIED` must not enter resolver-dependent `PENDING_PRICE` solely because it has flows.
4. Existing confirmed economic types must keep current pricing pipeline semantics.

### Required Tests

1. `NormalizedTransactionBuilderTest.unclassifiedStartsAsNotPriceableReviewWork`
2. `NormalizedTransactionPipelineJobsTest.approvalWithoutFlowsConfirmsWithoutReview`
3. Regression test that `SWAP` and `EXTERNAL_INBOUND` pricing behavior is unchanged.

### Acceptance Criteria (DoD)

1. `APPROVAL` rows no longer end in `NEEDS_REVIEW` only because flows are empty.
2. `UNCLASSIFIED` rows are not treated as price backlog before successful reclassification.

---

## T-082 - Stable and native pricing aliases

- **Module(s):** `common`, `pricing`
- **Roles:** backend-dev

### Goal

Remove avoidable `PENDING_PRICE` backlog for audited stable aliases and native/pseudo-native assets.

### Implementation Scope

1. Extend `StablecoinRegistry` with audited contracts for:
   - Unichain `USDC`
   - Unichain `USD₮0`
   - zkSync `USDC.e`
   - Avalanche `GHO`
2. Add explicit native historical price alias resolution before generic CoinGecko contract lookup.
3. Support canonical native and wrapped-native aliases for all supported EVM networks.
4. Support zkSync pseudo-native `0x000000000000000000000000000000000000800a` as `ETH`.

### Required Tests

1. `StablecoinRegistryTest.auditedStableAliasesAreStablecoins`
2. `HistoricalPriceResolverChainTest.nativeAliasesResolveBeforeCoinGeckoContractLookup`
3. `HistoricalPriceResolverChainTest.auditedStableAliasesResolveToOneUsd`

### Acceptance Criteria (DoD)

1. Audited `USDC`, `USD₮0`, `GHO`, `USDC.e` rows no longer depend on CoinGecko market lookup.
2. Native and wrapped-native aliases resolve through deterministic aliasing before generic contract lookup.

---

## T-083 - Deterministic symbol propagation for normalized flows

- **Module(s):** `ingestion/classifier`, `ingestion/normalizer`
- **Roles:** backend-dev

### Goal

Preserve `assetSymbol` in normalized flows whenever explorer-backed raw metadata already contains the symbol.

### Implementation Scope

1. Add explorer metadata fallback for transfer-based classifier paths when token-decimals resolver returns blank symbol.
2. Add equivalent symbol fallback for LP flow assembly paths built from receipt/log evidence.
3. Keep canonical native symbol handling unchanged.
4. Avoid network calls for symbol enrichment in the normalizer.

### Required Tests

1. `TransferClassifierTest.syntheticExplorerTransferPreservesTokenSymbolFallback`
2. `LpClassifierTest.baseLpFeeClaimPreservesCakeSymbol`
3. `NormalizedTransactionBuilderTest.nativePseudoTokenFlowKeepsEthSymbol`

### Acceptance Criteria (DoD)

1. Representative `ARB`, `CAKE`, and zkSync native pseudo-token fixtures no longer produce blank `assetSymbol`.
2. Same input always produces the same symbol output.

---

## T-084 - Same-asset refund legs must not trigger pricing workload

- **Module(s):** `domain/transaction/normalized`, `ingestion/job/pricing`
- **Roles:** backend-dev

### Goal

Avoid pointless price lookup when `EXTERNAL_TRANSFER_OUT` includes a small same-asset inbound refund or change leg.

### Implementation Scope

1. Add policy/helper for `EXTERNAL_TRANSFER_OUT` same-asset refund detection.
2. Inbound refund leg must not require direct price resolution when there is a corresponding outbound leg of the same asset in the same transaction.
3. Do not broaden this rule to swaps or unrelated bidirectional multi-asset cases.

### Required Tests

1. `NormalizedTransactionPipelineJobsTest.externalTransferOutSameAssetRefundSkipsPriceLookup`
2. Regression test that same-asset `SWAP` is still handled by existing classifier/type logic.

### Acceptance Criteria (DoD)

1. Same-asset refund/change rows do not remain in `PENDING_PRICE` only because of the positive refund leg.

---

## T-085 - Inbound promo/phishing spam hardening

- **Module(s):** `ingestion/filter`, `resources`
- **Roles:** backend-dev

### Goal

Prevent newly observed promo/phishing inbound spam from entering normalized and pricing pipelines.

### Implementation Scope

1. Extend high-confidence inbound spam fingerprints in `application.yml` for newly observed promo/phishing contracts.
2. Keep hard-drop guard strict:
   - sender is not wallet
   - inbound-only to wallet
   - no wallet outbound leg in the same tx
   - fingerprint match required
3. Preserve current zero-value poisoning logic.
4. Do not drop wallet-initiated legitimate protocol interactions.

### Required Tests

1. `ScamFilterTest.knownInboundSpamFingerprintDropsPromotionalClaimTx`
2. `ScamFilterTest.walletInitiatedClaimLikeTxIsNotDroppedByInboundSpamRule`
3. Regression test for existing zero-value poisoning fingerprints remains green.

### Acceptance Criteria (DoD)

1. Newly observed promo/phishing inbound rows do not reach normalization after replay.
2. Legitimate wallet-initiated flows are not regressed by the new hard-drop logic.

---

## Follow-up Notes

1. Remaining non-spam protocol families (`receipt/debt token inflows`, `vault multicall families`, `low-signal no-log cases`) stay in follow-up classification scope and require separate classifier design/review.
2. Historical normalized/session data can be wiped and replayed; no `classifierVersion` or `lpEvidenceVersion` persistence is required.

## Worker Handoff Command

```text
$backend-dev implement docs/tasks/30-normalized-review-pricing-symbol-and-scam-remediation.md (T-081..T-085) end-to-end with tests
```
