# Protocol overview

This directory defines the typed contracts consumed by `runtime-core/`.

Core artifacts:

- `pipeline-config.schema.json`
- `handoff.schema.json`
- `question.schema.json`
- `command.schema.json`
- `pipeline-state.schema.json`

JavaScript in `runtime-core/` should treat these contracts as the authoritative machine-facing protocol.
