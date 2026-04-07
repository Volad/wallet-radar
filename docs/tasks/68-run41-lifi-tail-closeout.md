# Run 41 LI.FI Residual Tail Closeout

Status: In progress

Goal:

Close the remaining `LI.FI / Jumper` residual continuity tail confirmed by
`run/41` without regressing the restored destination-side bridge behavior or the
explicit `Bybit external custody` policy.

Related inputs:

- [run/41 audit report](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/41/clarification-readiness-audit.md)
- [run/41 audit summary](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/41/audit_summary.json)
- [run/41 unresolved LI.FI rows](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/41/lifi_unresolved_rows.tsv)
- [run/41 rule updates](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/41/normalization_rule_updates.md)
- [LI.FI protocol rules](../normalization/protocols/li-fi.md)
- [Accounting policy](../03-accounting.md)

## Problem Statement

`run/41` left one narrow but still accounting-relevant `LI.FI` tail:

1. Three receiving tx hashes already exist in the normalized outcome, but still
   materialize as `EXTERNAL_TRANSFER_IN` instead of `BRIDGE_IN`.
2. Eleven receiving tx hashes are already known from official LI.FI status, but
   are still absent from both `raw_transactions` and `normalized_transactions`.
3. Four LI.FI status responses return the same tx hash for sending and
   receiving, and must never self-link the source row.

The resolved examples show that the root causes are now narrower than in
`run/40`:

- destination-side bridge pairing must work across the full tracked wallet
  universe, not only inside the same source wallet
- receiving tx discovery must use official `receivingTxHash + receivingNetwork`
  as a bounded targeted fetch path
- same-tx LI.FI echoes need a hard guard before pair materialization

## Scope

In scope:

- cross-wallet tracked-universe LI.FI destination lookup
- deterministic targeted raw fetch by `receivingTxHash`
- immediate same-run normalization of newly discovered receiving txs
- same-tx LI.FI echo guard
- rerun preparation after implementation

Out of scope:

- fuzzy bridge matching by timestamp or amount proximity
- generic bridge tx-hash discovery for non-LI.FI protocols
- baseline refresh

## Acceptance Criteria (DoD)

1. The following destination tx hashes materialize as `BRIDGE_IN` on rerun:
   - `0xbdd28dacd0c62925efbb32bc388cec5972af270dd2ea77637ed1ff8390cba70c`
   - `0x8048a7c7a3609ead776327c63b46cd7b9c9c3f7d61af78493de6242fc2758ce2`
   - `0x0f67b417c1002ffbe6f9d56334fd0084d1fcb0b068caf0d09fd3e86ea30674fe`

2. Destination-side LI.FI bridge pairing is allowed when the receiving tx lands
   on a different tracked wallet from the source, as long as both wallets are
   inside the tracked user universe.

3. When official LI.FI status provides a receiving tx hash that is still absent
   from Mongo, the runtime performs a bounded targeted fetch for tracked wallets
   and immediately normalizes the discovered destination tx in the same run.

4. If a receiving tx hash still cannot be fetched for any tracked wallet, the
   source row remains explicit unresolved bridge continuity; no synthetic
   destination row is fabricated.

5. Same-tx LI.FI status echoes never produce:
   - `matchedCounterparty = self`
   - synthetic `BRIDGE_IN`
   - synthetic bridge continuity carry

6. Existing good behavior stays intact:
   - the audited pair
     `0x9f6983d00441ed13bf45e1b7ac34e94540fb61f58e4a9a2189826b1e761a2f7f`
     -> `0x7d8c79a327637fda080bcfa9204181359de791ccff95dd4b2f1b020b8af0b678`
     remains `BRIDGE_OUT -> BRIDGE_IN`
   - `Bybit external custody` rows remain `EXTERNAL_TRANSFER_IN/OUT`
   - no new broad family regressions appear in bridge, swap, LP, lending, or
     Bybit trade clustering

## Edge Cases

- receiving tx belongs to another tracked wallet, not the source wallet
- receiving tx exists in raw form but was never normalized
- receiving tx can be fetched by hash, but explorer transfer evidence is empty
- LI.FI status returns `sendingTxHash == receivingTxHash`
- source row already carries persisted LI.FI status from a previous run

## Task Breakdown

1. `BE-R41-01` Expand LI.FI source sweep eligibility
   - revisit route-proven `BRIDGE_OUT` rows even when `matchedCounterparty`
     already exists
   - do not restrict destination lookup to the source wallet only

2. `BE-R41-02` Add receiving-tx discovery for missing LI.FI destinations
   - attempt tracked-wallet targeted fetch by
     `receivingTxHash + receivingNetwork`
   - if fetched, normalize immediately and feed the result back into bridge
     materialization

3. `BE-R41-03` Add hard same-tx echo guard
   - never self-link a LI.FI source row
   - never create synthetic `BRIDGE_IN` from `sendingTxHash == receivingTxHash`

4. `BE-R41-04` Lock the tail with regression tests
   - cross-wallet destination promotion
   - missing destination targeted fetch
   - same-tx echo rejection

5. `BE-R41-05` Prepare rerun
   - clear derived state
   - reset raw normalization state
   - keep raw backfill evidence intact
