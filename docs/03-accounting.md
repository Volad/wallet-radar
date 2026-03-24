д# WalletRadar — Accounting Policy

> **Version:** v3 target
> **Last updated:** 2026-03-24
> **Accounting method:** AVCO

---

## 1. Canonical Accounting Input

WalletRadar computes basis only from canonical documents in:

- `normalized_transactions WHERE status = CONFIRMED`

Canonical documents may originate from:

- `source = ON_CHAIN`
- `source = BYBIT`

Raw collections remain source evidence:

- `raw_transactions`
- `external_ledger_raw`

They are used for reconstruction and audit, but never replayed directly for AVCO.

Economic meaning is derived from backfill-available raw evidence and canonical
flows, not from human-readable explorer page summaries.

Tx-level native `value` participates in accounting only when it comes from
canonical tx-level raw evidence. Token transfer-row amounts must never be
reinterpreted as direct native movement.

---

## 2. Replay Order

Replay must be deterministic:

1. `blockTimestamp ASC`
2. `transactionIndex ASC`
3. `_id ASC` as final tie-breaker

For Bybit canonical rows, `transactionIndex = 0`.

---

## 3. AVCO Rules

### On BUY

```text
newAvco = (currentAvco * currentQty + priceUsd * deltaQty) / (currentQty + deltaQty)
newQty  = currentQty + deltaQty
```

### On SELL

```text
avcoAtSale   = currentAvco
realisedPnl  = (sellPriceUsd - avcoAtSale) * abs(deltaQty)
newQty       = currentQty - abs(deltaQty)
newAvco      = currentAvco
```

### On TRANSFER

- quantity moves
- basis carries forward
- no new acquisition lot is created
- no realised PnL is created

### On FEE

- `FEE` is stored as a separate canonical flow
- fee quantity reduces the fee asset quantity and contributes to `totalGasPaidUsd`
- when policy capitalizes gas into an acquisition, replay may allocate fee USD into the same transaction's BUY basis
- outside such capitalization, `FEE` does not create a new lot and does not create realised PnL for the target asset being acquired or disposed

### On PRICE_UNKNOWN

- quantity still updates
- price fields remain null
- `hasIncompleteHistory = true`

---

## 4. Basis Continuity Rules

Basis continuity applies when the economic owner did not dispose of the asset:

- `INTERNAL_TRANSFER`
  Basis carries between tracked wallets.
- `BRIDGE_OUT -> BRIDGE_IN`
  Basis carries across networks when the bridge pair is correlated.
- `BRIDGE_OUT(depositV3) -> BRIDGE_IN(fillV3Relay/fillRelay/redeemWithFee/execute302/directFulfill)`
  Destination-side settlement remains bridge continuity, not repay or vault semantics.
- `Bybit -> on-chain`
  Same accounting universe. Correlate by `txHash` and shared `correlationId`.
- `PROTOCOL_CUSTODY`
  Basis moves into and out of custody without creating BUY/SELL.
- `LENDING_DEPOSIT -> LENDING_WITHDRAW`
  Principal basis moves between spot balance and receipt-token/custody state without realization.
- `VAULT_DEPOSIT -> VAULT_WITHDRAW`
  Basis moves between spot balance and vault-share/custody state without realization.
- `REWARD_CLAIM`
  Requires actual inbound reward movement to the tracked wallet. Claim calls with
  no inbound movement stay explicit non-economic / review rows and must not mint
  synthetic basis.

For matched Bybit withdraw/deposit:

- both source sides remain traceable
- canonical replay uses `TRANSFER` semantics only
- no duplicate BUY/SELL is allowed

---

## 5. Asset Identity Rules

- WETH-like wrappers are stored as-is
- wrapper-to-native aliasing is applied only at replay/pricing time when policy allows
- `stETH`, `mETH`, `rETH`, `wstETH`, and `cbETH` remain distinct assets
- LP tokens, receipt tokens, vault shares, and custody markers are not treated as new basis lots unless explicitly modeled as economic principal

---

## 6. Pricing Policy

Historical price resolution order:

1. stablecoin parity
2. swap-derived price
3. wrapper/native mapping
4. CoinGecko historical fallback
5. unresolved price flag

Implications:

- pricing failure does not remove quantity from replay
- pricing gaps must be visible as warnings or blockers
- CoinGecko is a fallback, not the primary pricing mechanism

---

## 7. Clarification Policy

`Clarification` may enrich receipt-safe metadata:

- execution status
- gas fields
- created contract address

Clarification is allowed only when those receipt-safe fields are actually missing.
Low confidence alone does not move a row into clarification.

Metadata-only clarification may not:

- treat synthetic `rawData.logs[]` as authoritative event evidence
- silently rewrite economic meaning without traceable evidence
- leave clarification-eligible rows without explicit missing receipt-safe reasons
- under-report a currently missing receipt-safe field in `missingDataReasons[]`

Implications:

- ambiguous `EXTERNAL_INBOUND` vs `REWARD_CLAIM` is not a clarification problem
- plain positive inbound transfer defaults are not a clarification problem
- promo/phishing suppression is not a clarification problem
- wrapped-native `deposit()` / `withdraw(uint256)` is not a clarification problem
- bridge entry / settlement selectors are not a clarification problem
- LP position-manager `multicall` / `modifyLiquidities` routing is not a clarification problem
- missing `transactionIndex` is a raw-repair problem before normalization, not a clarification retry
- `MISSING_CONTRACT_ADDRESS` is not a generic clarification fallback; it is valid
  only for explicit contract-creation rows
- missing `effectiveGasPrice` remains a clarification reason even when the
  tracked wallet is not the tx fee payer; live clarification rows must reflect
  the actual missing receipt-safe tx metadata
- `CLAIM_WITHOUT_MOVEMENT` is a valid per-wallet terminal outcome when the claim
  signer does not receive the reward transfer in persisted raw evidence

Clarification may additionally use full receipt evidence for an allowlisted
residual-review set:

- it may fetch and persist full receipt logs for that allowlisted set
- it should persist both:
  - the adapted clarification evidence used by runtime classification
  - the raw full receipt payload, when the source exposes it
- if a clarification source call already fetched a receipt payload, that
  source-native payload is persisted in full even when the current row uses
  only metadata-safe clarification semantics
- it must persist that clarification evidence in one canonical raw-level
  location that live Mongo audits and runtime classification both read
- clarification is not complete for a row unless that evidence is persisted on
  the raw document in a deterministic shape
- metadata-safe clarification versus receipt-log-backed clarification is a
  semantic policy only; it must not truncate an already fetched receipt before
  persistence
- it must store those fields separately from synthetic `rawData.logs[]`
- runtime classification and normalization access clarification evidence only
  through `OnChainRawTransactionView` / the canonical raw projection
- it may also persist same-source internal transfers for an allowlisted
  native-bridge subset when those internal legs are required for deterministic
  bridge continuity
- it may rerun classification only when official protocol semantics and the
  fetched receipt evidence together make the row deterministic
- it must fetch clarification evidence from the same source family that produced
  the raw row:
  - RPC-backed raw -> RPC clarification
  - Etherscan-family raw -> Etherscan-family clarification
  - Blockscout-backed raw -> Blockscout clarification
- cross-source fallback is allowed only as an explicit documented fallback, not
  as the default clarification path
- it may narrow a row into an explicit non-economic terminal state when receipt
  proves cleanup/admin behavior but not economic movement
- it may not use traces, explorer page labels, or analyst-only notes as runtime
  evidence
- it must not be used for rows that are already closable from current raw
  evidence, such as claim-family no-movement rows or known handler gaps
- safe current-raw non-economic families should leave `NEEDS_REVIEW` before
  clarification is even attempted, including spam / airdrop clusters,
  explicit `CLAIM_WITHOUT_MOVEMENT`, failed transactions, documented admin /
  governance actions, pending-request / pending-order initiation rows, and
  out-of-scope NFT or attestation mints
- if clarification still leaves only weak protocol identity, zero logs, or
  wrapper-only bookkeeping evidence, the row remains in the documented
  irreducible stop-condition set and is not forced into synthetic economic
  semantics

Clarification success does not by itself make the dataset pricing-ready.
Pricing remains blocked while a live post-rerun audit still shows:

- resolved wrapped-native selector rows leaking into `VAULT_DEPOSIT`,
  `VAULT_WITHDRAW`, or `LENDING_WITHDRAW`
- resolved recognized Across `depositV3(...)` rows leaking into `VAULT_DEPOSIT`
- resolved route-tagged bridge-initiation rows leaking into
  `EXTERNAL_TRANSFER_OUT`, including LI.FI / Jumper `callDiamondWith*`
  families and `transferRemote(...)`
- resolved Circle CCTP `redeem(bytes cctpMsg,bytes cctpSigs)` rows leaking into
  `VAULT_WITHDRAW`
- resolved explicit receiver-wallet `claim(...)` / `claimWithSig(...)` payout
  rows leaking into `EXTERNAL_INBOUND`
- resolved pending `claimSharesAndRequestRedeem(...)` rows leaking into
  priceable `EXTERNAL_TRANSFER_OUT`
- resolved claim-income rows leaking into `EXTERNAL_INBOUND`, including
  Pancake `harvest(...)` and vesting `release()`
- priceable GMX `createOrder(...)` rows leaking into `EXTERNAL_TRANSFER_OUT`
  before final settlement semantics exist
- clarification persistence mismatches where normalized clarification counters
  are ahead of persisted raw clarification evidence
- a broad repeatable review-tail family that should already be deterministic
  from current raw or allowlisted clarification evidence

Those are classification-time basis-semantics failures, not clarification-tail
warnings.

Pricing-readiness gate:

- an economic row may move to `PENDING_PRICE` or `CONFIRMED` only when
  persisted raw evidence or persisted clarification evidence proves non-fee
  movement semantics that are sufficient for cost-basis replay
- fee-only rows may remain resolved only when they belong to an explicit
  non-economic family such as `APPROVE`, `ADMIN_CONFIG`, or another documented
  terminal cleanup/admin type
- wrapper continuity, bridge-entry semantics, route-tagged bridge-initiation
  semantics, claim-income semantics, order-initiation demotion, liquidity
  entry/exit semantics, spam / airdrop demotion, and admin/config demotion
  must be correct before pricing/AVCO begins
- pricing/AVCO may start only when the residual review tail has been reduced to
  the documented irreducible stop-condition set; broad repeatable review
  families are still a basis blocker even when clarification is otherwise
  complete
- after `run/9`, the resolved lane is materially clean and
  `clarification_persistence_mismatches = 0`; pricing is still blocked only by
  the audited residual basis blocker set:
  - Base Pancake / Infinity LP-exit container
    `0x0a757aeeb58667c545017cd8e5cd60dc994a8945ed810c60ea2aed18688f4f7a`
  - Avalanche Euler batch rows
    `0x1e0c429514e9cf892b0b6a11e3cfb290eff5c0c26a557c835496e4ba61717fdb`,
    `0x233c2b959739d298d1012405e9b3d7e535a87d590a81bcb304c6dc0cb3ce5e4f`,
    `0x305f37a69956a13001962216c845385996114876173bdbaef644bbe3baadf5df`
- the remaining BSC Pancake / Infinity row
  `0x0088de663d549fbc58dfa8dbba4180a346a580b1d6277254fa84a8ed9c27967a`
  is no longer treated as a basis blocker once persisted receipt evidence
  proves zero-effect `modifyLiquidities(...)`; it should leave review as an
  explicit non-priceable terminal stop-condition

Current audited clarification candidate families for full receipt enrichment:

- Slipstream cleanup-burn, stake-contract, and zero-effect collect families
- Pancake / Infinity concentrated-liquidity exit and `modifyLiquidities`
  families
- selected Euler-style batch rows where full receipt logs reveal real asset
  transfers
- ParaSwap `swapExactAmountOut(...)`
- GMX `executeOrder(...)`
- Katana `routeSingle(...)`
- allowlisted native bridge families whose same-source internal transfers are
  required for deterministic bridge continuity
- Pancake / Infinity CL exit containers that prove collect / withdrawal /
  unwrap continuity from same-source clarification evidence
- Pancake / Infinity `modifyLiquidities(...)` rows that prove positive
  liquidity-add or tracked-wallet funding direction from persisted full receipt
- once those receipt-helpful families are closed, any remaining review row must
  either:
  - be a documented safe stop-condition with no basis impact, or
  - remain an explicit basis blocker that still prevents pricing/AVCO

---

## 8. Supported Accounting Scope

On-chain accounting networks:

- `ETHEREUM`
- `ARBITRUM`
- `OPTIMISM`
- `POLYGON`
- `BASE`
- `BSC`
- `AVALANCHE`
- `MANTLE`
- `LINEA`
- `UNICHAIN`
- `ZKSYNC`
- `KATANA`
- `PLASMA`

CEX accounting source:

- `BYBIT`

Out of scope for this milestone:

- additional CEX providers
- tax reporting
- NFTs
- rebase-token quantity tracking

---

## 9. Outputs

The replay layer must be able to produce:

- per-wallet asset quantity
- per-wallet AVCO
- realised PnL
- incomplete-history flags
- reconciliation status against available balance data

All outputs must be derivable again from the same canonical inputs with identical ordering.

For continuity events, replay must move quantity and carried basis without creating realised PnL.
