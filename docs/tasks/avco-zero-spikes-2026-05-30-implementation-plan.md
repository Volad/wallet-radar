# AVCO Zero-Spikes — Implementation Plan
**Audit ref:** `docs/tasks/avco-zero-spikes-2026-05-30-audit.md`  
**Date:** 2026-05-30  
**Blockers:** B-ZERO-1 … B-ZERO-6  
**User approval:** confirmed — implement → verify → re-audit loop until normalization/replay defects cleared  
**Cycle status:** CLOSED — all P0/P1 blockers PASS (2026-05-31); B-ZERO-6 deferred to next cycle

---

## Summary

| Blocker | Severity | Root cause | Fix type |
|---------|----------|------------|----------|
| B-ZERO-1 | P0 — graph zero-cliffs | `TimelineAvcoAuthority` returns UNAVAILABLE when event legs have null avco (EARN drain, dust DISPOSE, GAS_ONLY) though family covered qty unchanged | Read-model fix | **PASS** — 484→75 UNAVAILABLE; 0 bb>0.001 with avco=0 |
| B-ZERO-2 | P0 — CMETH basis leak | `EXECUTION_SPOT` SELL replays on umbrella `BYBIT:33625378` while CMETH inventory sits on `:FUND` / `:EARN` → `DISPOSE qtyD=0` (38 rows) | Replay wallet routing | **PASS** — 38→0 zero-dispose; tx 2200000000707964104 qtyD=−0.0038 cbD=−$10.34 |
| B-ZERO-3 | P1 — corridor uncovered | FUND `FUNDING_HISTORY` corridor OUT marked `excludedFromAccounting:true` → no CARRY_OUT; on-chain IN is zero-basis ACQUIRE | Normalization + replay | **PASS** — CARRY_OUT cbD=−$13.13 uncovD=0; CARRY_IN cbD=+$13.00 uncovD=0 |
| B-ZERO-4/5 | P1 — orphan bridges | Across OUT missing `correlationId`; LiFi OUT has corr but no IN in universe | Linking / classification | **PASS** — 0x258 corr stamped; 0x6c5 IN found on OPTIMISM; 10 BRIDGE_IN NEEDS_REVIEW→0 |
| B-ZERO-6 | P2 — +30% spike | BASE LiFi shortfall: spot-priced CARRY_IN when OUT carried $0 | move_basis (existing B-1 family) | **DEFERRED** |

**Out of scope:** frontend chart changes, new transaction types, manual per-tx overrides.

---

## Iteration protocol (mandatory after each merge)

1. `./scripts/prod-reset-rebuild-backend.sh --skip-frontend`
2. Wait for normalization + cost-basis replay
3. Re-run `scripts/audit/avco-zero-spikes-2026-05-30.mongosh.js` + API UNAVAILABLE count
4. Delegate `financial-logic-auditor` (readonly) — new blockers only
5. If blockers remain → revise this plan (append B-ZERO-N) and repeat from P0

---

## B-ZERO-1 — Timeline UNAVAILABLE cliff storm

### Root cause

484/1992 timeline rows get `avcoKind=UNAVAILABLE` because `TimelineAvcoAuthority.resolve()` selects a member leg with `avcoAfterUsd=null` (typical: EARN `REALLOCATE_OUT` with bb→0, dust `DISPOSE`, `GAS_ONLY`). Chart renders null at Y-min → vertical cliff. **Family covered qty and total basis remain correct** in `AggregatedState`.

### Fix

**Files:** `TimelineAvcoAuthority.java`, `AssetLedgerQueryService.java`

1. Add `KIND_CARRIED_FORWARD` (or reuse PRIMARY with explicit kind string).
2. Extend `resolve()` signature to accept optional context:
   - `aggregatedCoveredQuantityAfter`
   - `aggregatedTotalCostBasisUsdAfter`
   - `lastAvcoByAssetIdentity` map (already maintained in query service)
3. When `selectAuthoritativePoint()` returns null or chosen leg has null/`≤0` avco:
   - If aggregated covered qty did **not** decrease materially (tolerance ε) vs event start, return carried-forward AVCO from `lastAvcoByAssetIdentity` for best family spot identity.
   - Prefer family-level fallback: `totalCostBasis / coveredQuantity` when covered > ε.
4. Improve scoring (secondary): on earn withdraw events, prefer `BYBIT:*:FUND` / umbrella leg over empty `:EARN` leg when both present in `memberPoints`.

### Tests

- `AssetLedgerQueryServiceTest`: earn withdraw grouped event → non-null `avcoAfterUsd`, kind `CARRIED_FORWARD` or `PRIMARY_FLOW`
- `TimelineAvcoAuthorityTest` (unit): UNAVAILABLE → carry-forward when covered unchanged

### Acceptance

- API timeline UNAVAILABLE count drops from **484** to **< 100** (target: basis-neutral events only)
- Suspect txs `a67c0479`, convert dust, `734bf6f1` emit non-null AVCO within **5%** of adjacent PRIMARY rows
- Dashboard `current.avco` unchanged (±$5): **$2,589**

---

## B-ZERO-2 — EXECUTION_SPOT CMETH zero dispose

### Root cause

Tx `BYBIT-33625378:EXECUTION_SPOT:2200000000707964104:…`:
- Normalized wallet: `BYBIT:33625378:UTA`
- Ledger applies CMETH SELL on **`BYBIT:33625378`** (umbrella root)
- CMETH inventory before sell: **`BYBIT:33625378:FUND` qty=0.14379048** (seq 494)
- `applySell()` on empty umbrella position → `DISPOSE qtyD=0, cbD=0`
- Pattern: **38** CMETH `DISPOSE` rows with `quantityDelta=0`

### Fix

**Files:** `ReplayDispatcher.java` and/or Bybit-specific swap routing (investigate `familyEquivalentCustodyReplayHandler`, `AccountingAssetIdentitySupport.replayPositionWalletAddress`, Bybit sub-account resolution)

1. For Bybit `EXECUTION_SPOT` / `SWAP` SELL legs on family assets (CMETH, ETH, …):
   - Resolve dispose bucket from **actual replay inventory** (scan FUND/UTA/EARN sub-wallets in same accounting universe), not only tx `walletAddress` umbrella root.
2. Apply `applySell()` against the sub-wallet position holding sufficient qty; emit ledger on that wallet.
3. Do **not** invent basis — proportional dispose from held lot only.

### Tests

- `AvcoReplayServiceTest`: EXECUTION_SPOT CMETH sell with inventory on `:FUND` → `DISPOSE qtyD=-0.0038`, `cbD≈-$10.92`
- Regression: CMETH zero-dispose count = 0 after rebuild

### Acceptance

```javascript
db.asset_ledger_points.countDocuments({
  assetSymbol: 'CMETH', basisEffect: 'DISPOSE', quantityDelta: 0
}) === 0
```

Tx `2200000000707964104` ledger: `qtyD=-0.0038`, `cbD` within 1% of `0.0038 × avco_before`.

---

## B-ZERO-3 — Corridor 0x6ac6 FUND leg excluded

### Root cause

Pair `BYBIT-CORRIDOR:ARBITRUM:0x6ac6fc6010af5b…`:
- On-chain IN: `ACQUIRE +0.0039528 ETH`, cbD=0, uncovD=+0.0039528 (seq 500)
- FUND OUT: `BYBIT-33625378:FUNDING_HISTORY:0ca2c683…` — **`excludedFromAccounting: true`**, **0 ledger points**
- `ReplayDispatcher` skips excluded rows (line 107)

### Fix

**Files:** `BybitNormalizationService.java` / stream collapser OR corridor-specific inclusion rule; `TransferReplayHandler.java`

1. **Normalization:** Do not exclude FUNDING_HISTORY corridor OUT when:
   - `correlationId` starts with `BYBIT-CORRIDOR:` AND
   - correlated on-chain IN exists in same universe
2. **Replay:** Ensure FUND CARRY_OUT + on-chain CARRY_IN with matched basis (ADR-019 proportional rule already implemented for corridors)

### Tests

- Integration/replay test: corridor pair → FUND CARRY_OUT + on-chain CARRY_IN; `uncovD=0` on inbound

### Acceptance

0x6ac6 on-chain leg: `uncoveredQuantityDelta=0`, `costBasisDeltaUsd > 0`, matched to FUND outbound basis (±$0.01).

---

## B-ZERO-4 / B-ZERO-5 — Unpaired bridges

### Root cause

| Tx | Bridge | Issue |
|----|--------|-------|
| 0x258ed5… | Across V2 ARB | `BRIDGE_OUT`, **no correlationId**, no IN in universe |
| 0x6c5bd9… | LiFi ARB | `correlationId=bridge:lifi:…`, no IN with same corr |

OUT legs preserve wallet AVCO (no zero-cliff) but orphan OUT leaves destination uncovered off-universe.

### Fix

**Files:** bridge classifier / linking (`OnChainClassifier`, linking job, bridge correlation support)

1. **B-ZERO-4:** Assign `bridge:across:0x258ed5…` (or existing Across prefix) on OUT during classification
2. **B-ZERO-5:** Verify IN on destination chain — if outside session wallets, document as `UNSUPPORTED_SCOPE` orphan; if in universe but unlinked, fix linking matcher
3. Do not fabricate IN events

### Acceptance

- 0x258: correlationId populated; linking status documented
- 0x6c5: paired IN found OR explicit orphan record in audit with evidence
- Unpaired `BRIDGE_OUT` with empty corr count reduced from **46**

---

## B-ZERO-6 — BASE LiFi 0.799 ETH shortfall (P2)

Defer to existing B-1 normalization track unless quick win available in corridor shortfall handler.

**Direction:** When CARRY_OUT basis=0 due to shortfall, do not apply spot fallback CARRY_IN above carried evidence basis.

---

## Documentation

| Doc | Change |
|-----|--------|
| `docs/03-accounting.md` | Timeline AVCO carry-forward policy for basis-neutral events |
| `docs/adr/` | ADR amendment if corridor inclusion policy changes (B-ZERO-3) |

---

## Implementation order

1. **B-ZERO-1** (read-model — immediate graph fix)
2. **B-ZERO-2** (replay — material basis leak)
3. **B-ZERO-3** (normalization + replay)
4. **B-ZERO-4/5** (linking — investigate before code)
5. **B-ZERO-6** (optional same cycle if time)

---

## Risks

| Risk | Mitigation |
|------|------------|
| Carry-forward masks real basis loss | Gate on aggregated covered qty unchanged; auditor re-scan bb>0.001 & avco=0 |
| Sub-wallet routing breaks other Bybit swaps | Unit tests + full CMETH zero-dispose count |
| Corridor inclusion re-introduces duplicate FH/EXECUTION_SPOT | Scope exclusion rule to `BYBIT-CORRIDOR:` only, not all FH duplicates |
| Bridge IN off-universe | Mark unsupported; do not synthetic IN |

---

## Verification commands

```bash
./scripts/prod-reset-rebuild-backend.sh --skip-frontend

curl -s "http://127.0.0.1:18086/api/v1/sessions/df5e69cc-a0c0-4910-8b7d-74488fa266e2/asset-ledger?familyIdentity=FAMILY:ETH" \
  | jq '{unavailable: [.timeline[]|select(.avcoKind=="UNAVAILABLE")]|length, current: .current}'

docker exec walletradar-mongodb-prod mongosh walletradar --quiet --file scripts/audit/avco-zero-spikes-2026-05-30.mongosh.js
```
