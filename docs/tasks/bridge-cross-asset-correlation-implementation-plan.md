# NEW-08 — Bridge Cross-Asset Correlation & Value-Carry — Implementation Plan

> **Type:** Solution/System-Architecture plan (design only, read-only author). Authorized for a
> backend-dev to implement afterward.
> **Defect:** NEW-08 — cross-network cross-asset LI.FI/Jumper bridge legs misclassified as
> `EXTERNAL_TRANSFER`, never correlated, destination gets a fabricated fresh `ACQUIRE` at a spot
> ETH mark instead of carried source value.
> **Earliest failed stage:** `classification` (bridge-registry coverage gap for newer chains),
> compounded by a linking gap (no deterministic cross-asset pairing) and a replay-shaping gap
> (the existing asset-changing settlement carry is suppressed for simple 1:1 cross-asset pairs).
> **Phase-1 audit:** [`results/bridge-correlation-audit.md`](../../results/bridge-correlation-audit.md);
> [`results/blockers.md`](../../results/blockers.md) NEW-08.
> **Accounting universe (regression anchor):** `df5e69cc-a0c0-4910-8b7d-74488fa266e2`.

---

## A. Decisions & Assumptions

### A.1 Confirmed root cause (from raw, do not re-litigate)

The pair is proven and evidence is present on both chains (`EVIDENCE_PRESENT_UNLINKED`). This plan
**fixes the earliest wrong pipeline stage and relies on a full rerun to rebuild downstream state**;
it is **not** a sweep/repair over already-normalized rows.

| Leg | tx | network | raw evidence | current (wrong) | target |
|---|---|---|---|---|---|
| SOURCE | `0xda7d556e…558de7` | UNICHAIN | `to=0x1bcd304f…d586` (unregistered LiFi diamond), `input=0xd7a08473` (`callDiamondWithEIP2612Signature`), calldata `jumper.exchange`/`gasZipBridge`, USDC out −2050.040045 | `EXTERNAL_TRANSFER_OUT`, SELL/DISPOSE, no corrId | `BRIDGE_OUT`, `bridge:lifi:0xda7d556e…`, REALLOCATE_OUT settlement carry |
| DEST | `0xc0aaf96b…5712c` | KATANA | `from=0x8c826f79…7ba1` (LiFi relayer, registered `AGGREGATOR/GAS_PAYER`), `input=0x` native settlement, ETH in +0.452894 | `EXTERNAL_TRANSFER_IN`, BUY/ACQUIRE @ DZENGI $4,715.67 = $2,135.70 | `BRIDGE_IN`, same corrId, REALLOCATE_IN @ carried basis $2,050.04 (AVCO ≈ $4,526.7/ETH) |

### A.2 Key design decisions

1. **Fix classification first (registry), then pairing, then replay shaping.** The registry gap is
   the earliest wrong step and the highest-leverage, lowest-risk change. Do not model
   dataset-specific tx hashes anywhere in production code; register **contracts** and generalize the
   **rule**. Real tx hashes are used only as regression anchors in tests.

2. **Reuse the existing asset-changing bridge settlement carry — do NOT invent new accounting.**
   The correct cross-asset value-carry is already implemented and documented
   (`docs/pipeline/cost-basis/03-basis-pools-and-carry.md` §"Asset-changing bridge settlement";
   ADR-020 amendment). It runs when a `BRIDGE_OUT(assetX) → BRIDGE_IN(assetY)` pair is
   `continuityCandidate=false`, both principal legs are `TRANSFER`-role, both carry a shared
   `correlationId` + `matchedCounterparty`, so `ReplayPendingTransferKeyFactory.bridgeSettlementKey`
   emits `bridge-settlement:<corrId>` on **both** legs. Source drains its own basis into the queue
   (`applyLinkedBridgeSettlementTransfer` outbound branch → REALLOCATE_OUT); destination drains it
   (inbound branch → REALLOCATE_IN with the carried source basis). For a stablecoin source this
   yields exactly "consideration given" = source USD value ($2,050.04) on the destination asset.
   **The only reason it does not fire for our case is the linking layer never shapes a simple 1:1
   cross-asset pair into that form** (see §E).

3. **Do not mis-promote the LiFi relayer to `family=BRIDGE`.** `0x8c826f79…` is genuinely a
   relayer/solver (gas-payer), exactly like the Relay solver payout addresses. Teach the linker to
   accept a **LiFi `GAS_PAYER` inbound as trusted destination settlement evidence** (mirroring the
   existing `isRelayPayoutEntry` acceptance for Relay `GAS_PAYER`), rather than lying about its
   family in the registry. This keeps registry semantics honest and is deterministic.

4. **Determinism / cost policy.** All pairing is local (Mongo + registry), address/selector/route-tag
   anchored, with strict time + USD-value windows and unique-candidate gates. The LiFi status HTTP
   API (`liFiStatusGateway`) remains an optional enrichment only; correctness must not depend on it.

5. **Scope carve-out.** Only supported EVM chains/contracts. No new unsupported-family carve-outs.

### A.3 Assumptions

- `0x1bcd304fdad1d1d66529159b1bc8d13c9158d586` is a UNICHAIN LI.FI executor/proxy in the same
  contract class as the already-registered `LI.FI Mantle user entry proxy` (`0xbdff0c1c…`) and the
  per-chain `LI.FI Permit2Proxy` entries (incl. UNICHAIN `0xa3681352…`). Verify address provenance
  before merge (§C.3) — sourcing is part of the task, not an assumption to ship blind.
- The Katana ETH-family AVCO distortion (+$85.66 basis, +$189/ETH) and its propagation into the
  SushiSwap LP + surviving vbETH lot (NEW-01) are the material impact. Other failed pairs
  (ZKSYNC/ARBITRUM/ETHEREUM stablecoins) are ≈$0 AVCO — **hygiene, not required for the Katana case**.

---

## B. Architecture & Data Flow

### B.1 Component map (stages touched)

```
NORMALIZATION ─────────────────────────────────────────────────────────────────────────────
  OnChainFamilyClassifier chain
    ├─ BridgeSettlementClassifier      (dest: BRIDGE_IN from registered BRIDGE contract)   [Layer A/D]
    └─ BridgeStartClassifier           (src: BRIDGE_OUT, address-anchored callDiamond)      [Layer A]
         └─ ProtocolRegistryService ← protocol-registry.json                                [Layer A]

LINKING (clarification sweeps) ─────────────────────────────────────────────────────────────
    ├─ LiFiBridgePairLinkService       (seed src → find dest; materializePair)              [Layer B/C/D]
    │    └─ resolveRoutedBridgeFallbackDestination / isStrongRoutedBridgeFallbackDestination
    │    └─ resolveRelayPayoutEntry     (trusted settlement evidence)                       [Layer A/D]
    ├─ CrossNetworkBridgePairFallbackService (orphan BRIDGE_IN ↔ BRIDGE_OUT)               [Layer B/D]
    └─ BridgePairLinkSupport           (retag flows, cc flag, counterparty)                 [Layer C]

REPLAY (AVCO) ──────────────────────────────────────────────────────────────────────────────
    ReplayDispatcher → TransferReplayHandler.applyTransfer
      └─ isLinkedBridgeSettlementTransfer → LinkedBridgeTransferReplaySupport
                                            .applyLinkedBridgeSettlementTransfer            [Layer C]
      keyed by ReplayPendingTransferKeyFactory.bridgeSettlementKey ("bridge-settlement:")   [Layer C]
```

### B.2 Current (broken) data flow

```
 UNICHAIN                                            KATANA
 ┌───────────────────────────┐                      ┌──────────────────────────────┐
 │ 0xda7d556e (USDC -2050.04)│                      │ 0xc0aaf96b (ETH +0.452894)   │
 │ to=0x1bcd304f  UNREGISTERED│                      │ from=0x8c826f79 GAS_PAYER    │
 └─────────────┬─────────────┘                      └───────────────┬──────────────┘
   BridgeStartClassifier                              BridgeSettlementClassifier
   callDiamond addr-anchor MISS ✗                     needs family=BRIDGE → relayer is
   → EXTERNAL_TRANSFER_OUT (SELL)                      AGGREGATOR ✗ → EXTERNAL_TRANSFER_IN (BUY)
                 │                                                    │
                 ▼                                                    ▼
   LiFiBridgePairLinkService.isLiFiSourceCandidate      no BRIDGE_OUT to pair to;
   requires type==BRIDGE_OUT ✗ → never processed        CrossNetworkBridgePairFallback is
                                                          same-family-only ✗ (USDC≠ETH)
                 └──────────────── NO correlationId ─────────────────┘
                                        │
                                        ▼
   REPLAY: src USDC DISPOSE @ market;  dest ETH ACQUIRE @ DZENGI $4,715.67  (FABRICATED)
```

### B.3 Target data flow

```
 UNICHAIN                                            KATANA
 ┌───────────────────────────┐                      ┌──────────────────────────────┐
 │ 0xda7d556e (USDC -2050.04)│                      │ 0xc0aaf96b (ETH +0.452894)   │
 │ to=0x1bcd304f  REGISTERED  │  [Layer A]           │ from=0x8c826f79 GAS_PAYER    │
 │ LiFi BRIDGE/BRIDGE_ENTRY   │                      │ (trusted LiFi payout evid.)  │ [Layer A/D]
 └─────────────┬─────────────┘                      └───────────────┬──────────────┘
   BridgeStartClassifier                              stays EXTERNAL_TRANSFER_IN at
   addr-anchor HIT + route tag ✓                      classification; promoted in linking
   → BRIDGE_OUT                                                       │
                 │                                                    │
                 ▼                                                    ▼
   LiFiBridgePairLinkService  ── cross-asset pairing [Layer B] ──────►  promote → BRIDGE_IN
   (registered settlement evidence + route tag + Δt≤180s + USD-value tol + unique)
                 │  materializePair: cc=FALSE, both legs TRANSFER (prices cleared),          [Layer C]
                 │  matchedCounterparty set on BOTH, corrId=bridge:lifi:0xda7d556e…
                 ▼
   REPLAY  bridge-settlement:<corrId>  (network-agnostic key, both legs)                     [Layer C]
     src TRANSFER(-) → REALLOCATE_OUT: drains USDC basis (≈$2,050.04) into settlement queue
     dst TRANSFER(+) → REALLOCATE_IN : restores ETH @ carried $2,050.04  → AVCO ≈ $4,526.7/ETH
   RESULT: no fresh ACQUIRE; basis conserved; feeds Katana SushiSwap LP + vbETH at correct AVCO
```

---

## C. Layer A — Registry Coverage (REQUIRED for Katana; highest leverage, lowest risk)

### C.1 New registry entry — UNICHAIN LI.FI diamond/executor (enables source `BRIDGE_OUT`)

File: `backend/core/src/main/resources/protocol-registry.json`, under `"contracts"`.

```json
"0x1bcd304fdad1d1d66529159b1bc8d13c9158d586": {
  "name": "LI.FI Diamond (Unichain executor)",
  "protocol": "LI.FI",
  "version": "V1",
  "family": "BRIDGE",
  "role": "BRIDGE_ENTRY",
  "event_type": "BRIDGE_OUT",
  "networks": ["UNICHAIN"],
  "confidence": "HIGH",
  "notes": "NEW-08: Unichain LI.FI executor for callDiamondWithEIP2612Signature (0xd7a08473) / callDiamondWithPermit2 (0x0193b9fc) bridge-out. Address-anchored (see BridgeStartClassifier.knownLiFiDiamondEntry); route tag (jumper.exchange) required, not amount-heuristic."
}
```

Why this is sufficient and deterministic:
- `BridgeStartClassifier.classifyLiFiRouteBridge()` treats `callDiamond*` selectors as
  **address-anchored**: the guard `if (callDiamondSelector && knownLiFiDiamondEntry.isEmpty()) return
  empty` currently fires because the address is missing. `knownLiFiDiamondEntry()` requires
  `family=BRIDGE ∧ role∈{BRIDGE_ENTRY,ROUTER} ∧ protocolName contains "lifi"` — the entry above
  satisfies all three, so the guard passes.
- It still additionally requires `LiFiRouteSupport.hasRouteTag(view)` (calldata carries
  `jumper.exchange`/`gasZipBridge` — present) and `hasBridgeFunding` (outbound USDC — present) and
  `!hasSameWalletInboundTransfer`. Classification is **selector + address + route-tag** gated → no
  amount heuristic. Result: `BRIDGE_OUT`.

### C.2 Destination relayer settlement — accept LiFi `GAS_PAYER` as trusted payout evidence (code, not registry mutation)

The registry entry for `0x8c826f79…` (`AGGREGATOR/GAS_PAYER`, networks incl. KATANA/UNICHAIN) stays
as-is. Two edits make it resolvable as **destination bridge settlement evidence**, mirroring the
already-trusted Relay `GAS_PAYER` path:

- File: `LiFiBridgePairLinkService.java`, method `isRelayPayoutEntry(ProtocolRegistryEntry)` →
  generalize to also accept LiFi:

  ```
  // was: role == GAS_PAYER && isRelayProtocol(name)
  // now: role == GAS_PAYER && (isRelayProtocol(name) || isLiFiProtocol(name))
  ```
  (`resolveRelayPayoutEntry(...)` already checks the top-level `from`/wallet recipient shape and only
  fires when the inbound top-level `from` is this registered relayer — it stays address-anchored.)

- This makes `resolveRoutedSettlementEntry(...)` return **trusted settlement evidence** for the
  Katana native settlement, which (a) lets `isStrongRoutedBridgeFallbackDestination` use the
  relaxed (trusted) 180s/15% window instead of the generic 90s/2% one, and (b) stamps
  `protocolName=LI.FI` on the destination. It is the same seam the audit references
  (`isRelayPayoutEntry already accepts a Relay GAS_PAYER payout`).

### C.3 Address provenance (do before merge — do not ship blind)

Verify `0x1bcd304f…` is a genuine LI.FI Unichain contract by at least two of:
1. LI.FI published deployments (`https://github.com/lifinance/contracts` deployment JSON for
   Unichain) / LI.FI API `GET /v1/chains` + contract metadata.
2. Unichain explorer contract label / verified source (Diamond/Executor/Permit2Proxy family).
3. Cross-check the raw calldata already captured for `0xda7d556e…558de7`
   (`callDiamondWithEIP2612Signature`, `jumper.exchange`, GasZip proxy `0x864b314d…9232`).

Prefer sourcing coverage from LI.FI's published per-chain contract set (Diamond, Permit2Proxy,
Executor, Receiver) rather than per-incident patches. If provenance cannot be established at HIGH
confidence, register at `MEDIUM` — classification still succeeds (address-anchored + route tag), and
`MEDIUM` confidence flows through unchanged.

### C.4 Broader-chain hygiene (NOT required for the Katana case)

- Add `ZKSYNC` to the Relay source depositor `0x2ec2c4c3…926e` `networks` list (currently
  `ARBITRUM/ETHEREUM/BASE/OPTIMISM`) so ZKSYNC USDC bridge-outs type as `BRIDGE_OUT`
  (`RelayBridgeClassificationSupport.resolveRelayDepositoryBridgeEntry`). Stablecoin → ≈$0 AVCO.
- Audit other newer chains against LI.FI/Relay published contract sets. Track as follow-up; keep out
  of the Katana-critical path.

---

## D. Layer B — Cross-Asset Pairing Algorithm (REQUIRED for Katana)

Once the source is `BRIDGE_OUT`, `LiFiBridgePairLinkService.isLiFiSourceCandidate` accepts it. The
remaining gap: **every deterministic local pairing path is same-family only** and cannot pair
USDC→ETH.

### D.1 Where to change

1. `LiFiBridgePairLinkService.isStrongRoutedBridgeFallbackDestination(source, destination)` — the
   deterministic local fallback used inside `seedSourceCounterparty` (no external API). Today it
   hard-requires `supportsBridgeContinuity(sourceFlow, destinationFlow)` (same family / canonical
   alias) → returns `false` for USDC→ETH.
2. `CrossNetworkBridgePairFallbackService.isCompatibleOutbound(...)` — for the reverse entry point
   (orphan `BRIDGE_IN` looking back for a `BRIDGE_OUT`). Today requires
   `inboundFamily == outboundFamily`.

### D.2 Matching keys (deterministic, address/route anchored)

Pair `BRIDGE_OUT`(assetX, netA) with `BRIDGE_IN`/`EXTERNAL_TRANSFER_IN`(assetY, netB) iff **all** hold:

| # | Gate | Source of truth |
|---|---|---|
| 1 | Same wallet | `source.walletAddress == destination.walletAddress` |
| 2 | Cross-network | `source.networkId != destination.networkId` |
| 3 | Single principal each side | `principalFlows(source,-1).size()==1 && principalFlows(destination,1).size()==1` |
| 4 | Trusted settlement evidence on destination | `resolveRoutedSettlementEntry(destRaw,dest).isPresent()` — a **registered** LiFi/Relay/Across bridge or LiFi/Relay `GAS_PAYER` payout (Layer A/C.2). **Never** value+time alone. |
| 5 | Route identity on source | `LiFiRouteSupport.hasRouteTag(sourceView)` OR `isLiFiProtocol(source.protocolName)` (route/protocol anchored) |
| 6 | Time window | `|Δt| ≤ 180s` (trusted) — reuse `ROUTED_BRIDGE_FALLBACK_MAX_TIME_DELTA`; the pair is 2s |
| 7 | **USD-value proximity** (replaces qty tolerance for cross-asset) | `|srcUsd − dstUsd| / max(srcUsd,dstUsd) ≤ VALUE_TOL` |
| 8 | Uniqueness | exactly **one** accepted candidate; else abstain |
| 9 | Pairing-state compatible | existing `isPairingStateCompatible` / `isCorrelationCompatible` unchanged |

`VALUE_TOL` proposal: `0.02` (2%) when the destination has trusted registered settlement evidence
(bridge fees/slippage on a same-route corridor are small). Compute USD via existing flow
`valueUsd`, else `qty × replayMarketAuthority`/`unitPriceUsd` at each leg's own timestamp. If **either**
leg's USD value cannot be resolved, **abstain** (do not fall back to qty across different assets).

### D.3 Algorithm (pseudocode) — cross-asset branch inside `isStrongRoutedBridgeFallbackDestination`

```
accept(source, dest):
  if !isMaterializableDestination(dest): return false
  if !sameWallet || sameNetwork || sameHash: return false
  if !pairingStateCompatible(both) : return false
  srcP = singlePrincipal(source, -1);  dstP = singlePrincipal(dest, +1)
  if srcP==null || dstP==null: return false
  trusted = resolveRoutedSettlementEntry(destRaw, dest).isPresent()      // gate #4
  if supportsBridgeContinuity(srcP, dstP):                               // same-asset: existing path
       return existingSameAssetChecks(...)                               // (qty tol, unchanged)
  // ---- cross-asset branch (NEW) ----
  if !trusted: return false                                             // MUST be registered-anchored
  if !hasRouteTag(sourceView) && !isLiFiProtocol(source.protocolName): return false
  if |Δt| > 180s: return false
  srcUsd = usdValue(source, srcP);  dstUsd = usdValue(dest, dstP)
  if srcUsd==null || dstUsd==null: return false                        // abstain, never qty
  if relDiff(srcUsd, dstUsd) > VALUE_TOL: return false
  return true
```

The caller `resolveRoutedBridgeFallbackDestination` already enforces **uniqueness**
(`accepted.size() != 1 → empty`), satisfying gate #8. `CrossNetworkBridgePairFallbackService` gets
the symmetric change in `isCompatibleOutbound` (USD-value + trusted-evidence gate for the cross-asset
case; keep `quantitiesCompatible` for same-family).

### D.4 Precedence vs same-asset pairing

- **Same-asset (`supportsBridgeContinuity==true`) is attempted first** and keeps its existing
  quantity-tolerance path and `continuityCandidate=true` (plain move-basis). The cross-asset branch
  is only reached when families differ. No behavior change for same-asset corridors.
- The LiFi status-API path (`liFiStatusGateway`) remains a later, optional enrichment; the local
  deterministic path above runs first (`seedSourceCounterparty` calls
  `resolveRoutedBridgeFallbackDestination` before any API lookup when no status is present).

---

## E. Layer C — Cross-Asset Value-Carry Accounting (REQUIRED for Katana)

### E.1 The accounting policy already exists — reuse it

For `BRIDGE_OUT(assetX) → BRIDGE_IN(assetY)` with `continuityCandidate=false`, the replay already
implements value-carry via `bridge-settlement:<corrId>`:

- `ReplayPendingTransferKeyFactory.bridgeSettlementKey` emits the key for a `BRIDGE_OUT`/`BRIDGE_IN`
  leg when: role is `TRANSFER`, single principal, `cc=false`, `correlationId` set,
  `matchedCounterparty` set. It is **network-agnostic** (keyed only on `correlationId`).
- `TransferReplayHandler.applyTransfer → isLinkedBridgeSettlementTransfer →
  LinkedBridgeTransferReplaySupport.applyLinkedBridgeSettlementTransfer`:
  - source outbound `TRANSFER(-)` → `removeTransferCarry` drains the source USDC basis and enqueues
    it → `routeSettlementBasisEffect` = **REALLOCATE_OUT**;
  - destination inbound `TRANSFER(+)` → drains the queued carry → `restoreToPosition` with the
    carried basis → **REALLOCATE_IN**.
- Result: destination ETH basis = source consideration given ($2,050.04); AVCO ≈ $4,526.7/ETH; no
  fresh market `ACQUIRE`. This maps onto the existing SWAP-style dispose→acquire semantics via
  REALLOCATE (no synthetic source PnL), the documented conservative policy
  (`03-basis-pools-and-carry.md` §"Asset-changing bridge settlement").

**Conclusion: no new accounting engine, no new pending-key, no new handler is required.** The work is
to make the linker **shape** the pair so this path fires.

### E.2 The one real code change: stop suppressing the simple 1:1 cross-asset carry

`LiFiBridgePairLinkService.materializePair` today contains the `simpleCrossAssetSwap` guard: for
`cc=false` + exactly one outbound + one inbound of different families, it **nulls
`matchedCounterparty` on the source**. That makes `bridgeSettlementKey` return `null` on the source
(the key requires `matchedCounterparty`), so the source never enqueues its carry → the destination
drain finds nothing → spot fallback → fabricated `ACQUIRE`. This is exactly our case.

Root of the old `~$968` orphan the guard was defending against: a **supplemental LINKED top-up**
destination that used `bridgeTransferKey` (`bridge:`) while the source used `bridgeSettlementKey`
(`bridge-settlement:`) — mismatched key families. That mismatch is specific to the supplemental
multi-flow shape, **not** the primary simple 1:1 pair (where the destination has no `LINKED:`
counterparty and therefore also resolves `bridge-settlement:`).

Required change (design intent for the implementer):

1. **Narrow the guard** so it only suppresses the source counterparty for the **supplemental
   LINKED / multi-flow** shape, not the primary simple 1:1 cross-asset pair. For the primary simple
   1:1 pair, **keep `matchedCounterparty` on both legs** and set `continuityCandidate=false`.
2. **Retag both principal legs to `TRANSFER` with cleared price fields for the cross-asset pair.**
   Today `retagPrincipalFlowsForBridgeContinuity` runs only in the `if (continuityCandidate)` block
   (same-asset). The destination inbound is already retagged unconditionally by
   `alignDestinationInboundRolesForBridgeSettlement`; the **source** is not. Add a cross-asset
   source retag (a `cc=false`, single-principal analogue of
   `BridgePairLinkSupport.retagPrincipalFlowsForBridgeContinuity` that sets the outbound principal to
   `TRANSFER` and clears `unitPriceUsd/valueUsd/priceSource/avcoAtTimeOfSale/realisedPnlUsd`). Both
   legs must end as single-principal `TRANSFER` so `bridgeSettlementKey` fires on both with the same
   `bridge-settlement:<corrId>`.
3. Keep `correlationId = bridge:lifi:<sourceTxHash>` on both legs (already done by `correlationId()`)
   and promote the destination `EXTERNAL_TRANSFER_IN → BRIDGE_IN` (already done in the
   `isMaterializableDestination` block).

Post-change invariant to assert: for the simple 1:1 cross-asset pair,
`bridgeSettlementKey(source) != null && bridgeSettlementKey(destination) != null` and both equal
`bridge-settlement:bridge:lifi:<sourceHash>`.

### E.3 ADR / policy note

- **No new accounting-policy ADR is strictly required** — the value-carry policy already exists.
- **Recommended: a short ADR addendum** (amend ADR-020 / ADR-033 area, or a one-file ADR) recording
  that simple 1:1 cross-asset LiFi/Jumper bridges now route through the asset-changing settlement
  carry (superseding the prior guard that treated them as market dispose+acquire), and documenting
  the deliberate choice of **REALLOCATE (no source PnL realization)** over dispose-at-AVCO for the
  cross-asset source leg.
- **Flag for the accounting owner (out of scope here):** REALLOCATE defers PnL on a *volatile→volatile*
  cross-asset bridge; for a stablecoin source (our case, and all material cases in this universe)
  REALLOCATE and dispose-at-AVCO are financially identical (source PnL ≈ $0). If a future audit shows
  a material volatile→volatile cross-asset bridge, revisit realize-vs-defer as a separate decision.

---

## F. Layer D — Anti-Over-Correlation Guardrail (REQUIRED — safety)

The pairing MUST NOT promote scam/dust/address-poisoning receives or unrelated external transfers.
Deterministic gates (all must hold; any miss → leave as `EXTERNAL_TRANSFER`, unchanged):

1. **Registered-contract anchor (hard).** Cross-asset promotion requires the destination inbound to
   have **trusted registered settlement evidence** (`resolveRoutedSettlementEntry` present — a
   registered bridge or LiFi/Relay/Across `GAS_PAYER` payout). A receive from an unregistered EOA or
   an unknown token contract can **never** become a bridge. This alone excludes scam-token airdrops
   (`priceSource=UNKNOWN`, no registered contract) and address-poisoning look-alikes.
2. **Route/protocol anchor on source (hard).** Source must be a registered bridge `BRIDGE_OUT` OR
   carry a LiFi route tag / LiFi protocol name.
3. **Never demote/relabel `SCAM`.** Do not process rows already typed `SCAM`; address-poisoning
   SELLs stay `SCAM`/external. Restrict candidates to `isMaterializableDestination`
   (`BRIDGE_IN` or inbound-only `EXTERNAL_TRANSFER_IN`).
4. **Time proximity (hard).** `|Δt| ≤ 180s` (trusted). Dust airdrops arriving unrelated in time fail.
5. **USD-value proximity (hard).** Cross-asset requires `relDiff(srcUsd,dstUsd) ≤ VALUE_TOL` with
   **both** legs USD-resolvable; abstain otherwise. A $2,050 out vs a $0.001 dust in fails.
6. **Uniqueness (hard).** Exactly one candidate; ambiguity → abstain (existing `accepted.size()!=1`).
7. **Same wallet + cross-network + single principal each side** (structural).

These are the same anchors the audit prescribes ("only registered bridge contracts + route
tags/value-time proximity; never promote scam/dust/address-poisoning receives"). No amount-only or
time-only promotion is permitted anywhere.

---

## G. Risks, Regression Surface & Rebuild Mode

### G.1 Regression risks & mitigations

| Risk | Mitigation |
|---|---|
| Breaking currently-working **same-asset** bridges (ETH→ETH move-basis) | Cross-asset branch is reached **only** when `supportsBridgeContinuity==false`; same-asset keeps `cc=true`, `bridgeTransferKey`, existing qty tolerance. Add explicit regression test on a same-asset corridor. |
| Re-opening the `~$968` supplemental-LINKED orphan | Narrow the guard to the supplemental/multi-flow shape only; keep the supplemental path (`materializeSupplementalSourceAnchor`) untouched; assert both simple-pair legs resolve `bridge-settlement:` (same key family). |
| Over-correlation of scam/dust | Layer D hard gates (registered-contract anchor + route anchor + value/time proximity + uniqueness). Add negative tests (scam airdrop, unregistered EOA, dust, ambiguous). |
| BASE relayer corridor already correct → double-touch | BASE source is already `BRIDGE_OUT`; changes are additive (relayer acceptance generalized, cross-asset branch gated). Assert BASE `0x50e25447…` remains `BRIDGE_IN`+correlated, byte-identical. |
| Value-carry mis-computed when a leg lacks USD price | Abstain on unresolved USD (no qty-across-assets fallback); leaves rows external rather than fabricating a wrong carry. |
| NEW-01 / NEW-02 interaction (recently fixed) | Katana ETH now enters LP at the corrected AVCO (≈$4,526.7); re-verify the SushiSwap LP + vbETH lot after rebuild (auditor re-run). Expect ETH-family basis to **drop** by $85.66 and AVCO by ~$189/ETH vs current. |

### G.2 Prod rebuild mode (per `prod-reset-rebuild-workflow` rule)

Registry + classification + linking + replay/accounting change → **full renormalization** required:

```bash
./scripts/prod-reset-rebuild-backend.sh --skip-frontend
```

- **No** `--clear-pricing-cache` (pricing policy unchanged; carried basis does not read new prices).
- `--backend-only` is **insufficient** (would skip renormalization/reclassification/replay).

---

## H. Test Plan & Ordered Task List

### H.1 Test list (unit + integration; specific fixtures/anchors)

**Layer A — registry / classification**
- `ProtocolRegistryLoaderTest`: JSON still loads; `lookup(UNICHAIN, 0x1bcd304f…d586)` →
  `family=BRIDGE, role=BRIDGE_ENTRY, protocol="LI.FI"`; no duplicate-key error.
- `BridgeStartClassifierTest`:
  - +: UNICHAIN `methodId=0xd7a08473` to `0x1bcd304f…`, route tag present, USDC −2050.04 outbound,
    no same-wallet inbound → `BRIDGE_OUT`.
  - −: same selector to an **unregistered** address → `Optional.empty()` (address-anchor guard
    unchanged).
- (hygiene) `RelayBridgeClassificationSupport` / loader: ZKSYNC added to `0x2ec2c4c3…` → ZKSYNC
  `EXT_OUT` to it types `BRIDGE_OUT` (fixtures `0x306505eb…`, `0x5c152961…`, `0xd14b27aa…`).

**Layer B/D — pairing & guardrail** (`LiFiBridgePairLinkServiceTest`,
`CrossNetworkBridgePairFallbackServiceTest`)
- +: source `0xda7d556e…:UNICHAIN` (BRIDGE_OUT, USDC −2050.040045) + dest `0xc0aaf96b…:KATANA`
  (from `0x8c826f79…`, ETH +0.452894), same wallet `0x1a87f12…d693f`, Δt=2s → paired:
  dest promoted `EXTERNAL_TRANSFER_IN → BRIDGE_IN`; both `correlationId=bridge:lifi:0xda7d556e…`;
  `continuityCandidate=false`; both principal legs `TRANSFER`; both `matchedCounterparty` set.
- − (guardrail): dest inbound from an **unregistered EOA** → not paired.
- − (guardrail): scam-token airdrop inbound (no registered contract, `priceSource=UNKNOWN`) → not
  paired.
- − (guardrail): dust inbound ($0.001) with $2,050 source → value tolerance fails → not paired.
- − (uniqueness): two eligible destinations in window → abstain.
- regression: same-asset ETH→ETH cross-network corridor still pairs with `cc=true` (move-basis),
  unchanged qty tolerance.
- regression: BASE `0x50e25447…` relayer corridor still `BRIDGE_IN` + correlated (byte-identical).

**Layer C — replay shaping & accounting** (`ReplayPendingTransferKeyFactoryTest`, replay
integration around `LinkedBridgeTransferReplaySupport`/`BridgeTransferReplaySupport`)
- `bridgeSettlementKey`: for the shaped cross-asset pair (cc=false, single TRANSFER principal,
  matchedCounterparty) → **non-null on both source and destination**, equal
  `bridge-settlement:bridge:lifi:0xda7d556e…`.
- same-asset (cc=true) → `bridgeTransferKey` (`bridge:`), `bridgeSettlementKey` null (unchanged).
- integration: replay the pair → source ETH... USDC leg `REALLOCATE_OUT` draining ≈$2,050.04;
  destination ETH `REALLOCATE_IN` basis `= $2,050.04`, `avcoAfterUsd ≈ $4,526.7/ETH`,
  `uncovered=0`, **no `ACQUIRE`, no `UNKNOWN`**, `costBasisDeltaUsd ≠ $2,135.70`.

**Arch/guard**
- `CorridorBasisConservationGuardTest`: the cross-asset corridor leaves **no** flagged orphan
  CARRY_OUT (settlement carry consumed) — extends the existing cross-asset-swap suppression case.
- Existing ArchUnit module-boundary suite must stay green (no new cross-package dependency
  introduced; changes are within `normalization.classification`, `linking.clarification`,
  `costbasis.replay`).

**End-to-end acceptance (after `--skip-frontend` rebuild)**
- `normalized_transactions`: `0xda7d556e…` = `BRIDGE_OUT`, `0xc0aaf96b…` = `BRIDGE_IN`, shared
  `correlationId=bridge:lifi:0xda7d556e…`, `continuityCandidate=false`.
- `asset_ledger_points`: Katana ETH point `basisEffect=REALLOCATE_IN`,
  `costBasisDeltaUsd=$2,050.04` (±dust), `avcoAfterUsd ≈ $4,526.7`; **no** ETH `ACQUIRE @ $4,715.67`.
- ETH-family total basis **down $85.66** vs pre-fix; max ETH-family AVCO no longer $4,715.
- Zero new material `UNKNOWN` / uncovered; scam/dust rows unchanged (still external).
- `financial-logic-auditor` re-run: NEW-08 → CLOSED; NEW-01 re-verified at corrected entry AVCO;
  no new Medium+ defect; no regression.

### H.2 Ordered task list (for the backend engineer)

1. **Verify** `0x1bcd304f…` provenance (§C.3); pick `confidence` (HIGH if verified, else MEDIUM).
2. **Layer A:** add the UNICHAIN LI.FI diamond entry to `protocol-registry.json` (§C.1);
   add `ProtocolRegistryLoaderTest` + `BridgeStartClassifierTest` cases.
3. **Layer A/C.2:** generalize `LiFiBridgePairLinkService.isRelayPayoutEntry` to accept LiFi
   `GAS_PAYER` (trusted destination settlement evidence); unit-test `resolveRoutedSettlementEntry`
   returns present for the Katana relayer settlement.
4. **Layer B/D:** add the cross-asset acceptance branch to
   `isStrongRoutedBridgeFallbackDestination` (and symmetric `isCompatibleOutbound` in
   `CrossNetworkBridgePairFallbackService`) with the §D.2 gates + §F guardrail; add value-proximity
   helper; add +/− tests.
5. **Layer C:** in `materializePair`, narrow the `simpleCrossAssetSwap` guard to the supplemental/
   multi-flow shape only, keep `matchedCounterparty` on both legs for the primary simple 1:1 pair,
   `cc=false`; add a cross-asset **source** retag-to-`TRANSFER` (clear prices), analogous to the
   destination `alignDestinationInboundRolesForBridgeSettlement`; assert `bridgeSettlementKey` fires
   on both legs.
6. **Replay tests:** `ReplayPendingTransferKeyFactoryTest` + integration proving REALLOCATE_OUT/IN
   value-carry and corrected AVCO; conservation-guard test.
7. **Regression tests:** same-asset corridor, BASE relayer corridor, scam/dust/EOA/ambiguity
   negatives.
8. **(Hygiene, optional / separate PR)** add ZKSYNC to Relay source `0x2ec2c4c3…`; audit other newer
   chains against LI.FI/Relay published contract sets.
9. **ADR addendum** (§E.3) documenting the guard change + REALLOCATE-vs-dispose choice.
10. **Rebuild** `./scripts/prod-reset-rebuild-backend.sh --skip-frontend`; run the §H.1 end-to-end
    acceptance; hand to `financial-logic-auditor` for NEW-08 close-out.

### H.3 Layer ranking (value / risk)

| Layer | Required to close Katana ETH case? | Value | Risk | Notes |
|---|---|---|---|---|
| A — registry coverage (UNICHAIN diamond + relayer acceptance) | **Yes** | High | Low | Earliest stage; enables source `BRIDGE_OUT` + trusted dest evidence |
| B — cross-asset pairing | **Yes** | High | Medium | Deterministic, registered-anchored, value+time gated |
| C — value-carry shaping (guard narrow + source retag) | **Yes** | High | Medium | Reuses existing settlement carry; guard change is the sensitive part |
| D — anti-over-correlation guardrail | **Yes** (safety) | High | Low | Hard gates; prevents scam/dust promotion |
| ZKSYNC/other-chain hygiene | No | Low | Low | Stablecoins ≈$0 AVCO; separate PR |

**Bottom line:** Layers A+B+C+D together close the user-flagged Katana ETH case by fixing the
earliest wrong stage (registry/classification) and letting the rerun rebuild correct downstream
state through the **already-existing** asset-changing bridge-settlement value-carry. The ZKSYNC and
other stablecoin coverage gaps are broader hygiene and can ship separately.
