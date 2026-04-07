# Run 22 Async Request / Batch Safety Closeout

Goal:

Close the `run/22` active-lane blockers by moving three unsafe on-chain families
out of priceable semantics unless WalletRadar can already prove their full basis
lifecycle from current raw plus persisted clarification evidence:

- GMX async deposit request / execute lifecycle
- audited Avalanche Euler `batch(...)` borrow-backed collateral openings
- Resolv burn-only `initiateWithdrawal(uint256)` unbonding initiation

## Scope

In scope:
- keep the current canonical schema and one-doc-per-tx model
- prefer explicit blocker review over silent active-lane corruption
- demote GMX async deposit request / settlement rows out of priceable
  `EXTERNAL_TRANSFER_OUT` / `VAULT_DEPOSIT`
- restore the audited Euler batch family to explicit blocker review unless a
  financially complete decoder exists
- demote burn-only Resolv unbonding initiation out of priceable
  `LENDING_WITHDRAW`
- allow GMX async lifecycle review rows to enter full-receipt clarification so
  future reruns persist more evidence without new backfill
- reset derived state only for rerun preparation

Out of scope:
- introducing a multi-row request-key correlation engine
- inventing synthetic basis carry for unresolved async lifecycle rows
- new backfill providers or extra raw source collections
- generic redesign of all lending / vault request families beyond the observed
  deterministic run/22 patterns

## Acceptance Criteria (DoD)

1. GMX helper `multicall(bytes[] data)` deposit-request rows with the audited
   calldata pattern and outbound non-share principal no longer remain priceable
   `EXTERNAL_TRANSFER_OUT`.
2. Representative full hashes
   `0x65ff93bb47919df22ae36055e0e8102c9ddec1f3e5e67e4e6fad7f694b6cff28`,
   `0x396547aae439b5e6a9f1da997f979819bee87e3e86317d003d8a6f1236130cdc`,
   `0xff684e80f06286cfb8b30b8c9011eb6b7ca109117b9523cbbcac756a3b242e79`,
   `0x9c6c4c6859449a0317f30d9ac340d741ecfdb684796ab3b3370c6fa63abc1d72`,
   and `0x9800006ec5b79e6710350872cd5dac4963e188a87062e581aca273f51da4f9ee`
   leave `PENDING_PRICE`.
3. GMX `executeDeposit(bytes32 key,tuple oracleParams)` and
   `executeGlvDeposit(bytes32 key,tuple oracleParams)` settlement rows no
   longer remain priceable standalone `VAULT_DEPOSIT`.
4. Representative full hashes
   `0x3ad60ac2e1c46805cebb2d0f8a5a1002364f701ebb88fdc7378a2b5bce06beab`,
   `0x61c1272c91efebcc1f118bdfb1ab840260f73dff32b439398899f0140f123a98`,
   `0x1aa3438d2be03e761a607c42e5f66a778a5a7890ebcbc9dd4e45c502d75330fb`,
   `0x52924cd84b0dcab31a9be5d6157af7a5c594ebe5cd8d73965746191d21912a6f`,
   and `0x9fab1650749416a4fcf94f02cf16abd99b80f3ec1f1a18851c6f891a21391579`
   leave `PENDING_PRICE`.
5. Those GMX rows become explicit blocker rows until deterministic lifecycle
   correlation exists; they do not silently downgrade to `CONFIRMED UNKNOWN`.
6. GMX async lifecycle blocker rows become eligible for full-receipt
   clarification so reruns can persist additional request / execution evidence
   without a new backfill.
7. The audited Avalanche Euler batch full hashes
   `0x1e0c429514e9cf892b0b6a11e3cfb290eff5c0c26a557c835496e4ba61717fdb`,
   `0x233c2b959739d298d1012405e9b3d7e535a87d590a81bcb304c6dc0cb3ce5e4f`,
   and `0x305f37a69956a13001962216c845385996114876173bdbaef644bbe3baadf5df`
   no longer reopen into active `LENDING_DEPOSIT`.
8. Those Euler rows persist explicit blocker review with a deterministic reason
   such as `EULER_BATCH_DECODER_REQUIRED`.
9. `0xd446b9d8a4b32b795cc957dc0e8381792bdf283824a8faf979042115f4c961c0`
   no longer remains priceable `LENDING_WITHDRAW`.
10. That Resolv row persists as explicit pending-unbonding review, not as a
    finalized withdraw.
11. No new backfill is required. Existing raw evidence is sufficient.
12. Mongo rerun prep resets only derived state and processing status while
    preserving raw source evidence, imported Bybit evidence, and persisted
    clarification evidence.

## Edge Cases

- Case: GMX helper `multicall(bytes[] data)` matches the audited calldata
  pattern but outbound principal is a GM / GLV share token rather than a
  stablecoin or underlying asset.
  - Scope: Out for `run/22` blocker closeout
  - Expected: do not force it into the audited deposit-request rule from this
    slice alone.

- Case: GMX execute-side settlement mints only GM / GLV share plus small native
  refund and still lacks correlated request-side principal.
  - Scope: In
  - Expected: explicit blocker review, not priceable `VAULT_DEPOSIT`.

- Case: Euler `batch(...)` has full receipt evidence but only proves debt/share
  marker churn, not a financially complete wallet-boundary lifecycle.
  - Scope: In
  - Expected: explicit blocker review, not auto-upgraded lending semantics.

- Case: simple share-inbound plus principal-outbound batch without debt-backed
  collateral-open evidence.
  - Scope: In
  - Expected: keep current deterministic clarified lending path unchanged.

- Case: `initiateWithdrawal(uint256)` burns `stRESOLV` with no inbound
  `RESOLV`.
  - Scope: In
  - Expected: explicit pending-unbonding review, not finalized withdraw.

## Task Breakdown

1. `BE-07AO` GMX async deposit request safety closeout
   - detect the audited helper `multicall(bytes[] data)` deposit-request pattern
     from production-available calldata and movement shape
   - remove it from active `EXTERNAL_TRANSFER_OUT`
   - persist an explicit blocker reason

2. `BE-07AP` GMX executeDeposit / executeGlvDeposit settlement safety closeout
   - remove standalone settlement-side rows from active `VAULT_DEPOSIT`
   - keep them explicit blocker rows until request correlation exists
   - route them into the full-receipt clarification allowlist

3. `BE-07AQ` Euler batch reopen lock
   - stop auto-promoting audited borrow-backed Euler batch rows into active
     `LENDING_DEPOSIT` from debt/share marker evidence alone
   - restore explicit blocker reasoning for the audited family

4. `BE-07AR` Resolv unbonding-request safety closeout
   - narrow `initiateWithdrawal(uint256)` burn-only rows out of
     `LENDING_WITHDRAW`
   - persist explicit pending-unbonding review semantics

5. `BE-07AS` regression lock + rerun prep
   - add classifier tests for GMX request, GMX settlement, Euler batch reopen,
     and Resolv unbonding request
   - add clarification-eligibility / service tests for GMX full-receipt
     allowlist where applicable
   - reset derived collections and processing state only

## Risk Notes

- The main risk is solving the active-lane bug by silently excluding basis-
  relevant rows from accounting. This slice must prefer explicit blocker review
  over hidden omission.
- Do not claim GMX async lifecycle is financially closed if the runtime still
  cannot correlate request-side principal with execute-side settlement.
- Do not leave the audited Euler rows in `PENDING_PRICE` just because receipt
  logs are rich. Rich logs are not enough if replay semantics are still
  incomplete.
- Do not introduce a new backfill dependency in this slice. The fix must work
  with current raw plus persisted clarification evidence.
