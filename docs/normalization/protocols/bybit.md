# Bybit Protocol Rules

Status: Active protocol rule slice

## Scope

Cover the current `Bybit -> on-chain` continuity contract for:

- matched `withdraw_deposit` rows
- unmatched `withdraw_deposit` rows
- excluded review tails that are intentionally outside accounting

This document does not redefine general `SWAP`, `REWARD_CLAIM`, `BORROW`, or
`REPAY` semantics inside Bybit. It owns only the continuity boundary between
Bybit ledger rows and wallet-visible on-chain movements.

## Runtime Ownership

- Bybit normalization:
  [BybitNormalizationService.java](/Users/vladislavkondratenko/projects/wallet-radar/backend/src/main/java/com/walletradar/ingestion/job/bybit/BybitNormalizationService.java)
- Bybit canonical builder:
  [BybitCanonicalTransactionBuilder.java](/Users/vladislavkondratenko/projects/wallet-radar/backend/src/main/java/com/walletradar/ingestion/pipeline/bybit/BybitCanonicalTransactionBuilder.java)

## Authoritative Evidence

- `external_ledger_raw`
- persisted on-chain `normalized_transactions`
- deterministic correlation evidence available at normalization time:
  - same tx hash where applicable
  - same asset identity
  - same wallet / venue boundary
  - same source / destination network continuity

## Classification Rules

### Matched continuity

- A Bybit `withdraw_deposit` row may stay active accounting scope only when the
  runtime can deterministically correlate it with the corresponding on-chain
  movement.
- Bybit normalization may perform a late exact rematch during rerun for rows
  already marked `onChainCorrelation.status = UNMATCHED`, but only when the
  exact on-chain evidence is now present in Mongo:
  - same `txHash`
  - same `networkId`
  - persisted `raw_transactions` row exists
  - persisted on-chain `normalized_transactions` row exists
- Once correlated:
  - both sides must share the same `correlationId`
  - both sides must expose `matchedCounterparty`
  - replay may treat the pair as same-universe continuity

### Unmatched continuity

- A non-excluded Bybit row with `missingDataReasons += BRIDGE_ON_CHAIN_LEG_NOT_FOUND`
  is not move-basis-ready.
- Such a row must not be treated as accounting-complete simply because its
  canonical type is `EXTERNAL_TRANSFER_IN` or `EXTERNAL_TRANSFER_OUT`.
- Until its tracked-universe on-chain leg is found, it remains an explicit
  continuity blocker.

### External custody policy

- `withdraw_deposit` rows are not tracked-universe bridge continuity by default.
- If exact tracked-universe on-chain evidence is absent and the venue address is
  outside the tracked wallet universe, the row must be treated as
  `external custody`, not as an unresolved tracked-universe bridge.
- In that case:
  - canonical type stays `EXTERNAL_TRANSFER_IN` or `EXTERNAL_TRANSFER_OUT`
  - no synthetic `correlationId` is created
  - no synthetic `matchedCounterparty` is created
  - `BRIDGE_ON_CHAIN_LEG_NOT_FOUND` is not persisted
  - normalized rows must not remain active priceable `BUY` / `SELL` rows
  - until a dedicated external-custody move-basis contract exists, these rows
    stay explicit audit-only excluded rows outside the active accounting scope

## Clarification Rules

- The current clarification stage does not rescue unmatched Bybit continuity by
  itself.
- Matching must come from deterministic normalization-time continuity logic, not
  from audit-only external enrichment.

## Disallowed Fallbacks

- do not treat unmatched `withdraw_deposit` rows as fully accounting-ready
  transfers
- do not silently carry move basis across the Bybit boundary without
  `correlationId` plus `matchedCounterparty`
- do not hide `BRIDGE_ON_CHAIN_LEG_NOT_FOUND` by downgrading the row into an
  operator-invisible state

## Current Policy Notes

- The old inherited unmatched tail is now split into two policy lanes:
  - deterministic tracked-universe continuity gaps, which remain explicit
    bridge blockers until matched
  - audited external-venue / external-custody rows, which stay active as plain
    `EXTERNAL_TRANSFER_IN/OUT`, but no longer pretend to be missing bridge legs
    and no longer participate in active pricing / replay until a dedicated
    external-custody accounting lane exists
- A separate excluded tail of `11` Bybit `NEEDS_REVIEW` rows remains acceptable
  only because `excludedFromAccounting = true`.

## Regression Anchors

Representative unmatched continuity rows:

- `0x01ffd1e6cdc1dd4be3bcc22164b7d800fb8b9d6751daa704f2b1c17dafa2e127`
- `0x061965f4e02f59257195660e3a46fc68fc4e045be4e7cb2f1369472cb0a34958`
- `0x1a706ffebbc4a99a657cc10f38911b895da7f8656b9d1e0d67642640cfa6ab56`
- `0x2e25b035ef2bdf7a99d260f07e69b2b114a2a2cb571b64fe3aa8b18fb399416e`
- `0x2efc1c7051cb0f55aa591886fd93903f0bdc5f3057c35ff992ccc6ae2ac00d71`

All five are currently non-excluded Bybit transfer rows with
`BRIDGE_ON_CHAIN_LEG_NOT_FOUND` and no `correlationId` / `matchedCounterparty`.
