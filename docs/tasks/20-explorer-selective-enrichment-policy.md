# 20 — Explorer selective enrichment policy (details vs logs evidence)

## T-048 — SyncMethod-driven enrichment order with strict evidence policy

- **Module(s):** `ingestion/job/classification`, `ingestion/classifier`, `ingestion/adapter/evm/explorer`, `docs`
- **Roles:** business-analyst + system-architect requirements implemented by worker

### System Architect decision

Introduce a unified selective enrichment pipeline in normalization/classification that is driven by `RawTransaction.syncMethod` and data availability in `rawData`.

Design constraints:

- Do not introduce dedicated enrichment state fields on raw transaction; rely on:
  - `normalizationStatus` lifecycle
  - presence/absence of data in `rawData` (`explorer.details`, `logs`)
- Keep one shared flow engine for explorers, with network method-specific stage order:
  - `BLOCKSCOUT`: `details -> reclassify -> logs -> reclassify`
  - `ETHERSCAN`: `logs -> reclassify` (details optional fallback only)
- Preserve raw payload provenance:
  - store explorer details under `rawData.explorer.details`
  - keep canonical receipt logs in `rawData.logs`
- Avoid replacement-loss on enrichment merge: receipt/details merge must preserve existing explorer payload (`tx`, `tokenTransfers`, `internalTransfers`).

### Business Analyst acceptance criteria (DoD)

1. Enrichment stage order is selected by `RawTransaction.syncMethod`.
2. For `BLOCKSCOUT` low-confidence tx:
   - if `rawData.explorer.details` is missing, fetch details and reclassify;
   - if still low-confidence and canonical logs are missing, fetch receipt logs and reclassify.
3. For `ETHERSCAN` low-confidence tx:
   - if canonical logs are missing, fetch receipt logs and reclassify;
   - details fetch is not part of mandatory path.
4. `Explorer details` are context-only evidence:
   - allowed for method/function/address/status hints;
   - not a source of economic flow creation by itself.
5. Economic flows/legs are produced from:
   - canonical receipt logs;
   - synthetic fallback from explorer transfers only when logs are unavailable.
6. Synthetic fallback confidence is capped (lower than canonical logs path).
7. Repeated normalization run for same input is deterministic (same event set/order/confidence).
8. No duplicate meaning in output: same movement is not emitted as both transfer and swap/lp due to enrichment stage transitions.

### Worker implementation scope

- In `ClassificationProcessor`:
  - implement syncMethod-based selective enrichment order;
  - add details enrichment call path via `ExplorerProvider#getTransactionDetails`;
  - reclassify after each successful enrichment stage;
  - cap confidence when evidence is synthetic-only.
- In `RawTransactionNormalizationView`:
  - add merge API for details (`mergeTransactionDetails`);
  - preserve previous raw/explorer payload when merging receipt/details;
  - distinguish synthetic logs from canonical receipt logs.
- In explorer contracts:
  - keep `getTransactionDetails` in `ExplorerProvider` and implementations;
  - ensure payload is persisted in `rawData.explorer.details`.

### Tests

- `ClassificationProcessorTest`
  - blockscout order: details called before receipt on low-confidence tx
  - etherscan order: receipt path only
  - synthetic-only evidence confidence cap applied
- `RawTransactionNormalizationViewTest`
  - details merge + alias reading
  - receipt merge preserves explorer and replaces synthetic logs with canonical logs
- Existing explorer provider tests
  - `getTransactionDetails` mapping and endpoint contract per provider

