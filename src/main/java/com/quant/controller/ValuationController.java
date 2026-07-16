package com.quant.controller;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.quant.service.Ps10ValuationService;
import com.quant.service.Ps10ValuationService.Ps10Result;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 10 倍 PS 估值法对外端点（不依赖数据库 financial 表）。
 *
 * <p>给"科技风投 · 10 倍 PS 法"计算器使用：用户输入当前市值和 Y0/Y1/Y2 预测营收， 后端统一算 verdict + commentary + 各年 PS 倍数 +
 * 合理市值。
 *
 * <p>前端只负责把数据塞到表单、显示后端返回的表格，不再自己写 PS 公式。
 *
 * @since 2026-07-01
 */
@Slf4j
@RestController
@RequestMapping("/api/valuation")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ValuationController {

  private final Ps10ValuationService ps10ValuationService;

  /**
   * POST /api/valuation/ps10
   *
   * <p>Request:
   *
   * <pre>{@code
   * { "marketCap": 113.7, "revenueY0": 6.73, "revenueY1": 9.08, "revenueY2": 11.80, "netMarginPct": 23.51 }
   * }</pre>
   *
   * <p>Response:
   *
   * <pre>{@code
   * {
   *   "verdict": "合理",
   *   "commentary": "...",
   *   "method": "10 倍 PS 法（输入式）",
   *   "rows": [
   *     { "label": "今年", "revenue": 6.73, "psMultiple": 16.9, "fairCap": 67.3, "subVerdict": "高估" },
   *     { "label": "明年", "revenue": 9.08, "psMultiple": 12.5, "fairCap": 90.8, "subVerdict": "高估" },
   *     { "label": "后年", "revenue": 11.80, "psMultiple": 9.6, "fairCap": 118.0, "subVerdict": "合理" }
   *   ]
   * }
   * }</pre>
   */
  @PostMapping("/ps10")
  public Map<String, Object> calcPs10(@RequestBody Ps10CalcRequest req) {
    if (req == null) {
      req = new Ps10CalcRequest();
    }
    Ps10Result result =
        ps10ValuationService.evaluateFromInputs(
            req.marketCap, req.revenueY0, req.revenueY1, req.revenueY2, req.netMarginPct);

    // 拼装三行明细（前端表格）
    List<Map<String, Object>> rows = new ArrayList<>();
    if (req.revenueY0 != null && req.revenueY0.signum() > 0) {
      rows.add(buildRow("今年", req.marketCap, req.revenueY0));
    }
    if (req.revenueY1 != null && req.revenueY1.signum() > 0) {
      rows.add(buildRow("明年", req.marketCap, req.revenueY1));
    }
    if (req.revenueY2 != null && req.revenueY2.signum() > 0) {
      rows.add(buildRow("后年", req.marketCap, req.revenueY2));
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("verdict", result.verdict());
    body.put("commentary", result.commentary());
    body.put("method", result.method());
    body.put("fairCapY1Yi", result.fairCapY1Yi());
    body.put("fairCapY2Yi", result.fairCapY2Yi());
    body.put("rows", rows);
    return body;
  }

  /**
   * 单年 PS 倍数 + 合理市值 + 子结论。 子结论用绝对 PS 倍数分档（前端表格单行展示用）： - PS < 5 → 低估（绿色） - 5 ≤ PS ≤ 10 → 合理（黄色） - PS
   * > 10 → 高估（红色）
   */
  private Map<String, Object> buildRow(String label, BigDecimal marketCap, BigDecimal revenueYi) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("label", label);
    row.put("revenue", revenueYi);
    double ps =
        marketCap != null && marketCap.signum() > 0
            ? marketCap.doubleValue() / revenueYi.doubleValue()
            : Double.NaN;
    row.put("psMultiple", Double.isFinite(ps) ? Math.round(ps * 10.0) / 10.0 : null);
    row.put("fairCap", Math.round(revenueYi.doubleValue() * 10.0 * 10.0) / 10.0);
    if (!Double.isFinite(ps)) {
      row.put("subVerdict", "—");
    } else if (ps < 5) {
      row.put("subVerdict", "低估");
    } else if (ps <= 10) {
      row.put("subVerdict", "合理");
    } else {
      row.put("subVerdict", "高估");
    }
    return row;
  }

  /** 请求体。所有字段可选；marketCap / Y0/Y1/Y2 都为空时返回 verdict=—。 */
  public static class Ps10CalcRequest {
    public BigDecimal marketCap;
    public BigDecimal revenueY0;
    public BigDecimal revenueY1;
    public BigDecimal revenueY2;
    public BigDecimal netMarginPct;
  }
}
