# Implementation Plan: Aerodrome LP Registry + LTC Earn Pairer Docs

**Date:** 2026-07-05  
**Status:** DRAFT — awaiting user approval

---

## Summary

Two independent tracks:

| Track | Severity | Rebuild | Risk |
|---|---|---|---|
| A — Aerodrome Slipstream registry entry | CRITICAL | `--skip-frontend` (renorm) | LOW — single JSON line, no code |
| B — LTC/Bybit earn pairer doc updates | LOW | none (code already written by user) | LOW — docs only |

---

## Track A — Aerodrome Slipstream CL LP

### Root cause

`RegistryDirectTypeClassifier` could not resolve tx `0xe1ac13d9...` because  
`0x827922686190790b37229fd06084350e74485b72` (Aerodrome Slipstream NonfungiblePositionManager on BASE)  
is absent from `protocol-registry.json`. The tx fell through to `EXTERNAL_TRANSFER_OUT`,  
phantom-disposing **$593.92** of cost basis (312.86 USDC + 0.162 ETH) and making the position  
invisible on the LP page.

No application code changes needed — the `LpRegistryClassifier` pipeline already handles  
`event_type: "LP_MINT"` on POSITION_MANAGER entries identically for all other CL forks  
(Uniswap V3, PancakeSwap V3, Velodrome Slipstream).

### Change A.1 — Add registry entry

File: `backend/src/main/resources/protocol-registry.json` (or the equivalent Mongo seed / config path).

Add exactly:

```json
"0x827922686190790b37229fd06084350e74485b72": {
  "name": "Aerodrome Slipstream NonfungiblePositionManager",
  "protocol": "Aerodrome",
  "version": "Slipstream",
  "family": "DEX",
  "role": "POSITION_MANAGER",
  "event_type": "LP_MINT",
  "networks": ["BASE"],
  "confidence": "HIGH",
  "notes": "BASE concentrated-liquidity position manager (Aerodrome Slipstream / CL fork of Velodrome Slipstream). Mints ERC721 NFT positions. mint() = LP_ENTRY; collect() alone = LP_FEE_CLAIM; decreaseLiquidity()+collect() = LP_EXIT or LP_EXIT_PARTIAL; full burn = LP_EXIT_FINAL. Interface identical to Uniswap V3 NonfungiblePositionManager and Velodrome Slipstream on OPTIMISM."
}
```

Template reference: existing entry for `0x416b433906b1b72fa758e166e239c43d68dc6f29` (Velodrome Slipstream, OPTIMISM) — identical structure.

### Change A.2 — Rebuild

```bash
./scripts/prod-reset-rebuild-backend.sh --skip-frontend
```

Full renormalization required (classification stage affected).

### Acceptance criteria

- [ ] `0xe1ac13d9...` reclassified as `LP_ENTRY` (CONFIRMED)
- [ ] `lp_receipt_basis_pools` record created for position #72612405 with cost basis ~$593.92
- [ ] LP page shows 1 active BASE Aerodrome Slipstream position
- [ ] ETH balance on BASE restores ~0.162343 ETH covered by LP receipt
- [ ] USDC balance on BASE restores ~312.859 USDC covered by LP receipt
- [ ] No phantom `EXTERNAL_TRANSFER_OUT` for these amounts

### Future lifecycle (no action now)

Future `collect()` and `decreaseLiquidity()` calls on the same NFPM will auto-classify correctly once the registry entry is present. The LP page and cost basis will track them without further changes.

---

## Track C — Dashboard flow quantity display (frontend-only fix)

### Root cause

`dashboard-transactions-pane.component.ts` and `dashboard.component.ts` — `formatQuantity` uses  
`maximumFractionDigits: 3` unconditionally. Any quantity < 0.001 rounds to `0.000` and displays as `0`.  
Result: `FEE -0 ETH` / `BUY +0 ETH` instead of `FEE -0.000407 ETH` / `BUY +0.000407 ETH`.

### Change C.1

In `formatQuantity` of both files add a branch for `absolute < 0.001`:  
use `maximumFractionDigits: 8` so e.g. `0.000407` shows as `0.000407`.  
Values ≥ 0.001 keep existing 3-decimal behaviour.

### Rebuild

```bash
./scripts/prod-reset-rebuild-backend.sh --frontend-only
```

---

## Track B — LTC / Bybit Earn Pairer documentation

### What the user changed (code already deployed)

| Component | Change |
|---|---|
| `BybitEarnPrincipalTransferPairer` | Full rework: pairing window 30 min → 400 days; 3-pass FIFO (co-event bundle, co-event sibling, hold-FIFO equal-principal) |
| `BybitCanonicalTransactionBuilder` | New `buildCrossSubAccountStakingPair` — fuses cross-sub-account ETH-family staking into single `STAKING_DEPOSIT` |
| `BybitNormalizationService` | Cross-sub-account liquid staking (FUND METH ↔ EARN CMETH) → fused `STAKING_DEPOSIT` instead of two `INTERNAL_TRANSFER` |
| `BybitStreamAuthorityCollapser` | New: suppresses corridor-deposit-stake cycle duplicates |

These changes fix the **LTC 0.511 uncovered shortfall** (previously deferred) by correctly pairing multi-week Flexible-Savings subscribe/redeem cycles and fusing cross-sub-account staking.

### Change B.1 — ADR-043 document

Create `docs/adr/ADR-043-bybit-earn-principal-pairer-fifo.md`:

- Decision: replace tight 30-min window with earn-holding-horizon FIFO (400 days ceiling, equal-principal, co-event sibling pass)
- Drivers: real Bybit Flexible-Savings cycles span weeks to months; 30-min window left most unpaired
- Mechanism: 3-pass corridor FIFO (co-event bundle → co-event sibling → hold-FIFO equal-principal)
- Consequences: LTC coverage fully restored; multi-week earn positions pair correctly

### Change B.2 — Update pipeline normalization docs

File: `docs/pipeline/normalization/rules/bybit-earn.md` (or create if absent):

- Document `buildCrossSubAccountStakingPair` — when two sub-accounts (FUND+EARN) hold opposite legs of one liquid-staking conversion, they are fused into a single umbrella-booked `STAKING_DEPOSIT`
- Document `BybitStreamAuthorityCollapser` — corridor-deposit-stake cycle suppression

### Change B.3 — Update ADR-INDEX

Add ADR-043 entry to `docs/adr/INDEX.md`.

### Rebuild for Track B

None — code already deployed. No pipeline rebuild needed for doc-only changes.

---

## Ordering

1. **Track A first** (blocking LP position): add registry entry → `--skip-frontend` rebuild → verify AC
2. **Track B after** (non-blocking): write docs only, no rebuild

---

## Deferred

- LTC `0.511` shortfall: verify post-rebuild that LTC is now fully covered (Track A rebuild will re-run earn pairer)
- Aerodrome staking contracts (gauge / veAERO rewards): not present in dataset yet; address when first transaction appears
