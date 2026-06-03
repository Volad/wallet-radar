# LP_EXIT PancakeSwap V3 Native ETH — Implementation Plan

**Date:** 2026-06-03  
**Bug ID:** B-LP-EXIT-BASE-PANCAKE-UNKNOWN  
**Wallet:** `0x0a757aeeb58667c5…`  
**Network:** BASE

---

## 1. Bug Context

PancakeSwap V3 LP exit transaction `0x0a757aeeb58667c5` on BASE is classified as `UNKNOWN` with 0 flows instead of `LP_EXIT`. The transaction uses a `multicall` on the NonfungiblePositionManager (NFPM) that exits liquidity via `sweepToken` + WETH Withdrawal rather than collecting ERC-20 WETH directly to the user's wallet. As a result, the user's 0.79627 ETH receipt is invisible to the movement-leg extractor, causing a cascading cost-basis defect: the downstream BRIDGE_OUT carries $0 cost basis, and `aManWETH` AVCO drops to $1,533 instead of ~$2,257 (~$2,100 cbD shortfall).

---

## 2. Root Cause

### Transaction structure

The outer call is `multicall(bytes[])` (`0xac9650d8`) on NFPM (`0x46a15b0b` on BASE), containing three inner calls:

| # | Selector | Purpose |
|---|---|---|
| 1 | `0x0c49ccbe` — `decreaseLiquidity` | Burns LP liquidity |
| 2 | `0xfc6f7865` — `collect` | Collects WETH pool → NFPM |
| 3 | `0xdf2ab5bb` — `sweepToken` | Sweeps WETH from NFPM; internally calls `WETH.withdraw()` → sends native ETH to user |

### Relevant receipt logs

- **Log 2 (ERC-20 Transfer):** WETH pool (`0x72ab388e`) → NFPM (`0x46a15b0b`), 796271573705554221 wei — this is pool → NFPM, not pool → user.
- **Log 5 (WETH Withdrawal):** topic0 = `0x7fcf532c…5081b65`, topic1 encodes NFPM (`0x…46a15b0b`) as `src`, `data` encodes `wad` = 796271573705554221 wei (**0.79627 ETH**).

### Why `movementLegs` is empty

`MovementLegExtractor.extract()` assembles legs from three sources:

1. `view.explorerTokenTransfers()` — only contains WETH pool→NFPM (not to user's wallet, so ignored).
2. `view.explorerInternalTransfers()` — Blockscout returns **no internal tx trace** for this transaction.
3. Raw `value` field — 0 on outer multicall.

None of these sources captures the native ETH received by the user, so `movementLegs` contains zero inbound non-fee legs.

### Classification short-circuit

`LpPositionLifecycleSupport.resolvePositionManagerMulticallType(view, movementLegs)` checks:

```java
if (decreaseLiquidity && hasInboundNonFeeLeg(movementLegs)) {
    return NormalizedTransactionType.LP_EXIT;   // ← never reached
}
```

`hasInboundNonFeeLeg` returns false → the method returns null → `MethodAwareRegistryReviewClassifier` emits `ROUTER_METHOD_OVERLOAD_UNSUPPORTED` → classified as `UNKNOWN`.

---

## 3. Proposed Fix

### Phase 1 — Create `LpNativeExitLegEnricher`

**File:** `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/support/LpNativeExitLegEnricher.java`

**Responsibility:** When an LP position manager multicall exits liquidity via `sweepToken` + WETH Withdrawal (instead of the usual ERC-20 `collect` to user), synthesize a native ETH inbound `RawLeg` from the Withdrawal event amount.

**Pseudocode:**

```
static final String WETH_WITHDRAWAL_TOPIC =
    "0x7fcf532c15f0a6db0bd6d0e038bea71d30d808c7d98cb3bf7268a95bf5081b65";

static final String DECREASE_LIQUIDITY_SELECTOR = "0x0c49ccbe";
static final String BURN_SELECTOR               = "0x00f714ce";

public static List<RawLeg> enrichLegs(
        TransactionView view,
        NativeAssetSymbolResolver resolver,
        List<RawLeg> legs) {

    String nativeSymbol = resolver.nativeSymbol(view.network());

    // Guard 1: skip if an inbound native leg already exists
    boolean alreadyHasNativeInbound = legs.stream().anyMatch(leg ->
        leg.isInbound() && leg.asset().symbol().equals(nativeSymbol));
    if (alreadyHasNativeInbound) return legs;

    // Guard 2: input data must contain decreaseLiquidity or burn selector
    String inputData = view.inputData().toLowerCase();
    boolean hasLpSelector =
        inputData.contains(DECREASE_LIQUIDITY_SELECTOR.substring(2)) ||
        inputData.contains(BURN_SELECTOR.substring(2));
    if (!hasLpSelector) return legs;

    // Scan persisted logs for WETH Withdrawal event
    String userWallet = view.walletAddress().toLowerCase();
    for (PersistedLog log : view.persistedLogs()) {
        List<String> topics = log.topics();
        if (topics.size() < 2) continue;
        if (!WETH_WITHDRAWAL_TOPIC.equals(topics.get(0))) continue;

        // topic1 encodes the `src` (who called WETH.withdraw); must NOT be user's wallet
        String src = extractAddress(topics.get(1));
        if (src.equalsIgnoreCase(userWallet)) continue;

        // data encodes wad (uint256, 32-byte right-aligned)
        BigDecimal wad = decodeUint256Ether(log.data());
        if (wad.compareTo(BigDecimal.ZERO) <= 0) continue;

        // Synthesize a single inbound native leg; stop after first match
        List<RawLeg> enriched = new ArrayList<>(legs);
        enriched.add(RawLeg.nativeAsset(nativeSymbol, wad));
        return enriched;
    }

    return legs;
}

// Helpers
private static String extractAddress(String topic) {
    // topic is 32 bytes; address is right-aligned in last 20 bytes
    return "0x" + topic.replaceFirst("^0x", "").substring(24);
}

private static BigDecimal decodeUint256Ether(String hexData) {
    String clean = hexData.replaceFirst("^0x", "");
    if (clean.isEmpty()) return BigDecimal.ZERO;
    return new BigDecimal(new BigInteger(clean, 16))
        .movePointLeft(18);
}
```

**Key invariants:**
- Only the **first** matching Withdrawal log is used (guard against edge-case multiples).
- Does not add more than one synthetic ETH leg (Guard 1 prevents doubles if both internal-transfer and Withdrawal log are present).
- The Withdrawal topic is universal across all WETH9 deployments; no network-specific special-casing required.

---

### Phase 2 — Wire into `MovementLegExtractor`

**File:** `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/support/MovementLegExtractor.java`

After the existing `WrappedNativeSupport.enrichLegs(...)` call and before the final `return legs;`, add:

```java
legs = LpNativeExitLegEnricher.enrichLegs(view, nativeAssetSymbolResolver, legs);
```

No other files require modification. Once `movementLegs` includes the synthesized ETH inbound leg, `resolvePositionManagerMulticallType`'s existing `decreaseLiquidity && hasInboundNonFeeLeg` guard naturally returns `LP_EXIT`.

---

## 4. Affected Files

| File | Action |
|---|---|
| `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/support/LpNativeExitLegEnricher.java` | **CREATE** — new enricher |
| `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/support/MovementLegExtractor.java` | **MODIFY** — add enricher call after `WrappedNativeSupport.enrichLegs` |
| `backend/src/test/java/com/walletradar/ingestion/pipeline/classification/support/LpNativeExitLegEnricherTest.java` | **CREATE** — unit tests |
| `backend/src/test/java/com/walletradar/ingestion/pipeline/classification/support/MovementLegExtractorTest.java` | **MODIFY** — add test for WETH Withdrawal path |

---

## 5. Acceptance Criteria

| # | Criterion |
|---|---|
| AC-1 | `0x0a757aeeb58667c5` normalized as `LP_EXIT` with flows ≥ 2 (ETH inbound + LP-RECEIPT outbound + gas) |
| AC-2 | ETH flow `quantityDelta ≈ +0.79627 ETH` (tolerance ±0.0001) |
| AC-3 | No LP_EXIT on BASE with `classificationNote = ROUTER_METHOD_OVERLOAD_UNSUPPORTED` in `normalized_transactions` |
| AC-4 | `0x4ca0b79e` BRIDGE_OUT `costBasisUsd` > $1,000 (previously $0) |
| AC-5 | `aManWETH` AVCO recovers to ≥ $2,000 (from $1,533) |
| AC-6 | `LpNativeExitLegEnricher` unit tests pass: (a) happy path adds ETH inbound, (b) skipped when ETH inbound already present, (c) skipped when no `decreaseLiquidity`/`burn` in input data, (d) skipped when Withdrawal `src` is user's own wallet |
| AC-7 | No regression on existing PancakeSwap V3 LP_EXIT tests — USDC LP exits remain classified correctly |
| AC-8 | Pipeline reaches steady state (no PENDING_CLARIFICATION or NEEDS_REVIEW loops) |

---

## 6. Risks

| Risk | Mitigation |
|---|---|
| Other multicall patterns also emit WETH Withdrawal (e.g., SWAP + `unwrapWETH9`) | Guard on `decreaseLiquidity` or `burn` selector in input data prevents false positives for swap transactions |
| Duplicate synthetic legs if both internal-transfer AND Withdrawal log are present | Guard 1 (skip if ETH inbound already exists) prevents double-counting |
| Different WETH contracts on other networks | Fix relies on the Withdrawal event topic (`0x7fcf532c…`), which is identical for all WETH9 implementations; no per-network WETH address mapping needed |
| `sweepToken` called with non-WETH ERC-20 | Check is gated on the Withdrawal topic from the WETH contract; non-ETH sweeps never emit this topic and are therefore unaffected |
| Multiple LP exits in one multicall (theoretical) | Only the first Withdrawal log is synthesized; Guard 1 prevents a second synthetic leg from being added |
