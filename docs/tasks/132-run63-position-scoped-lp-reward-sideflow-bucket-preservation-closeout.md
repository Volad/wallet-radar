# 132 — Position-Scoped LP Reward Sideflow Bucket Preservation Closeout

Date: 2026-04-09
Owner slice: backend / accounting replay
Source live proof: `results/stats/29/position-scoped-lp-exit-residual-principal-replay-verification.md`

## Problem

The previous position-scoped LP replay fix was still incomplete on live data.

Audited `BASE / PancakeSwap` position:

- entry `0x743405fa303bfee9121cf0bfd4818871c8a829d5cb866d951b67fd4f25c03989`
- reward-like sideflow exit `0xe17a5a511c8a262b53ac812efd52162392a47583f41e233672226bf043a94ebc`
- later principal exit `0x62cfc2b9c29d4dbb6d4b5839de760ad2ccf0dddb9f56ed739eeb344048230c29`
- correlationId `lp-position:base:pancakeswap:450450`

The intermediate `CAKE` exit was consuming the whole remaining position bucket,
so the later `USDC` principal exit was rebuilding as zero-cost `UNKNOWN`.

## Root Cause

`AvcoReplayService.applyPositionScopedLpExit(...)` treated deferred
out-of-bucket positive transfer legs as allocatable even when the current exit
had not touched any eligible principal-return identity from the position
bucket.

That made a reward-only `LP_EXIT` behave like a terminal principal unwind:

1. reward token inherited remaining covered principal basis
2. replay cleared the bucket
3. the later actual principal-return exit had nothing left to restore

## Decision

Keep position-scoped LP exits conservative:

1. if the current exit touches no eligible principal-return identity from the
   live bucket
2. and only out-of-bucket positive transfer legs are present
3. then those legs remain `UNKNOWN`
4. and the remaining bucket must stay open for later principal exits under the
   same position correlation

This is safer than over-allocating basis into reward tokens.

## Acceptance Criteria

- reward-only or out-of-bucket `LP_EXIT*` rows must not consume the remaining
  position bucket by themselves
- later principal-return `LP_EXIT*` rows under the same
  `lp-position:<network>:<protocol>:<tokenId>` correlation must still see the
  preserved carry
- the audited pair
  `0xe17a5a511c8a262b53ac812efd52162392a47583f41e233672226bf043a94ebc`
  then
  `0x62cfc2b9c29d4dbb6d4b5839de760ad2ccf0dddb9f56ed739eeb344048230c29`
  must rebuild with:
  - `CAKE` reward sideflow = `UNKNOWN`
  - later `USDC` principal exit = `REALLOCATE_IN`
- full on-chain rerun must complete and refresh live coverage

## Detailed Task Breakdown

### Task A — replay guard

- detect when a position-scoped `LP_EXIT*` touched no eligible principal-return
  identity from the current bucket
- in that case:
  - record deferred out-of-bucket sideflows as `UNKNOWN`
  - keep the async LP bucket alive
  - do not call `clearAll()`

### Task B — regression coverage

- add a replay test for:
  - covered/uncovered LP entry
  - reward-only sideflow exit
  - later principal-return exit

### Task C — live verification

- rerun full on-chain normalization + pricing + replay
- verify the audited `BASE` Pancake position on live ledger points
- compare `ETH` current uncovered before/after rerun

## Risks And Guardrails

- do not auto-promote arbitrary reward tokens into principal continuity
- do not keep empty buckets alive after they have no remaining carry
- do not change normalization, clarification, or protocol detection in this
  slice

## Expected Outcome

After rerun:

- reward-only LP sideflows stop stealing principal basis
- later principal exits under the same LP position correlation restore the
  preserved carry correctly
- downstream uncovered `ETH` shrinks because the upstream `BASE` LP corridor no
  longer strands covered basis before bridge / custody / Mantle continuity
