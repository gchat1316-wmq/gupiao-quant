#!/usr/bin/env python3
"""
QMT/xtdata -> MySQL bridge for the tech_ai stock pool.

Environment:
  DB_HOST=127.0.0.1
  DB_PORT=3306
  DB_NAME=wucai_trade
  DB_USERNAME=root
  DB_PASSWORD=...
  QMT_POLL_SECONDS=15

Dependencies:
  pip install pymysql
  QMT/MiniQMT Python environment with xtquant available.
"""

from __future__ import annotations

import datetime as dt
import os
import time
from decimal import Decimal
from typing import Any

import pymysql
from xtquant import xtdata


def env(name: str, default: str) -> str:
    return os.environ.get(name, default)


def connect_db():
    return pymysql.connect(
        host=env("DB_HOST", "127.0.0.1"),
        port=int(env("DB_PORT", "3306")),
        user=env("DB_USERNAME", "root"),
        password=env("DB_PASSWORD", ""),
        database=env("DB_NAME", "wucai_trade"),
        charset="utf8mb4",
        autocommit=True,
        cursorclass=pymysql.cursors.DictCursor,
    )


def to_project_code(qmt_code: str) -> str:
    code, market = qmt_code.split(".", 1)
    return f"{code}.{market.lower()}"


def to_qmt_code(project_code: str) -> str:
    raw = project_code.strip()
    if "." in raw:
        code, market = raw.split(".", 1)
    else:
        code = raw
        market = "SH" if raw.startswith(("6", "9")) else "SZ"
    return f"{code}.{market.upper()}"


def load_pool(conn) -> list[str]:
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT stock_code
            FROM invest_stock_pool
            WHERE pool_type='tech_ai' AND status <> 'exited'
            ORDER BY id
            """
        )
        return [to_qmt_code(row["stock_code"]) for row in cur.fetchall()]


def parse_time(value: Any) -> dt.datetime:
    if isinstance(value, dt.datetime):
        return value
    if isinstance(value, (int, float)):
        text = str(int(value))
        if len(text) >= 13:
            return dt.datetime.fromtimestamp(int(text[:13]) / 1000)
        if len(text) == 8:
            return dt.datetime.strptime(text, "%Y%m%d")
    if isinstance(value, str) and value:
        for fmt in ("%Y%m%d%H%M%S", "%Y%m%d", "%Y-%m-%d %H:%M:%S"):
            try:
                return dt.datetime.strptime(value[: len(fmt)], fmt)
            except ValueError:
                pass
    return dt.datetime.now()


def decimal_or_none(value: Any) -> Decimal | None:
    if value is None or value == "":
        return None
    try:
        return Decimal(str(value))
    except Exception:
        return None


def latest_bar_open(qmt_code: str, period: str) -> tuple[Decimal | None, dt.datetime | None]:
    data = xtdata.get_market_data_ex(
        field_list=["time", "open"],
        stock_list=[qmt_code],
        period=period,
        count=1,
        dividend_type="none",
        fill_data=True,
    )
    frame = data.get(qmt_code)
    if frame is None or len(frame) == 0:
        return None, None
    row = frame.iloc[-1]
    return decimal_or_none(row.get("open")), parse_time(row.get("time"))


def subscribe(codes: list[str]) -> None:
    for code in codes:
        xtdata.subscribe_quote(code, period="tick", count=0)
        xtdata.subscribe_quote(code, period="1m", count=-1)
        xtdata.subscribe_quote(code, period="5m", count=-1)


def write_snapshot(conn, qmt_code: str, tick: dict[str, Any]) -> None:
    minute1_open, minute1_time = latest_bar_open(qmt_code, "1m")
    minute5_open, minute5_time = latest_bar_open(qmt_code, "5m")
    latest = tick.get("lastPrice") or tick.get("last_price") or tick.get("price")
    prev_close = tick.get("lastClose") or tick.get("preClose") or tick.get("pre_close")
    open_price = tick.get("open")
    volume = tick.get("volume")
    amount = tick.get("amount")
    turnover = tick.get("turnoverRate") or tick.get("turnover_rate")
    quote_time = parse_time(tick.get("time") or tick.get("stime") or tick.get("timetag"))

    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO tech_ai_quote_snapshot
              (stock_code, quote_time, latest_price, prev_close_price, open_price,
               volume, amount, turnover_rate, minute1_open_price, minute1_time,
               minute5_open_price, minute5_time, source)
            VALUES
              (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, 'qmt')
            """,
            (
                to_project_code(qmt_code),
                quote_time,
                decimal_or_none(latest),
                decimal_or_none(prev_close),
                decimal_or_none(open_price),
                int(volume) if volume is not None else None,
                decimal_or_none(amount),
                decimal_or_none(turnover),
                minute1_open,
                minute1_time,
                minute5_open,
                minute5_time,
            ),
        )


def main() -> None:
    poll_seconds = int(env("QMT_POLL_SECONDS", "15"))
    conn = connect_db()
    subscribed: set[str] = set()
    while True:
        try:
            codes = load_pool(conn)
            new_codes = [code for code in codes if code not in subscribed]
            if new_codes:
                subscribe(new_codes)
                subscribed.update(new_codes)
            if codes:
                ticks = xtdata.get_full_tick(codes)
                for code, tick in ticks.items():
                    if tick:
                        write_snapshot(conn, code, tick)
            time.sleep(poll_seconds)
        except KeyboardInterrupt:
            break
        except Exception as exc:
            print(f"[{dt.datetime.now()}] qmt bridge error: {exc}", flush=True)
            time.sleep(poll_seconds)


if __name__ == "__main__":
    main()
