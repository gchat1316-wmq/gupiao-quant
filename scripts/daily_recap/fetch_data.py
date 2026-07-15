#!/usr/bin/env python3
"""A股 / 美股每日复盘数据采集。

数据源三层 fallback 优先级：

- INDEX / US：akshare (primary) → tencent qt.gtimg.cn (backup) → eastmoney push2 (last resort)
- SECTOR / LIMIT-UP / LIMIT-DOWN：akshare (primary, 唯一可替代) → eastmoney push2 (last resort)
- SECTOR-BY-NAME：akshare → eastmoney

stdout 仅输出最终 JSON；警告 / debug 全部走 logger → stderr。
"""

from __future__ import annotations

import datetime as dt
import json
import logging
import sys
import time
from dataclasses import dataclass
from typing import Any, Callable, TypeVar

import requests

# ── logger ───────────────────────────────────────────────────────────────────
# 命名 logger（"daily_recap"），靠 propagate 复用到 root。
# 不在 import 时挂 handler —— 避免与 main() 里的 basicConfig 重复。
# 测试通过 unittest.assertLogs("daily_recap", ...) 捕获。
logger = logging.getLogger("daily_recap")

# akshare 是可选项：未安装时所有 _akshare_* 函数返回 []，自动走 fallback。
# 注意：import 放在函数内（lazy import）以便测试时 mock sys.modules['akshare'] 能生效。

# ── 常量 ─────────────────────────────────────────────────────────────────────

EM_BASE = "https://push2.eastmoney.com/api/qt/clist/get"
EM_HEADERS = {"User-Agent": "Mozilla/5.0", "Referer": "https://quote.eastmoney.com/"}

TENCENT_QUOTE_URL = "https://qt.gtimg.cn/q="

A_SHARE_INDEX_TARGETS: dict[str, str] = {
    "上证指数": "sh000001",
    "深证成指": "sz399001",
    "沪深300": "sh000300",
    "创业板指": "sz399006",
    "上证50": "sh000016",
    "科创50": "sh000688",
}

US_INDICES: list[tuple[str, str, str]] = [
    ("IXIC", "usIXIC", "纳指"),
    ("SPX", "usSPX", "标普500"),
    ("DJI", "usDJI", "道指"),
    ("NDX", "usNDX", "纳指100"),
    ("SOX", "usSOX", "费半"),
]

US_STOCKS: list[tuple[str, str, str]] = [
    # (display symbol, tencent code "us" + 大写 ticker, 中文名)
    ("NVDA", "usNVDA", "英伟达"),
    ("AAPL", "usAAPL", "苹果"),
    ("MSFT", "usMSFT", "微软"),
    ("INTC", "usINTC", "英特尔"),
    ("MU", "usMU", "美光"),
    ("TSLA", "usTSLA", "特斯拉"),
    ("AMZN", "usAMZN", "亚马逊"),
    ("GOOGL", "usGOOGL", "谷歌"),
]


# ── DTO ──────────────────────────────────────────────────────────────────────

@dataclass(frozen=True)
class IndexQuote:
    name: str
    code: str
    close: float
    change_pct: float


@dataclass(frozen=True)
class SectorQuote:
    name: str
    change_pct: float
    code: str = ""


@dataclass(frozen=True)
class LimitUpStock:
    name: str
    code: str
    close: float
    change_pct: float
    reason: str = ""


# ── 工具：重试（镜像 scripts/baostock_client.py:86-97） ─────────────────────

T = TypeVar("T")


def _retry(call: Callable[[], T], *, attempts: int = 3, delay: float = 1.0,
           label: str = "request") -> T:
    """捕获 Exception 重试 N 次,最后一次失败时抛出。"""
    last_err: Exception | None = None
    for i in range(attempts):
        try:
            return call()
        except Exception as e:
            last_err = e
            logger.warning("%s retry %d/%d after error: %s", label, i + 1, attempts, e)
            if i + 1 < attempts:
                time.sleep(delay)
    assert last_err is not None
    raise last_err


# ── 通用：东方财富 push2/clist/get ───────────────────────────────────────────

def _em_clist(fs: str, fid: str = "f3", po: int = 1, pn: int = 1, pz: int = 20,
              fields: str = "f2,f3,f4,f12,f14,f15,f16,f17,f18,f20,f21") -> list[dict]:
    """通用东方财富板块/clist 请求。失败返回空 list,不抛错（让 fallback 链兜底）。"""
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
        return data.get("data", {}).get("diff", []) or []
    except Exception as e:
        logger.warning("EM clist error: %s", e)
        return []


# ════════════════════════════════════════════════════════════════════════════
# A 股指数
# ════════════════════════════════════════════════════════════════════════════

def _a_share_index_akshare() -> list[IndexQuote]:
    """ak.stock_zh_index_spot_em() 全市场指数,过滤目标 6 个。"""
    try:
        import akshare as ak  # type: ignore  # noqa: PLC0415 (lazy import)
    except ImportError:
        return []
    try:
        df = _retry(lambda: ak.stock_zh_index_spot_em(),
                    label="ak.stock_zh_index_spot_em")
        out: list[IndexQuote] = []
        for _, row in df.iterrows():
            name = str(row.get("名称", ""))
            code = A_SHARE_INDEX_TARGETS.get(name)
            if not code:
                continue
            try:
                out.append(IndexQuote(
                    name=name, code=code,
                    close=float(row.get("最新价", 0) or 0),
                    change_pct=float(row.get("涨跌幅", 0) or 0),
                ))
            except (TypeError, ValueError):
                continue
        return out
    except Exception as e:
        logger.warning("akshare a-share index failed: %s", e)
        return []


def _a_share_index_tencent() -> list[IndexQuote]:
    """腾讯 qt.gtimg.cn/q= 批量取指数实时报价。GBK 编码。"""
    targets: list[tuple[str, str]] = [
        ("sh000001", "上证指数"), ("sz399001", "深证成指"),
        ("sh000300", "沪深300"),   ("sz399006", "创业板指"),
        ("sh000016", "上证50"),    ("sh000688", "科创50"),
        ("sz399005", "中小100"),
    ]
    codes = ",".join(c for c, _ in targets)
    out: list[IndexQuote] = []
    try:
        resp = _retry(lambda: requests.get(TENCENT_QUOTE_URL + codes, timeout=8),
                      label=f"tencent index batch")
        resp.encoding = "gbk"
        for line in resp.text.split("\n"):
            line = line.strip()
            if not line.startswith("v_") or "=" not in line:
                continue
            code_raw, payload = line[2:].split("=", 1)
            payload = payload.strip().strip('"')
            parts = payload.split("~")
            if len(parts) < 5:
                continue
            name = parts[1] if len(parts) > 1 else ""
            try:
                close = float(parts[3]) if parts[3] else 0.0
                prev = float(parts[4]) if parts[4] else close
                pct = round((close - prev) / prev * 100, 4) if prev else 0.0
            except ValueError:
                continue
            out.append(IndexQuote(name=name or code_raw, code=code_raw,
                                  close=close, change_pct=pct))
    except Exception as e:
        logger.warning("tencent a-share index batch failed: %s", e)
    return out


def _a_share_index_eastmoney() -> list[IndexQuote]:
    """eastmoney push2 拉指数行情（last resort）。"""
    items = _em_clist("m:1+s:2,m:0+t:5", fid="f3", po=1, pn=1, pz=20,
                      fields="f2,f3,f12,f14")
    out: list[IndexQuote] = []
    for it in items:
        name = str(it.get("f14", ""))
        code = str(it.get("f12", ""))
        if not name:
            continue
        try:
            out.append(IndexQuote(
                name=name, code=code,
                close=float(it.get("f2", 0) or 0),
                change_pct=float(it.get("f3", 0) or 0),
            ))
        except (TypeError, ValueError):
            continue
    return out


def fetch_a_share_index() -> list[dict[str, Any]]:
    """A 股指数：akshare → tencent → eastmoney。"""
    sources = (
        ("akshare", _a_share_index_akshare),
        ("tencent", _a_share_index_tencent),
        ("eastmoney", _a_share_index_eastmoney),
    )
    for label, src in sources:
        rows = src()
        if rows:
            logger.info("a-share index: using %s (%d rows)", label, len(rows))
            return [r.__dict__ for r in rows]
    logger.error("a-share index: all three sources failed")
    return []


# ════════════════════════════════════════════════════════════════════════════
# 板块 top / bottom
# ════════════════════════════════════════════════════════════════════════════

def _sector_akshare(n: int, *, ascending: bool) -> list[SectorQuote]:
    """ak.stock_sector_spot(indicator='新浪行业') — 单次拉全量 + 手动排序。

    注：akshare 1.18.64 中 indicator='涨幅'|'跌幅'|'行业板块'|'概念板块'
    会触发 UnboundLocalError（'r' 局部变量未绑定），不可用。
    """
    try:
        import akshare as ak  # type: ignore  # noqa: PLC0415
    except ImportError:
        return []
    try:
        df = _retry(lambda: ak.stock_sector_spot(indicator="新浪行业"),
                    label="ak.stock_sector_spot 新浪行业")
        sorted_df = df.sort_values("涨跌幅", ascending=ascending).head(n)
        return [
            SectorQuote(
                name=str(row.get("板块", "")),
                change_pct=float(row.get("涨跌幅", 0) or 0),
                code=str(row.get("label", "")),
            )
            for _, row in sorted_df.iterrows()
        ]
    except Exception as e:
        logger.warning("akshare sector %s failed: %s",
                       "bottom" if ascending else "top", e)
        return []


def _sector_top_akshare(n: int = 15) -> list[SectorQuote]:
    return _sector_akshare(n, ascending=False)


def _sector_bottom_akshare(n: int = 10) -> list[SectorQuote]:
    return _sector_akshare(n, ascending=True)


def _sector_top_eastmoney(n: int) -> list[SectorQuote]:
    items = _em_clist("m:90 t:2 f:!50", fid="f3", po=1, pn=1, pz=n)
    return [
        SectorQuote(name=str(it.get("f14", "")), change_pct=float(it.get("f3", 0) or 0),
                    code=str(it.get("f12", "")))
        for it in items if it.get("f14")
    ]


def _sector_bottom_eastmoney(n: int) -> list[SectorQuote]:
    items = _em_clist("m:90 t:2 f:!50", fid="f3", po=0, pn=1, pz=n)
    return [
        SectorQuote(name=str(it.get("f14", "")), change_pct=float(it.get("f3", 0) or 0),
                    code=str(it.get("f12", "")))
        for it in items if it.get("f14")
    ]


def fetch_sector_top(n: int = 15) -> list[dict[str, Any]]:
    rows = _sector_top_akshare(n) or _sector_top_eastmoney(n)
    return [r.__dict__ for r in rows]


def fetch_sector_bottom(n: int = 10) -> list[dict[str, Any]]:
    rows = _sector_bottom_akshare(n) or _sector_bottom_eastmoney(n)
    return [r.__dict__ for r in rows]


# ════════════════════════════════════════════════════════════════════════════
# 板块按名模糊匹配
# ════════════════════════════════════════════════════════════════════════════

def _sector_by_names_akshare(names: list[str]) -> list[SectorQuote]:
    """ak.stock_board_industry_name_em() 列出全部行业板块 → 模糊匹配 names。

    注：该函数从 akshare 1.18.x 开始走 HTTP 拉取（已确认在本机网络会
    RemoteDisconnected），失败时返回 [] 让 eastmoney 兜底。
    """
    if not names:
        return []
    try:
        import akshare as ak  # type: ignore  # noqa: PLC0415
    except ImportError:
        return []
    try:
        df = _retry(lambda: ak.stock_board_industry_name_em(),
                    label="ak.stock_board_industry_name_em")
        lowered = [n.lower() for n in names]
        out: list[SectorQuote] = []
        for _, row in df.iterrows():
            name = str(row.get("板块名称", ""))
            if not name:
                continue
            if any(n in name.lower() for n in lowered):
                out.append(SectorQuote(
                    name=name,
                    change_pct=float(row.get("涨跌幅", 0) or 0),
                    code=str(row.get("板块代码", "")),
                ))
        return out
    except Exception as e:
        logger.warning("akshare sector by names failed: %s", e)
        return []


def _sector_by_names_eastmoney(names: list[str]) -> list[SectorQuote]:
    items = _em_clist("m:90 t:2 f:!50", fid="f3", po=1, pn=1, pz=4000)
    out: list[SectorQuote] = []
    if not names:
        return out
    lowered = [n.lower() for n in names]
    for it in items:
        name = str(it.get("f14", "")) or ""
        if any(n in name.lower() for n in lowered):
            out.append(SectorQuote(
                name=name,
                change_pct=float(it.get("f3", 0) or 0),
                code=str(it.get("f12", "")),
            ))
    return out


def fetch_sector_by_names(names: list[str]) -> list[dict[str, Any]]:
    rows = _sector_by_names_akshare(names) or _sector_by_names_eastmoney(names)
    return [r.__dict__ for r in rows]


# ════════════════════════════════════════════════════════════════════════════
# 涨停 / 跌停股池
# ════════════════════════════════════════════════════════════════════════════

def _limit_up_akshare(n: int = 100) -> list[LimitUpStock]:
    """ak.stock_zt_pool_em(date=今天 YYYYMMDD)。"""
    try:
        import akshare as ak  # type: ignore  # noqa: PLC0415
    except ImportError:
        return []
    today = dt.date.today().strftime("%Y%m%d")
    try:
        df = _retry(lambda: ak.stock_zt_pool_em(date=today),
                    label=f"ak.stock_zt_pool_em {today}")
        out: list[LimitUpStock] = []
        for _, row in df.head(n).iterrows():
            try:
                out.append(LimitUpStock(
                    name=str(row.get("名称", "")),
                    code=str(row.get("代码", "")),
                    close=float(row.get("最新价", 0) or 0),
                    change_pct=float(row.get("涨幅%", 0) or 0),
                    reason=str(row.get("涨停原因", "")),
                ))
            except (TypeError, ValueError):
                continue
        return out
    except Exception as e:
        logger.warning("akshare limit-up pool failed: %s", e)
        return []


def _limit_down_akshare(n: int = 100) -> list[LimitUpStock]:
    """ak.stock_zt_pool_dtgc_em(date=今天 YYYYMMDD)。

    注：该函数返回列只有「涨跌幅」没有「跌幅%」，change_pct 取涨跌幅即可。
    """
    try:
        import akshare as ak  # type: ignore  # noqa: PLC0415
    except ImportError:
        return []
    today = dt.date.today().strftime("%Y%m%d")
    try:
        df = _retry(lambda: ak.stock_zt_pool_dtgc_em(date=today),
                    label=f"ak.stock_zt_pool_dtgc_em {today}")
        out: list[LimitUpStock] = []
        for _, row in df.head(n).iterrows():
            try:
                out.append(LimitUpStock(
                    name=str(row.get("名称", "")),
                    code=str(row.get("代码", "")),
                    close=float(row.get("最新价", 0) or 0),
                    change_pct=float(row.get("涨跌幅", 0) or 0),
                    reason="",
                ))
            except (TypeError, ValueError):
                continue
        return out
    except Exception as e:
        logger.warning("akshare limit-down pool failed: %s", e)
        return []


def _limit_up_eastmoney(threshold: float) -> list[LimitUpStock]:
    fs = "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23,m:0+t:81+s:2048"
    items = _em_clist(fs, fid="f3", po=1, pn=1, pz=100,
                      fields="f2,f3,f12,f14,f15,f16,f17,f18,f20,f21")
    out: list[LimitUpStock] = []
    for it in items:
        try:
            pct = float(it.get("f3", 0) or 0)
        except (TypeError, ValueError):
            continue
        if pct >= threshold:
            try:
                out.append(LimitUpStock(
                    name=str(it.get("f14", "")),
                    code=str(it.get("f12", "")),
                    close=float(it.get("f2", 0) or 0),
                    change_pct=pct,
                    reason="",
                ))
            except (TypeError, ValueError):
                continue
    return out


def _limit_down_eastmoney(threshold: float) -> list[LimitUpStock]:
    fs = "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23,m:0+t:81+s:2048"
    items = _em_clist(fs, fid="f3", po=0, pn=1, pz=100,
                      fields="f2,f3,f12,f14,f15,f16,f17,f18,f20,f21")
    out: list[LimitUpStock] = []
    for it in items:
        try:
            pct = float(it.get("f3", 0) or 0)
        except (TypeError, ValueError):
            continue
        if pct <= threshold:
            try:
                out.append(LimitUpStock(
                    name=str(it.get("f14", "")),
                    code=str(it.get("f12", "")),
                    close=float(it.get("f2", 0) or 0),
                    change_pct=pct,
                    reason="",
                ))
            except (TypeError, ValueError):
                continue
    return out


def fetch_limit_up_pool(threshold: float = 9.5) -> list[dict[str, Any]]:
    """akshare 一次性拉全量（不限阈值）；akshare 失败时 eastmoney 按 threshold 过滤。"""
    rows = _limit_up_akshare(200)
    if not rows:
        rows = _limit_up_eastmoney(threshold)
    return [r.__dict__ for r in rows]


def fetch_limit_down_pool(threshold: float = -9.5) -> list[dict[str, Any]]:
    rows = _limit_down_akshare(200)
    if not rows:
        rows = _limit_down_eastmoney(threshold)
    return [r.__dict__ for r in rows]


# ════════════════════════════════════════════════════════════════════════════
# 美股指数 / 个股
# ════════════════════════════════════════════════════════════════════════════

def _us_index_tencent() -> list[IndexQuote]:
    """腾讯 qt.gtimg.cn 拉美股指数。akshare 没有等价接口,tencent 是唯一源。"""
    codes = ",".join(code for _, code, _ in US_INDICES)
    name_map = {code.lower(): name for _, code, name in US_INDICES}
    out: list[IndexQuote] = []
    try:
        resp = _retry(lambda: requests.get(TENCENT_QUOTE_URL + codes, timeout=8),
                      label="tencent US index")
        resp.encoding = "gbk"
        for line in resp.text.split("\n"):
            line = line.strip()
            if not line or "none_match" in line or "=" not in line:
                continue
            code_raw, payload = line.split("=", 1)
            payload = payload.strip().strip('"')
            parts = payload.split("~")
            if len(parts) <= 4:
                continue
            try:
                close = float(parts[3]) if parts[3] else 0.0
                prev = float(parts[4]) if parts[4] else close
                pct = round((close - prev) / prev * 100, 2) if prev else 0.0
            except ValueError:
                continue
            sym = code_raw.lower().replace("v_", "")
            name = name_map.get(sym, parts[1] if len(parts) > 1 else sym)
            out.append(IndexQuote(name=name, code=sym.upper(), close=close, change_pct=pct))
    except Exception as e:
        logger.warning("tencent US index failed: %s", e)
    return out


def fetch_us_index() -> list[dict[str, Any]]:
    """美股指数：tencent 唯一源（akshare 无对应接口）。"""
    rows = _us_index_tencent()
    if not rows:
        logger.error("US index: tencent failed (akshare has no equivalent)")
    return [r.__dict__ for r in rows]


def _us_stocks_tencent() -> list[IndexQuote]:
    """腾讯 qt.gtimg.cn 拉美股个股。akshare stock_us_spot_em 在本机网络下不可达。"""
    codes = ",".join(code for _, code, _ in US_STOCKS)
    name_map = {code.lower().replace("us", ""): name for _, code, name in US_STOCKS}
    out: list[IndexQuote] = []
    try:
        resp = _retry(lambda: requests.get(TENCENT_QUOTE_URL + codes, timeout=8),
                      label="tencent US stocks")
        resp.encoding = "gbk"
        for line in resp.text.split("\n"):
            line = line.strip()
            if not line or "none_match" in line or "=" not in line:
                continue
            code_raw, payload = line.split("=", 1)
            payload = payload.strip().strip('"')
            parts = payload.split("~")
            if len(parts) <= 4:
                continue
            try:
                close = float(parts[3]) if parts[3] else 0.0
                prev = float(parts[4]) if parts[4] else close
                pct = round((close - prev) / prev * 100, 2) if prev else 0.0
            except ValueError:
                continue
            sym = code_raw.lower().replace("v_", "").replace("us", "").upper()
            name = name_map.get(sym.lower(), parts[1] if len(parts) > 1 else sym) or sym
            out.append(IndexQuote(name=name, code=sym, close=close, change_pct=pct))
    except Exception as e:
        logger.warning("tencent US stocks failed: %s", e)
    return out


def fetch_us_stocks() -> list[dict[str, Any]]:
    """美股个股：tencent 唯一源（akshare stock_us_spot_em 不可达）。"""
    rows = _us_stocks_tencent()
    if not rows:
        logger.error("US stocks: tencent failed")
    return [r.__dict__ for r in rows]


# ════════════════════════════════════════════════════════════════════════════
# 主采集函数
# ════════════════════════════════════════════════════════════════════════════

def collect_a_share() -> dict[str, Any]:
    """采集 A 股全量数据。"""
    today = dt.date.today().isoformat()
    return {
        "date": today,
        "market": "A股",
        "indexes": fetch_a_share_index(),
        "top_sectors": fetch_sector_top(15),
        "bottom_sectors": fetch_sector_bottom(10),
        "limit_up_20": fetch_limit_up_pool(19.5)[:15],
        "limit_up_10": fetch_limit_up_pool(9.5)[:30],
        "limit_down": fetch_limit_down_pool(-9.5)[:20],
        "tech_sectors": fetch_sector_by_names(["PCB", "光模块", "半导体", "元件", "通信设备"]),
    }


def collect_us_market() -> dict[str, Any]:
    """采集美股数据。"""
    today = dt.date.today().isoformat()
    return {
        "date": today,
        "market": "美股",
        "indexes": fetch_us_index(),
        "stocks": fetch_us_stocks(),
    }


# ════════════════════════════════════════════════════════════════════════════
# CLI 入口
# ════════════════════════════════════════════════════════════════════════════

def main() -> int:
    logging.basicConfig(level=logging.INFO,
                        format="%(asctime)s [%(levelname)s] %(message)s",
                        stream=sys.stderr)
    parser_args = _build_argparser().parse_args()
    data = (collect_a_share() if parser_args.market == "A股"
            else collect_us_market())
    json.dump(data, sys.stdout, ensure_ascii=False, indent=2)
    sys.stdout.write("\n")
    return 0


def _build_argparser():
    import argparse
    p = argparse.ArgumentParser(description="每日复盘数据采集")
    p.add_argument("--market", choices=["A股", "美股"], required=True)
    return p


if __name__ == "__main__":
    sys.exit(main())