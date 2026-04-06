# 94 — Accounting Universe Read Scope Closeout

## Context

Current `asset-ledger` and dashboard reads are still scoped by
`user_sessions.wallets` only.

That breaks the intended owner-level accounting story:

- `BYBIT` normalized trades and replay points exist
- move-basis continuity already carries through `BYBIT:<uid>` rows
- but session asset history starts only from on-chain legs because session reads
  ignore custodial refs

Observed ETH-family failure:

- early `BYBIT` ETH buys exist in:
  - `external_ledger_raw`
  - `normalized_transactions`
  - `asset_ledger_points`
- the UI timeline still starts from later on-chain ETH transfers because the
  read query loads only `session.getWallets().address`

## Decision

Introduce an explicit additive `AccountingUniverse`:

1. `UserSession` stores `accountingUniverseId`
2. `AccountingUniverse.members` persists stable owner refs
3. on-chain wallets are synchronized from the current session payload
4. exchange refs such as `BYBIT:<uid>` are synchronized from Bybit evidence
5. session asset-ledger reads use `accountingUniverse.members`
6. current dashboard live balances still use the current session wallet subset

This closes the immediate gap without changing the canonical normalization
contract.

## Runtime Contract

- `normalized_transactions.type` remains owner-agnostic
- replay output remains wallet-ref based
- session-facing history/debug reads resolve the active scope through
  `accountingUniverseId`
- `AccountingUniverse` is additive; historical members are not removed
  automatically when the visible wallet subset changes

## Backend Tasks

1. `BE-94-01` Add persisted `AccountingUniverse` document and repository.
2. `BE-94-02` Add `UserSession.accountingUniverseId`.
3. `BE-94-03` Synchronize on-chain wallet members from `SessionCommandService`.
4. `BE-94-04` Synchronize `BYBIT:<uid>` refs from `external_ledger_raw`
   evidence on Bybit completion.
5. `BE-94-05` Switch `AssetLedgerQueryService` to accounting-universe member
   refs.
6. `BE-94-06` Switch session dashboard ledger reads to accounting-universe
   member refs while keeping live balances scoped to current session wallets.
7. `BE-94-07` Add regression tests for:
   - timeline including early `BYBIT` ETH buys
   - session save creating `accountingUniverseId`
   - dashboard still building live rows from current on-chain wallets

## Acceptance Criteria

1. A session with matched `BYBIT` history sees early `BYBIT` acquisitions in
   `/api/v1/sessions/{sessionId}/asset-ledger?familyIdentity=FAMILY:ETH`.
2. Session wallet changes do not implicitly delete historical owner refs from
   the accounting universe.
3. Current on-chain dashboard holdings remain driven by current session wallets,
   not by stale historical universe members.
4. The API contract remains datastore-only; no request-path RPC or explorer
   calls are introduced.
