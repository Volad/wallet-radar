# ADR-010 — Flow-level counterparty on NormalizedTransaction.Flow

**Status:** Proposed
**Date:** 2026-05-16
**Inputs:** `cycle-autorun/cycle-data/cycle/5/results/n19-defect-catalog.md` (D-FLOWS-1, D-COUNTERPARTY-2), `n19-implementation-plan.md` §D

## Context

Today, `NormalizedTransaction` carries counterparty data **only at the transaction level**:

```text
NormalizedTransaction {
  walletAddress     : String
  counterpartyAddress : String   ← single, top-level
  counterpartyType    : String   ← single, top-level
  flows : List<Flow> {
    role, assetSymbol, assetContract, quantityDelta,
    unitPriceUsd, valueUsd, priceSource,
    avcoAtTimeOfSale, realisedPnlUsd, logIndex
    // NO counterpartyAddress / counterpartyType / accountRef
  }
}
```

(See [backend/src/main/java/com/walletradar/domain/transaction/normalized/NormalizedTransaction.java](backend/src/main/java/com/walletradar/domain/transaction/normalized/NormalizedTransaction.java) lines 117-164.)

This flat shape breaks down for several real cycle/5 transaction types:

### 1. Bybit Convert clusters

Each Convert event has **two legs**: OUT asset A, IN asset B. Per `n19-implementation-plan.md` §G and `n19-defect-catalog.md` D-CONVERT-LEGS, 106 Convert events spread across `funding-history` rows. The OUT leg may live on FUND ledger while the IN leg lands on UTA (Bybit auto-routes spot conversions through `Convert` between accountTypes). Without per-flow counterparty, both legs see only the transaction-level counterparty (which is necessarily a single address) and the AVCO engine cannot tell that the IN leg's basis must come from the sibling OUT leg, not from market price.

### 2. Bybit Universal Transfer master↔sub

For master→sub (or sub→master) transfers ([ADR-008](ADR-008-bybit-subaccount-discovery.md)), one canonical row carries `fromMemberId` and `toMemberId`. The transaction-level counterparty can only encode one. Per-flow counterparty is the only way to express "OWN-side debit on UTA / OWN-side credit on sub UID" cleanly.

### 3. On-chain ERC-20 multi-leg traces

When a single Ethereum transaction has 5 internal traces (e.g. Uniswap V3 router routing through multiple pools), the OWN wallet appears in multiple ERC-20 `Transfer` events with **different counterparties** per log index. Today the canonical builder collapses to a single counterparty (the router) — losing the per-pool address that the protocol classifier could use.

### 4. Multi-asset Bybit funding-history row

A FUND row like `Easy Earn | Flexible (Auto-Earn)` debit on FUND + credit on EARN sub-ledger has two flows. With only transaction-level counterparty, both flows think they face the same peer, but the OWN-side `walletRef` is different (FUND vs EARN) — which matters for the AVCO engine's per-`accountRef` book that this ADR mandates.

### Measurable consequences in production

Per `n19-defect-catalog.md`:

- D-FLOWS-1: blocker — flow schema lacks counterparty.
- D-COUNTERPARTY-2: 129 / 409 Bybit external transfers have `counterpartyAddress = null` because the address is not even reliably persisted at the transaction level.

Cumulative impact: contributes to D-COUNTERPARTY-1's 4,008 unclassified transactions and to the round-trip basis defect (D-ROUNDTRIP-BASIS) because [ADR-015](ADR-015-per-counterparty-basis-pool.md)'s pool MUST key on the per-leg counterparty.

## Decision

### D1. Extend `Flow` with three mandatory fields

```java
public static class Flow {
    private NormalizedLegRole role;
    private String assetContract;
    private String assetSymbol;
    private BigDecimal quantityDelta;
    private BigDecimal unitPriceUsd;
    private BigDecimal valueUsd;
    private PriceSource priceSource;
    private Boolean isInferred;
    private String inferenceReason;
    private ConfidenceLevel confidence;
    private BigDecimal avcoAtTimeOfSale;
    private BigDecimal realisedPnlUsd;
    private Integer logIndex;

    // NEW (this ADR):
    private String counterpartyAddress;   // mandatory, never null on CONFIRMED rows
    private String counterpartyType;      // mandatory, taxonomy of ADR-009 §D2
    private String accountRef;            // OWN-side ledger reference: "BYBIT:<uid>:<UTA|FUND|EARN>" / "evm:0x…" / "ton:UQ…" / "solana:9G…"
}
```

`counterpartyAddress` is keyed identically to a Member `ref` (per [ADR-007](ADR-007-mandatory-accounting-universe-membership.md) §D2). `counterpartyType` is one of the taxonomy values from [ADR-009](ADR-009-ownership-classification-via-universe.md) §D2. `accountRef` identifies which OWN sub-ledger the flow lives on — essential for Bybit umbrella AVCO bookkeeping per sub-account and for [ADR-015](ADR-015-per-counterparty-basis-pool.md) pool-key derivation.

Transaction-level `counterpartyAddress` / `counterpartyType` are retained for backward compatibility (dashboard summary tables already key on them) but become **derived** from the flows: when all flows share the same counterparty, the transaction-level value mirrors it; when flows diverge, the transaction-level value is `MULTI` and consumers must descend to the flow level.

### D2. Builders MUST populate per-flow counterparty during normalization

The change touches three builders.

#### Bybit canonical builder ([BybitCanonicalTransactionBuilder](backend/src/main/java/com/walletradar/ingestion/pipeline/bybit/BybitCanonicalTransactionBuilder.java))

All `counterpartyType` values below are existing entries in [`CounterpartyType`](backend/src/main/java/com/walletradar/ingestion/pipeline/clarification/CounterpartyType.java) — no new enum members are introduced. See [ADR-009](ADR-009-ownership-classification-via-universe.md) §D2 for the mapping rules.

| Source row | Flow[0] (OWN-side) | counterpartyAddress | counterpartyType | accountRef |
|---|---|---|---|---|
| `EXECUTION_SPOT` (trade) | sell-leg | `"BYBIT:<masterUid>:MATCHED_BOOK"` | `CEX` | `BYBIT:<uid>:UTA` |
| `EXECUTION_SPOT` (trade) | buy-leg  | `"BYBIT:<masterUid>:MATCHED_BOOK"` | `CEX` | `BYBIT:<uid>:UTA` |
| Convert OUT leg | sell asset | sibling leg's `tradeOrderId` resolves to `"BYBIT:<masterUid>:CONVERT:<tradeOrderId>"` | `CEX` | sender sub-ledger |
| Convert IN leg | buy asset  | same `tradeOrderId` → same key | `CEX` | receiver sub-ledger |
| Universal Transfer master↔sub | OUT | `"BYBIT:<toUid>[:LEDGER]"` | `CEX` | `BYBIT:<fromUid>:<fromLedger>` |
| `INTERNAL_TRANSFER deposit_*` (auto-route FUND→UTA) | TRANSFER | `"BYBIT:<masterUid>:FUND"` | `CEX` | `BYBIT:<masterUid>:UTA` (and the FUND side mirror via correlation id) |
| `FUNDING_HISTORY/Fiat` (P2P Purchase) | BUY | `"FIAT:P2P"` (or `"FIAT:P2P:<description>"`) | `CEX` | `BYBIT:<uid>:FUND` |
| `FUNDING_HISTORY/Deposit` | BUY | `toAddress` from `/v5/asset/deposit/query-record` | resolved via [ADR-009](ADR-009-ownership-classification-via-universe.md) §D2 (FA-001 promoted result) | `BYBIT:<uid>:FUND` |
| `FUNDING_HISTORY/Withdraw` | SELL | `fromAddress` from `/v5/asset/withdraw/query-record` | resolved via [ADR-009](ADR-009-ownership-classification-via-universe.md) §D2 (FA-001 promoted result) | `BYBIT:<uid>:FUND` |
| `FUNDING_HISTORY/Crypto Loan` | BORROW / REPAY | `"BYBIT:LOAN:<orderId>"` | `PROTOCOL` | `BYBIT:<uid>:UTA` |
| REWARD distribution | BUY | `"BYBIT:REWARD:<showBusiTypeEn>"` | `PROTOCOL` | `BYBIT:<uid>:EARN` |

#### On-chain canonical builder ([OnChainNormalizedTransactionBuilder](backend/src/main/java/com/walletradar/ingestion/pipeline/onchain))

For ERC-20 transfers: `counterpartyAddress` = the non-OWN side of the `Transfer(from, to, value)` log (lowercase); `counterpartyType` resolved via [ADR-009](ADR-009-ownership-classification-via-universe.md) §D2; `accountRef = networkPrefix + ":" + ownWallet`.

For native ETH flow: `counterpartyAddress` = the trace `from` / `to` (whichever is non-OWN).

For internal-to-external chained traces, every flow carries its own pairwise counterparty — no collapsing.

#### Synthetic / inferred rows

If a builder cannot determine a counterparty address (orphan FH row, missing chain sibling), it emits `counterpartyAddress = "UNKNOWN:" + deterministicKey(flow)` (per [ADR-009](ADR-009-ownership-classification-via-universe.md) §D3) and records `missingDataReasons += "COUNTERPARTY_ADDRESS_INFERRED"`. The flow ships, the clarification stage may later upgrade it.

### D3. AVCO engine keys by `(asset, accountRef)`, NOT `(asset, walletAddress)`

In [backend/src/main/java/com/walletradar/costbasis/application/replay/support/GenericFlowReplayEngine.java](backend/src/main/java/com/walletradar/costbasis/application/replay/support/GenericFlowReplayEngine.java), the `recomputePerWalletAvco` book switches from keying on `(asset, walletAddress)` to `(asset, Flow.accountRef)`. This is critical for the Bybit umbrella to keep separate AVCO books per sub-ledger (UTA / FUND / EARN) — matching the existing `ADR-006` §N17 contract that `FUNDING_HISTORY/Deposit` acquires basis on FUND, not UTA.

The per-counterparty pool of [ADR-015](ADR-015-per-counterparty-basis-pool.md) keys on `(Flow.counterpartyAddress, networkId, assetSymbol)`. Pool lookups happen on every flow processed by the engine.

### D4. Validation gate

Every CONFIRMED `NormalizedTransaction` MUST satisfy:

```
forAll(flow in tx.flows):
    flow.counterpartyAddress != null AND
    flow.counterpartyType    != null AND
    flow.accountRef          != null
```

Validation is added to [StatValidationService](backend/src/main/java/com/walletradar/costbasis/application/StatValidationService.java). Failing rows revert to `NEEDS_REVIEW` with `missingDataReasons += "FLOW_COUNTERPARTY_MISSING"`. The matching mongo guard:

```javascript
db.normalized_transactions.countDocuments({
  status: "CONFIRMED",
  $or: [
    {"flows.counterpartyAddress": null},
    {"flows.counterpartyType":    null},
    {"flows.accountRef":          null}
  ]
}) === 0
```

### D5. Indexing

The existing `normalized_wallet_counterparty_source_idx` (line 76-79 of `NormalizedTransaction.java`) already supports the transaction-level path. Add a sparse compound index `flows.counterpartyAddress` (already partially present via `normalized_flows_asset_contract_idx` pattern):

```java
@CompoundIndex(
    name = "normalized_flow_counterparty_idx",
    def = "{'flows.counterpartyAddress': 1, 'flows.assetSymbol': 1, 'blockTimestamp': 1}",
    sparse = true
)
```

Used by [ADR-015](ADR-015-per-counterparty-basis-pool.md)'s pool warmup at replay start (scan all flows for a given universeId, group by counterpartyAddress, build the in-memory map). Read-once at boot, not on the hot path.

### D6. Backward compatibility / read path

Existing dashboard queries that read `tx.counterpartyAddress` continue to work. When all flows have the same counterparty, the field equals that single address; when they diverge, the field equals `"MULTI"` and the frontend's transaction detail view descends into the flows array (already does so for `flows[]` display).

## Consequences

### Positive
- Each flow carries its own basis lineage. Convert / swap / sub-ledger movement is now correctly representable.
- D-FLOWS-1 and D-COUNTERPARTY-2 close: a flow with `null` counterparty cannot be CONFIRMED.
- [ADR-015](ADR-015-per-counterparty-basis-pool.md)'s pool gets its key reliably.
- Per-`accountRef` AVCO replaces the existing per-`walletAddress` book without changing the math, only the key (D3). Sub-ledger separation works without inventing a parallel data model.

### Negative
- Schema change is non-trivial: every existing CONFIRMED row in `normalized_transactions` becomes invalid (no flow-level counterparty). Requires a destructive rebuild via [scripts/prod-reset-rebuild-backend.sh](scripts/prod-reset-rebuild-backend.sh) (already part of the migration). Acceptable per the cycle/5 close-out posture.
- New sparse index marginally increases write cost on normalization. Bounded by the existing volume (~7k rows).
- Builders accumulate complexity: per-source counterparty derivation rules (D2) must be exhaustive. Mitigated by per-builder test fixtures (`BybitCanonicalTransactionBuilderTest`, `OnChainNormalizationServiceTest`).

### Migration
1. Schema-additive Java change to `Flow`. New fields are nullable in the model but enforced as non-null at the validation gate (D4).
2. Update all three builders (Bybit canonical, on-chain canonical, Bybit transfer-shadow) to emit per-flow counterparty.
3. Add new sparse index `normalized_flow_counterparty_idx` (D5).
4. Run [scripts/prod-reset-rebuild-backend.sh](scripts/prod-reset-rebuild-backend.sh) — re-extracts and re-normalizes all sources against the new builders.
5. Acceptance gate: post-rebuild, AC-010 assertions all pass.

## Acceptance criteria

| # | Assertion | Test |
|---|---|---|
| AC-010-1 | After full rebuild on N19 raw bundle, `db.normalized_transactions.countDocuments({status:"CONFIRMED", "flows.counterpartyAddress": null}) == 0` | `N19FullPipelineRebuildTest` |
| AC-010-2 | After rebuild, `db.normalized_transactions.countDocuments({status:"CONFIRMED", "flows.counterpartyType": null}) == 0` | `N19FullPipelineRebuildTest` |
| AC-010-3 | After rebuild, `db.normalized_transactions.countDocuments({status:"CONFIRMED", "flows.accountRef": null}) == 0` | `N19FullPipelineRebuildTest` |
| AC-010-4 | A Bybit Convert pair has two flows with the SAME counterpartyAddress (derived from `tradeOrderId`) and the SAME `BYBIT:LOAN:<orderId>` style key — but DIFFERENT `accountRef` when legs span FUND vs UTA | `BybitCanonicalTransactionBuilderTest` |
| AC-010-5 | An on-chain swap (`0xRouter` ERC-20 trace) has per-flow counterpartyAddress equal to the swap pool address, not the router | `OnChainNormalizationServiceTest` |
| AC-010-6 | `GenericFlowReplayEngine.recomputePerWalletAvco` produces a different basis curve for `BYBIT:<uid>:FUND` USDT vs `BYBIT:<uid>:UTA` USDT (proving per-`accountRef` keying) | `GenericFlowReplayEngineTest` |
| AC-010-7 | Sparse index `normalized_flow_counterparty_idx` exists; `db.normalized_transactions.find({"flows.counterpartyAddress": "<addr>"}).explain()` uses it | `N19FullPipelineRebuildTest` (index assertion) |
| AC-010-8 | Transaction-level `tx.counterpartyAddress == "MULTI"` when flows have distinct counterparties; equal to the shared value otherwise | `BybitCanonicalTransactionBuilderTest`, `OnChainNormalizationServiceTest` |

## References

- [cycle-autorun/cycle-data/cycle/5/results/n19-defect-catalog.md](cycle-autorun/cycle-data/cycle/5/results/n19-defect-catalog.md) — D-FLOWS-1, D-COUNTERPARTY-2.
- [cycle-autorun/cycle-data/cycle/5/results/n19-implementation-plan.md](cycle-autorun/cycle-data/cycle/5/results/n19-implementation-plan.md) §D.
- [ADR-007](ADR-007-mandatory-accounting-universe-membership.md), [ADR-008](ADR-008-bybit-subaccount-discovery.md), [ADR-009](ADR-009-ownership-classification-via-universe.md), [ADR-015](ADR-015-per-counterparty-basis-pool.md).
- [backend/src/main/java/com/walletradar/domain/transaction/normalized/NormalizedTransaction.java](backend/src/main/java/com/walletradar/domain/transaction/normalized/NormalizedTransaction.java).
- [backend/src/main/java/com/walletradar/ingestion/pipeline/bybit/BybitCanonicalTransactionBuilder.java](backend/src/main/java/com/walletradar/ingestion/pipeline/bybit/BybitCanonicalTransactionBuilder.java).
- [backend/src/main/java/com/walletradar/costbasis/application/replay/support/GenericFlowReplayEngine.java](backend/src/main/java/com/walletradar/costbasis/application/replay/support/GenericFlowReplayEngine.java).
- [backend/src/main/java/com/walletradar/costbasis/application/StatValidationService.java](backend/src/main/java/com/walletradar/costbasis/application/StatValidationService.java).
