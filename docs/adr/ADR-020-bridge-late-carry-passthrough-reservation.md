# ADR-020 — Bridge Late-Carry Pass-Through Reservation Invariant

**Date**: 2026-05-29 (amended 2026-06-13 — RC-2 out-hash-unique LiFi corridor relaxation)  
**Status**: Accepted  
**Related**: ADR-019 (Corridor Carry Policy), ADR-027 (LiFi destination discovery / corridor linking)

---

## Context

The `PassThroughCorridorPlanner` pre-builds corridors during the planning phase. A corridor links an inbound flow (e.g. BRIDGE_IN on ZKSync) to an outbound flow (e.g. LENDING_DEPOSIT on ZKSync) that should receive the carry from that inbound. The corridor is activated at replay time by `reservePassThroughCarryIfPlanned`, which stores the carry in `reservedPassThroughCarries` keyed by the inbound `FlowRef`.

For bridge pairs (BRIDGE_IN + BRIDGE_OUT), two replay orderings are possible:

1. **Happy path** — BRIDGE_OUT fires before BRIDGE_IN:  
   `applyLinkedBridgeTransfer (inbound)` finds the carry in the queue, calls `reservePassThroughCarryIfPlanned` → corridor activated ✓

2. **Broken path** — BRIDGE_IN fires before BRIDGE_OUT:  
   `applyLinkedBridgeTransfer (inbound)` finds no carry → calls `enqueuePendingInbound` → no reservation.  
   Later, `applyLinkedBridgeTransfer (outbound)` finds the pending inbound and calls `attachLateBridgeCarryToPendingInbound` to apply the authoritative carry. This method did **not** call `reservePassThroughCarryIfPlanned`.  
   Downstream consumers (LENDING_DEPOSIT, LP_ENTRY) called `takeReservedCarry` → returned null → fell back to draining the depleted family pool → captured near-zero AVCO.

### Observed Impact

ETH AVCO stuck at $1,953 (vs correct $2,692–$2,828) due to two ZKSync LENDING_DEPOSIT events capturing $3.49 and $7.62 cost basis instead of $1,598 and $1,343. Total under-attribution: $2,930.

---

## Decision

`attachLateBridgeCarryToPendingInbound` must call `reservePassThroughCarry` after applying the authoritative carry if the pending inbound carry stored a `sourceFlowRef`.

**Implementation:**

1. `CarryTransfer` gains a nullable `FlowRef sourceFlowRef` field — set only on `pendingInbound` carries and only when `flowIndex` is known at enqueue time.

2. `enqueuePendingInbound` is updated to accept `flowIndex` and compute `FlowRef sourceFlowRef = flowSupport.flowRef(transaction, flowIndex)`. All three call sites in `TransferReplayHandler` pass `flowIndex`.

3. `attachLateBridgeCarryToPendingInbound` receives `PassThroughCorridorPlan` and `Map<FlowRef, CarryTransfer> reservedPassThroughCarries` as additional parameters. After applying carry, if `pendingInbound.sourceFlowRef() != null`, it calls:
   ```java
   continuityCarryService.reservePassThroughCarry(
       passThroughCorridorPlan,
       pendingInbound.sourceFlowRef(),
       effectiveCarry,
       reservedPassThroughCarries
   );
   ```

**Secondary defensive change (P0-b):**

`PassThroughCorridorPlanner.selectWalletScopedInboundCandidate` now rejects cross-network pairings: when the outbound transaction has a non-null `networkId`, only pending inbound candidates with the same `networkId` are eligible. This prevents a same-wallet BRIDGE_IN from a different network pairing with a LENDING_DEPOSIT.

`PassThroughCandidate` stores the `networkId` of the inbound flow for this purpose.

---

## Invariant

> For all bridge pairs where BRIDGE_IN fires before BRIDGE_OUT in replay order, any pre-built pass-through corridor linking the BRIDGE_IN to a downstream consumer (LENDING_DEPOSIT, LP_ENTRY, STAKING_DEPOSIT, VAULT_DEPOSIT, or PROTOCOL_CUSTODY_DEPOSIT) **must** be activated inside `attachLateBridgeCarryToPendingInbound` by calling `reservePassThroughCarry` with the original BRIDGE_IN `FlowRef`.

> Downstream consumers **must** capture cost basis from `takeReservedCarry` when a reservation exists, never from the family pool directly.

---

## Consequences

- ETH family AVCO correctly reflects the full cost basis of ZKSync AZKSWETH deposits.
- Basis propagation from AZKSWETH → AARBWETH → AMANWETH is corrected.
- No change to happy-path ordering (BRIDGE_OUT before BRIDGE_IN) — `reservePassThroughCarryIfPlanned` already handled that path.
- No change to Bybit transit corridors (EXTERNAL_TRANSFER_IN/OUT) — these use counterparty-matched scope, not wallet-scoped fallback.
- `CarryTransfer.sourceFlowRef` is null for all non-pending-inbound carries and for pending inbounds created without a known `flowIndex` — backward compatible.

---

## Amendment (2026-06-13) — RC-2: out-hash-unique LiFi corridor pass-through relaxation

Prod audit (`results/blockers.md` B-ETH-02, `results/protocol-rule-pack.md` RP-2) found
cross-network LiFi ETH corridors whose destination `BRIDGE_IN` never inherited the source
`BRIDGE_OUT`'s reserved carry (UNICHAIN→ZKSYNC, BASE→LINEA, BASE→ARBITRUM). The source reserved
`CARRY_OUT` keyed `bridge:lifi:<outHash>` with `continuityCandidate=true`, but the destination
re-priced at market (F-5(a)) and left ~$2,945 of `CARRY_OUT` **orphaned** in the continuity
store. The dollar impact is small (market ≈ source AVCO), but the orphan is a determinism /
latent-divergence risk.

Root cause is two-fold and addressed in order:

1. **Linking stage (primary, RC-4 prerequisite):** the `bridge:lifi:<outHash>` corridor
   relationship was not propagated to the destination because the LiFi `BRIDGE_IN` was typed
   `UNKNOWN_EOA` (relayer EOA), not `BRIDGE`. RC-4 types any `BRIDGE_IN` whose
   `correlationId=bridge:lifi:<hash>` matches a known LiFi `BRIDGE_OUT` as `counterpartyType=BRIDGE`
   (ADR-027), making the corridor pairing unambiguous.

2. **Replay carry key (already network-agnostic — no P0-b change required):** the
   `BRIDGE_OUT → BRIDGE_IN` continuity carry is keyed by `ReplayPendingTransferKeyFactory.bridgeTransferKey`
   as `bridge:<correlationId>:<bridgeFamilyIdentity>` (and the supplemental
   `bridge:lifi:<outHash>:<family>` for `LINKED:` inbound legs). Neither embeds `networkId`, so once
   `materializePair` stamps both legs with the shared `bridge:lifi:<outHash>` correlation,
   `continuityCandidate=true`, and the `LINKED:<outHash>` inbound counterparty (RC-4), the
   destination consumes the source's reserved `CARRY_OUT` **across networks** with no further change.

   The **P0-b `selectWalletScopedInboundCandidate` same-`networkId` guard is intentionally left
   intact.** It governs a *different* path — the pass-through reservation that feeds a same-network
   downstream consumer (`LENDING_DEPOSIT`/`LP_ENTRY`/…) from a `BRIDGE_IN` — not the
   `BRIDGE_OUT → BRIDGE_IN` carry. Relaxing it broadly would re-break the same-wallet cross-network
   mis-pairing P0-b fixed, and it is not on the corridor-carry path, so RC-2 needs no relaxation here.
   If a future audit shows a residual same-network restriction *on the corridor-carry path itself*,
   relax it **strictly** for out-hash-unique LiFi corridors only (out-hash equality required; asset +
   minute proximity alone must never link cross-network).

### Target

Orphaned `CARRY_OUT` basis → 0; the destination inbound inherits the source carried AVCO; any
genuine excess tail still promotes at market-at-timestamp via F-5(a) (no double application).
**NOT** "CARRY net → 0" — the net bridged-in inventory (≈ +$8,566) is legitimate and preserved.

## Rejected Alternatives

- **Adding `networkId` to `PassThroughScopeKey.scope`**: Would break Bybit transit corridors where inbound arrives on ARBITRUM and outbound departs from MANTLE, both paired via the same counterparty address. The counterparty-matched scope path must remain network-agnostic.
- **Fixing the planner to not build corridors for late-arriving BRIDGE_IN**: The corridor planning is a pre-pass without replay state; it cannot know ordering. The fix must be in the replay handler.
- **Broadly relaxing the P0-b same-network guard** (RC-2): rejected — only out-hash-unique LiFi corridors qualify; asset+minute proximity must not link cross-network.
