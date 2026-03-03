# Review: LP Position / Fee Claim Classification Acceptance

**Date:** 2026-03-02  
**Participants:** Business Analyst, System Architect, Tx Classification Auditor  
**Decision:** Accepted for implementation (PancakeSwap/Uniswap/Aerodrome scope)

---

## Scope accepted

- Add internal classifier events for CL/NFT LP lifecycle:
  - `LP_POSITION_ENTRY`
  - `LP_POSITION_EXIT`
  - `LP_ADJUST`
  - `LP_FEE_CLAIM`
- Keep canonical LP operations for position lifecycle:
  - `LP_POSITION_ENTRY -> NormalizedTransactionType.LP_ENTRY`
  - `LP_POSITION_EXIT -> NormalizedTransactionType.LP_EXIT`
- Keep canonical custody operation for non-economic position moves:
  - `LP_ADJUST -> NormalizedTransactionType.LP_ADJUST`
- Add canonical type for fee collection:
  - `LP_FEE_CLAIM -> NormalizedTransactionType.LP_FEE_CLAIM`
- For strategy <-> wallet LP position NFT transfer without principal token movement, classify as LP adjust (`LP_ADJUST`).

---

## BA Acceptance Criteria (DoD)

1. If a tx contains ERC-721 `Transfer` of a known LP position NFT involving wallet, classifier emits `LP_POSITION_*`.
2. If LP position NFT moves between wallet and strategy/contract without principal token movement, output is `LP_ADJUST`.
3. If tx contains LP fee claim context (`collect`/`harvest`) and wallet receives ERC-20 tokens without outbound ERC-20 in the same tx, classifier emits `LP_FEE_CLAIM`.
4. LP position/fee claim tx must not be duplicated as generic `EXTERNAL_*` transfer events.
5. Result is deterministic for identical input payloads.

---

## Architecture Notes

- Classification continues to run through `RawTransactionNormalizationView` only.
- No extra RPC calls are introduced for LP classifier itself.
- Protocol scope is constrained via known contracts + protocol registry names.
- Existing LP ERC-20 entry/exit heuristics are kept intact and evaluated after LP position/fee-claim checks.

---

## Auditor Controls

- Conflict guard: `TransferClassifier` must skip tx that match `isLikelyLpPositionPattern` or `isLikelyLpFeeClaimPattern`.
- Determinism:
  - output list ordering follows log order and `logIndex`;
  - stable mapping from event type to normalized type.
- Accounting:
  - `LP_FEE_CLAIM` participates as inbound acquisition in AVCO replay.
  - `LP_POSITION_*` and `LP_ADJUST` flows are transfer-role markers (position ownership lifecycle), not swap legs.

---

## Test Vector Coverage (minimum)

- LP ERC-20 entry: two outbound assets + one LP mint inbound.
- LP ERC-20 exit: one LP burn outbound + two underlying inbound.
- LP position exit (NFT): strategy -> wallet ERC-721 transfer.
- LP fee claim: `collect` context + inbound ERC-20.
- Conflict test: same LP position tx yields no `TransferClassifier` fallback events.
