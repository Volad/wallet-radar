# Compound (V3 Comet)

> **Registry family:** LENDING  
> **Semantic classifier:** `CompoundCometClassifier`

## Scope

Compound V3 Comet and Bulker `invoke(bytes32[],bytes[])` batch lifecycle on Base and other deployed networks.

## Owned normalized types

- `LENDING_DEPOSIT`, `LENDING_WITHDRAW` — supply/collateral
- `BORROW`, `REPAY` — debt operations
- Bulker bundles remain **one canonical row**; lending read model may expose child display legs

## Authoritative evidence

- Comet/Bulker contract in registry
- Decoded `invoke` action array
- Stable reclassification: supply while debt exists may rewrite to `REPAY` (`COMET_BASE_REPAY`)

## Clarification policy

Full-receipt for Bulker bundles when internal transfers missing.

## Correlation rules

Market key: `comet-{network}-market` in lending read model.

## Disallowed fallbacks

Generic swap on Bulker `invoke` when Comet classifier matches.

## Related

- [Lending family](../families/lending.md)
- [Lending cycle example](../../../../examples/lending-cycle-example.md)
