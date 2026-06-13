# LiFi Supplemental Bridge Source Pairing — Implementation Plan

**Slug:** `lifi-bridge-topup-pairing`  
**Audit source:** `results/lifi-topup-pairing-blockers.md`  
**Blocker ID:** L-LIFI-SUPPLEMENTAL-01  
**Review status:** APPROVED + IMPLEMENTED (2026-06-07)  
**Created:** 2026-06-07

---

## Scope

- **Wallet:** `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f`
- **Networks:** MANTLE → ARBITRUM
- **Txs:** `0x585aef…` (supplemental WETH), `0x8b471042…` (principal USDe), `0x826189…` (destination)
- **Component:** `LiFiBridgePairLinkService`, tests, `docs/pipeline/normalization/rules/protocols/li-fi.md`
- **Out of scope:** Other unlinked `BRIDGE_OUT` rows (~27) with different failure modes (status miss, no destination, asset mismatch)

---

## Root Cause

**Stage:** `linking`

LiFi allows multiple sending txs for one receiving tx (principal stable leg + supplemental native top-up). Current linker treats the second source as a full pair attempt, hits `shouldPreserveExistingDestinationPairing`, and exits without anchoring the supplemental source.

---

## Ordered Changes

### Task 1 — Supplemental source-only materialization

**File:** `LiFiBridgePairLinkService.java`

When `shouldPreserveExistingDestinationPairing(source, destination)` is true **and** official LiFi status proves this source → this destination:

1. Call new `materializeSupplementalSourceAnchor(source, destination)` instead of returning.
2. Set on source only:
   - `matchedCounterparty = destination.txHash`
   - `correlationId = bridge:lifi:<source.txHash>`
   - `continuityCandidate = supportsPlainMoveBasis(source, destination)`
3. Do not read or write destination pairing fields.
4. Guard: only when `readExistingStatus(raw)` or fetched status has `receivingTxHash == destination.txHash` and `apiStatus=DONE`.

Optional leg hint (if needed for replay): store supplemental inbound asset match in existing clarification/link metadata only if already used elsewhere — prefer no new fields unless replay requires them.

### Task 2 — Update unit tests

**File:** `LiFiBridgePairLinkServiceTest.java`

Revise `laterLiFiSourceCannotOverwriteAlreadyMaterializedReciprocalDestinationPair`:

- Destination still paired only to principal (`matchedCounterparty = 0x8b471042…`)
- Top-up source **gains** anchor: `matchedCounterparty = 0x826189…`, `correlationId = bridge:lifi:0x585aef…`
- Assert destination flows unchanged (USD₮0 TRANSFER + ETH BUY)

Add negative test: unrelated second source with different LiFi destination must remain unlinked.

### Task 4 — Supplemental bridge queue in AVCO replay (stage: move_basis)

**File:** `ReplayPendingTransferKeyFactory.java`

**Change:** When a `BRIDGE_IN` inbound `TRANSFER` flow carries `counterpartyAddress = LINKED:<supplementalSourceTxHash>`, build `bridgeTransferKey` from `bridge:lifi:<supplementalSourceTxHash>` so supplemental OUT/IN legs share one queue without overwriting the principal destination pair.

---

### Task 3 — Documentation

**File:** `docs/pipeline/normalization/rules/protocols/li-fi.md`

Add section **Multi-source / supplemental LiFi routes**:

- Multiple LiFi status rows may reference one receiving tx
- First materialized pair wins destination reciprocal anchor (principal stable leg)
- Supplemental sources receive source-only anchor; must not overwrite destination counterparty
- WETH→ETH supplemental legs may enable plain move-basis continuity when canonical asset family matches

---

## Acceptance Criteria

1. After rebuild + linking sweep, `0x585aef…` has `matchedCounterparty = 0x826189…` and non-empty `correlationId`.
2. `0x826189…` remains paired to `0x8b471042…` only (principal stable leg unchanged).
3. Prod scan: all LiFi DONE supplemental sources pointing to an already-paired destination receive source anchor (currently 1 row).
4. `LiFiBridgePairLinkServiceTest` green; no regression on single-source LiFi pairs.
5. Financial auditor confirms WETH OUT ↔ ETH IN leg linkage in move-basis timeline (post-replay).

---

## Risks

| Risk | Mitigation |
|------|------------|
| False-positive supplemental anchor if LiFi status collides on receiving hash | Require DONE status + qty-compatible inbound leg via `selectPrimaryInboundBridgeFlow` |
| Replay double-counts basis on multi-flow destination | Source-only anchor; do not retag destination flows on supplemental pass |
| Ordering sensitivity | `reconcileOutstandingSources` already processes pending then anchored batches; supplemental pass is idempotent |

---

## Verification

```bash
./scripts/prod-reset-rebuild-backend.sh --skip-frontend
```

Then Mongo checks:

- `0x585aef…` anchored
- `0x826189…` principal pairing unchanged
- Re-run financial-logic-auditor against acceptance list
