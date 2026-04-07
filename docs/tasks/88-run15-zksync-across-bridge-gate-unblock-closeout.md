# 88 — Run 15 zkSync Across Bridge Gate Unblock Closeout

## Context

Run 15 proved that the current rerun did not fail because of broad replay
drift. It stopped before replay/materialization because one on-chain row still
blocks `ACCOUNTING_REPLAY`:

1. `asset_positions = 0`
2. `on_chain_balances = 0`
3. `reconciled_holdings = 0`
4. the only blocking row is:
   - `ZKSYNC`
   - `0x9712e051e33e603b22039ef74ed946e78664695aa341a8825a516822aa5f8966`
   - `status = NEEDS_REVIEW`
   - `excludedFromAccounting = false`
   - `missingDataReasons = ["CLASSIFICATION_FAILED"]`

Run 15 also proved that the tx is not a generic unknown routed send. Current
raw evidence shows a same-wallet `Across` bridge path:

- source tx on `zkSync`:
  `0x9712e051e33e603b22039ef74ed946e78664695aa341a8825a516822aa5f8966`
- destination tx on `Arbitrum`:
  `0xc88e8268f32c3cc5ef29c604f69a359422de22d452e255534c3399e9f478be41`
- follow-up Aave deposit on `Arbitrum`:
  `0x543f0944f4c8df9551ce66b2f6290b64f1219a3a70e008ad033c28bb482da589`

The source-side tx currently breaks for two reasons:

1. `MovementLegExtractor` still leaves `zkSync` system fee-sink refund legs as
   positive principal movement, so the tx no longer looks outbound-only.
2. there is still no deterministic source-side `Across` classifier for the
   audited `zkSync` routed selector `0x27ad57d5`.

Even after source typing, the current `AcrossBridgePairLinkService` would still
miss the pair because:

- it accepts destination only as `BRIDGE_IN`
- the audited destination is currently `EXTERNAL_TRANSFER_IN`
- the audited source/destination delta is `11s`, while the current link window
  is `5s`

## Architect Decision

Keep the remediation narrow and deterministic. Fix the pipeline at the
earliest correct layer instead of widening replay:

1. treat `zkSync` native-alias movements between the tracked wallet and the
   audited system fee sink `0x0000000000000000000000000000000000008001` as fee
   lifecycle evidence, not as principal transfer legs
2. add a narrow source-side `Across` bridge-start classifier for audited
   `zkSync` routed sends with selector `0x27ad57d5`
3. allow source-led same-wallet `Across` continuity to materialize against a
   bounded `EXTERNAL_TRANSFER_IN` destination and upgrade that destination into
   `BRIDGE_IN`
4. when replay is gate-blocked by active review work, persist the session stage
   as `BLOCKED`, not `COMPLETE`

## BA Scope / Acceptance Criteria

### DoD

1. `0x9712e051e33e603b22039ef74ed946e78664695aa341a8825a516822aa5f8966`
   no longer remains `UNKNOWN / NEEDS_REVIEW`.
2. The same tx resolves as source-side `BRIDGE_OUT` with protocol `Across` and
   preserves principal `ETH -0.689595000000000000`.
3. `zkSync` fee-sink precharge/refund alias transfers no longer survive as
   principal movement legs.
4. `0xc88e8268f32c3cc5ef29c604f69a359422de22d452e255534c3399e9f478be41`
   can be linked from the source row under bounded same-wallet `Across`
   continuity even when it initially normalized as `EXTERNAL_TRANSFER_IN`.
5. The linked destination is promoted to `BRIDGE_IN`, receives
   `correlationId/matchedCounterparty/continuityCandidate`, and becomes replay
   carry-ready.
6. When accounting replay is blocked by active review rows, session pipeline
   state is stored as `ACCOUNTING_REPLAY / BLOCKED`, not `COMPLETE`.

### In Scope

- `zkSync` fee-sink movement extraction netting
- audited `zkSync Across` source-side bridge classification
- bounded same-wallet `Across` pair linking to `EXTERNAL_TRANSFER_IN`
- replay gate status semantics
- regression tests
- docs and rerun preparation

### Out Of Scope

- broad `Across` router coverage outside the audited `zkSync` routed-send slice
- `sessionId` lineage on derived collections
- `aManWETH` principal-vs-yield policy
- residual dust tails on `Katana` / `Unichain`

## Backend Tasks

1. `BE-88-01` Net `zkSync` fee-sink native alias movements
2. `BE-88-02` Add audited `zkSync Across` source bridge typing
3. `BE-88-03` Extend `Across` pair linking for `EXTERNAL_TRANSFER_IN`
4. `BE-88-04` Correct replay gate state semantics
5. `BE-88-05` Add regression coverage
6. `BE-88-06` Prepare rerun

## Operational Follow-Up

After this slice lands:

1. rerun normalization, clarification, pricing, replay, on-chain balance
   refresh, and reconciliation
2. re-audit:
   - `0x9712e051e33e603b22039ef74ed946e78664695aa341a8825a516822aa5f8966`
   - `0xc88e8268f32c3cc5ef29c604f69a359422de22d452e255534c3399e9f478be41`
   - full-session ETH AVCO
   - non-empty `asset_positions`, `on_chain_balances`, and
     `reconciled_holdings`
