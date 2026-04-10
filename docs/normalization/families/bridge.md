# Bridge Family Rules

Status: Active family rule scaffold

## Scope

Own source-chain bridge initiation and destination-chain bridge settlement
families.

## Owned Normalized Types

- `BRIDGE_OUT`
- `BRIDGE_IN`

## Authoritative Evidence

- `OnChainRawTransactionView`
- persisted token/internal/native transfer evidence
- protocol-aware bridge selectors or protocol-local bridge resources
- clarification-fetched receipt evidence only when production clarification can
  really obtain it

## Clarification Rules

- explicit bridge-start families may request full-receipt clarification to
  persist route evidence
- clarification may help pair source and destination legs
- generic proximity heuristics alone are not enough to promote routed outbound
  txs into bridge semantics

## Correlation Rules

- `correlationId` is optional unless the protocol provides deterministic route
  or request keys
- `matchedCounterparty` is used only when exact lifecycle pairing is proven
- audited same-wallet bridge continuity may also materialize without an
  official route id when a protocol-proven source bridge row and one explicit
  destination bridge row form a unique deterministic pair:
  - same tracked wallet
  - different networks
  - same principal asset family
  - narrow audited time window
  - narrow audited quantity-drift tolerance
- this bounded fallback is currently allowed only for the audited
  high-confidence `Across` subset; it is not a generic proximity matcher

## Disallowed Fallbacks

- do not let generic `EXTERNAL_TRANSFER_OUT` capture explicit bridge-start rows
- do not let generic `VAULT_WITHDRAW` or `REPAY` capture explicit bridge-in rows
- do not demote route-tagged `LI.FI / Jumper` bridge-start calldata on a known
  bridge router into `EXTERNAL_TRANSFER_OUT` just because the recovered
  selector is not in a narrow static allowlist

## Baseline Expectations

- bridge families must remain continuity-safe and may not fabricate spot
  realization where only movement is proven
- asset-changing bridge routes remain `BRIDGE_OUT -> BRIDGE_IN` even when
  replay later restores destination acquisition cost from the source leg
- such route-settlement repair does not change canonical bridge typing and does
  not turn the pair into `continuityCandidate = true` plain carry
- same-wallet same-network replay reservation around `BRIDGE_IN` is allowed
  only for the immediate deterministic custody / transit corridor
- once a later principal-affecting row has already touched that bridge-received
  bucket, a further `BRIDGE_OUT` or custody-source row must fall back to the
  pooled source position rather than consuming a stale reserved inbound slice

## Current Runtime Scope

- destination-side bridge settlement with explicit settlement selector and
  inbound-only movement resolves as `BRIDGE_IN`
- destination-side passive settlement with empty input / blank function name
  may also resolve as `BRIDGE_IN` when current raw proves a registry-backed
  bridge sender through persisted internal-transfer or token-transfer evidence
- explicit source-side bridge start (`Mayan/Stargate/Squid`, LI.FI tagged route
  calls, `transferRemote`) resolves as `BRIDGE_OUT`
- route-tagged `LI.FI / Jumper` bridge-start rows with positive native funding
  or equivalent outbound movement remain `BRIDGE_OUT` when current raw calldata
  already proves bridge provider tags
- when such a source row has one outbound token principal leg plus one native
  outbound leg that exactly matches tx `value`, the native leg is canonical
  route funding / cost and must normalize as `FEE`, not as a second principal
  `TRANSFER`
- method-aware bridge-out (`Across depositV3`, bridge-entry overloads proven by
  registry) resolves as `BRIDGE_OUT`
- routed `MetaMask Bridge -> LI.FI adapter -> Across settlement` corridors may
  materialize as deterministic bridge pairs even when official LI.FI status is
  absent, but only when clarification proves one unique same-wallet
  cross-network destination from current canonical evidence plus verified
  `Across` settlement sender proof
- routed `LI.FI / Jumper` same-asset corridors may also materialize as
  deterministic bridge pairs when clarification proves one unique same-wallet
  Relay payout destination from current canonical evidence plus verified
  registry-backed `Relay` top-level payout sender proof
- post-clarification same-wallet `Across` source/destination pairs may receive
  deterministic `correlationId` and `matchedCounterparty` when bridge
  continuity is uniquely provable from current canonical rows
- official `Mayan` source-tx status may materialize a deterministic
  `swapAndStartBridgeTokensViaMayan(...) -> redeemWithFee(...)` pair even when
  settlement arrives much later and destination quantity is lower because of
  relayer / settlement fees
- already-linked asset-changing bridge pairs may receive replay-only
  destination cost restoration when:
  - the route key is deterministic
  - the pair is unique
  - source and destination each contribute one principal transfer leg
- registry-backed bridge contracts keep `protocolName` and `protocolVersion`
- unsupported bridge/router overloads terminate through the explicit final
  fallback stage, not through a hidden legacy path
