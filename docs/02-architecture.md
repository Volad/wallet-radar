# WalletRadar — Architecture

> **Version:** SAD v3 target summary
> **Last updated:** 2026-03-22
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
- synthetic `rawData.logs[]` remain out of bounds.
- Classification rules should follow protocol-source semantics when official
  contracts or protocol docs are available, not explorer UI labels.

### 3.3 Clarification

- Only for receipt-clarifiable records.
- Low confidence alone is not enough to enter clarification.
- Enrichment allowed:
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
- Clarification is not used to decide promo/phishing inbound, bridge-settlement continuity, LP position-manager multicalls, or zero-value no-op token calls.
- Clarification is not used to upgrade per-wallet `CLAIM_WITHOUT_MOVEMENT` rows
  into `REWARD_CLAIM`.
- Clarification rows must carry explicit missing receipt-safe reasons.

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
