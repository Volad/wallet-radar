# ADR-049: Venue-Agnostic CEX Transfer Linking

**Status:** Accepted  
**Date:** 2026-07-08  
**Theme:** Linking — generalize FA-001 beyond Bybit

---

## Context

[ADR-013](ADR-013-cex-cross-system-linking.md) established FA-001 cross-system linking for **Bybit** deposit/withdraw rows keyed on on-chain `txHash`. Dzengi (and future CEX venues) supply the same mechanical evidence: a chain transaction hash on deposit/withdraw API payloads.

Hard-coding `BybitTransferContinuityRepairService` or venue-specific linkers for each integration does not scale and violates the `application.cex.port` boundary (Track B1).

---

## Decision

### D1. FA-001 is venue-agnostic

Any canonical row that satisfies **all** of:

1. `source ∈ { BYBIT-normalized, DZENGI-normalized, … }` (CEX origin)
2. `type ∈ { EXTERNAL_TRANSFER_IN, EXTERNAL_TRANSFER_OUT }`
3. Non-blank `txHash` persisted on the canonical document
4. Matching chain-side row with the same `txHash` and quantity/timing tolerance

…participates in the same FA-001 `transfer_links` corridor defined in [ADR-003](ADR-003-transfer-links-fa001.md).

### D2. Venue prefix on wallet address, not on link key

- CEX side wallet: `BYBIT:<uid>`, `DZENGI:<uid>`, etc.
- Link key: `(networkId, txHash)` — **not** venue-prefixed.

### D3. Builder obligation

Each venue `*CanonicalTransactionBuilder` MUST copy venue-supplied chain hash fields onto `NormalizedTransaction.txHash`:

| Venue | Source field |
|-------|--------------|
| Bybit | `txID` on deposit/withdraw records |
| Dzengi | `blockchainTransactionHash` on deposit/withdraw payloads |

Missing hash → row cannot link; pool imbalance + conservation gate (ADR-013 D3).

### D4. No hot-wallet registry

ADR-013 D1 stands: no static exchange hot-wallet lists. Venue-agnostic FA-001 does not reintroduce registry files.

### D5. Linking stage ordering

`LinkingJob` consumes completion from all enabled CEX normalization stages (`BybitNormalizationCompletedEvent`, `DzengiNormalizationCompletedEvent`, …) before pricing.

---

## Consequences

- New CEX integrations only need canonical builder hash persistence + extracted deposit/withdraw streams — not a new linking subsystem.
- Frontend may show `matchedCounterparty` as `DZENGI:<uid>` after successful link (same UX pattern as `BYBIT:<uid>`).

## Related

- [ADR-013 CEX cross-system linking](ADR-013-cex-cross-system-linking.md)
- [Dzengi adaptation rules](../pipeline/normalization/rules/dzengi-adaptation.md)
- [Linking overview](../pipeline/linking/01-overview.md)
