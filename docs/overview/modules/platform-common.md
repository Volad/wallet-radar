# Platform common (`platform.common`)

## Purpose

Shared cross-cutting utilities with no domain or application coupling: retry policy, refresh status enums, and stage execution logging helpers.

## Key packages

| Package | Responsibility |
|---------|----------------|
| `platform.common` | `RetryPolicy` |
| `platform.common.refresh` | `RefreshStatus`, `RefreshTrigger` |
| `platform.common.job` | `StageExecutionLogSupport` |

## Allowed dependencies

- JDK and small third-party libs only (no `domain`, `application`, `api`).

## Extension seams

None — keep this module minimal; promote domain concepts out rather than growing platform.common.
