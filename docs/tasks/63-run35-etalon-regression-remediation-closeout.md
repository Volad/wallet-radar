# Run 35 Etalon Regression Remediation Closeout

Status: Implemented, rerun pending

Goal:

Keep the current semantic improvements that look correct under raw evidence,
and fix only the true regressions introduced after the latest classification +
clarification refactor.

This slice is intentionally narrow:

- keep the current `Euler EVK` semantic improvements
- fix the `11` `Velodrome Slipstream` approve regressions
- fix the `3` `LI.FI` bridge-start regressions
- do not hide the existing `Bybit <-> on-chain` continuity gap

Related inputs:

- [run/35 audit report](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/35/clarification-readiness-audit.md)
- [run/35 audit summary](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/35/audit_summary.json)
- [run/35 normalization rule updates](/Users/vladislavkondratenko/projects/wallet-radar/results/clarification/run/35/normalization_rule_updates.md)
- [ADR-001](../adr/ADR-001-onchain-classification-strangler-refactor.md)
- [LP family rules](../normalization/families/lp.md)
- [Bridge family rules](../normalization/families/bridge.md)

## Problem Statement

Run/35 proved that the current outcome is not etalon-equal:

- `changedRows = 39`
- `14` rows are real regressions
- `25` rows are likely approved semantic fixes or metadata enrichment

Regression group A:

- `11` on-chain `Velodrome Slipstream` rows drifted from
  `APPROVE / CONFIRMED` to `LP_ENTRY / NEEDS_REVIEW`
- current raw evidence shows only recovered approve selector from
  `rawData.input[0:10]` and zero movement
- these rows must not become accounting-bearing LP rows

Regression group B:

- `3` on-chain bridge rows drifted from `BRIDGE_OUT` to
  `EXTERNAL_TRANSFER_OUT`
- all three call `0x1231deb6f5749ef6ce6943a275a1d3e7486f4eae`
- all three have positive native value and bridge-route calldata tags such as
  `relay`, `across`, `jumper.exchange`
- these rows must keep bridge continuity semantics

Non-regression group:

- current `Euler EVK` drift looks more accurate than the etalon and should stay
  in place after careful regression-lock tests

Explicit non-goal for this slice:

- `Bybit` rows with `BRIDGE_ON_CHAIN_LEG_NOT_FOUND` remain a separate readiness
  blocker, but they are not a run/35 regression and must not be silently
  redefined in this narrow fix

## Scope

In scope:

- selector-precedence fix for approve rows when `rawData.methodId` is blank but
  `rawData.input` proves `approve(address,uint256)`
- preventing address-only LP protocol demotion from overriding recovered
  selector-level non-economic evidence
- route-tagged `LI.FI / Jumper` bridge-start recovery for known bridge-router
  calldata on observed production networks
- protocol-registry coverage for the audited `LI.FI Diamond` address on the
  observed networks if current raw evidence proves the deployment is active
- regression tests for both audited regression families
- documenting `Euler` drift as an approved semantic-fix candidate to keep

Out of scope:

- broad bridge-pair reconstruction
- `Bybit <-> on-chain` transfer matching redesign
- new clarification fetch families
- baseline refresh itself
- manual tx-hash-specific logic

## Acceptance Criteria (DoD)

1. A tx to trusted `Velodrome Slipstream Position Manager`
   `0x416b433906b1b72fa758e166e239c43d68dc6f29` with:
   - `rawData.methodId = 0x`
   - `rawData.input` starting with `0x095ea7b3`
   - `rawData.value = 0`
   - no token or internal transfers
   must classify to:
   - type `APPROVE`
   - status `CONFIRMED`
   and must not classify to `LP_ENTRY`.

2. Selector-derived non-economic evidence must outrank address-only protocol
   family fallback.
   - recovered `approve` from saved calldata is authoritative enough for
     `APPROVE`
   - contract identity alone is not enough to emit `LP_ENTRY`

3. A tx to known `LI.FI Diamond`
   `0x1231deb6f5749ef6ce6943a275a1d3e7486f4eae` on the observed production
   networks with:
   - positive native value or equivalent outbound movement
   - saved calldata containing official route tags such as `relay`,
     `across`, `jumper.exchange`
   - selector recovered from `rawData.input` even when top-level `methodId` is
     blank
   must classify to:
   - type `BRIDGE_OUT`
   and must not demote to `EXTERNAL_TRANSFER_OUT`.

4. The current `Euler EVK` semantic changes must remain intact:
   - `SWAP -> LENDING_LOOP_CLOSE`
   - `LP_EXIT -> LENDING_LOOP_CLOSE`
   - `SWAP -> LENDING_LOOP_REBALANCE`
   - `protocolVersion null -> v1`

5. No runtime code may special-case the audited transaction hashes.

6. After the next normalization + clarification rerun, the expected etalon diff
   may still contain the approved `Euler` semantic-fix set, but it must no
   longer contain:
   - the `11` `Velodrome` approve regressions
   - the `3` bridge continuity regressions

7. `Bybit` rows with `BRIDGE_ON_CHAIN_LEG_NOT_FOUND` must remain explicit
   blocker state and must not be treated as silently solved by this slice.

## Normalization Rules To Update

1. `rawData.input[0:10]` is authoritative selector evidence when
   `rawData.methodId` is blank or `"0x"`.

2. Address-only protocol identity for LP position managers may not override
   higher-fidelity selector-level non-economic evidence.

3. `LI.FI / Jumper` bridge-start recognition must accept route-tagged calldata
   on known bridge-router contracts even when the recovered selector is outside
   the current narrow allowlist, as long as:
   - bridge-route tags are present in saved calldata
   - native bridge funding or equivalent outbound movement is present

4. `Euler EVK` close / rebalance semantics are accepted as approved semantic
   fixes and should stay baseline-different until the next baseline refresh.

## Edge Cases

- recovered approve selector with non-zero token movement:
  must not auto-collapse into `APPROVE`; economic evidence wins
- trusted position manager with real increase-liquidity movement:
  must still classify to `LP_ENTRY`
- `LI.FI` route-tagged calldata without outbound movement:
  must not auto-promote to `BRIDGE_OUT`
- route-tagged generic aggregator call on an address that is not a known bridge
  router:
  remains generic outbound transfer or review according to current rules
- audited `Euler` batch rows:
  keep current semantics and must not regress back to `SWAP` or `LP_EXIT`

## Task Breakdown

1. `BE-R35-01` Approve selector precedence
   - ensure recovered `approve` selector can preempt address-only registry
     direct-type fallback
   - add regression coverage for audited `Velodrome Slipstream` zero-movement
     approve shape

2. `BE-R35-02` LI.FI bridge-start recovery
   - extend route-tagged `LI.FI / Jumper` bridge-start detection for the
     audited production shape
   - widen registry network coverage for the audited `LI.FI Diamond` address if
     current raw evidence proves those deployments are live in-scope
   - add regression coverage for:
     - explicit selector route-tagged bridge-start
     - blank-method recovered-selector route-tagged bridge-start

3. `BE-R35-03` Euler semantic-fix lock
   - add or keep regression coverage proving current `Euler EVK` close /
     rebalance semantics stay unchanged in this slice

4. `BE-R35-04` Rerun readiness preparation
   - do not run a broad backfill
   - reset normalized / derived collections for standard rerun
   - preserve current raw and clarification evidence

## Risk Notes

- Do not let the fix turn all selector-derived types into higher-priority truth
  globally. This slice is about selector-derived non-economic evidence
  outranking address-only LP fallback where economic movement is absent.
- Do not widen `LI.FI` bridge recognition to any routed aggregator. The bridge
  rule must still require known bridge-router identity plus route-tag evidence.
- Do not revert the current `Euler` rows back to the etalon. They are the only
  baseline drift set that currently looks economically more accurate.
