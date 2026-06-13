# Bybit Trade All Ways — Economic Ledger Fix

## Symptom

Dashboard integration **"Bybit Trade All Ways"** (`BYBIT-409666492`) showed ~$458 while the Bybit sub-account holds no funds.

## Root cause

Ledger/replay inflation, not a display bug:

1. **Jan-11 USDT double credit:** cross-UID FUND credit (+249.8845) plus `selfTransfer` UTA credit without matching FH `CARRY_OUT` on the umbrella position.
2. **SOL stranded on :FUND:** FH debit excluded by stream collapser while `selfTransfer` credit remained active.
3. **Sub↔sub universal transfers unpaired:** 3-leg `uni_trans_*` groups (e.g. `1bbc`, `c365`) never received `bybit-cross-uid-v1:` correlation.
4. **Live clamp gap:** empty successful fetch wrote no Mongo rows → dashboard preserved phantom ledger.

## Changes implemented

| Area | File | Fix |
|------|------|-----|
| Replay position | `AccountingAssetIdentitySupport` | FUND **outbound** collapsed legs drain umbrella, not empty `:FUND` sub-wallet |
| Stream symmetry | `BybitStreamAuthorityCollapser` | Restore excluded FH outbound when paired UTA inbound is active |
| Linking | `BybitInternalTransferPairer` | Multi-leg (3+) universal transfer pairing for sub↔sub |
| Defense-in-depth | `BybitLiveBalanceService`, dashboard/ledger read | Empty-balance tombstone + `KNOWN_EMPTY` clamp |

## Acceptance

- EC-1: `asset_ledger_points` for `BYBIT:409666492*` → USDT/SOL ≈ 0 after replay
- EC-2: Dashboard `bybit:409666492` ≈ $0
- EC-3: No Jan-11 double `CARRY_IN` on umbrella USDT
- EC-4: Sub↔sub universal transfers carry `bybit-cross-uid-v1:`

## Verification

```bash
./scripts/prod-reset-rebuild-backend.sh --skip-frontend
```

Then re-run linking + cost-basis replay; confirm Mongo ledger for UID `409666492`.
