# cycle-autorun

Independent, config-driven, cycle-based multi-role autorun pipeline.

This project is intentionally isolated from any other pipeline implementation.
It does not import, reference, or mutate any existing runtime or pipeline folder.

## Design goals

- Keep pipeline-specific behavior in project config and docs.
- Keep `runtime-core/` focused on typed protocol, state transitions, and command handling.
- Avoid hardcoded human prompts in JavaScript. Prompts live in `prompts/` and are rendered from config + protocol data.
- Support non-linear clarification flow through a per-cycle question log.
- Stop or pause execution when the configured cycle goal is already reached.
- Support questions addressed to roles or directly to the user (`to: "user"`).

## Main directories

- `pipeline.config.json` - pipeline-specific configuration
- `policies/` - strict shared rules for handoffs, questions, and goal stop behavior
- `roles/` - minimal role-facing docs
- `prompts/` - prompt templates consumed by runtime core
- `protocol/` - typed protocol documents and JSON schemas
- `runtime-core/` - generic state machine, controller, dispatcher, and command contracts
- `cycle-data/` - per-cycle artifacts, handoffs, questions, and goal status

## Runtime contour

`runtime-core/` manages:

- pipeline state
- summary rendering
- command queue/index
- dispatch request envelopes
- question routing
- handoff validation
- goal-reached stop logic

It does **not** contain pipeline-specific prompt prose or role definitions.
