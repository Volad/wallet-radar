# Run 28 — Aave Borrow/Repay Protocol Handoff Backlog

## Goal

Preserve `protocol = Aave` on proven borrow/repay rows when registry coverage
lags behind audited raw evidence.

## P0

1. Add zkSync Aave V3 pool `0x78e30497a3c7527d953c6b1e3541b021a98ac43c`
   to `protocol-registry.json`.
2. Keep registry as the primary source for pool identity and
   `protocolVersion`.
3. Add a narrow generic fallback:
   - selector is canonical `BORROW` / `REPAY`
   - same tx contains `variableDebt*` or `stableDebt*`
   - result gains `protocolName = Aave`
4. Do not infer `protocolVersion` on that fallback path.
5. Add regressions for:
   - zkSync Aave borrow fixture
   - zkSync Aave repay fixture
   - generic repay selector without debt marker
6. Prepare rerun-ready Mongo state after implementation.

## P1

1. Audit remaining Aave pool coverage across supported networks and add missing
   pool addresses to the registry.
2. Consider a future dedicated Aave semantic handoff only if registry+fallback
   still leaves uncovered protocol-label gaps.

## Constraints

- do not widen debt-marker heuristics beyond Aave borrow/repay
- do not use selector-only labeling
- do not change reserve/debt flow accounting semantics in this slice
