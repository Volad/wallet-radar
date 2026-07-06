#!/usr/bin/env python3
"""
N19 / S5 — Counterparty classifier.

Reads all raw extracts (Bybit + EVM + TON) and emits a unified
classified-flows JSONL where each row contains:

  {
    "ts": ISO-8601,
    "source": "BYBIT" | "EVM" | "TON",
    "subSource": e.g. "bybit-deposit-onchain", "evm-tokentx", "ton-jetton",
    "network": "ETHEREUM" | "BYBIT:UID" | "TON" | ...,
    "asset": "USDT",
    "qty": Decimal-string (positive: inflow to our universe; negative: outflow),
    "qtyDirection": "IN" | "OUT" | "INTERNAL",
    "ownSide": OWN entity touched (wallet/ledger),
    "counterpartySide": the other side's address / ledger,
    "counterpartyType": "OWN_WALLET" | "OWN_BYBIT" | "EXTERNAL" | "PROTOCOL" | "UNKNOWN",
    "counterpartyLabel": optional human label (Dzengi, Whitebird, …),
    "txHash": on-chain hash or Bybit transferId / orderId,
    "rawRef": pointer to source row,
  }

This is the **single ground-truth ledger** for downstream simulation
(S6 NetExternalCapital + S7 AVCO replay). No time-window logic; every
classification driven by counterparty addresses.

Output:
  cycle/5/results/n19-classified-flows.jsonl
  cycle/5/results/n19-classification-summary.json
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple

PROJECT_ROOT = Path(__file__).resolve().parents[2]
RESULTS_DIR = PROJECT_ROOT / "cycle-autorun/cycle-data/cycle/5/results"
RAW_DIR = RESULTS_DIR / "n19-raw"
ACCOUNT_UNIVERSE = RESULTS_DIR / "n19-account-universe.json"
OUT_FLOWS = RESULTS_DIR / "n19-classified-flows.jsonl"
OUT_SUMMARY = RESULTS_DIR / "n19-classification-summary.json"
OUT_UNKNOWN = RESULTS_DIR / "n19-unknown-counterparties.json"

STABLE_FAMILY = {"USDT", "USDC", "USDE", "USDT0", "USD0", "DAI", "TUSD", "BUSD", "USDt", "USDb"}


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
def D(x: Any) -> Decimal:
    if x is None or x == "":
        return Decimal(0)
    try:
        return Decimal(str(x))
    except (InvalidOperation, ValueError):
        return Decimal(0)


def lc(s: Any) -> Optional[str]:
    if s is None:
        return None
    return str(s).strip().lower() or None


def parse_ts_iso(*candidates: Any) -> Optional[str]:
    """First non-empty timestamp candidate → ISO-8601 UTC."""
    for c in candidates:
        if c is None or c == "":
            continue
        try:
            v = int(str(c))
            # heuristic: detect ms vs seconds
            if v > 10**12:
                return datetime.fromtimestamp(v / 1000, tz=timezone.utc).isoformat()
            if v > 10**10:
                return datetime.fromtimestamp(v / 1000, tz=timezone.utc).isoformat()
            if v > 10**9:
                return datetime.fromtimestamp(v, tz=timezone.utc).isoformat()
        except (TypeError, ValueError):
            pass
        # already ISO?
        s = str(c)
        if "T" in s and "-" in s:
            return s
    return None


# ---------------------------------------------------------------------------
# Universe loading
# ---------------------------------------------------------------------------
def load_universe() -> Dict[str, Any]:
    raw = json.loads(ACCOUNT_UNIVERSE.read_text())
    evm_wallets = {w["address"].lower() for w in raw["inScope"]["evmWallets"]}
    bybit_master_uid = raw["inScope"]["bybit"]["masterUid"]
    bybit_sub_uids = set(raw["inScope"]["bybit"].get("subAccountUids", []))
    bybit_ledgers = set(raw["inScope"]["bybit"]["ledgers"])
    ton_wallets = set()
    for w in raw["outOfScope"]["tonWallets"]:
        ton_wallets.add(w["address"])
        ton_wallets.add(w["address"].lower())
        if w.get("rawAddress"):
            ton_wallets.add(w["rawAddress"])
            ton_wallets.add(w["rawAddress"].lower())
    ton_wallets.discard("")
    solana_wallets = {a for a in (raw["outOfScope"].get("solana", {}).get("addresses") or []) if a and "TBD" not in a}

    known_external: Dict[str, str] = {}
    ke_evm = raw.get("knownExternalCounterparties", {}).get("evm", {})
    for k, v in ke_evm.items():
        if isinstance(v, dict) and v.get("address"):
            known_external[v["address"].lower()] = v.get("label", k)
        elif k.startswith("0x") and isinstance(v, str):
            known_external[k.lower()] = v
    # Embedded sub-dict with unknown onramps:
    unknown_onramps = ke_evm.get("_unknownEvmFiatOnramps", {}) or {}
    for addr, lbl in unknown_onramps.items():
        if addr.startswith("0x"):
            known_external[addr.lower()] = "fiat-onramp:" + str(lbl)[:60]
    # TON fiat onramps
    ton_ke = raw.get("knownExternalCounterparties", {}).get("ton", {}).get("_fiatOnrampSenders", {}) or {}
    for addr, lbl in ton_ke.items():
        if addr.startswith("UQ") or addr.startswith("EQ"):
            known_external[addr] = "ton-fiat-onramp:" + str(lbl)[:60]
            known_external[addr.lower()] = "ton-fiat-onramp:" + str(lbl)[:60]
    # Bybit hot wallets — these are NOT external capital counterparties from user's
    # net-flow perspective. On-chain transfer "0x1a87 -> bybit_hot 0x2ea8cb..." is
    # the same economic event as Bybit-side deposit endpoint record; counting both
    # would double-count. Treat Bybit hot wallets as OWN_BYBIT (a bridge to user's
    # own Bybit account).
    bybit_hot_wallets = set()
    bybit_hot = raw.get("knownExternalCounterparties", {}).get("bybit_hot_wallets", {}) or {}
    for k, addr in bybit_hot.items():
        if isinstance(addr, str) and (addr.startswith("0x") or addr.startswith("UQ") or addr.startswith("EQ") or len(addr) > 30):
            bybit_hot_wallets.add(addr.lower())
            bybit_hot_wallets.add(addr)

    # Pending OWN confirmation (TON wallets discovered but not yet confirmed)
    pending_own: Dict[str, str] = {}
    pending = raw.get("_pendingOwnConfirmation", {}).get("tonAddresses", []) or []
    for entry in pending:
        if isinstance(entry, dict) and entry.get("address"):
            pending_own[entry["address"]] = entry.get("evidence", "")[:80]
            pending_own[entry["address"].lower()] = entry.get("evidence", "")[:80]

    # Protocol contracts
    protocols: Dict[str, str] = {}
    pc = raw.get("knownProtocolContracts", {}) or {}
    for k, v in pc.items():
        if k.startswith("_"):
            continue
        if isinstance(v, str) and (v.startswith("0x") or v.startswith("UQ")):
            protocols[v.lower()] = k
    # _protocolHeuristicsRequired list — treat as PROTOCOL too (with caveat)
    for addr in pc.get("_protocolHeuristicsRequired", []) or []:
        protocols[addr.lower()] = "PROTOCOL_HEURISTIC"

    return {
        "evm_wallets": evm_wallets,
        "ton_wallets": ton_wallets,
        "solana_wallets": solana_wallets,
        "bybit_master_uid": bybit_master_uid,
        "bybit_sub_uids": bybit_sub_uids,
        "bybit_ledgers": bybit_ledgers,
        "bybit_hot_wallets": bybit_hot_wallets,
        "known_external": known_external,
        "pending_own": pending_own,
        "protocols": protocols,
    }


def classify_address(addr: Optional[str], universe: Dict[str, Any], network_hint: Optional[str] = None) -> Tuple[str, Optional[str]]:
    """
    Returns (counterpartyType, label). Order:
      OWN_WALLET    — confirmed own EVM/Solana/TON wallet
      OWN_BYBIT     — own Bybit master/sub UID
      PENDING_OWN   — discovered but awaiting user confirmation (treat as EXTERNAL conservatively)
      PROTOCOL      — known smart contract (DEX/AMM/Bridge/Lending/NFT manager) — neither OWN nor EXTERNAL
      EXTERNAL      — known onramp/exchange hot wallet (positive ID of external counterparty)
      UNKNOWN       — counterparty address missing/blank (data gap)
      UNCLASSIFIED  — non-blank but not in any list (needs manual investigation)
    """
    if not addr:
        return ("UNKNOWN", None)
    a = addr.strip()
    al = a.lower()
    if al.startswith("bybit:") or a.startswith("BYBIT:"):
        if al == f"bybit:{universe['bybit_master_uid']}".lower():
            return ("OWN_BYBIT", "master")
        for uid in universe["bybit_sub_uids"]:
            if al == f"bybit:{uid}".lower():
                return ("OWN_BYBIT", f"sub:{uid}")
        return ("EXTERNAL", None)
    if al in universe["evm_wallets"]:
        return ("OWN_WALLET", "evm")
    if a in universe["ton_wallets"] or al in universe["ton_wallets"]:
        return ("OWN_WALLET", "ton")
    if a in universe["solana_wallets"]:
        return ("OWN_WALLET", "solana")
    if al in universe.get("bybit_hot_wallets", set()) or a in universe.get("bybit_hot_wallets", set()):
        return ("OWN_BYBIT", "bybit-hot-wallet")
    if al in universe["protocols"]:
        return ("PROTOCOL", universe["protocols"][al])
    if al in universe["known_external"]:
        return ("EXTERNAL", universe["known_external"][al])
    if a in universe["pending_own"] or al in universe["pending_own"]:
        return ("PENDING_OWN", universe["pending_own"].get(a, universe["pending_own"].get(al, "")))
    return ("UNCLASSIFIED", None)


# ---------------------------------------------------------------------------
# Bybit streams classifier
# ---------------------------------------------------------------------------
def classify_bybit(universe: Dict[str, Any]) -> Iterable[Dict[str, Any]]:
    master_uid = universe["bybit_master_uid"]

    # --- deposit-onchain: fromAddress is counterparty
    path = RAW_DIR / "bybit-deposit-onchain.jsonl"
    if path.exists():
        for raw in iter_jsonl(path):
            cp = raw.get("fromAddress")
            t, label = classify_address(cp, universe)
            ts = parse_ts_iso(raw.get("successAt"))
            yield {
                "ts": ts,
                "source": "BYBIT",
                "subSource": "bybit-deposit-onchain",
                "network": raw.get("chain"),
                "asset": raw.get("coin"),
                "qty": str(D(raw.get("amount"))),
                "qtyDirection": "IN",
                "ownSide": f"BYBIT:{master_uid}",
                "counterpartySide": cp,
                "counterpartyType": t,
                "counterpartyLabel": label,
                "txHash": raw.get("txID"),
                "extras": {
                    "toAddress": raw.get("toAddress"),  # Bybit hot wallet
                    "depositId": raw.get("id"),
                    "depositType": raw.get("depositType"),
                    "status": raw.get("status"),
                },
            }

    # --- deposit-internal: deposit from another Bybit user, NOT counterparty address-bound (UID-bound)
    path = RAW_DIR / "bybit-deposit-internal.jsonl"
    if path.exists():
        for raw in iter_jsonl(path):
            from_uid = raw.get("fromMemberId") or raw.get("fromMember")
            t = ("OWN_BYBIT", "sub-internal-deposit") if from_uid and str(from_uid) in {master_uid, *universe["bybit_sub_uids"]} else ("EXTERNAL", None)
            ts = parse_ts_iso(raw.get("createdTime"), raw.get("updatedTime"))
            yield {
                "ts": ts,
                "source": "BYBIT",
                "subSource": "bybit-deposit-internal",
                "network": "BYBIT:INTERNAL",
                "asset": raw.get("coin"),
                "qty": str(D(raw.get("amount"))),
                "qtyDirection": "IN",
                "ownSide": f"BYBIT:{master_uid}",
                "counterpartySide": f"BYBIT:{from_uid}" if from_uid else None,
                "counterpartyType": t[0],
                "counterpartyLabel": t[1],
                "txHash": raw.get("txID") or raw.get("id"),
            }

    # --- withdrawal: toAddress is counterparty
    path = RAW_DIR / "bybit-withdrawal.jsonl"
    if path.exists():
        for raw in iter_jsonl(path):
            cp = raw.get("toAddress")
            t, label = classify_address(cp, universe)
            ts = parse_ts_iso(raw.get("updateTime"), raw.get("createTime"))
            yield {
                "ts": ts,
                "source": "BYBIT",
                "subSource": "bybit-withdrawal",
                "network": raw.get("chain"),
                "asset": raw.get("coin"),
                "qty": str(-D(raw.get("amount"))),
                "qtyDirection": "OUT",
                "ownSide": f"BYBIT:{master_uid}",
                "counterpartySide": cp,
                "counterpartyType": t,
                "counterpartyLabel": label,
                "txHash": raw.get("txID"),
                "extras": {
                    "withdrawId": raw.get("withdrawId"),
                    "withdrawFee": raw.get("withdrawFee"),
                    "status": raw.get("status"),
                },
            }

    # --- internal-transfer (FUND<->UTA within same UID)
    path = RAW_DIR / "bybit-internal-transfer.jsonl"
    if path.exists():
        for raw in iter_jsonl(path):
            from_acct = raw.get("fromAccountType")
            to_acct = raw.get("toAccountType")
            ts = parse_ts_iso(raw.get("timestamp"))
            yield {
                "ts": ts,
                "source": "BYBIT",
                "subSource": "bybit-internal-transfer",
                "network": "BYBIT:INTERNAL",
                "asset": raw.get("coin"),
                "qty": str(D(raw.get("amount"))),
                "qtyDirection": "INTERNAL",
                "ownSide": f"BYBIT:{master_uid}:{from_acct}->{to_acct}",
                "counterpartySide": f"BYBIT:{master_uid}",
                "counterpartyType": "OWN_BYBIT",
                "counterpartyLabel": "internal",
                "txHash": raw.get("transferId"),
                "extras": {"fromAccountType": from_acct, "toAccountType": to_acct, "status": raw.get("status")},
            }

    # --- universal-transfer (cross-UID, can be master <-> sub)
    path = RAW_DIR / "bybit-universal-transfer.jsonl"
    if path.exists():
        for raw in iter_jsonl(path):
            from_uid = str(raw.get("fromMemberId") or "")
            to_uid = str(raw.get("toMemberId") or "")
            from_own = from_uid in {master_uid, *universe["bybit_sub_uids"]}
            to_own = to_uid in {master_uid, *universe["bybit_sub_uids"]}
            if from_own and to_own:
                ctype = "OWN_BYBIT"
                label = f"sub:{from_uid}->{to_uid}"
            elif from_own:
                ctype, label = classify_address(None, universe)  # to_uid is external user
                ctype = "EXTERNAL"
            else:
                ctype = "EXTERNAL"
                label = None
            ts = parse_ts_iso(raw.get("timestamp"))
            yield {
                "ts": ts,
                "source": "BYBIT",
                "subSource": "bybit-universal-transfer",
                "network": "BYBIT:INTERNAL",
                "asset": raw.get("coin"),
                "qty": str(D(raw.get("amount"))),
                "qtyDirection": "INTERNAL" if ctype == "OWN_BYBIT" else "OUT",
                "ownSide": f"BYBIT:{from_uid}:{raw.get('fromAccountType')}",
                "counterpartySide": f"BYBIT:{to_uid}:{raw.get('toAccountType')}",
                "counterpartyType": ctype,
                "counterpartyLabel": label,
                "txHash": raw.get("transferId"),
            }

    # --- tx-log-unified: spot/perp/funding/fee/etc. inside UTA
    path = RAW_DIR / "bybit-tx-log-unified.jsonl"
    if path.exists():
        for raw in iter_jsonl(path):
            ts = parse_ts_iso(raw.get("transactionTime"))
            change = D(raw.get("change"))
            yield {
                "ts": ts,
                "source": "BYBIT",
                "subSource": "bybit-tx-log-unified",
                "network": "BYBIT:UTA",
                "asset": raw.get("currency"),
                "qty": str(change),
                "qtyDirection": "IN" if change > 0 else "OUT" if change < 0 else "ZERO",
                "ownSide": f"BYBIT:{master_uid}:UTA",
                "counterpartySide": None,
                "counterpartyType": "OWN_BYBIT",
                "counterpartyLabel": raw.get("type"),
                "txHash": raw.get("id"),
                "extras": {
                    "type": raw.get("type"),
                    "category": raw.get("category"),
                    "side": raw.get("side"),
                    "tradeId": raw.get("tradeId"),
                    "symbol": raw.get("symbol"),
                    "feeRate": raw.get("feeRate"),
                    "cashFlow": raw.get("cashFlow"),
                    "funding": raw.get("funding"),
                    "fee": raw.get("fee"),
                    "tradePrice": raw.get("tradePrice"),
                    "qty": raw.get("qty"),
                    "size": raw.get("size"),
                    "transSubType": raw.get("transSubType"),
                },
            }

    # --- funding-history: FUND ledger entries (deposits/withdraws/transfers/loans/earn)
    # Special handling: Fiat P2P purchases are EXTERNAL inflows (user paid local
    # currency, received crypto). They have no counterparty address but are
    # economically equivalent to depositing from a fiat onramp.
    path = RAW_DIR / "bybit-funding-history.jsonl"
    if path.exists():
        for raw in iter_jsonl(path):
            ts = parse_ts_iso(raw.get("createTime"))
            direction = (raw.get("ioDirection") or "").upper()  # "I" or "O"
            amount = D(raw.get("txnAmt"))
            signed = amount if direction == "I" else -amount
            busi = raw.get("showBusiTypeEn") or raw.get("showBusiType") or ""
            desc = raw.get("descriptionEn") or raw.get("description") or ""

            ctype = "OWN_BYBIT"
            cp_side = None
            cp_label = f"{busi}:{desc.strip()}"
            if busi == "Fiat":
                ctype = "EXTERNAL"
                cp_side = f"FIAT:P2P:{desc.strip()}"
                cp_label = "fiat-p2p-purchase"
            elif busi == "Airdrop":
                # Airdrops are EXTERNAL inflows of basis-zero asset (free money).
                # For NetExternalCapital purposes they're typically excluded
                # (no fiat paid). For AVCO they're tracked as zero-basis.
                ctype = "AIRDROP"
                cp_side = f"AIRDROP:{desc.strip()}"
                cp_label = "airdrop"
            elif busi == "Rewards":
                ctype = "REWARD"
                cp_side = f"REWARD:{desc.strip()}"
                cp_label = "reward"

            yield {
                "ts": ts,
                "source": "BYBIT",
                "subSource": "bybit-funding-history",
                "network": "BYBIT:FUND",
                "asset": raw.get("currency"),
                "qty": str(signed),
                "qtyDirection": "IN" if direction == "I" else "OUT" if direction == "O" else "ZERO",
                "ownSide": f"BYBIT:{master_uid}:FUND",
                "counterpartySide": cp_side,
                "counterpartyType": ctype,
                "counterpartyLabel": cp_label,
                "txHash": None,
                "extras": {
                    "showBusiTypeEn": busi,
                    "descriptionEn": desc.strip(),
                    "afterAmt": raw.get("afterAmt"),
                },
            }

    # --- execution-spot / linear
    for stream_name in ("execution-spot", "execution-linear"):
        path = RAW_DIR / f"bybit-{stream_name}.jsonl"
        if not path.exists():
            continue
        for raw in iter_jsonl(path):
            ts = parse_ts_iso(raw.get("execTime"))
            qty = D(raw.get("execQty"))
            price = D(raw.get("execPrice"))
            side = (raw.get("side") or "").upper()
            symbol = raw.get("symbol") or ""
            # spot symbol like USDTBTC etc.
            yield {
                "ts": ts,
                "source": "BYBIT",
                "subSource": f"bybit-{stream_name}",
                "network": f"BYBIT:UTA:{raw.get('category')}",
                "asset": None,  # symbol pair, leave qty as size, derive in S7
                "qty": str(qty),
                "qtyDirection": side,
                "ownSide": f"BYBIT:{master_uid}:UTA",
                "counterpartySide": "BYBIT:MARKET",
                "counterpartyType": "OWN_BYBIT",
                "counterpartyLabel": "spot-trade",
                "txHash": raw.get("execId"),
                "extras": {
                    "symbol": symbol,
                    "price": str(price),
                    "side": side,
                    "execValue": raw.get("execValue"),
                    "execFee": raw.get("execFee"),
                    "feeRate": raw.get("feeRate"),
                    "orderId": raw.get("orderId"),
                    "isMaker": raw.get("isMaker"),
                },
            }

    # --- crypto-loan-borrow / repay
    for stream_name, direction in (("crypto-loan-borrow", "IN"), ("crypto-loan-repay", "OUT")):
        path = RAW_DIR / f"bybit-{stream_name}.jsonl"
        if not path.exists():
            continue
        for raw in iter_jsonl(path):
            ts = parse_ts_iso(raw.get("createdTime"), raw.get("updatedTime"))
            yield {
                "ts": ts,
                "source": "BYBIT",
                "subSource": f"bybit-{stream_name}",
                "network": "BYBIT:LOAN",
                "asset": raw.get("loanCoin") or raw.get("coin"),
                "qty": str(D(raw.get("loanAmount") or raw.get("repayAmount"))),
                "qtyDirection": direction,
                "ownSide": f"BYBIT:{master_uid}:LOAN",
                "counterpartySide": "BYBIT:LOAN",
                "counterpartyType": "OWN_BYBIT",
                "counterpartyLabel": stream_name,
                "txHash": raw.get("orderId"),
                "extras": {
                    "orderId": raw.get("orderId"),
                    "collateralCoin": raw.get("collateralCoin"),
                    "collateralAmount": raw.get("collateralAmount"),
                    "status": raw.get("status"),
                    "interest": raw.get("interestAmount"),
                },
            }


# ---------------------------------------------------------------------------
# On-chain EVM classifier
# ---------------------------------------------------------------------------
def classify_evm(universe: Dict[str, Any]) -> Iterable[Dict[str, Any]]:
    path = RAW_DIR / "onchain-evm.jsonl"
    if not path.exists():
        return
    for raw in iter_jsonl(path):
        own = raw.get("__ownWallet")
        action = raw.get("__action")
        net = raw.get("__network")
        ts_raw = raw.get("timeStamp")
        ts = parse_ts_iso(ts_raw)
        from_addr = lc(raw.get("from"))
        to_addr = lc(raw.get("to"))

        # qty extraction
        if action == "tokentx":
            decimals = int(raw.get("tokenDecimal") or 0)
            value = D(raw.get("value"))
            qty = value / (Decimal(10) ** decimals) if decimals else value
            asset = raw.get("tokenSymbol")
        elif action == "txlist":
            qty = D(raw.get("value")) / (Decimal(10) ** 18)
            asset = "_NATIVE_"  # resolved later per network
            # Filter out 0-value contract calls (those are just smart contract interactions, not transfers)
            if qty == 0:
                continue
        elif action == "txlistinternal":
            qty = D(raw.get("value")) / (Decimal(10) ** 18)
            asset = "_NATIVE_"
            if qty == 0:
                continue
        else:
            continue

        # Determine direction relative to OWN wallet
        if from_addr == own and to_addr == own:
            # self-transfer (rare); count as INTERNAL
            qty_signed = Decimal(0)
            direction = "INTERNAL"
            cp = own
        elif from_addr == own:
            qty_signed = -qty
            direction = "OUT"
            cp = to_addr
        elif to_addr == own:
            qty_signed = qty
            direction = "IN"
            cp = from_addr
        else:
            continue  # neither side OWN, shouldn't happen

        ctype, label = classify_address(cp, universe)
        yield {
            "ts": ts,
            "source": "EVM",
            "subSource": f"evm-{action}",
            "network": net,
            "asset": asset if asset != "_NATIVE_" else None,  # mark, fixed below
            "qty": str(qty_signed),
            "qtyDirection": direction,
            "ownSide": own,
            "counterpartySide": cp,
            "counterpartyType": ctype,
            "counterpartyLabel": label,
            "txHash": raw.get("hash"),
            "extras": {
                "blockNumber": raw.get("blockNumber"),
                "tokenContract": raw.get("contractAddress"),
                "tokenName": raw.get("tokenName"),
                "tokenDecimals": raw.get("tokenDecimal"),
                "rawValue": raw.get("value"),
                "isError": raw.get("isError"),
                "txreceipt_status": raw.get("txreceipt_status"),
            },
        }


# ---------------------------------------------------------------------------
# TON classifier
# ---------------------------------------------------------------------------
# Jetton master → symbol map (TON). Decimals: USDT=6, DOGS=9, NOT=9, BOLT=9 etc.
TON_JETTON_MASTERS: Dict[str, Tuple[str, int]] = {
    "0:b113a994b5024a16719f69139328eb759596c38a25f59028b146fecdc3621dfe": ("USDT", 6),
    # DOGS master (common):
    "0:afc49cb8786f21c87045b19ede78fc6b46c51048513f8e9a6d44060199c1bf0c": ("DOGS", 9),
    "0:74f6fc1d50b2e3f5b29bd8b4f9bff0ee45770c3c10b6e9ae90ed3d99a7f88c4d": ("DOGS", 9),
    # Common stables / popular jettons (will expand as needed)
    "0:ad0f6fbbab11e1428361c6b8b6252cc7f9d9665aa298083b65119a918b7fb9ea": ("UNKNOWN_JETTON_AD0F", 9),
    "0:5bb0b607e6c0fbe2060ee033a88f442c1da34bf3534b0f5dc07619260f4529fb": ("UNKNOWN_JETTON_5BB0", 9),
}


def _ton_lower(s: Optional[str]) -> Optional[str]:
    if not s:
        return None
    s = s.strip()
    if ":" in s:
        prefix, rest = s.split(":", 1)
        return f"{prefix}:{rest.lower()}"
    return s.lower()


def classify_ton(universe: Dict[str, Any]) -> Iterable[Dict[str, Any]]:
    path = RAW_DIR / "onchain-ton.jsonl"
    if not path.exists():
        return
    # Build OWN TON raw-form set
    own_raw = set()
    for w in universe.get("ton_wallets", set()):
        own_raw.add(w)
        own_raw.add(_ton_lower(w))
    own_raw.discard(None)

    for raw in iter_jsonl(path):
        own = raw.get("__ownWallet")
        stream = raw.get("__stream")
        if stream != "ton-jetton":
            continue
        src_raw = _ton_lower(raw.get("source"))
        dst_raw = _ton_lower(raw.get("destination"))
        # Resolve jetton via master
        jmaster = _ton_lower(raw.get("jetton_master"))
        jinfo = TON_JETTON_MASTERS.get(jmaster, ("UNKNOWN_JETTON", 9))
        asset = jinfo[0]
        decimals = jinfo[1]
        amount = raw.get("amount")
        qty = D(amount) / (Decimal(10) ** decimals) if amount else Decimal(0)
        ts = parse_ts_iso(raw.get("transaction_now"))

        # Direction: if src is OWN raw → OUT; else IN
        if src_raw and src_raw in own_raw:
            qty_signed = -qty
            cp_raw = raw.get("destination")
            direction = "OUT"
        elif dst_raw and dst_raw in own_raw:
            qty_signed = qty
            cp_raw = raw.get("source")
            direction = "IN"
        else:
            # Neither side OWN — shouldn't happen since we extracted by owner_address
            continue

        ctype, label = classify_address(cp_raw, universe)
        yield {
            "ts": ts,
            "source": "TON",
            "subSource": "ton-jetton",
            "network": "TON",
            "asset": asset,
            "qty": str(qty_signed),
            "qtyDirection": direction,
            "ownSide": own,
            "counterpartySide": cp_raw,
            "counterpartyType": ctype,
            "counterpartyLabel": label,
            "txHash": raw.get("transaction_hash"),
            "extras": {"jetton_master": jmaster, "raw_amount": amount, "decimals": decimals},
        }


# ---------------------------------------------------------------------------
# Driver
# ---------------------------------------------------------------------------
def iter_jsonl(path: Path) -> Iterable[Dict[str, Any]]:
    with path.open() as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                yield json.loads(line)
            except json.JSONDecodeError:
                continue


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--only", choices=["bybit", "evm", "ton"], help="Only process one source")
    args = parser.parse_args()

    universe = load_universe()
    print(f"Loaded universe: {len(universe['evm_wallets'])} EVM wallets, "
          f"{len(universe['ton_wallets'])} TON wallets, {len(universe['solana_wallets'])} Solana wallets, "
          f"Bybit master {universe['bybit_master_uid']} + {len(universe['bybit_sub_uids'])} subs, "
          f"{len(universe['known_external'])} known external onramps, "
          f"{len(universe['protocols'])} known protocols, "
          f"{len(universe['pending_own'])} pending-OWN")

    summary = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "perSourceCount": defaultdict(int),
        "perCounterpartyType": defaultdict(int),
        "unknownCounterparties": defaultdict(int),
        "unclassifiedCounterparties": defaultdict(int),
        "externalCounterparties": defaultdict(int),
        "perSubSource": defaultdict(int),
    }

    sources = []
    if not args.only or args.only == "bybit":
        sources.append(("BYBIT", classify_bybit(universe)))
    if not args.only or args.only == "evm":
        sources.append(("EVM", classify_evm(universe)))
    if not args.only or args.only == "ton":
        sources.append(("TON", classify_ton(universe)))

    with OUT_FLOWS.open("w") as f:
        for src_name, gen in sources:
            for row in gen:
                f.write(json.dumps(row, ensure_ascii=False) + "\n")
                summary["perSourceCount"][src_name] += 1
                summary["perCounterpartyType"][row["counterpartyType"]] += 1
                summary["perSubSource"][row["subSource"]] += 1
                if row["counterpartyType"] == "UNKNOWN":
                    key = row.get("counterpartySide") or "(null)"
                    summary["unknownCounterparties"][key] += 1
                elif row["counterpartyType"] == "UNCLASSIFIED":
                    key = row.get("counterpartySide") or "(null)"
                    summary["unclassifiedCounterparties"][key] += 1
                elif row["counterpartyType"] == "EXTERNAL" and not row.get("counterpartyLabel"):
                    key = row.get("counterpartySide") or "(null)"
                    summary["externalCounterparties"][key] += 1

    # Sort + serialize
    summary["perSourceCount"] = dict(summary["perSourceCount"])
    summary["perCounterpartyType"] = dict(summary["perCounterpartyType"])
    summary["perSubSource"] = dict(summary["perSubSource"])
    unknown_sorted = sorted(summary["unknownCounterparties"].items(), key=lambda x: -x[1])[:200]
    unclassified_sorted = sorted(summary["unclassifiedCounterparties"].items(), key=lambda x: -x[1])[:300]
    external_sorted = sorted(summary["externalCounterparties"].items(), key=lambda x: -x[1])[:200]
    summary["unknownCounterparties"] = dict(unknown_sorted)
    summary["unclassifiedCounterparties"] = dict(unclassified_sorted)
    summary["externalCounterparties"] = dict(external_sorted)

    OUT_SUMMARY.write_text(json.dumps(summary, indent=2))

    # Dedicated file for unclassified counterparty investigation
    OUT_UNKNOWN.write_text(json.dumps({
        "generatedAt": summary["generatedAt"],
        "purpose": "Each entry needs manual identification (Etherscan/TonViewer label) before counterparty classification can be considered complete.",
        "missingCounterpartyDataCount": sum(v for _, v in unknown_sorted),
        "unclassifiedCount": sum(v for _, v in unclassified_sorted),
        "unclassifiedAddresses": dict(unclassified_sorted),
        "externalUnlabelled": dict(external_sorted),
    }, indent=2))

    print(f"\nClassified flows: {OUT_FLOWS}")
    print(f"Summary: {OUT_SUMMARY}")
    print(f"Unknown counterparties for review: {OUT_UNKNOWN}")
    print(f"Source counts: {dict(summary['perSourceCount'])}")
    print(f"Counterparty types: {dict(summary['perCounterpartyType'])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
