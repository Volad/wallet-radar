# Application pipeline (`application.pipeline`)

## Purpose

Cross-stage orchestration helpers shared by multiple pipeline apps (backfill → normalization → linking → replay). Not a write owner — coordinates logging and BSON coercion utilities.

## Key packages

| Package | Responsibility |
|---------|----------------|
| `application.pipeline.job.support` | Stage execution logging bridges |
| `application.pipeline.pipeline.support` | `BsonCoercionSupport` for Mongo operator payloads |

## Allowed dependencies

- `platform.common.job`
- `domain` / Mongo driver types for coercion helpers only

## Extension seams

Promote stage-specific orchestration into the owning `application.*` module; keep this package thin.
