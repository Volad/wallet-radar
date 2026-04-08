# 112 — Run 43 Wrap Continuity And Uncovered Provenance Closeout

Status: Done

## Goal

Close the last confirmed replay/doc mismatch from the latest live ETH audit and
make the remaining uncovered tail observable without pretending it is always a
pipeline bug.

## Corrected Diagnosis

After re-checking live Mongo and the current asset-ledger API, the previous
"remaining ETH bridge miss" diagnosis was no longer fully accurate.

What is true now:

- several previously reported bridge misses already heal through late carry
  attachment under the source-side tx hash
- the large live ETH uncovered tail is dominated by propagated incomplete
  source history, not by a fresh Bybit or same-family bridge defect
- a representative example is the Base -> zkSync lane:
  - `0xbdf26819493244fc76cc7fa9714f8788770e4f991f4285df81ef9af1974cc45c`
    (`BASE WRAP ETH -> WETH`)
  - `0xc8ba2e26c67b05d3b7f91106eaa328ec88b449b8ca4004aff1f7db7e1585ede8`
    (`BASE BRIDGE_OUT WETH`)
  - `0x6cbda0a45b4e882a512d1cf64e46a6ec9657d8666565140c20847140667cb043`
    (`ZKSYNC BRIDGE_IN WETH`)
  - `0xcfe0fd4d86b0116fecf0ffaaba0a41c5b26a174a7360981e968a6b2ed57f4e96`
    (`ZKSYNC LENDING_DEPOSIT WETH -> aZksWETH`)

The decisive live finding:

- immediately before `0xbdf...` the Base native `ETH` bucket is already zero in
  tracked ledger state
- the wrap therefore propagates pre-existing / untracked source history rather
  than destroying a covered lot that already existed in replay

At the same time, there was still one confirmed code-policy mismatch:

- docs already said `WRAP / UNWRAP` are continuity, not buy/sell
- replay still excluded canonical `WRAP / UNWRAP` from the order-insensitive
  simple family-equivalent custody lane used for audited `Aave`-style rows

That mismatch needed fixing even though it is not the dominant cause of the
current live ETH tail.

## Scope

In scope:

- replay support for canonical `WRAP / UNWRAP` inside the existing
  order-insensitive family-equivalent custody path
- current asset-ledger diagnostics for live uncovered buckets
- docs correction describing the updated root cause

Out of scope:

- inventing basis for prehistory / missing source evidence
- forcing current uncovered balances to zero when no source carry exists
- changing canonical normalization for spam or unknown rows

## Acceptance Criteria

1. Canonical `WRAP / UNWRAP` rows with one negative family leg and one positive
   family leg replay as an atomic carry pair.
2. Inbound-first flow order must not create a synthetic uncovered wrapped/native
   acquisition.
3. Existing audited `Aave` simple custody behavior remains unchanged.
4. Session asset-ledger current state exposes uncovered-bucket diagnostics so
   remaining live uncovered quantity can be separated into:
   - `NO_COVERED_LEDGER`
   - `PARTIAL_CARRY`
   - `MISSING_LEDGER_BUCKET`
5. The diagnostics remain read-only and do not synthesize any new basis.

## Backend Tasks

1. `BE-R43-01` Extend `AvcoReplayService` simple family-equivalent custody
   selection to canonical `WRAP / UNWRAP`.
2. `BE-R43-02` Add replay regression tests for:
   - `WRAP` with inbound-first wrapped leg
   - `UNWRAP` with inbound-first native leg
3. `BE-R43-03` Extend `AssetLedgerQueryService.CurrentStateView` with
   uncovered-bucket diagnostics.
4. `BE-R43-04` Add query-service regression coverage for uncovered-bucket
   diagnostics.
5. `BE-R43-05` Run a derived-state-only rerun after deployment:
   - keep raw / normalized evidence
   - clear `asset_ledger_points`
   - clear `on_chain_balances`

## Architecture Notes

The latest live audit changes the remediation priority:

- remaining large uncovered ETH is no longer assumed to be "broken replay until
  proven otherwise"
- the product must distinguish:
  - fixable downstream carry defects
  - honest prehistory / missing-source evidence

This is why the diagnostic surface is part of the closeout, not just a UI
nice-to-have.

## Completion Notes

- replay now treats canonical `WRAP / UNWRAP` as part of the existing
  order-insensitive family-equivalent custody path
- asset-ledger current state now exposes per-bucket uncovered diagnostics
- rerun remains derived-state only; raw and canonical normalized evidence stay
  untouched
