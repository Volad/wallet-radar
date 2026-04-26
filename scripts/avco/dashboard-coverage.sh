#!/usr/bin/env sh
set -eu

backend_url="http://127.0.0.1:18086"
session_id=""

while [ $# -gt 0 ]; do
  case "$1" in
    --backend-url)
      backend_url=${2:-}
      shift 2
      ;;
    --session-id)
      session_id=${2:-}
      shift 2
      ;;
    *)
      printf 'Unknown argument: %s\n' "$1" >&2
      exit 1
      ;;
  esac
done

if [ -z "$session_id" ]; then
  printf 'Usage: %s --session-id <session-id> [--backend-url <url>]\n' "$0" >&2
  exit 1
fi

dashboard_file=$(mktemp)
trap 'rm -f "$dashboard_file"' EXIT

curl -sf "$backend_url/api/v1/sessions/$session_id/dashboard" > "$dashboard_file"

node - "$dashboard_file" <<'EOF'
const fs = require('fs');

const dashboardPath = process.argv[2];
const data = JSON.parse(fs.readFileSync(dashboardPath, 'utf8'));
const positions = Array.isArray(data.tokenPositions) ? data.tokenPositions : [];

function num(value) {
  return typeof value === 'number' ? value : Number(value);
}

let totalMarketValueUsd = 0;
let coveredMarketValueUsd = 0;
const issueBreakdown = new Map();
const topProblemPositions = [];

for (const position of positions) {
  const quantity = num(position.quantity);
  const priceUsd = num(position.priceUsd);
  const backendMarketValueUsd = num(position.marketValueUsd);
  const marketValueUsd = Number.isFinite(backendMarketValueUsd)
    ? backendMarketValueUsd
    : quantity * priceUsd;
  const issue = position.priceIssue ?? position.issue ?? 'none';
  if (!Number.isFinite(marketValueUsd) || (marketValueUsd === 0 && issue !== 'unsupported_protocol_valuation')) {
    continue;
  }

  totalMarketValueUsd += marketValueUsd;
  issueBreakdown.set(issue, (issueBreakdown.get(issue) ?? 0) + marketValueUsd);

  if (issue === 'none' || issue === 'yield_accrual') {
    coveredMarketValueUsd += marketValueUsd;
  } else {
    topProblemPositions.push({
      symbol: position.symbol,
      networkId: position.networkId,
      walletAddress: position.walletAddress,
      issue,
      marketValueUsd,
      quantity,
      priceUsd,
      avcoUsd: num(position.avcoUsd),
      valuationModel: position.valuationModel ?? null,
      valuationUnderlyingSymbol: position.valuationUnderlyingSymbol ?? null,
      unsupportedValuationReason: position.unsupportedValuationReason ?? null
    });
  }
}

topProblemPositions.sort((left, right) => right.marketValueUsd - left.marketValueUsd);

const serializedIssueBreakdown = Array.from(issueBreakdown.entries())
  .map(([issue, marketValueUsd]) => ({issue, marketValueUsd}))
  .sort((left, right) => right.marketValueUsd - left.marketValueUsd);

console.log(JSON.stringify({
  capturedAt: new Date().toISOString(),
  sessionId: data.sessionId ?? null,
  portfolioValueUsd: data.summary?.portfolioValueUsd ?? null,
  coveredIssuesAllowed: ['none', 'yield_accrual'],
  marketValueUsdTotal: totalMarketValueUsd,
  marketValueUsdCovered: coveredMarketValueUsd,
  marketValueCoverageRatio: totalMarketValueUsd === 0 ? 1 : coveredMarketValueUsd / totalMarketValueUsd,
  issueBreakdown: serializedIssueBreakdown,
  topProblemPositions: topProblemPositions.slice(0, 10)
}, null, 2));
EOF
