# ADR-011 — Bybit Fiat P2P Purchase as `EXTERNAL_TRANSFER_IN` capital injection

**Status:** Proposed
**Date:** 2026-05-16
**Inputs:** `cycle-autorun/cycle-data/cycle/5/results/n19-defect-catalog.md` (D-FIAT-P2P), `n19-implementation-plan.md` §E, `n19-cross-validation.md`; user decisions 2026-05-16 (reuse existing seven-value `CounterpartyType`; no new `EXTERNAL_FIAT_P2P` enum value; "terminal" behaviour is emergent from the pool, not configured by a flag).

> Supersedes the earlier draft of this ADR that introduced `EXTERNAL_FIAT_P2P` as a new `CounterpartyType` and `EXTERNAL_TERMINAL` as a wallet-kind. Both proposed enum values are dropped per [ADR-009 §D2](ADR-009-ownership-classification-via-universe.md) (the seven existing `CounterpartyType` constants are sufficient). Fiat P2P is now classified as `CEX` against a synthetic counterparty address `FIAT:P2P`; its capital-injection semantics emerge from the per-counterparty pool of [ADR-015](ADR-015-per-counterparty-basis-pool.md).

## Context

The user manually deposits BYN (Belarusian rubles) to Bybit's Fiat-P2P market three times during the audit window, totalling 2,000 USDT credited to the FUND ledger:

| Date | USDT received | Bybit row `showBusiTypeEn` |
|---|---:|---|
| 2024-12-25 | 500 | `Fiat` (P2P Purchase) |
| 2025-01-03 | 500 | `Fiat` (P2P Purchase) |
| 2025-01-10 | 1,000 | `Fiat` (P2P Purchase) |
| **Total** | **2,000** | |

These rows are present in [`cycle-autorun/cycle-data/cycle/5/results/n19-raw/bybit-funding-history.jsonl`](cycle-autorun/cycle-data/cycle/5/results/n19-raw/bybit-funding-history.jsonl) (per `n19-defect-catalog.md` D-FIAT-P2P evidence). They represent **real fiat onramp** — the user's BYN bank balance went down and Bybit FUND USDT went up — but the current backend either drops them or routes them to `INTERNAL_TRANSFER`. The result: NEC is short by $2,000.

Phase 1 confirmed the gap arithmetically (`n19-cross-validation.md`):

| | USD |
|---|---:|
| User-claimed NEC | $14,230.60 |
| Conservative S6 derived (with Fiat P2P counted) | $14,139.51 |
| Conservative S6 derived (without Fiat P2P — current backend behaviour) | ≈ $12,140 |
| Delta attributable to Fiat P2P | **$2,000** |

This makes D-FIAT-P2P the single largest individual NEC gap among the 17 defects.

There is no on-chain leg for P2P Purchase — the BYN side never touches a blockchain. The Bybit `funding-history` row is the **only** record. The peer counterparty is an arbitrary P2P seller (a Bybit user) reachable via `userId` in some payloads; for accounting purposes the peer is irrelevant because the user paid fiat directly to that seller off-platform. Conceptually the value transfer happens entirely inside Bybit's ecosystem (BYN → P2P book → user FUND USDT), so the row anchors against a Bybit-side synthetic counterparty.

## Decision

### D1. `showBusiTypeEn == "Fiat"` (alias `"P2P Purchase"`) → canonical type `external_in_fiat_p2p`

In [BybitExtractionService.mapFundingHistoryCanonicalType](backend/src/main/java/com/walletradar/integration/bybit/BybitExtractionService.java) add the branch:

```java
switch (showBusiTypeEn) {
    // …existing cases…
    case "Fiat":
    case "P2P Purchase":
        return "external_in_fiat_p2p";
    // …
}
```

Downstream in [BybitCanonicalTransactionBuilder](backend/src/main/java/com/walletradar/ingestion/pipeline/bybit/BybitCanonicalTransactionBuilder.java), `external_in_fiat_p2p` maps to a single-flow `NormalizedTransaction`:

| Field | Value |
|---|---|
| `NormalizedTransaction.type` | `EXTERNAL_TRANSFER_IN` |
| `NormalizedTransaction.counterpartyAddress` | `"FIAT:P2P"` (or `"FIAT:P2P:<description>"` when a sub-key adds clarity, e.g. provider name from raw row; pool-key shape unchanged) |
| `NormalizedTransaction.counterpartyType` | `CEX` |
| `Flow.role` (per [ADR-010](ADR-010-flow-level-counterparty.md)) | `BUY` (matches [ADR-006 §N16/§N17](ADR-006-cycle5-bybit-stream-authority-and-earn-subaccount.md) BUY-on-external-in convention) |
| `Flow.counterpartyAddress` (per [ADR-010 §D1](ADR-010-flow-level-counterparty.md)) | `"FIAT:P2P"` |
| `Flow.counterpartyType` (per [ADR-009 §D2](ADR-009-ownership-classification-via-universe.md)) | `CEX` |
| `Flow.accountRef` | `BYBIT:<masterUid>:FUND` |
| `Flow.unitPriceUsd` | `1.0` (USDT stable peg; see D2) |
| `Flow.valueUsd` | `qty * 1.0` |
| `Flow.priceSource` | `STABLE_USD_PEG` |
| `Flow.assetSymbol` | `USDT` (or row's actual asset; in practice always USDT) |

### D2. Why `counterpartyType = CEX` for a synthetic address

`CEX` is the seven-value-enum constant that describes "the counterparty is a centralised exchange ledger account" (per [ADR-009 §D2](ADR-009-ownership-classification-via-universe.md)). The Fiat P2P book is a piece of Bybit's internal book-keeping infrastructure — the value transfer happens entirely inside Bybit's universe: the user's BYN buy-order is matched against another Bybit user's USDT sell-order, then the P2P engine credits the buyer's FUND ledger. From the cost-basis engine's perspective the source of the USDT is "Bybit" (the venue), not a chain wallet, not a protocol, not an EOA. Labelling it `CEX` keeps the dashboard summary tables coherent (Fiat P2P inflows surface alongside other Bybit-side activity) and avoids inventing a one-off `EXTERNAL_FIAT_P2P` constant that the rest of the seven-value taxonomy does not need.

The classifier walk order in [ADR-009 §D2](ADR-009-ownership-classification-via-universe.md) does not see the synthetic `"FIAT:P2P"` address — the canonical builder writes the type directly per the [ADR-010 §D2 builder table](ADR-010-flow-level-counterparty.md). This is consistent with how other Bybit-side synthetic counterparty keys (e.g. `"BYBIT:<masterUid>:CONVERT:<tradeOrderId>"`, `"BYBIT:REWARD:<showBusiTypeEn>"`) are assigned at build time rather than re-derived from `AccountingUniverseService.classify`.

### D3. Pricing — peg at $1.00 for stables; CoinGecko historical otherwise

The vast majority of Bybit Fiat P2P rows are USDT (occasionally USDC). For USDT/USDC/USDE/DAI we use the stable-USD peg ($1.00) deterministically — this avoids polling CoinGecko on the hot path and matches the cost-first principle. For any non-stable asset that appears under `Fiat` (defensive — none observed in audit data), fall back to CoinGecko historical via the existing [`CanonicalAssetCatalog`](backend/src/main/java/com/walletradar/pricing/domain/CanonicalAssetCatalog.java) pricing chain.

### D4. Capital-injection semantics emerge from a one-sided pool

Per [ADR-015 §D2](ADR-015-per-counterparty-basis-pool.md) the per-counterparty pool keyed on `(universeId, "FIAT:P2P", BYBIT, STABLE_USD)` is universal — no `treatAsTerminal` flag, no special-case branch. Each Fiat P2P inflow appears as an IN flow and pushes:

- `pool.lifetimeInQty       += qty`
- `pool.lifetimeInBasisUsd  += qty × $1.00`
- `pool.qtyHeld             -= popQty (=0 because pool was empty)`
- `pool.lifetimeOut*        unchanged`

Because the user never sells USDT back to Bybit P2P during the audit window (no `"P2P Sale"` row exists), the FIAT:P2P pool stays one-sided forever:

- `lifetimeOutBasisUsd = 0`
- `lifetimeInBasisUsd  = 2,000`
- `netCapitalDeltaUsd  = +2,000`
- `qtyHeld             = 0` (every IN is fully residual — no prior OUT to pop against)

The "terminal" notion is therefore **emergent**: the pool's lifetime asymmetry is what makes the inflow real new capital, not a configuration flag. There is no `EXTERNAL_TERMINAL` tag, no `treatAsTerminal` switch, no parallel registry — the data is the answer.

### D5. NEC contribution under the unified formula

Per [ADR-009 §D4](ADR-009-ownership-classification-via-universe.md) and [ADR-015 §D9](ADR-015-per-counterparty-basis-pool.md), Net External Capital is

```
NEC = Σ_{c : isMember(c) = false} (pool[c].lifetimeInBasisUsd − pool[c].lifetimeOutBasisUsd)
```

`"FIAT:P2P"` is NOT a member of `AccountingUniverse` (it is a synthetic Bybit-side bookkeeping handle, not a user-owned custody endpoint), so `isMember("FIAT:P2P", BYBIT) = false` and the FIAT:P2P pool contributes `+$2,000` to NEC mechanically. The same code path applies to Whitebird, Dzengi, and any future fiat onramp address — no per-provider configuration. The "fiat-onramp lifecycle" is uniform across providers because the math is uniform.

### D6. Symmetric P2P sale handling (defensive)

The Bybit API also exposes `showBusiTypeEn == "P2P Sale"` for users selling USDT back to BYN. The user has not done this in the audit window, but the same routing applies symmetrically:

```java
case "P2P Sale":
    return "external_out_fiat_p2p";
```

→ `EXTERNAL_TRANSFER_OUT`, `Flow.role = SELL`, `counterpartyAddress = "FIAT:P2P"`, `counterpartyType = CEX`, `unitPriceUsd = 1.0`. The OUT flow pushes onto the FIAT:P2P pool and pops as much qty as exists (typically zero in a buy-only history → flow is residual at market = $1; pool's `lifetimeOut*` grows). When OUTs and INs balance, NEC contribution from FIAT:P2P nets to zero — exactly the user's economic reality.

### D7. Dedup against `INTERNAL_TRANSFER` mirror

Empirically, some Bybit P2P rows produce a sibling `INTERNAL_TRANSFER` row reflecting the FUND credit (similar pattern to the `maw_deduct_transfer_*` mirror handled in [ADR-006 §N12](ADR-006-cycle5-bybit-stream-authority-and-earn-subaccount.md)). The new `external_in_fiat_p2p` branch MUST run before the existing `INTERNAL_TRANSFER` extraction so that the Fiat P2P row claims the credit and the sibling `INTERNAL_TRANSFER` row is marked `basisRelevant=false`. The pairing key:

```text
(masterUid, asset, |qty|, timestamp window ±5s, showBusiTypeEn=="Fiat") → primary
sibling INTERNAL_TRANSFER row in same window with matching qty/asset → mirror (basisRelevant=false)
```

If no `INTERNAL_TRANSFER` sibling is found, the Fiat P2P row stands alone — no error.

### D8. No retroactive on-chain hydration

Fiat P2P never has a `txHash`. The hydration logic from [ADR-006 §N18](ADR-006-cycle5-bybit-stream-authority-and-earn-subaccount.md) (`hydrateFundingHistoryDepositFromOnChain`) MUST NOT attempt to look up a chain sibling for `showBusiTypeEn=="Fiat"` rows. Add an early `return` guard in [BybitExtractionService.hydrateFundingHistoryFromOnChainSibling](backend/src/main/java/com/walletradar/integration/bybit/BybitExtractionService.java).

## Consequences

### Positive
- D-FIAT-P2P closes deterministically: NEC gains $2,000 via the universal pool mechanism. Brings independent NEC derivation from $12,140 → $14,140, within $91 of user-claimed $14,231 (per `n19-cross-validation.md` §1).
- Zero new enum values: the seven-value `CounterpartyType` taxonomy stays minimal.
- Zero per-provider configuration: any future fiat onramp (BYN, EUR, USD bank wires) lands on the same code path with a different synthetic counterparty key.
- The "terminal" semantic is observable in pool data (`lifetimeIn > 0, lifetimeOut == 0`) instead of stamped at write time, which means promotion / demotion of a counterparty is purely a label change — no replay needed.
- AVCO engine is untouched; the `BUY` path handles two extra rows.

### Negative
- One more case in the `mapFundingHistoryCanonicalType` switch; minimal but tested.
- If Bybit renames `showBusiTypeEn` in the future (rare; the field is stable), the branch must be updated. Mitigation: the alias `"P2P Purchase"` is already accepted in D1.
- The synthetic `"FIAT:P2P"` address is not visible in any registry — operators must understand that the pool surfaces in audit reports only because the canonical builder writes the address explicitly. Mitigation: the `pool.observedAssetSymbols` and `pool.counterpartyTypeAtLastTouch = CEX` fields ([ADR-015 §D1](ADR-015-per-counterparty-basis-pool.md)) make the pool self-describing.

### Migration
1. Code change to `BybitExtractionService.mapFundingHistoryCanonicalType` (D1).
2. Code change to `BybitCanonicalTransactionBuilder` per the D1 table.
3. Run [scripts/prod-reset-rebuild-backend.sh](scripts/prod-reset-rebuild-backend.sh).
4. Acceptance check: D-FIAT-P2P resolves; NEC delta = +$2,000 vs pre-fix baseline; FIAT:P2P pool exists with `lifetimeInBasisUsd ≈ 2000`, `lifetimeOutBasisUsd == 0`.

## Acceptance criteria

| # | Assertion | Test |
|---|---|---|
| AC-011-1 | After rebuild, `db.normalized_transactions.countDocuments({type: "EXTERNAL_TRANSFER_IN", counterpartyType: "CEX", counterpartyAddress: /^FIAT:P2P/}) == 3` | `N19FullPipelineRebuildTest` |
| AC-011-2 | Sum of `flows.valueUsd` over those 3 rows == `2000.00 ± 0.01` | `N19FullPipelineRebuildTest` |
| AC-011-3 | `BybitCanonicalTransactionBuilder` maps `external_in_fiat_p2p` to `EXTERNAL_TRANSFER_IN` with `Flow.role=BUY`, `Flow.counterpartyAddress="FIAT:P2P"`, `Flow.counterpartyType=CEX`, `accountRef="BYBIT:33625378:FUND"` | `BybitCanonicalTransactionBuilderTest` (using 3 fixtures from `n19-raw/bybit-funding-history.jsonl`) |
| AC-011-4 | `Flow.unitPriceUsd == 1.0` and `Flow.priceSource == STABLE_USD_PEG` for USDT Fiat P2P rows | `BybitCanonicalTransactionBuilderTest` |
| AC-011-5 | Sibling `INTERNAL_TRANSFER` row in same time window is marked `basisRelevant=false` (when one exists) | `BybitExtractionServiceTest` |
| AC-011-6 | After rebuild, total `EXTERNAL_TRANSFER_IN` USD on Bybit master = pre-fix baseline + `2000.00` | `N19FullPipelineRebuildTest` |
| AC-011-7 | The 3 Fiat P2P rows do NOT trigger `hydrateFundingHistoryFromOnChainSibling` (verified via spy / metric) | `BybitExtractionServiceTest` |
| AC-011-8 | `db.counterparty_basis_pools.findOne({counterpartyAddress: "FIAT:P2P"})` has `lifetimeInBasisUsd ≈ 2000.00`, `lifetimeOutBasisUsd == 0`, `qtyHeld == 0`, `isMemberAtLastTouch == false` | `N19FullPipelineRebuildTest` + `CounterpartyBasisPoolTest` |
| AC-011-9 | NEC under the unified formula of [ADR-015 §D9](ADR-015-per-counterparty-basis-pool.md) gains exactly `+2000.00` from the FIAT:P2P pool relative to the pre-fix baseline | `PortfolioConservationGateTest` |
| AC-011-10 | NEC derived from the rebuilt pipeline equals `14_030 ± 300` (matching `n19-cross-validation.md` §1 independent simulation) | `N19FullPipelineRebuildTest` (conservation-gate field) |

## References

- [cycle-autorun/cycle-data/cycle/5/results/n19-defect-catalog.md](cycle-autorun/cycle-data/cycle/5/results/n19-defect-catalog.md) — D-FIAT-P2P.
- [cycle-autorun/cycle-data/cycle/5/results/n19-implementation-plan.md](cycle-autorun/cycle-data/cycle/5/results/n19-implementation-plan.md) §E.
- [cycle-autorun/cycle-data/cycle/5/results/n19-cross-validation.md](cycle-autorun/cycle-data/cycle/5/results/n19-cross-validation.md) §1.
- [cycle-autorun/cycle-data/cycle/5/results/n19-raw/bybit-funding-history.jsonl](cycle-autorun/cycle-data/cycle/5/results/n19-raw/bybit-funding-history.jsonl) — raw evidence (filter `showBusiTypeEn=="Fiat"`).
- [ADR-009](ADR-009-ownership-classification-via-universe.md) — Ownership Classification via AccountingUniverse (seven-value `CounterpartyType` taxonomy).
- [ADR-010](ADR-010-flow-level-counterparty.md) — Flow-level counterparty (synthetic `"FIAT:P2P"` key shape).
- [ADR-014](ADR-014-portfolio-conservation-gate.md) — conservation gate that picks up the +$2,000 NEC contribution.
- [ADR-015](ADR-015-per-counterparty-basis-pool.md) §D2, §D9 — universal pool mechanism and unified NEC formula.
- [backend/src/main/java/com/walletradar/integration/bybit/BybitExtractionService.java](backend/src/main/java/com/walletradar/integration/bybit/BybitExtractionService.java) — `mapFundingHistoryCanonicalType` and `hydrateFundingHistoryFromOnChainSibling`.
- [backend/src/main/java/com/walletradar/ingestion/pipeline/bybit/BybitCanonicalTransactionBuilder.java](backend/src/main/java/com/walletradar/ingestion/pipeline/bybit/BybitCanonicalTransactionBuilder.java).
- [backend/src/test/java/com/walletradar/ingestion/pipeline/bybit/BybitCanonicalTransactionBuilderTest.java](backend/src/test/java/com/walletradar/ingestion/pipeline/bybit/BybitCanonicalTransactionBuilderTest.java).
- [docs/adr/ADR-006-cycle5-bybit-stream-authority-and-earn-subaccount.md](ADR-006-cycle5-bybit-stream-authority-and-earn-subaccount.md) §N16 part 2 / §N17 (BUY/SELL convention for external transfers; precedent used here).
