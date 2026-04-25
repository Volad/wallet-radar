# ETH basis summary

## Exact ETH dirty buckets

```json
[
  {
"walletAddress": "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f",
"networkId": "ARBITRUM",
"assetSymbol": "ETH",
"assetContract": "NATIVE:ARBITRUM",
"currentQuantity": 0.017999233483578642,
"coveredQuantity": 0.01358434690397287,
"uncoveredQuantity": 0.004414886579605773,
"latestType": "SWAP",
"latestProtocol": "Velora/ParaSwap",
"latestTxHash": "0x101c297d1a67fc0a30fc96c1aa5cc8786d630bbb00d274c9dc7bbced4c6dccd7",
"basisEffect": "GAS_ONLY",
"hasIncompleteHistoryAfter": true,
"hasUnresolvedFlagsAfter": true,
"unresolvedFlagCountAfter": 45
  },
  {
"walletAddress": "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f",
"networkId": "BASE",
"assetSymbol": "ETH",
"assetContract": "NATIVE:BASE",
"currentQuantity": 0.001092538442123013,
"coveredQuantity": 0,
"uncoveredQuantity": 0.001092538442123013,
"latestType": "BRIDGE_OUT",
"latestProtocol": null,
"latestTxHash": "0xda6537107ce79f1adca4abeeef7abc49da76cf4b23de3f17ee8c43cbfbb8c9b2",
"basisEffect": "GAS_ONLY",
"hasIncompleteHistoryAfter": true,
"hasUnresolvedFlagsAfter": true,
"unresolvedFlagCountAfter": 573
  },
  {
"walletAddress": "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f",
"networkId": "LINEA",
"assetSymbol": "ETH",
"assetContract": "NATIVE:LINEA",
"currentQuantity": 0.000136745050149994,
"coveredQuantity": 7.983686378461e-05,
"uncoveredQuantity": 5.690818636538401e-05,
"latestType": "SWAP",
"latestProtocol": "KyberSwap",
"latestTxHash": "0x4ce9ca5507ca6fdbef53baefeaf40f5caf85466d8cfa49e840f12b9f3626d329",
"basisEffect": "GAS_ONLY",
"hasIncompleteHistoryAfter": true,
"hasUnresolvedFlagsAfter": true,
"unresolvedFlagCountAfter": 3
  }
]
```

## ETH family dirty buckets

```json
[
  {
"walletAddress": "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f",
"networkId": "MANTLE",
"accountingAssetIdentity": "0xeac30ed8609f564ae65c809c4bf42db2ff426d2c",
"accountingFamilyIdentity": "FAMILY:ETH",
"assetSymbol": "AMANWETH",
"currentQuantity": 3.068981347069372,
"coveredQuantity": 3.06,
"uncoveredQuantity": 0.008981347069371814,
"normalizedType": "LENDING_DEPOSIT",
"protocolName": null,
"txHash": "0x3b8592a7465ce33bd64b266e11d1f665954cc3735be6e1590dffd226ac8664bd",
"basisEffect": "REALLOCATE_IN",
"hasIncompleteHistoryAfter": false,
"hasUnresolvedFlagsAfter": false,
"unresolvedFlagCountAfter": 0
  },
  {
"walletAddress": "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f",
"networkId": "ARBITRUM",
"accountingAssetIdentity": "NATIVE:ARBITRUM",
"accountingFamilyIdentity": "FAMILY:ETH",
"assetSymbol": "ETH",
"currentQuantity": 0.017999233483578642,
"coveredQuantity": 0.01358434690397287,
"uncoveredQuantity": 0.004414886579605773,
"normalizedType": "SWAP",
"protocolName": "Velora/ParaSwap",
"txHash": "0x101c297d1a67fc0a30fc96c1aa5cc8786d630bbb00d274c9dc7bbced4c6dccd7",
"basisEffect": "GAS_ONLY",
"hasIncompleteHistoryAfter": true,
"hasUnresolvedFlagsAfter": true,
"unresolvedFlagCountAfter": 45
  },
  {
"walletAddress": "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f",
"networkId": "BASE",
"accountingAssetIdentity": "NATIVE:BASE",
"accountingFamilyIdentity": "FAMILY:ETH",
"assetSymbol": "ETH",
"currentQuantity": 0.001092538442123013,
"coveredQuantity": 0,
"uncoveredQuantity": 0.001092538442123013,
"normalizedType": "BRIDGE_OUT",
"protocolName": null,
"txHash": "0xda6537107ce79f1adca4abeeef7abc49da76cf4b23de3f17ee8c43cbfbb8c9b2",
"basisEffect": "GAS_ONLY",
"hasIncompleteHistoryAfter": true,
"hasUnresolvedFlagsAfter": true,
"unresolvedFlagCountAfter": 573
  }
]
```

## ETH Arbitrum lineage tail

```json
[
  {
"blockTimestamp": "2026-02-19T10:38:11Z",
"txHash": "0xed89c9ba89f13a906b68e57c1b5a58c598fac0ef64ff17747a34740faf691d3c",
"type": "LENDING_DEPOSIT",
"protocolName": "Aave",
"basisEffect": "GAS_ONLY",
"quantityDelta": "-0.000003249458676",
"basisBackedQuantityAfter": "0.000009876482323998",
"uncoveredQuantityAfter": "0.004717379061842772",
"quantityShortfallAfter": "0",
"hasIncompleteHistoryAfter": true,
"hasUnresolvedFlagsAfter": true,
"unresolvedFlagCountAfter": 45
  },
  {
"blockTimestamp": "2026-02-19T10:38:37Z",
"txHash": "0xe6b8b8566a7a779325b6bbddcc6eb578afee4b1872e9dac4cb068d0e72d5921a",
"type": "SWAP",
"protocolName": "1inch",
"basisEffect": "GAS_ONLY",
"quantityDelta": "-0.00000316598",
"basisBackedQuantityAfter": "0.000006710502323998",
"uncoveredQuantityAfter": "0.004717379061842772",
"quantityShortfallAfter": "0",
"hasIncompleteHistoryAfter": true,
"hasUnresolvedFlagsAfter": true,
"unresolvedFlagCountAfter": 45
  },
  {
"blockTimestamp": "2026-02-23T18:39:33Z",
"txHash": "0x47e07d2d5560c415a75057ab18116b7d03eecb33f878eafb4d616b8561f413f8",
"type": "SWAP",
"protocolName": "Velora/ParaSwap",
"basisEffect": "ACQUIRE",
"quantityDelta": "0.008561799836019201",
"basisBackedQuantityAfter": "0.008568510338343199",
"uncoveredQuantityAfter": "0.004717379061842772",
"quantityShortfallAfter": "0",
"hasIncompleteHistoryAfter": true,
"hasUnresolvedFlagsAfter": true,
"unresolvedFlagCountAfter": 45
  },
  {
"blockTimestamp": "2026-02-23T18:39:33Z",
"txHash": "0x47e07d2d5560c415a75057ab18116b7d03eecb33f878eafb4d616b8561f413f8",
"type": "SWAP",
"protocolName": "Velora/ParaSwap",
"basisEffect": "GAS_ONLY",
"quantityDelta": "-0.00001144622",
"basisBackedQuantityAfter": "0.008557064118343199",
"uncoveredQuantityAfter": "0.004717379061842772",
"quantityShortfallAfter": "0",
"hasIncompleteHistoryAfter": true,
"hasUnresolvedFlagsAfter": true,
"unresolvedFlagCountAfter": 45
  },
  {
"blockTimestamp": "2026-02-23T18:41:40Z",
"txHash": "0xdaf7ed3f56f829fc965124b21741f20e83adabbff9d7a490fe5b3093edd287ad",
"type": "SWAP",
"protocolName": "1inch",
"basisEffect": "GAS_ONLY",
"quantityDelta": "-0.00000277910067",
"basisBackedQuantityAfter": "0.008554285017673199",
"uncoveredQuantityAfter": "0.004717379061842772",
"quantityShortfallAfter": "0",
"hasIncompleteHistoryAfter": true,
"hasUnresolvedFlagsAfter": true,
"unresolvedFlagCountAfter": 45
  },
  {
"blockTimestamp": "2026-03-27T22:03:26Z",
"txHash": "0x3b3f44afd40f9873d568aa1dcd05b97303e3ef478ce6b2f021fbbd54b78aadf1",
"type": "SWAP",
"protocolName": "Velora/ParaSwap",
"basisEffect": "GAS_ONLY",
"quantityDelta": "-0.000004292305024",
"basisBackedQuantityAfter": "0.008549992712649199",
"uncoveredQuantityAfter": "0.004717379061842772",
"quantityShortfallAfter": "0",
"hasIncompleteHistoryAfter": true,
"hasUnresolvedFlagsAfter": true,
"unresolvedFlagCountAfter": 45
  },
  {
"blockTimestamp": "2026-03-27T22:04:17Z",
"txHash": "0x101c297d1a67fc0a30fc96c1aa5cc8786d630bbb00d274c9dc7bbced4c6dccd7",
"type": "SWAP",
"protocolName": "Velora/ParaSwap",
"basisEffect": "ACQUIRE",
"quantityDelta": "0.00503809154986367",
"basisBackedQuantityAfter": "0.013588084262512869",
"uncoveredQuantityAfter": "0.004717379061842772",
"quantityShortfallAfter": "0",
"hasIncompleteHistoryAfter": true,
"hasUnresolvedFlagsAfter": true,
"unresolvedFlagCountAfter": 45
  },
  {
"blockTimestamp": "2026-03-27T22:04:17Z",
"txHash": "0x101c297d1a67fc0a30fc96c1aa5cc8786d630bbb00d274c9dc7bbced4c6dccd7",
"type": "SWAP",
"protocolName": "Velora/ParaSwap",
"basisEffect": "GAS_ONLY",
"quantityDelta": "-0.00000373735854",
"basisBackedQuantityAfter": "0.013584346903972869",
"uncoveredQuantityAfter": "0.004717379061842772",
"quantityShortfallAfter": "0",
"hasIncompleteHistoryAfter": true,
"hasUnresolvedFlagsAfter": true,
"unresolvedFlagCountAfter": 45
  }
]
```
