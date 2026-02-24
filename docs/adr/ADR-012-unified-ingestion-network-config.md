# ADR-012: Unified Per-Network Ingestion Config

**Date:** 2025  
**Status:** Accepted  
**Deciders:** Solution Architect, Business Analyst

---

## Context

Ingestion settings were split across two configuration areas:

- **RPC endpoints:** `walletradar.rpc.evm.endpoints-per-network` (per-network URL lists).
- **Batch block size:** `walletradar.ingestion.evm.batch-block-size` (per-network batch size for `eth_getLogs`, see ADR-011).

The RPC endpoint rotator did not consistently use per-network endpoints from the same source of truth, and having two separate blocks made it unclear which networks were fully configured and increased the risk of misconfiguration (e.g. endpoints for a network but no batch size, or vice versa). A single per-network config block improves clarity and ensures rotator and batch-size resolver use the same network key (e.g. `NetworkId` name).

---

## Decision

1. **Single per-network config block**
   - Introduce one block: **`walletradar.ingestion.network`**.
   - Map key = **`NetworkId` name** (e.g. `ETHEREUM`, `ARBITRUM`, `POLYGON`, …).
   - Each entry contains:
     - **`urls`** — list of RPC endpoint URLs (used by the rotator for that network).
     - **`batch-block-size`** — integer; batch size for `eth_getLogs` (or equivalent) for that network.

2. **Configuration properties**
   - One `@ConfigurationProperties` class (e.g. **`IngestionNetworkProperties`**) with a nested type **`NetworkIngestionEntry`** (or equivalent) holding `urls` and `batch-block-size`.
   - The root binds the map under `walletradar.ingestion.network`; key = network id name, value = `NetworkIngestionEntry`.

3. **Deprecation / removal**
   - **Remove** (or stop using):
     - `walletradar.rpc.evm.*` (including `endpoints-per-network` and any legacy single list).
     - `walletradar.ingestion.evm.batch-block-size` (and any global EVM-only batch size key).
   - **EvmBatchBlockSizeResolver** and the RPC **rotator(s)** read from the new `walletradar.ingestion.network` map only.

4. **Relation to ADR-011**
   - **ADR-011** remains the authority for **batch size semantics and defaults** (e.g. min 1, max cap, fallback 2000, unknown network → default, no failure).
   - The **config layout** (where batch size is defined) is **superseded by this ADR**: batch size is now under `walletradar.ingestion.network.<networkId>.batch-block-size`.

---

## Consequences

- **Config:** Single source of truth per network: `walletradar.ingestion.network` with `urls` and `batch-block-size` per `NetworkId` name.
- **Code:** EvmBatchBlockSizeResolver and rotator(s) use `IngestionNetworkProperties` (or equivalent); no references to `walletradar.rpc.evm.*` or `walletradar.ingestion.evm.batch-block-size`.
- **Docs:** `docs/02-architecture.md` describes network config under `walletradar.ingestion.network` and references this ADR.
- **ADR-011:** Updated with a note that its config layout is superseded by ADR-012; batch size semantics and defaults are unchanged.
