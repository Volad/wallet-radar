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
- [ ] The default clarification path uses receipt metadata only for status/gas/contract creation fields, does not rely on synthetic `rawData.logs[]`, and is entered only when those receipt-safe fields are actually missing.
- [ ] Rows that enter `PENDING_CLARIFICATION` record explicit missing receipt-safe reasons; empty `missingDataReasons[]` is not acceptable for clarification-eligible rows.
- [ ] Clarification reasons reflect the actually missing receipt-safe fields from canonical raw evidence. `MISSING_CONTRACT_ADDRESS` is allowed only when contract-creation intent is explicitly evidenced by the tx-shaped raw payload; missing `effectiveGasPrice` must not be masked by legacy `gasPrice`.
- [ ] A live clarification row that is missing both execution status and `effectiveGasPrice` surfaces both reasons in `missingDataReasons[]`, even when the row is not a fee-payer acquisition/disposal row.
- [ ] Clarification may use receipt logs for semantic reclassification only for an allowlisted residual-review set where the audit and official protocol semantics show that receipt evidence can materially close the gap.
- [ ] Clarification persists full receipt logs in a dedicated clarification-evidence field and re-runs classification from canonical raw evidence plus that persisted receipt evidence; it does not use synthetic `rawData.logs[]`, traces, or explorer UI summaries.
- [ ] If a clarification source call already fetched a receipt payload, the system persists that source-native receipt payload in full alongside the adapted clarification evidence; it does not truncate an already fetched receipt down to a metadata-only subset.
- [ ] Clarification enrichment follows raw-source lineage by default: RPC-backed raw uses RPC clarification, Etherscan-family raw uses Etherscan-family clarification, and Blockscout-backed raw uses Blockscout clarification.
- [ ] Clarification source routing and storage are deterministic: the same raw row always chooses the same clarification source family and produces the same adapted evidence plus the same persisted raw receipt payload shape.
- [ ] Clarification is not considered complete for a row unless the fetched clarification evidence is actually persisted on the corresponding `raw_transactions` document; counters without persisted raw evidence are invalid.
- [ ] Clarification persistence uses one canonical raw-level storage contract that is visible to both runtime classification and live Mongo audits. Writing receipt-safe scalar fields into `rawData.*` without also persisting the authoritative clarification-evidence block does not count as replay-safe clarification persistence.
- [ ] Clarification does not keep a mode that truncates an already fetched receipt before persistence. Any distinction between metadata-safe clarification and allowlisted receipt-log clarification is a usage policy, not a storage policy.
- [ ] Clarification telemetry remains live-parity safe: persisted `clarificationEvidence` on a raw row must have matching normalized clarification attempt counters or an explicit telemetry-mismatch warning; silent drift is not acceptable.
- [ ] Runtime classification and normalization read clarification evidence only through `OnChainRawTransactionView` / the canonical raw projection. Direct reads from `rawData.clarificationEvidence` outside the raw view are not allowed.
- [ ] Rows already closable from current raw evidence, such as claim-family no-movement rows or known Morpho handler gaps, are fixed in classification and do not wait for receipt-log clarification.
- [ ] Receipt-only cleanup/admin families may narrow to explicit non-economic terminal states, but receipt enrichment may not invent economic movement that is absent from persisted evidence.
- [ ] Protocol-registry runtime data is loaded from `backend/src/main/resources/protocol-registry.json` only; `event_topics` are reference-only and ignored by the classifier.
- [ ] Multi-function registry entries use explicit `specialHandler` dispatch and return one canonical result per raw tx.
- [ ] Wrapped-native `deposit()` / `withdraw(uint256)` on known wrapper contracts classify as `WRAP` / `UNWRAP` before generic function-name fallback.
- [ ] Wrapped-native `deposit()` / `withdraw(uint256)` on known wrapper contracts still classify as `WRAP` / `UNWRAP` when top-level `to` is missing or weak, as long as persisted raw or persisted clarification evidence proves canonical mint/burn plus native continuity.
- [ ] Wrapped-native `deposit()` / `withdraw(uint256)` on supported predeploy-style wrappers such as Plasma `WXPL9` still classify as `WRAP` / `UNWRAP` from selector continuity plus saved mint/burn and native movement evidence; they do not leak into `VAULT_WITHDRAW`.
- [ ] Live post-clarification pricing-readiness audit shows zero resolved wrapped-native selector rows (`0xd0e30db0`, `0x2e1a7d4d`) leaking into `VAULT_DEPOSIT`, `VAULT_WITHDRAW`, or `LENDING_WITHDRAW`.
- [ ] Blank or `"0x"` `rawData.methodId` is recovered from `rawData.input[0:10]` before method-id classification runs.
- [ ] Known bridge/router methods are not allowed to collapse into generic `VAULT_*` or `EXTERNAL_*` types just because the decoded name contains `deposit`, `withdraw`, `multicall`, or `batch`.
- [ ] Plain positive inbound transfer legs default to `EXTERNAL_INBOUND` unless reward-specific contract evidence or bridge continuity evidence exists.
- [ ] `AMBIGUOUS_INBOUND_VS_REWARD` defaults to `EXTERNAL_INBOUND` unless reward-specific evidence exists; receipt clarification is not used to resolve that ambiguity.
- [ ] Promo/phishing inbound patterns are excluded from reward ambiguity handling and do not enter accounting as normal `EXTERNAL_INBOUND` or `REWARD_CLAIM`.
- [ ] Scam filtering uses composite promo/phishing signals and must not drop known legitimate reward-claim routes that lack promo markers.
- [ ] Source-specific backfill paths may enrich `raw_transactions`, but production classification remains source-agnostic and reads only canonical raw evidence through the normalization view/projection.
- [ ] Real provider-emitted backfill logs and tx metadata may be persisted as canonical raw evidence; synthetic or invented `rawData.logs[]` remain forbidden.
- [ ] Top-level tx fields in `raw_transactions` (`from`, `to`, `value`, `input`, `methodId`, `functionName`) always describe the tx row, never a token-transfer row; transfer-style payloads may enrich `explorer.tokenTransfers[]` only.
- [ ] Direct native movement is derived only from canonical tx-level evidence; token transfer amounts must never create duplicate native legs.
- [ ] `BSC` may use provider-first backfill with native RPC fallback for repair/missing fields, but classifier behavior must not branch on `BSC` or on ingestion source.
- [ ] Provider-first `BSC` backfill persists approve-only and no-movement tx rows from the provider feed; they are not silently dropped during mapping or persistence.
- [ ] Production classification is derived only from backfill-available raw evidence and receipt-safe clarification evidence; human-readable explorer page summaries never override raw legs.
- [ ] Protocol-specific classification rules are justified by official protocol contracts/docs or equivalent primary sources when those sources exist; explorer UI labels alone are not sufficient rule authority.
- [ ] Across `depositV3` classifies to `BRIDGE_OUT` on recognized bridge-entry contracts.
- [ ] Recognized bridge-entry methods such as Across `depositV3(...)` may not fall through to `VAULT_DEPOSIT` once selector, contract identity, and outbound bridge-funding movement are present.
- [ ] Live post-clarification pricing-readiness audit shows zero recognized Across `depositV3(...)` rows leaking into `VAULT_DEPOSIT`.
- [ ] Route-tagged bridge initiations such as LI.FI / Jumper `callDiamondWith*` bridge paths and `transferRemote(...)` classify to `BRIDGE_OUT` or another explicit bridge-initiation family, not `EXTERNAL_TRANSFER_OUT`, when persisted raw proves bridge-route identity plus source-side funding movement.
- [ ] LI.FI / Jumper `callDiamondWith*` bridge paths still classify to bridge-initiation semantics when top-level `rawData.methodId` is blank or `0x`, as long as the selector is recoverable from `rawData.input` and persisted calldata contains official route-tag evidence.
- [ ] Priceable bridge-initiation rows may not collapse into `EXTERNAL_TRANSFER_OUT` merely because the current raw carries only outbound source-chain movement and fee legs.
- [ ] Circle CCTP `redeem(bytes cctpMsg,bytes cctpSigs)` destination-side rows with persisted bridged payout movement classify to `BRIDGE_IN`, not `VAULT_WITHDRAW`.
- [ ] GMX `createOrder(...)` and similar order-initiation rows may not enter pricing as `EXTERNAL_TRANSFER_OUT` until persisted evidence proves finalized economic settlement; pending-order escrow is not a disposal.
- [ ] Claim-income families such as Pancake `harvest(...)` and vesting `release()` may not remain generic `EXTERNAL_INBOUND` once current raw proves contract-scoped claim / release semantics.
- [ ] Explicit receiver-wallet claim families such as merkle `claim(...)` and signed `claimWithSig(...)` classify to `REWARD_CLAIM`, not generic `EXTERNAL_INBOUND`, when persisted raw or persisted clarification evidence proves both claim-family identity and payout into the tracked wallet.
- [ ] Request-initiation rows such as `claimSharesAndRequestRedeem(uint256 sharesToRedeem)` may not remain priceable `EXTERNAL_TRANSFER_OUT` before later settlement or bridge-completion evidence exists.
- [ ] Repeated self-promotional inbound families such as Base `multicall(bytes[] data)` token-drop rows, Plasma spam / airdrop selector `0x1939c1ff`, and repeated spam-like selector `0xeec4378e` may not remain priceable `EXTERNAL_INBOUND`; they must narrow to explicit non-priceable spam / airdrop semantics.
- [ ] Known Velodrome Slipstream `increaseLiquidity(...)` on trusted position manager `0x416b433906b1b72fa758e166e239c43d68dc6f29` classifies to `LP_ENTRY`, not `EXTERNAL_TRANSFER_OUT`, when current raw or persisted clarification evidence proves liquidity-add funding semantics.
- [ ] Known Velodrome stake-contract actions on `0xc762d18800b3f78ae56e9e61ad7be98a413d59de` classify to `LP_POSITION_STAKE` / `LP_POSITION_UNSTAKE`, not generic `EXTERNAL_INBOUND`, when contract identity and selector semantics are already deterministic from current raw or persisted clarification evidence.
- [ ] Clarification telemetry parity is live-auditable: persisted `clarificationEvidence` on raw rows may not coexist silently with zero normalized clarification counters.
- [ ] Clarification persistence is replay-safe: a live Mongo audit shows zero rows where normalized clarification counters are greater than zero while the paired raw row lacks persisted clarification evidence or persisted raw full receipt payload.
- [ ] Clarification may persist same-source internal transfers for an allowlisted native-bridge subset when those internal legs are required for deterministic bridge continuity and are available from the same source family that produced the raw row.
- [ ] Bridge-settlement selectors such as `fillV3Relay`, `fillRelay`, `redeemWithFee`, `execute302`, and `directFulfill` classify to bridge continuity semantics rather than `REPAY` or `VAULT_*` fallback.
- [ ] Legitimate `redeemWithFee(...)` bridge settlement and `claimWithRecipient(...)` reward routes must bypass promo/phishing review.
- [ ] Known claim family `0x3ef3d8ba38ebe18db133cec108f4d14ce00dd9ae` classifies contract-aware: real inbound movement -> `REWARD_CLAIM`; no-movement claim call -> explicit non-economic or review path, never silent reward minting.
- [ ] The same claim tx may classify differently per tracked wallet when only one tracked wallet actually receives the reward; non-receiving tracked wallets stay explicit `CLAIM_WITHOUT_MOVEMENT` or review, never synthetic `REWARD_CLAIM`.
- [ ] `CLAIM_WITHOUT_MOVEMENT` is a valid per-wallet terminal outcome for claim-family calls where the tracked wallet signs the claim path but receives no inbound reward movement in persisted raw evidence.
- [ ] Safe current-raw review-tail families leave `NEEDS_REVIEW` without waiting for clarification: spam / airdrop rows, explicit `CLAIM_WITHOUT_MOVEMENT`, failed transactions, documented admin / governance / pending-request / pending-order families, and out-of-scope NFT / attestation mints resolve to explicit non-priceable terminal states.
- [ ] Clarification allowlist covers the receipt-log-rich residual families validated by the run/6 audit: Slipstream cleanup burn, Slipstream stake-contract actions, zero-effect collect, Pancake / Infinity CL exit and `modifyLiquidities`, Euler `batch(...)`, ParaSwap `swapExactAmountOut(...)`, GMX `executeOrder(...)`, and Katana `routeSingle(...)`.
- [ ] Base Pancake / Infinity CL exit containers such as `0x0a757aeeb58667c545017cd8e5cd60dc994a8945ed810c60ea2aed18688f4f7a` may close to `LP_EXIT` only when persisted same-source clarification evidence proves collect / withdrawal / unwrap continuity, including real receipt logs and any required same-source internal native payout legs.
- [ ] BSC Pancake / Infinity `modifyLiquidities(...)` rows such as `0x0088de663d549fbc58dfa8dbba4180a346a580b1d6277254fa84a8ed9c27967a` may close to `LP_ENTRY` only when persisted full receipt evidence proves positive liquidity-add semantics or explicit tracked-wallet funding into the CL manager / pool. Contract identity plus selector plus a generic `ModifyLiquidity` topic alone are not sufficient.
- [ ] Base routed economic rows such as `0x71edb81701d7c95d92d5ad4ec43574db388c7e5e21974385374883b021e0f5da` may not remain `UNKNOWN / NEEDS_REVIEW` once persisted clarification evidence proves a multi-leg ParaSwap exact-out path with real economic continuity.
- [ ] Receipt-log-rich Avalanche Euler `batch(...)` rows such as `0x1e0c429514e9cf892b0b6a11e3cfb290eff5c0c26a557c835496e4ba61717fdb`, `0x233c2b959739d298d1012405e9b3d7e535a87d590a81bcb304c6dc0cb3ce5e4f`, and `0x305f37a69956a13001962216c845385996114876173bdbaef644bbe3baadf5df` may not remain generic `CLASSIFICATION_FAILED` once persisted full receipt logs are rich enough to decode the underlying economic lending/collateral semantics.
- [ ] Katana `routeSingle(...)` rows such as `0x67f4e9e1767850c427920a1238903ed6fc56e6cadd4d3defcacc7a99e1329499` may not remain generic `CLASSIFICATION_FAILED` once persisted full receipt proves a multi-leg routed economic path.
- [ ] The residual zkSync economic row `0x9712e051e33e603b22039ef74ed946e78664695aa341a8825a516822aa5f8966` must either:
  - receive stronger verified protocol identity and deterministic canonical semantics, or
  - stay an explicit documented basis-blocking stop-condition.
  It may not stay silently mixed into a generic review tail without an explicit product/ops interpretation.
- [ ] Receipt-log-rich families that clarification still cannot close after official-protocol validation remain in an explicit documented stop-condition set; they do not get forced into synthetic economic types just to reduce the review tail.
- [ ] Infrastructure work on clarification receipt persistence must not change classification or clarification semantics by itself. Allowlists, rule precedence, and economic-type decisions stay unchanged unless a separate rule task explicitly says otherwise.
- [ ] Known lending `withdraw(...)` selectors on lending pools classify to `LENDING_WITHDRAW`, not `LENDING_DEPOSIT`.
- [ ] Trader Joe `LBRouter.addLiquidity(...)` classifies to `LP_ENTRY`, not `LENDING_DEPOSIT`, when persisted raw shows outbound asset funding and LP-liquidity semantics.
- [ ] Approval or configuration calls such as `setMinterApproval(...)` never normalize into economic LP or vault types when persisted evidence is fee-only.
- [ ] Zero-amount token transfers with no economic counterflow never create `BUY` / `SELL` movement; they resolve to contract-scoped admin/no-op handling or explicit review.
- [ ] Position-manager `multicall` that mints or increases a V3 LP position normalizes to `LP_ENTRY`, not `UNKNOWN`.
- [ ] Method-aware bundle routers such as Morpho Bundler3 and CL position-manager `modifyLiquidities` do not fall through to broad `multicall` / `deposit` / `withdraw` keyword fallback.
- [ ] Concentrated-liquidity position-manager `multicall` and `modifyLiquidities` on `ETHEREUM`, `ARBITRUM`, `BASE`, `UNICHAIN`, and `BSC` are resolved through method-aware LP routing using persisted raw evidence only.
- [ ] Economic rows may enter `PENDING_PRICE` or `CONFIRMED` only when persisted raw or persisted clarification evidence contains non-fee movement evidence sufficient for pricing and later basis replay; fee-only rows may remain priceable only when their resolved type is explicitly non-economic.
- [ ] Pricing / AVCO does not start until the post-rerun live audit shows zero resolved wrapped-native continuity leaks, zero resolved recognized Across `depositV3(...)` leaks, zero route-tagged bridge-initiation leaks into `EXTERNAL_TRANSFER_OUT`, zero Circle CCTP `redeem(...)` leaks into `VAULT_WITHDRAW`, zero explicit receiver-wallet claim payout leaks into `EXTERNAL_INBOUND`, zero pending redeem-request initiation leaks into priceable `EXTERNAL_TRANSFER_OUT`, and zero priceable GMX `createOrder(...)` rows without finalized settlement semantics.
- [ ] Pricing / AVCO does not start until the post-rerun live audit also shows zero resolved self-promotional / spam-like inbound families leaking into priceable `EXTERNAL_INBOUND`, zero trusted Velodrome Slipstream `increaseLiquidity(...)` rows leaking into `EXTERNAL_TRANSFER_OUT`, and zero trusted Velodrome stake-contract actions leaking into generic `EXTERNAL_INBOUND`.
- [ ] Pricing / AVCO starts only after the post-rerun live audit shows zero priceable resolved-lane promo/spam leakage; the post-`run/13` gate is already green and must stay green on future reruns.
- [ ] `NEEDS_REVIEW = 0` and `PENDING_CLARIFICATION = 0` are necessary but still not sufficient signals on their own; the live audit remains the release gate for pricing.
- [ ] `0x0088de663d549fbc58dfa8dbba4180a346a580b1d6277254fa84a8ed9c27967a` may close as `LP_ENTRY` only if persisted proof shows positive liquidity-add or tracked-wallet funding into the CL manager / pool. When persisted receipt instead proves zero-effect `modifyLiquidities(...)` (`liquidityDelta = 0`, `liquidityChange = 0`, `feesAccrued = 0`) and no economic transfers, the row must narrow to an explicit non-priceable terminal stop-condition and leave `NEEDS_REVIEW`.
- [ ] The run/6 closeout slice requires `normalization + clarification` rerun only; no new backfill is required to achieve the planned review-tail reduction.
- [ ] The run/7 closeout slice also requires `normalization + clarification` rerun only; no new backfill is required for the remaining pricing blockers or clarification replay-safety fixes.
- [ ] The post-`run/13` pricing milestone requires no new backfill; the current raw and normalized datasets are sufficient to begin implementing and validating pricing.
- [ ] Missing `rawData.transactionIndex` is repaired before canonical normalization or surfaced explicitly as a blocker; no guessed ordering index is allowed.
- [ ] Historical pricing follows the order: stablecoin parity -> exact execution price from canonical source evidence -> swap-derived -> wrapper/native mapping -> Binance historical -> CoinGecko historical fallback -> unresolved price flag.
- [ ] `SWAP` pricing uses canonical wallet-boundary execution ratio before any external candle source.
- [ ] `LP_ENTRY`, `LP_EXIT`, matched bridge continuity, protocol custody, lending continuity, vault continuity, and staking continuity do not require principal market pricing to preserve basis; only explicit fee or reward side-flows require pricing.
- [ ] `EXTERNAL_INBOUND`, `REWARD_CLAIM`, and `LP_FEE_CLAIM` use receive-time fair market value when deterministic tx-local pricing does not already exist.
- [ ] `EXTERNAL_TRANSFER_OUT` uses event-time fair market value unless the row has already been normalized into explicit continuity or pending-request semantics.
- [ ] Binance is the primary external market-data source for listed assets; CoinGecko is bounded fallback and may not be the assumed backbone for a two-year long-tail DeFi pricing pipeline.
- [ ] Bybit trade rows use exact ledger execution price before any external market-data lookup.
- [ ] Bybit withdraw/deposit correlation is required before final unified AVCO replay, but Bybit ledger data is not required to resolve on-chain tx prices themselves.
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
- Case: Wrapped-native withdraw row has weak or absent top-level `to`, but persisted raw shows wrapped-token burn plus native transfer back to wallet | Scope: In | Expected behaviour: canonical type is still `UNWRAP`, not `VAULT_WITHDRAW` or `LENDING_WITHDRAW`.
- Case: Plasma `WXPL9 withdraw(uint256)` uses a supported predeploy-style wrapper and current raw proves wrapper burn plus native continuity | Scope: In | Expected behaviour: canonical type is `UNWRAP`, not `VAULT_WITHDRAW`.
- Case: Wrapped-native deposit row has weak or absent top-level `value`, but persisted raw or persisted clarification evidence still proves selector, wrapper identity, and canonical wrapped mint semantics | Scope: In | Expected behaviour: canonical type is still `WRAP`, not `VAULT_DEPOSIT`.
- Case: `rawData.methodId` is `"0x"` but calldata contains a selector | Scope: In | Expected behaviour: selector is recovered from `rawData.input[0:10]` before method-id classification.
- Case: Bridge/router function name contains `deposit` or `multicall` | Scope: In | Expected behaviour: known method/registry path wins over broad function-name fallback.
- Case: Inbound transfer has no reward-specific evidence | Scope: In | Expected behaviour: tx defaults to `EXTERNAL_INBOUND` and may retain `AMBIGUOUS_INBOUND_VS_REWARD` as metadata; it does not wait in clarification for receipt metadata.
- Case: Inbound transfer has promo/phishing token metadata | Scope: In | Expected behaviour: tx is filtered or surfaced as explicit spam/phishing review; it does not stay in reward ambiguity.
- Case: Explorer page summary says "Transfer" but raw legs show token out + native in | Scope: In | Expected behaviour: classifier follows raw legs and keeps swap-like semantics.
- Case: Backfill source is provider-first and persists real logs into raw | Scope: In | Expected behaviour: classifier may use those persisted raw logs through the normal view/projection; it does not branch on source or network to change semantics.
- Case: Recognized bridge-entry `depositV3` call on Across | Scope: In | Expected behaviour: canonical type is `BRIDGE_OUT`, not `VAULT_DEPOSIT`.
- Case: Recognized Across `depositV3(...)` row has weak or contaminated top-level `to`, but persisted outbound bridge-funding transfer still targets a known `SpokePool` | Scope: In | Expected behaviour: canonical type remains `BRIDGE_OUT`, not `VAULT_DEPOSIT`.
- Case: LI.FI / Jumper `callDiamondWith*` row carries route tags such as `jumper.exchange`, `stargateV2Bus`, `mayanFastMCTP`, `symbiosis`, `cbridge`, or `glacis` in persisted calldata and source-side outbound funding movement | Scope: In | Expected behaviour: canonical type is bridge-initiation continuity, not `EXTERNAL_TRANSFER_OUT`.
- Case: LI.FI / Jumper `callDiamondWith*` row has top-level `rawData.methodId = 0x`, but selector is recoverable from `rawData.input` and calldata carries official route tags | Scope: In | Expected behaviour: canonical type is still `BRIDGE_OUT`, not `EXTERNAL_TRANSFER_OUT`.
- Case: `transferRemote(uint32,bytes32,uint256)` sends tokens into a bridge adapter and pays native messaging gas | Scope: In | Expected behaviour: canonical type is `BRIDGE_OUT`, not `EXTERNAL_TRANSFER_OUT`.
- Case: Circle CCTP `redeem(bytes cctpMsg,bytes cctpSigs)` receives bridged `USDC` into the tracked wallet on the destination chain | Scope: In | Expected behaviour: canonical type is `BRIDGE_IN`, not `VAULT_WITHDRAW`.
- Case: GMX `createOrder(tuple order)` sends execution fee and creates an order request without persisted final settlement movement | Scope: In | Expected behaviour: row stays non-priceable review / pending-order semantics and does not become `EXTERNAL_TRANSFER_OUT`.
- Case: Pancake `harvest(...)` emits reward transfer into the tracked wallet | Scope: In | Expected behaviour: canonical type is `REWARD_CLAIM`, not generic `EXTERNAL_INBOUND`.
- Case: Vesting / release contract `release()` emits a payout into the tracked wallet | Scope: In | Expected behaviour: canonical type is explicit claim / income semantics, not generic `EXTERNAL_INBOUND`.
- Case: Explicit `claim(...)` or `claimWithSig(...)` row pays reward directly into the tracked wallet | Scope: In | Expected behaviour: canonical type is `REWARD_CLAIM`, not generic `EXTERNAL_INBOUND`.
- Case: `claimSharesAndRequestRedeem(uint256 sharesToRedeem)` burns or transfers vault shares but no settlement asset has arrived yet | Scope: In | Expected behaviour: row stays non-priceable pending-redeem/request-initiation semantics, not `EXTERNAL_TRANSFER_OUT`.
- Case: raw row persists `clarificationEvidence`, but the paired normalized row still shows zero clarification attempt counters | Scope: In | Expected behaviour: row is surfaced as a telemetry mismatch and cannot be silently treated as if clarification never ran.
- Case: current raw already proves a spam / airdrop family from repeated inbound-only fingerprints | Scope: In | Expected behaviour: row leaves `NEEDS_REVIEW` for an explicit non-priceable terminal spam / airdrop lane; clarification is not required.
- Case: `SWAP` has one stablecoin leg and one volatile leg | Scope: In | Expected behaviour: volatile leg price is derived from the executed ratio, not from Binance/CoinGecko candle.
- Case: `SWAP` has two volatile legs but one leg has deterministic external price at block time | Scope: In | Expected behaviour: the known leg is priced externally once and the other is derived from the same executed ratio.
- Case: `LP_ENTRY` deposits principal into concentrated-liquidity custody | Scope: In | Expected behaviour: principal basis carries into custody; pricing is required only for explicit fee/reward subflows, not to mint synthetic BUY/SELL.
- Case: `LP_EXIT` returns principal plus collected fees | Scope: In | Expected behaviour: returned principal is continuity, collected fee delta is priced as `LP_FEE_CLAIM`.
- Case: matched `BRIDGE_OUT` plus `BRIDGE_IN` pair across networks | Scope: In | Expected behaviour: principal carries basis with no duplicated external pricing on both sides.
- Case: unmatched bridge send remains explicit continuity/pending family | Scope: In | Expected behaviour: the pricing stage does not invent a synthetic disposal just to assign USD.
- Case: Bybit trade row already carries exact execution price | Scope: In | Expected behaviour: pricing uses the ledger execution price and does not call Binance/CoinGecko for the principal leg.
- Case: token is unlisted on Binance and has no deterministic tx-local pricing | Scope: In | Expected behaviour: CoinGecko fallback is attempted when supported; otherwise the row becomes `PRICE_UNKNOWN` with incomplete-history signaling.
- Case: current raw already proves `CLAIM_WITHOUT_MOVEMENT` for the tracked wallet | Scope: In | Expected behaviour: row leaves `NEEDS_REVIEW` for an explicit terminal no-movement claim state; it does not wait for clarification.
- Case: current raw proves an admin / governance / pending-request family such as `approveDelegation(...)`, `createProxyWithNonce(...)`, `vote(...)`, `reset(...)`, `allow(...)`, or `claimSharesAndRequestRedeem(...)` | Scope: In | Expected behaviour: row narrows to explicit non-priceable terminal semantics and does not remain generic review.
- Case: Trader Joe `LBRouter.addLiquidity(...)` sends two assets out and may refund leftovers | Scope: In | Expected behaviour: canonical type is `LP_ENTRY`, not `LENDING_DEPOSIT`.
- Case: `setMinterApproval(address,bool)` has zero token transfers, zero internal transfers, and fee-only flow | Scope: In | Expected behaviour: canonical type is explicit admin/config or another non-economic terminal state, never `LP_ENTRY`.
- Case: Bridge settlement tx uses `fillV3Relay` / `fillRelay` / `redeemWithFee` | Scope: In | Expected behaviour: canonical type follows bridge continuity semantics, not `REPAY` or generic vault/lending fallback.
- Case: Explorer/provider payload is transfer-shaped and top-level `rawData.value` equals a token transfer amount | Scope: In | Expected behaviour: native leg extraction ignores the contaminated value and uses canonical tx-level evidence only.
- Case: `claimWithRecipient(...)` on an allowlisted reward distributor carries real inbound token movement | Scope: In | Expected behaviour: canonical type is `REWARD_CLAIM` and promo/phishing heuristics do not override it.
- Case: Known claim contract call has no inbound or outbound economic movement in raw | Scope: In | Expected behaviour: tx does not auto-become `REWARD_CLAIM`; it goes to explicit non-economic or review handling.
- Case: Clarification-eligible row is missing `txreceipt_status` and `effectiveGasPrice` but is not a contract creation tx | Scope: In | Expected behaviour: `missingDataReasons[]` contains `MISSING_EXECUTION_STATUS` and `MISSING_EFFECTIVE_GAS_PRICE`, and does not contain `MISSING_CONTRACT_ADDRESS`.
- Case: `PENDING_CLARIFICATION` row is a plain inbound receive and the tracked wallet is not the fee payer | Scope: In | Expected behaviour: clarification reasons still reflect all actually missing receipt-safe tx metadata needed by the clarification stage; the row is not mislabeled just because gas is paid by another address.
- Case: Claim-family call is present for two tracked wallets but only one wallet receives the reward transfer | Scope: In | Expected behaviour: receiving wallet becomes `REWARD_CLAIM`; non-receiving wallet remains explicit `CLAIM_WITHOUT_MOVEMENT` or review and is not upgraded by clarification.
- Case: Mantle merkle-style `claim(...)` call has no payout to the tracked wallet in current raw | Scope: In | Expected behaviour: row narrows to `CLAIM_WITHOUT_MOVEMENT` or equivalent explicit no-movement claim state during classification; it does not wait for clarification.
- Case: Morpho Bundler `multicall` contains `morphoWithdrawCollateral(...)` and current raw already shows inbound asset movement | Scope: In | Expected behaviour: row is closed in the handler/classifier, not in clarification.
- Case: Receipt-log enrichment is enabled for a Pancake CL exit family and full receipt logs reveal deterministic movement | Scope: In | Expected behaviour: clarification may persist receipt logs and re-run classification into the correct LP-exit-related type.
- Case: clarification fetched a receipt for a metadata-safe row and the source returned logs plus other full receipt fields | Scope: In | Expected behaviour: the full source-native receipt payload is still persisted in the canonical clarification-evidence block, even if this run uses only receipt-safe scalar fields semantically.
- Case: Base Pancake / Infinity position-manager `multicall(bytes[] data)` produces `Collect`, `Withdrawal`, and same-source native payout evidence in persisted clarification receipt / internal transfers | Scope: In | Expected behaviour: canonical type is `LP_EXIT`, not router review or generic transfer semantics.
- Case: Raw row came from Blockscout-backed ingestion | Scope: In | Expected behaviour: clarification fetch uses Blockscout-compatible receipt/log endpoints first; it does not silently switch to RPC as the normal path.
- Case: pipeline code needs clarification evidence during classification or normalization | Scope: In | Expected behaviour: it reads that evidence only through `OnChainRawTransactionView` / the canonical projection, not by accessing raw BSON clarification fields directly.
- Case: BSC Pancake / Infinity `modifyLiquidities(...)` is known by contract and selector but persisted clarification evidence shows no positive liquidity-add amount and no tracked-wallet funding leg | Scope: In | Expected behaviour: row remains explicit review with `INSUFFICIENT_MOVEMENT_EVIDENCE`; it does not auto-promote to `LP_ENTRY`.
- Case: Receipt-log enrichment is enabled for a burn-only LP NFT cleanup family and receipt proves no asset movement | Scope: In | Expected behaviour: row may narrow only to an explicit non-economic terminal state; it does not become `LP_EXIT`.
- Case: repeated Base `multicall(bytes[] data)` inbound token-drop rows or Plasma selector `0x1939c1ff` spam families carry no trustworthy economic counterparty semantics | Scope: In | Expected behaviour: rows narrow to explicit non-priceable spam / airdrop semantics and do not enter pricing as `EXTERNAL_INBOUND`.
- Case: trusted Velodrome Slipstream `increaseLiquidity(...)` adds liquidity on a known position manager | Scope: In | Expected behaviour: canonical type is `LP_ENTRY`, not `EXTERNAL_TRANSFER_OUT`.
- Case: trusted Velodrome stake-contract `deposit(uint256)` / `withdraw(uint256)` acts on LP custody state rather than wallet income | Scope: In | Expected behaviour: canonical type is `LP_POSITION_STAKE` / `LP_POSITION_UNSTAKE`, not `EXTERNAL_INBOUND`.
- Case: Receipt-log enrichment is enabled for an allowlisted native bridge family and same-source internal transfers show the bridged native payout | Scope: In | Expected behaviour: clarification persists those internal legs and bridge continuity may close deterministically without a new backfill.
- Case: Receipt-log enrichment still leaves only wrapper bookkeeping or zero-log evidence | Scope: In | Expected behaviour: row remains in the documented irreducible stop-condition set and is not forced into synthetic economic semantics.
- Case: Row is already resolved to an economic type but persisted evidence still contains only fee flow | Scope: In | Expected behaviour: row does not remain price-ready; it must demote back to clarification or review unless the resolved type is explicitly non-economic.
- Case: Explorer or RPC traces would explain a row but production clarification does not persist those traces | Scope: In | Expected behaviour: the row stays explicit review; unsupported evidence sources are not used.
- Case: Known lending `withdraw(...)` burns receipt token and returns underlying asset | Scope: In | Expected behaviour: canonical type is `LENDING_WITHDRAW`, not `LENDING_DEPOSIT`.
- Case: Token transfer leg has zero quantity and no economic counterflow | Scope: In | Expected behaviour: tx does not produce economic movement; it routes to explicit no-op/admin handling or review.
- Case: Known V3 position-manager `multicall` adds liquidity and mints NFT | Scope: In | Expected behaviour: tx becomes `LP_ENTRY`, not router `UNKNOWN`.
- Case: Morpho Bundler3 `multicall` mixes protocol actions | Scope: In | Expected behaviour: method-aware contract routing decides final canonical type; broad bundle keywords do not decide it.
- Case: CL position-manager `modifyLiquidities` changes a concentrated-liquidity position | Scope: In | Expected behaviour: canonical type follows decoded action set / legs, not generic `UNKNOWN`.
- Case: Legitimate BSC claim route arrives from provider-backed raw with claim selector, inbound token transfer, and claim event | Scope: In | Expected behaviour: tx becomes `REWARD_CLAIM` and survives scam filtering.
- Case: BSC provider feed returns approve-only tx with receipt/logs but no economic movement | Scope: In | Expected behaviour: tx is persisted in `raw_transactions` and later normalizes to `APPROVE`, not silently dropped from raw coverage.
- Case: BSC Pancake Infinity `multicall(bytes[] data)` mints a position NFT | Scope: In | Expected behaviour: tx becomes `LP_ENTRY`, not router `UNKNOWN`.
- Case: BSC Pancake Infinity `modifyLiquidities(bytes payload,uint256 deadline)` changes position state | Scope: In | Expected behaviour: tx resolves through method-aware LP routing, not `REGISTRY_SPECIAL_HANDLER_REQUIRED`.
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
5. Clarification rewrite — implement receipt enrichment with explicit eligibility gating, metadata-safe defaults, allowlisted full-receipt persistence, and deterministic persistence of clarification evidence back onto `raw_transactions`. Depends on: 4.
6. Clarification residual closeout — add bounded receipt-log reclassification, intentional-review lock for receipt-insufficient families, and evidence-persistence parity checks between normalized counters and raw evidence. Depends on: 4, 5.
7. Pricing rewrite — implement event-local resolvers first, Binance primary external source, CoinGecko bounded fallback, Bybit execution-price reuse, deterministic historical price caching, and an evidence gate that blocks fee-only economic rows from entering pricing or replay. Depends on: 1, 4, 5, 6.
8. Bybit normalization rewrite — pair UTA trades with sliding `±5 sec`, correlate withdraw/deposit rows by `txHash`, and emit canonical correlated docs. Depends on: 1.
9. AVCO/reconciliation rewrite — replay confirmed canonical docs with transfer carry-over and correlation semantics. Depends on: 1, 7, 8.
10. Determinism and blocker reporting — add explicit blocker/warning outputs and deterministic ordering guarantees across all replay inputs. Depends on: 4, 8, 9.
11. Documentation pass — keep `architecture-v3.md`, `docs/02-architecture.md`, and `docs/03-accounting.md` aligned with the implemented rules. Depends on: 1-10.

## Risk Notes / Assumptions / Open Questions

- Assumption: raw collection quality is already sufficient; this milestone starts after data acquisition.
- Assumption: Bybit remains the only CEX source in the near term.
- Assumption: Binance coverage is sufficient for majors and listed assets, but not for the full DeFi long tail; tx-local pricing and explicit `PRICE_UNKNOWN` remain first-class behaviors.
- Risk: transfer carry-over semantics are implemented incompletely and reintroduce double-counting | Mitigation: require correlation tests covering matched deposit/withdraw pairs.
- Risk: network support drifts from code/config again | Mitigation: keep docs aligned with `NetworkId` and pricing/normalization coverage.
- Risk: unresolved-price behavior gets treated as hard failure | Mitigation: preserve quantity movement and surface explicit incomplete-history flags.
- Risk: `PENDING_CLARIFICATION` becomes a generic low-confidence backlog and hides classifier gaps | Mitigation: gate clarification by missing receipt-safe metadata only and track unsupported semantic gaps separately.
- Risk: explorer payload parity drifts by network and blocks confident classification, especially on `BSC` | Mitigation: keep raw-source completeness checks explicit and track incomplete explorer payloads as blockers instead of silently weakening rules.
- Risk: provider-specific BSC backfill enriches raw but classifier accidentally becomes source-aware | Mitigation: persist provider evidence into canonical raw fields only and keep classifier inputs limited to `raw_transactions` view/projection.
- Risk: provider API drift or partial payload on `BSC` reintroduces missing tx-level fields or logs | Mitigation: add provider-response validation, native repair fallback, and fixture-based regression tests from audited BSC hashes.
- Risk: tx-level fields are contaminated by transfer-row payloads and produce bogus native bridge legs | Mitigation: separate tx-row and transfer-row evidence at ingestion, read direct native value only from canonical tx-level fields, and re-normalize representative bridge-settlement fixtures.
- Risk: bridge-initiation routes such as LI.FI / Jumper and `transferRemote(...)` collapse into generic `EXTERNAL_TRANSFER_OUT` because the classifier only sees source-side sell legs | Mitigation: require route-tag / protocol-identity semantics to win before generic external-transfer fallback and keep bridge-initiation families out of pricing until continuity semantics are correct.
- Risk: LI.FI / Jumper bridge routes still leak when the selector exists only in `rawData.input` and top-level `methodId` is blank | Mitigation: recover selectors from saved calldata, combine them with official route tags, and make bridge routing win before generic external-transfer fallback.
- Risk: Circle CCTP destination-side `redeem(bytes cctpMsg,bytes cctpSigs)` leaks into `VAULT_WITHDRAW`, breaking bridge continuity and basis carry-over | Mitigation: require official CCTP redeem semantics plus persisted bridged payout movement to win before generic vault fallback.
- Risk: claim-income families such as `harvest(...)` and `release()` collapse into generic `EXTERNAL_INBOUND`, hiding income semantics that matter for downstream reporting | Mitigation: add contract-aware claim-income routing backed by official protocol / contract semantics.
- Risk: explicit receiver-wallet claim families such as `claim(...)` and `claimWithSig(...)` stay generic `EXTERNAL_INBOUND`, hiding reward-acquisition semantics that matter for pricing and basis | Mitigation: promote only evidence-backed receiver-wallet payouts into `REWARD_CLAIM` and leave suspicious claim-like rows in narrow review.
- Risk: order-initiation families such as GMX `createOrder(...)` become priceable disposals before final settlement is observable | Mitigation: demote order-initiation rows to explicit pending-order/review semantics unless persisted raw proves final economic completion.
- Risk: request-initiation rows such as `claimSharesAndRequestRedeem(...)` become priceable disposals before settlement or cancellation is known | Mitigation: keep them in explicit non-priceable pending-redeem/request-initiation semantics until later settlement evidence exists.
- Risk: legitimate clarification rows become opaque because `missingDataReasons[]` stays empty | Mitigation: require explicit receipt-safe missing reasons at normalization and clarification entry.
- Risk: clarification reasons drift away from the real missing fields and hide the true blocker | Mitigation: compute reasons from the raw view, require explicit contract-creation evidence before emitting `MISSING_CONTRACT_ADDRESS`, and treat missing `effectiveGasPrice` independently from legacy `gasPrice`.
- Risk: clarification readiness is declared too early because review-family debt is mixed with the real clarification blocker | Mitigation: treat promo/phishing, router overloads, zero-amount families, and `CLAIM_WITHOUT_MOVEMENT` as `NEEDS_REVIEW` work, not clarification debt.
- Risk: per-wallet claim signer rows are force-promoted into reward acquisition | Mitigation: keep `CLAIM_WITHOUT_MOVEMENT` explicit when no inbound reward reaches the tracked wallet in persisted raw evidence.
- Risk: review-tail reduction starts inventing semantics just to reach zero review rows | Mitigation: keep the run/6 documented irreducible stop-condition explicit and prefer narrow terminal/admin lanes or honest review over synthetic economic closure.
- Risk: clarification turns into an unbounded second classifier and hides raw-quality debt | Mitigation: keep receipt-log enrichment allowlisted, persist only production-fetchable receipt evidence, and leave rows already closable from current raw in the classification backlog.
- Risk: receipt-log enrichment starts depending on traces or explorer UI summaries that are unavailable in production | Mitigation: forbid traces and explorer summaries as runtime evidence, and require every receipt-log clarification rule to be justified by official protocol semantics plus receipt evidence alone.
- Risk: clarification observability drifts so raw shows persisted receipt evidence while normalized counters still claim no clarification happened | Mitigation: add live-parity checks between raw clarification evidence and normalized clarification attempt counters, and treat drift as a warning that must be fixed before readiness is declared.
- Risk: clarification writes receipt-safe scalar fields into `rawData.*` but fails to persist the authoritative clarification-evidence block in one canonical raw-level location | Mitigation: enforce one canonical clarification storage contract that both runtime classification and live Mongo audits read, and derive normalized clarification counters from that persisted raw state only.
- Risk: self-promotional Base / Plasma spam clusters are treated as priceable `EXTERNAL_INBOUND` and pollute inventory, pricing, and AVCO | Mitigation: demote repeatable spam / airdrop families into explicit non-priceable terminal semantics before pricing.
- Open question: whether a self-hosted long-tail DEX price index is needed after MVP if Binance plus bounded CoinGecko fallback still leaves too many non-listed assets unresolved.
- Risk: concentrated-liquidity container calls are over-promoted from contract identity alone | Mitigation: require persisted proof of economic direction, such as collect / withdrawal / unwrap continuity for Base `0x0a757...` and positive liquidity-add or tracked-wallet funding evidence for BSC `0x0088...`, before they can leave review.
