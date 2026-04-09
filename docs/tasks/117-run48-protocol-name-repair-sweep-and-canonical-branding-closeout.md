# 117 - Protocol Name Repair Sweep And Canonical Branding

Status: Implemented

Owner: Codex

Date: 2026-04-08

## Problem

The first clarification-time `protocolName` enrichment slice proved the
pipeline, but two issues remained:

1. startup repair stopped too early
   - it terminated on the first batch with `updated = 0`
   - unresolved early rows could therefore starve later resolvable rows
2. canonical protocol branding was inconsistent
   - `LiFi` instead of `LI.FI`
   - `FLUID` instead of `Fluid`
   - `Merkle` instead of `Merkl`
   - `Paraswap` instead of `Velora/ParaSwap`

This made UI filtering/debugging weaker than intended even when canonical
economic rows were otherwise correct.

## Decision

Keep `protocolName` enrichment clarification-adjacent, but split repair into a
real sweep:

- clarification-time enrichment remains batch-oriented and best-effort
- startup repair now pages deterministically through all candidate rows instead
  of stopping when the first batch contains only unresolved rows
- protocol branding is canonicalized centrally so registry growth and historical
  repair converge on one product-facing name

## Implemented

### Repair sweep

- added `ProtocolNameCanonicalizer`
- added cursor-style repair scan in `ProtocolNameEnrichmentQueryService`
- added `processRepairSweep(...)` in `ProtocolNameEnrichmentService`
- updated `ProtocolNameRepairJob` to run the full sweep instead of
  `while(processNextBatch() > 0)`

### Canonical protocol names

Canonical names now normalize to:

- `LI.FI`
- `Fluid`
- `Merkl`
- `Velora/ParaSwap`

Historical rows with legacy aliases are eligible for repair without a full
normalization rerun.

### Registry growth

Added/updated first-wave registry coverage:

- canonical brand rename for `LI.FI`
- canonical brand rename for `Velora/ParaSwap`
- canonical brand rename for `Fluid`
- canonical brand rename for `Merkl`
- official Lagoon factory entries on:
  - Ethereum
  - Arbitrum
  - Base
  - Avalanche / Mantle / Katana

## Acceptance Criteria

- resolvable rows later in the scan are no longer blocked by unresolved early
  rows
- protocol-brand aliases converge to one canonical product-facing name
- full normalization rerun is still not required for label-only protocol
  updates
- unresolved vault/pool instances are not guessed; unknowns remain unknown
  until address evidence exists

## Remaining Backlog

Still requires additional coverage work:

- Yearn vault-instance coverage beyond one generic entry
- SushiSwap helper / route-processor coverage beyond current router entries
- Balancer helper / gauge / pool-adjacent addresses where the vault alone is
  insufficient
- Lagoon vault-instance coverage beyond factory-level addresses
- more exact Velora/ParaSwap helper coverage

Those follow-ups extend registry/detection coverage, but do not require a
pipeline-contract change.
