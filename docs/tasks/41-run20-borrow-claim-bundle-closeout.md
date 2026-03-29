# Run 20 Borrow / Claim Bundle Closeout

Goal:

Close the `run/20` active-lane blocker where:

- `BORROW` / `REPAY` rows on zkSync Aave-style paths still persist debt-marker
  and native settlement legs as economic `BUY` / `SELL`
- Angle `REWARD_CLAIM` rows still persist self-canceling wrapper burn pairs as
  active economic legs
- Pendle `zapOutV3SingleToken(...)` on `0x70f61901658aafb7ae57da0c30695ce4417e72b9`
  still resolves as plain `REWARD_CLAIM` instead of an LP-exit bundle with
  separated reward semantics

## Scope

In scope:
- keep canonical transaction families deterministic and rerun-only
- keep Aave-style `BORROW` / `REPAY` types, but reassign non-principal side
  legs to continuity
- collapse self-canceling same-asset same-quantity marker pairs from active
  `REWARD_CLAIM` economics
- add a narrow Pendle method-aware override for
  `zapOutV3SingleToken(uint256,uint256,tuple,tuple,bool)` on
  `0x70f61901658aafb7ae57da0c30695ce4417e72b9`
- allow pricing for explicit `BUY` / `SELL` side-flows inside continuity-type
  rows while keeping all `TRANSFER` legs non-priceable
- reset derived state only for rerun preparation

Out of scope:
- new backfill providers or extra receipt downloads
- generic redesign of claim bundling beyond the observed deterministic patterns
- changing Bybit semantics
- introducing a new multi-row canonical model

## Acceptance Criteria (DoD)

1. zkSync Aave-style `BORROW` rows keep the reserve asset as the only economic
   `BUY` leg.
2. zkSync Aave-style `REPAY` rows keep the reserve asset as the only economic
   `SELL` leg.
3. `variableDebt*` marker legs and zkSync native settlement / refund legs no
   longer persist as economic `BUY` / `SELL` in `BORROW` / `REPAY`.
4. Angle reward-distributor claim rows no longer persist exact same-asset
   same-quantity wrapper mint/burn pairs as active economic legs.
5. The representative Angle full hash
   `0x627fecf2e434d5f10e783745611aad780c7d6680fdcaef267e568e1f793ef093`
   keeps only true reward inflows in the active economic lane.
6. The representative Pendle full hash
   `0xf7f8908b455261dc67a7f905ca99f1041987de690a7574d440e31739c3132430`
   no longer resolves as plain `REWARD_CLAIM`.
7. That Pendle row resolves as an LP-exit bundle where:
   - the self-canceling LP marker pair is removed from active economics
   - the returned principal output remains `TRANSFER`
   - the true reward leg remains `BUY`
8. Pricing never requests market price for `TRANSFER` flows.
9. Pricing still requests market price for explicit `BUY` / `SELL` side-flows
   even when the parent tx type is a continuity family such as `LP_EXIT`.
10. No new backfill is required. Existing raw and persisted clarification
    evidence are sufficient.
11. Mongo rerun prep resets only derived state and processing status, while
    preserving source raw evidence and clarification evidence.

## Edge Cases

- Case: Aave-style borrow mints `variableDebt*` and also returns small native
  refund legs in the same tx.
  - Scope: In
  - Expected: reserve asset stays `BUY`; debt marker and refund remain
    continuity-only.

- Case: Aave-style repay burns `variableDebt*` to zero address and moves native
  settlement ETH through zkSync system contracts.
  - Scope: In
  - Expected: reserve asset stays `SELL`; debt burn and settlement/refund
    remain continuity-only.

- Case: reward-claim tx has exact same-asset same-quantity inbound and outbound
  legs on one wrapper contract.
  - Scope: In
  - Expected: the pair is removed from active economics; only the true reward
    inflows remain priceable.

- Case: Pendle zap-out bundle has LP marker churn plus one principal output and
  one reward token.
  - Scope: In
  - Expected: principal output remains `TRANSFER`; reward token remains `BUY`.

- Case: continuity-type tx contains only `TRANSFER` and `FEE`.
  - Scope: In
  - Expected: pricing touches only `FEE`.

## Task Breakdown

1. `BE-07AF` borrow/repay side-leg continuity closeout
   - reassign Aave debt-marker and zkSync native settlement/refund legs from
     economic `BUY` / `SELL` into continuity `TRANSFER`
   - keep reserve-asset principal unchanged

2. `BE-07AG` reward-claim self-cancel pair closeout
   - collapse exact same-asset same-quantity in/out marker pairs from active
     `REWARD_CLAIM` economics
   - preserve true reward inflows as priceable `BUY`

3. `BE-07AH` Pendle zap-out bundle override closeout
   - add narrow method-aware handling for `0x8b284b0e` on
     `0x70f61901658aafb7ae57da0c30695ce4417e72b9`
   - emit LP-exit bundle semantics with `TRANSFER` principal and `BUY` reward

4. `BE-07AI` continuity-side-flow pricing lock
   - make `TRANSFER` universally non-priceable
   - keep explicit `BUY` / `SELL` side-flows inside continuity families
     priceable

5. `BE-07AJ` rerun preparation pack
   - reset derived collections and processing state only
   - preserve raw on-chain evidence, persisted clarification evidence, and
     imported Bybit evidence

## Risk Notes

- The main risk is fixing only the audit examples and leaving the generic role
  model inconsistent. The implementation should be deterministic and limited to
  evidence patterns that the runtime can actually observe from current raw.
- Do not solve the Pendle bundle by excluding it from accounting. It is
  basis-relevant.
- Do not reopen Bybit review-tail logic in this slice; `run/20` blockers are
  on-chain only.
