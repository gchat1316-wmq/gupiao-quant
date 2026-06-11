#!/usr/bin/env python3
"""Backfill trade_stock_basic valuation fields from BaoStock.

Required packages:
  pip install baostock pandas pymysql

Database connection is read from DB_HOST/DB_PORT/DB_NAME/DB_USERNAME/DB_PASSWORD.
"""

from __future__ import annotations

import argparse
import datetime as dt
import os
from decimal import Decimal, InvalidOperation

import baostock as bs
import pymysql


def to_baostock_code(project_code: str) -> str:
    code, market = project_code.split(".", 1)
    return f"{market.lower()}.{code}"


def to_project_code(baostock_code: str) -> str:
    market, code = baostock_code.split(".", 1)
    return f"{code}.{market.upper()}"


def decimal_or_none(raw: str | None) -> Decimal | None:
    if raw is None or raw == "":
        return None
    try:
        return Decimal(raw)
    except InvalidOperation:
        return None


def date_or_none(raw: str | None) -> dt.date | None:
    if not raw:
        return None
    return dt.date.fromisoformat(raw)


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


def load_codes(conn, codes: list[str] | None, limit: int | None) -> list[str]:
    if codes:
        return codes
    sql = """
        SELECT stock_code
        FROM trade_stock_basic
        WHERE stock_code REGEXP '^[0-9]{6}\\.(SH|SZ)$'
        ORDER BY stock_code
    """
    if limit:
        sql += " LIMIT %s"
    with conn.cursor() as cur:
        cur.execute(sql, (limit,) if limit else None)
        return [row["stock_code"] for row in cur.fetchall()]


def fetch_basic(bs_code: str) -> dict[str, str] | None:
    rs = bs.query_stock_basic(code=bs_code)
    if rs.error_code != "0" or not rs.next():
        return None
    return dict(zip(rs.fields, rs.get_row_data()))


def fetch_latest_valuation(bs_code: str) -> dict[str, str] | None:
    end = dt.date.today()
    start = end - dt.timedelta(days=21)
    fields = "date,code,close,peTTM,pbMRQ,psTTM"
    rs = bs.query_history_k_data_plus(
        bs_code,
        fields,
        start_date=start.isoformat(),
        end_date=end.isoformat(),
        frequency="d",
        adjustflag="3",
    )
    if rs.error_code != "0":
        return None
    latest = None
    while rs.next():
        latest = dict(zip(rs.fields, rs.get_row_data()))
    return latest


def update_stock(conn, project_code: str, basic: dict[str, str] | None, valuation: dict[str, str] | None) -> None:
    if not basic and not valuation:
        return

    stock_name = basic.get("code_name") if basic else None
    exchange = project_code.split(".", 1)[1]
    list_date = date_or_none(basic.get("ipoDate")) if basic else None
    pe_ttm = decimal_or_none(valuation.get("peTTM")) if valuation else None
    pb = decimal_or_none(valuation.get("pbMRQ")) if valuation else None
    ps_ttm = decimal_or_none(valuation.get("psTTM")) if valuation else None

    with conn.cursor() as cur:
        cur.execute(
            """
            UPDATE trade_stock_basic
            SET stock_name = COALESCE(%s, stock_name),
                exchange = COALESCE(exchange, %s),
                list_date = COALESCE(list_date, %s),
                pe_ttm = COALESCE(%s, pe_ttm),
                pb = COALESCE(%s, pb),
                ps_ttm = COALESCE(%s, ps_ttm),
                valuation_updated_at = CASE
                    WHEN %s IS NOT NULL OR %s IS NOT NULL OR %s IS NOT NULL THEN NOW()
                    ELSE valuation_updated_at
                END,
                data_source = 'baostock'
            WHERE stock_code = %s
            """,
            (stock_name, exchange, list_date, pe_ttm, pb, ps_ttm, pe_ttm, pb, ps_ttm, project_code),
        )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--codes", nargs="*", help="Project codes, e.g. 688279.SH 300750.SZ")
    parser.add_argument("--limit", type=int, help="Limit when syncing all local stocks")
    args = parser.parse_args()

    conn = connect_db()
    codes = load_codes(conn, args.codes, args.limit)
    lg = bs.login()
    if lg.error_code != "0":
        raise RuntimeError(f"baostock login failed: {lg.error_msg}")

    updated = 0
    try:
        for project_code in codes:
            bs_code = to_baostock_code(project_code)
            basic = fetch_basic(bs_code)
            valuation = fetch_latest_valuation(bs_code)
            update_stock(conn, project_code, basic, valuation)
            updated += 1
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        bs.logout()
        conn.close()

    print(f"synced {updated} stocks from baostock")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
