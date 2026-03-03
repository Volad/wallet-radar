# 18 — LP Lifecycle Disambiguation for CL/NFT Positions

## T-046 — Separate LP position ownership moves from economic LP entry/exit

- **Module(s):** `ingestion/classifier`, `ingestion/normalizer`, `ingestion/job/pricing`, `costbasis/engine`
- **Roles:** system-architect + business-analyst requirements implemented by worker

### System Architect decision

Introduce canonical `LP_ADJUST` to represent concentrated-liquidity position NFT ownership movements that do not change LP principal at protocol level (wallet <-> strategy custody moves).

Design constraints:

- Keep `LP_ENTRY` / `LP_EXIT` only for economic principal movement.
- Keep `LP_FEE_CLAIM` only for fee harvest/collect inflows.
- Keep deterministic classifier precedence:
  1. LP exit from position context with inbound ERC-20
  2. LP fee claim
  3. LP position NFT lifecycle fallback
- `LP_ADJUST` must not require pricing and must not affect AVCO replay quantities/cost basis.

### Business Analyst acceptance criteria (DoD)

1. NFT transfer `wallet -> strategy` or `strategy -> wallet` for known LP position NFT contracts is normalized as `type=LP_ADJUST`.
2. NFT mint (`zero -> wallet`) remains LP position entry semantic (`LP_POSITION_ENTRY -> LP_ENTRY` mapping is allowed).
3. NFT burn (`wallet -> zero`) remains LP position exit semantic (`LP_POSITION_EXIT -> LP_EXIT` mapping is allowed).
4. `LP_ADJUST` is never blocked by missing price and proceeds to `CONFIRMED`.
5. `LP_ADJUST` does not change AVCO inventory/cost basis.
6. Existing LP economic paths (`LP_ENTRY`, `LP_EXIT`, `LP_FEE_CLAIM`) remain deterministic and covered by tests.

### Worker implementation scope

- Add enum values:
  - `EconomicEventType.LP_ADJUST`
  - `NormalizedTransactionType.LP_ADJUST`
- In `LpClassifier`:
  - classify custody NFT transfers (non-mint/non-burn) as `LP_ADJUST`;
  - keep mint/burn as `LP_POSITION_ENTRY/EXIT`;
  - check `classifyLpExitFromPositionContext` and `classifyLpFeeClaim` before LP position fallback.
- In `NormalizedTransactionBuilder`:
  - map `LP_ADJUST` to canonical `LP_ADJUST`;
  - set flow role for `LP_ADJUST` to `TRANSFER`.
- In pricing pipeline:
  - `LP_ADJUST` does not require unit price resolution.
- In AVCO engines:
  - ignore `LP_ADJUST` transactions in replay and cross-wallet AVCO aggregation input.

### Tests

- `LpClassifierTest`
  - inbound strategy -> wallet NFT transfer => `LP_ADJUST`
  - outbound wallet -> strategy NFT transfer => `LP_ADJUST`
- `NormalizedTransactionBuilderTest`
  - `LP_ADJUST` maps to `type=LP_ADJUST` and `TRANSFER` role
- `NormalizedTransactionPipelineJobsTest`
  - `LP_ADJUST` skips price resolution and reaches `CONFIRMED`

