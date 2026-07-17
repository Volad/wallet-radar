# Implementation Plan — LP zap-in basis routing (NEW-01, VBETH LP_EXIT UNKNOWN)

## Scope

- **Blocker:** NEW-01 (HIGH) — single-token V3 "zap-in" LP entries leak principal basis; the paired
  LP exit falls back to `UNKNOWN` → fresh market ACQUIRE.
- **Wallet / network:** `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f` on `KATANA` (SushiSwap V3).
  Reproduced position: `lp-position:katana:0x2659c6085d26144117d904c46b48b6d180393d27:36201`
  (vbETH/vbUSDC pool, native-ETH zap-in).
- **Assets:** FAMILY:ETH principal (vbETH/native ETH). Generalises to any single-token CL zap-in that
  refunds dust of a sibling pool token.
- **Out of scope:** BASE Uniswap/Pancake V3 multi-token entries (already outbound-only), Balancer/Curve
  same-tx receipt mints, Dzengi fiat label (NEW-02, label-only), pre-backfill evidence gaps.

## Root cause (from Phase 1 `financial-logic-auditor`)

The routing gate `LpReceiptEntryReplayHandler.hasOnlyOutboundPrincipalFlows(NormalizedTransaction)`
aggregates net quantity **by raw asset symbol**. A CL zap-in mints the position from a single deposited
token (native ETH) but the router refunds **dust** of the two pool tokens (vbETH ≈ 6.57e-16, vbUSDC ≈
0.000002) in the same transaction. Those dust legs are net-positive per raw symbol, so the method
returns `false`.

That single `false` disables **both** basis-carry paths that `PositionScopedLpExitReplayHandler`
later reads:

1. `LpReceiptEntryReplayHandler.isLpReceiptEntry` → `false` ⇒ **no `lp_receipt_basis_pools` row** is
   created (confirmed: 0 docs for the corrId).
2. `GenericAsyncLifecycleReplayHandler.isPositionScopedLpEntryOutbound` is gated on the same predicate
   ⇒ the −0.45 ETH deposit **never enters** `asyncLifecycleBucket(corrId)`.

Instead the ETH deposit is booked through `transferReplayHandler.applyTransfer` (generic
continuity/pending-transfer bucket) as `REALLOCATE_OUT` of **$2,122**. At exit the vbETH inflow is
matched against only (a) the LP receipt pool and (b) the async bucket — both empty — so it resolves to
`UNKNOWN` and is acquired at exit market price ($3,605.59) instead of carrying the $2,122 entry basis.

**Confirmed non-cause:** continuity-identity mapping is correct — `VBETH → FAMILY:ETH` in both
`AccountingAssetFamilySupport.SYMBOL_FAMILIES` and `AccountingAssetClassificationSupport`
(same family as native ETH). Once the entry routes to the receipt-pool path, the existing exit-side
`restoreInboundFromLpReceiptPool` already resolves `continuityIdentity(vbETH) = FAMILY:ETH` and carries
the basis with no exit-side change.

**Financial impact:** ~$2,122 tax basis leaked (~$416 net) on FAMILY:ETH; surviving vbETH lot AVCO
distorted to $3,605.59 vs authoritative ~$4,715.

## Changes (ordered)

### 1. Make the routing gate materiality-aware (single change, core defect)

File: `backend/core/src/main/java/com/walletradar/application/costbasis/application/replay/handler/LpReceiptEntryReplayHandler.java`
Method: `static boolean hasOnlyOutboundPrincipalFlows(NormalizedTransaction)`.

Replace raw-symbol netting with **continuity-family netting + USD materiality**:

- Aggregate net **quantity** and net **USD value** per `AccountingAssetFamilySupport.continuityIdentity(assetSymbol, assetContract)`
  (FEE + LP-receipt markers excluded, as today). Netting by family collapses the vbETH dust refund into
  the same FAMILY:ETH bucket as the native-ETH deposit, so that family stays net-outbound.
- A net-**positive** family disqualifies the receipt-pool path **only when its net inbound is material**.
  Materiality test (USD, robust to cross-asset quantity scale):
  - compute `totalOutboundUsd` = sum of |net USD| over net-outbound families;
  - a net-positive family is **dust** when `netInboundUsd < max($1.00, 1% × totalOutboundUsd)`;
  - USD per flow uses `valueUsd` when present, else `|quantityDelta| × unitPriceUsd`; a net-positive
    family with **no resolvable USD** but **quantity below a dust epsilon relative to the largest
    outbound quantity in its own family** is also treated as dust (covers unpriced dust like vbUSDC).
- Require at least one **material** net-outbound family (unchanged intent: this must be a real deposit).
- Any **material** net-positive family still returns `false` (preserves Curve/Balancer rejection where
  the pool returns a materially different asset).

This is a pure predicate refinement; it only *admits additional* zap-in shapes into the clean
receipt-pool path and never removes shapes that pass today (all-outbound remains all-outbound).

### 2. No exit-side change

`PositionScopedLpExitReplayHandler.restoreInboundFromLpReceiptPool` already handles same-family carry
and cross-asset proportional drain. Once the pool exists (keyed `FAMILY:ETH`), the vbETH exit carries
the basis. `shouldIgnoreLpReceiptMarker` calls the same predicate, so its behaviour stays consistent
for the newly-admitted shape (synthetic inbound receipt marker ignored on entry).

## Docs

- `docs/pipeline/cost-basis/03-basis-pools-and-carry.md` — add a subsection: "Single-token CL zap-in
  entries" describing family-net + dust-tolerance routing into `lp_receipt_basis_pools`.
- `results/lp-exit-rebalancing-protocol-rule-pack.md` — add the KATANA zap-in rule (dust sibling-token
  refunds do not disqualify receipt-pool routing).
- No new ADR: this refines the existing basis-pool routing contract (ADR-040/ADR-045 remain valid);
  no accounting-policy change.

## Acceptance

After `./scripts/prod-reset-rebuild-backend.sh --skip-frontend` and full replay:

1. `lp_receipt_basis_pools` has a row for `lp-position:katana:…36201` keyed on `FAMILY:ETH` with
   entry basis ≈ $2,122.
2. LP_ENTRY `0x67f4e9e1…`: native ETH −0.45 → `REALLOCATE_OUT`; the synthetic LP-RECEIPT carries
   ≈ $2,122 (not $0, `uncoveredQuantityAfter = 0`).
3. LP_EXIT `0xa12ccafc…`: vbETH inflow → `REALLOCATE_IN` (not `UNKNOWN`); `avcoAfterUsd` ≈ $4,715 for
   the carried portion (not $3,605.59); no fresh market ACQUIRE of the carried quantity.
4. **No regression:** BASE positions #477096 (≈ $2,088.58 @ ~$3,866.81/ETH), #472497, #646414 still
   carry basis; global metrics unchanged except the corrected FAMILY:ETH basis; no new
   `basisEffect=UNKNOWN` material points introduced.
5. New unit tests in `LpReceiptEntryReplayHandlerTest` for: single-token zap-in with dust sibling
   refund (admitted), all-outbound multi-token (still admitted), material different-asset return
   (still rejected).
6. `financial-logic-auditor` re-run: NEW-01 closed; no new Medium+ pipeline defect.

## Risks

- **Threshold tuning:** too-loose materiality could admit a genuine Curve/Balancer mixed shape.
  Mitigation: dual absolute ($1) + relative (1%) USD gate, plus require a material outbound family;
  covered by the "material different-asset return still rejected" test.
- **Determinism:** family netting must be deterministic (LinkedHashMap iteration, no float). Use
  `BigDecimal` + `MathContext.DECIMAL128`, mirroring existing code.
- **Ordering:** none — predicate is pure over the transaction's flows.
