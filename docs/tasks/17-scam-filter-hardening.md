# 17 — Scam Filter Hardening (Unsolicited Multicall Airdrops)

## T-045 — Harden scam filter against unsolicited multicall mass-airdrop patterns

- **Module(s):** `ingestion/filter`, `ingestion/config`
- **Description:**  
  Improve `ScamFilter` to reduce two critical risks:
  1. **False negatives** for unsolicited spam drops distributed via `multicall(bytes[])` with many embedded ERC20 `transfer(address,uint256)` subcalls.
  2. **False positives** from overly broad token-text heuristics (e.g., legitimate long bridged token names).

### Implementation scope

- Add a dedicated heuristic signal for unsolicited multicall mass-airdrop transactions:
  - wallet did **not** initiate tx (`wallet != tx.from`)
  - tx method/function indicates multicall (`methodId == 0xac9650d8` or function contains `multicall`)
  - inbound token transfer to wallet exists
  - tx input contains at least N occurrences of ERC20 transfer selector `a9059cbb`
- Add configuration knobs in `ScamFilterProperties` and `application.yml`:
  - `suspiciousMulticallAirdropScore`
  - `suspiciousMulticallAirdropMinTransferCalls`
- Narrow suspicious token text detection to URL/bait patterns; remove pure length-only trigger.

### Acceptance criteria

1. Unsolicited multicall tx with many embedded transfer subcalls is dropped.
2. Wallet-initiated multicall tx is not dropped by this heuristic.
3. Unsolicited inbound transfer with long but legitimate bridged token name is not dropped solely due name length.
4. Behavior remains deterministic (same raw tx -> same drop decision and signals).

### Tests

- `ScamFilterTest.unsolicitedMulticallMassAirdrop_returnsTrue`
- `ScamFilterTest.walletInitiatedMulticall_notScam`
- `ScamFilterTest.unsolicitedInboundLongLegitTokenName_notDropped`

### Notes

- This task only hardens ingestion-time dropping logic; it does not change normalized event taxonomy.
