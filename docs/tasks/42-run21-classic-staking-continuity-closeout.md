# Run 21 Classic Staking Continuity Closeout

Goal:

Close the `run/21` active-lane blocker where Pancake SmartChef
`deposit(uint256)` rows on BSC still persist staked `CAKE` principal as
economic `SELL` instead of staking continuity.

## Scope

In scope:
- keep canonical `STAKING_DEPOSIT` / `STAKING_WITHDRAW` types unchanged
- split liquid-staking versus classic stake-contract principal-role semantics
  for the observed Pancake SmartChef family
- keep SmartChef principal as continuity `TRANSFER`
- keep harvested reward side-flows inside the same staking tx as economic `BUY`
- add staking continuity bucket support in replay so staked principal carries
  basis into and out of staking custody
- reset derived state only for rerun preparation

Out of scope:
- generic redesign of all staking protocols without current raw evidence
- new backfill providers or extra receipt downloads
- changes to Bybit normalization
- changing liquid-staking semantics such as `AVAX -> sAVAX`

## Acceptance Criteria (DoD)

1. Pancake SmartChef `deposit(uint256)` on
   `0x7816f1711828c52eb3ca5a2f075a0c06e0548bd6` remains
   `STAKING_DEPOSIT`, not `EXTERNAL_TRANSFER_OUT` or `REWARD_CLAIM`.
2. In those rows, staked `CAKE` principal no longer persists as `SELL`.
3. That staked principal persists as `TRANSFER`.
4. Any harvested reward-token inbound in the same tx remains `BUY`.
5. Representative full hash
   `0x4ac9ed2fd7a0a1e51c71fef2962cf161fc36652c28b229a742718a9ee886c7e7`
   keeps `CAKE:TRANSFER` and reward `U:BUY`.
6. Representative full hash
   `0x2de9c04e35a6a1942db36af5afab317b1aa6ff08de4ce1c4622a7f23feedd698`
   no longer fabricates a `CAKE` sale.
7. Liquid-staking rows such as `AVAX -> sAVAX` keep current
   `SELL AVAX / BUY sAVAX` semantics.
8. Replay moves `STAKING_DEPOSIT` principal into staking continuity custody
   instead of marking it as unresolved generic transfer.
9. Replay is ready to restore `STAKING_WITHDRAW` principal from the same
   continuity bucket once those rows persist `TRANSFER` principal semantics.
10. No new backfill is required. Existing raw evidence is sufficient.
11. Mongo rerun prep resets only derived state and processing status while
    preserving raw source evidence and persisted clarification evidence.

## Edge Cases

- Case: SmartChef `deposit(uint256)` also harvests a reward token in the same tx.
  - Scope: In
  - Expected: staked principal stays `TRANSFER`; reward inbound stays `BUY`.

- Case: SmartChef stake flow mints or burns a proof-of-ownership marker at the
  same principal quantity.
  - Scope: In
  - Expected: that marker remains continuity-only `TRANSFER`, not `BUY` / `SELL`.

- Case: classic staking tx has no reward side-flow at all.
  - Scope: In
  - Expected: only continuity `TRANSFER` principal and fee remain.

- Case: liquid staking mint such as `submit()` returns a distinct derivative
  asset (`stETH`, `sAVAX`, `rETH`).
  - Scope: In
  - Expected: stays economic `SELL principal / BUY derivative`.

- Case: another stake-contract family is not yet proven by current raw.
  - Scope: Out
  - Expected: do not generalize this slice beyond the observed deterministic
    Pancake SmartChef pattern.

## Task Breakdown

1. `BE-07AK` classic SmartChef staking role closeout
   - add narrow method-aware flow-role override for Pancake SmartChef
     `deposit(uint256)` on `0x7816f1711828c52eb3ca5a2f075a0c06e0548bd6`
   - keep principal/proof-token legs as `TRANSFER`
   - keep harvested reward side-flows as `BUY`

2. `BE-07AL` staking continuity replay bucket closeout
   - add `STAKING_DEPOSIT` principal carry into staking continuity custody
   - add symmetric `STAKING_WITHDRAW` bucket restore support for future
     continuity-classified withdraw rows

3. `BE-07AM` classic-vs-liquid staking regression lock
   - add classifier test for SmartChef deposit with reward side-effect
   - add classifier test that liquid-staking `AVAX -> sAVAX` remains economic
   - add replay/pricing tests for staking continuity principal plus reward
     side-flow

4. `BE-07AN` rerun preparation pack
   - reset derived collections and processing state only
   - preserve raw on-chain evidence, persisted clarification evidence, and
     imported Bybit evidence

## Risk Notes

- The main risk is over-generalizing from one classic staking family and
  accidentally breaking liquid staking. The implementation must stay narrow and
  evidence-driven.
- Do not solve the blocker by excluding SmartChef rows from accounting. They
  are basis-relevant.
- Do not reintroduce synthetic `BUY` / `SELL` on continuity principal just to
  make pricing easier. Pricing must follow accounting semantics, not the other
  way around.
