# Run 19 LP Principal Continuity Closeout

Goal:

Close the `run/19` active-lane blocker where canonical `LP_ENTRY` rows still
persist principal `SELL` flows and canonical `LP_EXIT` rows still persist
principal `BUY` flows. LP principal must remain custody continuity, not
disposal/acquisition.

## Scope

In scope:
- persist underlying LP principal legs as `TRANSFER` for:
  - `LP_ENTRY`
  - `LP_EXIT`
  - `LP_EXIT_PARTIAL`
  - `LP_EXIT_FINAL`
  - legacy `LP_ADJUST`
- keep `LP_FEE_CLAIM` inbound economics as `BUY`
- carry LP principal basis through replay using LP custody continuity rather
  than unresolved generic transfer pairing
- ignore LP receipt markers (`LP token`, `BPT`, CL NFT) as basis assets during
  replay; they must not open new lots or pending transfer tails
- rerun normalization using existing raw and clarification evidence only

Out of scope:
- redefining LP family classifier boundaries
- new clarification/backfill providers or extra receipt downloads
- changing swap, bridge, lending, or Bybit semantics
- introducing priceable synthetic LP token buys/sells

## Acceptance Criteria (DoD)

1. Canonical `LP_ENTRY` principal outflows persist with `role = TRANSFER`, not
   `SELL`.
2. Canonical `LP_EXIT`, `LP_EXIT_PARTIAL`, and `LP_EXIT_FINAL` principal inflows
   persist with `role = TRANSFER`, not `BUY`.
3. Legacy `LP_ADJUST` non-fee flows persist as continuity `TRANSFER`, not
   synthetic `BUY` / `SELL`.
4. Canonical `LP_FEE_CLAIM` keeps inbound economic fee/reward flows as `BUY`.
5. AVCO replay moves `LP_ENTRY` principal into LP custody continuity and
   restores `LP_EXIT*` principal from LP custody continuity without opening
   disposal/acquisition accounting.
6. LP receipt-marker flows (`LP token`, `BPT`, CL NFT) do not create new basis
   lots, unmatched transfer tails, or zero-balance phantom positions during
   replay.
7. No new clarification/backfill step is required. Existing raw and persisted
   clarification evidence are sufficient; fix is rerun-only.
8. Mongo rerun prep resets derived state only and preserves source raw evidence
   and clarification evidence.

## Edge Cases

- Case: Balancer `joinPool(...)` has outbound `USDC` and inbound `BPT`.
  - Scope: In
  - Expected: both flows persist as `TRANSFER`; replay carries basis on the
    `USDC` custody side and does not open a new `BPT` basis lot.

- Case: concentrated-liquidity `modifyLiquidities(...)` mint path has outbound
  token funding and no explicit NFT transfer in explorer transfers.
  - Scope: In
  - Expected: outbound principal still persists as `TRANSFER`; absence of a
    receipt-marker flow does not demote the row.

- Case: `collect(...)` fee-only row has positive wallet inflow.
  - Scope: In
  - Expected: canonical type remains `LP_FEE_CLAIM`; inbound economics stay
    `BUY`.

- Case: `LP_EXIT` burns an LP marker and returns underlying token principal.
  - Scope: In
  - Expected: LP marker burn is ignored as a basis asset; underlying inflow
    restores carry-over basis from LP custody continuity.

## Task Breakdown

1. `BE-07AB` LP principal flow-role closeout
   - update normalization role assignment for `LP_ENTRY`, `LP_EXIT`,
     `LP_EXIT_PARTIAL`, `LP_EXIT_FINAL`, and legacy `LP_ADJUST`
   - keep `LP_FEE_CLAIM` pricing semantics unchanged

2. `BE-07AC` LP replay continuity bucket closeout
   - move LP principal into and out of a dedicated continuity bucket during
     replay
   - avoid generic tx-hash transfer pairing for LP principal

3. `BE-07AD` LP receipt-marker safety lock
   - ignore LP receipt-marker flows as independent basis assets during replay
   - prevent unmatched transfer tails or phantom zero-balance positions

4. `BE-07AE` rerun preparation pack
   - reset derived collections and processing state only
   - preserve raw on-chain evidence, persisted clarification evidence, and
     imported Bybit evidence

## Risk Notes

- The main risk is only fixing normalization roles while leaving replay on
  generic transfer pairing. That would remove false `BUY` / `SELL`, but still
  lose LP carry-over basis.
- LP fee claim semantics must stay separate from LP principal continuity.
- Mongo reset must not delete top-level `clarificationEvidence` or imported
  `external_ledger_raw` evidence.
