# ADR-054: Per-asset AVCO for staked / value-accruing derivatives (one asset, one cost-basis pool)

| Field | Value |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-07-13 |
| **Theme** | Cost basis / asset identity / replay / pricing / read model |
| **Supersedes** | ADR-017 staked-ETH inclusion clause (value-accruing LST/LRT no longer share the ETH spot-family series) |
| **Amends** | ADR-040 (Tax→Market lane naming), ADR-045 (family membership — C2 excluded), ADR-031 (replay-time fail-closed) |
| **Clarifies** | ADR-019 / ADR-029 / ADR-043 (corridor carry gated on canonical-token identity) |
| **Cites** | ADR-004 (physical quantity vs basis-backed) |
| **Plan** | `docs/tasks/eth-family-tracking-model-implementation-plan.md` |

## Context

`AccountingAssetFamilySupport.SYMBOL_FAMILIES` collapses ETH **and every ETH derivative** into a single
`FAMILY:ETH` continuity identity, and the replay handlers (`LiquidStakingReplayHandler`,
`FamilyEquivalentCustodyReplayHandler`) carry basis **1:1 by covered quantity** between family members
with **no realized P&L**.

This 1:1 fungibility assumption is only valid for representations that are the **same underlying claim,
redeemable 1:1 on demand at a fixed rate** (ETH↔WETH; rebasing Aave aTokens whose balance grows but
stays 1:1 with WETH). It is **invalid** for value-accruing liquid-staking / restaking tokens
(wstETH, cmETH, weETH, rETH, cbETH, mETH, rsETH, ezETH, osETH, …) whose ETH-redemption rate drifts above
1 and which trade at their **own market price** with their own depeg risk.

Observed consequences (financial audit, `results/`, and `docs/tasks/eth-family-tracking-model-implementation-plan.md` §3–§4):

- A staked token carried 1:1 removes 1.0 ETH of basis and re-attaches it to a smaller derivative quantity → per-token AVCO inflates; the reverse deflates it.
- `FAMILY:ETH` blends bases from radically different acquisition contexts (venue staking, on-chain spot, corridor WETH) into one covered-weighted average → large, confusing AVCO swings (ADR-045 needles amplified).
- A disposal of a staked token (`cmETH → USDT`) realizes P&L against the **wrong** (ETH-blended) basis.
- `CanonicalAssetCatalog` actively **aliases** `CMETH/METH/WEETH → ETH` (`isPeggedNative`) and prices `WSTETH` as stETH, so even where the model would price a derivative at its own market it silently uses ETH's price.

The product must correctly account for staked assets that are **independently sold**, **converted to each
other**, or **converted to independent assets** (directive in the plan).

## Decision

Adopt a single, universal principle — **"one asset, one cost-basis pool"**:

> Every distinct economic asset has exactly one AVCO (cost-basis) pool. Two representations share a pool
> **only if** they are the same underlying claim, redeemable 1:1 on demand at a fixed rate. **Any movement
> between distinct assets is a disposal + acquisition priced at market, realizing P&L. Any movement within
> the same asset carries basis with no P&L.**

### 1. Asset identity classification (declarative registry)

A single declarative registry classifies every asset into one of:

| Category | Rule | Identity | Conversion accounting |
|---|---|---|---|
| **C1 — same-asset custody representation** | same underlying, redeemable **1:1 on demand at fixed rate** | shares the underlying family (e.g. `FAMILY:ETH`) | **carry basis, NO P&L** (REALLOCATE) |
| **C2 — distinct market asset** (staked / value-accruing / restaked / yield-vault share) | own market price; not fixed-1:1-redeemable; independently sellable | **own per-token identity** (e.g. `FAMILY:CMETH`, `FAMILY:WSTETH`) | **disposal + acquisition at market, REALIZE P&L** |
| **C3 — everything else** | default | own asset identity | normal swap semantics (already correct) |

**Boundary rules (encoded in the registry, guarded by an ArchTest):**

- Rebasing receipt where balance grows and **quantity == underlying 1:1** (Aave `a*WETH` aTokens) → **C1**.
- **ERC-4626 vault share of the same underlying** (Euler `eWETH`, Morpho/MetaMorpho WETH vaults) where
  quantity is fixed and the share→underlying rate drifts → **C2**, even though the underlying is WETH. The
  registry must distinguish *Aave a\*WETH (C1, rebasing 1:1)* from *Euler/Morpho ERC-4626 receipts of WETH
  (C2)*.
- Receipt/share where **quantity is fixed and the exchange/redemption rate drifts** (Compound/Venus vTokens,
  all value-accruing LST/LRT) → **C2**.
- A **receipt of a C2 asset** (Euler/Yearn wrapping an LST/LRT) → **C2**.

**ETH C1 members:** `ETH`, `WETH`, all Aave `A*WETH` variants (rebasing aTokens), `VBETH` (ETH bridged to
the Katana network), and the same ETH bridged across chains.
**ETH C2 members:** `STETH`, `WSTETH`, `RETH`, `CBETH`, `METH`, `CMETH`, `OSETH`, `EETH`, `WEETH`,
`EWEETH`, `EWETH` (Euler ERC-4626 receipt of WETH), `EZETH`, `RSETH`, `YVVBETH`, and any other
value-accruing yield-vault share of ETH.

> **stETH note:** although Lido stETH rebases ≈1:1, it is an independently-tradable asset with depeg risk
> and its own market price → **C2** (own pool). The "≈1:1" property does not make it the same claim as ETH.

### 2. Conversion accounting — realize P&L at market (no new replay handler)

A move is **basis-carry** (REALLOCATE, no P&L) **iff both legs share the same canonical-token identity**
(C1↔C1 in the same family, or the **same token** moved across chains / custody locations incl. corridors
and bridges). **Otherwise it is a disposal + acquisition at each leg's pre-resolved market price**
(`flow.unitPriceUsd`), realizing P&L against the **source** pool.

Consequently `ETH→C2` (stake) and `C2→ETH` (unstake) **realize P&L at market**, symmetric on both
directions — dispose the source at market, acquire the target at fresh market basis. This holds equally
for rebasing (stETH) and non-rebasing value-accruing C2 (wstETH/cmETH); the rebase governs *income
recognition* (§4), not the disposal boundary.

Because C2 tokens no longer share `FAMILY:ETH`, realized P&L falls out of the **existing generic
DISPOSE+ACQUIRE** path — `LiquidStakingReplayHandler` / `FamilyEquivalentCustodyReplayHandler` simply stop
firing for ETH↔C2. **No new replay handler; routing stays pricing-free** (replay reads pre-resolved
prices).

**Net lane for C1→C2 conversions:** The Net lane for the C2 acquisition inherits the net basis
released by the C1 disposal, using the same `swapNetRef` carry mechanism as SWAP transactions
(ADR-040 Bug B). The inherited net basis is capped at the C2 market basis
(`min(inherited, market)`) to preserve the global `Net AVCO ≤ Market AVCO` invariant. Only the
Market lane receives fresh market price.

### 3. Same-token custody carry (both classes)

A **same-token** move — a `cmETH` CEX→wallet corridor deposit, a C2 token bridged across chains — is NOT a
conversion; it **carries basis with no P&L**. Corridor carry (ADR-019/029/043) is gated on
**canonical-token identity**, so a C2 same-token corridor still carries.

### 4. Income recognition (rewards / rebases / interest)

- **Value-accruing (non-rebasing) C2** (wstETH, cmETH, weETH…): yield is embedded in price → surfaces as
  unrealized gain in the token's own AVCO, realized on disposal. No special handling (correct by construction).
- **Rebasing C2** (stETH) and **C1 lending receipts** (aWETH interest): the balance grows. Each accrual is
  **income booked at market value** in the AVCO-authoritative **Market** lane: `qty += Δ`,
  `basis += Δ × marketPrice`, income realized = `Δ × marketPrice`; per-unit AVCO stays stable (yield is
  income, not basis dilution). **No accrual path adds quantity at $0 basis in the Market lane** — this
  removes the zero-cost `REWARD_CLAIM` behaviour that previously inflated/blended cmETH. The **Net** lane
  (ADR-040) still books such income at $0 by design (its purpose).

### 5. Three identity layers (do NOT re-key positions)

- **L1 — asset identity** (classification / continuity): governs carry-vs-realize (this ADR's registry).
- **L2 — cost-basis pool**: keyed per `(wallet, network, contract)` exactly as today — even ETH is not one
  cross-chain pool. **Pool keying is unchanged.**
- **L3 — read-model aggregation**: cross-chain / cross-wallet unification of a single C2 token is a
  read-model rollup (parallel to ADR-045), **NOT** a position re-key.

### 6. Pricing (load-bearing prerequisite)

The `CanonicalAssetCatalog` pricing aliases (`CMETH/METH/WEETH → ETH` with
`PEGGED_NATIVE_SYMBOLS = {CMETH, METH, WEETH, BBSOL}`, and `WSTETH → staked-ether` = the stETH id) **must be
removed**, or a free source's price is bypassed and the model degrades to the 1:1 carry it replaces.

**Resolve via FREE, high-limit sources first (user directive 2026-07-13; CoinGecko is paid/rate-limited →
last resort only).** Existing resolver priority already favours free sources: DZENGI/ECB(0) → **Bybit(1)**
→ Binance(2) → **DefiLlama(3)** → CoinGecko(4). Verified live coverage for the held C2 set:

- **DefiLlama by contract (free) is the primary independent source** — covers every held C2 on every chain
  in the dataset (mantle, arbitrum, katana, unichain), including historical (e.g. wstETH on Arbitrum
  2025-04-17 = $1890.37, distinct from ETH). **`DefiLlamaClient` must add the `KATANA` and `UNICHAIN` chain
  slugs** (both confirmed supported by DefiLlama) to unlock weETH/yvvbETH/wstETH on those chains.
- **Bybit (free)** independently prices `mETH` (`METHUSDT` verified live).
- **CoinGecko is avoided**; if ever reached, `WSTETH` must map to its own id (`wrapped-steth`), never
  `staked-ether`.

With these changes **no fail-closed is expected for the held C2 set**. An ArchTest asserts no C2 symbol maps
to a base-asset market symbol / `isPeggedNative`, plus a positive test that a `WSTETH` disposal prices at
wstETH's own market (≠ ETH where they diverge). Fail-closed (§9) remains the guard for genuinely unlisted
future tokens.

### 7. Lane naming — Tax → Market

The ADR-040 dual lane is retained; the gross/tax lane is renamed **Market** everywhere (labels, enums,
docs, ADR text; internal `avcoUsd`/`avcoAfterUsd` field names kept for backward compatibility per ADR-040).
Lanes are **Market AVCO** + **Net AVCO** (see ADR-040 amendment 2026-07-13).

### 8. Read model

C2 tokens no longer roll into the `FAMILY:ETH` spot line; each C2 renders its **own** per-asset AVCO line.
`FAMILY:ETH` shows only true same-asset ETH. Cross-chain unification of one C2 token is an L3 aggregation.
An optional informational "total ETH-denominated exposure" overlay may be shown, clearly labelled, and is
**never** an AVCO source.

### 9. Fail-closed on unpriceable conversions

A C2 conversion with no obtainable market price → explicit `AVCO undefined` (ADR-031, extended to
replay-time) + flag; **never** a silent 1:1 carry. A fail-closed disposal must **flag + defer**, never
silently strand basis; the conservation gate accounts for fail-closed events so a deferred/unpriced C2 leg
surfaces as a flagged imbalance rather than a masked loss/gain.

## Consequences

- Staked/derivative disposals realize P&L against the correct per-token basis; stable spot-ETH AVCO; no
  cross-venue basis blending under one identity.
- Requires **full renormalization + replay reset** and `--clear-pricing-cache` (pricing aliases change).
  Accepted per the directive (existing data disposable).
- C2 pricing coverage at every conversion timestamp is load-bearing; a missing price blocks P&L realization
  and routes to `AVCO undefined` (never fabricated).
- No position re-key migration (L2 pools unchanged); cross-chain unification stays a read-model concern.
- Applies uniformly beyond ETH (BTC/AVAX/MNT/SOL): `SAVAX`, `BBSOL` → C2 own pool; `WBTC`/`A*WBTC` → C1
  only if 1:1-redeemable.

## Alternatives considered

- **Model A (status quo — one `FAMILY:ETH`, 1:1 carry):** conflates distinct assets; realizes P&L against a
  blended basis; wstETH's >1 rate corrupts family quantity. Financially wrong.
- **Model B (single family, rate-normalized ETH-equivalent quantity):** needs a deterministic historical
  redemption-rate oracle per token at backfill time (not reliably available) and still hides genuinely
  distinct assets (different depeg/price risk) behind one AVCO. Over-engineered and still economically lossy.
- **Carry basis on stake (no P&L):** internally incoherent once C2 is an independently-priced asset — it
  assigns C2 a synthetic `basis/qty` unit cost matching no real market price, reproducing the observed
  stale-blend and Net-vs-Market divergence (`financial-logic-auditor`, 2026-07-13).

## References

- `AccountingAssetFamilySupport`, `ReplayTransferClassifier`, `LiquidStakingReplayHandler`,
  `FamilyEquivalentCustodyReplayHandler`, `GenericFlowReplayEngine`
- `CanonicalAssetCatalog` (pricing alias removal), `PriceExternalSourceOrchestrator`
- `AssetLedgerQueryService` (read-model / L3 aggregation)
- ADR-004, ADR-017, ADR-019, ADR-029, ADR-031, ADR-040, ADR-043, ADR-045
- `docs/tasks/eth-family-tracking-model-implementation-plan.md`
- `docs/pipeline/cost-basis/02-avco-rules.md`, `docs/pipeline/cost-basis/03-basis-pools-and-carry.md`
- `docs/examples/avco-replay-examples.md` (Example 5 — worked walkthrough)
