# Module documentation template

Use this template for every bounded context under `docs/overview/modules/<module>.md`.
CI doc-coverage guard fails if an `application.*` or `platform.*` package lacks a matching page.

## Purpose

One paragraph: what this module owns and what it must **not** do.

## Public port (`application.<name>.port`)

Interfaces and boundary DTOs other apps and the BFF may import — the stable contract.

## Owned collections (write owner)

| Collection | Role |
|------------|------|
| | |

## Read ports consumed

| Source module | Port / DTO | Purpose |
|---------------|------------|---------|
| | | |

## Key packages

| Package | Responsibility |
|---------|----------------|
| `port/` | |
| `domain/` | |
| `service/` | |
| `infrastructure/` | |

## Allowed dependencies

- `canonical`
- `platform`
- Other apps: `application.*.port` only

## Extension seams

SPIs this module exposes (e.g. `ReplayHandler`, `LpPositionReader`).

## Worked example

Synthetic end-to-end flow through this module (placeholder hashes only).

## Microservice extraction

What becomes the deployable boundary and wire contract.
