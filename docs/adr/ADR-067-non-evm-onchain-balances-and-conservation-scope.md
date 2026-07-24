# ADR-067: Non-EVM on-chain balance providers, family-aware read-path addressing, and SOL/TON conservation scope

**Status:** Accepted  
**Date:** 2026-07-19  
**Scope:** Portfolio read model, on-chain balance refresh, dashboard/lending read path, conservation gate

---

## Context

After Solana + TON ingestion, normalization, backfill (ADR-063/064/065/066) landed, the accounting ledger held Solana and TON positions (`asset_ledger_points`: SOLANA and TON holdings with `qty > 0`), but the dashboard surfaced **none** of them, and the lending view showed no Solana lending positions. Three independent read-path defects were traced:

1. **No non-EVM on-chain balance provider.** `OnChainBalanceRefreshService` was entirely EVM-specific (`eth_getBalance` / ERC-20 `balanceOf` via Ankr / Etherscan / BlockScout / EVM JSON-RPC). `on_chain_balances` therefore contained only EVM rows. `SessionDashboardQueryService.toView()` builds on-chain `tokenPositions` by iterating `on_chain_balances` and enriching with ledger points — so with no Solana/TON balance rows, no Solana/TON positions were ever emitted.

2. **Read path lowercased wallet addresses.** Dashboard and lending read paths lowercased every `walletAddress` before a case-sensitive Mongo `walletAddress $in` query and before in-memory bucket joins. This is safe for EVM/CEX but corrupts case-sensitive base58 Solana and friendly TON addresses, so their balance/ledger rows never matched the session wallet.

3. **SOL/TON hard-coded out-of-scope (OOS).** `OutOfScopeFamilySupport` excludes `FAMILY:SOL`, `FAMILY:TON` (and symbols `SOL`/`BBSOL`/`TON`/`TONCOIN`) from `adjustedMTM`/reportedPnL in `PortfolioConservationGate`. This was correct when SOL/TON only existed as CEX-boundary assets with no on-chain home. Now that on-chain SOL/TON wallets exist, surfacing their positions in `portfolioValueUsd` while they stay excluded from conservation `adjustedMTM` would skew the conservation delta.

---

## Decision

### 1. Per-family `OnChainBalanceProvider` SPI (EVM path unchanged)

Introduce `OnChainBalanceProvider` (`networkId()` + `fetchBalances(walletAddress)` → `ProviderBalance` list) in `application.costbasis.application.balance`, alongside the existing `OnChainBalance`, `OnChainBalanceRefreshQueryService`, and `OnChainBalanceRefresher` port. It lives in `costbasis` (not `portfolio.application`) so the family implementations may read the normalization-time metadata registries without the portfolio read model taking a compile-time dependency on the normalization pipeline (`ModuleBoundaryTest` rule `portfolio_read_model_must_not_depend_on_ingestion_job_or_pipeline_write`).

`OnChainBalanceRefreshService` now splits candidates into **EVM** (candidate-driven Ankr → Etherscan → BlockScout → JSON-RPC, unchanged) and **non-EVM** (provider-driven) sets, loads both, then applies the existing single `deleteBalances` → `saveAll` lifecycle. The EVM code path is byte-for-byte unchanged; when no providers are registered (unit tests), all candidates are EVM.

- **`SolanaOnChainBalanceProvider`**: native SOL via `getBalance` (lamports, 9 decimals, booked `NATIVE:SOLANA`); SPL tokens via `getTokenAccountsByOwner` with the SPL Token program id + `jsonParsed` encoding (amount + decimals per mint). Symbols resolve via `SolanaSplTokenMetadataRegistry` (USDC/USDT/wSOL seeded); unknown mints keep the mint as symbol. Endpoint wiring mirrors `SolanaNetworkAdapter`. Base58 is case-sensitive and never lowercased.
- **`TonOnChainBalanceProvider`**: native TON via `GET /accountStates` (nanoTON, 9 decimals, booked with the `TONCOIN` sentinel that `TonNormalizedTransactionBuilder` stamps on native flows); jettons via `GET /jetton/wallets?owner_address=...`. Decimals/symbols resolve via `TonJettonMetadataRegistry` then the response `metadata` block. **Jettons with no resolvable decimals are skipped** — booking them at the wrong native precision (9) would be off by orders of magnitude (dust/scam hygiene). The owner is passed in friendly form and the jetton master stored lowercased, matching `TonNormalizedTransactionBuilder#canonicalJettonContract`.

**Bounding:** non-EVM providers enumerate a wallet's live holdings directly from RPC (their canonical addresses never match the lowercased EVM candidate query), then results are bounded to the accounting-universe asset identities derived from the confirmed canonical candidate rows, **plus** the always-included network-native asset (`nativeAsset = true`). This keeps dust/scam tokens out while guaranteeing native SOL/TON always surface.

### 2. Family-aware read-path addressing

New `WalletAddressReadScope.normalize(address)` shared util: EVM/CEX lowercased (unchanged legacy behavior), Solana base58 case-preserved, TON collapsed to preferred canonical member ref (via `OnChainAddressClassifier.classifyDomain` + `TonAddressCanonicalizer`). Applied on all read paths that previously lowercased: `SessionDashboardQueryService`, `SessionLendingQueryService`, and `OnChainBalanceRefreshQueryService` candidate loading. The balance-writer and the read path now agree on address form so `$in` queries and bucket joins match.

### 3. SOL/TON: displayed but conservation-scoped

SOL/TON on-chain positions are now **displayed** on the dashboard (present in `portfolioValueUsd`, `totalUnrealizedPnlUsd`, and `tokenPositions`). `OutOfScopeFamilySupport` is **left unchanged**: SOL/TON remain OOS for the conservation identity because `PortfolioConservationGate` NEC computation has no boundary support for their external capital flows (NEC is EVM/CEX-tuned).

To keep the identity `reportedPnL ≈ adjustedMTM − NEC` balanced, the gate is fed **in-scope** mark-to-market and unrealized P&L that exclude OOS families symmetrically with the already-excluded OOS realized (`inScopeRealisedPnlUsd`). Because MtM, unrealized, and realized are all excluded for OOS families in lockstep, the conservation delta is unaffected by surfacing SOL/TON. CEX-held SOL/TON (e.g. Bybit) with no on-chain wallet continues to be handled exactly as before.

This is deliberately conservative: it fixes the visible bug (positions never surfaced) without asserting a conservation guarantee the NEC boundary cannot yet honor for these families. Bringing SOL/TON fully in-scope for conservation requires non-EVM NEC boundary support and is left to a follow-up.

---

## Consequences

- Solana (native SOL + SPL) and TON (native TON + jettons) positions now render on the dashboard; base58/friendly addresses match end-to-end.
- Solana lending positions surface **iff** an on-chain balance row exists for the receipt/collateral asset and a ledger point backs it; live health-factor/market-rate snapshotting remains EVM-only (see status note below).
- The conservation delta is unchanged for existing EVM/CEX portfolios; SOL/TON contribute to displayed portfolio value/PnL but not to the conservation identity.
- If Helius or TON Center are unavailable, the providers log a warning and return an empty list for that wallet (the scheduled refresh is retry-safe and never fails the whole snapshot).

### Lending status (scoped)

The lending view depends on the same balance/holdings + ledger read path, now fixed for addressing. Live lending **health-factor** and **market-rate** snapshots (`lending_health_factor_snapshots`, `lending_market_rate_snapshots`) are still produced only by EVM-specific refreshers (Aave v3 / Euler EVK / Morpho). Solana lending protocols (Kamino, Jupiter) have **no** health-factor/market-rate snapshotter yet. Solana lending positions surface via balances + ledger where present; live HF/APY for Solana lending is **out of scope** for this change and requires a dedicated Solana lending snapshotter (follow-up).

---

## References

- ADR-014 — Portfolio conservation gate (RC-8 OOS realized exclusion).
- ADR-034 — NEC computation via direct transaction scan (EVM/CEX-tuned boundary).
- ADR-063 / ADR-064 — Solana / TON normalization and acquisition (metadata registries).
- ADR-065 / ADR-066 — Non-EVM backfill enablement and per-family counterparty resolution.
- `ModuleBoundaryTest` — portfolio read model must not depend on the normalization pipeline (drove the `costbasis.application.balance` package placement).

---

## Addendum (2026-07-22): balance-capture hardening

**Context.** The single `deleteBalances` → `saveAll` lifecycle and the forced-live path proved fragile:
a still-held Base `aWETH` lot transiently vanished from `on_chain_balances` on a capture hiccup, which
silently moved the covered-qty-weighted headline ETH AVCO (see ADR-078 and
`results/eth_basis.md`). The accounting ledger was never wrong; only the read-model capture was. These
are internal robustness fixes to the refresh path (no contract change); the one policy change (what the
headline weighting does with a missing bucket) is ADR-078.

**Hardening decisions.**

1. **No silent drop on transient error.** A candidate that resolved a nonzero net-flow must never be
   silently skipped on a transient `decimals()`/`balanceOf`/RPC error; fall back to the last snapshot /
   indexed value and mark it, rather than emitting no row.
2. **Fallback chain for forced-live candidates.** `requiresLiveBalanceOf` (lending-receipt/debt)
   candidates go through the existing provider chain (Ankr → BlockScout → Etherscan → live RPC) instead
   of a single fallback-less live-RPC call. If **all** providers fail, retain the last-known snapshot
   and raise a health flag — never drop.
3. **Non-destructive per-bucket upsert + wipe-branch hardening.** Refresh upserts per bucket
   (idempotent by `_id = prefix:wallet:network:accountingIdentity`) instead of delete-then-write. The
   two existing wipe paths (the empty-candidate `deleteBalances` short-circuit and the global
   `deleteAll()`) must not run on a failed/partial fetch, so a transient query/RPC failure cannot erase
   the read model. A candidate that resolves an authoritative zero writes an explicit zero (so a real
   disposal still drops out — feeds the ADR-078 missing-vs-zero distinction).
