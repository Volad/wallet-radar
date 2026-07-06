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

Position-scoped LP replay identity is `lp-position:<network>:<nfpmContractLowercased>:<tokenId>`
(RC-1, ADR-018). PancakeSwap V3 is a Uniswap-V3-interface fork sharing the same NFPM selectors;
the position identity is keyed by the **NFPM contract** (`rawData.to`, e.g. PancakeSwap V3
`0x46a15b0b27311cedf172ab29e4f4766fbe7f4364` on BASE/ARBITRUM), never by the protocol slug.
Entry (often a generic `multicall`) and exit (registry-classified) therefore resolve to the same
pool. The protocol slug is display-only — the entry must not be silently mislabeled `uniswap`.

### Staking/farming wrapper canonicalization (RC-5, ADR-018)

When the LP NFT is staked into **MasterChefV3** (`STAKE_CONTRACT`, e.g. BASE
`0xc6a2db661d5a5690172d8eb0a7dea2d3008665a3`, the Arbitrum MasterChefV3), the wrapper custodies the
NFT and `decreaseLiquidity`/`collect`/`harvest`/`withdraw` calls hit the wrapper — so `rawData.to` is
the wrapper, not the NFPM. The wrapper must be **canonicalized to its underlying NFPM** via the
data-driven `underlyingPositionManager` registry mapping (`LpStakingWrapperResolver`), so the staked
flows key to `lp-position:<net>:<NFPM>:<tokenId>` — the SAME pool as the unstaked entry — and the
trapped basis flows back. Distinct NFPM positions sharing a numeric tokenId stay separate. An
unregistered wrapper that custodies an NFPM NFT keeps a contract-keyed identity and emits a one-time
coverage `WARN`.

## Disallowed fallbacks

Fee claim vs principal close conflated — use `LpPrincipalCloseEvidence`.

## Related

- [LP family](../families/lp.md)
- [LP cycle example](../../../../examples/lp-cycle-example.md)
