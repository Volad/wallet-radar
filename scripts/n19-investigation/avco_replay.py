#!/usr/bin/env python3
"""
N19 / S7 — Independent AVCO replay simulation.

Replays Bybit raw events chronologically and rebuilds per-asset
position + average cost basis + realised PnL, **without referencing
the application's normalised pipeline**. Used as oracle to compare
against the production dashboard.

Scope:
  - Bybit only (UTA + FUND + EARN treated as one umbrella for now).
  - Asset-level AVCO; cross-ledger transfers within Bybit do NOT
    realise PnL.
  - Trades on UTA (spot + execution-spot) realise PnL.
  - Crypto Loans: borrow IN (debit qty IN, basis at market price);
    repay OUT (debit qty OUT, basis consumed proportionally).
    Loan interest = realised loss.
  - Earn (Flexible/On-Chain/Launchpool): subscription = move within
    Bybit (asset rotation, no PnL); redemption = move back; interest
    = REWARD inflow with basis = current market price (zero-cost gain).
  - Convert: SELL one asset + BUY another; PnL realised on SELL side.
  - Airdrops/Rewards: qty IN with zero basis (full upside is PnL on
    eventual sell).
  - Bybit Deposit (on-chain): inflow with basis = market price at
    timestamp, unless counterparty is OWN_WALLET — in which case basis
    comes from the source wallet (here approximated as market price).
  - Bybit Withdrawal: outflow at AVCO basis. No PnL (transfer to OWN
    is internal; to EXTERNAL is treated as "spend").

Outputs:
  cycle/5/results/n19-portfolio-replay.json
"""

from __future__ import annotations

import json
import os
import sys
import time
from collections import defaultdict
from datetime import datetime, timezone
from decimal import Decimal, getcontext
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple
from urllib import error, parse, request

getcontext().prec = 50

PROJECT_ROOT = Path(__file__).resolve().parents[2]
RESULTS_DIR = PROJECT_ROOT / "cycle-autorun/cycle-data/cycle/5/results"
FLOWS_PATH = RESULTS_DIR / "n19-classified-flows.jsonl"
BALANCES_PATH = RESULTS_DIR / "n19-raw/bybit-balances.json"
OUT_PATH = RESULTS_DIR / "n19-portfolio-replay.json"

STABLES = {"USDT", "USDC", "USDE", "USDT0", "USDt0", "DAI", "TUSD", "BUSD"}

# Approximate prices (fallback when CoinGecko unavailable / for instant pricing)
PRICE_NOW: Dict[str, Decimal] = {
    "USDT": Decimal("1.0"),
    "USDC": Decimal("1.0"),
    "USDE": Decimal("1.0"),
    "USDT0": Decimal("1.0"),
    "DAI": Decimal("1.0"),
    "BTC": Decimal("100000"),
    "WBTC": Decimal("100000"),
    "ETH": Decimal("3500"),
    "CMETH": Decimal("3650"),
    "METH": Decimal("3650"),
    "SOL": Decimal("180"),
    "MNT": Decimal("0.72"),
    "ARB": Decimal("0.13"),
    "TON": Decimal("1.6"),
    "DOGS": Decimal("0.00019"),
    "DOGE": Decimal("0.113"),
    "LDO": Decimal("0.41"),
    "ONDO": Decimal("0.42"),
    "LINK": Decimal("10.7"),
    "LTC": Decimal("59"),
    "XRP": Decimal("1.47"),
    "XPL": Decimal("0.09"),
    "AVAX": Decimal("17"),
    "AGLD": Decimal("0.30"),
    "WLKN": Decimal("0.005"),
    "ALCH": Decimal("0.02"),
    "FLOCK": Decimal("0.10"),
}


def D(x: Any) -> Decimal:
    if x is None or x == "":
        return Decimal(0)
    try:
        return Decimal(str(x))
    except Exception:
        return Decimal(0)


def price(asset: Optional[str]) -> Decimal:
    if not asset:
        return Decimal(0)
    return PRICE_NOW.get(asset.upper(), Decimal(0))


# ---------------------------------------------------------------------------
# Asset book — AVCO
# ---------------------------------------------------------------------------
class Book:
    def __init__(self, label: str = ""):
        self.label = label
        # asset -> {"qty": Decimal, "basis": Decimal (USD-equivalent), "realised": Decimal, "uncovered_qty": Decimal}
        self.pos: Dict[str, Dict[str, Decimal]] = defaultdict(lambda: {"qty": Decimal(0), "basis": Decimal(0), "realised": Decimal(0), "uncovered_qty": Decimal(0)})

    def add(self, asset: str, qty: Decimal, basis_usd: Decimal):
        if qty == 0:
            return
        s = self.pos[asset]
        s["qty"] += qty
        s["basis"] += basis_usd

    def remove(self, asset: str, qty: Decimal) -> Tuple[Decimal, Decimal]:
        """Remove qty at average cost. Returns (qty_removed, basis_consumed_usd)."""
        s = self.pos[asset]
        if s["qty"] <= 0:
            s["qty"] -= qty
            s["uncovered_qty"] += qty
            return (qty, Decimal(0))
        avg = (s["basis"] / s["qty"]) if s["qty"] > 0 else Decimal(0)
        actual = min(qty, s["qty"])
        basis_consumed = avg * actual
        s["qty"] -= qty
        s["basis"] -= basis_consumed
        if s["basis"] < 0:
            s["basis"] = Decimal(0)
        if qty > actual:
            s["uncovered_qty"] += (qty - actual)
        return (actual, basis_consumed)

    def sell(self, asset: str, qty: Decimal, proceeds_usd: Decimal):
        """Realised PnL = proceeds − basis consumed."""
        actual, basis_consumed = self.remove(asset, qty)
        pnl = proceeds_usd * (actual / qty if qty > 0 else Decimal(0)) - basis_consumed
        self.pos[asset]["realised"] += pnl

    def transfer_out(self, asset: str, qty: Decimal) -> Tuple[Decimal, Decimal]:
        """Move qty+basis to another book. No PnL realised."""
        return self.remove(asset, qty)


# ---------------------------------------------------------------------------
# Replay engine
# ---------------------------------------------------------------------------
def load_flows() -> List[Dict[str, Any]]:
    flows = []
    with FLOWS_PATH.open() as f:
        for line in f:
            flows.append(json.loads(line))
    flows.sort(key=lambda f: f.get("ts") or "")
    return flows


def is_bybit(f: Dict[str, Any]) -> bool:
    return f.get("source") == "BYBIT"


def main() -> int:
    flows = load_flows()
    print(f"Loaded {len(flows)} classified flows.")

    book = Book("BYBIT_UMBRELLA")

    # Track invariants
    realised_pnl_events: List[Dict[str, Any]] = []
    external_capital_in = Decimal(0)
    external_capital_out = Decimal(0)
    airdrops_qty: Dict[str, Decimal] = defaultdict(Decimal)
    rewards_qty: Dict[str, Decimal] = defaultdict(Decimal)
    fees_usd = Decimal(0)
    loan_open_qty: Dict[str, Decimal] = defaultdict(Decimal)  # outstanding loan principal
    loan_interest_usd = Decimal(0)

    # ---- Spot trade reconstruction: tx-log-unified type=TRADE on UTA ----
    # tx-log-unified has paired rows (one for base coin, one for quote coin) per execId.
    # Easier: use bybit-execution-spot which has per-row symbol/side/qty/price.
    spot_trades: List[Dict[str, Any]] = []
    fh_trades: List[Dict[str, Any]] = []  # Currency Buy/Sell from FUND
    convert_trades: List[Dict[str, Any]] = []  # Convert pairs from FUND

    # ---- Pass 1: separate flows by handler ----
    handler_buckets: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
    for f in flows:
        if not is_bybit(f):
            continue
        ss = f["subSource"]
        handler_buckets[ss].append(f)

    print(f"Sub-source counts: {dict((k, len(v)) for k, v in handler_buckets.items())}")

    # ---- Process timeline ----
    # We collapse all Bybit sources into one timeline. Ordering preserved by ts.
    bybit_flows = [f for f in flows if is_bybit(f)]

    # We need spot trade reconstruction. tx-log-unified TRADE rows come paired
    # (base+quote). To realise PnL correctly, we pair execId rows together
    # before processing.
    tx_log_rows = handler_buckets.get("bybit-tx-log-unified", [])
    # Group tx_log_rows by (tradeId or orderId+execTime) for TRADE type
    trade_groups: Dict[Tuple[str, str], List[Dict[str, Any]]] = defaultdict(list)
    other_tx_log: List[Dict[str, Any]] = []
    for r in tx_log_rows:
        ex = r.get("extras", {}) or {}
        if ex.get("type") == "TRADE":
            key = (ex.get("tradeId") or "", r.get("txHash") or "")
            trade_groups[key].append(r)
        else:
            other_tx_log.append(r)

    print(f"Grouped {len(trade_groups)} unique trades from tx-log-unified")

    # Re-build a timeline of "events" with structure {ts, kind, ...}
    events: List[Dict[str, Any]] = []

    # 1) Bybit Deposits (on-chain)
    for f in handler_buckets.get("bybit-deposit-onchain", []):
        events.append({
            "ts": f["ts"],
            "kind": "DEPOSIT",
            "asset": f["asset"],
            "qty": D(f["qty"]),
            "ctype": f["counterpartyType"],
            "cp": f.get("counterpartySide"),
            "extras": f.get("extras", {}),
        })

    # 2) Bybit Withdrawals
    for f in handler_buckets.get("bybit-withdrawal", []):
        events.append({
            "ts": f["ts"],
            "kind": "WITHDRAW",
            "asset": f["asset"],
            "qty": D(f["qty"]),  # negative
            "ctype": f["counterpartyType"],
            "cp": f.get("counterpartySide"),
            "extras": f.get("extras", {}),
        })

    # 3) Funding history — many sub-types; key handlers below.
    for f in handler_buckets.get("bybit-funding-history", []):
        events.append({
            "ts": f["ts"],
            "kind": "FH",
            "asset": f["asset"],
            "qty": D(f["qty"]),
            "ctype": f["counterpartyType"],
            "cp": f.get("counterpartySide"),
            "label": f.get("counterpartyLabel"),
            "extras": f.get("extras", {}),
        })

    # 4) Trades on UTA (from execution-spot)
    for f in handler_buckets.get("bybit-execution-spot", []):
        ex = f.get("extras", {}) or {}
        symbol = ex.get("symbol") or ""
        side = ex.get("side", "").upper()
        ex_qty = D(ex.get("execQty") or f.get("qty"))  # base qty
        ex_price = D(ex.get("price"))
        # Parse symbol like FLOCKUSDT -> base=FLOCK, quote=USDT
        base, quote = None, None
        for q in ("USDT", "USDC", "USDE", "BTC", "ETH", "EUR"):
            if symbol.endswith(q) and len(symbol) > len(q):
                base = symbol[: -len(q)]
                quote = q
                break
        if not base:
            continue
        events.append({
            "ts": f["ts"],
            "kind": "SPOT_TRADE",
            "side": side,
            "base": base,
            "quote": quote,
            "baseQty": ex_qty,
            "price": ex_price,
            "feeCoin": ex.get("feeCurrency"),
            "feeAmount": D(ex.get("feeRate")) * ex_qty if ex.get("feeRate") else Decimal(0),
            "execValue": D(ex.get("execValue") or (ex_qty * ex_price)),
            "isMaker": ex.get("isMaker"),
            "orderId": ex.get("orderId"),
        })

    # 5) Internal transfers within Bybit (FUND<->UTA<->EARN)
    for f in handler_buckets.get("bybit-internal-transfer", []):
        events.append({
            "ts": f["ts"],
            "kind": "INTERNAL_TRANSFER",
            "asset": f["asset"],
            "qty": D(f["qty"]),
            "extras": f.get("extras", {}),
        })

    # 6) Universal transfers
    for f in handler_buckets.get("bybit-universal-transfer", []):
        events.append({
            "ts": f["ts"],
            "kind": "UNIVERSAL_TRANSFER",
            "asset": f["asset"],
            "qty": D(f["qty"]),
            "ctype": f["counterpartyType"],
            "cp": f.get("counterpartySide"),
            "extras": f.get("extras", {}),
        })

    # 7) tx-log non-TRADE rows
    for f in other_tx_log:
        ex = f.get("extras", {}) or {}
        events.append({
            "ts": f["ts"],
            "kind": "UTA_LEDGER",
            "asset": f["asset"],
            "qty": D(f["qty"]),
            "type": ex.get("type"),
            "extras": ex,
        })

    events.sort(key=lambda e: e["ts"] or "")
    print(f"Total events to replay: {len(events)}")

    # ---- Replay ----
    for e in events:
        kind = e["kind"]
        if kind == "DEPOSIT":
            asset = e["asset"]
            qty = e["qty"]
            ctype = e["ctype"]
            basis_per_unit = price(asset) if asset not in STABLES else Decimal(1)
            basis_usd = qty * basis_per_unit
            book.add(asset, qty, basis_usd)
            if ctype == "EXTERNAL":
                external_capital_in += basis_usd
        elif kind == "WITHDRAW":
            asset = e["asset"]
            qty = -e["qty"]  # remove positive amount
            ctype = e["ctype"]
            if qty > 0:
                qty_rem, basis_cons = book.remove(asset, qty)
                if ctype == "EXTERNAL":
                    # Treat as external spend at avg basis (no PnL booked here).
                    external_capital_out += basis_cons
        elif kind == "FH":
            asset = e["asset"]
            qty = e["qty"]
            ctype = e["ctype"]
            label = (e.get("label") or "")
            desc_en = (e.get("extras", {}) or {}).get("descriptionEn", "")
            busi = (e.get("extras", {}) or {}).get("showBusiTypeEn", "")
            if busi == "Fiat":
                # P2P Purchase fiat onramp — qty in, basis = qty (stable) or qty*price
                basis_per_unit = Decimal(1) if asset in STABLES else price(asset)
                basis_usd = qty * basis_per_unit
                book.add(asset, qty, basis_usd)
                external_capital_in += basis_usd if qty > 0 else Decimal(0)
            elif ctype == "AIRDROP":
                # Zero-basis inflow
                if qty > 0:
                    book.add(asset, qty, Decimal(0))
                    airdrops_qty[asset] += qty
            elif ctype == "REWARD":
                if qty > 0:
                    book.add(asset, qty, Decimal(0))
                    rewards_qty[asset] += qty
            elif busi == "Crypto Loans":
                if "Borrow Released" in desc_en or "Increase Collateral" in desc_en:
                    if qty > 0:
                        # Borrow released: liability, but qty becomes available.
                        # We DO NOT add basis (it's a loan, future payable).
                        # Track as zero-basis qty addition; the repayment will
                        # subtract qty too, and any net difference is interest.
                        book.add(asset, qty, Decimal(0))
                        loan_open_qty[asset] += qty
                elif "Borrow Repayment" in desc_en or "Repay Principal" in desc_en:
                    if qty < 0:
                        actual, basis_cons = book.remove(asset, -qty)
                        loan_open_qty[asset] -= actual
                elif "Repay Interest" in desc_en:
                    if qty < 0:
                        actual, basis_cons = book.remove(asset, -qty)
                        loan_interest_usd += (price(asset) * actual)
                elif "Decrease Collateral" in desc_en:
                    if qty < 0:
                        book.remove(asset, -qty)
                else:
                    pass  # other loan types
            elif busi == "Earn":
                # Earn movements stay within Bybit umbrella; pure FH-side leg.
                # For umbrella view treat as no-op (cross-ledger transfer).
                # EXCEPT: Easy Earn | Flexible Interest Distribution = REWARD inflow.
                if "Interest" in desc_en or "Rewards Distribution" in desc_en:
                    if qty > 0:
                        book.add(asset, qty, Decimal(0))  # zero basis
                        rewards_qty[asset] += qty
                else:
                    # Subscription / Redemption / Launchpool — net zero within umbrella.
                    pass
            elif busi == "Convert":
                # Convert pair: we'll see both legs separately. For umbrella view,
                # process each leg as buy/sell at the leg's notional value.
                if qty > 0:
                    # IN-side of convert: basis is the asset received.
                    # Approximation: at market price now (would be better with paired matching).
                    basis_per_unit = Decimal(1) if asset in STABLES else price(asset)
                    book.add(asset, qty, qty * basis_per_unit)
                else:
                    out_qty = -qty
                    actual, basis_cons = book.remove(asset, out_qty)
                    # No PnL realised here in umbrella view (convert is asset rotation
                    # within Bybit; PnL realisation happens on eventual external sale).
            elif busi in ("Transfer in", "Transfer out"):
                # No-op for umbrella view
                pass
            elif busi == "Withdraw":
                if qty < 0:
                    out_qty = -qty
                    book.remove(asset, out_qty)
            elif busi == "Deposit":
                # No-op: deposit is captured by bybit-deposit-onchain stream
                pass
            else:
                pass  # ignore unknown
        elif kind == "INTERNAL_TRANSFER" or kind == "UNIVERSAL_TRANSFER":
            # No-op for umbrella (both sides are OWN_BYBIT)
            pass
        elif kind == "SPOT_TRADE":
            base = e["base"]
            quote = e["quote"]
            base_qty = e["baseQty"]
            ex_value = e["execValue"]
            fee_coin = e.get("feeCoin")
            fee_amount = e.get("feeAmount") or Decimal(0)
            if e["side"] == "BUY":
                # acquire base, spend quote
                if quote in STABLES:
                    cost_usd = ex_value
                else:
                    cost_usd = ex_value * (price(quote))
                book.remove(quote, ex_value)  # spend quote
                book.add(base, base_qty, cost_usd)
            else:  # SELL
                if quote in STABLES:
                    proceeds_usd = ex_value
                else:
                    proceeds_usd = ex_value * price(quote)
                book.sell(base, base_qty, proceeds_usd)
                book.add(quote, ex_value, proceeds_usd)
            if fee_coin and fee_amount > 0:
                book.remove(fee_coin, fee_amount)
                fees_usd += fee_amount * (Decimal(1) if fee_coin in STABLES else price(fee_coin))
        elif kind == "UTA_LEDGER":
            # tx-log entries: TRANSFER_IN/OUT, BORROW/REPAY, FEE, INTEREST, BONUS, AIRDROP
            t = e.get("type") or ""
            asset = e["asset"]
            qty = e["qty"]
            if t in ("TRANSFER_IN", "TRANSFER_OUT"):
                # Already handled by internal-transfer endpoint; skip
                pass
            elif t == "AIRDROP":
                if qty > 0:
                    book.add(asset, qty, Decimal(0))
                    airdrops_qty[asset] += qty
            elif t == "BONUS":
                if qty > 0:
                    book.add(asset, qty, Decimal(0))
                    rewards_qty[asset] += qty
            elif t == "INTEREST":
                if qty > 0:
                    book.add(asset, qty, Decimal(0))
                    rewards_qty[asset] += qty
            elif t == "FEE":
                if qty < 0:
                    book.remove(asset, -qty)
                    fees_usd += (-qty) * (Decimal(1) if asset in STABLES else price(asset))
            elif t == "BORROW":
                if qty > 0:
                    book.add(asset, qty, Decimal(0))
                    loan_open_qty[asset] += qty
            elif t == "REPAY":
                if qty < 0:
                    actual, _ = book.remove(asset, -qty)
                    loan_open_qty[asset] -= actual
            elif t == "FLEXIBLE_STAKING_SUBSCRIPTION":
                pass  # umbrella view: cross-ledger
            elif t == "FLEXIBLE_STAKING_REDEMPTION":
                pass
            elif t in ("CURRENCY_BUY", "CURRENCY_SELL"):
                # Bybit convert v1 — pair handled in funding-history side
                pass

    # ---- Result ----
    final_qty: Dict[str, Decimal] = {}
    final_basis: Dict[str, Decimal] = {}
    final_realised: Dict[str, Decimal] = {}
    final_uncovered: Dict[str, Decimal] = {}
    for asset, s in book.pos.items():
        if s["qty"] == 0 and s["realised"] == 0 and s["uncovered_qty"] == 0:
            continue
        final_qty[asset] = s["qty"]
        final_basis[asset] = s["basis"]
        final_realised[asset] = s["realised"]
        final_uncovered[asset] = s["uncovered_qty"]

    total_realised = sum(final_realised.values(), Decimal(0))
    total_basis = sum(final_basis.values(), Decimal(0))
    # Mark-to-market portfolio value
    total_mtm_value = Decimal(0)
    for asset, qty in final_qty.items():
        if qty > 0:
            total_mtm_value += qty * (Decimal(1) if asset in STABLES else price(asset))

    out = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "scope": "Bybit umbrella (UTA + FUND + EARN) AVCO simulation. Strictly bottom-up from raw API events.",
        "totals": {
            "externalCapitalInUsd": str(external_capital_in.quantize(Decimal("0.01"))),
            "externalCapitalOutUsd": str(external_capital_out.quantize(Decimal("0.01"))),
            "netExternalCapitalUsd": str((external_capital_in - external_capital_out).quantize(Decimal("0.01"))),
            "totalBasisInBookUsd": str(total_basis.quantize(Decimal("0.01"))),
            "totalMarkToMarketUsd": str(total_mtm_value.quantize(Decimal("0.01"))),
            "totalRealisedPnlUsd": str(total_realised.quantize(Decimal("0.01"))),
            "loanInterestPaidUsd": str(loan_interest_usd.quantize(Decimal("0.01"))),
            "fundingFeesPaidUsd": str(fees_usd.quantize(Decimal("0.01"))),
        },
        "conservationCheck": {
            "principle": "Mark-to-market value should equal NetExternalCapital + Realised PnL + Unrealised PnL + Rewards + Airdrops − Fees − LoanInterest",
            "rewardsValueUsd": str(sum((qty * (Decimal(1) if asset in STABLES else price(asset))) for asset, qty in rewards_qty.items()).quantize(Decimal("0.01"))),
            "airdropsValueUsd": str(sum((qty * (Decimal(1) if asset in STABLES else price(asset))) for asset, qty in airdrops_qty.items()).quantize(Decimal("0.01"))),
            "unrealisedPnlUsd": str((total_mtm_value - total_basis).quantize(Decimal("0.01"))),
            "implicitDelta_MarkVsExternalPlusPnl": str(
                (total_mtm_value - (external_capital_in - external_capital_out) - total_realised).quantize(Decimal("0.01"))
            ),
        },
        "perAsset": {
            asset: {
                "qty": str(final_qty[asset].normalize() if isinstance(final_qty[asset], Decimal) else final_qty[asset]),
                "basisUsd": str(final_basis.get(asset, Decimal(0)).quantize(Decimal("0.01"))),
                "avgPriceUsd": str((final_basis[asset] / final_qty[asset]).quantize(Decimal("0.01")) if final_qty[asset] > 0 else Decimal(0)),
                "currentPriceUsd": str(price(asset)) if asset not in STABLES else "1.00",
                "markToMarketUsd": str((final_qty[asset] * (Decimal(1) if asset in STABLES else price(asset))).quantize(Decimal("0.01"))),
                "realisedPnlUsd": str(final_realised.get(asset, Decimal(0)).quantize(Decimal("0.01"))),
                "uncoveredQty": str(final_uncovered.get(asset, Decimal(0))),
                "airdropQty": str(airdrops_qty.get(asset, Decimal(0))),
                "rewardQty": str(rewards_qty.get(asset, Decimal(0))),
                "loanOpenQty": str(loan_open_qty.get(asset, Decimal(0))),
            }
            for asset in sorted(final_qty.keys())
        },
        "outstandingLoans": {k: str(v) for k, v in loan_open_qty.items() if v != 0},
    }
    OUT_PATH.write_text(json.dumps(out, indent=2))
    print(f"\nWritten: {OUT_PATH}")
    print(f"NetExternalCapital: ${external_capital_in - external_capital_out:,.2f}")
    print(f"Mark-to-Market:     ${total_mtm_value:,.2f}")
    print(f"Realised PnL:       ${total_realised:,.2f}")
    print(f"Unrealised PnL:     ${total_mtm_value - total_basis:,.2f}")
    print(f"Conservation delta: ${total_mtm_value - (external_capital_in - external_capital_out) - total_realised:,.2f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
