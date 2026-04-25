# Cycle 67 counterparty construction rules

## Purpose

Define how `counterpartyAddress` should be built on current canonical rows without confusing row-local counterparties with lifecycle pairing.

## Semantic boundary

- `counterpartyAddress` answers: "which contract or external peer did this row directly interact with?"
- `correlationId` answers: "which lifecycle does this row belong to?"
- `matchedCounterparty` answers: "which exact peer row is the other side of the lifecycle?"
- `protocolName` answers: "which protocol brand best describes the row?"

These fields are complementary. They are not substitutes for one another.

## Construction hierarchy

1. If the row is a protocol interaction and the interacted contract is deterministic, set `counterpartyAddress` to that contract.
2. If the row is a bridge source or destination with one deterministic settlement contract, set `counterpartyAddress` to the source or settlement contract and use `matchedCounterparty` for the lifecycle pair.
3. If the row is an external transfer and transfer evidence shows one unique external peer, set `counterpartyAddress` to that peer.
4. If there is no deterministic row-local peer, leave `counterpartyAddress = null` rather than fabricating one.

## Family-specific rules

| Family | `counterpartyAddress` rule | Pairing rule | Current evidence anchor |
| --- | --- | --- | --- |
| `SWAP` | interacted router or aggregator contract from raw tx | normally no lifecycle peer | `0x101c297...` |
| `WRAP` / `UNWRAP` | canonical wrapper contract | normally no lifecycle peer | `BASE/OPTIMISM/UNICHAIN/AVALANCHE wrapped-native rows` |
| `BRIDGE_OUT` | source bridge contract | deterministic `correlationId` + reciprocal `matchedCounterparty` to destination row | `0x9f6983...` |
| `BRIDGE_IN` | settlement contract when unique | deterministic `correlationId` + reciprocal `matchedCounterparty` to source row | `0x7d8c79...` |
| `LENDING_DEPOSIT` / `LENDING_WITHDRAW` | lending pool or gateway contract | lifecycle pairing only when there is a separate async peer | `0xc0ca8c...`, `0xfbbfd229...` |
| `VAULT_DEPOSIT` / `VAULT_WITHDRAW` | vault contract | usually row-local only, no external lifecycle pair | `0x0765f4...` |
| `LP_ENTRY` / `LP_EXIT` | position manager or LP pool contract | retain LP-position identity in `correlationId` | `0x091e3560...`, `0xac23f81...` |
| `EXTERNAL_TRANSFER_IN` | unique external sender from transfer evidence | do not fabricate same-wallet peer | live transfer gaps |
| `EXTERNAL_TRANSFER_OUT` | unique external recipient from transfer evidence | do not fabricate same-wallet peer | live transfer gaps |
| `REWARD_CLAIM` | reward distributor contract when present | no lifecycle pair unless the protocol documents one | Linea `release()` target |

## Negative rules

- Do not copy `matchedCounterparty` into `counterpartyAddress`.
- Do not infer `counterpartyAddress` from explorer prose or UI labels.
- Do not reuse a nearby row's `protocolName` as proof of counterparty.
- Do not create many-to-many counterparty groups when the evidence is ambiguous.
- Do not use the receipt token contract as the displayed counterparty when the interacted vault or pool contract is already known and more specific.

## Live gap priority table

| Type | Missing `counterpartyAddress` rows | Construction rule needed |
| --- | ---: | --- |
| SWAP | 268 | Use interacted router/aggregator contract from raw tx as row-local counterparty. |
| EXTERNAL_TRANSFER_IN | 187 | Use unique external sender from transfer evidence; do not synthesize same-wallet peers. |
| BRIDGE_OUT | 138 | Use source bridge contract as counterparty; pair lifecycle with `matchedCounterparty`. |
| EXTERNAL_TRANSFER_OUT | 127 | Use unique external recipient from transfer evidence. |
| BRIDGE_IN | 116 | Use destination settlement contract when unique; pair lifecycle with the source row. |
| LP_ENTRY | 90 | Use pool/position manager contract, not the LP token itself. |
| LENDING_DEPOSIT | 87 | Use lending pool/vault contract proven by the interacted address or receipt token. |
| LP_EXIT | 62 | Use pool/position manager contract, then allocate basis from the carried LP position. |
| LENDING_WITHDRAW | 44 | Use lending pool/vault contract proven by the interacted address or receipt token. |
| VAULT_DEPOSIT | 26 | Use vault contract proven by interacted address. |
| VAULT_WITHDRAW | 26 | Use vault contract proven by interacted address. |
| BORROW | 21 | Use debt/pool contract when selector plus debt markers prove the protocol. |
| REPAY | 16 | Use debt/pool contract when selector plus debt markers prove the protocol. |
| STAKING_DEPOSIT | 6 | Use staking contract; keep continuity in family carry. |
| STAKING_WITHDRAW | 1 | Use staking contract; keep lifecycle pairing separate from counterparty. |

## Current sample rows missing counterparty

```json
[
  {
"txHash": "0x0765f4b17d9961c3cd7e63b4dc1e69c948954d51816807a4ba51a8ac4709f977",
"networkId": "ARBITRUM",
"walletAddress": "0xf03b52e8686b962e051a6075a06b96cb8a663021",
"type": "VAULT_WITHDRAW",
"source": "ON_CHAIN",
"status": "CONFIRMED",
"classifiedBy": "FUNCTION_NAME",
"protocolName": null,
"protocolVersion": null,
"counterpartyAddress": null,
"matchedCounterparty": null,
"correlationId": null,
"continuityCandidate": false,
"missingDataReasons": [],
"blockTimestamp": {
  "$date": "2026-04-11T11:24:34Z"
},
"flows": [
  {
    "role": "TRANSFER",
    "assetSymbol": "eUSDC-6",
    "quantityDelta": {
      "$numberDecimal": "-975.179422"
    },
    "unitPriceUsd": null
  },
  {
    "role": "TRANSFER",
    "assetSymbol": "USDC",
    "quantityDelta": {
      "$numberDecimal": "975.179422"
    },
    "unitPriceUsd": null
  },
  {
    "role": "BUY",
    "assetSymbol": "USDC",
    "quantityDelta": {
      "$numberDecimal": "30.901588"
    },
    "unitPriceUsd": {
      "$numberDecimal": "1"
    }
  },
  {
    "role": "FEE",
    "assetSymbol": "ETH",
    "quantityDelta": {
      "$numberDecimal": "-0.000003229212526"
    },
    "unitPriceUsd": {
      "$numberDecimal": "2241.45"
    }
  }
]
  },
  {
"txHash": "0x101c297d1a67fc0a30fc96c1aa5cc8786d630bbb00d274c9dc7bbced4c6dccd7",
"networkId": "ARBITRUM",
"walletAddress": "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f",
"type": "SWAP",
"source": "ON_CHAIN",
"status": "CONFIRMED",
"classifiedBy": "PROTOCOL_REGISTRY",
"protocolName": "Velora/ParaSwap",
"protocolVersion": "V6.2",
"counterpartyAddress": null,
"matchedCounterparty": null,
"correlationId": null,
"continuityCandidate": false,
"missingDataReasons": [],
"blockTimestamp": {
  "$date": "2026-03-27T22:04:17Z"
},
"flows": [
  {
    "role": "SELL",
    "assetSymbol": "USDC",
    "quantityDelta": {
      "$numberDecimal": "-1E+1"
    },
    "unitPriceUsd": {
      "$numberDecimal": "1"
    }
  },
  {
    "role": "BUY",
    "assetSymbol": "ETH",
    "quantityDelta": {
      "$numberDecimal": "0.00503809154986367"
    },
    "unitPriceUsd": {
      "$numberDecimal": "1984.878579721442040245065454069926"
    }
  },
  {
    "role": "FEE",
    "assetSymbol": "ETH",
    "quantityDelta": {
      "$numberDecimal": "-0.00000373735854"
    },
    "unitPriceUsd": {
      "$numberDecimal": "1984.878579721442040245065454069926"
    }
  }
]
  },
  {
"txHash": "0x3b3f44afd40f9873d568aa1dcd05b97303e3ef478ce6b2f021fbbd54b78aadf1",
"networkId": "ARBITRUM",
"walletAddress": "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f",
"type": "SWAP",
"source": "ON_CHAIN",
"status": "CONFIRMED",
"classifiedBy": "PROTOCOL_REGISTRY",
"protocolName": "Velora/ParaSwap",
"protocolVersion": "V6.2",
"counterpartyAddress": null,
"matchedCounterparty": null,
"correlationId": null,
"continuityCandidate": false,
"missingDataReasons": [],
"blockTimestamp": {
  "$date": "2026-03-27T22:03:26Z"
},
"flows": [
  {
    "role": "SELL",
    "assetSymbol": "USDC",
    "quantityDelta": {
      "$numberDecimal": "-1E+1"
    },
    "unitPriceUsd": {
      "$numberDecimal": "1"
    }
  },
  {
    "role": "BUY",
    "assetSymbol": "WBTC",
    "quantityDelta": {
      "$numberDecimal": "0.0001517"
    },
    "unitPriceUsd": {
      "$numberDecimal": "65919.57811470006591957811470006592"
    }
  },
  {
    "role": "FEE",
    "assetSymbol": "ETH",
    "quantityDelta": {
      "$numberDecimal": "-0.000004292305024"
    },
    "unitPriceUsd": {
      "$numberDecimal": "1985.3"
    }
  }
]
  },
  {
"txHash": "0x7d8c79a327637fda080bcfa9204181359de791ccff95dd4b2f1b020b8af0b678",
"networkId": "ARBITRUM",
"walletAddress": "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f",
"type": "BRIDGE_IN",
"source": "ON_CHAIN",
"status": "CONFIRMED",
"classifiedBy": "HEURISTIC",
"protocolName": null,
"protocolVersion": null,
"counterpartyAddress": null,
"matchedCounterparty": "0x9f6983d00441ed13bf45e1b7ac34e94540fb61f58e4a9a2189826b1e761a2f7f",
"correlationId": "bridge:lifi:0x9f6983d00441ed13bf45e1b7ac34e94540fb61f58e4a9a2189826b1e761a2f7f",
"continuityCandidate": true,
"missingDataReasons": [],
"blockTimestamp": {
  "$date": "2026-03-24T11:05:44Z"
},
"flows": [
  {
    "role": "TRANSFER",
    "assetSymbol": "USDC",
    "quantityDelta": {
      "$numberDecimal": "28.920966"
    },
    "unitPriceUsd": null
  }
]
  },
  {
"txHash": "0x9f6983d00441ed13bf45e1b7ac34e94540fb61f58e4a9a2189826b1e761a2f7f",
"networkId": "KATANA",
"walletAddress": "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f",
"type": "BRIDGE_OUT",
"source": "ON_CHAIN",
"status": "CONFIRMED",
"classifiedBy": "HEURISTIC",
"protocolName": null,
"protocolVersion": null,
"counterpartyAddress": null,
"matchedCounterparty": "0x7d8c79a327637fda080bcfa9204181359de791ccff95dd4b2f1b020b8af0b678",
"correlationId": "bridge:lifi:0x9f6983d00441ed13bf45e1b7ac34e94540fb61f58e4a9a2189826b1e761a2f7f",
"continuityCandidate": true,
"missingDataReasons": [],
"blockTimestamp": {
  "$date": "2026-03-24T11:05:43Z"
},
"flows": [
  {
    "role": "TRANSFER",
    "assetSymbol": "vbUSDC",
    "quantityDelta": {
      "$numberDecimal": "-28.997378"
    },
    "unitPriceUsd": null
  },
  {
    "role": "FEE",
    "assetSymbol": "ETH",
    "quantityDelta": {
      "$numberDecimal": "-1.816572E-7"
    },
    "unitPriceUsd": {
      "$numberDecimal": "2154.5"
    }
  }
]
  },
  {
"txHash": "0xc9b422cdf001efacbfd843efdaa60a4d6d574c1bb1a1c1b070c7781c181c73e3",
"networkId": "KATANA",
"walletAddress": "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f",
"type": "SWAP",
"source": "ON_CHAIN",
"status": "CONFIRMED",
"classifiedBy": "HEURISTIC",
"protocolName": null,
"protocolVersion": null,
"counterpartyAddress": null,
"matchedCounterparty": null,
"correlationId": null,
"continuityCandidate": false,
"missingDataReasons": [],
"blockTimestamp": {
  "$date": "2026-03-24T11:03:37Z"
},
"flows": [
  {
    "role": "SELL",
    "assetSymbol": "KAT",
    "quantityDelta": {
      "$numberDecimal": "-2462.847703643179149015"
    },
    "unitPriceUsd": {
      "$numberDecimal": "0.01173"
    }
  },
  {
    "role": "BUY",
    "assetSymbol": "vbUSDC",
    "quantityDelta": {
      "$numberDecimal": "28.676798"
    },
    "unitPriceUsd": {
      "$numberDecimal": "1.007406878680614600624028875190319"
    }
  },
  {
    "role": "FEE",
    "assetSymbol": "ETH",
    "quantityDelta": {
      "$numberDecimal": "-0.0000027104792"
    },
    "unitPriceUsd": {
      "$numberDecimal": "2153.2"
    }
  }
]
  },
  {
"txHash": "0xac23f81f7c6b0b774201c1c1417d52cb6497947af864549fcdbe470e819eaf7f",
"networkId": "BSC",
"walletAddress": "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f",
"type": "LP_ENTRY",
"source": "ON_CHAIN",
"status": "CONFIRMED",
"classifiedBy": "PROTOCOL_REGISTRY",
"protocolName": "PancakeSwap",
"protocolVersion": "Infinity",
"counterpartyAddress": null,
"matchedCounterparty": null,
"correlationId": "lp-position:bsc:pancakeswap:750857",
"continuityCandidate": false,
"missingDataReasons": [],
"blockTimestamp": {
  "$date": "2026-03-10T12:54:14Z"
},
"flows": [
  {
    "role": "TRANSFER",
    "assetSymbol": "XYZ",
    "quantityDelta": {
      "$numberDecimal": "-78156.773205715470746505"
    },
    "unitPriceUsd": null
  },
  {
    "role": "FEE",
    "assetSymbol": "BNB",
    "quantityDelta": {
      "$numberDecimal": "-0.00002005"
    },
    "unitPriceUsd": {
      "$numberDecimal": "646.7"
    }
  }
]
  },
  {
"txHash": "0x8cd845033478862d78ae2214fa63e822a9dd217fab4c428801285eb1bb40d2e1",
"networkId": "BSC",
"walletAddress": "0x1a87f12ac07e9746e9b053b8d7ef1d45270d693f",
"type": "LP_EXIT",
"source": "ON_CHAIN",
"status": "CONFIRMED",
"classifiedBy": "PROTOCOL_REGISTRY",
"protocolName": "PancakeSwap",
"protocolVersion": "Infinity",
"counterpartyAddress": null,
"matchedCounterparty": null,
"correlationId": "lp-position:bsc:pancakeswap:643922",
"continuityCandidate": false,
"missingDataReasons": [],
"blockTimestamp": {
  "$date": "2026-03-10T12:48:44Z"
},
"flows": [
  {
    "role": "TRANSFER",
    "assetSymbol": "XYZ",
    "quantityDelta": {
      "$numberDecimal": "73999.99999999999999998"
    },
    "unitPriceUsd": null
  },
  {
    "role": "FEE",
    "assetSymbol": "BNB",
    "quantityDelta": {
      "$numberDecimal": "-0.0000064456"
    },
    "unitPriceUsd": {
      "$numberDecimal": "646"
    }
  }
]
  }
]
```
