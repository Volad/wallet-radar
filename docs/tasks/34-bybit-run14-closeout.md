# Bybit Run 14 Closeout

Goal:

Close the `run/14` Bybit blockers so the unified `ON_CHAIN + BYBIT` canonical stream becomes safe for `pricing -> AVCO -> move basis` replay.

## Scope

In scope:
- Bybit normalization correctness only
- `external_ledger_raw -> normalized_transactions`
- canonical movement semantics for:
  - `fund_asset_changes` `Convert`
  - `uta_derivatives` `TRADE`
  - matched `withdraw_deposit`
  - rare `ETH 2.0` and `Loans` rows surfaced by `run/14`
- deterministic dedup / rerun safety
- regression coverage and rerun handoff

Out of scope:
- changing on-chain classification / clarification rules
- changing pricing policy
- changing AVCO replay rules
- adding new evidence sources beyond already imported Bybit raw data

## Acceptance Criteria (DoD)

1. `fund_asset_changes` `Convert` rows no longer produce canonical `SWAP` rows with `flows=[]`.
2. Every normalized Bybit `SWAP` row persists explicit dual-leg economic movement sufficient for pricing and replay.
3. `uta_derivatives` trade rows are greedily paired into deterministic dual-leg `SWAP` rows whenever a matching counter-leg exists in imported raw data.
4. The UTA trade pairer does not rely on stablecoin-suffix-only contract parsing to recover the traded assets; paired raw asset symbols remain the authoritative leg assets.
5. If a UTA trade row truly has no recoverable counter-leg in imported raw data, it may still fall back to orphan behavior, but that fallback must be exceptional rather than the steady-state result for the entire trade family.
6. Live rerun after the fix reduces `missingDataReasons = UTA_TRADE_PAIR_NOT_FOUND` from the current systemic `1132` rows to only genuine residual orphan cases.
7. Overlapping `withdraw_deposit` exports may not create duplicate normalized `INTERNAL_TRANSFER` rows for the same business event.
8. A matched Bybit/on-chain transfer correlation is unique on the Bybit side by business key and unique on the on-chain side by `correlationId`.
9. The duplicate event found in `run/14` for tx hash `0x2efc1c7051cb0f55aa591886fd93903f0bdc5f3057c35ff992ccc6ae2ac00d71` is no longer double-materialized after rerun.
10. Rare `ETH 2.0` and `Loans` rows may not remain silently priceable with semantically weak one-leg BUY/SELL mappings if current raw evidence is insufficient to prove those semantics.
11. For rare Bybit families that are still not deterministic from imported raw rows, normalization must prefer an explicit bounded review / unsupported lane over silent economic misstatement.
12. Bybit normalization remains idempotent, retry-safe, and rerun-safe.
13. Existing matched transfer continuity between Bybit and on-chain remains intact; the fix may not regress the previously working `correlationId` carry-over path.
14. Existing on-chain normalization / clarification behavior remains unchanged.

## Edge Cases

- Case: `Convert` consists of exactly two opposite-asset rows within the same second.
  - Scope: In
  - Expected: one deterministic canonical `SWAP` with both legs persisted.

- Case: `Convert` cluster contains more than two rows at nearly the same timestamp.
  - Scope: In
  - Expected: normalization uses deterministic grouping/pairing and either closes each resolvable pair or sends unresolved residue to explicit review; it does not silently emit empty-flow swaps.

- Case: UTA trade contract is exotic (`BBSOLSOL`) and cannot be parsed by a stablecoin suffix table.
  - Scope: In
  - Expected: paired asset symbols come from the actual raw rows; normalization does not crash and does not drop the counter-leg.

- Case: UTA trade raw rows are imported without one counter-leg.
  - Scope: In
  - Expected: row may remain orphan with explicit missing reason, but this must reflect a true raw-data gap rather than a pairing-logic failure.

- Case: Same `withdraw_deposit` tx hash exists in two imported source files because export windows overlap.
  - Scope: In
  - Expected: normalization materializes only one canonical Bybit event for that business key.

- Case: `ETH 2.0` stake and mint appear as separate rows far apart in time.
  - Scope: In
  - Expected: if current imported raw cannot deterministically pair them, they may not remain silently priceable with misleading one-leg semantics.

- Case: `Loans / Pledge Assets` or `Repay Principal` rows expose only one asset movement.
  - Scope: In
  - Expected: normalization either maps them to an explicit continuity-safe supported semantic or leaves them out of the priceable lane pending documented support.

## Task Breakdown

1. `BE-07A` Bybit `Convert` pairing closeout
   - pair `fund_asset_changes` `Convert` rows into deterministic dual-leg canonical swaps
   - remove empty-flow `SWAP` steady state

2. `BE-07B` Bybit UTA trade pair-recovery closeout
   - restore deterministic opposite-leg pairing for `uta_derivatives`
   - build canonical `SWAP` from paired raw asset symbols and execution evidence
   - keep orphan fallback only for genuine unmatched residue

3. `BE-07C` Bybit overlap-dedup closeout
   - prevent duplicate normalization of the same `withdraw_deposit` business event across overlapping exports
   - make correlation uniqueness auditable

4. `BE-07D` Bybit rare-family safety gate
   - review `ETH 2.0` and `Loans` raw families from `run/14`
   - either close them deterministically or demote them from silent priceable semantics

5. `BE-07E` Bybit rerun pack + repeat-audit handoff
   - rerun-safe cleanup instructions
   - regression tests
   - handoff to repeat `run/15` financial audit

## Risk Notes

- The main risk is silent financial wrongness, not visible review backlog.
- `NEEDS_REVIEW = 0` must not be used as a readiness signal if Bybit economic rows are still semantically incomplete.
- Dedup must be based on a deterministic business key, not on file name alone.
- The implementation must not widen evidence beyond imported `external_ledger_raw`.
- The implementation must not regress existing matched Bybit/on-chain transfer continuity.

## Architecture Notes

- This slice stays inside the existing `external_ledger_raw -> normalized_transactions` architecture.
- No new storage system, external provider, or paid service is introduced.
- Canonical read path remains unchanged: replay consumes `normalized_transactions` only.
- The goal is not to redesign replay, but to make Bybit normalized evidence replay-safe.
