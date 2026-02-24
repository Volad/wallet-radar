# ADR-010: Gradle and Monorepo

**Date:** 2025  
**Status:** Accepted  
**Deciders:** Product Owner, Solution Architect

---

## Context

WalletRadar has a backend (Java 21, Spring Boot) and a frontend (Angular). We need a single source of truth for:
- Build tool for the backend (Gradle vs Maven).
- Repository layout: one repo for both backend and frontend (monorepo) vs separate repos.

An initial bootstrap used Maven; the agent rules and system-architect definition specified Gradle. The project must follow the rules consistently.

---

## Decision

1. **Build tool for backend: Gradle**
   - All backend code is built with **Gradle** (Groovy or Kotlin DSL). Use Gradle Wrapper (`gradlew`) at the backend root.
   - **Do not use Maven** for the backend. Any existing Maven setup (e.g. `pom.xml`) should be replaced with a Gradle build when aligning the codebase with this ADR.

2. **Repository layout: monorepo**
   - **Backend** and **frontend** live in the **same repository** (monorepo).
   - **Backend:** One Gradle project (root or under `backend/`). Contains all Java/Spring Boot modules per the architecture (api, ingestion, costbasis, pricing, snapshot, domain, config, common).
   - **Frontend:** Angular application in a dedicated directory (e.g. `frontend/`), with its own `package.json` and npm/yarn build.
   - Root may be a Gradle multi-project that includes the backend (and optionally triggers or wraps frontend build), or `backend/` and `frontend/` may be built independently from the root (e.g. `./gradlew :backend:build` and `cd frontend && npm run build`).

---

## Rationale

- **Gradle:** Aligns with existing agent and architect rules; flexible for multi-module and future native/npm integration; consistent with many Java/Spring projects.
- **Monorepo:** Single repo simplifies versioning, refactors across API and UI, and CI (one clone, one place for docs and ADRs). No need for separate backend/frontend repos for MVP.

---

## Consequences

- **.cursor/rules:** Build and repository layout are codified in a rule (e.g. `build-and-repo.mdc`) so agents use Gradle and monorepo layout.
- **docs/02-architecture.md** and **docs/00-context.md** state Gradle and monorepo explicitly.
- **Migration:** If the codebase currently uses Maven, it must be migrated to Gradle (same source, new build scripts) and placed in the chosen monorepo layout (e.g. `backend/`). Frontend, when added, goes in `frontend/`.
