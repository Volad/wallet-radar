#!/usr/bin/env sh
# Compare two financial-snapshot.json files (exact BigDecimal equality on terminal rows).
set -eu

if [ $# -ne 2 ]; then
  printf 'Usage: %s <before.json> <after.json>\n' "$0" >&2
  exit 1
fi

before=$1
after=$2

python3 - "$before" "$after" <<'PY'
import json, sys
from decimal import Decimal

def load(path):
    with open(path) as f:
        return json.load(f)

def key(row):
    u = row["_id"]
    return (
        u.get("universe"),
        u.get("wallet"),
        u.get("network"),
        u.get("asset"),
    )

def as_map(doc):
    out = {}
    for row in doc.get("terminalByAsset", []):
        out[key(row)] = row
    return out

before = load(sys.argv[1])
after = load(sys.argv[2])

fields = ("quantityAfter", "avcoAfterUsd", "totalCostBasisAfterUsd", "cumulativeRealisedPnlUsd")
mismatches = []

for k in sorted(set(before_map := as_map(before)) | set(after_map := as_map(after))):
    b = before_map.get(k)
    a = after_map.get(k)
    if b is None:
        mismatches.append((k, "missing in before", a))
        continue
    if a is None:
        mismatches.append((k, "missing in after", b))
        continue
    for f in fields:
        bv, av = b.get(f), a.get(f)
        if bv is None and av is None:
            continue
        try:
            bd = Decimal(str(bv)) if bv is not None else None
            ad = Decimal(str(av)) if av is not None else None
        except Exception:
            bd, ad = bv, av
        if bd != ad:
            mismatches.append((k, f, bv, av))

cons_b = before.get("conservation", {})
cons_a = after.get("conservation", {})
cons_diff = {k: (cons_b.get(k), cons_a.get(k)) for k in cons_b if cons_b.get(k) != cons_a.get(k)}

print(f"before assets: {len(before_map)}")
print(f"after assets:  {len(after_map)}")
print(f"field mismatches: {len(mismatches)}")
if cons_diff:
    print(f"conservation diffs: {cons_diff}")

if mismatches:
    print("\nFirst 20 mismatches:")
    for item in mismatches[:20]:
        print(item)
    sys.exit(1)

print("PARITY OK — terminal financial snapshot matches")
PY
