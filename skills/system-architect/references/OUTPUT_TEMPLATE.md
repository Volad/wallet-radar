# Architecture Report — WalletRadar

SECTION A — Decisions & Assumptions
- Context:
- Goals:
- Assumptions:
- Out of scope (MVP):

SECTION B — Cost‑Efficient Architecture Diagram (ASCII)
```
[Paste ASCII diagram here]
```

SECTION C — Module Breakdown (Spring Boot packages)
- api →
- ingestion →
- costbasis →
- pricing →
- snapshot →
- domain →
- config →
- common →
- Dependency rules:

SECTION D — Mongo Collections + Index Strategy
- Collections and key fields:
- Indexes (compound + unique + TTL if any):
- Read paths satisfied by indexes:

SECTION E — Data Flow
- Initial Backfill (Phase 1 raw fetch; Phase 2 classification):
- Incremental Sync (hourly):
- Current Balance Poll (10 min):
- Deferred Price Resolution (2–5 min):
- Manual Override & Manual Compensating Tx (async AVCO replay):
- Reconciliation (on‑chain vs derived):

SECTION F — Scaling Path
- MVP (single host):
- Phase 2 (when):
- Phase 3 (boundaries):

SECTION G — Cost Analysis
- Infra estimate ($/month):
- Major cost levers and mitigations:
- RPC & API rate limits and cache plan:

SECTION H — Risks & Mitigations
- Risk:
- Mitigation:

