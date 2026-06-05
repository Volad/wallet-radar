# Morpho

> **Registry family:** LENDING, YIELD (vault)  
> **JSON profile:** `backend/src/main/resources/protocols/morpho.json`  
> **Semantic classifier:** `MorphoProtocolSemanticClassifier`

## Scope

Morpho Bundler3 swaps, collateral borrow, and MetaMorpho vault deposit/withdraw on supported networks.

## Owned normalized types

- `SWAP` — bundler swap routes
- `BORROW`, `REPAY` — collateral operations
- `VAULT_DEPOSIT`, `VAULT_WITHDRAW` — MetaMorpho vault lifecycle
- `LENDING_DEPOSIT`, `LENDING_WITHDRAW` — when routed through lending family

## Authoritative evidence

- Contract in `protocol-registry.json` with family LENDING or YIELD
- `MorphoProtocolSemanticClassifier` semantic hints from calldata
- `LendingClassifier` / `VaultSemanticClassifier` terminal mapping

## Clarification policy

Full-receipt when bundler multi-call obscures leg quantities.

## Correlation rules

Vault/account lifecycle keyed by Morpho market/vault address.

## Disallowed fallbacks

Generic swap heuristic when bundler semantic hint is present.

## Related

- [Lending family](../families/lending.md)
- [Vault family](../families/vault.md)
