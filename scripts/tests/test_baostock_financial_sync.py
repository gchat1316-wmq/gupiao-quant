"""Unit tests for baostock_financial_sync.

Run from project root:
    python3 scripts/tests/test_baostock_financial_sync.py

Stdlib only (no pytest required).
"""

from __future__ import annotations

import sys
import unittest
from datetime import date
from decimal import Decimal
from pathlib import Path

# Make the sibling script importable when running from project root.
SCRIPTS_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS_DIR))

from baostock_financial_sync import (  # noqa: E402  (intentional path tweak)
    build_record,
    pct_from_ratio,
    parse_decimal_or_none,
    upsert_sql,
    upsert_row,
)


def _profit(extra: dict | None = None) -> dict:
    base = {
        "code": "sz.301696",
        "statDate": "2025-12-31",
        "roeAvg": "0.437293",
        "npMargin": "0.393744",
        "gpMargin": "0.600031",
        "netProfit": "423151074.320000",
        "epsTTM": "1.170466",
    }
    if extra:
        base.update(extra)
    return base


def _growth(extra: dict | None = None) -> dict:
    base = {"code": "sz.301696", "statDate": "2025-12-31", "YOYNI": "0.269818"}
    if extra:
        base.update(extra)
    return base


def _balance(extra: dict | None = None) -> dict:
    base = {
        "code": "sz.301696",
        "statDate": "2025-12-31",
        "currentRatio": "5.425513",
        "liabilityToAsset": "0.166568",
    }
    if extra:
        base.update(extra)
    return base


class PctFromRatioTests(unittest.TestCase):
    """pct_from_ratio: 0.x → percentage; None / "" / garbage → None."""

    def test_half_becomes_fifty(self):
        self.assertEqual(pct_from_ratio("0.5"), Decimal("50.0000"))

    def test_small_ratio(self):
        self.assertEqual(pct_from_ratio("0.437293"), Decimal("43.7293"))

    def test_negative_ratio(self):
        self.assertEqual(pct_from_ratio("-0.123"), Decimal("-12.3000"))

    def test_none_returns_none(self):
        self.assertIsNone(pct_from_ratio(None))

    def test_empty_string_returns_none(self):
        self.assertIsNone(pct_from_ratio(""))

    def test_garbage_returns_none(self):
        self.assertIsNone(pct_from_ratio("abc"))
        self.assertIsNone(pct_from_ratio("--"))

    def test_passes_through_non_string_numbers(self):
        # ints/floats cast — but note comparison must use Decimal on RHS
        result = pct_from_ratio(0.25)
        self.assertIsNotNone(result)
        self.assertAlmostEqual(float(result), 25.0, places=4)


class ParseDecimalOrNoneTests(unittest.TestCase):
    def test_string_decimal(self):
        self.assertEqual(parse_decimal_or_none("1.17"), Decimal("1.1700"))

    def test_invalid_returns_none(self):
        self.assertIsNone(parse_decimal_or_none(""))
        self.assertIsNone(parse_decimal_or_none("n/a"))
        self.assertIsNone(parse_decimal_or_none(None))


class BuildRecordTests(unittest.TestCase):
    """build_record: merge 5 BaoStock endpoints → INSERT dict."""

    def test_full_payload_maps_all_known_fields(self):
        rec = build_record(
            code="301696.SZ",
            name="三瑞智能",
            profit=_profit(),
            growth=_growth(),
            balance=_balance(),
        )
        assert rec is not None
        # 报告期
        self.assertEqual(rec["report_date"], date(2025, 12, 31))
        # 来自 profit
        self.assertEqual(rec["net_profit"], Decimal("423151074.320000"))
        self.assertEqual(rec["eps"], Decimal("1.170466"))
        self.assertEqual(rec["gross_margin"], Decimal("60.0031"))
        self.assertEqual(rec["net_margin"], Decimal("39.3744"))
        self.assertEqual(rec["roe"], Decimal("43.7293"))
        # 来自 balance —— ratio numbers 也要 ×100，但 currentRatio 本身是倍数不 ×100
        # liabilityToAsset 0.166568 → 16.6568 (%)
        self.assertEqual(rec["debt_ratio"], Decimal("16.6568"))
        # currentRatio 不 ×100，但定量到 4dp → 5.425513 → 5.4255
        self.assertEqual(rec["current_ratio"], Decimal("5.4255"))
        # BaoStock 给不出的字段 → None（不污染、留给后续 source 补）
        self.assertIsNone(rec["revenue"])
        self.assertIsNone(rec["revenue_yoy"])
        self.assertIsNone(rec["deducted_netprofit_yoy"])
        self.assertIsNone(rec["deducted_netprofit_ttm"])
        self.assertIsNone(rec["roa"])
        self.assertIsNone(rec["operating_cashflow"])
        self.assertIsNone(rec["total_assets"])
        self.assertIsNone(rec["total_equity"])

    def test_dedupes_by_stat_date_across_endpoints(self):
        """profit/growth/balance statDate 不一致 → 以 profit 为准，否则 None。"""
        rec = build_record(
            code="301696.SZ",
            name="三瑞智能",
            profit=_profit({"statDate": "2025-12-31"}),
            growth=_growth({"statDate": "2025-09-30"}),
            balance=_balance({"statDate": "2025-12-31"}),
        )
        assert rec is not None
        self.assertEqual(rec["report_date"], date(2025, 12, 31))

    def test_partial_endpoints_falls_back_to_any_stat_date(self):
        rec = build_record(
            code="301696.SZ",
            name="三瑞智能",
            profit=None,
            growth=_growth(),
            balance=None,
        )
        assert rec is not None
        self.assertEqual(rec["report_date"], date(2025, 12, 31))
        # 没利润接口时，net_profit/eps 都是 None
        self.assertIsNone(rec["net_profit"])
        self.assertIsNone(rec["eps"])

    def test_all_endpoints_empty_returns_none(self):
        self.assertIsNone(build_record("301696.SZ", "三瑞智能", None, None, None))

    def test_handles_blanks_and_garbage_gracefully(self):
        rec = build_record(
            code="301696.SZ",
            name="三瑞智能",
            profit=_profit({"roeAvg": "", "netProfit": "", "epsTTM": "n/a"}),
            growth=_growth({"YOYNI": ""}),
            balance=_balance({"currentRatio": "", "liabilityToAsset": ""}),
        )
        assert rec is not None
        self.assertIsNone(rec["roe"])
        self.assertIsNone(rec["net_profit"])
        self.assertIsNone(rec["eps"])
        self.assertIsNone(rec["debt_ratio"])  # empty → None
        self.assertIsNone(rec["current_ratio"])


class UpsertSqlTests(unittest.TestCase):
    def test_upsert_row_uses_insert_ignore(self):
        """STRATEGY：表里现存 (code, date) 多是 qmt/wind 数据，不能覆盖 —— 必须 INSERT IGNORE。"""
        sql = upsert_sql()
        self.assertIn("INSERT IGNORE", sql)
        self.assertIn("trade_stock_financial", sql)

    def test_upsert_row_generates_params_in_correct_order(self):
        # 必须填全部 18 列 (build_record 才会成功给到 upsert)
        full = build_record(
            code="301696.SZ",
            name="三瑞智能",
            profit=_profit(),
            growth=_growth(),
            balance=_balance(),
        )
        assert full is not None
        sql, params = upsert_row(full)
        self.assertIn("INSERT IGNORE", sql)
        # 18 个数值列 + data_source='baostock'（写在 SQL 里、不走 params）
        self.assertEqual(len(params), 18)
        # code、name、date 在前 3 位
        self.assertEqual(params[0], "301696.SZ")
        self.assertEqual(params[1], "三瑞智能")
        self.assertEqual(params[2], date(2025, 12, 31))
        # data_source 硬编码为 baostock（在 SQL 里），不通过 params
        self.assertIn("'baostock'", sql)


if __name__ == "__main__":
    unittest.main(verbosity=2)
