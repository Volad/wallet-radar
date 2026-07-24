# ADR-063: Solana normalization via Helius Enhanced Transactions API

**Status:** Accepted  
**Date:** 2026-07-18  
**Scope:** Solana ingestion, raw acquisition, normalization stage

---

## Context

WalletRadar needed to add Solana chain support. Solana's programming model differs fundamentally from EVM:
- Transactions invoke *programs* (not contracts) via instruction data; there are no ABI-encoded selectors or receipt event logs.
- Multiple protocols can be involved in a single transaction through CPIs (Cross-Program Invocations).
- Helius RPC provides an "Enhanced Transactions" REST API that pre-parses transactions into structured DeFi events (`type`, `source`, `events.swap`, `tokenTransfers`, `nativeTransfers`, `accountData`, `instructions`).

We already had Helius RPC credentials configured (`SOLANA_HELIUS_RPC_URL`, `SOLANA_HELIUS_PARSE_TRANSACTIONS_URL`, `SOLANA_HELIUS_PARSE_TRANSACTIONS_HISTORY_URL`).

---

## Decision

**Use Helius Enhanced Transactions REST API as the primary acquisition source for Solana.**

1. `HeliusSolanaNetworkAdapter` calls `GET /v0/addresses/{address}/transactions` (paginated by signature cursor) and stores the full Helius parsed payload in `rawData.heliusParsed`.
2. `SolanaRawTransactionView` reads all DeFi-relevant fields from the Helius payload without raw instruction decoding.
3. `SolanaTransactionClassifier` classifies by **program ID extracted from `instructions[]`**, treating Helius `source`/`type` as secondary hints only.
4. `SolanaNormalizedTransactionBuilder` builds canonical flows from `tokenTransfers`, `nativeTransfers`, `events.swap`, and `accountData`.
5. The base `SolanaNetworkAdapter` (JSON-RPC `getSignaturesForAddress` + `getTransaction`) is kept as a `@Order(2)` fallback for when Helius is not configured.

---

## Rationale

| Alternative | Why rejected |
|---|---|
| Raw JSON-RPC + instruction decoding | Very large engineering surface; each program has custom instruction layouts; borsh decoding needed per IDL |
| Helius `source`/`type` only (no program-ID check) | Observed wallets show Jupiter Lend, Hawksight, and Raydium rows labelled `UNKNOWN` — `source` alone is insufficient |
| Anchor IDL parsing | Added complexity; Helius Enhanced API already covers our top protocols |

The Helius API is already provisioned, free-to-use within our quota, and provides sufficiently structured data for full DeFi classification.

---

## Program-ID classification priority (see `SolanaTransactionClassifier`)

| Priority | Program ID | Classification |
|---|---|---|
| 1 | `jupr81YtYssSyPt8jbnGuiWon5f6x9TcDEFxYe3Bdzi` | Jupiter Lend (LENDING family) |
| 2 | `KLend2g3cP87fffoy8q1mQqGKjrxjC8boSyAYavgmjD` | Kamino Lend (LENDING) |
| 3 | `kvauTFR8qm1dhniz6pYuBZkuene38GjkNbFxHWe4s1o` | Kamino Vault (YIELD) |
| 4 | `LBUZKhRxPF3XUpBCjp4YzTKgLccjZhTSDM9YuVaPwxo` | Meteora DLMM (LP) |
| 5 | Meteora vault programs | Meteora Vault (YIELD) |
| 6 | `FqGg2Y1FNxMiGd51Q6UETixQWkF5fB92MysbYogRJb3P` | Hawksight → Meteora (LP) |
| 7 | `CAMMCzo5YL8w4VFF8KVHrK22GGUsp5VTaW7grrKgrWqK` | Raydium CLMM (LP/DEX) |
| 8 | `675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8` | Raydium AMM v4 (DEX) |
| 9 | `JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4` | Jupiter swap (AGGREGATOR) |
| 10 | Other aggregators (DFLOW, OKX) | SWAP |
| 11 | `Stake11111111111111111111111111111111111111` | Staking |
| 12 | System-program only | EXTERNAL_TRANSFER or INTERNAL_TRANSFER |

---

## Jupiter Lend looping (special case)

The user has an open SOL/USDC multiply (leveraged loop) position via Jupiter Lend. Because the loop is still open, replay must produce an **open lending compartment** — not realized P&L. The loop legs (collateral supply → borrow → re-deploy) are continuity-carry and must not be market-priced at the individual step. `LENDING_LOOP_OPEN` / `LENDING_LOOP_REBALANCE` types carry position identity via `correlationId = "sol-lending:jupiter-lend:{reserveAddress}"`.

---

## LP / lending / vault move-basis continuity (RC-S-LP)

Deposits into and withdrawals out of Solana LP / lending / vault positions are **basis-carrying continuity moves**, never market `BUY`/`SELL`. Booking a deposited underlying as a disposal (or a returned underlying as a fresh market acquisition) breaks AVCO.

- **LP_ENTRY / LP_EXIT roles.** Every wallet principal leg is normalized as `TRANSFER` (outbound-negated entry, inbound-positive exit). `LP_FEE_CLAIM` stays `LP_FEE_INCOME`.
- **Meteora DLMM position identity (NFT positions).** DLMM mints an NFT position, not a fungible LP token, so there is no receipt-token flow to link entry↔exit. `SolanaLpPositionResolver` derives `correlationId = "lp-position:solana:meteora-dlmm:{positionPda}"`, where the position PDA is `accounts[0]` of the largest Meteora DLMM `addLiquidity*` / `removeLiquidity*` instruction (≥ 10 accounts) in the Helius payload. The same PDA appears on the entry and the exit, so the position never splits. This is the Solana analogue of the EVM CL-NFT `lp-position:<network>:<nfpm>:<tokenId>` scheme (ADR-018) and routes the row through the shared `LpReceiptEntryReplayHandler` / `PositionScopedLpExitReplayHandler` receipt-pool machinery — deterministic on rerun, **no read-path RPC**.
- **Lending / vault deposit/withdraw.** A single `TRANSFER` principal leg per row; the shared network-agnostic replay classifier parks basis on deposit (`REALLOCATE_OUT`) and restores it on withdraw (`REALLOCATE_IN`), matching EVM lending/vault continuity. No Solana-specific replay handler is added.
- **UNSUPPORTED_SCOPE — Hawksight-managed positions.** When the LP principal is custodied and rebalanced inside a Hawksight vault PDA (`source = HAWKSIGHT`, `EXTENSION_EXECUTE`), internal rebalances never touch the tracked wallet and no DLMM instruction is present at the wallet level. The resolver returns `null` (no fabricated identity); such rows ride the generic family-continuity bucket (no phantom disposal/acquisition). Per-position isolation for Hawksight is not reconstructable from wallet-level Helius evidence — a documented, bounded limitation. Raydium CLMM / Meteora Dynamic AMM are likewise left to generic continuity until real tracked-universe evidence exists.

---

## Consequences

- Solana raw transactions land in `raw_transactions` with `rawData.source = "HELIUS_ENHANCED"` and `rawData.heliusParsed` containing the full parsed object.
- EVM normalization pipeline unchanged — Solana uses its own `SolanaRawTransactionView` and builder.
- `OnChainRawTransactionView.validationErrors()` no longer rejects SOLANA.
- `PendingRawTransactionQueryService` no longer excludes SOLANA.
- Helius quota must be monitored; fallback to base RPC adapter is automatic when `helius.parse-transactions-history-url` is blank.
