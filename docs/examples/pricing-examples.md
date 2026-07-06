# Pricing Examples

Synthetic USD resolution paths. See [pricing resolver chain](../pipeline/pricing/02-resolver-chain.md).

---

## Example 1: Event-local execution price (swap)

**Tx:** `0xEXAMPLE_TX_1` type `SWAP`, flows SELL 1 ETH / BUY 3200 USDC.

| Flow | Resolver | Result |
|------|----------|--------|
| ETH (sell) | `SwapDerivedPriceResolver` | $3200 from paired leg |
| USDC (buy) | `StablecoinPriceResolver` | $1.00 |

Status → `PENDING_STAT`.

---

## Example 2: External historical quote

**Tx:** `0xEXAMPLE_TX_10` type `REWARD_CLAIM`, obscure token `EXAMPLE`.

| Flow | Resolver | Result |
|------|----------|--------|
| EXAMPLE | Event-local chain → miss | |
| EXAMPLE | `PriceExternalSourceOrchestrator` → Binance/CoinGecko | $0.42 at block time |
| Cache | `historical_prices` | Upserted |

---

## Example 3: Stablecoin $1 fallback on replay

**Tx:** priced with `priceSource=UNKNOWN` for USDC inbound.

| Stage | Behavior |
|-------|----------|
| Pricing | May stay unresolved |
| Replay | `GenericFlowReplayEngine.applyBuy` applies USD stable $1 fallback for BUY |

---

## Example 4: PRICING_SKIPPED

**Tx:** `0xEXAMPLE_TX_6` type `SPONSORED_GAS_IN`.

| Flow | Stamp |
|------|-------|
| Native gas qty | `PRICING_SKIPPED` — qty only at replay |

## Related

- [ADR-021 swap multi-sell](../adr/ADR-021-swap-multi-sell-price-derivation-and-lp-harvest-gate.md)
