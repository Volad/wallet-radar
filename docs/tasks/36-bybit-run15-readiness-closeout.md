# Bybit Run 15 Readiness Closeout

Goal:

Close the run/15 blockers that still prevent safe `pricing -> AVCO / cost basis / move basis` on the combined on-chain + Bybit universe.

## Scope

In scope:
- fix legacy Bybit inbound canonical mapping:
  - raw `canonicalType=EXTERNAL_INBOUND`
  - synthetic `withdraw_deposit` inbound canonical rows
- prevent basis-relevant Bybit rows from silently persisting as `UNKNOWN / CONFIRMED`
- remove residual `UTA_TRADE_PAIR_NOT_FOUND` rows from the priceable lane
- keep Bybit loan rows as explicit stop-condition `NEEDS_REVIEW`
- prepare Mongo for a clean rerun of on-chain normalization, Bybit normalization, clarification, pricing, and replay

Out of scope:
- redesigning Bybit import format
- adding a new external provider beyond Bybit
- changing AVCO policy
- changing on-chain classification rules outside the run/15 blocker set

## Acceptance Criteria (DoD)

1. Basis-relevant `fund_asset_changes` inbound rows with `canonicalType=EXTERNAL_INBOUND` normalize as `EXTERNAL_TRANSFER_IN`, not `UNKNOWN`.
2. Synthetic `withdraw_deposit` inbound rows normalize as `EXTERNAL_TRANSFER_IN`, not `UNKNOWN`.
3. Matched synthetic Bybit inbound rows preserve `correlationId`, `continuityCandidate`, and `matchedCounterparty`.
4. Basis-relevant Bybit rows with an unmapped canonical type do not become silent `CONFIRMED UNKNOWN`; they go to `NEEDS_REVIEW` with an explicit missing-data reason.
5. Residual `UTA_TRADE_PAIR_NOT_FOUND` rows do not remain in `PENDING_PRICE`.
6. Residual `UTA_TRADE_PAIR_NOT_FOUND` rows still preserve the observed one-leg evidence so the audit trail is not lost.
7. `BYBIT_LOAN_SEMANTICS_UNSUPPORTED` rows remain explicit `NEEDS_REVIEW` stop-condition rows.
8. `BRIDGE_ON_CHAIN_LEG_NOT_FOUND` unmatched withdraw rows remain owner-agnostic `EXTERNAL_TRANSFER_OUT`; they are not force-promoted into continuity.
9. A rerun after this slice no longer produces basis-relevant Bybit inbound rows as `CONFIRMED UNKNOWN`.
10. Mongo rerun prep resets derived pipeline state without deleting raw source evidence.

## Edge Cases

- Case: `fund_asset_changes` inbound row still carries legacy `EXTERNAL_INBOUND`.
  - Scope: In
  - Expected: normalized type becomes `EXTERNAL_TRANSFER_IN`.

- Case: `withdraw_deposit` inbound row materializes via synthetic tx-based `_id`.
  - Scope: In
  - Expected: normalized type is `EXTERNAL_TRANSFER_IN`; correlated same-universe metadata is preserved when an on-chain sibling exists.

- Case: basis-relevant Bybit row has a canonical string that no runtime mapper recognizes.
  - Scope: In
  - Expected: row becomes `NEEDS_REVIEW` with explicit unmapped-canonical reason, never `CONFIRMED UNKNOWN`.

- Case: residual UTA orphan leg exists in imported raw without any matching counter-leg.
  - Scope: In
  - Expected: row is non-priceable and explicit review; it does not silently acquire market price treatment.

- Case: Bybit withdraw has no matching tracked on-chain raw tx.
  - Scope: In
  - Expected: row remains owner-agnostic `EXTERNAL_TRANSFER_OUT`; continuity is not forced.

## Task Breakdown

1. `BE-07K` Bybit inbound legacy canonical mapping closeout
   - map legacy `EXTERNAL_INBOUND` into runtime `EXTERNAL_TRANSFER_IN`
   - use canonical mapped type when generating synthetic normalized ids

2. `BE-07L` Bybit silent-unknown safety gate closeout
   - prevent basis-relevant mapped Bybit rows from landing in `CONFIRMED UNKNOWN`
   - emit explicit `NEEDS_REVIEW` when canonical type is unmapped

3. `BE-07M` Bybit orphan UTA evidence gate closeout
   - keep the observed one-leg evidence
   - move `UTA_TRADE_PAIR_NOT_FOUND` rows out of `PENDING_PRICE`

4. `BE-07N` Run/15 replay-readiness rerun pack
   - reset derived collections and processing state only
   - keep `raw_transactions.rawData`, `raw_transactions.clarificationEvidence`, and `external_ledger_raw` evidence intact
   - provide deterministic Mongo end state for rerun

## Risk Notes

- The main risk is not explicit review tail; it is silent canonical loss in Bybit inbound rows that look resolved but cannot mint basis.
- Moving orphan UTA rows out of pricing may increase visible review tail, but that is financially safer than allowing incomplete trades into replay.
- Synthetic `withdraw_deposit` ids must remain deterministic across reruns even after the legacy inbound rename.
