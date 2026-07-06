# ADR-009 — Ownership Classification via AccountingUniverse

**Status:** Proposed
**Date:** 2026-05-16
**Inputs:** `cycle-autorun/cycle-data/cycle/5/results/n19-defect-catalog.md` (D-ROUNDTRIP-BASIS, D-MISSING-NETWORKS, D-MISSING-TON), `n19-implementation-plan.md` §B, §C, §K; user decisions 2026-05-16 (reuse of existing `CounterpartyType`; `backfillEnabled` flag dimension; no separate registries for hot wallets or fiat onramps)

> Supersedes (and renames) `ADR-009-four-way-wallet-ontology.md`. The four-way ontology (INDEXED_OWN / TRACKED_OWN / EXTERNAL_PASSTHROUGH / EXTERNAL_TERMINAL) is dropped. Ownership classification reuses the existing seven-value `CounterpartyType` enum and is driven entirely by `AccountingUniverse` membership.

## Context

The pre-cycle/5 model treats counterparty ownership as a binary classification:

- `OWN` — wallet appears in the user's session settings (EVM hot wallets) or is the Bybit master UID.
- `EXTERNAL` — everything else.

Two problems force a more expressive model:

### Problem 1 — OWN wallets on networks WalletRadar does not index

The user owns wallets on networks WalletRadar does NOT fully backfill (`SOLANA`, `TON`). They appear only on the "edge" of indexed flows: 13 Bybit → Solana withdrawals to `9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG` (1.86 SOL net out + USDC/USDT), 5 Bybit → TON withdrawals to user wallets (`UQAe4Uho…`, `UQDcaquh…`, `UQB423bm…`, `UQDdb_As…`, `UQAMVoQ1X1…`). Today these are tagged `EXTERNAL_TRANSFER_OUT` → phantom capital flight → phantom Realised PnL ≈ $500–$1,500.

Conservation truth (`n19-cross-validation.md`): the value did stay within the user's universe (Solana ≈ $120, TON ≈ $266). Treating them as EXTERNAL is a structural mis-attribution.

The fix is **not** to build new indexers for those networks. It is to admit the addresses into `AccountingUniverse` and let downstream classification recognise them as OWN. The ingestion/backfill dimension is orthogonal — see `Member.backfillEnabled` in [ADR-007](ADR-007-mandatory-accounting-universe-membership.md).

### Problem 2 — round-trip basis carry through unknown counterparties

User clarification on 2026-05-16:

> "если из какого-то кошелька в account universe ушли деньги на другой кошелек out of account universe то он все равно должен иметь from/to потому что потом с этого-же кошелька могло вернуться средства"
> "SOlana / TON в нашем контексте просто кошельки по которым мы не собераем полную историю транзакций. но мы должны знать что деньги уходили и приходили в /из этих сетей/кошельков"

Concretely: value frequently leaves OWN → unknown address X → OWN later. If X is treated as terminal external, the OUT realises basis at send-price and the IN creates new capital at receive-price — phantom Realised PnL = (price drift) × qty. The system has no mechanism to recognise the round-trip even though the `from/to` addresses are observable on both legs.

The fix is mechanical, not classification-driven: a **per-counterparty basis pool** keyed on the counterparty address itself (see [ADR-015](ADR-015-per-counterparty-basis-pool.md)). The label of the counterparty (OWN, PROTOCOL, BRIDGE, EOA, …) does not branch AVCO logic; it only steers reporting.

### Why no new enum values

A previous draft of this ADR proposed a four-way ontology (`INDEXED_OWN` / `TRACKED_OWN` / `EXTERNAL_PASSTHROUGH` / `EXTERNAL_TERMINAL`) with corresponding new `CounterpartyType` constants. User decision 2026-05-16:

- The two problems share a single structural requirement: **a counterparty must be either a Member of `AccountingUniverse` (OWN) or not (EXTERNAL).** That binary is sufficient for ownership reasoning.
- The "INDEXED / TRACKED" distinction is an **ingestion concern**, not an ownership concern. It is encoded as `Member.backfillEnabled` ([ADR-007](ADR-007-mandatory-accounting-universe-membership.md) §D2) and is invisible to the cost-basis engine.
- The "PASSTHROUGH vs TERMINAL" distinction is **emergent data**, not configuration. A fiat onramp that round-trips back to the user simply has a balanced pool; one that never returns has a one-sided pool. There is no `treatAsTerminal` flag (see [ADR-015](ADR-015-per-counterparty-basis-pool.md) §D2).

The existing seven `CounterpartyType` constants in [backend/src/main/java/com/walletradar/ingestion/pipeline/clarification/CounterpartyType.java](backend/src/main/java/com/walletradar/ingestion/pipeline/clarification/CounterpartyType.java) — `CEX`, `PERSONAL_WALLET`, `PROTOCOL`, `BRIDGE`, `UNKNOWN_EOA`, `UNKNOWN_CONTRACT`, `GENUINE_MISSING_SOURCE` — cover every observed case once `AccountingUniverse` becomes the canonical OWN authority.

## Decision

### D1. The two dimensions

Ownership and indexing are independent:

| Dimension | Source of truth | Values |
|---|---|---|
| **Ownership** | `AccountingUniverse.isMember(address, network)` | `OWN` (member) / `EXTERNAL` (not member). Hard binary. |
| **Indexed?** | `Member.backfillEnabled` (only meaningful when `isMember=true`) | `true` (full backfill) / `false` (registered as OWN but no ingestion adapter runs). |

`backfillEnabled` does NOT affect counterparty classification. It only gates the backfill planner.

### D2. Counterparty classifier walk order

The replacement classifier in [backend/src/main/java/com/walletradar/ingestion/pipeline/clarification/CounterpartyResolutionService.java](backend/src/main/java/com/walletradar/ingestion/pipeline/clarification/CounterpartyResolutionService.java) walks this strict order at the flow level — first match wins:

```
1. AccountingUniverseService.classify(address, network):
     isMember=true,  memberType=ON_CHAIN_WALLET   → CounterpartyType.PERSONAL_WALLET
     isMember=true,  memberType=EXCHANGE_ACCOUNT  → CounterpartyType.CEX
     isMember=false  → continue with the existing chain below
2. Protocol registry (`protocol-registry.json`) hit
                                                  → CounterpartyType.PROTOCOL
3. Bridge registry hit (subset of protocol registry; see ADR-003 D3)
                                                  → CounterpartyType.BRIDGE
4. On-chain contract shape (has bytecode at address)
                                                  → CounterpartyType.UNKNOWN_CONTRACT
5. Externally-owned-account shape (no bytecode, address looks like EOA)
                                                  → CounterpartyType.UNKNOWN_EOA
6. No counterparty data on the row at all (orphan row)
                                                  → CounterpartyType.GENUINE_MISSING_SOURCE
```

Step 1 is the only new behaviour. Steps 2–6 are the **existing** classifier chain; no new enum value, no new branch.

### D3. The address is always persisted

The flow's `counterpartyAddress` is ALWAYS recorded (see [ADR-010](ADR-010-flow-level-counterparty.md) §D1), regardless of the resulting `counterpartyType`. The address is what powers the per-counterparty basis pool ([ADR-015](ADR-015-per-counterparty-basis-pool.md)), and it is what allows future reclassification (e.g. admitting a previously-unknown EOA into `AccountingUniverse`) without a full pipeline rerun.

### D4. Round-trip basis carry is universal

Round-trip basis carry through any counterparty (OWN or EXTERNAL) is handled by the per-counterparty basis pool of [ADR-015](ADR-015-per-counterparty-basis-pool.md). The four counterparty kinds (OWN, PROTOCOL/BRIDGE, UNKNOWN_*) do NOT branch the AVCO engine:

| `counterpartyType` | Pool behaviour | MtM contribution |
|---|---|---|
| `PERSONAL_WALLET`, `CEX` (OWN) | Pool tracked; redundant with the OWN-side AVCO already kept per `accountRef`, used as audit invariant and as MtM source for `backfillEnabled=false` members. | OWN-side AVCO for `backfillEnabled=true`; pool `qtyHeld × avcoUsd` for `backfillEnabled=false`. |
| `PROTOCOL`, `BRIDGE` | Pool tracked; treats protocol deposits / bridge transits as basis-preserving "in transit" qty. | Pool `qtyHeld × avcoUsd` surfaced as protocol position (existing dashboard line). |
| `UNKNOWN_EOA`, `UNKNOWN_CONTRACT` | Pool tracked; OUT pushes basis, IN pops basis. Closed loops net to zero PnL. | Surfaced as "in transit" if `qtyHeld > 0`, NOT added to published MtM by default. |
| `GENUINE_MISSING_SOURCE` | No pool (flow is excluded from accounting; see [ADR-007](ADR-007-mandatory-accounting-universe-membership.md) §D5 and D-PROTOCOL-MISSING remediation). | None. |

NEC is computed uniformly:

```
NEC = Σ_{c ∈ counterparties where isMember(c)=false} (pool[c].lifetimeInBasisUsd − pool[c].lifetimeOutBasisUsd)
```

Conservation gate ([ADR-014](ADR-014-portfolio-conservation-gate.md)) checks `MtM − NEC ≈ Realised + Unrealised PnL`. There is no fiat-onramp special case.

### D5. Decision matrix

For a flow with counterparty address `X` on network `N`, the resulting `counterpartyType` is exactly:

| `AccountingUniverse` says | Registry / shape says | `counterpartyType` |
|---|---|---|
| `X` is `ON_CHAIN_WALLET` member | (irrelevant) | `PERSONAL_WALLET` |
| `X` is `EXCHANGE_ACCOUNT` member | (irrelevant) | `CEX` |
| `X` not a member | protocol-registry hit | `PROTOCOL` |
| `X` not a member | bridge-registry hit | `BRIDGE` |
| `X` not a member | has bytecode | `UNKNOWN_CONTRACT` |
| `X` not a member | EOA shape | `UNKNOWN_EOA` |
| `X` is null / unobtainable | n/a | `GENUINE_MISSING_SOURCE` |

The matrix is total: every flow lands in exactly one row. No row depends on dataset-specific lists of addresses.

### D6. Promotion / demotion is data-driven

When the user confirms a previously-unknown counterparty as OWN (e.g. the 5 TON wallets on 2026-05-16), the universe is amended (additive Member upsert); the next classifier pass labels new flows as `PERSONAL_WALLET`. Existing pool state for that counterparty is preserved (see [ADR-015](ADR-015-per-counterparty-basis-pool.md) §D5 self-healing properties) — no replay required for the basis math to be correct.

Conversely, demoting a wallet from OWN back to EXTERNAL is also a data change. The pool retains qty / basis; the MtM contribution simply moves out of "OWN ledger" into "in transit / EXTERNAL pool".

### D7. Ingestion config tie-in

| Network | `NetworkId` | Default `backfillEnabled` for newly-registered session wallets |
|---|---|---|
| ETHEREUM, ARBITRUM, …, MANTLE, BASE, LINEA, UNICHAIN, ZKSYNC, KATANA, PLASMA, AVALANCHE, POLYGON, OPTIMISM, BSC | existing | `true` |
| SOLANA | existing | `false` (no full Solana indexer in MVP) |
| TON | **new** (added per [n19-implementation-plan.md §B](cycle-autorun/cycle-data/cycle/5/results/n19-implementation-plan.md)) | `false` |

`walletradar.ingestion.network.<NETWORK>.fullIndex` in [backend/src/main/java/com/walletradar/ingestion/config/IngestionNetworkProperties.java](backend/src/main/java/com/walletradar/ingestion/config/IngestionNetworkProperties.java) drives the bootstrap default for `Member.backfillEnabled`. The user can override per wallet via the settings UI.

### D8. ASCII data flow

```
                  ┌───────────────────────────────────────────────────┐
                  │              AccountingUniverse (per-session)     │
                  │                                                   │
                  │  Members (ref, type, backfillEnabled):            │
                  │   • EVM master 4         ON_CHAIN_WALLET   true   │
                  │   • Bybit master + subs  EXCHANGE_ACCOUNT  true   │
                  │   • Solana 9Grpx4HK…     ON_CHAIN_WALLET   false  │
                  │   • TON 5 wallets        ON_CHAIN_WALLET   false  │
                  └─────────────────────────┬─────────────────────────┘
                                            │ classify(addr, network)
                                            ▼
              ┌────────────────────────────────────────────┐
              │     CounterpartyResolutionService          │
              │     (existing 7-value CounterpartyType)    │
              │                                            │
              │  step 1: AccountingUniverse → PERSONAL_WALLET / CEX
              │  step 2: protocol registry  → PROTOCOL / BRIDGE
              │  step 3: bytecode shape     → UNKNOWN_CONTRACT
              │  step 4: EOA shape          → UNKNOWN_EOA
              │  step 5: orphan             → GENUINE_MISSING_SOURCE
              └─────┬─────────────────────────────────┬────┘
                    │ flow.counterpartyAddress (always recorded)
                    ▼                                 ▼
            OWN flows                          EXTERNAL flows
              (PERSONAL_WALLET, CEX)             (PROTOCOL, BRIDGE,
                    │                              UNKNOWN_EOA,
                    │                              UNKNOWN_CONTRACT)
                    └───────────────┬───────────────┘
                                    ▼
                  ┌──────────────────────────────────┐
                  │  Per-counterparty basis pool      │  ◄── ADR-015 (universal)
                  │  push OUT  /  pop IN              │
                  │  key = (address, network,         │
                  │         assetFamily)              │
                  └──────────────────────────────────┘
                                    │
                                    ▼
                  AVCO ledger + conservation gate (ADR-014)
                                    │
                                    ▼
                          Dashboard publication
```

## Consequences

### Positive
- Solana / TON OWN wallets stop creating phantom EXTERNAL_OUT, even without implementing full Solana / TON indexers (cost-first principle preserved).
- Round-trip basis carry is correct for any counterparty (D-ROUNDTRIP-BASIS closes); the address is the key, not the label.
- Promotion / demotion of wallet ownership is a pure data change; no code path duplicates AVCO logic per wallet kind.
- Zero new `CounterpartyType` constants. Zero new resource files (no `bybit-hot-wallets.json`, no `fiat-onramps.json`). The classifier surface area shrinks compared to the original four-way draft.
- Existing implementations of `PROTOCOL`, `BRIDGE`, `UNKNOWN_EOA`, `UNKNOWN_CONTRACT` continue to apply without change.

### Negative
- The `backfillEnabled` boolean must be propagated to the dashboard MtM aggregator: an OWN member that is not backfilled contributes via the pool, not via a per-wallet AVCO ledger. Engineers must keep this rule straight (codified in D4).
- Members with `backfillEnabled=false` rely entirely on the basis pool's running AVCO. A bug in [ADR-015](ADR-015-per-counterparty-basis-pool.md) corrupts their MtM. Mitigated by the conservation gate ([ADR-014](ADR-014-portfolio-conservation-gate.md)).
- "EXTERNAL counterparty that round-trips" still shows up in the dashboard as in-transit qty until the round-trip completes. This is by design (the value really is in transit) but is novel UI surface area; specified in [ADR-014](ADR-014-portfolio-conservation-gate.md) §D6.

### Migration
1. Extend `AccountingUniverse.Member` with `backfillEnabled` (covered by [ADR-007](ADR-007-mandatory-accounting-universe-membership.md) §D2).
2. Add `NetworkId.TON` and the per-network `fullIndex` flag in `IngestionNetworkProperties` (D7).
3. Insert step 1 (`AccountingUniverseService.classify`) at position 0 of `CounterpartyResolutionService`'s walk order.
4. Remove any hardcoded address lists from classifier code (none expected after [ADR-007](ADR-007-mandatory-accounting-universe-membership.md) §D1).
5. Run [scripts/prod-reset-rebuild-backend.sh](scripts/prod-reset-rebuild-backend.sh); pool collection seeds chronologically per [ADR-015](ADR-015-per-counterparty-basis-pool.md).

## Acceptance criteria

| # | Assertion | Test |
|---|---|---|
| AC-009-1 | Five TON addresses from `n19-account-universe.json#outOfScope.tonWallets` are persisted as `MemberType=ON_CHAIN_WALLET`, `backfillEnabled=false`, `networks=[TON]` | `AccountUniverseClassifierTest` |
| AC-009-2 | Solana `9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG` is `MemberType=ON_CHAIN_WALLET`, `backfillEnabled=false`, `networks=[SOLANA]` | `AccountUniverseClassifierTest` |
| AC-009-3 | 13 Bybit → Solana withdrawals classify with `counterpartyType=PERSONAL_WALLET` and are normalised as `INTERNAL_TRANSFER`; OUT pushes onto the Solana counterparty pool | `N19FullPipelineRebuildTest` |
| AC-009-4 | An unknown EOA round-trip (send 100 USDT to `0xABC…`, receive 100 USDT back) yields `realisedPnlUsd = 0` on both legs and OWN AVCO unchanged | `CounterpartyBasisPoolTest` |
| AC-009-5 | Address `0xb2a4fb8acb60c4ecc164b0de9af99242f2c1fd2d` (Whitebird fiat onramp per `n19-account-universe.json`) classifies as `UNKNOWN_EOA` (not a member; EOA shape). The fiat-onramp semantic is emergent from the one-sided pool, not from a registry | `AccountUniverseClassifierTest` + `CounterpartyBasisPoolTest` |
| AC-009-6 | Unlabelled `0xDEADBEEF…` EOA classifies as `UNKNOWN_EOA`; pool tracks OUT/IN; `realisedPnlUsd = 0` for any closed round-trip | `AccountUniverseClassifierTest` + `CounterpartyBasisPoolTest` |
| AC-009-7 | `walletradar.ingestion.network.TON.fullIndex=false` ⇒ TON Members default to `backfillEnabled=false` ⇒ `BackfillJobPlanner` plans zero TON segments, but TON Members still resolve to `PERSONAL_WALLET` via `AccountingUniverseService.classify` | `BackfillJobPlannerTest` + `AccountUniverseClassifierTest` |
| AC-009-8 | Promotion path: amend universe to add a previously-unknown EOA as `ON_CHAIN_WALLET` — the pool's existing qty / basis is preserved; the next flow on that address classifies as `PERSONAL_WALLET`; no replay is required | `CounterpartyBasisPoolTest` |
| AC-009-9 | `db.normalized_transactions.find({counterpartyType: null}).count() == 0` and every row's `counterpartyType` is one of the seven existing constants | `N19FullPipelineRebuildTest` |

## References

- [cycle-autorun/cycle-data/cycle/5/results/n19-defect-catalog.md](cycle-autorun/cycle-data/cycle/5/results/n19-defect-catalog.md) — D-ROUNDTRIP-BASIS, D-MISSING-NETWORKS, D-MISSING-TON.
- [cycle-autorun/cycle-data/cycle/5/results/n19-account-universe.json](cycle-autorun/cycle-data/cycle/5/results/n19-account-universe.json) — Solana + TON address evidence.
- [cycle-autorun/cycle-data/cycle/5/results/n19-implementation-plan.md](cycle-autorun/cycle-data/cycle/5/results/n19-implementation-plan.md) §B, §C, §K.
- [ADR-003](ADR-003-transfer-links-fa001.md), [ADR-007](ADR-007-mandatory-accounting-universe-membership.md), [ADR-008](ADR-008-bybit-subaccount-discovery.md), [ADR-010](ADR-010-flow-level-counterparty.md), [ADR-013](ADR-013-cex-cross-system-linking.md), [ADR-014](ADR-014-portfolio-conservation-gate.md), [ADR-015](ADR-015-per-counterparty-basis-pool.md).
- [backend/src/main/java/com/walletradar/ingestion/pipeline/clarification/CounterpartyResolutionService.java](backend/src/main/java/com/walletradar/ingestion/pipeline/clarification/CounterpartyResolutionService.java).
- [backend/src/main/java/com/walletradar/ingestion/pipeline/clarification/CounterpartyType.java](backend/src/main/java/com/walletradar/ingestion/pipeline/clarification/CounterpartyType.java) — seven-value taxonomy reused unchanged.
- [backend/src/main/java/com/walletradar/ingestion/config/IngestionNetworkProperties.java](backend/src/main/java/com/walletradar/ingestion/config/IngestionNetworkProperties.java).
- [backend/src/main/java/com/walletradar/ingestion/job/backfill/BackfillJobPlanner.java](backend/src/main/java/com/walletradar/ingestion/job/backfill/BackfillJobPlanner.java).
- [backend/src/main/java/com/walletradar/domain/common/NetworkId.java](backend/src/main/java/com/walletradar/domain/common/NetworkId.java) — needs `TON` constant.

---

## Appendix — N19 ADR Cross-Reference Index

This appendix lists the 9 ADRs produced for cycle/5 N19 and the section of `cycle-autorun/cycle-data/cycle/5/results/n19-implementation-plan.md` each codifies. Also tracked in [docs/adr/INDEX.md](INDEX.md).

| ADR | Title | Plan section | Primary defects closed |
|---|---|---|---|
| [ADR-007](ADR-007-mandatory-accounting-universe-membership.md) | Mandatory Accounting Universe membership for OWN classification | §A | D-ROOT-1, D-COUNTERPARTY-1 (structural part), D-MISSING-NETWORKS, D-MISSING-TON |
| [ADR-008](ADR-008-bybit-subaccount-discovery.md) | Bybit sub-account discovery on backfill bootstrap | §A (change 3 + sub-UID merge) | D-ROOT-2 |
| [ADR-009](ADR-009-ownership-classification-via-universe.md) | Ownership Classification via AccountingUniverse | §B, §C | D-ROUNDTRIP-BASIS (label part), D-MISSING-NETWORKS, D-MISSING-TON |
| [ADR-010](ADR-010-flow-level-counterparty.md) | Flow-level counterparty on `NormalizedTransaction.Flow` | §D | D-FLOWS-1, D-COUNTERPARTY-2 |
| [ADR-011](ADR-011-bybit-fiat-p2p-external.md) | Bybit Fiat P2P Purchase = `EXTERNAL_TRANSFER_IN` terminal | §E | D-FIAT-P2P |
| [ADR-012](ADR-012-borrow-liability-tracker.md) | BorrowLiability tracker by `orderId` | §F | D-LOAN-ROUNDTRIP |
| [ADR-013](ADR-013-cex-cross-system-linking.md) | CEX Cross-System Linking via FA-001 (no hot-wallet registry) | §G | D-BYBIT-HOT-WALLET |
| [ADR-014](ADR-014-portfolio-conservation-gate.md) | Conservation gate at dashboard publication | §H | Cross-cutting (regression gate) |
| [ADR-015](ADR-015-per-counterparty-basis-pool.md) | Per-counterparty basis pool (universal round-trip basis carry) | §K | D-ROUNDTRIP-BASIS (mechanism), D-MISSING-NETWORKS (MtM), D-MISSING-TON (MtM) |

Plan sections G (Convert / spot dedup), I (Airdrop policy), J (migration), the test suite, frontend changes, and sequencing are implementation details, not ADR-grade decisions; they are tracked in `n19-implementation-plan.md` and the cycle/5 task list under `docs/tasks/15X-cycle5-*.md`.
