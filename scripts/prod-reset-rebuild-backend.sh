#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

clear_pricing_cache=false
start_frontend=false

for arg in "$@"; do
  case "$arg" in
    --clear-pricing-cache)
      clear_pricing_cache=true
      ;;
    --start-frontend)
      start_frontend=true
      ;;
    *)
      printf 'Unknown argument: %s\n' "$arg" >&2
      printf 'Usage: %s [--clear-pricing-cache] [--start-frontend]\n' "$0" >&2
      exit 1
      ;;
  esac
done

compose() {
  docker compose -f "$REPO_ROOT/docker-compose.yml" -f "$REPO_ROOT/docker-compose.prod.yml" --profile prod "$@"
}

printf 'Stopping prod services...\n'
compose stop frontend-prod backend-prod mongodb-prod

printf 'Starting prod Mongo only...\n'
compose up -d mongodb-prod

printf 'Resetting Mongo state for full renormalization...\n'
if [ "$clear_pricing_cache" = "true" ]; then
  sh "$SCRIPT_DIR/avco/reset-derived.sh" --clear-pricing-cache
else
  sh "$SCRIPT_DIR/avco/reset-derived.sh"
fi

printf 'Removing old backend container if present...\n'
compose rm -f -s backend-prod >/dev/null 2>&1 || true

printf 'Rebuilding backend-prod image...\n'
compose build backend-prod

printf 'Starting backend-prod...\n'
compose up -d backend-prod

if [ "$start_frontend" = "true" ]; then
  printf 'Starting frontend-prod...\n'
  compose up -d frontend-prod
fi

printf 'Done.\n'
