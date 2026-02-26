# Initial Balance Refresh on Wallet Add — Review Packet

Date: 2026-02-26  
Scope: backend implementation for immediate balance refresh on `POST /api/v1/wallets`

## What Was Implemented

- Added domain event `BalanceRefreshRequestedEvent`.
- `WalletBackfillService.addWallet(...)` now publishes:
  - `WalletAddedEvent` for backfill (unchanged behavior for pending networks),
  - `BalanceRefreshRequestedEvent` for immediate balance refresh on requested networks.
- Added `BalanceRefreshService` (shared path for:
  - initial refresh on wallet add,
  - periodic poll,
  - manual refresh endpoint).
- Added async event listener `WalletBalanceRefreshListener`.
- Added scheduled `CurrentBalancePollJob` (`walletradar.ingestion.balance.poll-interval-ms`, default 10 min).
- Added manual API endpoint:
  - `POST /api/v1/wallets/balances/refresh`
  - request: `wallets[]`, optional `networks[]`
  - response: `202 {"message":"Balance refresh triggered"}`
- Added `OnChainBalanceRepository`.
- Added `EconomicEventRepositoryCustom.findDistinctAssetContractsByWalletAddressAndNetworkId(...)` for token scope.

## Behavioral Notes

- `POST /wallets` remains non-blocking (`202`), refresh happens asynchronously.
- Refresh strategy:
  - always native balance,
  - ERC-20 token balances for known contracts from:
    - `economic_events` (distinct contracts by wallet/network),
    - existing `on_chain_balances` rows for wallet/network.
- Solana currently refreshes native balance (`getBalance`) only.

## Architect Review Focus

1. Confirm event-driven trigger and async execution model for initial refresh.
2. Confirm token scope strategy (`known assets only`) is acceptable for MVP without paid indexers.
3. Confirm scheduler defaults (`10 min`) and retry behavior from `RpcEndpointRotator` are sufficient.
4. Confirm package/module boundaries are acceptable:
   - `api -> ingestion.sync.balance`
   - no repository usage from API layer.

## Business Analyst Review Focus

1. Confirm UX expectation wording:
   - user sees initial balances shortly after add (async), not guaranteed in response.
2. Confirm acceptance criteria for manual refresh endpoint and poll cadence.
3. Confirm MVP limitation wording:
   - “all wallet tokens” is not guaranteed immediately; known token scope is used.
4. Confirm edge-case behavior:
   - backfill complete with no tx history still allows native on-chain balance visibility.

## Test Coverage Added

- `WalletBackfillServiceTest` (event publishing behavior updated).
- `BalanceRefreshServiceTest` (EVM native + known token upsert path).
- `WalletBalanceRefreshListenerTest`.
- `CurrentBalancePollJobTest`.
- `WalletAndSyncIntegrationTest` updated with `POST /wallets/balances/refresh` 202 test.
- `ModuleDependencyArchTest` passed after changes.

