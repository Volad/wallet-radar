# ADR-060 — Accounting asset-family registry consolidation (consolidation Wave W9)

> **Status:** Accepted (incorporates `financial-logic-auditor` + `system-architect` APPROVE-WITH-CHANGES review, 2026-07-17)
> **Date:** 2026-07-17
> **Theme:** Config consolidation / cost-basis / AVCO family identity
> **Companion:** [`docs/tasks/hardcoded-registry-consolidation-proposal.md`](../tasks/hardcoded-registry-consolidation-proposal.md) (Wave W9)
> **Relates to:** [ADR-054](ADR-054-per-asset-avco-for-staked-derivatives.md) (C1/C2), [ADR-045](ADR-045-family-covered-weighted-move-basis-avco-series.md)
> **Rejects suggestion:** the proposal's §1.1 phrasing "*consolidate onto `CanonicalAssetCatalog`*" — **rejected** (see §B).

## A. Context, problem, and assumptions

The proposal (§1.1) flags "**symbol registries in triplicate**": `CanonicalAssetCatalog` (pricing),
`AccountingAssetClassificationSupport` (C1/C2), and `AccountingAssetFamilySupport` (families) each
carry overlapping alias/family maps (~95 symbols each). Wave W9's stated target was to "consolidate
onto `CanonicalAssetCatalog`".

On inspection the three registries encode **three different concerns that are deliberately, and in
places divergently, defined** — so a naive merge is a financial-correctness regression, not a
cleanup:

| Registry | Concern | Key semantics |
|---|---|---|
| `CanonicalAssetCatalog` | **Pricing identity** — alias → canonical → CoinGecko/exchange quote | `SAVAX→AVAX`, `BBSOL→SOL` (priced *as* the underlying) |
| `AccountingAssetClassificationSupport` | **AVCO carry-vs-realize** (ADR-054 C1/C2) | `SAVAX→FAMILY:SAVAX`, `BBSOL→FAMILY:BBSOL` (*own* cost-basis pool) |
| `AccountingAssetFamilySupport.SYMBOL_FAMILIES` | **Continuity family fallback** | union-ish of C1/C2 families + one supplemental entry |

**Counterexamples that forbid merging pricing with accounting** (the "priced-as-underlying but
own-pool" set — verified against the dataset by the auditor review):

- **SAVAX / BBSOL:** priced *as* AVAX/SOL (`CanonicalAssetCatalog`) but are **C2 distinct assets**
  with their own cost-basis pool (`FAMILY:SAVAX` / `FAMILY:BBSOL`). Collapsing the two concerns
  would either misprice or mis-pool them.
- **AAVASAVAX → AVAX (pricing):** the third case, omitted from the first draft. The Aave receipt
  aAvaSAVAX is aliased to AVAX for pricing yet must stay `FAMILY:SAVAX` for accounting. The dataset
  shows aAvaSAVAX trades **~1.22–1.23× AVAX**, so merging it into `FAMILY:AVAX` would corrupt AVCO.
- **WSTETH / METH / WEETH / CMETH:** intentionally **not** aliased to ETH for pricing (they resolve
  their own market USD), while their accounting families are per-token (C2) except CMETH (C1
  `FAMILY:METH`). Pricing and accounting identity are independently tuned here (ADR-054 §6). This is
  a *non-conflicting* case (different concern, not a divergence to preserve).

**The genuine duplication** is *within the accounting layer*: `SYMBOL_FAMILIES` is almost entirely
subsumed by `C1_UNDERLYING_FAMILY ∪ C2_CONTINUITY_FAMILY` (identical symbol→family values), and
`AccountingAssetFamilySupport.continuityIdentity(...)` already **delegates to the C1/C2 registry
first**, only falling back to `SYMBOL_FAMILIES` when the registry returns `null`.

**The one non-trivial divergence** (verified key-by-key by both reviewers): `AAVASAVAX →
FAMILY:SAVAX` exists **only** in `SYMBOL_FAMILIES`. It resolves to a family **without** being
classified C1 or C2 (`isC2DistinctAsset("AAVASAVAX") == false`, and today
`canonicalTokenIdentity("AAVASAVAX",…) == null` and `continuityFamilyIdentity(…) == null` at the
classification layer — the family is produced *only* by `AccountingAssetFamilySupport`'s
`SYMBOL_FAMILIES` fallback).

Two traps make a naive deletion unsafe:

1. **Second consumer.** `SYMBOL_FAMILIES` is read in *two* places in `AccountingAssetFamilySupport`:
   `continuityIdentity` (after the registry, before `inferredFamilyIdentity`) **and**
   `inferredFamilyIdentity` (line ~237, for the lending-lifecycle underlying). Both must be migrated.
2. **Lending-inference reroute.** If `AAVASAVAX` loses its explicit family, `continuityIdentity`
   falls through to `inferredFamilyIdentity`, whose `LendingAssetSymbolSupport` lifecycle mapping
   sends it to the **wrong** `FAMILY:AVAX` (SAVAX→AVAX lifecycle). So the replacement entry MUST be
   consulted **before** `inferredFamilyIdentity`, exactly where `SYMBOL_FAMILIES` sits today.

Neither the pure delete nor the "promote to C2" path is a mechanical zero-diff extraction; both
require an accounting judgement + full `financial-logic-auditor` verification. Hence this ADR.

**Assumptions:** the AVCO/NEC auditor baseline (terminal ETH/BTC AVCO, `asset_ledger_points=11312`,
per-family pool identities) is the acceptance surface; no pricing behavior may change; the in-flight
ETH-family work (uncommitted `AccountingAssetClassificationSupport` / `AccountingAssetFamilySupport`
changes) is the current HEAD baseline.

## B. Decision (proposed)

1. **Do NOT merge pricing (`CanonicalAssetCatalog`) with accounting family identity.** They are
   separate concerns with deliberately divergent entries (SAVAX/BBSOL/WSTETH). `CanonicalAssetCatalog`
   stays the single source of **pricing** identity, untouched by W9.

2. **Make `AccountingAssetClassificationSupport` the single source of accounting-family identity.**
   Delete the ~80-entry `SYMBOL_FAMILIES` map in `AccountingAssetFamilySupport`; family resolution
   derives from the C1/C2 registry (which `continuityIdentity` already consults *first*, so every
   subsumed key is already shadowed by the registry and never reaches the fallback). Migrate **both**
   `SYMBOL_FAMILIES` readers (`continuityIdentity` and `inferredFamilyIdentity`).

3. **Handle `AAVASAVAX` via B3a (adopted; B3b rejected).** aAvaSAVAX is a 1:1 Aave receipt of sAVAX,
   i.e. **C1-like**, sharing sAVAX's `FAMILY:SAVAX` pool — *not* a C2 distinct asset.
   - **B3a — adopted (zero-diff):** replace `SYMBOL_FAMILIES` with a one-entry
     `SUPPLEMENTAL_FAMILIES = { "AAVASAVAX" → "FAMILY:SAVAX" }`, consulted at the **same points and
     order** as `SYMBOL_FAMILIES` today (in `continuityIdentity` after the registry / **before**
     `inferredFamilyIdentity`, and in `inferredFamilyIdentity` before its registry fallback). Because
     the registry already resolves every subsumed key upstream, this reproduces today's output
     byte-for-byte, and the single supplemental entry defeats the lending-inference reroute trap.
   - **B3b — rejected:** adding `AAVASAVAX` to `C2_DISTINCT_ASSET` would make
     `normalizationClusterForSymbol` return the wrong `CLUSTER:ETH_STAKING` (aAvaSAVAX is not an
     ETH-staking asset), and the first draft's claimed `includeInSpotFamilyTimelineAggregation`
     impact was inaccurate (no change for non-ETH families).
   - **B3c — optional follow-up (NOT zero-diff), reviewer-gated:** classify aAvaSAVAX as **C1** of
     underlying `FAMILY:SAVAX` (consistent with every other Aave aToken). This is the cleaner
     long-term model but flips `canonicalTokenIdentity`/`continuityFamilyIdentity` for aAvaSAVAX from
     `null → FAMILY:SAVAX` (a carry-vs-realize surface). Deferred to its own `financial-logic-auditor`
     gate; ship B3a first as the documented zero-diff step.

4. **Externalize the accounting-family registry to `asset-family-catalog.yml` — DEFERRED (not now).**
   This is AVCO-hot-path identity data; single-source (§B2) already eliminates the drift risk, and a
   golden test + ArchTest give fail-fast coverage. Config externalization adds parsing surface for no
   incremental safety here, so it is explicitly deferred rather than "optional".

**Adopted:** B1 + B2 + **B3a**. B3c and §B4 are reviewer-gated follow-ups, out of scope for the W9
implementation.

## C. Consequences

- Kills the accounting-family duplication (one source of truth: the C1/C2 registry), removing the
  drift risk where `SYMBOL_FAMILIES` and the C1/C2 maps could disagree and split/merge an AVCO pool.
- Pricing/accounting separation is documented and enforced, preventing a future "helpful" merge that
  would reintroduce the SAVAX misclassification.
- No pricing change; with B3a, no AVCO change (auditor must confirm exact parity).

## D. Acceptance (strengthened per review)

ETH/BTC AVCO + `asset_ledger_points` alone **cannot** catch AVAX/SOL-side pool drift, so acceptance
requires:

- `financial-logic-auditor`: terminal AVCO for `FAMILY:ETH`, `FAMILY:BTC`, **and `FAMILY:SAVAX`,
  `FAMILY:AVAX`, `FAMILY:BBSOL`, `FAMILY:SOL`** all match the pre-W9 baseline exactly; and
  `asset_ledger_points = 11312`.
- **Six-surface golden test** pinning, for `AAVASAVAX` and a representative subsumed key set (e.g.
  WBTC, AETHWETH, SAVAX, BBSOL, CMETH, an Euler-indexed receipt): `continuityIdentity`,
  `canonicalTokenIdentity`, `continuityFamilyIdentity`, `isC1SameAsset`, `isC2DistinctAsset`,
  `normalizationClusterForSymbol` — before == after.
- **Lending-reroute regression test**: `continuityIdentity("AAVASAVAX", …) == FAMILY:SAVAX` (proves
  the supplemental entry is consulted before `inferredFamilyIdentity`, not rerouted to `FAMILY:AVAX`).
- `:backend:core:test` green (incl. the existing `AccountingAssetClassificationArchTest`).

## E. Sequencing constraint

The in-flight **APPROVED `eth-family-tracking-model` Phase 5** work edits these same maps
(`AccountingAssetClassificationSupport` / `AccountingAssetFamilySupport` show uncommitted changes).
W9 must not have concurrent editors on these files — land/settle the ETH-family work first (or
sequence W9 immediately after), then re-baseline the auditor before implementing.

## F. Status

Accepted with the review changes folded in (§B adopts B1+B2+B3a; B3c/§B4 deferred). Reviewers:
`financial-logic-auditor` and `system-architect` — both APPROVE-WITH-CHANGES, changes incorporated.
