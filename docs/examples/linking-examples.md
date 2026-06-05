# Linking Examples

Synthetic pairing and continuity repairs. See [linking rules](../pipeline/linking/02-rules-and-repairs.md).

---

## Bridge OUT ↔ IN pairing {#bridge-out-in-pairing}

| Tx | Type | Network | correlationId |
|----|------|---------|---------------|
| `0xEXAMPLE_TX_2` | `BRIDGE_OUT` | ETHEREUM | `bridge:0xEXAMPLE_TX_2` |
| `0xEXAMPLE_TX_7` | `BRIDGE_IN` | ARBITRUM | `bridge:0xEXAMPLE_TX_2` |

**Linking steps:**
1. `BybitBridgeLinkService` / bridge pairers match OUT→IN by bridge protocol metadata
2. Set `matchedCounterparty`, `continuityCandidate=true` on IN row
3. `BridgePairContinuityRepairService` fixes IN rows still marked non-continuity

**Replay effect:** IN receives `CARRY_IN` (not `ACQUIRE`) — see [move basis carry](move-basis-carry-examples.md).

---

## Internal wallet ↔ wallet transfer

| Tx | From | To | Type |
|----|------|-----|------|
| `0xEXAMPLE_TX_8` | `0xWALLET_A` | `0xWALLET_B` | `INTERNAL_TRANSFER` |

Both wallets in same accounting universe.

**Linking:** reciprocal internal-transfer pair promotion → `continuityCandidate=true`, shared `correlationId`.

**Replay:** Wallet A `CARRY_OUT`, Wallet B `CARRY_IN` — AVCO preserved.

---

## Bybit deposit ↔ on-chain withdrawal

| Source | Type | Asset |
|--------|------|-------|
| Bybit normalized row | `EXTERNAL_TRANSFER_OUT` | 1.0 ETH |
| On-chain `0xEXAMPLE_TX_9` | `EXTERNAL_TRANSFER_IN` | 1.0 ETH |

**Linking:** `BybitBridgeLinkService` correlates by amount/time/txHash hints (ADR-013).

**Replay:** Counterparty basis pool or continuity carry depending on universe membership.

## Related

- [Bridge family](../pipeline/normalization/rules/families/bridge.md)
- [Transfer family](../pipeline/normalization/rules/families/transfer.md)
