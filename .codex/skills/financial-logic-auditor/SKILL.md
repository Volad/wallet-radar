---
name: financial-logic-auditor
description: WalletRadar ledger reconstruction and financial correctness audit using MongoDB raw sources and filesystem outputs. Use when Codex must rebuild end-to-end asset history, classify or reclassify on-chain and Bybit events, compute authoritative cost basis and AVCO, reconcile derived balances against on-chain positions, investigate normalization/pricing/accounting gaps, or produce audit artifacts without implementing application code.
---

# Financial Logic Auditor

Reconstruct authoritative ledger history from WalletRadar raw data and produce a traceable audit trail for balances, cost basis, and AVCO. Work directly with MongoDB and the filesystem, write scripts when needed, and do not implement application code.

## Quick Start

1. Count source coverage in `raw_transactions`, `normalized_transactions`, and `external_ledger_raw`.
2. Identify gaps by wallet, network, time range, `normalizationStatus=PENDING`, and `status=NEEDS_REVIEW`.
3. Use `normalized_transactions` with `status=CONFIRMED` only when the pipeline output is trustworthy; otherwise recompute from raw.
4. Process events strictly genesis-forward by `blockTimestamp ASC`, then `transactionIndex ASC`.
5. Record blockers with ID and status and continue the audit instead of stopping.

## Operating Rules

- Start from raw sources:
  - `raw_transactions` for on-chain ground truth
  - `external_ledger_raw` for Bybit ground truth
- When auditing Mongo data, cross-check important transaction facts against external sources in this order:
  - Etherscan-compatible explorer APIs and pages
  - Blockscout-compatible explorer APIs and pages
  - Routescan-compatible explorer APIs and pages
  - direct RPC only as a last resort when explorer evidence is insufficient
- Use `asset_positions` only for reconciliation, never as a reconstruction starting point.
- Use current on-chain balances only for final reconciliation.
- Ignore synthetic `rawData.logs[]`.
- For classification conclusions, trust only evidence that exists at backfill time and is available to the normal normalization path.
- For clarification conclusions, trust only evidence that can be pulled by the real clarification stage and would actually be available to that stage in production.
- Never work backwards from current state.
- Keep output deterministic: same inputs, same ordering, same result.
- If application code changes are required, report them separately; do not implement them.

## Workflow

1. Build source counts and coverage.
2. Resolve or document `NEEDS_REVIEW` and raw-data classification gaps.
3. Reconstruct one chronological event stream from on-chain and Bybit sources.
4. Correlate bridge, custody, and CEX transfer events to avoid double-counting.
5. Compute AVCO only after movement coverage is complete.
6. Reconcile derived quantities against on-chain quantities.
7. Write results to the required output files and keep intermediate datasets under `data/derived/`.

## References

Read [ledger-audit-spec.md](references/ledger-audit-spec.md) when you need the full operating spec:

- Mongo collection schemas and relevant fields
- Classification rules and flow semantics
- AVCO computation rules
- Supported networks and out-of-scope assets
- Known blockers and session startup checklist
- Required output files

Also use repository sources directly when needed:

- `protocol-registry.json`
- `normalization-architecture-en.md`

## Deliverables

- `results/blockers.md`
- `results/warnings.md`
- `results/reconciliation.md`
- `results/eth_basis.md`
- `data/derived/`

## Priorities

1. Financial correctness
2. Deterministic output
3. No double-counting
4. Correct DeFi semantics
5. Complete, traceable audit trail

## Guardrails

- Do not start from `asset_positions`.
- Do not use synthetic logs as evidence.
- Do not let explorer-only or RPC-only evidence redefine classification if that evidence is not available at backfill time.
- Do not let manual audit enrichment redefine clarification if the same fields would not be available to the real clarification stage.
- Treat unknown events, price gaps, and incomplete history explicitly.
- Keep every conclusion traceable to raw Mongo documents or source files.
