#!/usr/bin/env python3
"""Baostock on-demand pack fetcher for gupiao-quant stock-analysis module.

Replaces the old `/root/.agents/skills/baostock-finance-data/scripts/baostock_client.py`
that was hardcoded in application.yml. Lives in the project so the path is portable.

CLI:
    python3 baostock_client.py pack <code> <quoteDays> <years> [--lite]

    <code>       baostock format (sh.600000) OR project format (600000.SH) OR bare 6-digit
    <quoteDays>  trading days to summarize (e.g. 60)
    <years>      how many recent years of quarterly financials to pull (e.g. 2)
    --lite       skip forecast query (faster path used by prosperity-pick)

Output:
    Single JSON object on stdout, logs to stderr.

Required packages:
    pip install baostock
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import sys
import time
from typing import Any

import baostock as bs


# ---------- project code <-> baostock code ----------

PROJECT_PREFIX_TO_BS = {
    "SH": "sh",
    "SZ": "sz",
    "BJ": "bj",
}


def normalize_to_baostock(code: str) -> str:
    """Accept sh.600000, 600000.SH, 600000.sh, 600000 and return sh.600000."""
    if not code:
        raise ValueError("empty code")
    s = code.strip().lower()
    if re.match(r"^(sh|sz|bj)\.\d{6}$", s):
        return s
    if re.match(r"^\d{6}\.(sh|sz|bj)$", s):
        prefix, num = s.split(".", 1)
        return f"{prefix}.{num}"
    if re.match(r"^\d{6}$", s):
        if s.startswith(("60", "68", "90")):
            return f"sh.{s}"
        if s.startswith(("00", "30", "20")):
            return f"sz.{s}"
        if s.startswith(("43", "83", "87", "88")):
            return f"bj.{s}"
        # best-effort fallback
        return f"sh.{s}"
    # already in some other form; let baostock complain if invalid
    return s


# ---------- helpers ----------

def _f(value: Any) -> float | None:
    if value is None or value == "":
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _iter_result(rs) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    while rs.next():
        rows.append(dict(zip(rs.fields, rs.get_row_data())))
    return rows


def _retry(call, *, attempts: int = 2, delay: float = 1.5):
    """Retry a baostock query on transient network errors."""
    last_err: Exception | None = None
    for i in range(attempts):
        try:
            return call()
        except Exception as e:  # baostock raises on broken pipe / connection reset
            last_err = e
            print(f"[baostock_client] retry {i + 1}/{attempts} after error: {e}", file=sys.stderr)
            time.sleep(delay)
    assert last_err is not None
    raise last_err


# ---------- fetchers ----------

def fetch_quote(bs_code: str, quote_days: int) -> dict[str, Any]:
    """Summary of the most recent `quote_days` trading bars."""
    end = dt.date.today()
    start = end - dt.timedelta(days=max(int(quote_days) * 2, int(quote_days) + 10))
    rs = _retry(lambda: bs.query_history_k_data_plus(
        bs_code,
        "date,open,high,low,close,volume,turn,pctChg",
        start_date=start.isoformat(),
        end_date=end.isoformat(),
        frequency="d",
        adjustflag="3",
    ))
    if rs.error_code != "0":
        raise RuntimeError(f"query_history_k_data_plus failed: {rs.error_msg}")
    rows = _iter_result(rs)
    if not rows:
        return {}

    closes = [c for c in (_f(r.get("close")) for r in rows) if c is not None]
    highs = [c for c in (_f(r.get("high")) for r in rows) if c is not None]
    lows = [c for c in (_f(r.get("low")) for r in rows) if c is not None]
    volumes = [int(v) for v in (_f(r.get("volume")) for r in rows) if v is not None]
    turns = [c for c in (_f(r.get("turn")) for r in rows) if c is not None]

    last = rows[-1]
    first_close = closes[0] if closes else None
    last_close = closes[-1] if closes else None
    period_change_pct = None
    if first_close and last_close and first_close != 0:
        period_change_pct = round((last_close - first_close) / first_close * 100, 2)

    return {
        "as_of": last.get("date"),
        "open": last.get("open"),
        "close": last.get("close"),
        "high": last.get("high"),
        "low": last.get("low"),
        "volume": str(volumes[-1]) if volumes else None,
        "turn": last.get("turn"),
        "period_high": max(highs) if highs else None,
        "period_low": min(lows) if lows else None,
        "period_change_pct": period_change_pct,
        "bars_used": len(rows),
    }


def fetch_financial_history(bs_code: str, years: int) -> list[dict[str, Any]]:
    """Quarterly profitability snapshots, most recent first."""
    current_year = dt.date.today().year
    start_year = current_year - int(years) + 1
    out: list[dict[str, Any]] = []
    for year in range(start_year, current_year + 1):
        for quarter in (1, 2, 3, 4):
            rs = _retry(lambda y=year, q=quarter: bs.query_profit_data(bs_code, year=y, quarter=q))
            if rs.error_code != "0":
                print(f"[baostock_client] profit_data {year}Q{quarter} skip: {rs.error_msg}", file=sys.stderr)
                continue
            rows = _iter_result(rs)
            for r in rows:
                out.append({
                    "statDate": r.get("statDate"),
                    "profitability": {
                        "roe_avg": _f(r.get("roeAvg")),
                        "gp_margin": _f(r.get("gpMargin")),
                        "np_margin": _f(r.get("npMargin")),
                    },
                    "growth": {
                        # baostock's profit_data does not give YoY directly; the
                        # consumer is fine with None if we don't have it.
                        "yoy_revenue": _f(r.get("yoyNetProfit")),
                        "yoy_ni": _f(r.get("yoyNetProfit")),
                    },
                    "_raw": {k: r.get(k) for k in (
                        "roeAvg", "gpMargin", "npMargin", "netProfit", "MBRevenue",
                        "totalShare", "liqaShare",
                    )},
                })
    # sort by statDate ascending so the consumer can read newest from the tail
    out.sort(key=lambda x: x.get("statDate") or "")
    return out


def fetch_forecast(bs_code: str) -> list[dict[str, Any]]:
    """Recent earnings forecast / express notices."""
    end = dt.date.today()
    start = end - dt.timedelta(days=365 * 2)
    rs = _retry(lambda: bs.query_forecast_report(
        bs_code,
        start_date=start.isoformat(),
        end_date=end.isoformat(),
    ))
    if rs.error_code != "0":
        print(f"[baostock_client] forecast skip: {rs.error_msg}", file=sys.stderr)
        return []
    rows = _iter_result(rs)
    out = []
    for r in rows:
        out.append({
            "notice_date": r.get("profitNoticeDate") or r.get("noticeDate"),
            "type": r.get("profStatCode") or r.get("type"),
            "summary": r.get("profitForecastSumnps") or r.get("summary"),
            "growth_scope": r.get("profitForcastGrowthScope") or r.get("growthScope"),
        })
    return out


def fetch_industry(bs_code: str) -> dict[str, Any]:
    rs = _retry(lambda: bs.query_stock_industry(bs_code))
    if rs.error_code != "0":
        print(f"[baostock_client] industry skip: {rs.error_msg}", file=sys.stderr)
        return {}
    rows = _iter_result(rs)
    if not rows:
        return {}
    r = rows[-1]
    return {
        "industry": r.get("industry"),
        "industry_classification": r.get("industryClassification"),
    }


# ---------- entry points ----------

def cmd_pack(args: argparse.Namespace) -> int:
    bs_code = normalize_to_baostock(args.code)
    print(f"[baostock_client] pack code={bs_code} quoteDays={args.quoteDays} years={args.years} lite={args.lite}", file=sys.stderr)

    lg = bs.login()
    if lg.error_code != "0":
        print(f"baostock login failed: {lg.error_msg}", file=sys.stderr)
        return 2
    try:
        payload: dict[str, Any] = {
            "code": bs_code,
            "as_of": dt.date.today().isoformat(),
        }
        try:
            payload["quote"] = fetch_quote(bs_code, args.quoteDays)
        except Exception as e:
            print(f"[baostock_client] quote failed: {e}", file=sys.stderr)
            payload["quote"] = {}

        try:
            payload["financial_history"] = fetch_financial_history(bs_code, args.years)
        except Exception as e:
            print(f"[baostock_client] financial_history failed: {e}", file=sys.stderr)
            payload["financial_history"] = []

        if args.lite:
            payload["forecast"] = []
        else:
            try:
                payload["forecast"] = fetch_forecast(bs_code)
            except Exception as e:
                print(f"[baostock_client] forecast failed: {e}", file=sys.stderr)
                payload["forecast"] = []

        try:
            payload["industry"] = fetch_industry(bs_code)
        except Exception as e:
            print(f"[baostock_client] industry failed: {e}", file=sys.stderr)
            payload["industry"] = {}

        json.dump(payload, sys.stdout, ensure_ascii=False, default=str)
        sys.stdout.write("\n")
        return 0
    finally:
        bs.logout()


def main() -> int:
    parser = argparse.ArgumentParser(description="Baostock on-demand pack fetcher")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_pack = sub.add_parser("pack", help="fetch a single stock's data pack")
    p_pack.add_argument("code", help="sh.600000 / 600000.SH / 600000")
    p_pack.add_argument("quoteDays", type=int, help="trading days for quote summary")
    p_pack.add_argument("years", type=int, help="recent years of quarterly financials")
    p_pack.add_argument("--lite", action="store_true", help="skip forecast query")
    p_pack.set_defaults(func=cmd_pack)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
    except Exception as e:
        print(f"baostock_client fatal: {e}", file=sys.stderr)
        sys.exit(1)
