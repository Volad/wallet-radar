# ParaSwap / 1inch

> **Registry family:** DEX, AGGREGATOR  
> **Support:** `ParaSwapNativeSettlementSupport`, `OneInchNativeSettlementSupport`

## Scope

ParaSwap V6 and 1inch aggregator swaps including native ETH settlement legs missing from indexer transfer lists.

## Owned normalized types

- `SWAP` — primary
- `EXTERNAL_TRANSFER_OUT` — `RoutedAggregatorSendClassifier` for outbound-only router sends

## Authoritative evidence

- Router address in registry (DEX/AGGREGATOR)
- ParaSwap selectors in `SwapClassifier`
- Synthetic native inbound leg from settlement support when indexer omits ETH

## Clarification policy

Native settlement enrichment during movement leg extraction.

## Correlation rules

Single-tx atomic swap; no cross-tx pairing.

## Disallowed fallbacks

Bridge start on same-wallet atomic swap shape.

## Related

- [Swap family](../families/swap.md)
