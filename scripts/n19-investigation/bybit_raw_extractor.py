#!/usr/bin/env python3
"""
N19 / S2 — Independent Bybit raw extractor.

Pulls all relevant Bybit V5 endpoints into newline-delimited JSON for
downstream classification & simulation. NO transformation — only raw
shape with the parameters that produced each row attached. Pagination
is exhaustive; rate limiting honoured.

Outputs:
  cycle/5/results/n19-raw/bybit-{stream}.jsonl
  cycle/5/results/n19-raw/bybit-balances.json
  cycle/5/results/n19-bybit-raw-summary.json
"""

from __future__ import annotations

import argparse
import dataclasses
import hashlib
import hmac
import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple
from urllib import parse, request, error

PROJECT_ROOT = Path(__file__).resolve().parents[2]
ENV_PATH = PROJECT_ROOT / ".env"
OUT_DIR = PROJECT_ROOT / "cycle-autorun/cycle-data/cycle/5/results/n19-raw"
SUMMARY_PATH = PROJECT_ROOT / "cycle-autorun/cycle-data/cycle/5/results/n19-bybit-raw-summary.json"

BYBIT_BASE_URL = "https://api.bybit.com"
RECV_WINDOW_MS = 20000
PAGE_LIMIT = 50  # Bybit max varies per endpoint; 50 is conservative
DEFAULT_REQUEST_TIMEOUT_S = 30


# ---------------------------------------------------------------------------
# .env loader (no external dep)
# ---------------------------------------------------------------------------
def load_env(path: Path) -> Dict[str, str]:
    env: Dict[str, str] = {}
    if not path.exists():
        return env
    for raw_line in path.read_text().splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        env[key.strip()] = value.strip().strip('"').strip("'")
    return env


# ---------------------------------------------------------------------------
# Signed GET
# ---------------------------------------------------------------------------
def canonical_query(params: Dict[str, Any]) -> str:
    if not params:
        return ""
    items = sorted((k, str(v)) for k, v in params.items() if v is not None)
    return "&".join(f"{k}={v}" for k, v in items)


def signed_get(
    path: str,
    params: Dict[str, Any],
    api_key: str,
    api_secret: str,
) -> Dict[str, Any]:
    qs = canonical_query(params)
    ts = str(int(time.time() * 1000))
    payload = f"{ts}{api_key}{RECV_WINDOW_MS}{qs}"
    sign = hmac.new(api_secret.encode(), payload.encode(), hashlib.sha256).hexdigest()

    url = f"{BYBIT_BASE_URL}{path}"
    if qs:
        url += f"?{qs}"
    req = request.Request(url, method="GET")
    req.add_header("X-BAPI-API-KEY", api_key)
    req.add_header("X-BAPI-TIMESTAMP", ts)
    req.add_header("X-BAPI-RECV-WINDOW", str(RECV_WINDOW_MS))
    req.add_header("X-BAPI-SIGN", sign)

    backoff = 1.0
    for attempt in range(6):
        try:
            with request.urlopen(req, timeout=DEFAULT_REQUEST_TIMEOUT_S) as resp:
                body = resp.read().decode("utf-8")
                data = json.loads(body)
                ret_code = data.get("retCode", -1)
                if ret_code == 0:
                    return data
                if ret_code in (10006, 10018) or "rate" in (data.get("retMsg") or "").lower():
                    time.sleep(backoff)
                    backoff = min(backoff * 2, 30.0)
                    continue
                raise RuntimeError(
                    f"Bybit error retCode={ret_code} retMsg={data.get('retMsg')} path={path} params={params}"
                )
        except error.HTTPError as e:
            body = e.read().decode("utf-8") if hasattr(e, "read") else str(e)
            if e.code in (429, 502, 503, 504):
                time.sleep(backoff)
                backoff = min(backoff * 2, 30.0)
                continue
            raise RuntimeError(f"Bybit HTTP {e.code}: {body[:300]}") from e
        except (error.URLError, TimeoutError, json.JSONDecodeError) as e:
            time.sleep(backoff)
            backoff = min(backoff * 2, 30.0)
            if attempt == 5:
                raise
    raise RuntimeError(f"Bybit request exhausted retries: {path}")


# ---------------------------------------------------------------------------
# Pagination helpers
# ---------------------------------------------------------------------------
@dataclasses.dataclass
class StreamSpec:
    name: str
    path: str
    base_params: Dict[str, Any]
    paginate_by_cursor: bool = True
    time_window_kind: Optional[str] = None  # "ms" | "sec_create" | None
    time_window_max_ms: int = 30 * 24 * 3600 * 1000  # 30d default
    list_field: str = "list"


# Default time bounds: from 2024-01-01 to today + 1 day to catch tail.
DEFAULT_START_MS = int(datetime(2024, 1, 1, tzinfo=timezone.utc).timestamp() * 1000)
DEFAULT_END_MS = int((datetime.now(timezone.utc).timestamp() + 86400) * 1000)


def slide_windows(start_ms: int, end_ms: int, window_ms: int) -> Iterable[Tuple[int, int]]:
    cur = start_ms
    while cur < end_ms:
        nxt = min(cur + window_ms, end_ms)
        yield cur, nxt
        cur = nxt


def paginate(
    spec: StreamSpec,
    api_key: str,
    api_secret: str,
    fixed_params: Optional[Dict[str, Any]] = None,
) -> Iterable[Dict[str, Any]]:
    fixed_params = fixed_params or {}
    if spec.time_window_kind:
        for from_ms, to_ms in slide_windows(DEFAULT_START_MS, DEFAULT_END_MS, spec.time_window_max_ms):
            yield from _paginate_window(spec, api_key, api_secret, fixed_params, from_ms, to_ms)
    else:
        yield from _paginate_window(spec, api_key, api_secret, fixed_params, None, None)


def _paginate_window(
    spec: StreamSpec,
    api_key: str,
    api_secret: str,
    fixed_params: Dict[str, Any],
    from_ms: Optional[int],
    to_ms: Optional[int],
) -> Iterable[Dict[str, Any]]:
    cursor = None
    page_idx = 0
    while True:
        params = dict(spec.base_params)
        params.update(fixed_params)
        params["limit"] = PAGE_LIMIT
        if from_ms is not None and to_ms is not None:
            if spec.time_window_kind == "ms":
                params["startTime"] = from_ms
                params["endTime"] = to_ms
            elif spec.time_window_kind == "sec_create":
                params["createTimeFrom"] = from_ms // 1000
                params["createTimeTo"] = to_ms // 1000
        if cursor:
            params["cursor"] = cursor
        try:
            body = signed_get(spec.path, params, api_key, api_secret)
        except RuntimeError as e:
            msg = str(e).lower()
            # Some endpoints simply don't exist / are not subscribed -> stop, don't fail run.
            if any(s in msg for s in ("not exist", "permission", "subscribe", "10005", "uniftran", "10001")):
                print(f"  [warn] {spec.name} window {from_ms}-{to_ms} skipped: {e}", file=sys.stderr)
                return
            raise
        result = body.get("result") or {}
        rows = result.get(spec.list_field) or result.get("rows") or []
        if isinstance(rows, dict):
            rows = [rows]
        for row in rows:
            yield {
                "__stream": spec.name,
                "__path": spec.path,
                "__windowFromMs": from_ms,
                "__windowToMs": to_ms,
                "__pageIdx": page_idx,
                "__cursor": cursor,
                "__fetchedAt": datetime.now(timezone.utc).isoformat(),
                **row,
            }
        next_cursor = result.get("nextPageCursor") or result.get("cursor")
        if not next_cursor or next_cursor == cursor or not rows:
            break
        cursor = next_cursor
        page_idx += 1
        time.sleep(0.05)  # polite


# ---------------------------------------------------------------------------
# Stream catalog
# ---------------------------------------------------------------------------
STREAMS: List[StreamSpec] = [
    StreamSpec(
        name="deposit-onchain",
        path="/v5/asset/deposit/query-record",
        base_params={},
        time_window_kind="ms",
        time_window_max_ms=30 * 24 * 3600 * 1000,
    ),
    StreamSpec(
        name="deposit-internal",
        path="/v5/asset/deposit/query-internal-record",
        base_params={},
        time_window_kind="ms",
        time_window_max_ms=30 * 24 * 3600 * 1000,
    ),
    StreamSpec(
        name="withdrawal",
        path="/v5/asset/withdraw/query-record",
        base_params={"withdrawType": 2},
        time_window_kind="ms",
        time_window_max_ms=30 * 24 * 3600 * 1000,
    ),
    StreamSpec(
        name="internal-transfer",
        path="/v5/asset/transfer/query-inter-transfer-list",
        base_params={},
        time_window_kind="ms",
        time_window_max_ms=7 * 24 * 3600 * 1000,
    ),
    StreamSpec(
        name="universal-transfer",
        path="/v5/asset/transfer/query-universal-transfer-list",
        base_params={},
        time_window_kind="ms",
        time_window_max_ms=7 * 24 * 3600 * 1000,
    ),
    StreamSpec(
        name="tx-log-unified",
        path="/v5/account/transaction-log",
        base_params={"accountType": "UNIFIED"},
        time_window_kind="ms",
        time_window_max_ms=7 * 24 * 3600 * 1000,
    ),
    StreamSpec(
        name="funding-history",
        path="/v5/asset/fundinghistory",
        base_params={},
        time_window_kind="sec_create",
        time_window_max_ms=7 * 24 * 3600 * 1000,
    ),
    StreamSpec(
        name="execution-spot",
        path="/v5/execution/list",
        base_params={"category": "spot"},
        time_window_kind="ms",
        time_window_max_ms=7 * 24 * 3600 * 1000,
    ),
    StreamSpec(
        name="execution-linear",
        path="/v5/execution/list",
        base_params={"category": "linear"},
        time_window_kind="ms",
        time_window_max_ms=7 * 24 * 3600 * 1000,
    ),
    StreamSpec(
        name="convert-history-funding",
        path="/v5/asset/exchange/query-convert-history",
        base_params={"accountType": "eb_convert_funding"},
        time_window_kind=None,
    ),
    StreamSpec(
        name="convert-history-uta",
        path="/v5/asset/exchange/query-convert-history",
        base_params={"accountType": "eb_convert_uta"},
        time_window_kind=None,
    ),
    StreamSpec(
        name="crypto-loan-borrow",
        path="/v5/crypto-loan/borrow-history",
        base_params={},
        time_window_kind="ms",
        time_window_max_ms=30 * 24 * 3600 * 1000,
    ),
    StreamSpec(
        name="crypto-loan-repay",
        path="/v5/crypto-loan/repayment-history",
        base_params={},
        time_window_kind="ms",
        time_window_max_ms=30 * 24 * 3600 * 1000,
    ),
    StreamSpec(
        name="earn-flexible-order",
        path="/v5/earn/order",
        base_params={"category": "FlexibleSaving"},
        time_window_kind="ms",
        time_window_max_ms=30 * 24 * 3600 * 1000,
        list_field="list",
    ),
    StreamSpec(
        name="earn-onchain-order",
        path="/v5/earn/order",
        base_params={"category": "OnChain"},
        time_window_kind="ms",
        time_window_max_ms=30 * 24 * 3600 * 1000,
        list_field="list",
    ),
]


def fetch_balances(api_key: str, api_secret: str) -> Dict[str, Any]:
    """Snapshot of current balances across UTA, FUND, EARN."""
    out: Dict[str, Any] = {"snapshotAt": datetime.now(timezone.utc).isoformat()}

    # UTA wallet
    try:
        uta = signed_get(
            "/v5/account/wallet-balance",
            {"accountType": "UNIFIED"},
            api_key,
            api_secret,
        )
        out["uta"] = uta.get("result")
    except Exception as e:
        out["uta_error"] = str(e)

    # FUND
    try:
        fund = signed_get(
            "/v5/asset/transfer/query-account-coins-balance",
            {"accountType": "FUND"},
            api_key,
            api_secret,
        )
        out["fund"] = fund.get("result")
    except Exception as e:
        out["fund_error"] = str(e)

    # EARN (FlexibleSaving)
    try:
        earn = signed_get(
            "/v5/earn/position",
            {"category": "FlexibleSaving"},
            api_key,
            api_secret,
        )
        out["earn_flexible"] = earn.get("result")
    except Exception as e:
        out["earn_flexible_error"] = str(e)

    # OnChain Earn ("OnChain") and Liquidity Mining if exist
    for cat in ("OnChain", "LiquidityMining", "FixedTerm"):
        try:
            r = signed_get(
                "/v5/earn/position",
                {"category": cat},
                api_key,
                api_secret,
            )
            out[f"earn_{cat.lower()}"] = r.get("result")
        except Exception as e:
            out[f"earn_{cat.lower()}_error"] = str(e)

    # Sub-account list
    try:
        subs = signed_get("/v5/user/query-sub-members", {}, api_key, api_secret)
        out["sub_members"] = subs.get("result")
    except Exception as e:
        out["sub_members_error"] = str(e)

    return out


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main() -> int:
    parser = argparse.ArgumentParser(description="N19 S2 Bybit raw extractor")
    parser.add_argument("--only", nargs="*", help="Only run named streams (debug)")
    parser.add_argument("--skip-streams", action="store_true", help="Only fetch balances, skip history streams")
    parser.add_argument("--skip-balances", action="store_true", help="Skip current balances snapshot")
    args = parser.parse_args()

    env = load_env(ENV_PATH)
    api_key = env.get("BYBIT_API_KEY") or os.environ.get("BYBIT_API_KEY")
    api_secret = env.get("BYBIT_API_SECRET") or os.environ.get("BYBIT_API_SECRET")
    if not api_key or not api_secret:
        print("ERROR: BYBIT_API_KEY/BYBIT_API_SECRET not set in .env", file=sys.stderr)
        return 2

    OUT_DIR.mkdir(parents=True, exist_ok=True)

    summary: Dict[str, Any] = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "uid": "33625378",
        "windowStartUtc": datetime.fromtimestamp(DEFAULT_START_MS / 1000, tz=timezone.utc).isoformat(),
        "windowEndUtc": datetime.fromtimestamp(DEFAULT_END_MS / 1000, tz=timezone.utc).isoformat(),
        "streams": {},
        "balances": None,
    }

    if not args.skip_balances:
        print("[balances] snapshot current UTA/FUND/EARN…")
        balances = fetch_balances(api_key, api_secret)
        (OUT_DIR / "bybit-balances.json").write_text(json.dumps(balances, indent=2))
        summary["balances"] = {
            "uta_keys": list((balances.get("uta") or {}).keys()),
            "fund_keys": list((balances.get("fund") or {}).keys()),
            "earn_flexible_keys": list((balances.get("earn_flexible") or {}).keys()),
        }

    if args.skip_streams:
        SUMMARY_PATH.write_text(json.dumps(summary, indent=2))
        print(f"\nSummary written → {SUMMARY_PATH}")
        return 0

    for spec in STREAMS:
        if args.only and spec.name not in args.only:
            continue
        out_path = OUT_DIR / f"bybit-{spec.name}.jsonl"
        print(f"[{spec.name}] → {out_path}")
        count = 0
        try:
            with out_path.open("w") as f:
                for row in paginate(spec, api_key, api_secret):
                    f.write(json.dumps(row) + "\n")
                    count += 1
                    if count % 200 == 0:
                        print(f"  {spec.name}: {count} rows…")
            summary["streams"][spec.name] = {"rows": count, "path": str(out_path.relative_to(PROJECT_ROOT))}
            print(f"  {spec.name}: {count} rows DONE")
        except Exception as e:
            print(f"  [error] {spec.name}: {e}", file=sys.stderr)
            summary["streams"][spec.name] = {"error": str(e), "rows": count}

    SUMMARY_PATH.write_text(json.dumps(summary, indent=2))
    print(f"\nSummary written → {SUMMARY_PATH}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
