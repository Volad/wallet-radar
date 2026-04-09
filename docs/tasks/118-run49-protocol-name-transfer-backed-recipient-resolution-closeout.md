# 118 - Protocol Name Transfer-Backed Recipient Resolution

Status: Implemented

Owner: Codex

Date: 2026-04-08

## Problem

`protocolName` clarification-time enrichment was already operational, but a
large class of protocol-owned rows still stayed unlabeled:

- transfer-backed `SWAP`
- transfer-backed `APPROVE`
- transfer-backed bridge / custody rows

The root cause was that `OnChainRawTransactionView.toAddress()` intentionally
suppressed top-level `to` for explorer rows that were really token-transfer
projections. That behaviour is correct for economic classification, but it is
too aggressive for protocol identity enrichment.

Concrete consequence:

- official LFJ aggregator swaps on `0x45a62b090df48243f12a21897e7ed91863e2c86b`
  stayed with `protocolName = null` even though the raw tx recipient was
  deterministic and the registry entry was already present

## Decision

Keep economic classification unchanged, but give protocol enrichment its own
recipient accessor:

- economic classifiers still use transfer-safe `toAddress()`
- protocol identity enrichment uses `interactionToAddress()`
- `interactionToAddress()` reads the actual tx recipient from explorer tx
  payload or raw tx payload without transfer-row suppression

This keeps `protocolName` accurate while preserving existing type/flow logic.

## Implemented

- added `OnChainRawTransactionView.interactionToAddress()`
- switched direct protocol lookup in `ProtocolNameResolutionService` from
  `toAddress()` to `interactionToAddress()`
- added regression coverage for transfer-backed raw rows where:
  - economic classification should still suppress `to`
  - clarification-time protocol enrichment must still resolve the router

## Acceptance Criteria

- transfer-backed rows with official router/vault entrypoint in raw tx recipient
  can now resolve `protocolName`
- economic classification behaviour remains unchanged
- no full normalization rerun is required; clarification-time repair/backfill is
  sufficient

## Remaining Backlog

- Yearn still needs real vault-instance coverage beyond the single generic
  registry entry
- SushiSwap still needs more helper / route-processor addresses beyond the
  current router set
- Lagoon still needs vault-instance coverage; factory-only entries are not
  enough for instance-level deposits/withdrawals
