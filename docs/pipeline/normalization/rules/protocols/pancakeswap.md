# PancakeSwap

> **Registry family:** DEX, LP  
> **Support:** `LpNftClFlowMaterializer`, `LpNativeExitLegEnricher`, `LpRegistryClassifier`

## Scope

PancakeSwap V3 concentrated liquidity NFT LP entry/exit and router swaps on BSC and other networks.

## Owned normalized types

- `LP_ENTRY`, `LP_EXIT`, `LP_FEE_CLAIM`
- `SWAP` — router swaps via registry

## Authoritative evidence

- Position manager / router in `protocol-registry.json`
- NFT mint/burn legs via `LpNftClFlowMaterializer`
- Native exit enrichment when Blockscout omits native payout

## Clarification policy

`BlockScoutNativeSettlementClarificationSupport` for LP rows missing native leg.

## Correlation rules

NFT token id + pool address for position-scoped LP replay.

## Disallowed fallbacks

Fee claim vs principal close conflated — use `LpPrincipalCloseEvidence`.

## Related

- [LP family](../families/lp.md)
- [LP cycle example](../../../../examples/lp-cycle-example.md)
