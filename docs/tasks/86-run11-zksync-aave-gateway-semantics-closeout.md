# 86 — Run 11 zkSync Aave Gateway Semantics Closeout

## Context

Run 11 removed the previous `zkSync` native-alias duplication and pricing
guardrail blockers, but the session-level ETH AVCO is still unsafe because
three `zkSync` Aave gateway selectors continue to normalize through the wrong
families.

Confirmed findings:

1. `0x80500d20` (`withdrawETH(address,uint256,address)`) still materializes as
   `LENDING_DEPOSIT`
2. `0x02c205f0`
   (`supplyWithPermit(address,uint256,address,uint16,uint256,uint8,bytes32,bytes32)`)
   still materializes as `UNWRAP`
3. `0x474cf53d` (`depositETH(address,address,uint16)`) still materializes as
   `LP_EXIT`
4. replay is downstream-correct for custody continuity, but these bad tx types
   reopen `ZKSYNC / ETH` quantity and basis leakage

Reference hashes:

- `0xb20600840451280027707eee9330bfbea5737063ec9f648cca425657d61aa35a`
- `0x2d19722f7d6d9e21bb0a3af5dc5b5c6cc25029e42ef2aece2acda2b1b73d1a62`
- `0x2df84c4d03a6a46305ad1c0dea0434ff26e77698b394a0621bbdff622ab700ae`
- `0xcfe0fd4d86b0116fecf0ffaaba0a41c5b26a174a7360981e968a6b2ed57f4e96`
- `0x1e8c5df48903404173f82c0bacc872a32a179124a87b088321b6e1e31260e69e`
- `0xb7f3cd6b871b410276f3254618cf24572385408e376cdfd442b3cc58d82288ee`
- `0x4466d42d854d6dd9396146b4573e73c5dd2b9ac2d597fc72c841a7f31bec7a64`

This slice fixes the normalization semantics first. It does not redesign the
entire replay engine.

## Architect Decision

Introduce a narrow `zkSync` Aave gateway classifier instead of widening global
heuristics.

Reasoning:

- the failing selectors are specific, audited, and repeatable
- the current replay semantics are already correct for
  `LENDING_DEPOSIT / LENDING_WITHDRAW`
- changing generic `UNWRAP`, `LP_EXIT`, or transfer heuristics would create
  unnecessary blast radius

Required contracts:

1. selector-specific classification must run before generic LP / unwrap /
   fallback stages
2. the selector override must also prove the expected `ETH/WETH/aZksWETH`
   movement shape
3. corrected rows must replay to current on-chain quantity without synthetic
   native `ETH` carry

## BA Scope / Acceptance Criteria

### DoD

1. `withdrawETH(...)` normalizes to `LENDING_WITHDRAW`.
2. `supplyWithPermit(...)` normalizes to `LENDING_DEPOSIT`.
3. `depositETH(...)` normalizes to `LENDING_DEPOSIT`.
4. Selector override does not trigger when the expected wallet-boundary shape
   is absent.
5. Replay of the corrected `zkSync` Aave gateway lifecycle leaves no residual
   native `ETH` after redeposit and carries basis into `aZksWETH`.

### In Scope

- narrow method-aware family classifier for audited `zkSync` Aave gateway calls
- `Aave` runtime profile update for selector documentation
- regression tests for normalization and replay
- docs and operator rerun guidance

### Out Of Scope

- `sessionId` lineage on derived collections
- owner/accounting-universe transfer semantics
- yield/accrual policy for `aManWETH`
- non-ETH spam / debt / operator-noise mismatch ranking

## Backend Tasks

1. `BE-86-01` Add method-aware `zkSync` Aave gateway classifier
   - cover selectors `0x80500d20`, `0x02c205f0`, `0x474cf53d`
   - require the audited movement shape
   - emit canonical lending families

2. `BE-86-02` Update `Aave` runtime profile and docs
   - add gateway selectors to `protocols/aave.json`
   - document the gateway semantics in the protocol rule doc

3. `BE-86-03` Add regression tests
   - classifier tests for each selector
   - replay regression proving no residual `ZKSYNC / ETH` leak after
     `WETH -> aZksWETH -> ETH -> aZksWETH`

4. `BE-86-04` Prepare rerun
   - clear derived collections only
   - preserve raw evidence and pricing cache
   - rerun and re-audit `ZKSYNC / ETH`

## Operational Follow-Up

After this slice lands:

1. clear derived state only
2. preserve `raw_transactions`, `external_ledger_raw`, and `historical_prices`
3. rerun normalization, pricing, replay, balance refresh, and reconciliation
4. re-audit:
   - `ZKSYNC / ETH`
   - session-level ETH AVCO
   - reference hashes above
