# ADR-065: Backfill enablement for non-EVM networks (Solana, TON)

**Status:** Accepted  
**Date:** 2026-07-18  
**Scope:** Backfill pipeline, accounting universe, ingestion config

---

## Context

The backfill pipeline was EVM-centric. Solana and TON wallets in the accounting universe had `backfillEnabled: false` forced by two hardcoded checks:

1. `AccountingUniverseService.resolveBackfillEnabled()` returned `false` whenever a member's networks contained `SOLANA` or `TON`.
2. `application.yml` had `SOLANA: fullIndex: false` and `TON: fullIndex: false`, so any newly-created member also defaulted to `backfillEnabled: false`.
3. `BackfillJobRunner` skipped networks without a registered `BlockHeightResolver` or `BlockTimestampResolver`.

---

## Decision

1. **Remove hardcoded override**: `resolveBackfillEnabled()` now returns `member.getBackfillEnabled()` if set, otherwise `true`. No special-casing for SOLANA/TON.
2. **Flip config**: Set `SOLANA: fullIndex: true` and `TON: fullIndex: true` in `application.yml`.
3. **Register resolvers**:
   - `SolanaBlockHeightResolver` — calls `getSlot` (finalized commitment) via Solana RPC.
   - `SolanaBlockTimestampResolver` — calls `getBlockTime(slot)` via Solana RPC.
   - `TonBlockHeightResolver` — calls `GET /masterchainInfo` to get latest seqno.
   - `TonBlockTimestampResolver` — returns `Instant.now()` (per-tx timestamp read from toncenter response).
   All resolvers use `@Order(1)` to take priority over the EVM generic resolver.
4. **One-time Mongo migration**: Existing SOLANA/TON universe members with stored `backfillEnabled: false` were updated to `true` via direct Mongo update.
5. **Address case fix**: `TrackedWalletProjectionService.toAddressSet()` now preserves case for non-EVM addresses using `WalletRef.parse()` domain detection.

---

## Consequences

- SOLANA and TON wallets will now be picked up by `BackfillJobRunner` and have backfill segments planned.
- New wallets added via Settings UI default to `backfillEnabled: true` for all three domains.
- If Helius or TON Center become unavailable, the adapters throw `RpcException` and the backfill segment retries per normal retry policy.
- The 2-year Solana backfill for `9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG` (~5000+ transactions) will complete across multiple backfill cycles.
