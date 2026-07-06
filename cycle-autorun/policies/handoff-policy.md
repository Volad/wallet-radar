# Handoff policy

This policy is shared across all roles.

## Purpose

Handoffs are durable cycle artifacts.
They are not free-form chat, scratchpads, or informal progress notes.

## File location

The canonical handoff path is derived from `pipeline.config.json` path pattern:

- `cycle-data/cycle/{cycle}/handoffs/{role}.md`

For a normal forward transfer, the active owner writes the **incoming handoff file for the next role**.

## Allowed statuses

Only these values are allowed in `Status`:

- `active`
- `blocked`
- `completed`
- `completed_handoff`

No other status is valid.

## Required headers

Every pipeline-significant handoff must contain these exact header fields:

- `Status`
- `Task`
- `Transition`
- `Cycle`
- `Input basis`
- `Previous owner`
- `Next owner`

## Required sections

Every transition-ready forward handoff must contain these exact section headings:

- `## Summary`
- `## Next role requirements`
- `## Artifact references`

Optional:

- `## Notes`

Do not rename them, paraphrase them, or substitute near-equivalents.

## Forward handoff rule

For an ordinary forward transfer:

- the file owner is the expected next role
- `Status` must be `active`
- `Previous owner` must be the current owner
- `Next owner` must be the expected next role

## Artifact reference rule

`Artifact references` must contain at least one real artifact path that the next role needs.

## Blocked handoff rule

If the current role is truly blocked:

- write the same canonical handoff file
- use `Status: blocked`
- state the blocker concretely
- include the exact requirements needed to unblock

## Disallowed

- inventing statuses
- writing transition-ready handoffs with renamed required sections
- using handoff files as normal chat transport
- writing forward handoffs to arbitrary filenames
- omitting artifact references
