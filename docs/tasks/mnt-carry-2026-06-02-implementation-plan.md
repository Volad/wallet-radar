# B-MNT-CARRY-1: MANTLE MNT Internal Transfer Linking Fix

## Scope

- Wallets: `0xa0dd42c626b002778f93e1ab42cbed5f31c117b2` (sender) → `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f` (receiver)
- Network: MANTLE
- Asset: MNT
- Affected transactions: 5 `INTERNAL_TRANSFER`s without `correlationId`
- Approximate missing carry display: ~$69 total (split across 5 txs: $0.56, $21.33, $19.96, $26.64, $0.46)
- Both wallets share the same accounting universe (confirmed)

---

## Root cause

**Stage:** linking (clarification cycle — `OnChainInternalTransferPairRepairService`)

**Evidence state:** `EVIDENCE_PRESENT_UNLINKED`

### What is happening

The 5 MNT `INTERNAL_TRANSFER`s on MANTLE are classified by the normalizer with:
- `continuityCandidate = true`
- `correlationId = null`

There are **two** clarification services that assign `internal-tx:<network>:<txHash>` correlation IDs, and both exclude these transactions:

| Service | Exclusion condition |
|---|---|
| `InternalTransferPairLinkService` | Queries only `EXTERNAL_TRANSFER_IN` / `EXTERNAL_TRANSFER_OUT`; never sees `INTERNAL_TRANSFER` rows |
| `OnChainInternalTransferPairRepairService` | `loadOrphanBatch()` filters `continuityCandidate = false`; `isOrphanCandidate()` asserts `Boolean.FALSE.equals(candidate.getContinuityCandidate())` |

The 5 MNT transactions have `continuityCandidate = true` and no corrId, which is not covered by either service.
This leaves both sides as `INTERNAL_TRANSFER` with `continuityCandidate=true` and a null `correlationId`.

### Why CMETH works but MNT does not

CMETH `INTERNAL_TRANSFER`s on the same wallet pair DO carry the `internal-tx:mantle:` prefix.
Query evidence shows 10 CMETH transactions with valid `correlationId`; 0 MNT transactions with any `correlationId`.

The most likely reason is normalization origin:
- CMETH transactions were initially emitted as `EXTERNAL_TRANSFER_OUT` / `EXTERNAL_TRANSFER_IN` and were promoted to `INTERNAL_TRANSFER` by `InternalTransferPairLinkService` (which sets the corrId at promotion time).
- MNT transactions were classified **directly** as `INTERNAL_TRANSFER` (with `continuityCandidate=true`) during normalization — so they bypass `InternalTransferPairLinkService` entirely and land in the gap where `OnChainInternalTransferPairRepairService` cannot see them.

### Functional impact

The carry DOES propagate despite the missing corrId: the replay engine falls back to a `tx:<txHash>:<assetFamily>:<qty>` key in `ReplayPendingTransferKeyFactory.transferKey()`.
This causes a **two-phase CARRY_IN** at the receiver:

1. Phase 1 (from receiver's tx): `CARRY_IN qty=+N, costBasisDeltaUsd=$0, avcoAfterUsd=null` — quantity arrives, zero basis
2. Phase 2 (from sender's tx, recorded for receiver wallet): `CARRY_IN qty=0, costBasisDeltaUsd=$X, avcoAfterUsd=correct` — basis applied via `attachLateCarryToPendingInbound()`

The position AVCO is correct after both phases, but:
- Phase 1 displays `avcoAfterUsd = null` (AVCO undefined when position has qty but zero basis)
- The "$69 missing" is a display/audit artifact — the basis is present in Phase 2 which is attributed to the sender's transaction record
- The carry key is quantity-dependent (`tx:...:0.8`): any BigDecimal formatting difference between sender and receiver would cause a permanent mismatch

### Fix summary

Relax the `continuityCandidate=false` guard in `OnChainInternalTransferPairRepairService` to also repair `INTERNAL_TRANSFER`s with `continuityCandidate=true` and no `correlationId`.
After repair, the 5 MNT pairs will have `internal-tx:mantle:<txHash>` corrIds, and replay will use the `corr-family:` key → single-phase CARRY_IN with correct quantity AND basis.

---

## Changes required

### 1. `OnChainInternalTransferPairRepairService` — remove `continuityCandidate=false` guard

**File:** `backend/src/main/java/com/walletradar/ingestion/pipeline/clarification/OnChainInternalTransferPairRepairService.java`

**`loadOrphanBatch()` — line 204:** remove the `continuityCandidate = false` filter:

```java
// BEFORE
Criteria.where("continuityCandidate").is(false),

// AFTER — line removed (omit entirely from the query)
```

**`isOrphanCandidate()` — line 225:** remove the `continuityCandidate=false` assertion:

```java
// BEFORE
&& Boolean.FALSE.equals(candidate.getContinuityCandidate())

// AFTER — line removed
```

No other method needs change:
- `isPairable()` already verifies same txHash, same networkId, reciprocal flow signs, matching asset family, and same accounting universe — these checks are sufficient to confirm the pair is correct
- `promote()` already sets `continuityCandidate=true` (no-op for MNT which is already true) and sets the corrId and matchedCounterparty
- `materializePair()` and the corrId format (`internal-tx:<network>:<txHash>`) are correct as-is

### 2. Unit test — `OnChainInternalTransferPairRepairServiceTest`

Add a test case:
- Two `INTERNAL_TRANSFER` rows for the same txHash, same networkId (MANTLE)
- Both have `continuityCandidate = true`, `correlationId = null`
- Same accounting universe, reciprocal MNT quantities
- Asserts that after `reconcileOrphanSameTxPairs()`, both rows receive the `internal-tx:mantle:<txHash>` corrId and `continuityCandidate` remains true

### 3. Rebuild and verify

Run from repo root:
```bash
./scripts/prod-reset-rebuild-backend.sh --skip-frontend
```

---

## Docs

No new ADR required — this is a bug fix within an existing clarification service.

If the investigation surface is extended in future (e.g., adding a broader corrId repair sweep), update `docs/adr/ADR-019-corridor-carry-policy.md` accordingly.

---

## Acceptance criteria

After rebuild, all of the following must be true:

1. All 5 MNT `INTERNAL_TRANSFER`s on MANTLE for wallet pair `0xa0dd42c...` ↔ `0x1a87f12...` have `correlationId = internal-tx:mantle:<txHash>` and `continuityCandidate = true`.

   Verification query:
   ```js
   db.normalized_transactions.find({
     type: "INTERNAL_TRANSFER",
     walletAddress: "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f",
     "flows.assetSymbol": "MNT"
   }, { txHash: 1, correlationId: 1, continuityCandidate: 1, _id: 0 }).toArray()
   ```
   Expected: all 5 rows have `correlationId` matching `^internal-tx:mantle:0x`, `continuityCandidate = true`.

2. Each of the 5 receiver (`0x1a87f12...`) MNT `INTERNAL_TRANSFER`s produces **one** `asset_ledger_points` entry (not two): `basisEffect = CARRY_IN`, `quantityDelta > 0`, `costBasisDeltaUsd > 0`, `avcoAfterUsd` is non-null and non-zero.

   Verification query:
   ```js
   var hashes = [
     "0xffc959c27972e84a0e69860e9ed312dce3db85aa6e23f2e90f22e7969b447ca1",
     "0x3c011394be8b112beee14ece4a7eea3686ebee88015a4ed1f074edd4b96cafc7",
     "0xe2bf4c4ffb6de1ce01245768c4e672294d3ef8211e8a4af7720c4a28e5c28646",
     "0x4fa1f2a24b92cd234615050fb1cf3d48d4d7827bd7060a70c04bff726547e3c5",
     "0x7e5e74439c3e4c246ecb5093762d21fc3fd7d47c62d6a8069e3e7bed75b9c0ec"
   ];
   hashes.forEach(hash => {
     var tx = db.normalized_transactions.findOne({
       txHash: hash,
       walletAddress: "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f"
     });
     var lps = db.asset_ledger_points.find({ normalizedTransactionId: tx._id }).toArray();
     print(hash.substring(0,20), "CARRY_IN count:", lps.filter(x => x.basisEffect === "CARRY_IN").length);
   });
   ```
   Expected: each hash returns exactly 1 `CARRY_IN` with qty > 0 and cbDelta > 0.

3. No existing CMETH `INTERNAL_TRANSFER` corrIds are modified or removed.

   Verification query:
   ```js
   db.normalized_transactions.count({
     type: "INTERNAL_TRANSFER",
     correlationId: /^internal-tx:mantle:/,
     "flows.assetSymbol": "cmETH"
   })
   ```
   Expected: same count as before rebuild (10).

4. No regression in `BybitTransferContinuityRepairServiceTest`, `BridgePairContinuityRepairServiceTest`, or `OnChainInternalTransferPairRepairServiceTest`.

---

## Risks

### R1 — Transactions with `continuityCandidate=true` that should NOT receive a corrId

**Probability: Low.** `isPairable()` guards against false pairings: it requires same txHash, same networkId, same accounting universe, reciprocal flow signs, matching asset family, and quantity compatibility. An `INTERNAL_TRANSFER` with `continuityCandidate=true` and no corrId that does NOT have a reciprocal peer on the same txHash will simply not be paired.

**Mitigation:** Run `reconcileOrphanSameTxPairs()` against a staging snapshot first and inspect all modified rows before production deployment.

### R2 — Quantity key mismatch if qty formatting differs

**This risk is eliminated by the fix.** The current two-phase `tx:` key path is sensitive to BigDecimal formatting. After repair, the carry uses `corr-family:internal-tx:mantle:<txHash>:FAMILY:MNT`, which is quantity-independent.

### R3 — Re-run idempotency

**No risk.** `promote()` checks current field values before mutating. If the corrId is already set correctly, the method returns `changed=false` and no write is emitted. The `loadOrphanBatch()` query will stop returning these rows once corrIds are populated.

### R4 — Other networks with the same gap

The same gap could exist on other networks if normalization classifies `INTERNAL_TRANSFER` directly with `continuityCandidate=true`. The fix is network-agnostic (it applies to all ON_CHAIN INTERNAL_TRANSFERs), so it auto-covers any such cases.

**Mitigation:** After rebuild, run a broad check:
```js
db.normalized_transactions.count({
  source: "ON_CHAIN",
  type: "INTERNAL_TRANSFER",
  continuityCandidate: true,
  $or: [{ correlationId: null }, { correlationId: "" }, { correlationId: { $exists: false } }]
})
```
Expected: 0 after full repair sweep.
