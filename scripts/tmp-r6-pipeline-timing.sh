#!/usr/bin/env bash
# Poll user_sessions.pipelineState and print per-stage durations during full re-norm.
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/avco/common.sh"

mongo_uri=$(resolve_mongo_uri)
poll_secs=${POLL_SECS:-5}
timeout_secs=${TIMEOUT_SECS:-7200}

start_ts=$(date +%s)
last_stage=""
last_stage_ts=""

format_duration() {
  local secs=$1
  if (( secs < 60 )); then
    printf '%ss' "$secs"
  elif (( secs < 3600 )); then
    printf '%sm %ss' $((secs / 60)) $((secs % 60))
  else
    printf '%sh %sm %ss' $((secs / 3600)) $(((secs % 3600) / 60)) $((secs % 60))
  fi
}

read_state() {
  run_mongosh "$mongo_uri" <<'EOF'
const s = db.getSiblingDB("walletradar").user_sessions.findOne({}, { pipelineState: 1 });
if (!s || !s.pipelineState) {
  print("NONE|NONE|");
} else {
  const p = s.pipelineState;
  print([p.stage || "NONE", p.status || "NONE", p.updatedAt ? p.updatedAt.toISOString() : ""].join("|"));
}
EOF
}

printf '\n=== R6 pipeline timing monitor (poll=%ss) ===\n' "$poll_secs"
printf 'mongo: %s\nstarted: %s\n\n' "$mongo_uri" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"

while true; do
  now=$(date +%s)
  elapsed=$((now - start_ts))
  if (( elapsed >= timeout_secs )); then
    printf '\nTIMEOUT after %s\n' "$(format_duration "$elapsed")"
    exit 2
  fi

  line=$(read_state | tail -1)
  stage=$(printf '%s' "$line" | cut -d'|' -f1)
  status=$(printf '%s' "$line" | cut -d'|' -f2)
  updated=$(printf '%s' "$line" | cut -d'|' -f3)

  if [[ "$stage" != "$last_stage" ]]; then
    if [[ -n "$last_stage" && -n "$last_stage_ts" && "$last_stage" != "NONE" ]]; then
      dur=$((now - last_stage_ts))
      printf '[%s] STAGE DONE  %-28s duration=%s\n' "$(date -u +%H:%M:%S)" "$last_stage" "$(format_duration "$dur")"
    fi
    if [[ "$stage" != "NONE" ]]; then
      printf '[%s] STAGE START %-28s status=%-8s updated=%s\n' "$(date -u +%H:%M:%S)" "$stage" "$status" "$updated"
    fi
    last_stage="$stage"
    last_stage_ts=$now
  else
    printf '[%s] STILL      %-28s status=%-8s\n' "$(date -u +%H:%M:%S)" "$stage" "$status"
  fi

  if [[ "$stage" == "PORTFOLIO_SNAPSHOT_REFRESH" && "$status" == "COMPLETE" ]]; then
    if [[ -n "$last_stage" && "$last_stage" == "PORTFOLIO_SNAPSHOT_REFRESH" ]]; then
      dur=$(( $(date +%s) - last_stage_ts ))
      printf '[%s] STAGE DONE  %-28s duration=%s\n' "$(date -u +%H:%M:%S)" "PORTFOLIO_SNAPSHOT_REFRESH" "$(format_duration "$dur")"
    fi
    total=$(( $(date +%s) - start_ts ))
    printf '\n=== PIPELINE COMPLETE total=%s ===\n' "$(format_duration "$total")"
    exit 0
  fi

  if [[ "$stage" == "ACCOUNTING_REPLAY" && "$status" == "COMPLETE" ]]; then
    sleep "$poll_secs"
    line2=$(read_state | tail -1)
    stage2=$(printf '%s' "$line2" | cut -d'|' -f1)
    status2=$(printf '%s' "$line2" | cut -d'|' -f2)
    if [[ "$stage2" == "PORTFOLIO_SNAPSHOT_REFRESH" && "$status2" != "COMPLETE" ]]; then
      last_stage=""
      continue
    fi
    if [[ "$stage2" == "PORTFOLIO_SNAPSHOT_REFRESH" && "$status2" == "COMPLETE" ]]; then
      dur=$(( $(date +%s) - last_stage_ts ))
      printf '[%s] STAGE DONE  %-28s duration=%s\n' "$(date -u +%H:%M:%S)" "PORTFOLIO_SNAPSHOT_REFRESH" "$(format_duration "$dur")"
    fi
    total=$(( $(date +%s) - start_ts ))
    printf '\n=== PIPELINE COMPLETE total=%s ===\n' "$(format_duration "$total")"
    exit 0
  fi

  sleep "$poll_secs"
done
