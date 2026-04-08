# Run 37 — Correlated Carry Ingress Repair Closeout

## Goal

Repair same-family continuity carry for correlated external-ingress rows whose
destination quantity was rounded by the venue/provider, so replay restores the
proven source-side cost basis instead of minting uncovered inventory.

## Problem

The full pipeline audit showed that primary classification is no longer the
main ETH-family blocker. The remaining drift comes from replay-level continuity
matching:

- pipeline-resolved classification coverage remains high (`97.77%`)
- continuity candidate linking is mostly healthy (`256 / 284 = 90.14%`)
- but a small set of correlated `CARRY_IN` rows still restore with
  `costBasisDeltaUsd = 0`

Root cause:

1. source-side `CARRY_OUT` rows and destination-side `CARRY_IN` rows already
   share deterministic `correlationId`
2. both rows are already marked `continuityCandidate = true`
3. both belong to the same audited asset family
4. the destination quantity is truncated by the provider export/API
5. replay still keyed the carry match by exact quantity string, so the proven
   carry never attached

Observed live examples:

- `0x8ad4123d70423f5ca1755ac7514f5ad24cdd1c8d3f005bcc809afbecd17af027`
- `0xe2f90520f9302f75959689f8d2824497045164ba7c8679816c00fcb8abddffae`

## Target Policy

1. This is a replay-only repair for already-proven continuity. It must not
   change normalization or transaction typing.
2. Replay may attach source-side carry to a correlated inbound even when the
   inbound quantity was rounded, but only when:
   - `correlationId` is shared
   - both rows are already `continuityCandidate = true`
   - the continuity identity / audited family is the same
   - the quantity delta is inside a tiny provider-rounding tolerance
   - there is exactly one compatible carry candidate
3. If uniqueness is not provable, replay must not guess. It must fall back to
   the existing pending-inbound / pooled AVCO path.
4. Asset-changing routes remain out of scope. Shared `correlationId` alone does
   not authorize carry continuity across different asset families.

## Scope

In scope:

- replay transfer-key / pending-carry matching
- correlated same-family `EXTERNAL_TRANSFER_OUT -> EXTERNAL_TRANSFER_IN`
  continuity into external venues such as Bybit
- chronology-safe inbound-first attachment
- targeted replay rerun without deleting immutable raw or normalized evidence
- documentation updates for session scope and replay carry policy

Out of scope:

- reclassification of canonical rows
- generic bridge-family redesign
- new pricing sources
- UI changes for covered/uncovered quantity

## Acceptance Criteria

1. Same-family correlated venue ingress may restore carry even when the venue
   truncated quantity precision.
2. Replay preserves the covered/uncovered composition from the source-side
   carry; it may not silently re-open a new covered lot on the destination.
3. The repair works for both:
   - normal source-first ordering
   - inbound-first ordering
4. If more than one compatible carry candidate exists, replay attaches none.
5. Asset-changing routes with shared `correlationId` still do not receive
   plain carry continuity.
6. Targeted replay rerun completes without deleting immutable raw evidence.

## Architecture Tasks

1. Keep the repair strictly inside accounting replay.
2. Match on deterministic continuity identity plus `correlationId`, not on
   exact source/destination quantity string.
3. Treat provider rounding tolerance as a bounded replay invariant, not as a
   normalization heuristic.
4. Preserve ambiguity safety by requiring a unique compatible carry candidate.

## Business / Audit Tasks

1. Reclassify this defect as a replay continuity defect, not as a primary
   classification defect.
2. Track ETH-family drift separately from generic `NEEDS_REVIEW` counts.
3. Confirm that the repaired rows are same-family continuity and not
   asset-changing routes.
4. Verify that AVCO improvement comes from restored carry continuity rather than
   from synthetic repricing or lot resets.

## Backend Tasks

1. Update replay transfer matching so correlated continuity rows use
   `correlationId + continuityIdentity` as the deterministic key.
2. Add unique-fit queue lookup that tolerates tiny provider precision loss.
3. Preserve chronology-safe late carry attachment for inbound-first rows.
4. Add regression tests for:
   - source-first rounded ingress
   - inbound-first rounded ingress
   - venue transit that must not mix with pre-existing venue inventory
5. Leave the existing pooled fallback untouched when the fit is ambiguous.

## Mongo / Rerun Tasks

1. Prepare a targeted replay rerun only.
2. Keep immutable raw evidence and canonical normalized rows intact.
3. Clear replay outputs (`asset_ledger_points`, replay-derived current-holding
   projections if needed) so replay recomputes from confirmed canonical rows.
4. Restart backend and verify that replay resumes for the active session.

## Expected Outcome

After rerun:

- proven on-chain -> Bybit same-family continuity no longer loses carry because
  of venue-rounded inbound quantity
- ETH-family covered quantity increases where the source-side carry was already
  objective
- AVCO drift decreases without changing canonical types or introducing new
  heuristic normalization behavior

## Live Verification Notes

1. The Mantle `CMETH` ingress
   `0xe2f90520f9302f75959689f8d2824497045164ba7c8679816c00fcb8abddffae`
   restored non-zero carried basis after replay rerun.
2. The Arbitrum `ETH` ingress
   `0x8ad4123d70423f5ca1755ac7514f5ad24cdd1c8d3f005bcc809afbecd17af027`
   remained zero-cost after rerun because the source-side `CARRY_OUT` itself is
   fully uncovered in replay. This row is therefore not evidence that the new
   correlated-ingress repair failed; it is a legitimate uncovered continuity
   ingress under the current upstream proof state.
