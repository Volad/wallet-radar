# Task 158 — Cycle 5 portfolio phantom closeout (post-regression)

**Goal (one sentence):** Restore dashboard and ledger to **cycle/4 canonical truth** (~$11.4k portfolio, ~+$9k realised, Bybit ~$794) by fixing **N1–N8** per cycle/5 audit, after the regression (~$77k portfolio) caused by wrong **stream authority** and related replay gaps.

**Source of truth:** `cycle-autorun/cycle-data/cycle/5/results/` — especially `blockers.md`, `required-changes.md`, `accounting-failure-analysis.md`.

---

## 1. Acceptance criteria (DoD)

Treat these as **separate surfaces** (do not merge into one “green”):

| Surface | DoD |
|--------|-----|
| **Portfolio total** | $11 400 ± $500 (sim + dashboard after rebuild) |
| **Bybit umbrella** | $794 ± $5 vs `/v5/account/wallet-balance`; per-asset qty ±0.001 where spec lists |
| **On-chain wallets** | `0x1a87…` ~$8 938 ± $200; `0xf03b…` ~$1 675 ± $50; `0x68bc…` ~$2.65 ± $1 |
| **Realised PnL** | +$9 084 ± $500 (cumulative, post-replay) |
| **Unrealised %** | Finite, between −20% and +30%; no NaN AVCO; no $807k-class outliers |
| **Extracted counts** | Gate 6: aggregate by `sourceStream` **unchanged** vs pre-deploy (flag-only change) |

**Gates:** pre-deploy **Gate 1** orphan query (`INTERNAL_TRANSFER:deposit_*` without FH pair) = 0 **or** implement conditional FH/Deposit suppression per `blockers.md` Risk 1.

---

## 2. Edge cases (in / out)

| Case | Scope |
|------|--------|
| Auto-route **disabled** (deposit INTERNAL without FH twin) | **In** — Gate 1 + conditional `basisRelevant` for FH/Deposit |
| UTA→FUND vs FUND→UTA both directions | **In** — matrix in `required-changes.md` §C; tests for both |
| MAW `maw_deduct_transfer_*` on FUND | **In** — sender debit must exist in basis-relevant stream (N5) |
| `/v5/earn/position` as replay input | **Out** for cycle/5 close (ADR-006); optional probe cycle/6+ |
| TON / Solana user-ack off-dashboard | **Out** — unchanged from cycle/5 blockers §1 |

---

## 3. Task breakdown (ordered)

| ID | Dependency | Work |
|----|--------------|------|
| T1 | — | **N1:** FH/Deposit `basisRelevant=false` (+ Gate 1 or pair index) — `BybitExtractionService` |
| T2 | T1 | **N5:** TX_LOG `TRANSFER_OUT` basis true; FH **Transfer out** basis true; FH **Transfer in** stays false — extraction + unit tests per matrix |
| T3 | T2 | **N2:** Backfill `timeUtc` (and optional hash metadata) for `INTERNAL_TRANSFER:deposit_*` from `DEPOSIT_ONCHAIN` |
| T4 | T2–T3 | **N3:** Replay — `correlationId` / peer pairing **D-1** or at-time **D-2** for orphans; non-null carry into receiver flows where required |
| T5 | T4 | **N4:** `extractFlexibleSaving` sign + `basisRelevant=true`; route FH/Earn to **EARN** sub-account in `inferFundingHistorySubAccount` |
| T6 | — | **N6:** Aave aToken classifier / family equivalence **all** relevant chains (or explicit defer + ADR) |
| T7 | — | **N7:** Receipt peg pricing (`CMETH`, `BBSOL`, …) — optional same release or defer with DoD line |
| T8 | T1–T5 | Destructive `full-rebuild` + Gates 4–6 + dashboard checklist |

**N8** is marked resolved by N1+N5 combo in audit — no separate task unless regression reappears.

---

## 4. Risk notes

- **Cross-cycle doc drift:** Task 157 / cycle/4 H matrix **conflicts** with cycle/5 for FH/Deposit and TX_LOG transfer basis — **cycle/5 wins** until implemented and re-verified.  
- **blockers.md §7 DoD** line “N4 (EARN snapshot) scheduled for cycle/6” **contradicts** §5 Risk 4 (no new collection) — **interpretation:** optional **probe** in cycle/6, not blocking snapshot store (align with ADR-006).  
- **N6/N7:** If slipped, on-chain and receipt phantoms remain (~$7–10k band per analysis) — split DoD or defer explicitly.

---

## References

- ADR: `docs/adr/ADR-006-cycle5-bybit-stream-authority-and-earn-subaccount.md`  
- Supersedes EARN snapshot requirement: `docs/adr/ADR-005-cycle4-bybit-pipeline.md` (amended)  
- Prior epic: `docs/tasks/157-cycle4-bybit-ledger-audit-closeout.md`

---

## 5. Implementation status (code landed)

| Audit item | Summary |
|------------|---------|
| **N1** | FH `Deposit` → `basisRelevant=false` (`BybitExtractionService`). |
| **N2** | `deposit_*` internal transfers hydrate `timeUtc` / chain metadata from matching `DEPOSIT_ONCHAIN` row. |
| **N3** | `BybitCanonicalTransactionBuilder`: economy `correlationId` (`bybit-econ-v1:…`) for eligible streams including `EARN_FLEXIBLE_SAVING`; sub-account UUID rows keep `bybit-sub-transfer:` / `matchedCounterparty`. |
| **N4** | Flexible saving sign from EARN perspective, `basisRelevant=true`, `walletRef` **EARN**; FH Earn → **EARN** sub-account. |
| **N5** | TX_LOG `TRANSFER_OUT` and FH `Transfer out` basis-relevant; `TRANSFER_IN` / FH `Transfer in` non-basis where specified. |
| **N6** | `AccountingAssetFamilySupport`: explicit aToken symbols + `LendingAssetSymbolSupport.lendingReceiptLifecycleUnderlying` for cross-chain receipt→underlying family; dashboard valuation uses **physical** symbol for Aave protocol metadata while **display** symbol may roll up to the family label (`SessionDashboardQueryService`). |
| **N7** | `CanonicalAssetCatalog`: `CMETH`→`ETH`, `BBSOL`→`SOL` (+ existing receipt aliases); `COINGECKO_IDS` includes **SOL** for priced history. |
| **N8** | Covered by N1+N5 per cycle/5 audit (no separate code path). |
| **N9** | `BybitExtractionService.baseEvent` + `BybitBackfillSegmentExecutor.extractOccurredAt` now try `timestamp`, `createdAt`, `updatedAt`, `transferDate`, `transactionDate`, `blockTime` so `INTERNAL_TRANSFER`, `UNIVERSAL_TRANSFER`, `EARN_FLEXIBLE_SAVING` rows hydrate real economic time instead of falling back to `importedAt = today`. Added regression tests `internalTransferStreamHydratesTimeUtcFromTimestampField` + `earnFlexibleSavingHydratesTimeUtcFromCreatedAtField`. ADR-006 §6. |
| **N10** | `BybitExtractionService.inferFundingHistorySubAccount`: default for all FH rows is now `FUND` (not `EARN`) because `/v5/account/transaction-log` is the FUND wallet log (raw payload `showBusiType="fundingAccountRecord*"`). FH/Earn principal-flow rows (FlexSavings/Launchpool **subscribe/redeem**, Auto-Earn, Fixed Savings Redemption, on-chain Earn OUT subscription, on-chain Earn redemption) route to FUND as the FUND-side debit/credit leg paired with `EARN_FLEXIBLE_SAVING` (where mirror exists). EARN-only carve-outs: all `REWARD_CLAIM` interest/yield rows (auto-compound into the Earn position; this also reclassifies `On-Chain Earn Rewards Distribution (Bonus)` from `INTERNAL_TRANSFER` to `REWARD_CLAIM` in `mapFundingHistoryCanonicalType`) and `On-chain Earn subscription` rows with `ioDirection=I` (off-chain origin, receipt token never lands on FUND wallet balance). Regression tests: `fundingHistoryFlexibleSavingsSubscriptionRoutesToFundSubAccountAsBasisLeg`, `fundingHistoryFlexibleSavingsRedemptionRoutesToFundSubAccountAsBasisLeg`, `fundingHistoryFlexibleSavingsInterestRoutesToEarnAsBasisRelevant`, `fundingHistoryOnChainEarnSubscriptionOutboundDebitsFundSubAccount`, `fundingHistoryOnChainEarnRedemptionInboundCreditsFundSubAccount`, `fundingHistoryOnChainEarnSubscriptionInEthFamilyBecomesStakingDeposit` (unchanged: EARN credit). ADR-006 §7. |
| **N12** | `maw_deduct_transfer_*` withdrawal mirror inflates FUND. The `/v5/asset/transfer/query-inter-transfer-list` endpoint emits a synthetic `INTERNAL_TRANSFER` row (transferId prefix `maw_deduct_transfer_`) for every external withdrawal originating from UNIFIED. The row has `fromAccountType=UNIFIED, toAccountType=FUND` and runs **after** the paired `FH/Withdraw` event in chronological order. Result: the FH/Withdraw of `-X` on FUND finds an empty balance and produces a 0-delta shortfall, then the maw_deduct credits `+X` on FUND — a permanent phantom. **Fix**: in `BybitExtractionService.extractInternalTransfer`, when the row's `providerEventKey` (transferId) starts with `maw_deduct_transfer_`, the row is a withdrawal-companion mirror, not a real internal transfer; suppress with `basisRelevant=false`. The corresponding `TX_LOG TRANSFER_OUT` (same suffix) already debits UTA correctly via cycle/5 N5; the FH/Withdraw remains basis-relevant on FUND but becomes a shortfall (`quantityShortfallDelta=X`) because FUND legitimately has no inventory of that asset. The umbrella balance for the user is correct (UTA = pre-withdraw qty − X via TX_LOG TRANSFER_OUT; FUND = 0). Regression tests: `internalTransferMawDeductWithdrawalCompanionIsSuppressedFromBasis`, `universalTransferMawDeductWithdrawalCompanionIsSuppressedFromBasis`. ADR-006 §8. |
| **N13** | Cross-sub-account staking pair phantom (METH FUND → CMETH EARN). Two FH/Earn `On-chain Earn subscription` legs of one logical "stake METH → receive CMETH" operation land on different sub-accounts (METH outflow on FUND per N10, CMETH inflow on EARN per N10 carve-out). `BybitTradePairer.findLiquidStakingCounterLeg` pairs them by `bybitDescription` + asset-family within a time window, and `buildStakingPair` collapses them into a single `STAKING_DEPOSIT` with the anchor's `walletAddress`. The result: both flows apply to ONE wallet (whichever lexicographically wins anchor selection), so the METH outflow on FUND is never debited (FUND METH remains permanent phantom). **Fix**: in `BybitNormalizationService.normalizeLiquidStakingRow`, refuse to pair when the two legs are on different Bybit sub-accounts. Each leg becomes a standalone `INTERNAL_TRANSFER` via `builder.buildMappedRow`, with `walletRef` preserved per the extraction routing. Quantity balances per sub-account are now correct; the cross-family basis carry (METH FUND → CMETH EARN) is parked as a follow-up because it requires either per-flow `walletAddress` on `NormalizedTransaction.Flow` or a family-keyed `correlationId` scheme (`bybit-stake-fam-v1:`) — neither blocks cycle/5 balance closeout. Regression tests: `extractedLiquidStakingPairThatCrossesBybitSubAccountsIsNormalizedAsIndependentLegs`; the existing `extractedOnChainEarnSubscriptionPairBecomesConfirmedStakingDeposit` / `extractedEth20StakeMintPairBecomesConfirmedStakingDeposit` guard the same-sub-account positive case. ADR-006 §9. |
