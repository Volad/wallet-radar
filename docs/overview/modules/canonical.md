# Canonical layer (`canonical`)

## Purpose

Owns **pure, framework-free** vocabulary shared across normalization, linking, and cost-basis replay: correlation key prefixes, venue constants, and deterministic helpers. Must **not** import Spring, Mongo, HTTP clients, or pipeline jobs.

## Public port (`canonical`)

No Spring beans. Other modules import static constants and pure types only.

| Type | Role |
|------|------|
| `canonical.correlation.CorrelationContract` | Bybit corridor / earn / IT bundle prefix constants |
| `canonical.correlation.BybitCarryContinuitySupport` | Pure carry-continuity helpers |

Note: the earlier `ProtocolDescriptor` A5 value types were retired (2026-07-16);
protocol identity now lives in `protocols/*.json` → `ProtocolResourceDefinition`
(application layer). See [protocol-descriptor](../../reference/protocol-descriptor.md).

## Owned collections (write owner)

None. Canonical is not a persistence owner.

## Read ports consumed

None.

## Key packages

| Package | Responsibility |
|---------|----------------|
| `canonical.correlation` | Cross-stage correlation prefixes and pure continuity helpers |

## Allowed dependencies

- JDK only
- Other `canonical.*` packages

## Extension seams

- New correlation prefix families require ADR + updates to `CorrelationContract`
- `ModuleBoundaryTest.canonical_must_not_depend_on_spring_or_mongo` enforces purity

## Worked example

1. Bybit normalization assigns `correlationId = bybit-econ-v1:{uid}:{coin}:{amount}`.
2. Linking reads the same prefix from `CorrelationContract` to pair internal transfers.
3. `TransferReplayHandler` resolves carry source using `BYBIT_EARN_CARRY_PREFIX` without importing CEX services.

## Microservice extraction

Canonical ships as a **shared library JAR** with zero runtime deps. Any extracted service that replays or links must depend on the same artifact to keep correlation keys stable.
