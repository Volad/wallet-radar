# MULTI-counterparty bridge coverage + transfer typing + sponsored-gas AVCO — implementation plan

Status: REVISED (Phase 3 complete: BA + SA + FA all APPROVE-WITH-REVISIONS; revisions folded in). Awaiting user approval before Phase 4.

Session under audit: `df5e69cc-a0c0-4910-8b7d-74488fa266e2`. Prod Mongo `localhost:27019/walletradar`.

---

## 1. Scope & symptom
User reports: many BRIDGE_OUT/BRIDGE_IN legs are not linked on the chart and show counterparty "MULTI"; dense bridge cluster on the ETH ledger (screenshot, Aug 03 ~12:30–12:50 UTC) with AVCO dips. Asked to identify the real protocols behind "MULTI" and propose support extensions.

## 2. Key clarification (root finding)
"MULTI" is **not a protocol name**. It is the counterparty placeholder `FlowCounterpartySupport.MULTI_COUNTERPARTY` ("MULTI") stamped on transactions where `distinctCounterparties.size() > 1`. The FEE leg (synthetic `UNKNOWN:NETWORK_FEE`) is currently counted as a counterparty — this is the **earliest wrong-stage root cause** for single-recipient transfers wrongly carrying MULTI.

The defect set that is actionable: 124 legs with `counterpartyAddress="MULTI"` and `protocolResolutionState=TERMINAL_METADATA_ONLY` (bridge/transfer/external types).

## 3. Corrected protocol evidence (revised after FA verification)

| Real protocol | Legs | Signals | FA verdict |
|---|---|---|---|
| **LI.FI** (Diamond + Permit2Proxy) | **41** | LiFiDiamond `0x1231deb6…eae`; Permit2Proxies `0x89c6340b…`, `0xe5a89411…`, `0x6307119078…`, `0x2270a09b…`; `callDiamondWithEIP2612Signature` (`0xd7a08473`) / `callDiamondWithPermit2` (`0x0193b9fc`) / `callDiamondWithPermit2` variants; facet fns `startBridgeTokensVia*`. **Existing registry lists `0x89c6340b` only on ARBITRUM/BASE** → unresolved legs on AVALANCHE/ETHEREUM = exact defect. | ✅ CONFIRMED |
| LI.FI bridge via **GasZip** | 4 | `0x864b314d…` is a LI.FI proxy (impl `0xfbf7cc…`); `0xfc5f1003`=`startBridgeTokensViaGasZip` is a LI.FI facet. Protocol = **LI.FI**, underlying bridge = GasZip (metadata). | ⚠️ relabelled from "GasZip" |
| **Socket / Bungee** | 2 | `0x9dda6ef3…` — **unverified** (not canonical Socket Gateway `0x3a23F943…`). Mark as `UNVERIFIED` pending explorer confirmation; do not add to registry until verified. | ⚠️ unverified |
| **Plain ERC20 / native send (NOT bridge)** | 58 | `transfer`/`0xa9059cbb`/empty calldata — 41 ERC20 + 17 native, all single-recipient (44 → own session wallets, 14 → external EOA/GnosisSafe). Already produce `costBasisΔ<0, pnlΔ=0, uncoveredΔ=0` → reclassification is conservation-neutral. | ✅ CONFIRMED |
| ~~Relay~~ → **LI.FI Permit2Proxy** | (16 → merged above) | `0xd7a08473`/`0x0193b9fc` are LI.FI proxy selectors; the "Relay" addresses are literally `Permit2Proxy`. Real Relay sits in long-tail. **Do NOT register these as Relay.** | ❌ FA REFUTED |
| ~~deBridge DLN~~ → **ParaSwap V6.2 (mis-typed transfer)** | (1 → WS-3) | `0x6a000f20…` = `AugustusV6`; `0xe3ead59e`=`swapExactAmountIn`; already labelled Paraswap in `protocol-registry.json:701`. It is a DEX swap mis-typed `EXTERNAL_TRANSFER_OUT`. Belongs in WS-3. | ❌ FA REFUTED |
| Long-tail OTHER (Uniswap UniversalRouter, aggregators…) | ~18 | Including 2 Uniswap UR (`0x3593564c`=`execute`) mis-typed `EXTERNAL_TRANSFER_OUT`. Belong in WS-3 aggregator-swap mis-typing. Documented irreducible = ~8–10 after WS-1/WS-3. | document as irreducible |

WRAP (348) / UNWRAP (341) MULTI legs — canonical, no attribution needed; non-regression assertion required.

## 4. Screenshot cluster (Aug 03, ~12:30–12:50 UTC) — OUT OF SCOPE
Cross-network basis-carry dips (`pnlΔ=0`, avco hops between per-network pools). This is `bridge-corridor-carry-replay-determinism-implementation-plan.md` WS-B (C-1) territory. Excluded here. Joint acceptance check required: WS-4 "avco undefined when unbacked" must not mask legitimate WS-B carry dips once those are fixed.

## 5. Build-order dependencies (hard)

This plan **must land after** `bridge-corridor-carry-replay-determinism-implementation-plan.md`:
- WS-1 extends `KnownBridgeRouterRegistry` (created by Plan-B WS-D).
- WS-3's own-wallet-mis-bridge rule reuses `OwnWalletBridgeMistypeCorrectionService` (Plan-B WS-C). The two plans partition the leg set: Plan-B handles bridge-router own-wallet legs; this plan handles plain ERC20/native sends carrying MULTI. They share one correction service; no duplication.

## 6. Workstreams (upstream-first: WS-1 → WS-2 → WS-3 → WS-4)

### WS-1 — `protocol-registry.json` coverage (zero-RPC, data)
**Module: `backend/src/main/resources/protocol-registry.json` (data); `KnownBridgeRouterRegistry` for type-only fallback.**

Add/extend LI.FI entries to cover all networks, not just ARBITRUM/BASE:
- LiFiDiamond `0x1231deb6…` → multi-network (already present for some networks; add AVALANCHE/ETHEREUM/UNICHAIN/ZKSYNC/PLASMA).
- Permit2Proxies `0x89c6340b…`, `0xe5a89411…`, `0x628d684d…`, `0xa3681352…`, `0x3c6b2e0b…`, `0x6307119078…`, `0x2270a09b…` → add all to `protocol-registry.json` as LI.FI BRIDGE entry, with correct `networks` scope.
- GasZip proxy `0x864b314d…` → add as LI.FI BRIDGE, `underlyingBridge=GasZip` metadata.
- Do NOT add `0xd7a08473`/`0x0193b9fc` as Relay (they're LI.FI selectors).
- Do NOT add `0x6a000f20…` as deBridge (it's ParaSwap, already in registry).
- Socket `0x9dda6ef3…` → hold until explorer-verified.
- Also add to `KnownBridgeRouterRegistry` (type fallback) the same addresses so the clarification-time type-only guard can fire for legs not caught at classify-time.
- Acceptance: the 41 LI.FI + 4 GasZip-via-LI.FI legs get `protocolName=LI.FI`; `protocolResolutionState` transitions away from `TERMINAL_METADATA_ONLY`; zero-RPC assertion (no `EvmRpcClient` call in bridge-coverage path); rebuild == refresh (idempotent).

### WS-2 — LI.FI facet-function recognition (classifier logic)
**Module: extend `BridgeStartClassifier.BRIDGE_START_FUNCTION_KEYS` / `LiFiRouteSupport` / `BridgeMethodAwareClassifier` — NOT a new classifier.**

- Generalize the existing `*Via(Stargate|Squid|Mayan)` set to `*Via(Stargate|Squid|GasZip|Relay|Across|Hop|CBridge|Mayan|Amarok|Hyperlane|Stargate|Symbiosis)` on LI.FI diamond/proxy target addresses.
- Add `callDiamondWith*` pattern + address anchor (must be in the LI.FI diamond/proxy set from WS-1).
- Extract underlying bridge from function name as metadata where present.
- Address anchor mandatory — pattern alone (e.g. `callDiamondWith*` on an unrecognized address) scores MEDIUM confidence and does NOT set `protocolName`.
- Acceptance: a synthetic `callDiamondWithPermit2` call to a known LI.FI proxy → `protocolName=LI.FI`; a `multicall` to a non-LI.FI address → NOT tagged LI.FI; `startBridgeTokensViaGasZip` on a known proxy → `protocolName=LI.FI`, `underlyingBridge=GasZip`.

### WS-3 — Transfer typing + counterparty fix (MULTI root cause + 58 pseudo-bridges)
**Two sub-fixes:**

**WS-3a (root cause): `FlowCounterpartySupport.applyTransactionCounterparty`**
- Exclude `NormalizedLegRole.FEE` legs and synthetic `UNKNOWN:*` placeholders from the distinct-counterparty-address set before the size-2 → MULTI check.
- Effect: a single-recipient transfer with a gas FEE leg correctly gets `counterpartyAddress=<recipient>`, not MULTI.
- Acceptance: a single-recipient ERC20 transfer with FEE leg → concrete counterparty; a genuine multi-counterparty swap → still MULTI; no conservation change.

**WS-3b (residue): idempotent clarification re-stamp (for already-normalized legacy rows)**
- For the 44 own-wallet legs: extend `OwnWalletBridgeMistypeCorrectionService` (shared with Plan-B WS-C) — ensure it also covers legs with `counterpartyAddress=MULTI` (not just corridor-anchored bridge legs). Re-stamp type → `INTERNAL_TRANSFER`, counterparty → concrete address.
- For the 14 external-recipient legs (incl. the ParaSwap `0x6a000f20…`): add to `KnownBridgeRouterExternalTypeCorrectionService` or a new idempotent service → type → `EXTERNAL_TRANSFER_OUT`, counterparty → concrete address.
- For 2 Uniswap UR mis-typed `EXTERNAL_TRANSFER_OUT` + 1 ParaSwap: re-stamp type → `SWAP`.
- All services use terminal re-stamp (matches query only on the "wrong" type/counterparty, so double-run = 0 mutations). Pin order in `LinkingBatchProcessor`: WS-1/WS-2 classify-time fixes land before WS-3b.
- **Scope caveat:** Gnosis Safes not enrolled in the session are treated as EXTERNAL (basis leaves tracked universe) — document as supported boundary.
- Acceptance: 44 own-wallet → `INTERNAL_TRANSFER` + concrete counterparty; 14 external → `EXTERNAL_TRANSFER_OUT`; ParaSwap/UR → `SWAP`; `conservationDeltaUsd` unchanged; double-run idempotency test; no double-count.

### WS-4 — Sponsored-gas AVCO representation (read-model, zero-replay)
**Module: `AssetLedgerQueryService` / read path — NOT `AvcoReplayService`, NOT `asset_ledger_points` mutation.**

- Replay side already correct: `ReplayDispatcher.applySponsoredGasIn` emits `BasisEffect.GAS_ONLY`; `TimelineAvcoAuthority.isAllGasOnly` already carries AVCO forward in move-basis timeline.
- The read-model gap: asset-ledger chart plots raw `avcoAfterUsd=0` without the carry-forward, at 34 confirmed artifact points: 16 pure drain (qtyBefore≈0, basisBefore≈0) + 7 basis-less dust (qtyBefore>0, basisBefore=0) + 11 same in REWARD_CLAIM. **Gate: `basisBackedQuantityAfter≈0` (or `totalCostBasisAfterUsd≈0`)** — not `qtyBefore≈0`, which misses the 11 basis-less-dust points.
- Fix: apply the existing GAS_ONLY carry-forward / UNAVAILABLE semantics to the asset-ledger read path; represent AVCO as **null (undefined), never 0**, when `basisBackedQty≈0`. Threshold: < 1e-8 or configurable epsilon.
- Zero conservation change by construction (no `asset_ledger_points` written).
- ADR: short representation ADR ("AVCO is plotted as null when basis-backed qty ≈ 0; applies to both move-basis and asset-ledger read paths; no replay impact").
- Joint acceptance with WS-B (carry plan): WS-4 must not suppress avco-undefined at points with genuine basis (WS-B carry-through creates basis at those points); add a joint test covering drain→gas-in→reacquire→WS-B-carry sequence.
- Acceptance: 34 confirmed gas/reward dust points no longer plot avco=0; WRAP/UNWRAP avco=0 (655 points) unchanged (they have basisBefore>0 by design — gate must not suppress them); `conservationDeltaUsd` unchanged; non-zero-position SPONSORED_GAS_IN not affected.

## 7. Docs
- `docs/pipeline/normalization/rules/protocols/li-fi.md` — add facet/proxy recognition (WS-2 pattern + address-anchor guard, underlying bridge metadata).
- `docs/pipeline/normalization/rules/` — note network-scoped registry entries as root cause (WS-1 fix).
- `docs/pipeline/normalization/04-clarification-reclassification.md` — MULTI counterparty semantics (FEE exclusion, WS-3a); transfer-typing rule (WS-3b); clarify MULTI = genuine multi-principal only.
- `docs/pipeline/cost-basis/` — sponsored-gas basis-neutral / avco-undefined representation (WS-4).
- **ADR (MULTI semantics):** amend ADR-010; FEE leg excluded from counterparty set; MULTI = size>1 principals only.
- **ADR (avco-undefined):** representation rule — null when basis-backed qty ≈ 0; no replay impact.

## 8. Supported / unsupported (explicit)
**Supported by this plan:** LI.FI (Diamond, Permit2Proxies, facets/GasZip/Stargate/Squid/Relay underlying); plain ERC20/native internal & external transfers; aggregator-swap mis-typing fix (ParaSwap V6, Uniswap UR); sponsored-gas + reward-claim avco-undefined representation; MULTI = genuine multi-principal only.

**Out of scope / deferred:** basis-carry AVCO dips → corridor-carry plan WS-B (C-1 outbound-first ordering); real Relay receiver legs (in long-tail, but few; add when verified); Socket `0x9dda6ef3` (unverified); OOS families TON/SOL/HYPEREVM; WS-4 is representation-only (no PnL/conservation changes); ~8–10 irreducible long-tail OTHER legs (documented).

## 9. Acceptance (overall)
1. `./gradlew :backend:test` green incl. new registry/facet/typing/gas tests.
2. Full rebuild: 41 LI.FI + 4 GasZip-via-LI.FI legs resolve protocol + link on chart; 44 own-wallet + 14 external + 3 aggregator-swap legs reclassify with concrete counterparty; remaining unlinked: documented irreducible ~8–10.
3. No spurious avco=null at basis-bearing points; 34 gas/reward drain points no longer plot avco=0; WRAP/UNWRAP unchanged; `conservationDeltaUsd` unchanged from post-Plan-B baseline (≈ **−$24.28 ± $50**).
4. Double-run idempotency: second linking batch pass = 0 mutations.
5. No regression: WS-A..WS-E (Plan-B), RC-1..RC-9, spoof quarantine; 0 BYBIT-CORRIDOR guard breaches; MANTLE ETH avco ≈ $2,936.
6. Zero-RPC assertion: no `EvmRpcClient` call in bridge-coverage code path.
7. financial-logic-auditor sign-off.

## 10. Risks
- WS-3 ordering (WS-1/WS-2 classify-time before WS-3b clarification-time) is a hard dependency; violating it re-creates MULTI for already-identified legs.
- WS-4 gate threshold must not suppress WRAP/UNWRAP (they have basisBefore>0 — confirmed safe at `basisBackedQty≈0`).
- WS-2 address-anchor prevents false LI.FI attribution on unrecognized contracts.
- Plan-B must land first (build-order dependency, §5).

## 11. Suggested execution order
WS-1 (data/registry) → WS-2 (classifier extension) → WS-3a (FlowCounterpartySupport root cause) → WS-3b (idempotent clarification) → WS-4 (read-model) → Docs + ADRs → Rebuild + auditor sign-off.
