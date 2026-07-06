# ADR-044 — Router-agnostic native-settlement recovery and native-pool reconciliation gate

**Date**: 2026-07-01
**Status**: Proposed
**Related**: ADR-001 (on-chain classification strangler), ADR-004 (physical quantity vs basis-backed), ADR-014 (portfolio conservation gate), ADR-018 (LP protocol-family flow materialization), ADR-019 (corridor carry policy), ADR-021/ADR-022 (SWAP multi-leg pricing, LP_EXIT per-asset attribution), ADR-031 (AVCO undefined representation), ADR-043 (corridor carry basis conservation — establishes the *guard-extension, WARN-then-HARD_FAIL, no post-factum sweep* pattern reused here)
**Implements**: `docs/tasks/native-settlement-recovery-implementation-plan.md` (to be authored by backend-dev from §Implementation plan below)

---

## Context

The native-ETH sub-pool `accountingAssetIdentity = NATIVE:BASE` for wallet
`0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f` is under-credited on the **inflow** side. Independent
reconstruction from the database confirms the defect and rules out the two alternative hypotheses:

- **Flow reconstruction (BASE, this wallet).** Non-fee native ETH inbound = **2.70325 ETH** (177 flows);
  non-fee native ETH outbound = **−2.82863 ETH** (157 flows); net = **−0.12538 ETH**.
- **Terminal ledger point.** The last `NATIVE:BASE` `asset_ledger_point` carries
  `quantityShortfallAfter = 0.126290492105982958`, `avcoAfterUsd = 0`, `hasIncompleteHistoryAfter = true`.
  The replay engine **faithfully records** the deficit — it is not miscomputing; its *input legs are
  missing quantity*. → rules out **(ii) replay-side ordering artifact**.
- **Gas is not the cause.** Total `FEE:ETH` (gas) on BASE = **−0.00110 ETH** over 416 transactions, two
  orders of magnitude below the 0.126 gap. → rules out **(i) cumulative gas debits**.
- **Ground truth.** `on_chain_balances` stores the native balance as `assetContract = "NATIVE:BASE"`,
  quantity **0.000794 ETH**; the tracked terminal quantity is **0.0000179 ETH**. The tracked pool is
  short of the on-chain balance even at the terminal snapshot.

**First wrong stage = leg extraction (ingestion/normalization), not replay.** Of the 42 CONFIRMED
`SWAP` / `LP_EXIT` / `LP_EXIT_FINAL` / `LP_FEE_CLAIM` / `UNWRAP` transactions on BASE that carry **no**
native inbound leg:

- **42 / 42** have **zero indexed internal transfers to the wallet** (the explorer never surfaced the
  native settlement trace — indexer lag / unindexed internal calls).
- **All 42 are CONFIRMED** — they were classified off the *visible* token leg (e.g. USDC out of an LP
  exit) and never routed to full-receipt clarification, so the missing native leg was **silently
  dropped** rather than flagged.
- Only **10 / 42** have a WETH `Withdrawal(src, wad)` log persisted (from an earlier clarification),
  summing to **≈ 0.054 ETH** of directly log-provable native inbound; the other **32** have **no logs
  persisted at all**, so today there is no evidence surface to recover from.

The pipeline already has three **router-specific** native-settlement recovery seams, all in
`MovementLegExtractor.extract(...)` and all guarded by a `hasInboundNative` double-count check:

- `OneInchNativeSettlementSupport` — 1inch selector `0x07ed2379` only.
- `ParaSwapNativeSettlementSupport` — ParaSwap V6 selectors `0x7f457675`, `0xe3ead59e` only.
- `LpNativeExitLegEnricher` — LP exit, keyed on embedded `decreaseLiquidity` / `burn` selectors + WETH
  `Withdrawal` logs.

The gap is therefore twofold and coupled:

1. **Recovery generality (R).** Native settlement is recovered only for three hard-coded router
   selectors. 0x Settler, LiFi, Odos, KyberSwap, Uniswap Universal Router, CoW, and generic multicall
   LP exits are not covered.
2. **Evidence availability (E).** The canonical, exact, router-agnostic proof of the settled native
   quantity is the WETH `Withdrawal(src, wad)` receipt log, but logs are only persisted when a
   **full-receipt clarification** was triggered. A swap/LP-exit that classifies CONFIRMED off its
   visible token leg never triggers it, so 32/42 candidates have no evidence surface.

**Downstream consequence chain (given, corroborated).** Under-credited `NATIVE:BASE` drains to
$0/dust → LiFi `BRIDGE_OUT` native `TRANSFER` legs carry `valueUsd = undefined` (observed) → the
Linea `BRIDGE_IN` is uncovered → the Linea `LENDING_DEPOSIT` computes `avco = 0/0 = null`, rendered
"NaN". The NaN is a *symptom*; the defect is the dropped native inflow quantity upstream.

---

## Invariant

> **(a) Per native pool (reconciliation).** After renormalization + replay, for every
> `NATIVE:<chain>` `accountingAssetIdentity` that is not on a declared unsupported carve-out:
> the terminal `quantityShortfallAfter ≤ NATIVE_DUST` **and** `hasIncompleteHistoryAfter = false`,
> **and** the terminal `quantityAfter` reconciles to the stored `on_chain_balances` native quantity
> (`assetContract = "NATIVE:<chain>"`) within `NATIVE_DUST`.
>
> **(b) No zero-basis native carry-out over covered basis.** No `BRIDGE_OUT` / `CARRY_OUT` native leg
> may emit `$0` (null/zero `valueUsd` / `costBasisDelta`) while the source `NATIVE:<chain>` family pool
> holds covered basis at that timestamp. A `$0` carry-out over a covered pool is the ghost signature
> the gate flags.
>
> **(c) No double-count.** When an inbound native leg is *already present* (indexed internal transfer,
> native-alias transfer, or msg.value credit), no recovery synthesizer may add a second native inbound
> leg. This is the existing `hasInboundNative` guard and it is load-bearing.

`NATIVE_DUST` is a per-chain native-unit epsilon (proposal: `1e-4` native units, i.e. ~$0.20–0.40 for
ETH-family) plus a USD floor consistent with `PortfolioConservationGate`'s dust posture.

---

## Decisions

### D1 — The fix lives in leg extraction, never in replay or cost-basis attribution

The missing quantity is an **ingestion evidence** defect. Replay records the shortfall correctly.
Restoring the inbound native leg at extraction time lets the **existing** pricing/replay paths value it
with no new basis logic:

- A **SWAP** output-to-native leg is priced by the existing SWAP multi-leg pricing (ADR-021/022) as a
  priced `ACQUIRE` (native BUY at execution price) — never `$0`.
- An **LP_EXIT** native leg carries basis from the LP receipt basis pool via the existing LP_EXIT
  per-asset attribution (ADR-022) — never `$0`.

We therefore **must not** patch replay handlers, `CounterpartyBasisPool`, bridge carry constructors,
or already-normalized canonical rows. No `CounterpartyRepairJob` / startup sweep / historical-row
patch (see Guardrails). Correctness is achieved by fixing the earliest wrong stage and relying on a
full renormalization rerun to rebuild downstream state.

### D2 — Consolidate the three router-specific enrichers into ONE router-agnostic recovery, evidence-first

Introduce a single `NativeSettlementRecovery` support that runs **last** in
`MovementLegExtractor.extract(...)`, subsuming (and eventually replacing) the 1inch / ParaSwap /
LP-exit enrichers behind one guarded seam. It fires **only** when `hasInboundNative(legs) == false`
(Invariant c) and sources the recovered native inbound quantity in strict priority order, using the
**most on-chain-provable** evidence first:

1. **Indexed internal transfer to wallet** — already materialized at `extract()` step 2; the guard
   makes recovery a no-op. (Source of truth #1.)
2. **msg.value** — already materialized at step 3 (outbound side). (Source of truth #2, native-out.)
3. **WETH `Withdrawal(src, wad)` receipt log**, router-agnostic, **exact** quantity, gated by:
   - `src != wallet` (an intermediary unwrapped WETH — not the wallet selling its own WETH), **and**
   - there is **no** wallet-originated WETH outbound of the same `wad` in the same tx (positively
     excludes the sell side), **and**
   - the leg set exhibits a *missing-inbound* shape (net outbound-only in native/WETH terms, or an
     LP-exit / native-output-swap classification signal).
   This single rule covers 0x, LiFi, Odos, Kyber, Universal Router, ParaSwap, 1inch, and generic
   multicall LP exits uniformly. It is the primary generalization.
4. **Calldata (last resort, per-router, exact-amount only)** — the existing ParaSwap/1inch `dstToken
   == native sentinel && dstReceiver == wallet && exact destAmount/exact-out` decoders are retained as
   a fallback **only** where the amount is provably exact. LP `unwrapWETH9(amountMinimum,…)` is a floor,
   not the settled amount, so calldata is **not** an acceptable LP-exit quantity source — LP exits must
   use evidence #3.

The `movePointLeft(18)`/native-decimal handling and `hasInboundNative` guard are preserved verbatim.

### D3 — Make the WETH `Withdrawal` evidence available: a classification-time native-settlement clarification trigger

Evidence #3/#1 is absent for 32/42 candidates because CONFIRMED swaps/LP-exits never fetch a full
receipt. Add a **narrowly-gated** classification-time trigger (not a replay repair): when a
`SWAP` / `LP_EXIT*` / `LP_FEE_CLAIM` / `UNWRAP` transaction has

- **no** inbound native leg, **and**
- **no** indexed internal transfer to the wallet, **and**
- a **native-output signal** (embedded WETH `withdraw` selector `0x2e1a7d4d` in calldata, or a
  native-sentinel destination in calldata, or interaction `to` is a known aggregator/NPM router),

then route it to the **existing full-receipt clarification** path so the receipt (and its WETH
`Withdrawal` logs) is persisted, after which D2 recovers deterministically. This reuses the existing
`ClarificationPolicyService` / full-receipt evidence mechanism — **no new RPC subsystem**. The trigger
is bounded (42 txs over two years for this wallet) and respects the free-tier RPC constraint
(one `eth_getTransactionReceipt` per candidate, cached in `clarificationEvidence`). GET/read endpoints
remain zero-RPC.

### D4 — Native-pool reconciliation gate (extend the guard family; WARN-then-HARD_FAIL)

Add `NativePoolReconciliationGate`, modeled on `CorridorBasisConservationGuard` /
`BybitEarnSubPoolConservationGuard` (end-of-replay sweep, shared dust epsilon, out-of-scope carve-out,
**WARN first**, one-line flip to `HARD_FAIL` after a clean rebuild). It asserts, per `NATIVE:<chain>`
pool:

- Invariant (a): `quantityShortfallAfter ≤ NATIVE_DUST`, `hasIncompleteHistoryAfter = false`, and
  `|quantityAfter − onChainNativeQuantity| ≤ NATIVE_DUST` where `onChainNativeQuantity` is read from
  the already-persisted `on_chain_balances` (`assetContract = "NATIVE:<chain>"`) — **zero RPC**.
- Invariant (b): no native `BRIDGE_OUT` / `CARRY_OUT` leg with null/zero basis while the source pool
  holds covered basis at that timestamp.

**What it blocks:** it does **not** hide the number behind the UI. WARN-phase: it emits a structured
diagnostic (chain, shortfall, on-chain vs tracked delta, first offending tx) and marks the pool
incomplete. HARD_FAIL-phase (post clean rebuild): it fails the replay/publication for that universe so
a regression cannot silently ship. This is the tripwire that proves the recovery worked
(`shortfall → 0`) and prevents future silent under-crediting. It complements — does not replace —
`PortfolioConservationGate` (NEC), which operates in USD at dashboard read time.

### D5 — Feature-flagged, per-chain rollout

`native-settlement-recovery.enabled` (global) and an optional per-chain allow-list gate D2/D3. Default
**off** until the reconciliation gate (D4) is green on a clean rebuild for the affected chains, then
**on**. The gate severity (`WARN → HARD_FAIL`) is a separate one-line flip after verification. This
bounds the blast radius of a change that touches native-leg extraction across all EVM chains.

---

## Consequences

- `NATIVE:BASE` (and, by the same generalized rule, native pools on every EVM chain) reconciles to the
  on-chain native balance within dust; `quantityShortfallAfter → ~0`, `avcoAfterUsd` becomes non-zero,
  `hasIncompleteHistoryAfter → false`.
- The LiFi `BRIDGE_OUT` native legs acquire real carry basis (no `valueUsd = undefined`); the Linea
  `BRIDGE_IN` is covered; the Linea `LENDING_DEPOSIT` avco is a real number — the **"NaN" disappears
  because the underlying basis is correct**, not because it is hidden (satisfies the user mandate).
- The three router-specific enrichers collapse to one router-agnostic, evidence-first seam; adding a
  new router requires **no** code change (evidence #3 is selector-agnostic).
- Requires a **full renormalization + replay reset** to realise the corrected pools
  (`./scripts/prod-reset-rebuild-backend.sh --skip-frontend`). D3 changes normalization evidence, so a
  `--backend-only` rebuild is insufficient.
- One additional `eth_getTransactionReceipt` per native-settlement-suspect candidate at normalization
  (cached), within free-tier RPC budgets; GET endpoints stay zero-RPC.

---

## Open questions

- **Q1 (gate severity):** `NativePoolReconciliationGate` `WARN → HARD_FAIL` staged rollout, flipped
  only after a clean full rebuild shows every in-scope `NATIVE:<chain>` pool reconciled within dust.
- **Q2 (enricher consolidation scope):** whether to physically delete `OneInchNativeSettlementSupport`
  / `ParaSwapNativeSettlementSupport` in this cycle or keep them as thin calldata-fallback delegates of
  `NativeSettlementRecovery` (D2 evidence #4) until their regression tests are re-homed.
- **Q3 (carve-outs):** confirm the declared unsupported-network/asset boundaries (e.g. SOL/TON) so the
  gate does not flag out-of-scope native pools.
