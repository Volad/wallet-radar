# Cross-Cutting Classification Owners

> **Last updated:** 2026-06-05  
> Owners outside the 11 product families documented in [families/](families/).

These classifiers run in dedicated `OnChainClassificationInsertionPoint` stages before or alongside family classifiers.

| Owner | Class | Stage | Output types |
|-------|-------|-------|--------------|
| Failed execution | `FailedExecutionClassifier` | EARLY_GUARDS | Non-economic / excluded |
| Admin config | `AdminConfigClassifier`, `ResolvedWarningAdminConfigClassifier` | EARLY_GUARDS / late | `ADMIN_CONFIG` |
| Wrapped native | `WrappedNativeClassifier` | EARLY_GUARDS | `WRAP`, `UNWRAP` |
| Bridge settlement (in) | `BridgeSettlementClassifier` | EARLY_GUARDS | `BRIDGE_IN` |
| Reward route | `RewardRouteClassifier` | EARLY_GUARDS | Routes to claim types |
| Pre-spam unknown | `PreSpamUnknownClassifier` | PRE_SPAM_REVIEW | Parking before spam |
| Spam | `SpamClassifier` | POST_SPAM_REVIEW | Spam tagging |
| Non-economic | `NonEconomicClassifier` | POST_SPAM_REVIEW | No economic movement |
| Method id fallback | `MethodIdClassifier` | FINAL_FALLBACK | Various from selector map |
| Function name fallback | `FunctionNameClassifier` | FINAL_FALLBACK | Bridge/heuristic |
| Heuristic fallback | `HeuristicClassifier` | FINAL_FALLBACK | Residual bridge/LP/swap |

## Support classes (critical rules)

| Class | Rule |
|-------|------|
| `MovementLegExtractor` | Authoritative legs from raw view |
| `LiFiRouteSupport` | LI.FI/Jumper route tag in calldata |
| `BridgeSettlementSupport` | Known BRIDGE_IN selectors |
| `InboundSignalSupport` | Reward vs transfer vs promo signals |
| `ClarificationEligibilitySupport` | Receipt-clarification gate |
| `SponsoredGasTopUpSupport` | Protocol-funded gas top-ups → `SPONSORED_GAS_IN` |

Path prefix: `backend/.../ingestion/pipeline/classification/support/`

## Related

- [On-chain classification](../02-onchain-classification.md)
- [Classification stages ADR](../../../adr/ADR-001-onchain-classification-strangler-refactor.md)
