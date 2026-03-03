# 19 — LP v3/v4 Entry Economic Completeness

## T-047 — LP v3/v4 lifecycle state-machine + boundary completeness

- **Module(s):** `ingestion/classifier`, `ingestion/normalizer`, `costbasis/engine`, `docs`
- **Roles:** business-analyst + system-architect requirements implemented by worker

### System Architect decision

For concentrated-liquidity pools (v3/v4, NFT-based positions), lifecycle must be derived from position NFT transfer history (mint/transfer/burn) with tx-level ERC-20 receipt-log flows attached to each LP event.

Design constraints:

- Preserve classifier determinism and precedence:
  1. LP exit from position context
  2. LP entry from position context
  3. LP fee claim
  4. LP position NFT lifecycle fallback
- Replace generic custody `LP_ADJUST` semantics with explicit lifecycle types:
  - `LP_POSITION_STAKE`
  - `LP_POSITION_UNSTAKE`
- Replace generic economic LP exit with explicit:
  - `LP_EXIT_PARTIAL`
  - `LP_EXIT_FINAL`
- `groupId` must continue linking v3/v4 LP tx by position id (`LP_POSITION:{NETWORK}:{wallet}:{positionId}`).

### Business Analyst acceptance criteria (DoD)

1. Lifecycle source of truth is position NFT transfer (`mint/transfer/burn`) for known v3/v4 position NFT contracts.
2. For v3/v4 open transaction (`mint` + outbound principal tokens), normalized type is `LP_ENTRY` with outbound ERC-20 flows (negative quantities).
3. `collect`-only transactions are classified as `LP_FEE_CLAIM`.
4. `decreaseLiquidity` context with token inflow is classified as:
   - `LP_EXIT_PARTIAL` when no matching position burn in tx
   - `LP_EXIT_FINAL` when matching position burn is present in tx
5. NFT custody transfer `wallet -> farm/strategy` is `LP_POSITION_STAKE`; `farm/strategy -> wallet` is `LP_POSITION_UNSTAKE`.
6. For each LP tx in a `groupId`, ERC-20 receipt-log flows are attached to the emitted LP event(s) when present.
7. If within the available window for a `groupId` there is no opening or closing boundary, `boundaryStatuses` includes:
   - `OPENING_MISSING` when no entry exists
   - `CLOSING_MISSING` when no final close exists
8. No duplicate accounting: same token movement cannot be represented simultaneously as LP economic flow and generic transfer/swap for the same tx.

### Worker implementation scope

- In `LpClassifier`:
  - add/keep LP entry-from-position-context path:
    - detect minted LP position NFT (`zero -> wallet`) on known LP position NFT contracts;
    - extract outbound ERC-20 transfer logs (`wallet -> non-zero`) as `LP_ENTRY` events;
    - attach `positionId`.
  - classify NFT custody transfer fallback as `LP_POSITION_STAKE/LP_POSITION_UNSTAKE` (instead of generic `LP_ADJUST`).
  - classify position-exit context as `LP_EXIT_PARTIAL/LP_EXIT_FINAL` using in-tx NFT burn signal.
  - execute this path before LP fee-claim and LP position fallback.
- In `NormalizedTransactionBuilder`:
  - map new LP event types to canonical normalized types.
- In `NormalizedTransactionStatJob`:
  - compute and propagate `boundaryStatuses` on grouped LP tx by `groupId`.
- In pricing/stat/costbasis:
  - `LP_POSITION_STAKE/LP_POSITION_UNSTAKE` remain non-economic (no price requirement, no AVCO effect).
  - `LP_EXIT_PARTIAL/LP_EXIT_FINAL` follow LP exit accounting path.

### Tests

- `LpClassifierTest`
  - v3/v4 mint + outbound token logs => `LP_ENTRY` with outbound principal legs and `positionId`
  - custody transfer fallback => `LP_POSITION_STAKE/LP_POSITION_UNSTAKE`
  - decrease liquidity + collect without burn => `LP_EXIT_PARTIAL`
  - decrease liquidity + collect with burn => `LP_EXIT_FINAL`
- `NormalizedTransactionPipelineJobsTest`
  - `LP_POSITION_STAKE/LP_POSITION_UNSTAKE` do not require pricing
- `NormalizedTransactionStatJob` tests
  - boundary statuses set to `OPENING_MISSING` / `CLOSING_MISSING` by `groupId` window analysis
