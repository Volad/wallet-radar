# Solana/TON Dashboard & DeFi Correctness — Implementation Plan

Status: **DRAFT — awaiting approval** (no application code changed yet).

Session/universe: `df5e69cc-a0c0-4910-8b7d-74488fa266e2`.
Backing audit artifacts: `results/jupiter-ton-solana-*.md` (financial-logic-auditor).

## Scope

Wallets:
- Solana `9Grpx4HKXTe51Ug9nAYuND9qf2bw326WvxFyEULt1DhG`
- TON `UQAe4Uho4bZdfmCEqiyyuUH8ujmrsGJOwE2124OBDMVbS1Ms` (Ton wallet), `UQDcaquhb07rH_df1iy56EF5WUE_XGmq4KhNLPs-m7v9o37O` (Ton Defi)

Six reported symptoms → root causes:

| # | Symptom | Root cause | Stage | Class |
|---|---------|-----------|-------|-------|
| 1 | LP pools shown wrong (Solana as "Unknown pair"/Ethereum/Unavailable) | (a) `LpPositionRefreshService.normalizeAddress` lowercases base58; (b) no Solana LP on-chain enrichment (Meteora DLMM / Raydium CLMM); (c) open/closed logic EVM-only; (d) FE network badge from EVM-only map | read/enrichment | code gap |
| 2 | Jupiter USDC/SOL loop missing from lending | `SolanaTransactionClassifier.resolveLendingType` defaults all Jupiter Lend to `LENDING_DEPOSIT`; `buildLendingFlows` drops inbound borrow leg; no SOLANA `borrow_liabilities` | classification → move/cost basis | code gap (P0) |
| 3 | TON balances "wrong" (deposit not reflected) | NOT a balance bug — live TON jettons = 0 (correct). Perceived discrepancy = P1 phantom ledger residuals from TON jetton message fan-out (N× overcount) | normalization/ingestion | code gap (P1) |
| 4 | TON↔Solana transfer not shown | No on-chain TON↔Solana hop exists (value transited a CEX/bridge); non-EVM↔non-EVM venue-correlation corridor unsupported | linking | UNSUPPORTED_SCOPE (P2) — flag, don't drop |
| 5 | TON move basis empty | TON own↔own transfer booked asymmetrically: address canonicalization + jetton-wallet→owner resolution fail before `isOwnAddress`, so own transfers look external and no symmetric CARRY is emitted | clarification → linking | code gap (P2) |
| 6 | Solana & TON prices wrong / autoupdate broken | (a) SOL/TON absent from `tracked_price_assets` — registry rebuilt at startup before non-EVM balances landed (30-min fixed-delay cycle; fragile ordering); (b) Solana SPL / TON jettons have no price source (Bybit/Dzengi don't list them) | pricing | code gap + missing provider |

## Root cause (cluster summary from audit)

- **P0 (highest impact):** entire OPEN leveraged Jupiter Lend position invisible — ≈5.1 SOL collateral + ≈210 USDT debt; plus 3 phantom USDT SELL legs in loop tx `5YMocsUq…`. Program `jupr81YtYssSyPt8jbnGuiWon5f6x9TcDEFxYe3Bdzi`.
- **P1:** TON jetton fan-out → phantom ledger residuals (e.g. +61 USDT) vs 0 on-chain.
- **P2:** TON own-wallet carry asymmetric; TON↔Solana corridor unsupported.
- **LP display + pricing** (out of AVCO/ledger scope, my diagnosis): read-path + enrichment + price-registry ordering + missing non-EVM price provider.

## Changes — ordered (quick fixes first, per user; upstream-safe)

### Phase A — Quick, low-risk, no renormalization (`--backend-only` / `--frontend-only` / `--linking-only`)

- **A1 (issue 6a) — SOL/TON majors priced immediately.** Ensure the tracked-asset registry is rebuilt/priced after the on-chain balance refresh (portfolio-snapshot stage), or have the per-session `CurrentPriceQuoteRefreshService` include on-chain balance symbols (SOL, TON). Bybit already maps `SOLUSDT→SOL`, `TONUSDT→TON`. Deterministic, no accounting impact.
- **A2 (issue 1a) — LP read address casing.** Replace `LpPositionRefreshService.normalizeAddress` lowercasing with family-aware `WalletAddressReadScope.normalize` (base58 case-preserved, TON preferred member ref).
- **A3 (issue 1d) — FE non-EVM LP badge.** `lp-page.component.ts` network label/icon/color to handle SOLANA/TON (not fall back to EVM-only map / wrong Ethereum badge).
- **A4 (issue 1c) — Solana open/closed.** Make LP open/closed detection recognize Solana LP_EXIT (not only EVM `LP_EXIT_FINAL`) so genuinely open Meteora/Raydium positions aren't shown closed.

### Phase B — Normalization / accounting (needs `--skip-frontend` full renormalization + AVCO replay)

- **B1 (P0, issue 2) — Jupiter Lend borrow/loop.**
  - `SolanaTransactionClassifier.resolveLendingType`: classify by **net asset flow vs the `jupr81…` liquidity account**, not Helius `type` — BORROW / LENDING_WITHDRAW / LENDING_LOOP_OPEN/_REBALANCE / LENDING_DEPOSIT per rule pack RULE 1.
  - `SolanaNormalizedTransactionBuilder.buildLendingFlows`: stop dropping inbound legs (`outbound ? TRANSFER : null`) — emit inbound borrow leg.
  - `BorrowLiabilityTracker`: create SOLANA `borrow_liabilities` on BORROW, close on REPAY.
  - Accounting: borrowed principal enters at 0 cost basis; deposits/withdraws are CARRY; loop nets collateral, no phantom SPOT P&L.
- **B2 (P1, issue 3) — TON jetton fan-out dedup.** In TON raw→normalized path, dedup jetton transfers by `transaction_hash` (or `{jettonMaster, query_id, amount, from, to}`) before flow building — collapse async message fan-out to one economic event.
- **B3 (P2, issue 5) — TON own-wallet carry.** Canonicalize TON addresses to raw `0:<hex>` and resolve jetton-wallet→owner before `isOwnAddress`; own↔own transfers → INTERNAL both sides with symmetric CARRY_OUT/CARRY_IN (MOVE_BASIS).
- **B4 (P2, issue 4) — TON↔Solana corridor.** Explicitly flag as UNSUPPORTED_SCOPE (outbound EXTERNAL + Solana inbound uncovered receipt), documented — do not silently drop.

### Phase C — New capabilities (additive; `--backend-only` + refresh, except C2 LP)

- **C1 (issue 6b) — Non-EVM price providers (free public APIs, per approved Variant A).** New `LatestPriceProvider` backed by **Jupiter Price API** (SPL mints → USD; `JUPITER_API_KEY` in `.env`), keyed by mint/contract so SPL memecoins/mSOL price; add a TON price path (toncenter/public). Register in the latest-price cycle; keep stablecoin pins.
- **C2 (issue 1b) — Solana LP on-chain enrichment (biggest item).** Implement Meteora DLMM / Raydium CLMM / (Jupiter) position enrichment via free public REST (Jupiter/Meteora/Raydium) and/or Helius RPC to resolve token pair, amounts, TVL, unclaimed fees, in-range/open status — replacing the shell "On-chain enrichment unavailable" snapshot.

## Docs (before code where policy changes)

- Update `docs/pipeline/normalization/rules/families/solana.md` (Jupiter Lend borrow/loop rule), `.../ton.md` (jetton dedup + own-wallet carry canonicalization).
- Update `docs/reference/supported-networks-and-protocols.md` (Jupiter Lend, Meteora/Raydium LP enrichment, non-EVM pricing).
- New **ADR (needs explicit user approval)**: (i) Jupiter Lend accounting (borrow at 0 basis, loop carry); (ii) non-EVM latest-price provider (Jupiter/TON); (iii) Solana LP enrichment source; (iv) TON↔Solana UNSUPPORTED_SCOPE corridor decision.

## Acceptance

- **A1/6a:** `current_price_quotes` contains SOL and TON with fresh `pricedAt`; dashboard SOL/TON MtM correct.
- **A2/A3/A4/1:** LP view shows Solana positions with correct network badge, real token pair (after C2), correct open/closed.
- **B1/2:** Solana `BORROW≥1`; `borrow_liabilities` SOLANA ≈210 USDT OPEN; lending view shows one OPEN Jupiter loop (≈5.1 SOL / ≈210 USDT); no spurious USDT SELL legs in `5YMocsUq…`.
- **B2/3:** TON jetton ledger reconciles to live on-chain (0); TON↔TON own transfer (AMZNx 0.0877) shows symmetric carry.
- **B4/4:** TON↔Solana reported as unsupported corridor, not missing.
- **C1/6b:** Solana SPL / TON jettons priced where a public quote exists; unpriceable dust remains explicitly unpriced.
- **C2/1b:** Meteora/Raydium positions enriched (pair, TVL, fees) instead of "Unavailable".
- Post-change financial re-audit (financial-logic-auditor) passes with no regressions to EVM.

## Revision 1 — corrections from user + live-chain evidence (2026-07-19)

User feedback + live Helius/toncenter checks overturned several audit conclusions:

- **NEW ROOT DRIVER — stale/incomplete ingestion.** `sync_status` = COMPLETE (synced 2026-07-18) but normalized data ends **Solana 2026-06-05T20:09Z**, **TON 2026-05-04**. The bridge tx below is 2026-06-05T~20:13Z — ~4 min after the last ingested Solana tx. Non-EVM sync is not advancing to true head despite reporting COMPLETE. **This masks the loop debt growth, the bridge inbound, and TON DeFi deposits.** Must force a real head resync + fix the cursor/window defect. **This is prerequisite to validating everything else.**
- **Issue 2 (P0) confirmed real** and larger live: collateral ≈5.4218 SOL, debt ≈233.34 USDT (grew from ~210 via interest/further borrows not yet ingested).
- **Issue 3 correction:** both TON wallets hold **0 of every plain jetton** now (toncenter-confirmed) — balance provider is correct. The user's **64 USDT + 117 "gramm" (gold)** are held **inside TON DeFi vaults**: "Ton Defi" wallet (`UQDcaquhb…`) shows STON.fi vault (`stonfi_vault_v2`), **Affluent "Gold Multiply Vault" affGOLDm (gold, 8 dp)**, STON, plus xStocks equities MSTRx/AMZNx and Tether Gold XAUt0. → requires **TON DeFi position tracking** (new capability), not a balance-provider fix.
- **Issue 4 correction:** TON→Solana bridge is real and in scope. Example: Solana tx `48NMkoPb4Y7i8BQqgqSFA9jmnT4odXr5pjSjhd5kNo4xpfv1eYwLx8tQ13fW21SaHWYJ5XNyTBd1EkEAfFhNMao5` = **33.5 USDT** (mint `Es9vMFrz…`) received by `9Grpx4HK…` from relayer `2Hgx1Gj…`. Reclassify from UNSUPPORTED_SCOPE → **build a non-EVM bridge/venue-correlation corridor** (link TON jetton outbound ↔ Solana SPL inbound by asset+amount+time; carry basis).
- **Asset-family note:** TON now includes equities (MSTRx, AMZNx xStocks), Tether Gold (XAUt0), STON — pricing + family mapping must handle these (equity/commodity), not just crypto/stable.

### Phase 0 confirmed root causes (2026-07-19 deep dive)

Platform rule (user): **new wallets must backfill 2 years.** Three defects break this for non-EVM:

1. **2-year window not configured for SOLANA/TON.** `walletradar.ingestion.backfill.window-blocks` default = 5_500_000. Every EVM network overrides it to ~2y (e.g. ARBITRUM 252M @0.25s). SOLANA/TON have **no override** → inherit 5.5M. Measured block time ≈ **0.41 s** for BOTH (Solana slot 428200176→433700175 = 26d; TON seqno 75026830→80526829 = 5,499,999 blocks over 25.9d). So 5.5M ≈ **26 days**, not 2 years. Fix: set `window-blocks ≈ 165_000_000` and `avg-block-time-seconds ≈ 0.41` for SOLANA and TON (2y = 63.1M s / 0.41 ≈ 154M, +7% safety).
2. **Solana inbound SPL transfers to ATAs are never ingested.** Helius `/v0/addresses/{owner}/transactions` (getSignaturesForAddress on the owner) does NOT return transfers where the owner is only the recipient (owner pubkey absent from account keys; only its ATA is). Verified: newest returned = 2026-06-05T20:09:21 (= DB max), and the bridge sig `48NMko…` is absent. Fix: also fetch history for the wallet's token accounts (getTokenAccountsByOwner → per-ATA history / getSignaturesForAddress), dedup by signature. This is what makes the bridge inbound (33.5 USDT) and all received SPL tokens land.
3. **Partial fetch still marks the segment COMPLETE.** `HeliusSolanaNetworkAdapter.fetchTransactions` / `TonNetworkAdapter.fetchTransactions` catch `RpcException` mid-page and `break` returning partial results; `BackfillNetworkExecutor.processOneSegment` then marks the segment COMPLETE and advances the checkpoint to head. Under rate limiting (segments show `retryCount: 101`) this permanently skips the un-fetched older txs → the 2026-06-05→06-22 (Solana) / 2026-05-04→06-22 (TON) gaps. Fix: on mid-page failure that is not a natural end (empty/short page), rethrow so the segment is marked FAILED and retried (rely on client-level backoff), never complete on partial.

Phase 0 execution: apply (1)+(2)+(3), then **force a full 2-year resync** of Solana + TON (reset sync_status window/checkpoint + purge their raw/normalized/derived rows) and rebuild.

**Phase 0 STATUS: COMPLETE & VERIFIED (2026-07-19).** During the forced resync two additional defects surfaced and were fixed:
- (4) **N-segment fan-out for signature/offset-paging networks** (Solana, TON): the planner split the 2y window into 6 segments, and since these adapters ignore slot ranges (they page the full history per segment), all 6 ran identical full-history fetches in parallel → Helius HTTP 429 storm + retry death-spiral. Fix: `BackfillJobPlanner.resolveSegmentCount` returns exactly **1 segment** for SOLANA/TON; added `HeliusRequestThrottle` (client-side ~4 req/s, `walletradar.ingestion.solana.helius.min-request-interval-millis=250`) across Enhanced-API + RPC ATA calls.
- (5) **Parse endpoint 404**: `parseTransactions` did not normalize a configured trailing slash (`.../v0/transactions/?api-key=…`) the way `getTransactionHistory` did → 404 on every ATA enrichment. Fix: mirror the `"/?"→"?"` + trailing-slash strip in `parseTransactions`.

Verification: Solana raw 615→**4320** (ATA-inbound recovery), bridge tx `48NMko…` ingested + normalized as **+33.5 USDT** inbound; sync COMPLETE, retryCount 0, no 429. Solana normalized=669, TON=147. Config windows: SOLANA/TON `window-blocks=165_000_000`, `avg-block-time-seconds=0.41` (~2y @0.41s).

### Revised phase additions
- **Phase 0 (prerequisite) — Fix non-EVM incremental sync to reach true head; force full resync of Solana + TON.** Diagnose why cursor/window stops short while status=COMPLETE.
- **Phase B4 replaced:** implement TON↔Solana bridge corridor (supported), not unsupported-scope flag.
- **New Phase D — TON DeFi position tracking** (STON.fi vaults, Affluent Gold Multiply Vault, staking): normalize deposit/withdraw, represent vault positions + balances so 64 USDT / 117g gold surface. Largest new workstream; scope to confirm.

## Risks / ordering

- Phase B requires full renormalization + AVCO replay (`--skip-frontend`); re-measure the pre-existing conservation breach afterward (separate `conservation-b1b2`).
- Jupiter Price API rate limits / TON public source reliability → cache + retry (mirror existing Helius/toncenter client resilience).
- LP enrichment (C2) is the largest, most uncertain piece (protocol-specific math); ship after the accounting/pricing fixes so the dashboard is correct even before full LP enrichment lands.
- Cross-network TON↔Solana remains unsupported scope by design.
