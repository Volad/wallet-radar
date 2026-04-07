# Owner-Agnostic Transfer Taxonomy

Goal:

Make persisted `normalized_transactions` independent from the current accounting universe for transfer-like events, while preserving deterministic replay-time continuity for same-owner moves.

## Scope

In scope:
- rename `EXTERNAL_INBOUND` to `EXTERNAL_TRANSFER_IN`
- stop emitting persisted `INTERNAL_TRANSFER` for new reruns
- persist continuity hints separately from canonical type:
  - `correlationId`
  - `continuityCandidate`
  - `matchedCounterparty`
- keep `BRIDGE_OUT` / `BRIDGE_IN` as objective bridge semantics
- update pricing / stat / replay to honor replay-time continuity instead of persisted internality
- prepare Mongo for full rerun of on-chain normalization, Bybit normalization, clarification, pricing, and replay

Out of scope:
- redefining bridge semantics
- changing LP / vault / lending accounting families
- changing external price-source ordering
- introducing per-user or multi-tenant storage partitioning

## Acceptance Criteria (DoD)

1. New canonical inbound transfer rows persist as `EXTERNAL_TRANSFER_IN`, not `EXTERNAL_INBOUND`.
2. New reruns do not emit persisted `INTERNAL_TRANSFER` for on-chain wallet-to-wallet or Bybit/on-chain matched transfer continuity.
3. On-chain tracked-wallet counterparty detection persists owner-agnostic `EXTERNAL_TRANSFER_OUT` / `EXTERNAL_TRANSFER_IN` plus `continuityCandidate=true` and `matchedCounterparty`.
4. Matched Bybit withdraw/deposit correlation persists owner-agnostic `EXTERNAL_TRANSFER_OUT` / `EXTERNAL_TRANSFER_IN` plus `correlationId`, `continuityCandidate=true`, and `matchedCounterparty`.
5. `BRIDGE_OUT` and `BRIDGE_IN` remain persisted canonical bridge facts even when the source and destination wallets differ.
6. Replay carries basis for matched same-universe transfer continuity without requiring persisted `INTERNAL_TRANSFER`.
7. Replay does not carry basis for unmatched external transfer rows solely because they have external transfer type.
8. Pricing does not require market pricing for principal flows when a transfer row is explicitly marked as a matched continuity candidate.
9. Stat validation remains safe for continuity-confirmed rows and does not demote them merely because principal transfer flows carry no market price.
10. Legacy `INTERNAL_TRANSFER` handling may remain for already persisted rows, but new normalization output must not depend on it.
11. No on-chain bridge family regresses from `BRIDGE_OUT` / `BRIDGE_IN` into generic transfer families.
12. Mongo rerun prep resets derived pipeline state without deleting raw source evidence.

## Edge Cases

- Case: wallet A sends native asset to tracked wallet B on the same network.
  - Scope: In
  - Expected: persisted sender row is `EXTERNAL_TRANSFER_OUT`, persisted receiver row is `EXTERNAL_TRANSFER_IN`, both carry continuity metadata; replay may carry basis when both wallets belong to the active accounting universe.

- Case: wallet sends funds to Bybit deposit address and matching Bybit deposit row exists.
  - Scope: In
  - Expected: both canonical sides stay external transfer facts with continuity metadata; replay upgrades them into one carry-over move.

- Case: a different installation or future user does not have the counter-side evidence.
  - Scope: In
  - Expected: persisted type remains valid owner-agnostic transfer fact; continuity is not hard-coded into canonical type.

- Case: bridge sends asset across networks between two different owner wallets.
  - Scope: In
  - Expected: persisted type remains `BRIDGE_OUT` / `BRIDGE_IN`; ownership is resolved later by replay correlation rules, not by transfer-type collapse.

- Case: legacy rows already stored as `INTERNAL_TRANSFER`.
  - Scope: In
  - Expected: replay remains backward-safe, but rerun output migrates to the new taxonomy.

## Task Breakdown

1. `BE-07F` transfer taxonomy contract closeout
   - rename canonical inbound fallback type to `EXTERNAL_TRANSFER_IN`
   - update domain docs, accounting docs, and normalization docs

2. `BE-07G` on-chain owner-agnostic continuity metadata closeout
   - replace new `INTERNAL_TRANSFER` emission with external transfer facts plus continuity metadata
   - preserve `BRIDGE_OUT` / `BRIDGE_IN` semantics

3. `BE-07H` Bybit/on-chain correlation metadata closeout
   - keep matched transfer rows owner-agnostic in canonical type
   - persist `correlationId`, `continuityCandidate`, and `matchedCounterparty`

4. `BE-07I` replay and pricing continuity derivation closeout
   - derive move-basis continuity from metadata during replay
   - suppress duplicated principal pricing for matched continuity candidates

5. `BE-07J` rerun preparation pack
   - reset `normalized_transactions`, `asset_positions`, and raw/Bybit processing state
   - keep `raw_transactions.rawData` and `external_ledger_raw` source evidence intact
   - provide deterministic rerun commands / queries for the next normalization pass

## Risk Notes

- The main risk is silently preserving owner-specific semantics in globally persisted canonical rows.
- Renaming `EXTERNAL_INBOUND` is a semantic cleanup, but continuity relocation is the financially important change.
- If replay does not explicitly consume the new continuity metadata, the rerun will regress into ordinary external acquisition/disposal accounting.
- Bridge facts must remain separate from ordinary transfer facts because bridge continuity has different matching, audit, and failure semantics.
