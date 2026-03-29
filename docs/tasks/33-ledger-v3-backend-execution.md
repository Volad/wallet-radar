# Backend Execution Backlog — Ledger v3

Goal (1 sentence):

Implement the approved v3 backend approach so WalletRadar can normalize on-chain and Bybit evidence into one canonical accounting stream, price it deterministically, and replay authoritative AVCO without changing the existing backfill and `user_sessions` foundation.

## Implementation Constraints

- Respect `docs/architecture-v3.md`, `docs/03-accounting.md`, `docs/04-api.md`, and `docs/normalization-architecture-en.md` as the current source of truth.
- Do not redesign architecture, frontend, or product rules during implementation.
- Keep existing session and backfill APIs working.
- Keep scheduled/background jobs idempotent, retry-safe, concurrency-safe, and bounded.
- Avoid full collection scans in steady-state jobs.
- Use deterministic ordering and deterministic writes only.

## Execution Order

1. `BE-01` Canonical schema + repositories
2. `BE-02` Tracked-wallet projection
3. `BE-03` On-chain normalization foundation
4. `BE-04` On-chain classifier and leg extraction
5. `BE-05` Clarification
6. `BE-06` Pricing
7. `BE-07` Bybit normalization
8. `BE-08` AVCO replay and reconciliation
9. `BE-09` Jobs, observability, and blocker handling
10. `BE-10` Legacy-path cleanup and final verification

Parallelism allowed:

- `BE-02` may begin in parallel with `BE-01` after document shape is agreed.
- `BE-07` may begin after `BE-01` while `BE-03` to `BE-06` are in progress.
- `BE-09` can start after `BE-03` and expand as each pipeline stage lands.

## Immediate Continuation From Current Partial Implementation

The current codebase has already completed the normalization/clarification
closeout required for pricing readiness. The next executor slice must proceed
in this order:

1. `BE-06A` Pricing source contract + historical price cache
2. `BE-06B` Event-local resolvers for stablecoin, execution, swap-derived, and wrapper pricing
3. `BE-06C` Binance historical market-data adapter + deterministic symbol mapping
4. `BE-06D` CoinGecko bounded fallback + unresolved-price behavior
5. `BE-06E` Bybit pricing integration and continuity-safe pricing rules
6. `BE-06F` Pricing rerun pack + AVCO-start data gate

### BE-04A — Protocol-Registry Source Consolidation + Loader/Validator

Purpose:
- Make the registry safe and deterministic before it is allowed to drive production classification.

Primary write scope:
- `backend/src/main/resources/protocol-registry.json`
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- startup/config wiring for registry loading

Implementation scope:
- authoritative runtime source is `backend/src/main/resources/protocol-registry.json` only
- remove or stop depending on duplicate root-level registry files
- load contract entries from the classpath resource
- filter decorative section keys and metadata keys like `description`
- ignore `event_topics` entirely in runtime loader
- validate enums/shape strictly, including `PERP`
- treat network coverage gaps such as `UNICHAIN` with zero contract entries as warnings, not startup failure

Definition of done:
- runtime classifier reads one authoritative registry source
- malformed registry data fails fast at startup
- reference-only `event_topics` never enter runtime classification state

Required tests:
- registry-loader happy-path test from classpath resource
- validation test for bad enum/value
- test proving `event_topics` are ignored
- test proving decorative keys and metadata keys are skipped

### BE-04B — Single-Result Special-Handler Contract + Dispatcher

Purpose:
- Support multi-function protocol contracts without breaking the accepted `1 tx = 1 doc` invariant.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`

Implementation scope:
- add `specialHandler` and `decomposeByLegs` support to runtime registry model
- define one shared handler contract over:
  - `ProtocolRegistryEntry`
  - `RawTransactionNormalizationView`
  - already extracted `RawLeg` list
- handler output is exactly one canonical classification result
- unsupported method/function combination -> `UNKNOWN`, `NEEDS_REVIEW`, `HANDLER_UNSUPPORTED_METHOD`
- handlers must be pure functions:
  - no RPC
  - no Mongo
  - no synthetic logs

Definition of done:
- registry can explicitly route multi-function contracts into handler-based classification
- handler output shape is uniform and consumable by `NormalizedTransactionBuilder`
- unsupported handler cases are explicit and reviewable

Required tests:
- dispatcher routing test by `specialHandler`
- unsupported-method test producing `NEEDS_REVIEW`
- single-result contract test preserving `1 tx = 1 doc`

### BE-04C — Registry-Backed Classifier Rollout + Coverage Tests

Purpose:
- Replace the current protocol-registry stub with real registry-backed classification behavior.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- classifier tests and normalization tests

Implementation scope:
- wire loader + dispatcher into `ProtocolRegistryService` and `OnChainClassifier`
- first concrete handlers:
  - `BALANCER_VAULT`
  - `GMX_V2_EXCHANGE_ROUTER`
  - `PENDLE_ROUTER`
- keep fallback order strict:
  - registry
  - method id
  - function name
  - heuristic
- retain `UNICHAIN` fallback behavior until registry coverage exists

Definition of done:
- protocol-registry precedence works from the real classpath registry
- multi-function contracts no longer rely on one misleading static `event_type`
- fallback behavior remains deterministic for unsupported or uncovered networks/contracts

Required tests:
- Balancer join/exit handler tests
- GMX V2 `createOrder/createDeposit/createWithdrawal` tests
- Pendle swap vs LP add/remove tests
- `UNICHAIN` known-gap fallback test

## Immediate Follow-Up After Clarification + Needs-Review Audit

The current `PENDING_CLARIFICATION` and `NEEDS_REVIEW` audit identified a short
critical-path slice that should be executed before broader pricing and replay work
continues. This slice is still implementation-only; it does not reopen architecture.

Execute in this order:

1. `BE-04D` Selector recovery from calldata
2. `BE-04E` `ADMIN_CONFIG` type for known non-basis admin/config calls
3. `BE-05A` Clarification eligibility gate and status triage
4. `BE-04J` Wrapped-native selector fast-paths
5. `BE-04K` Bridge/router de-overload before generic function-name fallback
6. `BE-04L` Reward-vs-inbound defaulting without clarification
7. `BE-04F` V3 LP-position lifecycle refinement
8. `BE-04G` zkSync router coverage through registry + method-aware dispatch
9. `BE-03B` raw ordering metadata repair for missing `transactionIndex`
10. `BE-04H` Plasma spam/no-op handling
11. `BE-04M` Promo/phishing inbound exclusion from reward ambiguity
12. `BE-04N` Bridge-settlement continuity selectors
13. `BE-04O` Zero-amount token transfer and no-op routing
14. `BE-04P` Position-manager multicall LP-entry coverage
15. `BE-04Q` Plain inbound transfer narrowing + reward-distributor allowlist
16. `BE-04R` Across bridge-entry coverage (`depositV3`)
17. `BE-04S` Morpho Bundler3 method-aware routing
18. `BE-04T` CL position-manager `modifyLiquidities` coverage
19. `BE-04U` ScamFilter precision hardening + legitimate-claim regression
20. `BE-03D` Native/provider RPC split + composite provider orchestration
21. `BE-03C` BSC provider-first raw acquisition + payload-parity hardening
22. `BE-03E` Native RPC repair fallback for provider gaps
23. `BE-04V` Raw-view support for provider-backed canonical evidence
24. `BE-04W` Generic classification coverage for audited BSC families
25. `BE-04I` terminal failed-tx regression coverage

## Audit-Driven Priorities From `results/`

The latest audit artifacts in `results/` and `data/derived/` add four open
remediation blockers that must be treated as one combined execution slice, not
as an isolated `BSC` branch:

- `B-10` false `PENDING_CLARIFICATION` bucket
  - primary tasks:
    - `BE-05A`
    - `BE-04Q`
    - `BE-04N`
- `B-11` false positives in `PROMO_SPAM_PHISHING`
  - primary tasks:
    - `BE-04U`
    - `BE-04N`
    - `BE-04Q`
- `B-12` repeatable method-aware `NEEDS_REVIEW` families
  - primary tasks:
    - `BE-04P`
    - `BE-04T`
    - `BE-04G`
    - `BE-04W`
    - existing `BE-04F`
- `B-09` incomplete and non-closed `BSC` raw acquisition
  - primary tasks:
    - `BE-03D`
    - `BE-03C`
    - `BE-03E`
    - `BE-04V`
    - `BE-04W`

Execution priority for this audit-driven slice:
1. `BE-05A`
2. `BE-04U`
3. `BE-04N`
4. `BE-04Q`
5. `BE-04P`
6. `BE-04T`
7. `BE-04G`
8. `BE-03D`
9. `BE-03C`
10. `BE-03E`
11. `BE-04V`
12. `BE-04W`

## Repeat Classification Remediation Slice — 2026-03-22 Audit

The repeat audit in `results/raw-classification-audit.md` adds a broader
implementation slice. The goal is no longer only to reduce raw `NEEDS_REVIEW`,
but to close the main semantic gaps that still block trustworthy classification.

Execute in this order:

1. `BE-05B` False-clarification collapse + existing-row re-triage
2. `BE-04X` Selector-recovery closure on live classifier path
3. `BE-04Y` Bridge-settlement continuity hardening
4. `BE-04Z` Contract-aware reward-family expansion
5. `BE-04AA` Claim/no-op differentiation on known claim contracts
6. `BE-04AB` Lending withdraw semantic inversion fix
7. `BE-04AC` CL position-manager multicall coverage across supported networks
8. `BE-04AD` `modifyLiquidities` method-aware routing on Unichain and BSC
9. `BE-04AE` ScamFilter precedence hardening over bridge/reward allowlists
10. `BE-03F` BSC raw-completeness gate before marking sync complete
11. `BE-04AF` Final classification regression pack + repeat-audit fixtures

### BE-05B — False-Clarification Collapse + Existing-Row Re-Triage

Purpose:
- Remove the current false `PENDING_CLARIFICATION` backlog from the live path and
  stop sending fully populated rows into clarification.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- `backend/src/main/java/com/walletradar/ingestion/job/clarification/**`
- `backend/src/main/java/com/walletradar/ingestion/job/normalization/**`

Implementation scope:
- treat clarification as eligible only when receipt-safe fields are actually missing:
  - `txreceipt_status`
  - `gasUsed`
  - `effectiveGasPrice`
  - created contract address where applicable
- rows that already have those fields must start in `PENDING_PRICE` or `NEEDS_REVIEW`
- clarification job must short-circuit existing false rows and re-triage them instead
  of looping them back into the same bucket
- do not use confidence alone as clarification trigger

Definition of done:
- new normalization runs no longer create false `PENDING_CLARIFICATION`
- current false clarification rows can be re-processed without manual Mongo surgery
- clarification queue size becomes explainable by real missing enrichment only

Required fixtures:
- `0xd09408b311b762fc930bfb6190a9b3967c9b123ec7e6b89e9f29ceda01d46417`
- `0x27978f7bf88cd7a4825b991ac6e461fa96be75b280add44236beb2e060c61ba3`
- `0x565706c06b757b6cbf064cf36c884afcb78d314637ff906d11a6a09366cb2b07`

Required tests:
- row with full receipt-safe metadata starts in `PENDING_PRICE`
- unsupported semantic gap starts in `NEEDS_REVIEW`
- existing false clarification row is re-triaged on clarification run

### BE-04X — Selector-Recovery Closure On Live Classifier Path

Purpose:
- Close the remaining gap where blank `methodId` is still leaking into
  `CLASSIFICATION_FAILED` and `ROUTER_METHOD_OVERLOAD_UNSUPPORTED`.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/onchain/**`
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`

Implementation scope:
- guarantee that every classifier stage reads selector through one canonical
  recovered view value
- cover both plain method-id matching and router/handler dispatch inputs
- ensure recovered selector is visible to review/blocker code paths too
- do not add duplicate fallback logic inside support helpers

Definition of done:
- recoverable blank-selector rows stop failing only because `methodId` was empty
- current recoverable review clusters on Optimism/Base/zkSync are reduced after re-run

Required fixtures:
- `0x0df0357f71d270827610838eb48d0c35fc1437e0da0c12d83211724dc1a28cac`
- `0x927d3f458ada7e5ec67f77129e29edcaf2f69bd2b81490a42fec17c0cc3bd4fa`
- `0x9613c2d1d324436be6f4da1053fe0a54ac9be62722cd3996dfce064a905b31d8`
- `0xb7a9086def86956c896bb9a53326dacee73be2cf17c5741ea7c4e4e6f21c7afc`

Required tests:
- recovered selector reaches method-id rule
- recovered selector reaches router-overload dispatch
- recovered selector reaches review-reason generation

### BE-04Y — Bridge-Settlement Continuity Hardening

Purpose:
- fix the current semantic drift where bridge destination-side settlement becomes
  `REPAY` or promo/phishing review.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- `backend/src/main/resources/protocol-registry.json`

Implementation scope:
- classify these families as `BRIDGE_IN` on known bridge/settlement contracts:
  - `redeemWithFee(...)`
  - `fillV3Relay(...)`
  - `fillRelay(...)`
  - extend to existing documented settlement selectors such as `execute302` and `directFulfill`
- ensure this routing runs before promo/spam heuristics and before generic
  `repay` / `withdraw` function-name fallback
- keep continuity semantics basis-neutral

Definition of done:
- destination-side bridge settlement no longer lands in `REPAY`
- destination-side bridge settlement no longer lands in `PROMO_SPAM_PHISHING`

Required fixtures:
- `0xd2cdbd7a1ade37a8032b713e9844351d2f58fbd872851a0e88203b8fbb695c5f`
- `0xbf62327840d7624bc1ae12b9213cf66593b671d238da8e9feea57d1b3833ed38`
- `0x16a78ce7e8964eb95ef52d24ea618846e478a777b6df9d521884a30dc0b6ec1a`
- `0x27978f7bf88cd7a4825b991ac6e461fa96be75b280add44236beb2e060c61ba3`
- `0x565706c06b757b6cbf064cf36c884afcb78d314637ff906d11a6a09366cb2b07`

Required tests:
- `redeemWithFee(...)` -> `BRIDGE_IN`
- `fillV3Relay(...)` -> `BRIDGE_IN`
- `fillRelay(...)` -> `BRIDGE_IN`
- bridge settlement bypasses promo-spam rule

### BE-04Z — Contract-Aware Reward-Family Expansion

Purpose:
- stop classifying real reward claims as generic inbound, LP exit, lending deposit,
  or review on known claim contracts.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- `backend/src/main/resources/protocol-registry.json`

Implementation scope:
- add and/or fix reward-family coverage for known claim contracts and selectors:
  - `0x3ef3d8ba38ebe18db133cec108f4d14ce00dd9ae`
    - `0x71ee95c0`
    - `0x9fb67b58`
  - FLUID `0xbe5013dc`
  - Pendle `0x5eac6239`
- rule must remain raw-only:
  - known contract/selector
  - real inbound economic movement in raw legs
- do not rely on explorer-only labels or summaries

Definition of done:
- claim routes with real inbound reward movement normalize to `REWARD_CLAIM`
- same family no longer leaks into `EXTERNAL_INBOUND`, `LP_EXIT`, or `LENDING_DEPOSIT`

Required fixtures:
- `0x01cac047506298691607efa4bdc158b8b8678ea69855fc2558e8aa18a515ee03`
- `0xf13356fe9449ec9e831395e0074622e88e362a8f317e6b110d093bfaa25d2702`
- `0x56a0cf989540c29873a8df829464dcee6c7d27cf8ae84bc82570ffce4a3ca401`
- one audited FLUID claim hash from `results/`
- one audited Pendle claim hash from `results/`

Required tests:
- known claim contract + inbound movement -> `REWARD_CLAIM`
- `claimWithRecipient(...)` -> `REWARD_CLAIM`
- claim contract does not fall to plain inbound if allowlisted

### BE-04AA — Claim/No-Op Differentiation On Known Claim Contracts

Purpose:
- avoid silently promoting zero-movement claim calls to `REWARD_CLAIM` while also
  avoiding bogus `UNKNOWN` for deterministic non-economic calls.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`

Implementation scope:
- on known claim contracts:
  - if raw legs contain real inbound economic movement -> `REWARD_CLAIM`
  - if no movement exists in raw -> explicit non-economic path or explicit review
- do not invent reward legs from explorer UI text
- do not use clarification to upgrade zero-movement claim calls

Definition of done:
- zero-transfer claim calls are no longer mislabeled as economic reward events
- deterministic review/no-op policy exists for claim calls with no movement

Required fixtures:
- `0xb4e87a83eb3bdbdd797d601ff785cfb5ff767acbf736d61f47852d8a282c2240`
- `0x879f8d4cbe88b90a0a761d90d504b0c13796b440cb1c9cc6a6d48d7d65233596`
- `0x02a1ffe41b2026377618326dbe9cc1e6ad14f65706c8b1cbd3954d8fbae77a97`

Required tests:
- claim call with no movement does not auto-become `REWARD_CLAIM`
- claim call with no movement is deterministic and reviewable

### BE-04AB — Lending Withdraw Semantic Inversion Fix

Purpose:
- correct the current `withdraw(...) -> LENDING_DEPOSIT` inversion.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- registry/runtime metadata where needed

Implementation scope:
- ensure known lending withdraw selectors and/or registry roles classify as
  `LENDING_WITHDRAW`
- receipt-token burn + underlying inbound pattern must reinforce withdraw semantics
- prevent registry static type from forcing wrong direction when selector says withdraw

Definition of done:
- live Avalanche `0x69328dec` family stops producing `LENDING_DEPOSIT`

Required fixtures:
- `0x422aac44d6c416f31957949e2dac80a756545b3b28d9f375444d0ef433b7f907`
- at least one additional Avalanche `0x69328dec` tx
- existing `supply(...)` fixture to prove deposit path is not regressed

Required tests:
- `withdraw(...)` -> `LENDING_WITHDRAW`
- `supply(...)` still -> `LENDING_DEPOSIT`
- receipt-token burn + underlying receive stays continuity-safe

### BE-04AC — CL Position-Manager Multicall Coverage Across Supported Networks

Purpose:
- close the repeated `multicall(bytes[] data)` gap on CL position managers across
  Ethereum, Arbitrum, Base, Unichain, and BSC.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/support/**`
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- registry entries if missing

Implementation scope:
- decode outer `0xac9650d8 multicall(bytes[] data)`
- inspect inner calls and persisted raw evidence to identify:
  - mint/new position
  - increase liquidity
  - decrease/collect exit path
- keep classification source-agnostic:
  - use only persisted raw and view/projection
  - provider-backed real logs are allowed only if already persisted into raw

Definition of done:
- position-manager multicalls no longer default to `ROUTER_METHOD_OVERLOAD_UNSUPPORTED`
- known mint/add-liquidity cases normalize to `LP_ENTRY`

Required fixtures:
- `0x48a9208f705d2b7ab7395fb10b9bca7768019c78796f4822cc4bb14003a652ce`
- `0x3321a28e0e8a2ff77d0d43abf9d0b449ed902c26218e97a7dda81f6465a7ed67`
- `0x51dc36fc93e51dde5fafd1ab92d000d06104394d3179e3e39f0fcaa54cc53231`
- one Base `0x46a15...` multicall fixture from `results/`

Required tests:
- Uniswap/Pancake position-manager `multicall` mint -> `LP_ENTRY`
- add-liquidity multicall -> `LP_ENTRY`
- unsupported inner path still produces explicit review reason

### BE-04AD — `modifyLiquidities` Method-Aware Routing On Unichain And BSC

Purpose:
- close the current concentrated-liquidity router gap on `0xdd46508f`.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/support/**`
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- registry entries for supported position managers

Implementation scope:
- decode `modifyLiquidities(bytes payload,uint256 deadline)`
- derive final LP semantics from persisted raw evidence:
  - add/increase -> `LP_ENTRY`
  - remove/decrease/collect -> `LP_EXIT` or `LP_FEE_CLAIM` where applicable
- support both current audited families:
  - Unichain
  - BSC Pancake Infinity, likely `XYZ/USDT` pool path

Definition of done:
- `modifyLiquidities` no longer lands in `REGISTRY_SPECIAL_HANDLER_REQUIRED`
- BSC and Unichain CL families become explicit LP lifecycle events

Required fixtures:
- `0x0088de663d549fbc58dfa8dbba4180a346a580b1d6277254fa84a8ed9c27967a`
- `0x091e356020745e6555732067a025cee9243dee6848c47dd8eb97259293735e70`
- `0x8cd845033478862d78ae2214fa63e822a9dd217fab4c428801285eb1bb40d2e1`
- `0x84c8a89f9b7578b709015561a23abe76758f1521d44d6819499e226db25e15f2`

Required tests:
- `modifyLiquidities` entry path -> `LP_ENTRY`
- `modifyLiquidities` exit path -> `LP_EXIT`
- fee-claim-only subcase -> `LP_FEE_CLAIM` if raw supports it

### BE-04AE — ScamFilter Precedence Hardening Over Bridge/Reward Allowlists

Purpose:
- keep the useful spam filter while preventing it from overriding known legitimate
  bridge and reward routes.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/filter/**`
- classifier support where precedence is enforced

Implementation scope:
- run verified bridge/reward allowlists before promo/phishing heuristics
- keep composite spam signals for real spam clusters
- ensure provider-backed BSC claim routes survive the filter

Definition of done:
- legit bridge settlement is no longer dropped into promo/phishing review
- legit reward claims are no longer dropped into promo/phishing review
- obvious spam families remain filtered/reviewed

Required fixtures:
- `0xd2cdbd7a1ade37a8032b713e9844351d2f58fbd872851a0e88203b8fbb695c5f`
- `0xf13356fe9449ec9e831395e0074622e88e362a8f317e6b110d093bfaa25d2702`
- `0xa586770c653097bd905f0003edddcc59f295a8a64131a3ba0410d6fe43bb08e5`
- `0xcbee5437edfe64d3abe9f7b6e0b02daf059405d348ae90ee08a81a53b933c0b6`
- `0x23f8ed6d7db1af98174a9934718531d1e24f8c8f0e303882924fbb7b035eaa9e`

Required tests:
- `redeemWithFee(...)` bypasses scam rule
- `claimWithRecipient(...)` bypasses scam rule
- BSC legit claim fixture bypasses scam rule
- known spam fixtures still fail filter/review

### BE-03F — BSC Raw-Completeness Gate Before `COMPLETE`

Purpose:
- prevent the control plane from marking `BSC` complete when persisted raw coverage
  is obviously incomplete for the tracked wallet universe.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/job/backfill/**`
- `backend/src/main/java/com/walletradar/ingestion/adapter/evm/rpc/**`
- sync-status / segment completion logic

Implementation scope:
- add a provider-first completeness sanity gate before `sync_status=COMPLETE`
- if provider returns rows for one wallet but persisted raw remains empty or materially
  below expected coverage, sync must stay incomplete/error
- this is a backfill/control-plane task only; classifier remains source-agnostic

Definition of done:
- `BSC` cannot silently close with all rows on one wallet while two tracked wallets have zero raw
- audit can trust `COMPLETE` as at least minimally sane for BSC

Required fixtures:
- current three BSC tracked wallets from Mongo
- provider-backed coverage expectations from audited BSC run

Required tests:
- zero-raw wallet does not reach `COMPLETE`
- incomplete provider persistence does not reach `COMPLETE`
- healthy provider-first wallet can still reach `COMPLETE`

### BE-04AF — Final Classification Regression Pack + Repeat-Audit Fixtures

Purpose:
- freeze the current repeat-audit findings into tests so the same semantic drift
  cannot silently reappear.

Primary write scope:
- classifier tests
- clarification tests
- scam-filter tests
- any fixture loaders used by those tests

Implementation scope:
- lift the repeat-audit hashes from `data/derived/classification_rule_fixtures.tsv`
- cover at minimum:
  - false clarification collapse
  - selector recovery
  - bridge settlement continuity
  - reward-family expansion
  - claim/no-op differentiation
  - lending withdraw fix
  - BSC `multicall` mint
  - BSC `modifyLiquidities`
  - scam-filter legit-claim/legit-bridge precedence

Definition of done:
- rerunning the full repeat-audit regression suite would catch the currently open
  classification regressions

Required tests:
- one regression test per audited family, not one per network only

### BE-04D — Selector Recovery From Calldata

Purpose:
- Eliminate false `CLASSIFICATION_FAILED` outcomes caused by blank `rawData.methodId`
  when a valid selector is present in `rawData.input`.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/onchain/**`
- classifier tests

Implementation scope:
- in the raw normalization view, resolve method id as:
  - top-level `rawData.methodId`
  - else `rawData.input.substring(0, 10)` when available
  - else `"0x"`
- normalize to lower-case once at the raw-view boundary
- do not duplicate this fallback inside classifier steps

Definition of done:
- selector recovery happens exactly once in the raw-view layer
- blank `methodId` no longer blocks method-id classification when calldata is present
- current classifier ordering remains unchanged

Required fixtures:
- `0x09c4c1ba7c35cd06c3954eb3ad8d6c65cc2d16983cf2f49ed54b961d6aaca31a`
- `0x0b9dffeb5339fded48841c3ba47bef8e7fef7e7698b5885903cdb3df00ee34a0`
- `0x3ce39ffa14aeed0f9fff7f12c95aa33978185deb9c893d570542b09fa9c811ca`
- `0x75afb51a84d2ea637e79657931b8b8dc0b049014f223c465e2a9d231e9732ebf`
- `0xb7a9086def86956c896bb9a53326dacee73be2cf17c5741ea7c4e4e6f21c7afc`

Required tests:
- blank-method-id fallback test
- lower-case normalization test
- regression test proving classifier consumes recovered selector without extra branching

### BE-04E — `ADMIN_CONFIG` Type For Known Non-Basis Calls

Purpose:
- Separate wallet/protocol configuration calls from ERC-20 approvals and from true
  unknown economic events.

Primary write scope:
- `backend/src/main/java/com/walletradar/domain/transaction/normalized/**`
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- docs alignment if enum contracts change

Implementation scope:
- add `ADMIN_CONFIG` canonical type
- use it only for conservative, known non-economic admin/config signatures and
  known contracts
- do not introduce a broad fallback of
  `no transfers + no value + calldata + unknown contract -> ADMIN_CONFIG`
- keep gas as a normal `FEE` flow when present; movement flows stay empty
- initial status:
  - `CONFIRMED` for known `ADMIN_CONFIG`

Known candidates:
- `setRelayerApproval`
- `lockdown`
- `addOwnerWithThreshold`
- `setUserUseReserveAsCollateral`
- wallet `deploy(address referral)`
- Basenames-style resolver multicalls already verified as config/name-management

Definition of done:
- `APPROVE` remains reserved for true allowance semantics
- known admin/config calls stop polluting `UNKNOWN`
- no broad heuristic mislabels router/order/protocol-control calls as config

Required fixtures:
- `0x9d433634d94f01558d2eec9b410edbbcd22aa70e25b09f6f34349077dc9734b9`
- `0x361088362f735362333736f3b5d416a8934da25cf50b8394e58b35d019d0c9b4`
- `0x0b9dffeb5339fded48841c3ba47bef8e7fef7e7698b5885903cdb3df00ee34a0`
- `0x34be246995e967c03b1dd3dcc16b90f42646af5d94b41ddd92e21bf29f6ff2d7`
- `0x2554a78be28c4a7ce07ffbc51372639e0e66dd373e9d19714181c8b3776b4b50`
- `0x09c4c1ba7c35cd06c3954eb3ad8d6c65cc2d16983cf2f49ed54b961d6aaca31a`

Required tests:
- `ADMIN_CONFIG` has no movement legs
- `ADMIN_CONFIG` keeps `FEE` flow when gas exists
- ERC-20 `approve` still stays `APPROVE`, not `ADMIN_CONFIG`
- unknown zero-flow contract call does not auto-collapse into `ADMIN_CONFIG`

### BE-05A — Clarification Eligibility Gate And Status Triage

Purpose:
- Stop using `PENDING_CLARIFICATION` as a generic low-confidence bucket when
  receipt metadata cannot materially improve the row.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- `backend/src/main/java/com/walletradar/ingestion/job/clarification/**`
- `backend/src/main/java/com/walletradar/ingestion/pipeline/onchain/**`

Implementation scope:
- replace blanket `confidence = LOW -> PENDING_CLARIFICATION`
- introduce explicit `receiptClarifiable` gating based on missing receipt-safe fields:
  - `txreceipt_status`
  - gas used / effective gas price
  - created contract address
- `PENDING_CLARIFICATION` only when those fields are missing and clarification
  is supported for the network/provider
- low-confidence but economically coherent rows must start in `PENDING_PRICE`
- unsupported semantic gaps must start in `NEEDS_REVIEW`
- clarification success must not be required just to move already complete
  low-confidence rows into pricing

Definition of done:
- `PENDING_CLARIFICATION` contains only receipt-clarifiable rows
- rows that already have receipt-safe metadata no longer wait in clarification
- clarification is no longer the default sink for wrapped-native, router, or
  reward-vs-inbound ambiguity cases

Required fixtures:
- `0x77049db19258823d40facea05468380cd836611c04fcea659bef78b2aee739a9`
- `0x019c677a366369e95a64c7f3d2bf30b4911550f5b33ccecac07d5ef41000e604`
- `0x9641c86ece5cbb9031052cd57ccc8a408a9c2468436f46210d98a7d57b6a938d`

Required tests:
- low-confidence coherent row starts in `PENDING_PRICE`
- receipt-field gap starts in `PENDING_CLARIFICATION`
- unsupported semantic gap starts in `NEEDS_REVIEW`
- clarification query loads only truly receipt-clarifiable rows

### BE-04J — Wrapped-Native Selector Fast-Paths

Purpose:
- Eliminate the large wrapped-native cluster currently misclassified as
  `VAULT_*` or `EXTERNAL_TRANSFER_OUT`.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- wrapped-native support helpers
- classifier tests

Implementation scope:
- for configured wrapped-native contracts, classify by selector even when
  internal/native traces are missing:
  - `0xd0e30db0` -> `WRAP`
  - `0x2e1a7d4d` -> `UNWRAP`
- execute this fast-path before broad function-name fallback
- keep generic heuristics as fallback for non-wrapper contracts

Definition of done:
- wrapped-native `deposit()` / `withdraw(uint256)` no longer rely on
  receipt/internal-trace enrichment to reach `WRAP` / `UNWRAP`
- generic vault classification does not capture known wrapper contracts

Required fixtures:
- `0x3fbc580e02f08b37946a4d0a7041fa727251d3d3bf6de022d4623e99ebe4c3d5`
- `0x8d39e25b3122a24b8d47a477163c4f7f116c4d5e1e95b8f78e8f075807432e60`
- `0x9641c86ece5cbb9031052cd57ccc8a408a9c2468436f46210d98a7d57b6a938d`

Required tests:
- wrapped-native `deposit()` -> `WRAP`, `CONFIRMED`
- wrapped-native `withdraw(uint256)` -> `UNWRAP`, `CONFIRMED`
- non-wrapper `withdraw()` does not auto-classify as `UNWRAP`

### BE-04K — Bridge/Router De-Overload Before Generic Function-Name Fallback

Purpose:
- Prevent known bridge/router methods from collapsing into broad `VAULT_*`,
  `SWAP`, or `EXTERNAL_*` types just because the decoded name contains
  `deposit`, `withdraw`, `multicall`, or `batch`.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- `backend/src/main/resources/protocol-registry.json`
- special-handler tests where method-aware dispatch is required

Implementation scope:
- add explicit handling before generic function-name mapping for the current
  high-impact selectors and contracts:
  - Across `depositV3` (`0x7b939232`)
  - router `multicall` (`0xac9650d8`)
  - generic `batch` (`0xc16ae7a4`) where registry/method support exists
- method-aware bridge/router dispatch must produce a final type or explicit
  `NEEDS_REVIEW`, never a misleading broad fallback

Definition of done:
- bridge/router methods no longer become generic `VAULT_*` because of name matching
- unsupported router methods fail explicitly instead of hiding inside broad
  low-confidence function-name output

Required fixtures:
- `0xec9b4c23e62b8f38579fd8b4fedd9af13e08c57bcf724e3656c510a55183cc9a`
- `0xf4ecd0e670ba3026e2c099bad5c69c80d328a38fe010f0ee800666e8eec9b497`
- `0x972cd358a18776e69ebb3d566d6e59d70ea6499d03469cde6bc1c2da9fd8a6df`

Required tests:
- `depositV3` bridge path does not classify as `VAULT_DEPOSIT`
- known router `multicall` path does not classify by broad keyword fallback
- unsupported router method becomes explicit `NEEDS_REVIEW`

### BE-04L — Reward-vs-Inbound Defaulting Without Clarification

Purpose:
- Remove the large `AMBIGUOUS_INBOUND_VS_REWARD` backlog from clarification when
  receipt enrichment cannot resolve the ambiguity.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- protocol-registry or config support for known reward distributors
- classifier tests

Implementation scope:
- maintain a deterministic known-reward-distributor set
- inbound-only rows with reward-specific evidence -> `REWARD_CLAIM`
- inbound-only rows without reward-like evidence -> `EXTERNAL_INBOUND`
- inbound-only rows with reward-like signal but without reward-distributor proof
  -> `EXTERNAL_INBOUND` plus `missingDataReasons += AMBIGUOUS_INBOUND_VS_REWARD`
- these rows must go directly to pricing, not clarification

Definition of done:
- plain inbound rows no longer accumulate in `PENDING_CLARIFICATION`
- known reward distributors still classify to `REWARD_CLAIM`
- lack of reward evidence defaults conservatively and deterministically
- reward ambiguity is reserved for reward-like signals, not every plain inbound transfer

Required fixtures:
- `0x77049db19258823d40facea05468380cd836611c04fcea659bef78b2aee739a9`
- `0x019c677a366369e95a64c7f3d2bf30b4911550f5b33ccecac07d5ef41000e604`
- `0x42eb5879046096dc43692bee101ebf0b1ecefc92d44acca0ea07edc8ac717117`

Required tests:
- ambiguous inbound defaults to `EXTERNAL_INBOUND`
- known reward distributor defaults to `REWARD_CLAIM`
- ambiguity metadata does not force `PENDING_CLARIFICATION`

### BE-04F — V3 LP-Position Lifecycle Refinement

Purpose:
- Correctly classify LP position NFT mint/stake/unstake workflows without creating
  false `BUY/SELL` behavior.

Primary write scope:
- position-manager handlers
- special-handler implementations
- classifier tests

Implementation scope:
- `mint` / `increaseLiquidity` on known V3 position systems -> `LP_ENTRY`
- `safeTransferFrom` LP NFT -> `LP_POSITION_STAKE` or `LP_POSITION_UNSTAKE`
  only when calldata decode proves a known farm/strategy counterparty
- LP position NFT movements are continuity-only `TRANSFER`
- do not infer `STAKE` or `UNSTAKE` from selector alone

Definition of done:
- LP position lifecycle is no longer grouped under generic unknown ERC-721 transfer
- custody-style LP NFT movement never becomes `BUY/SELL`
- stake vs unstake is determined from decoded calldata + known counterparties

Required fixtures:
- `0x1f20761ec8224671fb0f3a120a3375f5b6d8923792fdd343619552c7b5706394`
- `0x4771964527379ef96cb2674ef41c7e6a76308be167ab28546bfcf542b5471f0d`
- `0x3ce39ffa14aeed0f9fff7f12c95aa33978185deb9c893d570542b09fa9c811ca`
- `0x67f4e9e1767850c427920a1238903ed6fc56e6cadd4d3defcacc7a99e1329499`

Required tests:
- position-NFT stake test
- position-NFT unstake test
- `increaseLiquidity` -> `LP_ENTRY` test
- new-position mint -> `LP_ENTRY` test

### BE-04G — zkSync Router Coverage Via Registry + Method-Aware Dispatch

Purpose:
- Classify real routed economic activity on zkSync without introducing network-specific
  hacks or overly broad static address-to-type mapping.

Primary write scope:
- `backend/src/main/resources/protocol-registry.json`
- registry loader tests
- router classifier/handler code

Implementation scope:
- add verified zkSync protocol addresses to registry
- use registry as the entry point
- single-purpose bridge contracts may map directly to final type
- multi-function routers must still dispatch by selector/legs through a handler or
  equivalent method-aware path
- do not encode `Universal Router = SWAP always`

Definition of done:
- verified zkSync bridge/router addresses are covered in registry
- real bridge/swap paths classify deterministically
- multi-function routers do not collapse to one static event type

Required fixtures:
- `0x75afb51a84d2ea637e79657931b8b8dc0b049014f223c465e2a9d231e9732ebf`
- `0xb7a9086def86956c896bb9a53326dacee73be2cf17c5741ea7c4e4e6f21c7afc`

Required tests:
- verified LiFi/Jumper bridge path test
- verified zkSync universal-router path test
- registry coverage test for new zkSync entries
- regression test proving multi-function router still dispatches by selector/legs

### BE-03B — Raw Ordering Metadata Repair For Missing `transactionIndex`

Purpose:
- Repair deterministic sort prerequisites before classification, instead of guessing
  intra-block order.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/onchain/**`
- new repair job/service under ingestion jobs
- raw transaction repository update path

Implementation scope:
- add a bounded metadata-repair stage for raw tx missing `transactionIndex`
- enrichment source:
  - `eth_getTransactionByHash(txHash)` or equivalent explorer tx-detail endpoint
- update only controlled metadata fields on the existing raw document
- if metadata cannot be repaired:
  - surface explicit blocker reason such as `MISSING_TRANSACTION_INDEX`
  - do not guess `0`, `MAX_VALUE`, or first-trace ordering
- keep this stage before canonical normalization ordering, not as post-classification clarification

Definition of done:
- missing `transactionIndex` does not force heuristic ordering guesses
- repaired raw docs can re-enter normal normalization
- irreparable cases remain explicit and bounded

Required fixtures:
- `0x4a47ab3cad76be52416e660e044b983acc9837cd9f05b59eabad7560636aa0b2`
- `0x17273e5ce3ae2392ed47d7c306cd15fae4f09f69c580829f735f35bc8707dae7`
- `0x05ebcae5434e10f95b183c66245d45111e549c677a45e5c28ecabeeed0086762`
- `0xd54c44df3acd95e8eda1184eee810a03cabd6f3e7b52cfdf210e82fae45e8d2c`
- `0xae668362246147af3787428fa8de31f7af26127cb9b676603c91d8257c6edc44`

Required tests:
- successful repair test from RPC/explorer metadata
- re-entry test from repaired raw to normalizer
- bounded-failure test for unrecoverable tx
- regression test proving no default `transactionIndex=0` guess is applied

### BE-04H — Plasma Spam And No-Op Handling

Purpose:
- Separate explicit spam/promo dust from harmless zero-movement admin actions on Plasma.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/filter/**`
- classifier tests for persisted legacy cases

Implementation scope:
- ingress path:
  - expand `ScamFilter` using a composite signal
  - do not rely on URL/TLD regex alone
- composite spam signal should combine:
  - promo/URL text
  - no meaningful economic movement
  - known spam fingerprint, bounce pattern, or deployer evidence
- persisted legacy raw that already bypassed spam filtering must still classify deterministically:
  - spam legacy cases -> explicit reviewable spam path or targeted cleanup/rebackfill
  - wallet `deploy(...)` / no-op setup -> `ADMIN_CONFIG`

Definition of done:
- future spam tx are filtered before persistence
- already persisted legacy spam does not leak into basis
- no-op wallet setup is not conflated with spam

Required fixtures:
- `0x10473490d38c970e08f8668bc7eca1c6226062fdb256df02158aa35d024f59f5`
- `0x2554a78be28c4a7ce07ffbc51372639e0e66dd373e9d19714181c8b3776b4b50`

Required tests:
- composite Plasma spam detection test
- anti-false-positive test for legitimate URL-like symbol edge case
- wallet deploy -> `ADMIN_CONFIG` test

### BE-04M — Promo/Phishing Inbound Exclusion From Reward Ambiguity

Purpose:
- Remove fake reward / fake airdrop inbound rows from the normal accounting path
  instead of letting them sit inside `AMBIGUOUS_INBOUND_VS_REWARD`.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/filter/**`
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- classifier and scam-filter tests

Implementation scope:
- detect promo/phishing markers in token metadata and decoded function labels:
  - `claim`, `airdrop`, `redeem`, `visit`, URL fragments, fake-store domains
- combine them with no meaningful economic counterflow
- ingress-time future rows:
  - drop through `ScamFilter` when the signal is strong enough
- already persisted legacy rows:
  - route to explicit reviewable spam/phishing handling
  - do not leave them in `AMBIGUOUS_INBOUND_VS_REWARD`

Definition of done:
- phishing inbound no longer normalizes as ordinary `EXTERNAL_INBOUND`
- reward ambiguity backlog excludes obvious promo/phishing rows
- legacy persisted spam does not leak into pricing or replay

Required fixtures:
- `0x09768aa81f61758bf98c02b846a658439c212db401cb45c835d79f87a6ebcac1`
- `0x23f8ed6d7db1af98174a9934718531d1e24f8c8f0e303882924fbb7b035eaa9e`
- `0xcbee5437edfe64d3abe9f7b6e0b02daf059405d348ae90ee08a81a53b933c0b6`

Required tests:
- phishing inbound is not classified as `REWARD_CLAIM`
- phishing inbound is not left in reward ambiguity
- legitimate reward claim with non-phishing metadata still survives

### BE-04N — Bridge-Settlement Continuity Selectors

Purpose:
- Reclassify destination-side bridge settlement tx away from misleading `REPAY`,
  `LENDING_WITHDRAW`, or broad vault fallback.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- `backend/src/main/resources/protocol-registry.json`
- bridge/relay classifier tests

Implementation scope:
- add method-aware bridge settlement handling for known selectors on verified
  bridge contracts:
  - `0x2e378115` `fillV3Relay`
  - `0xdeff4b24` `fillRelay`
  - `0xe2de2a03` `redeemWithFee`
  - `0xcfc32570` `execute302`
  - `0x6befa3a5` `directFulfill`
- destination-side settlement on recognized bridge paths -> `BRIDGE_IN`
- do not allow these selectors to collapse into `REPAY`, `LENDING_WITHDRAW`,
  `VAULT_WITHDRAW`, or generic inbound fallback

Definition of done:
- bridge settlement rows classify as bridge continuity events
- `fillRelay` / `fillV3Relay` no longer appear as `REPAY`
- bridge-settlement coverage is deterministic and contract-scoped

Required fixtures:
- `0x27978f7bf88cd7a4825b991ac6e461fa96be75b280add44236beb2e060c61ba3`
- `0x565706c06b757b6cbf064cf36c884afcb78d314637ff906d11a6a09366cb2b07`
- `0xec9b4c23e62b8f38579fd8b4fedd9af13e08c57bcf724e3656c510a55183cc9a`

Required tests:
- `fillV3Relay` -> `BRIDGE_IN`
- `fillRelay` -> `BRIDGE_IN`
- bridge settlement selector does not downgrade into `REPAY`

### BE-04O — Zero-Amount Token Transfer And No-Op Routing

Purpose:
- Prevent zero-quantity token transfer artifacts from creating fake economic
  movement or lingering as unexplained `UNKNOWN` rows.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- admin/no-op support helpers
- classifier tests

Implementation scope:
- detect `tokenTransfers` where every outbound token amount is `0`
- require no inbound token leg, no native counterflow, and no internal movement
- contract-scoped outcome only:
  - known setup/admin contract -> `ADMIN_CONFIG`
  - otherwise explicit review reason such as `ZERO_AMOUNT_TOKEN_TRANSFER`
- never create `BUY` / `SELL` from zero-amount token movements

Definition of done:
- zero-amount token transfers do not create basis-relevant movement
- known setup/admin no-op paths stop polluting `UNKNOWN`
- unknown zero-amount token paths stay explicit and reviewable

Required fixtures:
- `0xd66783b739785740fcb8af737dc5f06a02df684adf72fadb4cc4eca07f78959b`
- `0xf64be003679e600a679b42c56add6194ea931ca1054f2b659e0c0789a6852a89`

Required tests:
- zero-amount token transfer never creates `BUY`
- contract-scoped known no-op -> `ADMIN_CONFIG`
- unknown zero-amount token transfer -> explicit review reason

### BE-04P — Position-Manager Multicall LP-Entry Coverage

Purpose:
- Classify known V3 position-manager multicalls as `LP_ENTRY` when the inner
  calldata and raw legs prove liquidity add / position mint semantics.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- special-handler implementations
- LP classifier tests

Implementation scope:
- for known V3 position-manager contracts, decode supported `multicall`
  inner calls and detect:
  - add liquidity
  - mint new position NFT
- outer selector `0xac9650d8` must not force `UNKNOWN`
- resulting NFT position remains continuity-only `TRANSFER`

Definition of done:
- known position-manager multicalls no longer become router `UNKNOWN`
- multicall liquidity add + NFT mint normalizes to `LP_ENTRY`
- unsupported multicall inner paths still fail explicitly

Required fixtures:
- `0x48a9208f705d2b7ab7395fb10b9bca7768019c78796f4822cc4bb14003a652ce`
- `0x9fc947799f66aac8a55b669011afbbbe47e7c6786aceaf0df1cb25e588fb8823`

Required tests:
- Uniswap V3 position-manager multicall -> `LP_ENTRY`
- Arbitrum position-manager multicall -> `LP_ENTRY`
- unsupported multicall inner call -> explicit review

### BE-04Q — Plain Inbound Transfer Narrowing + Reward-Distributor Allowlist

Purpose:
- Stop plain positive inbound transfers from lingering in reward ambiguity when
  there is no contract-aware reward evidence.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- reward/inbound support helpers
- classifier tests

Implementation scope:
- keep plain inbound ERC-20 transfer-like rows on the default `EXTERNAL_INBOUND` path
- reserve `REWARD_CLAIM` for:
  - known reward distributors
  - known claim selectors on known claim contracts
  - equivalent contract-scoped evidence
- promo/phishing signals must preempt both reward classification and ordinary inbound defaulting
- explorer human-readable summary text must not override raw-leg semantics

Definition of done:
- plain inbound transfer rows no longer wait in reward ambiguity without evidence
- known legitimate reward distributors still produce `REWARD_CLAIM`
- swap-like raw legs are not downgraded just because an explorer page labels the tx as `Transfer`

Required fixtures:
- `0x062cd8bea820f4ea798b082d42748b27580a4c99b96b3e297875ea527e5c6669`
- `0x6244e72e0652dfc932579cb11a031e7a8bd257ff0c206311faf3b371adc13ef5`
- `0x7b42c8bf8d8809acc383d20ca40a2be494c1662c1ea7bfaa129d5cdf4b5ebc6a`
- `0x09768aa81f61758bf98c02b846a658439c212db401cb45c835d79f87a6ebcac1`

Required tests:
- plain inbound transfer -> `EXTERNAL_INBOUND`
- known reward distributor claim -> `REWARD_CLAIM`
- phishing inbound does not become `REWARD_CLAIM`
- raw swap-like legs outrank explorer "transfer" wording

### BE-04R — Across Bridge-Entry Coverage (`depositV3`)

Purpose:
- Ensure recognized bridge-entry calls classify to `BRIDGE_OUT` before generic
  deposit or vault fallback.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- `backend/src/main/resources/protocol-registry.json`
- bridge classifier tests

Implementation scope:
- add explicit contract-scoped handling for `0x7b939232` `depositV3`
- use verified Across/bridge entry contracts only
- keep destination-side settlement behavior from `BE-04N` aligned with the same
  continuity model
- do not allow `depositV3` to collapse into `VAULT_DEPOSIT`, `LENDING_DEPOSIT`,
  or generic `EXTERNAL_TRANSFER_OUT`

Definition of done:
- recognized Across bridge-entry rows classify as `BRIDGE_OUT`
- entry and settlement sides use one coherent bridge continuity rule set
- non-Across `depositV3`-like selectors still require contract evidence

Required fixtures:
- `0x77049db19258823d40facea05468380cd836611c04fcea659bef78b2aee739a9`
- `0x019c677a366369e95a64c7f3d2bf30b4911550f5b33ccecac07d5ef41000e604`

Required tests:
- verified `depositV3` -> `BRIDGE_OUT`
- `depositV3` without recognized bridge contract does not auto-classify
- bridge entry path stays out of generic vault fallback

### BE-04S — Morpho Bundler3 Method-Aware Routing

Purpose:
- Classify Morpho Bundler3 and similar method-aware protocol bundles without
  falling through to generic `multicall` or bundle heuristics.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- `backend/src/main/resources/protocol-registry.json`
- special-handler or method-dispatch tests

Implementation scope:
- add registry coverage for Morpho Bundler3 contracts present in the current raw set
- implement deterministic method-aware routing for observed Bundler3 paths
- if a Bundler3 method path is not supported, fail explicitly with review reason;
  do not guess from keywords

Definition of done:
- Bundler3 tx no longer collapse into generic router/bundle buckets
- supported Bundler3 method paths classify deterministically
- unsupported Bundler3 methods remain explicit review items

Required fixtures:
- `0xbf7f2915b4846389f3a798527b45681db72c6c388cd12d320c99539af0d6f06a`

Required tests:
- supported Bundler3 path classifies deterministically
- unsupported Bundler3 path -> explicit review
- Bundler3 does not auto-classify from generic `multicall` keyword

### BE-04T — CL Position-Manager `modifyLiquidities` Coverage

Purpose:
- Handle concentrated-liquidity position-manager action containers that are not
  simple V3 `multicall` but still encode LP lifecycle semantics.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- position-manager support helpers
- classifier tests

Implementation scope:
- add contract-aware handling for observed `modifyLiquidities` position-manager paths
- decode supported action sets / legs sufficiently to distinguish:
  - LP entry / increase
  - fee claim only
  - exit / decrease
- keep unsupported action combinations explicit and reviewable

Definition of done:
- known `modifyLiquidities` rows no longer collapse into generic `UNKNOWN`
- LP lifecycle semantics come from decoded actions / raw legs, not broad keywords
- unsupported action sets fail explicitly

Required fixtures:
- `0x8d0a977a8da2bfda43a4f6ca2edfbf1a83658f899c3ef6132086371c9f647a57`

Required tests:
- supported `modifyLiquidities` LP-entry path
- supported fee-claim or decrease path
- unsupported action set -> explicit review

### BE-04U — ScamFilter Precision Hardening + Legitimate-Claim Regression

Purpose:
- Strengthen promo/phishing suppression without introducing false positives on
  legitimate claim distributors already present in the raw set.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/filter/**`
- shared scam/promo text helpers
- scam-filter tests

Implementation scope:
- keep scam signals composite:
  - promo/URL/token-text markers
  - suspicious function labels
  - spam-cluster fingerprints where available
- do not treat any generic `claim` / `reward` wording as sufficient by itself
- add regression fixtures for legitimate claim distributors verified in the raw set

Definition of done:
- current promo/phishing clusters are dropped before raw persistence
- current legitimate reward-claim fixtures survive ScamFilter
- filter behavior is deterministic across supported explorer payload variants

Required fixtures:
- `0x09768aa81f61758bf98c02b846a658439c212db401cb45c835d79f87a6ebcac1`
- `0x23f8ed6d7db1af98174a9934718531d1e24f8c8f0e303882924fbb7b035eaa9e`
- `0xcbee5437edfe64d3abe9f7b6e0b02daf059405d348ae90ee08a81a53b933c0b6`
- `0xd2cdbd7a1ade37a8032b713e9844351d2f58fbd872851a0e88203b8fbb695c5f`
- `0xbf62327840d7624bc1ae12b9213cf66593b671d238da8e9feea57d1b3833ed38`
- `0x16a78ce7e8964eb95ef52d24ea618846e478a777b6df9d521884a30dc0b6ec1a`
- `0x062cd8bea820f4ea798b082d42748b27580a4c99b96b3e297875ea527e5c6669`
- `0x6244e72e0652dfc932579cb11a031e7a8bd257ff0c206311faf3b371adc13ef5`
- `0xf13356fe9449ec9e831395e0074622e88e362a8f317e6b110d093bfaa25d2702`
- `0xa586770c653097bd905f0003edddcc59f295a8a64131a3ba0410d6fe43bb08e5`
- `0x8d391cbda5bb54a3b1e5b01fa9fe971b439c0f21621ba205f4f441715cce7110`
- `0x1cca2a8af6f7bef5cef37c5dd636b3c64a37bb9ba0a6ad27b2afaa4e2c114a8f`

Required tests:
- Polygon fake airdrop is dropped
- Optimism fake reward is dropped
- Plasma spam cluster is dropped
- Base `redeemWithFee(...)` bridge redemption survives
- Avalanche `redeemWithFee(...)` bridge redemption survives
- Unichain `redeemWithFee(...)` bridge redemption survives
- Angle/Morpho legitimate claim survives
- COMP legitimate claim survives
- Arbitrum `claimWithRecipient(...)` survives
- BSC legitimate claim-by-proof survives
- BSC stream claim survives

### BE-03D — Native/Provider RPC Split + Composite Provider Orchestration

Purpose:
- Create a clean ingestion boundary where source-aware acquisition can evolve
  without leaking provider/network logic into normalization or classification.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/adapter/evm/rpc/**`
- adapter wiring/config
- adapter tests

Implementation scope:
- split EVM acquisition into explicit packages:
  - `rpc/native`
  - `rpc/provider`
  - composite/orchestration layer
- introduce a composite provider such as `RpcAdvancedEvmNetworkProvider`
  for provider-first + native-fallback orchestration
- keep classification/normalization out of this layer
- preserve current non-BSC acquisition paths unless explicitly migrated later

Definition of done:
- provider/native responsibilities are isolated
- classifier/normalizer remain source-agnostic
- current non-BSC flows keep working unchanged

Required tests:
- wiring test proving `BSC` can select provider-first acquisition
- wiring test proving non-BSC networks still use current acquisition path
- orchestration-order regression test for provider-first then native-fallback

### BE-03C — BSC Provider-First Raw Acquisition + Payload-Parity Hardening

Purpose:
- Replace the current BSC native-only discovery gap with provider-first wallet
  transaction acquisition that produces normalization-grade raw rows.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/adapter/**`
- provider client and raw-mapping layer
- raw-ingestion tests

Implementation scope:
- use Ankr advanced/provider API as the first acquisition path for `BSC`
- persist canonical raw fields needed by normalization whenever provider returns
  them:
  - `txHash`
  - `blockNumber`
  - `blockTimestamp`
  - `transactionIndex`
  - `methodId`
  - `input`
  - `txreceipt_status`
  - `gasUsed`
  - real tx `logs`
  - `tokenTransfers`
  - `internalTransfers`
- map provider payload into the same canonical raw shape used by normalization
- keep explicit blocker/reporting when provider payload is incomplete
- do not leak provider response objects past raw persistence

Definition of done:
- newly fetched BSC raw rows contain normalization-critical fields at parity with
  other supported EVM networks, or emit explicit ingest blockers
- audit no longer has to treat BSC as a special incompleteness bucket

Required fixtures:
- [2026-03-21T16_38_28_450Z-debug-0.log](/Users/vladislavkondratenko/projects/wallet-radar/2026-03-21T16_38_28_450Z-debug-0.log)
- current BSC raw sample from Mongo after next provider-backed sync

Required tests:
- provider payload maps selector + input + tx metadata into canonical raw
- provider payload maps real logs into canonical raw evidence
- provider payload maps or derives transfer arrays when available
- incomplete provider payload emits explicit ingest blocker instead of silent partial raw

### BE-03E — Native RPC Repair Fallback For Provider Gaps

Purpose:
- Keep BSC provider-first acquisition cheap while still repairing missing
  normalization-critical fields deterministically.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/adapter/evm/rpc/native/**`
- repair/orchestration layer
- adapter tests

Implementation scope:
- use native RPC only for targeted repair when provider-backed raw is missing:
  - `transactionIndex`
  - `blockTimestamp`
  - receipt/status fields
  - other normalization-critical tx metadata explicitly whitelisted
- avoid reverting to full native block-scan backfill for `BSC`
- keep repair bounded, idempotent, and `txHash`-scoped

Definition of done:
- missing provider fields no longer force full native re-fetch
- repair path is bounded and deterministic
- provider-first cost benefit is preserved

Required fixtures:
- BSC provider rows missing one or more tx-level fields

Required tests:
- missing `transactionIndex` repaired by native fallback
- provider-complete row bypasses native repair
- repair failure emits explicit ingest blocker without silent downgrade

### BE-04V — Raw-View Support For Provider-Backed Canonical Evidence

Purpose:
- Make richer provider-backed raw evidence usable by classifier without making
  classifier source-aware or `BSC`-aware.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/onchain/**`
- normalization view/projection helpers
- classifier tests

Implementation scope:
- expose persisted canonical raw evidence through the existing normalization
  view/projection:
  - method id
  - input
  - real persisted logs
  - transfer arrays
  - tx metadata
- keep the rule:
  - synthetic/invented logs are ignored
  - real backfill logs persisted in raw may be consumed
- classifier must not branch on source or network to use these fields

Definition of done:
- classifier reads one raw-view contract for all sources
- provider-backed rows become classifiable without source-specific branches
- synthetic logs remain forbidden

Required fixtures:
- BSC provider-backed raw samples from the debug log

Required tests:
- provider-backed raw logs are visible through the view
- synthetic-log fixture remains ignored
- classifier path does not inspect source/provider flags

### BE-04W — Generic Classification Coverage For Audited BSC Families

Purpose:
- Promote the latest BSC audit findings into generic raw-based classification
  rules, not `BSC`-aware classifier branches.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- `backend/src/main/resources/protocol-registry.json`
- classifier tests

Implementation scope:
- add generic contract/method-aware coverage using only raw-view evidence:
  - known 1inch `swap(...)` router path -> `SWAP`
  - known claim families with inbound transfer + claim event -> `REWARD_CLAIM`
  - known Mayan/LiFi bridge-entry path -> `BRIDGE_OUT`
  - known `SmartChefInitializable deposit(uint256)` path -> `STAKING_DEPOSIT`
  - known `CLPositionManager multicall/modifyLiquidities` paths -> `LP_ENTRY` /
    `LP_EXIT` when persisted logs and raw legs prove direction
- registry holds network-scoped contracts; classifier logic stays generic
- unsupported audited families remain explicit review items

Definition of done:
- audited BSC families no longer rely on ad-hoc review when their raw evidence
  is present
- classifier stays raw-based and source-agnostic
- unsupported audited paths remain deterministic and explicit

Required fixtures:
- `0x149342ddd1d445297b57b89b8e44e6bef79263e668f91c48f6109df61baddd50`
- `0x6837626582f572bf7dcdc92404ccebcd1c6c2c3f4a1175fbdc6452f54208e341`
- `0x85c08d7cec5db1f282f37560dfdca602555a79dc29c806730d63cf599ff44c44`
- `0xa586770c653097bd905f0003edddcc59f295a8a64131a3ba0410d6fe43bb08e5`
- `0x8d391cbda5bb54a3b1e5b01fa9fe971b439c0f21621ba205f4f441715cce7110`
- `0x1cca2a8af6f7bef5cef37c5dd636b3c64a37bb9ba0a6ad27b2afaa4e2c114a8f`
- `0x2de9c04e35a6a1942db36af5afab317b1aa6ff08de4ce1c4622a7f23feedd698`
- `0xac23f81f7c6b0b774201c1c1417d52cb6497947af864549fcdbe470e819eaf7f`
- `0x091e356020745e6555732067a025cee9243dee6848c47dd8eb97259293735e70`
- `0x8cd845033478862d78ae2214fa63e822a9dd217fab4c428801285eb1bb40d2e1`

Required tests:
- native inbound remains `EXTERNAL_INBOUND`
- 1inch swap path -> `SWAP`
- bridge-entry path -> `BRIDGE_OUT`
- audited claim families -> `REWARD_CLAIM`
- SmartChef `deposit(uint256)` -> `STAKING_DEPOSIT`
- CL position-manager entry/decrease path classify from raw evidence

### BE-04I — Terminal Failed-Tx Regression Coverage

Purpose:
- Freeze the accepted terminal behavior for reverted/failed transactions.

Primary write scope:
- classifier tests only unless defects are found

Implementation scope:
- keep failed execution rule as:
  - `rawData.isError == "1"` or `rawData.txreceipt_status == "0"`
  - -> `UNKNOWN`, `NEEDS_REVIEW`, `FAILED_TRANSACTION`
- no pricing, no clarification retries, no economic classification attempts

Definition of done:
- reverted tx never downgrade into generic `CLASSIFICATION_FAILED`
- both explorer-style failure fields remain covered

Required tests:
- `isError=1` failed-tx test
- `txreceipt_status=0` failed-tx test
- regression test proving failed tx bypasses downstream classification logic

## Work Packages

### BE-01 — Canonical Schema + Repositories

Purpose:
- Create or restore the backend canonical document model required by v3.

Primary write scope:
- `backend/src/main/java/com/walletradar/domain/**`
- `backend/src/main/java/com/walletradar/config/**`
- repository interfaces and Mongo mapping classes

Implementation scope:
- `NormalizedTransaction` document and nested `Flow`
- enums for source, type, status, price source, classification source, confidence
- `TrackedWallet` document and repository
- repository/index definitions for:
  - `normalized_transactions`
  - `tracked_wallets`
  - any supporting replay/projection collections kept in scope
- converters for deterministic numeric persistence where needed

Definition of done:
- Canonical schema matches docs for `source`, `transactionIndex`, `correlationId`, `flows[]`, and status pipeline.
- Mongo indexes required by `docs/architecture-v3.md` are declared or created by code.
- Existing code compiles against the new domain model.

Required tests:
- Mongo mapping/integration test for `NormalizedTransaction`
- repository uniqueness test for `(txHash, networkId, walletAddress)`
- repository/index test for `correlationId` and replay query paths

### BE-02 — Tracked-Wallet Projection

Purpose:
- Make internal-transfer detection installation-wide and session-independent.

Primary write scope:
- `backend/src/main/java/com/walletradar/domain/session/**`
- `backend/src/main/java/com/walletradar/ingestion/wallet/**`
- new/updated `tracked_wallets` projection service

Implementation scope:
- derive `tracked_wallets` from persisted session/wallet tracking state
- increment/decrement `refCount`
- keep projection updates idempotent
- expose a read API/service for normalization lookup
- add hook point for targeted re-normalization when wallet universe changes

Definition of done:
- normalization can query one installation-wide wallet universe
- no normalization path depends on per-request or per-session wallet payloads
- repeated session saves do not duplicate tracked-wallet rows

Required tests:
- projection lifecycle test for add/update/remove wallet references
- session save idempotency test
- internal-transfer lookup test using the projection

### BE-03 — On-Chain Normalization Foundation

Purpose:
- Build the deterministic raw-to-canonical processing skeleton before classifier details.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/**`
- `backend/src/main/java/com/walletradar/ingestion/job/**`
- `backend/src/main/java/com/walletradar/domain/transaction/raw/**`

Implementation scope:
- raw polling/query strategy by `normalizationStatus=PENDING`
- deterministic raw ordering by:
  - `rawData.timeStamp`
  - `rawData.transactionIndex`
  - `txHash`
- raw payload access layer/view object
- canonical builder shell
- idempotent normalized upsert path
- raw status transition `PENDING -> COMPLETE`

Definition of done:
- a raw transaction can move through the normalization skeleton without classifier-specific business logic
- raw polling and writes are deterministic and retry-safe
- canonical `_id` and uniqueness semantics are enforced

Required tests:
- deterministic raw ordering test
- idempotent reprocessing test
- raw status transition test

### BE-04 — On-Chain Classification + Leg Extraction

Purpose:
- Implement the approved on-chain classification and flow extraction contract.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- `backend/src/main/java/com/walletradar/ingestion/adapter/evm/**`
- `backend/src/main/java/com/walletradar/ingestion/filter/**`
- `backend/src/main/resources/protocol-registry.json`

Implementation scope:
- step order:
  - protocol registry
  - method id
  - function name
  - heuristic fallback
- protocol-registry authoritative source:
  - `backend/src/main/resources/protocol-registry.json`
- runtime loader behavior:
  - ignore `event_topics`
  - skip decorative section keys and metadata keys
  - validate enums/shape strictly, including `PERP`
  - warn on known coverage gaps such as `UNICHAIN` with zero contract entries
- explicit `specialHandler` dispatch for multi-function contracts
- handler contract:
  - input: `ProtocolRegistryEntry`, raw normalization view, extracted raw legs
  - output: exactly one canonical result per raw tx
  - unsupported method -> `UNKNOWN`, `NEEDS_REVIEW`, `HANDLER_UNSUPPORTED_METHOD`
- no synthetic `rawData.logs[]` as evidence
- flow extraction from:
  - token transfers
  - internal transfers
  - direct native value
- gas `FEE` leg extraction
- native symbol coverage including `UNICHAIN`, `KATANA`, and `PLASMA`
- canonical roles/types for:
  - swaps
  - lending and vault continuity
  - wraps/unwraps
  - bridge continuity
  - protocol custody
  - reward claims
  - internal/external transfers
  - approve/no-op cases

Definition of done:
- classification follows the strict order from `docs/normalization-architecture-en.md`
- protocol-registry data comes from one authoritative classpath source only
- `event_topics` do not enter runtime classification
- special handlers preserve the accepted `1 tx = 1 doc` invariant
- continuity events are `TRANSFER`, not synthetic `BUY/SELL`
- gas is emitted as separate `FEE`
- failed txs go to `NEEDS_REVIEW`

Required tests:
- protocol-registry precedence test
- registry-loader validation test
- registry-loader ignores `event_topics` test
- special-handler unsupported-method test
- synthetic-log exclusion test
- gas-leg extraction test
- lending/vault continuity role tests
- internal-transfer projection-based test
- `PLASMA` native symbol/gas-leg test
- Balancer special-handler test
- GMX V2 special-handler test
- Pendle special-handler test

### BE-05 — Clarification

Purpose:
- Implement bounded receipt enrichment without changing evidence rules.

Primary write scope:
- clarification job/service classes
- explorer enrichment adapters

Implementation scope:
- enrichment only for:
  - execution status
  - gas fields
  - created contract address
- do not use clarification as the default destination for every low-confidence row
- clarification must consume only rows that were marked receipt-clarifiable by
  normalization status triage
- bounded retry policy and attempt counters
- state transition from `PENDING_CLARIFICATION`

Definition of done:
- clarification cannot promote a record using synthetic logs
- clarification is optional, bounded, and deterministic
- clarification does not become a generic classifier backlog
- failures surface as `NEEDS_REVIEW`, not silent fallback

Required tests:
- clarification enrichment test for gas/status
- clarification-eligibility gating test
- attempt-limit test
- regression test proving synthetic logs cannot change classification

### BE-06 — Pricing

Purpose:
- Implement the approved pricing pipeline, including event-local pricing,
  external market-data fallback, unresolved-price behavior, and source-aware
  handling for Bybit canonical rows.

Primary write scope:
- `backend/src/main/java/com/walletradar/pricing/**` or restored equivalent module boundary
- pricing job and resolver chain
- Mongo persistence for historical price cache

Implementation scope:
- resolver order:
  - stablecoin parity
  - exact execution price from canonical source evidence
  - swap-derived
  - wrapper/native
  - Binance historical
  - CoinGecko historical
  - `PRICE_UNKNOWN`
- transaction-type contract:
  - `SWAP` prices from canonical wallet-boundary execution ratio before any
    external candle source
  - `LP_ENTRY`, `LP_EXIT`, matched bridge continuity, protocol custody,
    lending continuity, vault continuity, and staking continuity do not require
    principal market pricing for basis carry-over
  - `EXTERNAL_INBOUND`, `REWARD_CLAIM`, and `LP_FEE_CLAIM` acquire at
    receive-time fair market value
  - `EXTERNAL_TRANSFER_OUT` disposes at event-time fair market value unless an
    explicit continuity or pending-request semantic already won
  - matched Bybit deposit/withdraw plus on-chain leg prices fees only and may
    not price the principal twice
- external source strategy:
  - primary external source is Binance market data for listed assets
  - CoinGecko is bounded fallback only
  - long-tail DeFi assets must prefer tx-local swap-derived pricing before
    generic external market lookup
- price cache:
  - persist deterministic historical price buckets by asset + time + source
  - avoid repeated Binance / CoinGecko calls during reruns and replay
- `WRAPPER` price source support
- `EXECUTION` and `BINANCE` price source support
- unresolved-price handling:
  - quantity remains valid for replay
  - price fields remain null
  - `missingDataReasons += PRICE_UNRESOLVABLE`
  - status proceeds without discarding quantity

Definition of done:
- pricing does not block quantity continuity
- event-local evidence wins over external market data whenever the canonical row
  already proves execution price or exact swap ratio
- wrapper/native mapping works without unnecessary CoinGecko lookups
- Binance lookup is used before CoinGecko for listed assets
- Bybit trades reuse ledger execution price without unnecessary external lookup
- historical price cache deduplicates repeated rerun lookups deterministically
- non-FEE flow pricing behavior matches the docs

Required tests:
- stablecoin resolver test
- exact execution resolver test
- swap-derived resolver test
- wrapper/native resolver test
- Binance historical resolver test
- Binance-miss -> CoinGecko fallback test
- LP continuity row does not require principal market price test
- matched Bybit deposit/withdraw avoids duplicated principal pricing test
- `PRICE_UNKNOWN` non-blocking test
- pricing-job status transition test

Immediate executor split for `BE-06`:

1. `BE-06A` Pricing source contract + historical price cache
2. `BE-06B` Event-local resolvers for stablecoin, execution, swap-derived, and wrapper pricing
3. `BE-06C` Binance historical market-data adapter + deterministic symbol mapping
4. `BE-06D` CoinGecko bounded fallback + unresolved-price behavior
5. `BE-06E` Bybit pricing integration and continuity-safe pricing rules
6. `BE-06F` Pricing rerun pack + AVCO-start data gate

#### BE-06A — Pricing Source Contract + Historical Price Cache

Purpose:
- Introduce the canonical pricing inputs/outputs before external adapters are added.

Primary write scope:
- pricing domain model
- `historical_prices` persistence contract
- pricing repository/index wiring

Implementation scope:
- add `EXECUTION`, `BINANCE`, `COINGECKO`, `UNKNOWN` source semantics to the
  pricing layer
- key cache rows by deterministic asset identity + time bucket + source
- support rerun-safe upsert behavior

Definition of done:
- pricing stage reads/writes one canonical price-cache shape
- reruns do not duplicate cache rows or change meaning

Required tests:
- cache upsert/idempotency test
- price-source enum/serialization test

#### BE-06B — Event-Local Resolvers For Stablecoin, Execution, Swap-Derived, And Wrapper Pricing

Purpose:
- Price rows from canonical tx evidence before any external market lookup.

Primary write scope:
- event-local resolver chain
- normalized flow pricing mapper

Implementation scope:
- stablecoin parity
- exact execution price from canonical source evidence
- swap-derived ratio from canonical wallet-boundary legs
- wrapper/native alias pricing
- explicit continuity handling for LP/bridge/lending/vault/custody rows

Definition of done:
- swaps use tx-local execution ratio first
- continuity rows do not require synthetic principal market price
- reward/fee side-flows are still priceable when needed

Required tests:
- stablecoin swap anchor test
- two-leg swap with one externally priced anchor test
- LP continuity principal no-price-required test
- bridge continuity principal no-price-required test

#### BE-06C — Binance Historical Market-Data Adapter + Deterministic Symbol Mapping

Purpose:
- Make Binance the primary external market-data source for listed assets.

Primary write scope:
- Binance adapter/client
- symbol mapping / alias mapping
- cache-fill logic

Implementation scope:
- use Binance market-data endpoints and archive-compatible lookup strategy
- deterministic mapping from canonical asset identity to Binance trading pair
- respect listing/delist windows and avoid symbol-guess drift
- support native majors and known wrapped aliases through canonical mapping

Definition of done:
- listed assets price from Binance before CoinGecko is attempted
- mapping behavior is deterministic and auditable

Required tests:
- listed asset happy path
- delisted / unavailable symbol test
- wrapped alias mapping test

#### BE-06D — CoinGecko Bounded Fallback + Unresolved-Price Behavior

Purpose:
- Preserve coverage when Binance cannot price the row, without turning
  CoinGecko into the backbone of the pipeline.

Primary write scope:
- CoinGecko adapter/client
- fallback orchestration
- unresolved-price state handling

Implementation scope:
- attempt CoinGecko only after Binance miss or unsupported mapping
- keep request volume bounded and cache-backed
- surface `PRICE_UNRESOLVABLE` / `UNKNOWN` without dropping quantity

Definition of done:
- Binance miss flows deterministically into CoinGecko fallback or `UNKNOWN`
- unresolved-price rows continue replay with incomplete-history signaling

Required tests:
- Binance miss -> CoinGecko hit test
- Binance miss -> CoinGecko miss -> `PRICE_UNKNOWN` test

#### BE-06E — Bybit Pricing Integration And Continuity-Safe Pricing Rules

Purpose:
- Make the pricing stage source-aware enough to consume Bybit canonical docs
  without duplicating principal pricing across the unified accounting universe.

Primary write scope:
- pricing rules for `source=BYBIT`
- continuity correlation handling

Implementation scope:
- use exact Bybit execution price for paired trades
- matched withdraw/deposit plus on-chain leg prices fees only and preserves
  principal continuity
- unmatched Bybit deposit/withdraw rows price as ordinary external
  inbound/outbound only when correlation is absent

Definition of done:
- Bybit trades do not call external market-data sources for principal price when
  the ledger already provides it
- matched Bybit transfer pairs do not mint duplicated priced acquisitions or
  disposals

Required tests:
- Bybit trade execution-price reuse test
- matched withdraw correlation no-double-pricing test
- unmatched deposit/outbound pricing fallback test

#### BE-06F — Pricing Rerun Pack + AVCO-Start Data Gate

Purpose:
- Prove the pricing stage is data-ready before AVCO replay starts.

Primary write scope:
- pricing job orchestration
- observability / blocker output

Implementation scope:
- rerun pricing over normalized docs after resolver chain lands
- emit explicit blocker/warning outputs for unresolved pricing
- produce a live-data gate that must be green before AVCO replay starts

Definition of done:
- pricing can rerun idempotently over the same normalized set
- unresolved rows remain explicit and auditable
- AVCO start depends on data gate, not on code completion claim

Required tests:
- pricing rerun idempotency test
- pricing blocker emission test

### BE-07 — Bybit Normalization

Purpose:
- Materialize Bybit evidence into the same canonical stream as on-chain events.

Primary write scope:
- Bybit normalization services/jobs
- repository access for `external_ledger_raw`

Implementation scope:
- skip `basisRelevant=false` and `outOfScope=true`
- UTA trade pairing with sliding `±5 sec` window
- orphan-leg fallback behavior
- canonicalType-to-flows mapping
- withdraw/deposit correlation by `txHash`
- `correlationId` assignment
- synthetic custody wallet `BYBIT:<uid>`
- Bybit status initialization:
  - `PENDING_PRICE` for priced BUY/SELL rows
  - `CONFIRMED` for transfer-only continuity rows

Definition of done:
- Bybit canonical docs land in `normalized_transactions`
- matched Bybit/on-chain transfer pairs are traceable on both sides and do not create duplicate `BUY/SELL`
- orphan and unmatched cases stay explicit

Required tests:
- UTA pairer window-boundary test
- orphan-leg fallback test
- matched withdraw correlation test
- matched deposit correlation test
- Bybit status initialization test

### BE-08 — AVCO Replay + Reconciliation

Purpose:
- Rebuild replay on top of canonical `CONFIRMED` documents only.

Primary write scope:
- `backend/src/main/java/com/walletradar/costbasis/**` or restored equivalent module boundary
- replay state/reconciliation services

Implementation scope:
- deterministic replay order:
  - `blockTimestamp`
  - `transactionIndex`
  - `_id`
- apply rules for `BUY`, `SELL`, `TRANSFER`, `FEE`, `PRICE_UNKNOWN`
- carry-over semantics for:
  - internal transfer
  - bridge
  - Bybit correlated transfer
  - lending continuity
  - vault continuity
  - protocol custody
- update `asset_positions`
- reconciliation and incomplete-history flags

Definition of done:
- replay never starts from `asset_positions`
- continuity events do not create realized PnL
- `PRICE_UNKNOWN` preserves quantity and sets incomplete-history state

Required tests:
- deterministic replay ordering test
- transfer carry-over test
- matched Bybit no-double-counting test
- `FEE` handling test
- incomplete-history propagation test

### BE-09 — Jobs, Observability, and Blocker Handling

Purpose:
- Make the pipeline operable and diagnosable in production-safe background jobs.

Primary write scope:
- scheduled jobs
- logging/metrics hooks
- blocker/warning output surfaces used by backend processing

Implementation scope:
- start/end logging and key counters per job
- distinguishable outcomes for:
  - on-chain normalized
  - Bybit normalized
  - unmatched Bybit bridge
  - orphan UTA leg
  - unresolved price
  - `NEEDS_REVIEW`
- bounded batch processing
- no unbounded memory growth

Definition of done:
- operators can tell which stage failed and why
- repeated job runs remain idempotent
- no full collection scan is required for normal operation

Required tests:
- job idempotency test
- retry-safe test
- logging/metrics smoke test where practical

### BE-10 — Legacy-Path Cleanup + Final Verification

Purpose:
- Remove obsolete runtime paths and leave one authoritative backend implementation.

Primary write scope:
- old normalization/pricing/replay entry points
- stale feature flags or dead schedulers
- docs/tasks references if needed

Implementation scope:
- delete or disconnect superseded classification/pricing paths
- remove stale status transitions and unreachable code
- verify current API contract remains accurate
- keep only runtime paths that match v3 docs

Definition of done:
- there is one active canonical normalization path, one pricing path, and one replay path
- stale code no longer competes with v3 behavior
- docs referenced by `backend-dev` remain aligned with the final implementation

Required tests:
- full backend test suite for touched modules
- targeted end-to-end replay scenario covering on-chain + Bybit correlation

## Deep Classification Closeout Slice — 2026-03-22 Live Rerun Audit

This slice supersedes older remediation priorities where they conflict.

Do not reopen already resolved families from the live snapshot unless a regression
fixture proves they broke again. Resolved in the current audit:

- `BSC` provider-first ingestion now matches live provider coverage for the current wallet universe (`33 == 33`)
- `redeemWithFee(...)` already routes to `BRIDGE_IN`
- `fillV3Relay(...)` / `fillRelay(...)` already route to `BRIDGE_IN`
- Avalanche `0x69328dec withdraw(...)` no longer inverts to `LENDING_DEPOSIT`
- BSC Pancake Infinity `multicall` / `modifyLiquidities` are no longer generic `UNKNOWN`
- `claimWithRecipient(...)` is no longer a live promo/phishing false-positive cluster
- earlier mass bridge-flow contamination is no longer an active blocker on the representative bridge-in fixtures

Current execution order:

1. `BE-05D` clarification reason precision + contract-creation evidence guard
2. `BE-04AM` remaining router overload closeout on dominant live families
3. `BE-04AN` zero-amount / no-op terminal policy closeout
4. `BE-04AO` selector-recovery final parity on review paths
5. `BE-04AP` `CLAIM_WITHOUT_MOVEMENT` terminal semantics + regression hardening
6. `BE-04AQ` final live-rerun regression pack + repeat-audit fixtures

Do not spend new implementation effort on `BE-03F` in this slice. The current
live audit already shows `BSC` provider coverage parity for the active wallet
universe, and the RPC/provider control plane is expected to be rewritten later.

### BE-03G — Raw Tx-Shape Canonicalization + Transfer-Row Separation

Purpose:
- Eliminate the current explorer/provider raw contamination where transfer-row payloads overwrite tx-level fields and create bogus native legs.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/adapter/**`
- `backend/src/main/java/com/walletradar/ingestion/pipeline/onchain/**`
- raw mapping tests

Implementation scope:
- canonicalize tx-level fields from the dedicated tx payload (`explorer.tx` or provider tx object)
- never let transfer-row payloads overwrite top-level:
  - `from`
  - `to`
  - `value`
  - `input`
  - `methodId`
  - `functionName`
- ensure transfer-like payloads are persisted only under:
  - `explorer.tokenTransfers[]`
  - `explorer.internalTransfers[]`
- preserve persisted real receipt logs separately from synthetic transfer-derived logs
- keep classification source-agnostic: all fixes must land in canonical raw shape or raw view, not in `if network == ...` classifier branches

Definition of done:
- representative bridge/inbound rows no longer persist transfer-row token amount as top-level native `value`
- canonical raw tx fields remain stable across explorer-first and provider-first ingestion
- no new raw row can create a duplicated native leg purely because a token transfer row was promoted to tx scope

Required fixtures:
- Base `redeemWithFee(...)` `0xd2cdbd7a...`
- Unichain `fillV3Relay(...)` `0x27978f7b...`
- Base `execute302(...)` `0x0144c453...`
- zkSync bridge-in `0x9187f4ca...`

Required tests:
- raw mapper test proving tx-level `value` is preserved when token transfer amount differs
- regression test proving transfer-style payloads stay under `explorer.tokenTransfers[]`
- raw-view test proving canonical tx-level fields win over contaminated top-level payload

### BE-04AG — Native-Flow Guard For Contaminated Raw Rows

Purpose:
- Prevent financially dangerous duplicate native legs even before historical raw is re-backfilled.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/onchain/**`
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- normalization tests

Implementation scope:
- derive direct native value only from canonical tx-level evidence in the raw view
- suppress direct native leg extraction when tx-level `value` is clearly contaminated by a transfer-row amount
- keep internal transfer legs intact when they are real and corroborated
- do not weaken bridge/reward labels; this task fixes flow semantics, not event labels

Definition of done:
- the representative contaminated rows no longer produce bogus native `BUY` / `TRANSFER` legs
- bridge continuity rows keep correct labels and correct asset quantities

Required tests:
- Base `redeemWithFee(...)` does not emit ETH dust leg
- Unichain `fillV3Relay(...)` does not emit ETH dust leg
- Base `execute302(...)` does not duplicate USDE quantity as ETH
- zkSync bridge-in does not double-count ETH as native + token

### BE-03H — `BSC` Provider Persistence Parity For Approve-Only Rows

Purpose:
- Close the live gap where provider acquisition returns `33` tx for the active wallet but only `25` persist to `raw_transactions`.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/adapter/evm/rpc/provider/**`
- `backend/src/main/java/com/walletradar/ingestion/adapter/evm/rpc/nativerpc/**`
- BSC adapter tests

Implementation scope:
- persist every provider-returned tx hash for the segment unless the row is explicitly rejected by a documented blocker
- ensure approve-only / no-movement rows survive mapping and persistence
- do not mark provider acquisition successful for a segment while provider-returned rows are silently dropped during raw mapping
- keep this as ingestion parity work; do not add `BSC`-specific classifier branches

Definition of done:
- the current audited active wallet persists all `33` provider rows or records explicit blockers for the missing hashes
- the eight verified missing `approve(0x095ea7b3)` hashes appear in `raw_transactions`
- later normalization of those rows resolves to `APPROVE`

Required fixtures:
- missing hashes from the audit:
  - `0x37908ec5...`
  - `0xba3ca393...`
  - `0xa784bfed...`
  - `0x6b82f05d...`
  - `0xd7003539...`
  - `0x2ad6116e...`
  - `0x510b3896...`
  - `0x5b68a8cc...`

Required tests:
- provider adapter regression for approve-only rows with logs/receipt
- end-to-end segment test proving provider count parity on the audited wallet fixture

### BE-05C — Clarification Reason Hygiene

Purpose:
- Keep the legitimate clarification queue explainable and auditable.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/job/clarification/**`
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- clarification tests

Implementation scope:
- every row entering `PENDING_CLARIFICATION` must carry explicit missing reasons:
  - `MISSING_EXECUTION_STATUS`
  - `MISSING_EFFECTIVE_GAS_PRICE`
  - `MISSING_GAS_USED`
  - `MISSING_CONTRACT_ADDRESS`
- clarification re-triage must preserve or update those reasons deterministically
- reason hygiene is additive only; it must not turn legitimate clarification rows into pricing/review rows by itself

Definition of done:
- no clarification-eligible row is created with empty `missingDataReasons[]`
- current clarification backlog is explainable by receipt-safe missing fields alone

Required tests:
- normalization builder test for clarification reasons
- clarification retry test preserving updated reason set

### BE-04AH — Selector-Recovery Parity Across All Classifier Paths

Purpose:
- Close the remaining review rows that are still recoverable from persisted calldata.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- `backend/src/main/java/com/walletradar/ingestion/filter/**`
- selector recovery tests

Implementation scope:
- ensure every classifier branch, scam-filter allowlist, and special handler reads selector from the raw view, not directly from raw BSON
- remove remaining direct reads of top-level `rawData.methodId` where selector recovery from `input[0:10]` should apply
- cover the current dominant recoverable families on `BASE`, `OPTIMISM`, `ETHEREUM`, `AVALANCHE`, and `ZKSYNC`

Definition of done:
- the remaining selector-recoverable `NEEDS_REVIEW` rows no longer stay unresolved only because top-level `methodId` is blank

Required tests:
- router overload fixture where `methodId == 0x` but calldata has selector
- scam-filter allowlist fixture where recovered selector must bypass promo/phishing suppression

### BE-04AI — Merkl Claim-Family Closure + Per-Wallet `CLAIM_WITHOUT_MOVEMENT`

Purpose:
- Finish the main reward-family closure without minting fake rewards for non-receiving tracked wallets.

Primary write scope:
- `backend/src/main/resources/protocol-registry.json`
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- reward-family tests

Implementation scope:
- extend contract-aware claim coverage for `0x3ef3d8ba38ebe18db133cec108f4d14ce00dd9ae`
  across the remaining supported networks where raw evidence already exists
- align rule design to Merkl `Distributor.sol` semantics:
  - `claim(...)`
  - `claimWithRecipient(...)`
- classify only the receiving tracked wallet as `REWARD_CLAIM`
- keep non-receiving tracked wallets and no-movement claim rows explicit as `CLAIM_WITHOUT_MOVEMENT` or review
- do not use clarification to upgrade no-movement claim calls into reward acquisition

Protocol sources:
- Merkl `Distributor.sol`

Definition of done:
- Plasma and Unichain claim rows no longer collapse into generic `EXTERNAL_INBOUND`
- per-wallet `claimWithRecipient(...)` semantics remain correct for both receiving and non-receiving tracked wallets

Required tests:
- real inbound Merkl claim
- `claimWithRecipient(...)` duplicated across two tracked wallets
- no-movement claim call stays explicit non-economic/review

### BE-04AJ — Zero-Amount / No-Op Terminal Policy On Dominant Live Families

Purpose:
- Remove the remaining zero-amount/no-op families from ambiguous review where their non-economic meaning is already known.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- zero-amount family tests

Implementation scope:
- document and implement contract-aware terminal handling for the dominant live families:
  - Avalanche zero-amount transfer clusters
  - Arbitrum `0x0cf79e0a` clusters
  - explicit setup / batch no-op families already proven non-economic
- known non-economic families may resolve to `ADMIN_CONFIG`
- unknown zero-amount families remain `NEEDS_REVIEW` with `ZERO_AMOUNT_TOKEN_TRANSFER`
- no family in this slice may create synthetic `BUY` / `SELL`

Definition of done:
- dominant audited zero-amount families no longer stay open only because policy was undocumented
- unknown families remain explicit and reviewable

Required tests:
- known zero-amount family -> `ADMIN_CONFIG`
- unknown zero-amount family -> `UNKNOWN/NEEDS_REVIEW/ZERO_AMOUNT_TOKEN_TRANSFER`

### BE-04AK — Remaining Router Overload And Empty-Selector Closeout

Purpose:
- Shrink the remaining `NEEDS_REVIEW` queue to genuinely rare edge cases.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- `backend/src/main/resources/protocol-registry.json`
- router/LP tests

Implementation scope:
- close the dominant live router/LP clusters on:
  - Base
  - Ethereum
  - zkSync
  - Optimism
  - Avalanche
- prefer generic raw-evidence rules over network-specific branches
- for concentrated-liquidity and farm routes, align decoding to protocol-source semantics:
  - Pancake Infinity `CLPositionManager.sol`
  - SmartChef `SmartChefInitializable.sol`
  - Across `SpokePool.sol` where settlement / entry routing overlaps
- unsupported overloads must remain explicit `NEEDS_REVIEW`, not broad `FUNCTION_NAME` fallback

Definition of done:
- dominant router overload clusters from the deep audit exit review or become explicit handler-unsupported rows with deterministic reasons

Required tests:
- Ethereum Uniswap/Pancake position-manager `multicall`
- remaining Base router overload fixture
- zkSync overload fixture
- Optimism empty-selector fixture

### BE-04AL — Final Classification Regression Pack + Repeat-Audit Fixtures

Purpose:
- Lock in the closeout and make the next Mongo audit primarily a data check, not a rediscovery exercise.

Primary write scope:
- backend tests only

Implementation scope:
- codify all representative hashes from the deep audit as regression fixtures
- include protocol-source-backed fixtures for:
  - Across
  - Merkl
  - Pancake Infinity CL
  - SmartChef
- include raw contamination fixtures and BSC approve-only persistence fixtures

Definition of done:
- the next repeat audit is expected to move from semantic bug-finding to residual edge-case validation

Required tests:
- contamination regression pack
- bridge continuity pack
- reward/no-movement pack
- BSC provider parity pack
- selector recovery pack

### BE-05D — Clarification Reason Precision + Contract-Creation Evidence Guard

Purpose:
- Make the clarification queue trustworthy by aligning `missingDataReasons[]`
  with the fields that are actually missing in canonical raw evidence.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- `backend/src/main/java/com/walletradar/ingestion/pipeline/onchain/**`
- `backend/src/main/java/com/walletradar/ingestion/job/clarification/**`
- clarification tests

Implementation scope:
- compute clarification reasons from the raw view only
- treat `MISSING_EFFECTIVE_GAS_PRICE` as independent from legacy `gasPrice`
  fallback used for fee reconstruction
- emit `MISSING_CONTRACT_ADDRESS` only when contract-creation intent is
  explicitly evidenced by canonical tx-shaped raw payload
- preserve and recompute the same reason set during normalization rebuilds and
  clarification retries
- do not retarget status by itself; this task fixes explainability, not queue
  membership

Definition of done:
- current live clarification rows no longer carry spurious
  `MISSING_CONTRACT_ADDRESS`
- clarification rows missing only receipt status and effective gas price carry
  those exact reasons
- clarification retries keep deterministic reason hygiene

Required tests:
- builder test for missing effective gas price without contract creation
- raw-view test proving absent `to` key alone does not imply contract creation
- clarification retry test preserving corrected reason set

### BE-04AM — Remaining Router Overload Closeout On Dominant Live Families

Purpose:
- Shrink the live `ROUTER_METHOD_OVERLOAD_UNSUPPORTED` and residual
  `CLASSIFICATION_FAILED` queue to genuinely rare cases.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- `backend/src/main/resources/protocol-registry.json`
- router/LP tests

Implementation scope:
- close the dominant live overload families on:
  - Base Pancake/Uniswap-style position-manager `multicall(0xac9650d8)` rows
  - Arbitrum Pancake V3 MasterChef `multicall(0xac9650d8)` rows
  - zkSync routed `0x3593564c` rows with persisted ETH/USDC movement
  - Optimism Slipstream multicall / empty-selector family only where persisted
    raw evidence is sufficient
- use official protocol-source semantics where available:
  - Pancake Infinity `CLPositionManager.sol`
  - Velodrome Slipstream `NonfungiblePositionManager.sol`
  - Across `SpokePool.sol` when bridge routing overlaps
- if persisted raw evidence is still insufficient, keep the row explicit review;
  do not let explorer UI text silently redefine canonical output

Definition of done:
- dominant Base/Arbitrum router overload families resolve to deterministic LP or
  bridge continuity semantics
- zkSync `0x3593564c` no longer stays open only because the outer router method
  is generic
- Optimism empty-selector rows remain review only when raw evidence is truly
  insufficient

Required fixtures:
- Base `0xfffcf721...`
- Arbitrum `0x6537cd02...`
- zkSync `0xb7a9086d...`
- Optimism `0x927d3f45...`

Required tests:
- Base position-manager multicall exit/custody fixture
- Arbitrum MasterChef multicall LP exit / fee-claim fixture
- zkSync routed swap/bridge overload fixture
- Optimism insufficient-evidence fixture that stays explicit review

### BE-04AN — Zero-Amount / No-Op Terminal Policy Closeout

Purpose:
- Turn the dominant audited zero-amount families into deterministic
  non-economic outcomes and leave only genuinely unknown families in review.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- zero-amount family tests

Implementation scope:
- finalize contract-aware terminal handling for the dominant live families:
  - Arbitrum `0x0cf79e0a`
  - Avalanche `a9059cbb` zero-amount clusters
  - Avalanche/Base `0x12514bba` families
- known non-economic families may resolve to `ADMIN_CONFIG`
- unknown zero-amount families must remain `UNKNOWN/NEEDS_REVIEW` with
  `ZERO_AMOUNT_TOKEN_TRANSFER`
- no family in this slice may create synthetic `BUY` / `SELL`

Definition of done:
- the current dominant live zero-amount families no longer remain open only
  because terminal policy was undocumented
- unknown families stay explicit and auditable

Required tests:
- known zero-amount family -> `ADMIN_CONFIG`
- unknown zero-amount family -> `UNKNOWN/NEEDS_REVIEW/ZERO_AMOUNT_TOKEN_TRANSFER`

### BE-04AO — Selector-Recovery Final Parity On Review Paths

Purpose:
- Close the remaining review rows that are still recoverable from persisted
  calldata, now that selector recovery is no longer the dominant blocker.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- `backend/src/main/java/com/walletradar/ingestion/filter/**`
- selector recovery tests

Implementation scope:
- remove remaining direct reads of raw `methodId` in review-path logic,
  allowlists, and special handlers
- ensure recovered selector from `input[0:10]` is used consistently on the
  residual Base/Optimism/Ethereum/Avalanche/ZkSync families still open in the
  live audit

Definition of done:
- the remaining selector-recoverable live review rows no longer stay unresolved
  only because top-level `methodId` is blank

Required tests:
- recovered-selector review fixture
- recovered-selector allowlist fixture

### BE-04AP — `CLAIM_WITHOUT_MOVEMENT` Terminal Semantics + Regression Hardening

Purpose:
- Freeze the correct per-wallet semantics for claim-family rows where the
  tracked wallet signs the claim route but receives no reward movement.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- reward-family tests

Implementation scope:
- keep `CLAIM_WITHOUT_MOVEMENT` as an explicit terminal review outcome for
  non-receiving tracked wallets on known claim distributors
- do not auto-upgrade these rows during clarification
- add regression coverage for the audited Arbitrum and BSC examples where one
  tracked wallet receives the reward and another only signs the claim call

Definition of done:
- receiving tracked wallets become `REWARD_CLAIM`
- non-receiving tracked wallets remain explicit `CLAIM_WITHOUT_MOVEMENT`
- no clarification or reward allowlist path silently mints reward acquisition

Required fixtures:
- Arbitrum `0xf13356fe...`
- BSC `0xa586770c...`
- BSC `0xeb4fd02c...`

Required tests:
- duplicated claim tx across two tracked wallets
- no-movement claim row remains explicit terminal review

### BE-04AQ — Final Live-Rerun Regression Pack + Repeat-Audit Fixtures

Purpose:
- Lock the current live closeout so the next Mongo audit is mainly a data check,
  not a rediscovery exercise.

Primary write scope:
- backend tests only

Implementation scope:
- codify representative hashes from the latest live rerun audit into durable
  regression fixtures:
  - clarification reason hygiene rows
  - Base/Arbitrum/zkSync/Optimism router overload fixtures
  - zero-amount/no-op families
  - per-wallet claim-without-movement fixtures
  - BSC provider parity fixtures already resolved in the live run

Definition of done:
- the latest live-audit findings are reproducible in tests
- resolved families cannot silently regress without failing CI

## Final Classification Closeout Slice — 2026-03-22 Deep Audit

This slice supersedes older closeout ordering where it conflicts with the live
Mongo audit recorded in:

- `results/raw-classification-audit.md`
- `results/blockers.md`
- `results/warnings.md`
- `data/derived/classification_stage_snapshot.json`
- `data/derived/raw_cluster_audit.json`

Resolved and not to be reopened without a regression fixture:

- `BSC` provider-first coverage parity for the current wallet universe
- bridge-flow contamination as a broad live diagnosis
- `redeemWithFee(...)` / `fillV3Relay(...)` family routing to `BRIDGE_IN`
- main Merkl family-wide closure on `0x3ef3...`
- Pancake Infinity `BSC` `multicall` / `modifyLiquidities` generic `UNKNOWN`

Current execution order:

1. `BE-05E` clarification live parity closeout
2. `BE-04AR` residual router overload families
3. `BE-04AS` residual selector-recovery parity
4. `BE-04AT` residual `CLASSIFICATION_FAILED` / `HANDLER_UNSUPPORTED_METHOD` family triage
5. `BE-04AU` final zero-amount / no-op terminal policy
6. `BE-04AV` review-state regression lock (`CLAIM_WITHOUT_MOVEMENT` + promo true positives)
7. `BE-04AW` final rerun pack + repeat-audit handoff

Do not treat the following as clarification blockers in this slice:

- `PROMO_SPAM_PHISHING`
- `ROUTER_METHOD_OVERLOAD_UNSUPPORTED`
- `CLAIM_WITHOUT_MOVEMENT`
- `ZERO_AMOUNT_TOKEN_TRANSFER`
- narrow `CLASSIFICATION_FAILED` families

The only current clarification blocker from the live snapshot is reason parity:
`PENDING_CLARIFICATION` rows missing both execution status and
`effectiveGasPrice` must surface both reasons deterministically.

### BE-05E — Clarification Live Parity Closeout

Purpose:
- Close the last live blocker before clarification-readiness by making
  persisted clarification reasons match the actual missing receipt-safe fields
  seen in raw.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- `backend/src/main/java/com/walletradar/ingestion/pipeline/onchain/**`
- `backend/src/main/java/com/walletradar/ingestion/job/clarification/**`
- clarification tests

Implementation scope:
- compute clarification reasons from canonical raw-view evidence only
- surface `MISSING_EFFECTIVE_GAS_PRICE` on clarification rows whenever
  `effectiveGasPrice` is actually missing in raw, even when the tracked wallet
  is not the fee payer
- keep `MISSING_GAS_USED` and `MISSING_CONTRACT_ADDRESS` narrow:
  - `MISSING_GAS_USED` only when that field is actually missing
  - `MISSING_CONTRACT_ADDRESS` only on explicit contract-creation rows
- do not widen clarification queue membership in this task; fix parity and
  explainability first

Definition of done:
- a live clarification row missing both execution status and effective gas price
  persists both reasons after rerun
- plain inbound clarification rows no longer hide missing `effectiveGasPrice`
- clarification retry/rebuild paths preserve the corrected reason set

Required tests:
- non-fee-payer clarification row still records missing effective gas price
- builder retry preserves `MISSING_EXECUTION_STATUS + MISSING_EFFECTIVE_GAS_PRICE`
- explicit contract creation remains the only source of `MISSING_CONTRACT_ADDRESS`

### BE-04AR — Residual Router Overload Families

Purpose:
- Close the small remaining router-overload queue where persisted raw is now
  sufficient for deterministic method-aware routing.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- `backend/src/main/resources/protocol-registry.json`
- router/LP tests

Implementation scope:
- re-evaluate only the current live residual overload families:
  - Optimism `0x416b433906b1b72fa758e166e239c43d68dc6f29 + 0xac9650d8`
  - zkSync `0xdaee41e335322c85ff2c5a6745c98e1351806e98 + 0x3593564c`
  - Base `0x46a15b0b27311cedf172ab29e4f4766fbe7f4364 + 0xac9650d8`
- use child-selector decode and persisted raw logs/legs only
- if persisted raw is still insufficient, keep explicit review and do not
  upgrade the row from explorer UI text alone

Definition of done:
- residual overload rows no longer remain open when persisted raw already gives
  enough evidence for deterministic LP / swap / bridge routing
- insufficient-evidence rows stay explicit review by design

Required fixtures:
- Optimism `0x927d3f45...`
- zkSync `0xb7a9086d...`
- Base `0x0a757aee...`

Required tests:
- child-selector decode from outer `multicall`
- insufficient-evidence overload row that remains review
- routed multi-leg overload row that resolves deterministically from raw

### BE-04AS — Residual Selector-Recovery Parity

Purpose:
- Remove the remaining review rows that are still recoverable from persisted
  calldata because some residual review path bypasses the raw view selector.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- `backend/src/main/java/com/walletradar/ingestion/filter/**`
- selector recovery tests

Implementation scope:
- audit every review-path selector read and move it through the raw view when
  it still reads top-level `rawData.methodId` directly
- cover the current live residual recoverable rows on
  `BASE`, `OPTIMISM`, `ETHEREUM`, `AVALANCHE`, and `ZKSYNC`

Definition of done:
- selector-recoverable live review rows no longer remain open only because
  top-level `methodId` was blank

Required tests:
- recovered-selector review fixture
- recovered-selector allowlist fixture

### BE-04AT — Residual `CLASSIFICATION_FAILED` / `HANDLER_UNSUPPORTED_METHOD` Family Triage

Purpose:
- Collapse the remaining repeatable failure families into either deterministic
  known outcomes or explicit intentional review states.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- `backend/src/main/resources/protocol-registry.json`
- family-specific tests

Implementation scope:
- audit and close the repeatable live families first:
  - Plasma `0x1939c1ff` rows with two token transfers
  - Avalanche `batch(...)` `0xc16ae7a4`
  - Mantle `claim(...)` `0x5d4df3bf`
  - Arbitrum handler gap `0x374f435d`
- if a family is truly non-economic spam/no-op, move it to deterministic review
  with the correct reason instead of leaving generic `CLASSIFICATION_FAILED`
- if a family has enough persisted raw for a known type, normalize it
- if not enough evidence exists, keep it explicit review but with the narrowest
  honest reason

Definition of done:
- repeatable residual failure families are no longer hidden behind generic
  `CLASSIFICATION_FAILED` when a narrower deterministic outcome is possible

Required tests:
- family-specific regression for every closed repeatable group
- unresolved family that intentionally stays explicit review

### BE-04AU — Final Zero-Amount / No-Op Terminal Policy

Purpose:
- Finish the last small zero-amount/no-op set so the remaining rows are
  intentionally terminal rather than policy leftovers.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- zero-amount policy tests

Implementation scope:
- document and implement the final policy for the current live residual set:
  - Ethereum `0xa9059cbb`
  - Arbitrum `0xe94a5b23`
  - Unichain `0xc16ae7a4`
- known non-economic paths may resolve to `ADMIN_CONFIG`
- truly unknown paths remain `UNKNOWN/NEEDS_REVIEW/ZERO_AMOUNT_TOKEN_TRANSFER`

Definition of done:
- no live residual zero-amount row remains open only because policy was
  undocumented
- no row in this slice creates synthetic economic movement

Required tests:
- known residual family -> deterministic non-economic terminal state
- unknown residual family -> explicit zero-amount review

### BE-04AV — Review-State Regression Lock

Purpose:
- Freeze the families that are currently correct so future work does not
  regress them while shrinking the remaining review queue.

Primary write scope:
- backend tests only

Implementation scope:
- keep `CLAIM_WITHOUT_MOVEMENT` explicit for non-receiving claim signers
- keep true-positive promo/phishing clusters explicit review
- ensure `redeemWithFee(...)`, `fillV3Relay(...)`, and resolved `BSC` LP rows do
  not regress while other families are being closed

Definition of done:
- correct live review semantics are locked in durable regression coverage

Required fixtures:
- Arbitrum `0xf13356fe...`
- BSC `0xa586770c...`
- BSC `0xeb4fd02c...`
- Plasma `0xcbee5437...`

### BE-04AW — Final Rerun Pack + Repeat-Audit Handoff

Purpose:
- End the classification closeout slice with one rerun-ready pack that makes
  the next audit mostly a verification pass.

Primary write scope:
- backend tests
- task/docs updates if needed

Implementation scope:
- run targeted regression pack for:
  - clarification live parity
  - residual router overloads
  - residual selector parity
  - residual failure-family triage
  - zero-amount terminal policy
  - review-state regression lock
- hand off rerun expectations explicitly to the next Mongo audit

Definition of done:
- rerun expectations are documented and test-backed
- the next audit can decide clarification readiness from data, not from code
  guesswork

## Clarification Receipt-Enrichment Slice — 2026-03-22 Post-Classification Audit

This slice starts after the classification closeout rerun validated that the
remaining normalization debt is narrow review-tail work, not a systemic
classification failure.

Sources for this slice:

- `results/raw-classification-audit.md`
- `results/review-tail-classifiability-audit.md`
- `results/receipt-closure-check.md`
- `data/derived/review_tail_selected_tx_audit.json`
- `data/derived/review_tail_receipt_summary.json`

Architectural contract for this slice:

- Clarification stays one stage with two internal modes:
  - metadata-safe enrichment for receipt-clarifiable rows
  - allowlisted full-receipt enrichment for residual review families
- Full receipt enrichment is not a widening of the base
  `PENDING_CLARIFICATION` queue.
- Clarification may use only production-fetchable full receipt evidence.
- Clarification enrichment follows raw-source lineage by default:
  - RPC-backed raw -> RPC clarification
  - Etherscan-family raw -> Etherscan-family clarification
  - Blockscout-backed raw -> Blockscout clarification
- Clarification must not use traces, explorer UI labels, or analyst-only
  notes as runtime inputs.
- Rows already closable from current raw stay classification/handler work.

Current execution order:

1. `BE-04AX` claim-family no-movement closeout from current raw
2. `BE-04AY` Morpho Bundler withdraw-collateral handler closeout
3. `BE-05F` Clarification receipt-evidence contract + full receipt persistence + source-lineage routing
4. `BE-05G` Clarification LP / router receipt-log closeout
5. `BE-05H` Clarification batch/log closeout for Euler-style residuals
6. `BE-05I` Clarification non-economic cleanup / admin families
7. `BE-05J` intentional-review lock for receipt-insufficient families
8. `BE-05K` Clarification rerun pack + repeat-audit handoff

### BE-04AX — Claim-Family No-Movement Closeout From Current Raw

Purpose:
- Remove the remaining claim-family rows that still sit in generic
  `CLASSIFICATION_FAILED` even though current raw already proves a deterministic
  no-movement claim outcome.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- `backend/src/main/resources/protocol-registry.json`
- claim-family tests

Implementation scope:
- close the audited Mantle family:
  - `0x0045601c3c4c561012c108ea84a81e36eac24296 + 0x5d4df3bf`
- keep the rule raw-only and wallet-scoped:
  - known claim contract
  - claim selector
  - no inbound reward movement to the tracked wallet in persisted raw
- normalize to `CLAIM_WITHOUT_MOVEMENT` or equivalent explicit no-movement claim
  terminal semantics
- do not wait for clarification

Definition of done:
- audited claim-family no-movement rows no longer remain generic
  `CLASSIFICATION_FAILED`
- receiving-wallet rows still become `REWARD_CLAIM`
- non-receiving-wallet rows stay explicit no-movement claim outcomes

Required fixtures:
- Mantle `0x02b8f88942ef4bf12132e75b294ef5472d98fddcfd4ea5f9f3277c7492d967f7`
- Arbitrum wallet-scoped split `0xf13356fe9449ec9e831395e0074622e88e362a8f317e6b110d093bfaa25d2702`

Required tests:
- claim-family no-movement row narrows from generic failure to explicit terminal state
- same tx hash can still split to `REWARD_CLAIM` for a receiving tracked wallet

### BE-04AY — Morpho Bundler `morphoWithdrawCollateral(...)` Handler Closeout

Purpose:
- Close the audited Arbitrum Morpho handler gap where current raw already proves
  a collateral-withdraw path.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- Morpho handler tests

Implementation scope:
- extend Bundler handling for:
  - target `0x9954afb60bb5a222714c478ac86990f221788b88`
  - inner selector `0x1af3bbc6`
  - verified ABI semantics `morphoWithdrawCollateral((address,address,address,address,uint256),uint256,address)`
- require persisted inbound asset movement to the tracked wallet before closing
  the row
- normalize to the correct collateral/lending-withdraw continuity path
- do not defer these rows to clarification

Definition of done:
- the audited Morpho residuals no longer remain
  `HANDLER_UNSUPPORTED_METHOD`
- classification stays raw-only and source-agnostic

Required fixtures:
- `0xb8d6c7042a0266e7c7a34f66a69e0e5e92bca3d5e69f7983cff9d1adfb7d67b7`
- `0xcec238f36116929a51489063506964eaceb40bcb88ab711f148e6fdaa35f57e0`
- `0xedf2ad26a41e6c82a6a31fffc3020d2c44599d647a7670ee72d32a090176d594`

Required tests:
- supported Morpho withdraw-collateral bundle closes to deterministic type
- unsupported bundle method still stays explicit review

### BE-05F — Clarification Receipt-Evidence Contract + Full Receipt Persistence + Source-Lineage Routing

Purpose:
- Introduce the bounded data contract for full-receipt enrichment without
  turning clarification into an unbounded second classifier.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/job/clarification/**`
- `backend/src/main/java/com/walletradar/ingestion/pipeline/onchain/**`
- raw-view / clarification model tests
- docs or small schema helpers if needed

Implementation scope:
- keep metadata-safe clarification behavior for receipt-clarifiable rows
- add allowlisted full-receipt enrichment inside the same clarification stage:
  - full receipt logs
  - receipt status/gas fields already used by metadata-safe clarification
- persist both:
  - adapted clarification evidence used by runtime classification
  - raw full receipt payload when the source exposes it
- persist full receipt logs under a dedicated clarification-evidence field
  separate from synthetic `rawData.logs[]`
- expose that evidence through the normal raw view so classification remains
  source-agnostic
- define an allowlist gate by family / selector / contract identity
- route clarification fetch through the same source family that produced the raw
  row unless an explicit documented fallback is triggered
- implement this task in the following internal order:
  - storage contract for adapted clarification evidence
  - storage contract for raw full receipt payload
  - lineage-consistent clarification source router
  - raw-view exposure of clarification receipt evidence
- forbid:
  - traces
  - explorer UI summaries
  - ad-hoc audit-only annotations

Definition of done:
- production code has an explicit, test-backed boundary between
  metadata-safe clarification and allowlisted full-receipt clarification
- classifier can consume clarification receipt logs only through the raw view
- no synthetic log path can masquerade as real receipt evidence
- lineage-consistent clarification source selection is deterministic and tested
- future deterministic enrichment can reuse persisted full receipt payload
  without requiring a repeat fetch when the source originally exposed it

Required tests:
- clarification receipt logs persist into dedicated evidence field
- raw full receipt payload persists alongside adapted clarification evidence when available
- raw view exposes clarification receipt logs but still ignores synthetic logs
- non-allowlisted row cannot enter full-receipt clarification
- RPC-backed raw chooses RPC clarification path
- explorer-backed raw chooses its matching explorer-family clarification path

### BE-05G — Clarification LP / Router Receipt-Log Closeout

Purpose:
- Close the audited residual LP/router family where current raw is insufficient
  but full receipt logs are enough for deterministic LP-exit-related semantics.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/job/clarification/**`
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- LP/router clarification tests

Implementation scope:
- allowlist Pancake CL position-manager exit family:
  - Base `0x46a15b0b27311cedf172ab29e4f4766fbe7f4364 + 0xac9650d8`
- after clarification receipt fetch:
  - consume real receipt logs
  - decode known LP-exit-related event family
  - derive deterministic terminal type only when receipt evidence closes the row
- do not use explorer page summaries such as “Remove Liquidity” as runtime input

Definition of done:
- Base `0x0a757...` no longer remains overload review if full receipt evidence is
  present
- rows with the same outer container but still insufficient receipt evidence stay
  explicit review

Required fixtures:
- Base `0x0a757aeeb58667c545017cd8e5cd60dc994a8945ed810c60ea2aed18688f4f7a`

Required tests:
- receipt-log-enriched Pancake CL exit closes deterministically
- same family without sufficient receipt movement evidence stays review

### BE-05H — Clarification Batch / Log Closeout For Euler-Style Residuals

Purpose:
- Use receipt-log enrichment only where it materially closes the remaining
  Euler-style batch family, while preserving honest review for rows that still
  lack movement evidence.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/job/clarification/**`
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- batch/log clarification tests

Implementation scope:
- allowlist the audited Avalanche batch family:
  - `0xc16ae7a4 batch(...)`
- close rows only when clarification receipt logs reveal enough transfer or
  protocol evidence to derive deterministic movement semantics
- explicitly keep rows like `0x509c...` in review when even full receipt lacks
  economic movement evidence

Definition of done:
- `0x305f...` can be closed if the persisted clarification receipt logs remain
  materially sufficient
- `0x509c...` stays explicit review by design
- no Euler batch row is upgraded from calldata intent alone

Required fixtures:
- Avalanche `0x305f37a69956a13001962216c845385996114876173bdbaef644bbe3baadf5df`
- Avalanche `0x509c134b2795de71a1ee42db38b53af78003308e8c9ebf2b1bfa9ce8d348dcd2`

Required tests:
- receipt-log-rich Euler batch closes deterministically
- wrapper-only/no-movement receipt remains review

### BE-05I — Clarification Non-Economic Cleanup / Admin Families

Purpose:
- Narrow the audited receipt-helpful but non-economic residuals into explicit
  terminal cleanup/admin states without inventing LP or trading movement.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/job/clarification/**`
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- non-economic clarification tests

Implementation scope:
- allowlist the audited receipt-helpful families:
  - Optimism Velodrome Slipstream burn trio:
    - `0x927d3f45...`
    - `0xe1bc445f...`
    - `0x90720700...`
  - Optimism residual admin/governance candidate:
    - `0x9867f9d2...`
- if receipt proves only NFT cleanup / non-transfer protocol events:
  - narrow to explicit non-economic terminal state such as cleanup/admin review
  - do not mint `LP_EXIT`, `SWAP`, or other economic types

Definition of done:
- burn-only or governance-only receipt patterns are no longer generic overload
  failures when the allowlisted family is proven
- no non-economic clarification rule invents asset movement

Required tests:
- burn-only receipt family narrows to explicit non-economic terminal state
- protocol-event-only receipt family narrows only when allowlisted

### BE-05J — Intentional Review Lock For Receipt-Insufficient Families

Purpose:
- Freeze the families that must remain review even after the introduction of
  clarification receipt enrichment, so future work does not quietly over-classify them.

Primary write scope:
- backend tests only

Implementation scope:
- keep explicit review for:
  - no-evidence router/container rows that still lack transfers and receipt logs
  - claim-without-movement rows that stay wallet-scoped no-movement outcomes
  - Euler / batch rows whose full receipt still lacks movement evidence
- keep `PROMO_SPAM_PHISHING` true positives stable

Definition of done:
- receipt-insufficient rows remain intentional review after clarification work
- future rule additions cannot silently over-upgrade these families

Required fixtures:
- Avalanche `0x509c134b2795de71a1ee42db38b53af78003308e8c9ebf2b1bfa9ce8d348dcd2`
- BSC `0xeb4fd02cba10c357ea4f5441c0783c5282c01fcaa1a85c661575471df592c5ef`
- Arbitrum wallet-scoped `0xf13356fe9449ec9e831395e0074622e88e362a8f317e6b110d093bfaa25d2702`
- Plasma `0xcbee5437edfe64d3abe9f7b6e0b02daf059405d348ae90ee08a81a53b933c0b6`

### BE-05K — Clarification Rerun Pack + Repeat-Audit Handoff

Purpose:
- End the clarification transition slice with one rerun-ready pack that lets
  the next audit decide from data which residuals are truly closed, which moved
  to explicit non-economic states, and which still remain honest review.

Primary write scope:
- backend tests
- task/docs updates if needed

Implementation scope:
- run targeted regression coverage for:
  - claim-family no-movement closeout
  - Morpho withdraw-collateral handler closeout
  - clarification receipt-evidence contract
  - LP/router receipt-log closeout
  - Euler batch/log closeout
  - non-economic cleanup/admin families
  - intentional-review lock
- document rerun expectations for the next Mongo audit

Definition of done:
- rerun expectations are documented and test-backed
- the next audit can answer whether clarification materially shrank the
  review tail without blurring intentional review states

## Post-Clarification Pricing-Readiness Closeout — 2026-03-22 Deep Live Audit

This corrective slice starts after the clarification rerun and targets the live
gaps confirmed in `results/clarification/run/2/clarification-readiness-audit.md`.

Sources for this slice:

- `results/clarification/run/2/clarification-readiness-audit.md`
- `results/clarification/run/2/confirmed_resolved_misclassifications.tsv`
- `results/clarification/run/2/resolved_but_insufficient_evidence.tsv`
- `results/clarification/run/2/live_snapshot.json`
- `results/clarification/run/2/audit_summary.json`

Primary official/market sources for rule authority:

- WETH9:
  `https://raw.githubusercontent.com/gnosis/canonical-weth/master/contracts/WETH9.sol`
- Across `SpokePool.sol`:
  `https://raw.githubusercontent.com/across-protocol/contracts/master/contracts/SpokePool.sol`
- Trader Joe `LBRouter.sol`:
  `https://raw.githubusercontent.com/traderjoe-xyz/joe-v2/main/src/LBRouter.sol`
- Uniswap `NonfungiblePositionManager.sol`:
  `https://raw.githubusercontent.com/Uniswap/v3-periphery/main/contracts/NonfungiblePositionManager.sol`
- Pancake Infinity `CLPositionManager.sol`:
  `https://raw.githubusercontent.com/pancakeswap/infinity-periphery/main/src/pool-cl/CLPositionManager.sol`
- Pendle `ActionAddRemoveLiqV3.sol`:
  `https://raw.githubusercontent.com/pendle-finance/pendle-core-v2-public/main/contracts/router/ActionAddRemoveLiqV3.sol`
- GMX `ExchangeRouter.sol`:
  `https://raw.githubusercontent.com/gmx-io/gmx-synthetics/main/contracts/router/ExchangeRouter.sol`
- Merkl `Distributor.sol`:
  `https://raw.githubusercontent.com/AngleProtocol/merkl-contracts/main/contracts/Distributor.sol`

Execution order after `BE-05K`:

1. `BE-05L` wrapped-native continuity parity closeout
2. `BE-05M` Across bridge-entry semantic parity closeout
3. `BE-05N` Trader Joe LB liquidity family correction
4. `BE-05O` non-economic admin / no-op demotion from economic LP / vault families
5. `BE-05P` economic evidence gate before pricing
6. `BE-05Q` clarification evidence persistence live parity
7. `BE-05R` pricing-readiness rerun pack + repeat-audit handoff

Run/2 outcome:

- `BE-05N`, `BE-05O`, `BE-05P`, and `BE-05Q` look materially closed in live data.
- `BE-05L` and `BE-05M` are **not** closed from data and must be reopened from the run/2 audit output.

### BE-05L — Wrapped-Native Continuity Parity Closeout

Purpose:
- Eliminate the confirmed wrap / unwrap misclassifications that currently leak
  continuity rows into `VAULT_*` or `LENDING_WITHDRAW`.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- wrapper/native support helpers
- wrap/unwrap regression tests

Implementation scope:
- enforce wrapper precedence for known wrapped-native contracts using:
  - selector `0xd0e30db0 = deposit()`
  - selector `0x2e1a7d4d = withdraw(uint256)`
  - wrapped-token mint / burn evidence
  - native continuity evidence from raw or persisted clarification evidence
- close rows even when top-level `to` is weak or absent if canonical continuity
  evidence is still sufficient
- forbid fallback to generic `VAULT_DEPOSIT`, `VAULT_WITHDRAW`, or
  `LENDING_WITHDRAW` once canonical wrap/unwrap evidence is present

Definition of done:
- the confirmed wrap / unwrap families from the live audit no longer resolve to
  `VAULT_*` or `LENDING_WITHDRAW`
- wrap/unwrap behavior is deterministic across `ETHEREUM`, `ARBITRUM`,
  `AVALANCHE`, `PLASMA`, and `UNICHAIN`

Required fixtures:
- `0x11f7e79926cbb9eb85fbd39c171a561a69037a74e0ea87c499b2dc62a7dee958`
- `0x32d5adf0e402f5f2811b8fd252282a05bc38fa3f409c849a1a45c14d16871005`
- `0x41e1b02b3d27de7b86c3c25d21d116cf23085e1d67e70efa44cb4017eff1f72a`
- `0xc2b13c1218a4f90d29319f9d21b0f479d6b262ba7ace3a66c1f4c66afb2b1ff6`
- `0x26315fe4ce9e1da2694d790fc74142b5b6c0ee0b7e6630b8c2335fbfe95e5112`
- `0x8d39e25b3122a24b8d47a477163c4f7f116c4d5e1e95b8f78e8f075807432e60`
- `0xa467cc0897b4f063b5e5914223e122ed6906ad6b3243261517cfd72504d695be`
- `0xb417ec3a2c7dbc4517dd14acae3c15e070bc99bd6401fecd5de645e6f32ba813`

Required tests:
- wrapper `deposit()` plus mint resolves to `WRAP`
- wrapper `withdraw(uint256)` plus burn and native continuity resolves to
  `UNWRAP`
- generic vault/lending fallback remains unavailable once wrapper continuity is
  proven

### BE-05M — Across Bridge-Entry Semantic Parity Closeout

Purpose:
- Remove the residual bridge-entry misclassification where a recognized Across
  deposit still falls into `VAULT_DEPOSIT`.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- bridge classification tests

Implementation scope:
- give recognized bridge-entry semantics precedence for:
  - selector `0x7b939232 = depositV3(...)`
  - known Across SpokePool identity
  - outbound bridge-funding movement
- forbid generic `deposit` / `VAULT_DEPOSIT` fallback for this family

Definition of done:
- the confirmed live residual no longer resolves to `VAULT_DEPOSIT`
- bridge-entry rule remains raw-only and protocol-authoritative

Required fixtures:
- `0x8fc7da0a6aba524098b75fb9c1bfa651b4b50a90850832393c1313a745ac1e13`

Required tests:
- recognized Across `depositV3(...)` resolves to `BRIDGE_OUT`
- unknown deposit-family rows without bridge identity still follow existing
  generic routing

### BE-05N — Trader Joe LB Liquidity Family Correction

Purpose:
- Correct the confirmed Avalanche Trader Joe liquidity rows that are still
  mislabeled as lending deposits.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- liquidity-family tests

Implementation scope:
- recognize Trader Joe `LBRouter.addLiquidity(...)` as LP-entry semantics
- require the saved raw liquidity-funding pattern:
  - two outbound assets from the wallet
  - optional small refunds
- block `LENDING_DEPOSIT` fallback for this audited family

Definition of done:
- the audited Trader Joe rows resolve to `LP_ENTRY`
- no Trader Joe add-liquidity row remains `LENDING_DEPOSIT`

Required fixtures:
- `0x129822279a741ce22568a6bfbe3a4387cde3641bc107a83a11b0d6fa4911e0b5`
- `0x54a059e221fdbb4afc8c142706c6ecb39241bca714b03f5a9865dd3e7457317c`
- `0x351c025d7644e4e04cf8f75ee01deadc5907c742f29e75e78945d55efb78235e`
- `0xffd8c0b99555b0adafac163ef7465a0b0aa4cd50471f9cd0549ef7ab2df0e11e`
- `0xbf5347c9923b9f114d0d403396ea469cb441c96002ca1e8754208a46b5b25b90`

Required tests:
- audited Trader Joe add-liquidity row resolves to `LP_ENTRY`
- lending fallback still works for true lending deposit families

### BE-05O — Non-Economic Admin / No-Op Demotion From Economic LP / Vault Families

Purpose:
- Remove the confirmed fee-only admin/config rows from priceable economic
  families and narrow other audited fee-only cleanup cases away from economic
  output when movement evidence is absent.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- admin/no-op tests

Implementation scope:
- demote `setMinterApproval(address,bool)` to explicit admin/config handling
- audit and demote other fee-only resolved families when official semantics and
  saved evidence prove cleanup/admin behavior without asset movement
- keep economic LP/vault types unavailable for fee-only admin/config rows

Definition of done:
- `setMinterApproval(...)` no longer resolves to `LP_ENTRY`
- fee-only admin/config families are explicit and non-economic
- no demotion rule invents or destroys real movement evidence

Required fixtures:
- `0x1b330317bfa0f7d06b9de9b35d0f10c0d42bfaca462c9fba8c3a629ee4d5271f`
- `0xebed08badbae682446a078d78c9d43b0ff2b4aea6b48944b6c663a9e27e5bde7`
- `0x6f83d81c0fdb3e5bf839b0a3541e5578c7d300b0ce9a2d74207f2b9a0f94e4d4`
- `0x18eb2089dfbc17194cf37107ca0fbd0b6508a3073590fd356d4ffa2be663a9da`
- `0x6f50c885315d38a3e062b007a841d0fa77e6ed9127023e55c37d249bf523899c`

Required tests:
- approval/config family resolves to non-economic terminal type
- fee-only cleanup row cannot remain priceable economic output without
  supporting movement evidence

### BE-05P — Economic Evidence Gate Before Pricing

Purpose:
- Stop resolved-but-under-evidenced economic rows from entering pricing and
  later AVCO replay.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/onchain/**`
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- normalization / pricing-readiness tests

Implementation scope:
- define a structural gate for economic types:
  - require non-fee movement evidence in raw or persisted clarification evidence
  - treat fee-only rows as non-priceable unless their resolved type is explicit
    non-economic continuity/admin
- apply the gate before a row remains `PENDING_PRICE` or `CONFIRMED`
- demote under-evidenced rows back to clarification or review when the gate
  fails

Definition of done:
- the audited under-evidenced economic rows no longer remain price-ready
  `LP_ENTRY` / `VAULT_WITHDRAW`
- priceable rows have enough persisted movement evidence for later basis replay

Required fixtures:
- `0x0088de663d549fbc58dfa8dbba4180a346a580b1d6277254fa84a8ed9c27967a`
- `0xbea1ddd320653adc3ba0b122d623f21f3101b2c5c6b8d741ef392b3e03366690`
- `0xe2797d57f15c2c3cf8afc12382c294d59419797fee6f586ebeee8da7a2b36e41`
- `0x2d48bdb1a6aa2e248020806de49d7a32bf8bf1db24926c16fc9ef82da556d445`
- `0xe10f1066f25419378a4d559a59afa2e1dd23f0c361975fcac82dd0bdde64a24b`
- `0x39bb9bf778e42713c18fe2d12f9c692ed07dcd53317e00c2cb63b90a2ed46a83`
- `0x0c24997c61ef140fa5fdfdfaccbbc4da7ce658035a82981cfa3726f177970403`
- `0x8a12876e2fd89183c3d2378693a27e9de064811f752236cf26d33415f37419fe`
- `0x16af2f8aa057b07c98d0245e4952bf47cb03d8760453b117f60c94fe4527f0ff`
- `0xfdf0b2d28f8272c9f37eb825452de1232ded73ce52f5dfdb3a07d8bccec281ba`
- `0x52f759541668b75ee3aa2701707ad379c0db9f0616b18b39aa982e2e7bcea626`
- `0xea0023491e31e75de35ebe0fac43512581da03c9498b977307571feed3f6699a`
- `0x3415ccd28400e6eaf346a58f55d7a274f399f385aa47192daa45444c544099b6`
- `0xdaea615fbabe4b7ae0a85bac8bc2478166a746c11ee5511681f37ed7783a2ac7`

Required tests:
- fee-only economic row fails pricing-readiness gate
- row with persisted clarification movement evidence may pass the gate
- explicit non-economic row remains allowed without priceable movement

### BE-05Q — Clarification Evidence Persistence Live Parity

Purpose:
- Close the live mismatch where clarification attempts are recorded but
  `raw_transactions` still lack persisted clarification evidence.

Primary write scope:
- clarification persistence layer
- raw repository / mapping tests
- normalization / clarification integration tests

Implementation scope:
- ensure successful clarification writes adapted evidence and raw full receipt
  payload onto the raw row in the documented shape
- keep normalized counters and raw evidence persistence in sync
- prevent “attempt recorded, raw evidence absent” success paths

Definition of done:
- clarification attempts on normalized rows correspond to persisted
  clarification evidence on raw rows
- live rerun can prove clarification persistence from Mongo alone

Required tests:
- metadata-only clarification persists evidence on raw row
- full-receipt clarification persists adapted evidence plus raw full receipt
- failed clarification attempt does not masquerade as persisted success

### BE-05R — Pricing-Readiness Rerun Pack + Repeat-Audit Handoff

Purpose:
- Finish the post-clarification corrective slice with one rerun-ready pack that
  lets the next audit decide whether data is finally safe for pricing and AVCO.

Primary write scope:
- backend tests
- task/docs updates if needed

Implementation scope:
- run targeted regression coverage for:
  - wrap / unwrap continuity
  - Across bridge-entry parity
  - Trader Joe liquidity correction
  - admin / no-op demotion
  - pricing-readiness evidence gate
  - clarification evidence persistence parity
- document explicit rerun expectations for the next audit

Definition of done:
- rerun expectations are documented and test-backed
- the next audit can answer pricing-readiness from data instead of code
  guesswork
- acceptance gate for the next audit is explicit:
  - zero confirmed resolved wrapper / Across / Trader Joe / admin
    misclassifications
  - zero fee-only economic rows outside documented non-economic families
  - persisted clarification evidence exists on raw rows that actually used
    clarification

## Pricing-Readiness Residual Closeout — 2026-03-23 Run/2 Audit

This residual slice exists because the live run/2 Mongo audit proved that the
original `BE-05L` and `BE-05M` implementations were only partially successful.
The remaining blockers are narrow, but they still prevent pricing / AVCO
readiness because the affected rows are already resolved into priceable lanes.

Sources for this residual slice:

- `results/clarification/run/2/clarification-readiness-audit.md`
- `results/clarification/run/2/confirmed_resolved_misclassifications.tsv`
- `results/clarification/run/2/misclassification_flow_impact.json`
- `results/clarification/run/2/live_snapshot.json`

Execution order after run/2:

1. `BE-05S` wrapped-native residual live closeout (reopens `BE-05L`)
2. `BE-05T` Across `depositV3(...)` residual live closeout (reopens `BE-05M`)
3. `BE-05U` pricing-readiness rerun pack + repeat-audit handoff

### BE-05S — Wrapped-Native Residual Live Closeout (Reopens BE-05L)

Purpose:
- Eliminate the run/2 live residual where known wrapped-native selector rows
  still resolve to `VAULT_DEPOSIT`, `VAULT_WITHDRAW`, or `LENDING_WITHDRAW`.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- wrapped-native support helpers
- wrap / unwrap regression tests

Implementation scope:
- treat wrapped-native selector semantics as authoritative when both of the
  following are true:
  - selector is `0xd0e30db0 = deposit()` or `0x2e1a7d4d = withdraw(uint256)`
  - canonical raw or persisted clarification evidence proves wrapper identity
    plus the observable side of the 1:1 continuity
- allow deterministic counterpart-leg synthesis from canonical wrapper
  semantics when one side is missing but the other side is already proven in
  saved evidence
- do not require strong top-level `to` / `value` if the row already has:
  - known wrapped-native contract identity
  - selector continuity
  - wrapped mint/burn or native transfer continuity
- block registry or keyword fallback from winning once wrapper continuity is
  proven

Definition of done:
- run/2 residual counts are eliminated:
  - selector `0xd0e30db0` has no rows resolving to `VAULT_DEPOSIT`
  - selector `0x2e1a7d4d` has no rows resolving to `VAULT_WITHDRAW` or
    `LENDING_WITHDRAW`
- the audited live hashes below resolve to `WRAP` / `UNWRAP`
- no new backfill is required

Required fixtures:
- `0xa467cc0897b4f063b5e5914223e122ed6906ad6b3243261517cfd72504d695be`
- `0xb417ec3a2c7dbc4517dd14acae3c15e070bc99bd6401fecd5de645e6f32ba813`
- `0x11f7e79926cbb9eb85fbd39c171a561a69037a74e0ea87c499b2dc62a7dee958`
- `0x8d39e25b3122a24b8d47a477163c4f7f116c4d5e1e95b8f78e8f075807432e60`
- `0x26315fe4ce9e1da2694d790fc74142b5b6c0ee0b7e6630b8c2335fbfe95e5112`
- `0xab780159d54fc765e5749c204c0f8d564571cfb0fd547f10dca788a94c8aae78`

Required tests:
- wrapped-native `deposit()` with weak top-level `to` or `value` still resolves
  to `WRAP` when selector plus saved evidence proves canonical mint semantics
- wrapped-native `withdraw(uint256)` with weak top-level `to` still resolves to
  `UNWRAP` when selector plus saved evidence proves burn + native continuity
- once wrapper continuity is proven, generic `VAULT_*` / `LENDING_WITHDRAW`
  fallback cannot win

### BE-05T — Across `depositV3(...)` Residual Live Closeout (Reopens BE-05M)

Purpose:
- Eliminate the run/2 residual where one recognized Across `depositV3(...)`
  row still resolves to `VAULT_DEPOSIT`.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- bridge classification tests

Implementation scope:
- for selector `0x7b939232 = depositV3(...)`, accept either of these proof
  paths for `BRIDGE_OUT`:
  - canonical tx-level `to` is a known Across `SpokePool`
  - canonical saved outbound bridge-funding transfer targets a known Across
    `SpokePool`
- prevent generic `deposit` / `VAULT_DEPOSIT` fallback when method-aware bridge
  identity is proven through transfer evidence even if top-level `to` is weak
  or contaminated

Definition of done:
- `0x8fc7da0a6aba524098b75fb9c1bfa651b4b50a90850832393c1313a745ac1e13`
  resolves to `BRIDGE_OUT`
- run/2 residual count for recognized Across `depositV3(...) -> VAULT_DEPOSIT`
  becomes zero
- no new backfill is required

Required fixtures:
- `0x8fc7da0a6aba524098b75fb9c1bfa651b4b50a90850832393c1313a745ac1e13`

Required tests:
- recognized Across `depositV3(...)` resolves to `BRIDGE_OUT` from tx-level
  identity
- recognized Across `depositV3(...)` resolves to `BRIDGE_OUT` from persisted
  transfer-recipient identity when top-level `to` is weak
- unknown deposit-family rows without bridge identity still follow existing
  generic routing

### BE-05U — Pricing-Readiness Residual Rerun Pack + Repeat-Audit Handoff

Purpose:
- Finish the residual run/2 blocker slice and hand the next rerun to
  `financial-logic-auditor` with explicit live-data gates.

Primary write scope:
- backend tests
- task/docs updates if needed

Implementation scope:
- run targeted regression coverage for:
  - wrapped-native residual live fixtures
  - Across residual live fixture
- document rerun expectations for the next audit

Definition of done:
- the next audit gate is explicit and data-based:
  - zero resolved wrapped-native selector leaks into `VAULT_DEPOSIT`,
    `VAULT_WITHDRAW`, or `LENDING_WITHDRAW`
  - zero resolved recognized Across `depositV3(...)` leaks into
    `VAULT_DEPOSIT`
  - zero new under-evidenced economic rows are introduced
- rerun instructions remain `normalization + clarification` only; no backfill
  is required for this residual slice

## Pricing-Readiness Residual Closeout — 2026-03-23 Run/3 Audit

This residual slice starts after the run/3 Mongo audit proved that pricing is
still blocked by already-resolved semantic leaks. The main blockers are no
longer clarification persistence or generalized evidence gaps; they are live
misclassifications inside priceable or otherwise resolved lanes.

Sources for this residual slice:

- `results/clarification/run/3/clarification-readiness-audit.md`
- `results/clarification/run/3/confirmed_resolved_misclassifications.tsv`
- `results/clarification/run/3/resolved_but_insufficient_evidence.tsv`
- `results/clarification/run/3/misclassification_flow_impact.json`
- `results/clarification/run/3/remaining_review_tail.tsv`
- `results/clarification/run/3/audit_summary.json`

Primary official/market sources for rule authority:

- Plasma contracts:
  `https://plasma.to/docs/plasma-chain/network-information/plasma-contracts`
- LI.FI smart-contract architecture:
  `https://docs.li.fi/smart-contracts/overview`
- LI.FI route execution / intents docs:
  `https://docs.li.fi/integrate-li.fi-sdk/execute-routes-quotes`
- Hyperlane Warp Route interface:
  `https://docs.hyperlane.xyz/docs/applications/warp-routes/interface`
- Hyperlane Warp Route types:
  `https://docs.hyperlane.xyz/docs/protocol/warp-routes/warp-routes-types`
- GMX `ExchangeRouter.sol`:
  `https://raw.githubusercontent.com/gmx-io/gmx-synthetics/main/contracts/router/ExchangeRouter.sol`
- Pancake `MasterChefV3.sol`:
  `https://raw.githubusercontent.com/pancakeswap/pancake-v3-contracts/main/projects/masterchef-v3/contracts/MasterChefV3.sol`
- OpenZeppelin `VestingWallet.sol`:
  `https://raw.githubusercontent.com/OpenZeppelin/openzeppelin-contracts/master/contracts/finance/VestingWallet.sol`
- Uniswap `NonfungiblePositionManager.sol`:
  `https://raw.githubusercontent.com/Uniswap/v3-periphery/main/contracts/NonfungiblePositionManager.sol`
- Pancake Infinity `CLPositionManager.sol`:
  `https://raw.githubusercontent.com/pancakeswap/infinity-periphery/main/src/pool-cl/CLPositionManager.sol`
- Pendle `ActionAddRemoveLiqV3.sol`:
  `https://raw.githubusercontent.com/pendle-finance/pendle-core-v2-public/main/contracts/router/ActionAddRemoveLiqV3.sol`

Execution order after run/3:

1. `BE-05V` Plasma wrapped-native residual closeout (reopens `BE-05S` / `BE-05L`)
2. `BE-05W` LI.FI / Jumper bridge-route semantic closeout
3. `BE-05X` `transferRemote(...)` bridge-initiation semantic closeout
4. `BE-05Y` GMX order-initiation demotion from priceable `EXTERNAL_TRANSFER_OUT`
5. `BE-05Z` claim-income semantic closeout
6. `BE-05AA` residual warning-family triage
7. `BE-05AB` clarification telemetry parity closeout
8. `BE-05AC` pricing-readiness rerun pack + repeat-audit handoff

Run/3 outcome:

- `BE-05T` looks materially closed in live data; recognized Across
  `depositV3(...)` is no longer the dominant blocker.
- `BE-05S` remains partially open because Plasma `WXPL9 withdraw(uint256)`
  still leaks into `VAULT_WITHDRAW`.
- pricing remains blocked by new live confirmed clusters:
  - Plasma wrapped-native residuals
  - LI.FI / Jumper bridge-initiation rows leaking into
    `EXTERNAL_TRANSFER_OUT`
  - `transferRemote(...)` rows leaking into `EXTERNAL_TRANSFER_OUT`
  - GMX `createOrder(...)` leaking into priceable `EXTERNAL_TRANSFER_OUT`
  - `harvest(...)` and `release()` leaking into `EXTERNAL_INBOUND`

### BE-05V — Plasma Wrapped-Native Residual Closeout (Reopens BE-05S / BE-05L)

Purpose:
- Eliminate the last confirmed wrapped-native residuals on `PLASMA`, where
  `WXPL9 withdraw(uint256)` still resolves to `VAULT_WITHDRAW` instead of
  `UNWRAP`.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- wrapped-native support helpers
- wrap / unwrap regression tests

Implementation scope:
- treat `PLASMA` `WXPL9` identity from the official contracts registry as
  canonical wrapper identity
- keep selector semantics authoritative for:
  - `0xd0e30db0 = deposit()`
  - `0x2e1a7d4d = withdraw(uint256)`
- close rows when current raw or persisted clarification evidence proves:
  - wrapper identity
  - canonical mint / burn side
  - native continuity side
- forbid `VAULT_WITHDRAW` fallback once wrapper semantics are proven

Definition of done:
- `0xf8a6779b93a821950e49ac560fac94214b0f5bb8c650ced9d9d98e937d527450`
  resolves to `UNWRAP`
- `0xab780159d54fc765e5749c204c0f8d564571cfb0fd547f10dca788a94c8aae78`
  resolves to `UNWRAP`
- run/3 residual `PLASMA` wrapped-native count becomes zero

Required fixtures:
- `0xf8a6779b93a821950e49ac560fac94214b0f5bb8c650ced9d9d98e937d527450`
- `0xab780159d54fc765e5749c204c0f8d564571cfb0fd547f10dca788a94c8aae78`

Required tests:
- official Plasma `WXPL9 withdraw(uint256)` resolves to `UNWRAP`
- generic vault fallback remains unavailable once wrapper continuity is proven

### BE-05W — LI.FI / Jumper Bridge-Route Semantic Closeout

Purpose:
- Remove the confirmed bridge-initiation rows that currently leak into
  `EXTERNAL_TRANSFER_OUT` even though current raw already proves LI.FI / Jumper
  route identity plus source-side funding movement.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- bridge/router classification helpers
- bridge-route regression tests

Implementation scope:
- recognize LI.FI / Jumper initiation families:
  - `callDiamondWithEIP2612Signature(...)`
  - `callDiamondWithPermit2(...)`
- use only saved raw / clarification evidence:
  - selector
  - known route-tag strings in calldata
  - source-side outbound funding movement
- normalize these rows to at least `BRIDGE_OUT`
- do not rely on explorer UI route labels as runtime evidence

Definition of done:
- run/3 confirmed LI.FI / Jumper residual rows no longer resolve to
  `EXTERNAL_TRANSFER_OUT`
- route-tagged bridge initiations normalize to bridge continuity semantics
  without new backfill

Required fixtures:
- `0xd7832186ea268ec19e4ebf263e372438bd8d87dafda1e4dfcafb27eb68250309`
- `0x6e047abf4d509dfe8346bf7bc5d439b21cee4fe9b1ab938b62328c4de6951e92`
- `0xccf7a88df410d47890eebda5f373c3875fe48f77a1229fac058c33637453f3bf`
- `0x9a617889dccecb456b940f1230bae59660e94d525408a36477396d21cf3f993c`
- `0xaa3d124a27a07ae43141e4db03c35bbe66eb419509ff28420e3aff2557a9d499`
- `0x93f9d84635fa12506ffee1e145197452f22670008c7c574708928b8a88bbc2a8`
- `0x42ab47215cb6f87e90fe27f3e1ed70808b8257972b82182b5f1c5e0e924754c4`
- `0x425b32c1521f2b623c70645ec2fc1fd615f2cab49d7c2b37f250036dd32fcbb7`
- `0xe1912c5f17b780faa5722005cc06a796426a156abe175bd5f5b49d21812626ca`

Required tests:
- LI.FI / Jumper bridge-route row resolves to `BRIDGE_OUT`
- route-tagged bridge-initiation family does not collapse to
  `EXTERNAL_TRANSFER_OUT`
- unknown outbound transfer without bridge-route identity still follows
  existing generic routing

### BE-05X — `transferRemote(...)` Bridge-Initiation Semantic Closeout

Purpose:
- Close the confirmed cross-chain send family where `transferRemote(...)`
  currently resolves to `EXTERNAL_TRANSFER_OUT` instead of bridge-initiation
  continuity semantics.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- bridge-route tests

Implementation scope:
- recognize `transferRemote(uint32,bytes32,uint256)` as a bridge-initiation
  family when current raw proves:
  - token send into the router / bridge adapter
  - native fee / gas payment for the message
- align semantics with official Hyperlane Warp Route `TokenRouter`
  documentation and verified contract behavior
- normalize to `BRIDGE_OUT`

Definition of done:
- run/3 confirmed `transferRemote(...)` residual rows no longer resolve to
  `EXTERNAL_TRANSFER_OUT`
- bridge-initiation semantics are derived from saved evidence only

Required fixtures:
- `0xb1e9f65dd3492dc36db56354ba5f12a6772ed5cd5f546b4d095a48af8a741f62`
- `0x4a2eb3ee44ab87cbdfe4a6dc59e9733e5541b4bc41ef796e78693d746ba9fb6a`
- `0x5ca14340e17ce74a5bcdbef9fd1b72756f90ec41ee6394abb3dd8d6aff73d1fa`
- `0x87967a7a55ee3e20e0ea99f95dfe37ea0789def007a8c033f6d091d8e84f6924`
- `0x360988904f01f54f3cdbf38651e51ec9cd05633eb3f866ad284909e625c358ae`

Required tests:
- `transferRemote(...)` with token send + native message fee resolves to
  `BRIDGE_OUT`
- token send without bridge-route identity still follows existing generic
  routing

### BE-05Y — GMX Order-Initiation Demotion From Priceable `EXTERNAL_TRANSFER_OUT`

Purpose:
- Remove the confirmed GMX order-initiation leak where a pending order request
  currently enters pricing as if it were a finalized disposal.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- GMX special-handler tests

Implementation scope:
- align with official `ExchangeRouter.createOrder(...)` semantics:
  collateral transfer plus order creation is not final settlement
- demote `createOrder(tuple order)` from priceable
  `EXTERNAL_TRANSFER_OUT` into explicit non-priceable pending-order or review
  semantics unless saved evidence proves final settlement
- keep the rule raw-only

Definition of done:
- `0xb6b143aeeb3bada8c75347c49a7d8c5d3a2830a1f0da5b2e49a44a87a23c5105`
  no longer remains priceable `EXTERNAL_TRANSFER_OUT`
- pricing-readiness gate rejects pending-order initiation rows

Required fixtures:
- `0xb6b143aeeb3bada8c75347c49a7d8c5d3a2830a1f0da5b2e49a44a87a23c5105`

Required tests:
- GMX `createOrder(...)` no longer enters pricing as finalized disposal
- final settlement families remain unaffected

### BE-05Z — Claim-Income Semantic Closeout

Purpose:
- Remove the confirmed claim-income leaks where known reward / vesting payout
  families still resolve to generic `EXTERNAL_INBOUND`.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- claim / income regression tests
- registry entries if needed

Implementation scope:
- recognize Pancake `harvest(uint256,address)` as `REWARD_CLAIM` when saved raw
  proves claim-family identity plus inbound reward movement
- recognize vesting `release()` as explicit claim / income semantics when saved
  raw proves payout into the tracked wallet
- do not use explorer page summaries as runtime evidence

Definition of done:
- audited `harvest(...)` rows no longer resolve to `EXTERNAL_INBOUND`
- audited `release()` rows no longer resolve to `EXTERNAL_INBOUND`
- claim-income semantics remain wallet-scoped and evidence-backed

Required fixtures:
- `0x49c61e3e091fc071d634c2f0236340fa22756c4d6842ae8d24c6eed4412fdee9`
- `0xa17d86c2ddcb839a3c2872133f9ba6d448e86f2bb9d64eef91f6750e59c3f0f8`
- `0x61941de61deb7661e593e63ef2ba3e4da6ecb04eb22582b3d82bc930eecfd762`
- `0xf6a0536a509a3af49259c15dded668dfefafcee639fa491e1c946ab26d2b5b1b`
- `0xf124518909bf26468702ac7c8120415ed3b920fa5d6859727ca5c2e1e67e80e3`
- `0xfc6b315f7ae67ac96218afe923bc9ea27eadc4c7ec5fd3b50d9c3305326578dc`

Required tests:
- Pancake `harvest(...)` resolves to `REWARD_CLAIM`
- vesting `release()` resolves to explicit claim / income semantics
- generic inbound still applies to unknown inbound rows without claim evidence

### BE-05AA — Residual Warning-Family Triage

Purpose:
- Decide the two audited warning families narrowly and explicitly instead of
  leaving them as silent generic outcomes or broad speculative fixes.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- warning-family tests
- task/docs notes if final state is still review

Implementation scope:
- audit and either close or intentionally narrow:
  - `claim(address LBHooks,uint256[] ids)`
    `0x0bef999ae7808d602a1660c495b115e0d6e0c69a478b0492edd20ad3b00df45d`
  - `executeOrder(bytes32 key,tuple oracleParams)`
    `0x53bbb5b41325b3a043e9a9f16a6da4ab4624f0e7bbbf80fe8037446c4c2879e8`
- if evidence remains insufficient, keep explicit review with a narrow reason
  instead of generic fallback

Definition of done:
- both warning families are either deterministically classified or explicitly
  locked to narrow review semantics
- no broad heuristic is introduced from one-off audit guesses

Required tests:
- audited warning family either closes deterministically or remains explicit
  narrow review

### BE-05AB — Clarification Telemetry Parity Closeout

Purpose:
- Close the live observability warning where persisted clarification evidence is
  present on raw rows but normalized clarification attempt counters stay zero.

Primary write scope:
- clarification persistence / normalization mapping
- telemetry tests

Implementation scope:
- keep normalized counters consistent with persisted clarification evidence
- when parity cannot be established, emit explicit telemetry warning instead of
  silently presenting zero-attempt rows
- preserve current runtime semantics; this is observability parity work, not a
  pricing-semantic workaround

Definition of done:
- raw clarification evidence and normalized clarification counters stay in sync
  after rerun
- telemetry mismatch becomes an explicit, test-backed warning state rather than
  silent drift

Required tests:
- persisted clarification evidence increments the corresponding normalized
  attempt counters
- mismatch path is surfaced explicitly when persistence and counters diverge

### BE-05AC — Pricing-Readiness Rerun Pack + Repeat-Audit Handoff

Purpose:
- Finish the run/3 residual slice with one rerun-ready pack and explicit
  live-data gate for the next audit.

Primary write scope:
- backend tests
- task/docs updates if needed

Implementation scope:
- run targeted regression coverage for:
  - Plasma wrapped-native residuals
  - LI.FI / Jumper bridge-initiation rows
  - `transferRemote(...)` bridge-initiation rows
  - GMX order-initiation demotion
  - claim-income semantic closeout
  - warning-family triage
  - clarification telemetry parity
- document rerun expectations for the next audit

Definition of done:
- the next audit gate is explicit and data-based:
  - zero resolved wrapped-native leaks into `VAULT_DEPOSIT`,
    `VAULT_WITHDRAW`, or `LENDING_WITHDRAW`
  - zero resolved route-tagged bridge-initiation leaks into
    `EXTERNAL_TRANSFER_OUT`
  - zero resolved claim-income leaks into `EXTERNAL_INBOUND`
  - zero priceable GMX `createOrder(...)` rows without finalized settlement
    semantics
  - zero new under-evidenced economic rows
  - clarification telemetry parity is live and observable
- rerun instructions remain `normalization + clarification` only; no backfill
  is required for this residual slice

## Post-Clarification Pricing-Readiness Closeout — 2026-03-23 Run 4 Audit

Primary sources used for this slice:

- LI.FI user flows / route semantics:
  `https://docs.li.fi/introduction/user-flows-and-examples/difference-between-quote-and-route`
- LI.FI route transfer example:
  `https://docs.li.fi/li.fi-api/li.fi-api/transferring-tokens-example`
- Circle CCTP fees and message flow:
  `https://developers.circle.com/cctp/concepts/fees`
- Circle CCTP on HyperCore / destination-side redemption:
  `https://developers.circle.com/cctp/cctp-on-hypercore`
- Lagoon deposit / redeem flow:
  `https://docs.lagoon.finance/developer-hub/integration/deposit-flow`
- Lagoon vault controls:
  `https://docs.lagoon.finance/vault/how-to/pause-a-vault`
- LayerZero OFT overview:
  `https://docs.layerzero.network/v2/developers/starknet/oft/overview`
- GMX synthetics contracts:
  `https://github.com/gmx-io/gmx-synthetics`
- Uniswap Merkle Distributor reference:
  `https://github.com/Uniswap/merkle-distributor`

Execution order after run/4:

1. `BE-05AD` residual LI.FI / Jumper selector-recovery route closeout
2. `BE-05AE` Circle CCTP destination-side redeem bridge-in closeout
3. `BE-05AF` explicit claim-family reward closeout
4. `BE-05AG` pending redeem-request demotion from priceable `EXTERNAL_TRANSFER_OUT`
5. `BE-05AH` resolved warning-family triage
6. `BE-05AI` pricing-readiness rerun pack + repeat-audit handoff

Run/4 outcome:

- `BE-05V`, `BE-05X`, `BE-05Y`, `BE-05Z`, and `BE-05AB` look materially
  closed in live Mongo data.
- pricing remains blocked by four confirmed residual clusters:
  - LI.FI / Jumper bridge-route rows leaking into `EXTERNAL_TRANSFER_OUT`
  - Circle CCTP `redeem(...)` rows leaking into `VAULT_WITHDRAW`
  - explicit receiver-wallet claim payout rows leaking into
    `EXTERNAL_INBOUND`
  - pending `claimSharesAndRequestRedeem(...)` rows leaking into priceable
    `EXTERNAL_TRANSFER_OUT`

### BE-05AD — Residual LI.FI / Jumper Selector-Recovery Route Closeout

Purpose:
- Close the seven remaining LI.FI / Jumper bridge-initiation rows that still
  resolve to `EXTERNAL_TRANSFER_OUT` because bridge identity is present in
  saved calldata but does not survive through top-level method fields.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- selector-recovery helpers
- bridge-route regression tests

Implementation scope:
- recover selector from `rawData.input` when top-level `rawData.methodId` is
  blank or `0x`
- combine recovered selector with persisted LI.FI / Jumper route tags such as:
  - `jumper.exchange`
  - `relay`
  - `across`
  - `gasZipBridge`
- require source-side outbound funding movement
- normalize to at least `BRIDGE_OUT`
- keep the rule raw-only; explorer UI route labels remain non-runtime evidence

Definition of done:
- the following rows no longer resolve to `EXTERNAL_TRANSFER_OUT`:
  - `0x927cfa4d452608316410120af05d8b09c2f4d8d9cec5f9273457b7d8c3e47757`
  - `0xa218071766d181cdbf9349364e00edbca3ecda12a0dc615a2c3a5eb2180a3c38`
  - `0x4bd7b04bc2864b0012f19300690ae5cacb2806fdcc0b1612664d98b5015b48f6`
  - `0x122fa9578beecb57f21bc0f65e8c1fa531e88d1809381aec4dc8c24a3495859f`
  - `0x559460094fe1cfbcf37bb1fb4961f49809bb0f53a0787d02e0baedacec59f511`
  - `0xb9ad84bba02b46c1b0bf2f01d1a05f98d4c886bae36c5487411f80892f3f894a`
  - `0x4ca0b79ea7f374c8f90e4c13fc9da43a668f1d8352ae99b1d5a84ef4056ab4fb`
- bridge-route rows remain wallet-scoped and no new backfill is required

Required tests:
- recovered selector + LI.FI / Jumper route tags + outbound source funding ->
  `BRIDGE_OUT`
- blank-method outbound transfer without route identity still follows existing
  generic routing

### BE-05AE — Circle CCTP Destination-Side Redeem Bridge-In Closeout

Purpose:
- Close the destination-side CCTP redeem rows that still resolve to
  `VAULT_WITHDRAW` even though current raw plus clarification evidence already
  proves bridged payout into the tracked wallet.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- bridge-settlement helpers
- CCTP regression tests

Implementation scope:
- recognize `redeem(bytes cctpMsg,bytes cctpSigs)` as a destination-side bridge
  completion family when persisted evidence shows inbound bridged payout
- use official CCTP redeem semantics plus current raw / clarification evidence
  only
- normalize to `BRIDGE_IN`

Definition of done:
- the following rows no longer resolve to `VAULT_WITHDRAW`:
  - `0xe11ab43689786a2b518b8a058593926071a8cac4c99b02077c4ac82d6ac0848e`
  - `0x7970e3466ea26b51992ced5c2c2c352c82475dda75e45b7fa7543a92c3fe2cc0`
  - `0xd410b4e727a8eaaac5f9c7d2d109e31ed5ae1505f5fd0308ebeb5a7dfe217b8f`
- destination-side CCTP bridge completion becomes basis-carry continuity, not
  vault withdrawal fallback

Required tests:
- `redeem(bytes cctpMsg,bytes cctpSigs)` + inbound bridged `USDC` ->
  `BRIDGE_IN`
- generic vault withdrawal without bridge evidence remains unaffected

### BE-05AF — Explicit Claim-Family Reward Closeout

Purpose:
- Promote the remaining explicit receiver-wallet payout rows from generic
  `EXTERNAL_INBOUND` to `REWARD_CLAIM` when current raw already proves
  claim-family identity and payout into the tracked wallet.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- claim-family helpers / registry
- reward regression tests

Implementation scope:
- close only deterministic receiver-wallet payout families:
  - merkle `claim(...)`
  - signed `claimWithSig(...)`
- require both claim-family identity and persisted payout into the tracked
  wallet
- do not auto-promote suspicious claim-like families that still lack clean
  protocol identity

Definition of done:
- the following rows no longer resolve to `EXTERNAL_INBOUND`:
  - `0xa4bc20b7671f1f47fb98376158a26f56e3b4d20269425f2395e4a93a7713c89d`
  - `0xf64da0b9d9aaf751fd0392063e47876d643b1055ac0073012d16a381c3ee062e`
  - `0xf93ffc67e079e96f9971432f987699cf507ba26ad5ccbdb68deebd16208f880b`
  - `0xbbef6f2b95cba300475b1a748ead1715b84699499dec6fa7d1245a427031ebdf`
- promotion remains wallet-scoped and evidence-backed

Required tests:
- `claim(...)` + inbound payout -> `REWARD_CLAIM`
- `claimWithSig(...)` + inbound payout -> `REWARD_CLAIM`
- suspicious claim-like inbound without clean protocol identity still stays out
  of automatic reward promotion

### BE-05AG — Pending Redeem-Request Demotion From Priceable `EXTERNAL_TRANSFER_OUT`

Purpose:
- Keep request-initiation rows out of pricing until later settlement or cancel
  evidence exists.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- pending-request tests

Implementation scope:
- recognize `claimSharesAndRequestRedeem(uint256 sharesToRedeem)` as a
  request-initiation family
- demote it from priceable `EXTERNAL_TRANSFER_OUT`
- use explicit non-priceable pending-redeem/request-initiation semantics or
  narrow review if a dedicated type is not yet available

Definition of done:
- `0xd4b8de8881f203bfe3ecca7c8cc4d47113b91f1029f9bb3e9af2c883fcb04aaa`
  no longer remains priceable `EXTERNAL_TRANSFER_OUT`
- pricing gate blocks request-initiation rows until later settlement evidence
  exists

Required tests:
- `claimSharesAndRequestRedeem(...)` without settlement -> non-priceable
- final settlement families remain unaffected

### BE-05AH — Resolved Warning-Family Triage

Purpose:
- Decide the two remaining resolved warning families narrowly and explicitly
  instead of leaving them as silent generic resolved fallbacks.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- warning-family tests

Implementation scope:
- audit and either narrow or intentionally review-lock:
  - `0xe93b81f8b417e1274094313ecd7e88ecc750528a39128b935fecb48a4254ebc0`
  - `0xee4a6a69a970c3baf97f16760d597b25b9c3d42a55f7e08d41129076582d411c`
- do not introduce broad heuristics from one-off audit guesses

Definition of done:
- both warning families are either deterministically classified or explicitly
  kept in narrow review semantics
- no generic resolved fallback remains for these two rows

Required tests:
- audited warning family either closes deterministically or stays explicit
  narrow review

### BE-05AI — Pricing-Readiness Rerun Pack + Repeat-Audit Handoff

Purpose:
- Finish the run/4 residual slice with one rerun-ready pack and an explicit
  live-data gate for the next audit.

Primary write scope:
- backend tests
- task/docs updates if needed

Implementation scope:
- run targeted regression coverage for:
  - LI.FI / Jumper selector-recovery bridge-route rows
  - Circle CCTP destination-side redeem rows
  - explicit receiver-wallet claim payout rows
  - pending redeem-request initiation demotion
  - warning-family triage
- document rerun expectations for the next audit

Definition of done:
- the next audit gate is explicit and data-based:
  - zero resolved LI.FI / Jumper route leaks into `EXTERNAL_TRANSFER_OUT`
  - zero resolved Circle CCTP `redeem(...)` leaks into `VAULT_WITHDRAW`
  - zero resolved explicit receiver-wallet claim payout leaks into
    `EXTERNAL_INBOUND`
  - zero resolved pending redeem-request initiation leaks into priceable
    `EXTERNAL_TRANSFER_OUT`
  - previously closed families remain closed
  - no new under-evidenced economic rows appear
- rerun instructions remain `normalization + clarification` only; no backfill
  is required for this residual slice

## Run/6 Review-Tail Reduction Slice — 2026-03-23 Offline Planning And Simulation

This slice consumes the run/5 tail files and the run/6 offline rule-planning
package. It is intentionally designed to avoid repeated trial-and-error reruns.
The expected implementation order below is the last broad review-tail reduction
pack before the remaining review rows become an explicit stop-condition.

Sources for this slice:

- `results/clarification/run/5/remaining_review_tail.tsv`
- `results/clarification/run/5/resolved_but_insufficient_evidence.tsv`
- `results/clarification/run/5/clarification_persistence_mismatches.tsv`
- `results/clarification/run/6/review-tail-reduction-plan.md`
- `results/clarification/run/6/review_tail_rule_recommendations.tsv`
- `results/clarification/run/6/review_tail_row_actions.tsv`
- `results/clarification/run/6/review_tail_simulation.json`
- `results/clarification/run/6/clarification_persistence_simulation.json`
- `results/clarification/run/6/review_tail_irreducible.tsv`

Primary protocol / market references for this slice:

- Aave credit delegation docs
- Safe smart-account docs
- Velodrome Slipstream repository
- Uniswap V3 `NonfungiblePositionManager`
- Euler documentation / repositories
- GMX repository
- LI.FI route / flow documentation
- Lagoon redeem-request documentation
- CoW `ethflowcontract` repository

Execution order after run/6:

1. `BE-05AJ` terminal demotion for safe current-raw review families
2. `BE-05AK` clarification persistence and raw replay parity closeout
3. `BE-05AL` same-source internal transfer persistence for native bridge clarification
4. `BE-05AM` clarification closeout for Slipstream / CL lifecycle residuals
5. `BE-05AN` clarification closeout for Euler batch residuals
6. `BE-05AO` clarification closeout for ParaSwap / GMX / Katana residuals
7. `BE-05AP` irreducible stop-condition lock
8. `BE-05AQ` run/6 rerun pack + repeat-audit handoff

Run/6 planning verdict:

- no new backfill is required
- projected review-tail reduction:
  - `186 -> 22` from current-raw rules alone
  - `22 -> 4` from clarification closeouts
- projected clarification persistence mismatches: `294 -> 0`

### BE-05AJ — Terminal Demotion For Safe Current-Raw Review Families

Purpose:
- Remove the large current-raw review families that are already semantically
  understood and should not wait for clarification.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- rule/terminal-state tests

Implementation scope:
- move the following families out of `NEEDS_REVIEW` without clarification:
  - spam / airdrop clusters
  - explicit `CLAIM_WITHOUT_MOVEMENT`
  - failed transactions
  - documented admin / governance rows
  - documented pending-request / pending-order initiation rows
  - out-of-scope NFT / attestation mint rows
- preferred target is an explicit terminal non-priceable lane
- minimal safe fallback is a narrow non-priceable resolved state with the
  original warning reason preserved

Representative fixtures:
- spam / airdrop:
  - `0x1c0c7306d7e0bcab64d79197c83f46099c629560c386e17380e879f41881f5db`
  - `0x25c7bc51c435aa3c6cd61d484417e39e732a3647923ddc882d36b4e59d656fc4`
- claim-without-movement:
  - `0xf13356fe9449ec9e831395e0074622e88e362a8f317e6b110d093bfaa25d2702`
  - `0xa586770c653097bd905f0003edddcc59f295a8a64131a3ba0410d6fe43bb08e5`
- admin / governance:
  - `0xb6b3ac27afac284fcd0ef13463719c34925dd37cfcfb52dcb2cc894a5271d566`
  - `0x19e0f3b3c8d325e80000df1f95efbe65536ee060a8623e1d777f16cab5307caa`
  - `0x9867f9d202764ad9d019b0f89cb4b35e96cbc35bd5ac2fabea1edf5c7412bdf2`
  - `0xa382a8738fb0a8a66074f1d9259475537dd38869bb07664b9278d6a2974124db`
  - `0xcf10c62202254f334b8a7d8b37351f061de90eee142749a4fed1686532021fa5`
  - `0x3a00fee6baf13bddd3532d72e093e3e60972c4c1a7eefd1ca05d4f50c4934b5e`
  - `0x0c0e3f778debe41826e28a2ad615f15cb71f379396a4b63f5add20b118670ec1`
- pending-request / pending-order:
  - `0xd4b8de8881f203bfe3ecca7c8cc4d47113b91f1029f9bb3e9af2c883fcb04aaa`
  - `0xb6b143aeeb3bada8c75347c49a7d8c5d3a2830a1f0da5b2e49a44a87a23c5105`
- out-of-scope NFT / attestation:
  - `0x3415ccd28400e6eaf346a58f55d7a274f399f385aa47192daa45444c544099b6`
  - `0xdaea615fbabe4b7ae0a85bac8bc2478166a746c11ee5511681f37ed7783a2ac7`
  - `0x6f83d81c0fdb3e5bf839b0a3541e5578c7d300b0ce9a2d74207f2b9a0f94e4d4`

Definition of done:
- the run/5 safe current-raw families no longer remain in `NEEDS_REVIEW`
- none of these rows stay priceable
- no clarification fetch is required to close them

Required tests:
- spam / airdrop -> explicit non-priceable terminal lane
- claim-without-movement -> explicit terminal no-movement lane
- failed transaction -> terminal failed lane
- admin / governance / pending-request / pending-order -> explicit non-priceable lane
- out-of-scope NFT / attestation -> explicit non-priceable out-of-scope lane

### BE-05AK — Clarification Persistence And Raw Replay Parity Closeout

Purpose:
- Eliminate the run/5 `294` clarification persistence mismatches and make
  clarification replay-safe from raw state alone.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/job/clarification/**`
- `backend/src/main/java/com/walletradar/ingestion/pipeline/onchain/**`
- `backend/src/main/java/com/walletradar/ingestion/store/**`
- clarification persistence tests

Implementation scope:
- every clarification path that increments:
  - `clarificationAttempts`
  - `fullReceiptClarificationAttempts`
  must persist the supporting evidence on the raw row first
- normalized clarification counters must be derived from persisted raw
  clarification state, not from transient in-memory flow only
- persist:
  - adapted clarification evidence
  - raw full receipt payload when available
  - same-source provenance

Definition of done:
- live audit can show `clarification_persistence_mismatches = 0`
- raw and normalized clarification state remain replay-safe after rerun

Required tests:
- clarification counter increment without raw persistence is impossible
- persisted clarification evidence survives rerun replay
- normalized counters reflect persisted raw clarification state

### BE-05AL — Same-Source Internal Transfer Persistence For Native Bridge Clarification

Purpose:
- Support the allowlisted bridge families that require same-source internal
  transfers in addition to receipt logs for deterministic closure.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/job/clarification/**`
- `backend/src/main/java/com/walletradar/ingestion/pipeline/onchain/**`
- bridge clarification tests

Implementation scope:
- allow clarification to persist same-source internal transfers when:
  - the family is explicitly allowlisted
  - the source family that produced the raw row can expose those internal legs
  - the internal transfers are required to reconstruct native bridge continuity
- expose those persisted internal transfers through the raw view only
- do not introduce cross-source default fallback

Representative fixture:
- `0x1232a2724f8d2c2e0aa436192b31298ef3351b74bf319c347b9ff569830e7a03`

Definition of done:
- allowlisted native bridge rows can close from clarification without backfill
- the persisted internal-transfer shape is deterministic and lineage-safe

Required tests:
- same-source explorer-backed clarification persists internal transfers
- non-allowlisted row cannot start persisting internal transfers
- classification consumes those internal legs only through the raw view

### BE-05AM — Clarification Closeout For Slipstream / CL Lifecycle Residuals

Purpose:
- Use full-receipt clarification to close the remaining concentrated-liquidity
  lifecycle rows that are receipt-rich but under-evidenced in current raw.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/job/clarification/**`
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- CL lifecycle clarification tests

Implementation scope:
- allowlist and close the following families from persisted clarification
  evidence:
  - Slipstream cleanup / burn family -> explicit non-economic LP cleanup lane:
    - `0x907207001069b6c5b1c0f9aa740736a81ed0f7e8c02b2735a31c772d5bb6603e`
    - `0x91bba2c00fc37a862f2c277e6f8378bf682156425919c66c1b37faa50e9d61b7`
    - `0x927d3f458ada7e5ec67f77129e29edcaf2f69bd2b81490a42fec17c0cc3bd4fa`
    - `0x9c3a93479dd926c7a6e57395b14ab48ed73e673f5cb25f6c1ae6ac9b1bbf2c19`
    - `0xaf00ee8ac5154daa5f4f917d0929ddbacfb1d254ae3b228f3322312a39c798c8`
    - `0xe1bc445ff05954e4d9211570bdaed633b0ddddc70ee36d043574d5b9dd1b9630`
  - Slipstream stake-contract family -> `LP_POSITION_STAKE`:
    - `0x74abf9296937242aab88b493a37072458f003c50be937ac1670299e3aad6053e`
    - `0x83978f62a0f05b662a87210263e923ad568d616f5dd8c420d0485e1e21828a61`
  - zero-effect collect family -> explicit no-op collect lane:
    - `0x4673757b36119b4632f798ad4e0d72fbd170ee0b7be4e4901bd1155ab3881775`
  - Pancake / Infinity CL exit / modify-liquidity families:
    - `0x0a757aeeb58667c545017cd8e5cd60dc994a8945ed810c60ea2aed18688f4f7a`
    - `0x0088de663d549fbc58dfa8dbba4180a346a580b1d6277254fa84a8ed9c27967a`

Definition of done:
- these rows no longer remain in review when persisted clarification evidence
  is sufficient
- burn-only / cleanup-only rows do not become synthetic economic exits
- zero-effect collect does not stay `LP_FEE_CLAIM / NEEDS_REVIEW`

Required tests:
- Slipstream cleanup family -> explicit non-economic cleanup
- Slipstream stake family -> `LP_POSITION_STAKE`
- zero-effect collect -> explicit no-op collect lane
- Pancake / Infinity exit / modify-liquidity -> deterministic LP lifecycle type

### BE-05AN — Clarification Closeout For Euler Batch Residuals

Purpose:
- Close the receipt-log-rich Euler-style batch rows while preserving the honest
  wrapper-only stop-condition.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/job/clarification/**`
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- Euler clarification tests

Implementation scope:
- allowlist selector `0xc16ae7a4`
- close only when persisted clarification logs reveal enough transfer/event
  evidence to derive deterministic lending / collateral semantics
- keep wrapper-only cases explicit review

Closeable fixtures:
- `0x1e0c429514e9cf892b0b6a11e3cfb290eff5c0c26a557c835496e4ba61717fdb`
- `0x233c2b959739d298d1012405e9b3d7e535a87d590a81bcb304c6dc0cb3ce5e4f`
- `0x305f37a69956a13001962216c845385996114876173bdbaef644bbe3baadf5df`

Intentional stop-condition inside the family:
- `0x509c134b2795de71a1ee42db38b53af78003308e8c9ebf2b1bfa9ce8d348dcd2`

Definition of done:
- the three receipt-log-rich Euler rows close deterministically
- the wrapper-only row remains explicit review

Required tests:
- receipt-log-rich Euler batch closes
- wrapper-only Euler batch stays review

### BE-05AO — Clarification Closeout For ParaSwap / GMX / Katana Residuals

Purpose:
- Close the heterogeneous receipt-log-rich residuals that are already
  validated by official protocol semantics and explorer-compatible evidence.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/job/clarification/**`
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- settlement / router clarification tests

Implementation scope:
- close:
  - ParaSwap `swapExactAmountOut(...)`:
    - `0x71edb81701d7c95d92d5ad4ec43574db388c7e5e21974385374883b021e0f5da`
  - GMX `executeOrder(...)`:
    - `0x53bbb5b41325b3a043e9a9f16a6da4ab4624f0e7bbbf80fe8037446c4c2879e8`
  - LI.FI / Stargate native bridge route using same-source internal transfers:
    - `0x1232a2724f8d2c2e0aa436192b31298ef3351b74bf319c347b9ff569830e7a03`
  - Katana `routeSingle(...)` LP mint path:
    - `0x67f4e9e1767850c427920a1238903ed6fc56e6cadd4d3defcacc7a99e1329499`

Definition of done:
- each family closes from persisted clarification evidence only
- no explorer UI label is required at runtime
- no new backfill is introduced for these closeouts

Required tests:
- ParaSwap clarified receipt -> `SWAP`
- GMX clarified receipt -> deterministic settlement type
- same-source native bridge clarification -> bridge continuity type
- Katana clarified receipt -> LP-entry semantics

### BE-05AP — Irreducible Stop-Condition Lock

Purpose:
- Freeze the rows that should remain in review after the run/6 pack so future
  work does not force synthetic semantics just to reach zero review tail.

Primary write scope:
- backend tests
- small docs/task updates if needed

Intentional stop-condition fixtures:
- `0x508ad8c6695151cd84df379876cef4bd5c5370e8bdd660e54141a35ebe1d9d54`
- `0x504695248b7be49796e52895005019fa7ff268297e394078e336ec5a14cbcf54`
- `0x509c134b2795de71a1ee42db38b53af78003308e8c9ebf2b1bfa9ce8d348dcd2`
- `0x9712e051e33e603b22039ef74ed946e78664695aa341a8825a516822aa5f8966`

Definition of done:
- these rows remain explicit review unless a later slice brings stronger
  protocol identity or movement evidence
- no aggressive no-op or bridge demotion silently closes them

Required tests:
- each stop-condition fixture stays review
- nearby supported families still close correctly

### BE-05AQ — Run/6 Rerun Pack + Repeat-Audit Handoff

Purpose:
- Finish the run/6 closeout slice with one rerun-ready pack and a data-based
  audit gate.

Primary write scope:
- backend tests
- task/docs updates if needed

Implementation scope:
- run targeted regression coverage for:
  - safe current-raw terminal demotions
  - clarification persistence parity
  - same-source internal transfer persistence
  - Slipstream / CL lifecycle clarification closeouts
  - Euler clarification closeouts
  - ParaSwap / GMX / Katana clarification closeouts
  - irreducible stop-condition lock
- hand off the expected live-data gate explicitly

Definition of done:
- rerun instructions remain `normalization + clarification` only
- no new backfill is required for this slice
- the next audit gate is explicit and data-based:
  - review tail reduces from `186` toward the projected `4`
  - clarification persistence mismatches reduce from `294` to `0`
  - only the documented irreducible stop-condition remains unresolved

Required tests:
- regression pack covering every new family in `run/6`
- stop-condition lock tests

## Run/7 Pricing-Readiness Reopen — 2026-03-23 Post-Rerun Audit

This slice starts after the `run/7` live Mongo audit showed that the current
rerun materially reduced the review tail, but still left:

- `28` confirmed resolved semantic misclassifications in already priceable lanes
- `299` clarification persistence mismatches between raw and normalized state
- `18` remaining review rows

Sources for this slice:

- `results/clarification/run/7/clarification-readiness-audit.md`
- `results/clarification/run/7/confirmed_resolved_misclassifications.tsv`
- `results/clarification/run/7/clarification_persistence_mismatches.tsv`
- `results/clarification/run/7/remaining_review_tail.tsv`
- `results/clarification/run/7/resolved_warning_families.tsv`

Required protocol sources for this slice:

- Uniswap Multicall / V3 position-manager references
- Velodrome Slipstream `NonfungiblePositionManager`
- Pancake Infinity `CLPositionManager`
- GMX repository
- explorer verification pages for the full fixture hashes above

Run/7 planning verdict:

- no new backfill is required
- `BE-05AK` is not closed from live data and must be reopened as a narrower
  clarification-storage task
- `BE-05AM` is not closed from live data and must be split into explicit
  Base/BSC concentrated-liquidity proof tasks
- pricing remains blocked by:
  - clarification persistence that is not yet audit-visible from raw
  - self-promotional inbound families that still remain priceable
  - trusted Velodrome Slipstream lifecycle rows that still collapse into
    generic transfer / inbound types

Execution order after run/7:

1. `BE-05AR` clarification canonical persistence contract closeout (reopens `BE-05AK`)
2. `BE-05AS` self-promotional inbound demotion closeout
3. `BE-05AT` Velodrome Slipstream active lifecycle closeout
4. `BE-05AU` Pancake / Infinity concentrated-liquidity clarification proof closeout (reopens part of `BE-05AM`)
5. `BE-05AV` run/7 residual clarification family reopen
6. `BE-05AW` warning-family and stop-condition lock refresh
7. `BE-05AZ` run/7 rerun pack + repeat-audit handoff

### BE-05AR — Clarification Full-Receipt Persistence And Projection Contract Closeout (Reopens BE-05AK)

Purpose:
- Make clarification replay-safe from Mongo alone by persisting one canonical
  clarification-evidence contract on the raw row, without changing any
  classification or clarification semantics.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/job/clarification/**`
- `backend/src/main/java/com/walletradar/ingestion/pipeline/onchain/**`
- `backend/src/main/java/com/walletradar/ingestion/store/**`
- clarification persistence tests

Implementation scope:
- remove `ClarificationMode`; do not keep a code path that truncates an already
  fetched receipt before persistence
- persist one canonical raw-level clarification-evidence block that both:
  - runtime classification/raw view read
  - live Mongo audit scripts can detect
- metadata-safe clarification may still copy scalar receipt fields into
  `rawData.*`, but that alone must not be treated as successful clarification
- any clarification source call that already fetched a receipt payload must
  persist that source-native receipt payload in full
- clarification may still persist:
  - adapted clarification evidence
  - raw full receipt payload when the source exposes it
  - source-family provenance
- normalize or migrate any legacy nested clarification shape so one final
  contract exists going forward
- `OnChainRawTransactionView` / the canonical raw projection becomes the only
  sanctioned read path for clarification evidence; direct reads from raw BSON
  clarification fields outside the raw view are removed
- normalized clarification counters must be derivable from persisted raw
  clarification state only
- this task must not change:
  - classification rule precedence
  - clarification allowlists
  - semantic mapping of tx families

Representative mismatched hashes:
- `0xd09408b311b762fc930bfb6190a9b3967c9b123ec7e6b89e9f29ceda01d46417`
- `0x77049db19258823d40facea05468380cd836611c04fcea659bef78b2aee739a9`
- `0xef9675f9c9117c32d1ead38bf6019f2b8cb2fe725774d4d79f32c3ae7b1eb2ff`
- `0x0a757aeeb58667c545017cd8e5cd60dc994a8945ed810c60ea2aed18688f4f7a`
- `0x0088de663d549fbc58dfa8dbba4180a346a580b1d6277254fa84a8ed9c27967a`

Definition of done:
- live audit can show `clarification_persistence_mismatches = 0`
- rows with `fullReceiptClarificationAttempts > 0` also show persisted raw
  clarification full receipt in the canonical raw contract
- runtime raw view and audit scripts read the same persisted evidence shape
- no runtime path keeps a `metadata-only` storage contract after receipt fetch
- semantic classification outcomes stay controlled by existing rule tasks, not
  by this infrastructure slice

Required tests:
- metadata-safe clarification fetch still persists the full fetched receipt
- metadata clarification without canonical raw evidence persistence is impossible
- full-receipt clarification persists canonical evidence and raw full receipt
- rerun derives normalized clarification counters from persisted raw state only
- runtime clarification consumers read evidence through `OnChainRawTransactionView` only

### BE-05AS — Self-Promotional Inbound Demotion Closeout

Purpose:
- Remove the remaining self-promotional and spam-like inbound families from
  priceable `EXTERNAL_INBOUND`.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- spam/airdrop classification tests

Implementation scope:
- demote the audited repeatable families into explicit non-priceable spam /
  airdrop semantics:
  - Base `multicall(bytes[] data)` token-drop cluster
  - Plasma selector `0x1939c1ff`
  - repeated spam-like selector `0xeec4378e`
- keep the rule raw-only and deterministic:
  - repeated family fingerprint
  - no trustworthy protocol identity
  - no economically meaningful counterparty semantics
- do not let these rows proceed to pricing as `EXTERNAL_INBOUND`

Representative fixtures:
- Base:
  - `0x1741a56b72a5f21af1a005fa488ae0b6646ec0d0fdce043b4f3bb185c668e5c6`
  - `0x285963e00675d2af837ff2b4491a6401de1cb4c8ddc76d17e1b7254dd4ca5fc9`
  - `0x369aec0da1b0191c7243ea94be3afbaf416633bd426234ad19c846683cf39ad4`
  - `0xdf264bc19d87bace55ccb897072a83106b150c76e2c2d889f733df6d757d2e8a`
- Plasma:
  - `0x09500b02b55506d052abb1960c741f26f33ba399e89e024c27558e8e0da1c470`
  - `0x422e1a6bffc63606027e14f60e1b4080dbfbb59776d4747c2a5af3f24d162500`
  - `0x46f0ebcf3641cfbca63e544317baec1fad6f01dfd275929633c3ed8793228ca4`
- residual selector cluster:
  - `0x454cde45e691b585265ce2f3046c88b2c8a14695bd8df37332a00d25caf26f75`
  - `0x468b7eb98bc517c4bca1c92612f60516765911af28ed050af956d1c76707f7b6`
  - `0x51e998e50acee79fd2f9e65986e7febad78c4d537095c888a13e6dfe4a2fa454`
  - `0xa3c4522954d9a824a71c370fc24907c270873ca318d7a19ca8a6d3cc682e2cde`

Definition of done:
- live rerun shows zero resolved self-promotional inbound families leaking into
  priceable `EXTERNAL_INBOUND`
- these rows resolve to explicit non-priceable spam / airdrop semantics

Required tests:
- Base `multicall` spam family -> explicit non-priceable spam lane
- Plasma `0x1939c1ff` family -> explicit non-priceable spam lane
- selector `0xeec4378e` family -> explicit non-priceable spam lane

### BE-05AT — Velodrome Slipstream Active Lifecycle Closeout

Purpose:
- Close the remaining trusted Velodrome Slipstream lifecycle rows that still
  leak into generic transfer / inbound semantics.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- Slipstream lifecycle tests

Implementation scope:
- for trusted position manager `0x416b433906b1b72fa758e166e239c43d68dc6f29`
  and selector `0x219f5d17`, classify `increaseLiquidity(...)` as `LP_ENTRY`
  when current raw or persisted clarification evidence proves liquidity-add
  funding / position-growth semantics
- for trusted stake contract `0xc762d18800b3f78ae56e9e61ad7be98a413d59de`,
  classify:
  - `deposit(uint256)` -> `LP_POSITION_STAKE`
  - `withdraw(uint256)` -> `LP_POSITION_UNSTAKE`
- ensure wrapper-like selectors on this trusted stake contract do not fall
  through to generic `EXTERNAL_INBOUND` or wrapper semantics

Representative fixtures:
- `0x567597b49edc7d10b93236f1f52c9228df53399e4f45fe44f9e9724c2993021b`
- `0x74d965522376cf8581c45b0d1ba10f483ec1110c600d1de3b013c3083fcf8348`
- `0x787e754ebddd5b42b0b8e72492af31a5a895de12569e9e8ad16f39d771771df6`
- `0x9d01d6180ba687243865f9257ce6d909d519a54cfc4c42b014aa9c85c5ec1c75`
- `0xf2f2c8fe7486f8e6b6f09a7ff69513cf40fd1f66e5429c1f9eb374d651ffe538`
- `0x49a8ba9b4d31c734d3d78ee840a664a51a9f75236147148d0b555f0028ffef65`
- `0x84fa5b91a5c653f0dc1b9d4fc0b67420cd63adc02403da85e34504e21c49f69c`
- `0xd4b663df0df33c126937f296a8123a4094dcdcc6ebad84725b9fe708db2ebc40`

Definition of done:
- live rerun shows zero trusted Slipstream `increaseLiquidity(...)` rows
  leaking into `EXTERNAL_TRANSFER_OUT`
- live rerun shows zero trusted Velodrome stake-contract actions leaking into
  generic `EXTERNAL_INBOUND`

Required tests:
- trusted `increaseLiquidity(...)` -> `LP_ENTRY`
- trusted stake-contract deposit -> `LP_POSITION_STAKE`
- trusted stake-contract withdraw -> `LP_POSITION_UNSTAKE`

### BE-05AU — Pancake / Infinity Concentrated-Liquidity Clarification Proof Closeout (Reopens Part Of BE-05AM)

Purpose:
- Close the audited Base and BSC concentrated-liquidity residuals using
  persisted same-source clarification evidence only when that evidence proves
  economic direction strongly enough.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/job/clarification/**`
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- concentrated-liquidity clarification tests

Implementation scope:
- Base `0x0a757aeeb58667c545017cd8e5cd60dc994a8945ed810c60ea2aed18688f4f7a`
  may close to `LP_EXIT` only when persisted same-source clarification evidence
  proves:
  - trusted Pancake / Infinity position-manager identity
  - `multicall(bytes[] data)` exit-family calldata
  - real receipt logs showing collect / withdrawal continuity
  - any required same-source internal native payout legs
- BSC `0x0088de663d549fbc58dfa8dbba4180a346a580b1d6277254fa84a8ed9c27967a`
  may close to `LP_ENTRY` only when persisted full receipt proves:
  - `modifyLiquidities(...)` on trusted `CLPositionManager`
  - positive liquidity-add amounts or explicit tracked-wallet funding into the
    CL manager / pool
- do not auto-promote `0x0088...` from contract identity plus selector plus a
  generic `ModifyLiquidity` topic alone

Definition of done:
- `0x0a757...` closes to deterministic `LP_EXIT` once the persisted same-source
  clarification evidence is present
- `0x0088...` closes to deterministic `LP_ENTRY` only when positive
  liquidity-add evidence is actually persisted; otherwise it remains explicit
  review with `INSUFFICIENT_MOVEMENT_EVIDENCE`

Required tests:
- Base CL exit container with collect / withdrawal / payout evidence -> `LP_EXIT`
- BSC `modifyLiquidities(...)` with positive liquidity-add proof -> `LP_ENTRY`
- BSC `modifyLiquidities(...)` without positive add / funding proof -> stays review

### BE-05AV — Run/7 Residual Clarification Family Reopen

Purpose:
- Reopen the receipt-helpful residual families from `run/7` that were expected
  to close in earlier slices but are still visible in live review.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/job/clarification/**`
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- residual clarification tests

Implementation scope:
- verify and close from persisted clarification evidence where justified:
  - Euler batch residuals:
    - `0x1e0c429514e9cf892b0b6a11e3cfb290eff5c0c26a557c835496e4ba61717fdb`
    - `0x233c2b959739d298d1012405e9b3d7e535a87d590a81bcb304c6dc0cb3ce5e4f`
    - `0x305f37a69956a13001962216c845385996114876173bdbaef644bbe3baadf5df`
  - ParaSwap:
    - `0x71edb81701d7c95d92d5ad4ec43574db388c7e5e21974385374883b021e0f5da`
  - Katana:
    - `0x67f4e9e1767850c427920a1238903ed6fc56e6cadd4d3defcacc7a99e1329499`
  - Slipstream cleanup family still in review:
    - `0x907207001069b6c5b1c0f9aa740736a81ed0f7e8c02b2735a31c772d5bb6603e`
    - `0x91bba2c00fc37a862f2c277e6f8378bf682156425919c66c1b37faa50e9d61b7`
    - `0x927d3f458ada7e5ec67f77129e29edcaf2f69bd2b81490a42fec17c0cc3bd4fa`
    - `0x9c3a93479dd926c7a6e57395b14ab48ed73e673f5cb25f6c1ae6ac9b1bbf2c19`
    - `0xaf00ee8ac5154daa5f4f917d0929ddbacfb1d254ae3b228f3322312a39c798c8`
    - `0xe1bc445ff05954e4d9211570bdaed633b0ddddc70ee36d043574d5b9dd1b9630`
- keep the documented irreducible stop-condition explicit when full receipt
  still fails to produce deterministic economic direction

Definition of done:
- receipt-helpful run/7 residual families either close deterministically from
  persisted clarification evidence or are moved into the explicit documented
  stop-condition set
- no family remains in review merely because clarification evidence failed to
  persist or failed to be consumed

Required tests:
- one closeable Euler / ParaSwap / Katana family closes from clarification
- one Slipstream cleanup family narrows to explicit non-economic cleanup state
- irreducible stop-condition fixtures still remain review

### BE-05AW — Warning-Family And Stop-Condition Lock Refresh

Purpose:
- Freeze the remaining non-blocking warning families and the documented
  stop-condition rows so future closeout work does not reintroduce priceable
  leaks or synthetic semantics.

Primary write scope:
- backend tests
- small task/docs updates if needed

Implementation scope:
- lock current warning families as non-blocking warnings unless a later slice
  brings stronger protocol identity or persisted movement evidence
- refresh the irreducible stop-condition fixtures against the current live tail

Definition of done:
- warning families stay warnings, not silent priceable regressions
- stop-condition rows stay explicit review unless stronger evidence is added

Required tests:
- representative warning-family fixture remains warning/non-blocking
- representative stop-condition fixture remains explicit review

### BE-05AZ — Run/7 Rerun Pack + Repeat-Audit Handoff

Purpose:
- Finish the run/7 closeout slice with one rerun-ready pack and a strict
  data-based gate for pricing readiness.

Primary write scope:
- backend tests
- task/docs updates if needed

Implementation scope:
- run targeted regression coverage for:
  - clarification canonical persistence contract
  - self-promotional inbound demotion
  - trusted Slipstream active lifecycle closeout
  - Base/BSC concentrated-liquidity clarification proof
  - reopened Euler / ParaSwap / Katana / Slipstream cleanup residuals
  - warning-family / stop-condition lock
- hand off the expected live-data gate explicitly

Definition of done:
- rerun instructions remain `normalization + clarification` only
- no new backfill is required for this slice
- the next audit gate is explicit and data-based:
  - `clarification_persistence_mismatches = 0`
  - zero resolved self-promotional inbound leaks into priceable `EXTERNAL_INBOUND`
  - zero trusted Slipstream active lifecycle leaks into generic transfer /
    inbound types
  - Base `0x0a757...` and BSC `0x0088...` are either deterministically closed
    from persisted clarification evidence or explicitly remain in the
    documented stop-condition with the correct reason

Required tests:
- regression pack covering every new family in `run/7`
- stop-condition and warning-family lock tests

## Run/8 Follow-Up Slice

Run/8 planning verdict:

- no new backfill is required
- `BE-05AR` is materially closed for pricing-readiness purposes:
  - canonical top-level `clarificationEvidence` is persisted
  - `clarification_persistence_mismatches = 0`
  - legacy nested `rawData.clarificationEvidence` is now a data-shape warning,
    not the main blocker, as long as runtime reads through
    `OnChainRawTransactionView`
- the remaining blocker is an explicit eight-row basis-relevant review tail
  plus ten safe stop-condition rows that should no longer stay in review
- pricing remains blocked until those families are either:
  - deterministically closed from current raw plus persisted clarification
    evidence, or
  - explicitly demoted into non-priceable stop-condition semantics

Official / primary sources used to define this slice:

- Pancake Infinity `CLPositionManager`
- Uniswap V3 `NonfungiblePositionManager`
- Velodrome Slipstream `NonfungiblePositionManager`
- GMX `ExchangeRouter`
- Pendle `ActionAddRemoveLiqV3`
- explorer verification pages for the full fixture hashes listed below

Execution order after run/8:

1. `BE-05BA` Base Pancake / Infinity LP-exit clarification closeout
2. `BE-05BB` BSC Pancake / Infinity `modifyLiquidities(...)` proof closeout
3. `BE-05BC` Euler batch receipt-driven economic closeout
4. `BE-05BD` routed economic closeout for ParaSwap and Katana
5. `BE-05BE` safe stop-condition demotion closeout
6. `BE-05BF` zkSync residual verified-identity / stop-condition decision lock
7. `BE-05BG` run/8 warning-family and canonical-shape lock refresh
8. `BE-05BH` run/8 rerun pack + repeat-audit handoff

### BE-05BA — Base Pancake / Infinity LP-Exit Clarification Closeout

Purpose:
- Close the remaining Base position-manager exit container from persisted
  clarification evidence so it no longer blocks basis replay.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- `backend/src/main/java/com/walletradar/ingestion/pipeline/onchain/**`
- Base concentrated-liquidity clarification tests

Implementation scope:
- consume persisted same-source clarification evidence for
  `0x0a757aeeb58667c545017cd8e5cd60dc994a8945ed810c60ea2aed18688f4f7a`
- require all of:
  - trusted Pancake / Infinity position-manager identity
  - exit-family multicall continuity
  - persisted collect / withdrawal / unwrap proof from the full receipt
  - any required same-source native payout continuity
- close to deterministic `LP_EXIT`
- do not broaden this into selector-only LP-exit heuristics

Definition of done:
- `0x0a757aeeb58667c545017cd8e5cd60dc994a8945ed810c60ea2aed18688f4f7a`
  leaves `NEEDS_REVIEW` as `LP_EXIT`
- no new false-positive LP-exit promotion appears for weaker Base multicalls

Required tests:
- Base PM collect / withdrawal / unwrap continuity -> `LP_EXIT`
- Base PM multicall without exit continuity stays review

### BE-05BB — BSC Pancake / Infinity `modifyLiquidities(...)` Proof Closeout

Purpose:
- Promote the audited BSC concentrated-liquidity entry row only when persisted
  full receipt proves real liquidity-add semantics.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- BSC concentrated-liquidity tests

Implementation scope:
- consume persisted full receipt proof for
  `0x0088de663d549fbc58dfa8dbba4180a346a580b1d6277254fa84a8ed9c27967a`
- allow promotion to `LP_ENTRY` only when persisted evidence proves either:
  - positive liquidity-add amounts, or
  - explicit tracked-wallet funding into the CL manager / pool
- keep selector + contract identity + generic `ModifyLiquidity` topic
  insufficient on their own

Definition of done:
- `0x0088de663d549fbc58dfa8dbba4180a346a580b1d6277254fa84a8ed9c27967a`
  leaves `NEEDS_REVIEW` only when positive add/funding proof is consumed
- weaker `modifyLiquidities(...)` rows without that proof remain explicit review

Required tests:
- BSC `modifyLiquidities(...)` with positive add/funding proof -> `LP_ENTRY`
- BSC `modifyLiquidities(...)` without that proof stays review

### BE-05BC — Euler Batch Receipt-Driven Economic Closeout

Purpose:
- Finish the audited Avalanche Euler batch family that is still basis-blocking
  despite rich persisted full receipts.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- `backend/src/main/java/com/walletradar/ingestion/job/clarification/**`
- Euler-family tests

Implementation scope:
- consume persisted full receipt evidence for:
  - `0x1e0c429514e9cf892b0b6a11e3cfb290eff5c0c26a557c835496e4ba61717fdb`
  - `0x233c2b959739d298d1012405e9b3d7e535a87d590a81bcb304c6dc0cb3ce5e4f`
  - `0x305f37a69956a13001962216c845385996114876173bdbaef644bbe3baadf5df`
- decode enough batch semantics to emit deterministic economic types from the
  persisted logs and movements already stored in raw
- do not leave these rows as generic `CLASSIFICATION_FAILED` once the decoder
  can prove lending / collateral semantics

Definition of done:
- the three audited Euler batch rows leave `NEEDS_REVIEW`
- no new generic Euler batch family regression appears on fee-only /
  wrapper-only stop-condition rows

Required tests:
- one lending/collateral Euler batch fixture closes deterministically
- one wrapper-only Euler batch fixture remains explicit stop-condition

### BE-05BD — Routed Economic Closeout For ParaSwap And Katana

Purpose:
- Close the remaining routed economic rows that already have sufficient
  persisted clarification evidence.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- routed-economy tests

Implementation scope:
- Base ParaSwap exact-out path:
  - `0x71edb81701d7c95d92d5ad4ec43574db388c7e5e21974385374883b021e0f5da`
  - use recovered selector `0x7f457675` plus persisted transfers/logs to close
    to deterministic `SWAP`
- Katana routeSingle path:
  - `0x67f4e9e1767850c427920a1238903ed6fc56e6cadd4d3defcacc7a99e1329499`
  - consume persisted routed economic continuity to close out of
    `CLASSIFICATION_FAILED`
- keep the rules raw-only / clarification-evidence-only; no explorer page
  summary may become runtime evidence

Definition of done:
- the audited Base ParaSwap row leaves review as `SWAP`
- the audited Katana routed row leaves generic `CLASSIFICATION_FAILED`
- no new routed transfer row is force-promoted without persisted economic
  continuity

Required tests:
- Base ParaSwap exact-out fixture -> `SWAP`
- Katana `routeSingle(...)` fixture closes from persisted clarification evidence

### BE-05BE — Safe Stop-Condition Demotion Closeout

Purpose:
- Remove known non-economic cleanup / fee-only rows from `NEEDS_REVIEW` so the
  remaining tail reflects only basis-relevant uncertainty.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- stop-condition tests

Implementation scope:
- demote the audited safe stop-condition rows into explicit non-priceable
  terminal states:
  - Velodrome cleanup burn family:
    - `0x907207001069b6c5b1c0f9aa740736a81ed0f7e8c02b2735a31c772d5bb6603e`
    - `0x91bba2c00fc37a862f2c277e6f8378bf682156425919c66c1b37faa50e9d61b7`
    - `0x927d3f458ada7e5ec67f77129e29edcaf2f69bd2b81490a42fec17c0cc3bd4fa`
    - `0x9c3a93479dd926c7a6e57395b14ab48ed73e673f5cb25f6c1ae6ac9b1bbf2c19`
    - `0xaf00ee8ac5154daa5f4f917d0929ddbacfb1d254ae3b228f3322312a39c798c8`
    - `0xe1bc445ff05954e4d9211570bdaed633b0ddddc70ee36d043574d5b9dd1b9630`
  - Katana zero-effect collect:
    - `0x4673757b36119b4632f798ad4e0d72fbd170ee0b7be4e4901bd1155ab3881775`
  - fee-only / no-evidence stop-condition rows:
    - `0x508ad8c6695151cd84df379876cef4bd5c5370e8bdd660e54141a35ebe1d9d54`
    - `0x509c134b2795de71a1ee42db38b53af78003308e8c9ebf2b1bfa9ce8d348dcd2`
    - `0x504695248b7be49796e52895005019fa7ff268297e394078e336ec5a14cbcf54`
- do not invent economic flow for these rows; terminal non-priceable semantics
  are the intended outcome

Definition of done:
- the ten audited safe stop-condition rows leave `NEEDS_REVIEW`
- they become explicit non-priceable terminal rows

Required tests:
- one Velodrome cleanup-burn fixture -> explicit non-economic cleanup
- one zero-effect collect fixture -> explicit non-economic collect
- one fee-only zero-evidence fixture -> explicit terminal stop-condition

### BE-05BF — zkSync Residual Verified-Identity / Stop-Condition Decision Lock

Purpose:
- Prevent the remaining zkSync economically material residual from staying a
  silent generic review row without an explicit decision path.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- docs/task notes if needed
- zkSync residual tests

Implementation scope:
- analyze and lock the handling of
  `0x9712e051e33e603b22039ef74ed946e78664695aa341a8825a516822aa5f8966`
- if stronger verified protocol identity can be established from current raw
  plus persisted clarification evidence, close to deterministic canonical type
- otherwise keep it as an explicit documented basis-blocking stop-condition,
  not a generic mixed review row

Definition of done:
- the row is no longer a semantically vague generic review row
- backend behavior matches the documented decision

Required tests:
- fixture either closes deterministically with verified identity or stays an
  explicit documented stop-condition

### BE-05BG — Run/8 Warning-Family And Canonical-Shape Lock Refresh

Purpose:
- Freeze the new post-`run/8` steady state so future work does not reintroduce
  resolved-lane leaks or clarification-shape ambiguity.

Primary write scope:
- backend tests
- small task/docs updates if needed

Implementation scope:
- lock the current resolved warning families as non-blocking terminal rows
- lock canonical top-level clarification evidence as the only runtime-visible
  shape
- keep legacy nested clarification shape as non-runtime warning only

Definition of done:
- warning families stay non-blocking and non-priceable
- runtime still reads canonical clarification evidence only through the raw
  view/projection

Required tests:
- representative warning-family fixture remains non-blocking
- representative canonical clarification-evidence read path stays top-level only

### BE-05BH — Run/8 Rerun Pack + Repeat-Audit Handoff

Purpose:
- Finish the `run/8` closeout slice with one rerun-ready pack and a strict
  pricing-readiness audit gate.

Primary write scope:
- backend tests
- task/docs updates if needed

Implementation scope:
- run targeted regression coverage for:
  - Base Pancake / Infinity LP exit
  - BSC `modifyLiquidities(...)`
  - Euler batch closeout
  - Base ParaSwap and Katana routed paths
  - safe stop-condition demotion
  - zkSync residual decision lock
  - warning-family / canonical-shape lock
- hand off the next live-data gate explicitly

Definition of done:
- rerun instructions remain `normalization + clarification` only
- no new backfill is required for this slice
- next audit gate is explicit and data-based:
  - `clarification_persistence_mismatches = 0`
  - zero run/8 basis-blocking review rows across the audited families
  - audited safe stop-condition rows no longer remain in `NEEDS_REVIEW`

Required tests:
- regression pack covering every new family in `run/8`
- stop-condition and canonical-shape lock tests

## Run/9 Follow-Up Slice

Run/9 planning verdict:

- no new backfill is required
- resolved lane is materially clean:
  - `confirmed_resolved_misclassifications = 0`
  - `resolved_but_insufficient_evidence = 0`
- clarification persistence is no longer a blocker:
  - canonical top-level `clarificationEvidence` exists on all clarified rows
  - `clarification_persistence_mismatches = 0`
  - legacy nested clarification shape is gone in live Mongo
- pricing remains blocked by:
  - four basis-blocking review rows
  - one safe stop-condition row that should no longer remain in review

Official / primary sources used to define this slice:

- Euler EVC Integration Guide
- Euler EVC playground reference
- Pancake Infinity `CLPositionManager`
- Pancake Infinity `ICLPoolManager`
- Uniswap V3 `NonfungiblePositionManager`
- Velodrome Slipstream `NonfungiblePositionManager`
- GMX `ExchangeRouter`
- Pendle `ActionAddRemoveLiqV3`
- explorer verification pages for the full fixture hashes listed below

Execution order after run/9:

1. `BE-05BI` Base Pancake / Infinity LP-exit receipt closeout
2. `BE-05BJ` Avalanche Euler EVC batch economic decoder closeout
3. `BE-05BK` BSC Pancake Infinity zero-effect `modifyLiquidities(...)` demotion
4. `BE-05BL` run/9 warning-family and stop-condition lock refresh
5. `BE-05BM` run/9 rerun pack + repeat-audit handoff

### BE-05BI — Base Pancake / Infinity LP-Exit Receipt Closeout

Purpose:
- Close the last Base basis-blocking concentrated-liquidity exit row from
  already persisted clarification evidence.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- `backend/src/main/java/com/walletradar/ingestion/pipeline/onchain/**`
- Base concentrated-liquidity tests

Implementation scope:
- consume persisted full receipt evidence for
  `0x0a757aeeb58667c545017cd8e5cd60dc994a8945ed810c60ea2aed18688f4f7a`
- require all of:
  - trusted Pancake / Infinity position-manager identity
  - `multicall(bytes[] data)` exit-family continuity
  - persisted `DecreaseLiquidity` / `Burn` / `Collect` proof
  - persisted withdrawal / unwrap continuity and any required native payout
    continuity
- close deterministically to `LP_EXIT`
- do not broaden this into selector-only LP-exit heuristics

Definition of done:
- `0x0a757aeeb58667c545017cd8e5cd60dc994a8945ed810c60ea2aed18688f4f7a`
  leaves `NEEDS_REVIEW` as `LP_EXIT`
- weaker Base position-manager multicalls without the same continuity remain
  explicit review

Required tests:
- Base PM `multicall` with persisted decrease/collect/withdraw continuity ->
  `LP_EXIT`
- Base PM `multicall` without exit continuity stays review

### BE-05BJ — Avalanche Euler EVC Batch Economic Decoder Closeout

Purpose:
- Finish the last basis-blocking Euler EVC batch family using the already
  persisted full receipts.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- Euler-family tests

Implementation scope:
- decode enough batch semantics for:
  - `0x1e0c429514e9cf892b0b6a11e3cfb290eff5c0c26a557c835496e4ba61717fdb`
  - `0x233c2b959739d298d1012405e9b3d7e535a87d590a81bcb304c6dc0cb3ce5e4f`
  - `0x305f37a69956a13001962216c845385996114876173bdbaef644bbe3baadf5df`
- use only current raw plus already persisted clarification full receipts
- align semantics with official Euler EVC batching / sub-account model
- close to deterministic economic types instead of generic
  `CLASSIFICATION_FAILED`
- keep fee-only or wrapper-only Euler residuals in explicit terminal
  stop-condition semantics if they still lack economic movement proof

Definition of done:
- the three audited Euler rows leave `NEEDS_REVIEW`
- no new generic Euler batch regression appears on non-economic rows

Required tests:
- one deposit/collateral Euler batch fixture closes deterministically
- one borrow/withdraw or swap-like Euler batch fixture closes deterministically
- one fee-only / wrapper-only Euler residual remains explicit stop-condition

### BE-05BK — BSC Pancake Infinity Zero-Effect `modifyLiquidities(...)` Demotion

Purpose:
- Remove the last safe review row without inventing LP-entry basis where the
  persisted receipt proves no economic effect.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- BSC concentrated-liquidity stop-condition tests

Implementation scope:
- narrow
  `0x0088de663d549fbc58dfa8dbba4180a346a580b1d6277254fa84a8ed9c27967a`
  out of `NEEDS_REVIEW`
- treat persisted proof of:
  - pool-manager `ModifyLiquidity` with `liquidityDelta = 0`
  - CLPositionManager `ModifyLiquidity` with `liquidityChange = 0`
  - `feesAccrued = 0`
  - no token/internal transfers
  as explicit non-economic stop-condition semantics
- do not promote this family to `LP_ENTRY` unless stronger positive add/funding
  proof exists

Definition of done:
- `0x0088de663d549fbc58dfa8dbba4180a346a580b1d6277254fa84a8ed9c27967a`
  leaves `NEEDS_REVIEW`
- the resulting type is explicit non-priceable terminal semantics
- future zero-effect `modifyLiquidities(...)` rows do not leak back into
  `LP_ENTRY / NEEDS_REVIEW`

Required tests:
- zero-effect BSC `modifyLiquidities(...)` fixture -> explicit non-economic
  terminal state
- positive add/funding proof fixture still closes to `LP_ENTRY`

### BE-05BL — Run/9 Warning-Family And Stop-Condition Lock Refresh

Purpose:
- Freeze the new post-`run/9` steady state so future work does not reintroduce
  resolved-lane leaks or return safe stop-conditions back into review.

Primary write scope:
- backend tests
- small task/docs updates if needed

Implementation scope:
- lock resolved warning families as non-blocking terminal rows
- lock canonical top-level clarification evidence as the only runtime-visible
  clarification shape
- lock `0x0088...`-style zero-effect CL manager calls as non-priceable
  stop-conditions, not basis blockers

Definition of done:
- warning families stay non-blocking and non-priceable
- runtime still reads canonical clarification evidence only through the raw
  view / projection
- zero-effect CL manager calls remain outside the pricing gate

Required tests:
- representative warning-family fixture remains non-blocking
- representative canonical clarification-evidence read path stays top-level only
- representative zero-effect CL fixture remains non-priceable

### BE-05BM — Run/9 Rerun Pack + Repeat-Audit Handoff

Purpose:
- Finish the `run/9` closeout slice with one rerun-ready pack and a strict
  pricing-readiness audit gate.

Primary write scope:
- backend tests
- task/docs updates if needed

Implementation scope:
- run targeted regression coverage for:
  - Base Pancake / Infinity LP exit closeout
  - Avalanche Euler EVC batch closeout
  - BSC zero-effect `modifyLiquidities(...)` demotion
  - warning-family / stop-condition lock refresh
- hand off the next live-data gate explicitly

Definition of done:
- rerun instructions remain `normalization + clarification` only
- no new backfill is required for this slice
- next audit gate is explicit and data-based:
  - `clarification_persistence_mismatches = 0`
  - zero run/9 basis-blocking review rows across the audited families
  - the audited BSC zero-effect `modifyLiquidities(...)` row no longer remains
    in `NEEDS_REVIEW`

Required tests:
- regression pack covering every new family in `run/9`
- stop-condition and canonical-shape lock tests

Run/11 planning verdict:

- no new backfill is required
- review-tail volume is no longer the pricing blocker:
  - `NEEDS_REVIEW = 0`
  - `PENDING_CLARIFICATION = 0`
- clarification persistence is no longer the pricing blocker:
  - `clarification_persistence_mismatches = 0`
  - canonical top-level `clarificationEvidence` is present on clarified rows
- pricing remains blocked by ten audited resolved-lane promo-like inbound
  rows that already sit in priceable
  `EXTERNAL_INBOUND / PENDING_PRICE`
- these rows would create false zero-cost inbound lots and contaminate AVCO,
  cost basis, and move-basis replay
- current raw is sufficient for the closeout:
  - audited method families are already persisted
  - token identity and sender shape are already persisted on the raw rows
  - wallet-history isolation for the affected asset contracts is provable from
    current Mongo
  - no clarification redesign is required for this slice

Official / primary sources used to define this slice:

- explorer verification pages for the full audited hashes listed below
- official protocol references already validated in the preceding slices remain
  authoritative for the resolved LP / lending / bridge families that must not
  regress:
  - Euler EVC Integration Guide
  - Pancake Infinity `CLPositionManager`
  - Pancake Infinity `ICLPoolManager`
  - Uniswap V3 `NonfungiblePositionManager`
  - Pendle `ActionAddRemoveLiqV3`
  - GMX `ExchangeRouter`

Execution order after run/12:

1. `BE-05BR` Avalanche homoglyph stablecoin spoof demotion closeout
2. `BE-05BS` Ethereum self-drop promo token demotion closeout
3. `BE-05BT` Base / Unichain batched promo distribution demotion closeout
4. `BE-05BU` Polygon / Arbitrum distributor-transfer promo demotion closeout
5. `BE-05BV` run/12 resolved-lane promo stop-condition lock
6. `BE-05BW` run/12 rerun pack + repeat-audit handoff

### BE-05BR — Avalanche Homoglyph Stablecoin Spoof Demotion Closeout

Purpose:
- Remove the audited Avalanche homoglyph `UЅDС` spoof inbound family from the
  priceable resolved lane before pricing can mint fake stablecoin lots.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- Avalanche inbound / promo demotion tests

Implementation scope:
- demote the audited current-raw spoof family on non-canonical token contract
  `0x318c6a3cb85952641cd253b2311b0cee30f44822` from
  `EXTERNAL_INBOUND / PENDING_PRICE` into explicit non-priceable terminal
  semantics such as `UNKNOWN / CONFIRMED / PROMO_SPAM_PHISHING`
- cover the current audited hashes:
  - `0x7877f061ff3612b38da3d9f09829ac2fb789154a391a0611f7cf316a98be2585`
  - `0xb6949a71c32d3b2c2d1f84a9f0720030c4fca8806fefab48414743c1a9c94267`
- use only current raw evidence:
  - homoglyph `UЅDС` token identity
  - non-canonical contract address
  - repeated sender/distribution shape
- do not regress legitimate canonical stablecoin inbound transfers

Definition of done:
- the audited homoglyph `UЅDС` rows no longer remain priceable
  `EXTERNAL_INBOUND / PENDING_PRICE`
- legitimate canonical stablecoin transfers remain economic

Required tests:
- audited homoglyph `UЅDС` fixture -> explicit non-priceable promo lane
- canonical stablecoin transfer fixture remains economic

### BE-05BS — Ethereum Self-Drop Promo Token Demotion Closeout

Purpose:
- Remove the audited Ethereum token-contract self-drop promo families from the
  resolved priceable inbound lane.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- Ethereum self-drop promo tests

Implementation scope:
- demote direct token-contract self-drop inbound rows, where the token contract
  is also the transfer sender and current raw shows no later wallet history for
  the asset, into explicit non-priceable promo/spam terminal semantics
- cover the audited hashes:
  - `0xd012cc1be63c0cb2f057efa37f9aacaaf65d1685e9d8346f96c58d85df739978`
  - `0x8fe8035533c19f2e47d528bfafae0954cf82c7822113c0272cbb791ca3e1f267`
  - `0x99ecc6af49c532f05842c7958cc605f5202b870695cff1a7cbcce33d960be31c`
- keep legitimate reward distributors and ordinary token transfers outside this
  demotion

Definition of done:
- the audited Ethereum self-drop rows leave priceable
  `EXTERNAL_INBOUND / PENDING_PRICE`
- legitimate ERC-20 inbound transfers do not regress into promo/spam

Required tests:
- self-drop promo fixture -> explicit non-priceable promo lane
- legitimate inbound ERC-20 transfer fixture remains economic

### BE-05BT — Base / Unichain Batched Promo Distribution Demotion Closeout

Purpose:
- Remove the remaining audited batched promo distributions from Base and
  Unichain without regressing legitimate batched economic inbound.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- Base / Unichain batched promo tests

Implementation scope:
- demote the audited current-raw batched promo families into explicit
  non-priceable promo/spam terminal semantics:
  - Base `batchTransfer(address token, address[] recipients, uint256[] amounts)`
    / selector `0x1239ec8c`:
    `0x1c6ed7d5796c3b7612e6c74ac09e28f9e35c48ed628ab03238cf8b11a628b021`
  - Unichain
    `sendBatchTokens(address token,uint256 tokenAmount,address[] targets)` /
    selector `0x9f1b6858`:
    `0x2c2ea17b2b0552a67f29c006effed3ca555059394d079438046bcb2fed5ed27a`
- use only persisted raw evidence:
  - batched fan-out method family
  - inbound-only movement
  - promotional token identity
  - no later wallet history for the same asset
- do not regress legitimate batched reward / bridge / LP / lending rows

Definition of done:
- the audited Base and Unichain batched promo rows no longer remain priceable
  `EXTERNAL_INBOUND / PENDING_PRICE`
- legitimate batched economic rows remain economic

Required tests:
- Base `batchTransfer(...)` promo fixture -> explicit non-priceable promo lane
- Unichain `sendBatchTokens(...)` promo fixture -> explicit non-priceable
  promo lane
- representative legitimate batched inbound fixture remains economic

### BE-05BU — Polygon / Arbitrum Distributor-Transfer Promo Demotion Closeout

Purpose:
- Remove the remaining audited one-off distributor-transfer promo rows from the
  resolved priceable lane on Polygon and Arbitrum.

Primary write scope:
- `backend/src/main/java/com/walletradar/ingestion/pipeline/classification/**`
- Polygon / Arbitrum promo demotion tests

Implementation scope:
- demote the audited current-raw distributor-transfer families into explicit
  non-priceable promo/spam terminal semantics:
  - Polygon `ZHT Token` distributor transfers:
    `0x94bd752f0213ad5400835a9f1895c0b7c32ea855de1897ecd5919c25312ed20e`
    `0xbff90aa7171b084be578a4b0dc2351cb24ba3e823f4cb6134ac432b7edda15a0`
  - Arbitrum `xAUUSD` one-off distributor transfer:
    `0x9a2ed17307b8fe69ef297d90c4ee5794381e050746e727fabf6d58b7bd120a47`
- use only current raw + current Mongo evidence:
  - fixed distributor sender pattern
  - token identity
  - one-off wallet history for the asset contract
- keep legitimate token transfers outside this demotion

Definition of done:
- the audited Polygon and Arbitrum rows leave priceable
  `EXTERNAL_INBOUND / PENDING_PRICE`
- legitimate token transfers do not regress into promo/spam

Required tests:
- Polygon `ZHT` promo fixture -> explicit non-priceable promo lane
- Arbitrum `xAUUSD` promo fixture -> explicit non-priceable promo lane
- legitimate inbound transfer fixture remains economic

### BE-05BV — Run/12 Resolved-Lane Promo Stop-Condition Lock

Purpose:
- Freeze the post-`run/12` steady state so future reruns do not reintroduce
  resolved-lane promo-like inbound leakage into pricing.

Primary write scope:
- backend tests
- small classifier/docs updates if needed

Implementation scope:
- lock all ten audited run/12 promo-like families as explicit non-priceable
  terminal rows
- ensure `NEEDS_REVIEW = 0` does not become the only readiness signal in tests
- ensure these families do not silently fall back to generic
  `EXTERNAL_INBOUND / PENDING_PRICE`
- preserve the already-correct resolved LP / lending / bridge rows from
  `run/12`

Definition of done:
- all ten audited run/12 hashes stay outside the priceable inbound lane
- resolved-lane promo-like families remain explicit terminal non-priceable rows
- legitimate reward, bridge, lending, and LP rows do not regress

Required tests:
- regression pack covering each audited run/12 family
- representative legitimate inbound transfer fixture remains economic
- readiness test that asserts zero audited promo-like leaks in resolved lane

### BE-05BW — Run/12 Rerun Pack + Repeat-Audit Handoff

Purpose:
- Finish the run/12 closeout slice with a single rerun-ready pack and the next
  live-data pricing gate.

Primary write scope:
- backend tests
- task/docs updates if needed

Implementation scope:
- run targeted regression coverage for:
  - Avalanche homoglyph spoof demotion
  - Ethereum self-drop demotion
  - Base / Unichain batched promo demotion
  - Polygon / Arbitrum distributor-transfer demotion
  - resolved-lane promo stop-condition lock
- hand off the next live-data gate explicitly

Definition of done:
- no new backfill is required for this slice
- rerun instructions are explicit:
  - minimum required rerun: `normalization`
  - acceptable end-to-end rerun: `normalization + clarification`
- next audit gate is explicit and data-based:
  - zero confirmed resolved promo-like inbound rows remain in priceable
    `EXTERNAL_INBOUND / PENDING_PRICE`
  - the audited ten hashes no longer leak into pricing
  - clarification persistence remains at `0` mismatches

Required tests:
- regression pack covering every new family in `run/12`
- no-regression checks for the previously closed LP / lending / stop-condition
  rows

## Mandatory Test Matrix

At minimum, backend implementation must include automated coverage for:

- deterministic raw ordering
- deterministic replay ordering
- projection-based internal transfer detection
- synthetic-log exclusion
- `PRICE_UNKNOWN` quantity preservation
- wrapper/native pricing
- `PLASMA` native symbol handling
- matched Bybit withdraw correlation
- matched Bybit deposit correlation
- orphan UTA trade leg handling
- continuity carry-over for lending/vault/custody
- `FEE` role handling

## Operational Handoff Notes for Backend Dev

- Do not expand API surface unless separately requested.
- Do not reintroduce browser-local session assumptions.
- Do not use `asset_positions` as replay input.
- Do not treat unmatched bridge/custody/CEX rows as silent success.
- Prefer small vertical slices, but do not merge a slice that leaves canonical ordering or double-counting behavior undefined.

## Completion Gate

The implementation may be considered ready for review only when all of the following are true:

- canonical schema and indexes are in place
- one installation-wide tracked wallet universe is active
- on-chain and Bybit normalization both write to `normalized_transactions`
- pricing chain is complete, including `WRAPPER` and `PRICE_UNKNOWN`
- AVCO replay consumes only `CONFIRMED` canonical docs
- continuity events use carry-over semantics without double-counting
- logs/metrics show per-stage outcomes clearly
- touched backend tests pass
