# 115 - Protocol Identity Enrichment Proposal

## Status

Proposed

## Problem

WalletRadar often resolves the transaction `type` correctly while still leaving `protocolName` empty. This is common on:

- swaps through routers and aggregators
- lending interactions through gateways
- vault interactions through factory-deployed contracts
- LP interactions through pools, gauges, and helpers
- bridge routes that use a route layer plus a downstream settlement protocol

The current design assigns `protocolName` only as a side effect of the type classifier and direct hits in `protocol-registry.json`. This is too narrow.

## Decision

Introduce a dedicated **protocol identity enrichment** capability, separated from transaction type classification.

## Why

`type` answers “what economic action happened”.

`protocol identity` answers “which protocol or protocol product owned the touched contract or lifecycle step”.

These are related but not equivalent concerns.

Keeping them coupled leads to a structural blind spot:

- type can be correct
- protocol label can still be absent

## Scope

### In scope

- explicit protocol identity model
- static registry sync from official protocol deployments
- derived contract ownership for factory/registry-driven protocols
- safe lifecycle inheritance for already linked canonical rows
- API and UI exposure for filtering and debugging

### Out of scope

- speculative protocol guesses from token symbols
- live RPC-based protocol resolution on user reads
- replacing the existing economic type classifier

## Proposed Data Model

Persist or derive:

- `protocolSlug`
- `protocolName`
- `protocolVersion`
- `protocolProduct`
- `contractRole`
- `protocolIdentitySource`
- `protocolIdentityConfidence`

Recommended `protocolIdentitySource` values:

- `REGISTRY_STATIC`
- `REGISTRY_DERIVED_FACTORY`
- `REGISTRY_DERIVED_REGISTRY`
- `LIFECYCLE_INHERITED`
- `SEMANTIC_FALLBACK`

Recommended `contractRole` values:

- `ROUTER`
- `FACTORY`
- `POOL`
- `VAULT`
- `GAUGE`
- `POSITION_MANAGER`
- `GATEWAY`
- `HELPER`
- `PROXY`
- `DIAMOND`
- `REWARD_DISTRIBUTOR`
- `RECEIPT_TOKEN`

## Pipeline Placement

Recommended order:

1. raw ingestion
2. type classification
3. clarification / linking
4. protocol identity enrichment
5. canonical persistence
6. cost basis / ledger / UI reads

## Initial Target Protocols

### P0 static coverage

- Uniswap
- LI.FI
- Velora / ParaSwap
- Aave periphery and gateways
- Sushi
- Balancer routers/core

### P1 derived coverage

- Yearn
- Lagoon
- Balancer pools
- selected LP and staking ecosystems where the touched contracts are factory products

## Implementation Plan

### Phase 0

Define the model and persistence contract.

Tasks:

- add protocol identity fields
- preserve backward compatibility with `protocolName`
- extend DTOs and query models

### Phase 1

Static entrypoint sync from official sources.

Tasks:

- create import pipeline or checked-in generated artifact
- expand supported entrypoints for target protocols
- add tests for high-frequency currently-missing addresses

### Phase 2

Derived factory/registry ownership.

Tasks:

- Yearn: `AddressProvider -> ReleaseRegistry -> VaultFactory / Registry -> vault instances`
- Lagoon: `VaultFactory -> vault instances`
- Balancer: `Vault / pool factories -> pool instances`

### Phase 3

Lifecycle inheritance.

Tasks:

- propagate protocol identity across request/settlement and helper rows only when canonical linkage exists
- keep route-layer and destination-layer identity distinct when both matter

### Phase 4

API and UI support.

Tasks:

- filter by protocol slug
- optionally filter by contract role
- expose provenance for debugging

## Acceptance Criteria

1. `protocolName` coverage materially improves on `SWAP`, `LENDING_*`, `VAULT_*`, `LP_*`, and supported `BRIDGE_*` rows.
2. No protocol label is attached without a traceable source.
3. Yearn and Lagoon vault rows resolve via factory/registry ownership, not one-off manual vault lists.
4. Entry contracts published in official docs are represented in the local protocol identity data.
5. UI filtering uses stable slugs rather than free-text labels.

## Evidence

Current confirmed on-chain rows with missing `protocolName` include large counts in:

- `SWAP`
- `BRIDGE_OUT`
- `BRIDGE_IN`
- `LENDING_DEPOSIT`
- `VAULT_DEPOSIT`
- `VAULT_WITHDRAW`
- `LP_ENTRY`
- `LP_EXIT`

Current registry undercovers:

- `Yearn`
- `SushiSwap`
- `Lagoon`
- `Balancer`
- `CoW Swap`
- `Euler`

## Sources

- Uniswap deployment docs
- Balancer deployment docs
- Yearn V3 vault management and address docs
- Lagoon networks and addresses docs
- LI.FI smart contract and Composer docs
- Sushi official docs and SushiXSwap official repository
- Velora official developer docs
