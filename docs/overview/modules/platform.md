# Platform layer (`platform`)

## Purpose

Technical **infrastructure adapters** shared by application bounded contexts: on-chain transport (RPC, explorer), persistence cross-cutting, security, telemetry, and job utilities. Must **not** own normalization rules, linking repairs, AVCO replay, or REST DTOs.

## Public port (`platform`)

Platform exposes concrete adapters and small interfaces — not BFF-stable business ports.

| Submodule | Doc | Key interfaces |
|-----------|-----|----------------|
| `platform.networks` | [platform-networks](platform-networks.md) | `NetworkAdapter`, `NetworkFamily`, `ExplorerProvider` |
| `platform.persistence` | [platform-persistence](platform-persistence.md) | Mongo helpers, index conventions |
| `platform.security` | [platform-security](platform-security.md) | Auth filters, session binding |
| `platform.telemetry` | [platform-telemetry](platform-telemetry.md) | Stage execution logging |
| `platform.common` | [platform-common](platform-common.md) | Shared job/support utilities |

## Owned collections (write owner)

None at the platform layer. Platform writes only through application-owned repositories.

## Read ports consumed

| Source | Purpose |
|--------|---------|
| `domain.common.NetworkId` | Adapter routing |

## Key packages

| Package | Responsibility |
|---------|----------------|
| `platform.networks` | EVM RPC/explorer, Solana RPC, `NetworkFamily` routing (B2) |
| `platform.networks.evm.*` | JSON-RPC, ABI decode, explorer providers |
| `platform.networks.solana` | Solana RPC client and adapter |
| `platform.persistence` | Shared Mongo configuration patterns |
| `platform.security` | Google SSO binding, session guards |
| `platform.telemetry` | Pipeline stage timing / structured logs |
| `platform.common` | Reusable job support |

## Allowed dependencies

- `domain.common` (enums, address format)
- Spring configuration for property beans
- Third-party HTTP / Mongo drivers

## Extension seams

- `NetworkFamily` — groups `NetworkId` values by transport semantics (EVM, Solana, TON); see [add-a-network](../../reference/extensibility/add-a-network.md)
- `NetworkAdapter` — per-network raw fetch for backfill
- `ExplorerProvider` / `EvmRpcClient` — swappable transport

## Worked example

1. `BackfillSegmentExecutor` resolves `NetworkId.ARBITRUM` → EVM `NetworkAdapter`.
2. Adapter batches `eth_getLogs` in 2000-block windows via `RpcEndpointRotator`.
3. Raw rows persist through `application.backfill`; platform returns `List<RawTransaction>` only.

## Microservice extraction

Platform becomes **shared infra modules** (networks-lib, persistence-lib) consumed by extracted pipeline workers. RPC credentials and endpoint rotation stay centralized.
