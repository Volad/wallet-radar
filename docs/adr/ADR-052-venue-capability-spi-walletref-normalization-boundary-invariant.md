# ADR-052 — Venue Capability SPI, WalletRef, and the Normalization Boundary Invariant

**Status:** Accepted  
**Date:** 2026-07-10  
**Theme:** CEX abstraction / extensibility / ArchUnit enforcement

---

## Context

WalletRadar supports multiple CEX integrations (Bybit, Dzengi) alongside on-chain wallets. As integration count grew the codebase developed ~30 files that branched on raw string prefixes (`startsWith("BYBIT:")`, `endsWith(":FUND")`, `networkId === 'BYBIT'`) in every layer — linking, cost-basis replay, portfolio dashboard, the frontend. Adding a third venue would have required editing all of them.

Four structural problems were identified:

**W1 — SPI duplication.** A `VenueDescriptor` concept was proposed alongside the already-shipped `CexVenueProfile` + `CexLedgerSource` Track B1. The right move is to *extend* the existing SPI, not parallel it.

**W2 — Layer violation.** Venue descriptors coupled `CexLiveBalancePort` (a Core port) with domain-level identity. Domain modules must be pure value objects with no Spring or Core references.

**W3 — God interface.** A single 13-method `VenueCapability` forces flat venues (Dzengi, which has no sub-accounts) to stub sub-account methods → ISP violation.

**W4 — Registry at read-time.** Dashboard, conservation, and reconciliation queried `VenueRegistry` to branch on venue at every read, meaning adding a venue forced editing those read paths.

**W5 — Stale Dzengi correctness.** `AccountingUniverseService` mapped `DZENGI:uid` to the Solana bucket (wrong). `PortfolioConservationGate` had no Dzengi NEC leg. `AssetLedgerReconciliationService` had no Dzengi umbrella branch.

---

## Decision

### 1. Three-plane model

**Plane 1 — Ingestion (venue-aware).** Acquisition, extraction, normalization, and live/pricing adapters. The `VenueRegistry` `@Component` and all venue descriptors live *only* here.

**Plane 2 — Boundary contract (neutral, produced by normalization).** Normalization stamps venue-neutral attributes on `normalized_transactions`:
- *Wallet identity:* `walletDomainKind`, `venueId`, `subAccount`, `umbrellaKey`  
- *External-capital markers:* `externalCapitalBoundary` (INFLOW/OUTFLOW/null) + `externalCapitalEligibleUsd` — computed once via `VenueExternalCapitalPolicy`, never re-derived downstream

**Plane 3 — Consumption (neutral).** `WalletRef` (pure grammar parse) + stamped fields/markers are the *only* interpretation mechanism. Zero `VenueRegistry`, zero prefix literals in: linking, pricing, cost-basis replay, portfolio read-model, conservation, dashboard, and frontend.

### 2. Domain value objects (`backend/domain.wallet`, pure)

- `WalletDomainKind { EVM, SOLANA, TON, CEX }` — enum
- `WalletRef` — parses the uniform grammar (`PROVIDER:uid[:SUB]` for CEX; bare address for on-chain). Pure Java, no Spring. Exposes: `domain()`, `venueId()`, `uid()`, `subAccount()`, `umbrellaKey()`, `providerPrefix()`.
- `OnChainAddressClassifier` — absorbs `0x` / TON / Solana heuristics, single source of truth.

### 3. Segregated venue SPI (`application.cex.port`)

Four capability interfaces extend `CexVenueProfile`:
- `VenueIdentity` — `venueId`, `providerCode`, `source()`, ownership predicates, `accountKindSuffixes()`
- `VenueWalletModel` — `umbrellaKey`, `expandBackfillRefs`, `dashboardWalletRefs`; no-op default for flat venues
- `VenueLiveBalanceCapability` — `Optional<CexLiveBalancePort> liveBalancePort()`
- `VenueExternalCapitalPolicy` — decides at normalization time whether a flow is an external-capital boundary and its eligible USD basis

`VenueDescriptor` composes all four. `VenueRegistry @Component` injects `List<VenueDescriptor>`.

### 4. The core invariant (ArchUnit-enforced)

> **Venue specificity ends at normalization.** Post-normalization packages (`..costbasis..`, `..portfolio..`, `..pricing..`, `..linking..`, `..api..`) must NOT depend on `VenueRegistry`, `VenueDescriptor`, or concrete descriptor implementations.

Enforced by `ModuleDependencyArchTest.post_normalization_packages_must_not_depend_on_VenueRegistry_or_descriptors`.

Supplementary: `VenuePrefixGuardTest` scans source for raw venue prefix literals (`"BYBIT:"`, `":FUND"`, `":EARN"`, `":UTA"`) outside the ingestion-plane and session-bootstrap allowlist, requiring `CorrelationContract` constants everywhere else.

### 5. Frontend neutrality

- `WalletDomain = 'EVM' | 'SOLANA' | 'TON' | 'CEX'` added to `TokenPosition` DTO
- `wallet-ref.util.ts` single source of truth for `parseWalletDomain`, `isCexAddress`, `isOnChainAddress`, `parseVenueId`, `parseSubAccount`
- `EvmNetworkId` union no longer contains `'BYBIT'` (not an EVM chain)
- `onChainVsCexSplit` uses `position.domain === 'CEX'` (fixes Dzengi being counted as on-chain)
- `filterNetworks` populates venue entries dynamically from `sessionIntegrations[].provider`

---

## Consequences

### Adding a new venue now touches only

1. One `NormalizedTransactionSource` enum value (unavoidable closed-enum discriminator)
2. Acquisition/extraction package: API client, extraction service, extracted event + repository
3. Normalization package: canonical builder + pairers, `VenueDescriptor` implementing only real capabilities
4. Optional: `CexLiveBalancePort` adapter, pricing adapter

**Post-normalization edits: zero.** Proven by `ModuleDependencyArchTest` + `VenuePrefixGuardTest`.

### Dzengi correctness (emergent, not per-venue branch)

Dzengi CEX classification, reconciliation umbrella, and NEC counting emerge from the neutral contract after this change — no per-Dzengi branches anywhere in the consumption plane.

### Risks

- Boundary-contract fields require full renormalization/replay — mitigated by `mongodump` + exact-BigDecimal financial snapshot.
- `NormalizedTransactionSource` remains a closed enum — one value per venue is the only required non-venue-package edit.

---

## Related

- [ADR-048 Dzengi product scope](ADR-048-dzengi-product-scope.md)
- [ADR-049 Venue-agnostic CEX transfer linking](ADR-049-venue-agnostic-cex-transfer-linking.md)
- [ADR-050 Dzengi fiat FX pricing](ADR-050-dzengi-fiat-fx-pricing.md)
- [capability-behavior-spi.md](../reference/capability-behavior-spi.md)
- [add-an-integration.md](../reference/extensibility/add-an-integration.md)
