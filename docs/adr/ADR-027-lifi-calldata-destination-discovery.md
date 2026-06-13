# ADR-027 — Protocol-corroborated calldata wallet relevance for LiFi destination discovery

**Status:** Accepted  
**Date:** 2026-06-09  
**Inputs:** `docs/tasks/lifi-layerzero-destination-discovery-implementation-plan.md`, audit blockers L-LIFI-DEST-MISS-01 / L-BRIDGE-BASIS-GAP-01

## Context

Official LiFi `DONE` status names a receiving transaction hash for cross-chain routes. `LiFiReceivingTransactionDiscoveryService` ingests that hash via targeted fetch across tracked wallets.

LayerZero v2 `execute302` settlements (common on LiFi/Stargate routes) often credit the beneficiary only inside nested calldata. Explorer APIs may show **no** token/internal transfer rows naming the wallet. The prior gate (`from`/`to`, token transfer endpoints, internal transfer endpoints) rejected these destinations even when LiFi status and calldata proved the beneficiary.

Downstream: source-only anchor, `continuityCandidate=false`, orphan `CARRY_OUT` without `CARRY_IN`, family basis leak.

## Decision

### D1. LiFi-scoped evidence ladder (deterministic)

`LiFiDestinationDiscoverySupport.resolveWalletRelevance` accepts a destination for ingestion when **any** path matches, evaluated in order:

1. **`WALLET_TOUCH`** — existing `hasWalletTouchEvidence` (top-level endpoints, token/internal transfers)
2. **`TRACE`** — positive inbound internal transfer credit already present in explorer raw (subset refinement)
3. **`LIFI_CALLDATA`** — all of:
   - LiFi status `apiStatus=DONE`
   - receiving tx hash matches fetch target
   - settlement selector on allowlist (`BridgeSettlementSupport.isSettlementSelector`, includes `execute302`)
   - beneficiary wallet decoded from ABI address words in calldata (structured scan, not free-text substring)

Registry hit on `to`/`from` strengthens the match but is **not required** when the settlement selector is already allowlisted.

### D2. LiFi-corroborated settlement leg enrichment (Gate B)

When path is `LIFI_CALLDATA` and explorer raw lacks wallet-touch inbound legs, discovery synthesizes one **explorer-shaped internal transfer** before classification:

- Quantity from paired source `BRIDGE_OUT` principal (LiFi `sendingTxHash`)
- Sender from settlement executor / registry bridge contract
- Recipient = tracked beneficiary wallet
- Tagged `discoverySource=LIFI_CORROBORATED_SETTLEMENT` on raw only

Never synthesize from calldata alone without LiFi `DONE` and source principal evidence.

### D3. Module boundaries

- **`LiFiReceivingTransactionDiscoveryService`** — fetch, relevance, enrichment stamp, classify, persist
- **`LiFiBridgePairLinkService`** — unchanged pairing contract; calls `findOrDiscover(status)`
- **`hasWalletTouchEvidence`** — unchanged shared primitive (Mayan reuse)
- **`BridgePairContinuityRepairService`** — legacy repair only, not P0 path

### D4. Source wallet scan order

When LiFi status includes `sendingTxHash`, scan that source wallet **first** before other tracked wallets (deterministic cost optimization).

## Consequences

- LayerZero/Stargate calldata-only settlements become ingestible without per-tx production exceptions
- Move-basis pairing and replay carry can complete for ETH↔ETH routes when qty-compatible
- False-positive risk bounded by: LiFi `DONE` + settlement selector allowlist + structured beneficiary decode + source principal corroboration for synthesis
- `docs/pipeline/normalization/rules/protocols/li-fi.md` updated to document the ladder (replaces calldata-only prohibition)

## Related

- `LiFiDestinationDiscoverySupport`
- `LiFiReceivingTransactionDiscoveryService`
- `BridgeSettlementSupport`
- Anchor regression: `0x4890e907…` → `0x25550cf1…`
