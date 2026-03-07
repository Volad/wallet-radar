# ADR-027: Configurable Synthetic Native Contracts for Classifier Disambiguation

**Date:** 2026-03-06  
**Status:** Accepted  
**Deciders:** Product Owner, Business Analyst, System Architect

---

## Context

Some networks expose synthetic/pseudo native token transfer legs in explorer-derived logs.
Example observed in production data: zkSync `0x000000000000000000000000000000000000800a`.

These legs can break lend/vault disambiguation heuristics by introducing extra transfer assets in the same transaction,
which leads to false `EXTERNAL_*` classification instead of `LEND_*` semantics.

The previous implementation used a hardcoded special-case in `LendClassifier` for a single contract.
This is not scalable and requires code changes for each newly discovered network-specific synthetic native contract.

---

## Decision

1. Move synthetic native contract list into ingestion network configuration:
   - `walletradar.ingestion.network.<NETWORK>.synthetic-native-contracts`
2. Use this list in `LendClassifier` heuristics (vault deposit/withdraw pair resolution).
3. Remove classifier hardcoded network-specific native synthetic contract constants.
4. Reuse `LendClassifier` instance heuristics from `TransferClassifier` and `LpClassifier` (no static hardcoded path).

Initial configured set (observed in current data):
- `ZKSYNC`: `0x000000000000000000000000000000000000800a`

---

## Consequences

### Positive

- Adding newly discovered synthetic native contracts becomes a config change, not a code release.
- Lend/vault classification becomes more resilient to network-specific pseudo transfer legs.
- Reduces regression risk when extending supported EVM networks.

### Trade-offs

- Wrong config values can suppress legitimate transfer legs in lend/vault disambiguation path.
- Requires operational discipline to update config from observed data.

---

## Implementation Notes

- Config model extended in `IngestionNetworkProperties.NetworkIngestionEntry`.
- `application.yml` contains initial `synthetic-native-contracts` for zkSync.
- Unit tests cover behavior with and without configured synthetic native contracts.

---

## References

- ADR-012 (unified ingestion network config)
- ADR-025 (normalized transactions status pipeline)
- ADR-026 (EVM explorer-first ingestion v2)
