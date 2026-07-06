#!/usr/bin/env python3
"""
N19 / S3 — Independent on-chain raw extractor.

Pulls all ERC-20 + native + internal transfers for OWN EVM wallets
across all supported chains via Etherscan V2 unified endpoint
(https://api.etherscan.io/v2/api?chainid=X), and Jetton + native
TON transfers for OWN TON wallets via TonCenter v3.

Each row is enriched with the OWN wallet whose history it belongs
to so downstream classification can immediately decide OWN→OWN /
OWN→EXTERNAL / EXTERNAL→OWN.

Outputs:
  cycle/5/results/n19-raw/onchain-evm.jsonl
  cycle/5/results/n19-raw/onchain-ton.jsonl
  cycle/5/results/n19-onchain-raw-summary.json
"""

from __future__ import annotations

import argparse
import dataclasses
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
ACCOUNT_UNIVERSE = PROJECT_ROOT / "cycle-autorun/cycle-data/cycle/5/results/n19-account-universe.json"
OUT_DIR = PROJECT_ROOT / "cycle-autorun/cycle-data/cycle/5/results/n19-raw"
SUMMARY_PATH = PROJECT_ROOT / "cycle-autorun/cycle-data/cycle/5/results/n19-onchain-raw-summary.json"

ETHERSCAN_V2_BASE = "https://api.etherscan.io/v2/api"

# Networks where Etherscan V2 has indexing for our chains.
# Chain IDs match application.yml.
EVM_CHAINS: List[Dict[str, Any]] = [
    {"name": "ETHEREUM", "chainId": 1, "nativeSymbol": "ETH"},
    {"name": "ARBITRUM", "chainId": 42161, "nativeSymbol": "ETH"},
    {"name": "OPTIMISM", "chainId": 10, "nativeSymbol": "ETH"},
    {"name": "POLYGON", "chainId": 137, "nativeSymbol": "POL"},
    {"name": "BASE", "chainId": 8453, "nativeSymbol": "ETH"},
    {"name": "BSC", "chainId": 56, "nativeSymbol": "BNB"},
    {"name": "AVALANCHE", "chainId": 43114, "nativeSymbol": "AVAX"},
    {"name": "MANTLE", "chainId": 5000, "nativeSymbol": "MNT"},
    {"name": "LINEA", "chainId": 59144, "nativeSymbol": "ETH"},
    {"name": "UNICHAIN", "chainId": 130, "nativeSymbol": "ETH"},
    {"name": "ZKSYNC", "chainId": 324, "nativeSymbol": "ETH"},
    {"name": "KATANA", "chainId": 747474, "nativeSymbol": "ETH"},
    {"name": "PLASMA", "chainId": 9745, "nativeSymbol": "XPL"},
]

PAGE_SIZE = 1000
MAX_PAGES = 10  # Etherscan V2 caps at 10k rows; 10 pages of 1000.


# ---------------------------------------------------------------------------
# .env loader
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
# HTTP
# ---------------------------------------------------------------------------
def http_get_json(url: str, max_retries: int = 6) -> Dict[str, Any]:
    backoff = 1.0
    for attempt in range(max_retries):
        try:
            req = request.Request(url, headers={"User-Agent": "n19-onchain-extractor/1.0"})
            with request.urlopen(req, timeout=30) as resp:
                body = resp.read().decode("utf-8")
                return json.loads(body)
        except error.HTTPError as e:
            if e.code in (429, 502, 503, 504):
                time.sleep(backoff)
                backoff = min(backoff * 2, 30.0)
                continue
            raise
        except (error.URLError, TimeoutError, json.JSONDecodeError):
            time.sleep(backoff)
            backoff = min(backoff * 2, 30.0)
            if attempt == max_retries - 1:
                raise
    raise RuntimeError(f"Exhausted retries: {url}")


# ---------------------------------------------------------------------------
# Etherscan V2
# ---------------------------------------------------------------------------
def etherscan_account(
    chain_id: int,
    action: str,
    address: str,
    api_key: str,
    page: int,
) -> List[Dict[str, Any]]:
    params = {
        "chainid": chain_id,
        "module": "account",
        "action": action,
        "address": address,
        "startblock": 0,
        "endblock": 99999999,
        "page": page,
        "offset": PAGE_SIZE,
        "sort": "asc",
        "apikey": api_key,
    }
    url = ETHERSCAN_V2_BASE + "?" + parse.urlencode(params)
    body = http_get_json(url)
    if str(body.get("status")) != "1" and (body.get("message") or "").lower() != "no transactions found":
        msg = body.get("message") or body.get("result")
        if "no transactions" in (msg or "").lower():
            return []
        if "max rate limit" in (msg or "").lower() or "rate limit" in (msg or "").lower():
            time.sleep(2.0)
            return etherscan_account(chain_id, action, address, api_key, page)
        if msg and ("invalid api" in msg.lower() or "no records" in msg.lower()):
            return []
        # Some chains return empty result with message describing "OK"; trust result list.
    res = body.get("result")
    if isinstance(res, list):
        return res
    return []


def fetch_chain_for_wallet(
    chain: Dict[str, Any],
    wallet: str,
    api_key: str,
) -> Iterable[Dict[str, Any]]:
    for action in ("txlist", "tokentx", "txlistinternal"):
        page = 1
        while page <= MAX_PAGES:
            rows = etherscan_account(chain["chainId"], action, wallet, api_key, page)
            if not rows:
                break
            for r in rows:
                yield {
                    "__stream": f"evm-{action}",
                    "__network": chain["name"],
                    "__chainId": chain["chainId"],
                    "__ownWallet": wallet.lower(),
                    "__action": action,
                    "__fetchedAt": datetime.now(timezone.utc).isoformat(),
                    **r,
                }
            if len(rows) < PAGE_SIZE:
                break
            page += 1
            time.sleep(0.25)  # be polite vs rate limit (Etherscan 5 calls/sec free)


# ---------------------------------------------------------------------------
# TonCenter v3 (read-only TON indexer)
# ---------------------------------------------------------------------------
TONCENTER_BASE = "https://toncenter.com/api/v3"


def fetch_ton_jetton_transfers(wallet: str) -> Iterable[Dict[str, Any]]:
    """Pulls all jetton transfers (USDT, DOGS, etc.) for a TON wallet."""
    offset = 0
    limit = 100
    page = 0
    while True:
        url = (
            f"{TONCENTER_BASE}/jetton/transfers?owner_address={parse.quote(wallet)}"
            f"&limit={limit}&offset={offset}&direction=both"
        )
        body = http_get_json(url)
        rows = body.get("jetton_transfers") or body.get("transfers") or []
        if not rows:
            break
        for r in rows:
            yield {
                "__stream": "ton-jetton",
                "__network": "TON",
                "__ownWallet": wallet,
                "__fetchedAt": datetime.now(timezone.utc).isoformat(),
                **r,
            }
        if len(rows) < limit:
            break
        offset += limit
        page += 1
        if page > 100:
            break
        time.sleep(0.5)


def fetch_ton_native_transactions(wallet: str) -> Iterable[Dict[str, Any]]:
    """Pulls all native TON transfers for a wallet."""
    offset = 0
    limit = 100
    page = 0
    while True:
        url = (
            f"{TONCENTER_BASE}/transactions?account={parse.quote(wallet)}"
            f"&limit={limit}&offset={offset}&sort=asc"
        )
        body = http_get_json(url)
        rows = body.get("transactions") or []
        if not rows:
            break
        for r in rows:
            yield {
                "__stream": "ton-native",
                "__network": "TON",
                "__ownWallet": wallet,
                "__fetchedAt": datetime.now(timezone.utc).isoformat(),
                **r,
            }
        if len(rows) < limit:
            break
        offset += limit
        page += 1
        if page > 100:
            break
        time.sleep(0.5)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--only-evm", action="store_true")
    parser.add_argument("--only-ton", action="store_true")
    parser.add_argument("--only-chain", help="EVM network name (e.g. ETHEREUM)")
    parser.add_argument("--only-wallet", help="Restrict to specific wallet address (lowercase)")
    args = parser.parse_args()

    env = load_env(ENV_PATH)
    api_keys = {
        # All map to same Etherscan multichain key for free Etherscan-supported networks.
        "ETHEREUM": env.get("ETHEREUM_ETHERSCAN_API_KEY"),
        "ARBITRUM": env.get("ARBITRUM_ETHERSCAN_API_KEY"),
        "OPTIMISM": env.get("ETHEREUM_ETHERSCAN_API_KEY"),
        "POLYGON": env.get("POLYGON_ETHERSCAN_API_KEY"),
        "BASE": env.get("ETHEREUM_ETHERSCAN_API_KEY"),
        "BSC": env.get("ETHEREUM_ETHERSCAN_API_KEY"),
        "AVALANCHE": env.get("AVALANCHE_ETHERSCAN_API_KEY") or env.get("ETHEREUM_ETHERSCAN_API_KEY"),
        "MANTLE": env.get("MANTLE_ETHERSCAN_API_KEY"),
        "LINEA": env.get("LINEA_ETHERSCAN_API_KEY"),
        "UNICHAIN": env.get("UNICHAIN_ETHERSCAN_API_KEY"),
        "ZKSYNC": env.get("ETHEREUM_ETHERSCAN_API_KEY"),
        "KATANA": env.get("KATANA_ETHERSCAN_API_KEY"),
        "PLASMA": env.get("PLASMA_ETHERSCAN_API_KEY"),
    }

    universe = json.loads(ACCOUNT_UNIVERSE.read_text())
    evm_wallets = [w["address"].lower() for w in universe["inScope"]["evmWallets"]]
    ton_wallets = [w["address"] for w in universe["outOfScope"]["tonWallets"]]

    if args.only_wallet:
        evm_wallets = [w for w in evm_wallets if w == args.only_wallet.lower()]
        ton_wallets = [w for w in ton_wallets if w == args.only_wallet]

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    summary: Dict[str, Any] = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "evmWallets": evm_wallets,
        "tonWallets": ton_wallets,
        "perChainPerWallet": {},
        "perTonWallet": {},
    }

    # EVM
    if not args.only_ton:
        evm_path = OUT_DIR / "onchain-evm.jsonl"
        print(f"[evm] -> {evm_path}")
        total = 0
        with evm_path.open("w") as f:
            for chain in EVM_CHAINS:
                if args.only_chain and chain["name"] != args.only_chain:
                    continue
                api_key = api_keys.get(chain["name"])
                if not api_key:
                    print(f"  [skip] {chain['name']}: no API key", file=sys.stderr)
                    continue
                for wallet in evm_wallets:
                    count = 0
                    try:
                        for row in fetch_chain_for_wallet(chain, wallet, api_key):
                            f.write(json.dumps(row) + "\n")
                            count += 1
                    except Exception as e:
                        print(f"  [err] {chain['name']}/{wallet[:8]}: {e}", file=sys.stderr)
                    if count > 0:
                        print(f"  {chain['name']:10s} {wallet[:8]}…  rows={count}")
                    key = f"{chain['name']}/{wallet[:8]}"
                    summary["perChainPerWallet"][key] = count
                    total += count
        print(f"[evm] total rows: {total}")
        summary["evmTotal"] = total

    # TON
    if not args.only_evm:
        ton_path = OUT_DIR / "onchain-ton.jsonl"
        print(f"[ton] -> {ton_path}")
        total = 0
        with ton_path.open("w") as f:
            for wallet in ton_wallets:
                jcount = 0
                ncount = 0
                try:
                    for row in fetch_ton_jetton_transfers(wallet):
                        f.write(json.dumps(row) + "\n")
                        jcount += 1
                except Exception as e:
                    print(f"  [err-jetton] {wallet[:12]}: {e}", file=sys.stderr)
                try:
                    for row in fetch_ton_native_transactions(wallet):
                        f.write(json.dumps(row) + "\n")
                        ncount += 1
                except Exception as e:
                    print(f"  [err-native] {wallet[:12]}: {e}", file=sys.stderr)
                summary["perTonWallet"][wallet] = {"jetton": jcount, "native": ncount}
                total += jcount + ncount
                print(f"  {wallet[:14]}…  jetton={jcount} native={ncount}")
        print(f"[ton] total rows: {total}")
        summary["tonTotal"] = total

    SUMMARY_PATH.write_text(json.dumps(summary, indent=2))
    print(f"\nSummary written -> {SUMMARY_PATH}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
