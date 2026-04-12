# WalletRadar Orchestration Model

This file captures the preferred long-running execution model for this workspace.

## Core Model

Use persistent ACP Codex role sessions orchestrated by a central orchestrator.
The orchestrator should avoid context bloat by relying on local handoff/state files instead of long chat history.

## Roles

- architect
- business-analyst
- backend-dev
- frontend-dev
- financial-analyst
- orchestrator

## Preferred Session Strategy

- Run roles as persistent ACP sessions
- Default agent: `codex`
- Reuse role sessions across cycles
- Track role-to-session mapping in `handoff/SESSION_MAP.md`

## Goal-Driven Loop

The user defines the goal.
The loop continues until one of these states is true:
- `REACHED`
- explicit user pause/stop
- true hard blocker requiring user input

If the goal is not reached and no hard blocker exists, the loop must continue.

## Phase Order

1. PLANNING
   - architect + business-analyst read their local skills, handoff files, and only relevant docs
   - they update tasks, acceptance criteria, decisions, and docs when needed
   - critical documentation/decision changes require explicit user approval
2. EXECUTION
   - backend-dev + frontend-dev implement assigned tasks
   - they may prepare Mongo/data state as needed
   - they must wait for full normalization and cost-basis completion before handoff to audit
   - if they hit a problem, they first explain it to architect + business-analyst, receive revised tasks, then continue execution
3. AUDIT
   - financial-analyst reads local skill, handoff files, relevant docs, prior results if needed, then audits Mongo/data/on-chain state
   - may use helper scripts, protocol docs, and local reusable protocol notes
   - writes detailed reports to `results/stat/{cycle}/`
4. ORCHESTRATION DECISION
   - orchestrator decides whether to continue, ask user, pause, or stop
   - default when goal not reached and no hard blocker: continue

## State Contract

Primary local state lives in:
- `handoff/GOAL.md`
- `handoff/STATE.md`
- `handoff/TODO.md`
- `handoff/QUESTIONS.md`
- `handoff/DECISIONS.md`
- `handoff/AUDIT.md`
- `handoff/LOOP_DECISION.md`
- `handoff/DEVELOPER_HANDOFF.md`
- `handoff/SESSION_MAP.md`
- `handoff/CYCLE_HISTORY.md`
- `handoff/APPROVALS.md`
- `handoff/roles/*.md`

## Skills and Inputs

Local project skills in `skills/` are authoritative for role behavior:
- `skills/system-architect/`
- `skills/business-analyst/`
- `skills/backend-dev/`
- `skills/frontend-dev/`
- `skills/financial-logic-auditor/`

Roles should read in this order:
1. local role skill
2. current handoff files
3. only relevant project docs
4. only then code, data, and artifacts

## Reports and Audit Assets

- Preferred report path: `results/stat/{cycle}/`
- Reusable local audit assets may be stored under:
  - `data/derived/audit-helpers/`
  - `data/derived/protocol-notes/`

## Scripts and Secrets

- Project helper scripts live under `scripts/`
- Prefer existing scripts before inventing ad hoc commands
- Service credentials are stored in `.env`
- Roles may read `.env` only when needed and must not copy secrets into reports, handoff files, or chat

## Communication Rules

- Questions that do not block progress must not stop the loop
- Soft questions should be recorded with assumptions in `handoff/QUESTIONS.md`
- Inter-role communication must be summarized back into handoff files
- The orchestrator should send short delta instructions, not replay whole histories
