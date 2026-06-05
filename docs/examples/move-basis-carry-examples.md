# Move Basis Carry Examples

CARRY_OUT → CARRY_IN continuity. See [basis pools](../pipeline/cost-basis/03-basis-pools-and-carry.md) and [ADR-019](../adr/ADR-019-corridor-carry-policy.md).

---

## Example 1: Cross-wallet transfer (same universe)

| Wallet | Tx | Effect | qty | basis |
|--------|-----|--------|-----|-------|
| A | `0xEXAMPLE_TX_8` OUT | CARRY_OUT | −5 ETH | −$10000 |
| B | `0xEXAMPLE_TX_8` IN | CARRY_IN | +5 ETH | +$10000 |

**Move-basis UI:** Both appear in ETH family timeline; preset `basisMoves` shows carry pair. AVCO unchanged across transfer.

---

## Example 2: Bridge corridor (linked)

| Chain | Type | Effect |
|-------|------|--------|
| ETHEREUM | BRIDGE_OUT | CARRY_OUT 5000 USDC |
| ARBITRUM | BRIDGE_IN | CARRY_IN 5000 USDC |

Outbound AVCO preserved per ADR-019 corridor carry policy.

---

## Example 3: Per-counterparty pool REALLOCATE

External sender `0xEXTERNAL_CEX` → wallet `0xWALLET_A` (non-universe counterparty).

| Step | Effect |
|------|--------|
| Inbound | REALLOCATE_IN from counterparty pool (not fresh ACQUIRE at market) |
| Pool | `counterparty_basis_pools` qtyHeld updated |

## Related

- [Move basis page](../frontend/move-basis.md)
- [Linking examples](linking-examples.md)
