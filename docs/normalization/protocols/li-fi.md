# LI.FI Protocol Rules

Status: Active protocol rule slice

## Scope

Cover audited `LI.FI / Jumper` bridge-start semantics and protocol-owned
destination bridge-pair evidence for the `LI.FI / Jumper` route family.

## Runtime Ownership

- Bridge-start family mapping:
  [BridgeStartClassifier.java](/Users/vladislavkondratenko/projects/wallet-radar/backend/src/main/java/com/walletradar/ingestion/pipeline/classification/onchain/family/BridgeStartClassifier.java)
- Bridge method-aware path:
  [BridgeMethodAwareClassifier.java](/Users/vladislavkondratenko/projects/wallet-radar/backend/src/main/java/com/walletradar/ingestion/pipeline/classification/onchain/family/BridgeMethodAwareClassifier.java)
- Destination-side bridge pair linker:
  [LiFiBridgePairLinkService.java](/Users/vladislavkondratenko/projects/wallet-radar/backend/src/main/java/com/walletradar/ingestion/pipeline/clarification/LiFiBridgePairLinkService.java)
- Official status loader:
  [LiFiStatusGateway.java](/Users/vladislavkondratenko/projects/wallet-radar/backend/src/main/java/com/walletradar/ingestion/pipeline/clarification/LiFiStatusGateway.java)

## Authoritative Evidence

- `OnChainRawTransactionView`
- recovered selector from `rawData.methodId` or `rawData.input[0:10]`
- positive native value or equivalent outbound movement
- saved calldata route tags such as:
  - `relay`
  - `across`
  - `jumper.exchange`
- trusted bridge-router identity from
  `backend/src/main/resources/protocol-registry.json`
- official LI.FI status response when clarification already proved the source
  route and the runtime needs the receiving tx hash for pair materialization

## Classification Rules

- route-tagged `LI.FI / Jumper` bridge-start rows on a known `LI.FI Diamond`
  router remain `BRIDGE_OUT`
- this is true even when:
  - top-level `methodId` is blank
  - the recovered selector is outside the current narrow explicit selector
    allowlist
- the route tag plus wallet-boundary outbound funding is enough for source-side
  bridge-initiation semantics
- if the same source row also carries tx-level native `value` and exactly one
  outbound token principal leg, the native `value` leg is route funding / cost
  and must normalize as `FEE` instead of surviving as a second principal
  `TRANSFER`
- when official LI.FI status evidence resolves the receiving tx hash and the
  destination tx already exists in the current normalized outcome, the
  destination-side inbound row becomes `BRIDGE_IN`
- this destination-side promotion may resolve across the full tracked wallet
  universe; the receiving leg is not required to land on the same tracked
  wallet as the source leg
- when official LI.FI status resolves a receiving tx hash that is still absent
  from Mongo, the runtime may perform a bounded targeted tx-hash fetch across
  tracked wallets and normalize the discovered destination in the same run
- this targeted tx-hash discovery must work on both explorer-backed and
  `RPC`-backed networks; network transport differences must not change LI.FI
  bridge semantics
- official `receivingTxHash` alone is not enough to materialize a destination
  row; the fetched tx must still prove real tracked-wallet relevance from
  actual on-chain evidence:
  - top-level `from` / `to`
  - token transfer `from` / `to`
  - internal transfer `from` / `to`
- the persisted `rawTransaction.walletAddress` inherited from a targeted fetch
  request is never sufficient proof by itself
- unresolved route-proven source bridge starts may be revisited by a bounded
  protocol-owned sweep after on-chain clarification; destination-side bridge
  promotion must not depend on the source row having passed through one
  particular clarification lane first
- this promotion does not automatically imply plain move-basis continuity
- audited bridge-family-equivalent stable wrappers such as `vbUSDC -> USDC`
  may still qualify for move-basis continuity when the route is proven and
  accounting policy explicitly maps both sides into one stable family
- asset-changing route pairs such as `USDC -> ETH` must keep
  `continuityCandidate = false`
- once the pair is already linked by official LI.FI status plus canonical
  destination settlement, replay may still apply conservative
  route-settlement basis restoration on the destination asset:
  - source cost basis is reallocated into destination acquisition
  - source covered/uncovered ratio is preserved on destination quantity
  - this is not the same as plain move-basis continuity

## Clarification Rules

- clarification may fetch receipt evidence for later bridge-pair reconstruction
- clarification may also persist official LI.FI receiving-tx evidence for a
  route-proven source bridge start
- clarification is not the place to rescue a source-side bridge-start that is
  already provable from current raw calldata plus movement

## Disallowed Fallbacks

- do not demote route-tagged `LI.FI / Jumper` bridge starts into generic
  `EXTERNAL_TRANSFER_OUT`
- do not treat generic route tags on non-bridge-router contracts as sufficient
  for `BRIDGE_OUT`
- do not promote destination-side `EXTERNAL_TRANSFER_IN` into `BRIDGE_IN` from
  timestamp proximity alone
- do not materialize destination-side `UNKNOWN` review rows from a targeted
  receiving-tx fetch when the tx does not actually touch the tracked wallet on
  chain
- do not self-link a LI.FI source row when the official status response returns
  `sendingTxHash == receivingTxHash`
- do not enable `continuityCandidate = true` on asset-changing bridge routes
- do not let destination-side `BRIDGE_IN` materialization depend on accidental
  clarification order

## Regression Anchors

- `0x4bd7b04bc2864b0012f19300690ae5cacb2806fdcc0b1612664d98b5015b48f6`
- `0x559460094fe1cfbcf37bb1fb4961f49809bb0f53a0787d02e0baedacec59f511`
- `0x927cfa4d452608316410120af05d8b09c2f4d8d9cec5f9273457b7d8c3e47757`
- `0x9f6983d00441ed13bf45e1b7ac34e94540fb61f58e4a9a2189826b1e761a2f7f`
  -> `0x7d8c79a327637fda080bcfa9204181359de791ccff95dd4b2f1b020b8af0b678`

All three audited rows call `LI.FI Diamond`
`0x1231deb6f5749ef6ce6943a275a1d3e7486f4eae`, carry bridge-route calldata
markers, and must remain `BRIDGE_OUT`.
