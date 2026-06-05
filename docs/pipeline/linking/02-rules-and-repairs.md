# Linking — Rules and Repairs

> **Last updated:** 2026-06-05  
> **Pipeline stage:** `LINKING`

This document describes the deterministic passes executed by `LinkingBatchProcessor` and the metadata contract they enforce. Pass order matters: later passes assume earlier pairing context exists.

## Processor architecture

```mermaid
flowchart TD
  LBP[LinkingBatchProcessor.processNextBatch]
  LBP --> BB[BybitBridgeLinkService]
  LBP --> LC[OnChainLifecycleLinkService]
  LBP --> Bridge[LiFi / Mayan / Across bridge pairers]
  LBP --> Cow[CowSwapEthFlowSettlementLinkService]
  LBP --> IT[InternalTransferPairLinkService]
  LBP --> Bybit[Bybit transfer / internal repairs]
  LBP --> Safety[Poisoning / scam / NFT / GMX refund]
  LBP --> Fallback[Orphan inbound pricing fallbacks]
  LBP --> Legacy[Bridge continuity legacy repairs]
  LBP --> Vault[TurtleVaultBurnRepairService]
```

## Pass order (verified)

| # | Service | Purpose |
|---|---------|---------|
| 1 | `BybitBridgeLinkService` | Match `withdraw_deposit` rows to on-chain legs; stamp `onChainCorrelation` on extracted events |
| 2 | `OnChainLifecycleLinkService` | Async request/settlement, LP, staking, order lifecycles |
| 3 | `LiFiBridgePairLinkService` | LI.FI / Jumper route-tagged bridge pairs |
| 4 | `MayanCctpBridgePairLinkService` | Mayan / CCTP terminal status pairs |
| 5 | `AcrossBridgePairLinkService` | Across `depositV3` → settlement |
| 6 | `CowSwapEthFlowSettlementLinkService` | CoW Eth Flow request → settlement |
| 7 | `InternalTransferPairLinkService` | Same-session wallet↔wallet same-tx pairs → `INTERNAL_TRANSFER` |
| 8 | `KnownBridgeRouterExternalTypeCorrectionService` | Promote misclassified inbound bridge receipts |
| 9 | `BybitTransferContinuityRepairService` | Restore `BYBIT:<NETWORK>:<txHash>` corridor metadata on wallet rows |
| 10 | `BybitInternalTransferExternalCpReclassifier` | Same-UID Bybit externals → internal where proved |
| 11 | `ProtocolAttributionClassifier` | Stamp protocol counterparties before cross-network pairing |
| 12 | `CrossNetworkBridgePairFallbackService` | Routed Relay settlement pairing |
| 13 | `AddressPoisoningDetector` | Exclude vanity-prefix dust IN |
| 14 | `ScamDisperseClonePhishingTagger` | Tag known phishing OUT |
| 15 | `GmxV2RefundClassifier` | GMX execution-fee refund attribution |
| 16 | `EtherFiOftBridgeInClassifier` | weETH OFT mint → `BRIDGE_IN` |
| 17 | `NftMintRetagger` | NFT mint OUT reclassification |
| 18 | `UnmatchedBridgeInboundPricingFallbackService` | Reprice unsupported OUT; demote orphan IN to ACQUIRE |
| 19 | `BybitInternalTransferPairer.repairAll()` | Second Bybit internal pairing pass |
| 20 | `BybitTransferContinuityRepairService` | Re-run after internal pairer (corridor anchor preservation) |
| 21 | `BybitOnChainEarnOrphanRepairService` | Synthesize missing Earn subscription inflow |
| 22 | `BybitInternalTransferOrphanFallbackService` | Demote singleton internals |
| 23 | `BybitInternalTransferPairer.pairDemotedEconOrphans()` | Pair demoted econ orphans |
| 24 | `UnmatchedExternalTransferInPricingFallbackService` | Orphan IN when OUT outside session |
| 25 | `BridgePairContinuityRepairService` | Legacy sealed pairs; inbound counterparty; sealed inbounds |
| 26 | `OnChainInternalTransferPairRepairService` | Same-tx internal orphans across session wallets |
| 27 | `TurtleVaultBurnRepairService` | Synthesize missing ERC-4626 burn on vault withdraw |

## Bybit ↔ on-chain corridor

`BybitBridgeLinkService` handles `withdraw_deposit` extracted events:

```mermaid
stateDiagram-v2
  [*] --> Candidate: txHash + networkId present
  Candidate --> MATCHED: raw + normalized on-chain leg found
  Candidate --> EXTERNAL_CUSTODY: receiver not in tracked wallets
  Candidate --> UNMATCHED: no on-chain leg
  MATCHED --> ClearedMetadata: clear bridge gap annotations
  EXTERNAL_CUSTODY --> Continuity: BYBIT_SHADOW_CUSTODY correlation
  UNMATCHED --> Blocked: BRIDGE_ON_CHAIN_LEG_NOT_FOUND
```

Rules:

- Canonical type stays `EXTERNAL_TRANSFER_IN` / `EXTERNAL_TRANSFER_OUT`
- Deterministic correlation: `BYBIT:<NETWORK>:<txHash>`
- Wallet row continuity restored in `BybitTransferContinuityRepairService` when Bybit leg already matched
- Dust/spam without real Bybit row must not self-promote into custody continuity

## Bridge pairing evidence

Routed bridge pairs require protocol-backed evidence — not loose time proximity:

| Bridge family | Pairing service | Evidence required |
|---------------|-----------------|-------------------|
| LI.FI / Jumper | `LiFiBridgePairLinkService` | Route-tagged source + registry settlement sender |
| Mayan / CCTP | `MayanCctpBridgePairLinkService` | Terminal Mayan status + receiving tx hash |
| Across | `AcrossBridgePairLinkService` | `depositV3` calldata + unique bounded destination |
| Relay fallback | `CrossNetworkBridgePairFallbackService` | LI.FI Diamond source + Relay infrastructure sender |
| EtherFi OFT | `EtherFiOftBridgeInClassifier` | Cross-chain mint proof |

LI.FI status rows where `sendingTxHash == receivingTxHash` are status echoes — never self-linked bridge continuity.

## Internal transfer pairing (FA-001)

`InternalTransferPairLinkService` promotes same-tx reciprocal legs when:

- Same `txHash + networkId`
- One principal outbound, one inbound
- Matching principal family and quantity (tiny tolerance)
- Both wallets in session universe

One-sided tracked-counterparty hints remain external until reciprocal canonical row exists.

Target reversible store: `transfer_links` per [ADR-003](../../adr/ADR-003-transfer-links-fa001.md).

## Protocol name enrichment

Linking may fill or repair labels but not economics:

| Stage | Service | Allowed |
|-------|---------|---------|
| Normalization | Registry / semantic classifiers | Direct `protocolName` hit |
| Clarification | `ProtocolNameEnrichmentService` | Receipt-safe enrichment |
| Linking | `ProtocolAttributionClassifier`, `GmxV2RefundClassifier` | Counterparty + attribution stamps |
| Repair sweep | Historical protocol-name job | Metadata only |

`protocolName` is **not** a lifecycle key, pairing key, or proof of basis continuity.

## Orphan and fallback repairs

When pairing fails but economics must not stay at $0:

| Scenario | Service | Effect |
|----------|---------|--------|
| `BRIDGE_OUT` with no priced upstream inflow | `UnmatchedBridgeInboundPricingFallbackService.reconcileUnsupportedOutbounds` | Reprice source so carry can propagate |
| `BRIDGE_IN` with no OUT partner in session | `…reconcileOrphanInbounds` | Demote to market-priced ACQUIRE |
| `EXTERNAL_TRANSFER_IN` with OUT outside universe | `UnmatchedExternalTransferInPricingFallbackService` | Orphan inbound ACQUIRE |
| Sealed bridge pair (`continuityCandidate=false`) | `BridgePairContinuityRepairService` | Promote continuity when pair proved |
| Linked IN missing flow counterparty | `…reconcilePairedInboundCounterparty` | Restore metadata for replay |

## Display vs lifecycle (reminder)

| Field | Layer | Linking may set? |
|-------|-------|------------------|
| `correlationId` | Lifecycle | Yes |
| `matchedCounterparty` | Lifecycle | Yes |
| `continuityCandidate` | Replay hint | Yes |
| `eventGroupId` | Display | No (read/overlay path) |
| `memberNormalizedTransactionIds` | Display | No |

## Rules by transaction type

Detailed linking actions per canonical type:

| Type | Metadata written | Typical services |
|------|------------------|------------------|
| `BRIDGE_OUT` | `correlationId`, `matchedCounterparty`, optional `continuityCandidate` | Bridge pairers, `BridgePairContinuityRepairService` |
| `BRIDGE_IN` | Pair to OUT; may promote from `EXTERNAL_TRANSFER_IN` | All bridge pairers, `EtherFiOftBridgeInClassifier` |
| `EXTERNAL_TRANSFER_OUT` → Bybit | `BYBIT:…` correlation, `continuityCandidate` | `BybitTransferContinuityRepairService` |
| `EXTERNAL_TRANSFER_IN` ← Bybit | Same | `BybitBridgeLinkService`, corridor repair |
| `INTERNAL_TRANSFER` | Same-tx `correlationId`, reciprocal `matchedCounterparty` | `InternalTransferPairLinkService`, `OnChainInternalTransferPairRepairService` |
| `LP_ENTRY_REQUEST` / `LP_EXIT_REQUEST` | Lifecycle `correlationId` to settlement | `OnChainLifecycleLinkService` |
| `DEX_ORDER_REQUEST` | Terminal settlement link | `CowSwapEthFlowSettlementLinkService` |
| `VAULT_WITHDRAW` (Turtle) | Synthetic burn flow | `TurtleVaultBurnRepairService` |
| `EXTERNAL_TRANSFER_IN` (poisoning) | `excludedFromAccounting` | `AddressPoisoningDetector` |
| `EXTERNAL_TRANSFER_OUT` (phishing) | Review / exclusion tags | `ScamDisperseClonePhishingTagger` |
| GMX refund rows | `protocolName` / attribution | `GmxV2RefundClassifier` |
| NFT mint OUT | Non-economic retag | `NftMintRetagger` |
| Bybit Earn subscription | Synthetic matching EARN inflow | `BybitOnChainEarnOrphanRepairService` |
| Bybit `INTERNAL_TRANSFER` orphan | Demote or re-pair | `BybitInternalTransferOrphanFallbackService`, `BybitInternalTransferPairer` |

Types with **no linking pairing** (normalization/clarification only): `SWAP`, `BORROW`, `REPAY`, `REWARD_CLAIM`, `FEE`, `APPROVE`, `ADMIN_CONFIG`, derivative collateral flows.
