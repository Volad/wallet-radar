# EVM `RawTransaction` Field Mapping: Explorer Data vs RPC

## Scope
This document lists fields from `RawTransaction` payloads that are used by:
- classification (`ingestion/classifier/*`, `ingestion/job/classification/ClassificationProcessor`)
- scam/risk filtering (`ingestion/filter/ScamFilter`)
- timestamp/block helpers used during normalization.

The goal is to replace explorer-derived values (`rawData.explorer.*`) with RPC-derived data where possible.

## Legend
- `rawData.<...>` means BSON document inside `RawTransaction.rawData`.
- "RPC source" is the canonical JSON-RPC method and response path.
- "Derive" means post-processing from RPC response.

## Field Mapping Table
| RawTransaction field path | Used by (examples) | Purpose | Current explorer source | RPC source / derive |
|---|---|---|---|---|
| `txHash` | all classifiers, pipeline jobs | transaction identity | `tx.hash` / `tokentx.hash` / `txlistinternal.hash` | `eth_getLogs` result `transactionHash` or `eth_getTransactionByHash.result.hash` |
| `blockNumber` (top-level) | ordering, backfill ranges, timestamp estimation | tx block reference | `tx.blockNumber`, transfer `blockNumber` | `eth_getTransactionReceipt.result.blockNumber` (hex -> long) |
| `rawData.blockNumber` | `ClassificationProcessor.getBlockNumberFromRaw` | fallback block reference | `tx.blockNumber` / `tokentx.blockNumber` | `eth_getTransactionReceipt.result.blockNumber` |
| `rawData.logs[]` | `SwapClassifier`, `LendClassifier`, `TransferClassifier`, `BridgeCallClassifier`, `PerpOrderClassifier`, `NativeTransferClassifier` | primary event decoding | `proxy.eth_getTransactionReceipt.result.logs` | `eth_getTransactionReceipt.result.logs` |
| `rawData.logs[].address` | event decoders, scam filter | token/contract involved | receipt log `address` | `eth_getTransactionReceipt.result.logs[i].address` |
| `rawData.logs[].topics` | event decoders, scam filter | topic-based event type and addresses | receipt log `topics` | `eth_getTransactionReceipt.result.logs[i].topics` |
| `rawData.logs[].data` | event amount decoding | event numeric payload | receipt log `data` | `eth_getTransactionReceipt.result.logs[i].data` |
| `rawData.logs[].logIndex` | ordering and transfer pairing | deterministic event order | receipt log `logIndex` | `eth_getTransactionReceipt.result.logs[i].logIndex` |
| `rawData.from` | bridge/perp/native/lp/lend/scam logic | sender checks | `tx.from` | `eth_getTransactionByHash.result.from` (or receipt `from` if present) |
| `rawData.to` | router/protocol detection | destination checks | `tx.to` | `eth_getTransactionByHash.result.to` (or receipt `to`) |
| `rawData.value` | native value classification (bridge/perp/native/scam) | native amount in wei | `tx.value` | `eth_getTransactionByHash.result.value` |
| `rawData.input` | native-transfer/simple-call, approve/swap detection | calldata checks | `tx.input` | `eth_getTransactionByHash.result.input` |
| `rawData.methodId` | lend/bridge/perp/transfer/scam rules | function selector matching | `tx.methodId` | derive first 4 bytes from `input` |
| `rawData.functionName` | lend/lp/bridge/perp/transfer/scam heuristics | function semantic hints | `tx.functionName` | not from RPC directly; derive via ABI/signature DB using `methodId` |
| `rawData.status` | failed tx detection (`isFailedTx`) | execution success/failure | `proxy.eth_getTransactionReceipt.result.status` or `tx.status` | `eth_getTransactionReceipt.result.status` |
| `rawData.txreceipt_status` | failed tx detection | execution success/failure | `tx.txreceipt_status` | derive from `eth_getTransactionReceipt.result.status` (`0x0/0x1` -> `0/1`) |
| `rawData.isError` | failed tx detection | explorer-style failure flag | `tx.isError` | derive from receipt status (`status==0x0 -> isError=1`) |
| `rawData.timeStamp` | block timestamp fallback in `ClassificationProcessor` | event timestamp | `tx.timeStamp` | `eth_getBlockByNumber(blockNumber,false).result.timestamp` |
| `rawData.contractAddress` | `ScamFilter.collectTokenTransfers` fallback | token transfer fallback identity | `tokentx.contractAddress` | derive from ERC20 Transfer log `address` |
| `rawData.tokenSymbol` | `ScamFilter.collectTokenTransfers` fallback | scam/airdrop text heuristics | `tokentx.tokenSymbol` | `eth_call symbol()` |
| `rawData.tokenName` | `ScamFilter.collectTokenTransfers` fallback | scam/airdrop text heuristics | `tokentx.tokenName` | `eth_call name()` |
| `rawData.tokenDecimal` | `ScamFilter.collectTokenTransfers` fallback | scam/amount heuristics | `tokentx.tokenDecimal` | `eth_call decimals()` |
| `rawData.explorer.tx.*` | fallback readers `readRawOrExplorerTx(...)` | fallback when direct fields absent | `account.txlist` object | should be replaced by direct `rawData.*` from RPC tx/receipt |
| `rawData.explorer.tokenTransfers[]` | LP metadata, synthetic Transfer logs, scam filter, classification timestamp fallback | transfer-level hints & metadata | `account.tokentx` | derive from receipt `logs` with Transfer topic; token metadata via `eth_call` |
| `rawData.explorer.tokenTransfers[].from/to/value` | synthetic logs + classification/scam | transfer flow direction and amount | `tokentx` | decode ERC20 Transfer logs from `eth_getTransactionReceipt.logs` |
| `rawData.explorer.tokenTransfers[].contractAddress` | token address resolution | token identity | `tokentx.contractAddress` | `logs[i].address` for Transfer logs |
| `rawData.explorer.tokenTransfers[].tokenAddress` | LP/classification fallback token identity | alternative token identity key | `tokentx.tokenAddress` (if provider sets) | same as contract of Transfer log (`logs[i].address`) |
| `rawData.explorer.tokenTransfers[].logIndex` | synthetic log generation | preserve deterministic log ordering | `tokentx.logIndex` | `eth_getTransactionReceipt.result.logs[i].logIndex` |
| `rawData.explorer.tokenTransfers[].tokenDecimal` | amount normalization | decimals | `tokentx.tokenDecimal` | `eth_call` `decimals()` (`0x313ce567`) |
| `rawData.explorer.tokenTransfers[].tokenSymbol` | LP/scam heuristics | symbol heuristics | `tokentx.tokenSymbol` | `eth_call` `symbol()` (`0x95d89b41`) |
| `rawData.explorer.tokenTransfers[].tokenName` | LP/scam heuristics | name heuristics | `tokentx.tokenName` | `eth_call` `name()` (`0x06fdde03`) |
| `rawData.explorer.tokenTransfers[].timeStamp` | timestamp fallback | transfer timestamp | `tokentx.timeStamp` | same block timestamp from `eth_getBlockByNumber` |
| `rawData.explorer.internalTransfers[]` | `TransferClassifier`, `NativeTransferClassifier`, `ScamFilter` | internal native flows | `account.txlistinternal` | tracing RPC (`trace_transaction` / `debug_traceTransaction`) |
| `rawData.explorer.internalTransfers[].from/to/value` | transfer/native classifiers | direction and amount of internal native moves | `txlistinternal` | derive from trace call tree (`from`,`to`,`value`) |
| `rawData.explorer.internalTransfers[].isError` | internal transfer filtering | skip failed internals | `txlistinternal.isError` | derive from trace status/error (`error == null`) |

## Required RPC Requests
Use these requests to reconstruct all classification-relevant fields without Etherscan.

### 0) Transaction discovery by wallet (replacement for explorer `txlist`/`tokentx`)
```json
{
  "jsonrpc": "2.0",
  "id": 0,
  "method": "eth_getLogs",
  "params": [
    {
      "fromBlock": "0x<fromBlock>",
      "toBlock": "0x<toBlock>",
      "topics": [
        "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
        "0x000000000000000000000000<walletAddressNo0x>"
      ]
    }
  ]
}
```
And second query for inbound transfers:
```json
{
  "jsonrpc": "2.0",
  "id": 0,
  "method": "eth_getLogs",
  "params": [
    {
      "fromBlock": "0x<fromBlock>",
      "toBlock": "0x<toBlock>",
      "topics": [
        "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
        null,
        "0x000000000000000000000000<walletAddressNo0x>"
      ]
    }
  ]
}
```
Extract: `transactionHash` list -> then enrich each tx by hash (requests below).

### 1) Transaction core data
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "eth_getTransactionByHash",
  "params": ["0x<txHash>"]
}
```
Extract: `from`, `to`, `value`, `input`, `hash`, `blockNumber`.

### 2) Receipt + logs
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "eth_getTransactionReceipt",
  "params": ["0x<txHash>"]
}
```
Extract: `status`, `logs`, `gasUsed`, `effectiveGasPrice`, `contractAddress`, `blockNumber`.

### 3) Block timestamp
```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "eth_getBlockByNumber",
  "params": ["0x<blockNumberHex>", false]
}
```
Extract: `timestamp` (map to `timeStamp` fallback).

### 4) Token metadata (for Transfer log token contracts)
```json
{
  "jsonrpc": "2.0",
  "id": 4,
  "method": "eth_call",
  "params": [
    { "to": "0x<tokenContract>", "data": "0x313ce567" },
    "latest"
  ]
}
```
`decimals()`.

```json
{
  "jsonrpc": "2.0",
  "id": 5,
  "method": "eth_call",
  "params": [
    { "to": "0x<tokenContract>", "data": "0x95d89b41" },
    "latest"
  ]
}
```
`symbol()`.

```json
{
  "jsonrpc": "2.0",
  "id": 6,
  "method": "eth_call",
  "params": [
    { "to": "0x<tokenContract>", "data": "0x06fdde03" },
    "latest"
  ]
}
```
`name()`.

### 5) Internal native transfers (optional, if RPC supports tracing)
Preferred (Erigon/OpenEthereum style):
```json
{
  "jsonrpc": "2.0",
  "id": 7,
  "method": "trace_transaction",
  "params": ["0x<txHash>"]
}
```

Alternative (Geth debug API):
```json
{
  "jsonrpc": "2.0",
  "id": 8,
  "method": "debug_traceTransaction",
  "params": ["0x<txHash>", { "tracer": "callTracer" }]
}
```

## Gaps / Non-RPC-native fields
- `functionName`: not provided by Ethereum JSON-RPC. Must be derived via:
  - local selector map, or
  - external signature DB, or
  - verified ABI decode.
- Explorer-style `isError` / `txreceipt_status`: should be computed from `receipt.status`.
- `tokenTransfers` and `internalTransfers` arrays are explorer conveniences; in pure RPC they are derived artifacts.

## Recommended ingestion normalization (RPC-first)
1. Always store `eth_getTransactionByHash` + `eth_getTransactionReceipt` merge in `rawData`.
2. Build normalized helper arrays:
   - `tokenTransfers[]` from receipt Transfer logs.
   - `internalTransfers[]` from trace API when available.
3. Derive and persist:
   - `methodId`, `status/isError/txreceipt_status`, `timeStamp`.
4. Keep classifier readers on direct fields first, derived arrays second, explorer fallback last.
