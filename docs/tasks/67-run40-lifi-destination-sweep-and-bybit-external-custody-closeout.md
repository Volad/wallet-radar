# Run 40 LI.FI Destination Sweep And Bybit External Custody Closeout

Status: In progress

Goal:

Close the remaining accounting blockers from `run/40` without regressing the
restored `BYBIT` trade clustering or the accepted bridge-start behavior.

Related inputs:

- [run/40 audit report](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/40/clarification-readiness-audit.md)
- [run/40 audit summary](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/40/audit_summary.json)
- [run/40 LI.FI status audit summary](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/40/lifi_status_audit_summary.json)
- [run/40 bridge pair focus](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/40/bridge_pair_focus.json)
- [run/40 Bybit external custody candidates](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/40/bybit_external_custody_candidates.tsv)
- [LI.FI protocol rules](../normalization/protocols/li-fi.md)
- [Bybit protocol rules](../normalization/protocols/bybit.md)
- [Accounting policy](../03-accounting.md)

## Problem Statement

`run/40` confirmed two residual issues:

1. `LI.FI / Jumper` destination-side bridge continuity is still incomplete.
   The explicit audited pair
   `0x9f6983d00441ed13bf45e1b7ac34e94540fb61f58e4a9a2189826b1e761a2f7f`
   -> `0x7d8c79a327637fda080bcfa9204181359de791ccff95dd4b2f1b020b8af0b678`
   still materializes as `BRIDGE_OUT -> EXTERNAL_TRANSFER_IN`, even though the
   receiving tx hash is provable through official LI.FI status.
2. The remaining `18` non-excluded `BYBIT` rows still carry
   `BRIDGE_ON_CHAIN_LEG_NOT_FOUND`, but the raw evidence now points to
   `external custody / external venue` movement rather than tracked-universe
   bridge continuity.

## Scope

In scope:

- deterministic LI.FI destination-side `BRIDGE_IN` materialization that does
  not depend on source clarification order
- protocol-owned sweep over unresolved LI.FI source bridge starts
- family-equivalent bridge carry for audited stable-wrapper pairs such as
  `vbUSDC -> USDC`
- explicit Bybit external-custody policy for unmatched `withdraw_deposit` rows
  whose venue address is outside the tracked wallet universe

Out of scope:

- fuzzy bridge matching by timestamp proximity
- generic support for all external venues beyond the explicit `external custody`
  policy
- baseline refresh

## Architecture Decision Slice

`system-architect` contract for this closeout:

1. Official LI.FI status remains protocol-owned evidence.
2. Destination-side `BRIDGE_IN` materialization must not depend on whether the
   source row happened to pass through a specific clarification lane first.
3. A bounded post-clarification sweep over unresolved route-proven source
   bridge starts is allowed.
4. `Bybit -> external venue` and `external venue -> Bybit` are not bridge
   continuity by default. If the venue address is outside the tracked wallet
   universe and no exact tracked-universe on-chain leg exists, the row must be
   represented as `external custody`, not as an unresolved on-chain bridge.

## Acceptance Criteria (DoD)

1. The audited pair
   `0x9f6983d00441ed13bf45e1b7ac34e94540fb61f58e4a9a2189826b1e761a2f7f`
   -> `0x7d8c79a327637fda080bcfa9204181359de791ccff95dd4b2f1b020b8af0b678`
   materializes as:
   - source = `BRIDGE_OUT`
   - destination = `BRIDGE_IN`
   - bidirectional `matchedCounterparty`
   - deterministic shared bridge `correlationId`

2. `LI.FI / Jumper` destination-side promotion works even when the source row
   did not previously seed `protocolStatus` during clarification.

3. `vbUSDC -> USDC` is treated as one `USDC` family for bridge move-basis
   continuity under the audited LI.FI route, while still keeping asset ids
   distinct in canonical normalized rows.

4. The remaining `Bybit` unmatched `withdraw_deposit` rows no longer surface as
   `BRIDGE_ON_CHAIN_LEG_NOT_FOUND` when:
   - exact tracked-universe on-chain evidence is absent
   - and the venue address is outside the tracked wallet universe

5. Those `Bybit` rows remain canonical `EXTERNAL_TRANSFER_IN/OUT`:
   - no fabricated `correlationId`
   - no fabricated `matchedCounterparty`
   - no synthetic `BRIDGE_*` promotion
   - no synthetic `BUY` / `SELL`

6. Existing good behavior stays intact:
   - `BYBIT SWAP / PENDING_PRICE = 604`
   - `BYBIT STAKING_DEPOSIT / PENDING_PRICE = 1`
   - no new broad protocol-family regressions

## Edge Cases

- Source LI.FI bridge start already has persisted `receivingTxHash`, but the
  destination row appears only on a later rerun.
- Destination row exists as `EXTERNAL_TRANSFER_IN` and carries only plain
  inbound transfer facts; the source route must still be able to promote it.
- `Bybit` unmatched deposit goes to a known Bybit deposit address and has no
  tracked-universe on-chain tx hash match.
- `Bybit` unmatched withdrawal goes to an address outside the tracked wallet
  universe and later returns on the same asset with different quantity.

## Task Breakdown

1. `BE-R40-01` Add unresolved LI.FI source sweep
   - load bounded unresolved source `BRIDGE_OUT` candidates
   - fetch/persist official receiving tx evidence when the route is proven
   - materialize destination-side `BRIDGE_IN` if the receiving row already
     exists

2. `BE-R40-02` Decouple LI.FI status fetch from full-receipt order dependence
   - route-proven source bridge starts may fetch status even when the source did
     not pass through the previous full-receipt seed path

3. `BE-R40-03` Implement family-equivalent bridge carry
   - add explicit move-basis identity mapping for audited `vbUSDC -> USDC`
     bridge settlement

4. `BE-R40-04` Implement Bybit external custody policy
   - if `withdraw_deposit` is unmatched and the venue address is outside the
     tracked wallet universe, persist external-custody status instead of
     `BRIDGE_ON_CHAIN_LEG_NOT_FOUND`

5. `BE-R40-05` Lock regressions with tests
   - LI.FI source sweep promotion
   - `vbUSDC -> USDC` continuity carry
   - Bybit unmatched tracked-wallet address still stays a bridge gap
   - Bybit unmatched external-venue address resolves to external custody

6. `BE-R40-06` Prepare rerun
   - clear derived collections
   - reset raw normalization state
   - keep raw backfill evidence intact
