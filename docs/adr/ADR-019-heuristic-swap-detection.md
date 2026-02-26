# ADR-019: Heuristic swap detection (outflow A + inflow B in same tx)

**Date:** 2026-02  
**Status:** Accepted  
**Deciders:** System Architect, Business Analyst

---

## Context

- Swap classification today relies on **hardcoded DEX Swap event topics** (Uniswap V2, Uniswap V3, Velora-style V3 with fees). Only when one of these topics is present do we classify token Transfer logs as SWAP_SELL / SWAP_BUY and apply inline stablecoin-leg pricing (ADR-018).
- Every new DEX, aggregator, or pool with a different Swap event signature requires a code change. This does not scale and causes real swaps to be stored as EXTERNAL_TRANSFER_OUT / EXTERNAL_INBOUND (e.g. Velora before adding `0x19b47279...`).
- Domain and accounting treat a swap as “wallet gave asset A and received asset B”; the execution price (SWAP_DERIVED) is preferred for cost basis (01-domain, 03-accounting, ADR-018). The economic meaning does not depend on which contract emitted a Swap log.

---

## Decision

1. **Heuristic as primary swap signal:** For a given wallet and transaction, treat the tx as a **swap** if it contains:
   - at least one **outflow** (ERC20 Transfer where `from` = wallet) of asset A, and  
   - at least one **inflow** (ERC20 Transfer where `to` = wallet) of asset B,  
   with **A ≠ B** (different `assetContract`).
   When this holds, classify those transfers as **SWAP_SELL** (outflows) and **SWAP_BUY** (inflows) instead of EXTERNAL_TRANSFER_OUT / EXTERNAL_INBOUND. Inline swap price (ADR-018) continues to apply when exactly one leg is a stablecoin.

2. **Strict “one pair” rule to limit false positives:** Apply the heuristic only when, for the wallet in that tx, there is **exactly one distinct asset in outflows** and **exactly one distinct asset in inflows**. If the wallet has multiple outflow assets and/or multiple inflow assets (e.g. two different tokens out, one in), **do not** classify as swap; leave as EXTERNAL_* (or handle via existing topic-based path if a known Swap topic is present). Same asset out and same asset in (A = B) does **not** count as swap.

3. **Topic-based detection as secondary:** Keep detection of known Swap event topics (Uniswap V2, V3, Velora, etc.) for:
   - optional labelling (e.g. `protocolName`, “DEX swap”);
   - backward compatibility and explicit DEX signal.
   Do **not** require a Swap topic for SWAP_SELL / SWAP_BUY classification when the heuristic (1) and (2) are satisfied.

4. **Lending / borrow / collateral:** If a **lending (or other protocol) classifier** has already classified the tx (e.g. BORROW, REPAY, LEND_DEPOSIT), **do not** reclassify the same transfers as swap based on the heuristic. Protocol classification takes precedence. Document that “outflow A + inflow B” may be lending/collateral; heuristic applies only when no such classifier fires.

5. **Multi-hop:** When the heuristic yields **more than one** SWAP_SELL or more than one SWAP_BUY in the same tx (e.g. USDC→ETH→WBTC), we still classify those legs as SWAP_SELL / SWAP_BUY. **InlineSwapPriceEnricher** (ADR-018) already skips inline price when there is not exactly one sell and one buy per tx. Document that multi-hop swaps get no inline stablecoin price in v1; remaining events stay PRICE_PENDING or are resolved in Phase 2.

6. **P2P / OTC:** Atomic P2P or OTC swaps in one tx (wallet sends A, receives B) are **in scope**: classify as SWAP_SELL / SWAP_BUY with execution price (SWAP_DERIVED). Document in 01-domain or this ADR.

---

## Consequences

### Positive

- **No dependency on specific DEX/pool event signatures** for swap classification; works for any aggregator, private pool, and future protocols without code changes.
- **Fewer false negatives:** Real swaps that do not emit a known Swap topic are still classified as swap and can get inline price when one leg is stablecoin.
- **Alignment with domain:** “Outflow A + inflow B” matches the economic definition of a swap for cost basis and PnL; SWAP_DERIVED remains the preferred source.

### Trade-offs

- **Risk of false positives** (e.g. repay + collateral in same tx, or multi-send + one receive) if the “exactly one asset out, exactly one asset in” rule is not enforced or lending classifiers are not run first. Mitigated by strict one-pair rule and by giving protocol classifiers precedence.
- **Multi-hop:** Multiple SWAP_* events per tx; inline price only for the single-pair case; multi-hop remains PRICE_PENDING or Phase 2.

### Risks

- **Lending/repay/collateral** flows can look like “one outflow, one inflow”; must not classify as swap when a lending (or similar) classifier has already run. Document and enforce classifier order.
- **Multi-send + one receive** (e.g. send USDC to A and to B, receive ETH from C): with strict one-pair rule we require exactly one distinct asset in outflows; two sends of the same asset could be aggregated as “one asset out” — clarify in implementation whether we aggregate by asset or require exactly one Transfer out and one Transfer in. Recommendation: one distinct asset out, one distinct asset in; if multiple Transfer logs for the same asset, aggregate quantities for that asset.

---

## References

- **ADR-018** — Inline swap price (stablecoin leg); InlineSwapPriceEnricher; DeferredPriceResolutionJob skip.
- **docs/01-domain.md** — Price Sources (STABLECOIN, SWAP_DERIVED); event types SWAP_SELL, SWAP_BUY.
- **docs/03-accounting.md** — Cost basis and SWAP_DERIVED.
- **docs/tasks/12-heuristic-swap-detection.md** — Implementation task T-032.
