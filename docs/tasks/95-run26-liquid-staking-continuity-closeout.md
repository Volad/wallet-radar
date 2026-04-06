# Run 26 — Liquid Staking Continuity Closeout

## Goal

Treat liquid staking conversions as basis-preserving continuity instead of
realized disposals.

## Problem

The current runtime prices liquid staking conversions such as `ETH -> METH` and
`AVAX -> sAVAX` as economic `SELL principal / BUY derivative`. That creates
artificial realized PnL, breaks user-facing ETH-family AVCO intuition, and
pollutes timeline summaries with disposal events where the user only changed the
position wrapper.

The audited Bybit `ETH 2.0 Stake + Mint` pair demonstrates the failure mode:

- `ETH -0.709`
- `METH +0.66865026`
- current runtime materializes realized PnL instead of carrying basis

## Target Policy

1. Canonical normalized type remains `STAKING_DEPOSIT` / `STAKING_WITHDRAW`.
2. Liquid staking principal and derivative legs persist as continuity
   `TRANSFER`, not economic `SELL` / `BUY`.
3. Replay carries basis from principal into the liquid staking derivative on
   deposit and restores it on unwind.
4. Explicit reward side-flows in unrelated assets may remain economic `BUY`.
5. Realized PnL for liquid staking conversions must be `0`.

## Scope

In scope:

- on-chain liquid staking flow-role assignment
- Bybit `ETH 2.0` paired staking rows
- continuity family mapping for audited liquid staking derivatives
- replay regressions proving carry-over basis

Out of scope:

- tax-policy toggles for disposal-vs-continuity jurisdiction variants
- unaudited liquid staking symbols with insufficient family mapping evidence

## Acceptance Criteria

1. On-chain `STAKING_DEPOSIT` liquid staking conversions such as
   `AVAX -> sAVAX` persist both principal legs as `TRANSFER`.
2. On-chain `STAKING_WITHDRAW` liquid staking unwind persists both principal
   legs as `TRANSFER`.
3. Same-tx reward side-flows remain economic `BUY`.
4. Bybit paired `ETH 2.0 Stake + Mint` rows persist both legs as `TRANSFER`.
5. Bybit paired liquid staking rows become `CONFIRMED`, not `PENDING_PRICE`.
6. Replay carries basis from `ETH` into `METH` without realized PnL.
7. `AccountingAssetFamilySupport` recognizes audited liquid staking derivatives
   as members of the underlying base family.
8. Dashboard and asset-ledger family fallback logic use the shared family
   contract instead of local duplicated symbol sets.

## Implementation Tasks

1. Update shared accounting-family support with audited liquid staking family
   members.
2. Update on-chain staking flow-role assignment to convert same-family liquid
   staking principal legs into `TRANSFER`.
3. Update Bybit paired staking builder to emit `TRANSFER` flows for same-family
   liquid staking pairs.
4. Keep classic SmartChef staking override unchanged.
5. Add regressions for:
   - on-chain `AVAX -> sAVAX`
   - Bybit `ETH 2.0 Stake + Mint`
   - replay `ETH -> METH` carry-over basis
6. Reset data and prepare rerun-ready state after implementation.

## Expected Outcome

After rerun:

- liquid staking conversions no longer create artificial realized PnL
- ETH-family AVCO remains continuous through `ETH -> METH`
- audited liquid staking derivatives group under the correct family in ledger
  and dashboard reads
