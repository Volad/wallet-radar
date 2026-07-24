# ADR-066: Per-family counterparty resolution SPI and program-ID registry keying

**Status:** Accepted
**Date:** 2026-07-18
**Scope:** On-chain normalization enrichment (counterparty/protocol), protocol registry keying, Solana + TON
**Amends:** ADR-009 (Ownership Classification via AccountingUniverse), ADR-063 (Solana normalization via Helius)
**Related:** ADR-010 (flow-level counterparty), ADR-014 (portfolio conservation gate), ADR-064 (TON acquisition), ADR-065 (non-EVM backfill enablement)

---

## Context

After Solana (ADR-063) and TON (ADR-064) ingestion landed, on-chain rows for both networks were fetched and normalized, but the cost-basis replay gate stayed closed (`avcoReady=false`, `blockingNeedsReview≈498`) and all TON transactions collapsed to `UNKNOWN`. Root-cause audit (`results/solana-ton-*.md`) found:

1. **Counterparty/protocol enrichment is EVM-only and never runs for Solana/TON.** The enrichment step (`enrichCanonicalMetadata`) is invoked inline only inside the EVM `OnChainNormalizationService`. `SolanaNormalizationService`/`TonNormalizationService` build + mark complete without it, so `counterpartyType` stays null and `StatValidationService` permanently flags `STAT_COUNTERPARTY_TYPE_MISSING`.
2. **The resolution machinery is structurally EVM-bound.** `CounterpartyResolutionService` wraps raw in the EVM `OnChainRawTransactionView`; `OnChainRawTransactionView.normalizeAddress()` forces `0x…`/42-char; `CounterpartyEnrichmentService.loadRaw()` lowercases the case-sensitive base58 key. Even the **protocol registry loader** builds keys through `normalizeAddress`, so a base58 Solana program ID cannot be stored or looked up.
3. **Naive `switch(NetworkId)` inside the six shared services** would duplicate case-sensitive base58 / TON-canonical rules and multiply the EVM-regression surface.

The codebase already reasons in **network families** (`FlowCounterpartySupport.onChainAccountRef`, `WalletRef` domains, `NetworkAddressFormat`, venue-capability SPI in ADR-052).

## Decision

**1. Introduce a per-`NetworkFamily` `CounterpartyResolver` SPI.**
`CounterpartyEnrichmentService.enrichInPlace` selects a resolver by the transaction's network family:
- `EvmCounterpartyResolver` — the current EVM logic moved **verbatim** (guarantees byte-for-byte EVM behaviour).
- `SolanaCounterpartyResolver` — reads `SolanaRawTransactionView`: protocol identity from `instructions[].programId` → `counterpartyType=PROTOCOL` (+ protocolName); transfer peer from `nativeTransfers`/`tokenTransfers` → `AccountingUniverseService.classify` → `PERSONAL_WALLET`/`CEX`/`UNKNOWN_EOA`. Preserves case-sensitive base58.
- `TonCounterpartyResolver` — reads `TonRawTransactionView` and compares via `TonAddressCanonicalizer`.

**2. Family-aware address normalization for the protocol registry.**
Both `ProtocolRegistryLoader` (key-building at load) and `ProtocolRegistryService.lookup` select an `AddressNormalizer` by the entry's declared `networks`. EVM entries keep `0x`-lowercase normalization; Solana entries key on the raw base58 program ID. Program-ID entries must declare `networks: [SOLANA]`; mixed EVM+Solana entries are rejected so the normalizer choice is unambiguous. `OnChainRawTransactionView.normalizeAddress` becomes EVM-only.

**3. Shared `CanonicalMetadataEnricher` with per-family step opt-in.**
Enrichment is invoked inside each network's normalization service (consistent with the existing EVM inline model), but the shared enricher exposes steps that each family opts into. Solana/TON run **protocol-name + counterparty only**; EVM receipt-shaped steps (`registryBridgeInboundTypeCorrectionService`, `enrichProtocolFromReceiptIdentity`, `lendingReceiptIdentityService`) do not run on Helius/TON payloads until proven relevant.

**4. `FlowCounterpartySupport` boundary.**
The view-independent statics (`applyTransactionCounterparty`, `syncFlowsFromTransaction`, `flowsMissingCounterparty`, `onChainAccountRef`) stay shared. A parallel Solana peer-resolution path is added rather than switching inside the EVM-view-bound `enrichOnChainFlows`/`resolvePeerForFlow`.

## Rationale

| Alternative | Why rejected |
|---|---|
| `switch(NetworkId)` inside the six shared services | Duplicates base58/TON rules; large EVM-regression surface; violates single-responsibility |
| Only fix `ProtocolRegistryService.lookup` (not the loader) | Keys are built at load via `normalizeAddress` → base58 program IDs silently rejected/mis-keyed |
| Blanket-mirror EVM `enrichCanonicalMetadata` for Solana/TON | Runs EVM receipt-shaped steps on payloads that have no receipts — at best no-op, at worst wrong |
| Route Solana/TON through the EVM `OnChainRawTransactionView` | `normalizeAddress` destroys base58/TON identifiers |

The SPI concentrates each network's identity rules in one class, keeps the EVM path untouched, and matches the codebase's existing family-oriented boundaries.

## Consequences

- New `CounterpartyResolver` SPI with `Evm`/`Solana`/`Ton` implementations; `CounterpartyEnrichmentService` dispatches by family.
- `protocol-registry.json` gains Solana program-ID entries (`networks: [SOLANA]`); loader + lookup become family-normalizer-aware.
- `SolanaNormalizationService`/`TonNormalizationService` invoke the shared `CanonicalMetadataEnricher` (Solana/TON step set).
- EVM behaviour is unchanged and asserted by existing EVM tests plus explicit EVM fixtures.
- Determinism preserved: program-ID map is static config; net-delta direction is a pure function of stored balance deltas; TON canonicalization is deterministic (CRC16). No new read-path RPC.

## Amendment note to ADR-014

The replay-safe promotion path (`StatValidationService.promoteReplaySafeNeedsReview` / `hasReplaySafeFeeOnlyFlows`) must not treat an on-chain `UNKNOWN` row that had non-zero raw value but produced only a FEE flow as replay-safe (it silently confirmed TON rows whose economic value was dropped). The guard is scoped to that evidence condition and asserted not to change EVM promotion via EVM fixtures.
