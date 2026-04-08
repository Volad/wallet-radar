# 113 — Run 44 Aave Rebasing Receipt Accrual Normalization Closeout

Status: Done

## Goal

Close the confirmed remaining ETH coverage defect where audited `Aave` WETH
receipt buckets (`aArbWETH`, `aManWETH`, `aZksWETH`, ...) were normalizing the
full rebasing balance delta as principal continuity.

This slice intentionally prefers a canonical normalization repair over another
replay-only workaround.

## Confirmed Diagnosis

Latest live audit showed the large remaining `ETH` uncovered tail was already
present before the final Mantle / Bybit custody hops.

Confirmed chain:

- Mantle `0x3b8592a7465ce33bd64b266e11d1f665954cc3735be6e1590dffd226ac8664bd`
  simply inherits partial carry into `aManWETH`
- the same partial uncovered composition is already present on Arbitrum withdraw
  `0xe564fec189ce15b308d4031461077e3de4dcc3bb02c13732894ef500b7ac1af2`
- earlier Arbitrum `Aave` deposits such as:
  - `0x3099ace0372a6683a78efc60fe0b4b0c6f434e54fb1517089bdef8e1e0e7a58f`
  - `0xa6a38d63fa7e27a100215d2497919414312489d6c36eda179266a6d9047cf5d8`
  - `0x543f0944f4c8df9551ce66b2f6290b64f1219a3a70e008ad033c28bb482da589`
  already show receipt-side quantity larger than principal moved in the same tx

Root cause:

- `Aave` aTokens are rebasing / balance-accruing receipt assets
- current normalization emitted the whole tx-local receipt delta as
  continuity `TRANSFER`
- replay then treated the full larger receipt quantity as principal carry and
  necessarily left the extra portion uncovered

This is not a replay-order bug. It is a canonical flow-shape bug.

## Scope

In scope:

- audited `Aave` WETH receipt normalization split:
  - principal continuity
  - explicit accrual acquisition
- pricing alias coverage for audited `Aave` WETH receipt symbols
- full canonical rerun for affected on-chain rows

Out of scope:

- arbitrary receipt-token drift for protocols outside the audited `Aave`
  WETH receipt set
- replay changes for bridge / Bybit / vault families

## Accepted Policy

For audited rebasing `Aave` WETH receipts:

- `LENDING_DEPOSIT`
  - principal outbound stays `TRANSFER`
  - receipt inbound up to principal quantity stays `TRANSFER`
  - positive receipt excess becomes `BUY`
- `LENDING_WITHDRAW`
  - receipt outbound stays `TRANSFER`
  - underlying inbound up to receipt quantity stays `TRANSFER`
  - positive underlying excess becomes `BUY`

Rationale:

- principal continuity must remain exact
- rebasing excess is economically yield-like quantity growth
- the excess should be explicitly priceable and replayable, not hidden inside one
  oversized continuity leg

## Acceptance Criteria

1. Audited `Aave` WETH receipt drift no longer leaves synthetic uncovered
   principal when the tx-local excess is only rebasing accrual.
2. Canonical `LENDING_DEPOSIT` / `LENDING_WITHDRAW` rows emit:
   - `TRANSFER` for the matched principal amount
   - `BUY` for positive audited rebasing excess
3. Exact-equal principal/receipt rows stay unchanged.
4. Non-audited lending protocols do not inherit this rule.
5. Pricing can resolve audited `Aave` WETH receipt `BUY` flows through `ETH`
   market pricing.
6. A normalization rerun can rebuild canonical rows and downstream replay state
   without touching immutable raw evidence.

## Backend Tasks

1. `BE-R44-01` Add audited `Aave` rebasing receipt split to canonical flow
   building for `LENDING_DEPOSIT` / `LENDING_WITHDRAW`.
2. `BE-R44-02` Add regression coverage for:
   - generic `Aave` deposit with receipt excess
   - generic `Aave` withdraw with underlying excess
   - audited zkSync gateway deposit with receipt excess
3. `BE-R44-03` Extend pricing canonical symbol aliases so audited `Aave` WETH
   receipt `BUY` flows price through `ETH`.
4. `BE-R44-04` Run a canonical rerun:
   - reset affected `raw_transactions.normalizationStatus` to `PENDING`
   - clear affected `normalized_transactions`
   - clear downstream derived collections
   - keep immutable raw and integration evidence

## Architecture Notes

- This slice intentionally moves the fix earlier in the pipeline.
- Carry accuracy should be repaired where the canonical economic meaning is
  first expressed, not repeatedly compensated in replay.
- Replay remains deterministic and simpler when canonical rows already separate:
  - principal continuity
  - accrual acquisition

## Completion Notes

- audited `Aave` rebasing WETH receipt drift now normalizes as
  `principal TRANSFER + accrual BUY`
- pricing recognizes audited `Aave` WETH receipt symbols as `ETH`
- rerun should start from on-chain normalization because canonical flow shape
  changed
