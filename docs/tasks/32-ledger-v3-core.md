# Feature/Change Spec — WalletRadar (Business Analyst)

Goal (1 sentence):

Rebuild the v3 accounting core so WalletRadar can normalize on-chain and Bybit evidence into one canonical event stream, price it deterministically, and replay authoritative AVCO on top of the existing backfill and `user_sessions` foundation.

Implementation handoff:

- Backend executor-ready backlog: `docs/tasks/33-ledger-v3-backend-execution.md`

## Acceptance Criteria (Definition of Done)

- [ ] On-chain normalization starts from `raw_transactions` only and produces canonical rows in `normalized_transactions`.
- [ ] Bybit normalization starts from `external_ledger_raw` only and produces canonical rows in `normalized_transactions` for basis-relevant rows.
- [ ] Matched Bybit withdraw/deposit rows and on-chain txs share a `correlationId` and do not create duplicate BUY/SELL events in replay.
- [ ] UTA derivative trade pairing uses a sliding `±5 sec` window, not a fixed time bucket.
- [ ] Internal-transfer heuristics use one installation-wide tracked wallet universe, not per-session wallet sets.
- [ ] Clarification uses receipt metadata only for status/gas/contract creation fields, does not rely on synthetic `rawData.logs[]`, and is entered only when those receipt-safe fields are actually missing.
- [ ] Protocol-registry runtime data is loaded from `backend/src/main/resources/protocol-registry.json` only; `event_topics` are reference-only and ignored by the classifier.
- [ ] Multi-function registry entries use explicit `specialHandler` dispatch and return one canonical result per raw tx.
- [ ] Wrapped-native `deposit()` / `withdraw(uint256)` on known wrapper contracts classify as `WRAP` / `UNWRAP` before generic function-name fallback.
- [ ] Blank or `"0x"` `rawData.methodId` is recovered from `rawData.input[0:10]` before method-id classification runs.
- [ ] Known bridge/router methods are not allowed to collapse into generic `VAULT_*` or `EXTERNAL_*` types just because the decoded name contains `deposit`, `withdraw`, `multicall`, or `batch`.
- [ ] Plain positive inbound transfer legs default to `EXTERNAL_INBOUND` unless reward-specific contract evidence or bridge continuity evidence exists.
- [ ] `AMBIGUOUS_INBOUND_VS_REWARD` defaults to `EXTERNAL_INBOUND` unless reward-specific evidence exists; receipt clarification is not used to resolve that ambiguity.
- [ ] Promo/phishing inbound patterns are excluded from reward ambiguity handling and do not enter accounting as normal `EXTERNAL_INBOUND` or `REWARD_CLAIM`.
- [ ] Scam filtering uses composite promo/phishing signals and must not drop known legitimate reward-claim routes that lack promo markers.
- [ ] Source-specific backfill paths may enrich `raw_transactions`, but production classification remains source-agnostic and reads only canonical raw evidence through the normalization view/projection.
- [ ] Real provider-emitted backfill logs and tx metadata may be persisted as canonical raw evidence; synthetic or invented `rawData.logs[]` remain forbidden.
- [ ] `BSC` may use provider-first backfill with native RPC fallback for repair/missing fields, but classifier behavior must not branch on `BSC` or on ingestion source.
- [ ] Production classification is derived only from backfill-available raw evidence and receipt-safe clarification evidence; human-readable explorer page summaries never override raw legs.
- [ ] Across `depositV3` classifies to `BRIDGE_OUT` on recognized bridge-entry contracts.
- [ ] Bridge-settlement selectors such as `fillV3Relay`, `fillRelay`, `redeemWithFee`, `execute302`, and `directFulfill` classify to bridge continuity semantics rather than `REPAY` or `VAULT_*` fallback.
- [ ] Zero-amount token transfers with no economic counterflow never create `BUY` / `SELL` movement; they resolve to contract-scoped admin/no-op handling or explicit review.
- [ ] Position-manager `multicall` that mints or increases a V3 LP position normalizes to `LP_ENTRY`, not `UNKNOWN`.
- [ ] Method-aware bundle routers such as Morpho Bundler3 and CL position-manager `modifyLiquidities` do not fall through to broad `multicall` / `deposit` / `withdraw` keyword fallback.
- [ ] Missing `rawData.transactionIndex` is repaired before canonical normalization or surfaced explicitly as a blocker; no guessed ordering index is allowed.
- [ ] Historical pricing follows the order: stablecoin parity -> swap-derived -> wrapper/native mapping -> CoinGecko historical -> unresolved price flag.
- [ ] `PRICE_UNKNOWN` does not drop quantity from replay and sets incomplete-history signaling.
- [ ] AVCO replay is deterministic by `blockTimestamp ASC`, then `transactionIndex ASC`, then `_id ASC`.
- [ ] `KATANA` and `PLASMA` are treated as supported accounting networks in docs, requirements, and replay rules.
- [ ] The milestone does not require new onboarding UX, new current-balance architecture, new snapshot architecture, or any CEX provider beyond Bybit.
- [ ] Blockers and unresolved cases are recorded explicitly instead of being silently misclassified.
- [ ] Logging/telemetry can distinguish: on-chain normalized, Bybit normalized, unmatched Bybit bridge, orphan UTA leg, price unresolved, and needs-review outputs.

## Edge Cases (with scope)

- Case: Bybit withdrawal matched to on-chain receive by `txHash` | Scope: In | Expected behaviour: canonical rows are correlated, replay treats them as one basis-carry transfer, no duplicate BUY/SELL.
- Case: Bybit deposit matched to on-chain send by `txHash` | Scope: In | Expected behaviour: canonical rows are correlated, replay preserves basis continuity and avoids duplicate disposal/acquisition.
- Case: Same transfer is visible in two different sessions with different wallet sets | Scope: In | Expected behaviour: canonical tx meaning stays stable because transfer classification depends on installation-wide wallet universe, not session payload.
- Case: UTA trade legs fall on different 5-second buckets but are 2 seconds apart | Scope: In | Expected behaviour: pair must still be formed.
- Case: UTA trade has only one side present | Scope: In | Expected behaviour: orphan is classified conservatively and recorded for blocker tracking.
- Case: On-chain tx has only synthetic `rawData.logs[]` evidence | Scope: In | Expected behaviour: logs are ignored; classifier uses explorer/native fields only.
- Case: Wrapper contract call uses `deposit()` or `withdraw(uint256)` with no helpful function-name context | Scope: In | Expected behaviour: known wrapper selector wins and the tx becomes `WRAP` or `UNWRAP`, not generic `VAULT_*` or `EXTERNAL_*`.
- Case: `rawData.methodId` is `"0x"` but calldata contains a selector | Scope: In | Expected behaviour: selector is recovered from `rawData.input[0:10]` before method-id classification.
- Case: Bridge/router function name contains `deposit` or `multicall` | Scope: In | Expected behaviour: known method/registry path wins over broad function-name fallback.
- Case: Inbound transfer has no reward-specific evidence | Scope: In | Expected behaviour: tx defaults to `EXTERNAL_INBOUND` and may retain `AMBIGUOUS_INBOUND_VS_REWARD` as metadata; it does not wait in clarification for receipt metadata.
- Case: Inbound transfer has promo/phishing token metadata | Scope: In | Expected behaviour: tx is filtered or surfaced as explicit spam/phishing review; it does not stay in reward ambiguity.
- Case: Explorer page summary says "Transfer" but raw legs show token out + native in | Scope: In | Expected behaviour: classifier follows raw legs and keeps swap-like semantics.
- Case: Backfill source is provider-first and persists real logs into raw | Scope: In | Expected behaviour: classifier may use those persisted raw logs through the normal view/projection; it does not branch on source or network to change semantics.
- Case: Recognized bridge-entry `depositV3` call on Across | Scope: In | Expected behaviour: canonical type is `BRIDGE_OUT`, not `VAULT_DEPOSIT`.
- Case: Bridge settlement tx uses `fillV3Relay` / `fillRelay` / `redeemWithFee` | Scope: In | Expected behaviour: canonical type follows bridge continuity semantics, not `REPAY` or generic vault/lending fallback.
- Case: Token transfer leg has zero quantity and no economic counterflow | Scope: In | Expected behaviour: tx does not produce economic movement; it routes to explicit no-op/admin handling or review.
- Case: Known V3 position-manager `multicall` adds liquidity and mints NFT | Scope: In | Expected behaviour: tx becomes `LP_ENTRY`, not router `UNKNOWN`.
- Case: Morpho Bundler3 `multicall` mixes protocol actions | Scope: In | Expected behaviour: method-aware contract routing decides final canonical type; broad bundle keywords do not decide it.
- Case: CL position-manager `modifyLiquidities` changes a concentrated-liquidity position | Scope: In | Expected behaviour: canonical type follows decoded action set / legs, not generic `UNKNOWN`.
- Case: Legitimate BSC claim route arrives from provider-backed raw with claim selector, inbound token transfer, and claim event | Scope: In | Expected behaviour: tx becomes `REWARD_CLAIM` and survives scam filtering.
- Case: Raw tx is missing `transactionIndex` | Scope: In | Expected behaviour: tx enters bounded raw ordering repair before canonical normalization; ordering is never guessed.
- Case: Registry contract is known but the special handler does not support the observed method | Scope: In | Expected behaviour: tx becomes `UNKNOWN`, `NEEDS_REVIEW`, and records `HANDLER_UNSUPPORTED_METHOD`.
- Case: Price cannot be resolved for one leg | Scope: In | Expected behaviour: quantity remains in replay, asset state is marked incomplete.
- Case: Wrapped native asset appears on one network and native asset on another | Scope: In | Expected behaviour: storage keeps original symbol; alias policy is applied only in replay/pricing rules.
- Case: Additional CEX provider is proposed | Scope: Out | Expected behaviour: not implemented in this milestone.
- Case: New snapshot/chart pipeline is requested | Scope: Out | Expected behaviour: deferred; no dependency for v3 core completion.

## Supported vs Unsupported

- Supported:
  on-chain reconstruction from `raw_transactions`
  Bybit reconstruction from `external_ledger_raw`
  deterministic normalization, pricing, AVCO, and reconciliation
  networks `ETHEREUM`, `ARBITRUM`, `OPTIMISM`, `POLYGON`, `BASE`, `BSC`, `AVALANCHE`, `MANTLE`, `LINEA`, `UNICHAIN`, `ZKSYNC`, `KATANA`, `PLASMA`
  persisted `user_sessions` and existing backfill control-plane
- Unsupported:
  additional CEX providers
  tax reporting
  NFT accounting
  rebase-token quantity lifecycle
  redesigned onboarding or snapshot subsystems in the same milestone

## Test Scenarios

- Happy path:
  complete wallet×network raw set normalizes into canonical docs, prices successfully, and replays into stable `asset_positions`.
- Happy path:
  Bybit trade pair with BUY and SELL rows inside `±5 sec` becomes one canonical `SWAP`.
- Edge:
  matched Bybit withdrawal plus on-chain receive produces correlated transfer-only treatment without basis duplication.
- Edge:
  matched Bybit deposit plus on-chain send preserves basis continuity on the wallet side.
- Edge:
  unresolved price still updates quantity and emits incomplete-history signaling.
- Negative:
  synthetic logs alone cannot move a record from unresolved to confirmed classification.
- Negative:
  orphan UTA leg is not forcibly paired across an invalid time distance.
- Negative:
  unmatched Bybit bridge row is visible as unmatched, not silently discarded.

## Task Breakdown (Executor-ready)

1. Canonical model alignment — define and implement the target `normalized_transactions` fields needed for source, status, pricing, and correlation semantics. Depends on: none.
2. Wallet-universe projection — maintain one installation-wide tracked wallet set derived from persisted tracking state and use it as normalization input. Depends on: none.
3. Protocol-registry runtime integration — load and validate the classpath registry, ignore `event_topics`, and expose deterministic contract lookup. Depends on: 1.
4. On-chain normalization rewrite — rebuild classification, leg extraction, special-handler dispatch, wrapper/router fast-paths, and canonical construction from `raw_transactions` under strict ordering and evidence rules. Depends on: 1, 2, 3.
5. Clarification rewrite — implement receipt-metadata enrichment with explicit prohibition on synthetic-log evidence and explicit eligibility gating so low confidence alone does not enter clarification. Depends on: 4.
6. Pricing rewrite — implement the new resolver chain and unresolved-price handling. Depends on: 1, 4.
7. Bybit normalization rewrite — pair UTA trades with sliding `±5 sec`, correlate withdraw/deposit rows by `txHash`, and emit canonical correlated docs. Depends on: 1.
8. AVCO/reconciliation rewrite — replay confirmed canonical docs with transfer carry-over and correlation semantics. Depends on: 1, 6, 7.
9. Determinism and blocker reporting — add explicit blocker/warning outputs and deterministic ordering guarantees across all replay inputs. Depends on: 4, 7, 8.
10. Documentation pass — keep `architecture-v3.md`, `docs/02-architecture.md`, and `docs/03-accounting.md` aligned with the implemented rules. Depends on: 1-9.

## Risk Notes / Assumptions / Open Questions

- Assumption: raw collection quality is already sufficient; this milestone starts after data acquisition.
- Assumption: Bybit remains the only CEX source in the near term.
- Risk: transfer carry-over semantics are implemented incompletely and reintroduce double-counting | Mitigation: require correlation tests covering matched deposit/withdraw pairs.
- Risk: network support drifts from code/config again | Mitigation: keep docs aligned with `NetworkId` and pricing/normalization coverage.
- Risk: unresolved-price behavior gets treated as hard failure | Mitigation: preserve quantity movement and surface explicit incomplete-history flags.
- Risk: `PENDING_CLARIFICATION` becomes a generic low-confidence backlog and hides classifier gaps | Mitigation: gate clarification by missing receipt-safe metadata only and track unsupported semantic gaps separately.
- Risk: explorer payload parity drifts by network and blocks confident classification, especially on `BSC` | Mitigation: keep raw-source completeness checks explicit and track incomplete explorer payloads as blockers instead of silently weakening rules.
- Risk: provider-specific BSC backfill enriches raw but classifier accidentally becomes source-aware | Mitigation: persist provider evidence into canonical raw fields only and keep classifier inputs limited to `raw_transactions` view/projection.
- Risk: provider API drift or partial payload on `BSC` reintroduces missing tx-level fields or logs | Mitigation: add provider-response validation, native repair fallback, and fixture-based regression tests from audited BSC hashes.
