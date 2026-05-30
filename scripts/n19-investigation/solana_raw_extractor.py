#!/usr/bin/env python3
"""
N19 / S3-solana — Solana raw extractor via Helius.

Pulls all enhanced/parsed transactions for OWN Solana wallets.

Output:
  cycle/5/results/n19-raw/onchain-solana.jsonl
"""

from __future__ import annotations

import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional
from urllib import error, parse, request

PROJECT_ROOT = Path(__file__).resolve().parents[2]
ENV_PATH = PROJECT_ROOT / ".env"
ACCOUNT_UNIVERSE = PROJECT_ROOT / "cycle-autorun/cycle-data/cycle/5/results/n19-account-universe.json"
OUT_PATH = PROJECT_ROOT / "cycle-autorun/cycle-data/cycle/5/results/n19-raw/onchain-solana.jsonl"
SUMMARY_PATH = PROJECT_ROOT / "cycle-autorun/cycle-data/cycle/5/results/n19-solana-raw-summary.json"

PAGE_LIMIT = 100


def load_env(path: Path) -> Dict[str, str]:
    env: Dict[str, str] = {}
    if not path.exists():
        return env
    for raw_line in path.read_text().splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        env[k.strip()] = v.strip().strip('"').strip("'")
    return env


def http_get_json(url: str, max_retries: int = 6) -> Any:
    backoff = 1.0
    for attempt in range(max_retries):
        try:
            req = request.Request(url, headers={"User-Agent": "n19-solana-extractor/1.0"})
            with request.urlopen(req, timeout=60) as resp:
                body = resp.read().decode("utf-8")
                return json.loads(body)
        except error.HTTPError as e:
            if e.code in (429, 502, 503, 504):
                time.sleep(backoff)
                backoff = min(backoff * 2, 30.0)
                continue
            if attempt == max_retries - 1:
                raise
            time.sleep(backoff)
            backoff = min(backoff * 2, 30.0)
        except (error.URLError, TimeoutError, json.JSONDecodeError):
            time.sleep(backoff)
            backoff = min(backoff * 2, 30.0)
            if attempt == max_retries - 1:
                raise
    raise RuntimeError(f"Exhausted retries: {url}")


def fetch_helius_address_transactions(
    template_url: str,
    address: str,
) -> Iterable[Dict[str, Any]]:
    """
    Helius v0 enhanced/parsed transactions for an address.
    Endpoint shape:
      https://api-mainnet.helius-rpc.com/v0/addresses/{address}/transactions/?api-key=...
    Supports pagination via `before=<signature>`.
    """
    before: Optional[str] = None
    page = 0
    base_url = template_url.replace("{address}", parse.quote(address))
    while True:
        url = base_url + (f"&limit={PAGE_LIMIT}" if "?" in base_url else f"?limit={PAGE_LIMIT}")
        if before:
            url += f"&before={parse.quote(before)}"
        body = http_get_json(url)
        if not isinstance(body, list) or not body:
            break
        for r in body:
            yield {
                "__stream": "solana-helius",
                "__network": "SOLANA",
                "__ownWallet": address,
                "__fetchedAt": datetime.now(timezone.utc).isoformat(),
                **r,
            }
        if len(body) < PAGE_LIMIT:
            break
        last = body[-1]
        before = last.get("signature")
        if not before:
            break
        page += 1
        if page > 200:
            break
        time.sleep(0.25)


def main() -> int:
    env = load_env(ENV_PATH)
    template = env.get("SOLANA_HELIUS_PARSE_TRANSACTIONS_HISTORY_URL")
    if not template:
        print("[solana] SOLANA_HELIUS_PARSE_TRANSACTIONS_HISTORY_URL not set in .env", file=sys.stderr)
        return 1
    if "{address}" not in template:
        print("[solana] URL template must contain {address}", file=sys.stderr)
        return 1

    universe = json.loads(ACCOUNT_UNIVERSE.read_text())
    solana_section = (universe.get("outOfScope") or {}).get("solana") or {}
    addresses: List[str] = [a for a in (solana_section.get("addresses") or []) if a]
    if not addresses:
        print("[solana] No Solana addresses in account universe", file=sys.stderr)
        return 1

    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    summary: Dict[str, Any] = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "addresses": addresses,
        "perAddress": {},
    }
    print(f"[solana] -> {OUT_PATH}")
    total = 0
    with OUT_PATH.open("w") as f:
        for addr in addresses:
            count = 0
            try:
                for row in fetch_helius_address_transactions(template, addr):
                    f.write(json.dumps(row) + "\n")
                    count += 1
            except Exception as e:
                print(f"  [err] {addr[:12]}: {e}", file=sys.stderr)
            summary["perAddress"][addr] = count
            total += count
            print(f"  {addr[:14]}…  rows={count}")
    print(f"[solana] total rows: {total}")
    summary["solanaTotal"] = total

    SUMMARY_PATH.write_text(json.dumps(summary, indent=2))
    print(f"\nSummary written -> {SUMMARY_PATH}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
