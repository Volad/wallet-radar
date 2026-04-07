# Run 39 LI.FI Destination Bridge Continuity Closeout

Status: Implemented, rerun prepared

Goal:

Close the destination-side `LI.FI / Jumper` bridge-settlement gap confirmed by
`run/39`, while preserving the already-restored `BYBIT` trade clustering and
the current bridge-start behavior.

Related inputs:

- [run/39 audit report](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/39/clarification-readiness-audit.md)
- [run/39 audit summary](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/39/audit_summary.json)
- [run/39 bridge pair focus](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/39/bridge_pair_focus.json)
- [run/39 LI.FI status audit summary](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/39/lifi_status_audit_summary.json)
- [LI.FI protocol rules](../normalization/protocols/li-fi.md)
- [Bridge family rules](../normalization/families/bridge.md)
- [Accounting policy](../03-accounting.md)

## Problem Statement

`run/39` confirmed:

- the previous `BYBIT SWAP / PENDING_PRICE` regression is fixed
- the explicit audited pair
  `0x9f6983d00441ed13bf45e1b7ac34e94540fb61f58e4a9a2189826b1e761a2f7f`
  -> `0x7d8c79a327637fda080bcfa9204181359de791ccff95dd4b2f1b020b8af0b678`
  is a real bridge route
- source-side `BRIDGE_OUT` is already correct
- destination-side `0x7d8c79...` is still normalized as `EXTERNAL_TRANSFER_IN`
  even though official LI.FI status evidence points to the same route

Systemic audit result:

- `99` route-tagged `LI.FI / Jumper` source bridge-outs have a receiving tx hash
- only `40` are currently materialized as `BRIDGE_IN`
- `44` still sit as `EXTERNAL_TRANSFER_IN`
- `11` receiving tx hashes are missing from the current normalized outcome

Important nuance:

- `43` of the `44` current `EXTERNAL_TRANSFER_IN` rows already looked the same
  in the current etalon
- therefore “same as baseline” is not sufficient for bridge correctness

## Scope

In scope:

- protocol-owned destination-side `LI.FI / Jumper` bridge pair evidence
- deterministic materialization of source/destination bridge linkage when the
  receiving tx hash is known
- promotion of destination-side rows from `EXTERNAL_TRANSFER_IN` to
  `BRIDGE_IN` when the pair is proven
- replay-safe continuity flags only when current data proves plain move-basis
  continuity

Out of scope:

- broad redesign of all bridge settlement families
- fuzzy cross-chain matching by timestamp alone
- policy resolution of the remaining `18` `BYBIT` coverage gaps
- baseline refresh

## Architecture Decision Slice

`system-architect` contract for this closeout:

1. Source-of-truth for the LI.FI receiving tx hash may come only from
   protocol-owned evidence, not from audit-only artifacts.
2. The runtime may not fabricate `BRIDGE_IN` from loose proximity alone.
3. If the route is proven but same-asset move-basis is not proven, the runtime
   must still preserve the objective `BRIDGE_OUT -> BRIDGE_IN` semantics while
   keeping replay continuity conservative.
4. If the receiving tx hash exists but the receiving normalized row is still
   absent, the source row may persist pair evidence, but it may not become a
   synthetic completed continuity pair.

## Acceptance Criteria (DoD)

1. The audited pair
   `0x9f6983d00441ed13bf45e1b7ac34e94540fb61f58e4a9a2189826b1e761a2f7f`
   -> `0x7d8c79a327637fda080bcfa9204181359de791ccff95dd4b2f1b020b8af0b678`
   materializes as:
   - source = `BRIDGE_OUT`
   - destination = `BRIDGE_IN`
   - bidirectional `matchedCounterparty`
   - deterministic shared bridge correlation key

2. The destination-side promotion must be protocol-owned and data-driven:
   - no hardcoded tx hashes
   - no single-run audit artifact lookup in runtime

3. `EXTERNAL_TRANSFER_IN -> BRIDGE_IN` promotion is allowed only when:
   - the source row is already a proven `LI.FI / Jumper` bridge start
   - protocol status evidence resolves the receiving tx hash
   - the receiving tx hash points to the current destination row

4. Replay continuity must remain conservative:
   - if source and destination do not currently prove plain same-asset carry,
     the pair may remain `continuityCandidate = false`
   - the runtime must not silently move basis across an asset-changing bridge
     route

5. Existing good behavior must stay intact:
   - `BYBIT SWAP / PENDING_PRICE = 604`
   - `BYBIT STAKING_DEPOSIT / PENDING_PRICE = 1`
   - `GMX` async invariants stay clean
   - `LI.FI / Jumper` source-side bridge starts remain `BRIDGE_OUT`

## Task Breakdown

1. `BE-R39-01` Add protocol-owned LI.FI receiving-tx evidence loader
   - fetch receiving tx hash from the official LI.FI status endpoint
   - persist only the minimal stable fields needed for bridge linkage

2. `BE-R39-02` Materialize destination-side `BRIDGE_IN`
   - when the receiving tx hash points at an already-normalized destination row
   - retype the destination row to `BRIDGE_IN`
   - materialize bidirectional `matchedCounterparty`
   - materialize a deterministic bridge correlation key

3. `BE-R39-03` Keep replay continuity conservative
   - only set `continuityCandidate = true` when current canonical legs prove
     plain same-asset carry
   - do not create synthetic move-basis on asset-changing bridge routes

4. `BE-R39-04` Lock regressions with tests
   - audited pair promotion to `BRIDGE_IN`
   - delayed destination arrival path
   - replay carry for a same-asset correlated `BRIDGE_OUT / BRIDGE_IN` pair

5. `BE-R39-05` Rerun and re-audit
   - rerun:
     1. on-chain normalization
     2. on-chain clarification
     3. bybit normalization
   - produce a fresh audit package and compare against the accepted etalon

## Current Implementation Notes

- Implemented in code:
  - official LI.FI status lookup for route-proven source bridge starts
  - persisted minimal protocol-status evidence on the source raw row
  - destination-side promotion from `EXTERNAL_TRANSFER_IN` to `BRIDGE_IN`
    when the receiving tx hash points to the current normalized row
  - deterministic shared bridge correlation key
  - replay support for correlated `BRIDGE_OUT / BRIDGE_IN`
- Continuity remains conservative:
  - plain move-basis is enabled only for same-asset same-quantity bridge pairs
  - asset-changing bridge routes still become objective
    `BRIDGE_OUT / BRIDGE_IN`, but do not silently move basis
- Remaining operational step:
  - rerun normalization + clarification and re-audit the live dataset
- Mongo rerun preparation completed:
  - `normalized_transactions` cleared
  - `asset_positions` cleared
  - all `raw_transactions` reset to `normalizationStatus = PENDING`
  - all `external_ledger_raw` reset to `status = RAW`
  - session pipeline state reset to `BACKFILL / COMPLETE` so the
    session-level resume watchdog can re-emit the live pipeline trigger
