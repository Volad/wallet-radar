# LP Family Rules

Status: Active family rule scaffold

## Scope

Own LP entry/exit lifecycle, LP position staking, and LP fee claims across
spot-style liquidity protocols and pool products.

## Owned Normalized Types

- `LP_ENTRY_REQUEST`
- `LP_ENTRY_SETTLEMENT`
- `LP_ENTRY`
- `LP_EXIT_REQUEST`
- `LP_EXIT_SETTLEMENT`
- `LP_EXIT`
- `LP_EXIT_PARTIAL`
- `LP_EXIT_FINAL`
- `LP_ADJUST`
- `LP_POSITION_STAKE`
- `LP_POSITION_UNSTAKE`
- `LP_FEE_CLAIM`

## Authoritative Evidence

- `OnChainRawTransactionView`
- persisted token/internal/native transfer evidence
- protocol-owned lifecycle hints from protocol semantic classifiers
- clarification evidence for async request/settlement or pool-specific receipt
  logs

## Clarification Rules

- async LP request/settlement families may require clarification to persist
  deterministic lifecycle keys or related lifecycle rows
- clarification must materialize exact request/settlement links when evidence is
  already available

## Flow Rules

- LP principal legs remain continuity `TRANSFER`, not synthetic `BUY` / `SELL`
- explicit fee claims remain economic
- request and settlement rows must preserve lifecycle meaning instead of
  collapsing into generic transfers

## Correlation Rules

- exact async request/settlement pairs require deterministic `correlationId`
- when exact pair is proven, `matchedCounterparty` must be bidirectional
- higher-scope lifecycle keys win over intermediate keys

## Disallowed Fallbacks

- do not let generic `EXTERNAL_TRANSFER_*` capture async LP lifecycle rows
- do not let LP principal continuity drift into disposal/acquisition semantics
- do not let trusted LP position-manager identity alone promote zero-movement
  setup calls into `LP_ENTRY`
- recovered selector-level `APPROVE` evidence must outrank address-only LP
  position-manager fallback when economic movement is absent

## Baseline Expectations

- row-level parity is required for LP principal roles, correlation, and exact
  async pair linkage

## Current Strangler Scope

- extracted runtime rule: inbound-only `LBHooks` claim path resolves to
  `LP_FEE_CLAIM`
- extracted runtime rule: clarified `routeSingle` with wallet ERC-721 receive
  evidence resolves to `LP_ENTRY`
- extracted runtime rule: audited `GMX V2` async `GM/GLV` pool
  `LP_ENTRY_REQUEST / LP_ENTRY_SETTLEMENT / LP_EXIT_REQUEST / LP_EXIT_SETTLEMENT`
  lifecycle resolves outside legacy
- audited `Velodrome Slipstream` zero-movement approve calls to the trusted
  position manager remain `APPROVE`, not `LP_ENTRY`
