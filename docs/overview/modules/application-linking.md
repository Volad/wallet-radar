# Application linking (`application.linking`)

## Purpose

Pairs and clarifies normalized flows: bridge corridors, internal transfers, counterparty enrichment, and scam/protocol attribution before cost-basis replay.

## Owned collections (write owner)

| Collection | Role |
|------------|------|
| `normalized_transactions` | Linking metadata updates (correlation ids, counterparty fields) |

## Key packages

| Package | Responsibility |
|---------|----------------|
| `application.linking.job` | `LinkingJob`, batch processor, data gates |
| `application.linking.pipeline.clarification` | Per-protocol link and repair services |
| `application.linking.query` | `LinkingPendingStatusQuery` (read-only pending counts) |
| `application.linking.config` | `LinkingProperties` |

## Allowed dependencies

- `application.normalization` view/support types (not job triggers)
- `application.cex` normalization helpers for Bybit-specific pairing
- `platform.networks` for receipt/status gateway HTTP only
- `pricing` read paths for unmatched inbound fallbacks

## Extension seams

- Clarification services registered in `LinkingBatchProcessor`
- `FlowCounterpartySupport`, `CounterpartyType` — shared counterparty vocabulary
