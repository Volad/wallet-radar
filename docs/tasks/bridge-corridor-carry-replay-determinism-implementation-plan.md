# Bridge/Corridor Linking + Basis Carry + Replay Determinism — Implementation Plan

> Status: **Proposed — Phase 2 (awaiting approval; no code yet).**
> Workflow: `financial-correctness-audit-workflow`. Phase 1 audits: residual-divergence `agent_id b95d1e90`, bridge/corridor/carry `agent_id cf03d80f`.
> Builds on RC-9 (deterministic on-chain↔CEX corridor basis continuity) + RC-7 (bridge CARRY_IN inheritance) + `CorridorBasisConservationGuard` (WARN) — see `docs/adr/ADR-029-...md`.

## 1. Scope

- **Universe** df5e69cc-…; wallets 0x1a87f12a…, 0xf03b52e8…, 0x68bc3b81…, 0xa0dd42c6… (+ BYBIT).
- **Symptoms:** (a) incremental refresh leaves conservationDeltaUsd = +$4,134 (rebuild = −$2); (b) AVCO jumps on some transactions; (c) many bridge OUT/IN not linked on the chart.
- **Out of scope:** out-of-scope families (TON/SOL/HYPEREVM) remain excluded; peg-neutral USDC bridged to out-of-scope venues (e.g. Hyperliquid) where the destination is genuinely not in the dataset = irreducible.

## 2. Root causes (from Phase 1, ranked)

| ID | Cluster | Stage | Genuine $ | Evidence | Confidence |
|----|---------|-------|-----------|----------|------------|
| **RC-12** | Replay accumulator books double-seeded on refresh (`borrow_liabilities`, `counterparty_basis_pools`, `lp_receipt_basis_pools`) | replay (orchestration) | **+$4,134** (4 borrows exactly 2×) | PRESENT (non-determinism) | HIGH |
| **C-1** | Bybit stream-collapse (`bybit-collapsed-v1` FUND→UTA) drops carried same-asset basis → AVCO jumps | linking + cost_basis | **≈ $2,269** (ETH $1,029 + MNT $948 + alts $292) | PRESENT_UNLINKED | HIGH |
| **BR-1** | Bridge OUT/IN unlinked where destination IS in dataset (real linking defect) + own-wallet leg mis-typed BRIDGE_OUT | linking / classification | ~$200 ETH risk; chart-clutter | PRESENT_UNLINKED | HIGH |
| **BR-2** | Bridge destination-discovery coverage gap (Hyperlane/rhino.fi/EtherFi/MetaMask/1inch + 33 no-`protocolName` legs) → null correlationId | linking / classification | peg-neutral, low $ | mixed | MED |
| **G-1** | Guard over-counts cross-asset bridge-swaps (USDE→USDT $862, CAKE $99) + OOS ($277) as orphaned carry | guard scope | $0 net (false WARN ~$962+$277) | PRESENT_UNUSABLE | HIGH |

`internal-tx:` (45) and `BYBIT-CORRIDOR:` (69) are 100% paired — **healthy, no change**.

## 3. Changes (ordered, upstream-first / determinism-first)

### WS-A — RC-12: replay accumulator idempotency (determinism) — TOP PRIORITY
- In the AVCO replay orchestration (`AvcoReplayService.replayConfirmed`), reconstruct **every persisted accumulator book from an EMPTY map each replay run** — exactly as `asset_ledger_points` already are. The fix belongs in `AvcoReplayService` (the seed), **not** in the trackers: drop the `loadAllForUniverse` seed (lines ~85/92/100) for `borrow_liabilities`, `counterparty_basis_pools`, `lp_receipt_basis_pools`; start each from empty. The trackers' accumulate semantics (`recordBorrow`/`lookupOrCreate.safeAdd`/`deposit`) stay correct once the seed is empty.
- **(R2, verified)** The replay always reprocesses the **full CONFIRMED set** (`ConfirmedReplayQueryService.loadOrderedConfirmed` has no since/window filter) and is the **sole writer** of all three books via end-of-run replace-only (`replaceUniverse*` = `deleteByUniverseId → saveAll`); `persistDirty(...)` is dead code (unused outside tests). Therefore rebuild-from-empty can only drop **stale** state, never legitimate cross-run state — this resolves the BA gating questions Q1/Q2/E1 (no accumulator-only/manual-override seed exists; all three books are pure derived projections). Add an explicit assertion to the ADR so a future incremental-window optimization cannot silently reintroduce double-seeding.
- **(architect)** Replace the "second full replay" runtime guard with a cheap **compute-vs-persisted drift canary** (compare freshly-computed book totals against what was just persisted; log/flag drift). Keep the unit **idempotency test** (rebuild==refresh) as the hard check.
- **Acceptance (R1/R3 pinned):** after full rebuild **then** incremental refresh (and refresh×N, N≥2): per-loan `qtyBorrowed` == on-chain and `qtyOpen` == on-chain `borrowed−repaid` (MANTLE 2,496; bybit-pledge 1,246 MNT; BASE 600; AVAX 390); aggregate `openBorrowLiabilityUsd` drops from the doubled **$8,754.55** to true (~half the +$4,374 phantom removed); `lp_receipt_basis_pools` & `counterparty_basis_pools` `qtyHeld` neither double **nor** lose state vs a single clean rebuild (**bit-identical**); `books(rebuild) == books(rebuild→refresh) == books(rebuild→refresh×2)` within ε; conservationDeltaUsd within |≤ $50|. Determinism canary severity = log/WARN (HARD_FAIL deferred, tracked follow-up).

### WS-B — C-1: Bybit stream-collapse must preserve carried basis (AVCO jumps)
- **(R1 — investigate first)** Before coding, pin the exact mechanism of the dropped seq816 carry (−0.148 ETH / −$391.85, no consuming CARRY_IN): (a) asymmetric mirror exclusion leaving a one-sided leg (`enforceCollapsedUtFundPairSymmetry` only restores FUND-outbound/UTA-inbound), (b) surviving-leg asset-key mismatch, or (c) credit-before-debit ordering. State the finding in the plan; the remedy differs per cause.
- The fix must make both collapsed legs ride the **same `corr-family` queue** (`continuityCandidate=true`, outbound-first ordering) so the existing **inherit-once** `PendingTransferStore` machinery carries the basis — **do not inject a synthetic credit** (that risks double-credit). Pin the carry to the **replay layer** (architect).
- **Acceptance (R3/R4 anchored):** seq816 ETH `CARRY_OUT` is consumed by a matching `CARRY_IN` (zero orphan); **quantity-conservation** at FUND→UTA collapse points (carried-in qty == carried-out qty; no umbrella/inventory inflation, no double credit); no AVCO discontinuity at collapse seqs beyond pricing dust ε; guard total falls **$3,515 → ≈ $1,246** after WS-B (then **→ ≈ 0** after WS-E); FAMILY:ETH/MNT carry net conserved.

### WS-C — BR-1: real bridge-linking defects (chart symptom, high-certainty subset)
- Fix the proven both-sides-in-dataset unlinked bridge pairs (anchor: `BASE ETH 0.0111 → ZKSYNC ETH`): ensure the destination is discovered and both legs share the corridor key + `counterpartyType=BRIDGE` so the chart connects them.
- **(R5)** Correct the own-wallet transfer mis-typed as `BRIDGE_OUT` via a **reusable rule** — both endpoints own/member wallets ⇒ `INTERNAL_TRANSFER`/self — with `0xe61256…` used **only as a test fixture/evidence anchor**, never a hardcoded exclusion.
- **Acceptance (R5 counts):** state the starting unlinked-leg count and the specific in-dataset pairs that must link; the named pair links; the mis-typed leg reclassifies; negative test: a self-transfer is not falsely linked as a bridge pair.

### WS-D — BR-2: bridge destination-discovery coverage (broaden, bounded, zero-RPC)
- Extend destination-discovery/keying beyond LiFi (ADR-027) + Across + Relay to long-tail families with **deterministic, evidence-only (zero-RPC, from raw/registry)** detection (Hyperlane router pattern, rhino.fi, EtherFi, MetaMask Bridge, 1inch Fusion) **only where the counterpart leg exists in the dataset**; for the 33 no-`protocolName` legs derive a reusable bridge-counterparty rule. **No dataset-specific hardcoded hashes** in production code (fixtures only).
- Mark as **irreducible/expected** the peg-neutral legs bridged to OOS venues with no in-dataset destination — and make "peg-neutral" a **checked assumption** (asset is a stablecoin at/near peg); a non-stable asset bridged to an OOS venue must **not** be silently accepted as irreducible.
- **Acceptance (R5 counts):** each new detector has a positive **and** anti-link (negative) test + value-conservation; document how many of the 33 no-`protocolName` legs get linked vs documented irreducible, and the expected **final irreducible count**.

### WS-E — G-1: guard scope tuning (reduce false WARN, conservation-neutral)
- **(R4 — corridor-level, not symbol-level)** Every guarded queue key is already asset-specific, so "same-asset" alone is a no-op. The guard must suppress a leftover CARRY_OUT **only when the corridor's matched destination leg is a different asset/family** (legitimate swap, e.g. USDE→USDT, CAKE) — consulting the counterpart leg, not the orphan's own symbol — and must **keep flagging** a same-asset destination whose credit took spot/$0 (genuine orphan).
- Consider the cleaner upstream alternative (architect/auditor): model a **cross-asset corridor source leg as a DISPOSAL** so it never releases a covered same-asset carry in the first place (removes the false orphan at source rather than filtering at the guard). Decide between guard-filter vs source-disposal during WS-E design.
- **Acceptance:** guard residual drops by ~$962 (cross-asset) + $277 (OOS) → genuine in-scope only; **negative test**: a genuine same-asset orphan (ETH→ETH credit took spot) still WARNs after the scope change. Re-evaluate WARN→HARD_FAIL promotion (ADR-029 Q1) after WS-A/WS-B land clean (tracked follow-up).

## 4. Docs
- **New ADR-030** (architect): RC-12 replay-accumulator idempotency — all persisted accumulator books rebuilt from empty each replay; full-CONFIRMED-reprocess + sole-writer (replace-only) assertion; drift canary. (Distinct ADR, not an ADR-029 amendment.)
- `docs/pipeline/cost-basis/03-basis-pools-and-carry.md`: Bybit stream-collapse basis-carry rule (C-1); guard corridor-level cross-asset scope (G-1).
- `docs/pipeline/normalization/rules/protocols/` + linking docs: bridge destination-discovery coverage + counterparty rule (BR-2); own-wallet vs BRIDGE_OUT typing rule (BR-1).

## 5. Acceptance (rerun, auditor sign-off)
1. `./gradlew :backend:test` green incl. new determinism + carry tests.
2. Full rebuild → conservationDeltaUsd ≈ −$2 (unchanged baseline).
3. **Incremental refresh** → conservationDeltaUsd within |≤ $50|; each open loan `qtyBorrowed` == on-chain (not 2×); MANTLE ETH avco holds $2,937; 0 `BYBIT-CORRIDOR:` breaches.
4. Guard in-scope residual ≈ 0 for `bybit-collapsed-v1` ETH/MNT/alts (C-1); guard total no longer includes cross-asset/OOS false counts (G-1).
5. Bridge chart: proven in-dataset pairs linked; mis-typed leg fixed; remaining unlinked legs are documented irreducible OOS tail.
6. financial-logic-auditor sign-off; no RC-1/2/4/5/6/7/8/9 + spoof regression.

## 5a. Edge cases (from BA review)
- **E1 (resolved):** no borrow/pool/LP state is seeded only in an accumulator book — all three are pure projections of the CONFIRMED set (verified by auditor + architect), so rebuild-from-empty drops nothing legitimate.
- **E2:** a fully-repaid loan (`qtyOpen == 0`) stays 0 across refresh×N (no negative, no re-double).
- **E3:** partial-bucket FUND→UTA collapse; a single collapse event spanning multiple assets (each carries basis independently); FUND→UTA→FUND round-trip.
- **E4:** cross-asset corridor (USDE→USDT, CAKE) excluded from the guard must still **conserve basis on the ledger** (realized/avco correct) or be explicitly accepted as ADR-029 Q2 deferred scope — enforced by a test, not just suppressed.
- **E5:** multi-hop bridges (A→B→C) — state in/out of scope; document intermediate-leg behavior.
- **E6:** one-sided bridge leg whose destination is in-scope but the counterpart tx is genuinely not ingested (2-year cutoff) = **expected-pending**, distinct from OOS-irreducible.

## 6. Risks
- WS-A: rebuild already starts empty, so the change is a **no-op on the full-rebuild path** (verified); the only behavioral change is on incremental refresh (removes doubling). Note `deleteAll` vs `deleteByUniverseId` asymmetry; a **post-deploy full rebuild** is required. Validated by the rebuild==refresh==refresh×2 determinism test.
- WS-B intra-Bybit carry must not double-credit or inflate inventory; route both legs onto one inherit-once queue (no synthetic credit) + qty-conservation check.
- WS-D bridge-family detection must be zero-RPC, evidence-only, with anti-link negative cases; no hardcoded hashes.
- WS-E guard scope change must key on corridor-level cross-asset (not the orphan's own symbol) and must keep a negative test proving a genuine same-asset orphan still WARNs.

## 7. Suggested execution order
WS-A (determinism, closes the $4,134) → WS-B (C-1 AVCO jumps, $2,269) → WS-E (guard scope, de-noise) → WS-C (BR-1 real bridge defects) → WS-D (BR-2 coverage). Docs alongside. Rebuild + incremental-refresh (×2) determinism verify + auditor sign-off.

## 8. Phase 3 review outcome
All three reviewers **APPROVE with required revisions** (now folded in): financial-logic-auditor (R1–R5: WS-B root-cause-first, WS-A full-CONFIRMED/sole-writer assertion, WS-B qty-conservation, G-1 corridor-level + negative test, BR-1 reusable rule), business-analyst (measurable AC: qtyOpen/openBorrowLiabilityUsd, lp two-sided DoD, refresh×N chain, AVCO/bridge numeric anchors; edge cases E1–E6), system-architect (ADR-030 not amendment, drift canary, replay-layer carry, zero-RPC WS-D, §6 risk corrections). Ready for implementation on user approval.
