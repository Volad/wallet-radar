# Financial correctness audit & fix

Follow the project rule **financial-correctness-audit-workflow** (`.cursor/rules/financial-correctness-audit-workflow.mdc`) end to end.

## User input (fill from chat or ask if missing)

- **Symptom:** what is wrong (AVCO, move basis, cost basis, linking, pricing, replay)
- **Scope:** session / wallets / networks / assets (if known)
- **Constraints:** e.g. backend only, no frontend, single protocol family

## Execute

### Phase 1 — Classify
- Delegate **`financial-logic-auditor`** (`readonly: true`).
- Cluster blockers by earliest failed stage: `classification` → `clarification` → `linking` → `pricing` → `move_basis` → `cost_basis` → `avco` → `replay` → `verification`.
- Update `results/` per `.codex/skills/financial-logic-auditor/SKILL.md`.
- **No application code.**

### Phase 2 — Plan
- Write `docs/tasks/{slug}-implementation-plan.md` (pick a short slug from the symptom).
- Include scope, root cause, ordered changes (upstream first), docs, acceptance, risks.

### Phase 3 — Review
- Run in parallel: `financial-logic-auditor`, `business-analyst`, `system-architect`.
- Revise until all approve or the user explicitly approves.

### Stop gate
- **Stop before Phase 4** unless the user says the plan is approved.

### Phase 4–6 (only after user approval)
- Update docs (`docs/03-accounting.md`, `docs/adr/`, family docs as needed).
- Delegate **`backend-dev`** to implement the approved plan.
- Verify: `./scripts/prod-reset-rebuild-backend.sh --skip-frontend` (add `--clear-pricing-cache` if pricing policy changed) → wait for normalization/replay → re-run **`financial-logic-auditor`** against plan acceptance.

## Default if user gave no details

Ask once for symptom and scope, then start Phase 1.
