# Warnings

- `PENDING_CLARIFICATION` is now mostly conceptually correct, but the reason payload is misleading.
- current rows in this state: `300`
- raw-side missing `txreceipt_status`: `300`
- raw-side missing `effectiveGasPrice`: `300`
- raw-side missing `gasUsed`: `0`
- actual contract-creation rows: `0`
- persisted `MISSING_CONTRACT_ADDRESS`: `300` and all `300` are spurious

- selector metadata is still incomplete.
- empty `rawData.methodId`: `1230`
- recoverable from `rawData.input[0:10]`: `856`
- current `NEEDS_REVIEW` rows still selector-recoverable: `24`

- `BSC` raw completeness is now healthy for the current wallet universe.
- provider count for `0x1a87...`: `33`
- persisted raw count for `0x1a87...`: `33`
- provider count for `0xf03b...`: `0`
- provider count for `0x68bc...`: `0`

- the old bridge native-leg contamination warning is no longer generally true.
- representative bridge rows were rechecked and the old bogus native flows are gone
- only `2` `BRIDGE_IN` rows still contain native legs, and both are supported by real raw transfers rather than copied token amounts

- the main reward family on `0x3ef3d8ba38ebe18db133cec108f4d14ce00dd9ae` is now mostly healthy.
- correctly normalized `REWARD_CLAIM`: `41`
- remaining review rows are narrow `CLAIM_WITHOUT_MOVEMENT` per-wallet cases, not broad family failure

- `PROMO_SPAM_PHISHING` looks materially cleaner than in older runs.
- dominant families remain obvious spam/promo clusters, especially Polygon promo spam and Plasma `.cfd` tokens
- no fresh false-positive cluster was confirmed this round for `redeemWithFee(...)` or `claimWithRecipient(...)`

- zero-amount/no-op families remain unresolved from a product-policy standpoint.
- `ZERO_AMOUNT_TOKEN_TRANSFER` review rows: `38`
- dominant clusters are on Arbitrum, Avalanche, and Base
- they should remain non-economic until a final terminal policy is documented

- router-overload review families remain the main semantic classification debt.
- strongest open clusters:
- Base `0xc6a2... + 0xac9650d8`: `8`
- Optimism `0x416b... + 0xac9650d8`: `6`
- zkSync `0xdaee... + 0x3593564c`: `4`
- Arbitrum `0x5e09... + 0xac9650d8`: `2`
