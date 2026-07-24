# ADR-074 — Network-agnostic post-normalization invariant (generalizes ADR-052 to the network axis)

**Status:** Accepted
**Date:** 2026-07-20
**Theme:** Normalization boundary / network-family capability flags / ArchUnit enforcement

---

## Context

[ADR-052](ADR-052-venue-capability-spi-walletref-normalization-boundary-invariant.md) established the
invariant **"venue specificity ends at normalization"** and enforced it with ArchUnit
(`post_normalization_packages_must_not_depend_on_VenueRegistry_or_descriptors`) plus a
`VenuePrefixGuardTest` source scan. That closed the *venue* axis (Bybit/Dzengi).

With Solana and TON ingestion (ADR-063..ADR-071) a second leak appeared on the ***network* axis**:
post-normalization read/query view-assembly services re-derived network specifics that were already
known at normalization time. Two concrete anti-patterns:

- **`NetworkAddressFormat.isEvm(networkId)` in the lending read path.** `LendingCycleBuilder` used
  `isEvm(...)` to decide whether to synthesize outstanding collateral / promote a group to OPEN,
  because EVM lending surfaces a fungible receipt-token balance (Aave aTokens) while Solana/TON
  (Jupiter Lend, Kamino, TON) are receipt-less.
- **`"lp-position:solana:"` correlation-id prefix tests in the LP read/refresh path.**
  `SessionLpQueryService` and `LpPositionRefreshService` string-matched the network name baked into
  the correlation id to select the residual-tolerant, snapshot-driven closure used by Solana
  concentrated-liquidity (Meteora DLMM / Raydium CLMM) positions, which record a full removal as a
  terminal `LP_EXIT` (never `LP_EXIT_FINAL`).

Each such site means "add a network ⇒ edit the consumption plane" — exactly the coupling ADR-052
removed for venues.

---

## Decision

**After normalization, downstream is network-agnostic.** This is the direct generalization of the
ADR-052 invariant from the venue axis to the network axis, using the same three-plane model:
network specifics are resolved **once** at normalization and stamped as **semantic capability
flags**; read/query services consume the flags and never branch on `NetworkId` members,
`NetworkAddressFormat.isEvm(...)`, or a network name embedded in a string.

### 1. Capability flags on the normalized record (primitive/enum only)

Domain purity is preserved (`domain_must_not_depend_on_runtime_layers`): the flags are `Boolean`
fields on `NormalizedTransaction`, never a resolver/registry/descriptor type. They form an **open
capability vocabulary** (add a flag, not a closed enum branch):

- `receiptBearingCollateral` — `true` when the row's network represents lending collateral as a
  fungible receipt token (EVM aTokens/cTokens); `false` for receipt-less lending (Jupiter Lend on
  Solana, TON). Reuses the existing `custodialOffChain` (ADR-072) pattern.
- `lpConcentrated` — `true` for concentrated-liquidity LP positions whose full exit is a terminal
  `LP_EXIT` (residual-tolerant, snapshot-driven closure) — currently Solana DLMM/CLMM. EVM CL-NFT
  positions emit `LP_EXIT_FINAL` and are left unstamped.

Already-canonical `walletAddress` / `txHash` continue to live on the record (no new fields); read
services do not re-derive per-family identity in-band.

The `lpConcentrated` capability is also carried onto the derived `LpPositionSnapshot` record so the
refresh service can close ghost snapshots from the flag alone (no network / prefix test), and onto
`LendingHistoryEntryView` so the cycle builder reads it per event.

### 2. Stamping — the single place network specifics are allowed

A **single shared seam**, `NormalizedCapabilityFlagStamper.stamp(tx)` (package
`application/normalization`), derives both flags from fields that are always present / preserved on
the final normalized row — never from the raw payload:

- `receiptBearingCollateral = NetworkAddressFormat.isEvm(networkId)` — EVM `true`, Solana/TON
  `false`.
- `lpConcentrated = true` when `correlationId` carries the `lp-position:solana:` concentrated-liquidity
  grammar (Meteora DLMM / Raydium CLMM), keyed on the correlation-id prefix rather than `type` so it
  survives a rebuild that re-classifies the row.

Every builder path invokes the stamper as its last step, after the correlation id is finalized:

- EVM: `OnChainNormalizedTransactionBuilder` — from `build(...)` **and** the
  `rebuildAfterClarification(...)` / `rebuildAfterReclassification(...)` paths that
  `ON_CHAIN_RECLASSIFICATION` runs through.
- Solana: `SolanaNormalizedTransactionBuilder.build(...)`.
- TON: `TonNormalizedTransactionBuilder.build(...)`.
- `SolanaLpPositionReader` (single-network on-chain enrichment adapter) stamps
  `LpPositionSnapshot.lpConcentrated = true`.

**Reclassification robustness (regression fix, 2026-07-20).** Because reclassification rebuilds every
on-chain row through `rebuildAfterReclassification(...)` — which preserves `correlationId` but
constructs a fresh `NormalizedTransaction` — stamping only inside the network-specific `build(...)`
silently dropped the Solana flags after `ON_CHAIN_RECLASSIFICATION` (`receiptBearingCollateral` and
`lpConcentrated` absent on all Solana rows). Routing every build/rebuild path through the shared
stamper makes the flags **re-derive deterministically** on any rebuild. Guarded by
`OnChainNormalizedTransactionBuilderTest` assertions on the *rebuild* output (Solana LP →
`lpConcentrated=true`; Solana Jupiter Lend borrow → `receiptBearingCollateral=false`; TON/EVM
receipt flag), not just on `build(...)`.

### 3. Consumption — read the flag, never the network

- `LendingCycleBuilder.isReceiptLessLendingNetwork(...)`, the group OPEN-promotion, and
  `withSynthesizedOutstandingSupply(...)` are driven by `receiptBearingCollateral`; the
  `NetworkAddressFormat` dependency is removed from the class.
- `SessionLpQueryService.resolveClosed(...)` and `LpPositionRefreshService`
  (`computeClosedByCorrelation`, ghost-snapshot close, tx-fallback skip) are driven by
  `lpConcentrated`; the `"lp-position:solana:"` prefix tests are removed.

Behavior is **identical** — a pure refactor behind the new attributes (no accounting/pricing change).

### 4. Enforcement (ArchUnit + source scan)

- **ArchUnit (sibling to the ADR-052 rule):**
  `ModuleDependencyArchTest.post_normalization_read_query_packages_must_not_depend_on_NetworkAddressFormat`
  forbids `..costbasis..`, `..portfolio..`, `..pricing..`, `..liquiditypools..`, `..lending..`,
  `..api..` from depending on `NetworkAddressFormat` (the network-family classifier — the analogue of
  `VenueRegistry`). Scope note: the rule targets `NetworkAddressFormat` **only, not `NetworkId`** —
  DTOs/keys/records may freely *carry* `NetworkId` (data, not a branch). The single documented
  carve-out is the WS-3 live-position true-up machinery (`*LiveTrueUpService`), which sits upstream of
  the read plane (mirroring the venue rule's single-venue adapter allowance).
- **Source scan (`VenuePrefixGuardTest`-style):** `NetworkBranchGuardTest` scans post-normalization
  Java for the `"lp-position:solana:"` literal (ArchUnit sees types, not string constants), allowing
  only the ingestion plane (`application/normalization`, `application/liquiditypools/enrichment`).

---

## Consequences

- **Adding a network no longer edits the consumption plane** for these paths — the same guarantee
  ADR-052 gives for venues, now proven by the network ArchUnit rule + `NetworkBranchGuardTest`.
- New capability flags require full renormalization to back-fill (`null` on legacy rows). During the
  transition a `null` `receiptBearingCollateral` reads as "not receipt-less" (EVM behavior), so EVM
  results are unchanged even before renormalization; receipt-less/concentrated behavior activates once
  rows are re-stamped.
- The flags are an **open vocabulary**: a future non-EVM receipt-bearing lending protocol only sets
  `receiptBearingCollateral = true` in its builder — no read-path edit.
- **Out of scope (documented):** `NetworkId`-value comparisons that express a *protocol-deployment*
  data-availability nuance rather than a network-family branch (e.g. the Fluid-on-Plasma full-receipt
  PnL-warning gate in `LendingCycleBuilder`) are left as-is — they carry `NetworkId` as data, which the
  invariant explicitly permits, and generalizing them would invent a narrow dataset-specific flag.

---

## Related

- [ADR-052 Venue Capability SPI, WalletRef, and the Normalization Boundary Invariant](ADR-052-venue-capability-spi-walletref-normalization-boundary-invariant.md) — the venue-axis precedent this generalizes.
- [ADR-070 Solana LP on-chain enrichment](ADR-070-solana-lp-enrichment.md) — the `lp-position:solana:*` closure semantics now carried by `lpConcentrated`.
- [ADR-071 Live lending position SPIs](ADR-071-live-lending-position-spi.md) — the WS-3 live-position machinery carved out of the network ArchUnit rule.
- [ADR-072 External custody destinations](ADR-072-external-custody-destinations.md) — the `custodialOffChain` capability-flag pattern reused here.
