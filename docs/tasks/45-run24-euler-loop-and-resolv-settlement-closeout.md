# Run 24 Euler Loop + Resolv Settlement Closeout

Goal:

Close the remaining `run/24` normalization blockers without any new raw source
or external backfill:

- audited Resolv unstake settlement role mismatch
- audited Euler `batch(...)` leverage / looping open rows
- audited Euler partial unwind rows needed to avoid the next silent active-lane
  replay leak

## Scope

In scope:
- keep the current one-doc-per-tx canonical model
- keep existing raw / clarification evidence as the only source of truth
- resolve audited Euler loop rows with a pragmatic share-position accounting
  model
- cover partial unwind semantics for the currently observed Euler close-side
  family
- keep Mongo rerun prep limited to derived state only

Out of scope:
- new explorer providers, new backfill jobs, or extra raw collections
- generalized liability accounting for all leverage protocols
- full decoder support for every Euler `batch(...)` variant beyond the audited
  run/24 families

## Acceptance Criteria (DoD)

1. `0xde8afcabd1284b6b287eb9550d204e03d9d1c59da518a2d5acbe3f4613f38a5b`
   no longer persists its principal `RESOLV` payout as `BUY`.
2. The correlated Resolv pair
   `0xd446b9d8a4b32b795cc957dc0e8381792bdf283824a8faf979042115f4c961c0`
   -> `0xde8afcabd1284b6b287eb9550d204e03d9d1c59da518a2d5acbe3f4613f38a5b`
   remains `STAKING_WITHDRAW_REQUEST -> STAKING_WITHDRAW`, but the settlement
   principal leg is continuity `TRANSFER`.
3. Audited Euler open rows
   `0x1e0c429514e9cf892b0b6a11e3cfb290eff5c0c26a557c835496e4ba61717fdb`,
   `0x233c2b959739d298d1012405e9b3d7e535a87d590a81bcb304c6dc0cb3ce5e4f`,
   `0x305f37a69956a13001962216c845385996114876173bdbaef644bbe3baadf5df`
   no longer resolve to plain `LENDING_DEPOSIT`.
4. Those three audited Euler open rows resolve to explicit
   `LENDING_LOOP_OPEN`.
5. Their canonical economic leg is the acquired collateral-share asset
   (`eUSDC-2` in the audited rows), not a marker-only transfer.
6. Their share-acquisition leg already carries deterministic event-local price
   from current clarified stable-like supply evidence; no external market-data
   lookup is required for that leg.
7. Audited Euler partial unwind rows
   `0xb4f475053ead9e30ba41032cbc0382bfb52c7f6a9782b47c21ead294c89f55ec`
   and `0x46177d31314a31e6934fdaca01c8d24d50a5e260de4b66fd1dda74e990d3d69d`
   no longer persist as generic `LP_EXIT`.
8. Those audited partial unwind rows resolve to `LENDING_LOOP_DECREASE` with:
   - `SELL` on the collateral-share asset
   - `BUY` on the returned wallet-visible stable-like asset
   - tx-local implied execution price persisted on the share disposal leg
9. Audited Euler final unwind rows
   `0xf9db2e5ecd31eb22ed030b64e63c68f4f940d5f6f7a828ced74e0e9d0fd3ba5a`
   and `0xe48503f199b371592abbe831e59b78073e3185adac2533c7953a03804f3b29b6`
   no longer persist as generic `SWAP`.
10. Those audited final unwind rows resolve to `LENDING_LOOP_CLOSE` with:
    - `SELL` on the collateral-share asset
    - `BUY` on returned `USDC`
11. Replay remains deterministic and needs no new special carry logic for these
    audited Euler loop rows because the canonical share acquisition/disposal
    legs now carry the required basis semantics directly.
12. No new backfill is required. Existing raw plus clarification evidence is
    sufficient.
13. Mongo rerun prep resets only derived collections and processing status while
    preserving raw source evidence, imported Bybit evidence, and persisted
    clarification evidence.

## Edge Cases

- Case: Euler loop open has debt-marker mint and share mint but the clarified
  stable-like supply leg cannot be identified deterministically.
  - Scope: In
  - Expected: remain explicit blocker review; do not fabricate a priced
    `LENDING_LOOP_OPEN`.

- Case: Euler batch burns share to zero and returns underlying to the wallet.
  - Scope: In
  - Expected: resolve to `LENDING_LOOP_CLOSE`, not generic `SWAP`.

- Case: Euler batch reduces share exposure and returns a stable-like debt asset
  to the wallet without a zero-address burn.
  - Scope: In
  - Expected: resolve to `LENDING_LOOP_DECREASE`, not `LP_EXIT`.

- Case: Resolv settlement returns principal only.
  - Scope: In
  - Expected: stay continuity `TRANSFER`; do not create a fresh acquisition lot.

## Task Breakdown

1. `BE-07AZ` Resolv settlement continuity role closeout
   - keep `STAKING_WITHDRAW_REQUEST -> STAKING_WITHDRAW`
   - build transfer-only settlement flows for audited Resolv payout rows
   - preserve current `correlationId`

2. `BE-07BA` Euler loop open canonicalization closeout
   - add `LENDING_LOOP_OPEN`
   - detect audited borrow-backed Euler opens from current clarified evidence
   - persist the collateral-share acquisition leg with implied event-local price

3. `BE-07BB` Euler partial/final unwind canonicalization closeout
   - add `LENDING_LOOP_DECREASE` and `LENDING_LOOP_CLOSE`
   - resolve audited partial unwind rows from current wallet-boundary raw
     transfers
   - resolve audited share-burn unwind rows from current wallet-boundary raw
     transfers

4. `BE-07BC` replay/pricing contract lock
   - ensure pre-resolved Euler loop leg prices survive pricing unchanged
   - ensure replay works on canonical share acquisition/disposal semantics
     without bespoke post-pricing hacks

5. `BE-07BD` regression lock + rerun prep
   - add classifier / pricing / replay regression tests for Resolv and Euler
   - reset derived collections and processing status only

## Risk Notes

- Do not reopen this slice into generalized liability accounting for all
  leveraged protocols. The current goal is deterministic audited normalization on
  the observed Euler family.
- Do not fabricate Euler loop pricing from external market data when current
  clarified stable-like execution evidence already exists.
- Do not keep audited Euler partial/final unwind rows in generic `LP_EXIT` or
  `SWAP`; that silently corrupts AVCO on the share-position asset.
- Do not add new backfill dependency in this slice.
