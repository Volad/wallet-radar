# Supported Networks and Protocols

> **Last updated:** 2026-07-17

This page is the consolidated catalog of everything WalletRadar supports —
**networks**, **on-chain protocols/contracts**, and **CEX venues** — each with a
reference to the runtime configuration file and the code that consumes it.

**Runtime source of truth** (the tables below are a human snapshot; when they
disagree, the config/code wins):

| Domain | Config (classpath / YAML) | Loader / consumer (code) |
|--------|---------------------------|--------------------------|
| Networks | `backend/core/src/main/resources/network-descriptors.yml`, `application.yml` (`walletradar.ingestion.network.*`) | `NetworkId.java`, `NetworkDescriptorConfiguration.java`, `NetworkRegistry.java`, `IngestionNetworkProperties.java` |
| On-chain protocols / contracts | `backend/core/src/main/resources/protocol-registry.json` | `ProtocolRegistryLoader.java` → `ProtocolRegistryService.java` |
| Protocol identity / markers / hints | `backend/core/src/main/resources/protocols/*.json` | `ProtocolResourceLoader.java` (`ProtocolResourceCatalog`) → `*ProtocolSemanticClassifier` |
| CEX venues | `application.yml` (`walletradar.cex.*`) | `VenueDescriptor` impls under `application.cex.acquisition.venue.*`, discovered by `VenueRegistry.java` |

> **Two protocol config planes** (address-keyed registry + protocol-keyed
> `protocols/*.json`). The former standalone `protocol-descriptors/*.json` plane and its
> descriptor SPI were folded into `protocols/*.json` and retired on 2026-07-16 — see
> [Protocol config consolidation assessment](../tasks/protocol-config-consolidation-assessment.md#5-outcome-implemented).

**Snapshot counts (2026-07-16):** 15 `NetworkId` values (13 EVM in the registry +
`SOLANA` + `TON`), 71 registry protocols across 228 contract entries, 9
`protocols/*.json` protocol profiles, 10 per-protocol semantic classifiers, 2 CEX
venues.

## NetworkId matrix

Authoritative enum: `NetworkId.java` (15 values).

| Network | syncMethod | Explorer / RPC | Adapter | Notes |
|---------|------------|----------------|---------|-------|
| ETHEREUM | ETHERSCAN | Etherscan V2 | ExplorerEvmNetworkAdapter | Native ETH, WETH at replay |
| ARBITRUM | ETHERSCAN | Arbiscan | ExplorerEvmNetworkAdapter |  |
| OPTIMISM | BLOCKSCOUT | Optimism Blockscout | ExplorerEvmNetworkAdapter |  |
| POLYGON | ETHERSCAN | Polygonscan | ExplorerEvmNetworkAdapter |  |
| BASE | BLOCKSCOUT | Base Blockscout | ExplorerEvmNetworkAdapter |  |
| BSC | RPC | Ankr provider + RPC | BscProviderFirstRpcNetworkAdapter | Provider-first path |
| AVALANCHE | ETHERSCAN | Routescan | ExplorerEvmNetworkAdapter |  |
| MANTLE | ETHERSCAN | Mantlescan | ExplorerEvmNetworkAdapter |  |
| LINEA | ETHERSCAN | Etherscan V2 chain 59144 | ExplorerEvmNetworkAdapter |  |
| UNICHAIN | ETHERSCAN | Etherscan V2 chain 130 | ExplorerEvmNetworkAdapter |  |
| KATANA | ETHERSCAN | Etherscan V2 chain 747474 | ExplorerEvmNetworkAdapter |  |
| PLASMA | ETHERSCAN | Etherscan V2 chain 9745 | ExplorerEvmNetworkAdapter |  |
| ZKSYNC | BLOCKSCOUT | zkSync Blockscout | ExplorerEvmNetworkAdapter | Native alias fee rules |
| SOLANA | RPC | Solana JSON-RPC | SolanaNetworkAdapter | fullIndex: false |
| TON | RPC | — | — | fullIndex: false, placeholder |

### Network configuration & code references

| Concern | File | Notes |
|---------|------|-------|
| Enum (15 values) | `backend/domain/src/main/java/com/walletradar/domain/common/NetworkId.java` | 13 EVM + `SOLANA` + `TON` |
| Address format kind | `backend/domain/.../common/NetworkAddressFormatKind.java` | `EVM`, `SOLANA`, `TON` |
| Per-network descriptor (native/wrapped symbol, address format) | `backend/core/src/main/resources/network-descriptors.yml` | bound via `NetworkProperties` (`walletradar.networks`) |
| Per-network contract sets: `wrapped-native`, `native-alias-contracts`, `usd-stable-contracts`, `eth-family-contracts` | `network-descriptors.yml` | `NetworkRegistry` accessors: `wrappedNativeContract`, `nativeAliasIdentityContracts`, `usdStableContracts`, `ethFamilyEquivalentContracts()`; static bridges `NetworkNativeAssets`, `NetworkStablecoinContracts` |
| Descriptor record + wiring | `backend/domain/.../common/NetworkDescriptor.java`, `backend/platform/.../networks/descriptor/NetworkDescriptorConfiguration.java`, `NetworkRegistry.java` | imported in `application.yml` |
| Ingestion (chain-id, explorer/RPC, syncMethod) | `application.yml` `walletradar.ingestion.network.<NETWORK>` | schema `IngestionNetworkProperties.java` |
| Network family SPI (EVM / Solana / TON transport + address rules) | `backend/platform/.../networks` (`NetworkFamily`) | see [capability-behavior-spi](capability-behavior-spi.md#network-family-spi-b2) |

Chain IDs: ETHEREUM 1, OPTIMISM 10, UNICHAIN 130, POLYGON 137, ZKSYNC 324, MANTLE
5000, BASE 8453, PLASMA 9745, ARBITRUM 42161, AVALANCHE 43114, BSC 56, LINEA 59144,
KATANA 747474. `SOLANA` / `TON` are RPC-only (no chain-id, `fullIndex: false`).

> **Registry vs enum:** `SOLANA` and `TON` exist on `NetworkId` and in
> `network-descriptors.yml`/ingestion config but are **absent** from
> `protocol-registry.json` `supported_networks` (13 EVM only) — no on-chain
> protocol contracts are registered for them yet.

> **ETH-family allowlist (W11):** the NEC "fake ETH/WETH token" filter
> (`PortfolioConservationGate`, `RC-fake-native`) no longer hardcodes WETH contracts.
> `NetworkRegistry.ethFamilyEquivalentContracts()` derives them from
> `network-descriptors.yml`: for `native-symbol == ETH` networks, `wrapped-native` +
> `native-alias-contracts`; plus the optional `eth-family-contracts` field for bridged
> WETH on non-ETH chains (Mantle precompile, Avalanche WETH.e). Non-ETH-native
> wrapped-natives (WBNB/WMATIC/WAVAX/WMNT/WXPL) are never included. See
> [conservation gate](../pipeline/portfolio-snapshot/03-conservation-gate.md) and the
> [consolidation inventory](../tasks/hardcoded-registry-consolidation-proposal.md#4j-w11--implemented-this-change).

## Protocol coverage matrix

| Protocol | Contract entries | Networks | Registry family | Coverage tier | JSON profile | Rule doc |
|----------|------------------|----------|-----------------|---------------|--------------|----------|
| 0x Protocol | 1 | ARBITRUM, AVALANCHE, BASE, BSC (+3) | AGGREGATOR | heuristic-only | no | — |
| 1inch | 2 | ARBITRUM, AVALANCHE, BASE, BSC (+6) | AGGREGATOR | registry-backed | no | [doc](../pipeline/normalization/rules/protocols/paraswap-1inch.md) |
| Aave | 12 | ARBITRUM, AVALANCHE, BASE, BSC (+6) | AGGREGATOR, LENDING, LP | dedicated-semantic | yes | [doc](../pipeline/normalization/rules/protocols/aave.md) |
| Across | 6 | ARBITRUM, BASE, ETHEREUM, OPTIMISM (+3) | BRIDGE | dedicated-semantic | no | [doc](../pipeline/normalization/rules/protocols/across.md) |
| Aerodrome | 2 | BASE | DEX | registry-backed | no | — |
| Angle | 1 | ARBITRUM, AVALANCHE, BASE, ETHEREUM (+3) | YIELD | heuristic-only | no | — |
| Arbitrum | 3 | ARBITRUM, ETHEREUM | BRIDGE | registry-backed | no | — |
| Aura | 2 | AVALANCHE | YIELD | dedicated-semantic (AuraProtocolSemanticClassifier) | no | withdrawAndUnwrap → REWARD_CLAIM |
| Axelar | 1 | ARBITRUM | BRIDGE | heuristic-only | no | — |
| Balancer | 1 | ARBITRUM, AVALANCHE, BASE, ETHEREUM (+3) | DEX | dedicated-semantic | yes | [doc](../pipeline/normalization/rules/protocols/balancer.md) |
| Base | 1 | ETHEREUM | BRIDGE | heuristic-only | no | — |
| Beefy | 1 | ARBITRUM, AVALANCHE, BSC, OPTIMISM (+1) | YIELD | heuristic-only | no | — |
| BENQI | 1 | AVALANCHE | LENDING | heuristic-only | no | — |
| Bridge Router | 1 | OPTIMISM | BRIDGE | heuristic-only | no | — |
| Camelot | 4 | ARBITRUM | DEX | registry-backed | no | — |
| Canonical | 10 | ARBITRUM, AVALANCHE, BASE, BSC (+9) | WRAPPER | registry-backed | no | — |
| Circle CCTP | 1 | ARBITRUM, AVALANCHE, BASE, ETHEREUM (+2) | BRIDGE | heuristic-only | no | — |
| Compound | 10 | ARBITRUM, ETHEREUM, OPTIMISM, POLYGON (+1) | LENDING, YIELD | dedicated-semantic | no | [doc](../pipeline/normalization/rules/protocols/compound.md) |
| Convex | 1 | ETHEREUM | YIELD | heuristic-only | no | — |
| Curve | 4 | ARBITRUM, AVALANCHE, BASE, BSC (+4) | DEX, LP | registry-backed | no | — |
| deBridge | 3 | ARBITRUM, AVALANCHE, BASE, BSC (+4) | BRIDGE | registry-backed | no | — |
| Equilibria | 3 | MANTLE | YIELD | dedicated-semantic (EquilibriaProtocolSemanticClassifier) | no | — |
| Euler | 5 | AVALANCHE, LINEA, PLASMA | LENDING | dedicated-semantic | yes | [doc](../pipeline/normalization/rules/protocols/euler.md) |
| Fluid | 6 | ARBITRUM, PLASMA | DEX, LENDING, YIELD | dedicated-semantic | no | [doc](../pipeline/normalization/rules/protocols/fluid.md) |
| Frax | 2 | ETHEREUM | STAKING | registry-backed | no | — |
| FusionX | 4 | MANTLE | DEX | registry-backed | no | — |
| GMX | 13 | ARBITRUM, AVALANCHE | PERP, YIELD | dedicated-semantic | yes | [doc](../pipeline/normalization/rules/protocols/gmx-v2.md) |
| Hop Protocol | 3 | ARBITRUM, ETHEREUM | BRIDGE | registry-backed | no | — |
| Hyperlane | 2 | ARBITRUM, ETHEREUM | BRIDGE | registry-backed | no | — |
| Hyperliquid | 1 | ARBITRUM | CUSTODY | heuristic-only | no | — |
| INIT Capital | 3 | MANTLE | LENDING | dedicated-semantic (InitCapitalSemanticClassifier) | no | — |
| Katana | 1 | KATANA | DEX | heuristic-only | no | — |
| Katana/AggLayer | 1 | KATANA | BRIDGE | heuristic-only | no | — |
| KyberSwap | 1 | ARBITRUM, AVALANCHE, BASE, BSC (+5) | AGGREGATOR | heuristic-only | no | — |
| Lagoon | 4 | ARBITRUM, AVALANCHE, BASE, ETHEREUM (+2) | YIELD | registry-backed | no | — |
| Lendle Rewards Station | 1 | MANTLE | YIELD | heuristic-only | no | — |
| LFJ | 2 | ARBITRUM, AVALANCHE | AGGREGATOR, DEX | registry-backed | no | — |
| LI.FI | 12 | 13 EVM networks (diamond + Permit2Proxy, incl. UNICHAIN `0x1bcd304f…`) | BRIDGE | dedicated-semantic | no | [doc](../pipeline/normalization/rules/protocols/li-fi.md) |
| Lido | 2 | ETHEREUM | STAKING, WRAPPER | registry-backed | no | — |
| LiFi | 1 | ZKSYNC | BRIDGE | heuristic-only | no | — |
| Linea | 2 | ETHEREUM | BRIDGE | registry-backed | no | — |
| Linea Rewards | 1 | LINEA | YIELD | heuristic-only | no | — |
| Magpie | 1 | ARBITRUM | AGGREGATOR | heuristic-only | no | — |
| Merchant Moe | 4 | MANTLE | AGGREGATOR, DEX | registry-backed | no | — |
| Merkl | 1 | MANTLE | YIELD | heuristic-only | no | — |
| MetaMask Bridge | 1 | ARBITRUM | BRIDGE | heuristic-only | no | — |
| MEV Capital | 1 | AVALANCHE | YIELD | heuristic-only | no | — |
| Morpho | 2 | ARBITRUM, KATANA | AGGREGATOR, LENDING | dedicated-semantic | yes | [doc](../pipeline/normalization/rules/protocols/morpho.md) |
| Optimism | 1 | ETHEREUM | BRIDGE | heuristic-only | no | — |
| Optimism/Base | 1 | BASE, OPTIMISM | BRIDGE | heuristic-only | no | — |
| PancakeSwap | 8 | ARBITRUM, BASE, BSC, ETHEREUM (+2) | DEX, STAKING | registry-backed | no | [doc](../pipeline/normalization/rules/protocols/pancakeswap.md) |
| Paradex | 1 | ETHEREUM | CUSTODY | heuristic-only | no | — |
| Pendle | 6 | ARBITRUM, BASE, BSC, ETHEREUM (+2) | YIELD | dedicated-semantic | yes | [doc](../pipeline/normalization/rules/protocols/pendle.md) |
| Polygon | 2 | ETHEREUM | BRIDGE | registry-backed | no | — |
| QuickSwap | 2 | POLYGON | DEX | registry-backed | no | — |
| Rabby | 1 | 7 EVM networks | AGGREGATOR (`GAS_PAYER`) | registry-backed | no | Gas Fee Payer → basis-neutral `SPONSORED_GAS_IN` (NEW-14) |
| Radiant | 1 | ARBITRUM, BSC | LENDING | heuristic-only | no | — |
| Relay | 7 | ARBITRUM, AVALANCHE, BASE, BSC, ZKSYNC (+7) | BRIDGE (+ `GAS_PAYER`) | registry-backed | no | Relay `GAS_PAYER`→`BRIDGE_IN`, ZKSYNC coverage (NEW-11) |
| Rewards | 3 | BSC | YIELD | registry-backed | no | — |
| Rocket Pool | 2 | ETHEREUM | STAKING | registry-backed | no | — |
| Routed Swap | 1 | ARBITRUM | AGGREGATOR | heuristic-only | no | — |
| Stargate | 4 | ARBITRUM, AVALANCHE, ETHEREUM, OPTIMISM (+1) | BRIDGE | registry-backed | no | — |
| SushiSwap | 3 | ARBITRUM, AVALANCHE, BSC, ETHEREUM (+3) | DEX, LP | registry-backed | no | — |
| TraderJoe | 1 | ARBITRUM, AVALANCHE, BSC | DEX | heuristic-only | no | — |
| Turtle Finance | 1 | AVALANCHE | YIELD | heuristic-only | no | — |
| Uniswap | 16 | ARBITRUM, AVALANCHE, BASE, BSC (+7) | DEX | registry-backed | yes | [doc](../pipeline/normalization/rules/protocols/uniswap.md) |
| Universal Router | 1 | OPTIMISM | AGGREGATOR | heuristic-only | no | — |
| Velodrome | 5 | OPTIMISM | DEX | registry-backed | no | [doc](../pipeline/normalization/rules/protocols/velodrome.md) |
| Velora/ParaSwap | 3 | ARBITRUM, AVALANCHE, BASE, BSC (+3) | AGGREGATOR | registry-backed | no | — |
| Yearn | 1 | ETHEREUM | YIELD | heuristic-only | no | — |
| zkSync Era | 2 | ETHEREUM | BRIDGE | registry-backed | no | — |

> The matrix is a snapshot of `protocol-registry.json` (2026-05-07, 71 protocols /
> 228 contracts). The registry file is the runtime source of truth; regenerate the
> table from it rather than hand-editing when contracts change.

## Protocol configuration & code references

### Registry (`protocol-registry.json`) — address → classification

| Concern | File |
|---------|------|
| Registry JSON (contracts, `families`, `supported_networks`, `event_types`, `method_ids`) | `backend/core/src/main/resources/protocol-registry.json` |
| Loader / service | `.../classification/registry/ProtocolRegistryLoader.java`, `ProtocolRegistryService.java`, `ProtocolRegistryEntry.java` |
| `family` enum (10) | `ProtocolRegistryFamily.java` — `DEX, LENDING, STAKING, BRIDGE, CUSTODY, AGGREGATOR, YIELD, WRAPPER, PERP, LP` |
| `role` enum (16) | `ProtocolRegistryRole.java` — `ROUTER, POOL, POSITION_MANAGER, FACTORY, BRIDGE_ENTRY, BRIDGE_EXIT, STAKE_CONTRACT, VAULT, REWARD_ROUTER, GAS_PAYER, WRAPPER_TOKEN, WRAPPER_CONTRACT, ORDER_VAULT, EXCHANGE_ROUTER, POSITION_ROUTER, ORDER_BOOK` |
| `event_type` enum | `ProtocolRegistryEventType.java` (the JSON `event_types` header now lists `LENDING_LOOP_REBALANCE`) |
| `specialHandler` enum (7) | `ProtocolRegistrySpecialHandlerType.java` — `BALANCER_VAULT, BALANCER_V3_VAULT, GMX_V2_EXCHANGE_ROUTER, LFJ_LB_PAIR, LFJ_LB_ROUTER, PENDLE_ROUTER, MORPHO_BUNDLER` |
| Registry-driven classifiers | `.../classification/onchain/protocol/registry/{RegistryDirectTypeClassifier,SpecialHandlerRegistryReviewClassifier,MethodAwareRegistryReviewClassifier}.java` |

### Protocol profiles (`protocols/*.json`) — identity + semantic-classifier hints

Consumed by `ProtocolResourceLoader` (`ProtocolResourceCatalog`) → `ProtocolResourceDefinition`.
9 profiles: `aave, balancer, cow, euler, gmx-v2, morpho, pendle, resolv, uniswap`.
Fields: `key, protocol, version, aliases, capabilities, families, clarificationHints,
markers`, plus the optional folded descriptor metadata (`semanticClassifier`,
`lpPresentation`, `lending`, `valuationSource` — present on `aave`, `gmx-v2`, `uniswap`;
canonical but not yet wired to a consumer). See [protocol-descriptor](protocol-descriptor.md).

Per-protocol **semantic classifiers** (`.../classification/onchain/protocol/**`,
auto-wired by `ProtocolSemanticService`):

| Classifier | Protocol | `protocols/*.json`? |
|------------|----------|---------------------|
| `BalancerProtocolSemanticClassifier` | Balancer | yes |
| `CowSwapProtocolSemanticClassifier` | CoW Swap | yes |
| `EulerProtocolSemanticClassifier` | Euler | yes |
| `GmxProtocolSemanticClassifier` | GMX V2 | yes |
| `MorphoProtocolSemanticClassifier` | Morpho | yes |
| `PendleProtocolSemanticClassifier` | Pendle | yes |
| `ResolvProtocolSemanticClassifier` | Resolv | yes |
| `EquilibriaProtocolSemanticClassifier` | Equilibria | no |
| `InitCapitalSemanticClassifier` | INIT Capital | no |
| `AuraProtocolSemanticClassifier` | Aura | no |

### Protocol descriptors — retired (folded into `protocols/*.json`)

The standalone `protocol-descriptors/*.json` plane, `ProtocolDescriptorLoader` /
`ProtocolDescriptorService`, and the descriptor behavior SPI (`descriptor/spi/*`) were
**deleted on 2026-07-16**. Their identity fields now live inside the matching
`protocols/*.json` profile (see above). See the
[consolidation assessment](../tasks/protocol-config-consolidation-assessment.md#5-outcome-implemented).

## CEX venues

Venue support is normalization-plane only (ADR-052); post-normalization modules read
the neutral contract, never `VenueDescriptor`. Descriptors are discovered by
`VenueRegistry` (`backend/core/src/main/java/com/walletradar/application/cex/`).

| Venue | Descriptor | Account suffixes | Ingested streams |
|-------|-----------|------------------|------------------|
| **Bybit** | `cex/acquisition/venue/bybit/BybitVenueDescriptor.java` | `:FUND`, `:UTA`, `:EARN`, `:BOT` (ADR-058) | `TRANSACTION_LOG, EXECUTION_{LINEAR,INVERSE,SPOT,OPTION}, FUNDING_HISTORY, INTERNAL_TRANSFER, UNIVERSAL_TRANSFER, DEPOSIT_{ONCHAIN,INTERNAL}, WITHDRAWAL, CONVERT_HISTORY, EARN_FLEXIBLE_SAVING` |
| **Dzengi** | `cex/acquisition/venue/dzengi/DzengiVenueDescriptor.java` | none (flat wallet) | `LEDGER, DEPOSITS, WITHDRAWALS, MY_TRADES, MY_TRADES_V2, TRADING_POSITIONS_HISTORY, EXCHANGE_INFO` |

- Extraction: `BybitExtractionService.java`, `DzengiExtractionService.java`
- Normalization: `cex/normalization/venue/{bybit,dzengi}/`, jobs in `cex/job/`
- SPI contract: [capability-behavior-spi](capability-behavior-spi.md#cex-ledger-spi-b1--adr-052)

## Bridges & cross-chain integrations

Bridge protocols are registered in `protocol-registry.json` (`family=BRIDGE` or
`GAS_PAYER` role) and paired at the linking stage:

| Integration | Registry | Linking / clarification service |
|-------------|----------|---------------------------------|
| LI.FI / Jumper | 12 contracts, 13 nets | `LiFiBridgePairLinkService`, `LiFiForeignDestinationReclassificationService`, `LiFiStatusGateway`, `LiFiDestinationDiscoverySupport` |
| Relay | 7 contracts (+`GAS_PAYER`) | `RelayBridgeClassificationSupport`, `CrossNetworkBridgePairFallbackService` |
| Across | 6 contracts | `AcrossBridgePairLinkService` |
| Mayan + Circle CCTP | CCTP 1, Mayan code | `MayanCctpBridgePairLinkService`, `MayanStatusGateway` |
| Stargate / LayerZero | Stargate 4; LZ executors in `KnownBridgeRouterRegistry` | `EtherFiOftBridgeInClassifier` (weETH OFT), `LiFiDestinationDiscoverySupport` |
| Native L1↔L2 (Arbitrum, Base, Optimism, Polygon, Linea, zkSync Era, Katana/AggLayer, Hop, Hyperlane, deBridge) | registry `BRIDGE` entries | generic `BridgeStartClassifier`, `CrossNetworkBridgePairFallbackService`, `BridgePairContinuityRepairService`, `RegistryBridgeInboundTypeCorrectionService` |

Orchestrated by `LinkingBatchProcessor`; known routers hard-listed in
`KnownBridgeRouterRegistry.java`. See
[linking rules](../pipeline/linking/02-rules-and-repairs.md).
