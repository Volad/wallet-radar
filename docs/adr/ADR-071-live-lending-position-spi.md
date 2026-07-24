# ADR-071: Live lending position SPIs (protocol-authoritative collateral / debt / health)

**Status:** Accepted
**Date:** 2026-07-20
**Scope:** Lending refresh jobs, cost-basis balance contribution, borrow-liability true-up, lending view

---

## Context

ADR-069 made Jupiter Lend (Solana) borrows classify and account correctly, then added an
**accounting-derived** synthesized SUPPLY so the lending view stopped showing `supplyUsd = 0` /
`HF = 0`. That synthesized collateral is a genuine estimate: it drifts from the live on-chain
position under protocol rebalances, interest accrual, and uncaptured decrease events. ADR-069 itself
flagged "an exact live health factor / collateral still requires a dedicated … on-chain position
reader (future work)."

Two receipt-model families now coexist:

- **EVM (Aave, …):** collateral/debt are fungible receipt tokens (aToken / variable-debt token) that
  already flow live through generic ERC-20 `balanceOf` into `on_chain_balances`. Only the health
  factor needed a live read (`LendingAaveV3HealthCollector`, ADR-026).
- **Receipt-less (Jupiter Lend on Solana):** no fungible receipt token, so collateral is invisible to
  the wallet-token enumeration and debt was frozen at the classification-time principal.

The live-read path was hardcoded to Aave (an `AAVE_PROTOCOL_KEY` filter in the refresh service and a
`startsWith("AAVE")` gate in `LendingCycleBuilder`), so no second protocol could plug in.

---

## Decision

**Introduce two narrow, per-protocol SPIs — not one God reader — and dispatch to them by
`supports(protocolKey, networkId)`. Transport stays in `backend/platform`; orchestration and
accounting stay in `core.application.lending`. Live reads are background-only; GET paths read
persisted snapshots (snapshot-first, ADR-053/067).**

1. **`LendingLivePositionReader`** (per-wallet): `read(LivePositionRequest) → Optional<LiveLendingPosition>`
   carrying `{ collateral[], debt[], healthFactor, liquidationThreshold, loanToValue, source }`.
2. **`LendingMarketRateReader`** (per-market): `collect(ActiveMarket) → Optional<LendingMarketRateSnapshot>`
   carrying supply/borrow APY + provenance. Per-market granularity shares one reserve read across
   every wallet in that market.
3. **Aave collectors implement the SPIs unchanged in behaviour.** `LendingAaveV3HealthCollector →
   LendingLivePositionReader` (reports HF/LT/LTV only; EVM collateral/debt stay live via `balanceOf`,
   so its legs are empty — re-contributing would double-count). `LendingAaveV3MarketRateCollector →
   LendingMarketRateReader`. The refresh services (`LendingHealthFactorRefreshService`,
   `LendingMarketRateRefreshService`, and on-demand) iterate `List<…Reader>` and dispatch via
   `supports(...)`; the hardcoded Aave filter and the `LendingCycleBuilder` `startsWith("AAVE")` gate
   are deleted.
4. **`JupiterLendLivePositionReader` (Solana)** reads the wallet's positions + vault risk params via
   the platform `JupiterLendClient` (Jupiter Lend Borrow API, reusing the Jupiter request throttle).
   Reserve params (CF/LT) are **discovered from the vault config, never hardcoded**. Outstanding debt
   is taken from the position's `borrow` field (principal + accrued interest); `dustBorrow` is a
   negligible sub-unit rounding residual (~0.05 USDT), not the debt, and is used only as a fallback
   when `borrow` is absent.
5. **Single-authority rule (count the position exactly once).** The live reader is the sole authority
   across the three surfaces:
   - **`on_chain_balances`:** `ProtocolLockedBalanceProvider` SPI contributes the locked SOL
     collateral as a **native-SOL** leg (`NATIVE:SOLANA`) from the persisted snapshot — read via the
     existing `NonEvmOnChainBalanceLoader` merge — so dashboard quantity + move-basis pick it up
     exactly like an Aave aToken. Collateral is a **CARRY** valued at market from the SOL AVCO already
     in the ledger; it mints **no new basis**.
   - **Lending view:** the live collateral surfaces as a live SUPPLY position; `LendingCycleBuilder`
     trues up the synthesized SUPPLY quantity to the live figure (self-suppresses the estimate). The
     synthesized path is **retained as a clearly-stale fallback** for when the reader is unreachable —
     not deleted.
   - **Dashboard grand total:** derived from `on_chain_balances` rows; it does **not** separately add
     the lending group `supplyUsd` on top (verified unchanged).
6. **Persistence.** A new `lending_live_position_snapshots` collection stores the single-authority
   live position (collateral/debt/HF/LT/LTV, `source=LIVE_PROTOCOL`) for snapshot-first reads. Live HF
   is also written to `lending_health_factor_snapshots`; live APY to `lending_market_rate_snapshots`.

---

## Rationale

| Alternative | Why rejected |
|---|---|
| One `LendingPositionReader` God interface | Conflates per-wallet position reads with per-market rate reads (different cardinality, cache, failure modes); two SPIs keep each cohesive |
| Keep the Aave-only hardcoded filter | Blocks any second protocol; the dispatch-by-`supports` list is the extension point |
| Contribute Jupiter collateral by re-minting basis | Double-counts against the SOL AVCO already in the ledger; collateral must be a market-valued carry |
| Delete the synthesized SUPPLY path | Leaves a gap when the reader is unreachable; keep it as a guarded stale fallback |
| Live read on the GET path | Network I/O on reads; snapshot-first keeps reads deterministic and fast |
| Transport in `core` | Violates the module boundary; raw HTTP/RPC belongs in `backend/platform` behind a port |

---

## Consequences

- After a live refresh, Solana wallet `9Grpx…DhG` reports collateral ≈ **5.42 SOL**, debt ≈ **233 USDT**
  (once), CF 0.80 / LT 0.85 ⇒ **HF ≈ 1.51**, **LTV ≈ 56.4%**, all `LIVE_PROTOCOL`.
- The position is counted exactly once: locked SOL merges into `on_chain_balances`; the lending view
  reflects the same collateral; the grand total is not double-added.
- Any new protocol/network plugs in by adding a reader implementing `supports(...)` — no refresh-service
  edits.
- Bound: readers are best-effort and background-only; a stale/unreachable reader degrades to the guarded
  synthesized estimate (collateral) and the last snapshot (HF), never a fabricated live value.

## Regression anchors

- `JupiterLendLivePositionReaderTest` (ground-truth collateral/debt/HF/LTV; `supports` dispatch)
- `LendingLiabilityLiveTrueUpServiceTest` (WS-4 SET/override, EVM skip — see ADR-069 amendment)
- `JupiterLendLockedCollateralProviderTest` (native-SOL balance contribution; snapshot-first)
- `SessionLendingQueryServiceTest` (Aave parity: live-snapshot-absent falls back to the estimate)
