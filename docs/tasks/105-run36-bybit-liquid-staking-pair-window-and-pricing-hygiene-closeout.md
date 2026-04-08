# Run 36 — Bybit Liquid-Staking Pair Window And Pricing Hygiene Closeout

## Goal

Unblock replay after pricing by fixing the two remaining active Bybit review
rows and by cleaning stale pricing flags that inflate telemetry without
representing real replay blockers.

## Problem

After the previous ETH-family continuity repairs, the session still stopped at
`ACCOUNTING_REPLAY / BLOCKED` even though pricing completed. The root causes
were:

1. Two active Bybit `NEEDS_REVIEW` rows remained for the official
   `METH -> CMETH` `On-chain Earn subscription` lifecycle because the liquid
   staking pairer only searched inside a one-hour window.
2. `needsReview = 128` was operator-misleading because only `2` rows were true
   replay blockers; the remaining `126` rows were explicit audit-only excluded
   tails.
3. `unresolvedPrice = 124` was also inflated because some already-priced rows
   still carried stale `PRICE_UNRESOLVABLE` from older passes.

## Target Policy

1. Audited Bybit liquid-staking receipt wrappers such as `METH -> CMETH`
   follow the same continuity policy as audited `ETH -> METH`.
2. Deterministic pairing may use a bounded multi-hour window when the official
   earn lifecycle is still provable by:
   - same user/account
   - same Bybit lifecycle description when present
   - opposite signed quantities
   - same audited accounting family
   - exact or nearest absolute quantity match
3. Replay gating must be explained by active blockers only. Excluded audit-only
   review rows stay visible, but they are not replay blockers.
4. `PRICE_UNRESOLVABLE` is a live-state reason. If a row no longer has any
   replay-relevant unpriced flow, the stale reason must be cleared before the
   pricing stage announces completion.

## Scope

In scope:

- Bybit extracted liquid-staking pairer window/ranking
- legacy Bybit pairer parity
- stale `PRICE_UNRESOLVABLE` repair during pricing
- rerun preparation for the two blocking Bybit rows
- telemetry/documentation clarification for blocking vs excluded review counts

Out of scope:

- new pricing sources for genuinely unresolved assets
- generic UI redesign for telemetry counts
- reclassification of already excluded Bybit external-custody audit tails

## Acceptance Criteria

1. `METH -> CMETH` official Bybit `On-chain Earn subscription` rows that settle
   a few hours apart pair successfully during normalization.
2. The two former blockers no longer persist as active
   `BYBIT_LIQUID_STAKING_PAIR_NOT_FOUND` rows.
3. Pricing clears stale `PRICE_UNRESOLVABLE` when every replay-relevant flow on
   the row is already priced.
4. Session replay no longer blocks on the prior two Bybit review rows.
5. Documentation explicitly distinguishes:
   - blocking review
   - excluded review
   - stale vs current unresolved-price signals

## Implementation Tasks

1. Widen audited Bybit liquid-staking pairing from one hour to a bounded
   multi-hour window.
2. Tighten candidate selection with:
   - same official description when present
   - same accounting-family identity
   - exact quantity match preferred before nearest quantity
3. Apply the same rule to both extracted and legacy Bybit pairers.
4. Add a pricing-stage repair pass that removes stale
   `PRICE_UNRESOLVABLE` from non-`PENDING_PRICE` rows when no
   replay-relevant flow still lacks price.
5. Reopen only the two blocking Bybit extracted rows for rerun and remove their
   stale normalized blockers without deleting immutable raw evidence.
6. Restart backend and verify the session moves past pricing into replay.

## Expected Outcome

After rerun:

- the `METH -> CMETH` Bybit earn lifecycle is normalized as one deterministic
  audited liquid-staking continuity pair
- replay is blocked only by real active review rows, not by excluded audit
  tails
- stale unresolved-price telemetry no longer overstates current pricing risk
