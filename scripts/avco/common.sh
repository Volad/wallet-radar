#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)

if [ -f "$REPO_ROOT/.runtime-ports.env" ]; then
  # shellcheck disable=SC1091
  . "$REPO_ROOT/.runtime-ports.env"
fi

if [ -f "$REPO_ROOT/.env" ]; then
  # shellcheck disable=SC1091
  . "$REPO_ROOT/.env"
fi

detect_runtime_profile() {
  if docker ps --format '{{.Names}}' | grep -qx 'walletradar-mongodb-prod'; then
    printf '%s\n' "prod"
    return
  fi
  printf '%s\n' "local"
}

mongo_uri_for_profile() {
  profile=$1
  case "$profile" in
    prod)
      printf '%s\n' "${WR_MONGO_URI:-mongodb://localhost:${WR_PROD_MONGODB_PORT:-27019}/walletradar}"
      ;;
    local)
      printf '%s\n' "${WR_MONGO_URI:-${MONGODB_URI:-mongodb://localhost:27018/walletradar}}"
      ;;
    *)
      printf 'Unsupported runtime profile: %s\n' "$profile" >&2
      exit 1
      ;;
  esac
}

resolve_mongo_uri() {
  if [ "${WR_MONGO_URI:-}" != "" ]; then
    printf '%s\n' "$WR_MONGO_URI"
    return
  fi
  profile=${WR_RUNTIME_PROFILE:-$(detect_runtime_profile)}
  mongo_uri_for_profile "$profile"
}

mongo_container_for_profile() {
  profile=$1
  case "$profile" in
    prod)
      printf '%s\n' "walletradar-mongodb-prod"
      ;;
    local)
      printf '%s\n' "walletradar-mongodb"
      ;;
    *)
      printf 'Unsupported runtime profile: %s\n' "$profile" >&2
      exit 1
      ;;
  esac
}

run_mongosh() {
  mongo_uri=$1
  shift

  if command -v mongosh >/dev/null 2>&1; then
    mongosh "$mongo_uri" --quiet --file /dev/stdin "$@"
    return
  fi

  profile=${WR_RUNTIME_PROFILE:-$(detect_runtime_profile)}
  container=$(mongo_container_for_profile "$profile")
  docker exec -i "$container" mongosh "mongodb://127.0.0.1:27017/walletradar" --quiet --file /dev/stdin "$@"
}

next_stats_run_id() {
  stats_dir="$REPO_ROOT/results/stats"
  mkdir -p "$stats_dir"
  last_id=$(ls -1 "$stats_dir" 2>/dev/null | rg '^[0-9]+$' | sort -n | tail -n 1 || true)
  if [ -z "$last_id" ]; then
    printf '%s\n' "1"
    return
  fi
  printf '%s\n' "$((last_id + 1))"
}
