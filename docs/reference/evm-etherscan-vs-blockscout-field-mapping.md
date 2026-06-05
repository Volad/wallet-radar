# EVM `RawTransaction` Field Mapping: Etherscan vs Blockscout

## Scope
This document compares data sources for fields used by WalletRadar classification and scam filtering logic from `RawTransaction.rawData`.

Focus areas:
- `ingestion/classifier/*`
- `ingestion/filter/ScamFilter`
- `ingestion/job/classification/ClassificationProcessor`

## Notes
- Both Etherscan and Blockscout provide Etherscan-style `module/action` APIs.
- Blockscout also provides ETH JSON-RPC at `/api/eth-rpc`.
- Some fields (notably `functionName`) are provider conveniences and may be missing depending on instance/chain.

## Field Mapping Table
| Raw field path used in backend | Etherscan source | Blockscout source | Compatibility note |
|---|---|---|---|
| `rawData.blockNumber` | `account.txlist[].blockNumber` / `account.tokentx[].blockNumber` / proxy receipt `blockNumber` | `account.txlist[].blockNumber` / `account.tokentx[].blockNumber` / `eth_getTransactionReceipt.result.blockNumber` | Compatible |
| `rawData.timeStamp` | `account.txlist[].timeStamp`, `account.tokentx[].timeStamp` | `account.txlist[].timeStamp`, `account.tokentx[].timeStamp` | Compatible |
| `rawData.hash` / `txHash` | `txlist/tokentx/txlistinternal.hash` | `txlist/tokentx/txlistinternal.hash` | Compatible |
| `rawData.from` | `txlist[].from` | `txlist[].from` | Compatible |
| `rawData.to` | `txlist[].to` | `txlist[].to` | Compatible |
| `rawData.value` | `txlist[].value` | `txlist[].value` | Compatible |
| `rawData.input` | `txlist[].input`, `tokentx[].input` | `txlist[].input`, `tokentx[].input` | Compatible |
| `rawData.methodId` | Often present in Etherscan tx payloads (`methodId`) | Not consistently documented in `txlist` response | Derive from `input` first 4 bytes for portability |
| `rawData.functionName` | Often present in Etherscan tx payloads (`functionName`) | Not documented in `txlist` sample | Treat as optional hint only; derive by selector/ABI if needed |
| `rawData.status` | Proxy receipt: `module=proxy&action=eth_getTransactionReceipt` -> `result.status` | `/api/eth-rpc` `eth_getTransactionReceipt` -> `result.status` | Prefer receipt status in both providers |
| `rawData.txreceipt_status` | `txlist[].txreceipt_status` | `txlist[].txreceipt_status` (documented) or `module=transaction&action=gettxreceiptstatus` | Compatible with fallback |
| `rawData.isError` | `txlist[].isError`, `txlistinternal[].isError` | `txlist[].isError`, `txlistinternal[].isError`, `module=transaction&action=getstatus` | Compatible |
| `rawData.logs[]` | Proxy receipt logs (`eth_getTransactionReceipt`) | ETH RPC receipt logs (`eth_getTransactionReceipt`) or `transaction.gettxinfo` logs | Prefer ETH RPC receipt logs |
| `rawData.logs[].address` | `receipt.logs[i].address` | `receipt.logs[i].address` | Compatible |
| `rawData.logs[].topics` | `receipt.logs[i].topics` | `receipt.logs[i].topics` | Compatible |
| `rawData.logs[].data` | `receipt.logs[i].data` | `receipt.logs[i].data` | Compatible |
| `rawData.logs[].logIndex` | `receipt.logs[i].logIndex` | `receipt.logs[i].logIndex` | Compatible |
| `rawData.explorer.tokenTransfers[]` | `module=account&action=tokentx` | `module=account&action=tokentx` | Compatible |
| `...tokenTransfers[].from/to/value` | `tokentx` fields | `tokentx` fields | Compatible |
| `...tokenTransfers[].contractAddress` | `tokentx.contractAddress` | `tokentx.contractAddress` | Compatible |
| `...tokenTransfers[].tokenName` | `tokentx.tokenName` | `tokentx.tokenName` | Compatible |
| `...tokenTransfers[].tokenSymbol` | `tokentx.tokenSymbol` | `tokentx.tokenSymbol` | Compatible |
| `...tokenTransfers[].tokenDecimal` | `tokentx.tokenDecimal` | `tokentx.tokenDecimal` | Compatible |
| `...tokenTransfers[].logIndex` | May exist by provider | Not guaranteed in `tokentx` response | Use receipt logs as source of truth when needed |
| `rawData.explorer.internalTransfers[]` | `module=account&action=txlistinternal` | `module=account&action=txlistinternal` | Compatible |
| `...internalTransfers[].from/to/value` | `txlistinternal` fields | `txlistinternal` fields | Compatible |
| `...internalTransfers[].isError` | `txlistinternal.isError` | `txlistinternal.isError` | Compatible |
| `...internalTransfers[].traceId` | `txlistinternal.traceId` | `txlistinternal.traceId` | Compatible |
| `rawData.contractAddress` (token fallback) | Often from `tokentx.contractAddress` in fallback payloads | Same from `tokentx.contractAddress` | Compatible |
| `rawData.tokenName/tokenSymbol/tokenDecimal` (token fallback) | From token transfer payloads | From token transfer payloads | Compatible |

## Request Mapping (Etherscan -> Blockscout)
| Purpose | Etherscan | Blockscout equivalent | Recommended for backend |
|---|---|---|---|
| Normal tx list by address | `/api?module=account&action=txlist` | `/api?module=account&action=txlist` | Same contract, easy swap |
| ERC20 transfers by address | `/api?module=account&action=tokentx` | `/api?module=account&action=tokentx` | Same contract |
| Internal transfers by address | `/api?module=account&action=txlistinternal` | `/api?module=account&action=txlistinternal` | Same contract |
| Receipt status | `/api?module=transaction&action=gettxreceiptstatus` | `/api?module=transaction&action=gettxreceiptstatus` | Same contract |
| Execution status / error | `/api?module=transaction&action=getstatus` | `/api?module=transaction&action=getstatus` | Same contract |
| Full tx info | Usually `proxy` methods + txlist fields | `/api?module=transaction&action=gettxinfo` | Useful Blockscout-specific enrichment |
| Transaction by hash (JSON-RPC) | `/api?module=proxy&action=eth_getTransactionByHash` | `/api/eth-rpc` method `eth_getTransactionByHash` | Prefer ETH RPC style |
| Receipt by hash (JSON-RPC) | `/api?module=proxy&action=eth_getTransactionReceipt` | `/api/eth-rpc` method `eth_getTransactionReceipt` | Prefer ETH RPC style |
| Block by number (timestamp) | `/api?module=proxy&action=eth_getBlockByNumber` | `/api/eth-rpc` method `eth_getBlockByNumber` | Prefer ETH RPC style |
| Contract calls (`decimals/symbol/name`) | `/api?module=proxy&action=eth_call` | `/api/eth-rpc` method `eth_call` | Prefer ETH RPC style |

## Practical migration guidance
1. Keep existing `module=account` calls (`txlist`, `tokentx`, `txlistinternal`) unchanged; switch base URL to Blockscout instance.
2. For any logic dependent on `functionName`/`methodId`, rely on `input`-based derivation first.
3. For authoritative success/failure checks, use receipt `status` from `eth_getTransactionReceipt`.
4. For log-order-sensitive logic, prefer receipt logs (`logIndex`) over transfer list convenience payloads.

## Source references
- Blockscout migration guide: https://docs.blockscout.com/get-started/migration-guide
- Blockscout RPC-compatible API overview: https://docs.blockscout.com/devs/apis/rpc
- Blockscout account `txlist`: https://docs.blockscout.com/api-reference/account/get-a-list-of-transactions-by-address
- Blockscout account `txlistinternal`: https://docs.blockscout.com/api-reference/account/get-a-list-of-internal-transactions
- Blockscout account `tokentx`: https://docs.blockscout.com/api-reference/account/get-erc-20-token-transfer-events-by-address
- Blockscout ETH RPC methods: https://docs.blockscout.com/devs/apis/rpc/eth-rpc
- Blockscout transaction endpoints: https://docs.blockscout.com/devs/apis/rpc/transaction
