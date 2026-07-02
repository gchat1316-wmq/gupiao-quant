#!/usr/bin/env python3
"""BaoStock → trade_stock_financial backfill.

Required packages:
    pip install baostock pymysql

Strategy
--------
The existing trade_stock_financial table is mixed-source (rows stamped with
``data_source='qmt'`` or ``'wind'`` historically); we MUST NOT overwrite them.
For every (stock_code, report_date) we upsert with ``INSERT IGNORE`` — only
fills in rows that are missing in the local DB.

BaoStock endpoints consumed per quarter (2018~current year):
    query_profit_data     → net_profit, eps, gross_margin, net_margin, roe
    query_balance_data    → current_ratio, debt_ratio
    query_growth_data     → *unused*; BaoStock's YOYNI is parent-NI YoY not
                            deducted-netprofit YoY, so we conservatively leave
                            deducted_netprofit_yoy NULL to avoid misleading
                            the existing qmt-sourced values.
    query_dupont_data     → *unused*; no raw operating_cashflow / revenue /
                            total_assets / total_equity — keep them NULL.

DB connection reads DB_HOST/DB_PORT/DB_NAME/DB_USERNAME/DB_PASSWORD env vars
(same as baostock_daily_sync.py).

CLI:
    python3 baostock_financial_sync.py [--codes 301696.SZ 600519.SH] [--limit N] \\
        [--start-year 2018] [--max-retries 3]
"""

from __future__ import annotations

import argparse
import datetime as dt
import os
import sys
import time
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from typing import Any

import baostock as bs
import pymysql


# ---------- helpers ----------

# BaoStock returns ratios in [0,1]; our decimal(10,4) column holds percentage.
_RATIO_PRECISION = Decimal("0.0001")


def pct_from_ratio(raw: Any) -> Decimal | None:
    """BaoStock 0.x → percentage with 4dp. None-safe."""
    if raw is None or raw == "":
        return None
    try:
        ratio = Decimal(str(raw))
    except InvalidOperation:
        return None
    pct = ratio * Decimal(100)
    return pct.quantize(_RATIO_PRECISION, rounding=ROUND_HALF_UP)


def parse_decimal_or_none(raw: Any, *, quantize_to: Decimal | None = None) -> Decimal | None:
    """Cast ``raw`` to ``Decimal``; ``None`` on bad input. Optional 4-dp quantization
    for floating-point ratios like BaoStock ``currentRatio``."""
    if raw is None or raw == "":
        return None
    try:
        d = Decimal(str(raw))
    except InvalidOperation:
        return None
    if quantize_to is not None:
        return d.quantize(quantize_to, rounding=ROUND_HALF_UP)
    return d


# ---------- record building (pure function — fully unit-tested) ----------


def build_record(
    code: str,
    name: str,
    profit: dict[str, str] | None,
    growth: dict[str, str] | None,  # noqa: ARG001  (kept for future use)
    balance: dict[str, str] | None,
    dupont: dict[str, str] | None = None,  # noqa: ARG001  (kept for future use)
) -> dict[str, Any] | None:
    """Merge BaoStock endpoints for a single (stock, quarter) into an INSERT dict.

    Returns ``None`` if no endpoint supplied a ``statDate`` (nothing to insert).
    """
    date_str = (
        (profit or {}).get("statDate")
        or (balance or {}).get("statDate")
        or (growth or {}).get("statDate")
        or (dupont or {}).get("statDate")
    )
    if not date_str:
        return None
    try:
        report_date = dt.date.fromisoformat(date_str)
    except ValueError:
        return None

    profit = profit or {}
    balance = balance or {}

    return {
        "stock_code": code,
        "stock_name": name,
        "report_date": report_date,
        # revenue / revenue_yoy left NULL — BaoStock 不提供 raw 营业总收入
        "revenue": None,
        "revenue_yoy": None,
        # profit endpoint (raw 元)
        "net_profit": parse_decimal_or_none(profit.get("netProfit")),
        # BaoStock 的 YOYNI 是归母净利润同比，不是扣非；保守不写避免误导
        "deducted_netprofit_yoy": None,
        "deducted_netprofit_ttm": None,
        "eps": parse_decimal_or_none(profit.get("epsTTM")),
        # ratios (×100)
        "roe": pct_from_ratio(profit.get("roeAvg")),
        "roa": None,
        "gross_margin": pct_from_ratio(profit.get("gpMargin")),
        "net_margin": pct_from_ratio(profit.get("npMargin")),
        "debt_ratio": pct_from_ratio(balance.get("liabilityToAsset")),
        # currentRatio is 倍数 (e.g. 5.4)，不要再 ×100；定量到 4dp 对齐 decimal(10,4) schema
        "current_ratio": parse_decimal_or_none(
            balance.get("currentRatio"), quantize_to=Decimal("0.0001")
        ),
        # cashflow / balance sheet 绝对值 BaoStock 不提供
        "operating_cashflow": None,
        "total_assets": None,
        "total_equity": None,
    }


# ---------- SQL ----------

_INSERT_COLUMNS = (
    "stock_code", "stock_name", "report_date",
    "revenue", "revenue_yoy",
    "net_profit", "deducted_netprofit_yoy", "deducted_netprofit_ttm",
    "eps", "roe", "roa", "gross_margin", "net_margin",
    "debt_ratio", "current_ratio",
    "operating_cashflow", "total_assets", "total_equity",
)
_INSERT_PLACEHOLDERS = ", ".join(["%s"] * len(_INSERT_COLUMNS))


def upsert_sql() -> str:
    """``INSERT IGNORE`` so we never overwrite qmt/wind historical rows."""
    return (
        f"INSERT IGNORE INTO trade_stock_financial ({', '.join(_INSERT_COLUMNS)}) "
        f"VALUES ({_INSERT_PLACEHOLDERS})"
    )


def upsert_row(record: dict[str, Any]) -> tuple[str, tuple]:
    sql = upsert_sql() + " /* data_source='baostock' */"
    params = tuple(record[col] for col in _INSERT_COLUMNS)
    return sql, params


# ---------- DB / network plumbing (matches baostock_daily_sync.py) ----------


def to_baostock_code(project_code: str) -> str:
    code, market = project_code.split(".", 1)
    return f"{market.lower()}.{code}"


def to_project_code(bs_code: str) -> str:
    market, code = bs_code.split(".", 1)
    return f"{code}.{market.upper()}"


def connect_db():
    return pymysql.connect(
        host=os.getenv("DB_HOST", "127.0.0.1"),
        port=int(os.getenv("DB_PORT", "3306")),
        user=os.getenv("DB_USERNAME", "root"),
        password=os.getenv("DB_PASSWORD", ""),
        database=os.getenv("DB_NAME", "wucai_trade"),
        charset="utf8mb4",
        autocommit=False,
        cursorclass=pymysql.cursors.DictCursor,
    )


def is_retryable_db_error(exc: Exception) -> bool:
    if not isinstance(exc, pymysql.MySQLError):
        return False
    code = exc.args[0] if exc.args else None
    return code in (1205, 1213)


def load_codes_with_meta(conn, codes: list[str] | None, limit: int | None) -> list[tuple[str, str]]:
    """Return ``[(code, name), ...]`` sorted by code."""
    sql = """
        SELECT stock_code, COALESCE(stock_name, '') AS stock_name
        FROM trade_stock_basic
        WHERE stock_code REGEXP '^[0-9]{6}\\.(SH|SZ)$'
        ORDER BY stock_code
    """
    args: tuple = ()
    if codes:
        placeholders = ",".join(["%s"] * len(codes))
        sql = f"""
            SELECT stock_code, COALESCE(stock_name, '') AS stock_name
            FROM trade_stock_basic
            WHERE stock_code IN ({placeholders})
            ORDER BY stock_code
        """
        args = tuple(codes)
    elif limit:
        sql += " LIMIT %s"
        args = (limit,)
    with conn.cursor() as cur:
        cur.execute(sql, args)
        return [(row["stock_code"], row["stock_name"]) for row in cur.fetchall()]


def _query(bs_code: str, fn_name: str, year: int, quarter: int):
    fn = getattr(bs, fn_name)
    return fn(bs_code, year=year, quarter=quarter)


def fetch_quarter_endpoints(bs_code: str, year: int, quarter: int) -> tuple[dict | None, dict | None, dict | None]:
    """Return (profit, balance, growth). Each is None on error_code != '0' or empty rows."""
    out_profit = out_balance = out_growth = None

    for fn_name, slot in (
        ("query_profit_data", "profit"),
        ("query_balance_data", "balance"),
        ("query_growth_data", "growth"),
    ):
        try:
            rs = _query(bs_code, fn_name, year, quarter)
        except Exception as exc:
            print(f"[financial_sync] {bs_code} {year}Q{quarter} {fn_name} raised: {exc}", file=sys.stderr)
            continue
        if rs.error_code != "0":
            continue
        # BaoStock returns at most one row per (code, year, quarter)
        while rs.next():
            row = dict(zip(rs.fields, rs.get_row_data()))
            if slot == "profit":
                out_profit = row
            elif slot == "balance":
                out_balance = row
            else:
                out_growth = row
    return out_profit, out_balance, out_growth


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--codes", nargs="*", help="Project codes, e.g. 301696.SZ 600519.SH")
    parser.add_argument("--limit", type=int, help="Limit when syncing all local stocks")
    parser.add_argument("--start-year", type=int, default=2018, help="Earliest year to backfill")
    parser.add_argument("--max-retries", type=int, default=3)
    args = parser.parse_args()

    conn = connect_db()
    pairs = load_codes_with_meta(conn, args.codes, args.limit)

    lg = bs.login()
    if lg.error_code != "0":
        print(f"baostock login failed: {lg.error_msg}", file=sys.stderr)
        return 2

    inserted = 0
    skipped = 0
    failures: list[str] = []
    current_year = dt.date.today().year

    try:
        for idx, (project_code, stock_name) in enumerate(pairs, start=1):
            bs_code = to_baostock_code(project_code)
            for year in range(args.start_year, current_year + 1):
                for quarter in (1, 2, 3, 4):
                    for attempt in range(1, max(args.max_retries, 1) + 1):
                        try:
                            profit, balance, growth = fetch_quarter_endpoints(bs_code, year, quarter)
                            rec = build_record(project_code, stock_name, profit, growth, balance)
                            if rec is None:
                                # nothing to insert (BaoStock 无数据 / statDate 缺失)
                                skipped += 1
                                break
                            sql, params = upsert_row(rec)
                            with conn.cursor() as cur:
                                affected = cur.execute(sql, params)
                            conn.commit()
                            inserted += int(affected)
                            break
                        except Exception as exc:
                            conn.rollback()
                            if attempt < max(args.max_retries, 1) and is_retryable_db_error(exc):
                                time.sleep(attempt)
                                continue
                            failures.append(f"{project_code} {year}Q{quarter}: {exc}")
                            break  # don't keep retrying non-DB errors
            if idx % 50 == 0:
                print(f"progress financial {idx}/{len(pairs)} inserted={inserted} skipped={skipped}",
                      flush=True)
    finally:
        bs.logout()
        conn.close()

    print(f"synced {len(pairs)} stocks from baostock; inserted={inserted} skipped={skipped} "
          f"failures={len(failures)} data_source='baostock'")
    if failures:
        for f in failures[:20]:
            print(f, file=sys.stderr)
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
