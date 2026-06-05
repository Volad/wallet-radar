# Uniswap Protocol Rules

Status: Active protocol rule scaffold

## Scope

Cover Uniswap spot swap and LP position-management semantics, including router,
universal router, and position-manager flows.

## Protocol-Local Resources

Planned resource file:

- `backend/src/main/resources/protocols/uniswap.json`

Expected contents:

- router selector hints
- position-manager selector hints
- clarification hints for multicall container decoding

## Authoritative Evidence

- `OnChainRawTransactionView`
- registry discovery
- saved calldata and recovered selectors
- persisted transfer evidence

## Lifecycle Shapes

- `SWAP`
- `LP_ENTRY`
- `LP_EXIT`
- `LP_ADJUST`
- `LP_FEE_CLAIM`
- wrapped containerized variants where runtime contract requires them

## Clarification Rules

- clarification may be used when current raw evidence cannot separate container
  subcalls or LP lifecycle details
- clarification must not override already-sufficient wallet-visible swap or LP
  evidence

## Family Handoff

- swap semantics hand off to `SwapClassifier`
- LP semantics hand off to `LpClassifier`

## Disallowed Fallbacks

- do not let generic transfer or generic multicall fallback win before router /
  position-manager semantics are checked

## Baseline and Regression Anchors

- swap parity
- LP entry/exit parity
- LP principal continuity parity
