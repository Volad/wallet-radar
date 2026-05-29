# ADR-018: LP Protocol-Family Flow Materialization

Status: Accepted (amended 2026-05-29 — ETH-C10 Katana/Pendle CMETH registration)  
Date: 2026-05-27

## Context

Prod audit showed LP principal exits existed in raw evidence but normalized rows lacked
`LP-RECEIPT` flows and misclassified harvest-only txs as principal `LP_EXIT`. A single
`LP-RECEIPT` template does not fit GMX async markets, Pendle LPT, fungible BPT, or gauge
stake lifecycles.

## Decision

Introduce protocol-family materialization at normalization time:

| Family | Correlation key | Flow materialization |
|---|---|---|
| A — NFT CL | `lp-position:{net}:{slug}:{tokenId}` | Synthetic `LP-RECEIPT` from ERC721 mint/burn + ERC20 principal legs |
| B — GMX async | `gmx-lp:{net}:{marketSlug}` | Preserve GM/GLV share legs; no NFT receipt pool |
| C — Pendle | `pendle-lp:{net}:{marketId}` | Preserve PENDLE-LPT legs |
| D — Fungible LP | composite `lp:` bucket | Outbound BPT/LP burn; no synthetic NFT receipt |
| E — Gauge/farm | optional link only | `LP_POSITION_STAKE/UNSTAKE` ≠ NFT position close |

Shared gate `LpPrincipalCloseEvidence` downgrades inbound-only / collect-only shapes to
`LP_FEE_CLAIM` unless position-reduction evidence exists (decrease, burn, negative
ModifyLiquidity, ERC721 from wallet).

## Consequences

- Re-normalize + full replay required; no ledger zeroing sweeps.
- Replay handlers remain family-specific (`LpReceipt*`, `GmxLp*`, composite `lp:`).
- Acceptance uses `scripts/audit/lp-position-lifecycle-audit.mongosh.js`.

## Amendment (2026-05-29) — ETH-C10: Katana SushiSwap and Pendle CMETH

Prod audit (`lp-pool-avco-table.md`, Issues E and F) identified two unregistered LP protocols:

### Katana SushiSwap (Angle vbETH-vbUSDC vault) — Classification: `classification` stage

- LP_ENTRY tx (2025-09-12) for 0.45 ETH @ $4,527 had `correlationId=null`, `protocolName=null`.
- Root cause: Katana SushiSwap vault contract not in the protocol registry.
- **Fix:** Add Katana SushiSwap contract to protocol registry; emit `lp-position:katana:sushiswap:{poolId}` (Family A — NFT CL pattern).
- Fee claim txs (vbETH, SUSHI, vbUSDC) must inherit the same `correlationId`.

### Pendle CMETH LP (Mantle network) — Classification: `classification` stage

- Multiple LP_ENTRY/LP_EXIT txs on Mantle have `protocolName=Pendle` but `correlationId=null`.
- Root cause: Mantle CMETH Pendle market not registered in `PendleLpCorrelationSupport` (symbol `PENDLE-LPT` / `CMETH-LPT` → marketId map missing).
- **Fix:** Register Mantle CMETH Pendle market in `PendleLpCorrelationSupport`; ensure `pendle-lp:mantle:{marketId}` is emitted (Family C — Pendle).
- PENDLE fee token claims must inherit the same `correlationId`.

### Acceptance

- A11: ≥1 Katana LP tx has `correlationId` matching `lp-position:katana:sushiswap:*`; ≥1 associated fee-claim tx has same `correlationId`.
- A12: ≥1 Pendle CMETH LP tx has `correlationId` matching `pendle-lp:mantle:*`; ≥1 PENDLE fee-claim has same `correlationId`.

## Alternatives considered

- Replay-only receipt synthesis — rejected (violates earliest-stage fix rule).
- One global `LP-RECEIPT` symbol for all protocols — rejected (GMX/Pendle/Curve mismatch).
