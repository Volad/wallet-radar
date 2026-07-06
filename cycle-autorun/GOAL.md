# cycle-autorun goal

Build a reusable cycle-based autorun runtime where:

- pipeline-specific role behavior is configured in `pipeline.config.json`
- handoff and question behavior are governed by strict shared policy
- runtime-core remains protocol-first and pipeline-agnostic
- agents can ask questions to roles or directly to the user within a cycle
- the pipeline stops or pauses when the goal is already reached

Update per-cycle execution truth in `cycle-data/cycle/<N>/goal-status.json`.
