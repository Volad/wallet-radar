# WalletRadar Frontend

Angular SPA for WalletRadar dashboard UI, implemented from `walletradar-v3.jsx` visual reference.

## Stack

- Angular 19
- TypeScript (strict)
- RxJS
- SCSS

## Run

```bash
npm install
npm start
```

Dev server: `http://127.0.0.1:4200/`

## Build

```bash
npm run build
```

Production bundle output: `dist/wallet-radar-frontend/`

## Test

```bash
npm run test -- --watch=false --browsers=ChromeHeadless
```

## Structure

- `src/app/core/models/` — typed domain/UI models
- `src/app/core/data/` — mock dashboard dataset
- `src/app/core/services/` — data service layer (Observable-based)
- `src/app/features/dashboard/` — dashboard shell, sections, filters, transactions editor
- `src/styles.scss` — global design tokens and base styles
