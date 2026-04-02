# Run 42 LI.FI Wallet Relevance And Bybit External Custody Safety

Status: In progress

Goal:

Close the real root causes behind the residual `run/42` blockers without
inventing synthetic bridge continuity or synthetic acquisition/disposal.

Related inputs:

- [run/42 audit report](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/42/clarification-readiness-audit.md)
- [run/42 audit summary](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/42/audit_summary.json)
- [run/42 LI.FI unresolved rows](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/42/lifi_unresolved_rows.tsv)
- [run/42 Bybit external custody inventory](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/42/bybit_external_custody_inventory.tsv)
- [LI.FI protocol rules](../normalization/protocols/li-fi.md)
- [Bybit protocol rules](../normalization/protocols/bybit.md)
- [Accounting policy](../03-accounting.md)

## Problem Statement

Deeper inspection after `run/42` showed that the remaining blockers are narrower
than the original audit wording suggested:

1. The residual `LI.FI` `UNKNOWN / NEEDS_REVIEW` destination rows are not
   missing promotions from valid destination settlements. They are false-positive
   targeted receiving-tx discoveries created under the source wallet address,
   even though the fetched receiving tx does not actually touch any tracked
   wallet on-chain.
2. `Bybit external custody` rows are now correctly identified on the raw layer,
   but the normalized layer still keeps them as active priceable `BUY` / `SELL`
   rows, which is not accounting-safe.

## Scope

In scope:

- hard wallet-relevance validation for LI.FI receiving-tx discovery
- prevention of false-positive destination row materialization
- rerun cleanup of already persisted false-positive LI.FI destination artifacts
- accounting-safe normalized semantics for `Bybit external custody`
- rerun preparation after implementation

Out of scope:

- fuzzy bridge matching
- new external-venue move-basis engine
- baseline refresh

## Acceptance Criteria (DoD)

1. Official `LI.FI` receiving tx evidence is materialized only when the fetched
   tx has actual on-chain wallet relevance for the tracked wallet:
   - top-level `from` / `to`
   - token transfer `from` / `to`
   - internal transfer `from` / `to`
   The persisted `rawTransaction.walletAddress` alone is not sufficient proof.

2. False-positive LI.FI destination artifacts no longer remain in the rerun
   outcome:
   - no non-excluded `UNKNOWN` rows created only from wallet-address injection
   - official `receivingTxHash` with no tracked-wallet on-chain touch remains
     unresolved, not synthetic `BRIDGE_IN`

3. Same-tx LI.FI status echoes continue to be ignored:
   - no self-link
   - no synthetic destination row

4. `Bybit external custody` rows are accounting-safe on the normalized layer:
   - canonical type remains `EXTERNAL_TRANSFER_IN` / `EXTERNAL_TRANSFER_OUT`
   - no synthetic `correlationId`
   - no synthetic `matchedCounterparty`
   - no `BUY` / `SELL` flows
   - rows are explicitly outside active accounting scope until a dedicated
     external-custody move-basis contract exists

5. Existing good behavior stays intact:
   - audited pair
     `0x9f6983d00441ed13bf45e1b7ac34e94540fb61f58e4a9a2189826b1e761a2f7f`
     -> `0x7d8c79a327637fda080bcfa9204181359de791ccff95dd4b2f1b020b8af0b678`
     remains `BRIDGE_OUT -> BRIDGE_IN`
   - matched `Bybit <-> on-chain` continuity still materializes
   - `BYBIT SWAP / PENDING_PRICE = 604` remains intact

## Edge Cases

- official `LI.FI` status points to a tx hash that exists on-chain but does not
  touch any tracked wallet
- targeted receiving-tx fetch returns a tx whose persisted wallet address equals
  the requested wallet, but all actual on-chain movement stays outside the
  tracked wallet universe
- `Bybit external custody` deposit later returns from the same external venue
  with a different quantity
- same-tx status echo still carries `DONE / COMPLETED`

## Task Breakdown

1. `BE-R42-01` Harden LI.FI receiving discovery
   - validate wallet relevance from actual on-chain evidence only
   - stop treating injected `rawTransaction.walletAddress` as proof

2. `BE-R42-02` Prevent synthetic destination materialization
   - unresolved official receiving hashes without wallet-touch evidence remain
     unresolved bridge evidence, not `BRIDGE_IN` or `UNKNOWN` destination rows

3. `BE-R42-03` Make Bybit external custody accounting-safe
   - normalize into explicit audit-only excluded rows
   - remove synthetic economic `BUY` / `SELL` semantics

4. `BE-R42-04` Lock regressions with tests
   - LI.FI irrelevant receiving tx is rejected
   - valid audited LI.FI destination still materializes
   - Bybit external custody no longer creates active priceable rows
   - matched Bybit continuity still works

5. `BE-R42-05` Prepare rerun
   - remove false-positive LI.FI destination artifacts from raw + normalized
   - clear derived collections
   - reset raw normalization state
   - keep valid raw backfill evidence intact

## Risk Notes

- Official protocol status remains authoritative for `receivingTxHash`, but it
  is not authoritative proof that the destination settlement belongs to the
  tracked wallet universe.
- `Bybit external custody` remains a future `move basis` policy lane; this task
  only makes the current normalization/accounting pipeline safe.
