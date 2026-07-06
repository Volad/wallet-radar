# Move Basis (Asset Ledger)

> **Route:** `/sessions/:sessionId/assets/:familyIdentity`  
> **Component:** `frontend/src/app/features/asset-ledger/asset-ledger-page.component.ts`  
> **UI title:** "Move basis"

There is **no** `/move-basis` route.

## Data flow

```mermaid
sequenceDiagram
  participant Page as AssetLedgerPageComponent
  participant API as WalletApiService
  participant BE as AssetLedgerQueryService

  Page->>API: GET /sessions/{id}
  Page->>API: GET /sessions/{id}/asset-ledger?familyIdentity=
  API->>BE: timeline + ledger points
  Note over Page: Canvas charts render AVCO timeline
```

## Displays

- **Sidebar filters:** event families, type toggles, basis-effect toggles, date presets
- **Summary:** Net AVCO (primary) and Tax AVCO (secondary), qty held, covered/uncovered pills, realised PnL, gas paid
- **AVCO timeline chart** — two series (Net solid, Tax dashed); markers per replay event; hover/pin tooltips show both values. The plotted line is the **family covered-weighted per-bucket AVCO** (`Σ coveredᵢ·avcoᵢ / Σ coveredᵢ`, [ADR-045](../adr/ADR-045-family-covered-weighted-move-basis-avco-series.md)). Behavior notes:
  - The line **breaks** (gap) when family covered qty = 0 (undefined AVCO, ADR-031); it is never drawn at $0.
  - The line still shows **large, economically-real swings** when the bulk of an asset moves in/out of LP / bridge / receipt positions (which live in the excluded `FAMILY:LP_RECEIPT` view) — this is expected, not a bug. Per-pool detail is in the event-log rows/tooltip and is a **distinct** surface from the family line.
  - The plotted terminal value equals the summary/headline family AVCO.
- **Range control** — dual slider; default last 21 days (min 16 points)
- **Position size chart** — quantity over time
- **Realised P&L chart** — disposal events + cumulative path
- **Event log table** — type, protocol, date, qty Δ, amount, unit price, from, to, realised PnL; expandable row with AVCO/basis/flows/gas
- **Matched transfers** — correlated bridge/transfer legs collapsed into one marker with expandable legs

## API

| Method | Path |
|--------|------|
| GET | `/api/v1/sessions/{id}/asset-ledger?familyIdentity=...` |
| GET | `/api/v1/sessions/{id}` — wallet labels/colors |

Read-only — no write APIs.

## UI rules

| Preset | Behavior |
|--------|----------|
| `economics` (default) | Hide WRAP, UNWRAP, GAS_ONLY types and GAS_ONLY basis effect |
| `all` | Show everything |
| `transfers` | Bridge + transfer types only |
| `basisMoves` | CARRY_*, REALLOCATE_* basis effects only |

**Basis move set:** `CARRY_IN`, `CARRY_OUT`, `REALLOCATE_IN`, `REALLOCATE_OUT`

Backend authority: [ADR-045 Family covered-weighted move-basis AVCO series](../adr/ADR-045-family-covered-weighted-move-basis-avco-series.md) (supersedes the ADR-017 per-identity chart source; `AssetLedgerQueryService` timeline loop). [ADR-017](../adr/ADR-017-timeline-avco-authority.md) still governs the spot-family filter and staked-ETH inclusion.

## Related

- [Ledger points reference](../reference/ledger-points-and-basis-effects.md)
- [Move basis carry examples](../examples/move-basis-carry-examples.md)
