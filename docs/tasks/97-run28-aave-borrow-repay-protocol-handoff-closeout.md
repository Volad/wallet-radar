# Run 28 — Aave Borrow/Repay Protocol Handoff Closeout

## Goal

Keep `protocolName = Aave` on borrow/repay rows even when a real Aave pool tx
falls through the generic selector lane.

## Problem

Current runtime correctly normalizes some `Aave` borrow/repay rows to canonical
`BORROW` / `REPAY`, but the protocol label can disappear when the exact pool
address is missing from `backend/src/main/resources/protocol-registry.json`.

Confirmed live example:

- `0xb2ece385ffb473e9b5755cdafa8e7e11346e8c744cf588dab5fd2963a14efe3f`
  (`ZKSYNC`)

Observed state before the fix:

- `type = REPAY`
- `classifiedBy = METHOD_ID`
- `protocolName = null`
- raw evidence still proves an Aave repay shape:
  - selector `0x573ade81` (`repay(...)`)
  - debt-marker burn `variableDebtZksUSDC`
  - reserve-asset spend `USDC`

That makes UI protocol labeling misleading and weakens operator confidence even
though the economic normalization is already correct.

## Target Policy

1. Address-level registry remains the primary protocol handoff for Aave pool
   identity and `protocolVersion`.
2. A narrow generic fallback is allowed for Aave borrow/repay only when both
   conditions hold:
   - selector resolves to canonical `BORROW` or `REPAY`
   - current movement evidence includes `variableDebt*` or `stableDebt*`
     continuity markers
3. This fallback may attach `protocolName = Aave`, but it must not guess the
   pool contract identity or `protocolVersion`.
4. Debt markers alone are not enough.
5. Bare `borrow(...)` / `repay(...)` selector hits without Aave-style debt
   markers are not enough.
6. Economic flow semantics stay unchanged:
   - reserve asset remains the only economic principal leg
   - debt marker mint/burn remains continuity-only `TRANSFER`

## Scope

In scope:

- Aave registry/resource documentation
- protocol-registry coverage for the audited zkSync pool
- narrow generic `BORROW` / `REPAY` protocol-name fallback
- regression tests
- rerun preparation

Out of scope:

- redesigning the full protocol semantic routing stack
- changing borrow/repay accounting semantics
- frontend-only protocol display logic

## Acceptance Criteria

1. The audited zkSync repay hash
   `0xb2ece385ffb473e9b5755cdafa8e7e11346e8c744cf588dab5fd2963a14efe3f`
   resolves with `protocolName = Aave`.
2. Existing audited zkSync Aave borrow/repay classifier fixtures keep:
   - `type = BORROW` / `REPAY`
   - reserve/debt flow semantics unchanged
   - `protocolName = Aave`
3. A bare generic `repay(...)` selector row without `variableDebt*` /
   `stableDebt*` markers does not gain `protocolName = Aave`.
4. Registry-backed Aave rows continue to use the registry as the authoritative
   source for pool identity and `protocolVersion`.

## Tasks

### BA-97-01 Requirements

1. Freeze the conjunction rule:
   - `borrow/repay selector` + `Aave debt marker`
   - never either one alone
2. Keep the fallback label-only:
   - `protocolName = Aave`
   - no inferred `protocolVersion`

### SA-97-02 Architecture

1. Keep `protocol-registry.json` as the primary protocol identity source.
2. Treat the generic selector fallback as a narrow resilience layer for
   protocol labeling, not as a replacement for registry discovery.
3. Expand the registry with the audited zkSync Aave V3 pool address.

### BE-97-03 Backend

1. Add zkSync Aave V3 pool
   `0x78e30497a3c7527d953c6b1e3541b021a98ac43c` to
   `protocol-registry.json`.
2. Teach the generic `METHOD_ID` fallback to attach `protocolName = Aave` for
   `BORROW` / `REPAY` only when Aave debt-marker continuity is present.
3. Keep `protocolVersion = null` on that fallback path.
4. Add regressions for:
   - zkSync Aave borrow with debt marker
   - zkSync Aave repay with debt marker
   - generic repay selector without debt marker

### Ops-97-04 Rerun

1. Clear derived collections only.
2. Preserve `raw_transactions`, `external_ledger_raw`, and
   `historical_prices`.
3. Reset raw/external statuses and session pipeline state to rerun-ready.

## Expected Outcome

After rerun:

- audited Aave borrow/repay rows keep the correct protocol label
- UI no longer shows blank protocol pills for proven Aave debt actions
- registry remains authoritative, while the generic fallback only covers the
  narrow protocol-label gap
