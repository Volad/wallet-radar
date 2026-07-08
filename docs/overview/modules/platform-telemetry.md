# Platform telemetry (`platform.telemetry`)

## Purpose

Pipeline observability snapshots for operator dashboards and health endpoints — read-only aggregation of stage progress metrics.

## Key packages

| Package | Responsibility |
|---------|----------------|
| `platform.telemetry` | `PipelineTelemetrySnapshot`, `PipelineTelemetrySnapshotService` |

## Allowed dependencies

- `application` read ports or Mongo queries for pipeline state (no write side effects)

## Extension seams

`PipelineTelemetrySnapshot` DTO — extend fields as new pipeline stages gain metrics.
