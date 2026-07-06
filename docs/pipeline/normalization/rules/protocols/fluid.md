# Fluid

> **Registry family:** LENDING, YIELD  
> **Classifier:** `FluidVaultClassifier`

## Scope

Fluid vault `operate`, wrapper NFT/cast supply, borrow, withdraw, payback on supported networks (including Plasma).

## Owned normalized types

- `LENDING_DEPOSIT`, `LENDING_WITHDRAW`
- `BORROW`, `REPAY`
- `VAULT_DEPOSIT`, `VAULT_WITHDRAW` — wrapper paths

## Authoritative evidence

- Decoded intent from calldata before visible transfer direction
- `LogOperate`, NFT `Transfer`, ERC-20 transfer log references in `metadata` / `clarificationEvidence`
- Post-reclassification Fluid receipt recovery for supported rows lacking durable logs

## Clarification policy

Full-receipt recovery reruns reclassification; not a read-time RPC path.

## Receipt identity

Fluid receipt tickers use `f{Underlying}` (e.g. `fUSDC`, `fWETH`). Many flows surface the **underlying** directly (`USDC`, `USDT0`); identity then comes from the vault contract via `LendingReceiptIdentityService` (registry + derived index).

**Market key:** `vault-{receiptOrVaultContract[2..10]}` — history and balance paths share `LendingMarketKeyResolver` (not static `vault-account`). See [ADR-035](../../../../adr/ADR-035-lending-receipt-identity-resolver.md) and [ADR-036](../../../../adr/ADR-036-contract-first-lending-market-key-and-live-debt.md).

## Correlation rules

Vault address + NFT id → deterministic position/lifecycle key.

## Disallowed fallbacks

Transfer-direction-only classification when decoded Fluid intent is present.

## Related

- [Vault family](../families/vault.md)
- [Lending family](../families/lending.md)
