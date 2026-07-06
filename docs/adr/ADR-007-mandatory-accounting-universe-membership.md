# ADR-007 — Mandatory Accounting Universe membership for OWN classification

**Status:** Proposed
**Date:** 2026-05-16
**Inputs:** `cycle-autorun/cycle-data/cycle/5/results/n19-README.md`, `n19-defect-catalog.md` (D-ROOT-1, D-ROOT-2, D-COUNTERPARTY-1, D-MISSING-NETWORKS, D-MISSING-TON), `n19-implementation-plan.md` §A, `n19-account-universe.json`; user decisions 2026-05-16 (reuse of existing `CounterpartyType`; `backfillEnabled` flag; no hot-wallet / fiat-onramp resource files)

## Context

Cycle/5 N19 Phase 1 proved three numbers from raw evidence:

- Net External Capital independently derived: **$14,029.88** (user-claimed $14,230.60, delta $200 ≈ 1.4% within pricing tolerance).
- Live Portfolio (universe) = Bybit live API **$795** + EVM `external-truth.md` **$10,616** + TON **$266** + Solana **$120** = **$11,797**.
- Implied Total PnL = MtM − NEC = **−$2,233** to **−$2,820** depending on scope variant.

Dashboard reports **Realised −$348** / Total ≈ **−$1,500** — wrong by $700–$2,500 vs. the conservation truth.

The single biggest root cause is **D-ROOT-1**: in production, `db.accounting_universes.estimatedDocumentCount() == 0`. There is no central registry that can answer `isOwn(address, network)` for any consumer. Every classifier (`CounterpartyResolutionService`, transfer linker, balance allocator) falls back to local heuristics or to `UNKNOWN_EOA`. This cascades into:

- 4,008 / 7,279 normalized transactions (≈ 55 %) with `counterpartyType = null` (D-COUNTERPARTY-1).
- 129 / 409 Bybit external transfers with no `counterpartyAddress` (D-COUNTERPARTY-2).
- 13 Bybit → Solana `9Grpx4HK…` withdrawals classified as EXTERNAL outflow instead of OWN-side internal transfer (D-MISSING-NETWORKS).
- All 5 user TON wallets (`UQAe4Uho…`, `UQDcaquh…`, `UQB423bm…`, `UQDdb_AsW…`, `UQAMVoQ1X1…`) missing from the universe (D-MISSING-TON).
- All 4 Bybit sub-account UIDs (`516601508`, `421768407`, `421325298`, `409666492`) missing from the master member (D-ROOT-2 — bootstrap mechanism in [ADR-008](ADR-008-bybit-subaccount-discovery.md)).

The current persisted document model already exists ([backend/src/main/java/com/walletradar/domain/session/AccountingUniverse.java](backend/src/main/java/com/walletradar/domain/session/AccountingUniverse.java)) but is not populated for the production session and does not yet distinguish wallets we **index** end-to-end (EVM, Bybit) from wallets the user **owns but we do not backfill** (TON, Solana). The latter must still register as OWN so that flows touching them are not phantom-EXTERNAL.

The universe is therefore promoted from a session metadata artefact to a **mandatory invariant**: an empty or under-populated universe is a deployment error, not a soft default.

## Decision

### D1. AccountingUniverse is the only legal source of OWN identity

Every counterparty / wallet classification in the pipeline (`CounterpartyResolutionService`, `BybitTransferContinuityRepairService`, `InternalTransferPairLinkService`, balance allocator, dashboard query) MUST resolve `isOwn` via [backend/src/main/java/com/walletradar/session/application/AccountingUniverseService.java](backend/src/main/java/com/walletradar/session/application/AccountingUniverseService.java). No service is allowed to maintain its own static list of OWN addresses, Bybit UIDs, or "bridge" hot wallets.

There is no separate hot-wallet registry and no separate fiat-onramp registry. The rule is single: **address is OWN iff it is a Member of `AccountingUniverse`; otherwise it is EXTERNAL.** Counterparty further classification (PROTOCOL / BRIDGE / UNKNOWN_EOA / UNKNOWN_CONTRACT / GENUINE_MISSING_SOURCE) is delegated to the existing classifier chain — see [ADR-009](ADR-009-ownership-classification-via-universe.md).

### D2. `Member` schema — add `backfillEnabled`

`AccountingUniverse.Member` is extended with a single new field; the existing `MemberType` (`ON_CHAIN_WALLET`, `EXCHANGE_ACCOUNT`) is kept as-is:

```text
Member {
  ref                : String          // canonical reference (see normalization rules)
  type               : MemberType      // ON_CHAIN_WALLET | EXCHANGE_ACCOUNT
  provider           : String          // "EVM" | "TON" | "SOLANA" | "BYBIT"
  networks           : List<NetworkId> // includes new TON enum value
  subAccountUids     : List<String>    // only meaningful for EXCHANGE_ACCOUNT (see ADR-008)
  backfillEnabled    : boolean         // NEW — true iff WalletRadar runs full ingestion for this member
  firstSeenAt        : Instant
  lastSeenAt         : Instant
}
```

Semantics of `backfillEnabled`:

| Value | Meaning | Example |
|---|---|---|
| `true` (default) | WalletRadar runs the configured ingestion adapter end-to-end for this member. Backfill and incremental jobs produce normalized rows. | EVM session wallets, Bybit master + sub UIDs. |
| `false` | The address is OWN (in `AccountingUniverse`) but no ingestion adapter runs against it. The member exists only so that flows on indexed members which touch this address resolve to `PERSONAL_WALLET` instead of `UNKNOWN_EOA`. | TON wallets, Solana wallet `9Grpx4HK…` (no Solana / TON indexer in MVP). |

**Critical invariant: `backfillEnabled` is orthogonal to ownership.** Any address registered in `AccountingUniverse` is OWN — full stop — regardless of the flag. The flag only steers ingestion job planning ([backend/src/main/java/com/walletradar/ingestion/job/backfill/BackfillJobPlanner.java](backend/src/main/java/com/walletradar/ingestion/job/backfill/BackfillJobPlanner.java)): when `false`, no backfill or incremental segments are planned for that member.

`backfillEnabled` is user-controlled per session wallet via the existing settings flow (default `true` when the network has a working ingestion adapter, default `false` when the user manually registers an address on an unsupported network).

Reference normalization (matches `n19-account-universe.json#ownAddressNormalizationRules`):

| Provider | `ref` form | Example |
|---|---|---|
| `EVM` | lowercase `0x…40hex`, no whitespace | `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f` |
| `TON` | user-facing `UQ…` / `EQ…` (raw `0:hex` accepted as alias) | `UQDcaquhb07rH_df1iy56EF5WUE_XGmq4KhNLPs-m7v9o37O` |
| `SOLANA` | base58 | `9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG` |
| `BYBIT` | `BYBIT:<UID>` (the sub-ledger suffix `:UTA` / `:FUND` / `:EARN` is a sibling reference, not a separate member) | `BYBIT:33625378` |

### D3. The universe MUST be non-empty at session boot

On session creation / Bybit integration upsert / EVM wallet upsert, the universe bootstrap pass ([backend/src/main/java/com/walletradar/session/application/AccountingUniverseSyncService.java](backend/src/main/java/com/walletradar/session/application/AccountingUniverseSyncService.java)) populates the universe with:

1. **All registered EVM session wallets** as `ON_CHAIN_WALLET`, `backfillEnabled=true`.
2. **All registered TON session wallets** as `ON_CHAIN_WALLET`, `backfillEnabled=false` (no TON indexer; new `NetworkId.TON`).
3. **All registered Solana session wallets** as `ON_CHAIN_WALLET`, `backfillEnabled=false` (no full Solana indexer in MVP).
4. **Bybit master member** as `EXCHANGE_ACCOUNT`, `backfillEnabled=true`, with `subAccountUids[]` populated via [ADR-008](ADR-008-bybit-subaccount-discovery.md).

No hot-wallet members. Bybit-side hot wallets are EXTERNAL by definition (they are not the user's account). Chain ↔ Bybit reconciliation runs via FA-001 `transfer_links` keyed on `txHash` — see [ADR-013](ADR-013-cex-cross-system-linking.md), built on [ADR-003](ADR-003-transfer-links-fa001.md).

If `members.size() == 0` after bootstrap (e.g. the only configured integration failed), the sync service MUST emit a `UNIVERSE_BOOTSTRAP_INCOMPLETE` health event and the dashboard MUST refuse to publish PnL (`conservationBreached=true`; see [ADR-014](ADR-014-portfolio-conservation-gate.md)). The system fails closed.

### D4. `AccountingUniverseService` query contract

A new query API replaces ad-hoc `equalsIgnoreCase` checks scattered through classifiers:

```java
record OwnMembership(boolean isMember, MemberType memberType, boolean backfillEnabled, String ref) {}

OwnMembership classify(String address, NetworkId network);

default boolean isMember(String address, NetworkId network) {
    return classify(address, network).isMember();
}
```

`classify` returns:

- `isMember=true, memberType=ON_CHAIN_WALLET` for EVM/TON/Solana session wallets.
- `isMember=true, memberType=EXCHANGE_ACCOUNT` for the Bybit master UID and any of its `subAccountUids[]` (sub-ledger suffixes `:UTA` / `:FUND` / `:EARN` resolve to the same member).
- `isMember=false` otherwise.

Downstream counterparty classification ([ADR-009](ADR-009-ownership-classification-via-universe.md) §D2) maps:

| `OwnMembership` | Resulting `CounterpartyType` |
|---|---|
| `isMember=true, memberType=ON_CHAIN_WALLET`     | `PERSONAL_WALLET` |
| `isMember=true, memberType=EXCHANGE_ACCOUNT`    | `CEX` |
| `isMember=false` | delegate to the existing classifier chain (protocol-registry → bridge-registry → contract-shape) which already produces `PROTOCOL`, `BRIDGE`, `UNKNOWN_EOA`, `UNKNOWN_CONTRACT`, or `GENUINE_MISSING_SOURCE`. |

No new enum values are added to [backend/src/main/java/com/walletradar/ingestion/pipeline/clarification/CounterpartyType.java](backend/src/main/java/com/walletradar/ingestion/pipeline/clarification/CounterpartyType.java). The seven existing constants (`CEX`, `PERSONAL_WALLET`, `PROTOCOL`, `BRIDGE`, `UNKNOWN_EOA`, `UNKNOWN_CONTRACT`, `GENUINE_MISSING_SOURCE`) cover every observed counterparty under the AccountingUniverse-driven model.

Internal lookups use a per-session in-memory index loaded once at startup (Caffeine cache keyed by `(universeId, networkId)`, invalidated on `AccountingUniverseSyncService` writes). The `classify` call is O(1) hash lookup; it MUST NOT touch Mongo on the hot path. Index keys are stored lowercased for EVM, base58 for Solana, and canonical `UQ…` for TON; raw `0:hex` TON form maps to the same canonical key on lookup.

### D5. Mandatory non-null `counterpartyType` invariant

`StatValidationService` rejects any `NormalizedTransaction` reaching the pricing stage with `counterpartyType == null`. The rejection records `missingDataReasons += "COUNTERPARTY_TYPE_MISSING"` and routes the row to clarification, never to the AVCO replay. This is the structural enforcement that closes D-COUNTERPARTY-1.

For the rare flow where the underlying source has no counterparty data at all (D-COUNTERPARTY-2's residual after exhaustion of Bybit `deposit-record` / `withdraw-record` hydration), the classifier emits `counterpartyType = GENUINE_MISSING_SOURCE` and the row is excluded from accounting until evidence improves. The invariant is that the field is **non-null**, not that it is non-`GENUINE_MISSING_SOURCE`.

## Consequences

### Positive
- Single source of truth for OWN identity — no split-brain between classifier code, configuration files, and metadata.
- Solana / TON OWN wallets (currently EXTERNAL phantoms) become OWN without needing on-chain indexers — `backfillEnabled=false` is the structural lever.
- Master ↔ sub Universal Transfers reclassify to `INTERNAL_TRANSFER`, eliminating phantom EXTERNAL contributions to NEC.
- Conservation invariant becomes computable at every pipeline tick (see [ADR-014](ADR-014-portfolio-conservation-gate.md)).
- Zero new enum values, zero new resource files. The classifier extension surface is exactly one method (`AccountingUniverseService.classify`) called at position 0 of the existing chain.

### Negative
- Adds a deployment-time check: empty universe = startup error. Operators must populate the universe before any backfill produces normalized data.
- `backfillEnabled` introduces a UI/settings concept the user must understand when adding an unsupported-network wallet. Mitigated by sensible default (`true` if a working adapter exists for the wallet's primary network; `false` otherwise) and by surfacing the flag explicitly in the settings panel.
- All consumers must be migrated to the new `AccountingUniverseService` API in one PR or behind a feature flag — partial adoption is a regression risk because mixed-source classification produces inconsistent OWN/EXTERNAL labels per flow.

### Migration
1. Schema-additive deploy: extend `Member` with `backfillEnabled` (Boolean; default `true` for documents lacking the field, EXCEPT for members on networks `SOLANA`/`TON` which default to `false` via a one-shot migration). Pre-existing documents read as default-on-the-fly and are rewritten on the first sync pass.
2. Implement `AccountingUniverseService.classify` and the in-memory index.
3. Backfill once per session via `AccountingUniverseSyncService.bootstrap` (covers D-MISSING-NETWORKS, D-MISSING-TON, D-ROOT-2 in conjunction with [ADR-008](ADR-008-bybit-subaccount-discovery.md)).
4. Run [scripts/prod-reset-rebuild-backend.sh](scripts/prod-reset-rebuild-backend.sh) to clear `normalized_transactions`, `asset_ledger_points`, and `counterparty_basis_pools` (see [ADR-015](ADR-015-per-counterparty-basis-pool.md)). The pipeline replays from raw evidence under the new classifier.
5. Acceptance gate: post-rebuild `db.normalized_transactions.countDocuments({counterpartyType: null}) == 0`.

## Acceptance criteria

| # | Assertion | Test |
|---|---|---|
| AC-007-1 | `db.accounting_universes.estimatedDocumentCount() >= 1` and `members.size() >= EVM_wallets + TON_wallets + Solana_wallets + 1 Bybit master` for every active session | `AccountUniverseClassifierTest` (universe seed assertion) |
| AC-007-2 | `AccountingUniverseService.classify("0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f", ETHEREUM)` → `isMember=true, memberType=ON_CHAIN_WALLET, backfillEnabled=true` | `AccountUniverseClassifierTest` |
| AC-007-3 | `AccountingUniverseService.classify("9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG", SOLANA)` → `isMember=true, memberType=ON_CHAIN_WALLET, backfillEnabled=false` | `AccountUniverseClassifierTest` |
| AC-007-4 | `AccountingUniverseService.classify("UQDcaquhb07rH_df1iy56EF5WUE_XGmq4KhNLPs-m7v9o37O", TON)` → `isMember=true, memberType=ON_CHAIN_WALLET, backfillEnabled=false`. The raw form `0:dc6aaba1…` resolves to the same member | `AccountUniverseClassifierTest` |
| AC-007-5 | `AccountingUniverseService.classify("BYBIT:516601508", null)` → `isMember=true, memberType=EXCHANGE_ACCOUNT`. The suffix forms `BYBIT:516601508:UTA / :FUND / :EARN` resolve to the same member | `AccountUniverseClassifierTest` |
| AC-007-6 | After full rebuild on the N19 raw bundle, `db.normalized_transactions.countDocuments({counterpartyType: null}) == 0` | `N19FullPipelineRebuildTest` |
| AC-007-7 | Empty universe at boot ⇒ `SessionPipelineStateService` reports `UNIVERSE_BOOTSTRAP_INCOMPLETE` and dashboard returns `conservationBreached=true` | `N19FullPipelineRebuildTest` (negative path) |
| AC-007-8 | `BackfillJobPlanner` plans zero on-chain segments for any member with `backfillEnabled=false`; ingestion logs `SKIPPED_BACKFILL_DISABLED` per skipped member | `BackfillJobPlannerTest` |
| AC-007-9 | `AccountingUniverseService.classify` performs zero Mongo calls after warmup (verified via Mongo command listener) | `AccountUniverseClassifierTest` (perf assertion) |

## References

- [cycle-autorun/cycle-data/cycle/5/results/n19-defect-catalog.md](cycle-autorun/cycle-data/cycle/5/results/n19-defect-catalog.md) — D-ROOT-1, D-ROOT-2, D-COUNTERPARTY-1, D-MISSING-NETWORKS, D-MISSING-TON.
- [cycle-autorun/cycle-data/cycle/5/results/n19-account-universe.json](cycle-autorun/cycle-data/cycle/5/results/n19-account-universe.json) — canonical OWN-address registry used in simulation; this ADR codifies its persistence.
- [cycle-autorun/cycle-data/cycle/5/results/n19-implementation-plan.md](cycle-autorun/cycle-data/cycle/5/results/n19-implementation-plan.md) §A.
- [ADR-003](ADR-003-transfer-links-fa001.md) — FA-001 corridor; used by [ADR-013](ADR-013-cex-cross-system-linking.md) for chain ↔ CEX linking.
- [ADR-008](ADR-008-bybit-subaccount-discovery.md), [ADR-009](ADR-009-ownership-classification-via-universe.md), [ADR-010](ADR-010-flow-level-counterparty.md), [ADR-013](ADR-013-cex-cross-system-linking.md), [ADR-014](ADR-014-portfolio-conservation-gate.md), [ADR-015](ADR-015-per-counterparty-basis-pool.md).
- [backend/src/main/java/com/walletradar/domain/session/AccountingUniverse.java](backend/src/main/java/com/walletradar/domain/session/AccountingUniverse.java) — current persisted shape (to be extended with `backfillEnabled`).
- [backend/src/main/java/com/walletradar/session/application/AccountingUniverseSyncService.java](backend/src/main/java/com/walletradar/session/application/AccountingUniverseSyncService.java) — bootstrap entry point.
- [backend/src/main/java/com/walletradar/session/application/AccountingUniverseService.java](backend/src/main/java/com/walletradar/session/application/AccountingUniverseService.java) — `classify` API.
- [backend/src/main/java/com/walletradar/ingestion/pipeline/clarification/CounterpartyType.java](backend/src/main/java/com/walletradar/ingestion/pipeline/clarification/CounterpartyType.java) — existing 7-value taxonomy reused as-is.
- [backend/src/main/java/com/walletradar/ingestion/job/backfill/BackfillJobPlanner.java](backend/src/main/java/com/walletradar/ingestion/job/backfill/BackfillJobPlanner.java) — gated by `Member.backfillEnabled`.
