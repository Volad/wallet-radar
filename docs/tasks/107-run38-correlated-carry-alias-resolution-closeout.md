# Run 38 — Correlated Carry Alias Resolution Closeout

## Goal

Repair the remaining replay carry losses for already-proven correlated
`EXTERNAL_TRANSFER_OUT -> EXTERNAL_TRANSFER_IN` paths where the source-side
on-chain asset is contract-identified but the destination-side venue asset is
symbol-identified.

## Problem

The full pipeline audit for `results/stats/2` showed that the previous replay
repair solved venue rounding for same-family ingress, but a narrower defect
remained:

- `10` zero-cost Bybit `CARRY_IN` rows still existed
- `6` of them were legitimate uncovered ingress because the source-side exit
  was already zero-cost
- `4` of them were real continuity misses:
  - `ARB` source contract identity vs Bybit `SYMBOL:ARB`
  - `USD₮0` / `USDT0` source contract identity vs Bybit `SYMBOL:USDT`

Root cause:

1. correlated carry matching still keyed replay continuity on
   `correlationId + continuity identity`
2. for these rows the source-side identity fell back to exact contract because
   no broader accounting family existed
3. the Bybit destination fell back to symbol identity because no contract
   existed on the provider side
4. replay therefore treated the carry as different assets even though the move
   was already proven by `correlationId`, `matchedCounterparty`, and quantity
   compatibility

## Target Policy

1. Keep the repair replay-only.
2. Do not broaden global portfolio family aggregation for all assets.
3. When correlated carry matching already has strong continuity proof
   (`correlationId + continuityCandidate`), replay may use a narrower
   canonical symbol alias key **only for the transfer-matching key** when the
   default continuity identity is not already a `FAMILY:*` identity.
4. This alias key may normalize provider / bridge ticker variants such as:
   - `USD₮0 -> USDT`
   - `USDT0 -> USDT`
   - exact same-symbol contract source -> venue symbol destination
5. If the asset already belongs to an audited accounting family
   (`FAMILY:ETH`, `FAMILY:USDC`, etc.), replay must keep using that family
   identity and must not demote it to symbol-only matching.

## Scope

In scope:

- replay transfer-key generation for correlated continuity rows
- targeted carry restoration for `ARB` and `USDT*` Bybit ingress
- regression tests for symbol-alias continuity into provider wallets
- replay-only rerun after the fix
- documentation updates for correlated alias repair policy

Out of scope:

- changing canonical normalized transaction types
- changing dashboard family aggregation behavior
- pricing policy redesign
- bridge clarification redesign
- UI changes for covered/uncovered AVCO display

## Acceptance Criteria

1. A correlated `CARRY_OUT` with non-zero cost basis on the source side must
   restore non-zero carried basis into Bybit even when:
   - the source row is contract-identified on-chain
   - the destination row is symbol-identified in Bybit
2. `ARB` correlated ingress restores carry without inventing realized PnL.
3. `USD₮0` / `USDT0` correlated ingress restores carry into Bybit `USDT`
   without inventing a new acquisition lot.
4. Legitimate uncovered ingress remains uncovered when the source-side carry is
   already zero-cost.
5. Existing `ETH/WETH` family continuity continues to use audited family
   identity and remains unaffected.
6. The fix is limited to replay keying; canonical normalization and pricing
   behavior remain unchanged.

## Business Acceptance Criteria (DoD)

1. The four audited continuity defect rows no longer appear as zero-cost
   Bybit ingress after replay rerun:
   - `0x546082949f25e323d0a8b3435881a297dd2723ea7a0d23b7a8c651329a0cdd67`
   - `0x95de722dc0f424aec2335a56402b3a7200fe5f463a912abfe5b1b493a583827a`
   - `0xd81619b49282d6c2996abe90f35ec28227fd29d80cd80a1f588a90fa9a67e413`
   - `0xeb64f23546c7efe2b3157f6b89f3db579651bff8bcd23cc71d5ce8b00844a298`
2. The six audited legitimate uncovered ingress rows remain uncovered after the
   same replay rerun.
3. No new active `NEEDS_REVIEW` blockers are introduced.
4. `ACCOUNTING_REPLAY` completes successfully after targeted rerun.

## Edge Cases

In scope:

- same proven continuity path, same `correlationId`, same economic asset,
  different source contract vs destination symbol identity
- provider ticker aliases that are already canonical within the product
  (`USD₮0`, `USDT0`, `USDT`)
- same-symbol venue ingress where the on-chain side currently falls back to
  exact contract identity

Out of scope:

- asset-changing routes with shared `correlationId`
- unsupported loan semantics
- speculative symbol aliases without continuity proof
- global stablecoin family expansion across the whole product

## Architecture Tasks

1. Keep `accountingFamilyIdentity` and replay alias key separate concepts.
2. Use replay-local canonical symbol alias resolution only inside correlated
   carry matching.
3. Preserve existing `FAMILY:*` identities as stronger than alias-based symbol
   keys.
4. Keep the blast radius bounded to already-correlated transfer continuity.

## Backend Tasks

1. Add replay-local canonical symbol alias resolution for correlated transfer
   keys when the default continuity identity is not already `FAMILY:*`.
2. Normalize at least:
   - `USD₮0 -> USDT`
   - `USDT0 -> USDT`
3. Preserve exact current behavior for:
   - `ETH/WETH` family continuity
   - legitimate zero-cost uncovered ingress
4. Add regression tests for:
   - `ARB` on-chain contract source -> Bybit `ARB`
   - `USD₮0` on-chain contract source -> Bybit `USDT`

## Mongo / Rerun Tasks

1. Keep immutable raw evidence and canonical normalized rows intact.
2. Prepare a replay-only rerun by clearing replay-derived outputs:
   - `asset_ledger_points`
   - `on_chain_balances`
3. Keep the active session in a state that allows the resume watchdog to
   re-emit replay.
4. Restart backend and verify replay completion on the active session.

## Expected Outcome

After rerun:

- Bybit ingress for the four audited alias cases restores carried basis
- the six legitimate uncovered ingress rows remain unchanged
- ETH family is unaffected by this repair except where upstream alias cases
  materially feed into later ETH paths
- replay accuracy improves without widening global asset-family aggregation
