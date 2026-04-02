# Run 43 LI.FI RPC Receiving Repair

Status: Done

Goal:

Close the remaining `run/43` accounting blocker by fixing deterministic
receiving-tx discovery on `RPC` networks, without widening bridge continuity
rules or changing the accepted `Bybit external custody` policy lane.

Related inputs:

- [run/43 audit report](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/43/clarification-readiness-audit.md)
- [run/43 audit summary](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/43/audit_summary.json)
- [run/43 unresolved LI.FI rows](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/43/lifi_unresolved_rows.tsv)
- [LI.FI protocol rules](../normalization/protocols/li-fi.md)
- [Accounting policy](../03-accounting.md)

## Problem Statement

`run/43` narrowed the live blocker to `7` official `LI.FI` receiving tx hashes
that are tracked-wallet relevant but still absent from both `raw_transactions`
and `normalized_transactions`.

Deeper inspection showed the root cause is infrastructural, not semantic:

1. `LiFiReceivingTransactionDiscoveryService` already performs deterministic
   tx-hash discovery by `receivingTxHash + receivingNetwork`.
2. But `ReceiptClarificationGateway.fetchRawTransactionByHash(...)` currently
   supports only explorer-backed fetch paths.
3. `BSC` is configured with `syncMethod = RPC`, so targeted receiving discovery
   returns empty even when the official receiving tx hash is correct and the tx
   does touch the tracked wallet.

This leaves valid `LI.FI` bridge destinations unmaterialized and blocks final
`move basis` readiness.

## Scope

In scope:

- RPC-backed `fetchRawTransactionByHash(...)`
- deterministic merge of `eth_getTransactionByHash + eth_getTransactionReceipt`
- token transfer derivation from receipt logs for the RPC path
- preservation of existing wallet-touch validation in LI.FI receiving discovery
- rerun preparation after implementation

Out of scope:

- fuzzy tx matching
- generic bridge discovery beyond official tx hashes
- changes to the `Bybit external custody` accounting policy
- baseline refresh

## Acceptance Criteria (DoD)

1. `ReceiptClarificationGateway.fetchRawTransactionByHash(...)` works for
   networks whose configured `syncMethod` is `RPC`.

2. The RPC fetch path persists enough canonical raw evidence for immediate
   normalization:
   - top-level tx fields
   - receipt status
   - receipt logs
   - derived token transfers from receipt logs
   - deterministic `rawTransaction.id = txHash:network:wallet`

3. `LiFiReceivingTransactionDiscoveryService` can now discover and normalize
   official receiving tx hashes on `BSC` through the RPC path when wallet-touch
   evidence exists.

4. Existing safeguards remain intact:
   - same-tx `LI.FI` echoes stay ignored
   - `outside tracked universe` receiving tx hashes still do not materialize
   - no synthetic `BRIDGE_IN` is created without real wallet-touch evidence

5. After rerun preparation:
   - raw backfill evidence stays intact
   - derived collections are cleared
   - raw normalization state is reset to pending

## Edge Cases

- RPC transaction exists, but receipt is missing
- RPC receipt exists, but token transfer evidence must be derived only from logs
- top-level `to` is not the tracked wallet, but ERC20 transfer recipient is
  tracked
- official receiving tx hash is valid but belongs outside the tracked universe
- official receiving tx hash is on a network with explorer sync, not RPC

## Task Breakdown

1. `BE-R43-01` Add RPC fetch-by-hash support in `ReceiptClarificationGateway`
2. `BE-R43-02` Lock regression with tests for:
   - RPC raw fetch construction
   - LI.FI receiving discovery on RPC network
   - outside-tracked tx rejection remains intact
3. `BE-R43-03` Update LI.FI rule docs to state that official tx-hash discovery
   also works on RPC-backed networks
4. `BE-R43-04` Prepare rerun
   - clear derived state
   - reset raw normalization statuses
   - keep valid raw backfill coverage intact

## Risk Notes

- This task fixes a transport/infrastructure gap, not the `LI.FI` semantic
  contract.
- `3` official receiving tx hashes still appear to be outside the tracked
  universe; they should remain non-blocking audit evidence, not synthetic
  bridge destinations.

## Completion Notes

- `ReceiptClarificationGateway.fetchRawTransactionByHash(...)` now supports
  `RPC` networks and constructs deterministic raw rows from
  `eth_getTransactionByHash + eth_getTransactionReceipt + eth_getBlockByNumber`.
- Targeted regression coverage added for the `BSC` RPC path.
- `LI.FI` docs updated to state that official tx-hash discovery must work on
  both explorer-backed and RPC-backed networks.
