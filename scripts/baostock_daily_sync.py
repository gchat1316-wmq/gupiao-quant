#!/usr/bin/env python3
"""Backfill trade_stock_daily from BaoStock daily bars.

Required packages:
  pip install baostock pymysql

Database connection is read from DB_HOST/DB_PORT/DB_NAME/DB_USERNAME/DB_PASSWORD.
"""

from __future__ import annotations

import argparse
import datetime as dt
import os
import time
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


def int_or_none(raw: str | None) -> int | None:
    if raw is None or raw == "":
        return None
    try:
        return int(float(raw))
    except ValueError:
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


def is_retryable_db_error(exc: Exception) -> bool:
    if not isinstance(exc, pymysql.MySQLError):
        return False
    code = exc.args[0] if exc.args else None
    return code in (1205, 1213)


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


def fetch_daily_rows(bs_code: str, days_back: int) -> list[dict[str, str]]:
    end = dt.date.today()
    start = end - dt.timedelta(days=days_back)
    fields = "date,code,open,high,low,close,volume,amount,turn"
    rs = bs.query_history_k_data_plus(
        bs_code,
        fields,
        start_date=start.isoformat(),
        end_date=end.isoformat(),
        frequency="d",
        adjustflag="2",
    )
    if rs.error_code != "0":
        raise RuntimeError(f"{bs_code}: {rs.error_msg}")
    rows: list[dict[str, str]] = []
    while rs.next():
        rows.append(dict(zip(rs.fields, rs.get_row_data())))
    return rows


def upsert_rows(conn, rows: list[dict[str, str]]) -> int:
    if not rows:
        return 0
    values = []
    for row in rows:
        project_code = to_project_code(row["code"])
        values.append((
            project_code,
            date_or_none(row.get("date")),
            decimal_or_none(row.get("open")),
            decimal_or_none(row.get("high")),
            decimal_or_none(row.get("low")),
            decimal_or_none(row.get("close")),
            int_or_none(row.get("volume")),
            decimal_or_none(row.get("amount")),
            decimal_or_none(row.get("turn")),
        ))
    with conn.cursor() as cur:
        cur.executemany(
            """
            INSERT INTO trade_stock_daily
              (stock_code, trade_date, open_price, high_price, low_price, close_price, volume, amount, turnover_rate)
            VALUES
              (%s, %s, %s, %s, %s, %s, %s, %s, %s)
            ON DUPLICATE KEY UPDATE
              open_price = VALUES(open_price),
              high_price = VALUES(high_price),
              low_price = VALUES(low_price),
              close_price = VALUES(close_price),
              volume = VALUES(volume),
              amount = VALUES(amount),
              turnover_rate = VALUES(turnover_rate)
            """,
            values,
        )
    return len(values)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--codes", nargs="*", help="Project codes, e.g. 688279.SH 300750.SZ")
    parser.add_argument("--limit", type=int, help="Limit when syncing all local stocks")
    parser.add_argument("--days-back", type=int, default=30)
    parser.add_argument("--max-retries", type=int, default=3)
    args = parser.parse_args()

    conn = connect_db()
    codes = load_codes(conn, args.codes, args.limit)
    lg = bs.login()
    if lg.error_code != "0":
        raise RuntimeError(f"baostock login failed: {lg.error_msg}")

    upserted = 0
    failures: list[str] = []
    try:
        for idx, project_code in enumerate(codes, start=1):
            for attempt in range(1, max(args.max_retries, 1) + 1):
                try:
                    rows = fetch_daily_rows(to_baostock_code(project_code), args.days_back)
                    upserted += upsert_rows(conn, rows)
                    conn.commit()
                    if idx % 100 == 0:
                        print(f"progress daily {idx}/{len(codes)} upserted_rows={upserted}", flush=True)
                    break
                except Exception as exc:
                    conn.rollback()
                    if attempt < max(args.max_retries, 1) and is_retryable_db_error(exc):
                        time.sleep(attempt)
                        continue
                    failures.append(f"{project_code}: {exc}")
                    break
    finally:
        bs.logout()
        conn.close()

    print(f"synced {upserted} daily rows from baostock; failures={len(failures)}")
    if failures:
        for failure in failures[:20]:
            print(failure, file=os.sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
