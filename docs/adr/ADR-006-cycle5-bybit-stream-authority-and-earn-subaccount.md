# ADR-006 — Cycle 5 Bybit stream authority matrix and EARN as sub-account

**Status:** Accepted (supersedes EARN data-source part of ADR-005 §4 for the audited path)  
**Date:** 2026-05-11  
**Inputs:** `cycle-autorun/cycle-data/cycle/5/results/blockers.md`, `required-changes.md`, `accounting-failure-analysis.md`

## Problem

Post–cycle/4 code changes produced a structurally wrong dashboard (~$77k portfolio vs ~$11.4k truth). Cycle/5 root-cause analysis isolates **eight defects (N1–N8)**; **N1 + N5** dominate phantom inflation (~$58k of ~$66k). Remainder: **N3** (basis-less transfer inflows), **N4** (EARN invisible), **N6–N7** (on-chain receipt basis/pricing).

## Decision

### 1. One authoritative basis-relevant leg per economic event (Bybit umbrella)

Per sub-account, exactly one stream row must carry basis for each event class. The matrix in `cycle/5/results/required-changes.md` §A is canonical for implementation and review. In particular:

- **On-chain auto-route deposit:** authoritative credit = `INTERNAL_TRANSFER` stream `deposit_<hash>` on **UTA**; **`FUNDING_HISTORY/Deposit` on FUND must be `basisRelevant=false`** (N1) to avoid double-count with the auto-route leg.
- **Manual / MAW moves:** **sender** debits must be basis-relevant: `TRANSACTION_LOG/TRANSFER_OUT` (UTA sender) and **`FUNDING_HISTORY/Transfer out`** (FUND sender) **`basisRelevant=true`**; **receiver** duplicate streams stay non-basis where they mirror `INTERNAL_TRANSFER` stream (N5). This reverses the prior “suppress all TX_LOG transfer rows” shortcut when it dropped the sender.

### 2. EARN is a third Bybit sub-account, not a new persisted snapshot domain

For flexible saving and FH/Earn flows: use existing **`BYBIT:{uid}:EARN`**, `INTERNAL_TRANSFER` canonical type, and `TransferReplayHandler` — **no new `bybit_earn_snapshots` collection** as a correctness prerequisite (cycle/5). Optional **weekly `/v5/earn/position` reconciliation probe** (alert-only) may follow in a later cycle; it is **not** the ledger source of truth during replay.

**ADR-005 §4** (persisted earn snapshot as required for close) is **superseded** for cycle/5 closeout by this ADR: acceptance is ledger-backed EARN wallet + sign/routing fixes per `required-changes.md` change-set F.

### 3. Gate 1 (orphan safety) before unconditional FH/Deposit suppression

If Mongo shows `INTERNAL_TRANSFER:deposit_*` rows **without** a matching FH/Deposit (auto-route disabled / edge UID), **do not** blindly set all FH/Deposit to non-basis: use conditional suppression (pair index) as in cycle/5 `blockers.md` §5 Risk 1.

### 4. N3 / N2 / D — extraction and replay contract

- **N2:** `timeUtc` on `INTERNAL_TRANSFER:deposit_*` must be backfilled from matching `DEPOSIT_ONCHAIN` (and related hash metadata where applicable).  
- **N3:** Receiver `INTERNAL_TRANSFER` legs must receive deterministic cost carry (`valueDeltaUsd` / pairing): **D-1** preferred (`correlationId` + peer sender leg from streams enabled in §1); **D-2** at-time pricing for true orphans (e.g. pure on-chain sender) after N2.

### 5. N6 / N7 — scope boundary

- **N6** (Aave aToken basis on non-Mantle chains): in-scope for cycle/5 **or** explicitly deferred with ADR + DoD split (see task 158).  
- **N7** (receipt peg pricing): nice-to-have per cycle/5 blockers; same deferral rule.

### 6. N9 — `timeUtc` lookup must cover every Bybit stream's timestamp field

**Problem (observed runtime regression after N1–N8 rollout):**
After the first `full-rebuild`, dashboard still over-reported Bybit by ~$6.8k. Root cause: `BybitExtractionService.baseEvent` only inspected `transactionTime`, `execTime`, `createTime`, `updatedTime`; the streams that ship the real economic timestamp under a **different field name** silently fell back to `importedAt` (today).

| Stream | Real timestamp field | Was missed before fix |
|---|---|---|
| `INTERNAL_TRANSFER` (`/v5/asset/transfer/query-inter-transfer-list`) | `timestamp` (epoch ms string) | Yes |
| `UNIVERSAL_TRANSFER` (`/v5/asset/transfer/query-universal-transfer-list`) | `timestamp` | Yes |
| `EARN_FLEXIBLE_SAVING` (`/v5/earn/order`) | `createdAt` / `updatedAt` | Yes |

The fallback `importedAt = today` collapsed years of historical credits/debits onto one minute today. The replay engine then processed historical `CARRY_OUT` (debits) BEFORE `CARRY_IN` (credits) for the same asset, producing `quantityDelta = 0` for the debits (no basis to carry) and full positive deltas for the today-stamped credits. Net effect: every transfer's debit leg was dropped from the ledger.

**Decision:** the canonical `timeUtc` extraction must accept the union of timestamp field names used by all Bybit streams. `BybitExtractionService.baseEvent` and the upstream `BybitBackfillSegmentExecutor.extractOccurredAt` are both updated to try `timestamp`, `createdAt`, `updatedAt`, `transferDate`, `transactionDate`, `blockTime` in addition to the original set. The `BybitExtractedEventMapper` `BYBIT_EVENT_TIME_UTC_MISSING_USING_IMPORTED_AT` warning stays as a permanent alarm to catch future stream additions that introduce yet another field name.

**Acceptance gate:** post-rebuild query `db.normalized_transactions.countDocuments({source:"BYBIT", blockTimestamp:{$gte: ISODate(today)}})` must equal 0 (no Bybit normalized rows stamped "today" by accident).

### 7. N10 — `FUNDING_HISTORY` is the FUND wallet log; FH/Earn principal rows are the FUND-side leg of FUND↔EARN transfers (route them to FUND, not EARN)

**Problem (observed runtime regression after N9 rollout):**
After the N9 timestamp fix the dashboard reported `BYBIT:UID:FUND` with **phantom non-USDT balances**: USDC 1 585, MNT 1 203, LDO 337, LINK 17, ONDO 400, LTC 0.76, etc. — assets that the user's truth table places exclusively in the EARN bucket. Total FUND ledger inflation: ~$7 700.

**Root cause (raw evidence, not assumption):**
Inspecting `integration_raw_events.payload` for `FUNDING_HISTORY` rows reveals `showBusiType="fundingAccountRecord*"` (e.g. `fundingAccountRecordEarn`, `fundingAccountRecordFlexSavingInterestDistribution`). Per Bybit's own naming, `/v5/account/transaction-log` is the **canonical FUND wallet log**. Every FH row — regardless of `showBusiTypeEn` category — is a FUND-side state change. The pre-N10 code routed FH/Earn rows to `BYBIT:UID:EARN` (via `inferFundingHistorySubAccount`), which misattributed the FUND-side debit leg of FlexSavings/Launchpool subscriptions to EARN. EARN was then independently credited by the authoritative `EARN_FLEXIBLE_SAVING` stream, producing the apparent "double-count" and motivating a wrong suppression hypothesis.

The first attempted N10 fix (suppress FH/Earn INTERNAL_TRANSFER rows) silenced EARN double-counts but left FUND with the inflows from earlier deposits / convertions / withdrawals **without** the matching debits when those funds were transferred into Earn — phantom FUND balances.

**Decision (paired-leg model):** in `BybitExtractionService.inferFundingHistorySubAccount`:

- Default for all FH rows: `BYBIT:UID:FUND` (was: `EARN` for `Earn`-typed rows).
- Carve-outs for FH/Earn rows that **logically credit the EARN bucket without a FUND counterpart**:
  - `REWARD_CLAIM` rows (`Flexible Savings Interest Distribution`, `Launchpool Yield`, `Fixed Savings Interest Distribution`, `On-Chain Earn Rewards Distribution (Bonus)` — the last reclassified from `INTERNAL_TRANSFER` to `REWARD_CLAIM` in `mapFundingHistoryCanonicalType`): route to EARN. Interest / yield auto-compounds into the Earn position; the FH log row is a notification, not a FUND balance change.
  - `On-chain Earn subscription` with **`ioDirection=I`** (positive `quantityRaw`): route to EARN. Off-chain origin (e.g. user deposited ETH from external wallet to mint CMETH directly into the Earn product); the receipt token never lands on FUND wallet balance.
- All other FH/Earn rows route to FUND, including:
  - `On-chain Earn subscription` with **`ioDirection=O`** (negative `quantityRaw`): FUND-side debit when a Bybit-held liquid-staking token (METH / CMETH / BBSOL) is moved from FUND into the on-chain Earn product; pairs with the matching `Mint` row that credits FUND with the receipt token.
  - `On-chain Earn redemption` (`ioDirection=I`): FUND credit when the principal returns to FUND.
  - Manual FlexSavings Subscribe / Redeem, Launchpool Subscribe / Withdraw, Auto-Earn (`Easy Earn | Flexible (Auto-Earn)`), Fixed Savings Principal Redemption: FUND debit / credit, paired with the authoritative `EARN_FLEXIBLE_SAVING` leg on EARN.

**Result:** for every FUND↔EARN principal flow there are now consistent legs on the correct sub-account, and the existing `TransferReplayHandler` pairs them via the `bybit-econ-v1:` correlationId added in cycle/5 N3.

**Acceptance gates (cycle/5 closeout):**

- FUND ledger contains only assets the user actually holds in the funding wallet (live API: only dust per `/v5/asset/transfer/query-account-coins-balance accountType=FUND`).
- EARN ledger per-asset totals match `/v5/earn/position category=FlexibleSaving` within rounding tolerance.
- No (asset, sub-account) pair where the closing `quantityAfter` exceeds the live API value by > 5% **for assets observed in the live API**.

**Known residual (carried to N11):** assets that left Bybit via Earn-product withdrawals (e.g. CMETH / BBSOL receipt-token withdrawals through Bybit's on-chain liquid-staking redemption flow) are not currently captured by any of the active streams (`FUNDING_HISTORY`, `WITHDRAWAL`, `EARN_FLEXIBLE_SAVING`, `INTERNAL_TRANSFER`, `UNIVERSAL_TRANSFER`, `TRANSACTION_LOG`, `EXECUTION_*`). This produces phantom liquid-staking receipt positions on the EARN sub-account for users who have unstaked off Bybit. Tracking endpoint is suspected to be `/v5/earn/order` with `category=OnChain` filter or a dedicated on-chain earn redemption log — to be confirmed and added as a new ingestion stream in a follow-up cycle.

### 8. N12 — `maw_deduct_transfer_*` is a withdrawal mirror, not a real internal transfer

**Problem (observed runtime regression after N10 rollout):**
After the N10 routing fix, the dashboard still over-reported FUND by ~$5k with phantom USDC 1 585, USDT ~200, MNT 1 528, SOL 1.98, DOGE 511, METH 0.668, etc. The user's truth places only USDT ≈ 59.6 on FUND.

**Root cause (raw evidence from `integration_raw_events`):**
Every external withdrawal initiated from `UNIFIED` produces a parallel synthetic row in `/v5/asset/transfer/query-inter-transfer-list` whose `transferId` follows the pattern `maw_deduct_transfer_186_<uuid>` and has `fromAccountType=UNIFIED, toAccountType=FUND`. This is Bybit's internal accounting mirror — it represents the UNIFIED→FUND leg that Bybit applies right before the external transfer leaves FUND. Concretely, for a single withdrawal of `X` asset:

| Order | Stream | Effect on our ledger | Reality |
|---|---|---|---|
| `T+0` | `EXECUTION_SPOT` / earlier event | UTA `+X` | User has `X` on UTA |
| `T+0.785s` | `TRANSACTION_LOG` `TRANSFER_OUT` (id suffix `maw_deduct_transfer_*`) | UTA `-X` (basis-relevant) | UTA balance now `0` |
| `T+0s` | `FUNDING_HISTORY/Withdraw` | FUND `-X` → **SHORTFALL** (FUND was `0`) | External wallet gains `X` |
| `T+1s` | `INTERNAL_TRANSFER` `maw_deduct_transfer_*` | FUND `+X` (basis-relevant CARRY_IN) | **phantom** — Bybit's internal accounting only |

The `INTERNAL_TRANSFER` row runs **after** the `FH/Withdraw` (in chronological order it is 1 s later). The withdrawal therefore produces a `quantityShortfallDelta=X` instead of a real debit, and the subsequent maw_deduct credit `+X` becomes a permanent phantom on FUND.

**Decision:** in `BybitExtractionService.extractInternalTransfer` (and `extractUniversalTransfer`, defensively), when the row's `providerEventKey` starts with `maw_deduct_transfer_`, the row is a withdrawal-companion mirror — **`basisRelevant=false`**. The corresponding `TRANSACTION_LOG/TRANSFER_OUT` (same `maw_deduct_transfer_*` suffix) keeps cycle/5 N5 basis-relevant behavior so UTA is debited cleanly; the FH/Withdraw remains the authoritative external-out record on FUND (it now produces a `quantityShortfallDelta` rather than a real debit — accepted, FUND legitimately did not hold the asset). The umbrella balance for the user is correct (UTA − X, FUND = 0).

**Acceptance gate:** post-rebuild, no `INTERNAL_TRANSFER` `maw_deduct_transfer_*` row contributes to FUND ledger `quantityAfter`; aggregate FUND `quantityAfter` per asset matches live `/v5/asset/transfer/query-account-coins-balance accountType=FUND` within rounding tolerance.

### 9. N13 — Liquid-staking pairs that cross sub-accounts must not be collapsed into a single STAKING_DEPOSIT

**Problem:**
`BybitTradePairer.findLiquidStakingCounterLeg` matches two FH/Earn legs of the same `bybitDescription` ("On-chain Earn subscription") within a family equivalence (METH / CMETH / ETH all `FAMILY:ETH`) and `buildStakingPair` collapses them into a single `NormalizedTransaction` of type `STAKING_DEPOSIT`. The transaction stores ONE `walletAddress` (the anchor's). When the two legs are on different sub-accounts — `METH outflow` on `BYBIT:UID:FUND` and `CMETH inflow` on `BYBIT:UID:EARN` per the N10 routing — both flows replay onto the anchor's wallet only. The METH outflow leg therefore never debits FUND (FUND METH remains a permanent phantom worth ~$1.5k per ~0.66 METH).

**Decision:** in `BybitNormalizationService.normalizeLiquidStakingRow`, after `findLiquidStakingCounterLeg` returns a candidate, verify that `row.walletRef == pair.walletRef`. If they differ, **do not pair**: instead normalize each leg independently via `builder.buildMappedRow` (each becomes a standalone `INTERNAL_TRANSFER`, with its `walletRef` preserved from extraction).

Quantity balance per sub-account is correct after the fix. Cross-family basis carry (METH FUND → CMETH EARN) is **out of scope** for cycle/5 closeout; it requires either per-`Flow` `walletAddress` on `NormalizedTransaction.Flow` or a family-keyed correlation id scheme (proposal: `bybit-stake-fam-v1:UID:FAMILY:|qty|:minute-bucket`). Tracking ticket: cycle/6.

**Acceptance gate:** no `STAKING_DEPOSIT` normalized transaction whose flows reference distinct sub-accounts; aggregate FUND/EARN METH and CMETH inventories match user truth (FUND METH = 0, EARN CMETH ≈ 0 after all subscribe/redeem/withdraw cycles).

### 11. N17 — Funding History is the canonical FUND accounting anchor; chain-aware streams are continuity mirrors (REVERTS N16 part 1)

**Problem (observed runtime regression after N16 rollout):**
After N16, the dashboard still reports:
- USDT umbrella ledger end-state: 85.02 (UTA 79.99 + EARN 5.02 + FUND 0); live truth: 93.36 (UTA 93.36, FUND 0, EARN 0).
- USDT shortfall on FUND `14 106` and on UTA `6 453`. Basis was destroyed for ~20 deposits.
- Realized PnL on Bybit USDT remains `0`; total Bybit realized PnL `+$196.64` — still nowhere near the strongly negative figure expected from ~$14 k deposited and ~$0.6 k held.

**Root cause (raw evidence):**

Triple booking of the same physical on-chain deposit:

| Stream | sourceFileType | chain | txHash | canonicalType | basisRelevant (extraction) |
|---|---|---|---|---|---|
| `DEPOSIT_ONCHAIN` (`/v5/asset/deposit/query-record`) | `withdraw_deposit` | `ETH`/`ARBI`/… | present | `EXTERNAL_INBOUND` | **`false`** |
| `FUNDING_HISTORY/Deposit` | `fund_asset_changes` | `BYBIT` | null | `EXTERNAL_INBOUND` | **`true`** |
| `INTERNAL_TRANSFER:deposit_*` (auto-route FUND→UTA mirror) | `fund_asset_changes` | `BYBIT` | null | `INTERNAL_TRANSFER` | `true` (sub-account carry) |

All three rows reference the same Bybit-side `transferId` (the chain-aware row links via `txHash`). The design intent per `extractChainDeposit` is: *"FH Deposit is FUND accounting anchor; on-chain row is for hash continuity only."* That makes FH/Deposit the **single** basis-acquiring row and DEPOSIT_ONCHAIN a continuity mirror.

However, `BybitNormalizationService.isTransferShadowRow` matched every `fund_asset_changes` + `chain=BYBIT` + `EXTERNAL_INBOUND/Deposit` row (i.e., **every** FH/Deposit), routed it through `normalizeTransferShadowRow`, found the chain-aware `DEPOSIT_ONCHAIN` sibling via `BybitTransferShadowPairer`, and called `markTransferShadowExcluded` — which sets `excludedFromAccounting=true`, reverts flow role to `TRANSFER`, and clears pricing. The intended anchor was therefore **excluded**. On the chain-aware side, `DEPOSIT_ONCHAIN` ships with `basisRelevant=false` (correct), so the builder also marks it excluded (N1/N5 `BYBIT_BASIS_IRRELEVANT`). Net effect: **zero** basis-acquiring rows survive normalization → `quantityShortfallAfter` accumulates on every later disposal. The symmetric path destroyed FH/Withdraw basis disposal too.

N16 part 1 attempted to make the `INTERNAL_TRANSFER:deposit_*` auto-route mirror the canonical anchor (rewrite to `EXTERNAL_INBOUND/Deposit` + `basisRelevant=true`). That partially worked (13 of 20 USDT deposits survived) but:
- It double-counted with the surviving FH/Fiat anchors (3 events × $2 000) → 16 events totalling 9 912 USDT acquired, still ~3 395 USDT below real-life 13 307 USDT.
- After the reclassification, the auto-route mirror also matched `isTransferShadowRow` (new `canonicalType="EXTERNAL_INBOUND"` + `bybitType="Deposit"`), causing the shadow pairer to silently exclude many of them as well.
- The deposit_* row is semantically an **internal FUND→UTA carry**, NOT a second external acquisition. Treating it as external acquisition is a category error — even when basis accidentally lands at UTA instead of FUND, total inventory is off by the carry amount, and any later FUND-side disposal (e.g., on-chain withdraw via FH/Withdraw on FUND) crashes against zero basis.

**Decision (two coupled fixes):**

1. **Revert N16 part 1** in `BybitExtractionService.extractInternalTransfer` / `.extractUniversalTransfer`: remove the `reclassifyExternalDepositAutoRouteIfNeeded` call. `deposit_*` auto-route mirrors keep `canonicalType="INTERNAL_TRANSFER"` and behave as a FUND→UTA sub-account carry (basis-relevant). The carry pairs with the FH/Internal_transfer / TRANSACTION_LOG / UNIVERSAL_TRANSFER counterpart via `bybitInternalTransferEconomyCorrelationId` (cycle/5 N3) so the basis acquired by the FH/Deposit anchor on FUND flows to UTA cleanly.

2. **Disable FH-as-shadow** in `BybitNormalizationService.isTransferShadowRow`: the method now returns `false` unconditionally. `FUNDING_HISTORY/Deposit` and `FUNDING_HISTORY/Withdraw` go through `BybitCanonicalTransactionBuilder.buildMappedRow` normally, which (with N16 part 2 kept in place) emits `EXTERNAL_TRANSFER_IN` with `role=BUY` and `EXTERNAL_TRANSFER_OUT` with `role=SELL`. The pricing stage stamps `unitPriceUsd` (≈ $1 for stablecoins; CoinGecko historical otherwise) and AVCO `applyBuy` / `applySell` ACQUIRE / DISPOSE basis at market price. The chain-aware `DEPOSIT_ONCHAIN` and `WITHDRAWAL` rows (`basisRelevant=false` set by `extractChainDeposit` / `extractWithdrawal`) continue to be excluded via the existing N1/N5 `BYBIT_BASIS_IRRELEVANT` rule in the builder — they remain pure continuity mirrors for hash linkage.

**Status semantics:**
- FH/Deposit / FH/Withdraw → `EXTERNAL_TRANSFER_IN/OUT`, role `BUY/SELL`, status `PENDING_PRICE` until pricing → `CONFIRMED`. AVCO ACQUIRE / DISPOSE at market price.
- DEPOSIT_ONCHAIN / WITHDRAWAL → `EXTERNAL_TRANSFER_IN/OUT`, role `BUY/SELL` in flow shape, but `excludedFromAccounting=true` with reason `BYBIT_BASIS_IRRELEVANT` (no AVCO impact).
- `INTERNAL_TRANSFER:deposit_*` → `INTERNAL_TRANSFER`, role `TRANSFER`, basis-relevant FUND→UTA carry; pairs via N3 correlation.

**Result (expected after rebuild):**
- Each on-chain deposit acquires basis exactly **once** (FH/Deposit anchor on FUND wallet).
- Each on-chain withdrawal disposes basis exactly **once** (FH/Withdraw anchor on FUND wallet) → realized PnL crystallises.
- Total Bybit USDT acquired = 13 307.56 (matches user-confirmed cumulative deposits).
- Total Bybit USDT disposed via external withdrawals = 6 492.94 (matches user-confirmed cumulative withdrawals).
- Realized PnL turns appropriately negative once disposals < cost basis are crystallised.

**Acceptance gates:**
- `db.bybit_extracted_events.countDocuments({sourceStream: "INTERNAL_TRANSFER", providerEventKey: /^deposit_/, canonicalType: "EXTERNAL_INBOUND"}) == 0` (revert N16 part 1).
- For every (uid, asset, qty, minute) USDT external deposit, exactly one ledger point with `normalizedType="EXTERNAL_TRANSFER_IN"` and `basisEffect="ACQUIRE"` exists on `BYBIT:{uid}:FUND`.
- `db.asset_ledger_points.aggregate([{$match: {assetSymbol: "USDT", walletAddress: /^BYBIT:33625378:FUND/, normalizedType: "EXTERNAL_TRANSFER_IN", basisEffect: "ACQUIRE"}}, {$group: {_id: null, qty: {$sum: "$quantityDelta"}}}])` totals **13 307.56**.
- `BYBIT:33625378:FUND` USDT `quantityShortfallAfter` returns to `0` (or near-zero rounding) — no phantom disposals.
- Total Bybit umbrella realized PnL for USDT < 0 (or near zero if all USDT was traded at exactly $1; not strictly required for USDT, but the disposal path is observable and correct).

### 10. N16 — External Bybit transfers must acquire / dispose basis at market price (BUY / SELL), not transfer-match (part 2 kept; part 1 SUPERSEDED by N17)

**Problem (observed runtime regression after N15 live-balance clamp rollout):**
- Bybit `USDT` umbrella ledger end-state: 85.02 (UTA 79.99 + EARN 5.02 + FUND 0).
- Bybit live API: 93.36 (UTA only; FUND 0; EARN 0). Δ +8 USDT not directly the bug, see below.
- **Crucial signal:** `BYBIT:33625378:FUND` carries **`quantityShortfallAfter = 14 106 USDT`** and `BYBIT:33625378:UTA` carries **`quantityShortfallAfter = 1 058 USDT`** at the latest replay point — basis was released for **>$15 k of USDT-equivalent disposals** for which the replay engine had no acquired basis.
- Reported realized PnL `+$28` on Bybit USDT (essentially zero) and `+$641` total, while the user actually deposited **13 307.56 USDT (≈ $13.3 k)** plus 1 474 USDC, 4.46 ETH, 1 988 MNT, 2 125 USDE, etc. from off-platform sources (verified via paginated `/v5/asset/deposit/query-record` 2024-01 → today). Disposed/withdrew the bulk of it. Expected realized PnL: strongly negative; observed: ≈ flat. The conservation-of-basis ledger should crystallise the loss as either realized (assets sold below cost) or unrealised on current holdings — instead it silently dropped basis on every external inflow and outflow.

**Root cause (raw evidence):**

Bybit auto-routes every on-chain deposit through `/v5/asset/transfer/query-inter-transfer-list` with a `providerEventKey` starting `deposit_` (FUND → UNIFIED leg crediting UTA). Per cycle/5 N1, this row was designated as the **canonical authoritative anchor** for external deposits; `DEPOSIT_ONCHAIN` and `FUNDING_HISTORY/Deposit` were both marked `basisRelevant=false` mirrors. The auto-route row itself carries `basisRelevant=true`. So far so good.

The remaining gap: `BybitExtractionService.extractInternalTransfer` keeps `canonicalType="INTERNAL_TRANSFER"` for these rows, and `BybitCanonicalTransactionBuilder.mappedFlows` then maps `EXTERNAL_TRANSFER_IN/OUT` and `INTERNAL_TRANSFER` to `NormalizedLegRole.TRANSFER`. The replay engine's `TransferReplayHandler.applyTransfer` walks the transfer-pending queue looking for a matching `CARRY_OUT` counterpart leg (intra-Bybit sub-account transfer or bridge). For genuine external deposits there is no counterpart — the source wallet is off-platform. So:

1. Inbound deposit (e.g., 930.4 USDT credited to UTA from on-chain) is queued as `pending inbound` → quantity increases but uncovered (no basis).
2. Subsequent disposals (SWAP → USDT consumed) consume the uncovered quantity → `quantityShortfallAfter` accumulates; AVCO can't compute realized PnL because basis is undefined.
3. The same applies to `EXTERNAL_TRANSFER_OUT` (FH/Withdraw): role=TRANSFER goes through transfer-matching, finds nothing, applies `applyUnknownTransfer` → basis silently dropped, no realized PnL.

Cumulative effect: 13 307 USDT of deposit basis missing, 6 505 USDT of withdrawal basis silently dropped. Realized PnL stays near zero because basis was never created. Tens of thousands of dollars of true economic gain/loss accumulate as `quantityShortfallAfter` instead of `realisedPnlDeltaUsd`.

A second consistency issue: on-chain classifier already maps `EXTERNAL_TRANSFER_IN/OUT` to `NormalizedLegRole.BUY/SELL` (`OnChainClassificationSupport.roleForType`). Bybit canonical builder used a different convention (`TRANSFER`), producing an inconsistent AVCO model for the same canonical type.

**Decision (two coupled fixes):**

1. **`BybitExtractionService.extractInternalTransfer` / `.extractUniversalTransfer`** — after the standard extraction, call `reclassifyExternalDepositAutoRouteIfNeeded(rawEvent, event)`: if `providerEventKey` starts with `deposit_` and `quantityRaw > 0`, override `canonicalType = "EXTERNAL_INBOUND"`, `bybitType = "Deposit"`, `basisRelevant = true`. This makes the canonical anchor for external on-chain deposits use the same canonical type as `FUNDING_HISTORY/Fiat` and on-chain `EXTERNAL_TRANSFER_IN` events.

2. **`BybitCanonicalTransactionBuilder.mappedFlows`** — emit `NormalizedLegRole.BUY` for `EXTERNAL_TRANSFER_IN` and `NormalizedLegRole.SELL` for `EXTERNAL_TRANSFER_OUT`, matching the on-chain convention. The pricing stage (`PriceableFlowPolicy.requiresMarketPrice`) already prices BUY/SELL flows; the AVCO engine (`GenericFlowReplayEngine.applyBuy` / `applySell`) creates basis = qty × market_price and crystallises realized PnL = (sale_price − avco) × qty.

**Status semantics:** external transfers transition `CONFIRMED → PENDING_PRICE` until the pricing stage stamps `unitPriceUsd`. For continuity-linked bridge transfers (`continuityCandidate=true` + `correlationId`/`txHash`), `PriceableFlowPolicy.isContinuityPrincipal` short-circuits pricing and the basis flows via the existing continuity bucket / transfer-pending mechanism — no behavioural change for that path. The shadow-custody linking path (`BybitBridgeLinkService.applyExternalCustodyContinuity`) explicitly resets flow role to TRANSFER and clears prices — preserved.

**Result:**
- Every external deposit creates basis at market price (≈ $1 for stablecoins; CoinGecko historical for others). Subsequent disposals release basis cleanly → no shortfall accumulation.
- Every external withdrawal disposes basis at market price → realized PnL = market − avco. For an asset bought at $1 and withdrawn at $1 (USDT), realized PnL ≈ 0. For ETH bought at $2 300 and later withdrawn at $1 800, realized PnL = -$500 × qty. The strongly negative realized PnL the user expects from ~$14 k deposited and ~$0.8 k remaining is now actually crystallised in the ledger (the missing ~$13 k splits between realized loss on assets sold below cost and net basis carried out via withdrawals that subsequently sold on-chain at a loss).
- On-chain and Bybit `EXTERNAL_TRANSFER_IN/OUT` share the same BUY/SELL convention.

**Acceptance gates (cycle/5 closeout):**

- Post-rebuild, `db.asset_ledger_points.aggregate([{$match:{walletAddress: /BYBIT:33625378/i, quantityShortfallAfter: {$gt: 0}}}, {$group: {_id: "$walletAddress", maxShortfall: {$max: "$quantityShortfallAfter"}}}])` returns no rows with `maxShortfall > 5` per (asset, sub-account) for assets present in the live API.
- Realized PnL sum across all assets on Bybit reflects actual disposal economics — for the audited session (where the user transferred ~$14 k in, retained ~$0.8 k umbrella, withdrew the rest), the realized + unrealised total must equal `deposits_in − withdrawals_out − current_value` within rounding tolerance.
- 45 `DEPOSIT_ONCHAIN`-paired `INTERNAL_TRANSFER:deposit_*` rows normalize to 45 `NormalizedTransactionType.EXTERNAL_TRANSFER_IN` (not `INTERNAL_TRANSFER`) for integration `BYBIT-33625378`.
- 15 `FUNDING_HISTORY/Withdraw` USDT rows produce ledger points with `basisEffect ∈ {ACQUIRE, DISPOSE}` (not `UNKNOWN`); `realisedPnlDeltaUsd` is populated when sale price ≠ acquired AVCO.

### 11. N18 — Continuity linking for cross-wallet (on-chain ↔ Bybit) transfers requires FH-anchor hydration + universe matching + status/precision tolerance

**Problem (observed runtime regression after N17 supersession of N16-part-1):**
- N17 made `FUNDING_HISTORY/Deposit` the canonical basis-acquiring anchor for FUND-side deposits and reaffirmed the N16-part-2 convention (`EXTERNAL_TRANSFER_IN → BUY`, `EXTERNAL_TRANSFER_OUT → SELL`). Replay shortfall on the FUND sub-account fell to ≈ 0, the umbrella USDT balance reconciled, and dashboard portfolio value dropped from $33.6 k to ~$11 k.
- But realized PnL stayed *positive* (+$1 087 on the 0x1a87 wallet) when the user had deposited ~$14 150 to Bybit against a current ~$11 100 balance — i.e., the expected sign of realized PnL is *negative*. Root-cause query showed the offender: on-chain `EXTERNAL_TRANSFER_OUT` events to Bybit deposit addresses (CMETH +$1 571, USDC +$88, ETH/MNT, etc.) were crystallising fresh realized PnL because the AVCO engine treated them as outright sales — the cross-wallet continuity link to the Bybit FH-anchor that should have carried basis silently failed for every pair.

**Root causes (independent, all required to be fixed for continuity to fire):**

1. **FH-anchor missing chain metadata.** `BybitExtractionService.extractFundingHistory` did not populate `txHash` / `networkId` / `chain` on the `BybitExtractedEvent` (the FH endpoint omits those, the auto-route row carries them). `BybitTransferContinuityRepairService.repair()` queries `findAllByTxHashAndNetworkIdAndSource(...)` so an FH-anchor without `txHash` is invisible to the matcher.
2. **Backfill segment ordering.** `BybitBackfillSegmentPlanner` schedules `FUNDING_HISTORY` segments before `DEPOSIT_ONCHAIN`/`WITHDRAWAL`. Initial hydration during extraction therefore runs with an empty chain-side `bybit_extracted_events` table and finds no sibling to read `txHash` from.
3. **`AccountingUniverseService.shareUniverseMembers` reference mismatch.** `UserSession.universe.members` stores Bybit wallets as `BYBIT:<uid>` (no sub-account suffix), but normalized transactions write `BYBIT:<uid>:FUND` / `BYBIT:<uid>:UTA` / `BYBIT:<uid>:EARN`. A direct `$all`-style membership check returned no overlap → pair rejected at `isPairable`.
4. **`PENDING_PRICE` rejected by linker.** N17 set FH-anchor status to `PENDING_PRICE` until the pricing job stamps `unitPriceUsd`. Linking runs *before* pricing per the gate service. `isRepairableBybitStatus` only accepted `CONFIRMED`/`NEEDS_REVIEW` → every FH-anchor was filtered out.
5. **Strict 1×10⁻⁶ quantity tolerance.** Bybit FH rows carry full ~15-digit precision (`0.862092260317885`); on-chain decoded rows use the token's declared decimals (typically 8 → `0.86209226`). The pre-existing `QTY_MATCH_TOLERANCE` rejected the difference and hydration / pairing both failed for non-stablecoin assets.

**Decision (five coupled fixes; all required, none individually sufficient):**

1. **Hydrate FH events at extraction time when possible** (`BybitExtractionService.extractFundingHistory` calls `hydrateFundingHistoryDepositFromOnChain` / `hydrateFundingHistoryWithdrawFromOnChain`) **and re-hydrate during normalization** (`BybitNormalizationService.normalize` calls `BybitExtractionService.hydrateFundingHistoryFromOnChainSibling(row)`). Re-hydration during normalization is the load-bearing path because of cause (2): by the time normalization runs, all `DEPOSIT_ONCHAIN` / `WITHDRAWAL` rows are present in `bybit_extracted_events` and the sibling lookup succeeds. Hydration only sets `txHash` / `networkId` / `chain` / `timeUtc` when present on the chain sibling; quantity / asset / sub-account are never overwritten.
2. **Relaxed quantity tolerance for FH ↔ chain sibling matching** (`BybitExtractionService.quantitiesMatchWithChainPrecision`): `abs(L - R) ≤ max(1×10⁻⁷, 5×10⁻⁴ × max(L, R))` — absorbs the chain-side 8-decimal truncation without admitting genuinely different transfers (largest realistic same-token bridge has ≥ 6 significant digits of margin between distinct events). Used in `findMatchingChainDepositRow` and `findMatchingChainWithdrawalRow`; the linker `BybitTransferContinuityRepairService.quantitiesCompatible` keeps the 5×10⁻⁴ relative tolerance independently.
3. **Universe membership matching tolerates suffixes** (`AccountingUniverseService.shareUniverseMembers`): the query now generates `bybitRefCandidates(walletRef)` = `{<original>, <root-without-suffix>}` for any `BYBIT:<uid>(:[A-Z]+)?` input and matches against `members` with `$or` over `$all` clauses. So `BYBIT:33625378:FUND` matches a universe row containing `BYBIT:33625378` and vice versa.
4. **`isRepairableBybitStatus` accepts `PENDING_PRICE`** (`BybitTransferContinuityRepairService`). Without this, after N17 every FH-anchor remains unpaired until pricing runs — but pricing for a `BUY`/`SELL` flow then crystallises a fresh basis at market, producing the very phantom step-up the linker exists to eliminate. Linking *must* run before pricing for FH-anchors.
5. **Symmetric Bybit-side retag on pair** (`BybitTransferContinuityRepairService.retagBybitPrincipalFlowsForContinuity`, mirror of the existing `retagOnChainPrincipalFlowsForContinuity`): once a pair is established, demote the Bybit FH-anchor principal flow from `BUY`/`SELL` to `TRANSFER` and clear `unitPriceUsd` / `valueUsd` / `priceSource` / `avcoAtTimeOfSale` / `realisedPnlUsd`. With both sides as `TRANSFER` the replay engine routes the move through `TransferReplayHandler.applyTransfer` and the on-chain leg's AVCO crosses unchanged into the Bybit pool — realized PnL on the pair = 0, which is correct (the user is moving their own asset between their own wallets).

**Operational invariants (post-N18):**

- `db.normalized_transactions.find({source: "ON_CHAIN", continuityCandidate: true, correlationId: /^BYBIT:/}).count()` ≥ the count of true cross-wallet bridge events present in the session. After cycle/5 close-out rebuild for session `df5e69cc...`: 31 paired (16 inbound, 15 outbound).
- Realized PnL contribution from `EXTERNAL_TRANSFER_*` types (both sources) trends to ≈ 0 (only the residual minority where on-chain price ≠ Bybit price at the same `txHash` instant, plus the un-bridgeable transfers to third-party venues that legitimately are sales).
- Backend dashboard summary for the audited session converged from `Portfolio $33.6 k, Realized +$1.1 k` to `Portfolio $11 012, Realized -$348.9` after applying N18 — value matches live truth ($11.1 k umbrella) and sign of realized PnL matches the deposit-vs-balance arithmetic.

**Known follow-ups (not in N18):**

- **BORROW → ACQUIRE without liability tracking.** Borrows from Aave / Bybit margin still appear as `BUY` events crediting the asset pool without an offsetting liability row, so a *un-repaid* loan inflates equity by the borrowed notional. The cycle-5 audited session has all loans closed so the bug is not load-bearing for current numbers, but future sessions with outstanding positions will need a liability ledger. To be addressed in a follow-up ADR.
- **Bybit-side retag does not always persist.** When pricing or a later normalizer batch re-saves the FH-anchor by ID after the linker, the role/flow-clearing changes from `retagBybitPrincipalFlowsForContinuity` can be overwritten. The on-chain side stays correctly retagged so the pair's realized PnL is approximately right (the on-chain dispose-as-TRANSFER doesn't crystallise PnL), but the Bybit side may then look like an `ACQUIRE` at market. Mitigation: idempotent `BybitTransferContinuityRepairService.repair` is safe to re-run, and the `LinkingJob` does so on every pipeline tick. A more durable fix is to gate normalizer overwrite by `continuityCandidate=true` (i.e., once linked, preserve linker fields); deferred.

## Consequences

- **GET / dashboard:** unchanged cost posture (snapshot-first); fixes are in extraction, normalization metadata, and replay.  
- **Regression discipline:** Gate 6 (identical `bybit_extracted_events` counts pre/post flag-only deploy) catches accidental re-ingestion skew.  
- **Operator flow:** destructive `full-rebuild` + sim gates in cycle/5 `blockers.md` remain the integration acceptance path.

## Alternatives considered

- **Keep FH/Deposit basis-relevant** while also crediting UTA auto-route: rejected (proven double-count, N1).  
- **Persist earn snapshots as replay input:** deferred; cycle/5 proves sub-account + INTERNAL_TRANSFER sufficient for audited session.
