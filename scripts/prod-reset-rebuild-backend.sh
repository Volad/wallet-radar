#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

clear_pricing_cache=false
rebuild_frontend=true
frontend_only=false
# Targeted partial-reset modes — skip the full MongoDB teardown/renormalization.
#   --linking-only:           No Mongo wipe; restart backend so linking/pricing/replay re-run.
#   --reclassification-only:  Reset ON_CHAIN rows to PENDING_RECLASSIFICATION + clear downstream.
#   --clarification-only:     Reset PENDING_CLARIFICATION evidence + clear pipelineState.
linking_only=false
reclassification_only=false
clarification_only=false

for arg in "$@"; do
  case "$arg" in
    --clear-pricing-cache)
      clear_pricing_cache=true
      ;;
    --start-frontend)
      rebuild_frontend=true
      ;;
    --skip-frontend)
      rebuild_frontend=false
      ;;
    --frontend-only)
      frontend_only=true
      rebuild_frontend=true
      ;;
    --linking-only)
      linking_only=true
      rebuild_frontend=false
      ;;
    --reclassification-only)
      reclassification_only=true
      rebuild_frontend=false
      ;;
    --clarification-only)
      clarification_only=true
      rebuild_frontend=false
      ;;
    *)
      printf 'Unknown argument: %s\n' "$arg" >&2
      printf 'Usage: %s [--clear-pricing-cache] [--start-frontend] [--skip-frontend] [--frontend-only]\n' "$0" >&2
      printf '           [--linking-only] [--reclassification-only] [--clarification-only]\n' >&2
      exit 1
      ;;
  esac
done

compose() {
  docker compose -f "$REPO_ROOT/docker-compose.yml" -f "$REPO_ROOT/docker-compose.prod.yml" --profile prod "$@"
}

# ─── Targeted partial-reset shortcuts ─────────────────────────────────────────

if [ "$linking_only" = "true" ]; then
  printf 'Targeted rebuild: linking-only (no Mongo wipe, no renormalization).\n'
  printf 'MongoDB stays running. Only pipelineState is cleared so linking re-runs from start.\n'
  compose stop backend-prod
  compose rm -f -s backend-prod >/dev/null 2>&1 || true
  # Clear only the pipeline-run state; normalised data is preserved.
  sh "$SCRIPT_DIR/avco/reset-clarification-state.sh" 2>/dev/null || true
  # Override: we only want pipelineState cleared, not clarification evidence.
  # reset-clarification-state handles that conservatively (only PENDING_CLARIFICATION rows).
  printf 'Rebuilding backend-prod image (no cache)...\n'
  compose build --no-cache backend-prod
  compose up -d backend-prod
  printf 'Done (linking-only rebuild).\n'
  exit 0
fi

if [ "$reclassification_only" = "true" ]; then
  printf 'Targeted rebuild: reclassification-only (resets ON_CHAIN to PENDING_RECLASSIFICATION).\n'
  compose stop backend-prod
  compose rm -f -s backend-prod >/dev/null 2>&1 || true
  sh "$SCRIPT_DIR/avco/reset-reclassification-state.sh"
  printf 'Rebuilding backend-prod image (no cache)...\n'
  compose build --no-cache backend-prod
  compose up -d backend-prod
  printf 'Done (reclassification-only rebuild).\n'
  exit 0
fi

if [ "$clarification_only" = "true" ]; then
  printf 'Targeted rebuild: clarification-only (resets stuck PENDING_CLARIFICATION rows).\n'
  compose stop backend-prod
  compose rm -f -s backend-prod >/dev/null 2>&1 || true
  sh "$SCRIPT_DIR/avco/reset-clarification-state.sh"
  printf 'Rebuilding backend-prod image (no cache)...\n'
  compose build --no-cache backend-prod
  compose up -d backend-prod
  printf 'Done (clarification-only rebuild).\n'
  exit 0
fi

if [ "$frontend_only" = "true" ]; then
  printf 'Rebuilding frontend-prod only (Mongo and backend unchanged)...\n'
  compose stop frontend-prod
  compose rm -f -s frontend-prod >/dev/null 2>&1 || true
  compose build frontend-prod
  compose up -d frontend-prod
  printf 'Done.\n'
  exit 0
fi

wait_for_mongo_ready() {
  container_name=walletradar-mongodb-prod
  attempts=0
  max_attempts=60

  printf 'Waiting for %s healthcheck...\n' "$container_name"
  while [ "$attempts" -lt "$max_attempts" ]; do
    status=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_name" 2>/dev/null || true)
    if [ "$status" = "healthy" ]; then
      return 0
    fi
    attempts=$((attempts + 1))
    sleep 1
  done

  printf 'Timed out waiting for %s to become healthy\n' "$container_name" >&2
  docker logs --tail 50 "$container_name" >&2 || true
  exit 1
}

printf 'Stopping prod services...\n'
if [ "$rebuild_frontend" = "true" ]; then
  compose stop frontend-prod backend-prod mongodb-prod
else
  compose stop backend-prod mongodb-prod
fi

printf 'Starting prod Mongo only...\n'
compose up -d mongodb-prod
wait_for_mongo_ready

printf 'Resetting Mongo state for full renormalization...\n'
if [ "$clear_pricing_cache" = "true" ]; then
  sh "$SCRIPT_DIR/avco/reset-derived.sh" --clear-pricing-cache
else
  sh "$SCRIPT_DIR/avco/reset-derived.sh"
fi

printf 'Removing old prod application containers if present...\n'
if [ "$rebuild_frontend" = "true" ]; then
  compose rm -f -s backend-prod frontend-prod >/dev/null 2>&1 || true
else
  compose rm -f -s backend-prod >/dev/null 2>&1 || true
fi

printf 'Rebuilding backend-prod image (no cache)...\n'
compose build --no-cache backend-prod

if [ "$rebuild_frontend" = "true" ]; then
  printf 'Rebuilding frontend-prod image...\n'
  compose build frontend-prod
fi

printf 'Starting backend-prod...\n'
compose up -d backend-prod

if [ "$rebuild_frontend" = "true" ]; then
  printf 'Starting frontend-prod...\n'
  compose up -d frontend-prod
fi

printf 'Done.\n'
