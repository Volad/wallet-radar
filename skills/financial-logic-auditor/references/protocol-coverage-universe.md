# Protocol Coverage Universe

## Purpose

This reference defines the minimum protocol-coverage expectation for the financial auditor.
It is a seed universe, not a hardcoded final list.
Use it to prioritize protocol identification and external-flow counterparty attribution across live user history.

## Coverage Rule

For every materially relevant raw flow:

- attempt protocol identification first
- if the flow is still external, attempt counterparty attribution second
- if neither can be resolved, state whether the remainder is provisional or irreducible on the current evidence basis

Do not stop at generic labels such as `EXTERNAL_INBOUND`, `EXTERNAL_TRANSFER_OUT`, `unknown contract`, or `unknown DeFi interaction` when reusable identification is possible.

## Priority Tiers

### Tier 1

Must-check protocols and venues that frequently appear in active EVM user history and often affect financial semantics:

- Aave
- Euler
- Uniswap
- 1inch
- Paraswap
- PancakeSwap
- Trader Joe
- LFJ
- Fluid
- Lagoon
- Paradex
- Lighter

### Tier 2

Check when transaction structure, addresses, asset movements, or historical usage patterns suggest relevance:

- major bridges
- major vault protocols
- major liquid staking and restaking protocols
- major perpetual and margin venues
- major stable swap and meta-aggregator routers
- materially present protocols from the current DeFiLlama top universe

### Tier 3

Opportunistic coverage for lower-frequency protocols when evidence indicates repeated material presence in the dataset.

## Protocol Identification Heuristics

Attempt protocol attribution using, in order:

1. `protocol-registry.json`
2. direct contract address recognition
3. known router, vault, pool, pair, escrow, or bridge endpoint addresses
4. method selectors and transaction structure
5. token movement patterns and lifecycle semantics
6. venue correlation
7. explorer-visible contract identity when consistent with production-available evidence

Protocol attribution should end in one of:

- exact protocol attribution
- protocol-family attribution
- provisional attribution
- irreducibly unknown

## Counterparty Attribution For External Flows

For `EXTERNAL_INBOUND`, `EXTERNAL_TRANSFER_OUT`, and equivalent external custody movements, attempt counterparty assignment from:

- protocol or venue identity already established for the flow
- direct counterparty address recognition
- known deposit, withdrawal, router, vault, bridge, or settlement addresses
- repeated address behavior across the same dataset
- stable transaction-shape patterns tied to one protocol or venue family
- bridge pairing or venue correlation that reveals the counterparty family

Counterparty output should include:

- counterparty label
- counterparty type
- confidence: `HIGH`, `MEDIUM`, `LOW`, or `IRREDUCIBLE`
- attribution basis

## Counterparty Types

Use one of:

- `PROTOCOL`
- `ROUTER`
- `BRIDGE`
- `CEX`
- `MARKET_MAKER`
- `PERSONAL_WALLET`
- `UNKNOWN_CONTRACT`
- `UNKNOWN_EOA`
- `OTHER`

## Negative Rules

- Do not assign a protocol only because an asset is commonly used there.
- Do not assign a counterparty only because one tx looks similar to a known flow.
- Do not use explorer-only labels that are unavailable to production if they contradict raw evidence.
- Do not collapse distinct venues or protocol families into one label when the evidence supports a narrower attribution.
- Do not leave repeated high-similarity flows unattributed if a reusable rule can classify them.
