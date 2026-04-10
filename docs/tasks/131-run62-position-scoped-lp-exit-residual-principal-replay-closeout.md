# 131 — Position-Scoped LP Exit Residual Principal Replay Closeout

Date: 2026-04-09
Owner slice: backend / accounting replay
Source audit lineage: `results/stats/22/full-pipeline-audit.md`, `results/stats/27/full-pipeline-audit.md`

## Problem

The remaining `ETH` uncovered tail was still being carried honestly into
`MANTLE / aManWETH`, but the upstream replay path on concentrated-liquidity
`LP_EXIT` rows was still dropping covered principal basis in two deterministic
cases:

- same-position residual principal returned in a different asset than the
  original source asset
- same-position residual principal returned in deterministic USD-stable assets
  without an explicit persisted `unitPriceUsd` on the transfer leg

Audited upstream examples:

- `0x62cfc2b9c29d4dbb6d4b5839de760ad2ccf0dddb9f56ed739eeb344048230c29`
  (`BASE`, PancakeSwap) returned only `USDC`, but residual covered principal
  from the original `WETH` leg was still materializing as zero-cost `UNKNOWN`
- that uncovered `USDC` then propagated through later `Euler`, `1inch`,
  `PancakeSwap`, `Bybit`, and finally into the visible `MANTLE / aManWETH`
  tail

This was a replay allocation bug, not a normalization or pricing bug.

## Root Cause

`AvcoReplayService.applyPositionScopedLpExit(...)` still had two overly
conservative guards:

1. positive transfer legs whose asset identity was not present among the source
   position carries were demoted to immediate `UNKNOWN`, even though
   concentrated-liquidity exits may legitimately return principal in a shifted
   asset mix
2. residual principal allocation only recognized explicit `unitPriceUsd`
   pricing, so deterministic USD-stable transfer legs without persisted
   transfer-side prices could not participate in value-based replay allocation

These guards were causing covered principal basis to strand upstream and later
reappear as uncovered destination inventory.

## Decision

Keep position-scoped LP replay conservative, but narrow the residual principal
rules:

1. same-asset carry is restored first
2. cross-asset positive transfer legs are treated as deferred residual
   principal candidates, not immediate `UNKNOWN`
3. if same-family residual principal still exists, out-of-bucket side assets
   stay `UNKNOWN` and must not dilute that principal allocation
4. if no same-family residual principal remains, deferred cross-asset residual
   principal may receive the remaining covered basis
5. replay-known-value allocation may use deterministic USD-stable parity for
   transfer legs even when `unitPriceUsd` was not persisted on the canonical
   flow

This keeps reward-like side assets conservative while still allowing covered
principal to move across valid position exits.

## Acceptance Criteria

- a position-scoped `LP_EXIT` must no longer strand covered principal basis
  just because the returned principal asset differs from the original source
  asset
- deterministic USD-stable residual transfer legs may participate in replay
  value allocation without requiring an explicit persisted `unitPriceUsd`
- out-of-bucket reward-like legs must remain `UNKNOWN` when a same-family
  residual principal leg is still available
- existing `GMX` request/settlement replay rules and bridge replay rules must
  remain unchanged

## Detailed Task Breakdown

### Task A — replay allocation boundary

- split `LP_EXIT` residual handling into:
  - same-family residual principal candidates
  - deferred cross-asset residual candidates
- only escalate deferred cross-asset residuals into allocation when no
  same-family residual principal remains

### Task B — replay-known-value pricing

- introduce a replay-local deterministic price helper
- allow that helper to use:
  - explicit flow `unitPriceUsd`
  - trusted USD-stable parity for on-chain transfer legs

### Task C — regression coverage

- add regression for:
  - same-position covered `WETH` basis flowing into a residual `USDC` return
  - same-position residual `USDT` / `DAI` basket using replay-known-value
    allocation
- keep existing LP exit reward-side regression green

### Task D — rerun and verification

- rerun the full on-chain slice
- verify the audited `BASE` Pancake exit lineage on live ledger points
- compare `ETH` uncovered before / after the rerun

## Risks And Guardrails

- do not treat every cross-asset positive LP-exit leg as principal by default;
  reward-like side assets stay `UNKNOWN` unless the residual-principal rule
  explicitly promotes them
- do not let stablecoin replay-known-value logic bleed into canonical pricing;
  this is replay-only support
- do not change normalization types or clarification behavior in this slice

## Expected Outcome

After rerun:

- covered upstream LP basis no longer dies on deterministic residual principal
  exits
- stable residual transfer baskets (`USDC` / `USDT` / `DAI` etc.) can receive
  replay basis when they are the only remaining principal return path
- downstream `ETH` uncovered shrinks because the `BASE -> zkSync -> ARBITRUM ->
  BYBIT -> MANTLE` corridor starts from a better-covered source bucket
