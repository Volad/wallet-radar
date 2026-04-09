# Run 51 LI.FI Routed Across Passive Settlement Closeout

Status: Done

Goal:

Close the audited routed bridge gap where a real `MetaMask Bridge -> LI.FI
adapter -> Across settlement` corridor still materializes as
`BRIDGE_OUT -> EXTERNAL_TRANSFER_IN` because the destination settlement is
passive and official LI.FI status is absent.

Related inputs:

- [bridge pair focus](/Users/vladislavkondratenko/projects/wallet-radar/results/stats/12/bridge-pair-focus.md)
- [bridge pair summary](/Users/vladislavkondratenko/projects/wallet-radar/results/stats/12/summary.json)
- [Bridge family rules](../normalization/families/bridge.md)
- [LI.FI protocol rules](../normalization/protocols/li-fi.md)
- [Accounting policy](../03-accounting.md)

## Problem Statement

The audited pair
`0x1a756dd5b8d6144d250f3f2a86d25a718e4ac0e3c2044042c1d749ecacda95f6`
-> `0x4a47ab3cad76be52416e660e044b983acc9837cd9f05b59eabad7560636aa0b2`
is a real bridge corridor:

1. source is an Arbitrum `BRIDGE_OUT` through `MetaMask Bridge`
2. saved calldata proves `lifiAdapterV2`
3. destination is a same-wallet Ethereum inbound settlement funded by verified
   `Across Protocol: Ethereum Spoke Pool V2`
4. current canonical state still leaves destination as
   `EXTERNAL_TRANSFER_IN` with no pair metadata

That breaks both debug clarity and downstream bridge continuity.

## Scope

In scope:

- passive destination bridge-settlement promotion from registry-backed
  internal-transfer evidence
- deterministic LI.FI-routed fallback linking to verified `Across`
  settlement when official LI.FI status is absent
- preserving source entry `protocolName` instead of rewriting it to the
  settlement provider
- full on-chain rerun after the normalization/clarification change

Out of scope:

- fuzzy bridge matching without protocol proof
- long-gap route guesses
- non-LI.FI routed bridges without route evidence
- asset-changing route repair beyond current accounting policy

## Acceptance Criteria (DoD)

1. An inbound-only on-chain destination with empty input and blank function
   name may resolve to `BRIDGE_IN` only when current raw proves a
   registry-backed bridge sender through internal-transfer or token-transfer
   evidence.

2. A routed source `BRIDGE_OUT` may materialize a destination pair without
   official LI.FI status only when all of the following are true:
   - source is `ON_CHAIN`
   - source is `BRIDGE_OUT`
   - source raw proves LI.FI route evidence
   - destination is same-wallet, cross-network, `ON_CHAIN`
   - destination is inbound-only `BRIDGE_IN` or `EXTERNAL_TRANSFER_IN`
   - destination settlement sender proves verified `Across` bridge
     infrastructure
   - both rows expose exactly one principal flow
   - principal asset family matches under
     `BridgeAssetFamilySupport.continuityIdentity(...)`
   - destination quantity is not greater than source quantity
   - timestamp delta stays within the audited bounded window
   - quantity drift stays within the audited bounded tolerance
   - there is exactly one qualifying destination candidate

3. When such a pair is proven:
   - destination becomes `BRIDGE_IN` if needed
   - both rows receive deterministic `correlationId`
   - both rows receive `matchedCounterparty`
   - `continuityCandidate` remains derived from current canonical flow
     evidence only
   - source `protocolName` may remain `MetaMask Bridge`

4. Negative safeguards remain intact:
   - generic empty-input inbound transfers do not auto-promote
   - ambiguous multiple candidates do not auto-link
   - same-network rows do not auto-link
   - destination quantity greater than source quantity does not auto-link
   - non-LI.FI routed sources do not enter this fallback path

5. Rerun preparation:
   - raw evidence remains intact
   - on-chain normalized rows are re-opened from raw
   - derived collections are rebuilt from the rerun

## Edge Cases

- source route provider is MetaMask Bridge while settlement provider is Across
- destination protocol proof exists only in `internalTransfers[].from`
- official LI.FI status later arrives and agrees with the fallback pair
- multiple inbound same-wallet ETH settlements land inside the same time window
- destination is already `BRIDGE_IN` but still misses pair metadata

## Task Breakdown

1. `BE-R51-01` Allow passive bridge settlement classification from verified
   internal-transfer sender evidence
2. `BE-R51-02` Persist or reuse route evidence for LI.FI-routed source rows
   without overwriting source entry protocol identity
3. `BE-R51-03` Extend LI.FI clarification linker with deterministic routed
   fallback to verified `Across` settlement
4. `BE-R51-04` Lock regression coverage for:
   - passive `BRIDGE_IN` promotion
   - routed fallback link success
   - ambiguous candidate rejection
5. `BE-R51-05` Run safe on-chain rerun and verify the audited pair

## Risk Notes

- Over-broad bridge registry entries can create false-positive `BRIDGE_IN`
  promotions; registry scope must stay tight.
- The fallback must remain unique-fit only. Ambiguous windows stay unresolved
  on purpose.
- Source `protocolName` and downstream settlement proof must remain separate
  concepts; pairing must not rewrite history.

## Completion Notes

- Passive settlement bridge classification now accepts registry-backed bridge
  senders observed only in persisted internal-transfer evidence.
- `MetaMask Bridge` was added as a first-class bridge entry while keeping the
  routed pair logic protocol-bounded.
- `LiFiBridgePairLinkService` now supports a deterministic LI.FI-routed
  fallback to verified `Across` settlement when official LI.FI status is
  absent.
- This slice requires a full on-chain canonical rerun because classification
  and clarification outcomes changed.
