# ADR-001: Modular Monolith over Microservices for MVP

**Date:** 2025  
**Status:** Accepted  
**Deciders:** Solution Architect

---

## Context

WalletRadar requires multiple distinct processing concerns:
- Transaction ingestion (RPC I/O–bound, bursty)
- AVCO calculation (CPU-bound, sequential)
- Price resolution (rate-limited external API)
- Snapshot building (scheduled, periodic)
- REST API serving (read-heavy, latency-sensitive)

The team is small. Infrastructure budget is ≤ $28/month. We need to ship a working MVP quickly without premature complexity.

Two realistic options exist:
- **Option A:** Microservices from day one (separate deployable per domain)
- **Option B:** Modular monolith with domain-isolated packages, extractable later

## Decision

**Option B — Modular Monolith.**

A single Spring Boot JAR with domain-isolated packages (`ingestion/`, `costbasis/`, `pricing/`, `snapshot/`, `api/`). Inter-module communication exclusively via Spring `ApplicationEvent`. Package boundaries enforced by ArchUnit tests in CI.

## Rationale

| Concern | Microservices | Modular Monolith |
|---------|--------------|-----------------|
| Network latency between modules | High (HTTP/gRPC hops) | Zero (in-process calls) |
| Operational complexity | High (service discovery, health checks, distributed tracing) | Low (single process) |
| Infrastructure cost | High (multiple VMs or k8s) | Low (single VPS $14–28/mo) |
| Development velocity | Slow (inter-service contracts, versioning) | Fast (refactor within JAR) |
| Extraction path | N/A | Clean: each package = future service |

The ArchUnit boundary enforcement means modules cannot call each other's internals — this discipline preserves the extraction path to microservices in Phase 3 without rewriting business logic.

## Consequences

- **Positive:** Single `docker-compose up -d` deployment. No service mesh. No API gateway in MVP. No distributed transaction complexity.
- **Positive:** All 4 thread pools share one JVM heap — no serialisation overhead between AVCO engine and ingestion.
- **Negative:** A bug in one module can crash the entire process. Mitigated by ArchUnit boundaries and separate thread pools per concern.
- **Negative:** Horizontal scaling requires stateless design from day one (Caffeine cache, no local state in services). This is enforced by design.

## Review Trigger

Extract a module into a microservice when:
- A specific module's CPU or I/O load demonstrably degrades other modules' SLAs on the same instance
- Team size grows beyond 5 engineers and module ownership becomes a coordination bottleneck
