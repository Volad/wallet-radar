# A6 — Gradle subprojects (Track A)

Compiler-enforced module boundaries via Gradle subprojects. Target DAG aligns with
`ModuleBoundaryTest` package rules.

## Current layout

All backend modules live under `backend/`:

```
wallet-radar/
├── settings.gradle.kts
├── build.gradle.kts
└── backend/
    ├── build.gradle.kts     # :backend — Spring Boot launcher (bootJar / bootRun)
    ├── core/                # :backend:core — application (api, platform, application.*, costbasis, …)
    │   └── src/
    ├── domain/              # :backend:domain — shared entities + Mongo @Document models
    ├── canonical/           # :backend:canonical — pure correlation/carry helpers
    └── config/checkstyle/
```

### Dependency DAG

```
:backend:domain
   ↑
:backend:canonical
   ↑
:backend:core          (java-library; all former backend/src code)
   ↑
:backend               (Spring Boot fat JAR aggregator)
```

## Commands

| Task | Command |
|------|---------|
| Full unit tests | `./gradlew test` |
| Application tests | `./gradlew :backend:core:test` |
| Backend umbrella (runs core + domain + canonical tests) | `./gradlew :backend:test` |
| Run API | `./gradlew :backend:bootRun` |
| Boot JAR | `./gradlew :backend:bootJar` |
| Domain only | `./gradlew :backend:domain:test` |
| Canonical only | `./gradlew :backend:canonical:test` |

Docker Compose still uses `:backend:bootRun`.

## Package naming decision

Packages `costbasis/`, `pricing/`, `lending/`, `liquiditypools/`, `portfolio/` are **intentionally kept at top level** — they will become separate Gradle subprojects without renaming to `application.costbasis` etc. Rationale: names are stable in ADRs, docs and tests; compiler-enforced boundaries are more valuable than naming uniformity. No cross-package moves planned before Gradle splits.

## Remaining splits (next milestones)

Split `:backend:core` into vertical modules (same packages, compiler-enforced DAG):

| # | Subproject | Source packages (target) | Depends on | Status |
|---|------------|--------------------------|------------|--------|
| 1 | `:backend:platform` | `com.walletradar.platform.**` | `:backend:domain` | **Next** |
| 2 | `:backend:app-costbasis` | `costbasis.**` | `:backend:domain`, `:backend:canonical`, `:backend:platform` | Pending |
| 3 | `:backend:app-pricing` | `pricing.**` | `:backend:domain`, `:backend:platform` | Pending |
| 4 | `:backend:app-portfolio` | `portfolio.**`, `liquiditypools.**`, `lending.**` | `:backend:domain`, ports | Pending |
| 5 | `:backend:app-cex` | `application.cex.**` | `:backend:domain`, `:backend:canonical`, `:backend:platform` | Pending |
| 6 | `:backend:app-normalization` | `application.normalization.**` | `:backend:domain`, `:backend:canonical`, `:backend:platform` | Pending |
| 7 | `:backend:app-backfill` | `application.backfill.**` | `:backend:domain`, `:backend:platform` | Pending |
| 8 | `:backend:app-linking` | `application.linking.**` | `:backend:domain`, `:backend:canonical`, `:backend:platform` | Pending |
| 9 | `:backend:app-pipeline` | `application.pipeline.**`, orchestration jobs | app modules via ports | Pending |
| 10 | `:backend:api` | `api.**`, `config.**`, `auth.**`, `session.**`, `integration.**` | all app modules | Pending |

## Split checklist (per subproject)

For each extracted subproject:
1. Create `backend/<name>/build.gradle.kts` with explicit `dependencies` block
2. Move source tree — `git mv backend/core/src/.../com/walletradar/<pkg> backend/<name>/src/...`
3. Add to `settings.gradle.kts`
4. Fix compile errors (missing deps → add ports or move to correct layer)
5. Run `./gradlew :backend:<name>:compileJava`
6. Add ArchUnit boundary test to the subproject
7. Run `./gradlew :backend:core:test` — full suite green
8. Run `./scripts/prod-reset-rebuild-backend.sh --backend-only` — prod smoke

## Pre-split housekeeping (A2, do first)

Small cleanups that reduce noise during splits:

| Item | Action | Size |
|------|--------|------|
| `integration/admin` | Already cleaned (admin API removed). Move `integration/config` → `config/` or `session/config` | ~3 files |
| `auth/` | Move 2 files → `platform/security` | 2 files |
| `config/` (root-level Spring configs) | Move → `session/config` or `platform/common` | 4 files |

## Notes

- `WalletRadarApplication` lives in `:backend:core`; `:backend` sets `springBoot.mainClass` for `bootJar` / `bootRun`. After full split it migrates to `:backend:api` or stays in `:backend`.
- `ModuleBoundaryTest` runs in `:backend:core`; update location after each split.
- `IdentityProvider` and `TonAddressCanonicalizer` live in `:backend:domain`.
- `accounting/support` stays shared — it will remain in `:backend:core` or migrate to `:backend:canonical` (pure helpers, no Spring).
