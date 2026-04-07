# Run 27 — Euler Simple Vault vs Loop Closeout

## Goal

Separate simple Euler vault deposits / withdraws from audited borrow-backed
Euler loop lifecycle.

## Problem

Current Euler protocol-aware normalization still lets some simple
`batch(...)` rows collapse into the loop lane or generic heuristic lane:

- simple vault `USDC -> eUSDC-6` can fall through to generic `SWAP`
- simple vault `eUSDC-6 -> USDC` can incorrectly resolve to
  `LENDING_LOOP_CLOSE`

That makes the `move basis` UX misleading and pollutes accounting semantics
with loop lifecycle where the user only opened or withdrew a plain vault
position.

The audited Arbitrum evidence proving the failure mode is:

- deposit:
  `0x8e940d70131f8a52fd6bc1d84cec901f44b2981b065680ae285cc00d4c29d124`
- partial withdraw:
  `0x9aad9182c92e4eb4cfb9e560c5695f8d6dc650b3e95cd2ab351fed4cfbf3ed8d`
- final withdraw:
  `0x248f9dd324adbd9d60172a002d217d712fd6cee501dac05ee3a2460f83eb4bbd`

These rows use:

- `to = 0x6302ef0f34100cddfb5489fbcb6ee1aa95cd1066`
- share contract `0x44c10da836d2abe881b77bbb0b3dce5f85c0c1cc`
- share symbol `eUSDC-6`

and do not carry borrow / debt evidence.

By contrast, the already audited loop family remains on the Euler loop router:

- `to = 0xddcbe30a761edd2e19bba930a977475265f36fa1`
- share symbol family such as `eUSDC-2`

## Target Policy

1. Euler lifecycle is clarification-first:
   - if receipt-backed clarification does not prove the lifecycle, the row must
     stay `UNKNOWN / PENDING_CLARIFICATION`
   - it must carry `EULER_BATCH_DECODER_REQUIRED`
   - it must enter the automatic receipt-clarification queue
   - after clarification, if lifecycle is still not provable, the row must end
     as explicit `NEEDS_REVIEW`
2. Euler simple vault lifecycle is protocol-aware but non-loop only when
   clarification proves it:
   - `stable out + share mint/in` -> `LENDING_DEPOSIT`
   - `share burn/out + stable in` -> `LENDING_WITHDRAW`
3. Euler loop lifecycle remains reserved for the audited loop router path and
   still requires reliable protocol evidence.
4. `LENDING_LOOP_OPEN` still requires borrow / debt evidence.
5. `LENDING_LOOP_REBALANCE` still requires share-to-share restructure evidence.
6. `LENDING_LOOP_DECREASE` / `LENDING_LOOP_CLOSE` must not be emitted for
   non-loop-router simple vault rows.
7. Simple withdraw rows must not encode `partial` vs `final` at normalized type
   level. That distinction belongs to replay/UI state, not to raw
   normalization.

## Scope

In scope:

- Euler protocol semantic classifier
- lending family classifier handoff for Euler semantics
- Euler protocol/runtime documentation
- regression tests for the three audited Arbitrum simple-vault hashes

Out of scope:

- user-facing linked-position UX for `USDC` vs `eUSDC-*`
- expanding Euler loop support beyond the currently audited router family
- API contract changes

## Acceptance Criteria

1. Without clarification, the three audited Arbitrum simple-vault hashes stay
   `UNKNOWN / PENDING_CLARIFICATION` with `EULER_BATCH_DECODER_REQUIRED`:
   - `0x8e940d70131f8a52fd6bc1d84cec901f44b2981b065680ae285cc00d4c29d124`
   - `0x9aad9182c92e4eb4cfb9e560c5695f8d6dc650b3e95cd2ab351fed4cfbf3ed8d`
   - `0x248f9dd324adbd9d60172a002d217d712fd6cee501dac05ee3a2460f83eb4bbd`
2. When clarification proves the lifecycle, those same rows resolve to:
   - deposit -> `LENDING_DEPOSIT`, `protocol = Euler`
   - withdraws -> `LENDING_WITHDRAW`, `protocol = Euler`
3. The existing audited loop rows on router `0xddcbe30a...` keep resolving to
   `LENDING_LOOP_OPEN`, `LENDING_LOOP_REBALANCE`, `LENDING_LOOP_DECREASE`, and
   `LENDING_LOOP_CLOSE`.
4. No simple Euler vault row without reliable clarification resolves to
   `SWAP`, `LP_EXIT`, `LENDING_DEPOSIT`, `LENDING_WITHDRAW`,
   `LENDING_LOOP_DECREASE`, or `LENDING_LOOP_CLOSE`.
5. `OnChainClarificationJob` automatically picks Euler rows carrying
   `EULER_BATCH_DECODER_REQUIRED`.
6. No frontend change is required for correctness in this slice.

## Implementation Tasks

1. Tighten Euler protocol semantics so no hint is emitted without receipt-backed
   clarification.
2. Keep loop decrease/close hints restricted to the audited loop router lane.
3. Route Euler-like batch rows without reliable clarification to
   `PENDING_CLARIFICATION`.
4. Keep simple vault `batch(...)` rows on the lending deposit / withdraw lane
   only when clarification proves the lifecycle.
5. Add protocol semantic classifier regressions for missing-clarification
   non-loop-router rows.
6. Add on-chain classifier regressions for the three audited Arbitrum hashes in
   both modes:
   - without clarification -> review
   - with clarification -> lending deposit/withdraw
7. Keep existing audited loop regressions green.
8. Reset derived collections and statuses to rerun-ready state after the fix.

## Expected Outcome

After rerun:

- simple Euler vault activity no longer appears as `SWAP` or `loop close`
- unclarified Euler batch rows stay explicit blocker review until clarification
  proves their lifecycle
- clarified `eUSDC-6` activity can materialize as the correct vault lifecycle
- audited loop rows keep their existing share-position semantics
