# Cycle 67 protocol rule pack

## Purpose

This rule pack documents reusable protocol-detection rules that are justified by the current live dataset and safe to use without redefining economics from explorer-only evidence.

## Rule priority

1. Exact registry hit on the interacted contract
2. Audited method selector on the interacted contract
3. Canonical lifecycle shape and transfer pattern from persisted raw evidence
4. Clarification-time enrichment only when it uses evidence that production clarification can really access

## Reusable rules

### 1. Canonical wrapped-native contracts must always label `WRAP` / `UNWRAP`

- Observable raw evidence:
  - interacted contract is canonical wrapped-native contract
  - normalized type is already `WRAP` or `UNWRAP`
  - selector may be explicit (`deposit()`, `withdraw(uint256)`) or missing on native-transfer wrappers
- Canonical result:
  - keep the economic type as `WRAP` / `UNWRAP`
  - set `protocolName` from the canonical wrapper brand
  - set `counterpartyAddress` to the wrapper contract
- High-volume current targets:
  - `BASE / 0x4200000000000000000000000000000000000006`
  - `OPTIMISM / 0x4200000000000000000000000000000000000006`
  - `UNICHAIN / 0x4200000000000000000000000000000000000006`
  - `AVALANCHE / 0xb31f66aa3c1e785363f0875a1b74e27b85fd66c7`
- Negative guards:
  - do not infer wrap semantics from arbitrary ERC20 transfers
  - do not let a missing selector suppress a known canonical wrapper label when the contract address already proves it

### 2. LI.FI and similar routed bridges need protocol proof on the source row and lifecycle proof across both rows

- Observable raw evidence:
  - source row is `BRIDGE_OUT`
  - destination row is `BRIDGE_IN`
  - same wallet
  - different networks
  - one principal family on both sides
  - one unique candidate inside the audited time/quantity window
- Canonical result:
  - keep source/destination as separate canonical rows
  - materialize one deterministic `correlationId`
  - materialize reciprocal `matchedCounterparty`
  - set source `protocolName` from the source bridge proof
  - destination may carry settlement-brand metadata separately
- Current anchors:
  - `0x9f6983d00441ed13bf45e1b7ac34e94540fb61f58e4a9a2189826b1e761a2f7f`
  - `0x7d8c79a327637fda080bcfa9204181359de791ccff95dd4b2f1b020b8af0b678`
- Negative guards:
  - no ambiguous multi-candidate pairing
  - no same-network auto-link
  - no many-to-many bridge grouping

### 3. Vault and lending receipt-token rows must preserve protocol identity from the interacted contract

- Observable raw evidence:
  - interacted contract is the vault or pool
  - one receipt-token mint/burn leg plus one underlying principal leg
- Canonical result:
  - keep `LENDING_DEPOSIT`, `LENDING_WITHDRAW`, `VAULT_DEPOSIT`, `VAULT_WITHDRAW`
  - set `protocolName` and `counterpartyAddress` from the interacted contract
  - keep economics separate from later protocol-name sweeps
- Current anchors:
  - `0xc0ca8c4022bbfbb8bfd0660155e4857dd80c0cf5c521b8ad5f61ab4738fc0cab`
  - `0x0765f4b17d9961c3cd7e63b4dc1e69c948954d51816807a4ba51a8ac4709f977`
- Negative guards:
  - do not relabel economics from explorer prose
  - do not use a receipt token symbol alone as protocol proof when the interacted contract is available

### 4. Swap routers should receive protocol labels and row-local counterparties from the interacted contract

- Observable raw evidence:
  - one sold asset, one bought asset, routed through a known router or aggregator contract
- Canonical result:
  - keep `SWAP`
  - `protocolName` from the router/aggregator contract
  - `counterpartyAddress` from the interacted router
- Current anchors:
  - `0x101c297d1a67fc0a30fc96c1aa5cc8786d630bbb00d274c9dc7bbced4c6dccd7`
  - `0xc9b422cdf001efacbfd843efdaa60a4d6d574c1bb1a1c1b070c7781c181c73e3`
- Negative guards:
  - do not use transfer recipients or liquidity pool hops as the displayed protocol brand when the router contract is already known

### 5. LP position lifecycles need protocol proof plus deterministic position identity

- Observable raw evidence:
  - LP-position `correlationId`
  - multi-leg principal return on exit or multi-leg deposit on entry
  - interacted contract or position manager proves the protocol brand
- Canonical result:
  - keep `LP_ENTRY` / `LP_EXIT`
  - persist protocol label from the position manager or pool contract
  - keep the LP-position identity available for later basis allocation
- Current anchors:
  - `0x091e356020745e6555732067a025cee9243dee6848c47dd8eb97259293735e70`
  - `0xac23f81f7c6b0b774201c1c1417d52cb6497947af864549fcdbe470e819eaf7f`
- Negative guards:
  - do not collapse LP exits into generic transfers
  - do not leave supported LP exits as `basisEffect=UNKNOWN`

## Registry and enrichment priority table

| Network | Type | Interacted address | Method | Count | Address already in registry | Change package implication |
| --- | --- | --- | --- | ---: | --- | --- |
| BASE | WRAP | 0x4200000000000000000000000000000000000006 | 0x | 130 | yes | Existing registry coverage is not reaching final row enrichment. |
| BASE | UNWRAP | 0x4200000000000000000000000000000000000006 | 0x | 129 | yes | Existing registry coverage is not reaching final row enrichment. |
| UNICHAIN | WRAP | 0x4200000000000000000000000000000000000006 | 0xd0e30db0 | 113 | yes | Existing registry coverage is not reaching final row enrichment. |
| OPTIMISM | UNWRAP | 0x4200000000000000000000000000000000000006 | 0x | 100 | yes | Existing registry coverage is not reaching final row enrichment. |
| OPTIMISM | WRAP | 0x4200000000000000000000000000000000000006 | 0x | 100 | yes | Existing registry coverage is not reaching final row enrichment. |
| UNICHAIN | UNWRAP | 0x4200000000000000000000000000000000000006 | 0x2e1a7d4d | 92 | yes | Existing registry coverage is not reaching final row enrichment. |
| BASE | BRIDGE_OUT | 0x89c6340b1a1f4b25d36cd8b063d49045caf3f818 | 0x | 18 | no | Add registry or audited enrichment coverage for this target. |
| ARBITRUM | BRIDGE_OUT | 0x89c6340b1a1f4b25d36cd8b063d49045caf3f818 | 0xd7a08473 | 16 | no | Add registry or audited enrichment coverage for this target. |
| AVALANCHE | SWAP | 0x45a62b090df48243f12a21897e7ed91863e2c86b | 0xf1910f70 | 15 | yes | Existing registry coverage is not reaching final row enrichment. |
| ARBITRUM | SWAP | 0x0000000000001ff3684f28c67538d4d072c22734 | 0x2213bc0b | 9 | no | Add registry or audited enrichment coverage for this target. |
| AVALANCHE | UNWRAP | 0xb31f66aa3c1e785363f0875a1b74e27b85fd66c7 | 0x2e1a7d4d | 8 | yes | Existing registry coverage is not reaching final row enrichment. |
| KATANA | SWAP | 0xac4c6e212a361c968f1725b4d055b47e63f80b75 | 0x5f3bd1c8 | 8 | no | Add registry or audited enrichment coverage for this target. |
| UNICHAIN | BRIDGE_IN | n/a | 0xdeff4b24 | 8 | no | Add registry or audited enrichment coverage for this target. |
| LINEA | REWARD_CLAIM | 0x5828a3c0f07c6b841205d12660e0abb869bf98dc | 0x86d1a69f | 7 | no | Add registry or audited enrichment coverage for this target. |
| OPTIMISM | BRIDGE_OUT | 0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f | 0x | 7 | no | Add registry or audited enrichment coverage for this target. |

## Outcome expected from this rule pack

- protocol labels become deterministic on rows that are already canonically correct
- metadata repair remains separated from economic reclassification
- downstream counterparty construction can rely on stable protocol and interacted-contract evidence
