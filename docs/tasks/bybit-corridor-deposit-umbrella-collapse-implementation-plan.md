# Bybit corridor DEPOSIT → umbrella collapse (BLOCKER-4)

**Slug:** `bybit-corridor-deposit-umbrella-collapse`
**Date:** 2026-07-13
**Stage:** `replay` (position-key resolution)
**Related:** ADR-054 Phase 6b follow-up; `results/blockers.md` BLOCKER-4

---

## 1. Scope

- **Symptom:** 7 cmETH ledger points on `BYBIT:33625378` flagged with a constant
  `quantityShortfallAfter = 0.09992031`; the 2025-04-17 `convert` SWAP sold 0.66931648 CMETH
  against an umbrella holding only 0.56939617.
- **Assets/wallets:** `BYBIT:33625378` — CMETH (0.1 stranded) and MNT (16.04 stranded) on `:FUND`.
  Systemic to any Bybit corridor DEPOSIT that lands on a sub-account and is later consumed by
  umbrella-level spot trading.
- **Constraints:** backend only; Bybit venue; no frontend; must not regress earn-principal
  (`bybit-earn-principal-v1` / `bybit-earn-onchain-fund-v1`) `:FUND` cases or corridor WITHDRAWALS.

## 2. Root cause

`AccountingAssetIdentitySupport.replayPositionWalletAddress` (lines ~152–157, `isBybitCorridorFromFund`
lines ~240–252) preserves the full `:FUND` wallet for **all** `BYBIT-CORRIDOR` legs regardless of
direction.

- **Outbound corridor (withdrawal, `quantityDelta < 0`):** correct — the `CARRY_OUT` must drain the
  `:FUND`-funded pool.
- **Inbound corridor (deposit, `quantityDelta > 0`):** WRONG — the `CARRY_IN` credits `:FUND`, but
  all other Bybit spot activity (internal `bybit-collapsed-v1` transfers, converts, EXECUTION_SPOT
  sells) operates on the umbrella-collapsed key. The deposited inventory is stranded and
  unreachable, producing a phantom disposal shortfall.

Evidence (DB reconstruction, before convert `replaySequence < 1558`):
`umbrella 0.56939617 + :FUND 0.10000000 = 0.66939617` ≈ sold 0.66931648.

## 3. Ordered changes (upstream first)

1. **`AccountingAssetIdentitySupport`** (`backend/core/.../costbasis/support/`)
   - Rename `isBybitCorridorFromFund(NormalizedTransaction)` →
     `isBybitCorridorOutboundDrainFromFund(NormalizedTransaction, NormalizedTransaction.Flow)`
     (direction encoded in the name; architect req).
   - **Discriminator (data-verified):** Bybit-side corridor legs are `INTERNAL_TRANSFER` with role
     `TRANSFER`, a single flow, and **no FEE legs** (51 outbound NEG + 18 inbound POS = 69 total).
     The architect's `type == EXTERNAL_TRANSFER_OUT` suggestion does **not** apply (those are
     INTERNAL_TRANSFER). Use the flow sign, fee-guarded:
     `flow != null && flow.getRole() != FEE && flow.getQuantityDelta() != null &&
     flow.getQuantityDelta().signum() < 0` → preserve `:FUND` (outbound drain only).
   - Update the caller in `replayPositionWalletAddress` to pass `flow`. Inbound corridor deposits
     (`quantityDelta > 0`) then fall through to `positionWalletAddress(transaction)`, which collapses
     `:FUND`/`:UTA` → umbrella root. (`:UTA` inbound already collapses today; this is a no-op for
     `:UTA` — BA E2.)
   - Keep the `isEarnPrincipalPaired` branch first (unchanged) so `bybit-earn-principal-v1` /
     `bybit-earn-onchain-fund-v1` `:FUND` handling is untouched (E1).
   - Update the class Javadoc to state the direction rule.

2. **Layer decision (architect):** keep the collapse in `replayPositionWalletAddress` (materialization
   identity), NOT upstream normalization — the raw `:FUND` `walletAddress` / `flow.accountRef` are
   audit evidence that ADR-042 disposal-redirect and the outbound carry drain depend on. Leave the 69
   sub-account-keyed corridor rows untouched.

3. **Conservation guards** — `CorridorBasisConservationGuard` /
   `BybitEarnSubPoolConservationGuard` match on the corridor **queue key** (correlationId + asset
   identity), NOT the position wallet, so routing the inbound credit to the umbrella keeps the same
   `CARRY_IN`→queue consumption; conservation is invariant to the position-key collapse. Assert zero
   new violations (do not merely "verify").

## 4. Docs

- Update the class-level Javadoc on `replayPositionWalletAddress` / the renamed predicate to state
  the direction rule (outbound preserves `:FUND`, inbound collapses to umbrella).
- **ADR-042** (sub-account routing): add a short subsection recording the direction-aware corridor
  rule and noting inbound deposits no longer create a `:FUND` position for the accountRef redirect to
  find (consistent with the "exists + covers" gate). **ADR-054**: one-line cross-reference (BLOCKER-4
  is its Phase 6b follow-up). No new ADR; ADR-017 unchanged.

## 5. Acceptance

Re-audit after `./scripts/prod-reset-rebuild-backend.sh --skip-frontend` (pricing cache clear NOT
required — replay position-keying only; do not pass `--clear-pricing-cache` unless a pricing
dependency surfaces — BA):

- **Target + broadened assets** (auditor: same bug also flags ETH, ARB, USDC): `asset_ledger_points`
  with `hasUnresolvedFlagsAfter=true` on `BYBIT:33625378` → **0** for **CMETH, ETH, ARB, USDC**; and
  the total count of Bybit flagged points does not increase (should drop by the CMETH 7 + the ETH/ARB/
  USDC/MNT strand points).
- **No nonzero `quantityShortfallAfter`** on any of those assets; venue-wide regression sweep: no
  **new** nonzero `quantityShortfallAfter` anywhere.
- **Quantity-conservation invariant:** for CMETH and MNT the sum of `quantityDelta` across all Bybit
  keys (`umbrella + :FUND + :UTA + :EARN`) is unchanged pre/post-fix (inventory relocated, not
  created/destroyed).
- **Anchor points:** the `replaySequence 1453` corridor deposit resolves to the **umbrella** key (not
  `:FUND`); the `replaySequence 1558` convert disposes 0.66931648 with full basis coverage, zero
  shortfall.
- **Reconciled totals unchanged:** LDO/ONDO/LTC (and other `:FUND`/earn-routed reconciled assets)
  keep combined per-asset balances and reconciliation status.
- **AVCO/P&L delta check:** quantify umbrella AVCO + realized-P&L deltas per affected asset (esp. ETH,
  whose corridor inbound/outbound span weeks with spot disposals between) and confirm they are the
  expected unified-account commingling effect, not silent regressions.
- **MNT framing (auditor):** MNT `:FUND` 16.04 is the only *permanent* residual (net of a
  `bybit-earn-principal-v1` drain); the MNT *umbrella* shortfall from `seq 138` is a **separate**
  pre-existing issue this fix will NOT clear — do not conflate or gate on it.
- No regression: existing Bybit replay tests green (corridor continuity, earn-principal, collapsed
  self-transfer, rekeyed FUND drain, cross-sub-account staking, RC-9 self-funded corridor-out). Full
  `:backend:test` green.

## 6. Risks

- **Regressing earn-principal `:FUND` cases** — mitigated: gated by `isEarnPrincipalPaired`, which
  runs first (unchanged).
- **Outbound corridor draining a prior inbound-deposited `:FUND` pool** — inbound now on the umbrella;
  a later outbound drain resolves via `BybitCarrySourceResolver` inventory waterfall (`:FUND` primary
  under-covers → umbrella sibling), confirmed by the 2025-04-11 corridor-out at `replaySequence 1448`
  already recording on the umbrella. Regression test required.
- **AVCO commingling** — collapsing inbound corridor basis into the umbrella blends it with spot AVCO;
  a spot disposal between a corridor inbound and its matched outbound now re-prices at blended AVCO
  (more correct under unified-account semantics, but a real accounting change — quantify per §5).
- **Basis conservation** — holds because guards key on queue, not position wallet (see §3.3).
- **Dzengi (BA E6):** fix is BYBIT-corridor-prefix scoped only; Dzengi explicitly out of scope. Log a
  follow-up to check whether Dzengi exhibits the same inbound-corridor `:FUND` strand.
- **Scope guardrail:** code-path fix validated by full rerun/re-audit only — do NOT add a startup
  sweep or mutate already-normalized corridor rows.

## 7. Test plan

- **Identity unit tests** (`AccountingAssetIdentitySupportTest`, alongside existing
  `_forCollapsedFundInbound`/`_forCollapsedFundOutbound`/`_keepsFundSuffix_forEarnOnchainFundRepair`):
  corridor-corr inbound (`+qty`) on `:FUND` → umbrella; corridor-corr outbound (`-qty`) on `:FUND` →
  `:FUND` preserved; `:UTA` inbound → umbrella; **fee-flow-on-deposit guard** (a `FEE`-role or
  zero-delta flow must not preserve `:FUND` — E5). Keep the earn-onchain-fund `:FUND` test green.
- **Replay split-inventory convert:** 0.5694 umbrella + 0.1 corridor-deposit → convert fully covered,
  zero shortfall.
- **E1 corridor-inbound → earn-principal subscribe** (MNT `seq 6692→7070` pattern): deposit lands on
  umbrella, flexible-earn subscribe drains umbrella, zero shortfall, no double-count.
- **E3 deposit → later corridor withdrawal same asset:** inbound (umbrella) then outbound (`:FUND`)
  drains via umbrella waterfall, zero shortfall, correct basis release.
- **E4 multiple partial converts** across the unified umbrella pool: each covered, no residual/negative
  dust after the last.
