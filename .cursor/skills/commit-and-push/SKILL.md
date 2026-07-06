---
name: commit-and-push
description: Stages, commits, and pushes WalletRadar code and documentation changes with repo-safe git hygiene. Use when the user asks to commit, push, save changes to git, ship code/docs, or run /commit-and-push.
---

# Commit and push (code + docs)

Commit and push **application code and documentation** only. Do not commit unless the user explicitly asked (this skill counts as explicit intent).

## Scope

**Include:**
- `backend/**`, `frontend/**`, `docs/**`, `scripts/**`
- Project skills/rules under `.cursor/skills/**`, `.cursor/rules/**` when part of the change

**Exclude (never stage unless user overrides):**
- Secrets: `.env`, credentials, API keys
- Local scratch: `.tmp-*`, `.tmp-shots/**`, `results/**` (audit artifacts), IDE-only noise
- Unrelated untracked binaries or screenshots

If unsure whether a path belongs, ask once.

## Workflow

### 1. Inspect (parallel)

```bash
git status
git diff
git diff --staged
git log -3 --oneline
git branch -vv
```

### 2. Stage

Stage only in-scope paths, e.g.:

```bash
git add backend/ frontend/ docs/ scripts/
git add .cursor/skills/ .cursor/rules/   # when changed
```

Verify with `git status` — no secrets or scratch files staged.

### 3. Commit message

- 1–2 sentences, **why** over what
- Match recent repo style from `git log`
- Pass via HEREDOC:

```bash
git commit -m "$(cat <<'EOF'
Short imperative summary.

Optional second sentence for context.
EOF
)"
```

**Safety (never violate):**
- Do not change git config
- No `--no-verify`, no force push to `main`/`master`
- No `commit --amend` unless user requested AND HEAD is yours AND not pushed
- If hook fails, fix and make a **new** commit (do not amend a failed commit)

### 4. Push

Only after a successful commit and when push was requested (this skill includes push):

```bash
git push -u origin HEAD
```

If branch has no upstream, always use `-u`. Warn before any force push; never force-push `main`/`master`.

### 5. Confirm

Run `git status` after push. Report commit hash, branch, and remote URL if push succeeded.

## Split commits (optional)

Prefer **one logical commit** when changes are one feature/fix. Split only when the user asked or when unrelated areas would confuse review (e.g. backend pipeline vs frontend-only).

## Related user rules

Follow the repository's git safety and PR conventions in Cursor user rules when they apply to the same operation.
