# 21 — Classifier Fast-Path Coverage and Enrichment Optimization

## T-049 — Reduce external enrichment calls and `NEEDS_REVIEW` via deterministic classifier fast-paths

- **Module(s):** `ingestion/classifier`, `ingestion/job/classification`, `ingestion/config`, `docs`
- **Roles:** business-analyst + system-architect requirements implemented by worker

### System Architect decision

Introduce a deterministic pre-enrichment classification policy that uses the cheapest available evidence first and requests explorer enrichment only when classifier-specific evidence is missing.

Design constraints:

- Selector resolution must be unified in `RawTransactionNormalizationView`:
  - prefer explicit `methodId` when valid;
  - fallback to first 4 bytes from `input` (`0x????????`) when `methodId` is empty/`0x`.
- Classifiers must consume selector/address helpers only from `RawTransactionNormalizationView` (no direct raw JSON parsing in classifiers).
- Enrichment policy remains syncMethod-driven, but with classifier-aware short-circuit:
  - do not call `getTransactionDetails` / `getReceipt` if the transaction is already classifiable with medium/high confidence from existing raw payload;
  - do not call enrichment for known no-benefit patterns (value=0, no token/internal flows, unknown selector + unknown target contract).
- Keep strict evidence policy:
  - `details` = contextual hints only (method/protocol/contract metadata);
  - economic flows are sourced from canonical logs (or synthetic fallback from explorer transfer lists when logs are unavailable).
- Keep deterministic conflict resolution in dispatcher:
  - avoid emitting duplicated semantics for the same tx movement (for example LP + generic transfer duplicates).

### Business Analyst acceptance criteria (DoD)

1. **Selector fallback coverage**
   - For EVM transactions with `methodId="0x"` and non-empty `input`, classifiers still detect function signatures via selector fallback.
2. **No unnecessary enrichment**
   - If `rawData.explorer.details` already exists, details enrichment is not requested again.
   - If canonical logs already exist in `rawData.logs`, receipt enrichment is not requested again.
3. **Fast-path classification without logs/details**
   - Wrap/unwrap native (`deposit()` / `withdraw(uint256)`) is classified without extra explorer calls when tx envelope is sufficient.
   - Known bridge/perp no-log signatures are classified from selector+to+value when evidence is sufficient.
   - LP position NFT custody transfer (`safeTransferFrom`) can be classified as `LP_POSITION_STAKE` / `LP_POSITION_UNSTAKE` without receipt fetch when calldata provides sender/receiver/tokenId and contract is known LP position manager.
4. **Controlled low-signal fallback**
   - For unsupported low-signal transactions (no logs, no token/internal transfers, value=0, unknown selector/contract), pipeline marks `NEEDS_REVIEW` without repeated enrichment retries.
5. **Deterministic output**
   - Same raw input produces stable event set/type/order/confidence across reruns.
6. **No duplicate accounting semantics**
   - A tx movement is not emitted as both LP-specific and generic transfer/swap due to classifier overlap.

### Worker implementation scope

#### 1) Shared view/accessors

- Extend `RawTransactionNormalizationView` with canonical selector accessors and calldata helpers:
  - `selector()`, `hasSelector(String)`;
  - address/uint decoding helpers for common ABI patterns used by classifiers (at minimum `safeTransferFrom(address,address,uint256)`).
- Replace remaining direct methodId parsing in classifiers with view-level selector helpers.

#### 2) Classifier rule updates (by classifier)

- `NativeTransferClassifier`
  - rely on selector fallback (`deposit`/`withdraw`) even when explorer `methodId` is empty.
  - keep no-log fast-path classification for wrap/unwrap and simple native transfers.

- `BridgeCallClassifier`
  - use selector fallback;
  - expand known bridge signature/router allowlist from observed BASE/ARBITRUM patterns;
  - require wallet sender + positive native value for no-log bridge out classification.

- `PerpOrderClassifier`
  - use selector fallback for `createOrder`-style signatures when `methodId` is missing.

- `LpClassifier`
  - add no-log calldata-based detection for known LP position NFT custody transfer (`0x42842e0e`) on known position manager contracts;
  - classify wallet->strategy as `LP_POSITION_STAKE`, strategy->wallet as `LP_POSITION_UNSTAKE`;
  - keep economic LP_ENTRY/EXIT/FEE_CLAIM dependent on transfer evidence (canonical or synthetic transfer logs), not on details-only hints.

- `TransferClassifier` / `SwapClassifier` / `LendClassifier`
  - keep transfer-evidence-first policy;
  - ensure overlap guards with LP-specific classifiers are deterministic and do not emit duplicates.

#### 3) Dispatcher and enrichment policy

- `TxClassifierDispatcher`
  - add deterministic overlap resolution contract (single semantic owner for ambiguous patterns) and tests.
- `ClassificationProcessor`
  - add early no-benefit enrichment stop for low-signal tx;
  - keep syncMethod stage order, but skip stages when evidence already present;
  - preserve synthetic-confidence cap behavior.

#### 4) Observability

- Add counters/log markers for:
  - selector-fallback usage;
  - enrichment skipped (already-present evidence);
  - enrichment skipped (no-benefit heuristic);
  - low-signal direct `NEEDS_REVIEW`.

### Required tests

- `RawTransactionNormalizationViewTest`
  - selector fallback from input when `methodId=0x`;
  - calldata decode for `safeTransferFrom(address,address,uint256)`.

- `NativeTransferClassifierTest`
  - wrap/unwrap classification works with empty `methodId` and selector from input.

- `BridgeCallClassifierTest`
  - bridge out classification uses selector fallback;
  - unknown selector + zero value does not classify as bridge.

- `PerpOrderClassifierTest`
  - `createOrder` classification works with selector fallback.

- `LpClassifierTest`
  - no-log `safeTransferFrom` on known LP position manager -> `LP_POSITION_STAKE/UNSTAKE`;
  - LP economic events are not emitted from details-only hints without transfer evidence.

- `TxClassifierDispatcherTest`
  - overlap conflict case emits a single semantic owner result (no LP+TRANSFER duplicate).

- `ClassificationProcessorTest`
  - details/receipt enrichment skipped when already present;
  - low-signal no-benefit tx short-circuits to `NEEDS_REVIEW` without extra explorer calls.

### Notes

- This task is optimization-focused: reduce external explorer load and reduce avoidable `NEEDS_REVIEW` while keeping accounting correctness and deterministic behavior.
