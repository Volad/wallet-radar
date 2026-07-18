# Hardcoded symbols/addresses → config consolidation proposal

> **Date:** 2026-07-16
> **Type:** Architecture assessment + migration proposal (inventory-driven)
> **Scope:** hardcoded EVM contract addresses, asset symbols, method selectors and
> external-API ids in `backend/*/src/main/java/**` that duplicate or belong in the
> config planes (`protocol-registry.json`, `protocols/*.json`, `network-descriptors.yml`).
> **Companion:** [protocol-config-consolidation-assessment.md](protocol-config-consolidation-assessment.md)
> (that doc retired the descriptor plane; this one addresses in-code hardcode drift).

## 0. TL;DR

A full backend scan found **203 unique `0x…` addresses**, **152 method selectors**, and
**several parallel symbol/pricing registries** across 58 Java main files. **145 of 203
addresses live only in Java** (absent from any config plane), and network token data is
**triplicated**. The highest-value, lowest-risk consolidations are:

1. **Wrapped-native / native-alias contracts** → collapse the 3 Java copies onto
   `network-descriptors.yml` (already authoritative).
2. **Bridge routers + payout addresses** (~50 unique) → `protocol-registry.json`
   (roles `ROUTER` / `BRIDGE_ENTRY` / `GAS_PAYER` / new `PAYOUT`).
3. **GMX V2 handler/vault set** (24) → `protocols/gmx-v2.json` + registry contracts.
4. **GMX/Uniswap selectors & topics** duplicated in Java classifiers → `protocols/*.json`
   markers (the `ProtocolResourceDefinition` loader already exists).

These are structural refactors and should each ship as an independent, test-guarded PR;
none is required for financial correctness today, but they remove drift risk (a new
deployment address silently missing from the registry, or a symbol map diverging).

## 1. Current state (inventory)

| Plane | Role today | Cross-check |
|---|---|---|
| `protocol-registry.json` | address → family/role/protocol/event_type | 52/203 Java addresses overlap |
| `network-descriptors.yml` | per-network native/wrapped-native/stablecoin | 13/203 overlap (token contracts only) |
| `protocols/*.json` | protocol markers/hints/capabilities | 7 addresses, 43/152 selectors |
| **Java main** | de-facto registries + inline constants | **145 addresses & 127 selectors are Java-only** |

### 1.1 Fragmentation hotspots

- **Wrapped-native triplication:** `AccountingAssetIdentitySupport`,
  `NativeWrappedTokenSupport`, and `PortfolioConservationGate.KNOWN_WETH_CONTRACTS` each
  hardcode WETH/WBNB/WMATIC/WAVAX/WMNT per network — data that `network-descriptors.yml`
  already owns and exposes via `NetworkRegistry` / `NativeAssetSymbolResolver`.
- **Bridge address sets spread across 3 classes:** `KnownBridgeRouterRegistry` (41),
  `PortfolioConservationGate` payout sets (~30), `KnownProtocolCounterpartyRegistry` (4) —
  overlapping and partly absent from `protocol-registry.json` (19 bridge routers missing).
- **GMX:** `GmxV2HandlerRegistry` (24 handler/vault addresses, 23 absent from registry);
  GMX selectors/topics re-declared in `GmxProtocolSemanticClassifier` despite existing in
  `protocols/gmx-v2.json`.
- **Symbol registries in triplicate:** `CanonicalAssetCatalog` (pricing),
  `AccountingAssetClassificationSupport` (C1/C2), `AccountingAssetFamilySupport` (families)
  each carry overlapping alias/family maps (~95 symbols each).
- **CEX stable-symbol sets:** duplicated 4× inside the Bybit module.
- **Duplicated claim-selector list:** identical in `InboundSignalSupport` and `ScamFilter`.

## 2. Recommendation — migrate in ranked waves

Each wave is independently shippable (config edit + delete Java constants + keep behavior
via the existing config loader) and must be verified with `--skip-frontend` renormalization
+ `financial-logic-auditor`.

| # | Migrate | From (Java) | To (config) | Risk | Value |
|---|---|---|---|---|---|
| **W1** | Wrapped-native + native-alias contracts (13 networks) | `AccountingAssetIdentitySupport`, `NativeWrappedTokenSupport`, `PortfolioConservationGate` WETH set | `network-descriptors.yml` via `NativeAssetSymbolResolver` / `NetworkRegistry` | Low | High (kills triplication) |
| **W2** | Bridge routers + payout/relayer addresses (~50) | `KnownBridgeRouterRegistry`, `PortfolioConservationGate`, `KnownProtocolCounterpartyRegistry` | **new `counterparty-hints.json` plane** (network-agnostic membership + scoped counterparty attribution) — see [ADR-059](../adr/ADR-059-counterparty-hints-config-plane.md) | Medium | High (largest Java-only cluster) |
| **W3** | GMX V2 handler/vault set (24) | `GmxV2HandlerRegistry` | `protocols/gmx-v2.json` capability list + registry contracts | Medium | High |
| **W4** | GMX/Uniswap selectors + event topics | `GmxProtocolSemanticClassifier`, `LpPositionLifecycleSupport`, `LpPositionCorrelationSupport` | `protocols/*.json` `markers` (loader already exists) | Medium | Medium |
| **W5** | Global direct method→type map (18 selectors) | `DirectMethodIdSupport` | protocol-registry `event_type` defaults or protocols JSON | Medium | Medium |
| **W6** | Contract→symbol/decimal overrides | `TokenSymbolFallbackSupport`, `OnChainNormalizedTransactionBuilder:866-883` | `network-descriptors.yml` token-metadata section | Low | Medium |
| **W7** | EtherFi weETH OFT + minter (9) | `EtherFiOftBridgeInClassifier` | new `protocols/etherfi.json` | Low | Medium |
| **W8** | Pancake MasterChef per-network (6) | `PancakeMasterChefEnricher` | `protocols/pancake.json` or registry | Low | Low |
| **W9** | Unify symbol registries (C1/C2 + families ↔ pricing) | `AccountingAssetClassificationSupport`, `AccountingAssetFamilySupport` | consolidate onto `CanonicalAssetCatalog` (or shared `asset-catalog.yml`) | Medium | Medium |
| **W10** | CEX stable-symbol sets (4×) | Bybit `*Support`/`*Service`/`*Descriptor` | single `BybitVenueDescriptor` accessor | Low | Low (internal dedup) |

### 2.1 Suggested config-schema additions

- `protocol-registry.json`: add role **`PAYOUT`** (bridge/solver payout addresses used by
  the conservation gate) so payout evidence is registry-driven, not Java-set-driven.
  **Revised by [ADR-059](../adr/ADR-059-counterparty-hints-config-plane.md):** W2 does **not**
  add a `PAYOUT` role; network-agnostic bridge/payout/relay/LP-pool addresses move to a dedicated
  `counterparty-hints.json` plane instead (solvers are EOAs and the match is network-agnostic,
  both of which are a poor fit for the network-keyed contract registry).
- `network-descriptors.yml`: optional **`token-metadata`** block per network
  (`contract → {symbol, decimals}`) to absorb `TokenSymbolFallbackSupport` and the Fluid
  decimals overrides; optional **`defillama-slug`** field (see §3.1).
- `protocols/*.json`: rely on existing `markers.methodSelectors` / `markers.eventTopics`
  for W4/W5 — no schema change needed.

### 2.2 Legitimately code-resident (do NOT migrate)

- EVM standards: zero address, ERC-20 `Transfer`/`Approval` topics, native sentinel
  `0xeeee…eeee`, keccak topic **computers**.
- `ScamFilter` / `ScamDisperseClonePhishingTagger` security heuristics and phishing
  fingerprints (behavioral, security-sensitive).
- `SponsoredGasTopUpSupport` amount thresholds (not addresses).
- `NonEconomicClassifier` / `DefaultClassifier` tx-hash audit allowlists (dataset-specific).

### 2.3 Good existing patterns to replicate

- `NetworkStablecoinContracts` and `NativeAssetSymbolResolver` bind **from
  `network-descriptors.yml` at runtime with zero hardcoded data** — this is the target
  shape for W1/W6.
- `SponsoredGasTopUpSupport` already reads `GAS_PAYER` from the registry.

## 3. Known inconsistencies surfaced by the scan (fix during migration)

1. **Mantle WETH semantics:** `PortfolioConservationGate` uses the precompile
   `0xdeaddead…1111`; `network-descriptors.yml` has WMNT `0x78c1b0…`. Different assets —
   reconcile explicitly when W1 lands (Mantle has both a WMNT and a bridged-WETH identity).
2. **PLASMA missing from `DefiLlamaClient.chainSlug()`** — falls to `default → null`, so
   DefiLlama-by-contract pricing is unavailable for Plasma tokens (XPL still prices via the
   CoinGecko `plasma` id). Add `case PLASMA -> "plasma"` after verifying the DefiLlama slug.
3. **Katana WETH:** now set in `network-descriptors.yml` (`0xee7d8bcf…` vbETH, this
   change); the wrapped-native Java maps (W1 targets) still omit Katana — W1 fixes this.
4. **Duplicated claim-selector list** in `InboundSignalSupport` and `ScamFilter` — dedup.

## 4. R3 point 3 — resolved (this change)

The `network-descriptors.yml` hygiene items from the descriptor-consolidation assessment
(§4 F4 / §3 R3) are now applied:

- **KATANA** wrapped-native `contract` added: `0xee7d8bcfb72bc1880d0cf19822eb0a2e6577ab62`
  (Katana WETH / vbETH — **not** the default OP-Stack `0x420…006`; verified against Katana
  docs). Previously null → wrap/unwrap was never detected on Katana
  (`WrappedNativeSupport` short-circuits on a null contract).
- **PLASMA** wrapped-native `symbol` corrected `WXPL9 → WXPL` (verified on Plasmascan /
  DexPaprika: `0x6100e367…` is `Wrapped XPL`, symbol `WXPL`, 18 decimals). The stale
  `WXPL9 → XPL` defensive alias in `CanonicalAssetCatalog` was removed (dead once the
  config emits the real symbol; the on-chain `symbol()` is `WXPL`, never `WXPL9`).

`WrappedNativeSupport` keys wrap/unwrap on the **contract** (symbol is only the synthetic
leg label) and pricing/AVCO key on contract, so both edits are low-risk corrections to
on-chain reality. Verified by unit tests + `--skip-frontend` renormalization.

## 4b. W3 — implemented (this change)

The GMX V2 handler/vault address set (24 addresses, Arbitrum + Avalanche, current +
deprecated v2.1) moved out of hardcoded Java (`GmxV2HandlerRegistry`) into the authoritative
`protocols/gmx-v2.json` config plane:

- **Config:** new `handlerContracts` block in `protocols/gmx-v2.json` (network-keyed lists;
  16 Arbitrum + 8 Avalanche = 24 lowercase addresses).
- **Loader:** `ProtocolResourceDefinition` gained an optional `handlerContracts`
  (`Map<network, List<address>>`) component plus a flattened, network-agnostic
  `handlerContractAddresses()` accessor (addresses normalized lowercase, deduped).
- **Binder:** new `GmxHandlerRegistryBinder` (`@Service`) binds the static
  `GmxV2HandlerRegistry` membership predicate at startup from the config (mirrors
  `CounterpartyHintService`), logging the loaded count.
- **Adapter:** `GmxV2HandlerRegistry` is now a thin `bind(...)`-backed adapter — all existing
  static call sites (`GmxV2RefundClassifier`, `AddressPoisoningDetector`) unchanged.
- **Tests:** golden-set test asserts the config carries exactly 24 handlers and every known
  handler resolves; full `:backend:core:test` green. Verified by `--skip-frontend`
  renormalization + `financial-logic-auditor`.

## 4c. W4 — implemented, rescoped (this change)

**Reassessment.** The original W4 framing (*"move GMX/Uniswap selectors + event topics into
`protocols/*.json` markers"*) was found to be a partial mis-fit on inspection:

- `GmxProtocolSemanticClassifier` is **already config-first** — it reads selectors, subcall
  selectors, event topics and event names from `protocols/gmx-v2.json`
  (`configuredSubcallSelectors` / `configuredEventTopics` / `matchesMethodSelector`); the inline
  hex literals are dead fallbacks only. Nothing left to migrate.
- The real duplication was `LpPositionLifecycleSupport` and `LpPositionCorrelationSupport` each
  **re-declaring the same ~12 selectors + the ERC-721 topic**. But those are cross-protocol ABI
  standards (Uniswap V3 `NonfungiblePositionManager` + V4 `PositionManager`, reused verbatim by
  Pancake/Sushi/Angle/Aura/MasterChef forks), consumed by **static utilities** with no protocol
  resource at call time. Per §2.2 these are ABI standards; forcing them into a single protocol's
  JSON would be semantically wrong and would require threading a Spring resource catalog into
  static utils (~17 files) for Medium value.

**Delivered.** A dedicated shared config plane holds the cross-protocol LP position-manager ABI
vocabulary as a single source of truth (owner-preferred: one common file, not per-class constants):

- **Config:** new `backend/core/src/main/resources/lp-position-manager-abi.json`
  (15 method selectors + 2 event topics).
- **Holder:** `LpPositionManagerAbi` — eagerly loads the classpath resource, fail-fast on
  missing/malformed keys, exposes the values as named constants. Self-contained (no Spring
  binding), so it works identically under Spring and in plain unit tests.
- **Refactor:** `LpPositionLifecycleSupport` + `LpPositionCorrelationSupport` now reference the
  shared holder; the coupled `switch` selector sets became `if/else` chains (case labels require
  compile-time constants) with byte-for-byte identical semantics. This removes the drift risk
  where the two classes could diverge and split an LP entry/exit correlation id (AVCO corruption).
- **Tests:** golden-set test pins every selector/topic value; full `:backend:core:test` green.
  Verified by `--skip-frontend` renormalization + `financial-logic-auditor`.

GMX selector/topic literals were left in place as harmless config fallbacks (already superseded by
`protocols/gmx-v2.json`); trimming them is deferred as low-value cleanup.

## 4d. W5 — implemented (this change)

The global selector→`NormalizedTransactionType` fallback map (18 entries) moved out of the
hardcoded `DirectMethodIdSupport.METHOD_ID_TYPES` into a shared config plane (same shape as W4):

- **Config:** new `backend/core/src/main/resources/direct-method-types.json` (18 address-agnostic
  cross-protocol selectors → enum type names).
- **Refactor:** `DirectMethodIdSupport` now loads the map once from the classpath resource,
  fail-fast on missing/malformed config or unknown enum names (validated via
  `NormalizedTransactionType.valueOf`). `resolveType` keeps the exact pre-config
  `Map.get(methodId)` semantics (keys lowercased at load; raw lookup — behaviour-preserving).
- **Tests:** golden-set test pins all 18 selector→type mappings; full `:backend:core:test` green.
  Verified by `--skip-frontend` renormalization + `financial-logic-auditor`.

## 4e. W6 — implemented (this change)

The two hardcoded contract→token-metadata (symbol / decimals) lookup sites moved out of Java
into a shared config plane (same self-loading-holder shape as W4/W5):

- **Config:** new `backend/core/src/main/resources/token-metadata.json` with **two intentionally
  separate groups** (`fallbackTokens`, 4 contracts; `builderTokens`, 4 contracts). The two groups
  are kept distinct on purpose — they back two independent lookup paths with different coverage and
  different defaults, so merging them into one flat map would change normalization output (a
  contract present in one set but absent from the other would newly resolve via the other path).
- **Holder:** `TokenMetadataRegistry` — eagerly loads the classpath resource, fail-fast on
  missing/malformed config, keys lowercased at load. Exposes `fallbackSymbol/fallbackDecimals/
  decimalOverride` (fallbackTokens) and `builderSymbol/builderDecimals` (builderTokens).
- **Refactor:**
  - `TokenSymbolFallbackSupport` — the three static maps (`KNOWN_CONTRACT_SYMBOLS`,
    `KNOWN_CONTRACT_DECIMALS`, `CONTRACT_DECIMAL_OVERRIDES`) are gone; all four public accessors
    delegate to `TokenMetadataRegistry`, preserving each caller's fallback/override semantics and
    default returns. The soUSDC authoritative decimal-override rationale is retained in the Javadoc.
  - `OnChainNormalizedTransactionBuilder.tokenDecimals/tokenSymbol` — the two private `switch`
    tables delegate to `TokenMetadataRegistry.builder*`, keeping their own defaults (decimals `18`,
    symbol = raw contract) untouched.
- **Tests:** golden-set test pins every symbol/decimal/override in both groups and asserts group
  isolation; full `:backend:core:test` green. Verified by `--skip-frontend` renormalization
  (telemetry identical to W5: onChainNormalized=3501, bybitNormalized=4392, pendingStat=6,
  assetLedgerPoints=11312, no CorridorBasis HARD_FAIL) + `financial-logic-auditor`.

The proposal's original W6 target (`network-descriptors.yml` per-network token-metadata section) was
reassessed: every consumer here is **network-agnostic** (looks up by contract only), so per-network
YAML keying would add structure the callers cannot use plus cross-module `NetworkDescriptor`/
`NetworkProperties` plumbing for Low value. The dedicated flat config mirrors the accepted W4/W5
pattern with zero cross-module churn. Contracts remain globally unique across both groups.

## 4f. W7 — implemented (this change)

The EtherFi weETH OFT bridge-in address sets (8 LayerZero OFT token deployments + 1 canonical
minter proxy = 9 addresses) moved out of hardcoded Java (`EtherFiOftBridgeInClassifier`) into a
new authoritative `protocols/etherfi.json` config plane:

- **Config:** new `backend/core/src/main/resources/protocols/etherfi.json` with a generic
  role-keyed `contractSets` block: `weethOftTokens` (8) + `minterProxies` (1). Per-network
  annotations for each OFT deployment are kept in the file `_comment` (the same OFT address is
  deployed across multiple chains, so a flat set — matching the classifier's network-agnostic
  membership test — is the faithful model, not a per-network map).
- **Loader:** `ProtocolResourceDefinition` gained a generic optional `contractSets`
  (`Map<role, List<address>>`, normalized lowercase like `handlerContracts`) plus a
  `contractSet(role)` accessor returning the normalized set for one role. Unlike W3's flattened
  `handlerContractAddresses()`, roles stay **distinct** — the classifier requires the token in one
  role AND the sender in another, so they must not be conflated.
- **Refactor:** `EtherFiOftBridgeInClassifier` (a Spring `@Service`) now injects
  `ProtocolResourceCatalog`, loads both sets at construction, and **fails fast** if either role is
  absent/empty (a silent empty set would stop bridge-in reclassification and leak basis). The two
  static `Set.of(...)` constants are gone; matching logic is byte-for-byte unchanged.
- **Tests:** golden-set test pins the exact 8 + 1 addresses via the real config; existing
  match/reclassify tests now build the classifier from `ProtocolResourceLoader`. One positional
  `new ProtocolResourceDefinition(...)` in `ProtocolRegistryClassifierTest` gained the new trailing
  `null`. Full `:backend:core:test` green. Verified by `--skip-frontend` renormalization +
  `financial-logic-auditor`.

## 4g. W8 — implemented (this change)

The PancakeSwap MasterChef V3 reward-contract addresses (6 networks) moved out of hardcoded Java
(`PancakeMasterChefEnricher.MASTER_CHEF_BY_NETWORK`) into `protocols/pancake.json`:

- **Config:** new `backend/core/src/main/resources/protocols/pancake.json` reusing the generic W7
  `contractSets` block, here **keyed by network id** (BSC / ETHEREUM / ARBITRUM / BASE / ZKSYNC /
  LINEA), each mapping to its single MasterChef V3 address.
- **Loader:** no new record field — `ProtocolResourceDefinition.contractSets()` (the record
  accessor) already exposes the normalized `Map<key, List<address>>`; the `contractSets` javadoc
  was broadened to cover network-id partitioning in addition to W7's semantic roles.
- **Refactor:** `PancakeMasterChefEnricher` (a Spring `@Component`) now injects
  `ProtocolResourceCatalog`, builds its network→address map at construction (uppercased network
  keys to match `NetworkId.name()`), and **fails fast** if the resource is missing/empty. Lookup,
  `pendingCake` RPC call, and CAKE merge logic are byte-for-byte unchanged. Addresses are stored
  lowercase (RPC `eth_call` `to` is case-insensitive, so this is behavior-neutral).
- **Scope:** this enricher is an **LP-snapshot reward reader** (CAKE farming rewards for the
  Fees & Rewards card / APR), NOT on the cost-basis/AVCO path — a `--backend-only` change with zero
  renormalization or AVCO impact.
- **Tests:** golden-set test pins all 6 network→address mappings and the fail-fast behavior; full
  `:backend:core:test` green.

## 4h. W9 — implemented (see ADR-060)

The ~80-entry `AccountingAssetFamilySupport.SYMBOL_FAMILIES` map (a duplicate of the C1/C2
accounting-family registry in `AccountingAssetClassificationSupport`) was removed. The registry is
now the single source of accounting-family identity; only the one genuinely non-registry entry
(`AAVASAVAX → FAMILY:SAVAX`) remains, as a documented `SUPPLEMENTAL_FAMILIES` consulted at the same
two call sites and order as before (after the registry, before `inferredFamilyIdentity`, so the
SAVAX→AVAX lending-inference reroute cannot hijack it). Both former readers were migrated; the
`AccountingAssetClassificationArchTest` was re-pointed at resolution-level invariants; a six-surface
golden test + a lending-reroute regression test were added. Verified zero-diff by
`financial-logic-auditor`: ETH/BTC terminal AVCO match to 32 digits, `asset_ledger_points = 11312`,
AAVASAVAX pools under `FAMILY:SAVAX` with no leak. Design + review record: **ADR-060**.

## 4i. W10 + W4+ known inconsistencies — implemented (this change)

- **W10 (Bybit stable-symbol sets):** the identical 10-symbol normalization peg set copied across
  four Bybit classes (`BybitCanonicalTransactionBuilder` — dead copy, removed; plus
  `BybitCanonicalMappedRowSupport`, `BybitCanonicalFlowCounterpartySupport`,
  `BybitBotTransferCostBasisService`) is now a single `BybitStablecoinPegSymbols` holder. It is kept
  **separate** from `BybitVenueDescriptor.STABLECOIN_SYMBOLS` (the NEC/FUND-inflow eligibility set),
  which intentionally diverges (accepts bare `USD`, excludes `DAI/FDUSD/PYUSD/TUSD/USD1`). Golden
  test pins membership + the divergence guard.
- **§3.2 (DefiLlama PLASMA slug):** added `case PLASMA -> "plasma"` to `DefiLlamaClient.chainSlug()`,
  after verifying live that `coins.llama.fi/prices/current/plasma:0x6100e367…` returns a WXPL price.
  Unlocks free DefiLlama-by-contract pricing for Plasma tokens.
- **§3.4 (claim-selector list):** the two lists were **not** identical (the scan overstated it):
  `ScamFilter` uniquely carries `0xe2de2a03` (bridge `redeemWithFee`) and `InboundSignalSupport`
  uniquely carries `0x5d4df3bf` (generic claim). The shared 8-selector core is extracted into
  `RewardClaimSelectors.SHARED_CLAIM_SELECTORS`; each consumer composes it via `withExtra(...)` so
  membership stays behavior-identical while the core has one source.
- **§3.1 (Mantle WETH):** verified — the `PortfolioConservationGate` Mantle WETH precompile
  (`0xdeaddead…1111`, bridged-ETH identity) and `network-descriptors.yml` WMNT (`0x78c1b0…`, wrapped
  gas token) are legitimately distinct assets with no conflation. Added a clarifying comment at the
  gate to document the divergence; no behavior change.

## 4j. W11 — implemented (this change)

`PortfolioConservationGate.KNOWN_WETH_CONTRACTS` — the cross-network ETH/WETH scam-token allowlist
used by NEC (a flow labelled ETH/WETH with a contract not in the set is treated as a fake token) —
was a hand-maintained static list that had **already drifted** from `network-descriptors.yml`:

- **Drift closed (latent safeguard):** Katana WETH/vbETH (`0xee7d8bcf…`, present in descriptors) was
  **missing** from the hardcoded list. Today's Katana flows are labelled `vbETH` (outside
  `ETH_FAMILY_SYMBOLS`, so `RC-fake-native` never fired on them → NEC impact $0), but a future
  canonical `ETH`/`WETH`-labelled Katana flow would have been wrongly dropped. Now included via
  derivation, so the list can no longer drift from `network-descriptors.yml`.
- **Dead entry dropped:** Cronos zkEVM WETH (`0x2def…`) — Cronos is not a `NetworkId`, so the entry
  was unreachable (0 flows in the dataset). Removal is a verified no-op.

The list is now **derived from configuration** via `NetworkRegistry.ethFamilyEquivalentContracts()`:
for every network whose `native-symbol == ETH`, its `wrapped-native` contract + `native-alias-contracts`
(e.g. the zkSync native-ETH proxy); plus, for any network, a new optional `eth-family-contracts`
descriptor field holding bridged WETH on non-ETH chains (Mantle precompile `0xdead…1111`, Avalanche
WETH.e `0x49d5…`). Networks whose native is not ETH contribute **only** their explicit
`eth-family-contracts`, never their wrapped-native (WBNB/WMATIC/WAVAX/WMNT/WXPL), so a fake "WETH"
reusing a non-ETH wrapper contract cannot be allowlisted.

- **Config:** `eth-family-contracts` added to MANTLE + AVALANCHE in `network-descriptors.yml`;
  `NetworkProperties.NetworkEntry.ethFamilyContracts` + `NetworkDescriptor.ethFamilyContracts` +
  `NetworkRegistry.ethFamilyEquivalentContracts()` derived accessor.
- **Refactor:** `PortfolioConservationGate` injects `NetworkRegistry` and builds the allowlist at
  construction; the two membership checks are unchanged.
- **Not zero-diff** (adds Katana, drops dead Cronos — a strict NEC-correctness improvement); verified
  by golden test, full `:backend:core:test`, and the standard `--skip-frontend` renorm + auditor gate.

## 5. Status

Waves **W1–W11 implemented and audited** (each zero-diff / off-AVCO-path as noted per section,
except W11 which is a deliberate NEC-correctness fix under the audit gate); W4+ known-inconsistency
items resolved. This document remains the inventory of record.

## Related

- [Protocol config consolidation assessment](protocol-config-consolidation-assessment.md)
- [Supported networks and protocols](../reference/supported-networks-and-protocols.md)
- [Protocol descriptor](../reference/protocol-descriptor.md)
