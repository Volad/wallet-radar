# ADR-008 — Bybit sub-account discovery on backfill bootstrap

**Status:** Proposed
**Date:** 2026-05-16
**Inputs:** `cycle-autorun/cycle-data/cycle/5/results/n19-defect-catalog.md` (D-ROOT-2), `n19-implementation-plan.md` §A, `n19-account-universe.json`

## Context

WalletRadar treats a Bybit umbrella as a single `BYBIT:<masterUid>` member with three sub-ledgers (`UTA`, `FUND`, `EARN`). In production today, only the master UID `33625378` is registered:

```text
db.bybit_extracted_events.distinct("walletRef")
== ["BYBIT:33625378", "BYBIT:33625378:EARN", "BYBIT:33625378:FUND", "BYBIT:33625378:UTA"]
```

The Bybit live API `/v5/user/query-sub-members` reveals **four user-owned sub-accounts** under this master: `516601508`, `421768407`, `421325298`, `409666492` (per `n19-account-universe.json#inScope.bybit.subAccountUids`).

When the user moves funds master ↔ sub via `/v5/asset/transfer/universal-transfer` (UNIVERSAL_TRANSFER stream), the row carries `fromMemberId` and `toMemberId`. With the sub-UIDs unknown, the resolver:

- Cannot label the counterparty `BYBIT:<subUid>` as OWN ([backend/src/main/java/com/walletradar/ingestion/pipeline/clarification/InternalTransferPairLinkService.java](backend/src/main/java/com/walletradar/ingestion/pipeline/clarification/InternalTransferPairLinkService.java) compares against an empty set).
- Defaults to `EXTERNAL_TRANSFER_IN/OUT`, inflating NEC inflow and suppressing realised PnL on the outflow.
- Misses the matching peer leg on the sub side because the sub side is not extracted at all (no Bybit credentials configured per sub).

Estimated impact per `n19-defect-catalog.md`: **$500-1,000** of phantom NEC and Realised PnL noise. The two largest sub-accounts (`516601508`, `421768407`) account for ≈ 80 % of the master↔sub Universal Transfer volume in the audit window.

Refreshing the sub-list automatically also future-proofs against the user adding a new sub-account in Bybit UI — without discovery, every new sub would silently appear as an external counterparty.

## Decision

### D1. Add a signed sub-member discovery call to `BybitApiClient`

Add a new method to [backend/src/main/java/com/walletradar/integration/bybit/BybitApiClient.java](backend/src/main/java/com/walletradar/integration/bybit/BybitApiClient.java):

```java
public List<BybitSubMember> fetchSubMembers(String masterApiKey, String masterApiSecret) {
    // GET /v5/user/query-sub-members?showAllAccounts=false&pageLimit=20
    // signed with master HMAC-SHA256; standard cycle/5 rate limiting + retry/jitter
    // returns: List<{ uid, username, memberType, status, accountMode, accountState }>
}
```

`BybitSubMember.memberType ∈ { "USER_OWNED_SUB", "AGENT_SUB", "CUSTODY_SUB" }`. Only `USER_OWNED_SUB` entries are persisted into the universe (D2). Other types stay observable in audit logs but are excluded from the OWN set so that a custody / agent sub does not silently become user-owned basis.

### D2. Persist sub-UIDs into the master member

The Bybit master `Member` (`type=EXCHANGE_ACCOUNT`, `provider="BYBIT"`) gains a `List<String> subAccountUids` field (already declared in [ADR-007](ADR-007-mandatory-accounting-universe-membership.md) §D2). `AccountingUniverseSyncService.onBybitIntegrationUpsert` calls `BybitApiClient.fetchSubMembers` and merges the resulting UIDs into the member's `subAccountUids`. Existing entries are preserved (additive merge) — a sub-account removed from Bybit's side is NOT auto-deleted because historical flows still reference it.

Persisted shape:

```javascript
{
  ref: "BYBIT:33625378",
  type: "EXCHANGE_ACCOUNT",
  backfillEnabled: true,
  provider: "BYBIT",
  networks: ["BYBIT"],
  subAccountUids: ["516601508", "421768407", "421325298", "409666492"],
  firstSeenAt: ...,
  lastSeenAt: ...
}
```

### D3. Caching + refresh policy

| Trigger | Action |
|---|---|
| Bybit integration upsert (key change / new wallet) | Force-refresh via `BybitApiClient.fetchSubMembers`, persist into universe. |
| `bybitFullRebuild` start | Force-refresh once at the start of the run. |
| Scheduled refresh | Once per 24 h via [AccountUniverseSyncPlanScheduler](backend/src/main/java/com/walletradar/session/application/AccountUniverseSyncPlanScheduler.java); idempotent merge. |
| In-process cache | Caffeine, key = `masterUid`, TTL = 24 h, max size = 16. No Mongo round-trip on hot path. |

The 24-hour TTL aligns with WalletRadar's cost-first principle: `/v5/user/query-sub-members` is rate-limited to ≈ 50 req / 5 s per UID and free-tier; refreshing it daily costs essentially nothing and adopts a new sub-account within at most 24 h. A manual refresh button on the Integrations admin page (already present per [backend/src/main/java/com/walletradar/api/controller/AdminIntegrationPipelineController.java](backend/src/main/java/com/walletradar/api/controller/AdminIntegrationPipelineController.java)) provides immediate refresh when needed.

### D4. Sub-account ledger reference taxonomy

Every Bybit flow has a `walletRef` of the form `BYBIT:<UID>(:<LEDGER>)?` where:

- `UID` ∈ master ∪ subAccountUids.
- `LEDGER` ∈ `UTA | FUND | EARN`, or omitted when the row's sub-ledger is implicit at the source (e.g. `UNIVERSAL_TRANSFER` carries `fromAccountType` / `toAccountType` separately).

The `AccountingUniverseService.classify` API (see [ADR-007](ADR-007-mandatory-accounting-universe-membership.md) §D4) MUST treat any `BYBIT:<UID>[:LEDGER]` as `CounterpartyType.CEX` if the UID is in the master ∪ subs set. The implementation reuses `bybitRefCandidates(walletRef)` introduced for ADR-006 N18 in [AccountingUniverseService.shareUniverseMembers](backend/src/main/java/com/walletradar/session/application/AccountingUniverseService.java) — the same suffix-tolerance behaviour applies.

### D5. Classification rule for Universal Transfer rows

In [backend/src/main/java/com/walletradar/integration/bybit/BybitExtractionService.java](backend/src/main/java/com/walletradar/integration/bybit/BybitExtractionService.java) `extractUniversalTransfer` (and the downstream [BybitCanonicalTransactionBuilder](backend/src/main/java/com/walletradar/ingestion/pipeline/bybit/BybitCanonicalTransactionBuilder.java)):

```
if fromMemberId ∈ {master, subs} AND toMemberId ∈ {master, subs}:
    canonicalType = "INTERNAL_TRANSFER"
    counterpartyType = CEX     // existing CounterpartyType, no new enum value
    counterpartyAddress = "BYBIT:<peerUid>"   // see ADR-010 for flow-level shape
```

The pair is correlated via the existing `bybit-econ-v1:` correlationId scheme (cycle/5 N3); the peer leg becomes visible to the replay engine through [TransferReplayHandler](backend/src/main/java/com/walletradar/costbasis/application/replay/handler) once the peer-UID's flows are extracted. If the peer is sub-only-key (no credentials), the leg is **synthetic** — see D6.

### D6. Sub-account credentials are optional; synthetic peer flows are emitted

When the user provides credentials only for the master UID, the sub-account side is invisible to the extractor. To keep the basis-carry working without forcing additional credentials, the master-side Universal Transfer row is paired against a **synthetic peer flow** generated by [BybitExtractionService](backend/src/main/java/com/walletradar/integration/bybit/BybitExtractionService.java):

- Same `bybit-econ-v1:` correlation id.
- Opposite sign quantity.
- `walletRef = BYBIT:<peerUid>` (no ledger suffix).
- `provenance = "SYNTHETIC_MASTER_DERIVED"`.
- `basisRelevant = true` so the receiver leg picks up basis from the per-counterparty pool ([ADR-015](ADR-015-per-counterparty-basis-pool.md)) when the user later moves funds back to master.

Per-counterparty pool ([ADR-015](ADR-015-per-counterparty-basis-pool.md)) closes the loop: even with only synthetic peer flows, a master→sub→master round-trip nets to zero realised PnL.

## Consequences

### Positive
- D-ROOT-2 closes structurally: every master↔sub Universal Transfer is `INTERNAL_TRANSFER`, basis carries via [TransferReplayHandler](backend/src/main/java/com/walletradar/costbasis/application/replay/handler) and [ADR-015](ADR-015-per-counterparty-basis-pool.md).
- New sub-accounts appear in the universe within at most 24 h, automatically. No code change needed.
- No extra credentials required from the user (synthetic peer flows under D6).

### Negative
- One extra signed REST call to `/v5/user/query-sub-members` on bootstrap and once daily — negligible cost; well within Bybit's free tier.
- Universe document grows slightly; capped at a handful of sub-UIDs per master.
- Synthetic peer flows complicate the per-asset ledger view for sub UIDs (their balance is reconstructed from master-side debits / credits, not from a direct sub-side extraction). Mitigation: sub-account view is informational only until the user adds sub credentials.

### Migration
1. Schema-additive: add `subAccountUids` to Member (covered by [ADR-007](ADR-007-mandatory-accounting-universe-membership.md) §D2 migration).
2. Deploy `BybitApiClient.fetchSubMembers` and wire into `AccountingUniverseSyncService`.
3. Run [scripts/prod-reset-rebuild-backend.sh](scripts/prod-reset-rebuild-backend.sh): on next boot, sync fetches sub-UIDs and persists them before backfill starts.
4. Pipeline reprocesses Universal Transfers; those previously classified as `EXTERNAL_TRANSFER_*` reclassify to `INTERNAL_TRANSFER`.

## Acceptance criteria

| # | Assertion | Test |
|---|---|---|
| AC-008-1 | After sync, `db.accounting_universes.findOne({"members.ref": "BYBIT:33625378"}).members.find(m=>m.ref=="BYBIT:33625378").subAccountUids` contains exactly `{516601508, 421768407, 421325298, 409666492}` | `AccountUniverseClassifierTest` |
| AC-008-2 | `BybitApiClient.fetchSubMembers` is called exactly once per `bybitFullRebuild`, validated via Mockito `verify(...).times(1)` | `BybitExtractionServiceTest` |
| AC-008-3 | Sync called twice within < 24 h hits the Caffeine cache; only the first triggers a REST call | `BybitApiClientTest` |
| AC-008-4 | `AccountingUniverseService.classify("BYBIT:516601508:UTA", null).counterpartyType == CounterpartyType.CEX` (suffix tolerance) | `AccountUniverseClassifierTest` |
| AC-008-5 | Universal Transfer row with `fromMemberId=33625378, toMemberId=516601508` normalises to `NormalizedTransactionType.INTERNAL_TRANSFER` with `counterpartyAddress="BYBIT:516601508"` and `counterpartyType=CEX` | `BybitCanonicalTransactionBuilderTest` |
| AC-008-6 | Across the N19 raw bundle, the count of `INTERNAL_TRANSFER` rows tagged `counterpartyType=CEX` and `counterpartyAddress` matching `^BYBIT:(516601508\|421768407\|421325298\|409666492)$` is ≥ the count of master↔sub Universal Transfer events in `n19-raw/bybit-universal-transfer.jsonl` filtered to UIDs ∈ master ∪ subs | `N19FullPipelineRebuildTest` |
| AC-008-7 | Synthetic peer flow path: a master→sub Universal Transfer produces 1 master-side `EXTERNAL_OUT(role=TRANSFER)` + 1 synthetic sub-side `EXTERNAL_IN(role=TRANSFER)` with matching `correlationId` | `BybitExtractionServiceTest` |

## References

- [cycle-autorun/cycle-data/cycle/5/results/n19-defect-catalog.md](cycle-autorun/cycle-data/cycle/5/results/n19-defect-catalog.md) — D-ROOT-2.
- [cycle-autorun/cycle-data/cycle/5/results/n19-account-universe.json](cycle-autorun/cycle-data/cycle/5/results/n19-account-universe.json) — sub-UIDs evidence (line 42).
- [cycle-autorun/cycle-data/cycle/5/results/n19-implementation-plan.md](cycle-autorun/cycle-data/cycle/5/results/n19-implementation-plan.md) §A change 3.
- [ADR-007](ADR-007-mandatory-accounting-universe-membership.md), [ADR-009](ADR-009-ownership-classification-via-universe.md), [ADR-010](ADR-010-flow-level-counterparty.md), [ADR-015](ADR-015-per-counterparty-basis-pool.md).
- [backend/src/main/java/com/walletradar/integration/bybit/BybitApiClient.java](backend/src/main/java/com/walletradar/integration/bybit/BybitApiClient.java).
- [backend/src/main/java/com/walletradar/integration/bybit/BybitExtractionService.java](backend/src/main/java/com/walletradar/integration/bybit/BybitExtractionService.java) `extractUniversalTransfer`.
- [backend/src/main/java/com/walletradar/ingestion/pipeline/bybit/BybitCanonicalTransactionBuilder.java](backend/src/main/java/com/walletradar/ingestion/pipeline/bybit/BybitCanonicalTransactionBuilder.java).
- [backend/src/main/java/com/walletradar/session/application/AccountingUniverseService.java](backend/src/main/java/com/walletradar/session/application/AccountingUniverseService.java) `shareUniverseMembers` / suffix tolerance.
- [docs/adr/ADR-006-cycle5-bybit-stream-authority-and-earn-subaccount.md](docs/adr/ADR-006-cycle5-bybit-stream-authority-and-earn-subaccount.md) §N18 — the `bybitRefCandidates` suffix-tolerance design this ADR reuses.
