# WalletRadar — Architecture

> **Version:** SAD v3 target summary
> **Last updated:** 2026-03-24
> **Style:** Modular monolith (Spring Boot)

This document is the concise architecture summary for the v3 accounting rewrite.
The full target design, decision log, data-flow details, and cost analysis are in
[architecture-v3.md](architecture-v3.md).

---

## 1. Scope

The v3 milestone is intentionally narrow:

- keep existing raw collection (`raw_transactions`, `sync_status`, `backfill_segments`)
- keep persisted `user_sessions`
- keep an installation-wide tracked wallet universe for canonical normalization
- rebuild normalization on top of raw evidence
- rebuild pricing on top of canonical normalized flows
- replay AVCO and reconciliation from canonical confirmed events

Not part of this milestone:

- new onboarding/control-plane redesign
- current-balance polling redesign
- portfolio snapshot/chart redesign
- additional CEX providers beyond Bybit

---

## 2. High-Level Context

```text
Browser/API Client
    |
    v
Spring Boot
    |- api/session + api/wallet
    |- session/               persisted user_sessions
    |- wallet-universe/       tracked_wallets projection
    |- ingestion/backfill/    existing raw collection
    |- normalization/         on-chain + Bybit canonicalization
    |- pricing/               historical USD resolution
    |- costbasis/             AVCO replay + reconciliation
    |
    +--> MongoDB
         user_sessions
         sync_status / backfill_segments
         tracked_wallets
         raw_transactions
         external_ledger_raw
         normalized_transactions
         asset_positions
```

---

## 3. Core Pipeline

### 3.1 Raw collection

- EVM ingestion may be explorer-first or provider-first by network, with bounded native RPC repair where configured.
- `ScamFilter` stays pre-persistence.
- `ScamFilter` must use composite promo/phishing signals and preserve known
  legitimate reward-claim routes that do not carry promo markers.
- Tx-level fields must be canonicalized before persistence. Transfer-style payload
  rows may populate `explorer.tokenTransfers[]`, but must not overwrite top-level
  tx fields such as `from`, `to`, `value`, `input`, or `methodId`.
- Raw docs land in `raw_transactions` with `normalizationStatus=PENDING`.

### 3.2 On-chain normalization

- Start only after raw backfill for wallet×network is complete.
- If tx-level ordering metadata is incomplete, run raw ordering repair before canonical normalization.
- Internal-transfer heuristics use the installation-wide tracked wallet universe, never an individual session payload.
- Process in chronological order:
  - `rawData.timeStamp ASC`
  - `rawData.transactionIndex ASC`
  - `txHash ASC`
- `txHash` is the raw-stage tie-breaker because canonical `_id` does not exist yet during normalization.
- Classification order:
  - protocol registry
  - method id
  - function name
  - transfer-pattern heuristics
- `rawData.methodId` must be recovered from `rawData.input[0:10]` when the stored
  selector is blank or `"0x"`.
- Known wrapper selectors and known bridge/router methods must be resolved before generic `deposit` / `withdraw` / `multicall` name fallbacks can capture them.
- Plain positive inbound transfer legs default to `EXTERNAL_INBOUND` unless
  contract-aware reward or bridge evidence exists.
- Promo/phishing inbound patterns must be removed from reward ambiguity handling before generic inbound defaults are applied.
- Repeated self-promotional inbound families such as Base `multicall(bytes[] data)` token-drop rows, Plasma selector `0x1939c1ff`, and repeated selector `0xeec4378e` must narrow before generic `EXTERNAL_INBOUND` can win.
- Economic meaning follows backfill-available raw legs and contract identity.
  Human-readable explorer page summaries are audit-only and never override
  canonical classifier output.
- Known bridge-entry selectors such as Across `depositV3` must resolve to
  `BRIDGE_OUT` before generic vault/lending fallback can capture them.
- Bridge-settlement selectors such as `fillV3Relay`, `fillRelay`, `redeemWithFee`, `execute302`, and `directFulfill` must be resolved as bridge continuity before broad `repay` / `redeem` / `withdraw` keyword fallback.
- Known LP position-manager router containers such as `multicall` and
  Uniswap-v4-style `modifyLiquidities` must be dispatched by contract-aware
  inner method rules, not by broad router keywords.
- Method-aware protocol bundles such as Morpho Bundler3 must be classified by
  contract-scoped dispatch before generic `multicall` / `bundle` fallback.
- Zero-amount token transfers without economic counterflow must never create `BUY` / `SELL` legs. Known setup/admin calls may resolve to `ADMIN_CONFIG`; unknown cases remain explicit review items.
- Protocol-registry runtime data is loaded from `backend/src/main/resources/protocol-registry.json` only.
- `event_topics` remain reference-only metadata and are ignored by the runtime classifier.
- Registry entries marked with `specialHandler` dispatch into one deterministic handler result per raw tx.
- If a special handler cannot support the observed method/function combination, the tx becomes `UNKNOWN -> NEEDS_REVIEW` with an explicit missing-data reason.
- Evidence sources:
  - canonical tx-level fields from the raw view / `explorer.tx`
  - `rawData.explorer.tokenTransfers[]`
  - `rawData.explorer.internalTransfers[]`
  - direct native tx value only when it is canonical tx-level evidence
  - persisted real receipt logs when a method-aware handler explicitly needs them
  - dedicated clarification receipt-log evidence when that evidence is
    persisted into canonical raw shape and exposed through the same raw view
- synthetic `rawData.logs[]` remain out of bounds.
- Classification rules should follow protocol-source semantics when official
  contracts or protocol docs are available, not explorer UI labels.
- Wrapped-native continuity must be resolved before generic vault/lending
  fallback:
  - `deposit()` on a known wrapper plus wrapped-token mint -> `WRAP`
  - `withdraw(uint256)` on a known wrapper plus wrapped-token burn and native
    continuity -> `UNWRAP`
- Recognized bridge-entry methods such as Across `depositV3(...)` must resolve
  before generic deposit / vault fallback.
- Route-tagged bridge-initiation families such as LI.FI / Jumper
  `callDiamondWith*` paths and `transferRemote(...)` must resolve before
  generic `EXTERNAL_TRANSFER_OUT` fallback can win.
- Route-tagged LI.FI / Jumper bridge initiations remain bridge semantics even
  when top-level `methodId` is blank, as long as the selector is recoverable
  from saved calldata and the calldata still carries official route-tag
  evidence.
- Circle CCTP destination-side `redeem(bytes cctpMsg,bytes cctpSigs)` rows
  with persisted bridged payout movement must resolve to `BRIDGE_IN` before
  generic `VAULT_WITHDRAW` fallback can win.
- Claim-income families such as `harvest(...)` and `release()` must resolve to
  explicit claim / income semantics before generic `EXTERNAL_INBOUND` fallback
  can win.
- Explicit receiver-wallet `claim(...)` / `claimWithSig(...)` payout rows must
  resolve to `REWARD_CLAIM` before generic `EXTERNAL_INBOUND` fallback can win.
- Request-initiation families such as
  `claimSharesAndRequestRedeem(uint256 sharesToRedeem)` are not finalized
  disposals and may not remain priceable `EXTERNAL_TRANSFER_OUT` rows until
  persisted evidence proves settlement or cancellation.
- GMX `createOrder(...)` and similar order-initiation rows are not finalized
  disposals and may not become priceable `EXTERNAL_TRANSFER_OUT` rows until
  persisted evidence proves final settlement.
- Trader Joe `LBRouter.addLiquidity(...)` is LP-entry semantics, not lending.
- Approval/configuration families such as `setMinterApproval(...)` are
  non-economic and must not resolve to LP or vault types from fee-only flow.
- Economic rows must not proceed to pricing unless canonical raw evidence or
  persisted clarification evidence proves non-fee movement semantics that are
  sufficient for later basis replay.
- Pricing readiness is a live-data gate, not a code-complete claim: successful
  tests or reruns are insufficient if the post-rerun Mongo audit still finds
  resolved wrapped-native continuity leaking into `VAULT_*` /
  `LENDING_WITHDRAW`, recognized bridge-entry rows leaking into
  `VAULT_DEPOSIT`, route-tagged bridge initiations leaking into
  `EXTERNAL_TRANSFER_OUT`, Circle CCTP `redeem(...)` rows leaking into
  `VAULT_WITHDRAW`, explicit receiver-wallet claim payout rows leaking into
  `EXTERNAL_INBOUND`, pending redeem-request initiation rows leaking into
  priceable `EXTERNAL_TRANSFER_OUT`, claim-income rows leaking into
  `EXTERNAL_INBOUND`, priceable GMX order-initiation rows leaking into
  `EXTERNAL_TRANSFER_OUT`, clarification persistence mismatches between raw and
  normalized state, or a broad repeatable review family that should have
  become deterministic from current raw or allowlisted clarification evidence.
- In the current post-`run/9` state, resolved-lane leakage and clarification
  persistence are no longer the primary blocker.
- The remaining pricing gate is a narrow review tail that must close from
  current raw plus already persisted clarification evidence:
  - Base Pancake / Infinity LP-exit container
    `0x0a757aeeb58667c545017cd8e5cd60dc994a8945ed810c60ea2aed18688f4f7a`
  - Avalanche Euler batch rows
    `0x1e0c429514e9cf892b0b6a11e3cfb290eff5c0c26a557c835496e4ba61717fdb`,
    `0x233c2b959739d298d1012405e9b3d7e535a87d590a81bcb304c6dc0cb3ce5e4f`,
    `0x305f37a69956a13001962216c845385996114876173bdbaef644bbe3baadf5df`
- The only remaining safe stop-condition review row is
  `0x0088de663d549fbc58dfa8dbba4180a346a580b1d6277254fa84a8ed9c27967a`
  on BSC. Persisted receipt evidence currently proves a zero-effect
  `modifyLiquidities(...)` call (`liquidityDelta = 0`, `liquidityChange = 0`,
  `feesAccrued = 0`), so this row should leave `NEEDS_REVIEW`, but it is not
  itself the reason pricing stays blocked.
- No new backfill is required for the current blocker set; the intended
  closeout remains `normalization + clarification` only.

### 3.3 Clarification

- `Clarification` is the on-chain receipt-enrichment stage.
- It is only for receipt-clarifiable records.
- Low confidence alone is not enough to enter `Clarification`.
- Base clarification enrichment allowed:
  - execution status
  - gas
  - created contract address
- Clarification reasons must match the actual missing receipt-safe fields.
  `MISSING_CONTRACT_ADDRESS` is valid only when contract-creation intent is
  explicitly evidenced by the tx-shaped raw payload.
- Missing `effectiveGasPrice` is a clarification reason even when legacy
  `gasPrice` is still usable for provisional fee math.
- Low-confidence rows that are already economically coherent must proceed directly to `PENDING_PRICE`.
- Unsupported semantic gaps must move directly to `NEEDS_REVIEW`.
- Clarification must not treat synthetic logs as first-class classification input.
- Metadata-only clarification is not used to decide promo/phishing inbound, bridge-settlement continuity, LP position-manager multicalls, or zero-value no-op token calls.
- Metadata-only clarification is not used to upgrade per-wallet `CLAIM_WITHOUT_MOVEMENT` rows
  into `REWARD_CLAIM`.
- Clarification is not the place to repair route-tagged LI.FI / Jumper bridge
  initiation leaks, Circle CCTP `redeem(...)` destination-side bridge-in
  semantics, explicit receiver-wallet claim payout semantics, or pending
  redeem-request initiation semantics when those rows are already closable from
  current raw plus persisted clarification evidence.
- Clarification rows must carry explicit missing receipt-safe reasons.
- Clarification may additionally fetch full receipt evidence only for an
  allowlisted set of review families where the audit and official protocol
  semantics show that receipt evidence can materially close the gap.
- When full receipt evidence is fetched, clarification should persist both:
  - the adapted clarification-evidence fields used by runtime classification
  - the raw full receipt payload when the source makes it available
- If a clarification source call already returns a receipt payload, the system
  persists that source-native receipt payload in full even when the current
  clarification path will use only metadata-safe fields semantically.
- Clarification is not complete for a row unless the fetched clarification
  evidence is actually persisted back to the raw row in a deterministic shape.
- Clarification persistence must use one canonical raw-level evidence contract
  that is visible to both runtime classification and live Mongo audits.
  Writing only scalar receipt-safe fields into `rawData.*` is not sufficient
  when normalized counters claim clarification already happened.
- The distinction between metadata-safe clarification and receipt-log-backed
  clarification is a semantic-usage policy, not a storage-truncation policy.
- Runtime pipeline code may consume clarification evidence only through
  `OnChainRawTransactionView` / the canonical raw projection.
- When canonical top-level `clarificationEvidence` is present and runtime reads
  only through the canonical raw projection, any leftover nested
  `rawData.clarificationEvidence` shape is a data-shape warning, not a pricing
  blocker by itself.
- Clarification telemetry must stay live-parity safe: persisted clarification
  evidence on raw rows must not silently drift away from normalized
  clarification attempt counters.
- Full-receipt clarification may also persist same-source internal transfers
  for an allowlisted native-bridge subset when those internal legs are needed
  to reconstruct bridge continuity and the producing source family exposes
  them.
- full receipt payload must remain separate from synthetic `rawData.logs[]` and
  from the adapted canonical evidence fields.
- Clarification may rerun classification only from canonical raw evidence plus
  production-fetchable full receipt evidence.
- Concentrated-liquidity clarification must still prove economic direction:
  - Base Pancake / Infinity exit containers need persisted collect /
    withdrawal / unwrap continuity before they become `LP_EXIT`
  - BSC `modifyLiquidities(...)` needs persisted positive liquidity-add or
    tracked-wallet funding evidence before it becomes `LP_ENTRY`
- Clarification enrichment must follow source lineage:
  - RPC-backed raw -> RPC clarification
  - Etherscan-family raw -> Etherscan-family clarification
  - Blockscout-backed raw -> Blockscout clarification
- Cross-source clarification fallback is allowed only when the configured
  lineage-consistent source fails and that fallback path is explicitly
  documented.
- Clarification must not use traces, explorer UI summaries, or manual audit
  notes as runtime evidence.
- Families already closable from current raw, such as claim-family no-movement
  rows and known Morpho handler gaps, must be fixed in classification rather
  than deferred to clarification enrichment.
- Safe non-economic review-tail families that are already deterministic from
  current raw must leave `NEEDS_REVIEW` without clarification. This includes
  spam / airdrop clusters, explicit `CLAIM_WITHOUT_MOVEMENT`, failed
  transactions, documented admin / governance actions, pending-request /
  pending-order initiation rows, and out-of-scope NFT or attestation mints.
- Clarification is reserved for receipt-closeable residuals such as
  Slipstream cleanup/stake families, Pancake / Infinity CL lifecycle families,
  Euler `batch(...)`, ParaSwap `swapExactAmountOut(...)`, GMX
  `executeOrder(...)`, and Katana `routeSingle(...)` when persisted
  full-receipt evidence actually closes them.
- If clarification still leaves only weak protocol identity, zero logs, or
  wrapper-only bookkeeping evidence, the row stays in an explicit documented
  stop-condition set instead of being forced into synthetic economic
  semantics.
- Under-evidenced economic rows may be promoted only when persisted
  clarification evidence closes the missing movement semantics; otherwise they
  must stay review/clarification and may not move into pricing.

### 3.4 Bybit normalization

- `external_ledger_raw` remains immutable source evidence.
- `BybitTradePairer` uses a sliding `±5 sec` window for UTA trade pairing.
- Matched Bybit withdraw/deposit and on-chain movements share a `correlationId`.
- Canonical accounting docs still land in `normalized_transactions` for both `ON_CHAIN` and `BYBIT`.
- Double-counting is prevented by `TRANSFER` semantics and replay carry-over rules, not by discarding one side of the source trail.

### 3.5 Pricing and replay

- Pricing order:
  - stablecoin parity
  - swap-derived ratio
  - wrapper/native mapping
  - CoinGecko historical fallback
  - `PRICE_UNKNOWN`
- AVCO replay input is `normalized_transactions WHERE status=CONFIRMED`.
- Pricing / AVCO may not start until the post-rerun live audit shows:
  - zero resolved wrapped-native selector leaks into `VAULT_DEPOSIT`,
    `VAULT_WITHDRAW`, or `LENDING_WITHDRAW`
  - zero resolved recognized Across `depositV3(...)` leaks into `VAULT_DEPOSIT`
  - zero resolved route-tagged bridge-initiation leaks into
    `EXTERNAL_TRANSFER_OUT`, including LI.FI / Jumper `callDiamondWith*`
    families and `transferRemote(...)`
  - zero resolved Circle CCTP `redeem(...)` leaks into `VAULT_WITHDRAW`
  - zero resolved explicit receiver-wallet claim payout leaks into
    `EXTERNAL_INBOUND`, including merkle `claim(...)` and signed
    `claimWithSig(...)`
  - zero resolved pending redeem-request initiation leaks into priceable
    `EXTERNAL_TRANSFER_OUT`, including
    `claimSharesAndRequestRedeem(...)`
  - zero resolved claim-income leaks into `EXTERNAL_INBOUND`, including
    Pancake `harvest(...)` and vesting `release()`
  - zero priceable GMX `createOrder(...)` rows without finalized settlement
    semantics
- Replay order is deterministic:
  - `blockTimestamp ASC`
  - `transactionIndex ASC`
  - `_id ASC`
- `_id` is the canonical-stage tie-breaker after normalized documents have been materialized.

---

## 4. Key Collections

- `user_sessions`
  Persisted wallet sets and selected networks keyed by client-generated `sessionId`.
- `tracked_wallets`
  Installation-wide wallet universe used by canonical normalization and transfer detection.
- `sync_status`
  Backfill lifecycle per wallet×network.
- `backfill_segments`
  Persistent segment orchestration and retries.
- `raw_transactions`
  Immutable on-chain evidence with controlled enrichment.
- `external_ledger_raw`
  Immutable Bybit import layer.
- `normalized_transactions`
  Canonical accounting stream for both on-chain and Bybit sources.
- `asset_positions`
  Materialized replay state and reconciliation flags.

---

## 5. Design Rules

- GET endpoints remain datastore-only.
- Raw collections are source evidence; canonical accounting consumes normalized documents only.
- `backend/src/main/resources/protocol-registry.json` is the only authoritative protocol-registry source for runtime classification.
- Tx-level native value must never be reconstructed from token transfer-row amounts.
- `KATANA` and `PLASMA` are supported EVM networks in the v3 accounting layer.
- WETH aliasing happens at replay time only.
- Basis continuity applies to:
  - wallet-internal transfers
  - bridge matches
  - lending and vault custody movements
  - correlated Bybit <-> on-chain custody movements

---

## 6. Deferred Work

Deferred until after v3 core is stable:

- current-balance polling
- portfolio snapshots
- broader transaction history projections
- extra CEX connectors
- microservice extraction
