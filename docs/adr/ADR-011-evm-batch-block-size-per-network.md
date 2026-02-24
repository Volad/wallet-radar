# ADR-011: Configurable EVM eth_getLogs Batch Block Size per Network

**Date:** 2025  
**Status:** Accepted  
**Deciders:** Solution Architect, Business Analyst

---

## Context

EVM ingestion uses `eth_getLogs` in block-range batches. A single global batch size does not fit all networks: public RPC limits, L1 vs L2 block throughput, and rate limits vary. We need to configure batch size per EVM network (or at least different defaults for L1 vs L2) without failing ingestion for unknown or new networks.

Product constraints (docs/00-context.md, docs/tasks/00-ingestion-core.md) require:
- Configurable batch size **per EVM network** (or L1/L2 defaults).
- Global default **2000 blocks** when no per-network value is set.
- Invalid values (≤0 or above a reasonable cap) must not be applied; use default and log.
- Unknown or new EVM `networkId` → use global default; **do not fail** ingestion.

---

## Decision

1. **Per-network configuration**
   - Batch block size for `eth_getLogs` is **configurable per EVM `networkId`**.
   - Optionally, different defaults for L1 vs L2 may be supported (e.g. global default for L1, another default for L2); if not implemented in v1, per-network map is sufficient.

2. **Config format**
   - Use Spring Boot configuration with a **per-network map**, e.g.:
     - `walletradar.ingestion.evm.batch-block-size[<networkId>]=<N>` (e.g. `batch-block-size[1]=2000`, `batch-block-size[42161]=1000`), or
     - Equivalent YAML structure under `walletradar.ingestion.evm` key, e.g. `batch-block-size` as a map from networkId (string or number) to integer.
   - A **global default** key (e.g. `default` or a single `batch-block-size` scalar) may be used for the fallback value; if absent, the hard-coded default is **2000**.

3. **Validation rules**
   - **Minimum:** 1 (no zero or negative batch size).
   - **Maximum:** A reasonable cap (e.g. **10_000** blocks) to avoid RPC timeouts and provider rejections; exact cap is implementation-defined and should be documented.
   - If the configured value is ≤0 or above the cap: **do not apply**; use the global default (2000) and **log a warning**.

4. **Resolution**
   - **EvmNetworkAdapter** (or a dedicated ingestion config bean, e.g. `IngestionAdapterConfig`) **resolves** the batch size by `networkId` at runtime:
     - If a value exists for the given `networkId` and passes validation → use it.
     - If the value is invalid (≤0 or > cap) → use global default 2000 and log warning.
     - If `networkId` is unknown (no key in config) → use **global default 2000**; **do not fail** ingestion.

---

## Rationale

- **Cost and reliability:** Smaller batches on rate-limited or strict RPCs reduce failed requests; larger batches where allowed reduce round-trips.
- **No failure on unknown networks:** New or unsupported EVM chains can be added without code change; they get the safe default.
- **Single place of resolution:** EvmNetworkAdapter or ingestion config keeps resolution logic in one place and avoids magic constants in the adapter.

---

## Consequences

- **docs/02-architecture.md:** Ingestion adapter section states that EVM batch size is configurable per network (default 2000) and references this ADR.
- **docs/00-context.md** and **docs/tasks/00-ingestion-core.md:** Already state the product requirement; implementation must align with this ADR.
- **Implementation:** EvmNetworkAdapter (or ingestion config) must read from the configured map, validate, and fall back to 2000 for missing/invalid/unknown networkId.

---

## Note: config layout superseded by ADR-012

The **config layout** (separate `walletradar.ingestion.evm.batch-block-size` and RPC endpoints elsewhere) is superseded by **ADR-012** (unified per-network config under `walletradar.ingestion.network` with `urls` and `batch-block-size` per network). Batch size **semantics and defaults** (min 1, max cap, fallback 2000, unknown network → default without failure) remain as defined in this ADR.
