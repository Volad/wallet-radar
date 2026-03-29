# Run 23 Async Lifecycle Economic Closeout

Goal:

Finish the remaining `run/23` normalization blocker families without any new
raw source or backfill dependency. The current persisted raw plus saved full
receipt / clarification evidence is sufficient to close:

- GMX async deposit request / execute lifecycle
- audited Avalanche Euler `batch(...)` collateral-open rows
- Resolv unstake cooldown request / later claim lifecycle

## Scope

In scope:
- keep the current one-doc-per-tx canonical model
- introduce explicit async lifecycle types where the tx itself is a request or
  settlement step rather than a single-shot finalized event
- persist deterministic `correlationId` when current raw / receipt evidence can
  already prove the lifecycle key
- allow the replay stage to carry basis through those async lifecycle families
  without creating synthetic sells or buys
- close the 15 blocking `run/23` review rows on current evidence
- reset derived state only for rerun preparation

Out of scope:
- new explorer providers, new backfill jobs, or extra raw source collections
- generalized pending-position reporting outside the current normalized /
  replay model
- redesign of all async lifecycle families beyond the audited GMX / Resolv
  patterns in the current dataset

## Acceptance Criteria (DoD)

1. GMX helper `multicall(bytes[] data)` deposit-request rows with persisted
   event-emitter request keys no longer remain `UNKNOWN / NEEDS_REVIEW`.
2. Representative full hashes
   `0x65ff93bb47919df22ae36055e0e8102c9ddec1f3e5e67e4e6fad7f694b6cff28`,
   `0x396547aae439b5e6a9f1da997f979819bee87e3e86317d003d8a6f1236130cdc`,
   `0xff684e80f06286cfb8b30b8c9011eb6b7ca109117b9523cbbcac756a3b242e79`,
   `0x9c6c4c6859449a0317f30d9ac340d741ecfdb684796ab3b3370c6fa63abc1d72`,
   `0x9800006ec5b79e6710350872cd5dac4963e188a87062e581aca273f51da4f9ee`,
   and `0xc4a56103ffc881bf5900b6e77e0a6b488b810c445f83a07a9e11ff8499635da7`
   resolve to a deterministic async LP-entry request family with persisted
   `correlationId`.
3. GMX `executeDeposit(bytes32 key,tuple oracleParams)` and
   `executeGlvDeposit(bytes32 key,tuple oracleParams)` settlement rows no
   longer remain `UNKNOWN / NEEDS_REVIEW`.
4. Representative full hashes
   `0x3ad60ac2e1c46805cebb2d0f8a5a1002364f701ebb88fdc7378a2b5bce06beab`,
   `0x61c1272c91efebcc1f118bdfb1ab840260f73dff32b439398899f0140f123a98`,
   `0x1aa3438d2be03e761a607c42e5f66a778a5a7890ebcbc9dd4e45c502d75330fb`,
   `0x52924cd84b0dcab31a9be5d6157af7a5c594ebe5cd8d73965746191d21912a6f`,
   and `0x9fab1650749416a4fcf94f02cf16abd99b80f3ec1f1a18851c6f891a21391579`
   resolve to a deterministic async LP-entry settlement family with the same
   `correlationId` as their request-side pair.
5. `0xd446b9d8a4b32b795cc957dc0e8381792bdf283824a8faf979042115f4c961c0`
   no longer remains `UNKNOWN / NEEDS_REVIEW`; it resolves to an explicit
   unstake / cooldown request family with deterministic correlation metadata.
6. `0xde8afcabd1284b6b287eb9550d204e03d9d1c59da518a2d5acbe3f4613f38a5b`
   no longer remains generic `VAULT_WITHDRAW`; it resolves to the audited
   Resolv unstake settlement family and shares the same `correlationId`.
7. The audited Avalanche Euler batch full hashes
   `0x1e0c429514e9cf892b0b6a11e3cfb290eff5c0c26a557c835496e4ba61717fdb`,
   `0x233c2b959739d298d1012405e9b3d7e535a87d590a81bcb304c6dc0cb3ce5e4f`,
   and `0x305f37a69956a13001962216c845385996114876173bdbaef644bbe3baadf5df`
   leave `NEEDS_REVIEW` only when clarification already proves the audited
   borrow / transfer / swap / supply lifecycle; marker-only debt/share evidence
   alone still may not close the family.
8. Wrapper-only / no-movement Euler rows such as
   `0x509c134b2795de71a1ee42db38b53af78003308e8c9ebf2b1bfa9ce8d348dcd2`
   remain explicit stop-condition rows.
9. Replay carries basis deterministically through:
   - `LP_ENTRY_REQUEST -> LP_ENTRY_SETTLEMENT`
   - `STAKING_WITHDRAW_REQUEST -> STAKING_WITHDRAW`
   without inventing synthetic market-price sells or buys.
10. No new backfill is required. Existing raw evidence is sufficient.
11. Mongo rerun prep resets only derived collections and processing status while
    preserving raw source evidence, imported Bybit evidence, and persisted
    clarification evidence.

## Edge Cases

- Case: GMX request row has persisted event-emitter key but no matching
  settlement row exists in the current dataset.
  - Scope: In
  - Expected: the request still resolves to explicit async request semantics;
    it does not stay review-only and does not fabricate a finalized LP entry.

- Case: GMX settlement row has a request key and mints a market / GLV share plus
  a small native refund.
  - Scope: In
  - Expected: the settlement resolves to explicit async settlement semantics;
    same-asset refund may restore carry-over basis, remaining principal cost
    remains attached to the lifecycle rather than becoming a synthetic sale.

- Case: Resolv unstake request burns `stRESOLV` and later claim returns `RESOLV`
  in a separate tx.
  - Scope: In
  - Expected: both rows are deterministic, correlated, and no longer require
    manual review.

- Case: receipt-log-rich Euler batch proves only debt mint + share mint and no
  intermediate borrowed-asset / swap / resupply path.
  - Scope: In
  - Expected: remain explicit review; do not reopen into active
    `LENDING_DEPOSIT`.

## Task Breakdown

1. `BE-07AT` GMX async LP-request closeout
   - extract deterministic request key from persisted GMX event-emitter logs
   - resolve audited request rows into explicit async LP-entry request type
   - persist `correlationId`

2. `BE-07AU` GMX async LP-settlement closeout
   - extract deterministic request key from persisted execute-side logs
   - resolve audited settlement rows into explicit async LP-entry settlement
     type
   - persist the same `correlationId` as the request-side pair

3. `BE-07AV` Resolv cooldown request / claim closeout
   - resolve `initiateWithdrawal(uint256)` into explicit unstake request type
   - resolve `withdraw(bool withdrawBNB, address token)` into audited unstake
     settlement semantics
   - attach deterministic correlation metadata on current evidence

4. `BE-07AW` Euler clarified batch economic closeout
   - close the three audited Avalanche Euler rows only when clarification proves
     the full borrow / swap / supply lifecycle
   - keep wrapper-only / marker-only rows explicit stop-condition review

5. `BE-07AX` correlation projection + replay carry closeout
   - project `correlationId` from classifier output into canonical on-chain docs
   - carry basis through the new async lifecycle families during replay
   - keep generic LP / staking replay behavior unchanged outside this audited
     slice

6. `BE-07AY` regression lock + rerun prep
   - add classifier / builder / replay regression tests for GMX, Resolv, and
     Euler
   - reset derived collections and processing status only

## Risk Notes

- Do not solve `run/23` by quietly excluding audited on-chain rows from
  accounting. The goal is deterministic normalization on current evidence.
- Do not infer GMX request / settlement correlation from time proximity when the
  current persisted request key is already available in logs.
- Do not reopen audited Euler rows from debt/share markers alone. The fix must
  rely on the clarified transfer path, not selector intent.
- Do not require any new backfill or new source system in this slice.
