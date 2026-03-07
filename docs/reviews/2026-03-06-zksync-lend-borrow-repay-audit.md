# 2026-03-06 — ZKsync Lend/Borrow/Repay Classification Audit

## Scope

Validation of current `raw_transactions -> normalized_transactions` classification results on `networkId=ZKSYNC`
for wallet `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f`.

## Data Snapshot

- `raw_transactions` on ZKSYNC: `49`
- `normalized_transactions` on ZKSYNC: `49`
- Type distribution:
  - `EXTERNAL_TRANSFER_OUT`: `27`
  - `EXTERNAL_INBOUND`: `16`
  - `SWAP`: `6`
  - `LEND_DEPOSIT/LEND_WITHDRAWAL/BORROW/REPAY`: `0`

## Selector-based Verification

Observed transactions by selector:

- `0x02c205f0` (deposit-like): `2` raw tx -> all normalized as `SWAP`
- `0x69328dec` (withdraw-like): `1` raw tx -> normalized as `SWAP`
- `0xa415bcad` (borrow-like): `3` raw tx -> all normalized as `EXTERNAL_TRANSFER_OUT`
- `0x573ade81` (repay-like): `4` raw tx -> all normalized as `EXTERNAL_TRANSFER_OUT`

## Concrete Examples

- `0xcfe0fd4d...` (`0x02c205f0`) -> `SWAP`
- `0x1e8c5df4...` (`0x02c205f0`) -> `SWAP`
- `0x4f771525...` (`0x69328dec`) -> `SWAP`
- `0x69aa8504...` (`0xa415bcad`) -> `EXTERNAL_TRANSFER_OUT`
- `0x5d349184...` (`0xa415bcad`) -> `EXTERNAL_TRANSFER_OUT`
- `0x836b9999...` (`0xa415bcad`) -> `EXTERNAL_TRANSFER_OUT`
- `0x8fb1c960...` (`0x573ade81`) -> `EXTERNAL_TRANSFER_OUT`

## Evidence Notes

For affected borrow/repay and lend-like transactions, synthetic transfer legs for
`0x000000000000000000000000000000000000800a` (ETH pseudo-token) are present in raw token transfers/logs.

Current output indicates that config-driven synthetic-native filtering is not effectively applied
to all lend paths (`BORROW/REPAY` and selector-driven lend context on synthetic logs).

## Outcome

Current behavior does not meet expected lending semantics for discussed ZKsync transactions.

Follow-up implementation task is required: see `docs/tasks/26-zksync-lend-borrow-repay-synthetic-log-classification-fix.md`.
