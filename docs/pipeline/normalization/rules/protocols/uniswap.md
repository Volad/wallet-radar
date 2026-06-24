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

## CL-NFT position identity — keyed by the NonfungiblePositionManager contract (RC-1, ADR-018)

Uniswap V3 has many same-interface forks (PancakeSwap V3, Aerodrome/Velodrome Slipstream,
SushiSwap V3) that share the `NonfungiblePositionManager` selectors (`mint`/`increaseLiquidity`/
`decreaseLiquidity`/`collect`, often wrapped in `multicall(0xac9650d8)`). Each fork's NFPM mints
its **own** ERC-721 tokenId space, so a `tokenId` is meaningful only together with its NFPM
contract.

- The CL-NFT position identity is `lp-position:<network>:<nfpmContractLowercased>:<tokenId>`,
  where `<nfpmContract>` is derived from the transaction's `rawData.to` (the interacted
  position-manager contract). This is identical for the LP_ENTRY and the LP_EXIT, so a position
  can never split across two receipt pools.
- The **protocol slug is display-only**, resolved from the registry by contract. The generic
  `LpClassifier` must **never** default an unrecognized V3-interface NFPM to `uniswap`; identity
  is the contract, the label is registry-resolved, and a missing registry entry logs a one-time
  coverage warning (it does not change identity).
- This is what prevents the PancakeSwap-V3-on-the-same-NFPM collision where an entry was tagged
  `uniswap` and the exit `pancakeswap` for the same `(network, tokenId)`.

### Uniswap V4 PositionManager identity (RC-6, ADR-018)

Uniswap V4 (`modifyLiquidities(0xdd46508f)` on the singleton PositionManager, e.g. UNICHAIN
`0x4529a01c7a0410167c5740c487a8de60232617bf`) uses the **identical** rule: key every V4
`LP_ENTRY`/`LP_EXIT` by `lp-position:<network>:<fullPMcontractLower>:<tokenId>`.

- A new-mint `modifyLiquidities` assigns the tokenId on-chain (it is **not** in calldata, only in
  the resulting ERC-721 mint log). A direct `modifyLiquidities` with a MINT action / position-NFT
  mint log is therefore a **receipt-clarification shape**: it goes `PENDING_CLARIFICATION` (null
  correlation id) until the mint log resolves the tokenId, then keys the full-PM identity.
- The legacy **truncated-contract aggregate** branch (`lp-position:<net>:<slug>:<16-hex>`, no
  tokenId) is eliminated for NFT-based position managers; the per-contract fallback now uses the
  **full** contract and applies only to genuine no-NFT vault-style position managers.

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
