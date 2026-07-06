# ADR-033 — Bridge Multi-Flow Role Alignment at Clarification Stage

**Status:** Accepted  
**Date:** 2026-06-14  
**Supersedes:** —  
**Related:** ADR-014 (conservation gate), ADR-017 (ETH family)

---

## Context

LI.FI / Jumper bridges occasionally deliver multiple assets to the destination wallet in a single transaction. A canonical example is a cross-asset bridge where the user bridges USDe (Arbitrum → Arbitrum via an intermediary) and the receiving transaction contains:

1. **USD₮0** — the bridged principal (the asset the user actually receives)
2. **ETH** — a small gas refund or protocol incentive paid by the bridge router

After on-chain normalization, both flows arrive with role `TRANSFER`. The `ReplayPendingTransferKeyFactory.bridgeSettlementKey()` guard requires `hasSinglePrincipalTransferFlow == true` for a transaction to receive a `bridge-settlement:` key. With two `TRANSFER` flows this guard fails → `bridgeSettlementKey = null` → the BRIDGE_IN cannot consume the corridor basis carry posted by the BRIDGE_OUT → carry orphaned.

---

## Decision

When `LiFiBridgePairLinkService` materializes a confirmed LI.FI bridge pair (source has been linked to destination), and the destination BRIDGE_IN has **≥ 2 inbound `TRANSFER`-role flows**, the service performs **role alignment** before persisting the destination:

1. **Primary flow selection** — `selectPrimaryInboundBridgeFlowForSourcePrincipal` identifies the principal bridged asset:
   - First priority: a destination flow whose `BridgeAssetFamilySupport.continuityIdentity` equals the source's outbound flow identity (same family or canonical alias).
   - Second priority: a destination flow that matches the stable-settlement rank (both source and candidate are USD stables, or both are EUR stables).
   - Tie-breaking: lowest relative quantity difference from the source outbound amount.

2. **Role demotion** — all non-primary inbound `TRANSFER` flows are demoted to `BUY`:
   - The bridged gas refund (ETH) becomes a `BUY` in the destination transaction, reflecting an incidental acquisition.
   - The primary flow retains `TRANSFER`, making `hasSinglePrincipalTransferFlow == true`.

3. **Guard conditions** — alignment applies only when:
   - The pair is **confirmed linked** (we are inside `materializePair`, called with a matched source and destination).
   - The destination is **inbound-only** (no outbound principal flows).
   - The source has **exactly 1 principal outbound flow** (multi-source bridges are excluded; their settlement key is already suppressed by the multi-flow guard).

---

## Consequences

- Multi-flow LI.FI destination transactions produce a valid `bridge-settlement:` key, allowing the BRIDGE_IN to consume the corridor basis carry posted by the BRIDGE_OUT.
- Secondary assets (e.g. ETH gas refund) are accounted as fresh acquisitions (`BUY`) with no carry basis, which is semantically correct — they are new assets, not continuations of the bridged principal.
- Same-asset LI.FI bridges (continuityCandidate=true) are unaffected: `supportsPlainMoveBasis` still returns `true` only when the destination has exactly 1 principal flow at call time.
- Standalone BRIDGE_IN transactions (no linked source) are not role-aligned; their flows remain as originally normalized.

---

## Alternatives Considered

- **Post-hoc replay repair** — Adjusting carry keys at AVCO replay time. Rejected: misdiagnoses the root cause (a clarification defect) and violates the principle of fixing defects at the earliest pipeline stage.
- **New NormalizedTransaction field** — Adding `primaryBridgeFlowIndex`. Rejected: unnecessary data model change when role semantics already encode the distinction.
