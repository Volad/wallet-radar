# Transfer Family Rules

Status: Active family rule scaffold

## Scope

Own generic wallet-visible transfers and claim-like income only when no more
specific family owns the lifecycle.

## Owned Normalized Types

- [`REWARD_CLAIM`](../../../../reference/transaction-types.md#reward-claim)
- [`EXTERNAL_TRANSFER_OUT`](../../../../reference/transaction-types.md#external-transfer-out)
- [`EXTERNAL_TRANSFER_IN`](../../../../reference/transaction-types.md#external-transfer-in)
- [`SPONSORED_GAS_IN`](../../../../reference/transaction-types.md#sponsored-gas-in)
- [`INTERNAL_TRANSFER`](../../../../reference/transaction-types.md#internal-transfer)

## Authoritative Evidence

- `OnChainRawTransactionView`
- persisted token/internal/native transfers
- tracked-wallet universe for internal-transfer continuity only where the
  approved contract still allows it
- wallet-to-Bybit continuity stays canonical `EXTERNAL_TRANSFER_*`; exact
  same-universe carry is restored later from Bybit correlation evidence, not by
  promoting the row into `INTERNAL_TRANSFER`

## Clarification Rules

- clarification may support reward-vs-transfer disambiguation when current
  production evidence can actually provide it
- generic transfer fallback must remain conservative
- clarification may promote a simple same-tx reciprocal on-chain pair from
  `EXTERNAL_TRANSFER_IN/OUT` into `INTERNAL_TRANSFER` only when:
  - both wallet-local canonical rows already exist
  - `txHash + networkId` match
  - matched counterparties are reciprocal
  - both wallet refs share one `accounting_universe`
  - one principal flow is inbound and one outbound
  - principal family and quantity match within a tiny transfer tolerance
  - neither row already belongs to another lifecycle (`correlationId`,
    protocol-owned route)
- one-sided tracked-counterparty rows stay external; do not invent
  `INTERNAL_TRANSFER` from a hint alone
- for simple direct native transfers with one-sided raw coverage, normalization
  may repair the missing same-universe raw peer first; the canonical type still
  upgrades only after clarification sees the reciprocal pair

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
- verified solver / relay funded native gas assistance ->
  `SPONSORED_GAS_IN`
  - wallet-boundary native inbound only
  - empty input / `methodId == 0x`
  - no token transfers
  - no internal transfers
  - sender resolves to registry-backed `GAS_PAYER`
  - quantity fits audited per-network gas-topup envelope
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
- do not persist `INTERNAL_TRANSFER` from tracked-wallet lookup alone when the
  reciprocal canonical peer row is missing

## Baseline Expectations

- transfer fallback must stay late in the chain
- row-level parity is required for active transfer families because they affect
  pricing and basis directly
