# Supported Networks and Protocols

> **Last updated:** 2026-06-05

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

Config prefix: `walletradar.ingestion.network.<NETWORK>` in `application.yml`.

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
| Euler | 4 | LINEA, PLASMA | LENDING | dedicated-semantic | yes | [doc](../pipeline/normalization/rules/protocols/euler.md) |
| Fluid | 6 | ARBITRUM, PLASMA | DEX, LENDING, YIELD | dedicated-semantic | no | [doc](../pipeline/normalization/rules/protocols/fluid.md) |
| Frax | 2 | ETHEREUM | STAKING | registry-backed | no | — |
| FusionX | 4 | MANTLE | DEX | registry-backed | no | — |
| GMX | 13 | ARBITRUM, AVALANCHE | PERP, YIELD | dedicated-semantic | yes | [doc](../pipeline/normalization/rules/protocols/gmx-v2.md) |
| Hop Protocol | 3 | ARBITRUM, ETHEREUM | BRIDGE | registry-backed | no | — |
| Hyperlane | 2 | ARBITRUM, ETHEREUM | BRIDGE | registry-backed | no | — |
| Hyperliquid | 1 | ARBITRUM | CUSTODY | heuristic-only | no | — |
| Katana | 1 | KATANA | DEX | heuristic-only | no | — |
| Katana/AggLayer | 1 | KATANA | BRIDGE | heuristic-only | no | — |
| KyberSwap | 1 | ARBITRUM, AVALANCHE, BASE, BSC (+5) | AGGREGATOR | heuristic-only | no | — |
| Lagoon | 4 | ARBITRUM, AVALANCHE, BASE, ETHEREUM (+2) | YIELD | registry-backed | no | — |
| Lendle Rewards Station | 1 | MANTLE | YIELD | heuristic-only | no | — |
| LFJ | 2 | ARBITRUM, AVALANCHE | AGGREGATOR, DEX | registry-backed | no | — |
| LI.FI | 3 | ARBITRUM, BASE, BSC, ETHEREUM (+1) | BRIDGE | dedicated-semantic | no | [doc](../pipeline/normalization/rules/protocols/li-fi.md) |
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
| Radiant | 1 | ARBITRUM, BSC | LENDING | heuristic-only | no | — |
| Relay | 3 | ARBITRUM, AVALANCHE, BASE, BSC (+7) | AGGREGATOR, BRIDGE | registry-backed | no | — |
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
