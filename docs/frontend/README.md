# Frontend

> **Last updated:** 2026-06-05  
> Angular standalone SPA under `frontend/src/app/`.

## Architecture

```mermaid
flowchart TB
  subgraph core [core/]
    Services[WalletApiService, DashboardDataService, LendingDataService]
    Models[dashboard.models, lending.models]
    Storage[SessionStorageService]
  end
  subgraph features [features/]
    Dashboard[dashboard/]
    AssetLedger[asset-ledger/]
    Settings[settings/]
    Lending[lending/]
  end
  AppRoutes[app.routes.ts] --> Dashboard
  Dashboard --> AssetLedger
  Dashboard --> Settings
  Dashboard --> Lending
  core --> features
```

## Routing (`app.routes.ts`)

| Path | Component | Mode |
|------|-----------|------|
| `''` | `DashboardComponent` | Dashboard |
| `settings` | `DashboardComponent` | `data.mode: settings` |
| `lending` | `DashboardComponent` | `data.mode: lending` |
| `sessions/:sessionId/assets/:familyIdentity` | `DashboardComponent` | Asset ledger deep link |
| `**` | redirect → `''` | |

Single shell: `DashboardComponent` switches workspace by route `data.mode` and asset-ledger signals.

## State management

- **Signals** + `computed` + `effect` / `toSignal` — no NgRx
- Session ID: `SessionStorageService` → `localStorage` key `wr.sessionId`
- API base: `/api/v1` from `environment.apiBaseUrl`

## Pages

| Doc | Route |
|-----|-------|
| [Dashboard](dashboard.md) | `/` |
| [Move basis](move-basis.md) | `/sessions/:id/assets/:familyIdentity` |
| [Settings](settings.md) | `/settings` |
| [Lending market](lending-market.md) | `/lending` |

## Related

- [API reference](../reference/api.md)
- [Architecture overview](../overview/03-architecture.md)
