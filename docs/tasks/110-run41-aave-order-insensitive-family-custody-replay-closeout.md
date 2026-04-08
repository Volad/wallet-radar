# Run 41 Closeout: Aave Order-Insensitive Family Custody Replay

## Summary

This slice closes the replay defect behind the remaining large `ETH` coverage
gap after linked bridge carry repair.

The root cause was not an Aave protocol drift bug. It was an order-sensitive
replay bug:

- canonical `Aave` `LENDING_DEPOSIT` rows may list the receipt token mint
  before the principal outbound transfer
- replay previously consumed flows strictly in canonical order
- inbound-first receipt legs were therefore materialized as uncovered before the
  paired principal carry became available
- the later outbound leg only filled the continuity bucket after the synthetic
  uncovered receipt inventory had already been created

This inflated uncovered `aArbWETH` tails and then propagated them forward into
`WETH`, `Bybit`, and `Mantle` ETH-family balances.

## Policy

For simple audited family-equivalent custody rows with:

- exactly one principal outbound transfer
- exactly one receipt inbound transfer
- both legs inside the same audited accounting family

replay must treat the pair atomically and ignore raw normalized leg order.

This applies to:

- `PROTOCOL_CUSTODY_DEPOSIT`
- `PROTOCOL_CUSTODY_WITHDRAW`
- `LENDING_DEPOSIT`
- `LENDING_WITHDRAW`
- `VAULT_DEPOSIT`
- `VAULT_WITHDRAW`

Minor quantity drift is handled with the same carry principle as linked bridge
replay:

- full source cost basis remains on the destination leg
- only unmatched destination excess quantity remains uncovered

## Implementation

Backend:

- added transaction-level selection for simple family-equivalent custody pairs
- added atomic replay handler that:
  - removes carry from the outbound principal leg first
  - restores the inbound receipt leg with bridge-style full-cost carry semantics
  - processes non-paired flows afterwards in normal replay order

Files:

- `backend/src/main/java/com/walletradar/costbasis/application/AvcoReplayService.java`
- `backend/src/test/java/com/walletradar/costbasis/application/AvcoReplayServiceTest.java`

## Regression Anchors

- `0x3099ace0372a6683a78efc60fe0b4b0c6f434e54fb1517089bdef8e1e0e7a58f`
  `ETH -> aArbWETH`
- `0xe06d2de0150486755c0e846c173026866972bf7c5e7372ce560e5c2a0879b4af`
  `WETH -> aArbWETH`
- `0xa6a38d63fa7e27a100215d2497919414312489d6c36eda179266a6d9047cf5d8`
  `WETH -> aArbWETH`
- `0xe564fec189ce15b308d4031461077e3de4dcc3bb02c13732894ef500b7ac1af2`
  `aArbWETH -> WETH`

## Verification

Targeted test:

- `:backend:test --tests 'com.walletradar.costbasis.application.AvcoReplayServiceTest'`

Expected live effect after replay-only rerun:

- materially lower uncovered `aArbWETH`
- materially lower uncovered downstream `ETH/WETH`
- higher ETH-family basis coverage without changing raw or normalized evidence
