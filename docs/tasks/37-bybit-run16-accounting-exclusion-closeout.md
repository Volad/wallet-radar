# Bybit Run 16 Accounting Exclusion Closeout

Goal:

Close the `run/16` blocker tail by introducing an explicit accounting-exclusion
contract for deterministic Bybit rows that are known, persisted, and auditable,
but still unsupported or insufficient for safe replay.

## Scope

In scope:
- add an explicit normalized-document exclusion contract:
  - `excludedFromAccounting`
  - `accountingExclusionReason`
- mark residual Bybit orphan UTA trade legs as excluded audit-only review rows
- mark unsupported Bybit loan rows as excluded audit-only review rows
- update pricing / replay readiness gates so excluded review rows do not block
  `avcoReady`
- update operational telemetry so blocking review rows and excluded review rows
  are visible separately
- prepare Mongo for a clean rerun of on-chain normalization, Bybit normalization,
  clarification, pricing, and replay

Out of scope:
- adding a new Bybit source file or alternate provider
- modeling Bybit loan accounting semantics
- changing AVCO formulas
- changing supported on-chain classification rules outside the `run/16` blocker set

## Acceptance Criteria (DoD)

1. Residual `UTA_TRADE_PAIR_NOT_FOUND` rows persist as:
   - `status = NEEDS_REVIEW`
   - `excludedFromAccounting = true`
   - `accountingExclusionReason = UTA_TRADE_PAIR_NOT_FOUND`
2. Residual `UTA_TRADE_PAIR_NOT_FOUND` rows still preserve the observed one-leg
   evidence in `flows[]`.
3. Residual `BYBIT_LOAN_SEMANTICS_UNSUPPORTED` rows persist as:
   - `status = NEEDS_REVIEW`
   - `excludedFromAccounting = true`
   - `accountingExclusionReason = BYBIT_LOAN_SEMANTICS_UNSUPPORTED`
4. Basis-relevant unmapped Bybit canonical rows do **not** become excluded by
   default; they remain blocking review rows.
5. `PricingDataGateService` counts only blocking review rows when deciding
   `avcoReady`.
6. `PipelineTelemetrySnapshotService` exposes blocking review rows and excluded
   review rows separately.
7. Excluded review rows never enter AVCO replay.
8. Excluded review rows remain queryable in Mongo and visible to audits.
9. A rerun after this slice no longer blocks pricing / replay readiness on the
   current `run/16` Bybit tail, assuming no new blocking review rows appear.
10. Mongo rerun prep resets derived pipeline state without deleting raw source
    evidence or clarification evidence.

## Edge Cases

- Case: residual orphan UTA leg still has one observed `BUY`/`SELL` flow.
  - Scope: In
  - Expected: row remains persisted for audit, but excluded from accounting.

- Case: Bybit loan row is deterministic but semantically unsupported.
  - Scope: In
  - Expected: row remains persisted for audit, but excluded from accounting.

- Case: basis-relevant Bybit row has an unmapped canonical string due to a
  runtime mapper gap.
  - Scope: In
  - Expected: row stays blocking `NEEDS_REVIEW`, not excluded.

- Case: incomplete convert cluster or malformed trade role.
  - Scope: In
  - Expected: row stays blocking `NEEDS_REVIEW`, not excluded.

- Case: unmatched `withdraw_deposit` row with `BRIDGE_ON_CHAIN_LEG_NOT_FOUND`.
  - Scope: In
  - Expected: row remains owner-agnostic `EXTERNAL_TRANSFER_IN/OUT`, not excluded.

## Task Breakdown

1. `BE-07O` normalized accounting-exclusion contract closeout
   - add `excludedFromAccounting`
   - add `accountingExclusionReason`
   - index the new gate shape

2. `BE-07P` Bybit orphan UTA exclusion closeout
   - keep observed leg evidence
   - keep explicit `NEEDS_REVIEW`
   - mark as excluded from accounting

3. `BE-07Q` Bybit unsupported loan exclusion closeout
   - keep explicit `NEEDS_REVIEW`
   - mark as excluded from accounting

4. `BE-07R` pricing / replay gate and telemetry split closeout
   - blocking review count excludes audit-only excluded rows
   - telemetry exposes blocking vs excluded review counts

5. `BE-07S` rerun pack
   - reset derived collections and processing state only
   - keep `raw_transactions.rawData`, `raw_transactions.clarificationEvidence`,
     and `external_ledger_raw` evidence intact

## Risk Notes

- The main risk is silent semantic loss. This slice must not hide new mapper bugs
  behind exclusion flags.
- Exclusion must remain narrow and deterministic. It is not a generic escape hatch
  for unresolved economic rows.
- The resulting replay will be authoritative only for the active accounting scope,
  with excluded rows still visible as audit artifacts.
