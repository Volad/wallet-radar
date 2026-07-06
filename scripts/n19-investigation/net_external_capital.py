#!/usr/bin/env python3
"""
N19 / S6 — Net External Capital Calculator.

Reads classified flows and computes Net External Capital
in USDT-equivalent. The number must approximate the user's
fiat ledger (≈ $14,230.60 in this case).

Method:
  NetExternalCapital = SUM(USD value of inflows from EXTERNAL counterparties)
                     − SUM(USD value of outflows to EXTERNAL counterparties)
                     + SUM(USD value of inflows from PENDING_OWN counterparties)
                       [PENDING_OWN conservatively treated as EXTERNAL until
                        user confirms; sensitivity analysis at the end shows
                        result both ways.]

USD valuation:
  - Stables (USDT, USDC, USDE, USDT0, DAI) = $1.00
  - Other assets: spot price at flow timestamp via CoinGecko historical API,
    with a fallback "median for the period" price if API fails.

For Bybit-side flows, the network is irrelevant — we use the asset.

Outputs:
  cycle/5/results/n19-net-external-capital.json
"""

from __future__ import annotations

import json
import os
import sys
import time
from collections import defaultdict
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple
from urllib import parse, request, error

PROJECT_ROOT = Path(__file__).resolve().parents[2]
RESULTS_DIR = PROJECT_ROOT / "cycle-autorun/cycle-data/cycle/5/results"
FLOWS_PATH = RESULTS_DIR / "n19-classified-flows.jsonl"
OUT_PATH = RESULTS_DIR / "n19-net-external-capital.json"

STABLES = {"USDT", "USDC", "USDE", "USDT0", "USDt0", "DAI", "TUSD", "BUSD"}

# Hard-coded approximate prices for non-stables (used if no live API succeeds).
# These are weighted-average prices for 2024-2026 period. For more precise pricing
# the CoinGecko historical endpoint is used.
APPROX_PRICE_USD: Dict[str, Decimal] = {
    "ETH": Decimal("2800"),  # 2025 weighted avg
    "BTC": Decimal("80000"),
    "WBTC": Decimal("80000"),
    "TON": Decimal("3.5"),  # 2025 avg ~$3
    "DOGS": Decimal("0.00019"),  # 2025 avg
    "DOGE": Decimal("0.18"),
    "MNT": Decimal("0.80"),
    "CMETH": Decimal("2900"),  # ≈ ETH price
    "METH": Decimal("2900"),
    "ARB": Decimal("0.45"),
    "LDO": Decimal("1.10"),
    "ONDO": Decimal("0.85"),
    "LINK": Decimal("14"),
    "LTC": Decimal("80"),
    "SOL": Decimal("170"),
    "XPL": Decimal("0.20"),
    "XRP": Decimal("1.50"),
    "AVAX": Decimal("30"),
}

# CoinGecko id mapping
COINGECKO_IDS: Dict[str, str] = {
    "ETH": "ethereum",
    "BTC": "bitcoin",
    "WBTC": "wrapped-bitcoin",
    "TON": "the-open-network",
    "DOGS": "dogs-2",
    "DOGE": "dogecoin",
    "MNT": "mantle",
    "CMETH": "ether-fi-staked-eth",  # approx
    "METH": "mantle-staked-ether",
    "ARB": "arbitrum",
    "LDO": "lido-dao",
    "ONDO": "ondo-finance",
    "LINK": "chainlink",
    "LTC": "litecoin",
    "SOL": "solana",
    "XPL": "plasma",
    "XRP": "ripple",
    "AVAX": "avalanche-2",
}

_price_cache: Dict[Tuple[str, str], Decimal] = {}


def env_value(key: str) -> Optional[str]:
    """Pull from .env without external deps."""
    env_path = PROJECT_ROOT / ".env"
    if env_path.exists():
        for raw in env_path.read_text().splitlines():
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            if k.strip() == key:
                return v.strip().strip('"').strip("'")
    return os.environ.get(key)


COINGECKO_KEY = env_value("COINGECKO_API_KEY")


def coingecko_price(asset: str, ts_iso: str) -> Optional[Decimal]:
    """Fetch USD price for asset at a date via CoinGecko free API."""
    cg_id = COINGECKO_IDS.get(asset)
    if not cg_id:
        return None
    # Use day-resolution (free tier supports /coins/{id}/history?date=DD-MM-YYYY)
    try:
        dt = datetime.fromisoformat(ts_iso.replace("Z", "+00:00")) if ts_iso else None
    except (ValueError, TypeError):
        return None
    if not dt:
        return None
    date_str = dt.strftime("%d-%m-%Y")
    cache_key = (asset, date_str)
    if cache_key in _price_cache:
        return _price_cache[cache_key]
    url = f"https://api.coingecko.com/api/v3/coins/{cg_id}/history?date={date_str}&localization=false"
    headers = {"User-Agent": "n19-pricing/1.0"}
    if COINGECKO_KEY:
        headers["x-cg-demo-api-key"] = COINGECKO_KEY
    try:
        req = request.Request(url, headers=headers)
        with request.urlopen(req, timeout=15) as resp:
            body = json.loads(resp.read().decode("utf-8"))
        price = body.get("market_data", {}).get("current_price", {}).get("usd")
        if price is None:
            return None
        d = Decimal(str(price))
        _price_cache[cache_key] = d
        return d
    except (error.HTTPError, error.URLError, TimeoutError, json.JSONDecodeError, KeyError, ValueError):
        return None


def usd_value(asset: Optional[str], qty: Decimal, ts: Optional[str]) -> Decimal:
    if not asset or qty == 0:
        return Decimal(0)
    asset_u = asset.upper()
    if asset_u in STABLES or asset_u in ("USD",):
        return qty
    # Try live API first
    if ts:
        live = coingecko_price(asset_u, ts)
        if live is not None:
            return qty * live
    return qty * APPROX_PRICE_USD.get(asset_u, Decimal(0))


def main() -> int:
    if not FLOWS_PATH.exists():
        print(f"ERROR: {FLOWS_PATH} not found. Run classify_flows.py first.", file=sys.stderr)
        return 2

    # Buckets
    in_external_per_asset = defaultdict(lambda: defaultdict(Decimal))
    in_external_per_cp = defaultdict(lambda: defaultdict(Decimal))
    out_external_per_asset = defaultdict(lambda: defaultdict(Decimal))
    out_external_per_cp = defaultdict(lambda: defaultdict(Decimal))

    in_pending_per_asset = defaultdict(Decimal)
    out_pending_per_asset = defaultdict(Decimal)

    detail_external_flows: List[Dict[str, Any]] = []

    total_in_usd = Decimal(0)
    total_out_usd = Decimal(0)
    total_pending_in_usd = Decimal(0)
    total_pending_out_usd = Decimal(0)

    with FLOWS_PATH.open() as f:
        for line in f:
            row = json.loads(line)
            ctype = row["counterpartyType"]
            asset = (row.get("asset") or "").upper() or None
            qty = Decimal(row["qty"]) if row.get("qty") else Decimal(0)
            ts = row.get("ts")
            cp = row.get("counterpartySide") or "(null)"
            cp_label = row.get("counterpartyLabel") or ""
            source = row["source"]
            sub = row["subSource"]

            if ctype not in ("EXTERNAL", "PENDING_OWN"):
                continue

            # Skip irrelevant flows: protocol-internal, intra-Bybit fees, etc.
            # For external capital we only care about meaningful transfers.
            # Filter out near-zero values.
            if abs(qty) < Decimal("0.0001"):
                continue

            value_usd = usd_value(asset, qty, ts)  # signed by qty direction
            if ctype == "EXTERNAL":
                if qty > 0:
                    in_external_per_asset[asset][cp] += qty
                    in_external_per_cp[cp][asset] += qty
                    total_in_usd += value_usd
                else:
                    out_external_per_asset[asset][cp] += qty
                    out_external_per_cp[cp][asset] += qty
                    total_out_usd += abs(value_usd)
            elif ctype == "PENDING_OWN":
                if qty > 0:
                    in_pending_per_asset[(asset, cp)] += qty
                    total_pending_in_usd += value_usd
                else:
                    out_pending_per_asset[(asset, cp)] += qty
                    total_pending_out_usd += abs(value_usd)

            detail_external_flows.append({
                "ts": ts,
                "source": source,
                "subSource": sub,
                "asset": asset,
                "qty": str(qty),
                "usd": str(value_usd.quantize(Decimal("0.01"))),
                "counterparty": cp,
                "counterpartyType": ctype,
                "counterpartyLabel": cp_label,
                "ownSide": row.get("ownSide"),
            })

    # Net External Capital — two scenarios:
    # A: Treat PENDING_OWN as EXTERNAL (conservative — these are unconfirmed)
    # B: Treat PENDING_OWN as OWN (optimistic — user owns these)
    net_conservative = total_in_usd + total_pending_in_usd - total_out_usd - total_pending_out_usd
    net_optimistic = total_in_usd - total_out_usd

    # Per-asset summary (only EXTERNAL strict)
    asset_summary = {}
    def _sum(values):
        total = Decimal(0)
        for v in values:
            total += v
        return total
    for asset, cps in in_external_per_asset.items():
        if asset is None:
            continue
        in_qty = _sum(cps.values())
        out_qty = _sum(out_external_per_asset.get(asset, {}).values())
        asset_summary[asset] = {
            "inQty": str(in_qty.quantize(Decimal("0.000001"))),
            "outQty": str(out_qty.quantize(Decimal("0.000001"))),
            "netQty": str((in_qty + out_qty).quantize(Decimal("0.000001"))),
            "approxPriceUsd": str(APPROX_PRICE_USD.get(asset, Decimal(0)) if asset not in STABLES else Decimal(1)),
            "netUsdApprox": str((Decimal(1) if asset in STABLES else APPROX_PRICE_USD.get(asset, Decimal(0))) * (in_qty + out_qty)),
        }
    for asset, cps in out_external_per_asset.items():
        if asset not in asset_summary and asset is not None:
            in_qty = _sum(in_external_per_asset.get(asset, {}).values())
            out_qty = _sum(cps.values())
            asset_summary[asset] = {
                "inQty": str(in_qty.quantize(Decimal("0.000001"))),
                "outQty": str(out_qty.quantize(Decimal("0.000001"))),
                "netQty": str((in_qty + out_qty).quantize(Decimal("0.000001"))),
                "approxPriceUsd": str(APPROX_PRICE_USD.get(asset, Decimal(0)) if asset not in STABLES else Decimal(1)),
                "netUsdApprox": str((Decimal(1) if asset in STABLES else APPROX_PRICE_USD.get(asset, Decimal(0))) * (in_qty + out_qty)),
            }

    # Per-counterparty summary
    cp_summary = {}
    all_cps = set(in_external_per_cp.keys()) | set(out_external_per_cp.keys())
    for cp in all_cps:
        ins = {a: str(v.quantize(Decimal("0.000001"))) for a, v in in_external_per_cp.get(cp, {}).items()}
        outs = {a: str(v.quantize(Decimal("0.000001"))) for a, v in out_external_per_cp.get(cp, {}).items()}
        cp_summary[cp] = {
            "ins": ins,
            "outs": outs,
        }

    out = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "method": "Sum USD value of EXTERNAL inflows minus EXTERNAL outflows. Stables=$1; non-stables priced via CoinGecko historical (fallback approximate).",
        "principle": "Only counterparties classified EXTERNAL (confirmed fiat onramp / exchange hot wallet) count. OWN_WALLET, OWN_BYBIT, PROTOCOL, UNCLASSIFIED do NOT contribute.",
        "result": {
            "netExternalCapitalUsdConservative_PendingAsExternal": str(net_conservative.quantize(Decimal("0.01"))),
            "netExternalCapitalUsdOptimistic_PendingAsOwn": str(net_optimistic.quantize(Decimal("0.01"))),
            "totalInflowUsd": str(total_in_usd.quantize(Decimal("0.01"))),
            "totalOutflowUsd": str(total_out_usd.quantize(Decimal("0.01"))),
            "totalPendingInflowUsd": str(total_pending_in_usd.quantize(Decimal("0.01"))),
            "totalPendingOutflowUsd": str(total_pending_out_usd.quantize(Decimal("0.01"))),
            "userClaimedNetExternal": "14230.60",
            "discrepancyVsUserConservative": str((net_conservative - Decimal("14230.60")).quantize(Decimal("0.01"))),
            "discrepancyVsUserOptimistic": str((net_optimistic - Decimal("14230.60")).quantize(Decimal("0.01"))),
        },
        "perAssetSummary": asset_summary,
        "perCounterpartySummary": cp_summary,
        "detailedExternalFlowsCount": len(detail_external_flows),
        "detailedExternalFlowsTopByValue": sorted(detail_external_flows, key=lambda x: -abs(Decimal(x["usd"])))[:80],
    }
    OUT_PATH.write_text(json.dumps(out, indent=2, default=str))
    print(f"\nWritten: {OUT_PATH}")
    print(f"Net External Capital (conservative, PENDING_OWN as EXTERNAL): ${net_conservative:,.2f}")
    print(f"Net External Capital (optimistic, PENDING_OWN as OWN):       ${net_optimistic:,.2f}")
    print(f"User claimed: $14,230.60")
    print(f"Discrepancy (cons.) = ${net_conservative - Decimal('14230.60'):,.2f}")
    print(f"Discrepancy (opt.)  = ${net_optimistic - Decimal('14230.60'):,.2f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
