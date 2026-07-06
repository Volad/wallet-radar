#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

if [ $# -ne 2 ]; then
  printf 'Usage: %s <base-run-id> <candidate-run-id>\n' "$0" >&2
  exit 1
fi

base_run_id=$1
candidate_run_id=$2

base_dir="$REPO_ROOT/results/stats/$base_run_id"
candidate_dir="$REPO_ROOT/results/stats/$candidate_run_id"

base_summary="$base_dir/summary.json"
candidate_summary="$candidate_dir/summary.json"
base_fingerprints="$base_dir/data/derived/replay-fingerprints.json"
candidate_fingerprints="$candidate_dir/data/derived/replay-fingerprints.json"

for required_file in \
  "$base_summary" \
  "$candidate_summary" \
  "$base_fingerprints" \
  "$candidate_fingerprints"
do
  if [ ! -f "$required_file" ]; then
    printf 'Missing required file: %s\n' "$required_file" >&2
    exit 1
  fi
done

base_summary_compact=$(tr -d '\n' < "$base_summary")
candidate_summary_compact=$(tr -d '\n' < "$candidate_summary")
base_fingerprints_compact=$(tr -d '\n' < "$base_fingerprints")
candidate_fingerprints_compact=$(tr -d '\n' < "$candidate_fingerprints")

BASE_SUMMARY="$base_summary_compact" \
CANDIDATE_SUMMARY="$candidate_summary_compact" \
BASE_FINGERPRINTS="$base_fingerprints_compact" \
CANDIDATE_FINGERPRINTS="$candidate_fingerprints_compact" \
node <<'EOF'
const baseSummary = JSON.parse(process.env.BASE_SUMMARY);
const candidateSummary = JSON.parse(process.env.CANDIDATE_SUMMARY);
const baseFingerprints = JSON.parse(process.env.BASE_FINGERPRINTS);
const candidateFingerprints = JSON.parse(process.env.CANDIDATE_FINGERPRINTS);

function flatten(prefix, value, target) {
  if (Array.isArray(value)) {
    target[prefix] = JSON.stringify(value);
    return;
  }
  if (value && typeof value === "object") {
    for (const [key, nested] of Object.entries(value)) {
      flatten(prefix ? `${prefix}.${key}` : key, nested, target);
    }
    return;
  }
  target[prefix] = value;
}

function diffObjects(base, candidate) {
  const flatBase = {};
  const flatCandidate = {};
  flatten("", base, flatBase);
  flatten("", candidate, flatCandidate);
  const keys = [...new Set([...Object.keys(flatBase), ...Object.keys(flatCandidate)])].sort();
  return keys
    .filter((key) => flatBase[key] !== flatCandidate[key])
    .map((key) => ({
      key,
      base: flatBase[key] ?? null,
      candidate: flatCandidate[key] ?? null
    }));
}

const summaryDiffs = diffObjects(baseSummary, candidateSummary);
const fingerprintDiffs = diffObjects(baseFingerprints, candidateFingerprints)
  .filter((entry) => entry.key !== "capturedAt");

console.log(JSON.stringify({
  baseRunId: process.argv[1],
  candidateRunId: process.argv[2],
  summaryDiffs,
  fingerprintDiffs,
  summaryMatch: summaryDiffs.length === 0,
  fingerprintMatch: fingerprintDiffs.length === 0
}, null, 2));
EOF
