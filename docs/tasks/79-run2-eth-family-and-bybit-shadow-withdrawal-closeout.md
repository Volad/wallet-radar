# 79 — Run 2 ETH Family And Bybit Shadow Withdrawal Closeout

## Context

Deep audit `results/stat/run/2` found that current `cost basis / AVCO / move basis`
is still not trustworthy for `ETH family`.

The audit proved three concrete facts:

1. `Bybit ETH` really exists in raw and really moves on-chain.
2. `13` `Bybit ETH EXTERNAL_TRANSFER_OUT` rows are already matched to exact on-chain legs.
3. Another `13` `Bybit ETH EXTERNAL_TRANSFER_OUT` rows are exchange-side shadow
   withdrawal rows from `fund_asset_changes`, not independent economic exits.

Main example:

- Bybit matched withdrawal correlation:
  `BYBIT:MANTLE:0xa5e755a68349c9956b51ced38575733278b40467971ca4b9f9f40937fd5d2920`
- Bybit side:
  `source=BYBIT`, `type=EXTERNAL_TRANSFER_OUT`, `SELL ETH -3.06`
- on-chain side:
  `source=ON_CHAIN`, `type=EXTERNAL_TRANSFER_IN`, `BUY WETH +3.06`
- later custody step:
  `0x3b8592a7465ce33bd64b266e11d1f665954cc3735be6e1590dffd226ac8664bd`
  `LENDING_DEPOSIT WETH -3.06 -> aManWETH +3.059999999999999999`

Current live consequence:

- live on-chain ETH-family holdings are about `3.088616233148614665`
- non-Bybit `asset_positions` materialize about `19.288360093725756883`

This means replay still double-counts or breaks continuity on the
`Bybit -> on-chain -> Aave custody` path.

## Architect Decision

Architecture stays modular-monolith and event-driven.

The correction is local to canonical Bybit handling and accounting replay:

- keep immutable raw evidence unchanged
- keep canonical normalized docs traceable on both sides
- stop using raw asset identity as the sole accounting continuity identity
- introduce one deterministic accounting family contract for approved
  custody-equivalent assets
- keep user-facing holdings separate from raw source evidence semantics

Required architecture-level changes:

1. `ETH family` must be modeled at accounting-family level, not only as scattered
   pricing aliases or bridge-only exceptions.
2. Matched `Bybit` withdrawal principal may not remain an active realized `SELL`
   when the same move is already correlated to the tracked on-chain receipt.
3. Exchange-side shadow withdrawal rows may remain persisted for audit, but must
   be excluded from active replay once the chain-aware sibling exists.
4. Replay continuity keys and protocol-custody buckets must use family-aware
   continuity identity for approved custody-equivalent families.

## BA Scope / Acceptance Criteria

### DoD

1. `Bybit ETH -> on-chain WETH` matched transfers carry basis without realized
   sale on the transferred principal.
2. `ETH family` is explicit and centralized for accounting continuity.
3. The minimum supported `ETH family` for this slice is:
   - `ETH`
   - network `WETH`
   - `aEthWETH`
   - `aArbWETH`
   - `aLinWETH`
   - `aManWETH`
   - `aZksWETH`
   - `vbETH`
4. The following remain outside the generic `ETH family` carry contract unless a
   dedicated policy says otherwise:
   - `stETH`
   - `wstETH`
   - `cbETH`
   - `mETH`
   - `cmETH`
   - `rETH`
5. A matched `Bybit ETH` withdrawal to tracked on-chain `WETH` restores basis on
   the on-chain side and does not duplicate quantity in replay.
6. `WETH -> aManWETH` custody movement on Aave preserves basis into the receipt
   side and does not leave stale principal inventory behind.
7. `fund_asset_changes` shadow withdrawal rows that duplicate a chain-aware
   `withdraw_deposit` sibling are persisted as audit-visible but excluded from
   active accounting scope.
8. After rerun, `asset_positions` no longer contain duplicated `Bybit ETH`
   principal caused by those shadow rows.

### Edge Cases

- Case: `Bybit ETH` withdrawal matched to on-chain `WETH` with exact quantity.
  Expected: one continuity carry, zero realized sale on principal.
- Case: `Bybit ETH` withdrawal has gross exchange-side row and smaller net
  chain-aware row.
  Expected: shadow gross row excluded from replay; chain-aware row remains the
  active principal continuity row.
- Case: `Bybit ETH` withdrawal to untracked venue.
  Expected: remain external transfer / external custody under existing policy.
- Case: `WETH -> aManWETH` lending deposit.
  Expected: basis leaves `WETH` and lands in `aManWETH`.
- Case: `ETH -> stETH`.
  Expected: not auto-collapsed into the generic `ETH family`.

## Backend Tasks

1. `BE-79-01` Introduce accounting family support
   - add one shared support class for replay continuity family identity
   - include the approved `ETH family`
   - preserve existing `USDC/vbUSDC` bridge-family behaviour

2. `BE-79-02` Make replay continuity family-aware
   - update transfer carry keys to use accounting-family continuity identity
   - update protocol-custody buckets (`LENDING_*`, `VAULT_*`, etc.) to use
     family-aware continuity keys instead of raw asset identity only
   - keep final persisted position symbols/contracts unchanged unless the
     existing replay flow already changes them

3. `BE-79-03` Suppress Bybit withdrawal shadow rows
   - detect `fund_asset_changes` shadow withdrawal rows that duplicate a
     chain-aware `withdraw_deposit` sibling
   - mark them excluded from accounting with explicit audit reason
   - keep raw evidence untouched

4. `BE-79-04` Regression tests
   - matched `Bybit ETH -> on-chain WETH` carry keeps one lot and no duplicate
     realized sale
   - `WETH -> aManWETH` preserves basis through custody deposit
   - shadow withdrawal row becomes excluded audit-only evidence
   - existing `Bybit` unmatched external-venue policy remains unchanged

## Operational Follow-Up

After code lands:

1. clear derived state only
2. preserve raw evidence and historical price cache
3. rerun `normalization -> clarification -> pricing -> accounting replay`
4. re-audit `ETH family` holdings and basis against live on-chain state
