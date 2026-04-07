# 89 — Run 17 Native Provider Identity And zkSync Across Closeout

## Context

Run 17 proved that replay now materializes again, but two audited correctness
gaps still prevent a fully provable session-level ETH total and AVCO:

1. `on_chain_balances` under-reports native ETH on provider-first networks
   (`ARBITRUM`, `BASE`, `ETHEREUM`, `LINEA`, `OPTIMISM`) as zero even when live
   external balance evidence is positive.
2. same-wallet `Across` bridge continuity is still broken on the audited pair:
   - source:
     `0x9712e051e33e603b22039ef74ed946e78664695aa341a8825a516822aa5f8966`
   - destination:
     `0xc88e8268f32c3cc5ef29c604f69a359422de22d452e255534c3399e9f478be41`
3. session state may remain stale at `ACCOUNTING_REPLAY / RUNNING` even after
   derived replay outputs already exist.

Confirmed run 17 evidence:

- `on_chain_balances` currently stores
  `NATIVE:ARBITRUM/BASE/ETHEREUM/LINEA/OPTIMISM = 0`
- live provider / RPC evidence for the same wallet-network rows is positive
- `Ankr` returns native assets with
  `contractAddress = 0x0000000000000000000000000000000000000000`
- stored raw evidence for `0x9712...` contains only four boundary native alias
  transfers, not the full helper / settlement hop chain used by the synthetic
  test fixture
- current normalized source row is still `EXTERNAL_TRANSFER_OUT`
- current destination row is still `EXTERNAL_TRANSFER_IN`
- session `44d4ca3f-84b9-49c0-8339-03a4f537c385` remains stale at
  `ACCOUNTING_REPLAY / RUNNING` although:
  - `pendingStat = 0`
  - `pendingPrice = 0`
  - `asset_positions > 0`
  - `on_chain_balances > 0`
  - `reconciled_holdings > 0`

## Architect Decision

Keep the remediation narrow and deterministic:

1. treat provider-native zero-address contract payloads as native accounting
   identity, not ERC-20 contract identity
2. extend the audited `zkSync Across` source classifier to accept stored
   calldata-plus-boundary-funding evidence when explorer transfer lists omit
   intermediate route hops
3. keep same-wallet `Across` destination promotion source-led via existing
   pair-linking once the source row becomes `BRIDGE_OUT / Across`
4. heal stale `ACCOUNTING_REPLAY / RUNNING` only when no pending work remains
   and derived replay outputs are already materialized

## BA Scope / Acceptance Criteria

### DoD

1. Provider-native rows returned with
   `contractAddress = 0x0000000000000000000000000000000000000000` materialize as
   `NATIVE:<NETWORK>` in `on_chain_balances`.
2. The audited positive native ETH rows on provider-first networks no longer
   collapse to zero after rerun.
3. `0x9712e051e33e603b22039ef74ed946e78664695aa341a8825a516822aa5f8966`
   resolves as `BRIDGE_OUT / Across` from the real stored raw shape currently
   present in Mongo.
4. The tx keeps principal `ETH -0.689595000000000000` and fee
   `ETH -0.0000240788825`.
5. Existing same-wallet `Across` pair-linking can then promote
   `0xc88e8268f32c3cc5ef29c604f69a359422de22d452e255534c3399e9f478be41`
   into `BRIDGE_IN` on rerun without widening replay semantics.
6. A stale session already past replay/materialization no longer remains stuck
   at `ACCOUNTING_REPLAY / RUNNING`.

### In Scope

- native provider identity normalization for zero-address payloads
- audited `zkSync Across` source typing against real stored raw evidence
- stale replay-state healing
- regression tests
- rerun preparation

### Out Of Scope

- broader `Across` router coverage beyond the audited `zkSync` slice
- `sessionId` lineage on derived collections
- principal-vs-yield policy for `aManWETH`
- residual dust tails on `Katana` / `Unichain`

## Backend Tasks

1. `BE-89-01` Normalize provider-native zero-address payloads into native identity
2. `BE-89-02` Add regression for provider-native current balance materialization
3. `BE-89-03` Relax audited `zkSync Across` source classifier to stored raw shape
4. `BE-89-04` Add regression for calldata-plus-boundary-funding `Across` source typing
5. `BE-89-05` Heal stale `ACCOUNTING_REPLAY / RUNNING` when replay outputs already exist
6. `BE-89-06` Prepare rerun

## Operational Follow-Up

After this slice lands:

1. rerun normalization, clarification, pricing, replay, and balance refresh
2. re-audit:
   - native ETH on `ARBITRUM`, `BASE`, `ETHEREUM`, `LINEA`, `OPTIMISM`
   - `0x9712e051e33e603b22039ef74ed946e78664695aa341a8825a516822aa5f8966`
   - `0xc88e8268f32c3cc5ef29c604f69a359422de22d452e255534c3399e9f478be41`
   - session-level provable ETH AVCO
