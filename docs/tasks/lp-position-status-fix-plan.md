# LP position status, enrichment, two-leg entry, and UI fix

Implementation completed per approved plan. Key changes:

- **Refresh trigger:** `LpPositionRefreshJob` listens to `AccountingReplayCompletedEvent` (removed boot-only refresh).
- **Enrichment:** shell snapshots on failure; token ordering swap; closed on `liquidity==0`; `unknown` status when no snapshot.
- **Read-model:** `depositedMarketUsd`, `entryToken0/1`, multi-leg `TxnView`, `scope=active|closed|all` API filter.
- **Classification:** GMX settlements map to `gmx-lp:` via `GmxMarketCorrelationSupport`; `LP_EXIT_FINAL` on full NFT close.
- **Frontend:** Active/Closed/All pills, unknown→Tracking, entry deposit panel, two-leg txn history.

Verification: `./scripts/prod-reset-rebuild-backend.sh --skip-frontend` then `--frontend-only`.
