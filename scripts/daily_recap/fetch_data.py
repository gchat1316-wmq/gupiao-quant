#!/usr/bin/env python3
"""
fetch_data.py - A股/美股行情数据采集
支持东方财富 push2 接口 + akshare fallback
"""

from __future__ import annotations

import datetime as dt
import json
import os
import sys
from typing import Any

import requests

# ── 东方财富板块接口 ────────────────────────────────────────────────────────

EM_BASE = "https://push2.eastmoney.com/api/qt/clist/get"
EM_HEADERS = {"User-Agent": "Mozilla/5.0", "Referer": "https://quote.eastmoney.com/"}


def _em_clist(fs: str, fid: str = "f3", po: int = 1, pn: int = 1, pz: int = 20,
              fields: str = "f2,f3,f4,f12,f14,f15,f16,f17,f18,f20,f21") -> list[dict]:
    """通用东方财富板块/clist 请求"""
    params = {
        "pn": pn, "pz": pz, "po": po, "np": 1,
        "ut": "bd1d9ddb04089700cf9c27f6f7426281",
        "fltt": 2, "invt": 2, "fid": fid,
        "fs": fs,
        "fields": fields,
        "_": int(dt.datetime.now().timestamp() * 1000),
    }
    try:
        resp = requests.get(EM_BASE, params=params, timeout=10, headers=EM_HEADERS)
        data = resp.json()
        return data.get("data", {}).get("diff", [])
    except Exception as e:
        print(f"[WARN] EM clist error: {e}", file=sys.stderr)
        return []


def fetch_a_share_index() -> list[dict]:
    """腾讯财经 A 股主要指数"""
    targets = [
        ("sh000001", "上证指数"),
        ("sz399001", "深证成指"),
        ("sh000300", "沪深300"),
        ("sz399006", "创业板指"),
        ("sh000016", "上证50"),
        ("sh000688", "科创50"),
        ("sz399005", "中小100"),
    ]
    results = []
    for code, name in targets:
        try:
            url = f"https://qt.gtimg.cn/q={code}"
            resp = requests.get(url, timeout=5)
            parts = resp.text.strip().split("~")
            if len(parts) > 10:
                close = float(parts[3]) if parts[3] else 0
                prev_close = float(parts[4]) if parts[4] else close
                change_pct = round((close - prev_close) / prev_close * 100, 4) if prev_close else 0
                results.append({"name": name, "code": code, "close": close, "change_pct": change_pct})
        except Exception as e:
            print(f"[WARN] fetch index {code}: {e}", file=sys.stderr)
    return results


def fetch_sector_top(n: int = 15) -> list[dict]:
    """东方财富领涨板块"""
    items = _em_clist("m:90 t:2 f:!50", fid="f3", po=1, pn=1, pz=n)
    return [{"name": it.get("f14", ""), "change_pct": it.get("f3", 0)} for it in items if it.get("f14")]


def fetch_sector_bottom(n: int = 10) -> list[dict]:
    """东方财富领跌板块"""
    items = _em_clist("m:90 t:2 f:!50", fid="f3", po=0, pn=1, pz=n)
    return [{"name": it.get("f14", ""), "change_pct": it.get("f3", 0)} for it in items if it.get("f14")]


def fetch_limit_up_pool(threshold: float = 9.5) -> list[dict]:
    """东方财富涨停股池（按涨幅阈值）"""
    fs = "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23,m:0+t:81+s:2048"
    items = _em_clist(fs, fid="f3", po=1, pn=1, pz=100,
                      fields="f2,f3,f12,f14,f15,f16,f17,f18,f20,f21")
    result = []
    for it in items:
        pct = it.get("f3", 0) or 0
        if pct >= threshold:
            result.append({
                "name": it.get("f14", ""),
                "code": it.get("f12", ""),
                "close": it.get("f2", ""),
                "change_pct": pct,
                "volume": it.get("f20", ""),
                "amount": it.get("f21", ""),
                "reason": "",
            })
    return result


def fetch_limit_down_pool(threshold: float = -9.5) -> list[dict]:
    """东方财富跌停股池"""
    fs = "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23,m:0+t:81+s:2048"
    items = _em_clist(fs, fid="f3", po=0, pn=1, pz=100,
                      fields="f2,f3,f12,f14,f15,f16,f17,f18,f20,f21")
    result = []
    for it in items:
        pct = it.get("f3", 0) or 0
        if pct <= threshold:
            result.append({
                "name": it.get("f14", ""),
                "code": it.get("f12", ""),
                "close": it.get("f2", ""),
                "change_pct": pct,
                "volume": it.get("f20", ""),
                "amount": it.get("f21", ""),
            })
    return result


def fetch_sector_by_names(names: list[str]) -> list[dict]:
    """按板块名称模糊匹配板块行情"""
    result = []
    items = _em_clist("m:90 t:2 f:!50", fid="f3", po=1, pn=1, pz=4000)
    for it in items:
        name = it.get("f14", "") or ""
        if any(n.lower() in name.lower() for n in names):
            result.append({"name": name, "change_pct": it.get("f3", 0), "code": it.get("f12", "")})
    return result


# ── 美股接口 ────────────────────────────────────────────────────────────────

US_INDICES = [
    ("IXIC", "usIXIC", "纳指"),
    ("SPX", "usSPX", "标普500"),
    ("DJI", "usDJI", "道指"),
    ("NDX", "usNDX", "纳指100"),
    ("SOX", "usSOX", "费半"),
]

US_STOCKS = [
    ("NVDA", "usnvda", "英伟达"),
    ("AAPL", "usaapl", "苹果"),
    ("MSFT", "usmsft", "微软"),
    ("INTC", "usintc", "英特尔"),
    ("MU", "usmu", "美光"),
    ("TSLA", "ustsla", "特斯拉"),
    ("AMZN", "usamzn", "亚马逊"),
    ("GOOGL", "usgoog", "谷歌"),
]


def fetch_us_index() -> list[dict]:
    """腾讯财经美股指数
    格式: v_usIXIC="200~纳斯达克~.IXIC~25873.18~26281.61~...
    parts[2]=中文名, parts[3]=symbol, parts[4]=当前价, parts[5]=昨收
    """
    codes = ",".join(code for _, code, _ in US_INDICES)
    results = []
    try:
        url = f"https://qt.gtimg.cn/q={codes}"
        resp = requests.get(url, timeout=8)
        lines = [l for l in resp.text.strip().split("\n") if l and 'none_match' not in l]
        for line in lines:
            parts = line.split("~")
            # parts[1]=中文名, parts[2]=符号, parts[3]=当前价, parts[4]=昨收
            if len(parts) > 4:
                name = parts[1] if parts[1] else ""
                sym = parts[2] if parts[2] else ""
                close = float(parts[3]) if parts[3] else 0
                prev_close = float(parts[4]) if parts[4] else close
                change_pct = round((close - prev_close) / prev_close * 100, 2) if prev_close else 0
                results.append({"name": name, "code": sym, "close": close, "change_pct": change_pct})
    except Exception as e:
        print(f"[WARN] fetch US index: {e}", file=sys.stderr)
    return results


def fetch_us_stocks() -> list[dict]:
    """腾讯财经美股个股
    格式同指数
    """
    codes = ",".join(code.lower() for _, code, _ in US_STOCKS)
    results = []
    try:
        url = f"https://qt.gtimg.cn/q={codes}"
        resp = requests.get(url, timeout=8)
        lines = [l for l in resp.text.strip().split("\n") if l and 'none_match' not in l]
        name_map = {code.lower(): name for _, code, name in US_STOCKS}
        for line in lines:
            parts = line.split("~")
            # parts[1]=中文名, parts[2]=符号, parts[3]=当前价, parts[4]=昨收
            if len(parts) > 4:
                name_em = parts[1] if parts[1] else ""
                sym_raw = parts[2] if parts[2] else ""
                sym = sym_raw.replace("us", "").upper()
                name = name_map.get(sym.lower(), name_em) or name_em or sym
                close = float(parts[3]) if parts[3] else 0
                prev_close = float(parts[4]) if parts[4] else close
                change_pct = round((close - prev_close) / prev_close * 100, 2) if prev_close else 0
                results.append({"name": name, "code": sym, "close": close, "change_pct": change_pct})
    except Exception as e:
        print(f"[WARN] fetch US stocks: {e}", file=sys.stderr)
    return results


# ── akshare fallback ─────────────────────────────────────────────────────────

def fetch_a_share_index_akshare() -> list[dict]:
    """akshare A股指数 fallback"""
    try:
        import akshare as ak
        df = ak.stock_zh_index_spot_em()
        target_names = {"上证指数": "sh000001", "深证成指": "sz399001", "沪深300": "sh000300",
                        "创业板指": "sz399006", "上证50": "sh000016", "科创50": "sh000688"}
        rows = []
        for _, row in df.iterrows():
            name = row.get("名称", "")
            if name in target_names:
                close = float(row.get("最新价", 0) or 0)
                change_pct = float(row.get("涨跌幅", 0) or 0)
                rows.append({"name": name, "code": target_names[name], "close": close, "change_pct": change_pct})
        return rows
    except Exception as e:
        print(f"[WARN] akshare index fallback failed: {e}", file=sys.stderr)
        return []


def fetch_sector_top_akshare(n: int = 15) -> list[dict]:
    """akshare 板块涨幅 fallback"""
    try:
        import akshare as ak
        df = ak.stock_sector_spot(indicator="涨幅")
        result = []
        for _, row in df.head(n).iterrows():
            result.append({"name": str(row.get("名称", "")), "change_pct": float(row.get("涨跌幅", 0) or 0)})
        return result
    except Exception as e:
        print(f"[WARN] akshare sector top failed: {e}", file=sys.stderr)
        return []


def fetch_limit_up_akshare(n: int = 50) -> list[dict]:
    """akshare 涨停股 fallback"""
    try:
        import akshare as ak
        df = ak.stock_zt_pool_em(date=dt.date.today().strftime("%Y%m%d"))
        result = []
        for _, row in df.head(n).iterrows():
            result.append({
                "name": str(row.get("名称", "")),
                "code": str(row.get("代码", "")),
                "close": float(row.get("最新价", 0) or 0),
                "change_pct": float(row.get("涨幅%", 0) or 0),
                "reason": str(row.get("涨停原因", "")),
            })
        return result
    except Exception as e:
        print(f"[WARN] akshare zt pool failed: {e}", file=sys.stderr)
        return []


# ── 主采集函数 ─────────────────────────────────────────────────────────────

def collect_a_share() -> dict:
    """采集 A 股全量数据"""
    today = dt.date.today().isoformat()

    # 指数（腾讯优先，akshare fallback）
    idx = fetch_a_share_index()
    if not idx:
        idx = fetch_a_share_index_akshare()

    # 板块
    top = fetch_sector_top(15)
    bot = fetch_sector_bottom(10)

    # 涨停/跌停（东方财富优先，akshare fallback）
    up20 = fetch_limit_up_pool(19.5)
    up10 = fetch_limit_up_pool(9.5)
    down = fetch_limit_down_pool(-9.5)

    if not up20:
        up20 = fetch_limit_up_akshare(15)
    if not up10:
        up10 = fetch_limit_up_akshare(30)

    # 科技相关板块
    tech = fetch_sector_by_names(["PCB", "光模块", "半导体", "元件", "通信设备"])

    return {
        "date": today,
        "market": "A股",
        "indexes": idx,
        "top_sectors": top,
        "bottom_sectors": bot,
        "limit_up_20": up20[:15],
        "limit_up_10": up10[:30],
        "limit_down": down[:20],
        "tech_sectors": tech,
    }


def collect_us_market() -> dict:
    """采集美股数据"""
    today = dt.date.today().isoformat()
    idx = fetch_us_index()
    stocks = fetch_us_stocks()
    return {
        "date": today,
        "market": "美股",
        "indexes": idx,
        "stocks": stocks,
    }


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="每日复盘数据采集")
    parser.add_argument("--market", choices=["A股", "美股"], required=True)
    args = parser.parse_args()

    if args.market == "A股":
        data = collect_a_share()
    else:
        data = collect_us_market()

    print(json.dumps(data, ensure_ascii=False, indent=2))
