# ADR-073: Unified token-metadata resolution — descriptor override → persistent cache → live resolver → unresolved

**Status:** Accepted
**Date:** 2026-07-20
**Scope:** Normalization/enrichment token identity (symbol + decimals) for all networks; retires `token-metadata.json`

---

## Context

Token `symbol` and `decimals` were resolved from a checked-in `backend/core/src/main/resources/token-metadata.json` quad-store with four disjoint groups:

- `solanaSplTokens` (SPL USDC/USDT/wSOL) — read by `SolanaSplTokenMetadataRegistry`.
- `tonJettons` (USDT-TON + hand-added RWA/xStock masters) — read by `TonJettonMetadataRegistry`.
- `fallbackTokens` / `builderTokens` (EVM) — read by `TokenMetadataRegistry` / `OnChainNormalizedTransactionBuilder`.

This had three problems:

1. **Hand-maintained coverage.** Every new SPL mint / jetton master had to be added by hand; live-traded RWA/xStock jettons and long-tail SPL mints were perpetually stale.
2. **No live path at normalization.** A live Solana resolver (`JupiterSplTokenMetadataResolver`, ADR-068) existed but was wired only at read paths; TON had no live jetton metadata resolver at all.
3. **`decimals` is financially load-bearing.** `qty = raw / 10^decimals` feeds AVCO and value. A wrong live-defaulted decimals (e.g. a jetton defaulting to 9) would silently corrupt an asset's entire recomputed history. Some tokens **misreport** decimals on-chain (e.g. `soUSDC` reports 12), so a hardcoded override is irreducible for those.

Normalization is a **replayable background pipeline** and there is an ArchUnit rule that **replay must not call RPC**. Caffeine alone is insufficient: it is lost on restart, and a 2-year renormalization would re-hit Jupiter/toncenter for thousands of mints and could return **different symbols across runs** (non-deterministic history).

---

## Decision

Introduce a single deterministic resolution order used everywhere token metadata is resolved at **normalization**:

```
descriptor override → persistent cache → live resolver (write-through) → explicit `unresolved`
```

### Tiers

1. **Descriptor override (highest priority, checked in).** An explicit per-network `token-overrides` map lives in `network-descriptors.yml` (single source of truth — **no** per-token metadata is hardcoded in Java). Each entry may carry `symbol`, `decimals`, and/or `decimal-override`. This tier carries the **irreducible, load-bearing** entries that must survive deletion of the JSON:
   - `soUSDC` decimals **12** (Solana; on-chain misreport)
   - USDT-TON decimals **6**
   - SPL USDC / USDT decimals **6**
   - AMZNx / MSTRx decimals **8** (TON xStock)
   - XAUT0 decimals **6** (TON RWA gold)
   - genuinely load-bearing EVM `fallbackTokens`/`builderTokens` decimals (symbol-only redundant entries were dropped — live RPC + descriptor stables cover them).
   `NetworkTokenOverrides` reads this map with per-network key normalization (EVM lowercased, Solana exact base58, TON canonicalized `workchain:hex`).

2. **Persistent cache (durable, deterministic replay).** A Mongo `token_metadata_cache` collection keyed by `networkId + contract/mint/jetton-master` storing `symbol`, `decimals`, `source`, `firstSeenAt`, `updatedAt` (unique compound index on `networkId`+`contract`). Mirrors the persist-then-replay idiom of `historical_prices` / `v4_pool_state_cache`. **Write-through:** the first live resolution during normalization persists identity so subsequent replays read from Mongo and never call RPC.

3. **Live resolver (write-through, normalization/enrichment stage only).** Per-network `LiveTokenMetadataResolver` SPI dispatched by `supports(networkId)`:
   - **Solana** — `SolanaLiveTokenMetadataResolver` wrapping the platform `JupiterClient` (Jupiter Tokens v2).
   - **TON** — `TonJettonMetadataResolver` wrapping a new platform `TonMetadataClient` (`WebClientTonMetadataClient`, TON Center jetton-master metadata) behind a `TonMetadataRequestThrottle` (≈1 rps free tier, mirroring the WS-6 `TonPriceRequestThrottle`). Resolves RWA/xStock jettons live so no hand entries are needed.
   Live resolvers **never throw** — a venue error / timeout resolves to empty and the resolver falls through to cache/override, never to a wrong default.

4. **Explicit `unresolved`.** An unknown token with no descriptor, cache, or live hit returns `ResolvedTokenMetadata.unresolved()` — never a fabricated default-decimals value.

### Integration seam

`TokenMetadataResolutionService` (core, `normalization.pipeline.metadata`) owns the cache + live tiers and the full ordering. It is invoked at the `CanonicalMetadataEnricher` seam (part of the Solana/TON normalization pipeline) to finalize placeholder flow symbols and warm the durable cache. The static descriptor registries (`SolanaSplTokenMetadataRegistry`, `TonJettonMetadataRegistry`, `TokenMetadataRegistry`) keep their **public method signatures** but now delegate internally to `NetworkTokenOverrides` (descriptor-only, network-free) so the builders/classifiers remain pure and deterministic in unit tests; the cache/live tiers are reached through the service at the enrichment seam, never from the static builders (which would otherwise pull RPC into replay).

### Boundaries

- HTTP/RPC transports (`JupiterClient`, `TonMetadataClient`) live in `backend/platform` behind ports.
- No live calls in the replay stage — the ArchUnit `replay must not call RPC` rule is preserved because live resolution runs only at normalization/enrichment and is cached write-through.
- Caffeine remains inside the live resolvers (short-lived per-run accelerator); the Mongo cache is the durable, cross-run authority.

---

## Consequences

- `token-metadata.json` is **deleted**; the registries that only existed to read it now read the descriptor override map.
- Renormalization is **deterministic and RPC-free on replay**: identity is served from descriptor overrides or the persisted `token_metadata_cache`; live calls happen once (first normalization) and are written through.
- Load-bearing decimals are preserved as a **checked-in, reviewable** map before deletion (migration-then-delete safety), gated by equivalence unit tests proving each irreducible decimal resolves identically via the new path, that a resolver timeout falls back to cache/override (no silent decimals corruption), and that an unknown token yields explicit `unresolved`.
- RWA/xStock jettons and long-tail SPL mints resolve **live** instead of requiring hand entries.
- Failure is graceful: an unreachable Jupiter/toncenter yields empty → cache/override → else `unresolved`; normalization never fails on a metadata miss.

### Must be validated only after full renormalization

- Before/after equivalence diff on `decimals`/`symbol`/`quantity` across a full `--skip-frontend` (+`--clear-pricing-cache`) renormalization — unit tests prove the resolution path, but the population of `token_metadata_cache` and the absence of symbol drift across the 2-year Solana/TON history can only be confirmed after the pipeline re-runs.

---

## References

- ADR-063 / ADR-064 — Solana Helius / TON Center normalization (source of raw token references).
- ADR-067 — non-EVM on-chain balances (metadata registries + balance providers consuming resolved identity).
- ADR-068 (incl. WS-6 amendment) — Jupiter price/metadata + TON price throttle pattern mirrored by the metadata throttle/client.
- `historical_prices` / `v4_pool_state_cache` — persist-then-replay precedent for the durable cache.
- Plan: `docs/tasks/solana-ton-live-positions-metadata-boundaries-implementation-plan.md` — WS-7.
