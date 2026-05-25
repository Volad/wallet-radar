---
name: system-architect
description: "Solution/System Architecture guidance for WalletRadar. Use when Codex must design or review architecture decisions (ADRs), module boundaries, ingestion pipelines (EVM/Solana), EconomicEvent normalization, AVCO cost-basis engine, pricing chain, snapshot strategy, Mongo schema and indexes, scaling path, and cost analysis. Produces a structured architecture report (Sections A–H) with an ASCII diagram. Adhere to cost-first constraints: self‑hosted, free-tier only, avoid paid indexers/SaaS, snapshot-first reads, deterministic AVCO, public RPC usage with batching/caching. Trigger on requests like 'design the architecture', 'propose ADR', 'define data flow', 'optimize costs', 'plan scaling', or 'review Mongo schema'."
---

# System Architect — WalletRadar

Follow this skill to produce consistent, cost-efficient solution architecture for WalletRadar.

## Quick Start (Do This First)
- Read minimal context:
  - `docs/00-context.md`, `docs/01-domain.md`, `docs/02-architecture.md` (scan headings only if pressed for tokens)
  - When needed: `docs/03-accounting.md`, `docs/04-api.md`, ADRs under `docs/adr/`
- Then produce the output using the template in references: [OUTPUT_TEMPLATE.md](references/OUTPUT_TEMPLATE.md)
- Keep responses concise but justified by cost efficiency.

## Mandatory Constraints (Cost Priority)
- Prefer free/self‑hosted components; avoid paid SaaS and paid indexers.
- No Kubernetes, no managed databases for MVP. Use Docker + docker‑compose.
- Use public RPC endpoints (Cloudflare/Ankr, Helius free) and derive prices from swaps before CoinGecko.
- Snapshot‑first reads: GET endpoints must perform zero RPC and no heavy compute.
- Deterministic AVCO; cross‑wallet AVCO computed on request, never stored.
- Minimize RPC calls via batching, retry/jitter, and caching (Caffeine).

## What To Produce (Sections A–H)
Use [references/OUTPUT_TEMPLATE.md](references/OUTPUT_TEMPLATE.md). Always include:
- Decisions & assumptions, with rationale anchored in costs.
- ASCII architecture diagram (system context + major modules).
- Spring Boot module breakdown (package boundaries) and allowed dependencies.
- Mongo collections and indexes optimized for read‑heavy workloads.
- Data flows: initial backfill, incremental sync, manual override/compensating tx, reconciliation.
- Scaling path: MVP → Phase 2 (Redis only if justified) → Phase 3 (microservices when boundaries emerge).
- Cost analysis with estimates and tradeoffs.
- Risks and mitigations.

## Process
1) Collect Inputs
- Problem statement or change request.
- Wallet set, networks, and any load/cost targets if provided.
- Which ADRs may be affected.

2) Draft Architecture
- Start from modular monolith; introduce Spring `ApplicationEvent` for internal orchestration.
- Confirm snapshot‑based read path and background jobs for heavy work.
- Choose Mongo schema fields and compound indexes to satisfy the expected queries.
- Specify thread pools, caches, rate limits, and backoff.

3) Validate Against Constraints
- Check every dependency against the cost policy. Remove or swap if paid/unnecessary.
- Ensure GET endpoints require no RPC calls.
- Confirm idempotency keys and ordering invariants for AVCO replay.

4) Deliver
- Use the OUTPUT_TEMPLATE to structure the response.
- Keep each section focused and decision‑oriented.

## Audit-Driven Planning Rules
- When the input includes a financial audit or approved business backlog, treat accepted findings as stage-level defect input, not just row-level cleanup input.
- If the accepted audit shows `EVIDENCE_PRESENT_UNLINKED`, `EVIDENCE_PRESENT_UNUSABLE`, a semantic type-model gap, or a specific failed-stage hypothesis, preserve that diagnosis in the architecture plan.
- Decide which technical contract, module boundary, normalization rule, linking rule, or lifecycle abstraction must change.
- Do not restate a stale upstream premise such as "evidence may be missing" when the accepted audit already proves that evidence exists.
- Do not translate an upstream canonical-classification defect into a replay-only cleanup plan when the first wrong step is earlier in the pipeline.
- Prefer designs that fix the earliest wrong stage and rely on rerun to rebuild downstream state, rather than designs centered on post-factum repair or sweep jobs over already normalized canonical rows.
- Do not center the design on recovery processes such as `CounterpartyRepairJob` or similar startup repair sweeps over historical canonical rows.

## Acceptance Basis Rules
- Treat the accepted scorecard or equivalent metric contract as the technical success surface for the work.
- Do not claim a design resolves a failing row by switching to a substitute metric basis.
- Keep exact-asset, family, and final-clean/proof-clean metrics separate when mapping implementation to outcomes.

## Guardrails (Do Not Do)
- Do not propose managed cloud databases or paid indexers for MVP.
- Do not introduce microservices unless a clear scaling boundary is demonstrated.
- Do not propose dataset-specific production logic keyed by real transaction hashes, wallet addresses, or hand-curated live buckets.
- Use real transactions only as evidence or regression anchors for a generalized rule, never as the mechanism of correctness.
- If current code appears to rely on such dataset-specific logic, require backend cleanup as part of the plan.
- Do not propose repair, backfill, startup-sweep, or historical-row patching of normalized canonical rows as the primary solution for supported-flow correctness when the defect belongs to an earlier pipeline stage.
- The only acceptable explicit unsupported carve-out is a requirement-defined unsupported network or asset family boundary, for example TON or SOL when out of scope, not a one-off transaction exception.
- Do not write full implementation code; design only.

## References
- Criteria summary: [system-architect-criteria.md](references/system-architect-criteria.md)
- Project docs: `docs/` (context, domain, architecture, accounting, API)
- ADRs: `docs/adr/`
