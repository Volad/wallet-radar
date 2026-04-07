# Spam Family Rules

Status: Migration target

## Scope

Own explicit spam, phishing, promo-drop, and other clearly non-economic scam
families that should not be mixed into generic `UNKNOWN`.

## Owned Normalized Types

Target ownership:

- `SPAM`

Current-runtime compatibility note:

- legacy runtime may still emit some spam-like rows as `UNKNOWN` with explicit
  spam/phishing reasons until the dedicated family is fully implemented

## Authoritative Evidence

- `OnChainRawTransactionView` tx-level fields
- persisted token/internal transfer arrays
- protocol/promo heuristics that are available at backfill time
- persisted clarification evidence only when that same evidence is available to
  the real clarification stage

## Clarification Rules

- spam classification should normally not depend on clarification
- clarification may only strengthen already-supported spam detection; it must
  not invent spam semantics from evidence unavailable to production

## Correlation Rules

- no `correlationId` required
- no `matchedCounterparty` required

## Disallowed Fallbacks

- do not downgrade proven spam into generic `EXTERNAL_TRANSFER_IN`
- do not treat promo/drop artifacts as `REWARD_CLAIM`
- do not use explorer-only UI labeling as spam evidence

## Baseline Expectations

- row-level parity is required for all already-recognized spam-like rows until a
  deliberate migration from `UNKNOWN` to `SPAM` is explicitly approved
