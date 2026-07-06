Answer the open cycle question now.

You are `{{role}}`.
Question id: `{{questionId}}`
Asked by: `{{fromRole}}`
Target: `{{toRole}}`
Cycle: `{{cycle}}`

Question:
{{questionText}}

Relevant context:
{{questionContext}}

Update the canonical question record in:
`{{questionLogPath}}`

Rules:
- answer concretely
- do not create a second parallel question for the same topic unless scope truly changed
- if you cannot answer fully, state the blocker precisely in the same question record
