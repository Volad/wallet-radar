# Normalization Examples

Synthetic walkthroughs for on-chain classification. See [normalization rules](../pipeline/normalization/rules/README.md).

---

## Example 1: Simple ETH → USDC swap

**Tx:** `0xEXAMPLE_TX_1` on ETHEREUM, wallet `0xWALLET_A` calls `0xROUTER_UNISWAP`.

| Step | Output |
|------|--------|
| Raw | `raw_transactions` row, `normalizationStatus=PENDING` |
| Classification | `SwapClassifier` → type `SWAP` |
| Flows | SELL 1.0 ETH, BUY 3200 USDC |
| Status | `PENDING_PRICE` (no clarification needed) |

---

## Example 2: Bridge OUT (LI.FI)

**Tx:** `0xEXAMPLE_TX_2`, wallet sends USDC via LI.FI diamond.

| Step | Output |
|------|--------|
| Classification | `BridgeStartClassifier` + `LiFiRouteSupport` |
| Type | `BRIDGE_OUT` |
| Flows | TRANSFER 5000 USDC outbound |
| Metadata | Route tag in calldata |

Downstream: [linking example](linking-examples.md#bridge-out-in-pairing).

---

## Example 3: LP entry (Uniswap V3 NFT)

**Tx:** `0xEXAMPLE_TX_3`, `multicall` on position manager.

| Step | Output |
|------|--------|
| Classification | `LpClassifier` with full receipt |
| Type | `LP_ENTRY` |
| Flows | SELL 0.5 ETH + 1500 USDC, BUY position NFT |
| Clarification | None if receipt proves mint |

---

## Example 4: Aave deposit

**Tx:** `0xEXAMPLE_TX_4`, Pool `supply()`.

| Step | Output |
|------|--------|
| Classification | `AaveReceiptShapeClassifier` or `LendingRegistryClassifier` |
| Type | `LENDING_DEPOSIT` |
| Flows | TRANSFER 10 WETH out, BUY 10 aWETH receipt |

---

## Example 5: Spam inbound

**Tx:** `0xEXAMPLE_TX_5`, unsolicited dust token.

| Step | Output |
|------|--------|
| Classification | `SpamClassifier` |
| Type | Tagged spam / excluded |
| Accounting | `excludedFromAccounting=true` — no replay |

---

## Example 6: Sponsored gas top-up

**Tx:** `0xEXAMPLE_TX_6`, protocol sends native ETH for gas.

| Step | Output |
|------|--------|
| Classification | `SponsoredGasTopUpSupport` |
| Type | `SPONSORED_GAS_IN` |
| Replay | `GAS_ONLY` basis effect |

## Related

- [Transaction types: SWAP](../reference/transaction-types.md#swap)
- [LP family](../pipeline/normalization/rules/families/lp.md)
