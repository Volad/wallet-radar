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
- on `BLOCKSCOUT`-backed concentrated-liquidity exits, clarification must also
  recover tx-level native settlement transfers when wallet-scoped explorer pages
  omit them but transaction-level explorer endpoints expose them
- these `BLOCKSCOUT` native-settlement recovery rows must skip metadata-only
  clarification and go straight to transfer-aware full-receipt clarification

## Flow Rules

- LP principal legs remain continuity `TRANSFER`, not synthetic `BUY` / `SELL`
- explicit fee claims remain economic
- request and settlement rows must preserve lifecycle meaning instead of
  collapsing into generic transfers
- `GMX V2 LP_ENTRY_REQUEST` keeps native execution-fee reserve as canonical
  `TRANSFER` evidence; replay, not normalization, is responsible for separating
  that reserve from non-native principal carry

## Correlation Rules

- exact async request/settlement pairs require deterministic `correlationId`
- when exact pair is proven, `matchedCounterparty` must be bidirectional
- higher-scope lifecycle keys win over intermediate keys
- concentrated-liquidity `LP_ENTRY` / `LP_EXIT*` rows use position-scoped
  deterministic correlation:
  - `lp-position:<network>:<protocol-slug>:<tokenId>`
- direct increase / decrease / collect / burn selectors may derive token id
  during normalization
- direct or multicall-embedded Uniswap V4 `modifyLiquidities` may also derive
  token id during normalization by decoding nested `unlockData` actions and the
  paired action params for existing-position operations
- mint-like rows without token id in current raw calldata must enter
  full-receipt clarification with `LP_POSITION_CORRELATION_REQUIRED`
- replay continuity for these rows is multi-asset and position-scoped; it is
  not a generic wallet-level same-asset bucket
- replay restores same-asset carry before attempting any cross-asset residual
  principal allocation
- positive transfer legs whose asset identity was not present among the source
  principal outbounds stay as deferred residual principal candidates rather than
  immediate `UNKNOWN`
- deferred residual candidates may receive principal basis only when they are
  the only remaining deterministic principal-return lane after same-asset carry
  has been consumed
- a reward-only or out-of-bucket sideflow exit must not flush the remaining
  position bucket on its own; replay leaves that sideflow `UNKNOWN` and keeps
  the bucket open for later principal-return exits under the same position
  correlation
- replay-local value allocation may reuse trusted USD-stable parity for
  on-chain transfer legs without explicit persisted `unitPriceUsd`
- `GMX V2 LP_ENTRY_REQUEST / LP_ENTRY_SETTLEMENT` is an approved transaction-
  level replay specialization:
  - request-side non-native outbounds reserve principal carry
  - request-side native outbound reserves execution fee
  - settlement native refund releases that reserve
  - settlement `GM` / `GLV` share inflow receives the remaining principal basis

## Disallowed Fallbacks

- do not let generic `EXTERNAL_TRANSFER_*` capture async LP lifecycle rows
- do not let LP principal continuity drift into disposal/acquisition semantics
- do not freeze `LP_EXIT` / `LP_FEE_CLAIM` as final when `BLOCKSCOUT`
  transaction-level internal transfers can still add a missing native settlement
  leg
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

## Protocol-Family Matrix (A–E)

| Family | Protocols | Correlation | Flow materialization |
|---|---|---|---|
| **A — NFT CL** | PancakeSwap, Uniswap, Velodrome Slipstream, Aerodrome | `lp-position:{network}:{protocol-slug}:{tokenId}` | `LpNftClFlowMaterializer`: inbound/outbound `LP-RECEIPT:*` from ERC721 logs + ERC20 principal legs |
| **B — GMX async** | GMX V2 GM/GLV markets | `gmx-lp:{network}:{market-slug}` from settlement share symbol (e.g. `GM: ETH/USD [WETH-USDC]`) | GM/GLV share legs only; async request/settlement lifecycle |
| **C — Pendle** | Pendle PT/LPT markets | `pendle-lp:{network}:{market-id}` | Actual `PENDLE-LPT` / PT legs; no NFT receipt pool |
| **D — Fungible LP** | Curve, Balancer, LFJ | composite `lp:` bucket via receipt token identity | Outbound fungible LP/BPT burn + principal return; no synthetic NFT receipt |
| **E — Gauge / farm** | Pancake MasterChef, Velodrome stake, Aura gauge | optional ERC721 link (phase 2) | `LP_POSITION_STAKE` / `LP_POSITION_UNSTAKE` **do not** close NFT CL positions |

### Principal vs fee claim

| Outcome | Evidence |
|---|---|
| `LP_FEE_CLAIM` | Collect-only or inbound reward tokens without position-reduction evidence |
| `LP_EXIT` (principal) | Decrease/burn/negative ModifyLiquidity/ERC721 from wallet **and** principal or receipt legs |

Pancake-specific: cake-only or dust-USDC-only former `LP_EXIT` rows downgrade to
`LP_FEE_CLAIM` with `lp-position` correlation when `tokenId` is decodable.

Harvest rows (`LP_FEE_CLAIM`) must carry `lp-position:*` correlation when calldata exposes
`tokenId`; they must not drain `lp_receipt_basis_pools` on replay.

Gauge stake/unstake moves custody of an NFT or farm share but does **not** by itself mark
the underlying concentrated-liquidity position closed.
