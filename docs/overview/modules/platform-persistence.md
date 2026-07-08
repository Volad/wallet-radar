# Platform persistence (`platform.persistence`)

## Purpose

MongoDB infrastructure shared across application modules: type converters and base `MongoConfig`.

## Key packages

| Package | Responsibility |
|---------|----------------|
| `platform.persistence.config` | `MongoConfig`, `Decimal128` ↔ `BigDecimal` converters |

## Allowed dependencies

- Spring Data MongoDB
- `domain` value types used in converters only

## Extension seams

None — application modules own their `@Document` models and repositories.
