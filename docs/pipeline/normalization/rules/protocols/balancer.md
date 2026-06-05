# Balancer

> **Registry family:** DEX, LP  
> **JSON profile:** `backend/src/main/resources/protocols/balancer.json`  
> **Semantic classifier:** `BalancerProtocolSemanticClassifier`  
> **Special handler:** `BALANCER_VAULT`

## Scope

Balancer Vault swap, join (LP entry), and exit (LP exit) through vault special handler.

## Owned normalized types

- `SWAP` — vault swap
- `LP_ENTRY`, `LP_EXIT` — pool join/exit
- `MULTI_ASSET_RECEIPT_LP` shapes via `MultiAssetReceiptLpClassifier`

## Authoritative evidence

- Vault contract address in registry with `specialHandler: BALANCER_VAULT`
- Transfer legs proving pool token mint/burn
- Semantic hint from `BalancerProtocolSemanticClassifier`

## Clarification policy

Receipt enrichment when join/exit leg amounts incomplete on explorer indexers.

## Correlation rules

Pool ID in metadata; LP correlation via pool address.

## Disallowed fallbacks

Plain `EXTERNAL_TRANSFER` when vault join/exit selectors match.

## Related

- [LP family](../families/lp.md)
- [Swap family](../families/swap.md)
