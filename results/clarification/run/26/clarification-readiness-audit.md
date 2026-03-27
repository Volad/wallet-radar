# Clarification Readiness Audit — Run 26

Generated at: 2026-03-27

## Executive Summary

Verdict: **READY_UNDER_EXCLUSION_POLICY**

`run/26` closes the last active on-chain blocker from `run/25`.

What improved:

1. The residual Euler `batch(...)` row `0x56ef233104fabcf809fbad26d5956f0450398cfd90a583fadfe6c7613a7bd332` no longer remains `UNKNOWN / NEEDS_REVIEW`; it is now modeled as `LENDING_LOOP_REBALANCE`.
2. There are now **zero blocking** `NEEDS_REVIEW` rows in the active accounting lane.
3. The active-lane invariants remain clean: `swapMissingBuy=0`, `swapMissingSell=0`, `lpEntryPrincipalMismatch=0`, `borrowRoleMismatch=0`, `repayRoleMismatch=0`, `rewardClaimRoleMismatch=0`, `classicStakingPrincipalMismatch=0`, `persistedInternalTransfer=0`, `BYBIT CONFIRMED UNKNOWN=0`.
4. The only `LP_EXIT` row with a `BUY` leg is still `0xf7f8908b455261dc67a7f905ca99f1041987de690a7574d440e31739c3132430`, and it remains an expected Pendle reward-side bundle, not a principal mismatch.

What remains outside active accounting scope:

1. `11` Bybit `NEEDS_REVIEW` rows remain explicitly persisted as `excludedFromAccounting=true`.
2. `1` GMX async request remains open in the current dataset without a settlement row: `0xc4a56103ffc881bf5900b6e77e0a6b488b810c445f83a07a9e11ff8499635da7`.

Financial interpretation:

- Under the **current exclusion contract**, the normalized + clarified dataset is ready to proceed into `pricing -> AVCO -> cost basis -> move basis`.
- The remaining `11` Bybit rows are not silent defects. They are explicit audit-only exclusions and are already outside active accounting scope.
- If product scope later expands to require those Bybit orphan-trade / loan families to participate in accounting, readiness must be reopened for that broader scope.

## Live Snapshot

- `raw_transactions = 3097`
- `normalized_transactions = 5548`
- `external_ledger_raw = 4305`
- `PENDING_CLARIFICATION = 0`
- `PENDING_PRICE = 3690`
- `PENDING_STAT = 0`
- `CONFIRMED = 1847`
- `NEEDS_REVIEW = 11`
- `blocking NEEDS_REVIEW = 0`
- `excluded NEEDS_REVIEW = 11`
- `clarificationEvidence = 20`
- `fullReceipt = 20`
- `persisted INTERNAL_TRANSFER = 0`

## Ready Findings

### RF-01: The residual Euler rebalance family is now normalized into an explicit continuity type

Audited full hashes:

- `0x56ef233104fabcf809fbad26d5956f0450398cfd90a583fadfe6c7613a7bd332`
- `0x08e6af7e66edbe02311f921fb6f17047e87f43acdcdb1c19526e73f7de46b50a`
- `0xa548b35769c68377b33172370d1a414facd1be4f3c8106d21fcc3940e38ee7a5`

Observed Mongo facts:

- all three rows are now `LENDING_LOOP_REBALANCE`
- all three rows are `PENDING_PRICE`, not `NEEDS_REVIEW`
- observed wallet-visible flows are continuity-only `TRANSFER` legs plus network fee
- there is no fabricated `BUY` / `SELL` leak on debt markers or replacement shares

Interpretation:

- this family now behaves like share-to-share restructuring, not like a fake deposit / withdraw / swap
- that is sufficient for deterministic replay under the current minimal Euler loop contract

See:

- `resolved_type_spot_checks.tsv`

### RF-02: Resolv async request / settlement remains continuity-safe

Audited full hashes:

- request: `0xd446b9d8a4b32b795cc957dc0e8381792bdf283824a8faf979042115f4c961c0`
- settlement: `0xde8afcabd1284b6b287eb9550d204e03d9d1c59da518a2d5acbe3f4613f38a5b`

Observed Mongo facts:

- request = `STAKING_WITHDRAW_REQUEST`
- settlement = `STAKING_WITHDRAW`
- principal legs on both rows are `TRANSFER`, not `BUY`
- the pair shares the same `correlationId`

Interpretation:

- this lifecycle is now safe for `move basis`
- settlement no longer fabricates a fresh acquisition of `RESOLV`

### RF-03: GMX async LP-entry family remains correctly split into request / settlement

Representative full hashes:

- request: `0x65ff93bb47919df22ae36055e0e8102c9ddec1f3e5e67e4e6fad7f694b6cff28`
- settlement: `0x3ad60ac2e1c46805cebb2d0f8a5a1002364f701ebb88fdc7378a2b5bce06beab`

Observed Mongo facts:

- request row is `LP_ENTRY_REQUEST`
- settlement row is `LP_ENTRY_SETTLEMENT`
- both rows carry the same `correlationId`
- request side persists continuity outflow of funding asset(s)
- settlement side persists continuity inflow of the GM market token

Interpretation:

- the GMX async lifecycle remains semantically correct for replay
- request and settlement are no longer flattened into a misleading one-step `EXTERNAL_TRANSFER_OUT` / inbound-only deposit pair

### RF-04: Pendle bundle-aware LP exit remains the only `LP_EXIT` with `BUY`, and it is expected

Audited full hash:

- `0xf7f8908b455261dc67a7f905ca99f1041987de690a7574d440e31739c3132430`

Observed Mongo facts:

- normalized row is `LP_EXIT`
- wallet-visible flows are:
  - `BUY:PENDLE:0.012731662739929251`
  - `TRANSFER:cmETH:0.862092260317885000`
  - `FEE:MNT:-0.074324282100000000`

Interpretation:

- this is the expected Pendle LP-exit + reward bundle pattern
- the `BUY` leg is reward-side only and does not indicate an LP principal mismatch

## Non-Blocking Warnings

### WR-01: Bybit excluded review tail remains explicit

Current excluded review tail:

- `UTA_TRADE_PAIR_NOT_FOUND = 8`
- `BYBIT_LOAN_SEMANTICS_UNSUPPORTED = 3`

Observed facts:

- all `11` rows are `excludedFromAccounting=true`
- none of them sit in the active lane
- none of them are persisted as silent `CONFIRMED UNKNOWN`

Interpretation:

- these rows are visible to audit, but they do not block accounting readiness under the current exclusion policy

See:

- `remaining_review_tail.tsv`
- `bybit_excluded_review_tail.tsv`

### WR-02: One GMX async request remains open in the current dataset

Full hash:

- `0xc4a56103ffc881bf5900b6e77e0a6b488b810c445f83a07a9e11ff8499635da7`

Observed Mongo facts:

- normalized row is `LP_ENTRY_REQUEST`
- correlation id is `0x8215843b63fa3b878e093a3c777f2ab9c6d31a94dbe1527143f055cc6b77c106`
- no matching `LP_ENTRY_SETTLEMENT` row exists in the current dataset for that correlation id

Interpretation:

- this is not a normalization blocker
- replay must preserve the open async bucket and carried basis until settlement arrives in later history

See:

- `gmx_async_open_request_warning.tsv`

## Readiness Verdict

Current readiness flags:

- `readyForPricing = true`
- `readyForAvco = true`
- `readyForCostBasis = true`
- `readyForMoveBasis = true`

Scope note:

- These readiness flags are **true under the current accounting scope contract**.
- The dataset still contains `11` explicitly excluded Bybit rows. They do **not** block readiness because they are intentionally out of accounting scope.
- If the requirement changes from "authoritative accounting scope" to "every raw source row must be normalized into active accounting semantics", then this verdict must be revisited.

## Backfill / Evidence Note

- **No new source backfill is required.**
- Existing `raw_transactions` and persisted `clarificationEvidence.fullReceipt` are sufficient for the now-resolved Euler / Resolv / GMX families.
- Remaining Bybit exclusions are not blocked by missing raw backfill; they are blocked by intentionally unsupported semantics.

## Recommended Next Step

1. Proceed to `pricing`.
2. Then run `costbasis replay`.
3. Keep the Bybit excluded tail out of accounting scope until orphan UTA trade pairing and Bybit loan semantics are intentionally implemented.
4. Ensure replay preserves open async carry-over for `0xc4a56103ffc881bf5900b6e77e0a6b488b810c445f83a07a9e11ff8499635da7`.

## Supporting Artifacts

- `audit_summary.json`
- `live_snapshot.json`
- `active_invariants.json`
- `type_status_matrix.json`
- `remaining_review_tail.tsv`
- `bybit_excluded_review_tail.tsv`
- `gmx_async_open_request_warning.tsv`
- `resolved_type_spot_checks.tsv`
- `external_validation.json`
