# On-Chain Run 17 Routed Swap Closeout

Goal:

Close the `run/17` active-lane blocker where routed on-chain rows remained
priceable `SWAP` despite lacking a wallet-visible `BUY` leg.

## Scope

In scope:
- classify explicit bridge-start selectors
  `swapAndStartBridgeTokensViaMayan(...)`,
  `swapAndStartBridgeTokensViaStargate(...)`, and
  `swapAndStartBridgeTokensViaSquid(...)` as `BRIDGE_OUT`
- demote outbound-only aggregator/router rows without a wallet-visible `BUY`
  leg out of the active `SWAP` lane
- enforce a pre-pricing invariant that canonical `SWAP` rows must have at least
  one non-fee `BUY` leg and at least one non-fee `SELL` leg
- rerun normalization/clarification safely after the classifier slice

Out of scope:
- changing Bybit normalization rules
- changing clarification evidence contracts
- inventing new paid data sources or new bridge backfill
- redefining AVCO formulas

## Acceptance Criteria (DoD)

1. Source-chain bridge-start calls with function names:
   - `swapAndStartBridgeTokensViaMayan(...)`
   - `swapAndStartBridgeTokensViaStargate(...)`
   - `swapAndStartBridgeTokensViaSquid(...)`
   persist as `type = BRIDGE_OUT` when canonical wallet-visible movement is
   source-side outbound.
2. Known aggregator/router rows may remain `type = SWAP` only when canonical
   wallet-boundary flows contain:
   - at least one non-fee `SELL`
   - at least one non-fee `BUY`
3. Known aggregator/router rows with outbound-only wallet-visible movement and
   no wallet-visible `BUY` leg persist as `EXTERNAL_TRANSFER_OUT`, not `SWAP`,
   unless a higher-priority bridge-start rule already proves `BRIDGE_OUT`.
4. If a candidate `SWAP` still has malformed wallet-boundary shape and no
   deterministic transfer/bridge demotion applies, it leaves the active lane
   before pricing and does not remain `PENDING_PRICE`.
5. A rerun after this slice produces `run/17` successor audit with:
   - `ON_CHAIN SWAP missing BUY = 0`
   - zero active `SWAP` rows missing either wallet-visible side leg
6. Mongo rerun prep resets only derived pipeline state and processing status; it
   does not delete raw on-chain evidence, clarification evidence, or Bybit raw
   evidence.

## Edge Cases

- Case: known bridge-start selector has same-tx fee and multiple outbound token
  legs but no wallet-visible inbound leg.
  - Scope: In
  - Expected: `BRIDGE_OUT`, not `SWAP`.

- Case: known aggregator router has outbound-only movement because the bought
  asset is delivered to another address, not to the tracked wallet.
  - Scope: In
  - Expected: `EXTERNAL_TRANSFER_OUT`, not `SWAP`.

- Case: known aggregator router returns a bought asset to the same tracked
  wallet.
  - Scope: In
  - Expected: row may remain `SWAP` when both wallet-boundary legs exist.

- Case: blank top-level `methodId` but recoverable selector / function evidence
  still proves bridge-start semantics.
  - Scope: In
  - Expected: bridge classifier still wins before generic swap fallback.

- Case: malformed `SWAP` candidate on an unsupported router family.
  - Scope: In
  - Expected: row leaves the active pricing lane as explicit review rather than
    staying silent `PENDING_PRICE`.

## Task Breakdown

1. `BE-07T` routed bridge-start selector closeout
   - classify Mayan / Stargate / Squid `swapAndStartBridgeTokensVia*` source
     legs as `BRIDGE_OUT`

2. `BE-07U` outbound-only aggregator routed-send demotion closeout
   - known aggregator/router rows without wallet-visible `BUY` leg demote to
     `EXTERNAL_TRANSFER_OUT`

3. `BE-07V` active-lane swap-shape invariant closeout
   - no `SWAP` may remain priceable without both wallet-visible side legs
   - unsupported malformed cases leave the active lane before pricing

4. `BE-07W` rerun pack
   - reset derived collections and processing state only
   - preserve raw evidence and clarification evidence

## Risk Notes

- The main risk is over-demotion: bridge-start selectors must still win before
  generic aggregator demotion.
- The invariant must inspect wallet-boundary flows, not router internals, or it
  will recreate the same silent-leak pattern under a different type.
- Mongo reset must not destroy top-level `clarificationEvidence` or imported
  `external_ledger_raw` evidence.
