# Module index

Per-bounded-context documentation for the WalletRadar layered backend model.  
Template: [_template.md](_template.md). CI guard: `DocumentationCoverageTest.required_module_pages_exist`.

## Layers

| Layer | Pages |
|-------|-------|
| **canonical** | [canonical.md](canonical.md) |
| **platform** | [platform.md](platform.md) · [platform-networks](platform-networks.md) · [platform-persistence](platform-persistence.md) · [platform-security](platform-security.md) · [platform-telemetry](platform-telemetry.md) · [platform-common](platform-common.md) |
| **application** | [application-backfill](application-backfill.md) · [application-cex](application-cex.md) · [application-normalization](application-normalization.md) · [application-linking](application-linking.md) · [application-costbasis](application-costbasis.md) · [application-portfolio](application-portfolio.md) · [application-pricing](application-pricing.md) · [application-lending](application-lending.md) · [application-liquiditypools](application-liquiditypools.md) · [application-session](application-session.md) · [application-pipeline](application-pipeline.md) |
| **api (BFF)** | [api-bff.md](api-bff.md) |

## Track A required pages

These six pages are enforced by `DocumentationCoverageTest`:

- [canonical.md](canonical.md)
- [platform.md](platform.md)
- [application-cex.md](application-cex.md)
- [application-costbasis.md](application-costbasis.md)
- [application-portfolio.md](application-portfolio.md)
- [api-bff.md](api-bff.md)

## Extensibility

| Guide | SPI |
|-------|-----|
| [Add a network](../../reference/extensibility/add-a-network.md) | `NetworkFamily` (B2) |
| [Add a protocol](../../reference/extensibility/add-a-protocol.md) | `AbstractProtocolCapabilityContractTest` (B3) |
| [Add a CEX integration](../../reference/extensibility/add-an-integration.md) | `CexLedgerSource` / `CexVenueProfile` / `CexLedgerEvent` (B1) |

See also [capability-behavior-spi](../../reference/capability-behavior-spi.md) and [protocol-descriptor](../../reference/protocol-descriptor.md).

## Related

- [Architecture overview](../03-architecture.md)
- [Extensibility implementation plan](../../tasks/extensibility-refactor-implementation-plan.md)
