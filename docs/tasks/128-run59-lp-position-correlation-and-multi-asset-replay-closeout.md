# 128 — LP Position Correlation And Multi-Asset Replay Closeout

Date: 2026-04-09
Owner slice: backend / on-chain normalization / clarification / accounting
Source audit: `results/stats/22/full-pipeline-audit.md`

## Problem

`ETH uncovered` stayed materially overstated after the audited Mantle and Bybit
continuity fixes.

The remaining lineage pointed upstream to concentrated-liquidity positions on
Arbitrum:

- `0xc17e7b91e9f19796761181ec40f9d5c0ef1511092dd557c2b02be0875dd06a4d`
- `0x1b5c1dd635041fc0125c3f37b1a9106fdc73b76f48a82587421363e820fd2891`
- `0xb84106bcf59674daffbd64049f01d7206800f66e84ea2ddff22b6452379477e6`
- `0x293cf2289fcbf131c77b5b3a66de63eaf29b5837eba5444ba98a13b1580c92cc`
- `0xe63ce6a88ebc1c03ebf95246df8bddca67e886931376fa808f126d877c1adfc4`

Before the fix:

- `LP_ENTRY` rows removed both `ETH` and `USDC` principal with carry-over basis
- later `LP_EXIT` rows returned mostly `ETH`
- replay restored only same-asset `ETH` carry and dropped the `USDC` side of
  the position basis
- the lost basis then propagated into downstream `BYBIT -> Mantle -> aManWETH`

This was a concentrated-LP replay model defect, not an Aave-specific bug.

## Root cause

Wallet-level continuity and position-level continuity were being mixed.

Wallet/family continuity is correct for lending, vaults, bridges, and wrapped
native custody. It is not correct for concentrated-liquidity positions where:

- entry principal may contain multiple assets
- exit principal may return a shifted asset mix
- the real lifecycle is anchored to one NFT position token id

## Decision

Do not patch this at UI or dashboard level.

Do not pool LP carry by wallet or protocol.

Instead:

1. derive deterministic concentrated-LP `correlationId` from position token id:
   - `lp-position:<network>:<protocol-slug>:<tokenId>`
2. when mint-style rows do not expose token id in calldata, send them to
   receipt clarification and recover the NFT mint log
3. replay concentrated-LP carry through a multi-asset position bucket keyed by
   that deterministic `correlationId`
4. allow principal basis to migrate only inside that position bucket
5. keep reward-only inflows conservative when they were never part of the
   position principal

## Implemented scope

### P0 deterministic LP position correlation

- added `LpPositionCorrelationSupport`
- decodes token id from:
  - direct calldata
  - multicall subcalls
  - clarified NFT mint logs
- emits deterministic correlation:
  - `lp-position:<network>:<protocol-slug>:<tokenId>`

### P0 receipt clarification gating

- introduced `LP_POSITION_CORRELATION_REQUIRED`
- metadata-only clarification now skips these rows
- full-receipt clarification recovers token id from persisted NFT mint logs

### P0 replay fix

- correlated `LP_ENTRY` rows store principal carry inside a position-scoped
  multi-asset async bucket
- correlated `LP_EXIT / LP_EXIT_PARTIAL / LP_EXIT_FINAL` restore carry from that
  same bucket instead of using wallet-level same-asset continuity
- same-asset restore is attempted first
- remaining principal basis may then reallocate into returned principal assets
  that belong to the same position bucket
- out-of-bucket reward assets do not inherit LP principal basis automatically

## Acceptance criteria

- concentrated-LP rows with directly decodable token id must persist non-empty
  deterministic `correlationId`
- mint-style concentrated-LP rows without token id in current raw calldata must
  become:
  - `status = PENDING_CLARIFICATION`
  - `missingDataReasons` contains `LP_POSITION_CORRELATION_REQUIRED`
- receipt clarification must recover token id from the persisted NFT mint log
  and rebuild the same deterministic `correlationId`
- replay must carry combined principal basis across the whole LP position, not
  only same-asset legs
- audited reward assets such as `CAKE` on
  `0x293cf2289fcbf131c77b5b3a66de63eaf29b5837eba5444ba98a13b1580c92cc`
  must not silently inherit unrelated LP principal basis

## Detailed task breakdown

### Task A — normalization

- derive concentrated-LP token id from calldata when possible
- persist deterministic `correlationId` on `LP_ENTRY` / `LP_EXIT*`
- add explicit blocker reason when mint-style rows still need receipt evidence

### Task B — clarification

- route `LP_POSITION_CORRELATION_REQUIRED` rows to full receipt clarification
- recover token id from persisted NFT mint logs only
- rebuild canonical row in place once correlation becomes deterministic

### Task C — replay

- create position-scoped multi-asset carry bucket keyed by LP `correlationId`
- restore same-asset carry first, then remaining principal basis only into
  principal assets present in the bucket
- keep out-of-bucket reward assets conservative

### Task D — verification

- rerun on-chain normalization and replay
- verify audited position hashes end with deterministic position correlation
- compare `ETH uncovered` before/after

## Risks and guardrails

- do not derive LP position identity from protocol proximity
- do not infer token id from synthetic `rawData.logs[]`
- do not allocate LP principal basis into reward assets that were never part of
  the position bucket
- do not collapse all rows from one DEX into one LP carry pool

## Expected outcome

After rerun:

- concentrated-LP continuity is anchored to the real NFT position lifecycle
- cross-asset LP principal basis is preserved through exits
- downstream `ETH` carry into `BYBIT -> Mantle -> aManWETH` no longer loses the
  upstream `USDC` side of the LP position basis
