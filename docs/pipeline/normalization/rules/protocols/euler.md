# Euler Protocol Rules

Status: Active protocol-owned semantic slice

## Scope

Cover Euler `batch(...)` families in two protocol-aware lanes:

- simple vault deposit / withdraw
- audited borrow-backed loop lifecycle

## Runtime Ownership

- Protocol semantic detection:
  [EulerProtocolSemanticClassifier.java](/Users/vladislavkondratenko/projects/wallet-radar/backend/src/main/java/com/walletradar/ingestion/pipeline/classification/onchain/protocol/euler/EulerProtocolSemanticClassifier.java)
- Family mapping:
  [LendingClassifier.java](/Users/vladislavkondratenko/projects/wallet-radar/backend/src/main/java/com/walletradar/ingestion/pipeline/classification/onchain/family/LendingClassifier.java)

## Protocol-Local Resources

- `backend/src/main/resources/protocols/euler.json`

Current active runtime profile owns:

- batch selector / function markers
- borrow-related log topic groups
- share/debt/stable asset marker groups used by loop detection
- audited loop-router vs simple-vault separation

## Authoritative Evidence

- `OnChainRawTransactionView`
- persisted token/internal transfers
- persisted full receipt logs
- protocol-specific bundle evidence derived by clarification

## Lifecycle Shapes

- `LENDING_DEPOSIT`
- `LENDING_WITHDRAW`
- `LENDING_LOOP_OPEN`
- `LENDING_LOOP_REBALANCE`
- `LENDING_LOOP_DECREASE`
- `LENDING_LOOP_CLOSE`

Current active semantic hints:

- `lending_deposit`
- `lending_withdraw`
- `lending_loop_open`
- `lending_loop_rebalance`
- `lending_loop_decrease`
- `lending_loop_close`

## Clarification Rules

- clarification is allowed when current raw evidence cannot distinguish simple
  supply/withdraw from loop open/rebalance/decrease/close
- clarification is not complete for Euler `batch(...)` rows when only receipt
  logs were persisted and transfer evidence is still empty
- audited native EVK deposits may still resolve to simple
  `LENDING_DEPOSIT` when clarification proves:
  - positive native tx `value`
  - share mint to the tracked wallet
  - one protocol-local fungible hop inside the clarified receipt
  - no share-burn / debt-close shape
- if production evidence still cannot prove the lifecycle, fallback must remain
  conservative and the row must stay `UNKNOWN / PENDING_CLARIFICATION`
- raw explorer transfers alone are not enough to open Euler lifecycle into
  active lending types
- non-loop-router `share burn -> stable return` is not enough to claim Euler
  loop close; with clarification it may resolve to simple
  `LENDING_WITHDRAW`, otherwise it stays review
- loop decrease / close remain reserved for the audited loop-router lane

## Lending market key (EVK)

- Per-vault grouping uses `matchedCounterparty` (EVK vault address) when present:
  `evk-vault-{address[2..10]}`
- Open position attachment uses the same key from ledger `matchedCounterparty`
  or balance `assetContract` (see ADR-025)
- Fallback without vault address: `evk-account`

## Correlation Rules

- exact async pairing is not the default
- correlation is protocol-owned and only persisted when deterministic lifecycle
  evidence exists

## Family Handoff

- protocol semantics hand off to `LendingClassifier`
- protocol semantic classifier now reads `euler.json` for batch/topic/asset
  markers before compatibility fallback heuristics
- `LendingClassifier` must keep non-loop-router simple vault rows on
  `LENDING_DEPOSIT` / `LENDING_WITHDRAW` only when clarification proves that
  lifecycle
- the `EULER_BATCH_DECODER_REQUIRED` reason must route those rows into the
  automatic receipt-clarification queue
- receipt-only clarified Euler rows with empty `tokenTransfers` /
  `internalTransfers` remain retryable for transfer-evidence recovery; they must
  not be treated as fully clarified

## Disallowed Fallbacks

- do not flatten proved loop bundles into plain `LENDING_DEPOSIT`
- do not convert internal swap/debt marker legs into generic realized spot swap
- do not classify simple vault `share -> stable` withdraw as
  `LENDING_LOOP_CLOSE`
- do not classify simple vault `stable -> share` deposit as generic `SWAP`
- do not classify proved native EVK `value -> share mint` deposits as generic
  `SWAP` merely because the principal-out leg is native rather than ERC-20

## Baseline and Regression Anchors

- loop family parity
- debt-marker continuity parity
- rebalance semantics parity
