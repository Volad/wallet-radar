#!/usr/bin/env sh
# Deprecated alias: rebuilds backend without Mongo reset.
# Prefer: ./scripts/prod-reset-rebuild-backend.sh --backend-only
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec sh "$SCRIPT_DIR/prod-reset-rebuild-backend.sh" --backend-only
