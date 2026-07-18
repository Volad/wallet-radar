# ADR-059 — Counterparty-hints config plane for network-agnostic bridge/payout/LP addresses (consolidation Wave W2)

> **Status:** Accepted
> **Date:** 2026-07-16
> **Theme:** Config consolidation / normalization / linking / conservation gate
> **Companion:** [`docs/tasks/hardcoded-registry-consolidation-proposal.md`](../tasks/hardcoded-registry-consolidation-proposal.md) (Wave W2)
> **Supersedes suggestion:** the proposal's §2.1 "add role `PAYOUT` to `protocol-registry.json`" — **rejected** here (see §B).

## A. Context, problem, and assumptions

Wave W2 of the hardcoded-config consolidation moves five hardcoded EVM address sets out of Java
and into config **without changing classification or NEC-conservation behavior**. The affected
Java holders and their exact query contracts (verified in code):

| Holder | Scope | Query contract | Data | Consumers |
|---|---|---|---|---|
| `KnownBridgeRouterRegistry` (`…/classification/support/`) | **network-agnostic** | `isKnownBridgeRouter(addr)`, `touchesKnownBridgeRouter(iter)`, `isKnownRewardDistributor`, `touchesKnownRewardDistributor` — **no `networkId`** | 31 `BRIDGE_ROUTERS` + 5 `REWARD_DISTRIBUTORS` (flat `Set<String>`) | `KnownBridgeRouterExternalTypeCorrectionService`, `AddressPoisoningDetector`, `HeuristicClassifier` |
| `KnownProtocolCounterpartyRegistry` (`…/classification/support/`) | **network-scoped** | `lookup(NetworkId, addr)` → `ProtocolAttribution(name, counterpartyType, asBridge)` | 4 entries (BASE ×2, ZKSYNC ×2) | `ProtocolAttributionClassifier` |
| `PortfolioConservationGate` `KNOWN_BRIDGE_PAYOUT_ADDRESSES` | **network-agnostic** | `set.contains(cp)` | ~14 solver/relayer EOAs + contracts | read-time NEC (this `@Service`) |
| `PortfolioConservationGate` `RELAY_SOURCE_ADDRESSES` | **network-agnostic** | `set.contains(cp)` | 1 | read-time NEC |
| `PortfolioConservationGate` `KNOWN_LP_POOL_ADDRESSES` | **network-agnostic** | `set.contains(cp)` | 4 (Katana vaults/pool) | read-time NEC |

Two hard facts drive the decision:

1. **The bridge-router / payout / relay / LP-pool detection is network-agnostic** — the same
   address (e.g. LI.FI Diamond `0x1231deb6…`) is *deliberately* matched on every network. Any
   design that narrows this to specific networks is a **behavior change** and is disqualified.
2. **`protocol-registry.json` is definitionally `(NetworkId, address)`-keyed.** Its loader
   (`ProtocolRegistryLoader`) *requires* a non-empty `networks[]`, *requires* `family`/`role`/
   `confidence` per entry, and *throws* on a duplicate `(network, address)` key. It feeds the hot
   per-tx classification lookup `ProtocolRegistryService.lookup(NetworkId, addr)`.

**Assumptions:** no new networks are silently gained/lost by the migration; the migration is
config + thin-adapter only (no logic change); the auditor baseline (NEC, per-address classification)
is the acceptance surface.

## B. Decision

**Adopt Option B — a dedicated, network-aware *counterparty-hints* config plane.**

Introduce a new classpath resource `counterparty-hints.json` (loaded like `protocol-registry.json`
/ `network-descriptors.yml` at startup) that owns **all five** address sets plus the four
network-scoped counterparty attributions. `protocol-registry.json` **stays purely** for
network-scoped protocol-**contract** classification (`family`/`role`/`event_type`) and is **not**
touched by W2.

- **`ProtocolRegistryRole` does NOT gain `PAYOUT`/`SOLVER`.** Payout/solver/relay/LP-pool addresses
  never enter `protocol-registry.json`, so no new role is needed. This explicitly rejects the
  proposal's §2.1 suggestion.
- Data lives in one file; the four Java holders become **thin adapters over a bound lookup**
  (the W1 `NetworkNativeAssets` / `NetworkStablecoinContracts` pattern), so all existing call
  sites keep their signatures and behavior is preserved by construction.

### Why B over A and C

**Option A (everything → `protocol-registry.json` with `networks:["*"]`) — rejected:**
- **Breaks behavior preservation.** A `*` entry must either expand to today's 13 concrete networks
  (so a network added later would *silently* drop out of a match that is network-agnostic today — a
  behavior change), or require a parallel address-only index — i.e. a *second* lookup contract
  bolted onto a service whose entire invariant is a single `(network, address)` key.
- **Couples the hot classification path to fuzzy heuristics.** Answering `isKnownBridgeRouter(addr)`
  would need a cross-network `lookupAnyNetwork(addr)` scan grafted onto the per-tx classifier.
- **Semantic lie for EOAs.** Relay/LiFi solvers (`0x7ff8bbf9…`, `0xcad97616…`, `0xf70da978…`) are
  **EOAs**, not protocol contracts. `family`/`role`/`confidence` are *required* registry fields; a
  solver EOA has no family. You would invent a synthetic family+role purely to satisfy the schema,
  polluting the classification plane.
- **The 17 already-in-registry addresses would collide** with the duplicate-`(network,address)`
  invariant, or force the network-agnostic heuristic to inherit the contract entry's `networks[]` —
  again narrowing a network-agnostic match.

**Option C (hybrid: network-scoped counterparties → registry; heuristics → new file) — rejected:**
- Moving the 4 `KnownProtocolCounterpartyRegistry` entries into `protocol-registry.json` re-imports
  A's semantic problem at small scale: `ProtocolAttribution(name, counterpartyType, asBridge)`
  (e.g. `ZkSync Paymaster`, `counterpartyType=PROTOCOL`, `asBridge=false`) does not map cleanly to
  required `family`/`role`, forcing a new role→attribution mapping layer and a rewrite of
  `ProtocolAttributionClassifier`'s lookup — **more behavior-drift risk for only 4 entries.**
- Splitting counterparties across two planes fragments the exact thing we are consolidating.

Option B keeps each address in the plane whose **query shape matches how it is actually queried**,
preserves the network-agnostic semantics literally (same flat `contains`), and keeps the per-tx
`(network, address)` classification path decoupled from behavioral hints. The counterparty-hints
plane is a first-class, durable concept ("network-aware behavioral address membership + optional
attribution"), distinct from the network-keyed contract-classification registry.

## C. Config schema — `counterparty-hints.json`

Location: `backend/core/src/main/resources/counterparty-hints.json`.

```json
{
  "version": "1.0.0",
  "last_updated": "2026-07-16",
  "description": "Network-aware behavioral counterparty hints: bridge routers, reward distributors, bridge payout/solver addresses, relay sources, LP pools, and network-scoped protocol counterparty attributions.",
  "categories": [
    "BRIDGE_ROUTER",
    "REWARD_DISTRIBUTOR",
    "BRIDGE_PAYOUT",
    "RELAY_SOURCE",
    "LP_POOL",
    "PROTOCOL_COUNTERPARTY"
  ],
  "entries": [
    {
      "address": "0x1231deb6f5749ef6ce6943a275a1d3e7486f4eae",
      "category": "BRIDGE_ROUTER",
      "networks": ["*"],
      "protocol": "LI.FI",
      "note": "LI.FI Diamond — main multi-network entry"
    },
    {
      "address": "0x8c826f795466e39acbff1bb4eeeb759609377ba1",
      "category": "PROTOCOL_COUNTERPARTY",
      "networks": ["BASE"],
      "protocol": "LI.FI",
      "counterpartyType": "BRIDGE",
      "asBridge": false
    }
  ]
}
```

**Field contract:**

| Field | Type | Required | Semantics |
|---|---|---|---|
| `address` | string | yes | Lowercased `0x…40hex`; normalized via `OnChainRawTransactionView.normalizeAddress`. |
| `category` | enum | yes | One of `categories`. Drives which index the entry lands in. |
| `networks` | string[] | **defaults to `["*"]` when omitted** | `["*"]` ⇒ network-agnostic membership (match on any network). A concrete list ⇒ scoped membership (only `PROTOCOL_COUNTERPARTY` uses this today: BASE / ZKSYNC). |
| `protocol` | string | no | Attribution name (also used as `ProtocolAttribution.name`). |
| `counterpartyType` | string | only for `PROTOCOL_COUNTERPARTY` | Maps to `ProtocolAttribution.counterpartyType` (`BRIDGE` / `PROTOCOL`). |
| `asBridge` | boolean | no (default `false`) | Maps to `ProtocolAttribution.asBridge`. |
| `note` | string | no | Human documentation only (carries over the existing inline comments/evidence). |

**Multi-category addresses are represented as multiple rows.** Several addresses are legitimately
dual-purpose — e.g. `0x00a55649…`, `0x2659c608…`, `0x2a2c512b…`, `0xba9dd716…`, `0x223ec22d…` are
each both a `BRIDGE_ROUTER` (from `KnownBridgeRouterRegistry`) **and** a `BRIDGE_PAYOUT`/`LP_POOL`
(from the gate). Likewise `0xf70da978…`/`0x91604f59…`/`0x8c826f79…` are both `BRIDGE_PAYOUT`
(network-agnostic) and `PROTOCOL_COUNTERPARTY` (scoped). Emit one row per `(address, category)`; the
loader builds a per-category index. This is intentional dual-purpose, **not** drift.

## D. Loader / service API and Java refactor

New pair in `…/normalization/pipeline/classification/registry/` (co-located with the protocol
registry), mirroring `ProtocolRegistryLoader` / `ProtocolRegistryService`:

```java
@Component
class CounterpartyHintLoader {                       // parses & validates the JSON, fail-fast
    LoadedCounterpartyHints loadFromClasspath();      // throws IllegalStateException on any parse/validation error
    record LoadedCounterpartyHints(
        Set<String> bridgeRouters,                    // category BRIDGE_ROUTER, networks=* → address set
        Set<String> rewardDistributors,
        Set<String> bridgePayouts,
        Set<String> relaySources,
        Set<String> lpPools,
        Map<CounterpartyKey, ProtocolAttribution> scopedCounterparties  // PROTOCOL_COUNTERPARTY, keyed (NetworkId,addr)
    ) {}
}

@Service
class CounterpartyHintService {                       // binds the static adapters at startup (constructor), like NetworkRegistry
    boolean isBridgeRouter(String address);
    boolean isRewardDistributor(String address);
    boolean isBridgePayout(String address);           // consumed by PortfolioConservationGate
    boolean isRelaySource(String address);            // consumed by PortfolioConservationGate
    boolean isLpPool(String address);                 // consumed by PortfolioConservationGate
    Optional<KnownProtocolCounterpartyRegistry.ProtocolAttribution> lookupCounterparty(NetworkId networkId, String address);
}
```

**Refactor of the three/four Java classes:**

1. **`KnownBridgeRouterRegistry` → thin bind-backed adapter (keep, do not delete).** Delete the two
   inline `Set.of(...)` literals; add
   `static void bind(Predicate<String> isRouter, Predicate<String> isDistributor)`. The five public
   static methods keep their signatures and delegate to the bound predicates (retain the existing
   `normalize(...)` guard so blank/non-`0x` inputs behave identically). `CounterpartyHintService`
   calls `bind(...)` in its constructor. **No change** to `HeuristicClassifier`,
   `AddressPoisoningDetector`, `KnownBridgeRouterExternalTypeCorrectionService` (static call sites
   unchanged).
2. **`KnownProtocolCounterpartyRegistry` → thin bind-backed adapter (keep, do not delete).** Delete
   the inline `Map.of(...)`; keep the public nested `ProtocolAttribution` record (so
   `ProtocolAttributionClassifier`'s import is unchanged); add
   `static void bind(BiFunction<NetworkId,String,Optional<ProtocolAttribution>>)`; `lookup` /
   `isKnownProtocol` delegate to the bound function. **No change** to `ProtocolAttributionClassifier`.
3. **`PortfolioConservationGate` → inject `CounterpartyHintService`.** It is already a `@Service`;
   add the dependency to its constructor. Replace `KNOWN_BRIDGE_PAYOUT_ADDRESSES.contains(cp)` →
   `hintService.isBridgePayout(cp)`, `RELAY_SOURCE_ADDRESSES` → `isRelaySource`,
   `KNOWN_LP_POOL_ADDRESSES.contains(...)` → `isLpPool`. Delete the three `Set.of(...)` constants.
   All call sites are instance methods (`computeEvmNecContribution`, `buildCorridorPairedHashes`), so
   injection is straightforward. **Out of W2 scope:** `KNOWN_WETH_CONTRACTS`, `STABLECOIN_SYMBOLS`,
   `ETH_FAMILY_SYMBOLS` — those belong to W1/W9 and stay put.

**Module boundaries / allowed deps:** the new loader+service live in `backend/core` alongside the
protocol registry. `ProtocolAttribution` stays in the `…/classification/support/` package (domain of
the adapters). No new dependency edges; no `backend/domain` or `backend/platform` change (the two
static holders already exist in `core`; the W1 holders in `domain` are untouched).

## E. Data flow and binding (ASCII)

```
                       startup (Spring context init)
                                  │
        counterparty-hints.json ──┤
                                  ▼
                    ┌─────────────────────────────┐
                    │   CounterpartyHintLoader     │  fail-fast validation
                    │   (parse + per-category idx) │  (addr fmt, category enum, networks)
                    └──────────────┬──────────────┘
                                   ▼
                    ┌─────────────────────────────┐
                    │   CounterpartyHintService    │  @Service (constructed eagerly)
                    │   ── binds static adapters ──┤
                    └───┬─────────────┬────────┬───┘
             bind()     │             │        │ inject
        ┌───────────────▼──┐   ┌──────▼──────┐ │
        │ KnownBridgeRouter │   │ KnownProtocol│ │
        │ Registry (adapter)│   │ Counterparty │ │
        └───┬───────┬───────┘   │ Registry     │ │
            │       │           │ (adapter)    │ │
   ┌────────▼─┐ ┌───▼────────┐  └──────┬───────┘ │
   │Heuristic │ │AddrPoisoning│        │         │
   │Classifier│ │Detector +   │        ▼         ▼
   └──────────┘ │BridgeRouter │  ┌────────────┐ ┌──────────────────────┐
                │ExtTypeCorr. │  │ Protocol   │ │ PortfolioConservation │
   (classification/linking)     │ Attribution │ │ Gate (read-time NEC)  │
                                │ Classifier  │ │ payout/relay/lp-pool  │
                                └────────────┘ └──────────────────────┘

  ── UNCHANGED, separate plane ──────────────────────────────────────────
  protocol-registry.json → ProtocolRegistryLoader → ProtocolRegistryService
        .lookup(NetworkId, addr)   (hot per-tx contract classification)
```

**Ordering guarantee:** identical to W1 — the `@Service` is a singleton constructed during context
initialization; the classification/linking pipeline and the read-time gate all run after the context
is ready, so `bind(...)` always precedes first use. On any load/validation failure the loader
**throws** (fail-fast), so the sets can never silently become empty (which would be a massive silent
behavior change).

## F. Handling the ~17 registry duplicates and cross-set overlaps

The ~17 bridge-router addresses that already exist in `protocol-registry.json` (LI.FI Permit2Proxy
entries, Diamond, etc.) are **genuine network-scoped protocol contracts** driving contract
classification. They answer a *different question* than the network-agnostic heuristic ("is this a
bridge router on any network?"). **Decision: keep both.**

- `protocol-registry.json` remains the single source for **network-scoped contract classification**
  (`family`/`role`/`event_type`).
- `counterparty-hints.json` is the single source for **network-agnostic behavioral membership**.
- The overlap is a deliberate dual-purpose, not accidental drift. We do **not** delete the registry
  entries and we do **not** try to derive the network-agnostic set from the registry (doing so would
  narrow it to the registry's `networks[]` = behavior change).
- **Drift guard (test-only, no runtime cost):** a regression test asserts that every registry
  contract that is *also* a `BRIDGE_ROUTER` hint stays present in both, and that the loaded hint
  sets equal the frozen golden sets (see §G). This is the honest single-source policy given the two
  distinct query shapes.

## G. Migration and verification plan (must be provably behavior-identical)

Prerequisite classification: this is a **normalization / classification / linking / conservation**
change → rebuild with `--skip-frontend` (full renormalization), and gate with
`financial-logic-auditor`. Per the prod-rebuild workflow, `--skip-frontend` triggers full
renorm + replay.

1. **Author `counterparty-hints.json`** by transcribing the exact addresses from the five sets
   (31 + 5 + 14 + 1 + 4) and the 4 counterparty attributions. Preserve inline comments as `note`.
   Emit multi-category rows for dual-purpose addresses (§C).
2. **Add loader/service + convert the four holders to adapters/injection** (§D). No logic change.
3. **Golden-set regression test (the behavior-identity proof).** Copy the *former* hardcoded
   constants into a test fixture and assert:
   - `bridgeRouters` == old `BRIDGE_ROUTERS` (exact set equality); same for the other four sets and
     the 4-entry counterparty map (per `(network, address)`).
   - Network-agnostic membership holds on an arbitrary sample of networks (e.g. assert
     `isKnownBridgeRouter(0x1231deb6…)` is true regardless of any network context) — pins the `*`
     semantics.
   - `lookupCounterparty(BASE, 0x8c826f79…)` returns exactly `("LI.FI","BRIDGE",false)`, and returns
     empty for the same address on a different network (pins scoped semantics).
4. **Keep the existing unit tests green** (`KnownBridgeRouterRegistryTest`,
   `KnownProtocolCounterpartyRegistryTest`) — they now exercise the adapter over the bound config.
5. **`./scripts/prod-reset-rebuild-backend.sh --skip-frontend`** (full renorm + replay). No
   `--clear-pricing-cache` (pricing untouched).
6. **`financial-logic-auditor` diff vs baseline:** per-address classification unchanged (same
   address → same `NormalizedTransactionType`/attribution), and NEC / `lifetimeExternalInflowUsd` /
   conservation delta unchanged for every universe. Any diff is a migration defect (typo) — fix and
   re-run.

Acceptance surface: the auditor scorecard / NEC parity is the technical success surface. "Green" =
zero classification diffs **and** zero NEC diffs, not a substitute metric.

## H. Consequences, risks, rollback

**Consequences (positive):**
- Five hardcoded sets + one static map become config-driven; new bridge/solver deployments are a
  config edit, not a code change (kills the largest Java-only address cluster's drift risk).
- The network-agnostic behavioral plane is now a named, reusable concept; future waves (e.g. GMX W3)
  have a clear home for network-agnostic address hints vs. network-scoped contracts.
- `protocol-registry.json` stays clean (no EOA solvers, no synthetic `PAYOUT` role).

**No Mongo schema change. No new index. No RPC. No cost/latency impact** (classpath JSON parsed once
at startup into in-memory sets; read path unchanged; GET endpoints still do zero RPC).

**Risks & mitigations:**
| Risk | Mitigation |
|---|---|
| Address typo / dropped entry during transcription | Golden-set equality test (§G.3) — hard fail before renorm. |
| Network-agnostic set accidentally scoped | `networks` defaults to `["*"]`; test pins network-agnostic membership. |
| Silent empty sets on load failure | Loader **throws** (fail-fast), never returns empty. |
| Binding not ready before first use | Eager `@Service` constructor bind; identical guarantee to W1 `NetworkRegistry`. |
| Overlap addresses lose a category | Multi-row-per-address schema + golden test covering each category. |

**Rollback:** config + thin-adapter only. Revert the single commit — the adapters return to inline
`Set.of(...)` and the gate to its static constants; no data migration, no Mongo change. Because the
change is proven behavior-identical, rollback is only needed for an unrelated regression.

## Related

- [Hardcoded registry consolidation proposal](../tasks/hardcoded-registry-consolidation-proposal.md) — Wave W2 (this ADR revises §2.1)
- [ADR-014](ADR-014-portfolio-conservation-gate.md) — conservation gate (NEC) whose sets are migrated
- [ADR-034](ADR-034-nec-transaction-scan.md) — NEC via transaction scan
- [Supported networks and protocols](../reference/supported-networks-and-protocols.md)
