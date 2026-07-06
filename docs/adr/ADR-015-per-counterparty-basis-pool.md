# ADR-015 — Per-counterparty AVCO basis pool

**Status:** Proposed
**Date:** 2026-05-16
**Inputs:** `cycle-autorun/cycle-data/cycle/5/results/n19-defect-catalog.md` (D-ROUNDTRIP-BASIS, D-MISSING-NETWORKS, D-MISSING-TON, D-BYBIT-HOT-WALLET — failed-link safety net, D-STABLE-FAMILY), `n19-implementation-plan.md` §K, user decisions 2026-05-16 (no `treatAsTerminal` flag; key includes `assetFamily`; STABLE_USD family collapse)

## Context

Cycle/5 N19 identified a structural defect that no amount of counterparty-classifier improvement can solve in the binary OWN/EXTERNAL model:

> User clarification, 2026-05-16: "если из какого-то кошелька в account universe ушли деньги на другой кошелек out of account universe то он все равно должен иметь from/to потому что потом с этого-же кошелька могло вернуться средства. SOlana / TON в нашем контексте просто кошельки по которым мы не собераем полную историю транзакций. но мы должны знать что деньги уходили и приходили в /из этих сетей/кошельков."

Concretely, today's flow lifecycle (post-cycle-5 N17/N18) handles two cases:

| Case | OUT | IN | Outcome |
|---|---|---|---|
| OWN → OWN (continuity-linked) | TRANSFER, no realisation | TRANSFER, basis carried | Correct |
| OWN → EXTERNAL one-way | SELL at market, realises PnL | n/a (never returns) | Correct for terminal sinks |

It does NOT handle:

| Case | OUT | IN | Outcome |
|---|---|---|---|
| OWN → unknown X → OWN later | SELL at market_T0, realises PnL (qty × (market_T0 − ownAvco)) | BUY at market_T1, new basis (qty × market_T1) | Phantom realised PnL = qty × (market_T1 − market_T0). Asymmetric price drift produces arbitrary PnL noise. |

For an unknown TON wallet X that received 100 USDT at $0.998 and returned 100 USDT at $1.005, today's engine realises +$0.70 on the OUT and books basis $1.005 on the IN — a net basis shift the user did NOT economically experience. Across cycle/5 audit window: estimated **$500–$1,500** of phantom PnL on SOL / TON / DOGS / EOA round-trips (per `n19-defect-catalog.md#D-ROUNDTRIP-BASIS`).

The user's principle is structural: **the same counterparty address links the OUT and IN** even when WalletRadar does not index the counterparty's wallet. The address is observable on both sides of the OWN edge. Therefore, basis should be **pooled by counterparty address**, not by wallet ownership.

This ADR specifies the universal mechanism that closes D-ROUNDTRIP-BASIS. It is intentionally label-agnostic: the same code path serves OWN counterparties (members of `AccountingUniverse`), PROTOCOL/BRIDGE counterparties, unknown EOAs, and unknown contracts. The only branching is on whether `counterpartyType == GENUINE_MISSING_SOURCE` (no pool; row excluded from accounting).

The "terminal" notion of the earlier draft — a `treatAsTerminal` flag that short-circuited the pool for fiat onramps — is dropped. User decision 2026-05-16: behaviour ("passthrough" vs effectively "terminal") emerges from the data. A counterparty that never returns funds simply has a one-sided pool (`lifetimeIn = 0, lifetimeOut > 0`); a counterparty that round-trips has a balanced pool. NEC is computed from the lifetime delta — see [ADR-009](ADR-009-ownership-classification-via-universe.md) §D4. No configuration needed.

## Decision

### D1. New collection `counterparty_basis_pools`

Persisted shape (single document per `(universeId, counterpartyAddress, networkId, assetFamily)`):

```javascript
{
  _id: "<universeId>:<counterpartyAddressLower>:<networkId>:<assetFamily>",   // composite key

  universeId:           "<universe id>",
  counterpartyAddress:  "<lowercased address / canonical ref>",
  networkId:            "ETHEREUM" | "ARBITRUM" | … | "BYBIT" | "SOLANA" | "TON",
  assetFamily:          "STABLE_USD" | "ETH" | "MNT" | "TON" | "SOL" | "DOGS" | …,

  // Live state — mutated on every OUT / IN
  qtyHeld:              Decimal128,   // qty currently "in transit through" counterparty
  basisHeldUsd:         Decimal128,   // total basis backing qtyHeld
  avcoUsd:              Decimal128,   // basisHeldUsd / qtyHeld; running AVCO (0 when qtyHeld == 0)

  // Lifetime metrics — append-only for audit + conservation + NEC
  lifetimeOutQty:       Decimal128,
  lifetimeOutBasisUsd:  Decimal128,
  lifetimeInQty:        Decimal128,
  lifetimeInBasisUsd:   Decimal128,
  netCapitalDeltaUsd:   Decimal128,   // (lifetimeInBasis − lifetimeOutBasis); used directly by NEC computation

  // Provenance / debugging — informational only
  counterpartyTypeAtLastTouch: "PERSONAL_WALLET" | "CEX" | "PROTOCOL" | "BRIDGE"
                              | "UNKNOWN_EOA" | "UNKNOWN_CONTRACT",
  isMemberAtLastTouch:        Boolean,    // mirror of AccountingUniverse.isMember at the most recent touch
  observedAssetSymbols:       [ "USDT", "USDC", … ],   // raw symbols that mapped to assetFamily — audit aid

  lastTouchedAt:        Instant,
  createdAt:            Instant
}
```

Indexes:

```javascript
db.counterparty_basis_pools.createIndex(
  { universeId: 1, counterpartyAddress: 1, networkId: 1, assetFamily: 1 },
  { unique: true })
db.counterparty_basis_pools.createIndex(
  { universeId: 1, isMemberAtLastTouch: 1, qtyHeld: 1 })
```

The collection lives alongside `asset_ledger_points` in the cost-basis domain: `backend/src/main/java/com/walletradar/costbasis/domain/CounterpartyBasisPool.java` + `CounterpartyBasisPoolRepository`.

### D2. Replay rules (the universal mechanism)

Per flow processed by [GenericFlowReplayEngine](backend/src/main/java/com/walletradar/costbasis/application/replay/support/GenericFlowReplayEngine.java) in **strict chronological order** (already enforced via `accountingOrdered` queue):

```
function applyFlow(flow):
    if flow.counterpartyAddress is null or flow.counterpartyType == GENUINE_MISSING_SOURCE:
        // ADR-010 §D4 + ADR-007 §D5 — excluded from accounting
        skip

    assetFamily = resolveAssetFamily(flow.assetSymbol)   // see D4 below
    pool = lookupOrCreatePool(universeId, flow.counterpartyAddress, flow.networkId, assetFamily)
    pool.counterpartyTypeAtLastTouch = flow.counterpartyType
    pool.isMemberAtLastTouch         = AccountingUniverseService.isMember(flow.counterpartyAddress, flow.networkId)
    pool.observedAssetSymbols ∪= flow.assetSymbol

    if isOutgoing(flow.role):   // SELL, TRANSFER_OUT, BORROW_OUT, etc.
        outBasisUsd = flow.quantityDelta * ownAvcoBeforeFlow      // basis qty leaves with
        pool.qtyHeld           += flow.quantityDelta
        pool.basisHeldUsd      += outBasisUsd
        pool.avcoUsd            = pool.basisHeldUsd / pool.qtyHeld
        pool.lifetimeOutQty    += flow.quantityDelta
        pool.lifetimeOutBasisUsd += outBasisUsd
        pool.netCapitalDeltaUsd -= outBasisUsd                    // capital "out" of OWN perspective
        flow.realisedPnlUsd     = 0                               // basis held by counterparty
        // OWN-side AVCO debit qty * ownAvco (basis travels with qty)

    elif isIncoming(flow.role):   // BUY, TRANSFER_IN, REPAY_IN, etc.
        popQty           = min(flow.quantityDelta, pool.qtyHeld)
        popBasisUsd      = popQty * pool.avcoUsd                  // pool's running AVCO at pop time
        residualQty      = flow.quantityDelta − popQty
        residualBasisUsd = residualQty * flow.unitPriceUsd        // residual = new capital at market

        pool.qtyHeld           -= popQty
        pool.basisHeldUsd      -= popBasisUsd
        pool.avcoUsd            = (pool.qtyHeld == 0) ? 0 : pool.basisHeldUsd / pool.qtyHeld
        pool.lifetimeInQty     += flow.quantityDelta
        pool.lifetimeInBasisUsd += (popBasisUsd + residualBasisUsd)
        pool.netCapitalDeltaUsd += (popBasisUsd + residualBasisUsd)

        flow.realisedPnlUsd     = 0
        // OWN-side basis acquired = popBasisUsd + residualBasisUsd
        // applyBuy(qty=flow.quantityDelta, basis=ownSideBasisAcquired)

    pool.lastTouchedAt = flow.blockTimestamp
```

The mechanism is universal. There is **no branching on counterparty type or `treatAsTerminal`**:

- **OWN counterparty, `backfillEnabled=true`** (user EVM ↔ Bybit master with successful FA-001 link): pool is redundant with the per-`accountRef` AVCO already kept on both ends. It serves as a cheap audit invariant and lets [ADR-014](ADR-014-portfolio-conservation-gate.md) reconcile both sides.
- **OWN counterparty, `backfillEnabled=false`** (Solana / TON wallets): pool is the canonical state holder. MtM contribution is `qtyHeld × avcoUsd` (per [ADR-014 §D2](ADR-014-portfolio-conservation-gate.md)).
- **PROTOCOL / BRIDGE counterparty**: pool tracks the "in-transit through the protocol" qty; surfaced as protocol position on the dashboard (existing line).
- **UNKNOWN_EOA / UNKNOWN_CONTRACT (round-tripping)**: pool depletes naturally on the return leg; no phantom PnL.
- **UNKNOWN_EOA / UNKNOWN_CONTRACT (one-way)**: pool retains qty / basis indefinitely; `lifetimeIn = 0, lifetimeOut > 0`; the counterparty's `netCapitalDeltaUsd` is negative (capital left OWN, never came back). NEC ([ADR-009](ADR-009-ownership-classification-via-universe.md) §D4) picks this up directly: it is a real external sink. The "fiat onramp" semantic is emergent.

### D3. Pool key — what counts as "the same counterparty"

Key: `(universeId, counterpartyAddress (lowercased / canonicalised), networkId, assetFamily)`.

- `counterpartyAddress` is the value of [ADR-010 §D1](ADR-010-flow-level-counterparty.md) `Flow.counterpartyAddress` — already canonicalised by the builder per `n19-account-universe.json#ownAddressNormalizationRules`.
- `networkId` ensures the same address on different chains (e.g. `0x2ea8cb6f…` on ETH vs ARB) gets separate pools — basis cannot transfer across networks.
- `assetFamily` collapses economically-equivalent assets so that stable-coin rotations through the same counterparty do not generate phantom PnL.

Synthetic counterparty keys like `"BYBIT:LOAN:<orderId>"`, `"BYBIT:REWARD:<showBusiTypeEn>"`, `"FIAT:P2P"` (per [ADR-010 §D2](ADR-010-flow-level-counterparty.md), [ADR-011 §D1](ADR-011-bybit-fiat-p2p-external.md)) follow the same key shape; `assetFamily` is resolved per their `assetSymbol` as usual.

### D4. Stable family collapse

The `assetFamily` derivation is a deterministic, table-driven function of `assetSymbol`. The only family with a non-trivial collapse today is `STABLE_USD`:

| Asset family | Member assets (canonical symbol matching, case-insensitive) |
|---|---|
| `STABLE_USD` | `USDT`, `USDC`, `USDE`, `USDT0`, `DAI`, `TUSD`, `BUSD` |
| `ETH` | `ETH` (and chain-specific `WETH` variants — see §D7 for the WETH ↔ ETH wrap policy) |
| `<other>` | symbol itself; one family per non-stable asset |

Behavioural consequence: USDT → USDC → USDE rotations through the same counterparty share one pool. The OUT/IN balance nets correctly even when the user momentarily holds a different stable on the OWN side. This closes D-STABLE-FAMILY ($50–$100) for the cross-counterparty path; intra-OWN stable rotations are handled by the existing AVCO engine (out of scope for this ADR).

Wrapped natives (`WETH` ↔ `ETH`, `WMATIC` ↔ `MATIC`, etc.) are **NOT** collapsed by this ADR. They are handled by existing wrap/unwrap classifiers and remain economically distinct in the per-counterparty pool. If empirical N19 evidence later shows a stable-like phantom PnL pattern on wrapped natives, a follow-up ADR can extend the family table.

Implementation: `AssetFamilyResolver` is a Spring bean loaded from a tiny static table in code (NOT a resource file — the family is a code-level taxonomy, not data). The table lives at `backend/src/main/java/com/walletradar/costbasis/domain/AssetFamily.java` as an enum with member-symbol arrays.

### D5. Performance — in-memory mutate, persist once at end of replay

The pool MUST NOT add Mongo round-trips per flow. Mandatory pattern:

```
on full replay run:
  1. Load all pools for universeId into in-memory Map<key, CounterpartyBasisPool> (one bounded find: typical ≤ a few hundred rows).
  2. Iterate flows chronologically; mutate the in-memory map.
  3. At end of run, persist all dirty rows in a single bulk `replaceOne` upsert.
on incremental replay (single flow / small batch):
  1. Fetch only the affected pool(s) by composite key (one find per unique key, typically 1–3).
  2. Mutate; persist immediately as a single bulk write at the end of the batch.
```

In-memory replay: O(n) flows × O(1) map lookup = ~n hashmap operations. For the N19 dataset (~7k flows), the entire pool update is < 50 ms. The replay engine MUST issue exactly **one find** and **one bulk write** per full-replay run for pool maintenance (asserted by `Mongo command listener` in `GenericFlowReplayEngineTest`).

This pattern is shared with [ADR-012](ADR-012-borrow-liability-tracker.md) (`BorrowLiability`). The replay engine factors out a small `PoolBook<K, V>` interface that both implementations satisfy. Cost guardrail compliance: the pool adds zero per-flow Mongo round-trips even at the largest expected replay sizes; no SaaS / paid index needed.

### D6. Self-healing properties

The pool's correctness does NOT depend on the wallet's current ownership classification. Three concrete scenarios:

1. **Promotion (UNKNOWN_EOA → PERSONAL_WALLET)**. User confirms an unknown EOA is actually theirs. The universe is amended ([ADR-007](ADR-007-mandatory-accounting-universe-membership.md)); the next classifier pass labels new flows accordingly. The pool's `qtyHeld` / `basisHeldUsd` are untouched and remain correct (basis was tracked all along via the address). Dashboard MtM aggregator shifts the pool's `qtyHeld × avcoUsd` from "in transit" to "OWN holdings" — no replay needed.
2. **Demotion (PERSONAL_WALLET → UNKNOWN_EOA)**. User confirms an address they previously claimed as OWN is actually their friend's. The pool retains qty / basis; the MtM contribution moves out of OWN holdings into "in transit". Conservation accounts for the basis as still-in-transit. No replay needed.
3. **One-way sink emerging from data**. User repeatedly sends to a counterparty that never returns funds. The pool's `lifetimeIn = 0, lifetimeOut > 0` make it look exactly like a fiat onramp without any registry tagging. NEC = `lifetimeIn − lifetimeOut` (negative) flows directly into the conservation equation.

### D7. Reset & rebuild

Add to [scripts/avco/reset-derived.sh](scripts/avco/reset-derived.sh):

```bash
mongo "$MONGO_URI" --eval 'db.counterparty_basis_pools.deleteMany({})'
```

The collection is fully derived from `normalized_transactions.flows[]` and replays deterministically. Full rebuild via [scripts/prod-reset-rebuild-backend.sh](scripts/prod-reset-rebuild-backend.sh) seeds the collection in the chronological order specified by `accountingOrdered`.

### D8. Boundary cases

| Case | Behaviour |
|---|---|
| OUT to counterparty C of qty q1, then IN from C of qty q2 > q1 | Pool depletes to 0 over q1; residual `(q2 − q1)` is treated as new external capital at market price (per D2 IN branch with residual). |
| OUT to C of q1 at AVCO $1, OWN buys more of same asset at $1.05, then IN from C of q1 | Pool pops at $1 (its stored AVCO); OWN-side AVCO recomputes weighted across the new basis acquired via the pool pop and the prior holdings. |
| OUT to C in USDT, IN from C in USDC | Same pool key (assetFamily=`STABLE_USD`); pool tracks qty in stable-USD units. `observedAssetSymbols` captures both for audit. |
| Concurrent OUT to C and IN from C in same transaction | Processed in flow order (logIndex). First flow mutates pool; second flow reads updated state. Net effect for same-tx round-trip is 0 if balanced. |
| Zero `qtyDelta` flow (rare; defensive) | No-op; lifetime counters untouched. |
| Negative `qtyDelta` on a role marked IN (data anomaly) | Logged as `POOL_SIGN_MISMATCH`; flow re-routed to clarification. |
| Stable-family rotation where one leg is to a non-stable asset (e.g. USDT → DAI is family-stable; USDT → ETH is NOT) | If both `flow.assetSymbol` resolve to `STABLE_USD`, share the pool. Otherwise, separate pools per resolved family. |

### D9. Conservation tie-back

The pool guarantees the identity used by the conservation gate ([ADR-014](ADR-014-portfolio-conservation-gate.md)):

```
   NEC ≡ Σ_{c : isMember(c) = false} (pool[c].lifetimeInBasisUsd − pool[c].lifetimeOutBasisUsd)

   conservationDelta = (MtM_OWN + Σ_{member, backfillEnabled=false} pool[m].qtyHeld × pool[m].avcoUsd)
                       − NEC − (reportedRealisedPnl + reportedUnrealisedPnl)

   |conservationDelta| ≤ max($50, 1% × MtM)        // unified gate threshold
```

This identity is mechanically verifiable; [PortfolioConservationGate](backend/src/main/java/com/walletradar/ingestion/wallet/query/PortfolioConservationGate.java) uses it (per [ADR-014 §D2](ADR-014-portfolio-conservation-gate.md)).

## Consequences

### Positive
- D-ROUNDTRIP-BASIS closes structurally. Asymmetric price drift between OUT and IN no longer creates phantom PnL.
- OWN wallets with `backfillEnabled=false` (TON, Solana) contribute correctly to MtM without on-chain indexing — solves D-MISSING-NETWORKS / D-MISSING-TON without paid Solana / TON indexers.
- Stable-family rotations through a single counterparty no longer produce phantom PnL (closes the cross-counterparty portion of D-STABLE-FAMILY).
- Self-healing on label changes — no need to re-run replay when the user confirms / disputes wallet ownership.
- The same mechanism handles failed FA-001 cross-system links ([ADR-013](ADR-013-cex-cross-system-linking.md)) as a safety net (failed link → two one-sided pools → conservation gate fires).
- Bounded performance — one read at start of replay, one bulk write at end.
- Zero new configuration flags. The behavioural difference between "passthrough" and "terminal" emerges from data; no `treatAsTerminal` switch to maintain or get wrong.

### Negative
- One new collection; size bounded by unique `(counterpartyAddress, network, assetFamily)` tuples per universe. For the N19 dataset, ~50–200 pool rows. No paginated reads needed.
- Replay engine becomes stateful per flow (read + mutate the in-memory pool map). Test coverage burden increases; mitigated by the dedicated `CounterpartyBasisPoolTest` and the `Mongo command listener` perf assertion of D5.
- The mechanism makes label-based reasoning fuzzy: a user may forget that a non-backfilled OWN wallet's MtM depends on a pool that may have closed. Mitigated by surfacing pool state in the dashboard's outstanding-transit view (per [ADR-014 §D6](ADR-014-portfolio-conservation-gate.md) breakdown logging).
- Non-stable asset family collapse is intentionally narrow (`STABLE_USD` only). Future families (wrapped natives, restaking variants) require an additive update to `AssetFamily` enum — kept as a code change because it is taxonomy, not data.

### Migration
1. Add `CounterpartyBasisPool` domain class, `CounterpartyBasisPoolRepository`, and `AssetFamily` enum + resolver.
2. Add `CounterpartyBasisPoolService` (`lookupOrCreate`, `pushOut`, `popIn`, `getAvco`, `flushDirty`).
3. Modify `GenericFlowReplayEngine.recomputePerWalletAvco` to invoke the service per flow when `Flow.counterpartyAddress != null` and `Flow.counterpartyType != GENUINE_MISSING_SOURCE` (always, post-[ADR-010](ADR-010-flow-level-counterparty.md) §D4).
4. Add the indexes from D1.
5. Add `db.counterparty_basis_pools.deleteMany({})` to [scripts/avco/reset-derived.sh](scripts/avco/reset-derived.sh) and to the rebuild path.
6. Run [scripts/prod-reset-rebuild-backend.sh](scripts/prod-reset-rebuild-backend.sh) — pool is rebuilt chronologically from raw evidence.

## Acceptance criteria

| # | Assertion | Test |
|---|---|---|
| AC-015-1 | Round-trip test: OWN sends 100 USDT to unknown EOA `X` at AVCO $1; receives 100 USDT back from `X`. Expected: pool `qtyHeld == 0`, OWN AVCO unchanged, all flow `realisedPnlUsd = 0` | `CounterpartyBasisPoolTest` |
| AC-015-2 | Partial return: OWN sends 100 USDT at AVCO $1; receives 30 USDT back. Expected: pool `qtyHeld == 70`, `avcoUsd == 1.0`, all flow `realisedPnlUsd = 0`. For an OWN member with `backfillEnabled=false`, MtM includes 70 × $1 via the pool | `CounterpartyBasisPoolTest` |
| AC-015-3 | Asymmetric price test: OWN sends 100 USDT at $1; later buys 100 more USDT at $1.05; later receives 100 USDT from `X`. Expected: pool pops at $1 (its stored AVCO); OWN AVCO weighted to `(100 × $1.05 + 100 × $1) / 200 = $1.025` post-return | `CounterpartyBasisPoolTest` |
| AC-015-4 | Stable-family collapse: OWN sends 100 USDT to `X` at AVCO $1.00; later receives 100 USDC from `X` at market $1.005. Expected: same pool key `assetFamily=STABLE_USD`; pool depletes to qty 0; `realisedPnlUsd = 0` on both legs (no phantom $0.50 PnL from stable-rotation) | `CounterpartyBasisPoolTest` + `AssetFamilyResolverTest` |
| AC-015-5 | One-way sink emerges naturally: OWN sends 500 USDT to onramp address `0xb2a4fb…` and never receives back. Expected: pool `lifetimeOutBasisUsd = 500`, `lifetimeInBasisUsd = 0`; NEC contribution `= −500` (capital left OWN); no `treatAsTerminal` flag set anywhere | `CounterpartyBasisPoolTest` + `PortfolioConservationGateTest` |
| AC-015-6 | After full rebuild on N19 raw bundle, `db.counterparty_basis_pools.count({qtyHeld: {$gt: 0}}) > 0` (proves pool is actively tracking) | `N19FullPipelineRebuildTest` |
| AC-015-7 | `Σ qtyHeld × avcoUsd over OWN members with backfillEnabled=false ≈ Solana ($120) + TON ($266) = $386` within `max($50, 1% × MtM)` (matches `external-truth.md` out-of-scope) | `N19FullPipelineRebuildTest` |
| AC-015-8 | Conservation identity from D9 holds: `|conservationDelta| ≤ max($50, 1% × MtM)` on the N19 rebuild | `N19FullPipelineRebuildTest` |
| AC-015-9 | Replay performance: full replay over the N19 raw bundle (~7k flows) completes pool updates in `< 200 ms` (excludes pricing); verified via micro-benchmark in `GenericFlowReplayEngineTest` | `GenericFlowReplayEngineTest` |
| AC-015-10 | Replay performs exactly ONE bulk find + ONE bulk write to `counterparty_basis_pools` per full-replay run, verified via Mongo command listener | `GenericFlowReplayEngineTest` |
| AC-015-11 | Self-healing: amending the universe to mark an EXTERNAL pool's counterparty as `ON_CHAIN_WALLET` (via universe upsert, no replay) immediately moves the pool's `qtyHeld × avcoUsd` contribution from "in transit" to OWN MtM | `CounterpartyBasisPoolTest` + `PortfolioConservationGateTest` |

## References

- [cycle-autorun/cycle-data/cycle/5/results/n19-defect-catalog.md](cycle-autorun/cycle-data/cycle/5/results/n19-defect-catalog.md) — D-ROUNDTRIP-BASIS (mechanism), D-MISSING-NETWORKS, D-MISSING-TON (MtM contribution), D-BYBIT-HOT-WALLET (safety net for failed FA-001), D-STABLE-FAMILY (cross-counterparty portion).
- [cycle-autorun/cycle-data/cycle/5/results/n19-implementation-plan.md](cycle-autorun/cycle-data/cycle/5/results/n19-implementation-plan.md) §K.
- [cycle-autorun/cycle-data/cycle/5/results/n19-cross-validation.md](cycle-autorun/cycle-data/cycle/5/results/n19-cross-validation.md) §3 — conservation truth that this mechanism satisfies.
- [ADR-003](ADR-003-transfer-links-fa001.md), [ADR-007](ADR-007-mandatory-accounting-universe-membership.md), [ADR-008](ADR-008-bybit-subaccount-discovery.md), [ADR-009](ADR-009-ownership-classification-via-universe.md), [ADR-010](ADR-010-flow-level-counterparty.md), [ADR-011](ADR-011-bybit-fiat-p2p-external.md), [ADR-012](ADR-012-borrow-liability-tracker.md), [ADR-013](ADR-013-cex-cross-system-linking.md), [ADR-014](ADR-014-portfolio-conservation-gate.md).
- [backend/src/main/java/com/walletradar/costbasis/application/replay/support/GenericFlowReplayEngine.java](backend/src/main/java/com/walletradar/costbasis/application/replay/support/GenericFlowReplayEngine.java) — `recomputePerWalletAvco` entry point.
- [scripts/avco/reset-derived.sh](scripts/avco/reset-derived.sh) — collection reset.
- [scripts/prod-reset-rebuild-backend.sh](scripts/prod-reset-rebuild-backend.sh) — full-rebuild path.
