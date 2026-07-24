# Normalization Rules

> **Location:** `docs/pipeline/normalization/rules/` (wiki).  
> Legacy copy at `docs/normalization/` — use wiki path as authoritative.

Explicit rule documents for classification + clarification. Runtime code must not invent rules ad hoc when they are not yet written here.

## Three-Layer Protocol Contract

1. **`protocol-registry.json`** — address-level truth (protocol, family, role)
2. **`backend/src/main/resources/protocols/*.json`** — protocol runtime profile (selectors, hints)
3. **`docs/pipeline/normalization/rules/protocols/*.md`** — human rules (evidence, correlation, fallbacks)

## Post-normalization boundary invariant (venue- and network-neutral)

After normalization, downstream read/query code must be **both venue-agnostic and network-agnostic**.
Network specifics are resolved once, at normalization time, and stamped as **semantic capability
flags** on the normalized record (primitive/enum only — never a resolver/registry type). Read paths
consume the flags; they never call `NetworkAddressFormat.isEvm(...)`, compare `NetworkId` members, or
match a network name embedded in a string such as `lp-position:solana:`.

- **`receiptBearingCollateral`** — lending collateral is a fungible on-chain receipt token (EVM
  aTokens/cTokens) vs receipt-less (Jupiter Lend on Solana, TON). Consumed by `LendingCycleBuilder`.
- **`lpConcentrated`** — concentrated-liquidity LP position with residual-tolerant, snapshot-driven
  closure (terminal `LP_EXIT`, never `LP_EXIT_FINAL`) — currently Solana DLMM/CLMM. Consumed by
  `SessionLpQueryService` / `LpPositionRefreshService` (and carried onto `LpPositionSnapshot`).
- **`custodialOffChain`** — external custody destination (ADR-072).

The single place network specifics are allowed is the normalization builders (EVM / Solana / TON) and
single-network ingestion/enrichment adapters. Enforced by
`ModuleDependencyArchTest.post_normalization_read_query_packages_must_not_depend_on_NetworkAddressFormat`
(sibling to the ADR-052 venue rule) and the `NetworkBranchGuardTest` source scan. See
[ADR-074](../../../adr/ADR-074-network-agnostic-post-normalization-invariant.md) (generalizes
[ADR-052](../../../adr/ADR-052-venue-capability-spi-walletref-normalization-boundary-invariant.md)).

## Rule Source Hierarchy

1. [Product context](../../../overview/01-product-context.md), [Domain glossary](../../../overview/02-domain-glossary.md), [Architecture](../../../overview/03-architecture.md), [Cost basis](../../cost-basis/01-overview.md), accepted [ADRs](../../../adr/INDEX.md)
2. Family and protocol rule documents in this directory
3. Mongo normalized baseline
4. Official protocol docs
5. `OnChainRawTransactionView` evidence
6. Approved baseline behavior (compatibility only)

## Families

- [Spam](families/spam.md)
- [Non-Economic](families/non-economic.md)
- [Swap](families/swap.md)
- [Bridge](families/bridge.md)
- [LP](families/lp.md)
- [Lending](families/lending.md)
- [Vault](families/vault.md)
- [Staking](families/staking.md)
- [Trading](families/trading.md)
- [Transfer](families/transfer.md)
- [Solana](families/solana.md)
- [TON](families/ton.md)
- [Default](families/default.md)
- [Cross-cutting owners](cross-cutting-owners.md)

## Protocols

- [GMX V2](protocols/gmx-v2.md)
- [CoW Swap](protocols/cow-swap.md)
- [Resolv](protocols/resolv.md)
- [Euler](protocols/euler.md)
- [Aave](protocols/aave.md)
- [Uniswap](protocols/uniswap.md)
- [Pendle](protocols/pendle.md)
- [Velodrome](protocols/velodrome.md)
- [LI.FI](protocols/li-fi.md)
- [Bybit](protocols/bybit.md)
- [Dzengi adaptation](dzengi-adaptation.md)
- [Morpho](protocols/morpho.md)
- [Balancer](protocols/balancer.md)
- [Compound](protocols/compound.md)
- [Fluid](protocols/fluid.md)
- [Across](protocols/across.md)
- [ParaSwap / 1inch](protocols/paraswap-1inch.md)
- [PancakeSwap](protocols/pancakeswap.md)

## Related

- [On-chain classification](../02-onchain-classification.md)
- [Transaction types reference](../../../reference/transaction-types.md)
- [Examples](../../../examples/normalization-examples.md)
- [ADR-001](../../../adr/ADR-001-onchain-classification-strangler-refactor.md)

## Shared Requirements

Every family or protocol rule document should state: scope, owned normalized types, authoritative evidence, clarification policy, correlationId rules, matchedCounterparty rules, disallowed fallbacks, regression anchors.
