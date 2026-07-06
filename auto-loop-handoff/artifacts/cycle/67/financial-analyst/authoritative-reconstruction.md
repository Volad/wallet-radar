# Cycle 67 authoritative reconstruction

## Operating result

The auditor-derived financially correct model for this live dataset is:

- same-wallet bridge source and destination rows are continuity, not disposal/reacquisition
- lending and vault receipt-token rows are continuity for the principal amount; only positive excess over carried principal is acquisition
- staking-wrapper transitions inside the same supported family are continuity for principal; only explicit excess is acquisition
- LP exits must close the carried LP-position basis and reallocate that basis onto returned assets; they must not stay `basisEffect=UNKNOWN`
- AVCO must consume the post-move-basis canonical result, not compensate for missing move-basis semantics after the fact

## Reconstructed supported flow classes

| Flow class | Auditor-derived canonical result | Basis treatment | AVCO treatment | Evidence anchors |
| --- | --- | --- | --- | --- |
| Same-wallet routed bridge | `BRIDGE_OUT` + `BRIDGE_IN` with one deterministic `correlationId` and reciprocal `matchedCounterparty` | carry full supported principal basis across networks; quantity drift stays inside the same continuity family | unchanged for carried principal | `0x9f6983...`, `0x7d8c79...` |
| ParaSwap / routed swap | canonical `SWAP` with router contract as row-local counterparty | dispose sold principal and acquire bought principal only after prior carry is already correct | AVCO updates only on explicit buy/sell legs | `0x101c297...` |
| Euler EVK vault withdraw | canonical `VAULT_WITHDRAW` with vault contract metadata and principal-vs-yield split | move carried basis from receipt token into underlying up to principal; treat excess underlying as acquisition | carried principal leaves AVCO unchanged; explicit excess updates AVCO | `0x0765f4...` |
| Mantle lending deposit | canonical `LENDING_DEPOSIT` with protocol/vault contract metadata | move basis from underlying into receipt token family-equivalent carry | unchanged for carried principal | `0xc0ca8c...` |
| Aave-style AVAX withdraw | canonical `LENDING_WITHDRAW` returning underlying AVAX from receipt token | move carried basis from `aAvaWAVAX` to native AVAX | unchanged for carried principal | `0xfbbfd229...` |
| Native AVAX staking wrapper | canonical `STAKING_DEPOSIT` into `sAVAX` | move carried AVAX-family basis into staking wrapper quantity | unchanged for carried principal | `0x682992de...` |
| PancakeSwap Infinity LP exit | canonical `LP_EXIT` with deterministic allocation from LP-position basis into returned assets | consume carried LP basis and split it across returned assets; do not leave returned principal uncovered | AVCO updates only for explicit acquisition excess after allocation | `0x091e3560...`, `0x8cd84503...` |

## Reference-asset reconstruction summary

| Asset | Exact coverage | Exact uncovered | Family coverage | Family uncovered | Exact final-clean | Family final-clean |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| ETH | 0.758585565758092328 | 0.005604525124182205 | 0.995413775064410755 | 0.014182496956267075 | no | no |
| BTC | 1 | 0 | 0.99998032365402878 | 8.999999999946551e-08 | yes | no |
| MNT | 1 | 0 | 0.008451767781545872 | 1675.520059541154068938 | yes | no |
| AVAX | 0.628797411316525223 | 0.093456895658393546 | 0.58750224244027005 | 1.101439081571954581 | no | no |
| USDC | 0.755283255824940825 | 330.858363999999937732 | 0.755283255824940825 | 330.858364000000051419 | no | no |
| USDT | 0.000218599015255947 | 1.234868226418219139 | 0.000218599015255947 | 1.234868226418219139 | no | no |

## Live lineage excerpts

### USDC Arbitrum tail

```json
[
  {
"blockTimestamp": "2026-02-19T07:40:08Z",
"txHash": "0x71d4cb955a881834d8e772a1a905700da91f455a85c7327aabac8b1f6e69e059",
"type": "SWAP",
"protocolName": "Velora/ParaSwap",
"basisEffect": "DISPOSE",
"quantityDelta": "-1151.3",
"basisBackedQuantityAfter": "0",
"uncoveredQuantityAfter": "35.714769",
"quantityShortfallAfter": "0",
"hasIncompleteHistoryAfter": true,
"hasUnresolvedFlagsAfter": true,
"unresolvedFlagCountAfter": 18
  },
  {
"blockTimestamp": "2026-02-19T10:38:37Z",
"txHash": "0xe6b8b8566a7a779325b6bbddcc6eb578afee4b1872e9dac4cb068d0e72d5921a",
"type": "SWAP",
"protocolName": "1inch",
"basisEffect": "ACQUIRE",
"quantityDelta": "0.31587",
"basisBackedQuantityAfter": "0.31587",
"uncoveredQuantityAfter": "35.714769",
"quantityShortfallAfter": "0",
"hasIncompleteHistoryAfter": true,
"hasUnresolvedFlagsAfter": true,
"unresolvedFlagCountAfter": 18
  },
  {
"blockTimestamp": "2026-02-23T18:39:33Z",
"txHash": "0x47e07d2d5560c415a75057ab18116b7d03eecb33f878eafb4d616b8561f413f8",
"type": "SWAP",
"protocolName": "Velora/ParaSwap",
"basisEffect": "DISPOSE",
"quantityDelta": "-16",
"basisBackedQuantityAfter": "0",
"uncoveredQuantityAfter": "20.030639",
"quantityShortfallAfter": "0",
"hasIncompleteHistoryAfter": true,
"hasUnresolvedFlagsAfter": true,
"unresolvedFlagCountAfter": 19
  },
  {
"blockTimestamp": "2026-02-23T18:41:40Z",
"txHash": "0xdaf7ed3f56f829fc965124b21741f20e83adabbff9d7a490fe5b3093edd287ad",
"type": "SWAP",
"protocolName": "1inch",
"basisEffect": "DISPOSE",
"quantityDelta": "-10.00306",
"basisBackedQuantityAfter": "0",
"uncoveredQuantityAfter": "10.027579",
"quantityShortfallAfter": "0",
"hasIncompleteHistoryAfter": true,
"hasUnresolvedFlagsAfter": true,
"unresolvedFlagCountAfter": 20
  },
  {
"blockTimestamp": "2026-03-10T12:47:02Z",
"txHash": "0x8186161871ab36192657b9e69c13ec0f9641f18a8afec56b965453ed55f77def",
"type": "EXTERNAL_TRANSFER_IN",
"protocolName": null,
"basisEffect": "ACQUIRE",
"quantityDelta": "326.955713",
"basisBackedQuantityAfter": "326.955713",
"uncoveredQuantityAfter": "10.027579",
"quantityShortfallAfter": "0",
"hasIncompleteHistoryAfter": true,
"hasUnresolvedFlagsAfter": true,
"unresolvedFlagCountAfter": 20
  },
  {
"blockTimestamp": "2026-03-24T11:05:44Z",
"txHash": "0x7d8c79a327637fda080bcfa9204181359de791ccff95dd4b2f1b020b8af0b678",
"type": "BRIDGE_IN",
"protocolName": null,
"basisEffect": "CARRY_IN",
"quantityDelta": "28.920966",
"basisBackedQuantityAfter": "355.632511",
"uncoveredQuantityAfter": "10.271747",
"quantityShortfallAfter": "0",
"hasIncompleteHistoryAfter": true,
"hasUnresolvedFlagsAfter": true,
"unresolvedFlagCountAfter": 21
  },
  {
"blockTimestamp": "2026-03-27T22:03:26Z",
"txHash": "0x3b3f44afd40f9873d568aa1dcd05b97303e3ef478ce6b2f021fbbd54b78aadf1",
"type": "SWAP",
"protocolName": "Velora/ParaSwap",
"basisEffect": "DISPOSE",
"quantityDelta": "-1E+1",
"basisBackedQuantityAfter": "345.632511",
"uncoveredQuantityAfter": "10.271747",
"quantityShortfallAfter": "0",
"hasIncompleteHistoryAfter": true,
"hasUnresolvedFlagsAfter": true,
"unresolvedFlagCountAfter": 21
  },
  {
"blockTimestamp": "2026-03-27T22:04:17Z",
"txHash": "0x101c297d1a67fc0a30fc96c1aa5cc8786d630bbb00d274c9dc7bbced4c6dccd7",
"type": "SWAP",
"protocolName": "Velora/ParaSwap",
"basisEffect": "DISPOSE",
"quantityDelta": "-1E+1",
"basisBackedQuantityAfter": "335.632511",
"uncoveredQuantityAfter": "10.271747",
"quantityShortfallAfter": "0",
"hasIncompleteHistoryAfter": true,
"hasUnresolvedFlagsAfter": true,
"unresolvedFlagCountAfter": 21
  }
]
```

### AVAX native tail

```json
[
  {
"blockTimestamp": "2026-01-05T09:38:55Z",
"txHash": "0x1d2227eb2c5f427e3571c55c8136c37879d8418f14776d7913315c88b8a0d27b",
"type": "SWAP",
"protocolName": "Velora/ParaSwap",
"basisEffect": "GAS_ONLY",
"quantityDelta": "-0.000010678977353221",
"basisBackedQuantityAfter": "0.609564799117845136",
"uncoveredQuantityAfter": "0.098433352611821618",
"quantityShortfallAfter": "0",
"hasIncompleteHistoryAfter": true,
"hasUnresolvedFlagsAfter": true,
"unresolvedFlagCountAfter": 22
  },
  {
"blockTimestamp": "2026-01-05T09:39:42Z",
"txHash": "0x682992de7690b96b0710f5817481994d0288f8ac4a4677e116372b38640a4cb4",
"type": "STAKING_DEPOSIT",
"protocolName": null,
"basisEffect": "REALLOCATE_OUT",
"quantityDelta": "-0.5",
"basisBackedQuantityAfter": "0.109564799117845136",
"uncoveredQuantityAfter": "0.098433352611821618",
"quantityShortfallAfter": "0",
"hasIncompleteHistoryAfter": true,
"hasUnresolvedFlagsAfter": true,
"unresolvedFlagCountAfter": 22
  },
  {
"blockTimestamp": "2026-01-05T09:39:42Z",
"txHash": "0x682992de7690b96b0710f5817481994d0288f8ac4a4677e116372b38640a4cb4",
"type": "STAKING_DEPOSIT",
"protocolName": null,
"basisEffect": "GAS_ONLY",
"quantityDelta": "-0.000008433753159949",
"basisBackedQuantityAfter": "0.109556365364685187",
"uncoveredQuantityAfter": "0.098433352611821618",
"quantityShortfallAfter": "0",
"hasIncompleteHistoryAfter": true,
"hasUnresolvedFlagsAfter": true,
"unresolvedFlagCountAfter": 22
  },
  {
"blockTimestamp": "2026-01-09T18:20:20Z",
"txHash": "0xce1ad77f24a2a48c1997b7147d67e29f98fa24e71d94fc3b4ebf6e8fa6dc77f6",
"type": "EXTERNAL_TRANSFER_IN",
"protocolName": null,
"basisEffect": "ACQUIRE",
"quantityDelta": "0.04822439142498889",
"basisBackedQuantityAfter": "0.157780756789674077",
"uncoveredQuantityAfter": "0.098433352611821618",
"quantityShortfallAfter": "0",
"hasIncompleteHistoryAfter": true,
"hasUnresolvedFlagsAfter": true,
"unresolvedFlagCountAfter": 22
  },
  {
"blockTimestamp": "2026-01-11T11:56:30Z",
"txHash": "0xd4b8de8881f203bfe3ecca7c8cc4d47113b91f1029f9bb3e9af2c883fcb04aaa",
"type": "UNKNOWN",
"protocolName": null,
"basisEffect": "GAS_ONLY",
"quantityDelta": "-0.000041014577830902",
"basisBackedQuantityAfter": "0.157739742211843175",
"uncoveredQuantityAfter": "0.098433352611821618",
"quantityShortfallAfter": "0",
"hasIncompleteHistoryAfter": true,
"hasUnresolvedFlagsAfter": true,
"unresolvedFlagCountAfter": 23
  },
  {
"blockTimestamp": "2026-01-12T09:56:41Z",
"txHash": "0xc8b94615c88aa7500fe80086a3afe53f96a280fd459f64d333bb9d8b28a74079",
"type": "VAULT_WITHDRAW",
"protocolName": null,
"basisEffect": "GAS_ONLY",
"quantityDelta": "-0.000020536337574828",
"basisBackedQuantityAfter": "0.157719205874268347",
"uncoveredQuantityAfter": "0.098433352611821618",
"quantityShortfallAfter": "0",
"hasIncompleteHistoryAfter": true,
"hasUnresolvedFlagsAfter": true,
"unresolvedFlagCountAfter": 23
  },
  {
"blockTimestamp": "2026-01-12T09:59:06Z",
"txHash": "0x96cd19c322a3f12905480f33ed5a04891d6ebe78da23b1c036dbe9f31f72c00e",
"type": "EXTERNAL_TRANSFER_OUT",
"protocolName": null,
"basisEffect": "GAS_ONLY",
"quantityDelta": "-0.00000959415017603",
"basisBackedQuantityAfter": "0.157709611724092317",
"uncoveredQuantityAfter": "0.098433352611821618",
"quantityShortfallAfter": "0",
"hasIncompleteHistoryAfter": true,
"hasUnresolvedFlagsAfter": true,
"unresolvedFlagCountAfter": 23
  },
  {
"blockTimestamp": "2026-01-12T10:00:03Z",
"txHash": "0x5e30c5086e680e2c6313074d796af64051338d9e1b0a27882c1ce1f436f18543",
"type": "EXTERNAL_TRANSFER_OUT",
"protocolName": null,
"basisEffect": "GAS_ONLY",
"quantityDelta": "-0.000009517779905556",
"basisBackedQuantityAfter": "0.157700093944186761",
"uncoveredQuantityAfter": "0.098433352611821618",
"quantityShortfallAfter": "0",
"hasIncompleteHistoryAfter": true,
"hasUnresolvedFlagsAfter": true,
"unresolvedFlagCountAfter": 23
  }
]
```

### USDT BSC exact tail

```json
[
  {
"blockTimestamp": "2026-03-10T12:48:17Z",
"txHash": "0x091e356020745e6555732067a025cee9243dee6848c47dd8eb97259293735e70",
"type": "LP_EXIT",
"protocolName": "PancakeSwap",
"basisEffect": "UNKNOWN",
"quantityDelta": "1.234868226418219121",
"basisBackedQuantityAfter": "0",
"uncoveredQuantityAfter": "1.234868226418219121",
"quantityShortfallAfter": "0",
"hasIncompleteHistoryAfter": true,
"hasUnresolvedFlagsAfter": true,
"unresolvedFlagCountAfter": 1
  }
]
```

## Reconstruction boundary

- On-chain supported surfaces above are reconstructed from current raw and current replay evidence
- Bybit family reconstruction is limited by the absence of `external_ledger_raw` in the live DB snapshot and is therefore handled as a broader-goal evidence boundary, not as a normal supported on-chain normalization defect
