# ADR-076: Jupiter Lend live market-rate reader + time-weighted factual-APY exposure

**Status:** Accepted
**Date:** 2026-07-21
**Scope:** Lending market-rate refresh, lending view Net APY, factual-APY calculator (all protocols)

---

## Context

Two lending-page correctness defects surfaced for a Jupiter Lend (Solana, receipt-less) leveraged
SOL/USDC loop where SOL was deposited across ~7 tranches over months:

1. **Factual APY overstated (~2.6×).** `LendingFactualApyCalculator` used the **first deposit**
   (`openingDepositByAsset`, populated via `putIfAbsent`) as the APR exposure denominator, while income
   accrued on the **full** principal. Deposits made after cycle open therefore inflated the ratio.
2. **Net APY shows `--` for Jupiter Lend.** The frontend `groupNetApy` returns null when a position's
   `protocolSupplyApyPct` / `protocolBorrowApyPct` are null. Aave populates these from
   `LendingAaveV3MarketRateCollector` (on-chain reserve read, `PROTOCOL_SNAPSHOT`); Jupiter Lend had
   **no** `LendingMarketRateReader`, so it fell back to `ACCOUNTING_ESTIMATE` and both fields were null.

The live-rate SPI and dispatch-by-`supports(...)` machinery already exist (ADR-071); the Jupiter Lend
Borrow API and its platform client (`JupiterLendClient` / `WebClientJupiterLendClient`) already exist
(ADR-069/071).

---

## Decision

### A. Time-weighted average principal as the factual-APR exposure denominator (all protocols)

`LendingFactualApyCalculator` now separates two concerns that were conflated on the first deposit:

- **Exposure denominator** = **time-weighted average principal** over `[start, end]`. A chronologically
  sorted timeline of signed principal deltas (deposits `+`, principal reductions `-` for supply;
  borrows `+`, repays `-` for borrow) is integrated as `Σ(running_balance × segment_seconds) / total_seconds`.
  Points are clamped to `[start, end]`; order is sorted defensively (not assumed from accumulation).
- **Income cost-basis** = **total principal deposited** (`principalInByAsset`), unchanged in meaning.

The timeline is built in `LendingCycleBuilder.DeltaAccumulator` (using each event's `blockTimestamp()`)
and exposed as `timeWeightedSupplyPrincipalByAsset(start,end)` / `timeWeightedBorrowPrincipalByAsset(start,end)`.
The calculator `Input` gains `timeWeightedSupplyPrincipalByAsset`, `totalSupplyPrincipalByAsset`, and
`timeWeightedBorrowPrincipalByAsset`; the `unavailableReason` / `resolveApyPrecision` /
`resolveApyUnavailableReason` supply-exposure checks now test the time-weighted map. **Fallback:** when
the timeline is empty or the duration is non-positive, the previous totals (`principalInByAsset` /
`borrowedByAsset`) are used so nothing regresses to null. Single-deposit cycles are numerically
unchanged (one deposit at cycle start time-weights to itself). This is shared by **every** lending
protocol (Aave, Euler, Fluid, Morpho, Jupiter Lend, …).

### B. `LendingJupiterLendMarketRateCollector` (Solana `LendingMarketRateReader`)

Jupiter Lend **does** expose live per-market rates via `GET /lend/v1/borrow/vaults` (`supplyRate`,
`borrowRate`). The venue encodes them in **basis points** (`458 → 4.58%`), cross-checked against the
Earn API where `rewardsRate + supplyRate == totalRate` (`71 + 351 == 422`). The platform client
(`WebClientJupiterLendClient`) now decodes these to a true percent (`rateFraction`, ÷100), mirroring its
existing per-mille risk-param decode, so consumers never re-scale.

The collector matches a discovered `ActiveMarket` to its vault by **side + canonical underlying symbol**
(`WSOL ≡ SOL`), treats the venue rate as an APR, and derives APY via the same per-second compounding as
the Aave reader (`apyConvention = PER_SECOND_COMPOUNDING`). It publishes `supply/borrow APY` with
`rateSource = JUPITER_LEND_BORROW_API` and `rateStatus = API_SNAPSHOT` (honest provenance: a venue REST
snapshot, not an on-chain read). `LendingCycleBuilder.positionRateMetric` now treats **both**
`PROTOCOL_SNAPSHOT` and `API_SNAPSHOT` as live rate statuses, so both drive the protocol supply/borrow
APY (and therefore Net APY). It **never fabricates** a rate: a missing side rate / vault yields an
`UNAVAILABLE` snapshot with an explicit reason.

### C. Second active-market discovery source for receipt-less positions

The reader in **B** only runs for markets emitted by `LendingActiveMarketDiscoveryService`. That service
originally discovered markets **only** by scanning `on_chain_balances` for lending receipt/debt tokens —
which works for EVM Aave (aTokens + variableDebt tokens are on-chain balances) but emits **nothing** for
receipt-less protocols: Jupiter Lend collateral is native SOL (`contract = NATIVE:SOLANA`, explicitly
excluded from the receipt scan) and the borrow is a synthetic liability with no debt-token balance. So no
`ActiveMarket` was emitted for Jupiter Lend and the reader in **B** never dispatched (observed:
`activeMarkets=5 saved=5`, all Aave, no SOLANA snapshot; Net APY still `--`).

A second source, `LendingReceiptLessActiveMarketSource`, now reads the single-authority live-position
snapshot (`lending_live_position_snapshots`, ADR-071) — the authoritative record of the CURRENT open
receipt-less position — and emits one `ActiveMarket` per collateral leg (`SUPPLY`) and debt leg
(`BORROW`) of the **freshest snapshot per `(session, protocol, network, wallet)` group** within a
30-minute active window (must exceed the 10-min HF-refresh cadence so an open position never ages out,
while a closed position — no longer re-snapshotted — drops out, mirroring how an `on_chain_balances` row
disappears on close). `LendingActiveMarketDiscoveryService` merges both sources into one map deduped by
the composite market key.

**Key alignment (why the snapshot attaches to the position).** The emitted market is keyed to match the
built lending position's lookup exactly: `marketKey = protocol:NETWORK:ACCOUNT-POOL` (receipt-less loops
resolve `marketAsset = account-pool` via the WS-8 capability flag, ADR-073; `displaySymbol` uppercases to
`ACCOUNT-POOL`), and `underlyingSymbol = displaySymbol(lifecycleAsset(legSymbol))` — the same
`cycleStateAsset` normalization the synthesized SUPPLY/BORROW positions carry (SOL→SOL, USDC→USDC). The
`protocol`/`networkId`/`walletAddress` are the snapshot's own fields, which are the same
`group.protocol()` / network / wallet the cycle uses (already proven by the live-collateral true-up
lookup). Network-agnostic and boundary-clean: no wallet/token/protocol string is hardcoded — a protocol
qualifies purely by having a live snapshot plus a reader whose `supports(...)` matches.

---

## Rationale

| Alternative | Why rejected |
|---|---|
| Keep first-deposit denominator | Overstates APR ~2.6× for multi-deposit cycles; income accrues on the whole principal |
| Time-weight the income cost-basis too | Wrong: cost-basis is total capital deployed, not an average |
| Fabricate a Jupiter Lend rate | Prohibited; venue actually exposes real rates, so read them, else `UNAVAILABLE` |
| Decode basis points in the collector | Unit-decoding belongs in the transport client (parity with per-mille risk params) |
| Gate consumer on `PROTOCOL_SNAPSHOT` only | Would silently drop the honest `API_SNAPSHOT`; widen to both live statuses |
| Per-network branch in consumption code | Violates ADR-074; dispatch stays in the reader's own `supports(...)` |
| Discover receipt-less markets from `on_chain_balances` | Native SOL is `NATIVE:` (excluded) and the debt is synthetic — no row qualifies |
| Re-add native `NATIVE:` rows to the receipt scan | Would break the EVM path's native-token exclusion; the receipt-less source is a separate path |
| String-match "Jupiter" to detect receipt-less | Prohibited hardcode; use the live-snapshot source + capability-flagged `account-pool` key |

---

## Consequences

- Multi-deposit lending cycles report a factual APR/APY on the time-weighted principal (materially lower
  than the old first-deposit figure); single-deposit cycles are unchanged.
- Jupiter Lend supply/borrow positions carry live `protocolSupplyApyPct` / `protocolBorrowApyPct`, so the
  lending page renders **Net APY** instead of `--` (the SOLANA market is now discovered → the reader runs
  → a SOLANA `lending_market_rate_snapshot` is saved → the position lookup attaches it).
- Receipt-less positions (Solana/TON) are discovered from their live-position snapshot, not
  `on_chain_balances`; the on-demand refresh writes the snapshot immediately before discovery, so the
  user-triggered path is deterministic even if the startup background jobs race.
- Any new protocol/network plugs a `LendingMarketRateReader` in via `supports(...)` — no refresh-service edits.
- Best-effort/background-only: an unreachable venue degrades to `UNAVAILABLE` (then the accounting
  estimate), never a fabricated live rate.

## Regression anchors

- `LendingFactualApyCalculatorTest` — `supplyAprUsesTimeWeightedPrincipalNotFirstDeposit`,
  `borrowAprUsesTimeWeightedOutstandingNotTotalEverBorrowed` (+ existing single-deposit cases green)
- `LendingJupiterLendMarketRateCollectorTest` — supply/borrow match, `WSOL≡SOL`, not-found & empty-vaults `UNAVAILABLE`
- `WebClientJupiterLendClientTest` — basis-point → percent decode
- `LendingReceiptLessActiveMarketSourceTest` — emits SUPPLY SOL + BORROW USDC from the live snapshot,
  latest-per-group, skips zero-qty legs
- `LendingActiveMarketDiscoveryServiceTest` — EVM receipt scan still emits Aave markets; receipt-less
  markets merge in and dedup by composite key
- `SessionLendingQueryServiceTest` — builder-path factual APY unchanged for single-deposit cycles
