# 109 — Run 40 Linked Bridge Carry Replay Closeout

Status: Done

## Goal

Close the dominant remaining `move -> cost basis -> AVCO` defect from
`results/stats/3`: already-linked same-family bridge pairs were still losing
carry during replay because the destination leg reused the generic correlated
transfer quantity matcher.

## Problem Statement

The audit proved that the remaining ETH coverage gap was no longer primarily a
classification or clarification failure.

Confirmed live evidence:

- `continuityCandidate = true`
- `correlationId` is present
- `matchedCounterparty` is present
- both rows are already `BRIDGE_OUT -> BRIDGE_IN`
- family identity is preserved

Yet replay still produced zero-cost destination carry for many bridge pairs.

Representative hashes:

- `0xd4c9f0618380a5d2201fced479053f9b1d1f0290e44da722565d01fe51966d1b`
  -> `0xb9ee45f9fe5aeaad327cc1a4666f08e22d67cf1aad4ed1b7f8f4c1e1d6742530`
- `0x4ca0b79ea7f374c8f90e4c13fc9da43a668f1d8352ae99b1d5a84ef4056ab4fb`
  -> `0x38d445c4fc8f54149185a606240f0c7b212047a637dae42d7491a835b08d1cf2`
- `0x9712e051e33e603b22039ef74ed946e78664695aa341a8825a516822aa5f8966`
  -> `0xc88e8268f32c3cc5ef29c604f69a359422de22d452e255534c3399e9f478be41`

Root cause:

- generic correlated transfer replay expects near-equal quantities
- bridge settlement regularly lands with bounded quantity drift
- for `dst < src` the old replay also sliced cost basis proportionally, which
  contradicted the documented bridge policy

## Scope

In scope:

- replay-only linked bridge carry repair
- same-family already-linked `BRIDGE_OUT -> BRIDGE_IN`
- source-first and inbound-first chronology
- docs and regression coverage

Out of scope:

- creating new bridge links
- widening clarification heuristics
- interest-bearing receipt drift on Aave-style positions
- pricing remediation

## Acceptance Criteria

1. When two rows already form a same-family linked bridge pair, replay uses a
   dedicated bridge carry path keyed by `correlationId + bridge family`.
2. Replay does not require near-equal source/destination quantities for that
   path.
3. If destination quantity is lower than source quantity, full source cost basis
   moves onto the smaller destination quantity.
4. If destination quantity is higher than source quantity, full source cost
   basis moves and only the excess destination quantity remains uncovered.
5. Inbound-first chronology remains supported without duplicating quantity.
6. If more than one pending opposite-side bridge candidate exists for the same
   key, replay refuses to guess.

## Backend Tasks

1. `BE-R40-01` Add linked bridge carry lane in `AvcoReplayService`
2. `BE-R40-02` Keep pass-through corridor compatibility for bridge inbound that
   later feeds custody / lending deposits
3. `BE-R40-03` Add regression tests for:
   - `dst < src`
   - `dst > src`
   - inbound-first late carry attachment
4. `BE-R40-04` Prepare replay-only rerun
   - preserve raw / extracted / normalized evidence
   - clear derived replay outputs only

## Completion Notes

- Added bridge-specific replay handling for already-linked same-family bridge
  pairs.
- Replay no longer scales source cost basis down merely because bridge
  settlement quantity drifted.
- Generic correlated-transfer matching remains unchanged for non-bridge lanes.
- `Aave` receipt drift remains a separate follow-up after this bridge carry
  repair.
