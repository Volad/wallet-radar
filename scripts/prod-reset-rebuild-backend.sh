#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

clear_pricing_cache=false
rebuild_frontend=true

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
    *)
      printf 'Unknown argument: %s\n' "$arg" >&2
      printf 'Usage: %s [--clear-pricing-cache] [--start-frontend] [--skip-frontend]\n' "$0" >&2
      exit 1
      ;;
  esac
done

compose() {
  docker compose -f "$REPO_ROOT/docker-compose.yml" -f "$REPO_ROOT/docker-compose.prod.yml" --profile prod "$@"
}

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
compose stop frontend-prod backend-prod mongodb-prod

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

printf 'Rebuilding backend-prod image...\n'
compose build backend-prod

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
