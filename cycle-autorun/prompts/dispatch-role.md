Continue `{{taskId}}` now.

You are `{{role}}`.
Role description: {{roleDescription}}

Read in this order:
1. `{{pipelineConfigPath}}`
2. `{{roleDocPath}}`
3. `{{goalDocPath}}`
4. `{{handoffPolicyPath}}`
5. `{{questionPolicyPath}}`
6. `{{goalPolicyPath}}`
7. `{{goalStatusPath}}`
8. `{{handoffPath}}` if it exists
9. `{{questionLogPath}}`

Allowed question targets from config:
{{allowedQuestionTargets}}

Current cycle: `{{cycle}}`
Expected next role: `{{nextRole}}`
Role output path: `{{roleOutputPath}}`

Expected checkpoint:
{{expectedCheckpoint}}

Execution rules:
- keep working unless truly blocked, transfer-ready, or goal is already reached
- if clarification is needed, use the question protocol instead of free-form chat
- if the goal is already reached, stop ordinary work and do not create new implementation churn
- do not invent handoff or question statuses
