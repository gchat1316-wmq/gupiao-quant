#!/usr/bin/env python3
"""Fetch latest 5-minute K data from BaoStock and print JSON.

Required package:
  pip install baostock
"""

from __future__ import annotations

import argparse
import contextlib
import datetime as dt
import json
import sys

import baostock as bs


def to_baostock_code(project_code: str) -> str:
    raw = project_code.strip()
    if "." in raw:
        code, market = raw.split(".", 1)
    else:
        code = raw
        market = "sh" if raw.startswith(("6", "9")) else "sz"
    return f"{market.lower()}.{code}"


def to_project_code(baostock_code: str) -> str:
    market, code = baostock_code.split(".", 1)
    return f"{code}.{market.lower()}"


def parse_baostock_time(raw: str | None, fallback_date: str | None) -> str | None:
    if raw:
        digits = "".join(ch for ch in raw if ch.isdigit())
        if len(digits) >= 14:
            return dt.datetime.strptime(digits[:14], "%Y%m%d%H%M%S").isoformat()
    if fallback_date:
        return dt.datetime.fromisoformat(fallback_date).isoformat()
    return None


def int_or_none(raw: str | None) -> int | None:
    if not raw:
        return None
    try:
        return int(float(raw))
    except ValueError:
        return None


def latest_5m(project_code: str, days: int) -> dict[str, object] | None:
    end = dt.date.today()
    start = end - dt.timedelta(days=days)
    bs_code = to_baostock_code(project_code)
    fields = "date,time,code,open,high,low,close,volume,amount,adjustflag"
    rs = bs.query_history_k_data_plus(
        bs_code,
        fields,
        start_date=start.isoformat(),
        end_date=end.isoformat(),
        frequency="5",
        adjustflag="3",
    )
    if rs.error_code != "0":
        print(f"{project_code}: {rs.error_msg}", file=sys.stderr)
        return None

    latest = None
    while rs.next():
        row = dict(zip(rs.fields, rs.get_row_data()))
        if row.get("close"):
            latest = row
    if not latest:
        return None

    project = to_project_code(latest.get("code") or bs_code)
    quote_time = parse_baostock_time(latest.get("time"), latest.get("date"))
    return {
        "stockCode": project,
        "quoteTime": quote_time,
        "latestPrice": latest.get("close") or None,
        "prevClosePrice": None,
        "openPrice": latest.get("open") or None,
        "volume": int_or_none(latest.get("volume")),
        "amount": latest.get("amount") or None,
        "turnoverRate": None,
        "minute5OpenPrice": latest.get("open") or None,
        "minute5Time": quote_time,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("codes", nargs="+", help="Project codes, e.g. 688301.sh")
    parser.add_argument("--days", type=int, default=10)
    args = parser.parse_args()

    with contextlib.redirect_stdout(sys.stderr):
        lg = bs.login()
    if lg.error_code != "0":
        print(lg.error_msg, file=sys.stderr)
        return 2
    try:
        rows = [row for code in args.codes if (row := latest_5m(code, args.days))]
        print(json.dumps(rows, ensure_ascii=False, separators=(",", ":")))
    finally:
        with contextlib.redirect_stdout(sys.stderr):
            bs.logout()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
