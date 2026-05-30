# ETH AVCO — PancakeSwap MasterChef Harvest Fix Plan

**Slug**: `avco-eth-masterchef-harvest-fix`  
**Date**: 2026-05-29  
**Audit source**: `results/avco-eth-harvest-fix-audit/`  
**Status**: Revised (post Phase 3 review)

---

## Scope

- **Wallets**: `0xf03b52e8686b962e051a6075a06b96cb8a663021` (Arbitrum), `0xf03b52e8686b962e051a6075a06b96cb8a663021` (BASE)
- **Networks**: Arbitrum, BASE
- **Protocol**: PancakeSwap V3 MasterChef
- **Assets**: ETH family (ETH/WETH as LP principals); CAKE as reward
- **Blocker ID**: P-A (MasterChef `withdraw(tokenId, address)` direct call)

---

## Root Cause

### Blocker P-A — MasterChef `withdraw` classified as LP_EXIT

`LpPrincipalCloseEvidence.refineLifecycleType()` uses `BURN_SELECTOR = "0x00f714ce"` as evidence of position reduction in `hasPositionReductionEvidence()`. This selector is the `methodId` for **two different functions** on PancakeSwap:

| Contract | Function | Selector | Action |
|----------|----------|----------|--------|
| Uniswap V3 NPM (Position Manager) | `burn(uint256 tokenId)` | `0x00f714ce` | Destroys the position NFT — genuine LP principal exit |
| PancakeSwap V3 MasterChef | `withdraw(uint256 tokenId, address to)` | `0x00f714ce` | Unstakes NFT from farm, distributes CAKE rewards, returns NFT to `to` — **no LP liquidity change** |

The B4 fix added:
```
isHarvestOnlyRewardPattern(movementLegs) && hasZeroLiquidityDecrease(view)
```
This handles `multicall(decreaseLiquidity(liquidity=0) + collect)` on the NPM. It does **not** handle the MasterChef `withdraw` because there is **no `decreaseLiquidity` call at all** in its calldata, making `hasZeroLiquidityDecrease` return `false`. The transaction stays `LP_EXIT`, burning the LP-RECEIPT prematurely.

**Effect**: The real LP_EXIT (the subsequent actual principal removal via `decreaseLiquidity`) finds 0 LP-RECEIPT balance → all principal inflows get `basisEffect=UNKNOWN`.

### ERC721 transfer log as the safe discriminator

The two functions emit **opposite** ERC721 Transfer events:
- **NPM `burn(tokenId)`**: emits Transfer(from=**wallet**, to=0x0) — NFT is destroyed; `from` is the wallet.
- **MasterChef `withdraw(tokenId, to)`**: emits Transfer(from=**MasterChef**, to=**wallet**) — NFT is returned to wallet; `to` is the wallet.

`LpPositionLifecycleSupport.hasAnyErc721TransferToWallet(view)` returns `true` when the wallet is in the `to` position (topics[2]) of any ERC721 Transfer log. This is:
- `true` for MasterChef `withdraw` → NFT returned to wallet
- `false` for NPM `burn` → NFT destroyed, not sent to wallet

This is the correct and minimal disambiguator. It does not require maintaining a contract address list.

### Affected positions

| Position | MasterChef harvest TX (wrong LP_EXIT) | Real LP_EXIT (UNKNOWN basis) | Missed basis |
|----------|---------------------------------------|------------------------------|-------------|
| ARB:PANCAKESWAP:196975 | `0xc300e72cd4fd6f96…` | `0x293cf2289fcbf131…` | ~$1,361 |
| ARB:PANCAKESWAP:204401 | `0x92c403b5f26851dc…` | `0xe63ce6a88ebc1c03…` | ~$1,141 |
| ARB:PANCAKESWAP:218217 | `0x728a33b5dce9c07e…` | `0x790636e7cd91dbb0…` | ~$140 |
| BASE:PANCAKESWAP:445831 | `0x3ed9b9f7bd16edde…` | `0x457b9d30ca278881…` | ~$78 |

**Total recoverable**: ~$2,720 ETH-family basis  
**Stage**: classification (`LpPrincipalCloseEvidence`)  
**Evidence state**: `EVIDENCE_PRESENT_UNUSABLE`

---

## Out-of-Scope in This Plan

| Pattern | Reason |
|---------|--------|
| **P-B** — Uniswap LP_ENTRY with no ETH cost basis (~$3,024) | Upstream ETH acquisition investigation for wallet `0x68bc3b81`. Classification is correct. The null-AVCO CARRY_IN at Arbitrum (`0x6ac6fc60`) precedes any LP classification issue. Not a classification defect. The MasterChef fix does NOT transitively recover P-B: the affected wallet (`0xf03b52e`) does not have null-AVCO ETH chains — its LP_ENTRY basis was correctly tracked. |
| **P-C** — Missing LP_ENTRY for Unichain positions (~$3,186) | `GENUINE_EVIDENCE_MISSING_PROVEN` — NFT transfer → synthetic LP_ENTRY is a new pipeline feature |
| **BSC-1** — BSC 643922 two exits from one receipt | Non-ETH family; partial-exit LP-RECEIPT model gap, separate concern |

---

## Changes

### P0 — `LpPrincipalCloseEvidence.java`

**File**: `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/support/LpPrincipalCloseEvidence.java`

Add a new OR-branch in `refineLifecycleType()` after the existing B4 condition:

```java
// MasterChef withdraw(tokenId, to) — direct 0x00f714ce call on farm contract.
// Unstakes NFT from farm and distributes CAKE rewards; LP principal stays in pool.
// Discriminated from NPM burn() by ERC721 Transfer direction: MasterChef returns
// the NFT to wallet (Transfer(MasterChef→wallet)), whereas NPM burn destroys it
// (Transfer(wallet→0x0)) — so hasAnyErc721TransferToWallet is true only for harvest.
if (isHarvestOnlyRewardPattern(movementLegs) && isMasterChefWithdrawDirectCall(view)) {
    return NormalizedTransactionType.LP_FEE_CLAIM;
}
```

New private method:

```java
private static boolean isMasterChefWithdrawDirectCall(OnChainRawTransactionView view) {
    if (view == null) return false;
    String methodId = normalizeSelector(view.methodId());
    if (!BURN_SELECTOR.equals(methodId)) return false;
    // NPM burn(tokenId) and MasterChef withdraw(tokenId, to) share selector 0x00f714ce.
    // NPM burn emits Transfer(wallet→0x0); MasterChef withdraw returns NFT to caller:
    // Transfer(MasterChef→wallet). hasAnyErc721TransferToWallet is true only for the latter.
    if (!LpPositionLifecycleSupport.hasAnyErc721TransferToWallet(view)) return false;
    // Guard: if calldata also embeds decreaseLiquidity, this is a real principal exit
    String inputData = view.inputData();
    return inputData == null
        || !CalldataDecodingSupport.containsEmbeddedSelector(inputData, DECREASE_LIQUIDITY_SELECTOR);
}
```

**Existing test to update**: `cakeOnlyInflows_burnSelectorOnlyNoDecreaseLiquidity_staysLpExit` in `LpPrincipalCloseEvidenceTest`. This test uses a minimal mock view with no ERC721 Transfer logs. With the new guard `hasAnyErc721TransferToWallet(view)` returning `false` (no logs), `isMasterChefWithdrawDirectCall` returns `false`, so the type correctly stays `LP_EXIT`. The test assertion does not need to change — but the test comment must be updated to explain that the bare-selector case with no ERC721 logs represents an NPM burn, not a MasterChef harvest.

**Required new unit tests** for `isMasterChefWithdrawDirectCall`:

| # | Scenario | Expected |
|---|----------|----------|
| T1 | `methodId=0x00f714ce`, no inputData, CAKE-only inflows, ERC721 Transfer(MasterChef→wallet) in logs | `LP_FEE_CLAIM` |
| T2 | `methodId=0x00f714ce`, inputData contains embedded `0x0c49ccbe` (decreaseLiquidity), CAKE-only inflows, ERC721 Transfer(MasterChef→wallet) in logs | `LP_EXIT` (decreaseLiquidity guard fires) |
| T3 | `methodId=0xac9650d8` (multicall outer), `0x00f714ce` embedded in inputData, CAKE-only inflows, ERC721 Transfer(MasterChef→wallet) | `LP_EXIT` (top-level methodId does not match `BURN_SELECTOR`) |
| T4 | `methodId=0x00f714ce`, no inputData, CAKE + WETH inflows, ERC721 Transfer(MasterChef→wallet) | `LP_EXIT` (`isHarvestOnlyRewardPattern` false — WETH is a principal token) |
| T5 | `methodId=0x00f714ce`, no inputData, CAKE-only inflows, no ERC721 logs (NPM burn shape) | `LP_EXIT` (`hasAnyErc721TransferToWallet` false — existing test scenario, no change to assertion needed) |

### Documentation updates

1. **`docs/03-accounting.md`** — extend the "Concentrated-liquidity harvest-only gate" rule:  
   Add a paragraph for the MasterChef `withdraw(tokenId, to)` pattern as a second recognized harvest-only variant. Distinguish it from `multicall(decreaseLiquidity(0)+collect)`: MasterChef withdraw is identified by `methodId=0x00f714ce` + ERC721 Transfer(MasterChef→wallet) + CAKE-only inflows.

2. **`docs/adr/ADR-021-...md`** — add an amendment section:  
   Record that the B4 harvest gate has a second branch for MasterChef direct `withdraw` calls, added 2026-05-29 (P-A). Note the shared selector and the ERC721 log direction as the discriminator. Note that `BURN_SELECTOR` in the code serves dual purpose: NPM burn detection AND MasterChef withdraw detection, with logs as the tie-breaker.

3. **`docs/adr/INDEX.md`** — update ADR-021 status to `Accepted (amended)`.

---

## Acceptance Criteria

After fix, rebuild (`--skip-frontend`), and replay:

| Check | Query / Verification |
|-------|---------------------|
| **A1** — Harvest TXs reclassified | `db.normalized_transactions.find({"txHash": {$in: ["0xc300e72c...", "0x92c403b5...", "0x728a33b5...", "0x3ed9b9f7..."]}}, {type:1})` → all `LP_FEE_CLAIM` |
| **A2** — LP-RECEIPT not consumed by harvest | After harvest TX: `db.asset_ledger_points.find({assetSymbol: {$regex: "PANCAKESWAP:(196975|204401|218217|445831)"}, normalizedType: "LP_FEE_CLAIM"}, {quantityAfter:1})` → all `quantityAfter = 1` |
| **A2b** — LP-RECEIPT consumed by real LP_EXIT | After real LP_EXIT: same query for `normalizedType: "LP_EXIT"` → all `quantityAfter = 0` |
| **A3** — Real LP_EXIT gets basis | `db.asset_ledger_points.find({normalizedTransactionId: {$regex: "0x293cf228|0xe63ce6a8|0x790636e7|0x457b9d30"}, accountingFamilyIdentity: "FAMILY:ETH"}, {basisEffect:1})` → `basisEffect = "REALLOCATE_IN"` (not `UNKNOWN`) |
| **A4** — ETH AVCO increase | Latest ETH AVCO from `asset_ledger_points` (sort by `replaySequence DESC`) ≥ **$2,155** (pre-fix $2,105 + ~50% of ~$2,720 recoverable basis) |
| **A5** — CAKE fees not UNKNOWN | `db.asset_ledger_points.find({normalizedTransactionId: {$regex: "0xc300e72c|0x92c403b5|0x728a33b5|0x3ed9b9f7"}, assetSymbol: "CAKE"}, {basisEffect:1, costBasisDeltaUsd:1})` → `basisEffect ≠ UNKNOWN` for all CAKE inflows |
| **A6** — LP_EXIT UNKNOWN count decreases | `db.asset_ledger_points.countDocuments({accountingFamilyIdentity: "FAMILY:ETH", normalizedType: "LP_EXIT", basisEffect: "UNKNOWN"})` → ≤ 14 (was 18; 4 positions fixed) |
| **A7** — No regression | `./gradlew :backend:test` passes; no regressions in `LpPrincipalCloseEvidenceTest` |

---

## Risks

| Risk | Mitigation |
|------|-----------|
| False-positive: a non-MasterChef protocol uses `0x00f714ce` as `methodId` and emits ERC721 Transfer to wallet | Requires CAKE-only inflows additionally (`isHarvestOnlyRewardPattern`); no known protocol has this combination |
| False-positive: multicall outer uses `0x00f714ce` AND embeds `decreaseLiquidity` | Guarded by `!containsEmbeddedSelector(inputData, DECREASE_LIQUIDITY_SELECTOR)` |
| False-negative: MasterChef `withdraw` returns CAKE + residual ETH gas refund | `isHarvestOnlyRewardPattern` returns `false` (ETH is neither CAKE nor dust stablecoin) → falls through to `LP_EXIT`. Safe fallback: position's LP-RECEIPT consumed prematurely, triggering existing UNKNOWN. Known gap, not fixable without extending `isHarvestOnlyRewardPattern`. |
| Future MasterChef version uses different selector | Silent miss; would require adding the new selector to a future fix. Scope: PancakeSwap V3 MasterChef only. |
| Persisted logs missing for harvest TX | `hasAnyErc721TransferToWallet` returns `false` → falls through to `LP_EXIT`. Safe fallback. |

---

## Implementation Order

1. **P0 — Code change** in `LpPrincipalCloseEvidence.java`: add `isMasterChefWithdrawDirectCall` + new OR-branch in `refineLifecycleType()`; update comment on existing test
2. **Unit tests** — add T1–T5 in `LpPrincipalCloseEvidenceTest`; run `./gradlew :backend:test`
3. **Documentation** — amend `docs/03-accounting.md` + ADR-021 + INDEX
4. **Rebuild** — `./scripts/prod-reset-rebuild-backend.sh --skip-frontend`
5. **Verify** — run acceptance checks A1–A7

---

## Rebuild Command

```bash
./scripts/prod-reset-rebuild-backend.sh --skip-frontend
```

No pricing cache clear needed — this is a pure classification fix. Re-normalization changes `type` from `LP_EXIT` to `LP_FEE_CLAIM` for harvest TXs; the cost-basis replay then correctly tracks LP-RECEIPT through the real LP_EXIT.
