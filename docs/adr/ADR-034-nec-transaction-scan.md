# ADR-034 — NEC computation via direct transaction scan

**Status:** Accepted  
**Date:** 2026-06-23  
**Supersedes:** [ADR-014](ADR-014-portfolio-conservation-gate.md) §D2 (pool-delta NEC formula)

---

## Context

[ADR-014 §D2](ADR-014-portfolio-conservation-gate.md) defined NEC as:

```
nec = Σ_{c : isMember(c)=false}
        (pool[c].lifetimeInBasisUsd − pool[c].lifetimeOutBasisUsd)
```

That is, NEC was derived from `counterparty_basis_pools` — summing the lifetime delta of every non-member counterparty pool.

During Cycle/11 S3 this approach was found to be systematically inaccurate for a multi-wallet EVM + CEX session:

1. **LP exits inflate NEC.** A `BRIDGE_IN` from a Katana LP pool pays back the user's own previously-deposited capital. The pool's `lifetimeInBasisUsd` grows, signalling capital received from the outside — but the corresponding `BRIDGE_OUT` depositing into the LP belongs to a different counterparty (the LP contract on the originating chain). Net pool delta is non-zero and inflates NEC, even though no real external capital crossed the boundary.

2. **Bridge relay solvers double-count.** A USDC bridge sends a `BRIDGE_OUT` of $100 to Relay solver A, then receives a `BRIDGE_IN` of $99.85 from Relay solver B (different address, same economic corridor). Pool A's out delta is −$100; pool B's in delta is +$99.85. Pool-level NEC sees both as separate non-member counterparties and double-counts ~$200 in both directions — but only when the solvers are not in the accounting universe.

3. **DApp reward distributions inflate NEC.** GMX V2 fee/profit `EXTERNAL_TRANSFER_IN` has no counterparty address and carries a protocol name. The pool for `UNKNOWN_COUNTERPARTY` accumulates these as external capital; in reality they are protocol-originated PnL settlements.

4. **Scam/fake-native tokens.** Phishing contracts airdrop fake ERC-20 tokens named "ETH" or "WETH". Their on-chain transfers produce `EXTERNAL_TRANSFER_OUT` (phishing drain appears as an outflow) or `EXTERNAL_TRANSFER_IN` (airdrop appears as inflow). Pool deltas accumulate these at face value from the pricing engine.

The pool-delta approach lacks the per-flow metadata needed to identify and exclude these classes of transactions after the fact.

---

## Decision

### D1. Replace pool-delta NEC with a two-pass direct transaction scan

NEC is computed by scanning `normalized_transactions` directly at dashboard GET time, applying a set of guards per flow. `counterparty_basis_pools` continues to be used for **MtM** computation of non-backfillEnabled members and for breach diagnostics.

### D2. Pass 1 — Bybit FUND stablecoin scan

Scan `EXTERNAL_TRANSFER_IN` / `EXTERNAL_TRANSFER_OUT` on wallets matching `^BYBIT:[^:]+:FUND$`.

Guards:
- **RC1** — inflows must be stablecoin-denominated (`normalizeStablecoinSymbol` → `STABLECOIN_SYMBOLS`). Crypto deposits to Bybit FUND (MNT, SOL, …) are crypto-to-crypto movements.
- **RC-fund-dust** — inflows < $5 USD are excluded.
- **isNonUniverseCounterparty** — both directions: skip flows whose counterparty is a universe member (including `BYBIT:<uid>:FUND/UTA/EARN` sub-accounts resolved to root `BYBIT:<uid>`).

### D3. Pass 2 — EVM wallet on-chain scan

Scan `EXTERNAL_TRANSFER_IN`, `EXTERNAL_TRANSFER_OUT`, `BRIDGE_IN`, `BRIDGE_OUT` for all EVM members with `backfillEnabled = true`.

#### D3a. Pre-pass A — pairedCorrelationIds

Build the set of `correlationId` values present in both a `BRIDGE_IN` and a `BRIDGE_OUT` within the universe. These represent fully-tracked intra-universe corridors; both legs are excluded from NEC (carry semantics own cost basis for these corridors).

#### D3b. Pre-pass B — buildCorridorPairedHashes

Build a **symmetric** txHash set covering both legs of bridge corridors identified by amount+time proximity. Symmetry (adding both legs) is required to keep `NEC = inflow − outflow` stable: removing only the inbound from inflow without also removing the outbound from outflow would erroneously increase NEC.

**Pattern 1 (RC3a)** — Relay/solver payout corridors.  
For each `BRIDGE_IN` / `EXT_IN` whose counterparty is in `KNOWN_BRIDGE_PAYOUT_ADDRESSES`: match a `BRIDGE_OUT` / `EXT_OUT` within ±4 h and ±1.5% USD. Add both hashes.

**Pattern 2 (RC2b)** — Bridge return / cancellation.  
For each `EXT_IN` whose counterparty matches the counterparty of a same-wallet `BRIDGE_OUT` within ±48 h and ±1.5% USD: this is a bridge refund. Add both hashes.

#### D3c. Per-transaction guards (applied in order)

1. **excludedFromAccounting = true** — skip unconditionally.
2. **pairedCorrelationIds** — skip BRIDGE_IN / BRIDGE_OUT whose `correlationId` belongs to a fully-tracked intra-universe corridor.
3. **corridorPairedHashes (RC2b + RC3a)** — skip both legs of amount+time-matched corridors.
4. **RC-direct** — any inbound from `KNOWN_BRIDGE_PAYOUT_ADDRESSES` is always a bridge receipt; skip.
5. **RC-dapp-reward** — `EXT_IN` with no counterparty address but a non-blank `protocolName` is a protocol reward or PnL settlement; skip.
6. **RC-evm-ext-asset** — for `EXT_IN` with a known symbol: only stablecoin or ETH-family passes. Excludes airdropped reward tokens, LP receipt tokens, and arbitrary ERC-20s. `BRIDGE_IN` is exempt (bridges may carry any asset as real capital).
7. **MULTI guard** — `BRIDGE_IN` whose flow-level counterparty is `"MULTI"` is ambiguous (multi-sender router); skip.
8. **RC-fake-native** — ETH/WETH-family flow with a non-null `assetContract` not in the ETH-family allowlist (`NetworkRegistry.ethFamilyEquivalentContracts()`, config-derived — see W11 amendment) is a scam/airdrop fake token; skip. Applies to both inbound and outbound to keep NEC balanced.
9. **RC3b** — inbound flow whose flow-level counterparty is in `KNOWN_LP_POOL_ADDRESSES` is an LP exit receipt; skip.
10. **isNonUniverseCounterparty** — flow whose resolved counterparty is a universe member is an internal transfer; skip.
11. **RC-evm-dust** — inbound USD value < $2 is excluded (bridge gas refunds, fractional payouts). Outflows are not filtered.

### D4. `pricedFlowValueUsd` — USD resolution chain

USD value is derived per flow in this priority order:
1. `flow.valueUsd` (non-zero).
2. `flow.unitPriceUsd × |quantityDelta|`.
3. Stablecoin identity (`|quantityDelta|` = USD, via `normalizeStablecoinSymbol`).
4. Cross-flow ETH-family inference (sibling flow in the same transaction with a derivable unit price).
5. Historical price cache (`ETH`/`WETH` from `BINANCE → BYBIT → COINGECKO` at `blockTimestamp`).

If no source yields a non-zero USD value, the flow is not counted.

### D5. Guard constant registries

| Constant | Type | Purpose |
|----------|------|---------|
| `KNOWN_BRIDGE_PAYOUT_ADDRESSES` | `Set<String>` | RC-direct + Pattern 1 inbound guard. Known Relay/LiFi solvers, Across SpokePool, etc. |
| `RELAY_SOURCE_ADDRESSES` | `Set<String>` | Pattern 1 outbound hint (legacy; now all EXT_OUT / BRIDGE_OUT are eligible outbound candidates). |
| `KNOWN_LP_POOL_ADDRESSES` | `Set<String>` | RC3b. Katana LP pool + vault contracts paying back LP exits. |
| ETH-family allowlist (`NetworkRegistry.ethFamilyEquivalentContracts()`) | `Set<String>` | RC-fake-native. Canonical WETH ERC-20 contracts per chain + native ETH chain proxies (zkSync Era `0x000...800a`). **Config-derived from `network-descriptors.yml` (W11)** — no longer a hardcoded constant. |
| `STABLECOIN_SYMBOLS` | `Set<String>` | RC1, RC-evm-ext-asset. Canonical stable denominations after `normalizeStablecoinSymbol`. |
| `ETH_FAMILY_SYMBOLS` | `Set<String>` | RC-fake-native, RC-evm-ext-asset. ETH and liquid-staking derivatives. |
| `MIN_FUND_FLOW_USD` | `$5` | RC-fund-dust. |
| `MIN_EVM_FLOW_USD` | `$2` | RC-evm-dust. |
| `BRIDGE_PAIR_WINDOW_MINUTES` | `240` min | Pattern 1 time window (±4 h). |
| `BRIDGE_RETURN_WINDOW_HOURS` | `48` h | Pattern 2 time window. |
| `BRIDGE_PAIR_AMOUNT_TOLERANCE` | `1.5%` | Amount matching tolerance for both patterns. |

---

## Consequences

### Positive

- All guard logic is co-located in `PortfolioConservationGate` and directly inspects flow-level metadata that pools do not expose.
- LP exits, bridge relay corridors, DApp rewards, and fake-native tokens are correctly excluded regardless of how the cost-basis pools classify them.
- Adding a new bridge solver or LP pool requires only a one-line addition to the relevant constant set — no replay required.

### Negative

- Guard constants (`KNOWN_BRIDGE_PAYOUT_ADDRESSES`, `KNOWN_LP_POOL_ADDRESSES`) require manual maintenance as new protocols are encountered. The ETH-family allowlist was later externalized to config (W11): it is derived from `network-descriptors.yml` via `NetworkRegistry.ethFamilyEquivalentContracts()`, so it cannot drift from the per-network descriptors.
- The scan is O(N) over EVM capital-flow transactions. For sessions with thousands of bridge transactions this may add measurable latency. Mitigated by field projection (only the required fields are loaded) and by the fact that dashboard reads are already relatively heavyweight.
- A new type of bridge corridor (e.g. a cross-chain protocol that uses a pattern not covered by Pattern 1 or 2) may temporarily inflate NEC until a guard is added.

### Migration from ADR-014 §D2

ADR-014 §D2 pool-delta formula is replaced in `PortfolioConservationGate.evaluate()` by the two-pass scan described above. `counterparty_basis_pools` is retained for:
- MtM contribution of non-backfillEnabled members (ADR-014 §D3).
- Breach diagnostic logging (`topNonMemberPoolsByNetCapitalDelta`, `topMemberPoolsByQtyHeld`).

No schema migration required. No re-replay required.

---

## Amendment — W11: ETH-family allowlist externalized to config (2026-07-17)

**Status:** Accepted (implemented)

The RC-fake-native guard's `KNOWN_WETH_CONTRACTS` static constant was replaced by a config-derived
set, `NetworkRegistry.ethFamilyEquivalentContracts()`, sourced from `network-descriptors.yml`
(consolidation Wave W11):

- For each network whose `native-symbol == ETH`: its `wrapped-native` contract + `native-alias-contracts`.
- Plus, for any network, a new optional `eth-family-contracts` field holding bridged WETH on non-ETH
  chains (Mantle precompile `0xdead…1111`, Avalanche WETH.e `0x49d5…`).
- Non-ETH-native wrapped-natives (WBNB/WMATIC/WAVAX/WMNT/WXPL) are never included, so a fake
  "WETH"/"ETH" flow reusing a non-ETH wrapper contract still fails the guard.

This closed a latent drift: Katana WETH (`0xee7d8bcf…`) was present in the descriptors but missing
from the hardcoded list, and a dead Cronos zkEVM entry (`0x2def…`, unsupported network) was dropped.
`PortfolioConservationGate` injects `NetworkRegistry` and builds the allowlist at construction; the
RC-fake-native logic is otherwise unchanged. Verified NEC/AVCO-invariant on the live dataset
(`asset_ledger_points=11312`, ETH/BTC terminal AVCO unchanged, NEC delta $0). See the
[consolidation inventory §4j](../tasks/hardcoded-registry-consolidation-proposal.md#4j-w11--implemented-this-change).

---

## References

- [ADR-014](ADR-014-portfolio-conservation-gate.md) — gate design, MtM formula, threshold, response fields.
- [ADR-009](ADR-009-ownership-classification-via-universe.md) — `AccountingUniverse` membership semantics.
- [ADR-015](ADR-015-per-counterparty-basis-pool.md) — per-counterparty pool semantics (used for MtM, not NEC).
- [Conservation gate doc](../pipeline/portfolio-snapshot/03-conservation-gate.md) — full guard reference.
- `backend/src/main/java/com/walletradar/ingestion/wallet/query/PortfolioConservationGate.java`
