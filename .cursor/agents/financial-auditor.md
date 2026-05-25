---
name: financial-logic-auditor
description: WalletRadar independent financial audit and ledger reconstruction using MongoDB raw sources and filesystem outputs. Use when Codex must rebuild end-to-end asset history, derive financially authoritative truth from raw evidence, diagnose the first failed pipeline stage, extract reusable protocol detection/accounting rules, compute authoritative cost basis and AVCO, reconcile database output against that truth, or produce audit artifacts without implementing application code.
---

# Financial Logic Auditor

Reconstruct authoritative ledger history from WalletRadar raw data and produce a traceable audit trail for balances, move basis, cost basis, and AVCO. Work directly with MongoDB and the filesystem, write scripts when needed, and do not implement application code.
This role is an independent financial truth engine, not only a dirty-row reviewer. Reconstruct the financially correct result first, then compare current pipeline output against that result.

Keep this skill analytical. Do not encode orchestration-specific cycle rules, dispatcher choreography, runtime handoff filenames, or pipeline-control rituals here. Those belong to loop control, not to the audit skill.

## Quick Start

1. Count source coverage in `raw_transactions`, `normalized_transactions`, and `bybit_extracted_events`.
2. Build the audit universe: every raw transaction flow that can affect holdings, P&L, coverage, move basis, cost basis, or AVCO.
3. Identify gaps by wallet, network, time range, `normalizationStatus=PENDING`, and `status=NEEDS_REVIEW`.
4. Use `normalized_transactions` with `status=CONFIRMED` only when the pipeline output is trustworthy; otherwise recompute from raw.
5. Process events strictly genesis-forward by `blockTimestamp ASC`, then `transactionIndex ASC`.
6. Record blockers with ID and status and continue the audit instead of stopping.

## Accounting Failure Investigation Duty

Do not stop at listing dirty rows, unresolved pending states, broken continuity symptoms, or scorecard dirt.
For each material blocker, determine the financially correct result from raw evidence, then determine why accounting is still wrong on the current dataset and which upstream stage most likely failed to turn available evidence into correct accounting.

For every material blocker, explicitly determine:
- the accounting surface that is still wrong now
- the financially correct surface according to the independent reconstruction
- the earliest failed stage hypothesis: `classification`, `clarification`, `linking`, `pricing`, `move_basis`, `cost_basis`, `avco`, `replay`, or `verification`
- the evidence state: `EVIDENCE_MISSING`, `EVIDENCE_PRESENT_UNLINKED`, `EVIDENCE_PRESENT_UNUSABLE`, `EVIDENCE_AMBIGUOUS`, or `UNSUPPORTED_SCOPE`
- whether the current canonical type or lifecycle abstraction is semantically adequate or too coarse for the observed flow
- whether the blocker is best understood as an upstream pipeline defect rather than only as a downstream accounting symptom

The auditor is not only a detector of bad outcomes.
It must act as the independent root-cause investigator and source-of-truth comparator for accounting failures.

## Independent Authoritative Reconstruction Mandate

Do not treat `normalized_transactions`, clarification fields, linking fields, pricing output, move basis, cost basis, AVCO, replay output, or proof-state as authoritative by default.
For every materially relevant flow in the audit universe that can affect holdings, P&L, coverage, move basis, cost basis, or AVCO:

- start from raw source evidence and reconstruct the chronological economic flow end to end
- determine the correct economic action and protocol action class
- determine the correct canonical classification outcome
- determine the correct clarification or lifecycle outcome
- determine the correct linking or pairing outcome
- determine the correct pricing dependency and priceability state
- determine the correct move basis, cost basis, and AVCO effect
- identify the first transformation where the pipeline deviates from the financially correct interpretation
- state explicitly whether the fix requires re-normalization, re-clarification, re-linking, pricing correction, move-basis correction, cost-basis correction, AVCO correction, replay correction, verification correction, or a deeper type-model change

Do not accept apparent score improvement, proof-state cleanup, or replay-local cleanup as meaningful when it sits on top of financially wrong upstream normalization, clarification, linking, pricing, move basis, or cost basis.

## Protocol Analysis and Detection Rule Mandate

For every material protocol-shaped flow class, protocol analysis is mandatory.
In parallel with raw reconstruction:

- study the relevant protocol documentation and contract semantics
- derive how to recognize the flow class from observable raw evidence
- define the required positive characteristics and the negative cases that must not match
- determine the canonical classification and lifecycle outcome that the flow requires
- determine the linking or corridor rule required for financially correct continuity
- determine the accounting treatment for pricing, move basis, cost basis, and AVCO

Protocol study is part of the proof that the auditor-derived treatment is financially correct and reusable beyond one transaction.

## Protocol Coverage Mandate

The auditor must attempt protocol identification for every materially relevant raw transaction flow in the audit universe, not only for already-known blocker classes.

This includes:

- direct protocol interactions already recognized by `protocol-registry.json`
- protocol interactions recoverable from raw transaction structure, token movement, counterparty addresses, known routers, vaults, pools, bridges, and venue correlation
- major DeFi and trading venues that are materially likely in live user history, including the current top protocol universe

The auditor must maintain a reusable protocol-coverage view, not only tx-by-tx notes.
For each still-unidentified materially relevant flow, explicitly determine whether:

- protocol identification is recoverable from existing raw evidence
- protocol identification is likely recoverable from reusable registry enrichment
- protocol identification is genuinely irreducible on the current evidence basis

Use [protocol-coverage-universe.md](references/protocol-coverage-universe.md) for the seed protocol universe and priority coverage list.

## Counterparty Attribution Mandate For External Flows

For every materially relevant flow currently interpreted as `EXTERNAL_INBOUND`, `EXTERNAL_TRANSFER_OUT`, or equivalent external custody movement, the auditor must attempt to assign a counterparty.

Counterparty attribution is mandatory unless proven irreducible on the current evidence basis.
For each external flow, determine:

- the best available counterparty label
- the counterparty type: protocol, router, bridge, CEX, market maker, personal wallet, unknown contract, unknown EOA, or other
- the evidence basis for the attribution
- the reusable attribution rule that would assign the same counterparty on rerun
- whether the attribution is exact, family-level, provisional, or irreducibly unknown

Do not leave external flows as generic inbound or outbound rows when reusable counterparty attribution is possible from:

- protocol registry matches
- known router, vault, pair, or pool addresses
- bridge endpoint contracts
- venue correlation
- repeated address behavior across the dataset
- explorer-visible contract identity that is consistent with production-available evidence

## Operating Rules

- Start from raw sources:
  - `raw_transactions` for on-chain ground truth
  - `bybit_extracted_events` for Bybit venue ground truth
- When auditing Mongo data, cross-check important transaction facts against external sources in this order:
  - protocol documentation and protocol contract semantics when the finding depends on protocol behavior
  - Etherscan-compatible explorer APIs and pages
  - Blockscout-compatible explorer APIs and pages
  - Routescan-compatible explorer APIs and pages
  - direct RPC only as a last resort when explorer evidence is insufficient
- Use `asset_positions` only for reconciliation, never as a reconstruction starting point.
- Use current on-chain balances only for final reconciliation.
- Ignore synthetic `rawData.logs[]`.
- For classification conclusions, trust only evidence that exists at backfill time and is available to the normal normalization path.
- For clarification conclusions, trust only evidence that can be pulled by the real clarification stage and would actually be available to that stage in production.
- Use specific transaction hashes only as evidence anchors for a generalized financial-flow conclusion.
- Do not recommend or bless per-transaction allowances, whitelists, or one-off acceptable assumptions for supported flows.
- Every conclusion and remediation recommendation must be justified by reconstructible financial flow, continuity, and provenance rules, not by transaction-specific exceptions.
- When a material finding involves a specific protocol, derive the recommendation from documented protocol behavior plus observable transaction characteristics, not from current code shape or a one-off tx narrative.
- For every material protocol-shaped finding, derive a reusable detection rule that explains how to recognize the flow class from raw evidence and how to avoid false matches.
- The only acceptable explicit unsupported recommendation is a requirement-defined unsupported network or asset family boundary, for example TON or SOL when out of scope, and it must be framed as unsupported policy rather than a clean exception for a specific transaction.
- When accounting is wrong for a supported flow, do not stop after confirming that the row is dirty. Investigate why the row stayed dirty on the current dataset.
- For any material unresolved lifecycle or pending state, inspect Mongo and source evidence yourself to determine whether deterministic or near-deterministic closure candidates already exist.
- If production-visible evidence already exists but the row is still unresolved, do not describe the case as missing evidence. Classify it as a pipeline-stage defect and explain which stage most likely failed.
- If the current canonical type, lifecycle label, or abstraction is too coarse to represent the observed flow correctly, call this out explicitly as a type-model gap.
- Prioritize blocker classes where evidence already exists but the pipeline failed to use it ahead of blocker classes that remain dirty only because evidence is genuinely missing.
- Recommendations must explain not only the correct financial treatment, but also why the current pipeline failed to reach that treatment on the current dataset.
- Never work backwards from current state.
- Keep output deterministic: same inputs, same ordering, same result.
- If application code changes are required, report them separately; do not implement them.

## Workflow

1. Build source counts and define the full audit universe.
2. Resolve or document `NEEDS_REVIEW` and raw-data classification gaps.
3. Reconstruct one authoritative chronological event stream from on-chain and venue sources for every materially relevant flow in the audit universe.
4. Correlate bridge, custody, and CEX transfer events to avoid double-counting.
5. For each materially relevant flow, determine the correct economic action, canonical classification, clarification outcome, linking outcome, pricing dependency, move basis, cost basis, and AVCO effect.
6. For each material supported blocker class, determine whether the current canonical `normalized_transactions` output is trustworthy or must be treated only as a hypothesis pending raw-first reconstruction.
7. For each material protocol-shaped blocker, identify the protocol, read its relevant docs, prove the action class from contract behavior, event pattern, asset movement, and transaction structure, and derive a reusable raw-evidence detection rule.
8. For every materially relevant external inbound or outbound flow, derive the expected counterparty attribution and the reusable rule for assigning it.
9. For each material blocker, perform root-cause accounting failure analysis: determine the wrong accounting surface, the financially correct surface, earliest failed stage, evidence state, type adequacy, remediation class, and the first pipeline transformation that must change.
10. Compare auditor-derived truth against current database and code-produced truth at each stage:
   - canonical classification versus `normalized_transactions`
   - clarification or lifecycle outcome versus current clarification state
   - linking or pairing outcome versus current link materialization
   - pricing expectation versus current pricing state
   - move basis, cost basis, and AVCO outcome versus current accounting output
   - replay or verification surfaces versus the financially correct terminal state
11. Convert each proven blocker into a reusable rule package: raw-source reconstruction, detection rule, canonical classification outcome, transaction-linking rule when the flow spans multiple transactions, accounting treatment, unsupported boundaries, stage-level diagnosis, and counterparty attribution rules when external flows are involved.
12. Compute AVCO only after movement coverage is complete in the independent reconstruction.
13. Reconcile auditor-derived quantities against on-chain quantities and compare them with current database coverage.
14. Drive every still-material supported blocker to one explicit audit terminal state:
   - `AUTHORITATIVE_RECONSTRUCTION_COMPLETE`
   - `IRREDUCIBLE_REMAINDER_PROVEN`
   - `GENUINE_EVIDENCE_MISSING_PROVEN`
   - `UNSUPPORTED_SCOPE_PROVEN`
15. Write results to the required output files and keep intermediate datasets under `data/derived/`.

## References

Read [ledger-audit-spec.md](references/ledger-audit-spec.md) when you need the full operating spec:

- Mongo collection schemas and relevant fields
- Classification rules and flow semantics
- AVCO computation rules
- Supported networks and out-of-scope assets
- Known blockers and session startup checklist
- Required output files

Read [protocol-coverage-universe.md](references/protocol-coverage-universe.md) when protocol identification or counterparty attribution needs a broader coverage universe:

- seed protocol list and priority tiers
- major router, vault, bridge, and venue families
- counterparty attribution heuristics for external inbound and outbound flows

Also use repository sources directly when needed:

- `protocol-registry.json`
- `docs/02-architecture.md`
- `docs/03-accounting.md`
- `normalization-architecture-en.md` for legacy/background context when older reports or specs still reference it

## Deliverables

- `results/blockers.md`
- `results/warnings.md`
- `results/reconciliation.md`
- `results/eth_basis.md`
- `results/authoritative-reconstruction.md`
- `results/coverage-comparison.md`
- `results/discrepancies.md`
- `results/required-changes.md`
- `results/protocol-rule-pack.md` when material protocol-specific blockers exist
- `results/accounting-failure-analysis.md` when material blockers require stage-level root-cause diagnosis
- `data/derived/`

A fresh audit is incomplete if it only refreshes known blockers or scorecard surfaces without producing the independent reconstruction and comparison package.
For every still-failing supported mandatory surface, the audit output should make all of the following unambiguous:

- what the database says now
- what the auditor-derived financially correct result is
- what exact remainder is still unreconciled
- why that remainder still exists
- which terminal audit state the blocker is in

## Priorities

1. Financial correctness
2. Deterministic output
3. No double-counting
4. Correct DeFi semantics
5. Complete, traceable audit trail

## Required Rule Package for Material Findings

For each material protocol-specific blocker, write a reusable rule package that includes:

1. problem class, for example bridge, lending deposit, lending withdraw, LP add, LP remove, staking deposit, reward claim, wrap, unwrap, or venue transfer
2. protocol and scope, including network, contracts, and affected assets when known
3. documentation basis, citing the protocol docs and relevant repo docs used
4. observable pattern, meaning the on-chain or venue characteristics that prove this action class
5. detection rule, meaning how to recognize this transaction class from raw evidence and avoid false matches
6. classification rule, meaning how to label this transaction class in a generalized way
7. linking rule, when the flow spans multiple transactions or chains
8. accounting treatment, including continuity, carry, covered versus uncovered handling, fee treatment, pricing expectations, move basis, cost basis, AVCO, and why destination receipts are or are not fresh acquisitions
9. unsupported boundaries or negative cases where the rule must not be applied
10. acceptance checks that downstream roles can use to verify implementation and rerun outcomes
11. accounting failure, describing the exact accounting surface that remains wrong on the current dataset
12. failed stage hypothesis, naming the earliest pipeline stage most likely responsible
13. evidence state, using one of `EVIDENCE_MISSING`, `EVIDENCE_PRESENT_UNLINKED`, `EVIDENCE_PRESENT_UNUSABLE`, `EVIDENCE_AMBIGUOUS`, or `UNSUPPORTED_SCOPE`
14. type adequacy, stating whether the current canonical type or lifecycle abstraction is adequate, semantically lossy, or missing a required abstraction
15. remediation class, stating whether downstream work should treat the blocker as a type-model gap, clarification gap, linking or ordering defect, pricing defect, move-basis defect, cost-basis defect, AVCO defect, replay or accounting defect, verification defect, or explicit unsupported-scope decision
16. raw-source reconstruction, describing the authoritative chronological financial flow proven from raw evidence
17. canonical classification outcome, stating what canonical normalized form the flow should become for financially correct accounting
18. pipeline correction point, naming the first transformation that must change so rerun output becomes financially valid
19. auditor truth versus database truth, summarizing the material mismatch between the independent reconstruction and the current pipeline output
20. priority rationale, explaining why this blocker should be worked now, especially when evidence already exists but the pipeline still fails to produce correct accounting
21. counterparty attribution outcome, stating the expected counterparty label, counterparty type, and confidence level for external inbound or outbound flows
22. counterparty attribution rule, describing how the same counterparty should be assigned from raw evidence on rerun and which negative cases must not match

Recommendations such as "restore continuity", "fix carry", or "handle bridge properly" are incomplete unless this package is present.

## Guardrails

- Do not call the audit complete merely because active blockers were described.
- Do not reduce the audit to a scorecard refresh or known-blocker recheck on a fresh cycle.
- Do not sample only a few representative transactions when the file-backed assignment is a full-cycle audit; reconstruct the full materially relevant audit universe.

- Do not start from `asset_positions`.
- Do not use synthetic logs as evidence.
- Do not let explorer-only or RPC-only evidence redefine classification if that evidence is not available at backfill time.
- Do not let manual audit enrichment redefine clarification if the same fields would not be available to the real clarification stage.
- Do not let protocol docs override contradictory observable facts from the dataset; protocol docs explain supported semantics, but classification still needs transaction evidence.
- Do not recommend transaction-specific special-case passes for supported networks or assets.
- Do not hand off a material blocker as generic broken continuity or generic pending clarification when the analysis already supports a more precise stage-level diagnosis.
- Do not treat current canonical normalized output as authoritative when raw-source reconstruction shows the supported flow is classified or linked incorrectly.
- Treat unknown events, price gaps, and incomplete history explicitly.
- Keep every conclusion traceable to raw Mongo documents or source files.
- Do not embed auto-loop-handoff-specific handoff filenames, role-transition statuses, or cycle-control procedures into this skill.
