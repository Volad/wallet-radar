# T-086..T-088 - Post-Remediation Classification and Pricing Follow-up

- **Owner:** backend-dev
- **Source:** повторный Mongo audit `normalized_transactions` on 2026-03-10 after `T-081..T-085`
- **Related docs:**
  - `docs/adr/ADR-025-normalized-transactions-status-pipeline.md`
  - `docs/adr/ADR-029-classification-pricing-status-and-native-wrap-taxonomy.md`
  - `docs/tasks/28-raw-vs-normalized-audit-remediation.md`
  - `docs/tasks/30-normalized-review-pricing-symbol-and-scam-remediation.md`

## Status Snapshot After T-081..T-085

Current `normalized_transactions` snapshot:

- `total = 2374`
- `CONFIRMED = 2155`
- `PENDING_PRICE = 147`
- `NEEDS_REVIEW = 72`
- `classificationStatus = NEEDS_REVIEW` now contains only `UNCLASSIFIED`
- `pricingStatus = NOT_REQUIRED` now covers all `UNCLASSIFIED`
- blank-symbol flows reduced from `80` to `49`

Current backlog composition:

- `PENDING_PRICE`
  - `EXTERNAL_INBOUND = 135`
  - `SWAP = 4`
  - `LP_FEE_CLAIM = 4`
  - `LP_EXIT_PARTIAL = 3`
  - `EXTERNAL_TRANSFER_OUT = 1`
- `NEEDS_REVIEW`
  - `UNCLASSIFIED = 72`

Observed residual families:

1. Promo/phishing inbound spam still reaching `normalized_transactions` and `PENDING_PRICE`
   - examples: `Telegram @TronVanity88_bot`, `visit www.*`, `Swap your Voucher ...`, `solshiba.top`
2. Blank-symbol synthetic lending/vault families
   - mainly `LEND_DEPOSIT` / `LEND_WITHDRAWAL`
   - mainly on `ARBITRUM`, `BASE`, `MANTLE`
3. Legit-looking priced assets still unresolved
   - examples: `ETH`, `WETH`, `ARB`, `wstETH`, `CAKE`, `GLV [WETH-USDC]`

## Locked Product Decisions

1. `UNCLASSIFIED` remains classification-review workload, not pricing workload.
2. Confirmed promo/phishing inbound spam must be dropped upstream; it must not survive into normalized or pricing stages.
3. Receipt/debt/vault-share assets must not be treated as generic `EXTERNAL_INBOUND` market-priced assets unless classifier semantics explicitly require that.
4. Blank `assetSymbol` is not acceptable for synthetic lending/vault flows when raw/protocol metadata is sufficient to determine the symbol deterministically.
5. Historical normalized/session data may be wiped and replayed; no versioning fields need to be persisted for this scope.
6. `USDC`, `USD₮0`, `GHO`, and `USDC.e` are treated as `$1.00` stablecoins for all audited supported-network contract aliases in this scope.

---

## T-086 - Residual inbound promo/phishing spam hardening

- **Module(s):** `ingestion/filter`, `resources`
- **Roles:** backend-dev

### Goal

Eliminate the remaining high-confidence promo/phishing inbound rows that still survive into `PENDING_PRICE`.

### Implementation Scope

1. Extend inbound spam fingerprints and/or deterministic text-pattern guards for the currently observed residual families:
   - `Telegram @TronVanity88_bot`
   - `visit www.*`
   - `Swap your Voucher ...`
   - `*.vip`, `*.top`, similar promo domains
2. Keep the same safety guards as `T-085`:
   - sender is not wallet
   - inbound-only to wallet
   - no wallet outbound leg in same tx
   - fingerprint or high-confidence promo pattern required
3. Do not drop legitimate wallet-initiated claim or protocol interactions.
4. After rollout, replay from raw and verify that these rows no longer appear in `normalized_transactions`.

### Required Tests

1. `ScamFilterTest.promotionalVisitTokenDropsBeforeNormalization`
2. `ScamFilterTest.voucherPromoTokenDropsBeforeNormalization`
3. `ScamFilterTest.walletInitiatedClaimLikeTxStillSurvives`

### Acceptance Criteria (DoD)

1. Residual promo/phishing inbound rows no longer reach `normalized_transactions` after replay.
2. Legitimate wallet-initiated flows are not regressed.

---

## T-087 - Lending/vault receipt-family classifier and symbol remediation

- **Module(s):** `ingestion/classifier`, `ingestion/normalizer`
- **Roles:** backend-dev

### Goal

Remove the remaining blank-symbol lending/vault families and stop treating receipt/debt/vault-share assets as generic market-priced inbound transfers.

### Implementation Scope

1. Audit the residual blank-symbol families, especially:
   - `0x078f358208685046a11c85e8ad32895ded33a249`
   - `0x7e97fa6893871a2751b5fe961978dccb2c201e65`
   - `0x2514a2ce842705ead703d02fabfd8250bfcfb8bd`
   - `0xd4a0e0b9149bcee3c920d2e00b5de09138fd8bb7`
   - `0xe12eed61e7cc36e4cf3304b8220b433f1fd6e254`
2. Determine whether each family should be classified as:
   - lending receipt/debt/vault event
   - non-economic wrapper/receipt movement
   - true external transfer
3. Ensure deterministic symbol propagation for the resulting synthetic flows.
4. For receipt/debt/vault-share assets, avoid direct pricing workload unless explicit economic semantics require it.

### Required Tests

1. `LendClassifierTest.receiptOrDebtFamilyDoesNotDegradeToGenericInbound`
2. `LendClassifierTest.syntheticLendFlowPreservesAssetSymbol`
3. `NormalizedTransactionPipelineJobsTest.receiptOrDebtFamilyDoesNotStayInPendingPriceWithoutNeed`

### Acceptance Criteria (DoD)

1. Residual blank-symbol lending/vault flows are either symbolized correctly or removed by corrected classification.
2. Receipt/debt/vault-share assets no longer sit in `PENDING_PRICE` as generic inbound rows when direct market price is not required.

---

## T-088 - Legit historical pricing follow-up for remaining market assets

- **Module(s):** `pricing`
- **Roles:** backend-dev

### Goal

Resolve the remaining legitimate `PENDING_PRICE` rows for market-priced assets that are neither spam nor receipt-token artifacts.

### Implementation Scope

1. Audit and close remaining resolver gaps for representative assets:
   - `ETH`
   - `WETH`
   - `ARB`
   - `wstETH`
   - `CAKE`
   - `GLV [WETH-USDC]` only if it is confirmed to require direct market pricing rather than explicit `NOT_REQUIRED`
2. Distinguish true market-priced assets from protocol share/receipt assets.
3. Add deterministic pricing resolution or explicit `NOT_REQUIRED` policy for each confirmed family.
4. Keep `UNCLASSIFIED` and spam rows out of this scope.

### Required Tests

1. `HistoricalPriceResolverChainTest.remainingLegitAssetFamiliesResolveOrBypassDeterministically`
2. `NormalizedTransactionPricingJobTest.legitResidualPriceRowsDoNotLoopWithoutReason`

### Acceptance Criteria (DoD)

1. Remaining legitimate market-priced residual rows no longer stay indefinitely in `PENDING_PRICE` after replay.
2. Protocol share/receipt assets are explicitly excluded from this pricing scope when direct market price is not required.

---

## Worker Handoff Command

```text
$backend-dev implement docs/tasks/31-post-remediation-classification-and-pricing-follow-up.md (T-086..T-088) end-to-end with tests
```
