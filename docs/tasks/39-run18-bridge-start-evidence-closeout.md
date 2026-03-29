# Run 18 Bridge-Start Evidence Closeout

Goal:

Close the `run/18` active-lane blocker where explicit source-chain bridge-start
rows still need persisted receipt evidence for later bridge-pair reconstruction,
while outbound-only routed aggregator rows must stay owner-agnostic
`EXTERNAL_TRANSFER_OUT`.

## Scope

In scope:
- keep explicit source-side selectors
  `swapAndStartBridgeTokensViaMayan(...)`,
  `swapAndStartBridgeTokensViaStargate(...)`, and
  `swapAndStartBridgeTokensViaSquid(...)` in `BRIDGE_OUT`
- mark those explicit bridge-start rows as requiring bounded full-receipt
  evidence when no persisted receipt evidence is available yet
- allow the full-receipt clarification worker to process that `BRIDGE_OUT`
  family even when the row is already `PENDING_PRICE`
- persist receipt logs / transfer evidence through the existing clarification
  contract so later bridge-pair reconstruction reads only from the raw view
- keep outbound-only 1inch / generic aggregator sends in
  `EXTERNAL_TRANSFER_OUT` unless protocol-specific bridge evidence exists

Out of scope:
- destination-side bridge pairing heuristics based only on timestamp or amount
- redefining owner-agnostic transfer taxonomy
- introducing new paid bridge indexers or new backfill providers
- changing AVCO formulas or replay ordering

## Acceptance Criteria (DoD)

1. Explicit source-side bridge-start rows with:
   - `swapAndStartBridgeTokensViaMayan(...)`
   - `swapAndStartBridgeTokensViaStargate(...)`
   - `swapAndStartBridgeTokensViaSquid(...)`
   persist as `type = BRIDGE_OUT` when wallet-visible movement is source-side
   outbound.
2. If such a row has no persisted full-receipt clarification evidence yet, the
   canonical row carries explicit missing-data reason
   `BRIDGE_PAIR_EVIDENCE_REQUIRED`.
3. Full-receipt clarification may load those rows while they are
   `status = PENDING_PRICE`, fetch full receipt evidence, persist it into raw
   clarification evidence, and rebuild the normalized row without changing the
   canonical `BRIDGE_OUT` type.
4. Once persisted receipt evidence exists, the rebuilt canonical row no longer
   carries `BRIDGE_PAIR_EVIDENCE_REQUIRED`.
5. Outbound-only aggregator/router rows with no wallet-visible `BUY` leg remain
   `EXTERNAL_TRANSFER_OUT`, including 1inch-style routed sends, unless a
   higher-priority bridge rule already proves `BRIDGE_OUT`.
6. No classifier rule may promote a routed outbound-only row to `BRIDGE_OUT`
   from timing-only or wallet-only pairing heuristics.
7. Mongo rerun prep resets only derived pipeline state and processing status; it
   does not delete raw on-chain evidence, clarification evidence, or Bybit raw
   evidence.

## Edge Cases

- Case: explicit bridge-start source row bridges `USDe` into `USD₮0`.
  - Scope: In
  - Expected: source row remains `BRIDGE_OUT`, but docs and later replay logic
    do not treat the future pair as automatic same-asset move-basis continuity.

- Case: explicit bridge-start source row bridges `USDT` into `USDC`.
  - Scope: In
  - Expected: same as above; `BRIDGE_OUT` is correct, plain basis carry is not
    implied by type alone.

- Case: 1inch outbound-only routed send is followed by a same-wallet inbound on
  another network in the same hour.
  - Scope: In
  - Expected: without protocol-specific bridge evidence, the source row remains
    `EXTERNAL_TRANSFER_OUT`.

- Case: explicit bridge-start row already has persisted clarification evidence.
  - Scope: In
  - Expected: no repeat bridge-evidence reason remains after reclassification.

## Task Breakdown

1. `BE-07X` explicit bridge-start semantic lock
   - keep explicit Mayan / Stargate / Squid source selectors in `BRIDGE_OUT`
   - attach `BRIDGE_PAIR_EVIDENCE_REQUIRED` only when persisted receipt
     evidence is still absent

2. `BE-07Y` bridge-start full-receipt evidence closeout
   - let the full-receipt clarification worker pull
     `PENDING_PRICE + BRIDGE_OUT + BRIDGE_PAIR_EVIDENCE_REQUIRED`
   - persist receipt logs / transfer evidence through the existing raw
     clarification contract

3. `BE-07Z` outbound-only routed aggregator safety lock
   - keep 1inch / generic aggregator outbound-only rows in
     `EXTERNAL_TRANSFER_OUT`
   - forbid time-window-only bridge promotion

4. `BE-07AA` rerun preparation pack
   - reset derived collections and processing state only
   - preserve raw evidence and clarification evidence

## Risk Notes

- The main risk is accidental overreach from bridge-pair evidence into
  classifier semantics. Receipt evidence should enrich later pairing, not change
  owner-agnostic source-side facts.
- `BRIDGE_OUT` type alone must not be read as automatic move-basis continuity
  when the eventual bridged asset changes along the route.
- Mongo reset must not destroy top-level `clarificationEvidence` or imported
  `external_ledger_raw` evidence.
