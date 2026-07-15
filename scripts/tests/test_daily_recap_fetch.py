"""Unit tests for daily_recap.fetch_data.

Run from project root:
    python3 scripts/tests/test_daily_recap_fetch.py

Stdlib only (no pytest required).
"""
from __future__ import annotations

import io
import logging
import re
import sys
import unittest
from pathlib import Path
from unittest import mock

SCRIPTS_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS_DIR))
sys.path.insert(0, str(SCRIPTS_DIR / "daily_recap"))

import fetch_data  # noqa: E402


class _FakeIndexDf:
    """Mimic pandas.DataFrame.head(N).iterrows() for akshare mocks."""

    def __init__(self, rows: list[dict]) -> None:
        self._rows = rows

    def iterrows(self):
        for i, row in enumerate(self._rows):
            yield i, row

    def head(self, n: int):
        return _FakeIndexDf(self._rows[:n])

    def sort_values(self, col: str, ascending: bool = True):
        return _FakeIndexDf(
            sorted(self._rows, key=lambda r: r.get(col, 0), reverse=not ascending)
        )


def _ak_index_df() -> _FakeIndexDf:
    return _FakeIndexDf([
        {"名称": "上证指数", "最新价": 3955.58, "涨跌幅": 0.29},
        {"名称": "深证成指", "最新价": 12500.0, "涨跌幅": 0.5},
        {"名称": "创业板指", "最新价": 2400.0, "涨跌幅": 1.2},
        {"名称": "沪深300", "最新价": 4500.0, "涨跌幅": 0.3},
        {"名称": "上证50", "最新价": 3000.0, "涨跌幅": 0.1},
        {"名称": "科创50", "最新价": 900.0, "涨跌幅": -0.2},
    ])


def _ak_sector_df() -> _FakeIndexDf:
    """ak.stock_sector_spot(indicator='新浪行业') shape: 板块 / 涨跌幅."""
    return _FakeIndexDf([
        {"板块": "半导体", "涨跌幅": 5.2},
        {"板块": "PCB", "涨跌幅": 4.1},
        {"板块": "玻璃行业", "涨跌幅": -3.1},
        {"板块": "新能源", "涨跌幅": 1.5},
    ])


def _ak_zt_pool_df() -> _FakeIndexDf:
    return _FakeIndexDf([
        {"名称": "赛腾股份", "代码": "603283", "最新价": 50.0,
         "涨跌幅": 10.0, "涨停原因": "AI 算力"},
        {"名称": "中科曙光", "代码": "603019", "最新价": 80.0,
         "涨跌幅": 10.0, "涨停原因": "国产芯片"},
    ])


def _ak_dtgc_pool_df() -> _FakeIndexDf:
    return _FakeIndexDf([
        {"名称": "贵绳股份", "代码": "600992", "最新价": 10.55, "涨跌幅": -9.98},
        {"名称": "跌停B", "代码": "688888", "最新价": 5.0, "涨跌幅": -10.0},
    ])


class RetryTest(unittest.TestCase):
    def test_succeeds_after_two_failures(self) -> None:
        calls = {"n": 0}

        def flaky():
            calls["n"] += 1
            if calls["n"] < 3:
                raise ConnectionError(f"boom {calls['n']}")
            return "ok"

        with self.assertLogs("daily_recap", level="WARNING") as cm:
            result = fetch_data._retry(flaky, attempts=3, delay=0, label="t")

        self.assertEqual(result, "ok")
        self.assertEqual(calls["n"], 3)
        self.assertTrue(any("retry 1/3" in line for line in cm.output))
        self.assertTrue(any("retry 2/3" in line for line in cm.output))

    def test_raises_after_all_attempts(self) -> None:
        def always_fail():
            raise ValueError("nope")

        with self.assertLogs("daily_recap", level="WARNING"):
            with self.assertRaises(ValueError) as ctx:
                fetch_data._retry(always_fail, attempts=3, delay=0)
        self.assertEqual(str(ctx.exception), "nope")


class AShareIndexChainTest(unittest.TestCase):
    """T3 ~ T6: a-share index three-tier fallback chain."""

    def _quotes(self) -> list[fetch_data.IndexQuote]:
        return [
            fetch_data.IndexQuote(name="上证指数", code="sh000001",
                                  close=3955.58, change_pct=0.29),
            fetch_data.IndexQuote(name="创业板指", code="sz399006",
                                  close=2400.0, change_pct=1.2),
        ]

    @mock.patch("fetch_data._a_share_index_eastmoney")
    @mock.patch("fetch_data._a_share_index_tencent")
    @mock.patch("fetch_data._a_share_index_akshare")
    def test_akshare_primary(self, m_ak, m_tx, m_em) -> None:
        m_ak.return_value = self._quotes()
        with self.assertLogs("daily_recap", level="INFO") as cm:
            result = fetch_data.fetch_a_share_index()
        self.assertEqual(len(result), 2)
        self.assertEqual(result[0]["name"], "上证指数")
        m_tx.assert_not_called()
        m_em.assert_not_called()
        self.assertTrue(any("using akshare" in l for l in cm.output))

    @mock.patch("fetch_data._a_share_index_eastmoney")
    @mock.patch("fetch_data._a_share_index_tencent")
    @mock.patch("fetch_data._a_share_index_akshare")
    def test_falls_back_to_tencent(self, m_ak, m_tx, m_em) -> None:
        m_ak.return_value = []
        m_tx.return_value = [fetch_data.IndexQuote(
            name="上证指数", code="sh000001", close=3955.58, change_pct=0.29)]
        result = fetch_data.fetch_a_share_index()
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["code"], "sh000001")
        m_em.assert_not_called()

    @mock.patch("fetch_data._a_share_index_eastmoney")
    @mock.patch("fetch_data._a_share_index_tencent")
    @mock.patch("fetch_data._a_share_index_akshare")
    def test_falls_back_to_eastmoney(self, m_ak, m_tx, m_em) -> None:
        m_ak.return_value = []
        m_tx.return_value = []
        m_em.return_value = [fetch_data.IndexQuote(
            name="上证指数", code="sh000001", close=3955.58, change_pct=0.29)]
        result = fetch_data.fetch_a_share_index()
        self.assertEqual(len(result), 1)
        m_tx.assert_called_once()

    @mock.patch("fetch_data._a_share_index_eastmoney")
    @mock.patch("fetch_data._a_share_index_tencent")
    @mock.patch("fetch_data._a_share_index_akshare")
    def test_all_fail_returns_empty(self, m_ak, m_tx, m_em) -> None:
        m_ak.return_value = []
        m_tx.return_value = []
        m_em.return_value = []
        with self.assertLogs("daily_recap", level="ERROR") as cm:
            result = fetch_data.fetch_a_share_index()
        self.assertEqual(result, [])
        self.assertTrue(any("all three sources failed" in l for l in cm.output))


class AShareIndexAkshareTest(unittest.TestCase):
    """T-int: _a_share_index_akshare real call to akshare (mocked via sys.modules)."""

    def test_returns_filtered_quotes(self) -> None:
        fake_ak = mock.MagicMock()
        fake_ak.stock_zh_index_spot_em.return_value = _ak_index_df()
        with mock.patch.dict(sys.modules, {"akshare": fake_ak}):
            result = fetch_data._a_share_index_akshare()
        # 6 row input, 6 of our names match A_SHARE_INDEX_TARGETS
        self.assertEqual(len(result), 6)
        self.assertTrue(all(isinstance(r, fetch_data.IndexQuote) for r in result))


class SectorChainTest(unittest.TestCase):
    """T7: sector top/bottom akshare-first, eastmoney-fallback."""

    @mock.patch("fetch_data._sector_top_eastmoney")
    @mock.patch("fetch_data._sector_top_akshare")
    def test_top_akshare_primary(self, m_ak, m_em) -> None:
        m_ak.return_value = [fetch_data.SectorQuote(name="半导体", change_pct=5.2)]
        result = fetch_data.fetch_sector_top(15)
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["name"], "半导体")
        m_em.assert_not_called()

    @mock.patch("fetch_data._sector_top_eastmoney")
    @mock.patch("fetch_data._sector_top_akshare")
    def test_top_falls_back_when_akshare_empty(self, m_ak, m_em) -> None:
        m_ak.return_value = []
        m_em.return_value = [fetch_data.SectorQuote(name="医药", change_pct=2.0)]
        result = fetch_data.fetch_sector_top(15)
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["name"], "医药")

    def test_top_akshare_uses_sina_industry_and_sorts_desc(self) -> None:
        """ak.stock_sector_spot(indicator='涨幅'|'跌幅') is broken in 1.18.64.
        Must use '新浪行业' + manual sort_values."""
        fake_ak = mock.MagicMock()
        fake_ak.stock_sector_spot.return_value = _ak_sector_df()
        with mock.patch.dict(sys.modules, {"akshare": fake_ak}):
            result = fetch_data._sector_top_akshare(15)
        self.assertEqual(result[0].name, "半导体")  # highest pct
        self.assertEqual(len(result), 4)
        fake_ak.stock_sector_spot.assert_called_once_with(indicator="新浪行业")

    def test_bottom_akshare_sorts_asc(self) -> None:
        fake_ak = mock.MagicMock()
        fake_ak.stock_sector_spot.return_value = _ak_sector_df()
        with mock.patch.dict(sys.modules, {"akshare": fake_ak}):
            result = fetch_data._sector_bottom_akshare(10)
        self.assertEqual(result[0].name, "玻璃行业")  # most negative


class LimitPoolTest(unittest.TestCase):
    """T8: limit-up and limit-down akshare."""

    @mock.patch("fetch_data._limit_up_eastmoney")
    @mock.patch("fetch_data._limit_up_akshare")
    def test_limit_up_akshare_primary(self, m_ak, m_em) -> None:
        m_ak.return_value = [fetch_data.LimitUpStock(
            name="赛腾股份", code="603283", close=50.0,
            change_pct=10.0, reason="AI 算力")]
        result = fetch_data.fetch_limit_up_pool(9.5)
        self.assertEqual(result[0]["name"], "赛腾股份")
        self.assertEqual(result[0]["reason"], "AI 算力")
        m_em.assert_not_called()

    def test_limit_down_akshare_calls_dtgc_em(self) -> None:
        fake_ak = mock.MagicMock()
        fake_ak.stock_zt_pool_dtgc_em.return_value = _ak_dtgc_pool_df()
        with mock.patch.dict(sys.modules, {"akshare": fake_ak}):
            result = fetch_data._limit_down_akshare(50)
        self.assertEqual(len(result), 2)
        self.assertEqual(result[0].name, "贵绳股份")
        # 仅一个参数 date=YYYYMMDD
        fake_ak.stock_zt_pool_dtgc_em.assert_called_once()
        call_kw = fake_ak.stock_zt_pool_dtgc_em.call_args.kwargs
        self.assertIn("date", call_kw)
        self.assertRegex(call_kw["date"], r"^\d{8}$")

    @mock.patch("fetch_data._limit_down_eastmoney")
    @mock.patch("fetch_data._limit_down_akshare")
    def test_limit_down_falls_back_to_eastmoney(self, m_ak, m_em) -> None:
        m_ak.return_value = []
        m_em.return_value = [fetch_data.LimitUpStock(
            name="A", code="000001", close=1.0, change_pct=-10.0)]
        result = fetch_data.fetch_limit_down_pool(-9.5)
        self.assertEqual(result[0]["name"], "A")


class SectorByNamesTest(unittest.TestCase):
    """T9: _sector_by_names_akshare fuzzy match on industry board list."""

    def test_fuzzy_match_succeeds(self) -> None:
        fake_ak = mock.MagicMock()
        fake_ak.stock_board_industry_name_em.return_value = _FakeIndexDf([
            {"板块名称": "半导体", "涨跌幅": 5.2, "板块代码": "BK1001"},
            {"板块名称": "PCB", "涨跌幅": 4.1, "板块代码": "BK1002"},
            {"板块名称": "银行", "涨跌幅": -0.5, "板块代码": "BK1003"},
        ])
        with mock.patch.dict(sys.modules, {"akshare": fake_ak}):
            result = fetch_data._sector_by_names_akshare(["半导体", "光模块"])
        names = [r.name for r in result]
        self.assertIn("半导体", names)
        self.assertNotIn("银行", names)

    def test_returns_empty_when_akshare_fails(self) -> None:
        fake_ak = mock.MagicMock()
        fake_ak.stock_board_industry_name_em.side_effect = ConnectionError("net")
        with mock.patch.dict(sys.modules, {"akshare": fake_ak}):
            result = fetch_data._sector_by_names_akshare(["半导体"])
        self.assertEqual(result, [])


class UsIndexChainTest(unittest.TestCase):
    """T10: US index uses tencent only (akshare has no equivalent)."""

    @mock.patch("fetch_data._us_index_tencent")
    def test_tencent_only(self, m_tx) -> None:
        m_tx.return_value = [fetch_data.IndexQuote(
            name="纳指", code="IXIC", close=25873.18, change_pct=0.5)]
        result = fetch_data.fetch_us_index()
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["name"], "纳指")
        m_tx.assert_called_once()


class CollectAShareShapeTest(unittest.TestCase):
    """T11: collect_a_share returns dict with all 9 expected keys."""

    def setUp(self) -> None:
        idx = [fetch_data.IndexQuote("上证指数", "sh000001", 3955.58, 0.29)]
        top = [fetch_data.SectorQuote("半导体", 5.2)]
        bot = [fetch_data.SectorQuote("玻璃行业", -3.1)]
        up = [fetch_data.LimitUpStock("A", "000001", 1.0, 10.0, "x")]
        down = [fetch_data.LimitUpStock("B", "000002", 1.0, -10.0, "")]
        tech = [fetch_data.SectorQuote("PCB", 4.1)]
        self._patches = [
            mock.patch("fetch_data.fetch_a_share_index", return_value=[r.__dict__ for r in idx]),
            mock.patch("fetch_data.fetch_sector_top", return_value=[r.__dict__ for r in top]),
            mock.patch("fetch_data.fetch_sector_bottom", return_value=[r.__dict__ for r in bot]),
            mock.patch("fetch_data.fetch_limit_up_pool", return_value=[r.__dict__ for r in up]),
            mock.patch("fetch_data.fetch_limit_down_pool", return_value=[r.__dict__ for r in down]),
            mock.patch("fetch_data.fetch_sector_by_names", return_value=[r.__dict__ for r in tech]),
        ]
        for p in self._patches:
            p.start()

    def tearDown(self) -> None:
        for p in self._patches:
            p.stop()

    def test_dict_shape(self) -> None:
        data = fetch_data.collect_a_share()
        self.assertEqual(data["market"], "A股")
        for key in ("date", "market", "indexes", "top_sectors", "bottom_sectors",
                    "limit_up_20", "limit_up_10", "limit_down", "tech_sectors"):
            self.assertIn(key, data, f"missing key: {key}")

    def test_limit_up_pool_called_with_two_thresholds(self) -> None:
        fetch_data.collect_a_share()
        thresholds = [c.kwargs.get("threshold", c.args[0] if c.args else None)
                      for c in fetch_data.fetch_limit_up_pool.call_args_list]
        # 调用过两次,一次 19.5,一次 9.5
        self.assertEqual(len(thresholds), 2)
        self.assertIn(19.5, thresholds)
        self.assertIn(9.5, thresholds)


class ModuleStyleTest(unittest.TestCase):
    """T12: static checks — no print(..., file=sys.stderr) left in source."""

    def test_no_print_in_fetch_data(self) -> None:
        src_path = Path(fetch_data.__file__)
        text = src_path.read_text(encoding="utf-8")
        # 找所有 print( 出现位置;允许在 __main__ 入口里有 json.dump + sys.stdout.write 替代
        bad = [(i + 1, line) for i, line in enumerate(text.splitlines())
               if re.search(r"\bprint\s*\(", line)]
        self.assertEqual(bad, [],
                         msg=f"fetch_data.py still contains print(): {bad}")

    def test_logger_used_in_module(self) -> None:
        self.assertTrue(hasattr(fetch_data, "logger"))
        self.assertIsInstance(fetch_data.logger, logging.Logger)


class CollectUsMarketShapeTest(unittest.TestCase):
    def test_returns_dict(self) -> None:
        with mock.patch("fetch_data.fetch_us_index", return_value=[]), \
             mock.patch("fetch_data.fetch_us_stocks", return_value=[]):
            data = fetch_data.collect_us_market()
        self.assertEqual(data["market"], "美股")
        self.assertIn("indexes", data)
        self.assertIn("stocks", data)


# ── 补充覆盖：tencent 解析 + eastmoney fallback + CLI 入口 ──────────────────

class TencentIndexTest(unittest.TestCase):
    """覆盖 _a_share_index_tencent 的 qt.gtimg.cn 解析分支。"""

    def _gbk_response(self, body: str) -> mock.Mock:
        r = mock.MagicMock()
        r.encoding = "gbk"
        r.text = body
        return r

    def test_parses_valid_lines(self) -> None:
        body = (
            'v_sh000001="1~上证指数~000001~3955.58~3967.13~3963.73~rest~"\n'
            'v_sz399006="0~创业板指~399006~2400.0~2390.0~2395.0~rest~"\n'
        )
        r = self._gbk_response(body)
        with mock.patch("fetch_data.requests.get", return_value=r):
            result = fetch_data._a_share_index_tencent()
        self.assertEqual(len(result), 2)
        self.assertEqual(result[0].code, "sh000001")
        self.assertEqual(result[1].name, "创业板指")

    def test_skips_malformed_lines(self) -> None:
        body = 'v_sh000001="malformed"\nbad_line_no_equals\nv_sh000002=""\n'
        r = self._gbk_response(body)
        with mock.patch("fetch_data.requests.get", return_value=r):
            result = fetch_data._a_share_index_tencent()
        self.assertEqual(result, [])

    def test_handles_network_error(self) -> None:
        with mock.patch("fetch_data.requests.get",
                        side_effect=ConnectionError("net")):
            with self.assertLogs("daily_recap", level="WARNING"):
                result = fetch_data._a_share_index_tencent()
        self.assertEqual(result, [])

    def test_skips_value_error_on_close_field(self) -> None:
        # parts[3] 不是数字 → ValueError 分支
        body = 'v_sh000001="1~上证指数~000001~not_a_num~3967.13~rest~"\n'
        r = self._gbk_response(body)
        with mock.patch("fetch_data.requests.get", return_value=r):
            result = fetch_data._a_share_index_tencent()
        self.assertEqual(result, [])


class AkshareEdgeCaseTest(unittest.TestCase):
    """覆盖 akshare 路径里的防御性 continue 分支。"""

    def test_a_share_index_skips_unknown_name(self) -> None:
        """name 不在 A_SHARE_INDEX_TARGETS → continue (line 161)."""
        df = _FakeIndexDf([
            {"名称": "未知指数", "最新价": 1.0, "涨跌幅": 0.5},
            {"名称": "上证指数", "最新价": 3955.58, "涨跌幅": 0.29},
        ])
        fake_ak = mock.MagicMock()
        fake_ak.stock_zh_index_spot_em.return_value = df
        with mock.patch.dict(sys.modules, {"akshare": fake_ak}):
            result = fetch_data._a_share_index_akshare()
        names = [r.name for r in result]
        self.assertEqual(names, ["上证指数"])

    def test_a_share_index_skips_bad_numeric_row(self) -> None:
        """row 的最新价是字符串 → TypeError → continue (line 168-169)."""
        df = _FakeIndexDf([
            {"名称": "上证指数", "最新价": "not-a-number", "涨跌幅": 0.1},
        ])
        fake_ak = mock.MagicMock()
        fake_ak.stock_zh_index_spot_em.return_value = df
        with mock.patch.dict(sys.modules, {"akshare": fake_ak}):
            result = fetch_data._a_share_index_akshare()
        self.assertEqual(result, [])

    def test_a_share_index_no_akshare(self) -> None:
        """akshare 不在 sys.modules → ImportError → 返回 [] (line 151-152)."""
        with mock.patch.dict(sys.modules, {"akshare": None}):
            result = fetch_data._a_share_index_akshare()
        self.assertEqual(result, [])


class EastmoneyFallbackTest(unittest.TestCase):
    """覆盖 _xxx_eastmoney 路径在 akshare 失败时被调用。"""

    def test_a_share_index_eastmoney_parses(self) -> None:
        with mock.patch("fetch_data._em_clist",
                        return_value=[{"f14": "上证指数", "f12": "000001",
                                       "f2": 3955.58, "f3": 0.29}]):
            result = fetch_data._a_share_index_eastmoney()
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0].name, "上证指数")

    def test_sector_top_eastmoney(self) -> None:
        with mock.patch("fetch_data._em_clist",
                        return_value=[{"f14": "半导体", "f3": 5.2, "f12": "BK1001"}]):
            result = fetch_data._sector_top_eastmoney(15)
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0].name, "半导体")
        self.assertEqual(result[0].code, "BK1001")

    def test_sector_bottom_eastmoney(self) -> None:
        with mock.patch("fetch_data._em_clist",
                        return_value=[{"f14": "银行", "f3": -1.0, "f12": "BK2002"}]):
            result = fetch_data._sector_bottom_eastmoney(10)
        self.assertEqual(result[0].name, "银行")

    def test_sector_by_names_eastmoney(self) -> None:
        with mock.patch("fetch_data._em_clist",
                        return_value=[{"f14": "半导体", "f3": 5.2, "f12": "BK1001"},
                                      {"f14": "银行", "f3": -1.0, "f12": "BK2002"}]):
            result = fetch_data._sector_by_names_eastmoney(["半导体"])
        names = [r.name for r in result]
        self.assertEqual(names, ["半导体"])

    def test_limit_up_eastmoney_filters_by_threshold(self) -> None:
        with mock.patch("fetch_data._em_clist",
                        return_value=[{"f14": "A", "f12": "000001", "f2": 1.0, "f3": 10.5},
                                      {"f14": "B", "f12": "000002", "f2": 2.0, "f3": 5.0}]):
            result = fetch_data._limit_up_eastmoney(9.5)
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0].name, "A")

    def test_limit_down_eastmoney_filters_by_threshold(self) -> None:
        with mock.patch("fetch_data._em_clist",
                        return_value=[{"f14": "X", "f12": "600001", "f2": 1.0, "f3": -10.0},
                                      {"f14": "Y", "f12": "600002", "f2": 2.0, "f3": -5.0}]):
            result = fetch_data._limit_down_eastmoney(-9.5)
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0].name, "X")


class UsStockTest(unittest.TestCase):
    """覆盖 _us_index_tencent / _us_stocks_tencent 解析分支。"""

    def _gbk_response(self, body: str) -> mock.Mock:
        r = mock.MagicMock()
        r.encoding = "gbk"
        r.text = body
        return r

    def test_us_index_parses(self) -> None:
        body = (
            'v_usIXIC="200~纳斯达克~.IXIC~25873.18~26281.61~rest~"\n'
            'v_usSPX="200~标普500~.SPX~5234.0~5180.0~rest~"\n'
        )
        with mock.patch("fetch_data.requests.get",
                        return_value=self._gbk_response(body)):
            result = fetch_data._us_index_tencent()
        names = [r.name for r in result]
        self.assertIn("纳指", names)
        self.assertIn("标普500", names)

    def test_us_stocks_parses(self) -> None:
        body = (
            'v_usnvda="200~英伟达~NVDA~900.0~880.0~rest~"\n'
            'v_usaapl="200~苹果~AAPL~180.0~178.0~rest~"\n'
        )
        with mock.patch("fetch_data.requests.get",
                        return_value=self._gbk_response(body)):
            result = fetch_data._us_stocks_tencent()
        names = [r.name for r in result]
        self.assertIn("英伟达", names)

    def test_us_stocks_handles_error(self) -> None:
        with mock.patch("fetch_data.requests.get",
                        side_effect=ConnectionError("net")):
            with self.assertLogs("daily_recap", level="WARNING"):
                result = fetch_data._us_stocks_tencent()
        self.assertEqual(result, [])


class MainEntryTest(unittest.TestCase):
    """覆盖 CLI 入口 main() / _build_argparser()。"""

    def test_argparser_requires_market(self) -> None:
        with self.assertRaises(SystemExit):
            fetch_data._build_argparser().parse_args([])

    def test_argparser_accepts_a_share(self) -> None:
        args = fetch_data._build_argparser().parse_args(["--market", "A股"])
        self.assertEqual(args.market, "A股")

    def test_main_a_share_prints_json(self) -> None:
        captured = io.StringIO()
        with mock.patch.object(sys, "argv", ["fetch_data.py", "--market", "A股"]), \
             mock.patch.object(sys, "stdout", captured), \
             mock.patch("fetch_data.collect_a_share",
                        return_value={"market": "A股", "x": 1}):
            rc = fetch_data.main()
        self.assertEqual(rc, 0)
        out = captured.getvalue()
        self.assertIn("A股", out)
        self.assertTrue(out.endswith("\n"))

    def test_main_us_market_dispatch(self) -> None:
        captured = io.StringIO()
        with mock.patch.object(sys, "argv", ["fetch_data.py", "--market", "美股"]), \
             mock.patch.object(sys, "stdout", captured), \
             mock.patch("fetch_data.collect_us_market",
                        return_value={"market": "美股", "y": 2}):
            rc = fetch_data.main()
        self.assertEqual(rc, 0)
        self.assertIn("美股", captured.getvalue())


class EmClistTest(unittest.TestCase):
    """覆盖 _em_clist 自身的 JSON 解析与 fallback。"""

    def test_returns_diff_list(self) -> None:
        fake_resp = mock.MagicMock()
        fake_resp.json.return_value = {"data": {"diff": [{"f12": "1", "f14": "x"}]}}
        with mock.patch("fetch_data.requests.get", return_value=fake_resp):
            self.assertEqual(fetch_data._em_clist("m:90"), [{"f12": "1", "f14": "x"}])

    def test_handles_empty_payload(self) -> None:
        fake_resp = mock.MagicMock()
        fake_resp.json.return_value = {"data": {"diff": []}}
        with mock.patch("fetch_data.requests.get", return_value=fake_resp):
            self.assertEqual(fetch_data._em_clist("m:90"), [])

    def test_handles_exception(self) -> None:
        with mock.patch("fetch_data.requests.get",
                        side_effect=ConnectionError("boom")):
            with self.assertLogs("daily_recap", level="WARNING"):
                self.assertEqual(fetch_data._em_clist("m:90"), [])

    def test_handles_missing_diff(self) -> None:
        fake_resp = mock.MagicMock()
        fake_resp.json.return_value = {"data": {}}
        with mock.patch("fetch_data.requests.get", return_value=fake_resp):
            self.assertEqual(fetch_data._em_clist("m:90"), [])


class CollectUsMarketTest(unittest.TestCase):
    def test_returns_expected_keys(self) -> None:
        idx = [fetch_data.IndexQuote("纳指", "IXIC", 25873.18, 0.5)]
        stocks = [fetch_data.IndexQuote("英伟达", "NVDA", 900.0, 1.0)]
        with mock.patch("fetch_data.fetch_us_index",
                        return_value=[r.__dict__ for r in idx]), \
             mock.patch("fetch_data.fetch_us_stocks",
                        return_value=[r.__dict__ for r in stocks]):
            data = fetch_data.collect_us_market()
        self.assertEqual(data["market"], "美股")
        self.assertEqual(data["indexes"][0]["name"], "纳指")
        self.assertEqual(data["stocks"][0]["code"], "NVDA")


if __name__ == "__main__":
    unittest.main(verbosity=2)