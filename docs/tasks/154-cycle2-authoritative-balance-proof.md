# Task 154 — cycle/2 authoritative balance proof remediation (E1–E5)

## Goal

Make pipeline outputs reproducible against independent authoritative sources per
`cycle-autorun/cycle-data/cycle/2/results/authoritative_balance_proof.md`.

This cycle is **not** an ingestion freshness issue. The defects are in the
accounting engine and Bybit normalization semantics.

## Acceptance Criteria (DoD)

### A. Bybit value correctness (authoritative API parity)

- For session `c584c760-b228-45fc-ae0f-84f7cd7bfd8f`, aggregated Bybit holdings
  (UTA + FUND + EARN) match live API truth (within qty tolerance ±0.001 per asset)
  when comparing per-asset quantities.

### B. Clamp leak removed (E1)

- Any asset with `quantityShortfallDelta > 0` must not inflate portfolio
  “quantity held” surfaces. Physical quantity must be conserved.

### C. Cross-stream dedupe (E3)

- `TRANSACTION_LOG/{TRADE,CURRENCY_BUY,CURRENCY_SELL}` does not contribute to AVCO
  (basisRelevant=false). Canonical spot trades remain `EXECUTION_SPOT/TRADE`.

### D. Bybit sub-account dimension (E2)

- Earn-locked assets (LDO/ONDO/LINK/LTC/MNT/ARB…) are not under-stated. The
  dashboard must show Bybit totals as Σ of Bybit sub-accounts (UTA+FUND+EARN),
  not only FUND-like legs.

### E. Receipt-token basis carry (E4)

- Aave V3 receipt tokens with non-zero quantities (e.g. `AAVAUSDC`, `AARBARB`,
  `AMANWMNT`, `AZKSZK`) have non-zero basis consistent with underlying deposits.

### F. Spam/scam exclusion (E5)

- Homoglyph stables and obvious scam tokens are excluded from dashboard and AVCO.

## Edge Cases

- Unsupported destinations (TON/SOLANA wallets) remain out of scope for holdings,
  but egress must not leave phantom Bybit inventory.
- Empty refresh windows: refresh may succeed while producing zero new events; UI
  must distinguish “pulled” vs “no new occurrences”.

## Task Breakdown (ordered)

1. **E1 (P0):** expose physical quantity for read surfaces using
   `quantityAfter - quantityShortfallAfter` (ADR-004).
2. **E3 (P0):** mark `TRANSACTION_LOG/{TRADE,CURRENCY_BUY,CURRENCY_SELL}` as
   `basisRelevant=false` in Bybit extraction. Add/adjust tests.
3. **E2 (P0):** introduce Bybit sub-account dimension and correct Earn semantics:
   `BYBIT:<uid>:UTA|FUND|EARN`, and ensure dashboard aggregates across them.
4. **E4 (P1):** fill protocol registry gaps and make lending-deposit → receipt
   linking deterministic on registry membership.
5. **E5 (P2):** add allow/deny heuristics for scam/homoglyph tokens, using
   `excludedFromAccounting=true` for replay and dashboard.
6. Run full prod renormalization: `scripts/prod-reset-rebuild-backend.sh`.

## Risk Notes

- E2 changes affect canonical wallet keys (`walletAddress`) and require careful
  backward compatibility in scope resolution and dashboard filtering.
- E4 depends on registry completeness; avoid heuristics keyed by specific tx hashes.

