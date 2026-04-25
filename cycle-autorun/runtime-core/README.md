# runtime-core

Protocol-first runtime for `cycle-autorun`.

## Responsibilities

- load `pipeline.config.json`
- reconcile goal status, question log, handoffs, and current owner
- produce machine-readable decisions in `state/pipeline-state.json`
- enqueue commands in `commands/`
- write dispatch request envelopes for an external transport/agent layer

## Non-responsibilities

- no pipeline-specific prompt prose in JavaScript
- no hidden role logic outside config + policy + protocol files
- no references to external pipeline folders

## Main files

- `tick.js` - simple entrypoint
- `controller.js` - command enqueue logic
- `dispatcher.js` - state reconciliation logic
- `command-runner.js` - command execution against local protocol artifacts
- `render-pipeline-summary.js` - human-readable summary view
- `protocol.js` - config loading, path resolution, prompt rendering, and protocol helpers
