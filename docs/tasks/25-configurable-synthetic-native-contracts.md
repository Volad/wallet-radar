# 25 — Configurable Synthetic Native Contracts

## T-057 — Move known synthetic native contracts from code to ingestion config

- **Module(s):** `ingestion/config`, `ingestion/classifier`, `docs`
- **Roles:** backend-dev

---

## Goal

Replace hardcoded synthetic-native contract exceptions in classifiers with configurable per-network lists.

---

## Product Decisions (Locked)

1. Synthetic/native pseudo contracts are configured per network:
   - `walletradar.ingestion.network.<NETWORK>.synthetic-native-contracts`
2. Classifier must consume this config for lend/vault disambiguation.
3. Initial known configured contract set includes:
   - `ZKSYNC`: `0x000000000000000000000000000000000000800a`
4. Adding new discovered contracts must require config/docs update, not code branching.

---

## Worker Handoff Command

```text
$backend-dev implement docs/tasks/25-configurable-synthetic-native-contracts.md (T-057) end-to-end with tests
```

---

## Implementation Scope

1. Extend ingestion network config model with `synthetic-native-contracts`.
2. Update `LendClassifier` to read synthetic native contracts from config (remove hardcoded zkSync constant).
3. Keep transfer/lp overlap guards consistent by reusing `LendClassifier` instance heuristics.
4. Update `application.yml` with initial known list.
5. Add/adjust tests:
   - positive path with configured synthetic native contract
   - regression path without config (heuristic should not over-classify)

---

## Acceptance Criteria (DoD)

1. No hardcoded zkSync synthetic native contract in classifier code.
2. `synthetic-native-contracts` is bindable from YAML per network.
3. ZKsync lend fixture with extra synthetic native legs remains classified as `LEND_*` with config.
4. Same fixture without configured synthetic native contract does not falsely produce `LEND_*`.
5. Related classifier tests pass.

---

## Validation Status (2026-03-06)

Partial: configuration extraction is implemented, but production-like ZKsync validation showed
remaining gaps for selector-based `BORROW/REPAY` and lend scenarios on synthetic logs.

Follow-up required:
- `docs/tasks/26-zksync-lend-borrow-repay-synthetic-log-classification-fix.md`
