# 87 — Run 13 zkSync Routed Send And Native Fee Dedup Closeout

## Context

Run 13 confirmed that the live session ETH total is already correct on-chain,
but the full-session ETH AVCO remains unsafe because one residual `zkSync`
normalization slice is still wrong.

Confirmed findings:

1. `run 12` fixed the previous `zkSync` Aave gateway typing defect:
   - `0x80500d20` now resolves as `LENDING_WITHDRAW`
   - `0x02c205f0` now resolves as `LENDING_DEPOSIT`
   - `0x474cf53d` now resolves as `LENDING_DEPOSIT`
2. The remaining high-signal blocker is `ZKSYNC / ETH`:
   - live quantity: `0.000272034646673678`
   - replay quantity: `0.689536957060923678`
   - overstatement: `0.689264922414250000`
3. The primary broken tx is
   `0x9712e051e33e603b22039ef74ed946e78664695aa341a8825a516822aa5f8966`
   where raw evidence proves `ETH -0.689595000000000000`, but the canonical row
   is still `UNKNOWN` with `UNVERIFIED_ROUTED_SEND`.
4. Several `zkSync` rows still overstate native `ETH` because the native-alias
   fee transfer and the explicit gas-fee leg are both being materialized.

Reference hashes:

- `0x9712e051e33e603b22039ef74ed946e78664695aa341a8825a516822aa5f8966`
- `0x69aa8504aabff01fa86c3f8910ecb186f7d5568e7594c4c1dcfa044291d9f021`
- `0x306505ebea5570d8587ee115132a072f3240338239cdfb7a03e83e1218373397`
- `0x8fb1c9606fd170f13e052e213460925c3d99aef986bc2b1cf74ddffec4bc50e1`
- `0xcfe0fd4d86b0116fecf0ffaaba0a41c5b26a174a7360981e968a6b2ed57f4e96`
- `0x2df84c4d03a6a46305ad1c0dea0434ff26e77698b394a0621bbdff622ab700ae`

This slice stays upstream. It fixes normalization and movement extraction; it
does not redesign AVCO replay.

## Architect Decision

Keep the remediation narrow and deterministic:

1. retire the stale hash-specific `UNKNOWN / UNVERIFIED_ROUTED_SEND` sink for
   `0x9712...`
2. allow the existing canonical fallback path to preserve the proved outbound
   principal movement
3. deduplicate `zkSync` native-alias fee evidence at movement extraction time:
   when the alias transfer exactly matches `gasUsed * gasPrice` to the audited
   fee sink, keep it once as fee evidence and do not also keep it as a
   principal transfer leg

Reasoning:

- the root cause is no longer protocol typing
- the remaining defect is now raw-movement preservation and fee duplication
- replay already behaves correctly when canonical movement evidence is correct
- widening replay rules would hide the upstream defect instead of fixing it

Required contracts:

1. a raw-proven outbound principal movement may not disappear behind a
   hash-specific `UNKNOWN` sink
2. native-alias fee evidence may materialize once only
3. normalized native net must not exceed the raw wallet-boundary native net for
   the same tx

## BA Scope / Acceptance Criteria

### DoD

1. `0x9712e051e33e603b22039ef74ed946e78664695aa341a8825a516822aa5f8966`
   no longer normalizes as `UNKNOWN / UNVERIFIED_ROUTED_SEND`.
2. The same row preserves the audited outbound principal
   `ETH -0.689595000000000000`.
3. On `zkSync`, when a native-alias transfer exactly matches
   `gasUsed * gasPrice` to the audited fee sink, normalization emits that fee
   once only.
4. Canonical native `ETH` net for audited `zkSync` gateway / borrow / repay
   rows matches the raw wallet-boundary native net.
5. The fix does not widen unrelated stop-condition rows or non-`zkSync`
   networks.

### In Scope

- remove the stale routed-send stop-condition for the audited `zkSync` tx
- native-alias fee dedup in movement extraction
- regression tests for routed-send preservation and fee dedup
- docs and rerun guidance

### Out Of Scope

- `sessionId` lineage in derived collections
- severity split for `reconciled_holdings MISMATCH`
- `aManWETH` principal-vs-yield policy
- dust unmatched tails on `Katana` / `Unichain`

## Backend Tasks

1. `BE-87-01` Retire the stale routed-send terminal sink
   - remove the hash-specific `UNKNOWN / UNVERIFIED_ROUTED_SEND` lock for
     `0x9712...`
   - preserve the proved outbound principal through the existing deterministic
     fallback path

2. `BE-87-02` Deduplicate `zkSync` native-alias fee evidence
   - detect native-alias fee transfer to the audited fee sink when quantity
     equals `gasUsed * gasPrice`
   - keep it as fee evidence once
   - prevent a second principal transfer leg for the same fee fact

3. `BE-87-03` Add regression coverage
   - routed-send fixture now resolves as canonical outbound movement
   - `zkSync` native-alias fee + refund rows preserve raw native net without
     doubling
   - keep existing `zkSync` Aave gateway tests green

4. `BE-87-04` Prepare rerun
   - clear derived collections only
   - preserve raw evidence and `historical_prices`
   - rerun normalization, pricing, replay, balance refresh, and reconciliation

## Operational Follow-Up

After this slice lands:

1. clear derived state only
2. preserve `raw_transactions`, `external_ledger_raw`, and `historical_prices`
3. rerun normalization, pricing, replay, balance refresh, and reconciliation
4. re-audit:
   - `ZKSYNC / ETH`
   - full-session ETH AVCO
   - the reference hashes above
