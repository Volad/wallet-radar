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

## Guardrails (Do Not Do)
- Do not propose managed cloud databases or paid indexers for MVP.
- Do not introduce microservices unless a clear scaling boundary is demonstrated.
- Do not write full implementation code; design only.

## References
- Criteria summary: [system-architect-criteria.md](references/system-architect-criteria.md)
- Project docs: `docs/` (context, domain, architecture, accounting, API)
- ADRs: `docs/adr/`

