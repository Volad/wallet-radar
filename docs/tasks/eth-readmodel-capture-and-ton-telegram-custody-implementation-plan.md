# ETH read-model balance-capture hardening & TON Telegram-Wallet custodial attribution — implementation plan

Status: DRAFT v2 (awaiting user approval — do not implement)
Date: 2026-07-21
Audit: `financial-logic-auditor` cycle 2026-07-21 (Clusters A & B); see `results/blockers.md`,
`results/eth_basis.md`, `results/accounting-failure-analysis.md`, `results/required-changes.md`
(items 8–12), `results/protocol-rule-pack.md` (Telegram-Wallet custodial corridor rule pack).
Phase-3 review (2026-07-21): financial-logic-auditor + business-analyst + system-architect all
returned REVISE; v2 incorporates their required changes (see "Phase-3 review resolutions" at end).

## Scope

- **Networks / assets:** EVM ETH family (native ETH + lending-receipt/debt tokens, e.g. Base `aWETH`,
  Mantle `amanWETH`); TON native + jettons on the two TON wallets.
- **Wallets:** all 4 EVM wallets (ETH read-model); TON `UQDcaquh…` (Ton Defi), `UQAe4Uho…` (Ton wallet).
- **Blocker IDs:** Cluster A (ETH AVCO read-model), Cluster B (TON Telegram-Wallet custodial
  attribution). Related-but-separate: B2 (TON DEX peers mislabeled as external transfers) — tracked,
  not in this plan's core scope.
- **Explicitly NOT in scope:** the cost-basis/AVCO/replay engine (proven correct at baseline), any
  re-normalization of ETH accounting, bringing the off-chain Telegram Earn/"Доход" balance in-scope
  (that is a separate policy decision, not a bug fix).

## Root cause

### Cluster A — ETH AVCO "increase after adding Solana/TON" = read-model / balance-capture defect
- Earliest failed stage: **on-chain balance capture (read model)** — NOT classification, pricing,
  linking, cost-basis, or replay. Evidence state: `EVIDENCE_PRESENT_UNUSABLE`.
- The `FAMILY:ETH` ledger is intact: net AVCO **$3,015.56**, market **$3,027.32**, covered 3.8206 ETH.
  The ledger contains zero Solana/TON wallets, zero SPL/jetton contracts, zero foreign symbols — all
  four cross-contamination hypotheses (shared pool, symbol merge, cross-network link, shared quote
  basis) rejected at the data level.
- The dashboard AVCO is **covered-quantity-weighted off `on_chain_balances`** and silently skips
  buckets with no balance row. The ADR-067 balance refresh (shipped *bundled* with the Solana/TON
  integration → temporal correlation only, not causation) transiently **dropped the still-held Base
  `aWETH` lot** (0.7478 ETH @ ~$1,830, the cheapest lot). Removing the cheapest lot pulls the pool
  average to ≈$3,304, matching the reported inflated ~$3,307/$3,324.
- Current data is self-corrected (Base `AWETH` = 0.75132 present at the 19:03Z capture; dashboard net
  AVCO $3,017.67 ≈ ledger $3,015.56). The **defect is latent**: silent-drop + destructive
  delete-then-write, no multi-provider fallback for forced-live lending-receipt/debt candidates.

### Cluster B — TON inbound from Telegram-Wallet pooled operator = counterparty-attribution defect
- Earliest failed stage: **clarification / counterparty attribution**. Evidence state:
  `EVIDENCE_AMBIGUOUS` + partial `UNSUPPORTED_SCOPE`.
- `0:DD6FF02C…` (and `0:023895AE…`) are `wallet_highload_v3r1`, tonapi name **"Wallet in Telegram"**,
  ~208k TON pooled = Telegram-Wallet **custodial hot wallets**. The app-shown "your TG-Wallet address"
  `UQDdb_As…` encodes to exactly `0:DD6FF02C…` (shared custodial address, not a personal wallet).
- The inbound (`lzxYkMrmAV…`, 20.548366 TON) is already booked **`CARRY_IN` at market ≈ TON pool AVCO
  ($1.509)** with **$0 phantom P&L**; deposit legs to pool `0:AB3F2953…` are `CARRY_OUT` with $0
  realized P&L. So TON AVCO is **not** materially distorted (~$87.49 total custody-boundary basis).
- The only thing wrong today is the **counterparty label**: `UNKNOWN_EOA` instead of a custodial
  "Telegram Wallet" counterparty. This is attribution, not a basis bug.

## Changes (ordered — read-model first, then attribution; both independent of the accounting engine)

### A. Read-model / balance-capture hardening (Cluster A) — `--backend-only`
A1. **No silent drop on transient error.** In the on-chain balance refresh path, a candidate that
   resolved a nonzero net-flow must never be silently `continue`d/dropped on a transient
   `decimals()`/`balanceOf` error; fall back to the last snapshot / indexed value.
   (`OnChainBalanceRefreshService`.)
A2. **Multi-provider fallback for forced-live candidates.** Thread `requiresLiveBalanceOf` candidates
   through the existing provider chain (Ankr → BlockScout → Etherscan → live RPC) instead of the
   fallback-less single live-RPC path. Fits the existing balance-provider SPI (no SPI change).
   **All-providers-fail:** retain last-known snapshot + raise a health flag; **never drop**.
A3. **Non-destructive per-bucket upsert + wipe-branch hardening.** Refresh must upsert per bucket
   (idempotent by `_id = prefix:wallet:network:accountingIdentity`), not delete-then-write. Harden the
   two existing wipe paths so a transient query/RPC failure cannot erase the read model: the
   empty-candidate short-circuit (`deleteBalances` when candidates==0) and the global `deleteAll()`
   path must not run on a failed/partial fetch.
A4. **Zero/staleness reconciliation (distinguish transient miss from legitimate zero).** A bucket that
   *resolved* an authoritative zero (genuine full withdrawal/disposal) must write an explicit zero and
   drop out of holdings + AVCO weighting — the guard must NOT retain a genuinely-sold lot. A bucket
   whose balance is *missing/errored* (post-A1–A3 this state is now distinguishable from a genuine 0)
   AND whose ledger is not staler than the last good capture → prefer the ledger-covered quantity for
   the headline covered-qty-weighted AVCO **and** raise a coverage/health flag. Fallback snapshots have
   a **max-age bound**; a stale-beyond-bound fallback flags rather than silently backfills. The guard
   adjusts AVCO *weighting* + flags only; it must not override the displayed on-chain
   quantity/valuation in the other direction (never overstate holdings). Depends on ingestion being
   current (a real disposal not yet ingested would show balance 0 vs. covered ledger — the freshness
   gate prevents the guard from masking it).

### B. TON Telegram-Wallet custodial counterparty attribution (Cluster B) — re-clarification
B1. **Registry-driven detection (deterministic source of truth).** In `TonCounterpartyResolver`
   (clarification), attribute the peer as the **existing `EXTERNAL_CUSTODY` counterparty type** (NOT a
   new `CUSTODIAL` type; `CounterpartyType.EXTERNAL_CUSTODY` javadoc already reads "e.g. Telegram
   Wallet Доход/Earn") with label "Telegram Wallet" when the peer address is in a **config-seeded,
   `TonAddressCanonicalizer`-keyed operator-address registry** (e.g. `0:DD6FF02C…`, `0:023895AE…`),
   mirroring `TonProtocolRegistry`. Keep this behind the TON resolver — do NOT put TON/tonapi-specific
   logic in shared clarification code (`ExternalCustodyDestinationSupport`, `CounterpartyEnrichmentService`)
   or `AccountingUniverseService`.
   - tonapi / the `wallet_highload_v3r1` interface are **offline discovery aids only** to populate the
     registry — never on the per-row pipeline path, never in GET (no tonapi client exists today; the
     stored TonCenter payload carries neither peer `interfaces` nor `name`). Any runtime tonapi lookup,
     if ever added, must be Caffeine-cached, long-TTL, and advisory-only (registry always wins).
B2. **Type stays EXTERNAL, basis effects unchanged (MATERIAL).** The transaction MUST remain
   `EXTERNAL_TRANSFER_IN/OUT` with `CARRY_IN`/`CARRY_OUT`. On TON, `counterpartyType` drives the type:
   `CEX`/`PERSONAL_WALLET` → `INTERNAL_TRANSFER` (which would try to carry basis from a tracked source
   pool that does NOT exist for the untracked custodial account → wrong carry/shortfall). Therefore
   Telegram Wallet must use the **custodial-but-external** `EXTERNAL_CUSTODY` type (+ `custodialOffChain`
   flag as `ExternalCustodyDestinationSupport` already does), never CEX/"CEX-like". Do **not** convert
   to ACQUIRE/DISPOSE and do **not** zero inbound basis.
B3. **Unknown/rotating operator default.** A new highload operator neither in the registry nor
   resolvable offline degrades to `UNKNOWN_EOA` — **never guess "Telegram"**. General rule: highload
   interface alone is never sufficient for any custodial label. Deterministic negative cases already
   exist without tonapi: Bybit highload → `AccountingUniverseService.classify` = `EXCHANGE_ACCOUNT`;
   DEX routers (Ston.fi/DeDust/Omniston) → `TonProtocolRegistry` family=`DEX`.
B4. **(Recommended) custody-corridor linking.** Link the deposit leg (to pool `0:AB3F2953…`) and the
   withdrawal leg (from the TG hot wallet) as one out-of-scope custody corridor per user, so the UI
   shows "moved to/from Telegram-Wallet custody" rather than two unrelated external transfers (and so
   funds sent to custody that then "disappear" from the portfolio read as an expected boundary, not a
   new bug). No amount/asset symmetry required (off-chain conversion allowed). No peer-to-peer link to
   the user's other on-chain wallets. Promoted from optional → recommended for exactly this UX reason.

## Docs to update (Phase 4, before code)
- **ADR-067 addendum** (docs only, A1–A3): silent-drop, provider fallback, non-destructive upsert,
  wipe-branch hardening.
- **ADR-078 (new): "Read-model coverage guard & headline-AVCO source policy"** (A4) — flips the
  headline covered-qty-weighted AVCO from live-balance-sourced to ledger-sourced for a
  missing/errored-but-fresh bucket; interacts with ADR-017/045/061/067. **Requires explicit user
  approval.**
- **ADR-079 (new) or ADR-072 addendum: "Telegram-Wallet custodial-operator registry & TON custody
  attribution"** — a maintained *global* custodial-operator registry (vs ADR-072's user-designated
  per-session destinations); record "registry deterministic; tonapi discovery-only" and the
  `EXTERNAL_CUSTODY` reuse. **Requires explicit user approval.**
- `docs/pipeline/normalization/rules/` (TON counterparty families): the registry-driven detection rule
  + negative cases; cross-reference the off-chain Earn/"Доход" out-of-scope policy and the documented
  UX (custody CARRY_OUT → funds leave the tracked portfolio).

## Acceptance
- **A (rebuild `--backend-only`, then re-audit):**
  - dashboard net ETH AVCO ≈ ledger $3,015.56 (±$5); Base `aWETH` (0.75132) and Mantle `amanWETH`
    present after refresh.
  - **Regression DoD (answers the user's actual complaint):** adding/removing a Solana/TON wallet
    leaves the ETH headline AVCO unchanged.
  - **Miss case:** simulated transient `balanceOf`/`decimals` failure (and all-providers-fail) on a
    forced-live receipt candidate does **not** drop the lot or move the headline AVCO; a coverage flag
    is raised.
  - **Symmetric (false-positive) case:** a bucket with an authoritative on-chain **zero (real
    disposal)** still drops out of holdings and AVCO weighting (no ghost lot, no false coverage flag).
  - stale-beyond-max-age fallback raises a flag rather than silently backfilling.
- **B (re-clarification, idempotent rerun):**
  - inbounds from `0:DD6FF02C…`/`0:023895AE…` show `counterpartyType=EXTERNAL_CUSTODY`, label
    "Telegram Wallet"; **`normalizedType` stays `EXTERNAL_TRANSFER_IN/OUT` (does NOT become
    `INTERNAL_TRANSFER`)**.
  - `0:7F97F36D…` shows Bybit (`EXCHANGE_ACCOUNT`); a not-in-registry highload operator stays
    `UNKNOWN_EOA`.
  - **TON family:** pool AVCO unchanged ≈ $1.509, covered qty unchanged (~70.37 TON), total TON basis
    unchanged; custody OUT legs keep $0 realized P&L; no new uncovered/shortfall TON.
  - **USDT leg** (operator B `0:023895AE…` sent 13 USDT): `FAMILY:USDT` AVCO/covered/basis unchanged,
    `CARRY_IN` handled identically, no new USDT uncovered/shortfall.
  - **Portfolio total realized P&L unchanged** (not just per-family AVCO).
  - registry-only path (tonapi disabled) yields the same result on rerun; rerunning clarification over
    existing rows updates the label deterministically without duplicating counterparties or touching
    basis.

## Risks / notes
- **No re-normalization, no replay** for either cluster. A needs only a balance refresh
  (`--backend-only`); B needs re-clarification (attribution), no cost-basis/replay.
- Cluster A is currently self-corrected in data → the fix is fragility-hardening to prevent
  recurrence, not an emergency data repair.
- Determinism: the operator-address registry (not tonapi labels) is the source of truth so detection
  is stable on rerun and offline.
- B2/DEX (TON peers mislabeled as external transfers, e.g. Ston.fi/DeDust/Omniston) is a real but
  separate cluster; it affects TON swap inventory/AVCO more than this custody attribution and should be
  planned separately (see `results/required-changes.md` item 5).

## Phase-3 review resolutions (2026-07-21)
- **financial-logic-auditor** (REVISE→resolved): CUSTODIAL-stays-EXTERNAL guarantee (B2); coverage-guard
  staleness gating vs real disposal (A4); symmetric disposal + USDT-leg + portfolio-P&L acceptance.
- **business-analyst** (REVISE→resolved): transient-miss vs legitimate-zero disambiguation (A4);
  add/remove-SOL/TON regression DoD; fallback freshness bound; unknown-operator default; idempotent
  rerun; all-providers-fail; corridor linking promoted to recommended; UX doc note.
- **system-architect** (REVISE→resolved): reuse existing `EXTERNAL_CUSTODY` type (no new `CUSTODIAL`);
  registry-as-source-of-truth behind TON resolver, tonapi discovery-only (no tonapi client today);
  zero/staleness reconciliation + wipe-branch hardening; ADR-078 (A4) and ADR-079/ADR-072-addendum (B).
