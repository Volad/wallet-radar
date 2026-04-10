# 122 — Sponsored Gas Top-Up Normalization Closeout

Date: 2026-04-09
Owner slice: backend / accounting / normalization
Source audit: `results/stats/15/full-pipeline-audit.md`

## Problem

Dense micro native inbound rows on `2025-09-20`, `2025-10-06`, and `2026-02-06`
were materialized as:

- `type = EXTERNAL_TRANSFER_IN`
- `flow role = BUY`
- priced from market feeds / `BYBIT`

That was too aggressive for the audited lane:

- pure native wallet-boundary inbound
- empty calldata / `methodId = 0x`
- no token transfers
- no internal transfers
- repeated sender from verified relay / solver infrastructure

This polluted:

- move-basis charts
- acquisition cost basis
- AVCO
- covered-share metrics

## Decision

Introduce a dedicated canonical lane:

- `SPONSORED_GAS_IN`

This is a transfer-family inbound, not a market-priced acquisition.

## Implemented scope

### P0 normalization / replay

- add canonical type `SPONSORED_GAS_IN`
- add protocol-registry role `GAS_PAYER`
- add audited `Relay` solver sender addresses to `protocol-registry.json`
- classify pure native inbound solver-funded payouts as `SPONSORED_GAS_IN`
  when all current production guardrails pass
- persist `protocolName = Relay` directly from registry-backed sender evidence
- skip pricing for this lane
- replay it as quantity increase with `costBasisDeltaUsd = 0`
- do **not** mark the quantity as uncovered by default

### Guardrails

Required:

1. `rawData.to == walletAddress`
2. tx-level native `value > 0`
3. no `explorer.tokenTransfers`
4. no `explorer.internalTransfers`
5. empty input / `methodId == 0x`
6. one positive non-fee native movement leg only
7. sender resolves to registry-backed `GAS_PAYER`
8. quantity is inside the audited per-network gas envelope

If any guardrail fails:

- remain conservative as `EXTERNAL_TRANSFER_IN`

## Explicit non-goals of this slice

- no source-side lifecycle inference
- no canonical parent-link field
- no UI grouping logic
- no route-fee allocation into sponsored gas basis

Those remain follow-up work.

## Follow-up backlog

### P1 display / parent-linking

- add display-only linking from `SPONSORED_GAS_IN` to the nearest unique
  destination-side actionable tx
- use that only for chart/card grouping, not for replay semantics

### P1 route-aware fee attribution

- if clarification later proves route-level quote/status evidence strong enough
  to allocate paid consideration into destination gas, allow deterministic
  cost attribution
- until then keep zero-cost sponsored basis

### P2 broader sender coverage

- extend registry-backed `GAS_PAYER` coverage beyond the currently audited
  `Relay` / solver sender set

## Acceptance criteria

- audited rows such as
  - `0xfc1c27db48579e15d25c8508dedcf4be732ff41f9f55ccfe12e7128dc5a9204e`
  - `0x7917082ba1ece5585557187d20bfdb43e8815bda4dbaa9fcf174b5c5cda7ce85`
  - `0xd644d3f86e788e8cb02e95787263294650cf091a102f6eab88e0cc91f2522ce5`
  normalize as `SPONSORED_GAS_IN`
- their flows are `TRANSFER`, not `BUY`
- they do not require market price
- replay keeps the quantity but does not invent market-priced acquisition cost
- on-chain rerun finishes without introducing new blocking review rows for this
  lane
