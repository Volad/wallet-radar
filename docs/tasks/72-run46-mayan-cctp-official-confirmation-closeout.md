# Run 46 Mayan CCTP Official Confirmation Closeout

Status: Completed

Goal:

Close the remaining `run/46` accounting blocker by replacing the fragile
same-wallet `Mayan/CCTP` bridge guess with deterministic official confirmation
from the source transaction hash.

Related inputs:

- [run/46 audit report](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/46/clarification-readiness-audit.md)
- [run/46 audit summary](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/46/audit_summary.json)
- [run/46 blocking bridge pair](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/46/bridge_blocking_pair_focus.json)
- [Bridge family rules](../normalization/families/bridge.md)
- [Accounting policy](../03-accounting.md)

## Problem Statement

`run/46` narrowed the live blocker to one deterministic same-wallet
`Mayan/CCTP` route:

- source `0x4f00bba837f9de20e32e5abbefdd53cf0ec5a8b948eebd9a841d170a74506c98`
- destination `0x5a85c1ea1fd0de63ba890b27e6bc6b720c87562010df6572da9f55a04d9ea467`

The current runtime already knows both canonical legs:

- source method `swapAndStartBridgeTokensViaMayan(...)`
- destination method `redeemWithFee(...)`
- same tracked wallet
- same `USDC` family

But the pair still misses `correlationId / matchedCounterparty`, so replay
cannot carry move basis deterministically.

The missing piece is official source-driven confirmation. `Mayan/CCTP` routes
can settle with meaningful delay and fee-bearing quantity drift, so a short-gap
same-quantity matcher is not enough. The runtime should confirm the receiving
tx hash from Mayan's official source-tx status endpoint, then materialize the
pair explicitly.

## Scope

In scope:

- official `Mayan` status lookup by source tx hash
- persisted clarification evidence for confirmed `Mayan/CCTP` routes
- deterministic pairing from source `swapAndStartBridgeTokensViaMayan(...)`
  to destination `redeemWithFee(...)`
- support for delayed bridge settlement up to at least one hour
- accounting policy clarification that quantity delta on this route is bridge /
  settlement cost, not market realization
- rerun preparation

Out of scope:

- fuzzy matching without official source-tx confirmation
- generic cross-protocol bridge heuristics
- baseline refresh

## Acceptance Criteria (DoD)

1. The runtime can query Mayan's official source-tx status endpoint using the
   source tx hash and extract:
   - source tx hash
   - receiving / redeem tx hash
   - destination wallet address when present
   - completion status
   - fee-bearing quantity metadata when present

2. A source `BRIDGE_OUT` row may materialize a `Mayan/CCTP` pair only when all
   of the following hold:
   - source raw method proves `swapAndStartBridgeTokensViaMayan(...)`
   - official Mayan status is terminal / settled
   - official status returns receiving tx hash
   - destination canonical row exists as `BRIDGE_IN` or inbound-only
     `EXTERNAL_TRANSFER_IN`
   - destination row belongs to the official receiving tx hash
   - destination row belongs to the official destination wallet when present
   - source and destination asset family match under
     `BridgeAssetFamilySupport.continuityIdentity(...)`

3. When such a pair is proven:
   - both rows receive the same deterministic `correlationId`
   - both rows receive `matchedCounterparty`
   - `continuityCandidate = true` remains allowed for same-family carry even
     when destination quantity is lower than source quantity
   - destination may be promoted from `EXTERNAL_TRANSFER_IN` to `BRIDGE_IN`
     when it is inbound-only and route-proven

4. Existing safeguards remain intact:
   - no pairing without official Mayan confirmation
   - no self-link
   - no pairing to a destination row on the wrong tracked wallet
   - no synthetic `BUY` / `SELL` introduction

5. Replay contract remains explicit:
   - source-minus-destination stable-asset delta on a confirmed `Mayan/CCTP`
     route is treated as bridge / settlement cost embedded into the carried
     basis, not as synthetic sale realization

6. After rerun preparation:
   - raw backfill evidence remains intact
   - derived collections are cleared
   - raw normalization state is reset to pending

## Edge Cases

- settlement completes tens of minutes after source tx
- official Mayan status is still non-terminal
- official receiving tx hash exists, but the destination row belongs outside
  the tracked wallet universe
- destination row already exists as `BRIDGE_IN`
- destination row still exists as audited inbound-only `EXTERNAL_TRANSFER_IN`
- quantity drift is meaningfully larger than typical `Across`-style bridge
  drift but still explained by documented fee-bearing settlement

## Task Breakdown

1. `BE-R46-01` Add official `Mayan` source-tx status gateway
2. `BE-R46-02` Add deterministic `Mayan/CCTP` pair linker
3. `BE-R46-03` Wire the linker into lifecycle linking and clarification
   post-processing
4. `BE-R46-04` Lock regression coverage for:
   - official source confirmation pairing
   - seeded source linking when destination already exists
   - non-terminal / mismatched destination rejection
5. `BE-R46-05` Update bridge/accounting docs for fee-bearing `Mayan/CCTP`
   settlement
6. `BE-R46-06` Prepare rerun
   - clear derived state
   - reset raw normalization statuses
   - keep valid raw backfill evidence intact

## Risk Notes

- The fix should prefer official Mayan confirmation over heuristic quantity
  matching.
- This task intentionally closes the current audited `Mayan/CCTP` continuity
  lane and does not widen generic bridge matching across all protocols.

## Completion Notes

- Added official Mayan source-tx status lookup through
  `https://explorer-api.mayan.finance/v3/swap/trx/{txHash}`.
- Persisted provider-specific clarification evidence under `mayanStatus`
  instead of overloading the existing LI.FI status slot.
- Materialized deterministic `bridge:mayan:{sourceTxHash}` correlation for
  official `swapAndStartBridgeTokensViaMayan(...) -> redeemWithFee(...)`
  settlement pairs.
- Confirmed that replay already carries same-family move basis correctly when
  the pair is linked, even with fee-bearing quantity delta.
