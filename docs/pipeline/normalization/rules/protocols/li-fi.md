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
- adapter evidence such as `lifiAdapterV2` when the entry router is not the
  canonical `LI.FI Diamond` but current raw still proves LI.FI-routed
  execution
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
  row; the fetched tx must still prove tracked-wallet relevance using the
  evidence ladder defined in [ADR-027](/docs/adr/ADR-027-lifi-calldata-destination-discovery.md):
  1. **`WALLET_TOUCH`** — top-level `from` / `to`, token transfer endpoints,
     internal transfer endpoints
  2. **`TRACE`** — positive inbound internal transfer credit in explorer raw
  3. **`LIFI_CALLDATA`** — LiFi `apiStatus=DONE` + allowlisted settlement
     selector (`BridgeSettlementSupport`) + beneficiary wallet decoded from
     structured ABI address words in calldata
- when path **`LIFI_CALLDATA`** applies but explorer raw lacks wallet-touch
  inbound legs, discovery may synthesize one LiFi-corroborated internal transfer
  from the paired source `BRIDGE_OUT` principal before classification; tag
  `discoverySource=LIFI_CORROBORATED_SETTLEMENT`
- the persisted `rawTransaction.walletAddress` inherited from a targeted fetch
  request is never sufficient proof by itself
- unresolved route-proven source bridge starts may be revisited by a bounded
  protocol-owned sweep after on-chain clarification; destination-side bridge
  promotion must not depend on the source row having passed through one
  particular clarification lane first
- audited routed `MetaMask Bridge -> LI.FI adapter -> Across settlement`
  sources may also be revisited by a bounded fallback when official LI.FI
  status is absent, but current canonical evidence proves:
  - LI.FI route-tagged source bridge start
  - unique same-wallet cross-network destination candidate
  - destination settlement sender is verified `Across` bridge infrastructure
  - same principal asset family
  - bounded audited time window and quantity drift
- this fallback may promote an inbound-only destination
  `EXTERNAL_TRANSFER_IN` into `BRIDGE_IN`
- audited route-tagged `LI.FI / Jumper` source bridge starts may also be paired
  with a unique same-wallet Relay payout destination when current canonical
  evidence proves:
  - route-proven source on a known `LI.FI Diamond`
  - destination top-level payout sender is registry-backed `Relay`
    infrastructure
  - same principal asset family
  - bounded audited time window and quantity drift
  - no competing destination candidate exists
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

## Multi-source / supplemental LI.FI routes

- official LI.FI status may return the same `receivingTxHash` for multiple
  route-proven `BRIDGE_OUT` sources on one bundle (principal stable leg plus
  supplemental native top-up)
- the first materialized pair keeps the destination reciprocal anchor on the
  principal source (`matchedCounterparty`, `correlationId`)
- later supplemental sources with `apiStatus=DONE` pointing at the same
  receiving tx receive a **source-only anchor**:
  - `matchedCounterparty = destination.txHash`
  - `correlationId = bridge:lifi:<supplementalSourceTxHash>`
  - destination header fields must not be overwritten
- when the supplemental outbound leg matches one inbound flow on the
  destination (for example `WETH -> ETH`), linking retags only that inbound
  flow to `TRANSFER`, stamps `counterpartyAddress = LINKED:<sourceTxHash>`,
  and AVCO replay pairs the leg via the supplemental `bridge:lifi:` queue
- supplemental anchors require a unique qty-compatible inbound leg match via
  `selectPrimaryInboundBridgeFlow`; unrelated second sources must remain
  unlinked

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
- do not treat empty-input inbound transfers as bridge settlement unless the
  settlement sender is registry-backed bridge infrastructure
- do not materialize destination-side `UNKNOWN` review rows from a targeted
  receiving-tx fetch when the tx does not actually touch the tracked wallet on
  chain
- do not self-link a LI.FI source row when the official status response returns
  `sendingTxHash == receivingTxHash`
- do not enable `continuityCandidate = true` on asset-changing bridge routes
- do not let destination-side `BRIDGE_IN` materialization depend on accidental
  clarification order

## WS-2: Facet / Proxy Recognition (multi-network, address-anchored)

### Problem (pre-WS-1)

`0x89c6340b…` (Permit2Proxy) was only registered for ARBITRUM/BASE, causing 41 LI.FI legs on
AVALANCHE/ETHEREUM to show `protocolResolutionState=TERMINAL_METADATA_ONLY`.

### WS-1 fix: network coverage

Extended `protocol-registry.json` to cover all deployed LI.FI entry points across 12 networks:

| Address prefix | Name | Networks |
|---|---|---|
| `0x1231deb6…` | LI.FI Diamond | ETHEREUM, ARBITRUM, BASE, BSC, AVALANCHE, OPTIMISM, LINEA, ZKSYNC, MANTLE, UNICHAIN, KATANA |
| `0x89c6340b…` | Permit2Proxy | ARBITRUM, BASE, AVALANCHE, ETHEREUM |
| `0xe5a89411…` | Permit2Proxy | LINEA |
| `0x628d684d…` | Permit2Proxy | KATANA |
| `0xa3681352…` | Permit2Proxy | UNICHAIN |
| `0x3c6b2e0b…` | Permit2Proxy | PLASMA |
| `0x6307119078…` | Permit2Proxy | OPTIMISM, BASE |
| `0x2270a09b…` | Permit2Proxy | OPTIMISM |
| `0x864b314d…` | GasZip Proxy | UNICHAIN (underlying: GasZip) |
| `0x943e6e07…` | Multicall / Across SpokePool | UNICHAIN |

All of the above are also registered in `KnownBridgeRouterRegistry` for type-only fallback.

### WS-2 fix: function-key pattern matching (address-anchored)

**`callDiamond*` selectors (address-anchored)**

Selectors `0xd7a08473` (`callDiamondWithEIP2612Signature`) and `0x0193b9fc`
(`callDiamondWithPermit2`) **must only classify as LI.FI when the `toAddress` is in the
known LI.FI diamond/proxy registry**. Pattern alone without address anchor → NOT LI.FI.

**`startBridgeTokensVia<Bridge>` and `swapAndStartBridgeTokensVia<Bridge>`**

All facet function prefixes are now recognized, including GasZip, Relay, Across, Hop, CBridge,
Amarok, Hyperlane, Symbiosis, and future additions via generic prefix guard
(`startBridgeTokensVia*`, `swapAndStartBridgeTokensVia*`).

**Underlying bridge metadata**

When `functionName` contains a bridge suffix (e.g. `startBridgeTokensViaGasZip`), the
underlying bridge name (`GasZip`) is preserved in `protocolVersion` or available in calldata
route tags. `LiFiRouteSupport.hasRouteTag` recognizes: `gaszip`, `gaszipbridge`, `relay`,
`across`, `cbridgebridge`, `cbridge`, `amarok`, `hyperlane`, `mayan`, `squid`.

**Address-anchor rule (non-negotiable)**

Do NOT attribute LI.FI solely from a `callDiamond*` function name or selector without first
confirming the target address is in the LI.FI registry. An unverified `callDiamond*` on an
unknown contract is a protocol design detail, not evidence of LI.FI routing.

## Long-tail bridge coverage (BR-2)

- Beyond protocol-owned linkers (LI.FI / Across / Relay / Mayan), the
  **protocol-name-agnostic** `CrossNetworkBridgePairFallbackService` provides zero-RPC,
  evidence-only destination discovery for legs with **no `protocolName`** and long-tail bridge
  families (Hyperlane router pattern, rhino.fi, EtherFi, MetaMask Bridge, 1inch Fusion), **only**
  where the counterpart leg already exists in the dataset (same wallet, cross-network, same asset
  family, amount within ±5%, ≤24h window). It stamps `counterpartyType=BRIDGE` on both legs so the
  pair renders as a connected corridor edge.
- Known-router externals are first promoted to `BRIDGE_IN/OUT` by
  `KnownBridgeRouterExternalTypeCorrectionService` using the registry of trusted bridge-router
  identities (protocol infrastructure addresses in `protocol-registry.json` /
  `KnownBridgeRouterRegistry`), never per-transaction hashes.
- **Own-wallet vs `BRIDGE_OUT` (BR-1).** `OwnWalletBridgeMistypeCorrectionService` reclassifies a
  `BRIDGE_OUT`/`BRIDGE_IN` whose counterparty is another own/member wallet to `INTERNAL_TRANSFER`
  (own-wallet move), decided by universe membership — no hardcoded address.
- **Peg-neutral checked assumption.** An orphan `BRIDGE_IN` with no in-session source is only
  accepted as a basis-neutral irreducible acquisition when its asset is a USD/EUR-pegged stablecoin
  (`PegNeutralBridgeAssumptionSupport`); a non-stable orphan is flagged
  `BRIDGE_ORPHAN_NON_PEG_BASIS_UNVERIFIED` for audit, never silently accepted.

## Regression Anchors

- `0x4bd7b04bc2864b0012f19300690ae5cacb2806fdcc0b1612664d98b5015b48f6`
- `0x559460094fe1cfbcf37bb1fb4961f49809bb0f53a0787d02e0baedacec59f511`
- `0x927cfa4d452608316410120af05d8b09c2f4d8d9cec5f9273457b7d8c3e47757`
- `0x9f6983d00441ed13bf45e1b7ac34e94540fb61f58e4a9a2189826b1e761a2f7f`
  -> `0x7d8c79a327637fda080bcfa9204181359de791ccff95dd4b2f1b020b8af0b678`
- `0x1a756dd5b8d6144d250f3f2a86d25a718e4ac0e3c2044042c1d749ecacda95f6`
  -> `0x4a47ab3cad76be52416e660e044b983acc9837cd9f05b59eabad7560636aa0b2`
- `0x4bd7b04bc2864b0012f19300690ae5cacb2806fdcc0b1612664d98b5015b48f6`
  -> `0x2108883281ea4cd12eb27e4a540f9f008e149c1e8fe7a1348e80311c1f4d9ff8`
- `0x4890e907f816a2f573559377fb97943efcbad26750cb3cf3bf96ff48a43504f7`
  -> `0x25550cf1685a0ce5ab3d546b595d6c43a742b8487ab4fbc2b7913bf03645b7aa`
  (LayerZero `execute302`, calldata beneficiary, ADR-027)

All three audited rows call `LI.FI Diamond`
`0x1231deb6f5749ef6ce6943a275a1d3e7486f4eae`, carry bridge-route calldata
markers, and must remain `BRIDGE_OUT`.

The MetaMask-routed audit pair keeps source `protocolName = MetaMask Bridge`,
but clarification may still use saved LI.FI adapter evidence plus `Across`
settlement proof to materialize the destination `BRIDGE_IN`.
