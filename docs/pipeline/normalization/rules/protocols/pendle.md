# Pendle Protocol Rules

> **Status:** Verified correct (R5). No code change required. Classification and replay correctly
> handle Pendle/Equilibria LP positions as Option-B carry. See ADR-023.

## Scope

Pendle router bundles on MANTLE (Equilibria-wrapped), including LP-style lifecycle, reward/bundle
cases, and compounding-receipt swap-fee accrual.

## Verified behavior (R5 sign-off)

### Fee / compounding model

Pendle liquidity (SY/PT/YT market-making) accumulates swap fees **by compounding them into the
LP position value** — there is no separate `Collect` event analogous to Uniswap V3.  When the
position exits:

- The returned token quantity already includes accumulated swap fees embedded in LP value growth.
- **No fee-split is performed at exit.** The entire returned principal is carried at combined USD
  basis (Option-B carry), consistent with the definitive accounting model in the plan.
- Fee income realizes implicitly: the returned assets' AVCO will be lower than the entry basis,
  so later sale realizes the compounding gain.

This is intentionally **correct** — no code change is needed. Do not add a fee-split leg for
Pendle exits.

### Reward tokens (PENDLE, eqb, vlCVX, etc.)

Reward tokens claimed alongside a Pendle exit or as standalone `REWARD_CLAIM` are booked as
**zero-cost acquisitions** (`costBasisDeltaUsd = 0`). They realize income only when sold.

Verified: all PENDLE reward legs on MANTLE positions carry `isZeroCostAcquisition = true` in the
replay ledger.

### Correlation

Pendle positions use the `pendle-lp:<network>:<marketAddress>:<nfpmTokenId>` 3-segment-plus
correlation key (distinct from the canonical `lp-position:` key used by Uniswap/Balancer/LFJ
families). This is recognized by `PendleLpCorrelationSupport` and round-trips correctly through
`LpReceiptSymbolSupport`.

## Protocol-local resources

- `backend/core/src/main/resources/protocols/pendle.json` — selector + event-topic hints used by
  `PendleProtocolSemanticClassifier` and `LpExitFeeClarificationTrigger`.

## Lifecycle shapes

| Type | Trigger |
|---|---|
| `LP_ENTRY` | Pendle market `addLiquidity` / SY deposit |
| `LP_EXIT` | `removeLiquidity` — **no fee split** (compounding carry) |
| `LP_FEE_CLAIM` | Standalone `claimRewards` when only reward tokens move |
| `REWARD_CLAIM` | PENDLE/eqb/other reward tokens — zero-cost |

## Authoritative evidence

- `OnChainRawTransactionView`
- Persisted transfer evidence (ERC-20 movements)
- Persisted full receipt when the bundle lifecycle requires it (clarification trigger)

## Clarification rules

Clarification is allowed for Pendle zap and bundle routes where raw transfer evidence alone cannot
separate LP principal from reward tokens. Reward side-flows may remain economic (non-zero cost)
only if there is a matching entry position receipt.

## Disallowed fallbacks

- Do not collapse composite bundle rows into plain `REWARD_CLAIM`.
- Do not let marker churn remain synthetic `BUY` / `SELL`.
- Do not apply V3-style `DecreaseLiquidity`/`Collect` fee-split to Pendle exits — Pendle has no
  `Collect` event; applying the split would fabricate zero-cost income where none exists.

## Katana 36201 (cosmetic, deferred — D7)

The Katana SushiSwap V3 exit for tokenId 36201 has one leg classified as `TRANSFER` role where
`REALLOCATE_IN` would be semantically cleaner. Deferred: `REALLOCATE_IN` is not yet a value in
`NormalizedLegRole`; adding it requires downstream UI + replay changes out of R5 scope.

## Baseline and regression anchors

- MANTLE Pendle positions (3 exits + 5 entries): all have `pendle-lp:` correlation; no drained
  pool has non-zero residual after replay; PENDLE reward legs carry `costBasisDeltaUsd = 0`.
- Katana 36201 exit: `vbETH` basis $1,705.995 unchanged (reference case, no change from R5).
