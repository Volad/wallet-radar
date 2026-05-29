# ADR-017 — Timeline AVCO authority (move-basis read model)

**Status:** Accepted (amended 2026-05-29 — staked ETH inclusion)  
**Date:** 2026-05-27  
**Context:** Cluster E — ETH family move-basis timeline showed misleading AVCO (~$1.60, ~$271k, ~$783k) while per-wallet ledger AVCO remained ~$2k–$4k.

## Problem

The asset-ledger timeline exposed three incompatible AVCO sources:

1. **Family rollup** — `sum(costBasisDelta) / sum(covered qty)` across heterogeneous family members (LP receipt share counts, CMETH qty, WETH qty).
2. **Max-|quantityDelta| primary leg** — on multi-leg txs the largest flow (often LP receipt or CMETH on `:EARN`) was chosen as `avcoAfterUsd`.
3. **Per-wallet replay points** — authoritative for bucket economics but not consistently selected for timeline rows.

The UI chart connects `avcoBefore = previous.avcoAfter` without changing layout. Incorrect `avcoAfterUsd` on timeline rows therefore produces visible spikes and flatlines.

## Decision

Introduce **`TimelineAvcoAuthority`** as the sole selector of timeline `avcoAfterUsd` for family ledger pages.

### Selection rules

For each grouped timeline event:

1. Consider only member ledger points eligible for **spot-family timeline aggregation** (see below).
2. Score candidates by spot-native symbol preference, basis-moving effects (`ACQUIRE`, `DISPOSE`, `CARRY_*`), non-zero `costBasisDeltaUsd`, and inbound legs.
3. Reject read-time **outliers** (>10× median spot AVCO, or covered ratio <1% with uncovered ratio >50%).
4. Emit `avcoKind=PRIMARY_FLOW` when a candidate survives; otherwise `avcoKind=UNAVAILABLE` (no `FAMILY_ROLLUP` on the main timeline line).

### Spot-family timeline aggregation filter

Shared with [`AccountingAssetFamilySupport`](../backend/src/main/java/com/walletradar/accounting/support/AccountingAssetFamilySupport.java):

- Exclude `LP-RECEIPT:*` (dedicated `FAMILY:LP_RECEIPT` at write).
- For `FAMILY:ETH` pages, **include** staked/liquid-staking ETH symbols — `CMETH`, `METH`, `WEETH`, `WSTETH`, `STETH`, `RSETH` — in both quantity rollup and AVCO authority.
- **Exclude** only non-ETH assets that happen to be in the family scope: `BBSOL` remains excluded from `FAMILY:ETH`.

**Amendment (2026-05-29):** Prior version excluded staked ETH symbols from the timeline filter. This was overriding user requirement R4 ("CMETH / WSTETH / RSETH / METH are staked ETH — must not be excluded from family cost basis"). The exclusion is removed. The full staked-ETH inclusion set is:

| Symbol | Type | Include in FAMILY:ETH |
|--------|------|----------------------|
| ETH | Native | Yes |
| WETH | Wrapped | Yes |
| AMANWETH | Lending receipt | Yes |
| CMETH | Liquid staking | Yes |
| METH | Liquid staking | Yes |
| WEETH | Liquid staking | Yes |
| WSTETH | Liquid staking | Yes |
| STETH | Staked | Yes |
| RSETH | Restaked | Yes |
| BBSOL | Non-ETH | **No** |
| LP-RECEIPT:* | LP NFT | **No** (own family) |

Family mapping for replay/custody is unchanged; only the move-basis timeline read model is updated.

### `avcoBefore` series

Timeline `avcoBeforeUsd` must follow the previous row of the **same `accountingAssetIdentity` spot series**, not a global previous row. The authority maintains `lastAvcoByAccountingAssetIdentity` while building timeline entries (API raw ledger points already expose per-point `avcoBeforeUsd`).

### Explicit non-goals

- No new Mongo `avcoAuthoritative` field on ledger points (read-time exclusion only).
- No separate LP-receipt timeline page in this ADR (phase 2 if needed).
- Dashboard header AVCO remains live-balance + per-bucket ledger (unchanged).

## Consequences

- ETH move-basis chart values align with spot economics (~$2k–$4k band) without UI chart changes.
- Legacy rows still tagged `FAMILY:ETH` for LP receipts benefit from read-time exclusion immediately; full `FAMILY:LP_RECEIPT` at write requires replay rebuild.
- Including staked ETH in the timeline will surface CMETH/METH/WEETH AVCO events on the ETH family chart — these are economically correct and user-required.
- Operators must treat three AVCO surfaces separately (dashboard, timeline, per-point ledger) — documented in `docs/03-accounting.md`.
- **Amendment impact:** requires `AccountingAssetFamilySupport` code change + full rebuild to take effect.

## Related

- Cluster E acceptance: `report.md`
- Replay fixes (earn umbrella carry, CMETH internal): `TransferReplayHandler`, `ContinuityCarryService`
- LP receipt family at write: `AccountingAssetFamilySupport`, `AssetLedgerSupport.accountingFamilyIdentity`
