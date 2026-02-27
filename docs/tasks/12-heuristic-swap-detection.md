# Feature 12: Heuristic swap detection (outflow A + inflow B in same tx)

**Tasks:** T-032

**Doc refs:** ADR-019, ADR-018, docs/01-domain.md, docs/02-architecture.md, docs/03-accounting.md

---

## T-032 — Heuristic swap classification (one asset out, one asset in)

- **Module(s):** ingestion (classifier)
- **Description:** Add **heuristic** swap detection: for a given wallet and tx, if the tx has exactly **one distinct asset** in outflows (Transfers where wallet is `from`) and exactly **one distinct asset** in inflows (Transfers where wallet is `to`), and the two assets differ, classify those transfers as **SWAP_SELL** and **SWAP_BUY** instead of EXTERNAL_TRANSFER_OUT / EXTERNAL_INBOUND. Keep existing **topic-based** detection (Uniswap V2/V3, Velora, etc.) as a parallel path; when either heuristic or topic indicates swap, emit SWAP_SELL/SWAP_BUY. Apply **protocol precedence**: if a lending (or other protocol) classifier has already classified any event in the tx (e.g. BORROW, REPAY, LEND_*), do **not** apply the heuristic for that tx (leave as EXTERNAL_* or whatever the protocol classifier set).
- **Rationale:** Avoid hardcoding every new DEX Swap topic; cover aggregators, private pools, P2P; align classification with economic definition “gave A, received B” (ADR-019).
- **DoD:**
  1. **Heuristic in TransferClassifier or new classifier:** For each tx and wallet, from the tx’s Transfer logs (wallet is `from` or `to`), compute:
     - set of distinct `assetContract` where wallet is `from` (outflow assets),
     - set of distinct `assetContract` where wallet is `to` (inflow assets).
     If both sets have size 1 and the single outflow asset ≠ single inflow asset, treat as swap: emit SWAP_SELL for each outflow Transfer (quantity negative) and SWAP_BUY for each inflow Transfer (quantity positive). If multiple Transfer logs for the same asset (e.g. two sends of USDC), aggregate into one logical outflow/inflow per asset (e.g. one SWAP_SELL with summed quantity).
  2. **Integration with existing SwapClassifier / TransferClassifier:** Do not duplicate logic. Options: (a) TransferClassifier first computes heuristic; if heuristic says swap for this tx, emit SWAP_SELL/SWAP_BUY instead of EXTERNAL_*; else if no known Swap topic, emit EXTERNAL_* as today. (b) Or: SwapClassifier runs first and, in addition to topic-based detection, applies heuristic when no Swap topic is present; if heuristic says swap, emit SWAP_SELL/SWAP_BUY from Transfer logs. (c) Or: new HeuristicSwapClassifier that runs after TransferClassifier and before or after SwapClassifier; if heuristic applies, replace EXTERNAL_* events for that (tx, wallet) with SWAP_* events. Choose (a), (b), or (c) so that topic-based and heuristic are both supported and protocol classifiers take precedence.
  3. **Protocol precedence:** Before applying heuristic for a tx, check whether any raw event for that tx was already classified as a protocol type (e.g. BORROW, REPAY, LEND_DEPOSIT, LEND_WITHDRAWAL, or other non-transfer types from LendClassifier / similar). If yes, do not apply heuristic for that tx (leave TransferClassifier output as EXTERNAL_* for that tx). If no protocol classifier produced events for this tx, or only SWAP_* / EXTERNAL_* are present, then apply heuristic.
  4. **Same tx, multiple wallets:** Heuristic is per (tx, wallet). Each wallet is evaluated independently (outflow/inflow sets are per wallet).
  5. **Unit tests:** (i) Tx with one Transfer out (USDC) and one Transfer in (WBTC) for wallet W, no Swap topic → SWAP_SELL (USDC) and SWAP_BUY (WBTC). (ii) Tx with two Transfer out (USDC to A, USDC to B) and one Transfer in (ETH) → one outflow asset (USDC), one inflow asset (ETH), sizes 1 and 1, different → one SWAP_SELL (USDC, aggregated quantity) and one SWAP_BUY (ETH). (iii) Tx with two different assets out (USDC and DAI) and one in (ETH) → two outflow assets → do not classify as swap (leave EXTERNAL_*). (iv) Tx with known Swap topic (e.g. Uniswap V2) → still get SWAP_SELL/SWAP_BUY (topic path). (v) Tx with one out, one in, but LendClassifier already produced BORROW for this tx → do not apply heuristic; keep EXTERNAL_* for the transfers. (vi) Same asset out and in (e.g. send USDC, receive USDC from another address) → do not swap (outflow asset equals inflow asset).
- **Dependencies:** Existing TransferClassifier, SwapClassifier, LendClassifier (or equivalent); TxClassifierDispatcher order.

---

## Implementation steps

### Step 1: Heuristic logic (single place)

- Implement: given `List<Document> logs` (Transfer logs only), `String walletAddress`, return whether the heuristic applies and, if so, the sets (outflowContracts, inflowContracts) or the events to emit.
- Rule: collect from logs all (assetContract, quantity, from, to, logIndex) where topic0 = Transfer; for wallet as `from` → outflow by assetContract; for wallet as `to` → inflow by assetContract. If distinct outflow contracts size == 1 and distinct inflow contracts size == 1 and they differ → swap. Quantities: aggregate by asset (sum amounts per asset for outflows, for inflows).

### Step 2: Wire into classification pipeline

- **Option A:** In TransferClassifier: after building EXTERNAL_TRANSFER_OUT / EXTERNAL_INBOUND list for the tx, check heuristic; if (outflow assets size 1, inflow assets size 1, different), and no protocol event for this tx (see Step 3), replace with SWAP_SELL / SWAP_BUY (same quantities, same logs).
- **Option B:** SwapClassifier: when building events from Transfer logs, first check topic-based swap; if no topic-based swap, then check heuristic; if heuristic holds, still emit SWAP_SELL/SWAP_BUY. TransferClassifier: when heuristic holds for tx (and no protocol), return empty so SwapClassifier’s output is used; or TransferClassifier also emits SWAP_* when heuristic holds. Avoid double emission.
- **Option C:** New HeuristicSwapClassifier that runs after TransferClassifier. For each tx in the batch, if TransferClassifier produced EXTERNAL_* events for wallet W, run heuristic for (tx, W); if heuristic says swap and no protocol event for tx, replace those EXTERNAL_* with SWAP_SELL/SWAP_BUY and drop the originals from the merged list. Dispatcher merges: run TransferClassifier, then HeuristicSwapClassifier to “promote” EXTERNAL_* to SWAP_* when heuristic applies.
- Recommended: **Option A** (minimal change, single classifier owns “is this a swap?” for both topic and heuristic).

### Step 3: Protocol precedence

- In TxClassifierDispatcher, classifiers run in order. If LendClassifier (or StakeClassifier, etc.) runs and produces events for a tx (e.g. BORROW), the tx is “protocol-classified”. Before applying heuristic in TransferClassifier (or in HeuristicSwapClassifier), check whether the **same tx** already has any event from another classifier whose type is not EXTERNAL_* / SWAP_* / INTERNAL_TRANSFER (e.g. BORROW, REPAY, LEND_DEPOSIT). If yes, do not apply heuristic for that tx. Implementation: either pass the “current events so far” into TransferClassifier (so it can see if tx already has BORROW etc.), or run protocol classifiers first and mark tx hashes that have protocol events, then TransferClassifier/SwapClassifier skip heuristic for those tx. Prefer: run TransferClassifier and SwapClassifier after other classifiers; when building the final list per tx, if the tx already has events from LendClassifier (or similar), do not run heuristic for that tx. Easiest: in TransferClassifier, we don’t have the list of other events; so either (1) run HeuristicSwapClassifier after all others and in it, for each tx, if any event for that tx is BORROW/REPAY/LEND_*, do not promote to SWAP_*, or (2) pass a Set<String> txHashesWithProtocolEvents into TransferClassifier. Option (1) is cleaner: a post-pass “HeuristicSwapClassifier” that only promotes EXTERNAL_* to SWAP_* when heuristic holds and tx has no protocol events.

### Step 4: Tests

- See DoD (5): at least 6 scenarios. Use RawTransaction with logs, wallet address, and optionally pre-existing event list for protocol precedence test.

---

## Acceptance criteria

- Txs with exactly one distinct asset out and one distinct asset in (different) for the wallet are classified as SWAP_SELL and SWAP_BUY without requiring a known Swap event topic.
- Txs with known Swap topic (Uniswap V2/V3, Velora) continue to be classified as swap.
- Txs with multiple distinct assets in outflows or inflows (e.g. two tokens out) are not classified as swap by heuristic; remain EXTERNAL_* unless topic-based swap applies.
- Txs already classified by a protocol classifier (e.g. BORROW) do not get heuristic swap; transfers remain EXTERNAL_*.
- Same asset out and in (A = B) is not a swap.
- `./gradlew :backend:test` passes.
- ADR-019 and this task are reflected in code and comments.

---

## Edge cases (document in ADR or code)

| Case | Handling |
|------|----------|
| Multi-hop (e.g. 2 out, 2 in) | Heuristic yields 2 SWAP_SELL and 2 SWAP_BUY; InlineSwapPriceEnricher skips (not exactly one sell and one buy). Documented in ADR-019. |
| Lending repay + collateral | Protocol classifier should fire; heuristic not applied. |
| P2P atomic swap | One out, one in → heuristic applies; SWAP_SELL/SWAP_BUY with SWAP_DERIVED. |
| Airdrop (in only) | No outflow → not swap. |
| Donation (out only) | No inflow → not swap. |
