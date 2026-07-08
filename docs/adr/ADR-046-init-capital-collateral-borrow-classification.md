# ADR-046 — INIT Capital collateral-borrow classification and BORROW net cost rule

**Status:** Accepted  
**Date:** 2026-07-07  
**Theme:** On-chain classification / Lending / AVCO

---

## Context

INIT Capital is a money market on Mantle Network. Its PositionManager contract exposes a single
`execute(tuple _params)` entry point (methodId `0x247d4981`) that bundles multiple DeFi actions
in one call. Observed patterns:

| Pattern | Inbound | Outbound |
|---------|---------|---------|
| Deposit collateral + Borrow | USDC from borrow pool | cmETH to collateral pool |
| Pure additional borrow | Asset from borrow pool | — |
| Repay + Withdraw collateral | cmETH from collateral pool | USDC to PositionManager |

Without protocol-specific classification, all patterns fell back to generic SWAP / BRIDGE_IN,
producing:
- A phantom DISPOSE on the collateral asset (fabricating tax PnL -$1,159 on Jun 17, 2025)
- Wrong re-acquisition of collateral at USDC cost via Bug B (cmETH net AVCO $1,167 vs correct $2,155)
- Cascading basis errors in downstream Pendle LP and Equilibria LP exits

Additionally, `BorrowReplayHandler` applied market price as **both** tax and net acquisition cost for
borrowed assets — treating borrowed liabilities as purchased assets in the Net AVCO lane.

---

## Decision

### D-1: INIT Capital semantic classifier

A dedicated `InitCapitalSemanticClassifier` is added to:
`com.walletradar.application.normalization.pipeline.classification.onchain.protocol.init`

Detection: methodId `0x247d4981` on the PositionManager contract.

Classification by token-transfer pattern:
- **Collateral out + borrow in** → `BORROW` (primary type covering the combined operation)
- **Pure borrow only** → `BORROW`
- **Repay + collateral return** → `LENDING_WITHDRAW`

### D-2: BORROW type replay — collateral SELL flow as REALLOCATE_OUT

When a `BORROW` transaction contains a SELL flow that is NOT a debt token (i.e., a real asset
acting as collateral), `ReplayDispatcher` routes it as `REALLOCATE_OUT` via the corridor/transfer
infrastructure rather than passing it to `BorrowReplayHandler` (which only handles inbound BUY flows).

The corridor key links the BORROW outbound to the corresponding LENDING_WITHDRAW inbound, preserving
the original cost basis of the collateral across the lending period.

### D-3: Bug D — BORROW inflows have $0 net acquisition cost

Borrowed assets enter the wallet as liabilities, not purchased assets. In the Net AVCO lane,
their acquisition cost is `$0`. In the Tax AVCO lane, acquisition cost remains `FMV × qty`
(market price at time of borrow), so Tax AVCO is unaffected.

`BorrowReplayHandler` is changed to call `applyBuyWithExplicitNetCost(flow, position,
taxCost=qty×marketPrice, netCost=BigDecimal.ZERO)` for all BUY inflows. The liability tracker
continues to receive the market price for correct repay liability matching.

This rule applies globally to **all** BORROW transactions (Aave, INIT Capital, GHO, Bybit).

### D-4: Equilibria Booster and reward pool registration

Equilibria contracts (`0x70f61901…` Booster, `0x2fa11dbc…` BaseRewardPool) are added to
`protocol-registry.json` on MANTLE with `hasExplicitClaimSignal=true`, enabling the
`RewardRouteClassifier` to correctly attribute PENDLE reward flows in `zapOutV3SingleToken`
as `ACQUIRE` at $0 net cost.

---

## Known INIT Capital Contracts (Mantle)

| Role | Address |
|------|---------|
| PositionManager | `0xf82cbcab75c1138a8f1f20179613e7c0c8337346` |
| cmETH collateral pool | `0x6cc1039746803bc325ec6eb7262def3a672ae243` |
| USDC borrow pool | `0x00a55649e597d463fd212fbe48a3b40f0e227d06` |

---

## Consequences

**Positive:**
- Eliminates phantom $1,159 tax PnL from Jun 17, 2025 INIT Capital deposit
- Restores cmETH net AVCO from $1,167 → $2,155 (correct)
- Downstream Pendle LP basis ($1,856 net) restored after pipeline reset
- Equilibria zapOut cmETH net AVCO also corrects automatically
- BORROW lanes: borrowed USDC, GHO, etc. no longer inflate Net AVCO

**Risk:**
- Bug D affects all BORROW transactions globally; other borrow-dependent tests must be re-verified
- If additional INIT Capital `execute` function patterns exist on Mantle, fallback is `UNKNOWN` type (surfaced as warning, not crash)

---

## References

- Bugs: BB-INIT-1, BB-EQB-1, Bug-D
- Implementation plan: `docs/tasks/init-capital-equilibria-classification-fix-plan.md`
- Prior art: `AuraProtocolSemanticClassifier` (same classifier pattern)
- ADR-012: Borrow liability tracker (repay matching, market-price basis)
- ADR-040: Dual cost basis Net/Tax AVCO
