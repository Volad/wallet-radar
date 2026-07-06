# Goal stop policy

The pipeline must be able to stop or pause when the cycle goal is already reached.

## Canonical goal status file

Derived from `pipeline.config.json`:

- `cycle-data/cycle/{cycle}/goal-status.json`

## Terminal statuses

If goal status is one of:

- `reached`
- `completed`
- `satisfied`
- `stopped`

then dispatcher should:

- stop issuing normal continue prompts
- stop routing ordinary forward work
- mark pipeline state as halted or goal-reached
- preserve current artifacts and state for auditability

## User questions still allowed

Even when the goal is reached, the runtime may still:

- accept questions to `user`
- accept summary or closeout work
- accept explicit override commands

## Disallowed

- continuing ordinary role work after terminal goal status without explicit override
- guessing goal completion from informal chat alone
