# Cost Basis — Borrow Liability

> **Last updated:** 2026-06-05  
> **Pipeline stage:** `ACCOUNTING_REPLAY`

Crypto-loan principal is tracked separately from spot AVCO so borrow/repay roundtrips do not fabricate realised PnL. Implementation: `BorrowLiabilityTracker` per [ADR-012](../../adr/ADR-012-borrow-liability-tracker.md).

## Data model

**Domain:** `BorrowLiability`  
**Repository:** `BorrowLiabilityRepository`  
**Collection:** `borrow_liabilities`  
**Replay context:** `BorrowLiabilityReplayContext`

Composite key:

```text
compositeId = universeId + ":" + orderId
```

| Field | Purpose |
|-------|---------|
| `orderId` | Provider loan order identifier |
| `accountRef` | Bybit account ref |
| `asset` | Reserve asset symbol |
| `qtyBorrowed` / `qtyOpen` | Original and remaining principal |
| `portfolioAvcoAtOpen` | Portfolio AVCO snapshot at borrow |
| `portfolioAvcoSource` | `PriceSource` used for snapshot |
| `status` | `OPEN`, `PARTIAL`, `CLOSED`, `OPEN_FROM_REPAY` |

## Handlers

| Handler | Trigger types | Role |
|---------|---------------|------|
| `BorrowReplayHandler` | `BORROW` | Record liability; reserve asset BUY |
| `RepayReplayHandler` | `REPAY` | Match liability; reserve asset SELL |

Only **reserve asset** principal is economic. Debt-marker mint/burn legs and execution refund legs remain continuity `TRANSFER` — no spot lots.

## Borrow flow

```mermaid
sequenceDiagram
  participant RP as ReplayDispatcher
  participant BH as BorrowReplayHandler
  participant BL as BorrowLiabilityTracker
  participant GFE as GenericFlowReplayEngine

  RP->>BH: BORROW transaction
  BH->>GFE: applyBuy(reserve asset)
  BH->>BL: recordBorrow(orderId, qty, portfolioAvco)
  Note over BL: status = OPEN
```

`recordBorrow`:

1. Snapshot current portfolio AVCO for reserve asset
2. Create or extend `BorrowLiability` for `orderId`
3. Store `portfolioAvcoAtOpen` for later repay matching

## Repay flow

```mermaid
sequenceDiagram
  participant RP as ReplayDispatcher
  participant RH as RepayReplayHandler
  participant BL as BorrowLiabilityTracker
  participant GFE as GenericFlowReplayEngine

  RP->>RH: REPAY transaction
  RH->>BL: matchRepay(orderId, qty)
  BL-->>RH: RepayMatch(matchedQty, liabilityAvcoUsd)
  RH->>GFE: applySell(reserve) with liability-aware PnL suppression
  Note over BL: qtyOpen reduced; CLOSED when ~0
```

`RepayMatch` returns:

- `matchedQty` — quantity matched to open liability
- `residualQty` — excess repay not tied to liability
- `liabilityAvcoUsd` — AVCO at open for matched portion
- `liabilityFound` — whether book entry existed

Zero-PnL roundtrip: when repay matches open borrow, disposal uses liability AVCO — not current spot AVCO — preventing phantom gain/loss on loan closure.

## Persistence

`AvcoReplayService` loads liabilities at replay start:

```java
borrowLiabilityTracker.loadAllForUniverse(universeId)
```

Replaces universe book at end:

```java
borrowLiabilityTracker.replaceUniverseLiabilities(universeId, borrowLiabilities)
```

## Dashboard integration

`PortfolioConservationGate` subtracts open liability USD from mark-to-market when evaluating conservation invariant (ADR-014).

## Rules by transaction type

Borrow-liability stage scope:

| Type | Liability behavior |
|------|-------------------|
| `BORROW` | `BorrowReplayHandler`: reserve `BUY` + `recordBorrow`; debt tokens `TRANSFER` only |
| `REPAY` | `RepayReplayHandler`: `matchRepay` + reserve `SELL`; debt burn `TRANSFER` only |
| `LENDING_BORROW` (Aave on-chain) | Same split: reserve economic, receipt/debt markers continuity |
| `LENDING_REPAY` | Same |
| `LENDING_LOOP_OPEN` | **Not** borrow liability — Euler loop uses share position (`EulerLoopReplayHandler`) |
| `LENDING_LOOP_*` | No `orderId` liability book |
| Bybit crypto loan rows | `orderId`-keyed when provider emits deterministic id |
| `SWAP` / `TRANSFER` | No liability interaction |
| `FEE` on loan | Priced fee; does not open liability |
| Partial repay | `status = PARTIAL`; remaining `qtyOpen` |
| Repay without prior borrow | `RepayMatch.liabilityFound = false`; normal SELL path |
| Over-repay | `residualQty` disposes at spot AVCO |

## Invariants

- Liability book is universe-scoped (`accountingUniverseId`)
- Replay is authoritative — book rebuilt each full replay
- Debt-marker evidence never becomes standalone replay lots
- Marker-only batch openings without wallet-boundary lifecycle stay `NEEDS_REVIEW` — never enter borrow handlers
