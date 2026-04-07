# Classification Stage Audit

Date: 2026-03-22
Generated at: 2026-03-22T16:36:22Z

Scope:
- `walletradar.raw_transactions`
- `walletradar.normalized_transactions`
- current `user_sessions` wallet universe
- clarification is currently disabled; this audit evaluates the post-classification snapshot
- explorer verification first, RPC only if explorer evidence is insufficient
- protocol-source verification through official docs/repos so rule recommendations stay aligned with real protocol semantics

Wallet universe:
- `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f`
- `0xf03b52e8686b962e051a6075a06b96cb8a663021`
- `0x68bc3b81c853338eaaa21552f57437dfd7bf5b7f`

Coverage:
- total raw rows: `3102`
- raw pending: `0`
- raw complete: `3102`
- total normalized on-chain rows: `3102`
- current Bybit raw rows: `0`
- networks present: `ARBITRUM, BASE, AVALANCHE, OPTIMISM, UNICHAIN, MANTLE, PLASMA, ETHEREUM, ZKSYNC, KATANA, LINEA, POLYGON, BSC`

Current on-chain status split:
- `PENDING_PRICE`: `1447`
- `CONFIRMED`: `1173`
- `PENDING_CLARIFICATION`: `300`
- `NEEDS_REVIEW`: `182`

Classifier source split:
- `HEURISTIC`: `1220`
- `METHOD_ID`: `1148`
- `PROTOCOL_REGISTRY`: `441`
- `FUNCTION_NAME`: `293`

## Executive Summary

This rerun looks normalization-ready.

Confirmed improvements after the last remediation slice:
- clarification reasons are now live-accurate: every current clarification row surfaces both `MISSING_EXECUTION_STATUS` and `MISSING_EFFECTIVE_GAS_PRICE`.
- the previous router-overload tail shrank again: the `zkSync` universal-router cluster is no longer open, and only `7` overload rows remain.
- the old zero-amount review tail is gone as an active family; the explicit `ZERO_AMOUNT_TOKEN_TRANSFER` bucket is now `0` in the live snapshot.
- `BSC` remains coverage-stable for the current wallet universe: raw `33`, normalized `33`, and the earlier missing-approve gap did not come back.

Current readiness verdict:
- classification / normalization is healthy enough to move forward.
- I do **not** see a remaining systemic normalization blocker.
- the remaining `NEEDS_REVIEW` queue is now mostly intentional review debt, not broad classifier failure.
- with clarification currently disabled, the present `PENDING_CLARIFICATION` rows are still acceptable because their reason set now matches the raw evidence.

## 1. Clarification Queue Is Correctly Formed

Current clarification split by network:
- `ARBITRUM`: `122`
- `BASE`: `50`
- `AVALANCHE`: `46`
- `UNICHAIN`: `24`
- `MANTLE`: `20`
- `ETHEREUM`: `13`
- `OPTIMISM`: `9`
- `PLASMA`: `8`
- `ZKSYNC`: `6`
- `POLYGON`: `2`

Current clarification split by type:
- `EXTERNAL_INBOUND`: `183`
- `BRIDGE_IN`: `50`
- `EXTERNAL_TRANSFER_OUT`: `36`
- `SWAP`: `14`
- `VAULT_DEPOSIT`: `8`
- `VAULT_WITHDRAW`: `8`
- `REWARD_CLAIM`: `1`

Persisted clarification reasons:
- `MISSING_EFFECTIVE_GAS_PRICE`: `300`
- `MISSING_EXECUTION_STATUS`: `300`
- `AMBIGUOUS_INBOUND_VS_REWARD`: `1`

Raw-side verification on all `300` clarification rows shows:
- missing `txreceipt_status` and `isError`: `300`
- missing `effectiveGasPrice`: `300`
- missing `gasUsed`: `0`

Conclusion:
- the clarification queue is semantically valid
- the reason payload is now aligned with the raw evidence
- because clarification is currently disabled, these rows remain waiting exactly for the missing receipt-safe fields and do not indicate a classification regression

## 2. Remaining Review Queue Is Small And Mostly Intentional

Current review reason split:
- `PROMO_SPAM_PHISHING`: `136`
- `CLASSIFICATION_FAILED`: `23`
- `CLAIM_WITHOUT_MOVEMENT`: `9`
- `ROUTER_METHOD_OVERLOAD_UNSUPPORTED`: `7`
- `FAILED_TRANSACTION`: `4`
- `HANDLER_UNSUPPORTED_METHOD`: `3`

Interpretation:
- `PROMO_SPAM_PHISHING` is still the dominant review bucket and remains a true-positive spam family, not a classifier blocker.
- `CLAIM_WITHOUT_MOVEMENT` remains financially correct per-wallet behavior.
- `ROUTER_METHOD_OVERLOAD_UNSUPPORTED` is down to `7` rows and all current examples have no persisted token transfers, no internal transfers, and no logs; leaving them in explicit review is the honest behavior.
- the residual `CLASSIFICATION_FAILED` set is only `23` rows and is concentrated in small no-evidence or protocol-specific families.

### 2.1 Router Overload Residuals

Current open router overload clusters:
- `OPTIMISM` `0x416b433906b1b72fa758e166e239c43d68dc6f29 + 0x`: `6`
- `BASE` `0x46a15b0b27311cedf172ab29e4f4766fbe7f4364 + 0x`: `1`

Representative raw check:
- Optimism `0x927d3f458ada7e5ec67f77129e29edcaf2f69bd2b81490a42fec17c0cc3bd4fa`
- Base `0x0a757aeeb58667c545017cd8e5cd60dc994a8945ed810c60ea2aed18688f4f7a`

For these rows raw persists:
- outer selector only (`0xac9650d8` recovered from calldata)
- no token transfers
- no internal transfers
- no logs

Conclusion:
- these are no longer hidden misclassifications
- they are explicit insufficient-evidence review rows
- they do not block moving on from normalization

### 2.2 Claim Without Movement Is Correct

Current `CLAIM_WITHOUT_MOVEMENT` clusters:
- `BSC` `0x8b681820` on `0x5810c486c231803578d0ae7b3de97991784be11f`: `2`
- `ETHEREUM` `0x71ee95c0` on `0x3ef3d8ba38ebe18db133cec108f4d14ce00dd9ae`: `2`
- `KATANA` `0x71ee95c0` on `0x3ef3d8ba38ebe18db133cec108f4d14ce00dd9ae`: `2`
- `ARBITRUM` `0x9fb67b58` on `0x3ef3d8ba38ebe18db133cec108f4d14ce00dd9ae`: `1`
- `AVALANCHE` `0x71ee95c0` on `0x3ef3d8ba38ebe18db133cec108f4d14ce00dd9ae`: `1`
- `UNICHAIN` `0x71ee95c0` on `0x3ef3d8ba38ebe18db133cec108f4d14ce00dd9ae`: `1`

Representative raw check:
- Unichain `0xb10350fc0440e7786a2046d6fa5b140781281e9e4aaba03a9b9bde6fece1932d`

Observed raw evidence:
- explicit claim call exists
- no reward transfer reaches the tracked wallet in persisted raw
- no internal transfer compensates for the missing reward movement

Conclusion:
- this is a correct per-wallet terminal review state
- forcing these rows into `REWARD_CLAIM` would be financially wrong

### 2.3 Residual Classification-Failed Families

Current top `CLASSIFICATION_FAILED` groups:
- `AVALANCHE` `0xc16ae7a4` on `0xddcbe30a761edd2e19bba930a977475265f36fa1`: `4`
- `OPTIMISM` `0x` on `0x41c914ee0c7e1a5edcd0295623e6dc557b5abf3c`: `3`
- `MANTLE` `0x5d4df3bf` on `0x0045601c3c4c561012c108ea84a81e36eac24296`: `2`
- `OPTIMISM` `0x` on `0xbc6043a5e50ba0c0213d2f7430a73e4590af97ad`: `2`
- `AVALANCHE` `0x7050ccd9` on `0x7037358a6f2c1d9e5cc9b4a29e7415a7060dadcc`: `1`
- `BASE` `0x` on `0x24e6e0795b3c7c71d965fcc4f371803d1c1dca1e`: `1`
- `BASE` `0x` on `0x6a000f20005980200259b80c5102003040001068`: `1`
- `BASE` `0x` on `0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f`: `1`

Representative raw checks:
- Avalanche `0x509c134b2795de71a1ee42db38b53af78003308e8c9ebf2b1bfa9ce8d348dcd2` (`0xc16ae7a4 batch(...)`): no transfers, no logs
- Mantle `0x02b8f88942ef4bf12132e75b294ef5472d98fddcfd4ea5f9f3277c7492d967f7` (`0x5d4df3bf claim(...)`): explicit claim call but no persisted movement evidence

Conclusion:
- this is no longer a broad classifier collapse
- the residual set is mostly honest explicit review for no-evidence container or claim calls
- there is still room for future tail cleanup, but not a blocker for moving forward

## 3. BSC Is Stable In The Current Snapshot

Current `BSC` status split:
- `PENDING_PRICE`: `23`
- `CONFIRMED`: `8`
- `NEEDS_REVIEW`: `2`

Current `BSC` type split:
- `APPROVE / CONFIRMED`: `8`
- `EXTERNAL_INBOUND / PENDING_PRICE`: `7`
- `STAKING_DEPOSIT / PENDING_PRICE`: `4`
- `LP_ENTRY / PENDING_PRICE`: `3`
- `REWARD_CLAIM / PENDING_PRICE`: `3`
- `LP_EXIT / PENDING_PRICE`: `2`
- `SWAP / PENDING_PRICE`: `2`
- `UNKNOWN / NEEDS_REVIEW`: `2`
- `BRIDGE_OUT / PENDING_PRICE`: `1`
- `EXTERNAL_TRANSFER_OUT / PENDING_PRICE`: `1`

What matters:
- raw `BSC` rows: `33`
- normalized `BSC` rows: `33`
- no new provider-ingestion regression is visible in Mongo
- the two remaining `BSC` review rows are `CLAIM_WITHOUT_MOVEMENT`, which matches the persisted raw evidence and is not a coverage bug

## 4. Selector Recovery Residuals Are No Longer A Major Blocker

Current selector-recovery counters:
- empty top-level `methodId`: `1216`
- recoverable from calldata: `842`
- current review rows still recoverable from calldata: `16`

Representative checks show the remaining recoverable review rows are mostly no-evidence container calls with:
- recovered selector from calldata
- zero token transfers
- zero internal transfers
- zero logs

Conclusion:
- selector parity is no longer the reason the classifier is failing system-wide
- the residual recoverable rows do not currently justify blocking the next stage

## 5. Official Sources Used To Avoid Reinventing Protocol Semantics

Official sources retained for semantic validation:
- Across `SpokePool.sol`: https://raw.githubusercontent.com/across-protocol/contracts/master/contracts/SpokePool.sol
- Pancake Infinity `CLPositionManager.sol`: https://raw.githubusercontent.com/pancakeswap/infinity-periphery/main/src/pool-cl/CLPositionManager.sol
- Pancake `SmartChefInitializable.sol`: https://raw.githubusercontent.com/pancakeswap/pancake-smart-contracts/master/projects/farms-pools/contracts/SmartChefInitializable.sol
- Merkl `Distributor.sol`: https://raw.githubusercontent.com/AngleProtocol/merkl-contracts/main/contracts/Distributor.sol
- Velodrome Slipstream `NonfungiblePositionManager.sol`: https://raw.githubusercontent.com/velodrome-finance/slipstream/main/contracts/periphery/NonfungiblePositionManager.sol
- Uniswap `UniversalRouter.sol`: https://raw.githubusercontent.com/Uniswap/universal-router/main/contracts/UniversalRouter.sol

Representative explorer pages rechecked this round:
- https://optimistic.etherscan.io/tx/0x927d3f458ada7e5ec67f77129e29edcaf2f69bd2b81490a42fec17c0cc3bd4fa
- https://basescan.org/tx/0x0a757aeeb58667c545017cd8e5cd60dc994a8945ed810c60ea2aed18688f4f7a
- https://uniscan.xyz/tx/0xb10350fc0440e7786a2046d6fa5b140781281e9e4aaba03a9b9bde6fece1932d
- https://explorer.mantle.xyz/tx/0x02b8f88942ef4bf12132e75b294ef5472d98fddcfd4ea5f9f3277c7492d967f7
- https://bscscan.com/tx/0x51dc36fc93e51dde5fafd1ab92d000d06104394d3179e3e39f0fcaa54cc53231
- https://bscscan.com/tx/0x0088de663d549fbc58dfa8dbba4180a346a580b1d6277254fa84a8ed9c27967a

## Final Verdict

If the question is strictly about classification / normalization quality, I would move forward.

Why:
- raw coverage is complete for the current wallet universe
- no `raw_transactions` remain pending
- clarification reasons are now correct
- the remaining review queue is small and mostly intentional
- the current residual debt is tail cleanup, not systemic normalization breakage

So:
- **yes, we can move дальше**
- **no, I do not see a normalization-stage blocker that requires another mandatory backfill before the next stage**
- the remaining open items should be tracked as explicit review-tail work, not as a stop-the-line normalization failure
