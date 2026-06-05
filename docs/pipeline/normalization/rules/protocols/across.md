# Across

> **Registry family:** BRIDGE  
> **Classifiers:** `BridgeMethodAwareClassifier`, `ZkSyncAcrossRoutedBridgeClassifier`, `FunctionNameClassifier`

## Scope

Across V3 `depositV3` bridge-out and destination settlement; zkSync routed source bridge variants.

## Owned normalized types

- `BRIDGE_OUT` — source chain deposit
- `BRIDGE_IN` — destination settlement (via `BridgeSettlementClassifier` or linking promotion)

## Authoritative evidence

- Across router/spoke addresses in registry or `KnownBridgeRouterRegistry`
- Method selector `depositV3`
- Calldata route parameters for source-led continuity repair

## Clarification policy

May promote inbound `EXTERNAL_TRANSFER_IN` to `BRIDGE_IN` during linking when source `BRIDGE_OUT` linked.

## Correlation rules

`correlationId` pairs OUT/IN; `continuityCandidate=true` after repair.

## Disallowed fallbacks

Plain external transfer when Across calldata route proven.

## Related

- [Bridge family](../families/bridge.md)
- [Linking examples](../../../../examples/linking-examples.md)
