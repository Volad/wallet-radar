# Run 34 — ETH Proof Accuracy and Pass-through Corridors Closeout

## Goal

Improve ETH-family proof accuracy without rolling back the new Bybit continuity
path by fixing the replay-stage pooling drift that currently inflates
user-facing AVCO on the destination lot.

## Problem

Current ETH-family audit showed two distinct mechanisms:

1. some upstream lots already arrive partially uncovered before they touch
   Bybit
2. a proven continuity inbound then pools into existing inventory and the later
   outbound/custody source leg consumes pooled AVCO instead of the exact carried
   basis

The most visible example is the Arbitrum -> Bybit -> Mantle ETH corridor:

- Arbitrum outbound to `BYBIT:33625378` is proven continuity
- Bybit outbound to Mantle is also proven continuity
- Mantle inbound is later deposited into `aManWETH`

Current replay restores the carried basis into the venue/local bucket, but then
lets the later outbound consume pooled inventory. When the bucket already
contains unrelated inventory, the destination AVCO drifts upward.

## Audit Findings

### Bybit transit drift

Before the large `3.06 ETH` inbound, Bybit already held a smaller ETH position:

- quantity: `0.08887217`
- total cost basis: `322.5821415522728166036887075606853`
- AVCO: `4017.687341360726999592488640508289`

After the continuity inbound from Arbitrum, replay pooled both inventories:

- quantity after inbound: `3.14887217`
- total cost basis after inbound: `5604.649089359266906553882978371546`
- AVCO after inbound: `2936.982508325686990777961103682842`

The later Bybit outbound to Mantle then removed the pooled AVCO instead of the
exact carried transit basis.

### Bridge-in to custody drift

The bridge-link itself is already present for several Arbitrum ETH rows.
However, the later custody-source outflow still consumes pooled local inventory.

Reference example:

- `BRIDGE_IN` on Arbitrum:
  - quantity: `0.79899917984786304`
- later `LENDING_DEPOSIT` source leg:
  - quantity: `0.798`

Replay today removes the pooled spot ETH position when the deposit happens,
instead of consuming the exact carried bridge basis first.

## Target Policy

1. Replay may build deterministic `pass-through corridors` from already
   confirmed canonical rows before AVCO application.
2. A corridor does not reclassify the row. It only reserves exact carry for one
   later deterministic outbound/custody source leg.
3. The first approved corridor families are:
   - custodial venue transit on `BYBIT:<uid>`
   - same-wallet same-network `BRIDGE_IN -> custody deposit source leg`
4. Corridor planning must stay conservative:
   - one unique open inbound candidate
   - same exact replay bucket identity
   - bounded quantity drift
   - no competing same-bucket negative principal flow before the paired outbound
5. If proof is not unique, replay must fall back to the ordinary pooled AVCO
   path.

## Scope

In scope:

- document replay-level corridor reservations
- implement deterministic corridor planning in `AvcoReplayService`
- consume reserved carry before pooled AVCO on paired outflows
- cover:
  - Bybit transit corridor
  - `BRIDGE_IN -> LENDING_DEPOSIT` corridor
- add regression tests
- prepare Mongo for rerun from current raw evidence

Out of scope:

- changing normalization/classification
- changing bridge-pair link services
- inventing portfolio-wide lot tracking
- frontend AVCO warning/display changes

## Acceptance Criteria

1. Replay can detect a unique proven continuity inbound on `BYBIT:<uid>` and
   reserve its carry for the next deterministic venue outbound from the same
   exact ETH-family bucket.
2. Replay can detect a unique proven same-wallet `BRIDGE_IN` and reserve its
   carry for the next deterministic custody-source outflow from the same exact
   ETH-family bucket.
3. Paired outflows consume reserved carry first and only then fall back to
   pooled AVCO, if remainder exists.
4. Existing continuity behavior remains unchanged when no corridor is provable.
5. Targeted tests prove:
   - pre-existing Bybit ETH inventory is not allowed to poison transit ETH carry
   - pre-existing local spot ETH inventory is not allowed to poison
     `BRIDGE_IN -> LENDING_DEPOSIT` carry
6. Current Mongo can be prepared for rerun without deleting raw evidence or
   re-running full backfill from scratch.

## Detailed Tasks

### FLA-103-01 Audit Pack

1. Freeze the current ETH audit facts in docs:
   - upstream partial coverage exists before Bybit
   - current Bybit venue pooling inflates the destination lot AVCO
   - several bridge links are already present, so bridge-pair discovery is not
     the immediate blocker for this slice

### SA-103-02 Architecture

1. Keep pipeline order unchanged.
2. Place corridor planning strictly inside accounting replay.
3. Keep corridor planning deterministic and canonical-row-only.
4. Do not introduce new persistence for corridor reservations in this slice.

### BA-103-03 Requirements

1. Define supported corridor families for the first slice.
2. Define uniqueness and tolerance rules.
3. Explicitly keep ambiguous cases on the pooled fallback path.
4. Define regression scenarios and expected accounting outcome.

### BE-103-04 Backend

1. Add replay-time corridor planner to `AvcoReplayService`.
2. Add reservation creation on proven continuity inbound application.
3. Add reserved-carry-first consumption on paired outflows.
4. Preserve current replay semantics for all non-paired rows.
5. Add targeted regression tests for:
   - Bybit transit isolation
   - bridge-in to lending-deposit isolation
6. Prepare current Mongo state for rerun from normalization/pricing/replay.

### FE-103-05 Frontend

No frontend work in this slice.

## Expected Outcome

After this slice:

- ETH-family destination lots carry transit/bridge basis more accurately
- pre-existing venue/local inventory no longer poisons deterministic pass-through
  corridors
- the current session can be re-normalized and replayed from existing raw
  evidence on the new accounting logic
