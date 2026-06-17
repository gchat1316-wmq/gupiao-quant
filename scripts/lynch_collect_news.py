#!/usr/bin/env python3
from __future__ import annotations

import json
import math
import random
import re
import sys
import time
import uuid
import warnings
from datetime import datetime

warnings.filterwarnings("ignore")

import requests

UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36"
EM_SESSION = requests.Session()
EM_SESSION.headers.update({"User-Agent": UA})
EM_MIN_INTERVAL = 1.0
_em_last_call = [0.0]


def em_get(url: str, params: dict | None = None, headers: dict | None = None, timeout: int = 15):
    wait = EM_MIN_INTERVAL - (time.time() - _em_last_call[0])
    if wait > 0:
        time.sleep(wait + random.uniform(0.1, 0.4))
    try:
        return EM_SESSION.get(url, params=params, headers=headers, timeout=timeout)
    finally:
        _em_last_call[0] = time.time()


def eastmoney_stock_news(code: str, page_size: int = 12) -> list[dict]:
    url = "https://search-api-web.eastmoney.com/search/jsonp"
    inner = json.dumps({
        "uid": "", "keyword": code, "type": ["cmsArticleWebOld"],
        "client": "web", "clientType": "web", "clientVersion": "curr",
        "param": {"cmsArticleWebOld": {"searchScope": "default", "sort": "default",
                  "pageIndex": 1, "pageSize": page_size, "preTag": "", "postTag": ""}},
    }, separators=(",", ":"))
    r = em_get(url, params={"cb": "jQuery_news", "param": inner},
               headers={"User-Agent": UA, "Referer": "https://so.eastmoney.com/"}, timeout=15)
    text = r.text
    data = json.loads(text[text.index("(") + 1:text.rindex(")")])
    rows = []
    for a in data.get("result", {}).get("cmsArticleWebOld", []) or []:
        rows.append({
            "title": re.sub(r"<[^>]+>", "", a.get("title", "")),
            "content": re.sub(r"<[^>]+>", "", a.get("content", ""))[:220],
            "time": a.get("date", ""),
            "source": a.get("mediaName", "东财"),
            "url": a.get("url", ""),
        })
    return rows


def eastmoney_global_news(page_size: int = 24) -> list[dict]:
    url = "https://np-weblist.eastmoney.com/comm/web/getFastNewsList"
    params = {
        "client": "web", "biz": "web_724", "fastColumn": "102", "sortEnd": "",
        "pageSize": str(page_size), "req_trace": str(uuid.uuid4()),
    }
    r = em_get(url, params=params,
               headers={"User-Agent": UA, "Referer": "https://kuaixun.eastmoney.com/"}, timeout=12)
    data = r.json()
    rows = []
    for item in data.get("data", {}).get("fastNewsList", []) or []:
        rows.append({
            "title": item.get("title", ""),
            "content": (item.get("summary", "") or "")[:220],
            "time": item.get("showTime", ""),
            "source": "东财·全球资讯",
            "url": "",
        })
    return rows


def _cninfo_ts_to_date(ts) -> str:
    if isinstance(ts, (int, float)):
        return datetime.fromtimestamp(ts / 1000).strftime("%Y-%m-%d")
    return str(ts)[:10] if ts else ""


def cninfo_announcements(code: str, page_size: int = 10) -> list[dict]:
    url = "https://www.cninfo.com.cn/new/hisAnnouncement/query"
    if code.startswith("6"):
        org_id = f"gssh0{code}"
    elif code.startswith("8") or code.startswith("4"):
        org_id = f"gsbj0{code}"
    else:
        org_id = f"gssz0{code}"
    payload = {
        "stock": f"{code},{org_id}", "tabName": "fulltext",
        "pageSize": str(page_size), "pageNum": "1", "column": "", "category": "",
        "plate": "", "seDate": "", "searchkey": "", "secid": "",
        "sortName": "", "sortType": "", "isHLtitle": "true",
    }
    headers = {
        "User-Agent": UA, "Content-Type": "application/x-www-form-urlencoded",
        "Referer": "https://www.cninfo.com.cn/new/disclosure",
        "Origin": "https://www.cninfo.com.cn",
    }
    r = requests.post(url, data=payload, headers=headers, timeout=15)
    rows = []
    for item in r.json().get("announcements", []) or []:
        rows.append({
            "title": item.get("announcementTitle", ""),
            "content": item.get("announcementTypeName", ""),
            "time": _cninfo_ts_to_date(item.get("announcementTime")),
            "source": "巨潮资讯",
            "url": f"https://www.cninfo.com.cn/new/disclosure/detail?annoId={item.get('announcementId', '')}",
        })
    return rows


def sanitize(obj):
    if isinstance(obj, float) and (math.isnan(obj) or math.isinf(obj)):
        return None
    if isinstance(obj, dict):
        return {k: sanitize(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [sanitize(v) for v in obj]
    return obj


def main():
    tickers = []
    if len(sys.argv) > 1 and sys.argv[1].strip():
        tickers = [t.strip() for t in sys.argv[1].split(",") if t.strip()]

    stock_news = []
    announcements = []
    for ticker in tickers:
        try:
            for item in eastmoney_stock_news(ticker):
                item["ticker"] = ticker
                item["category"] = "stock"
                stock_news.append(item)
        except Exception as exc:
            stock_news.append({"ticker": ticker, "category": "stock", "title": f"新闻抓取失败: {exc}", "content": "", "time": "", "source": "system", "url": ""})
        try:
            for item in cninfo_announcements(ticker):
                item["ticker"] = ticker
                item["category"] = "announcement"
                announcements.append(item)
        except Exception:
            pass

    market_news = []
    try:
        for item in eastmoney_global_news():
            item["category"] = "market"
            market_news.append(item)
    except Exception as exc:
        market_news.append({"category": "market", "title": f"市场快讯抓取失败: {exc}", "content": "", "time": "", "source": "system", "url": ""})

    result = {
        "collected_at": datetime.now().isoformat(),
        "stock_news": stock_news,
        "announcements": announcements,
        "market_news": market_news,
    }
    print(json.dumps(sanitize(result), ensure_ascii=False))


if __name__ == "__main__":
    main()
