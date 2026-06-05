# Implementation Plan: B-LP-ENTRY-NO-LEDGER — Uniswap V3 LP Entry Missing Ledger Points

**Date:** 2026-06-04  
**Severity:** P1  
**Estimated financial impact:** ~$448 cost basis (ETH + USDC) not recorded in accounting  
**Stage:** Classification (correlationId not set) + Normalization (USDC symbol unresolved)

---

## 1. Symptom

Transaction `0x3d41db62af05da7dc3fcc1fcd0660674a8f59f696818319eb55c6418ac532d88` (BASE, Uniswap V3 NonfungiblePositionManager `multicall`) is classified as `LP_ENTRY` but produces **zero `asset_ledger_points`**. The accounting engine silently skips it.

Current state in `normalized_transactions`:
- `type = LP_ENTRY` ✅
- `correlationId = ""` (empty) ❌
- `basisEffects = null` ❌
- `flows[0].assetSymbol = "ERC20:a02913"` (USDC unresolved) ❌

Current state in `asset_ledger_points`:
- `count = 0` ❌

---

## 2. Root Cause

### RC-1: LpClassifier does not extract the Uniswap V3 NFT tokenId
`LpClassifier` detects the ERC-721 Transfer (via `LpPositionLifecycleSupport.hasAnyErc721TransferToWallet`) but passes `correlationId = null` in the returned `ClassificationDecision`. Without `correlationId`, `ReplayPendingTransferKeyFactory` cannot construct the `lp:<lpReceiptIdentity>` bucket, and `ReplayTransactionRouter` has no LP position to route to → `basisEffects` stays null → 0 ledger points.

The correct `correlationId` format is `lp-position:{network}:{protocol}:{tokenId}` (confirmed from existing LP entries, e.g., `lp-position:ethereum:uniswap:922846`).

The Uniswap V3 NFT tokenId is available in `persistedLogs()` as the 4th topic of the ERC-721 Transfer event emitted by the NonfungiblePositionManager contract.

### RC-2: USDC BASE token symbol unresolved
`token_metadata` collection has 0 entries for BASE network. When the USDC ERC-20 transfer was derived from RPC receipt logs, `TokenSymbolFallbackSupport.resolve()` returned `ERC20:a02913` (last 6 chars of USDC contract address `0x833589fcd6edb6e08f4c7c32d4f71b54bda02913`).

`KNOWN_CONTRACT_SYMBOLS` in `TokenSymbolFallbackSupport` only has one entry (`eUSDC-2`). USDC on BASE is missing.

---

## 3. Scope

| Field | Value |
|---|---|
| Wallets affected | `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f` |
| Networks | BASE |
| Assets | ETH (~$200 cost basis), USDC (~$248 cost basis) |
| Protocols | Uniswap V3 NonfungiblePositionManager (multicall `0xac9650d8`) |
| Blocker ID | B-LP-ENTRY-NO-LEDGER |

---

## 4. Changes (upstream first)

### Change 1: `TokenSymbolFallbackSupport` — add well-known BASE tokens
**File:** `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/support/TokenSymbolFallbackSupport.java`

Add well-known BASE token contracts to `KNOWN_CONTRACT_SYMBOLS`:
- `0x833589fcd6edb6e08f4c7c32d4f71b54bda02913` → `USDC` (USDC on BASE)
- `0x4200000000000000000000000000000000000006` → `WETH` (WETH on BASE, canonical)

This is the earliest fix: ensures USDC is recognized as `USDC` not `ERC20:a02913` during normalization.

### Change 2: `LpPositionLifecycleSupport` — add `extractErc721TokenId` method
**File:** `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/support/LpPositionLifecycleSupport.java`

Add a method that scans `view.persistedLogs()` for the ERC-721 Transfer event (topic0 = `0xddf252ad...`, topics.size() == 4, topics[2] = wallet) and returns the tokenId as a `String` parsed from `topics[3]` (hex to decimal BigInteger → String).

```java
public static String extractErc721TokenIdForWallet(OnChainRawTransactionView view) {
    String wallet = OnChainRawTransactionView.normalizeAddress(view.walletAddress());
    for (Document log : view.persistedLogs()) {
        List<String> topics = normalizedTopics(log);
        if (topics.size() < 4) continue;
        if (!ERC721_TRANSFER_TOPIC.equals(topics.get(0))) continue;
        if (!wallet.equals(topicAddress(topics.get(2)))) continue; // 'to' must be wallet
        // topics[3] = tokenId as 32-byte hex
        String tokenIdHex = topics.get(3);
        if (tokenIdHex == null || tokenIdHex.isBlank()) continue;
        try {
            String hex = tokenIdHex.startsWith("0x") ? tokenIdHex.substring(2) : tokenIdHex;
            return new java.math.BigInteger(hex, 16).toString();
        } catch (NumberFormatException ignored) {}
    }
    return null;
}
```

### Change 3: `LpClassifier` — extract tokenId and build correlationId
**File:** `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/onchain/family/LpClassifier.java`

After confirming `hasAnyErc721TransferToWallet`:
1. Call `LpPositionLifecycleSupport.extractErc721TokenIdForWallet(context.view())`
2. Build `correlationId = "lp-position:" + networkId.toLowerCase() + ":uniswap:" + tokenId` if tokenId non-null
3. Pass `correlationId` in the `ClassificationDecision` (currently field position 7, index 6, after `missingDataReasons`)

This aligns with the existing `lp-position:{network}:{protocol}:{tokenId}` convention used by other Uniswap V3 LP entries.

If tokenId is null (extraction failed), set `correlationId = null` but add `"LP_NFT_ID_MISSING"` to `missingDataReasons` so the transaction goes to `NEEDS_REVIEW` rather than silently skipping accounting.

### Change 4: Re-normalize the affected transaction
After deploying, reset `0x3d41db62` `normalizationStatus` back to `PENDING` (or trigger re-normalization via the existing pipeline mechanism) so the fix takes effect. The full prod rebuild `./scripts/prod-reset-rebuild-backend.sh --skip-frontend` handles this automatically since it resets all normalized data.

---

## 5. ADR / Docs

No new ADR required. This is a bug fix to an existing LP classification pattern.

`docs/03-accounting.md`: no changes needed (LP carry-back accounting policy unchanged).

---

## 6. Acceptance Criteria

After prod rebuild:
1. `db.normalized_transactions.findOne({txHash: /0x3d41db62/}).correlationId` = `"lp-position:base:uniswap:<tokenId>"` (non-empty, numeric tokenId)
2. `db.normalized_transactions.findOne({txHash: /0x3d41db62/}).flows` — USDC flow has `assetSymbol = "USDC"` (not `ERC20:a02913`)
3. `db.asset_ledger_points.countDocuments({transactionId: /0x3d41db62/})` > 0
4. `db.normalized_transactions.findOne({txHash: /0x3d41db62/}).basisEffects` is non-null and contains LP basis entries
5. ETH and USDC flows both produce `CARRY_OUT`/`SELL` ledger points with correct cost basis

---

## 7. Risks

- **ERC-721 tokenId extraction depends on `persistedLogs()`**: only available when receipt clarification was run. For transactions without receipt logs, extraction fails gracefully (null correlationId + NEEDS_REVIEW status).
- **Network-specific correlationId format**: uses `networkId.toLowerCase()` for the network component. Consistent with existing entries (`ethereum`, `arbitrum`). BASE → `base`.
- **Protocol name in correlationId**: hardcoded `uniswap` for `LpClassifier` which handles `0xac9650d8` (Uniswap V3 NPM multicall) and `0xb94c3609` (routeSingle). Both are Uniswap V3. If other protocols use the same selectors, this may need adjustment later.
- **Other LP_ENTRY transactions with `correlationId = ""`**: the query found 2 ETHEREUM LP_ENTRYs with empty correlationId. These may also lack ledger points. The same fix applies to them if they involve Uniswap V3 NFT transfers.
