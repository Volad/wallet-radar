# Cost Basis — Overview

> **Last updated:** 2026-06-05  
> **Pipeline stage:** `ACCOUNTING_REPLAY` (`UserSession.PipelineStage.ACCOUNTING_REPLAY`)

Cost basis is WalletRadar's **average cost (AVCO)** accounting layer. It replays confirmed canonical transactions in deterministic order, materializes immutable ledger points, and persists auxiliary books (counterparty pools, LP receipt pools, borrow liabilities).

**Method:** AVCO (not FIFO/LIFO).  
**Authority:** Chronological replay over `normalized_transactions WHERE status = CONFIRMED AND excludedFromAccounting != true`.

## Related docs

| Doc | Focus |
|-----|-------|
| [AVCO rules](02-avco-rules.md) | BUY/SELL/TRANSFER formulas |
| [Basis pools & carry](03-basis-pools-and-carry.md) | Pools, corridors, continuity |
| [Borrow liability](04-borrow-liability.md) | Crypto-loan tracker |
| [Replay overview](../replay/01-overview.md) | Job, handlers, ledger |
| [Ledger points & basis effects](../../reference/ledger-points-and-basis-effects.md) | `AssetLedgerPoint`, `BasisEffect` semantics |

## Stage placement

```mermaid
sequenceDiagram
  participant PR as PRICING
  participant Gate as PricingDataGateService
  participant Job as CostBasisReplayJob
  participant Stat as StatValidationService
  participant AR as AvcoReplayService
  participant PS as PORTFOLIO_SNAPSHOT_REFRESH

  PR->>Job: PricingCompletedEvent
  Job->>Stat: processNextBatch (promote/demote)
  Job->>Gate: avcoReady?
  alt blocked
    Job-->>Job: ACCOUNTING_REPLAY / BLOCKED
  else ready
    Job->>AR: replayConfirmed(universeId, memberRefs)
    AR->>AR: asset_ledger_points + pools + liabilities
    Job->>PS: AccountingReplayCompletedEvent
  end
```

## Preconditions

Before replay starts:

1. Raw backfill complete for session scope
2. On-chain normalization + clarification + reclassification complete
3. Bybit normalization complete
4. Linking complete (Bybit ↔ on-chain rematch, bridge pairs)
5. Pricing gate green (`avcoReady`)

Move-basis is **part of replay**, not a separate pipeline stage.

## Entry points (verified)

| Class | Package | Role |
|-------|---------|------|
| `CostBasisReplayJob` | `costbasis/application/` | Stage driver, stat validation, gate check |
| `AvcoReplayService` | `costbasis/application/` | Full universe replay |
| `ReplayDispatcher` | `costbasis/application/replay/dispatch/` | Per-transaction routing |
| `ConfirmedReplayQueryService` | `costbasis/application/replay/query/` | Ordered confirmed load |
| `GenericFlowReplayEngine` | `costbasis/application/replay/support/` | Core BUY/SELL/FEE math |
| `StatValidationService` | `costbasis/application/` | Pre-replay stat promotion |

## Replay ordering

Deterministic sort (`ConfirmedReplayQueryService`):

1. `blockTimestamp ASC`
2. `transactionIndex ASC`
3. `_id ASC`

Bybit rows use `transactionIndex = 0`.

## Outputs

| Artifact | Collection | Scope |
|----------|------------|-------|
| Ledger timeline | `asset_ledger_points` | `accountingUniverseId` |
| Counterparty pools | `counterparty_basis_pools` | Per counterparty + asset family |
| LP receipt pools | `lp_receipt_basis_pools` | Per position / receipt marker |
| Borrow book | `borrow_liabilities` | Per `orderId` |
| Shortfall audit | `accounting_shortfall_audits` | Derived from ledger |
| Updated flows | `normalized_transactions` | Replay stamps (`avcoAtTimeOfSale`, `realisedPnlUsd`) |

Replay replaces universe-scoped collections atomically per run.

## Three AVCO surfaces (do not mix)

| Surface | Source | Use |
|---------|--------|-----|
| Dashboard header | `on_chain_balances` + latest ledger basis | Current portfolio AVCO |
| Family timeline | `TimelineAvcoAuthority` | Historical spot chart |
| Per-point ledger | `AssetLedgerPoint.avcoAfterUsd` | Audit / debugging |

See [ADR-017](../../adr/ADR-017-timeline-avco-authority.md).

## Current holding diagnostics (read-time)

Not replay events — derived on dashboard read:

| Issue | Meaning |
|-------|---------|
| `yield_accrual` | Live qty above covered; interest-bearing bucket |
| `coverage_gap` | Live qty above provable basis |
| `history_flags` | Covered qty OK but incomplete history |
| `missing_replay_point` | Balance exists, no ledger point |

## Supported scope

On-chain networks per [supported networks & protocols](../../reference/supported-networks-and-protocols.md); CEX: `BYBIT` only for this milestone.

Excluded rows (`excludedFromAccounting = true`) remain in Mongo for audit but never replay.

## Rules by transaction type

High-level **replay outcome** per type (detail in linked docs):

| Type | Basis effect |
|------|--------------|
| `BUY` | ACQUIRE — increases qty and cost basis |
| `SELL` | DISPOSE — realises PnL on covered qty |
| `FEE` | GAS_ONLY — reduces qty; may capitalise into acquisition |
| `INTERNAL_TRANSFER` | CARRY_OUT / CARRY_IN — no PnL |
| `BRIDGE_OUT` / `BRIDGE_IN` | Same-family carry or asset-changing settlement repair |
| `EXTERNAL_TRANSFER_*` (correlated) | CARRY with pending inbound ordering |
| `SPONSORED_GAS_IN` | Zero-cost ACQUIRE |
| `SWAP` | SELL then BUY (or handler-specific) |
| `LENDING_*` / `VAULT_*` | REALLOCATE / CARRY — principal not realised |
| `LP_ENTRY` / `LP_EXIT` | Receipt pool or position-scoped bucket |
| `BORROW` / `REPAY` | Reserve BUY/SELL + liability tracker |
| `STAKING_*` | Liquid-staking carry via `LiquidStakingReplayHandler` |
| `LENDING_LOOP_*` | `EulerLoopReplayHandler` |
| `GMX LP_ENTRY_*` | `GmxLpEntryReplayHandler` async escrow |
| `DEX_ORDER_*` | `AsyncSpotOrderReplayHandler` |
| `DERIVATIVE_*` | Collateral/fees only — no synthetic underlying spot |
| `REWARD_CLAIM` | ACQUIRE at priced FMV |
| `WRAP` / `UNWRAP` | AVCO preserved across wrapper (±0.1%) |
