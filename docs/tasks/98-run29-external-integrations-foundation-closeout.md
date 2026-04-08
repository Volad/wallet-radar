# Run 29 — External Integrations Foundation Closeout

## Goal

Replace the current Bybit-specific preloaded external-ledger control plane with
a session-owned, API-driven integration model that starts with Bybit and scales
to future providers.

## Problem

Current external ingestion is centered on `external_ledger_raw`, which is both:

- a Bybit-shaped source layer
- a partially interpreted staging model

That makes the control plane hard to generalize to other providers and keeps
session scope coupled to `accounting_universes`.

## Target Policy

1. `user_sessions` becomes the durable workspace aggregate for:
   - wallets
   - integrations
   - settings
   - pipeline state
2. Session scope is derived from:
   - on-chain wallet addresses
   - connected integration `accountRef`s
3. `accounting_universes` is removed from the new target design.
4. `backfill_segments` remains the single backfill orchestration model and is
   generalized for `ONCHAIN` and `INTEGRATION` sources.
5. External providers must do all possible provider-specific enrichment during
   `BACKFILL`.
6. There is no post-normalization provider clarification fallback.
7. Immutable external provider evidence is still persisted, but in a
   provider-neutral collection.
8. Existing Mongo data must remain intact until the frontend for the new flow
   is connected.

## Scope

In scope:

- session integration model
- encrypted Bybit credentials in `user_sessions`
- provider-neutral external raw collection
- generalized backfill segments
- Bybit API credential validation and session-owned backfill planning
- settings UI for connect / update / disconnect

Out of scope in this slice:

- deleting existing `external_ledger_raw` data
- migrating historical Mongo data
- supporting providers beyond Bybit
- fully removing legacy Bybit normalization runtime before new ingestion is
  wired end-to-end

## Acceptance Criteria

1. `user_sessions` can persist Bybit integration settings and encrypted
   credentials.
2. The encryption key is file-backed, local to the project, and ignored by git.
3. Session settings API exposes integrations without returning secrets.
4. A Bybit connect call validates credentials against official Bybit V5 APIs
   and persists `accountRef = BYBIT:<uid>`.
5. A successful Bybit connect plans 2 years of backfill segments in the shared
   `backfill_segments` collection with `sourceKind=INTEGRATION`.
6. A backend path exists to fetch Bybit data into a provider-neutral immutable
   raw collection.
7. The frontend exposes a settings screen with a Bybit integration form based
   on the provided `walletradar-settings.jsx` reference.
8. Existing Mongo data is preserved.

## Tasks

### BA-98-01 Requirements

1. Freeze the integration-first policy:
   - session owns integrations
   - provider enrichment belongs to backfill
   - no provider clarification after normalization
2. Freeze the storage rule:
   - keep immutable raw provider evidence
   - do not keep Bybit-specific control-plane assumptions

### SA-98-02 Architecture

1. Generalize `backfill_segments` instead of introducing a parallel sync tree.
2. Replace `external_ledger_raw` as a target design with a provider-neutral raw
   evidence collection.
3. Remove `accounting_universes` from the target session-scope model.
4. Keep one modular-monolith pipeline and one session pipeline state.

### BE-98-03 Backend

1. Extend `user_sessions` with integrations and settings.
2. Add local file-backed encryption support for provider credentials.
3. Generalize `BackfillSegment` for `ONCHAIN` and `INTEGRATION`.
4. Add provider-neutral `integration_raw_events`.
5. Implement Bybit API credential validation and backfill segment planning.
6. Keep existing Mongo data intact.

### FE-98-04 Frontend

1. Add a settings route and session settings API client.
2. Build the integrations UI using the provided JSX as visual reference only.
3. Support Bybit connect, update, disconnect, and connection status display.

### Ops-98-05 Secrets and Rollout

1. Generate a local project encryption key file after implementation.
2. Add the secrets directory to `.gitignore`.
3. Do not clear Mongo collections until the new frontend is attached.

## Expected Outcome

After this slice:

- WalletRadar has a real integration control plane inside `user_sessions`
- Bybit can be connected via UI using API key + secret
- encrypted credentials are stored locally and safely
- shared backfill infrastructure can schedule external-provider acquisition
- legacy external-ledger data remains untouched until the new flow is in place
