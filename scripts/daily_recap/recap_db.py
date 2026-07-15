#!/usr/bin/env python3
"""
recap_db.py - 复盘数据写入 MySQL wucai_trade.invest_market_recap
"""

from __future__ import annotations

import datetime as dt
import os
import sys

import pymysql


DB_CONF = {
    "host": os.getenv("DB_HOST", "43.140.208.165"),
    "port": int(os.getenv("DB_PORT", "3306")),
    "user": os.getenv("DB_USERNAME", "wucai"),
    "password": os.getenv("DB_PASSWORD", "Wucai@2026"),
    "database": os.getenv("DB_NAME", "wucai_trade"),
    "charset": "utf8mb4",
    "autocommit": True,
    "cursorclass": pymysql.cursors.DictCursor,
}


def connect_db():
    return pymysql.connect(**DB_CONF)


def indexes_summary(indexes: list[dict]) -> str:
    parts = []
    for idx in indexes:
        name = idx.get("name", "")
        change = idx.get("change_pct", 0)
        if name and change:
            sign = "+" if change > 0 else ""
            parts.append(f"{name}{sign}{change}%")
    return " | ".join(parts) if parts else ""


def calc_advance_decline(indexes: list[dict]) -> str:
    up = sum(1 for i in indexes if i.get("change_pct", 0) > 0)
    dn = sum(1 for i in indexes if i.get("change_pct", 0) < 0)
    return f"涨{up}/跌{dn}"


def extract_sentiment(indexes: list[dict], top_sectors: list[dict], bottom_sectors: list[dict]) -> str:
    avg_change = sum(i.get("change_pct", 0) for i in indexes) / max(len(indexes), 1)
    if avg_change > 1.0:
        sentiment = "强势普涨"
    elif avg_change > 0.3:
        sentiment = "震荡偏强"
    elif avg_change > -0.3:
        sentiment = "震荡分化"
    elif avg_change > -1.0:
        sentiment = "震荡偏弱"
    else:
        sentiment = "弱势普跌"

    if top_sectors:
        top_names = ",".join(s.get("name", "") for s in top_sectors[:3])
        sentiment += f"｜主线：{top_names}"
    return sentiment


def insert_recap(
    trade_date: str,
    title: str,
    content: str,
    indexes: list[dict],
    top_sectors: list[dict],
    bottom_sectors: list[dict],
    limit_up_count: int,
    limit_down_count: int,
    sectors: str = "",
    risks: str = "",
    catalysts: str = "",
    next_day_strategy: str = "",
    sentiment: str = "",
    key_data: str = "",
    recap_type: str = "evening",
    market: str = "A股",
) -> int | None:
    """写入复盘记录，返回新记录 id"""
    conn = connect_db()
    try:
        with conn.cursor() as cur:
            sql = """
                INSERT INTO invest_market_recap
                    (market, recap_date, recap_type, trade_date, title, content,
                     indexes_summary, advance_decline, limit_up, limit_down,
                     sentiment, sectors, risks, key_data, catalysts, next_day_strategy)
                VALUES
                    (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
            """
            trade_dt = dt.date.fromisoformat(trade_date)
            recap_dt = dt.date.today()
            idx_sum = indexes_summary(indexes)
            ad = calc_advance_decline(indexes)
            sent = sentiment or extract_sentiment(indexes, top_sectors, bottom_sectors)

            cur.execute(sql, (
                market, recap_dt, recap_type, trade_dt,
                title, content,
                idx_sum, ad, limit_up_count, limit_down_count,
                sent, sectors, risks, key_data, catalysts, next_day_strategy,
            ))
            return cur.lastrowid
    finally:
        conn.close()


def get_recent_recaps(limit: int = 10, market: str = None) -> list[dict]:
    """查询最近复盘记录"""
    conn = connect_db()
    try:
        with conn.cursor() as cur:
            if market:
                cur.execute(
                    "SELECT id, market, trade_date, title, sentiment, recap_date "
                    "FROM invest_market_recap WHERE market=%s ORDER BY id DESC LIMIT %s",
                    (market, limit)
                )
            else:
                cur.execute(
                    "SELECT id, market, trade_date, title, sentiment, recap_date "
                    "FROM invest_market_recap ORDER BY id DESC LIMIT %s",
                    (limit,)
                )
            return cur.fetchall()
    finally:
        conn.close()


def get_recap_content(recap_id: int) -> dict | None:
    """获取单条复盘完整内容"""
    conn = connect_db()
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT * FROM invest_market_recap WHERE id=%s",
                (recap_id,)
            )
            return cur.fetchone()
    finally:
        conn.close()


if __name__ == "__main__":
    import json
    recaps = get_recent_recaps(5)
    print("Recent recaps:")
    for r in recaps:
        print(f"  [{r['id']}] {r['trade_date']} {r['market']} {r['title']}")
