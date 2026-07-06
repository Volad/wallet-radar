# ADR-042 — Honor `flow.accountRef` in Replay Position Resolution

**Date**: 2026-07-01
**Status**: Accepted
**Related**: ADR-017 (Timeline AVCO authority), ADR-019 (corridor carry policy), ADR-029 (deterministic CEX corridor basis continuity), ADR-041 (Bybit Earn corridor pairing and `:FUND` carry symmetry)
**Implements**: closeout of the last two Bybit ETH-family phantom residuals after replay #9 (ETH umbrella `BYBIT:33625378` = `0.6929745940390085` → 0; CMETH `BYBIT:33625378:FUND` = `0.099082260317885` → 0)

---

## Context

`BybitCanonicalTransactionBuilder` collapses a sub-account `walletAddress` to the UID umbrella
(e.g. `BYBIT:33625378`) so that ADR-017 family rollup and RC-2 (`ADR-041`) net a `FUND ↔ UTA`
round-trip on a single position key. At the same time `finalizeBybitFlows` still stamps the
originating sub-account onto **`flow.accountRef`** (e.g. `BYBIT:33625378:FUND`).

The replay drain / disposal resolvers historically keyed on the collapsed `walletAddress` plus
coverage / max-quantity heuristics and **discarded `accountRef`**. When a leg's principal genuinely
sat on a named sub-account but the position key was collapsed to the umbrella, the drain was posted
to the umbrella and the sub-account kept a phantom residual. Two residuals survived replay #9, both
proven by the financial-logic-auditor against raw evidence:

- **Staking `:FUND` drain.** The 2025-04-18 `STAKING_DEPOSIT` (ETH `−0.6929746`,
  `flow.accountRef = BYBIT:33625378:FUND`) posted its drain to the umbrella (dust `−6E-9`) and never
  reduced `:FUND`, leaving a `0.6929` phantom on `:FUND` from April. The existing Fix A.1 guard in
  `LiquidStakingReplayHandler` keys on the **transaction** `walletAddress` suffix ending `:FUND`, but
  this transaction's `walletAddress` is the plain umbrella, so that guard never fires. A later
  2025-09-12 corridor withdrawal then relocated the phantom onto the umbrella. Draining `:FUND`
  correctly at 04-18 cascades: `:FUND = 0` at 09-12 and the corridor waterfalls the full amount off
  the umbrella, so no 09-12-specific change is required.
- **cmETH multi-fill convert.** The 2025-09-10 convert had 38 raw fills (`0.86209` = the whole `:FUND`
  balance), but replay posted only 30 disposals against `:FUND`; the other 8 fills (`0.099082`) went to
  the umbrella once a max-quantity tiebreak flipped mid-convert after `:FUND` dropped below the
  umbrella lot, stranding a `0.099082` residual on `:FUND`.

The upstream classification and normalization are correct; the defect is purely in **replay position
resolution discarding the explicit `accountRef`**.

---

## Invariant

> When a replay leg carries an explicit `flow.accountRef` naming a Bybit sub-account
> (`…:FUND` / `…:UTA` / `…:EARN`) that **(a)** already EXISTS as a position and **(b)** holds enough
> inventory to COVER the leg, the drain / disposal **target** is that named sub-position — not the
> umbrella. The umbrella-collapsed position **key is unchanged**; only the target is redirected. The
> decision is a pure function of `accountRef` + the named sub-position's inventory, **never** of
> counterparty, transaction type, or `correlationId`. When the named sub-position does not exist or
> cannot cover the leg, resolution falls back to prior (umbrella) behaviour.

---

## Decision

### D1 — One shared, coverage-and-existence-gated helper

`AccountRefPositionResolver.resolveInventoryBearingAccountRefKey(defaultKey, accountRef, positions, coverQuantity)`
is the single lever. It:

- recognises a sub-account only when `accountRef` ends in `:FUND` / `:UTA` / `:EARN` (so no non-Bybit
  or umbrella-level `accountRef` ever triggers a redirect);
- builds the candidate sub-key from the collapsed `defaultKey` with `walletAddress = accountRef`;
- **peeks** the position store (never mutates it), so no phantom sub-position is materialised;
- returns the sub-key only when the sub-position exists and covers `coverQuantity` at the shared
  dust-safe `CARRY_SOURCE_COVERAGE_RATIO = 0.999`; otherwise returns `defaultKey` unchanged.

It reuses the same coverage semantics as `TransferReplayHandler` / `BybitCarrySourceResolver` rather
than duplicating logic, and is idempotent (a pure function of its inputs).

### D2 — Wire the helper into three replay seams

1. **`LiquidStakingReplayHandler.resolveOutboundDrainPosition`** — runs the `accountRef` redirect
   before the transaction-`walletAddress` Fix A.1 guard, so the collapsed 2025-04-18 staking deposit
   drains the `:FUND` ETH principal (basis carries into the METH receipt) instead of the empty umbrella.
2. **`TransferReplayHandler.resolveCarrySourcePosition`** — runs the `accountRef` redirect first for
   corridor carry/drain source selection. Coverage gating preserves RC-2: a plain collapsed
   `FUND ↔ UTA` leg whose inventory sits on the umbrella finds no covering `:FUND` sub-position and
   keeps netting on the umbrella.
3. **`ReplayAssetSupport.resolveSellAssetKey`** — runs the `accountRef` redirect before the
   default-key coverage check and the max-quantity scan, so every fill of a multi-fill convert stays
   sticky to the named `:FUND` pool and the tiebreak can no longer flip late fills onto a sibling
   umbrella lot.

### D3 — Do not touch the conservation guard or re-key `replayPositionWalletAddress`

`BybitEarnSubPoolConservationGuard` measures combined totals and stays correct. The fix redirects only
the drain/disposal **target**; it does **not** globally re-key `replayPositionWalletAddress` (that
would regress RC-2), and it introduces **no** repair/sweep/backfill job that mutates already-normalized
canonical rows.

---

## Consequences

- The last two ETH-family residuals clear: ETH umbrella `BYBIT:33625378` → 0 and
  CMETH `BYBIT:33625378:FUND` → 0, matching live Bybit ETH = 0.
- RC-9 (AMANWETH 3.06, self-funded corridor-out on `:FUND`) is **structurally immune**: it already
  drains `:FUND` and emits a single `CARRY_OUT`; because the rule keys on `accountRef` + inventory
  (never on counterparty/type/`correlationId`, which are identical between RC-9 and the phantom), the
  redirect returns the same `:FUND` position and does not fan out onto a sibling umbrella lot.
- Controls remain intact: XPL 3.70, MNT, USDT, LTC, XRP, LDO, ONDO, and the preserved-control RC-9
  AMANWETH 3.06.
- Requires a full replay migration to realise the corrected pools (run separately from this change).

---

## Open questions

- **Q1:** if a future non-Bybit venue reuses `flow.accountRef` with a different sub-account grammar,
  the suffix allow-list (`:FUND`/`:UTA`/`:EARN`) must be extended deliberately rather than generalised
  to any suffix, to keep the redirect bounded.
