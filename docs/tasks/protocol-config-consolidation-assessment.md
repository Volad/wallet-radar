# Protocol config consolidation — assessment & optimization proposal

> **Date:** 2026-07-16
> **Type:** Architecture assessment
> **Scope:** the protocol/contract/network configuration files and their loaders
> **Status:** ✅ **R1 + R3 accepted and implemented (2026-07-16).** Two config planes
> remain (`protocol-registry.json` address-keyed, `protocols/*.json` protocol-keyed);
> the `protocol-descriptors/*.json` plane + descriptor SPI were folded into
> `protocols/*.json` and deleted. See [§5 Outcome](#5-outcome-implemented).

## 1. What exists today

WalletRadar splits protocol/contract configuration across **three** JSON groups
plus network config, each with its own loader:

| # | Config | Loader → consumer | Key | Purpose |
|---|--------|-------------------|-----|---------|
| 1 | `protocol-registry.json` (1 file, 71 protocols / 228 contracts) | `ProtocolRegistryLoader` → `ProtocolRegistryService` | `(networkId, address)` | Address → `family` / `role` / `event_type` / `specialHandler` classification |
| 2 | `protocols/*.json` (9 files) | `ProtocolResourceLoader` (`ProtocolResourceCatalog`) | `protocol[:version]` | Per-protocol **markers/hints** (method selectors, event names, aliases, `clarificationHints`) consumed by semantic classifiers |
| 3 | `protocol-descriptors/*.json` (3 files) | `ProtocolDescriptorLoader` → `ProtocolDescriptorService` | `protocol` | A5 **descriptor SPI** (capabilities, `classification`, `lpPresentation`, `lending`, `valuationSource`) |

Networks are configured separately (`network-descriptors.yml`, `application.yml`),
which is appropriate and out of scope for consolidation.

## 2. Findings

### F1 — Groups 1 and 2 are correctly separated (keep)

`protocol-registry.json` is **address-keyed** and high-cardinality (228 entries),
resolved on the hot path per transaction counterparty. `protocols/*.json` is
**protocol-keyed** and low-cardinality (9 entries), consumed only by the ~10
`*ProtocolSemanticClassifier` beans. They answer different questions ("what is this
address?" vs "how does this protocol behave?"), have different cardinality and
different change cadence. **Merging them would couple a hot, address-indexed lookup
to protocol-level narrative — not desirable.** Recommendation: **keep separate.**

### F2 — Group 3 (`protocol-descriptors/*.json` + descriptor SPI) is unwired scaffolding

- Only **3** descriptor files exist (`aave`, `gmx`, `uniswap`).
- `ProtocolDescriptorService` is injected **nowhere**. The descriptor behavior SPI
  (`descriptor/spi/LendingBehavior`, `LpPresentationBehavior`,
  `ProtocolClassificationBehavior`, valuation) has only `*Stub` implementations and
  **no dispatcher** — grep for `List<…Behavior>` / `Collection<…Behavior>` returns
  nothing. The JSON is loaded and cross-validated at startup, then never acted upon.
- It **duplicates** `protocol` / `version` / `capabilities` / `families` already in
  `protocols/*.json`, with a **divergent vocabulary**: descriptors use the
  `ProtocolCapability` enum (`CLASSIFICATION, LP_PRESENTATION, LENDING, VALUATION`)
  while `protocols/*.json` uses free-string flags (`LP_POSITION_NFT`, `BORROW_REPAY`,
  `ETH_FLOW`, …). `uniswap` and `aave` are described **twice** with different words.

This is classic A5 scaffolding that shipped ahead of its consumer (the descriptor
doc itself is marked "Design contract (A5)"). It currently adds cognitive load and a
second source of truth without functional payoff.

### F3 — Stray root `protocol-registry.json` is a stale, unused duplicate

The repo root has a git-tracked `protocol-registry.json` (`last_updated`
2026-03-17, 137 contracts) that is **not on the classpath** and **loaded by
nothing** — the runtime copy is `backend/core/src/main/resources/protocol-registry.json`
(2026-05-07, 228 contracts). 96 addresses exist only in the core copy; the root copy
has drifted. Per `docs/overview/architecture-decisions.md` D-13, duplicate repo
copies are not authoritative.

### F4 — Minor registry hygiene

- `LENDING_LOOP_REBALANCE` is a valid `ProtocolRegistryEventType` used by contracts
  but is missing from the JSON `event_types` header array (cosmetic; the loader does
  not validate contract `event_type` against that array).
- `network-descriptors.yml`: KATANA wrapped-native contract omitted; PLASMA wrapped
  symbol `WXPL9` looks like a typo for `WXPL`.

## 3. Recommendation

**Keep two config planes; retire the third.**

### R1 (recommended) — Fold descriptors into `protocols/*.json`, retire the dead SPI

1. Move the useful, non-duplicated descriptor fields (`classification.semanticClassifier`,
   `lpPresentation`, `lending`, `valuationSource`) into the corresponding
   `protocols/*.json` profile as optional sub-objects. One profile per protocol,
   keyed by `protocolKey`, matching the intent already documented in
   `docs/reference/protocol-descriptor.md`.
2. Unify the capability vocabulary on the `protocols/*.json` free-string flags (or
   promote them to a single enum) — remove the parallel `ProtocolCapability` set.
3. Delete `protocol-descriptors/*.json`, `ProtocolDescriptorLoader`,
   `ProtocolDescriptorService`, and `descriptor/spi/*` **until a real consumer
   exists**. Preserve the design in `docs/reference/protocol-descriptor.md` /
   `capability-behavior-spi.md` as the target contract.
4. Net effect: **one protocol-level file per protocol**, one address-level registry —
   two planes, no duplication, no unwired code.

### R2 (alternative) — Keep three, but make the third real

If the descriptor SPI is intended to land soon, instead:
1. Wire a dispatcher that injects `List<LendingBehavior>` / `List<LpPresentationBehavior>`
   / … and actually routes to them from the pipeline.
2. Add a drift `ArchTest`: every `protocol-descriptors/*.json` protocol must have a
   matching `protocols/*.json` profile, and shared fields (`version`, `families`)
   must agree.
3. Backfill descriptors for all protocols that have a semantic classifier, not just 3.

R2 costs more and only pays off if the SPI is genuinely on the near-term roadmap.
Given no consumer exists today, **R1 is preferred** (less code, single source of
truth, reversible via the preserved design docs).

### R3 (independent of R1/R2) — hygiene, do now

- **Delete the stray root `protocol-registry.json`** (done alongside this assessment).
- Add `LENDING_LOOP_REBALANCE` to the JSON `event_types` header for readability.
- Fix `network-descriptors.yml` KATANA wrapped-native contract and PLASMA `WXPL9→WXPL`.
- Optionally add a build check that fails if a second `protocol-registry.json`
  reappears outside `backend/core/src/main/resources/`.

## 4. Decision needed

R3 is low-risk hygiene and is applied now. **R1 vs R2 is an owner decision** — it
touches the A5 extensibility roadmap. If approved, promote R1 to an ADR
(supersede/append `docs/reference/protocol-descriptor.md`) and a `backend-dev`
implementation task.

## 5. Outcome (implemented)

**R1 accepted.** Executed 2026-07-16:

- **Folded** the useful descriptor fields into the matching `protocols/*.json`
  profile as optional sub-objects — now the single source of truth:
  - `protocols/aave.json` → `semanticClassifier`, `lending`, `valuationSource`
  - `protocols/gmx-v2.json` → `semanticClassifier`, `lpPresentation`, `valuationSource`
  - `protocols/uniswap.json` → `semanticClassifier`, `lpPresentation`
- **Extended** `ProtocolResourceDefinition` with optional nullable components
  (`semanticClassifier`, `LpPresentationConfig`, `LendingConfig`,
  `ValuationSourceConfig`) + `@JsonIgnoreProperties(ignoreUnknown = true)`. These are
  canonical metadata; no consumer is wired yet (no behavior change).
- **Deleted** the dead descriptor plane: `protocol-descriptors/*.json`,
  `ProtocolDescriptor`, `ProtocolDescriptorLoader`, `ProtocolDescriptorService`,
  `ProtocolCapability`, the entire `descriptor/spi/*` package (4 interfaces + 4
  stubs), and `ProtocolDescriptorLoaderTest`. Confirmed no runtime consumer existed.
- **Vocabulary** now lives once — the parallel `ProtocolCapability` enum is gone;
  `protocols/*.json` free-string `capabilities` are authoritative.

**R3 applied:** stray root `protocol-registry.json` deleted; `LENDING_LOOP_REBALANCE`
added to the `event_types` header. **`network-descriptors.yml` KATANA/PLASMA fixes now
applied (2026-07-16):** KATANA wrapped-native `contract` set to `0xee7d8bcf…` (Katana
WETH/vbETH, verified against Katana docs), PLASMA symbol corrected `WXPL9 → WXPL`
(verified on Plasmascan/DexPaprika), and the dead `WXPL9` alias removed from
`CanonicalAssetCatalog`. See
[hardcoded-registry-consolidation-proposal §4](hardcoded-registry-consolidation-proposal.md#4-r3-point-3--resolved-this-change).

A companion scan of in-code hardcoded addresses/symbols and a migration proposal (which
Java constants should move into the registry / protocol / network config planes) is in
[hardcoded-registry-consolidation-proposal.md](hardcoded-registry-consolidation-proposal.md).

**Verification:** `:backend:core:test` BUILD SUCCESSFUL (incl. Spring context load
without descriptor beans, plus a new folded-field parse assertion in
`ProtocolResourceLoaderTest`); `--backend-only` rebuild boots clean. No pipeline
renormalization required (the descriptor plane was unconsumed).

## Related

- [Supported networks and protocols](../reference/supported-networks-and-protocols.md)
- [Protocol descriptor (A5 design)](../reference/protocol-descriptor.md)
- [Capability & behavior SPI](../reference/capability-behavior-spi.md)
- [Add a protocol](../reference/extensibility/add-a-protocol.md)
