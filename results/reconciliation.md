# Bybit reconciliation summary, `BYBIT:33625378`

## Authoritative current truth
- total assets: `776.56 USD`
- available balance: `177.03 USD`
- in use / Earn: `599.52 USD`

## Reconciliation verdict
WalletRadar current Bybit projection does **not** reconcile to authoritative truth.

## Proven reasons
1. raw own-custody continuity rows exist, but normalized continuity rows do not
2. replay residue is being used as physical inventory truth
3. Earn / Launchpool principal and reward are not separated correctly
4. collateral / loan-state rows remain semantically too coarse

## Exact visible asset proof
- exact from raw:
  - `LINK 17.12740508`
  - `LDO 337.652448`
  - `ONDO 400.806778`
  - `DOGE 661.1697`
  - `USDT 93.3558`
  - `LTC 0.76065831`
  - `XRP 4.0533`
  - `ARB 36.50879889`
  - `XPL 3.70372886`
- materially proven but not corridor-closed:
  - `MNT 109.67`, unresolved decomposition remainder `0.11664154288053 MNT`

## Terminal interpretation
The live screenshot should be treated as the authoritative current balance surface for this account, while Mongo raw history explains the balance through continuity-aware rules and exposes the exact upstream classification defects that still prevent WalletRadar from producing the same result directly.
