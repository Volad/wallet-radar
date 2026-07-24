# Cluster-carry for intra-cluster cross-canonical conversions — implementation plan

- **Status:** REVISED (Phase 3 resolved) — ready for Phase 4
- **Owner audit:** `results/cross-canonical-conversion-phantom-pnl-audit.md`
- **Reviews:** financial-logic-auditor `5c7bbb64`, business-analyst `89a6bfdd`, system-architect `4f903244` — all CHANGES-REQUESTED; resolutions folded in below (§7).
- **User decision:** `convert_semantics = carry` (confirmed) — internal ETH↔staked-ETH (and SOL/AVAX) conversions must **carry basis, PnL = 0**; realize **only** on exit to a non-cluster asset (USDT/fiat/BTC/other).

## 1. Symptom

Effective cost for ETH explodes to ~$51k/ETH on early-history sliver windows; ETH-family cumulative
realized carries phantom P&L. Example:

```
tx BYBIT-33625378:FUNDING_HISTORY:9bc8c8a2…|…d552dbd4  (2025-03-12)
  ETH  DISPOSE  −0.709   basis −$1889.54  mktPnl −$555.93   (realized at market $1881)
  METH ACQUIRE  +0.66865 basis +$1333.20  mktPnl  0.00      (fresh market $1993.86)
```

Disposed ETH basis ($1889.54) is destroyed; acquired mETH re-based at market ($1333.20); the $556
difference is booked as realized loss although no sale to USD occurred.

## 2. Root cause (earliest failed stage: cost_basis / replay — by design, not a stage bug)

Per ADR-054 §2, replay treats **any** movement between distinct canonical assets as disposal +
market acquisition (realize). Cluster membership is computed but explicitly "normalization-only";
replay carry-vs-realize uses per-asset `canonicalTokenIdentity`. `LiquidStakingReplayHandler` already
implements the correct carry (REALLOCATE_OUT/IN, both-lane proportional basis, PnL=0) but only for
**same-`FAMILY:*`** legs sharing `canonicalTokenIdentity`; cross-family same-cluster legs (ETH↔mETH)
fall through to the generic realize path. This contradicts ADR-082/FB-01 (realize) and
`convert_semantics=carry` (carry); per user decision we adopt cluster-carry and supersede
ADR-054 §2 + ADR-082 **for intra-cluster conversions only**.

## 3. Target model — cluster-carry

For any principal conversion whose **every** principal leg resolves to the **same non-null staking
cluster** (contract-first, see §4.1) and which touches **no** non-cluster principal:

- Carry the full disposed basis (Market **and** Net) onto the acquired leg via REALLOCATE. **Realized
  PnL = 0** both lanes. Non-1:1 quantity conversion re-averages the total carried basis onto the
  destination quantity.
- Realize **only** when a non-cluster principal leg is present (true sale/exit).

**Cluster membership (authoritative for this fix):**
- **ETH cluster:** ETH, WETH, vbETH, mETH/METH, cmETH/CMETH, weETH/WEETH, wstETH/WSTETH, stETH,
  rETH/RETH, cbETH, yvVBETH, **PT-cmETH**, **PT-ETH**.
- **SOL cluster:** SOL, mSOL, vSOL, bSOL, bbSOL, jitoSOL.
- **AVAX cluster:** AVAX, WAVAX, sAVAX, **aAvaSAVAX (AAVASAVAX)**.
- **Excluded (fail-safe → realize):** GMX GM ETH/USD (`0x70d9…`, mixed ETH/USD, raw-contract identity,
  no clean cluster identity) and any token whose cluster resolves to null / a different cluster.

Conversion transaction types in scope: `STAKING_DEPOSIT`, `STAKING_WITHDRAW`, `VAULT_DEPOSIT`,
`VAULT_WITHDRAW`, and `SWAP` (intra-cluster on-chain, e.g. cmETH↔ETH, cmETH↔PT-cmETH, SOL↔mSOL).

## 4. Ordered changes (upstream first)

### 4.1 Cluster identity resolution — contract-first (architect Q2/Q4, auditor R1)
- Add `clusterForFamilyIdentity(String familyIdentity)` backed by ONE explicit `FAMILY→CLUSTER` table
  in `AccountingAssetClassificationSupport`. Resolve a flow's cluster via
  `AccountingAssetFamilySupport.continuityFamilyIdentity(symbol, contract)` → family → cluster
  (contract-first, LP-receipt-aware). Do **not** use the symbol-only `normalizationClusterForSymbol`
  for carry decisions.
- Extend membership so ALL in-history members resolve (fixes the auditor's 10 missed conversions):
  add `MSOL, VSOL, BSOL, JITOSOL` (SOL), `PT-CMETH, PT-ETH` (ETH, contract-keyed), `AAVASAVAX`
  (AVAX). Keep `normalizationClusterForSymbol` in sync (or refactor it to delegate to the new
  family→cluster table) so normalization and replay agree.
- Membership stays in the C1/C2 identity registry / a co-located `asset-clusters.json` FAMILY→CLUSTER
  table — **never** in `break-even-attribution.json` (read-model only). For 3 clusters, a reviewable
  table-driven map in code is acceptable; JSON externalization deferred until a 4th cluster.

### 4.2 Replay-facing predicate (`AccountingAssetClassificationSupport`)
- Add `isIntraClusterConversion(NormalizedTransaction)`: principals contain both an outbound
  (negative) and inbound (positive) leg; **every** principal leg resolves (contract-first) to the
  **same** non-null cluster; **no** principal leg is non-cluster. Fiat/stable/BTC/other or a
  second cluster ⇒ false (realize). Single-pass, type-gated for hot-path SWAP cost.

### 4.3 Extract carry engine + single `CLUSTER_CARRY` route (architect Q2)
- Extract the REALLOCATE engine (`applySelected` → `resolveOutboundDrainPosition` + `allocateInbound`
  both-lane proportional split + final-covered-principal remainder exactness + `:FUND`/accountRef
  drain redirects) into a shared collaborator (`ClusterCarryReplaySupport` / reuse existing).
- Generalize flow selection to group principals by **cluster identity** (via 4.1); the current
  same-family staking carry becomes the degenerate single-cluster case. Rename the route to
  `CLUSTER_CARRY` (superset), keeping the route registry's non-overlap invariant provable. Extend the
  selector to also accept `SWAP`/`VAULT_*` with SELL/BUY principals; verify SWAP SELL legs drain the
  correct pool (`assetKey` vs `resolveSellAssetKey`, auditor R5a).

### 4.4 Gate the generic realize arm OFF for carried txs (architect Q3 Hazard 1 — REQUIRED)
- In `ReplayDispatcher.replayGenericFlowsSkipping`, gate:
  `isCrossCanonicalStaking = (type checks) && hasCrossCanonicalIdentityPrincipalPair(tx) && !isIntraClusterConversion(tx)`.
  Routing alone does **not** stop `swapNetRef` re-base (ADR-082), the D1 `applyUnknownTransfer`
  fail-closed, or `stampCrossCanonicalRedemptionProceedsFromInbound` (which iterates all flows,
  ignoring the skip set). This guard makes them dormant for carried flows. All remain intact for
  cluster↔non-cluster realizing swaps.

### 4.5 Mirror / continuity dedup guard (architect Q3 Hazard 2)
- Carried legs bypass `markContinuityFlowSeen`. Ensure cluster-carry selection does not swallow legs
  owned by the continuity/custody-round-trip path, and add a mirror-document regression test so a
  Bybit mirror cannot double-carry.

### 4.6 Read-model (defense-in-depth; expect inert)
- With intra-cluster realized = 0, the AC-8 child-offset unfloor, T3 over-sliver guard, and ADR-082
  NET re-base become inert for these flows. Keep as defense; verify 0 firings on affected windows.
- **In-cluster reward inflows / unstake surplus rule (BA open Q):** discrete reward inflows
  (extra mETH/ETH not paired with an outbound) enter the cluster at **$0 basis = income** (lowers
  effective cost via the NET offset), consistent with "break-even to recover **net** investment".
  Rebase-token accrual stays out of scope. State this rule in docs.

### 4.7 ADR + docs
- New **ADR-083 "Cluster-carry for intra-cluster cross-canonical conversions"**: supersedes
  ADR-054 §2 and ADR-082/FB-01 **for intra-cluster conversions only**; realize semantics remain for
  cluster↔non-cluster.
- Amend **ADR-040** (carry both lanes, PnL=0; `Net ≤ Market` may equalize on carried lots — expected,
  the carry path writes basis via `restoreToPosition` bypassing the `min(market)` cap by design).
- Amend **ADR-062** (cluster→family realized attribution receives no intra-cluster realized P&L;
  `foldHeldExposure` held-basis fold unaffected).
- Update `docs/pipeline/cost-basis/02-avco-rules.md`, `03-basis-pools-and-carry.md`,
  `docs/examples/avco-replay-examples.md`, `docs/frontend/move-basis.md` (single "AVCO / Effective
  cost" metric already shipped; add tooltip copy: effective cost = break-even to recover **all**
  ETH-family investment incl. gas/lending interest/protocol fees/rewards/LP income, and internal
  staking conversions do **not** realize PnL — realization only on exit to a non-cluster asset).

## 5. Acceptance criteria (renamed CC-AC-* to avoid ADR-062 AC-# collision)

- **CC-AC-1** 2025-03-12 tx: ETH leg = REALLOCATE_OUT, PnL=0 both lanes; METH leg = REALLOCATE_IN
  inheriting $1889.54 total basis (avco ≈ $2826/mETH); Net carried identically.
- **CC-AC-2** Every predicate-caught intra-cluster conversion (re-derive exact universe from the
  predicate, not the family enumeration; includes the C1↔C1 ETH↔WETH/vbETH moves and the PT-cmETH
  round-trip) has `realisedPnlDeltaUsd` = `netRealisedPnlDeltaUsd` = 0 **for the covered portion**.
  (Uncovered outbound falls back to market-priced inbound via `applyFallbackSettlementFlow` — scope
  accordingly.)
- **CC-AC-3** Terminal all-time cluster realized (recompute post-fix; expected, phantom removed):
  ETH ≈ **+547.52 Mkt / +564.18 NET** (was +774.21; the all-time phantom is a net GAIN so terminal
  DROPS), SOL ≈ **+87.85 / +101.80**, AVAX ≈ **0.00 / 0.01**. Also assert the as-of-2025-05-29 ETH
  delta ≈ **+759 Mkt / +702 NET** (phantom loss removed). The +$2037.84 FB-01 `cmETH→PT-cmETH`
  Market gain must become 0.
- **CC-AC-4** Per-conversion basis conservation: Σ(carry-out) == Σ(carry-in) both lanes (±dust);
  global conservation gate clean; no invariant gate rejects a carried lot.
- **CC-AC-5** T3 over-sliver guard fires **0×** and AC-10 dust guard fires **0×** on the 2025-H1
  windows; early-history effective cost is continuous and no point exceeds `1.1× terminal AVCO`.
- **CC-AC-6** True sales to USDT/fiat/BTC: realized PnL **unchanged**; whole-portfolio realized moves
  by exactly the removed phantom total (Market/NET).
- **CC-AC-7** Headline value: record pre-fix terminal ETH effective cost (user cites ~$3028) and avg
  cost; post-fix values land within a stated tolerance with the delta explained (avg cost WILL shift
  by ≈ NET all-time phantom / held ETH-eq ≈ tens of $/ETH — surface to user, no silent change).
- **CC-AC-8** Scenario matrix: (a) round-trip ETH→mETH→ETH and cmETH→PT-cmETH→cmETH net to 0
  realized, no AVCO drift; (b) multi-leg `WSTETH+ETH→WEETH`, `aSAVAX+AVAX→AVAX` pool+allocate,
  Σ conserved, realized 0; (c) mixed cluster+non-cluster (`ETH→mETH + ETH→USDT`) realizes **fully**;
  (d) cross-cluster (ETH→SOL) realizes; (e) unknown/unmapped LST realizes (fail-closed);
  (f) GMX GM ETH/USD & (if excluded) PT-ETH realize; PT-cmETH carries; (g) non-cluster fee/gas leg
  never counts as a principal.
- **CC-AC-9** DZENGI wallet remains excluded and its figures unchanged; multi-wallet/network scope
  config untouched.
- **CC-AC-10** Full test suite + conservation/arch gates green.

## 6. Risks & mitigations

- **GMX GM ETH/USD / any unmapped instrument** → fail-safe realize (null cluster). Verify
  `isIntraClusterConversion` returns false.
- **Fee/gas legs** stay GAS_ONLY (predicate considers only principals).
- **Partial/dust/uncovered** handled by `allocateInbound` covered/uncovered split (CC-AC-2 scoping).
- **Down-conversions** (acquired market < disposed basis): carry preserves basis (no write-down) —
  intended; guard against `Net > Market` assertions on carried lots.
- **Registry blast radius:** extending membership via family→cluster table (not C1/C2 set edits)
  localizes the change to cluster resolution + carry, avoiding `accountingFamilyIdentity` churn on
  ledger points. If C1/C2 edits are unavoidable for PT tokens, add explicit ordered steps + gate
  coverage (auditor R4).
- **Verification:** `./scripts/prod-reset-rebuild-backend.sh --skip-frontend` (no `--clear-pricing-cache`
  — pricing policy unchanged) → wait for normalization/replay → re-run `financial-logic-auditor`
  against CC-AC-1…CC-AC-10. Frontend tooltip copy is a small follow-up `--frontend-only` pass.

## 7. Phase 3 review resolutions (traceability)
- Auditor R1 (under-coverage) → §4.1 membership extension (MSOL/VSOL/BSOL/JITOSOL/PT-CMETH/PT-ETH/AAVASAVAX).
- Auditor R2 / BA §2 / architect Q4 (PT contradiction) → §3: PT-cmETH **and** PT-ETH in cluster; GMX out.
- Auditor R3 / BA 1a (AC universe & terminal sign) → CC-AC-2/CC-AC-3 recompute + both surfaces.
- Auditor R4 (blast radius) → §4.1 family→cluster table + §6.
- Auditor R5 / architect Q3 (carry-path interactions) → §4.3 (SWAP SELL pool), §4.4 (gate), §4.5 (dedup).
- Auditor R6 / architect Q3 Hazard 1 (stamp still fires) → §4.4 explicit gate.
- BA (headline value, thresholds, DZENGI, tooltip, rewards) → CC-AC-5/7/9, §4.6, §4.7.
- Architect Q2 (engine extraction, single route, contract-first) → §4.1, §4.3.
- Architect Q4 (config location) → §4.1 (not break-even-attribution.json).
