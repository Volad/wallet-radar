# Cost basis engine (T-015, T-016)

Per-wallet AVCO and cross-wallet on-request aggregation. Used by backfill, override, manual tx, GET /assets.

---

## T-015 — AvcoEngine (per-wallet AVCO)

- **Module(s):** costbasis (engine)
- **Description:** Implement `AvcoEngine`: load economic_events for (wallet, asset) ORDER BY blockTimestamp ASC (include MANUAL_COMPENSATING). Apply active `cost_basis_overrides` to on-chain events only; manual events use own priceUsd. Run AVCO formula (BUY/SELL/INTERNAL_TRANSFER per 03-accounting). On SELL: set realisedPnlUsd, avcoAtTimeOfSale. Set hasIncompleteHistory if chronologically first event is SELL or transfer-out. Persist asset_positions. All arithmetic BigDecimal/Decimal128.
- **Doc refs:** 03-accounting (AVCO Formula, Realised P&L), 01-domain (INV-01, INV-07, INV-08, INV-09), 02-architecture (AvcoEngine)
- **DoD:** Engine implementation; unit tests with event sequences (BUY/SELL, INTERNAL_TRANSFER, override, manual compensating); integration test: persist events → run engine → verify positions.
- **Dependencies:** T-001, T-002, T-008

**Acceptance criteria**
- Events processed in blockTimestamp ASC; overrides apply to on-chain only; manual events use own priceUsd; hasIncompleteHistory set when first event is SELL/transfer-out; realisedPnlUsd and avcoAtTimeOfSale on SELL; unit and integration tests pass.

---

## T-016 — CrossWalletAvcoAggregatorService

- **Module(s):** costbasis (engine)
- **Description:** Implement on-request aggregation: given list of wallet addresses and assetSymbol, load all economic_events for that asset across those wallets; sort by blockTimestamp ASC; exclude INTERNAL_TRANSFER; run AVCO formula on merged timeline; return crossWalletAvco (and optionally quantity). Never persist. Use Caffeine cache (key: sorted(wallets)+assetSymbol, TTL 5min).
- **Doc refs:** 01-domain (Cross-Wallet AVCO, INV-04, INV-05), 03-accounting (Cross-Wallet AVCO), 02-architecture
- **DoD:** Service + cache; unit tests with multi-wallet event sets; verify INTERNAL_TRANSFER excluded and result not stored.
- **Dependencies:** T-015

**Acceptance criteria**
- crossWalletAvco computed on-request; INTERNAL_TRANSFER excluded; never persisted; cached per (sorted wallets, assetSymbol); unit tests pass.
