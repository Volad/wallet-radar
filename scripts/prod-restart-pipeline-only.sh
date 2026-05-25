#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

compose() {
  docker compose -f "$REPO_ROOT/docker-compose.yml" -f "$REPO_ROOT/docker-compose.prod.yml" --profile prod "$@"
}

printf 'Rebuilding backend image...\n'
"$REPO_ROOT/gradlew" :backend:bootJar -q

printf 'Restarting backend container (no Mongo reset)...\n'
compose build backend-prod
compose up -d backend-prod

printf 'Backend restarted. Monitor pipeline via /api/v1/sessions/<id>/backfill-status\n'
