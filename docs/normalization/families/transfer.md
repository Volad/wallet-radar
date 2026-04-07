# Transfer Family Rules

Status: Active family rule scaffold

## Scope

Own generic wallet-visible transfers and claim-like income only when no more
specific family owns the lifecycle.

## Owned Normalized Types

- `REWARD_CLAIM`
- `EXTERNAL_TRANSFER_OUT`
- `EXTERNAL_TRANSFER_IN`
- `INTERNAL_TRANSFER`

## Authoritative Evidence

- `OnChainRawTransactionView`
- persisted token/internal/native transfers
- tracked-wallet universe for internal-transfer continuity only where the
  approved contract still allows it

## Clarification Rules

- clarification may support reward-vs-transfer disambiguation when current
  production evidence can actually provide it
- generic transfer fallback must remain conservative

## Correlation Rules

- generic transfer rows normally have no `correlationId`
- `matchedCounterparty` is used only when a stronger protocol lifecycle proves
  deterministic pairing

## Current Extracted Rules

- known reward route on recognized reward distributor / reward router:
  - inbound payout -> `REWARD_CLAIM`
  - explicit claim without payout -> `UNKNOWN + CONFIRMED` with `CLAIM_WITHOUT_MOVEMENT`
- known claim income reward paths before clarified-economic fallback:
  - harvest on known DEX stake contract -> `REWARD_CLAIM`
  - `release()` / `getReward()` with inbound-only movement -> `REWARD_CLAIM`
  - merkle/native signature claim selectors with inbound-only movement -> `REWARD_CLAIM`
- outbound-only aggregator router call on registry-backed aggregator route ->
  `EXTERNAL_TRANSFER_OUT` with reason `ROUTED_AGGREGATOR_OUTBOUND_ONLY`
- protocol-specific clarification may recover a routed aggregator row back into an
  economic family when current production evidence proves wallet-boundary
  settlement:
  - active example: `1inch swap(...)` with calldata proving `dstToken=native`
    and `dstReceiver=tracked wallet`
  - when receipt/transfer evidence confirms wrapped-native unwrap into wallet
    settlement, the row must normalize as `SWAP`, not `EXTERNAL_TRANSFER_OUT`
  - on `RPC`-backed clarification rows this confirmation may come from the
    wrapped-native `Withdrawal` receipt log even if clarified token transfers do
    not include an explicit burn-to-zero transfer row

## Disallowed Fallbacks

- do not let transfer fallback capture bridge, LP, staking, lending, or trading
  lifecycle rows
- do not let claim-like spam rows resolve as `REWARD_CLAIM`

## Baseline Expectations

- transfer fallback must stay late in the chain
- row-level parity is required for active transfer families because they affect
  pricing and basis directly
