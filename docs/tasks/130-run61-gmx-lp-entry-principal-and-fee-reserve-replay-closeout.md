# 130 — GMX LP Entry Principal And Fee-Reserve Replay Closeout

Date: 2026-04-09
Owner slice: backend / accounting replay
Source audit: `results/stats/27/full-pipeline-audit.md`

## Problem

`GMX V2` async LP entry request / settlement pairs still leaked basis across the
request boundary.

Audited corridor:

- request `0xff684e80f06286cfb8b30b8c9011eb6b7ca109117b9523cbbcac756a3b242e79`
- settlement `0x1aa3438d2be03e761a607c42e5f66a778a5a7890ebcbc9dd4e45c502d75330fb`
- correlationId `0xa9576f9556b35c396ae6f27c52a8a14682def7fdc149646a688077a7067bb69d`

Before the fix:

- `LP_ENTRY_REQUEST` removed `USDC` principal basis and a native `ETH`
  execution-fee reserve from the wallet
- `LP_ENTRY_SETTLEMENT` restored only the tiny native refund
- minted `GM` / `GLV` market-token inflows could still materialize with
  `costBasisDeltaUsd = 0`
- any uncovered or partially refunded native execution-fee reserve could poison
  the whole request bucket and block principal allocation into the share token

This was a replay reservation bug, not a GMX classification bug.

## Root Cause

The generic async lifecycle bucket treated every request-side outbound
`TRANSFER` as one pooled principal carry bucket.

For `GMX V2 LP_ENTRY_REQUEST` that was too coarse:

- non-native request outbounds are principal assets that must fund the minted
  `GM` / `GLV` market token
- native request outbound is an execution-fee reserve, not principal inventory
- settlement-side native refund releases part of that reserve
- only the remaining principal basis should be allocated into the minted market
  token

When the reserve stayed inside the same generic bucket, refund / uncovered
reserve handling could prevent the principal carry from reaching the share
token deterministically.

## Decision

Keep the existing generic async lifecycle replay for other request / settlement
families, but introduce an audited `GMX V2 LP entry` replay lane with explicit
request-scoped reserve splitting.

Rules:

1. request-side non-native transfer outbounds go into a principal reservation
   bucket
2. request-side native transfer outbound goes into an execution-fee reserve
   bucket
3. settlement-side native refund releases the matching execution-fee reserve
4. minted `GM` / `GLV` inflows receive the remaining principal cost basis plus
   any covered net execution-fee reserve cost that was actually consumed by the
   lifecycle
5. uncovered execution-fee reserve must not block principal allocation into the
   minted market token

## Implemented Scope

### P0 transaction-level GMX replay lane

- `AvcoReplayService` now special-cases:
  - `GMX / LP_ENTRY_REQUEST`
  - `GMX / LP_ENTRY_SETTLEMENT`
- the replay lane is enabled only for the audited request pattern:
  - at least one non-native negative `TRANSFER`
  - one native negative `TRANSFER`
  - optional explicit `FEE`
- settlement lane is enabled only when the row contains a positive `GM:` or
  `GLV` share-token inflow

### P0 split async lifecycle bucket

- async lifecycle replay buckets now keep:
  - principal carries
  - execution-fee reserve carries
- generic async lifecycle behavior still reads only the principal carries

### P0 settlement behavior

- native refund first restores matching execution-fee reserve carry
- non-share positive transfer legs may still restore same-asset principal carry
  if present
- share-token inflows receive the remaining principal basis deterministically
- any leftover execution-fee reserve is consumed by the request lifecycle and
  does not reopen as a fresh acquisition lane

### P0 regression coverage

- added replay coverage proving that an uncovered native execution-fee reserve
  must not zero out principal allocation into the minted `GM` market token

## Acceptance Criteria

- audited `GMX V2 LP_ENTRY_REQUEST -> LP_ENTRY_SETTLEMENT` pairs must no longer
  mint `GM` / `GLV` with `costBasisDeltaUsd = 0` when the request principal was
  covered
- native settlement refund must restore the reserved native carry instead of
  materializing as a fresh acquisition lane
- an uncovered native execution-fee reserve must not block covered principal
  allocation into the share token
- generic non-GMX async lifecycle replay behavior must remain unchanged

## Detailed Task Breakdown

### Task A — replay specialization

- add a transaction-level `GMX LP entry` request handler
- add a transaction-level `GMX LP entry` settlement handler
- keep the existing generic async lifecycle path for all other families

### Task B — bucket split

- extend async lifecycle buckets with a separate execution-fee reserve map
- keep current principal carry methods stable for existing async callers

### Task C — tests

- add a GMX replay regression where the execution-fee reserve is uncovered but
  the principal is covered
- keep existing LP exit and bridge replay tests green

### Task D — rerun and verification

- rerun the full on-chain slice
- verify the audited request / settlement hashes on live ledger points
- compare `ETH` and `GMX market-token` uncovered before / after

## Risks and Guardrails

- do not reclassify GMX request-side native reserve into canonical `FEE`; this
  slice is replay-only
- do not pool native execution-fee reserve with principal request assets
- do not let uncovered execution-fee reserve poison covered principal
  allocation
- do not generalize the `GMX` transaction-level lane to all async LP families
  without audited protocol evidence

## Expected Outcome

After rerun:

- `GMX` market-token settlement inflows receive deterministic request-side
  basis
- native refund rows stop behaving like fresh zero-basis acquisitions
- the `GMX` request / settlement corridor no longer strands principal basis
  outside the minted `GM` / `GLV` inventory

## Live Verification

Live rerun artifacts:

- `results/stats/28/gmx-lp-entry-reserve-replay-verification.md`
- `results/stats/28/summary.json`
- `results/stats/28/data/derived/eth-current.json`
- `results/stats/28/data/derived/family-current-coverage.json`
- `results/stats/28/data/derived/gmx-lp-entry-focus.json`

Verified on the completed rerun:

- session reached `ACCOUNTING_REPLAY / COMPLETE`
- `asset_ledger_points = 9012`
- `on_chain_balances = 212`
- request
  `0xff684e80f06286cfb8b30b8c9011eb6b7ca109117b9523cbbcac756a3b242e79`
  remains `LP_ENTRY_REQUEST / GMX / CONFIRMED`
- settlement
  `0x1aa3438d2be03e761a607c42e5f66a778a5a7890ebcbc9dd4e45c502d75330fb`
  remains `LP_ENTRY_SETTLEMENT / GMX / CONFIRMED`
- settlement minted `GM: ETH/USD [WETH-USDC]` now carries
  `costBasisDeltaUsd = 149.7166519274125939953658691382334`
- settlement native refund restores reserve carry with
  `costBasisDeltaUsd = 0.002389958791803076398523948749354677`
- the audited `ARBITRUM / ETH` bucket improved from fully uncovered in
  `run 27` to:
  - quantity `0.018017739313338641`
  - covered `0.013584346903972869`
  - uncovered `0.004433392409365772`
- session-level `ETH uncovered` improved from
  `0.670704568223348` to `0.635020716733674967`

Remaining live tail after this slice is still dominated by:

- `MANTLE / AMANWETH`
- tx `0x3b8592a7465ce33bd64b266e11d1f665954cc3735be6e1590dffd226ac8664bd`
- uncovered `0.629454465525489890 ETH`
