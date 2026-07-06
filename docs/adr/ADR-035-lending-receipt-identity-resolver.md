# ADR-035: Lending receipt identity resolver

## Status

Accepted (2026-06-24)

## Context

Lending receipt tokens (aTokens, Euler EVK indexed shares, Morpho vault shares, Fluid/Compound/Silo receipts) were identified through fragmented symbol-prefix lists in `LendingAssetSymbolSupport` and `LendingProtocolNameSupport`. This caused:

- Euler indexed shares (`eWBTC-1`, `eUSDC-2`) not recognized as lending positions
- False `unresolved_principal_exit` when deposit underlying keys did not match live receipt balances
- Morpho vault shares (`gtUSDCc`, `MCUSDC`) missing from prefix lists
- Duplication with accounting (`AccountingAssetFamilySupport`) and classification (`protocols/*.json`) grammars

Normalization already resolves lending **protocol** from pool/vault contracts via `protocol-registry.json`, but the lending read model re-derived identity from symbols only.

## Decision

Introduce a shared **`LendingReceiptIdentityService`** that resolves `(networkId, contractAddress, assetSymbol)` → `{ protocol, underlyingSymbol, side }` with this priority:

1. **Derived contract index** — Mongo collection `lending_receipt_identity`, populated from normalized lending transactions by pairing receipt legs with underlying legs (`DERIVED_TX_PAIR`).
2. **Protocol registry** — pool/vault/Comet contracts that already carry `protocolName` (`REGISTRY`).
3. **Consolidated symbol grammar** — per-protocol fallback in `LendingAssetSymbolSupport` (`GRAMMAR`).

The resolver is used by:

- `LendingMarketKeyResolver` — contract-first market key for history and balance loops (see [ADR-036](ADR-036-contract-first-lending-market-key-and-live-debt.md))
- `SessionLendingQueryService` (cycle building, balance attachment, underlying/lifecycle keys, live debt-token BORROW positions)
- `LendingActiveMarketDiscoveryService` (rate collection market discovery)
- `OnChainNormalizationService` (protocol hint when registry lookup misses a known receipt contract; index build on each normalized lending tx)

**Deploy note:** clear `lending_receipt_identity` when rolling out ADR-036 so stale derived mappings do not shadow grammar/registry resolution. Market keys are query-time; no renormalization required for key correctness.

### Grammar fallback (when contract index miss)

| Protocol | Receipt pattern | Underlying |
|----------|-----------------|------------|
| Aave | `a{Net?}{Underlying}`, `variableDebt{Net?}{Underlying}` | stripped prefix |
| Euler | `e{Underlying}-{digits}`, `*DEBT` | indexed group / debt marker |
| Morpho | `gt{Underlying}c`, `mc{Underlying}`, `re7{Underlying}` | vault share body |
| Fluid | `f{Underlying}` | stripped prefix |
| Compound V2 | `c{Underlying}` | stripped prefix |
| Compound V3 | *(no receipt ticker)* | underlying asset via Comet contract |
| Silo | `so{Underlying}` | stripped prefix |

Collision guards: `EURC`, `EUSDE`, and similar real `E*` tokens are not treated as Euler receipts unless the indexed `e{X}-{N}` pattern matches.

## Consequences

- **Positive:** One lifecycle key for deposit, withdraw, and live receipt balance across Aave/Euler/Fluid/Compound/Morpho/Silo; fewer false unresolved cycles; normalization and read model share identity.
- **Positive:** Contract-first resolution survives symbol renames and network-specific receipt tickers.
- **Trade-off:** Derived index is a learning cache — first deposit/withdraw pair seeds a receipt contract; until then grammar fallback applies. Full backfill requires renormalization / lending query pass over history.
- **Related:** [ADR-036](ADR-036-contract-first-lending-market-key-and-live-debt.md) (market keys + live debt); [ADR-025](ADR-025-euler-evk-market-key.md) (superseded mechanism); lending family rules in `docs/pipeline/normalization/rules/families/lending.md`.

## Implementation

- `LendingReceiptIdentityService`, `LendingReceiptIdentityDocument`, `lending_receipt_identity` collection
- `LendingFactualApyNetStrategySupport` — asset-denominated net factual APY headline (USD weights only for exposure, not price P&L)
