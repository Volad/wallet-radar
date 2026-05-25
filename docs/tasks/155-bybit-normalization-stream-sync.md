# Task 155 — Bybit normalization gaps (audit) and per-stream sync visibility

## Goal (one sentence)

Align Bybit ingestion with `results/required-changes.md` for supported continuity streams and expose **per-stream last sync** so refresh/backfill is unambiguous.

## Acceptance criteria (DoD)

1. **Planner**: Initial and incremental Bybit backfill plans include segments for `INTERNAL_TRANSFER`, `UNIVERSAL_TRANSFER`, `DEPOSIT_INTERNAL`, and `CONVERT_HISTORY` (same time-range rules as other integration streams; transfer list streams respect the same 7-day effective window cap as other Bybit list APIs where applicable).
2. **Extraction**: Rows from internal/universal transfer streams are `basisRelevant=true`, use corridor-aware sub-account wallet refs (`BYBIT:<uid>:UTA|FUND|EARN`) from `fromAccountType` / `toAccountType` and movement direction.
3. **Normalization**: `INTERNAL_TRANSFER` canonical rows produce a non-empty `TRANSFER` flow with signed quantity via `BybitCanonicalTransactionBuilder` (no empty-flow silent drop).
4. **API**: `GET /api/v1/sessions/{sessionId}/settings` returns `integrations[].streamSync[]` for Bybit with one entry per `BybitIntegrationStream` enum value: `stream`, `lastSegmentCompletedAt` (max `completedAt` over COMPLETE `backfill_segments`), `newestStoredEventAt` (max `timeUtc` in `bybit_extracted_events` for that `sourceStream`). Non-Bybit integrations return an empty array.
5. **UI**: Settings → Integrations (Bybit card) shows a table of streams and the two timestamps when `streamSync` is non-empty.
6. **Regression**: Backend and frontend unit tests updated; `./gradlew :backend:test` passes.

## Edge cases (scope)

| Case | In scope |
|------|----------|
| Legacy rows without `walletRef` suffix | Fallback suffix in `BybitNormalizationService.dimensionWalletRefIfMissing` (FUND for internal/universal stream). |
| Stream never pulled | `lastSegmentCompletedAt` and `newestStoredEventAt` null for that stream row. |
| Provider balance vs replay ledger | **Out of scope** for this task (separate dashboard/projection work per audit P0). |
| Earn/Launchpool semantics, loan/collateral types | **Out of scope** unless already covered by existing extraction; remaining audit items are follow-up tasks. |

## Task breakdown (implementation order)

1. `BybitBackfillSegmentPlanner` — add four streams; fix incremental planner consistency; extend tests.
2. `BybitExtractionService` — basis relevant + corridor wallet ref + `isBasisRelevantCanonicalType` update; tests.
3. `BybitCanonicalTransactionBuilder` — `INTERNAL_TRANSFER` in `mappedFlows`; test.
4. `BybitIntegrationStreamSyncQueryService` + wire `SessionSettingsQueryService` / `SessionController` / DTOs; test mocks updated.
5. Frontend models + integrations section template/styles; specs.
6. Docs: this file, ADR-005, `docs/04-api.md` sample.

## Risk notes

- **API shape**: New field on settings response; older clients ignore `streamSync`.
- **Mongo load**: Two small aggregations per settings GET for Bybit; acceptable for snapshot-style settings page.

## References

- `results/required-changes.md` (independent audit backlog)
- ADR: `docs/adr/ADR-005-bybit-per-stream-sync-metadata.md`
