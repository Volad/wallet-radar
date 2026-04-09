# 119 - Protocol Registry Cross-Network Address Reuse

Status: Implemented

Owner: Codex

Date: 2026-04-08

## Problem

`protocolName` repair for transfer-backed `LFJ` swaps still missed official
Avalanche rows on `0x45a62b090df48243f12a21897e7ed91863e2c86b` even after
protocol enrichment started reading the raw interacted recipient.

The root cause was structural:

- `protocol-registry.json` keyed contract entries only by JSON object key
- the same address was present twice:
  - `LFJ` on Arbitrum / Avalanche
  - `Merchant Moe` on Mantle
- JSON duplicate-key semantics silently kept only the later entry

So runtime lookup by `network + address` never even saw the earlier `LFJ`
mapping.

## Decision

Keep runtime lookup keyed by `network + normalizedAddress`, but allow registry
entries to declare an explicit `address` field.

That makes the JSON object key an internal unique identifier instead of forcing
global address uniqueness inside the file.

## Implemented

- `ProtocolRegistryLoader` now reads optional `address` from each contract entry
  and falls back to the JSON key only when `address` is absent
- added loader regression coverage proving that one address can map to
  different protocols on different networks
- converted the conflicting `0x45a62...` entries to unique JSON keys with the
  same explicit `address`

## Acceptance Criteria

- same address may be represented multiple times in the registry as long as
  network coverage does not overlap
- runtime lookup still remains deterministic by `network + address`
- protocol repair can now resolve both:
  - `LFJ` on Arbitrum / Avalanche
  - `Merchant Moe` on Mantle

## Remaining Backlog

- Yearn still needs real vault-instance coverage
- SushiSwap still needs more helper / route-processor coverage
- Lagoon still needs instance-level vault coverage beyond factory entries
