# 92 — Run 21 Enable Replay And Remove Legacy Projections Closeout

## Context

Run 21 introduced `AssetLedgerPoint`, but two problems remained:

1. runtime config still used `walletradar.zbasis` while replay listens to
   `walletradar.costbasis`, so `PricingCompletedEvent -> ACCOUNTING_REPLAY`
   could stop at `PRICING / COMPLETE`
2. legacy snapshot collections `asset_positions` and `reconciled_holdings`
   were still wired into runtime orchestration and docs, despite the agreed
   migration to immutable asset-ledger truth

Observed failure mode after rerun:

- session pipeline state stopped at `PRICING / COMPLETE`
- `normalized_transactions.status = PENDING_STAT` remained non-zero
- `asset_ledger_points = 0`
- old snapshot collections could still exist from previous runs

## Decision

Finalize the migration:

1. fix runtime config key to `walletradar.costbasis`
2. remove `asset_positions` and `reconciled_holdings` from live runtime flow
3. use only:
   - `asset_ledger_points`
   - `on_chain_balances`
   as post-pricing accounting outputs
4. update watchdog bootstrap/healing logic to the new truth layer

## Runtime Contract

Accounting replay is enabled only by:

- `walletradar.costbasis.enabled = true`

Replay completion for a healthy session now means:

- `asset_ledger_points > 0`
- `on_chain_balances > 0` or bounded-zero outcome for the supported candidate universe
- no active `PENDING_STAT`
- no active accounting-blocking `NEEDS_REVIEW`

The resume watchdog must:

- bootstrap replay when confirmed rows exist but `asset_ledger_points` do not
- heal stale `ACCOUNTING_REPLAY / RUNNING` only when
  `asset_ledger_points` and `on_chain_balances` already exist

## Backend Tasks

1. `BE-92-01` Rename runtime config from `zbasis` to `costbasis`.
2. `BE-92-02` Remove legacy replay/job wiring to:
   - `asset_positions`
   - `reconciled_holdings`
3. `BE-92-03` Change replay bootstrap and stale-heal checks to
   `asset_ledger_points + on_chain_balances`.
4. `BE-92-04` Delete legacy runtime services and collections that are no longer
   part of the active accounting pipeline.
5. `BE-92-05` Rewrite tests from legacy snapshot assertions to immutable
   `asset_ledger_points`.
6. `BE-92-06` Ensure rerun preparation drops old legacy collections so they do
   not confuse audits.

## Acceptance Criteria

1. Session pipeline does not stop at `PRICING / COMPLETE` when
   `PENDING_STAT > 0`.
2. `PricingCompletedEvent` can reach `ACCOUNTING_REPLAY`.
3. Replay writes `asset_ledger_points`.
4. Post-replay balance refresh writes `on_chain_balances`.
5. `asset_positions` is not created by rerun.
6. `reconciled_holdings` is not created by rerun.
7. Docs no longer present legacy projections as active runtime layers.
