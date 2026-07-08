# Implementation Plan: INIT Capital + Equilibria Classification Fix

**Slug:** `init-capital-equilibria-classification-fix`  
**Date:** 2026-07-07  
**Stage:** Phase 2 (Plan)  
**Priority:** HIGH

---

## 1. Root Cause & Scope

### Bug BB-INIT-1 — INIT Capital transactions misclassified (HIGH)

INIT Capital is a money market on Mantle Network using a single `execute(tuple _params)` entry point
(methodId `0x247d4981`) on PositionManager `0xf82cbcab75c1138a8f1f20179613e7c0c8337346`.
One `execute` call can combine multiple actions (e.g., deposit collateral + borrow, or repay + withdraw collateral).

The classifier has no knowledge of INIT Capital, so it falls back to generic SWAP / BRIDGE_IN patterns.

**Three affected transactions:**

| # | TX hash | Date | Current | Correct |
|---|---------|------|---------|---------|
| A | `0x0df8d9ba…` | 2025-06-17 | `SWAP` cmETH→USDC | `BORROW`: cmETH REALLOCATE_OUT + USDC ACQUIRE($0 net) |
| B | `0xd11e65b0…` | 2025-07-21 | `BRIDGE_IN` +100 USDC | `BORROW`: USDC ACQUIRE($0 net) |
| C | `0xbc698f78…` | 2025-07-31 | `SWAP` USDC→cmETH | `LENDING_WITHDRAW`: USDC DISPOSE + cmETH REALLOCATE_IN |

**Context:** cmETH was used as INIT Capital collateral to borrow USDC:
- Jun 17: Exit Pendle LP → deposit cmETH as collateral → borrow 900 USDC (TX A)
- Jul 21: Borrow additional 100 USDC (TX B)
- Jul 31: Repay 1,005.30 USDC (principal + interest) → receive cmETH back (TX C)

**AVCO impact:**

| Metric | Current (wrong) | Should be |
|--------|----------------|-----------|
| cmETH net AVCO | $1,167 | $2,155 |
| cmETH net cost basis (0.8615) | $1,005.30 | $1,856.71 |
| Net basis error | **-$851** | — |
| Phantom tax PnL on Jun 17 | **-$1,159** (fabricated loss) | $0 (no sale occurred) |

**Cascade:** The wrong net cost from TX C propagates to:
- `0xfaf8160c` (Aug 1, LP_ENTRY Pendle) → stores $1,005 in `lp_receipt_basis_pools` instead of $1,857
- `0xf7f8908b` (Sep 10, Equilibria zapOut) → restores $1,005 net basis

Fixing INIT Capital will **auto-correct all downstream LP effects** after pipeline reset.

---

### Bug BB-EQB-1 — Equilibria PENDLE reward has UNKNOWN basis effect (LOW)

**TX:** `0xf7f8908b…` (2025-09-10) — Equilibria `zapOutV3SingleToken`  
**Symptom:** PENDLE reward flow has `basisEffect=UNKNOWN` instead of `ACQUIRE` at $0 net cost.  
**Root cause:** Equilibria reward contracts (`0x2fa11dbc…`) not registered; PENDLE flow not routed as reward.

Ledger state: PENDLE is acquired at $0 cost basis but with UNKNOWN basis effect —
functional for AVCO but incorrect for P&L attribution.

---

### Bug D (existing, linked) — BORROW net cost should be $0

When USDC (or any asset) is borrowed, it is a liability — not purchased.  
The `net` acquisition cost should be `$0` (only market/tax cost is recorded).  
This was already identified but not yet implemented.  
**Required for BB-INIT-1 to be fully correct.**

---

## 2. Known Contracts

### INIT Capital (Mantle)

| Role | Address | Notes |
|------|---------|-------|
| PositionManager | `0xf82cbcab75c1138a8f1f20179613e7c0c8337346` | Entry point for all user actions |
| cmETH collateral pool | `0x6cc1039746803bc325ec6eb7262def3a672ae243` | cmETH goes here on deposit, returns on withdraw |
| USDC borrow pool | `0x00a55649e597d463fd212fbe48a3b40f0e227d06` | USDC originates here on borrow |

### Equilibria (Mantle)

| Role | Address | Notes |
|------|---------|-------|
| Booster | `0x70f61901658aafb7ae57da0c30695ce4417e72b9` | `zapOutV3SingleToken`, `claimRewards` |
| BaseRewardPool | `0x2fa11dbcbf955467e6b6f81816b21250f8f3ac30` | Distributes PENDLE rewards |
| cmETH pool receipt | `0xc2535b24…` | eqbPENDLE-LPT (wrapper for Pendle LP) |

---

## 3. Ordered Changes

### Step 1 — `protocol-registry.json` (Mantle entries)

Add INIT Capital:
```json
{
  "contractAddress": "0xf82cbcab75c1138a8f1f20179613e7c0c8337346",
  "networkId": "MANTLE",
  "protocolName": "INIT Capital",
  "protocolVersion": "V1",
  "contractRole": "LENDING_ROUTER",
  "contractClass": "POSITION_MANAGER"
},
{
  "contractAddress": "0x6cc1039746803bc325ec6eb7262def3a672ae243",
  "networkId": "MANTLE",
  "protocolName": "INIT Capital",
  "protocolVersion": "V1",
  "contractRole": "LENDING_POOL",
  "contractClass": "COLLATERAL_POOL"
},
{
  "contractAddress": "0x00a55649e597d463fd212fbe48a3b40f0e227d06",
  "networkId": "MANTLE",
  "protocolName": "INIT Capital",
  "protocolVersion": "V1",
  "contractRole": "LENDING_POOL",
  "contractClass": "BORROW_POOL"
}
```

Add Equilibria:
```json
{
  "contractAddress": "0x70f61901658aafb7ae57da0c30695ce4417e72b9",
  "networkId": "MANTLE",
  "protocolName": "Equilibria",
  "protocolVersion": "V1",
  "contractRole": "YIELD_ROUTER",
  "contractClass": "BOOSTER",
  "hasExplicitClaimSignal": true
},
{
  "contractAddress": "0x2fa11dbcbf955467e6b6f81816b21250f8f3ac30",
  "networkId": "MANTLE",
  "protocolName": "Equilibria",
  "protocolVersion": "V1",
  "contractRole": "REWARD_POOL",
  "contractClass": "BASE_REWARD_POOL"
}
```

---

### Step 2 — `InitCapitalProtocolSemanticClassifier`

Location: `backend/.../classification/onchain/protocol/init/InitCapitalSemanticClassifier.java`

**Detection:** `to == PositionManager` AND `methodId == 0x247d4981`

**Classification logic** (based on token transfer patterns):

```
A. collateral-asset flows FROM wallet TO collateral-pool
   AND borrow-asset flows FROM borrow-pool TO wallet
   → type = BORROW
   → collateral-flow: role=SELL (REALLOCATE_OUT)
   → borrow-flow: role=BUY (ACQUIRE, netCost=0)

B. borrow-asset flows FROM wallet TO PositionManager
   AND collateral-asset flows FROM collateral-pool TO wallet
   → type = LENDING_WITHDRAW
   → repay-flow: role=SELL (DISPOSE)
   → collateral-return-flow: role=BUY (REALLOCATE_IN)

C. only borrow-asset flows FROM borrow-pool TO wallet (pure additional borrow)
   → type = BORROW
   → borrow-flow: role=BUY (ACQUIRE, netCost=0)
```

**correlationId for REALLOCATE corridor:**
`INIT:{networkId}:{walletAddress}:{collateral-contract}` (deterministic, per position)

---

### Step 3 — Bug D: BORROW net cost = $0

In replay handler for `BORROW` type:
- Collateral flows (`REALLOCATE_OUT`): carry existing net basis (no change)
- Borrow inflows (`ACQUIRE`): `netAcquisitionCostUsd = 0` (only tax cost = market value)

This applies globally to all `BORROW` transactions, not just INIT Capital.

---

### Step 4 — Equilibria PENDLE reward fix (BB-EQB-1)

Option A (registry-based): Add Equilibria Booster to registry with `hasExplicitClaimSignal=true`
→ `RewardRouteClassifier` will pick up PENDLE as reward and apply ACQUIRE at $0 net.

Option B (semantic classifier): Create `EquilibriaSemanticClassifier` to handle mixed exits
(zapOut = LP_EXIT + REWARD_CLAIM in one tx).

Recommend Option A for now (low effort), escalate to B if additional Equilibria functions need special handling.

---

### Step 5 — Continuity corridor for INIT Capital positions

The REALLOCATE_OUT (Tx A) → REALLOCATE_IN (Tx C) pair requires basis carry across time.

**Two options:**
1. **Reuse `LpReceiptBasisPool`** with `protocolName=INIT_CAPITAL` and `positionId={collateral-contract}`.
   - On BORROW tx: persist cmETH net/tax basis to pool.
   - On LENDING_WITHDRAW tx: look up pool and restore basis.
   - **Recommended** — already proven mechanism, minimal new code.

2. **Use corridor/carry system** with `correlationId`.
   - Lighter weight but requires corridor persisted in MongoDB across replay boundaries.

---

## 4. Acceptance Criteria

After `--skip-frontend` pipeline reset:

| Check | Expected |
|-------|---------|
| `0x0df8d9ba` type | `BORROW` |
| `0xd11e65b0` type | `BORROW` |
| `0xbc698f78` type | `LENDING_WITHDRAW` |
| cmETH netAvco (after `0xbc698f78`) | ≈ **$2,155** (restored from REALLOCATE_IN) |
| cmETH phantom tax PnL on Jun 17 | **$0** (no DISPOSE) |
| Aug 1 LP_ENTRY `lp_receipt_basis_pools` net basis | ≈ **$1,856** |
| Sep 10 Equilibria zapOut netAvcoAfter cmETH | ≈ **$2,155** |
| PENDLE in `0xf7f8908b` basisEffect | `ACQUIRE` ($0 net) |
| USDC borrow AVCO impact | $0 net cost propagated downstream |

---

## 5. Risks

| Risk | Mitigation |
|------|-----------|
| Other INIT Capital `execute` function signatures not handled | Inspect all 3 known txs; fallback to UNKNOWN type if pattern not matched |
| Corridor timing: replay order may differ | Use `LpReceiptBasisPool` (persisted) rather than in-memory carry |
| Bug D affects other BORROW txs (GHO, etc.) | Audit all `BORROW` type txs after deploy |
| Equilibria has more functions beyond `zapOut` and `claimRewards` | Limited to 2 txs on Mantle; add classifier if more variants found |

---

## 6. Docs to update

- `docs/reference/supported-networks-and-protocols.md` — add INIT Capital, Equilibria
- `docs/pipeline/cost-basis/02-avco-rules.md` — BORROW netCost=0 rule
- `docs/adr/` — new ADR for BORROW net cost rule (Bug D)
