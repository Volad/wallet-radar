# Question policy

Questions are explicit, typed, per-cycle coordination records.

## Canonical location

The canonical question log path is derived from `pipeline.config.json`:

- `cycle-data/cycle/{cycle}/questions/questions.jsonl`

Each line is one JSON object.

## Required fields

- `id`
- `cycle`
- `from`
- `to`
- `status`
- `blocking`
- `kind`
- `question`
- `createdAt`
- `updatedAt`

Optional fields:

- `context`
- `answer`
- `answeredAt`
- `resolvedAt`
- `cancelledAt`
- `artifacts`

## Allowed targets

`to` may be:

- any configured role id from `pipeline.config.json`
- `user`

## Allowed statuses

- `open`
- `answered`
- `resolved`
- `cancelled`
- `obsolete`

## Blocking rule

If `blocking: true` and `status: open`:

- dispatcher must not treat the asking role as transfer-ready
- dispatcher should route the question to `to`
- if `to = user`, dispatcher should pause for user answer instead of guessing

## Answer rule

Answers should update the same question record, not create a second parallel question.

Expected flow:

1. `open`
2. `answered`
3. `resolved`

## Disallowed

- free-form prose without JSON record
- ambiguous ownership or missing `to`
- using question logs as a substitute for handoff
- silently resolving unanswered blocking questions
