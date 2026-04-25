# Task 145: Cycle 79 AVCO Coverage, Protocol Resolve, and Counterparty Resolve Closeout

## Goal

Implement the full cycle 79 audit backlog so the next production rerun can prove progress toward `AVCO-COVERAGE-001` without repair-sweep shortcuts.

Audit basis:

- `auto-loop-handoff/artifacts/cycle/79/scorecard.md`
- `auto-loop-handoff/artifacts/cycle/79/financial-analyst/comprehensive-implementation-package.md`
- `auto-loop-handoff/artifacts/cycle/79/handoffs/backend-dev.md`

## Business Acceptance Criteria

- [ ] `ETH exact` uncovered decreases from `0.005586019294422211`, target `0`.
- [ ] `ETH family` uncovered decreases from `0.01478575000585997`, target `0`.
- [ ] `AVAX exact` uncovered decreases from `0.09345689565839355`, target `0`.
- [ ] `AVAX family` uncovered decreases from `1.1014390815719546`, target `0`.
- [ ] `USDC exact` uncovered decreases from `10.271747000000005`, target `0`.
- [ ] `USDC family` uncovered decreases from `10.271747000000005`, target `0`.
- [ ] `BTC exact` remains clean.
- [ ] `BTC family` dust `0.00000008999999999946551` is closed or explicitly terminal-classified as immaterial by project policy.
- [ ] `MNT exact/family` remains clean.
- [ ] `USDT exact/family` remains clean.
- [ ] Protocol gaps decrease from the cycle 79 baseline of `362` material missing rows.
- [ ] Counterparty gaps decrease from the cycle 79 baseline of `1183` material missing rows.
- [ ] Bridge unmatched counts decrease from `unmatchedBridgeOut=27`, `unmatchedBridgeIn=9`, or remaining rows have terminal reasons.
- [ ] Bybit `NEEDS_REVIEW=94` decreases or every remaining row has an explicit terminal source/policy reason.
- [ ] A fresh rerun with `scripts/prod-reset-rebuild-backend.sh --skip-frontend` completes and emits a new scorecard.

## Edge Cases

- Case: receipt-token withdraw returns less underlying than source share quantity. Scope: In. Expected: carry all covered source basis onto returned principal quantity; no synthetic PnL.
- Case: receipt-token withdraw returns more underlying than carried principal. Scope: In. Expected: carried principal remains transfer continuity; positive excess becomes explicit acquisition.
- Case: bridge destination quantity is lower because of fees/slippage. Scope: In. Expected: full carried basis follows supported bridge continuity, with explicit uncovered/excess only where evidence requires it.
- Case: bridge source and destination are only timestamp-near. Scope: In as negative case. Expected: no correlation without protocol-backed unique evidence.
- Case: row has row-local counterparty and lifecycle peer. Scope: In. Expected: `counterpartyAddress` stores row-local contract/peer; `matchedCounterparty` stores lifecycle pair.
- Case: Bybit venue row has no raw venue source under `external_ledger_raw`. Scope: In. Expected: normalize from `bybit_extracted_events` only if source contract is accepted, otherwise terminal-classify source boundary.
- Case: unsupported arbitrary live transaction. Scope: Out. Expected: no transaction-hash keyed production exception.

## Supported vs Unsupported

- Supported: EVM on-chain bridge, lending, vault, wrapper, liquid-staking, LP principal, Bybit extracted rows already in the source contract, mandatory assets `ETH`, `BTC`, `MNT`, `AVAX`, `USDC`, `USDT`.
- Unsupported: paid indexers, tax reporting, NFTs, Solana accounting, transaction-hash keyed production fixes, post-factum canonical row repair as primary correctness path.

## Test Scenarios

- Happy path: same-family `LENDING_WITHDRAW` with receipt outbound and underlying inbound carries principal basis.
- Happy path: `STAKING_DEPOSIT` with `AVAX` outbound and `sAVAX` inbound carries AVAX-family basis.
- Happy path: EVK/ERC-4626 `VAULT_WITHDRAW` carries USDC-family basis and prices only positive excess.
- Edge: protocol enrichment resolves from a registry-backed log address when direct `to` is not the protocol endpoint.
- Edge: counterparty enrichment resolves row-local bridge/swap/vault/staking contract and does not overwrite lifecycle fields.
- Negative: ambiguous multiple registry candidates do not set protocol or counterparty.
- Negative: bridge candidates with no unique lifecycle proof remain unmatched.

## Architecture Decisions

- Keep the modular monolith and existing pipeline order.
- Fix the earliest failed supported stage:
  - classification/normalization for canonical principal/excess shape
  - clarification/linking for protocol/counterparty/lifecycle metadata
  - replay move-basis for deterministic carry consumption
- Do not introduce new infrastructure, paid services, microservices, or startup repair jobs.
- Use existing Mongo collections and compound indexes; add indexes only for bounded enrichment queries if needed.
- Preserve snapshot-first reads; all heavy work remains in background pipeline/rerun.

## Executor Task Breakdown

1. Documentation alignment. Update accounting/linking/architecture docs with cycle 79 acceptance surfaces. Depends on: audit package.
2. Counterparty enrichment coverage. Resolve row-local counterparty from direct interacted contract, unique peer, and registry-backed log/address evidence. Depends on: existing enrichment services.
3. Protocol enrichment coverage. Resolve protocol from registry-backed interacted/log/source evidence without changing economics. Depends on: existing protocol registry.
4. Family-equivalent custody carry hardening. Ensure supported lending/vault/wrapper rows carry principal even if legacy flows use buy/sell roles on custody row types. Depends on: replay handlers.
5. Liquid staking carry hardening. Ensure same-family staking rows preserve principal carry and only leave explicit excess. Depends on: replay router.
6. Bybit terminal policy. Normalize supported extracted transfer rows and keep unsupported loan/shadow rows explicitly excluded or terminal-classified without affecting accounting. Depends on: Bybit source contract.
7. Regression tests. Add or update tests for enrichment, family custody, liquid staking, Bybit terminal behavior, and no hash-specific runtime logic. Depends on: tasks 2-6.
8. Verification rerun. Run backend tests where the environment allows, then `scripts/prod-reset-rebuild-backend.sh --skip-frontend`, then capture fresh scorecard. Depends on: implementation batch.

## Risk Notes

- Risk: Gradle tests may be blocked by sandbox socket restrictions. Mitigation: record the environment failure and run runtime script if host access allows it.
- Risk: protocol/counterparty gaps may require additional registry addresses not yet known. Mitigation: implement reusable resolver paths and leave irreducible rows terminal in the next audit.
- Risk: Bybit source policy can affect product scope. Mitigation: keep `bybit_extracted_events` source-contract decision explicit in docs and audit handoff.

## 2026-04-25 Executor Rerun Notes

Commands:

- `scripts/prod-reset-rebuild-backend.sh --skip-frontend`
- `scripts/avco/phase-coverage.sh --session-id c584c760-b228-45fc-ae0f-84f7cd7bfd8f`

Build and runtime:

- `backend-prod` Docker build completed successfully via `:backend:bootJar -x test`.
- Production reset restored `rawPending=3183`, cleared normalized/replay/balance read models, rebuilt backend image, and completed a fresh pipeline rerun.
- Final pipeline state after the latest rerun: `ACCOUNTING_REPLAY / COMPLETE`, timestamp `2026-04-25T07:45:35.550Z`.

Observed result after the registry-backed counterparty/raw-lookup implementation:

- `rawTransactions=3183`, `rawPending=0`, `normalizedTransactions=5818`, `confirmed=5724`, `needsReview=94`, `assetLedgerPoints=9308`, `onChainBalances=212`.
- Protocol gaps changed only marginally: `BRIDGE_IN` improved from `100` to `98`; total material protocol gaps remain effectively open.
- Counterparty gaps changed only marginally: `BRIDGE_IN` improved from `118` to `116`, while `EXTERNAL_TRANSFER_IN` worsened from `169` to `171`.
- Bridge lifecycle regressed on `unmatchedBridgeOut`, from `27` to `29`; `unmatchedBridgeIn` stayed `9`.
- Mandatory coverage did not close: ETH, AVAX, USDC uncovered quantities remain at the cycle 79 failure level, with ETH family drifting slightly worse on this rerun.

Conclusion:

- The cycle 79 closeout is not complete.
- The implemented metadata fallback is reusable and build-safe, but it is not sufficient as the primary backlog completion.
- The next backend batch must focus on evidence expansion and economics, not another rerun-only metadata pass:
  - add missing protocol registry coverage from audited raw rows;
  - fix bridge lifecycle matching regression introduced by broader counterparty resolution;
  - implement ETH/AVAX/USDC principal carry fixes in classification/replay;
  - terminal-classify or source-contract-resolve the remaining Bybit review tail.

## 2026-04-25 Follow-up Executor Notes

Additional implementation hardening:

- Restricted protocol/counterparty raw lookup back to strict `txHash/networkId/walletAddress` plus deterministic raw `_id`; removed the broad unique `txHash/networkId` fallback because it correlated with `EXTERNAL_TRANSFER_IN` and bridge lifecycle regressions.
- Added custody replay selection support for legacy `SELL` outbound / `BUY` inbound principal roles on supported same-family custody rows, while leaving explicit positive excess flows to the generic replay path.
- Added liquid-staking principal selection support for the same legacy role shape.
- Added family aliases for Euler-style wrapped receipt symbols needed by the cycle 79 evidence set.

Verification status:

- `scripts/prod-reset-rebuild-backend.sh --skip-frontend` was run again at `2026-04-25T08:14Z`; Mongo reset completed and backend-prod started.
- A scorecard captured at `2026-04-25T08:14:51.472Z` was discarded as non-final because the pipeline was still `ON_CHAIN_CLARIFICATION / RUNNING`.
- Subsequent final scorecard attempts were blocked by sandbox access to Docker socket: `permission denied while trying to connect to the docker API at unix:///Users/vladislav/.docker/run/docker.sock`.
- Direct host Mongo verification through `localhost:27019` was also blocked by sandbox network policy: `Operation not permitted`.
- Local Gradle tests could not run in this sandbox: default Gradle cache lock access is denied, and `GRADLE_USER_HOME=/tmp/gradle` cannot resolve `services.gradle.org`.

Current acceptance status:

- The last completed scorecard remains the previous cycle 79 executor observation above, not the discarded RUNNING snapshot.
- The backlog should be treated as partially implemented but not accepted until a completed post-hardening `phase-coverage` JSON is captured.
