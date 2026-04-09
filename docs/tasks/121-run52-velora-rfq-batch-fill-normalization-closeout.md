# Run 52 Velora RFQ Batch-Fill Normalization Closeout

Status: Done

Goal:

Close the active on-chain replay blocker where audited `Velora/ParaSwap`
Augustus RFQ batch-fill overloads remain
`UNKNOWN / ROUTER_METHOD_OVERLOAD_UNSUPPORTED` despite current raw already
proving a real swap.

Related inputs:

- [run 14 audit](../../results/stats/14/full-pipeline-audit.md)
- [run 14 metrics](../../results/stats/14/metrics.json)
- [Architecture policy](../02-architecture.md)
- [Accounting policy](../03-accounting.md)

## Problem Statement

The current live blocker
`0x21815249921ac127f3c52fbf018924f80a55e8fa7fc80505ca2488f0b80f256f`
on `AVALANCHE` is routed through
`0x6a000f20005980200259b80c5102003040001068` (`Velora/ParaSwap` Augustus
V6.2).

Current raw already proves:

1. wallet sends `USDt 5.331168`
2. maker sends `USDC 5.330533`
3. router method is `swapOnAugustusRFQTryBatchFill(...)`
4. protocol registry already knows the router as `Velora/ParaSwap`

Yet the row still remains `UNKNOWN / NEEDS_REVIEW` because the method-aware
swap registry path only treated `family = DEX` routers as swap-capable and did
not explicitly allow the RFQ batch-fill selector.

That is too narrow for audited aggregator RFQ routers and blocks fresh replay.

## Scope

In scope:

- method-aware swap classification for registry-backed `AGGREGATOR` routers
- explicit support for audited RFQ batch-fill selector `0xda35bb0d`
- regression coverage for raw transfer-backed Velora RFQ swaps
- safe on-chain rerun after normalization changes

Out of scope:

- clarification-driven retyping for cases where current raw still lacks
  transfer evidence
- heuristic time-window swap inference
- new protocol families outside the audited RFQ router class

## Acceptance Criteria (DoD)

1. A registry-backed router with `family = AGGREGATOR` may resolve as `SWAP`
   through the method-aware swap path when:
   - role is router-like
   - current raw proves distinct inbound and outbound principal assets
   - no bridge or custody lifecycle preempts it

2. Audited `Velora/ParaSwap` RFQ batch-fill selector `0xda35bb0d` must be
   recognized as method-aware router execution even when function names are
   blank or inconsistent.

3. The audited Avalanche row
   `0x21815249921ac127f3c52fbf018924f80a55e8fa7fc80505ca2488f0b80f256f`
   must normalize as:
   - `type = SWAP`
   - `classifiedBy = PROTOCOL_REGISTRY`
   - `status = PENDING_PRICE`
   - non-empty sell / buy flows
   - `protocolName = Velora/ParaSwap`

4. Negative safeguards remain intact:
   - outbound-only aggregator sends do not auto-become `SWAP`
   - same-asset refund-only rows do not become distinct-asset swaps unless
     existing exact-out rules already allow them
   - bridge and wrapper paths still preempt generic router swap logic

5. Operationally:
   - raw evidence remains untouched
   - on-chain normalized rows are reopened from raw
   - derived collections rebuild from the rerun

## Edge Cases

- selector present but function name blank
- function name present but selector recovered from calldata
- aggregator router with same-asset refund plus net swap
- aggregator router with only outbound movement
- RFQ fills where maker leg comes directly from maker wallet, not the router

## Task Breakdown

1. `BE-R52-01` Extend method-aware router support to cover audited RFQ selector
   `0xda35bb0d`
2. `BE-R52-02` Allow method-aware swap registry classification for router-like
   `AGGREGATOR` families
3. `BE-R52-03` Lock regression coverage for the audited Velora RFQ swap row
4. `BE-R52-04` Reopen on-chain canonical normalization from raw and rebuild the
   downstream pipeline
5. `BE-R52-05` Verify that the active replay blocker disappears

## Risk Notes

- Over-broad expansion from `DEX` to all `AGGREGATOR` routers would be unsafe
  if it ignored movement evidence. The rule must stay transfer-backed.
- RFQ selectors must not be used as standalone proof without real wallet out /
  inbound asset evidence.
- This slice should not rewrite accounting policy; it only restores a missing
  normalization rule for an already supported protocol family.

## Completion Notes

- Method-aware swap routing now accepts audited `AGGREGATOR` routers when
  current raw already proves swap-like movement.
- `0xda35bb0d` was added to the method-aware router selector allowlist so RFQ
  batch-fill rows do not depend on function-name quality.
- Targeted classifier regression now proves the audited `Velora/ParaSwap`
  Avalanche RFQ row resolves as `SWAP`.
- This slice requires a full on-chain canonical rerun because normalization
  output changed.
