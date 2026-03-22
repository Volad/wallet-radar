# Classification Stage Audit

Date: 2026-03-22

Scope:
- `walletradar.raw_transactions`
- `walletradar.normalized_transactions`
- current `user_sessions` wallet universe
- explorer verification first, RPC only when explorer evidence was insufficient
- protocol-source verification through official docs/repos so that rule recommendations stay aligned with real contract semantics

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
- networks present: `ARBITRUM, AVALANCHE, BASE, BSC, ETHEREUM, KATANA, LINEA, MANTLE, OPTIMISM, PLASMA, POLYGON, UNICHAIN, ZKSYNC`

Current on-chain status split:
- `PENDING_PRICE`: `1433`
- `CONFIRMED`: `1135`
- `PENDING_CLARIFICATION`: `300`
- `NEEDS_REVIEW`: `234`

Classifier source split:
- `HEURISTIC`: `1220`
- `METHOD_ID`: `1148`
- `PROTOCOL_REGISTRY`: `441`
- `FUNCTION_NAME`: `293`

Top normalized types:
- `EXTERNAL_INBOUND`: `598`
- `APPROVE`: `493`
- `WRAP`: `346`
- `SWAP`: `309`
- `UNWRAP`: `236`
- `UNKNOWN`: `234`
- `EXTERNAL_TRANSFER_OUT`: `207`
- `VAULT_WITHDRAW`: `126`
- `LP_ENTRY`: `102`
- `LENDING_DEPOSIT`: `65`
- `LP_EXIT`: `61`
- `REWARD_CLAIM`: `53`
- `BRIDGE_IN`: `50`

## Executive Summary

The current classification stage is materially healthier than the previous audit slice.

Confirmed improvements in the live snapshot:
- `BSC` provider-first ingestion is now coverage-complete for the active wallet: live Ankr provider returns `33` transactions and Mongo persists the same `33`.
- the earlier bridge flow contamination issue is largely resolved. The previously bad bridge examples no longer show bogus native legs copied from token-transfer values.
- `redeemWithFee(...)` and `fillV3Relay(...)` stay in `BRIDGE_IN`, not in promo-spam review or `REPAY`.
- Pancake Infinity `multicall` and `modifyLiquidities` on `BSC` are now normalized as `LP_ENTRY` / `LP_EXIT`.
- the main Merkl claim family at `0x3ef3d8ba38ebe18db133cec108f4d14ce00dd9ae` is effectively closed across the currently observed networks.

The main open issues have changed:
- the largest open correctness bug is now clarification-reason hygiene, not the clarification gate itself
- the remaining review queue is dominated by true promo/phishing clusters, zero-amount/no-op families, and a smaller set of method-aware router overloads
- selector recovery is still incomplete, but it is no longer the dominant blocker

## 1. Clarification Stage: Mostly Valid, But Reasons Are Wrong

The current `PENDING_CLARIFICATION` bucket is substantively valid.

Raw-side verification on all `300` clarification rows shows:
- missing `rawData.txreceipt_status`: `300`
- missing `rawData.effectiveGasPrice`: `300`
- missing `rawData.gasUsed`: `0`
- actual contract-creation rows (`rawData.creates`): `0`
- actual missing `contractAddress` on create rows: `0`

But the persisted `missingDataReasons` are wrong:
- `MISSING_EXECUTION_STATUS`: `300`
- `MISSING_CONTRACT_ADDRESS`: `300`
- `AMBIGUOUS_INBOUND_VS_REWARD`: `1`

Conclusion:
- clarification is still needed on these rows
- the reason model is not describing the real missing evidence
- `MISSING_CONTRACT_ADDRESS` is spurious on all `300` clarification rows in the live snapshot
- the actual missing receipt-safe field that is not being surfaced is `effectiveGasPrice`

Representative clarification rows:
- Arbitrum `EXTERNAL_INBOUND`: `69`
- Base `EXTERNAL_INBOUND`: `36`
- Avalanche `EXTERNAL_INBOUND`: `24`
- Arbitrum `EXTERNAL_TRANSFER_OUT`: `19`
- Mantle `EXTERNAL_INBOUND`: `18`
- Unichain `BRIDGE_IN`: `17`

Representative tx:
- `0xd2cdbd7a1ade37a8032b713e9844351d2f58fbd872851a0e88203b8fbb695c5f` on Base is now correctly `BRIDGE_IN`, but still sits in clarification because receipt status is absent
- `0x27978f7bf88cd7a4825b991ac6e461fa96be75b280add44236beb2e060c61ba3` on Unichain is now correctly `BRIDGE_IN`, but also needs clarification because execution status is absent

## 2. BSC Coverage Is Now Closed For The Current Wallet Universe

This was rechecked against live Ankr advanced API using the uncommented `ANKR_API_KEY` from `.env`.

Current provider coverage:
- `0x1a87...` -> `33`
- `0xf03b...` -> `0`
- `0x68bc...` -> `0`

Current persisted raw coverage:
- `BSC raw rows`: `33`
- all `33` belong to `0x1a87...`
- active-wallet provider count == raw count
- missing count: `0`

The earlier missing `approve(0x095ea7b3)` hashes are now present in raw and normalized as `APPROVE / CONFIRMED`:
- `0x37908ec5e77552b060fe5dba95c180f71fbe653de38308d033285327ad7a4386`
- `0x510b38963290beb50e5740dda9c57c74b6db6379a11ace4f110722684e878d37`
- plus the other six approve hashes from the previous audit slice

Current `BSC` normalized split:
- `PENDING_PRICE`: `23`
- `CONFIRMED`: `8`
- `NEEDS_REVIEW`: `2`

Current `BSC` types:
- `APPROVE / CONFIRMED`: `8`
- `EXTERNAL_INBOUND / PENDING_PRICE`: `7`
- `STAKING_DEPOSIT / PENDING_PRICE`: `4`
- `REWARD_CLAIM / PENDING_PRICE`: `3`
- `LP_ENTRY / PENDING_PRICE`: `3`
- `LP_EXIT / PENDING_PRICE`: `2`
- `SWAP / PENDING_PRICE`: `2`
- `BRIDGE_OUT / PENDING_PRICE`: `1`
- `EXTERNAL_TRANSFER_OUT / PENDING_PRICE`: `1`
- `UNKNOWN / NEEDS_REVIEW / CLAIM_WITHOUT_MOVEMENT`: `2`

Conclusion:
- the prior `BSC` raw-completeness blocker is resolved for the current wallet universe
- the remaining `BSC` classification debt is not ingestion breadth
- it is narrow semantic policy around claim-without-movement

## 3. Earlier Bridge Flow Contamination Is Mostly Gone

The previously bad bridge examples were rechecked in live Mongo:
- Base `redeemWithFee(...)` `0xd2cdbd7a...`
- Unichain `fillV3Relay(...)` `0x27978f7b...`
- Base `execute302(...)`
- zkSync bridge-in sample

Current result:
- the old bogus native legs copied from token-transfer values are gone from the representative bridge rows
- current bridge flows are now mostly token-only when they should be token-only

Only two `BRIDGE_IN` rows still carry native transfer flows:
- Arbitrum `0x826189720417ce31b983c2c7bb79f04ba4e330df80a0c016dab2bbee2fd61269`
- zkSync `0x9187f4ca7774e9a9de9f760a22ef43e7a162a8020c15fdb80412b6d38af06c22`

Those two no longer look like contamination:
- Arbitrum raw includes a real inbound `USD₮0` token transfer and a real inbound internal ETH transfer
- zkSync raw includes only ETH token movement on the canonical native-token contract

Conclusion:
- the earlier mass contamination blocker should be considered resolved
- remaining bridge/native dual-leg cases must now be evaluated case-by-case from raw evidence, not by reusing the old contamination diagnosis

## 4. Reward Claims Are Mostly Healthy Now

The main Merkl family on `0x3ef3d8ba38ebe18db133cec108f4d14ce00dd9ae` is now substantially closed:
- Avalanche `REWARD_CLAIM / PENDING_PRICE`: `12`
- Katana `REWARD_CLAIM / PENDING_PRICE`: `10`
- Arbitrum `REWARD_CLAIM / PENDING_PRICE`: `10`
- Plasma `REWARD_CLAIM / PENDING_PRICE`: `4`
- Unichain `REWARD_CLAIM / PENDING_PRICE`: `3`
- Ethereum `REWARD_CLAIM / PENDING_PRICE`: `2`

Remaining review rows in the same family are now explainable:
- `CLAIM_WITHOUT_MOVEMENT` on Ethereum, Katana, Avalanche, Unichain, and Arbitrum

These are not necessarily classifier bugs.

Representative proof:
- `0xf13356fe9449ec9e831395e0074622e88e362a8f317e6b110d093bfaa25d2702` exists twice in raw:
  - wallet `0x1a87...` receives ARB and is correctly `REWARD_CLAIM`
  - wallet `0xf03b...` only signs the `claimWithRecipient(...)` call and receives nothing, so `CLAIM_WITHOUT_MOVEMENT` is correct per wallet

The same pattern exists on BSC:
- `0xa586770c653097bd905f0003edddcc59f295a8a64131a3ba0410d6fe43bb08e5`
- `0xeb4fd02cba10c357ea4f5441c0783c5282c01fcaa1a85c661575471df592c5ef`

In both BSC rows:
- BscScan shows a claim-call on `0x5810...`
- raw token transfers do not send reward tokens to the tracked wallet
- the transfers are distributor-internal or routed to a third address

Conclusion:
- forcing these rows into `REWARD_CLAIM` would be wrong
- `CLAIM_WITHOUT_MOVEMENT` is currently a justified terminal review state for per-wallet accounting

## 5. Promo / Spam Filtering Looks Directionally Correct

The live `PROMO_SPAM_PHISHING` queue is dominated by obvious promo-airdrop / phishing clusters:
- Polygon promo/reward spam: `43`
- Plasma `.cfd` spam family: `33`
- Base promo-airdrop family: `32`
- Arbitrum promo-airdrop family: `14`
- Unichain promo-airdrop family: `7`
- Optimism fake reward family: `2`

Representative clusters from raw:
- Plasma `www.mybase.cfd - claim Base airdrop`: `13`
- Plasma `www.2base.cfd - claim Your Base airdrop`: `8`
- Polygon `PAWS | t.me/s/BD_PAWS | visit to claim`: `7`
- Arbitrum `ETH-Tokens.us`: `3`
- Unichain `$UNI - Claim at: [ t.ly/UNIWALLET ]`: `3`
- Optimism fake `Velodromefi.Store` rewards: `2`

No new false-positive cluster was found this round for:
- `redeemWithFee(...)`
- `claimWithRecipient(...)`

Conclusion:
- promo/spam filtering is materially cleaner than before
- the remaining families look directionally correct
- regression coverage is still needed so bridge settlement and legitimate reward claims do not regress back into this bucket

## 6. Remaining `NEEDS_REVIEW` Is Narrow And Repeatable

The review queue is no longer random. It is concentrated in a small number of repeatable families.

Top live groups:
- `PROMO_SPAM_PHISHING`: `131`
- `ZERO_AMOUNT_TOKEN_TRANSFER`: `38`
- `CLASSIFICATION_FAILED`: `28`
- `ROUTER_METHOD_OVERLOAD_UNSUPPORTED`: `21`
- `CLAIM_WITHOUT_MOVEMENT`: `9`
- `FAILED_TRANSACTION`: `4`
- `HANDLER_UNSUPPORTED_METHOD`: `3`

### 6.1 Router Overload Families

Main unresolved overload families:
- Base `0xc6a2db661d5a5690172d8eb0a7dea2d3008665a3`, outer `multicall(0xac9650d8)`, token transfer count `1`: `8`
- Optimism `0x416b433906b1b72fa758e166e239c43d68dc6f29`, outer `multicall(0xac9650d8)`, no persisted legs: `6`
- zkSync `0xdaee41e335322c85ff2c5a6745c98e1351806e98`, `0x3593564c`, multi-leg routed tx: `4`
- Arbitrum Pancake V3 MasterChef `0x5e09acf80c0296740ec5d6f643005a4ef8daa694`, outer `multicall(0xac9650d8)`: `2`

Evidence boundary matters here:
- `0xfffcf721...` on Base is explorer-decoded as `Transfer 1 of Pancake V3 Positions NFT-V1`, and raw has inbound USDC from the vault/stake contract. This is strongly suggestive of LP position custody/exit semantics.
- `0x927d3f45...` on Optimism is explorer-decoded as `Burn 1 of Slipstream Position NFT v1.2`, but current raw has no persisted token/internal legs, so explorer-only text should not silently redefine the canonical label.
- `0x6537cd02...` on Arbitrum is explorer-decoded as `Multicall` on PancakeSwap V3 Masterchef, and raw includes inbound USDC plus inbound internal ETH. This is a method-aware LP exit / fee-claim family, not random unknown.
- `0xb7a9086d...` on zkSync has persisted ETH and USDC movements under `0x3593564c`, so it remains a real router-overload gap rather than a metadata gap.

Conclusion:
- Base and Arbitrum multicall families are good candidates for method-aware LP exit / fee-claim handlers
- the Optimism Slipstream family still lacks enough persisted evidence in some rows; classifier work alone may not be sufficient for every member of the family
- zkSync `0x3593564c` remains a genuine router family requiring selector-aware dispatch over persisted raw

### 6.2 Zero-Amount / No-Op Families

Top zero-amount clusters:
- Arbitrum `0x0cf79e0a`: `10`
- Avalanche `a9059cbb transfer(...)`: `10`
- Avalanche `0x12514bba transfer(uint256 amnt)`: `8`
- Base `0x12514bba transfer(uint256 amount)`: `7`
- Unichain `batch(tuple[] items)`: `1`
- Ethereum zero-value `a9059cbb transfer(...)`: `1`

Financial conclusion:
- these rows are still non-economic from the tracked-wallet perspective
- they should not become `BUY/SELL`
- the remaining open question is product policy: explicit terminal non-economic type versus continued `UNKNOWN / NEEDS_REVIEW`

### 6.3 Remaining Classification-Failed Families

Largest residual clusters:
- Plasma spam method family `0x1939c1ff`: `5`
- Avalanche `batch(...)` on `0xddcbe30a...`: `4`
- Mantle `claim(...)` on `0x0045601c...`: `2`
- Optimism smart-account / config-like empty-selector families on `0x41c914...`, `0xbc6043...`, `0x4e1dcf...`
- Katana `routeSingle(...)` and `collect(...)` singletons

Conclusion:
- some of these are probably salvageable by selector recovery plus registry growth
- some should remain explicit review until richer persisted evidence exists

## 7. Protocol-Source Verification

To avoid inventing classification rules, the main open recommendations were checked against protocol sources.

Across official contracts:
- `depositV3` and `fillV3Relay` are explicit SpokePool entry/settlement functions in `SpokePool.sol`
- source: <https://raw.githubusercontent.com/across-protocol/contracts/master/contracts/SpokePool.sol>

Pancake Infinity CL position manager:
- `modifyLiquidities(...)` is a first-class entrypoint in `CLPositionManager.sol`
- source: <https://raw.githubusercontent.com/pancakeswap/infinity-periphery/main/src/pool-cl/CLPositionManager.sol>

Pancake SmartChef:
- `deposit(uint256)` and `withdraw(uint256)` are explicit stake/unstake functions in `SmartChefInitializable.sol`
- source: <https://raw.githubusercontent.com/pancakeswap/pancake-smart-contracts/master/projects/farms-pools/contracts/SmartChefInitializable.sol>

Merkl Distributor:
- `claim(...)` and `claimWithRecipient(...)` are explicit claim functions, and `claimWithRecipient` allows the reward recipient to differ from the calling user
- source: <https://raw.githubusercontent.com/AngleProtocol/merkl-contracts/main/contracts/Distributor.sol>

Velodrome / Slipstream position manager:
- the official `NonfungiblePositionManager` exposes `mint`, `decreaseLiquidity`, and `collect`
- source: <https://raw.githubusercontent.com/velodrome-finance/slipstream/main/contracts/periphery/NonfungiblePositionManager.sol>

These sources support the current rule direction:
- bridge entry vs bridge settlement should stay distinct
- CL position-manager families must be method-aware
- reward claims with custom recipients can legitimately produce `CLAIM_WITHOUT_MOVEMENT` for one tracked wallet and `REWARD_CLAIM` for another

## 8. Recommendations

Priority 1:
- fix clarification reason hygiene
- stop writing `MISSING_CONTRACT_ADDRESS` for non-creation rows
- start writing the actual missing receipt-safe fields, especially `MISSING_EFFECTIVE_GAS_PRICE`

Priority 2:
- keep the current `PENDING_CLARIFICATION` gate
- do not collapse these rows into `PENDING_PRICE`
- the queue is now mostly valid because execution status and effective gas price are actually absent

Priority 3:
- close the remaining router-overload families in a method-aware way using persisted raw only
- strongest candidates:
- Base `0xc6a2...` Pancake V3 position NFT transfer / exit family
- Arbitrum `0x5e09...` Pancake V3 MasterChef multicall family
- zkSync `0x3593564c` routed family

Priority 4:
- keep `CLAIM_WITHOUT_MOVEMENT` as a valid per-wallet terminal state
- do not force `REWARD_CLAIM` on BSC/Ethereum/Katana/Arbitrum rows when the tracked wallet does not actually receive the reward transfer

Priority 5:
- document a final terminal policy for zero-amount/no-op token families
- these rows are non-economic and should not remain ambiguous forever

Priority 6:
- continue selector-recovery parity work, but treat it as a medium-priority cleanup
- only `24` current review rows remain recoverable from persisted calldata

## 9. Representative Sources

Explorer pages:
- Base bridge redemption: <https://basescan.org/tx/0xd2cdbd7a1ade37a8032b713e9844351d2f58fbd872851a0e88203b8fbb695c5f>
- Unichain Across fill: <https://uniscan.xyz/tx/0x27978f7bf88cd7a4825b991ac6e461fa96be75b280add44236beb2e060c61ba3>
- BSC Pancake Infinity mint: <https://bscscan.com/tx/0x51dc36fc93e51dde5fafd1ab92d000d06104394d3179e3e39f0fcaa54cc53231>
- BSC Pancake Infinity modifyLiquidities: <https://bscscan.com/tx/0x0088de663d549fbc58dfa8dbba4180a346a580b1d6277254fa84a8ed9c27967a>
- Base unresolved router overload: <https://basescan.org/tx/0xfffcf7210f3c836192d28401f32b99e2bd49f90795c4236d2fd87c00e86976f4>
- Optimism unresolved Slipstream overload: <https://optimistic.etherscan.io/tx/0x927d3f458ada7e5ec67f77129e29edcaf2f69bd2b81490a42fec17c0cc3bd4fa>
- Arbitrum Pancake MasterChef multicall: <https://arbiscan.io/tx/0x6537cd02cabd5828d2dd584aae02480699914e8bcd790215ce1c09b0fd581091>
- zkSync unresolved router overload: <https://zksync.blockscout.com/tx/0xb7a9086def86956c896bb9a53326dacee73be2cf17c5741ea7c4e4e6f21c7afc>
- Arbitrum claim with recipient: <https://arbiscan.io/tx/0xf13356fe9449ec9e831395e0074622e88e362a8f317e6b110d093bfaa25d2702>
- BSC claim without movement sample: <https://bscscan.com/tx/0xa586770c653097bd905f0003edddcc59f295a8a64131a3ba0410d6fe43bb08e5>
- Plasma spam cluster: <https://plasmascan.to/tx/0xcbee5437edfe64d3abe9f7b6e0b02daf059405d348ae90ee08a81a53b933c0b6>

Official protocol sources:
- Across SpokePool: <https://raw.githubusercontent.com/across-protocol/contracts/master/contracts/SpokePool.sol>
- Pancake Infinity CLPositionManager: <https://raw.githubusercontent.com/pancakeswap/infinity-periphery/main/src/pool-cl/CLPositionManager.sol>
- Pancake SmartChefInitializable: <https://raw.githubusercontent.com/pancakeswap/pancake-smart-contracts/master/projects/farms-pools/contracts/SmartChefInitializable.sol>
- Merkl Distributor: <https://raw.githubusercontent.com/AngleProtocol/merkl-contracts/main/contracts/Distributor.sol>
- Velodrome Slipstream NFPM: <https://raw.githubusercontent.com/velodrome-finance/slipstream/main/contracts/periphery/NonfungiblePositionManager.sol>
