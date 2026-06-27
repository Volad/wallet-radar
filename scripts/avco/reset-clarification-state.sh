#!/usr/bin/env sh
# Targeted reset for stuck PENDING_CLARIFICATION transactions.
# Resets clarificationEvidence on the matching raw_transactions and clears pipeline state.
# Does NOT delete normalized_transactions or re-run normalization.
# Use with --clarification-only in prod-reset-rebuild-backend.sh.
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"

mongo_uri=$(resolve_mongo_uri)

run_mongosh "$mongo_uri" <<'EOF'
const pendingCount = db.normalized_transactions.countDocuments({ status: "PENDING_CLARIFICATION" });
print("PENDING_CLARIFICATION normalized_transactions: " + pendingCount);

if (pendingCount > 0) {
  // Reset retry counters so the clarification job will pick them up again.
  db.normalized_transactions.updateMany(
    { status: "PENDING_CLARIFICATION" },
    {
      $set:  { clarificationAttempts: 0, fullReceiptClarificationAttempts: 0, retryCount: 0 },
      $unset: { updatedAt: "", lastError: "", nextRetryAt: "" }
    }
  );

  // Collect txHashes of those documents so we can wipe clarificationEvidence from raw_transactions.
  const hashes = db.normalized_transactions.distinct("txHash", { status: "PENDING_CLARIFICATION" });
  print("Resetting clarificationEvidence on " + hashes.length + " raw transactions");
  if (hashes.length > 0) {
    db.raw_transactions.updateMany(
      { txHash: { $in: hashes } },
      { $unset: { clarificationEvidence: "", lastError: "", nextRetryAt: "" } }
    );
  }
}

// Always clear pipeline run state so the backend starts a fresh run.
db.user_sessions.updateMany({}, { $unset: { pipelineState: "" } });

print("Clarification state reset complete — " + pendingCount + " transactions re-queued");
EOF
