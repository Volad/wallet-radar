# LP Exit Fee Income & Rebalancing — Implementation Plan

> **Status:** ✅ **APPROVED FOR IMPLEMENTATION.** Phase 3 final review complete (2nd round — financial, BA, architecture). All required revisions folded in. User decisions: (1) income implicit in AVCO; (2) three ADRs approved; (3) R7 in scope. Implement in priority order P1→P8.
> **Audit source:** `results/lp-exit-rebalancing-*.md`, `results/blockers.md`
> **Method context:** AVCO (see `docs/pipeline/cost-basis/01-overview.md`, ADR-022, ADR-040)

## Priority & sequencing (final)

Duplicate priority labels from the earlier draft are resolved; blockers ordered by materiality **and** dependency (R1 unblocks R2; R3 pairs with R2; R4 needs R3):

| # | Blocker | Stage | Materiality | Depends on |
|---|---|---|---|---|
| P1 | **R1** V3/Slipstream split | classification | $ (BASE 477096 −$132 basis, net-lane leader) | — |
| P2 | **R2** cross-asset drain + all-stable per-lane cap | replay | structural (dual + all-stable exits) | R1 |
| P3 | **R3** net-lane carry+zero (V3+Slipstream+V4) | replay | **$3,422 dangling net, 24 pools, all networks** | R2 |
| P4 | **R4** V4/Infinity CL fee reader | classification+pricing | high (ETH-denominated in-range) | R3 |
| P5 | **R6a** Balancer V3 + gauge + Aura (7-tx lifecycle) | classification | structural (fabricated $43 basis) | R1,R2 |
| P6 | **R6b** Aave lending re-route | classification | ~$5–6 (ZK volatile) | — |
| P7 | **R7** LFJ Liquidity Book | classification+correlation | ~$2.63 (stable pair) — coverage | R2 |
| P8 | **R5** Pendle/compounding verify+doc | verification | none (already correct) | — |

## Scope

- **Wallet / universe:** `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f` (umbrella universe).
- **Networks / assets:** ETHEREUM, ARBITRUM, BASE, OPTIMISM, UNICHAIN, KATANA, BSC, MANTLE; assets ETH/WETH family, USDC/USDT stables, vbETH, cmETH, CAKE, PENDLE.
- **Transactions:** 60 LP exits (`LP_EXIT` 58 + `LP_EXIT_FINAL` 2). Correlation breakdown from DB:
  - 48 with valid `lp-position:` correlation, by family (from DB):
    - **33 V3-family** (R1): 20 pure V3 (Pancake V3 ×14, Uniswap V3 ×5, SushiSwap/Katana V3 ×1) + 13 **Slipstream** CL forks (Velodrome ×12 OPTIMISM, Aerodrome ×1 BASE — V3-style Decrease/Collect events, covered by R1).
    - **15 V4-family CL** (R4): Uniswap V4 ×12 + Pancake V4 ×1 (UNICHAIN) + Pancake **Infinity** CL ×2 (BSC).
  - 3 **Pendle/Equilibria** (MANTLE) with `pendle-lp:` correlation (valid, R5).
  - 9 with `null` correlation — **not one bucket, three distinct bugs** (see R6a/R6b/R7): 4 **Balancer V3** boosted-stable pool mislabelled "Aave" (R6a) + 1 **Aave V3 lending** withdrawal mislabelled LP (R6b) + 4 **LFJ / Trader Joe Liquidity Book** (genuine LP, correlation unsupported, R7).
- **Blocker IDs:** R1 (V3/Slipstream split), R2 (cross-asset drain + all-stable cap), R3 (net-lane carry+zero, all networks), R4 (V4/Infinity reader), R5 (Pendle verify/doc), R6a (Balancer V3 + gauge + **Aura**, 7-tx lifecycle), R6b (Aave lending re-route), R7 (LFJ Liquidity Book). Cross-cutting architecture seams: **A1** correlation-strategy SPI, **A2** multi-standard receipt materializer, **A3** generalized LP clarification trigger (ENTRY+EXIT), **A4** ArchTests.

## Root cause (stage + cluster)

At `LP_EXIT` the wallet receives **principal (return of capital) + accrued fees bundled in one token transfer**. The replay uses a pool-residual approach (`received − qtyHeld`) which is invalid because concentrated-liquidity positions **rebalance** (composition changes). Verified on-chain, the true split is `fee = Collect.amount − DecreaseLiquidity.amount` (V3 events).

**Definitive accounting model (auditor-confirmed): Option B.** At exit, pool the combined USD basis of the whole position and re-allocate it across the returned **principal** assets as return of capital, **no P&L at exit**; the **fee** is split off and booked as **zero-cost income**. Rebalancing/impermanent loss realizes automatically through AVCO when the returned assets are later sold — no independent P&L at exit.
- Option A (per-asset restore) is wrong — strands basis when composition shifts (477096: $1,143 USDC basis destroyed).
- Option C (realize internal AMM swaps) is wrong for AVCO — not a user disposal, not observable at position level, double-counts P&L.

Stage clusters: `classification/clarification` (R1, R4, R6a, R6b, R7), `cost_basis/replay` (R2, R3), `verification/docs` (R5). Cross-cutting seams A1–A4.

## Changes (priority per the table above; upstream pipeline stages first)

### R1 — Split principal vs fee at V3/Slipstream exit (classification/clarification)
- `LpExitFeeClarificationTrigger` (mirror `NativeSettlementClarificationTrigger`): force full-receipt fetch for V3 exits with `decreaseLiquidity` (`0x0c49ccbe`) + `collect` (`0xfc6f7865`) in calldata (logs not persisted today).
- `LpExitFeeDecomposer`: decode `DecreaseLiquidity` (principal) + `Collect` (total) from `persistedLogs`; `fee = collect − decrease` per token, mapped to inbound legs by token address.
- Flow split at classification (`LpNftClFlowMaterializer`): each inbound principal `TRANSFER` → principal leg (qty = principal) + fee-income leg (qty = fee, zero-cost tagged). Drop principal leg when principal = 0 (fee-only asset).
- **Also unblocks single-token rebalancing:** removing the fee-only leg takes that asset out of `directlyReturnedIdentities`, so the sibling cross-asset drain fires (Option B carry).

### R4 — Uniswap V4 / Pancake Infinity CL exit fee reader (classification + pricing)
- **Scope (DB):** 12 Uniswap V4 (UNICHAIN, NFPM `0x4529a01c…`, ETH/wstETH/WBTC/USD₮0/USDC — **volatile**) + 1 PancakeSwap V4 (UNICHAIN) + 2 Pancake **Infinity** CL (BSC, NFPM `0x55f4c8ab…`, single-token XYZ). **Correlation already works** (NFPM `tokenId` → `LP-RECEIPT` + basis pool exist) — unlike R6a/R7, R4 needs **no** correlation change; the gap is purely the **fee/principal split** + R3 net-lane.
- **Docs-verified event:** `ModifyLiquidity(PoolId indexed id, address indexed sender, int24 tickLower, int24 tickUpper, int256 liquidityDelta, bytes32 salt)` emits **no token amounts**; `feesAccrued` exists only as a `modifyLiquidity` **return value, not in logs** → principal must be derived. Refs: [v4-core IPoolManager](https://github.com/Uniswap/v4-core/blob/46c68346/src/interfaces/IPoolManager.sol), [Navigating V4 data](https://paragraph.com/@uniswap-foundation/how-to-navigate-uniswap-v4-data). Pancake **Infinity** is the same Vault + PoolManager + Hooks CLAMM architecture → same reader, parameterized per `(protocol, network)` for PoolManager/StateView addresses. Ref: [Pancake Infinity overview](https://developer.pancakeswap.finance/contracts/infinity/overview).
- New **`platform.networks.evm.rpc.V4PoolStateReader`** (NOT an extension of `liquiditypools.enrichment.LpRpcSupport` — that would create the first `normalization → liquiditypools.enrichment` edge and reuse a `"latest"`-tagged path): block-tagged, fail-safe, EVK precedent. Resolve `poolId` from `ModifyLiquidity` (topic1); read `sqrtPriceX96` via block-tagged `eth_call` to `PoolManager`/StateView `getSlot0(poolId)` at exit block. **[arch revision]**
- **Relocate** pure CL math `LpLiquidityAmountsSupport` out of `liquiditypools.enrichment` into a shared support package so classification uses it without importing the enrichment module. Add ArchTest rule: `normalization..` must not depend on `..liquiditypools.enrichment..`. **[arch revision]**
- **Durable block-pinned Mongo cache** `v4_pool_state_cache` keyed `(networkId, poolId, blockNumber) → sqrtPriceX96`, **write-once, including an explicit `UNRESOLVED` marker** (pattern: `historical_prices` / `lp_pool_depth_cache`). First renormalization populates; subsequent rebuilds read Mongo, never re-hitting archive RPC. Caching the unresolved outcome is required so a rebuild during archive-node downtime that baked `fee=0` is not silently overwritten by a later rebuild that reaches the archive node (byte-stable split across rebuilds); re-resolution only via explicit cache-clear. **[arch RR-5]**
- `LpV4ExitFeeDecomposer`: principal via `LpLiquidityAmountsSupport.getAmountsForLiquidity(sqrtPrice, ticks, |liquidityDelta|)` — three CL cases (price below range → all token0; above → all token1; in-range → both).
  - **Conservation-safe clamp [financial revision, blocking]:** cap principal at received — `principal = min(computedPrincipal, received)`, `fee = max(received − computedPrincipal, 0)` **per token**. Clamping only the fee (as the earlier draft said) would leave `principal_qty > received_qty` and **fabricate principal basis**, violating the `principal + fee qty == received qty` invariant.
  - **Out-of-range (single token): no price needed** — principal computable from `liquidityDelta` + ticks alone (e.g. both BSC Infinity XYZ exits).
  - **In-range (dual token): needs historical `sqrtPriceX96`** (e.g. UNICHAIN 42775 = USD₮0+ETH, 44515 = wstETH+ETH); fallback `principal = received` (`fee = 0`) — never fabricate income. Emit observable low-severity flag **`V4_FEE_SPLIT_UNRESOLVED`** so the degradation is not silent. **[BA + arch revision]**
- **Partial exits:** `tokenId` 44472 (×2) and 53290 (×2) each decrease then finalize — every partial `ModifyLiquidity` inherits the same split; proportional pool model unchanged.
- **Numeric ground truth for acceptance [BA revision]:** pin at least one in-range (e.g. 42775 → USD₮0 924.079939 + ETH 0.688511354; principal vs fee to be reconstructed from `liquidityDelta`+`sqrtPrice` before sign-off) and one out-of-range (BSC Infinity XYZ) case with exact principal/fee numbers in the auditor reconstruction before implementation acceptance.
- **Materiality:** high — ETH-denominated dual-token in-range exits; V4 net-lane dangling ≈ $983 on UNICHAIN, part of the cross-family $3,422 (see R3). R3 **must** land before/with R4.

### R2 — Cross-asset drain residual for dual-token exits (cost_basis/replay)
- In `PositionScopedLpExitReplayHandler.restoreInboundFromLpReceiptPool`: after each asset withdraws its own pool, re-allocate any un-drained sibling-pool basis onto returned principal so combined LP basis is fully conserved.
- **Peg-cap gating [financial revision, blocking]:** in the dual-token (B2) case, the stablecoin principal leg must stay **peg-capped in both lanes** (`min(qty×$1, availableBasis)`); do NOT suppress the peg cap via the existing `crossAssetBasisCarried` flag when a volatile principal sibling is also returned — otherwise USDC drifts off peg and the volatile leg is starved. The volatile leg absorbs the remainder.
- **Negative-remainder clamp [financial revision, blocking]:** when returned stablecoin face value exceeds combined basis, use `min(qty×$1, combinedBasis)` for the stablecoin and floor the volatile leg at $0.
- **All-stablecoin multi-token exits [financial revision, blocking]:** R6a (3 stables) and R7 (2 stables) have **no volatile absorber**, so "peg-cap-all-at-$1" fabricates basis when Σface > combinedBasis (R6a ≈ $5.85 of compounded yield). Rule: per-lane cap = `min(qty×$1, thatLane'sAvailableBasis)` and distribute the remainder so **Σ carried = combinedBasis exactly** — no fabrication, no destruction.
- **Double-count guard [financial revision]:** perform the sibling re-allocation as a single post-pass so basis is not carried twice.
- **net ≤ tax invariant [financial revision]:** apply symmetric net-lane capping so carried net basis never exceeds tax basis.
- No principal ever booked as market `ACQUIRE`.

### R3 — Carry + zero the net lane at pool drain, ALL networks (cost_basis/replay)
- In `drainAllLpReceiptPoolsForCorrelation`: **carry** the net (reward-discounted) lane to the exit asset **before** zeroing, then set `netBasisHeldUsd = 0` (ordering matters). **[financial revision]**
- **Scope correction [financial revision]:** this is **not** a V4/Unichain-only issue. Measured dangling `netBasisHeldUsd` on fully-drained pools (`qtyHeld=0`, tax=0) is **$3,422.22 across 24 pools on 5 networks** — the single largest $ correction in the plan:

| network | family | pools | dangling net |
|---|---|---|---|
| BASE | V3 | 7 | $1,511.91 (incl. 477096 USDC $1,159.20) |
| UNICHAIN | V4 | 8 | $983.03 |
| OPTIMISM | Slipstream | 4 | $609.09 |
| ARBITRUM | V3 | 3 | $160.64 |
| ETHEREUM | V3 | 2 | $157.54 |
| **total** | | **24** | **$3,422.22** |

- **Joint conservation test [financial revision]:** R2 carries the tax lane, R3 the net lane — add a per-correlation assertion that on every resulting asset carried net ≤ carried tax and neither lane is double-counted.

### Idempotent leg split (R1/R4 materialization) — **[arch revision, blocking]**
- The split must **consume/replace** the raw inbound full-received leg, not co-exist with it. `LpNftClFlowMaterializer` currently merges raw legs via `putIfAbsent` keyed by `contract|symbol|role|quantity`; define a deterministic transformation that (a) removes the raw inbound principal leg, (b) emits exactly principal (`qty=principal`) + fee (`qty=fee`, zero-cost tagged) legs with a stable marker (role/metadata) so re-runs are byte-stable, (c) drops the principal leg when `principal=0`.
- **WETH↔ETH identity mapping [BA revision]:** map native-ETH received legs to the WETH amounts in `Collect`/`DecreaseLiquidity` before diffing, else fee mapping misses.

### Shared replay booking
- `PositionScopedLpExitReplayHandler.applySettlement`: book fee-income leg as zero-cost `ACQUIRE` via `applyBuyWithAcquisitionCost(feeFlow, position, ZERO, REWARD_CLAIM)` (already `isZeroCostAcquisition`), ledger `ACQUIRE`. Principal leg flows through existing pool-restore + (R2-fixed) cross-asset drain.

### Reporting decision (product) — **DECIDED: keep implicit in AVCO**
- Fee income (V3/Slipstream/V4 fee legs, compounding via Pendle/Balancer/LFJ, lending interest via Aave) realizes only on later sale. **No `LpEarningPoint` at exit for now** — all families stay implicit in AVCO. Flag override/move-basis rows for re-review after full replay. Revisit surfacing explicit income separately once the pipeline is stable.

### R5 — Pendle / compounding (verify + docs)
- Verify reward tokens (PENDLE) book zero-cost; document that compounding-receipt swap-fee accrual is intentionally carried in principal and realized via AVCO on later sale. Optional cosmetic: relabel Katana 36201 exit leg `UNKNOWN → REALLOCATE_IN` (D7).

### R6 — "Aave"-labelled exits misclassified (classification/protocol-resolution)

The 5 null-correlation `protocolName=Aave` `LP_EXIT`s are **two different protocols**, both mis-resolved because a `HEURISTIC` set `protocolName=Aave` (from the token name) with `protocolResolutionState=TERMINAL_METADATA_ONLY`, then a generic LP heuristic mislabelled type/direction. Split into R6a + R6b. **Note:** R6a's 4 mis-typed `LP_EXIT`s are only part of a **7-tx lifecycle** — the other 3 (stakes/Aura unstake) are currently `LENDING_DEPOSIT`/`REWARD_CLAIM` (outside the 60 LP-exit count) and must be fixed together or BPT basis continuity breaks.

#### R6a — Balancer **V3** boosted-stable pool + gauge + **Aura**, not Aave (7-tx lifecycle, AVALANCHE) — **[financial revision, blocking: was under-scoped to 4]**
- **On-chain verified:** the BPT `Aave GHO/USDT/USDC` (`0xfcec3c8d…`) is a Balancer V3 **boosted-stable BPT** (wraps Aave aTokens → "Aave" in the name) minted/burned via **Balancer V3 Vault `0xba1333333333a1ba1108e8412f11850a5c319ba9`**. The full position lifecycle is **7 transactions, not 4**; the 3 missing ones sever BPT basis continuity and **fabricate ~$43 basis**:

| tx | real action | current type / proto | required |
|---|---|---|---|
| `0x983f…748fc2d` | **JOIN** stables → BPT +2144.92 | `LP_EXIT` / Aave (dir inversion) | `LP_ENTRY` / Balancer |
| `0x13d0…42f1` | **STAKE** BPT −2144.92 → gauge | `LENDING_DEPOSIT` / **Curve** | `LP_POSITION_STAKE` (basis-neutral) |
| `0x3993…b97ee2` | **gauge UNSTAKE** → BPT + `BAL` | `LP_EXIT` / Aave | `LP_POSITION_UNSTAKE` (+ BAL zero-cost) |
| `0xe84d…943da4` | **BURN** BPT −2102.03 → 3 stables | `LP_EXIT` / Aave | `LP_EXIT` / Balancer |
| `0xcbfe…30c8` | **STAKE** BPT −42.90 → **Aura vault** | `LENDING_DEPOSIT` / Aura | `LP_POSITION_STAKE` (basis-neutral) |
| `0x2447…f11e` | **Aura UNSTAKE** → BPT +42.90 + BAL/AURA/WAVAX | `REWARD_CLAIM` / Aura (**fabricates ~$43 BPT basis**) | `LP_POSITION_UNSTAKE` (BPT basis-neutral; BAL/AURA/WAVAX zero-cost) |
| `0xdf5c…663d4` | **BURN** BPT −42.90 → 3 stables | `LP_EXIT` / Aave | `LP_EXIT` / Balancer |

- **Root cause:** (1) protocol mis-resolution (BPT name → Aave; gauge stake → Curve; Aura recognized only by FUNCTION_NAME); (2) BPT is the **pool address** (Balancer V3 `ERC20MultiToken`, ERC-20-transferable, no `tokenId`) → NFPM correlation null; (3) **stake/unstake mislabelled `LENDING_DEPOSIT`/`REWARD_CLAIM`** → BPT leaves via lending and returns as zero-cost income, breaking join→…→burn continuity and injecting phantom basis.
- **Fee model (docs-verified):** Balancer V3 boosted pools compound **two** yield streams into BPT value (swap fees + underlying Aave lending yield on ERC-4626 stata stables), no separate Collect → COMPOUNDING (Option-B carry, no split). Refs: [Balancer V3 Boosted Pools](https://docs.balancer.fi/integration-guides/aggregators/boosted-pools.html), [Balancer V3 overview](https://medium.com/balancer-protocol/balancer-v3-the-future-of-amm-innovation-f8f856040122).
- **Fix:** (1) resolve **Balancer V3** by Vault `0xba1333…` (not token name); (2) add **Aura Finance** (`auraVault`) as a recognized protocol; (3) reclassify both stakes `LENDING_DEPOSIT → LP_POSITION_STAKE` and the Aura unstake `REWARD_CLAIM → LP_POSITION_UNSTAKE` as **basis-neutral moves (no zero-cost income on the BPT)**; BAL/AURA/WAVAX rewards zero-cost; (4) pool-level correlation (see A1/RR-1 canonical 3-segment `lp-position:<net>:balancerv3:<poolAddress>`).
- **Materiality:** stables ≈ $1 so terminal P&L ≈ $0, **but** the fabricated ~$43 BPT basis + broken continuity make R6a's own conservation unreachable until the stake/unstake legs are fixed.
- **Acceptance:** all 7 tx resolve to Balancer/Aura with correct types; BPT basis flows join→stake→unstake→burn **without break or fabrication**; combined basis conserved (≈$0 P&L, stable pool); no `REWARD_CLAIM`/`LENDING_DEPOSIT` on BPT legs.

#### R6b — Aave **V3 lending** withdrawal mislabelled `LP_EXIT` (1 ZKSYNC)
- **On-chain verified:** `0x4d1a…b30514` emits Aave V3 `Withdraw` (aZksZK `0xd6cd2c…` → ZK `0x5a7d6b…`, 159.62). Pure lending redemption.
- **Docs-verified model:** aTokens are **rebasing** (`balanceOf = scaledBalance × liquidityIndex`); `withdraw` burns aTokens → underlying = **supplied principal + accrued interest**. Ref: [Aave V3 Tokenization](https://aave.com/docs/aave-v3/smart-contracts/tokenization). **Accounting [financial revision]:** under AVCO (consistent with the fee treatment) accrued interest is **zero-cost income realized on later sale**, NOT an immediate FMV basis step-up — drop the "step-up" wording. In this specific tx aZksZK burned 160.02 → ZK 159.62 (a **partial** withdraw; a later dust withdraw `0x4f77…` closes it), so interest ≈ break-even here — apply a `max(withdrawn − suppliedPrincipal, 0)` floor so no negative/fabricated income.
- **Root cause = weak protocol resolution:** the identical Aave withdraw on `0x4f77…2114b` (2026-01-22) is **correctly** `LENDING_WITHDRAW` via `PROTOCOL_REGISTRY`; this one fell to `HEURISTIC/TERMINAL_METADATA_ONLY` → generic `LP_EXIT`. Its paired supply (`0x7e5a…08be`, +160.02 aZksZK) is also mislabelled `REWARD_CLAIM` (**zero-cost**), so the ZK basis chain is distorted before ZK is transferred out to Bybit (`0x7046…322c`).
- **Fix:** (1) strengthen Aave-on-zkSync protocol registry so aToken supply/withdraw route to the lending pipeline (`LENDING_SUPPLY`/`LENDING_WITHDRAW`), carrying supplied-principal basis (not zero-cost reward, not LP); (2) **verify the existing lending replay recognizes accrued interest as zero-cost income** — analogous to the R1 fee split; if not, add `max(withdrawn − suppliedPrincipal, 0)` = zero-cost income realized on sale.
- **Materiality:** ZK is volatile (~$0.032–0.041 × 160 ≈ $5–6); the zero-cost supply → withdraw → external-transfer chain mis-anchors ZK basis carried to Bybit.
- **Acceptance:** `b30514` typed `LENDING_WITHDRAW`; the paired supply typed `LENDING_SUPPLY` (not zero-cost); ZK principal basis at withdrawal = supplied ZK basis; interest booked zero-cost (break-even here); correct basis carries on the subsequent external transfer.

### R7 — LFJ / Trader Joe Liquidity Book untracked (classification + correlation)
- **Evidence (DB):** 4 exits + 5 entries on AVALANCHE, pair **AUSD/USDC** (`0x8573f98175d816d520248b5facf40d309b1c9cee`), `protocolName=LFJ`, `classifiedBy=FUNCTION_NAME`, `protocolResolutionState=RESOLVED_EXACT`, `correlationId=null` on **both entry and exit** → **0 AVALANCHE `lp_receipt_basis_pool`** → LFJ principal is untracked.
- **On-chain verified (Avalanche receipts):** entry emits ERC-1155 `TransferBatch` mint (`0x4a39dc06…`, from `0x0`→user, ids=bin IDs, amounts=liquidity shares) + `DepositedToBins` (`0x87f1f9dc…`); exit emits `TransferBatch` burn (user→`0x0`) + `WithdrawnFromBins` (`0xa32e1468…`). ERC-20 legs are the underlying token in/out.
- **Root cause (the real bug):** LB positions are **fungible per bin (ERC-1155 `LBToken`)**, not Uniswap-V3 NFPM NFTs with a single `tokenId`. The `lp-position:<net>:<contract>:<tokenId>` builder can't key them → null correlation, and the ERC-1155 receipt is **not captured as an LP-RECEIPT flow** → no basis pool. Entry/exit never link.
- **Fee model = DEFERRED BUNDLED-FEE SPLIT (Option-B carry now), NOT true compounding — docs-verified [financial revision]:** LB **v2.1** fees are "added to existing positions and **held in reserve** … claimed **when withdrawing**" — i.e. bundled-claimable-on-withdraw (like V3 `Collect`), not compounded into liquidity. `WithdrawnFromBins` already includes them; LB has **no `Collect`** and there are **0 `LP_FEE_CLAIM` for LFJ**. For this **stable AUSD/USDC** pair the Option-B carry (no split) is conservation-safe and fine, so ship it that way; a precise per-bin split (bin shares×price vs withdrawn) is **deferred** (negligible materiality, costly per-bin reconstruction). Keep an explicit **volatile-pair revisit/quarantine flag**. Refs: [LFJ Fees](https://developers.lfj.gg/concepts/fees), [LFJ Liquidity Book](https://docs.lfj.gg/).
- **Fix:**
  1. **Correlation:** pair-level, canonical **3-segment** key `lp-position:<net>:lfj:<lbPair>` (pool address in the tokenId slot — see A1/RR-1; a 2-segment key breaks `LpReceiptSymbolSupport`/`LpPositionRefreshService`). **Detection must be offline [arch RR-5]:** persisted log signatures `DepositedToBins`/`WithdrawnFromBins`/LB `TransferBatch` + static factory list, never a live factory `eth_call`. All entries/exits into a pair aggregate into **one** `lp_receipt_basis_pool`.
  2. **Receipt anchor:** capture the ERC-1155 `LBToken` `TransferBatch` as an LP-RECEIPT flow (sum of shares across bins) via the new multi-standard receipt materializer (A2/RR-2). Needs full-receipt fetch (logs=0 today) + ERC-1155 batch decoding → generalized LP clarification trigger (A3/RR-6).
- **Materiality (this wallet):** AUSD/USDC is **stable/stable**; total deposited ≈ $7,669, withdrawn ≈ $7,672, net ≈ **$2.63**. Both legs ≈ $1 → current AVCO distortion is negligible here. R7 is required for **correctness/coverage** and for **volatile LB pairs** (where the missing entry-basis pool would mis-realize deposits and strand basis).
- **Acceptance:** LFJ entries/exits correlate into one pair-level basis pool; deposits do not realize a disposal; exits book returned tokens as return-of-capital at carried combined basis (Option B); combined LFJ basis conserved (≈$0 P&L for this stable pair).
- **Note:** R7 is a new AMM family. If deferred, LFJ must be explicitly **flagged/quarantined**, not silently dropped.

## Architecture seams (cross-cutting) — **[arch RR-1..RR-7]**

The expanded scope introduces a **fungible / pool-level position-identity model** (Balancer V3 ERC-20 BPT, LFJ ERC-1155 bins) that the current 3-segment `lp-position:` key + NFT-mint-gated materializer + ad-hoc per-family correlation cannot absorb. Add these seams (all inside `normalization..classification.lp`; replay stays pure):

- **A1 (RR-1) Canonical correlation identity — BLOCKING:** keep the **strict 3-segment** `lp-position:<net>:<protocol>:<idOrPool>` invariant. Pair-level families use the pool address in the id slot (`balancerv3` / `lfj`). Must round-trip through `LpReceiptSymbolSupport` **and** `LpPositionRefreshService` (today `parts.length>=4`/`parts[2]`=NFPM assumptions break otherwise).
- **A2 (RR-2) Multi-standard receipt materializer — BLOCKING:** `LpNftClFlowMaterializer.enrichEntry` is gated on `hasPositionNftMintLog` (ERC-721 only). Add a materializer seam keyed by receipt-token standard: (a) ERC-721 position mint (existing), (b) ERC-1155 batch mint/burn (LFJ, sum shares across bins), (c) ERC-20 BPT mint/burn (Balancer V3) — all emitting the same `LpReceiptSymbolSupport` identity. Note the impact on `ReplayExecutionState.lpPositionReceiptLifecycle` (many mints/burns aggregated into one pool changes lifecycle-count semantics).
- **A3 (RR-3) Correlation strategy SPI:** replace the ≥6 divergent builders with a pluggable `LpCorrelationStrategy` (`(view, movementLegs, protocolHint) → correlationId`) per family (V3/Slipstream, V4/Infinity, Balancer V3, LFJ, Pendle, GMX), all emitting the A1 identity.
- **A3 (RR-6) One generalized `LpReceiptClarificationTrigger`:** family-parameterized (selector/topic sets from the strategy), idempotent in one place, covering **ENTRY + EXIT** (R6a JOIN, R7/R6a mints need full receipts too — current trigger is EXIT-only).
- **A4 (RR-7) ArchTests:** `normalization.. ⇏ ..liquiditypools.enrichment..` **and** `..costbasis..(replay) ⇏ ..platform.networks.evm.rpc..` (guarantee the V4 reader runs at classification/enrichment + is persisted, never at replay).

## Docs

- `docs/pipeline/cost-basis/03-basis-pools-and-carry.md` — extend ADR-022 exit attribution: fee split + dual-token cross-asset carry + net-lane carry+zero (all networks).
- `docs/pipeline/normalization/rules/` — LP exit fee-income leg materialization (V3/Slipstream + V4) + multi-standard receipt materialization.
- **Three ADRs required (all need explicit user approval) [arch revision]:**
  1. ADR — "LP exit: fee income vs principal return of capital (Option B)" (extends ADR-022 per-asset attribution, ADR-040 net/tax lanes; records dual-token cross-asset carry + net-lane carry+zero + all-stablecoin per-lane cap).
  2. ADR — "V4/CL historical pool-state read at classification + reproducibility cache" (archive-RPC dependency in normalization, `platform` reader placement, write-once block-pinned cache incl. `UNRESOLVED`, `fee=0`/out-of-range fallback contract).
  3. ADR — "Venue-agnostic LP position correlation & multi-standard receipt materialization" (A1–A3: 3-segment identity invariant, correlation-strategy SPI, receipt-materializer standard seam ERC-721/1155/ERC-20-BPT, fungible pool-level aggregation into one basis pool + its replay lifecycle-count impact).

## Acceptance

- **477096:** ETH `REALLOCATE_IN` qty 0.543429281 basis $2,083.434 (no market `ACQUIRE`); fee legs 0.00285 ETH + 9.948876 USDC each `costBasisDeltaUsd = 0`; all pools `qtyHeld = basisHeldUsd = netBasisHeldUsd = 0`.
- **72791605:** combined principal basis $593.919 fully carried; USDC principal avco $1.000 (peg-capped both lanes), WETH absorbs remainder (~$1,732); fee legs zero-cost; no non-zero pool residual (tax or net).
- **Katana 36201:** stays $1,705.995 on vbETH (reference case, unchanged).
- **V4 [BA revision]:** the pinned in-range (42775) + out-of-range (BSC Infinity XYZ) cases match numeric principal/fee ground truth; `principal = min(computed, received)` holds; out-of-range needs no price fetch; `V4_FEE_SPLIT_UNRESOLVED` count reported.
- **Partial-exit conservation [financial + BA revision]:** for V4 partials (44472/53290), sum of per-exit principal basis + remaining pool basis == total LP basis in.
- **Aggregate V3-family DoD [BA revision]:** across all 33 V3-family exits (incl. 13 Slipstream), portfolio basis conservation holds within tolerance **±$0.01/asset** (rounding); no exit leaves a non-zero drained-pool residual.
- **R3 net-lane, all networks [financial revision]:** after full replay, **every** drained pool on all 5 networks has `netBasisHeldUsd = 0`; the $3,422.22 across 24 pools is carried onto exit assets, not destroyed; joint per-correlation test `carried net ≤ carried tax`.
- **R6a (Balancer/Aura):** all 7 tx correctly typed (JOIN=ENTRY, stakes=STAKE, unstakes=UNSTAKE, burns=EXIT); Aura recognized; **no fabricated BPT basis** (join 2144.92 basis == burns 2102.03+42.90); BAL/AURA/WAVAX zero-cost.
- **R6b (Aave):** `b30514`=`LENDING_WITHDRAW`, paired supply=`LENDING_SUPPLY` (not zero-cost); interest zero-cost income (break-even here); ZK basis carries to Bybit.
- **R7 (LFJ):** entries/exits correlate into one 3-segment basis pool; deposits don't realize disposal; Option-B carry; combined basis conserved (net ≈ $2.63). If deferred, LFJ explicitly quarantined (flagged).
- **Observability flags (named) [BA revision]:** `V4_FEE_SPLIT_UNRESOLVED` (in-range no historical price), `LP_FEE_CLAMPED` (V4 `computedPrincipal > received`), `LP_BASIS_CONSERVATION_BREACH` (Σcarried ≠ combinedBasis beyond tolerance).
- **Invariants:** net ≤ tax basis after every exit; qty conservation (`principal + fee qty == received qty` per token); Σ carried basis == combinedBasis (all-stable exits).
- Portfolio basis conservation: LP basis in == principal basis out + $0 income, per position.
- Auditor sign-off re-run reconciles DB vs authoritative reconstruction (artifacts: `results/lp-exit-rebalancing-*.md`).

## Risks

- **V4 historical state (R4):** archive-node availability for block-tagged `getSlot0`; mitigated by out-of-range no-price path + write-once `UNRESOLVED`-cached `fee = 0` fallback.
- **Migration:** requires full renormalization + replay (`--skip-frontend`); LP pools rebuilt.
- **Ordering dependency:** P1→P8 table above. R1 before/with R2; R3 pairs with R2; R4 needs R3; R6a needs R1+R2 (stake/unstake continuity).
- **New position-identity model (A1–A3):** fungible pool-level positions (Balancer V3, LFJ) touch shared correlation/receipt/lifecycle contracts — highest integration risk; gated by ArchTests (A4) + ADR-3.
- **Phasing:** all blockers R1–R7 + A1–A4 are **in scope**. Delivery order per P1–P8 table. R7 is in scope (not deferred); if A1–A3 seams slip, R7 is quarantined with an explicit flag — not silently dropped.

## Rebuild

`./scripts/prod-reset-rebuild-backend.sh --skip-frontend` after each landed blocker (normalization + replay change).
