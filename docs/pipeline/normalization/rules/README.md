# Normalization Rules

> **Location:** `docs/pipeline/normalization/rules/` (wiki).  
> Legacy copy at `docs/normalization/` — use wiki path as authoritative.

Explicit rule documents for classification + clarification. Runtime code must not invent rules ad hoc when they are not yet written here.

## Three-Layer Protocol Contract

1. **`protocol-registry.json`** — address-level truth (protocol, family, role)
2. **`backend/src/main/resources/protocols/*.json`** — protocol runtime profile (selectors, hints)
3. **`docs/pipeline/normalization/rules/protocols/*.md`** — human rules (evidence, correlation, fallbacks)

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
