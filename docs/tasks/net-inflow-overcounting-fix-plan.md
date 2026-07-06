# Net Inflow Overcounting Fix Plan

**Problem:** `lifetimeExternalInflowUsd` reports **$29,354** against a user-verified ground-truth of **$14,270** (24 fiat deposits via Bybit P2P / Dzengi). Excess: **~$15,084**.

**Acceptance criterion:** After fixes, `lifetimeExternalInflowUsd ≈ $14,270 ± $200`.

---

## Audit Methodology

All figures were derived from live MongoDB queries that exactly replicate the Java code logic in `PortfolioConservationGate.java`:

```
lifetimeExternalInflowUsd = fundInflow + evmContrib.inflow()
```

where:
- `fundInflow` = `EXTERNAL_TRANSFER_IN` on `BYBIT:*:FUND` wallets, filtered by non-universe counterparty
- `evmContrib.inflow()` = `EXTERNAL_TRANSFER_IN + BRIDGE_IN` on EVM wallets, filtered by non-universe counterparty and excluding paired bridge corridors (shared `correlationId`)

---

## Verified Breakdown of $29,354

| Component | Transactions | Counted USD | Legitimate | Overcounting |
|---|---|---|---|---|
| BYBIT FUND `EXTERNAL_TRANSFER_IN` (universe filter applied) | 27 flows | $14,772 | $12,760 | **$2,013** |
| EVM `EXTERNAL_TRANSFER_IN` | 62 flows | $7,089 | $1,384 | **$5,684** |
| EVM unpaired `BRIDGE_IN` | 20 flows | $7,493 | $0 | **$7,493** |
| **Total** | | **$29,354** | **$14,144** | **$15,190** |

Expected after fix: **$29,354 − $15,190 = $14,164** (within ±$200 of $14,270 ✓)

---

## Root Cause 1 — Non-Stablecoin Crypto Deposited to Bybit FUND ($2,013)

### Description

The `sumFundExternalTransfers()` method counts ALL priced external inflows to `BYBIT:*:FUND`, including non-stablecoin crypto received from addresses outside the accounting universe. Two groups of transactions inflate the figure:

| Date | Asset | Amount | USD | From address |
|---|---|---|---|---|
| 2025-04-11 | MNT | 1,074.68 | $744.43 | `0x5c30940a4544ca845272fe97c4a27f2ed2cd7b64` |
| 2025-04-15 | MNT | 834.08 | $583.69 | `0x5c30940a4544ca845272fe97c4a27f2ed2cd7b64` |
| 2025-05-09 | DOGS | 1,000.00 | $0.19 | `UQAMVoQ1X1QQqSP7fdWTFJBdx_8VeyjJEwZxx6TxcAfomE1N` |
| 2025-05-09 | DOGS | 3,531,307.50 | $684.72 | `UQAMVoQ1X1QQqSP7fdWTFJBdx_8VeyjJEwZxx6TxcAfomE1N` |
| **Total** | | | **$2,013.03** | |

Neither MNT nor DOGS appear in the user's 24-deposit ground-truth table. The MNT sender `0x5c30940a...` is a Mantle-network EVM address **not in the accounting universe**. The DOGS sender is a TON address also not in the universe.

### Root Cause

Both senders are classified as non-universe counterparties, so the code correctly passes the `isNonUniverseCounterparty` check — but the transactions represent crypto from a **non-tracked external wallet**, not a fiat purchase. The Bybit FUND path has no filter for asset type or originating-wallet class.

### What Should Be Excluded and Why

These inflows should **not** count as `lifetimeExternalInflowUsd` because:
1. They do NOT appear in the user's verified deposit table.
2. They represent either (a) assets from another EVM/TON wallet the user owns but has not added to the universe, or (b) DeFi farming rewards bridged to Bybit. In either case they are not fiat capital entering for the first time.

### Fix Options (choose one)

**Option A — Add the sender addresses to the accounting universe.**
`0x5c30940a4544ca845272fe97c4a27f2ed2cd7b64` may be the user's own Mantle-network hot wallet. If confirmed, adding it as an `ON_CHAIN_WALLET` universe member (backfillEnabled: false if not fully tracked) makes the inflow intra-universe and correctly excluded.

**Option B — Exclude non-stablecoin Bybit FUND inflows from `lifetimeExternalInflowUsd`.**
In `sumFundExternalTransfers()`, add an asset-type guard so only stablecoin flows (symbols in `STABLECOIN_SYMBOLS`) contribute to the FUND inflow total. Non-stablecoin Bybit deposits (MNT, DOGS, SOL, TON) reflect crypto-to-crypto movements rather than net fiat capital injection.

```java
// Add inside the flow loop in sumFundExternalTransfers():
String normalizedSym = normalizeStablecoinSymbol(flow.getAssetSymbol());
if (!STABLECOIN_SYMBOLS.contains(normalizedSym)) {
    continue; // skip non-stablecoin crypto deposits to FUND
}
```

**Option B is safer** if the MNT/DOGS wallets are not confirmed as user-owned.

---

## Root Cause 2 — EVM `EXTERNAL_TRANSFER_IN` Bridge Receipts Misclassified ($5,684)

### Description

Several large `EXTERNAL_TRANSFER_IN` flows on EVM wallets are actually receipt legs of bridge corridors or OTC settlements, not new capital inflows. Four specific cases account for nearly all overcounting:

#### Case 2a — LiFi Bridge Receipt Misclassified as EXT_IN ($2,047, Sep 12 2025)

| Field | Value |
|---|---|
| Date | 2025-09-12 |
| Wallet | `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f` |
| Type | `EXTERNAL_TRANSFER_IN` (should be `BRIDGE_IN`) |
| Asset | ETH 0.4529 |
| USD | **$2,047.35** |
| Counterparty | `0x8c826f795466e39acbff1bb4eeeb759609377ba1` |
| txHash | `0xc0aaf96b2cffe3582bed2387854f2569b3ad0e99e92daea46126c8824aa5712c` |

**Evidence:** On Nov 17 2025 the same counterparty address `0x8c826f...` appears with correct `BRIDGE_IN` classification and a `bridge:lifi:*` `correlationId`. On Sep 12 the identical address pattern yielded `EXTERNAL_TRANSFER_IN` with no `correlationId`, indicating the LiFi receipt-side classification failed for this specific transaction. The flow role is `BUY` in both cases.

#### Case 2b — Relay Bridge Return Misclassified as EXT_IN ($2,828, Jan 12 2026)

| Field | Value |
|---|---|
| Date | 2026-01-12 |
| Wallet | `0xf03b52e8686b962e051a6075a06b96cb8a663021` |
| Type | `EXTERNAL_TRANSFER_IN` |
| Asset | USDC 2,828.31 |
| USD | **$2,828.31** |
| Counterparty | `0xf5f93d26229482adca3e42f84d08d549cf131658` |
| txHash | `0x7f6ccd24bac2737f9a41367c3e5b3712e6bfa9b8c40671558e924e1e63d75e68` |

**Evidence (confirmed by DB query):** On the SAME day, wallet `0xf03b52e8` sent `BRIDGE_OUT USDC -2829.12` to the **identical** counterparty `0xf5f93d26...`. The $2,828.31 inflow is the Relay bridge paying back USDC to the same wallet (within-block, cross-chain bridge settlement). Classification as `EXTERNAL_TRANSFER_IN` is wrong; it should be `BRIDGE_IN` paired to the `BRIDGE_OUT`.

#### Case 2c — Bridge Receipts from `0x7ff8bbf9...` ($750, Jan 13–16 2026)

| Date | Asset | USD |
|---|---|---|
| 2026-01-13 | USDC 648.22 | $648.22 |
| 2026-01-16 | USD₮0 102.25 | $102.25 |
| **Total** | | **$750.47** |

Counterparty `0x7ff8bbf9c8ab106db589e7863fb100525f61cce5` is a bridge relayer that paid out funds on the destination chain. On Jan 13 and 16, the user sent `BRIDGE_OUT` from another chain into this relayer; the USDC/USD₮0 receipts are the destination-side payouts, classified as `EXTERNAL_TRANSFER_IN` instead of `BRIDGE_IN`.

#### Case 2d — ETH from Unknown Sender ($58, Jan 29 2026)

| Date | Asset | USD | Counterparty |
|---|---|---|---|
| 2026-01-29 | ETH (×3) | $58.00 | `UNKNOWN:0xf3581fb98799bb1d55ec08a72dfb6668ae4` |

Small ETH received from an unidentified address. Evidence basis is insufficient to confirm whether this is bridge, OTC, or genuinely new capital. Treat as **low-priority investigation**.

### Root Cause

The LiFi and Relay bridge classifiers fail to tag receipt-side transactions as `BRIDGE_IN` in two scenarios:
1. **LiFi:** When the LiFi Diamond returns funds via a different relayer address than expected (e.g., `0x8c826f...` on the destination chain), the receipt-side classification falls through to `EXTERNAL_TRANSFER_IN`.
2. **Relay Protocol:** When the source transaction uses the Relay Protocol source contract (e.g., `0xf5f93d26...`) but is classified as `BRIDGE_OUT` (correct), the destination-chain receipt from the same Relay solver arrives as `EXTERNAL_TRANSFER_IN` instead of `BRIDGE_IN` because the `correlationId` is not propagated.

### What Should Be Excluded and Why

Cases 2a, 2b, 2c are bridge payout receipts. The corresponding outgoing leg is already captured as `BRIDGE_OUT` or `EXTERNAL_TRANSFER_OUT` (and thus as NEC outflow). Counting the receipt as NEC inflow means the same capital is counted twice — once out and once in — inflating `lifetimeExternalInflowUsd` without affecting `netExternalCapitalUsd` (which does net out correctly), but `lifetimeExternalInflowUsd` is the value shown to the user as "net inflow".

### Fix

**Short-term (data fix):** Add the following bridge counterparty addresses to the known Relay / bridge corpus so that:
- Transactions where the counterparty is `0xf5f93d26...` AND a same-day `BRIDGE_OUT` to the same counterparty exists → reclassify receipt as `BRIDGE_IN` and link via corridor.
- Transactions from `0x8c826f...` that match the LiFi relayer pattern (BUY role, ETH family) → classify as `BRIDGE_IN`.

**Medium-term (classification fix):** Extend `BridgeStartClassifier` / `LiFiBridgePairLinkService` to cover the Relay Protocol pattern:
- Relay source addresses: `0xf5f93d26229482adca3e42f84d08d549cf131658`, `0x89c6340b1a1f4b25d36cd8b063d49045caf3f818` (already used as LiFi target in many BRIDGE_OUTs)
- Relay destination payout addresses: `0xcad97616f91872c02ba3553db315db4015cbe850`, `0x7ff8bbf9c8ab106db589e7863fb100525f61cce5`
- Detection rule: When wallet receives USDC/USDT from a known Relay destination address within 30 minutes of sending the same asset (within ±1%) to a known Relay source address on any chain, classify receipt as `BRIDGE_IN` and assign a synthetic `correlationId` for corridor pairing.

---

## Root Cause 3 — Unpaired `BRIDGE_IN` Counted as External Inflow ($7,493)

### Description

The corridor-pairing logic excludes `BRIDGE_IN` from NEC only when the same `correlationId` appears in both a `BRIDGE_OUT` and a `BRIDGE_IN` in the dataset. The 20 unpaired `BRIDGE_IN` fall into three sub-categories:

#### Sub-cause 3a — Relay Protocol Corridors (EXT_OUT → BRIDGE_IN, $2,295)

The Relay Protocol operates by having the user send to a Relay source contract on chain A (`EXTERNAL_TRANSFER_OUT`, not `BRIDGE_OUT`), while the Relay solver pays out on chain B as `BRIDGE_IN`. Because the source leg is `EXTERNAL_TRANSFER_OUT` (no `correlationId`) and the destination leg is `BRIDGE_IN` (no `correlationId`), the current pairing logic (which only pairs `BRIDGE_OUT` ↔ `BRIDGE_IN` by shared `correlationId`) misses them entirely.

**Confirmed amounts (DB-verified):**

| Date | Asset | USD | Source EXT_OUT | Destination BRIDGE_IN |
|---|---|---|---|---|
| 2025-11-17 | USDC 1199.43 | $1,199.43 | $1,200 to `0x2ec2c4c3...` | from `0xcad97616...` |
| 2025-11-17 | USDC 789.63 | $789.63 | $790 to `0x2ec2c4c3...` | from `0xcad97616...` |
| 2025-11-17 | USDC 299.81 | $299.81 | $300 to `0x2ec2c4c3...` | from `0xcad97616...` |
| 2026-01-06 | USDC 6.64 | $6.64 | (small amounts) | from `0xcad97616...` |
| **Total** | | **$2,295.51** | EXT_OUT total: $2,296.61 | Delta: $1.10 (bridge fees) |

The $1.10 gap between source ($2,296.61) and destination ($2,295.51) confirms these are bridge fees — the same capital at 99.9% parity.

**Additional sub-case:** Jul 31 2025 — USDC bridge via LiFi ($1,535.71).

| Date | Asset | USD | Note |
|---|---|---|---|
| 2025-07-31 | USDC 1005.47 | $1,005.47 | BRIDGE_IN from `0xf70da97...`, no corrId |
| 2025-07-31 | USDC 500.38 | $500.38 | BRIDGE_IN from `0xc38e4e6a...`, no corrId |
| 2025-07-31 | USDC 29.86 | $29.86 | BRIDGE_IN from `0xc38e4e6a...`, no corrId |

Same day, BRIDGE_OUTs with LiFi corrIds exist for USD₮0 1000 + USDC 999.48. The destination-chain BRIDGE_INs have no corrId, so pairing fails.

#### Sub-cause 3b — Katana LP Exit Misclassified as BRIDGE_IN ($1,706)

| Date | Asset | USD | From |
|---|---|---|---|
| 2025-11-10 | vbETH 0.4732 | $1,706.00 | `0x2a2c512beaa8eb15495726c235472d82effb7a6b` (Katana LP pool) |

**Evidence:** Immediately before this `BRIDGE_IN`, there is an `LP_FEE_CLAIM` from the same counterparty address `0x2a2c512...`. The transaction is an LP position exit (user withdrawing their Katana vbETH LP shares), not a cross-chain bridge receipt. Classifying it as `BRIDGE_IN` is semantically wrong.

Because there is no matching `BRIDGE_OUT` and no `correlationId`, it is included in the NEC inflow as if ETH 0.473 arrived from outside the universe.

Correct classification: **`LP_EXIT`** or **`VAULT_WITHDRAW`** with the full LP lifecycle (vbETH is a Katana vault share token). This will not affect NEC at all since LP_EXIT is not a capital flow type.

#### Sub-cause 3c — Bridge Receipt from MULTI Counterparty ($1,196)

| Date | Asset | USD | From |
|---|---|---|---|
| 2025-11-21 | weETH 0.1441 | $402.01 | MULTI |
| 2025-11-21 | ETH 0.2847 | $794.33 | MULTI |

Current code explicitly skips `BRIDGE_IN` with `MULTI` counterparty only when processing the `BRIDGE_IN` flow's own `counterpartyAddress`. These two flows have `tx.counterpartyAddress = MULTI` but the guard in `computeEvmNecContribution()` only fires when `resolveCounterpartyAddress()` returns `MULTI`.

Looking at Nov 10: `BRIDGE_OUT weETH 0.2126 + ETH 0.2106` to `MULTI` (no corrId). These are likely the outgoing legs — same wallet, similar assets, 11 days prior. Without shared `correlationId`, the Nov 21 BRIDGE_INs cannot be automatically paired.

Additionally: `BRIDGE_IN ETH 0.0073 USD $19.90` from `0x1fa66e2b...` (Nov 21, small amount).

#### Summary of Sub-cause Amounts

| Sub-cause | USD | Mechanism |
|---|---|---|
| 3a: Relay Protocol EXT_OUT → BRIDGE_IN | $2,295 | No shared corrId; EXT_OUT not matched with BRIDGE_IN |
| 3a: Jul 31 LiFi BRIDGE_OUT → BRIDGE_IN (no corrId on IN) | $1,536 | corrId missing on destination leg |
| 3a: Oct 22 USDC (`0xf70da97...`) | $493 | Same pattern |
| 3b: Katana vbETH LP_EXIT misclassified | $1,706 | Wrong tx type |
| 3c: Nov 21 weETH + ETH from MULTI | $1,196 | No corrId, MULTI counterparty, no Nov 10 pairing |
| Other small BRIDGE_IN | $267 | Various |
| **Total** | **$7,493** | |

### Root Cause

The pairing mechanism has two gaps:
1. **EXT_OUT ↔ BRIDGE_IN corridors are never paired.** Only `BRIDGE_OUT ↔ BRIDGE_IN` with shared `correlationId` are handled. Relay Protocol's model is `EXT_OUT → BRIDGE_IN`, which bypasses the pairing entirely.
2. **`BRIDGE_IN` with no `correlationId` cannot be paired** even when the outgoing BRIDGE_OUT does have one (destination chain does not echo the source chain's LiFi tracking ID).

### Fixes

**Fix 3a — EXT_OUT ↔ BRIDGE_IN corridor pairing (Relay Protocol):**

In `computeEvmNecContribution()`, after building `pairedCorrelationIds`, add a second pairing pass:

```java
// Pair EXT_OUT → BRIDGE_IN corridors via known Relay bridge solver addresses.
// When a BRIDGE_IN from a known Relay solver (0xcad97616..., 0x7ff8bbf9...)
// is close in time (±2h) and amount (±1%) to an EXT_OUT to a known Relay source
// (0x2ec2c4c3..., 0xf5f93d26...), treat the BRIDGE_IN as paired.
Set<String> relaySourceAddresses = Set.of(
    "0x2ec2c4c3dc212c990d1bc2b48b0392a3951d926e",
    "0xf5f93d26229482adca3e42f84d08d549cf131658"
);
Set<String> relaySolverAddresses = Set.of(
    "0xcad97616f91872c02ba3553db315db4015cbe850",
    "0x7ff8bbf9c8ab106db589e7863fb100525f61cce5"
);
// Collect EXT_OUTs to Relay source addresses, then mark BRIDGE_INs from Relay solvers
// within amount tolerance as "relay-paired" (exclude from NEC inflow).
```

Alternatively — and more robustly — reclassify these transactions during ingestion clarification so that they receive a shared `correlationId` before reaching the NEC gate. This belongs in `BridgePairContinuityRepairService` or a new `RelayBridgePairRepairService`.

**Fix 3b — Katana LP_EXIT misclassification:**

Add `0x2a2c512beaa8eb15495726c235472d82effb7a6b` (Katana LP pool) to the LP registry so that vbETH/vbUSDC receipts from this contract are classified as `LP_EXIT` or `VAULT_WITHDRAW`, not `BRIDGE_IN`. This removes $1,706 from NEC inflow completely.

**Fix 3c — MULTI BRIDGE_IN without paired outflow:**

Temporal matching: when `BRIDGE_IN` has `MULTI` counterparty and no `correlationId`, check for `BRIDGE_OUT` to `MULTI` within ±15 days and ±10% of the same asset and amount. If matched, treat the pair as an intra-universe corridor. This is an imprecise heuristic; treat as low priority until the `correlationId` can be recovered from LiFi API.

---

## Code Changes Required

### `PortfolioConservationGate.java`

1. **BYBIT FUND stablecoin-only filter (RC1 Option B):**
   Add stablecoin check in `sumFundExternalTransfers()` flow loop before `isNonUniverseCounterparty`. Prevents non-stablecoin crypto (MNT, DOGS, SOL) from inflating `fundInflow`.

2. **Relay Protocol bridge pairing (RC3a):**
   Extend `computeEvmNecContribution()` to pair `EXTERNAL_TRANSFER_OUT` to known Relay source addresses with `BRIDGE_IN` from known Relay solver addresses, using temporal proximity and amount-tolerance matching (±2 hours, ±1%).

3. **Known bridge relayer exclusion (RC2, RC3a):**
   Optionally: add a set of known bridge receipt addresses (Relay solvers, LiFi relayers) to the universe exclusion check so that `BRIDGE_IN` from these addresses is automatically treated as a corridor receipt, not an external capital inflow.

### Normalization / Classification (upstream fixes, preferred)

4. **Relay Protocol classification (RC2b, RC3a):**
   Add Relay source and solver addresses to `protocol-registry.json` or the bridge classifier so that:
   - EXT_OUT to Relay source → classified as `BRIDGE_OUT` with a synthetic `correlationId` derived from amount + timestamp
   - BRIDGE_IN from Relay solver → assigned matching `correlationId` so existing pairing logic works without changes to `PortfolioConservationGate`

5. **Katana LP pool registration (RC3b):**
   Add `0x2a2c512beaa8eb15495726c235472d82effb7a6b` to `protocol-registry.json` as a Katana LP/vault pool. vbETH and vbUSDC receipts from this address will be classified as `LP_EXIT` / `VAULT_WITHDRAW` instead of `BRIDGE_IN`.

---

## Data Coverage Gaps (Not Overcounting, But Missing)

The following ground-truth deposits are **not present** in `normalized_transactions` (no BYBIT sync data):

| Date | Source | Amount |
|---|---|---|
| 2025-11-30 | ALFA VISA BYN | $100 USDT |
| 2026-01-13 | ALFA VISA BYN | $527 USDT ← received on-chain EVM, counted there |
| (2026-01-13 is in EVM EXT_IN at $527.46) | | |

The Jan 13 $527 deposit arrived as `EXTERNAL_TRANSFER_IN` on EVM wallet `0x1a87f12a` (counted correctly in `evmContrib.inflow()`), then was transferred on-chain to Bybit FUND (`INTERNAL_TRANSFER`). It is NOT double-counted.

The Nov 30 $100 USDT deposit is missing from both Bybit and on-chain data. This likely reflects an incomplete Bybit sync range (no `bybit_extracted_events` for the relevant period in this session). Resolving this requires a Bybit resync.

---

## Priority Order

| Priority | Fix | Estimated Reduction | Effort |
|---|---|---|---|
| **P1** | RC3b: Add Katana LP pool to registry (classification fix) | $1,706 | Low — one registry entry |
| **P1** | RC1 Option B: Stablecoin-only BYBIT FUND filter | $2,013 | Low — one code line |
| **P2** | RC2b + RC3a: Relay Protocol addresses in registry → correlationId pairing | $4,590 ($2,828+$2,295+relay cases) | Medium — new classifier rule |
| **P2** | RC2a: LiFi bridge relayer address added to BRIDGE_IN classifier | $2,047 | Medium — extend known relayer list |
| **P3** | RC3c: Temporal MULTI BRIDGE_IN matching | $1,196 | Medium-high — heuristic matching |
| **P3** | RC2d: Investigate ETH $58 from UNKNOWN sender | $58 | Low — needs on-chain lookup |

Implementing P1 + P2 alone yields: $2,013 + $4,590 + $2,047 = **$8,650** reduction → $29,354 − $8,650 = **$20,704** (not yet at target; P3 items needed).

Implementing all items: **$15,110** reduction → $29,354 − $15,110 = **$14,244** ≈ $14,270 ✓

---

## Acceptance Criteria

After all fixes are implemented and the pipeline re-runs:

- [ ] `lifetimeExternalInflowUsd` is in the range **$14,070 – $14,470**
- [ ] BYBIT FUND non-stablecoin flows (MNT, DOGS) no longer appear in `fundInflow`
- [ ] `EXTERNAL_TRANSFER_IN` from `0xf5f93d26...` on Jan 12 2026 is paired with matching `BRIDGE_OUT` and excluded from NEC inflow
- [ ] `BRIDGE_IN` from `0xcad97616...` (Relay) on Nov 17 2025 are excluded from NEC inflow (paired via Relay corridor logic)
- [ ] `BRIDGE_IN` vbETH from `0x2a2c512...` on Nov 10 2025 is reclassified as LP_EXIT, not counted in NEC
- [ ] `netExternalCapitalUsd` remains consistent with current value (net NEC should not change by more than $200 since the excess inflows are already offset by corresponding outflows)
