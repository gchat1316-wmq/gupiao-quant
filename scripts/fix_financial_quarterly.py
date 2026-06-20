#!/usr/bin/env python3
"""
修复 trade_stock_financial 表中年报累计营收问题。

问题：年报(report_date=12-31)的营收/净利是全年累计值，
      不是Q4单季值，导致25Q4营收显示454亿而不是126亿。

修复：对于12-31记录，如果该年份存在Q1/Q2/Q3数据，
      则计算 Q4 = 年报 - Q1 - Q2 - Q3，替换年报记录的各字段。

用法：
  python3 fix_financial_quarterly.py [--dry-run] [--stock-code 603259.SH]
"""
from __future__ import annotations

import argparse
import os
import sys
from decimal import Decimal, InvalidOperation
from typing import Optional

import pymysql
from pymysql.cursors import DictCursor


def connect_db():
    env = os.environ
    # 从环境变量读取，或使用默认值
    host = env.get("DB_HOST", "43.140.208.165")
    port = int(env.get("DB_PORT", "3306"))
    user = env.get("DB_USERNAME", "wucai_trade")
    password = env.get("DB_PASSWORD", "")
    database = env.get("DB_NAME", "wucai_trade")

    return pymysql.connect(
        host=host,
        port=port,
        user=user,
        password=password,
        database=database,
        charset="utf8mb4",
        cursorclass=DictCursor,
        autocommit=False,
    )


DECIMAL_FIELDS = [
    "revenue", "net_profit", "deducted_netprofit_ttm",
    "eps", "roe", "roa", "gross_margin", "net_margin",
    "debt_ratio", "current_ratio", "operating_cashflow",
    "total_assets", "total_equity",
]


def get_quarterly_records(conn, stock_code: str, year: int) -> dict[str, Optional[dict]]:
    """获取某股票某年的Q1/Q2/Q3单季记录(排除年报)，返回 {q1/q2/q3: record}"""
    # Q1=03-31, Q2=06-30, Q3=09-30
    quarters = {
        "q1": f"{year}-03-31",
        "q2": f"{year}-06-30",
        "q3": f"{year}-09-30",
    }
    result = {}
    with conn.cursor() as cur:
        for q_key, date_str in quarters.items():
            cur.execute(
                f"SELECT * FROM trade_stock_financial WHERE stock_code = %s AND report_date = %s",
                (stock_code, date_str),
            )
            row = cur.fetchone()
            if row:
                result[q_key] = row
    return result


def fix_annual_to_quarterly(conn, stock_code: str, year: int, dry_run: bool = True) -> dict:
    """
    将年报(report_date=12-31)的累计值替换为Q4单季值。
    Q4 = 年报 - Q1 - Q2 - Q3
    """
    annual_date = f"{year}-12-31"
    result = {"stock_code": stock_code, "year": year, "updated": False, "details": {}}

    with conn.cursor() as cur:
        # 获取年报记录
        cur.execute(
            "SELECT * FROM trade_stock_financial WHERE stock_code = %s AND report_date = %s",
            (stock_code, annual_date),
        )
        annual = cur.fetchone()
        if not annual:
            result["details"]["msg"] = "无年报记录，跳过"
            return result

        # 获取Q1/Q2/Q3记录
        quarters = get_quarterly_records(conn, stock_code, year)
        if len(quarters) < 3:
            result["details"]["msg"] = f"缺少Q1/Q2/Q3数据，只有 {list(quarters.keys())}，跳过"
            return result

        # 计算Q4 = 年报 - Q1 - Q2 - Q3
        q1, q2, q3 = quarters["q1"], quarters["q2"], quarters["q3"]
        updates = {}
        for field in DECIMAL_FIELDS:
            annual_val = annual.get(field)
            q1_val = q1.get(field)
            q2_val = q2.get(field)
            q3_val = q3.get(field)

            if all(v is not None for v in [annual_val, q1_val, q2_val, q3_val]):
                try:
                    annual_d = Decimal(str(annual_val))
                    q1_d = Decimal(str(q1_val))
                    q2_d = Decimal(str(q2_val))
                    q3_d = Decimal(str(q3_val))
                    q4_d = annual_d - q1_d - q2_d - q3_d
                    # 检查Q4是否为负或明显不合理
                    if q4_d < 0:
                        result["details"]["warn"] = f"{field}: Q4计算为负 ({q4_d})，可能是数据问题，跳过该字段"
                        continue
                    updates[field] = q4_d
                except (InvalidOperation, ValueError):
                    pass

        if not updates:
            result["details"]["msg"] = "无法计算Q4（字段值不完整）"
            return result

        result["details"]["annual_revenue"] = float(Decimal(str(annual["revenue"])) / 1e8)
        result["details"]["q1_revenue"] = float(Decimal(str(q1["revenue"])) / 1e8)
        result["details"]["q2_revenue"] = float(Decimal(str(q2["revenue"])) / 1e8)
        result["details"]["q3_revenue"] = float(Decimal(str(q3["revenue"])) / 1e8)
        q4_rev = updates.get("revenue")
        if q4_rev is not None:
            result["details"]["q4_revenue_new"] = float(q4_rev / 1e8)

        if dry_run:
            result["details"]["msg"] = f"[DRY RUN] 将更新 {len(updates)} 个字段"
            return result

        # 执行更新
        set_clause = ", ".join([f"{f} = %s" for f in updates.keys()])
        values = list(updates.values()) + [stock_code, annual_date]
        sql = f"UPDATE trade_stock_financial SET {set_clause} WHERE stock_code = %s AND report_date = %s"
        cur.execute(sql, values)
        result["updated"] = True
        result["details"]["msg"] = f"已更新 {len(updates)} 个字段"
        return result


def find_all_annual_records(conn, stock_code: Optional[str] = None) -> list[dict]:
    """找出所有年报记录（12-31日期，且存在同年Q1/Q2/Q3的）"""
    with conn.cursor() as cur:
        if stock_code:
            cur.execute(
                """SELECT DISTINCT tf.stock_code, tf.stock_name,
                          YEAR(tf.report_date) as yr, tf.report_date,
                          tf.revenue
                   FROM trade_stock_financial tf
                   WHERE tf.stock_code = %s
                     AND MONTH(tf.report_date) = 12
                     AND DAY(tf.report_date) = 31
                   ORDER BY tf.stock_code, yr""",
                (stock_code,),
            )
        else:
            cur.execute(
                """SELECT DISTINCT tf.stock_code, tf.stock_name,
                          YEAR(tf.report_date) as yr, tf.report_date,
                          tf.revenue
                   FROM trade_stock_financial tf
                   WHERE MONTH(tf.report_date) = 12
                     AND DAY(tf.report_date) = 31
                   ORDER BY tf.stock_code, yr
                   LIMIT 500"""
            )
        return cur.fetchall()


def main():
    parser = argparse.ArgumentParser(description="修复年报累计营收 -> Q4单季营收")
    parser.add_argument("--dry-run", action="store_true", default=True,
                        help="仅打印将要做的修改，不实际写入数据库")
    parser.add_argument("--apply", action="store_true",
                        help="实际写入数据库（需要先 dry-run 确认）")
    parser.add_argument("--stock-code", default="603259.SH",
                        help="指定股票代码")
    parser.add_argument("--all", action="store_true",
                        help="扫描所有股票（默认只处理指定股票）")
    args = parser.parse_args()

    if args.apply:
        args.dry_run = False

    print(f"连接数据库...")
    conn = connect_db()

    try:
        if args.all:
            print("扫描所有年报记录...")
            annuals = find_all_annual_records(conn)
            print(f"找到 {len(annuals)} 条年报记录，开始修复...")
            for rec in annuals:
                stock_code = rec["stock_code"]
                year = rec["yr"]
                result = fix_annual_to_quarterly(conn, stock_code, year, dry_run=args.dry_run)
                if result["details"].get("msg") and "跳过" not in result["details"]["msg"]:
                    print(f"  {stock_code} {year}: {result['details'].get('msg', '')}")
                    if "q4_revenue_new" in result["details"]:
                        print(f"    Q4营收: {result['details']['q4_revenue_new']:.2f}亿")
                elif not result["details"].get("msg"):
                    print(f"  {stock_code} {year}: 已更新 (revenue={result['details'].get('q4_revenue_new', '?'):.2f}亿)")
            if not args.dry_run:
                conn.commit()
                print("已提交!")
        else:
            stock_code = args.stock_code
            print(f"处理股票: {stock_code}")
            # 找出该股票所有可能的年份
            with conn.cursor() as cur:
                cur.execute(
                    """SELECT DISTINCT YEAR(report_date) as yr
                       FROM trade_stock_financial
                       WHERE stock_code = %s AND MONTH(report_date) = 12 AND DAY(report_date) = 31
                       ORDER BY yr""",
                    (stock_code,)
                )
                years = [r["yr"] for r in cur.fetchall()]

            for year in years:
                result = fix_annual_to_quarterly(conn, stock_code, year, dry_run=args.dry_run)
                details = result["details"]
                msg = details.get("msg", "")
                if result["updated"]:
                    print(f"  ✅ {year}年: {msg}")
                    if "q4_revenue_new" in details:
                        print(f"     Q4营收: {details['q4_revenue_new']:.2f}亿 (原年报: {details['annual_revenue']:.2f}亿)")
                else:
                    print(f"  ⏭ {year}年: {msg}")

            if not args.dry_run:
                conn.commit()
                print("\n已提交修改!")

    finally:
        conn.close()


if __name__ == "__main__":
    main()
