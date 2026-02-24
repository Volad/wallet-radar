---
name: worker-config-conventions
description: WalletRadar worker conventions for configuration: document all properties in application.yml and keep properties/config classes in the module's config package. Use when implementing or refactoring configuration, adding new @ConfigurationProperties or @Configuration beans, or when the user asks about config layout or application.yml.
---

# Worker config conventions

When implementing or changing configuration in the WalletRadar backend, follow these two rules.

## 1. All properties in application.yml

- **Every** configuration property used by the app must be **documented in** `backend/src/main/resources/application.yml`.
- When adding a new property (e.g. new `@ConfigurationProperties` or `@Value`):
  1. Add the key and a sensible default or example under the appropriate `walletradar.*` (or `spring.*`) section in `application.yml`.
  2. Add a short comment if the meaning or allowed values are not obvious.
- Existing properties (e.g. `walletradar.rpc.evm.endpoints`, `walletradar.ingestion.evm.batch-block-size`) must remain reflected in `application.yml`; if you introduce a new key in code, add it to the YAML in the same change.

**Example:** Adding `walletradar.ingestion.evm.endpoints-per-network` → add the nested map or commented example under `walletradar.rpc.evm` in `application.yml`.

## 2. Config classes in the module's config package

- **`@ConfigurationProperties`** classes (property wrappers) and **`@Configuration`** classes that define beans for a **specific module** must live in that module’s **`config`** package, not in the feature package (e.g. not in `adapter`, `store`, `service`).
- Package pattern: `com.walletradar.<module>.config`.

| Module    | Config package                    | Examples |
|-----------|-----------------------------------|----------|
| ingestion | `com.walletradar.ingestion.config` | `EvmIngestionProperties`, `IngestionAdapterConfig` (RPC endpoints, EVM batch size, rotator/client beans) |
| costbasis | `com.walletradar.costbasis.config` | Future: cost basis / recalc properties and config beans |
| pricing   | `com.walletradar.pricing.config`   | Future: price provider URLs, timeouts |
| snapshot  | `com.walletradar.snapshot.config`  | Future: snapshot cron, limits |
| (root)    | `com.walletradar.config`           | App-wide only: Mongo, Async, Caffeine, Scheduler, OTel |

- **Root `config/`** is only for app-wide concerns (Mongo, async executors, cache, scheduler). Module-specific settings and beans belong in `<module>/config`.
- When creating a new `@ConfigurationProperties` or a `@Configuration` that wires a module (e.g. ingestion adapters), place the class in `<module>/config` and update imports in that module.

**Example:** `EvmIngestionProperties` and `IngestionAdapterConfig` belong in `com.walletradar.ingestion.config`, not in `com.walletradar.ingestion.adapter`. Move them there and fix any references (e.g. in `EvmNetworkAdapter`, `EvmBatchBlockSizeResolver`, tests).

## Checklist

- [ ] New or changed properties are added or updated in `backend/src/main/resources/application.yml`.
- [ ] New `@ConfigurationProperties` / `@Configuration` for a module live in `com.walletradar.<module>.config`.
- [ ] Imports and references (including tests) are updated after moving config classes.
