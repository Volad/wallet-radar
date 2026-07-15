# ADR-057: Euler Finance EVK Internal Debt-Token Exclusion

| Field | Value |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-07-14 |
| **Theme** | Normalization / classification / inventory hygiene |
| **Implements** | BLOCKER-9 — Euler EVK Flash-Loan Inventory Pollution |

---

## Context

Euler Finance v2 uses the **Ethereum Vault Connector (EVK)** architecture. When a user opens or
rebalances a leveraged lending position (e.g. a `LENDING_LOOP_REBALANCE` transaction), the EVK
protocol internally mints **variable-debt tracking tokens** to bookkeep the user's outstanding
debt. These tokens are emitted as positive inbound ERC-20 transfers to the user's wallet address
but are **not economic assets**: they are internal protocol mechanics and are burned in the same
block when the debt is repaid.

Two such token contracts exist on AVALANCHE:

| Contract | Symbol |
|---|---|
| `0x2eb15b5e4e5749bdd46a8cca48c500f69bd0df5d` | `ERC20:D0DF5D` (Euler EVK variable-debt token) |
| `0x1d45674ec811f8a33c97616790bc5a81d4c9afac` | `ERC20:C9AFAC` (Euler EVK variable-debt token) |

Without exclusion the AVCO engine credits these inbound transfers as user acquisitions, creating
a phantom inventory balance that can never be burned — resulting in a permanent `lastShortfall`.

Additionally, `LENDING_LOOP_REBALANCE` transactions also contain transient **flash-loan inflows**
of the user's existing share tokens (e.g. `eUSDC-2`). These inflows arrive via the Euler flash-loan
mechanism, are used internally within the same transaction, and are fully repaid before the
transaction ends. They are identified by a sentinel counterparty address of the form
`UNKNOWN:<txHash>:NETWORK:WALLET:TRANSFER:ASSET:INDEX`, which the on-chain flow builder stamps on
legs whose counterparty cannot be resolved to a known address within the same transaction.

---

## Decision

### Part A — EVK debt-token clarification tagger

`EulerEvkDebtTokenTagger` is registered as a clarification pass in `LinkingBatchProcessor`.
For any `NormalizedTransaction` on AVALANCHE that contains a positive inbound flow
(`quantityDelta > 0`) from one of the registered EVK debt-token contracts, the transaction is
marked `excludedFromAccounting = true` with reason `EULER_EVK_INTERNAL_DEBT_TOKEN`.

This is implemented as a convergent clarification pass (idempotent, MongoDB query-gated so it
only touches un-tagged candidates). The entire transaction is excluded because EVK debt-token
receipts have no legitimate accounting meaning and the transactions that receive them are
pure Euler-internal rebalance mechanics.

### Part B — Flash-loan CARRY_IN suppression in ReplayDispatcher

In `ReplayDispatcher.replayGenericFlowsSkipping`, inbound flows within
`LENDING_LOOP_REBALANCE` transactions whose `counterpartyAddress` starts with `UNKNOWN:` and
contains the transaction hash are skipped during replay. These are the flash-loan legs described
above. The suppression is applied at replay time (not normalization time) because the sentinel
pattern is already stamped by the flow builder and requires no schema change.

---

## Rationale

| Option | Verdict |
|---|---|
| Exclude at normalization (classification stage) | Preferred for new pipelines; not used here because the EVK token contracts are known only post-classification and because existing `ClassificationDecision` does not have a per-flow exclusion mechanism — only a per-transaction flag. |
| Token exclusion registry (SpoofTokenQuarantineSupport) | Not used: spoof-token quarantine operates on address-only patterns for scam detection; EVK tokens require explicit allowlisting on AVALANCHE only. |
| Clarification pass (`EulerEvkDebtTokenTagger`) | **Chosen for Part A.** Consistent with `ScamDisperseClonePhishingTagger`. Clean, MongoDB-gated, idempotent. |
| Replay-time skip | **Chosen for Part B.** The sentinel counterparty pattern is already available at replay time and the fix requires zero schema changes. |

---

## Consequences

- `eUSDC-2`, `ERC20:D0DF5D`, `ERC20:C9AFAC` phantom shortfalls resolve to 0 after
  full pipeline re-run.
- Legitimate Euler lending flows (actual USDC deposits / withdrawals, real eUSDC-2 mints) are
  unaffected because they do not originate from the registered EVK debt-token contracts and do
  not use the flash-loan sentinel counterparty pattern.
- The tagger is bounded to AVALANCHE; it will not affect other networks.
- `ClassificationReasonCode.EULER_EVK_INTERNAL_DEBT_TOKEN` is the canonical reason code for
  both the exclusion flag and the `missingDataReasons` audit trail.
