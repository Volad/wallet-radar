Fix the current cycle handoff now.

You are still the active owner: `{{role}}`.
Cycle: `{{cycle}}`
Expected next role: `{{nextRole}}`
Required handoff path: `{{handoffPath}}`

Follow the shared handoff policy in:
`{{handoffPolicyPath}}`

Current contract errors:
{{handoffErrors}}

Rules:
- rewrite the canonical handoff file into the exact required structure
- do not answer with chat-only status
- use only allowed statuses
- keep artifact references concrete and real
