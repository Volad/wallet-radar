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

The current codebase already contains `BE-03` foundations and a partial `BE-04`
classifier skeleton. The next executor slice must proceed in this order:

1. `BE-04A` Protocol-registry source consolidation + loader/validator
2. `BE-04B` Single-result special-handler contract + dispatcher
3. `BE-04C` Registry-backed classifier rollout + coverage tests

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
- Implement the approved pricing chain and unresolved-price behavior.

Primary write scope:
- `backend/src/main/java/com/walletradar/pricing/**` or restored equivalent module boundary
- pricing job and resolver chain

Implementation scope:
- resolver order:
  - stablecoin parity
  - swap-derived
  - wrapper/native
  - CoinGecko historical
  - `PRICE_UNKNOWN`
- `WRAPPER` price source support
- unresolved-price handling:
  - quantity remains valid for replay
  - price fields remain null
  - `missingDataReasons += PRICE_UNRESOLVABLE`
  - status proceeds without discarding quantity

Definition of done:
- pricing does not block quantity continuity
- wrapper/native mapping works without unnecessary CoinGecko lookups
- non-FEE flow pricing behavior matches the docs

Required tests:
- stablecoin resolver test
- swap-derived resolver test
- wrapper/native resolver test
- `PRICE_UNKNOWN` non-blocking test
- pricing-job status transition test

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
