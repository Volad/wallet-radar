# Platform networks (`platform.networks`)

## Purpose

Transport and ABI layer for on-chain RPC and explorer access. Owns `NetworkAdapter` implementations (EVM RPC/explorer, Solana RPC) and endpoint rotation — **no** normalization, linking, or accounting logic.

## Key packages

| Package | Responsibility |
|---------|----------------|
| `platform.networks` | `NetworkAdapter`, `RpcEndpointRotator`, resolvers |
| `platform.networks.evm.rpc` | EVM JSON-RPC clients and adapters |
| `platform.networks.evm.explorer` | Explorer providers (Etherscan, BlockScout) |
| `platform.networks.evm.abi` | ABI decode helpers |
| `platform.networks.solana` | Solana RPC client and adapter |
| `platform.networks.config` | `walletradar.ingestion.*` network property bindings |

## Allowed dependencies

- `domain.common` (e.g. `NetworkId`) where unavoidable for adapter routing
- Spring config for property beans only

## Extension seams

- `NetworkAdapter` — per-network raw fetch entry point for backfill
- `ExplorerProvider` / `EvmRpcClient` — swappable transport implementations
