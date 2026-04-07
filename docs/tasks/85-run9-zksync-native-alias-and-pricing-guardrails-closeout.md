# 85 — Run 9 zkSync Native Alias And Pricing Guardrails Closeout

## Context

Run 9 audit proved that the current live wallet total is close to on-chain
state overall, but full-session ETH AVCO remains financially unsafe because the
`ZKSYNC / ETH` slice is still contaminated.

Confirmed audit findings:

1. `normalized_transactions` still contain duplicated native ETH legs on
   `zkSync` where the same economic movement is emitted both as:
   - `assetContract=0x000000000000000000000000000000000000800a`
   - symbol-only native `ETH`
2. Several `zkSync` swap rows still derive impossible ETH prices from
   `SWAP_DERIVED`.
3. Replay then collapses those bad legs into one `NATIVE:ZKSYNC` position and
   overstates basis / AVCO.

Reference hashes:

- `0xb5e18d789cfa26786efff3abb12b9f1a670c69626fd0d4ca6f9f72503e193401`
- `0x25c189e0a05b2c3652cd4fb6f03ca6a28ad85ced7fd63370ab9957bf7aed8d77`
- `0xa2e409ccdc5c3efd1b26c0039ec40cd64c912991c9fafb9e99fcec94020d21d9`
- `0x0417160bf2983a14fbccb45e646ab34e6f8d84d2572ee3f1c544e3568a324acd`
- `0x5e89a5d2179c576a505b4cebaf459d835b9289faa0f573d789274a0d042f5487`
- `0xb7a9086def86956c896bb9a53326dacee73be2cf17c5741ea7c4e4e6f21c7afc`
- `0xd70f0d0920b6ed2e837143b938254525f68325e4476bd6dfd73b7fd8b6a273a3`
- `0x0d1b391be3c596b495854c04220f4dfa582acf4014cbda76bdd7ec6c8cf1b9e3`

This slice fixes the two highest-signal upstream defects first. It is a
containment fix for rerun safety, not a full `zkSync` semantics rewrite.

## Architect Decision

Keep the current architecture split:

- normalization must not emit duplicate native facts
- pricing must not persist impossible tx-local ratios
- replay and read models stay unchanged unless the upstream contracts are still
  insufficient after rerun

Required contracts:

1. tx-level native `value` is secondary evidence and must not duplicate an
   already-materialized native-token alias transfer
2. `SWAP_DERIVED` is valid only when the target canonical asset appears exactly
   once among non-fee swap flows
3. multi-leg same-canonical swap rows must fall back to safer pricing sources

## BA Scope / Acceptance Criteria

### DoD

1. `zkSync` native ETH alias movements no longer duplicate tx-level `value`.
2. `BRIDGE_IN`, `BRIDGE_OUT`, and plain inbound transfer examples from the audit
   materialize one native principal leg instead of two.
3. Multi-leg same-canonical `SWAP` rows no longer persist impossible
   `SWAP_DERIVED` ETH prices.
4. Ordinary two-leg swap-derived pricing remains unchanged.

### In Scope

- `MovementLegExtractor` native alias dedup
- native alias support in ingestion resolver
- `SwapDerivedPriceResolver` guardrail
- regression tests
- docs and operator guidance

### Out Of Scope

- full `zkSync` same-family swap / continuity semantics redesign
- owner/accounting-universe custody semantics
- principal vs yield accrual policy
- `sessionId` lineage on derived collections

## Backend Tasks

1. `BE-85-01` Deduplicate tx-value-native alias overlap on `zkSync`
   - audit native token alias contracts in ingestion support
   - suppress symbol-only tx-value leg when a matching alias transfer already
     covers the movement

2. `BE-85-02` Guard `SWAP_DERIVED` against multi-leg same-canonical pricing
   - detect repeated canonical symbols within one `SWAP`
   - skip tx-local swap-derived pricing for those flows
   - allow safer fallback pricing instead

3. `BE-85-03` Add regression tests
   - inbound alias dedup
   - bridge alias dedup
   - guarded swap-derived fallback
   - ordinary two-leg swap-derived no-regression

4. `BE-85-04` Update docs
   - domain
   - architecture
   - accounting policy

## Operational Follow-Up

After this slice lands:

1. clear derived state only
2. preserve raw evidence and `historical_prices`
3. rerun the pipeline
4. re-audit the `ZKSYNC / ETH` holding and the reference hashes above
