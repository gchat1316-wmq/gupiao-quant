package com.quant.service.potential;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.quant.dto.practicalselect.FinancialAnalysis;
import com.quant.dto.practicalselect.PracticalSelectResponse;
import com.quant.dto.practicalselect.StarRating;
import com.quant.dto.practicalselect.TrendAnalysis;
import com.quant.dto.practicalselect.ValuationAnalysis;
import com.quant.entity.InvestPracticalSelectRecord;

import lombok.extern.slf4j.Slf4j;

/** 实战选股 · PDF 导出。 复用 scripts/render_pdf.py（Playwright headless chromium）。 */
@Slf4j
@Service
public class PracticalSelectPdfService {

  @Value("${app.upload-dir:uploads}")
  private String uploadDir;

  @Value("${spring.application.name:gupiao-quant}")
  private String appName;

  /** python 解释器（python3 / python） */
  @Value("${practical-select.pdf.python:python3}")
  private String pythonCommand;

  /**
   * 生成 PDF（已有则复用）。
   *
   * @return 相对 uploadDir 的路径
   */
  public String generate(InvestPracticalSelectRecord rec) {
    if (rec == null) throw new IllegalArgumentException("记录不存在");
    if (rec.getResultJson() == null || rec.getResultJson().isBlank()) {
      throw new IllegalStateException("记录缺少 resultJson，无法生成 PDF");
    }
    // 已有 PDF 则复用
    if (rec.getPdfPath() != null && !rec.getPdfPath().isBlank()) {
      File existing = Paths.get(uploadDir, rec.getPdfPath()).toFile();
      if (existing.exists() && existing.length() > 0) {
        log.info("PDF 已存在, 复用: {}", rec.getPdfPath());
        return rec.getPdfPath();
      }
    }

    PracticalSelectResponse data;
    try {
      data = new ObjectMapper().readValue(rec.getResultJson(), PracticalSelectResponse.class);
    } catch (Exception e) {
      throw new IllegalStateException("解析 resultJson 失败: " + e.getMessage(), e);
    }

    String code = rec.getStockCode() == null ? "stock" : rec.getStockCode();
    String fileName =
        String.format(
            "practical-select/%s/%d-%s.pdf",
            code.replaceAll("[^A-Za-z0-9._-]", "_"),
            rec.getId(),
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
    Path outPath = Paths.get(uploadDir, fileName);
    try {
      Files.createDirectories(outPath.getParent());
    } catch (IOException e) {
      throw new RuntimeException("创建 PDF 目录失败: " + outPath.getParent(), e);
    }
    String html = renderHtml(data, rec);
    htmlToPdf(html, outPath);
    log.info("PDF 生成成功: {} ({} bytes)", fileName, outPath.toFile().length());
    return fileName;
  }

  public File resolvePdfFile(String relativePath) {
    if (relativePath == null || relativePath.isBlank()) return null;
    Path p = Paths.get(uploadDir, relativePath).normalize();
    Path root = Paths.get(uploadDir).normalize();
    if (!p.startsWith(root)) return null; // 防穿越
    return p.toFile();
  }

  // ============================================================
  // PDF 渲染（调 python render_pdf.py）
  // ============================================================

  private void htmlToPdf(String html, Path outPath) {
    // 找 render_pdf.py：优先用环境变量，否则项目根 + scripts/render_pdf.py
    String scriptPath = System.getenv("PRACTICAL_SELECT_PDF_SCRIPT");
    if (scriptPath == null || scriptPath.isBlank()) {
      scriptPath = findScriptInProject();
    }
    if (scriptPath == null) {
      throw new RuntimeException("找不到 scripts/render_pdf.py，请设置 PRACTICAL_SELECT_PDF_SCRIPT 环境变量");
    }

    ProcessBuilder pb =
        new ProcessBuilder(pythonCommand, scriptPath, outPath.toAbsolutePath().toString());
    pb.redirectErrorStream(true);
    Process process = null;
    try {
      process = pb.start();
      process.getOutputStream().write(html.getBytes(StandardCharsets.UTF_8));
      process.getOutputStream().close();
      boolean done = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
      if (!done) {
        process.destroyForcibly();
        throw new RuntimeException("PDF 渲染超时 (60s)");
      }
      if (process.exitValue() != 0) {
        String err = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        throw new RuntimeException("PDF 渲染失败, 退出码 " + process.exitValue() + ": " + err);
      }
      if (!Files.exists(outPath)) {
        throw new RuntimeException("PDF 文件未生成");
      }
    } catch (Exception e) {
      throw new RuntimeException("PDF 生成失败: " + e.getMessage(), e);
    } finally {
      if (process != null && process.isAlive()) process.destroyForcibly();
    }
  }

  private String findScriptInProject() {
    // 1) 当前工作目录
    Path p = Paths.get(System.getProperty("user.dir"), "scripts", "render_pdf.py");
    if (Files.exists(p)) return p.toAbsolutePath().toString();
    // 2) 类的 classpath 根
    try {
      String cls =
          PracticalSelectPdfService.class
              .getProtectionDomain()
              .getCodeSource()
              .getLocation()
              .toURI()
              .getPath();
      // cls 形如 /path/to/target/classes/
      Path target = Paths.get(cls);
      Path project = target.getParent().getParent(); // target/.. = project root
      Path script = project.resolve("scripts/render_pdf.py");
      if (Files.exists(script)) return script.toAbsolutePath().toString();
    } catch (Exception ignore) {
    }
    return null;
  }

  // ============================================================
  // HTML 模板
  // ============================================================

  private String renderHtml(PracticalSelectResponse d, InvestPracticalSelectRecord rec) {
    StringBuilder sb = new StringBuilder(8192);
    sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'><title>")
        .append(esc(d.getStockName()))
        .append(" 实战选股分析</title>")
        .append("<style>")
        .append(
            """
              @page { size: A4; margin: 16mm 14mm; }
              * { box-sizing: border-box; }
              body { font-family: "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", sans-serif;
                     color: #1a2233; font-size: 10.5pt; line-height: 1.65; margin: 0; }
              h1 { font-size: 22pt; color: #1e3a8a; margin: 0 0 4pt; border-bottom: 3px solid #3b82f6;
                   padding-bottom: 6pt; display: flex; align-items: baseline; gap: 8pt; }
              h1 small { font-size: 12pt; color: #6b7280; font-weight: 400; }
              .sub { color: #6b7280; font-size: 9pt; margin-bottom: 14pt; }
              h2 { font-size: 14pt; color: #1a2233; margin: 14pt 0 6pt;
                   border-left: 4px solid #3b82f6; padding-left: 8pt; }
              .summary-box { background: #f0f9ff; border-left: 4px solid #3b82f6; padding: 8pt 12pt;
                             border-radius: 4pt; margin: 8pt 0; font-size: 10.5pt; }
              .kpi-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 6pt; margin: 6pt 0; }
              .kpi { background: #f9fafb; padding: 8pt 10pt; border-radius: 4pt; border: 1px solid #e5e7eb; }
              .kpi-label { font-size: 8pt; color: #6b7280; }
              .kpi-value { font-size: 13pt; font-weight: 600; margin-top: 2pt; font-family: "Courier New", monospace; }
              .kpi-value.up { color: #059669; }
              .kpi-value.down { color: #dc2626; }
              .verdict { display: inline-block; padding: 3pt 10pt; border-radius: 10pt;
                         font-size: 9pt; font-weight: 600; margin: 2pt 4pt 2pt 0; }
              .verdict-pass { background: #d1fae5; color: #065f46; }
              .verdict-warn { background: #fef3c7; color: #92400e; }
              .verdict-fail { background: #fee2e2; color: #991b1b; }
              .verdict-low  { background: #d1fae5; color: #065f46; }
              .verdict-fair { background: #dbeafe; color: #1e40af; }
              .verdict-high { background: #fee2e2; color: #991b1b; }
              table { width: 100%; border-collapse: collapse; margin: 6pt 0; font-size: 9pt; }
              th, td { border: 1px solid #d0d7e2; padding: 3pt 6pt; text-align: right;
                       font-family: "Courier New", monospace; }
              th { background: #f3f4f6; font-weight: 600; color: #4b5563; }
              td:first-child, th:first-child { text-align: left; font-family: inherit; }
              .up { color: #059669; } .down { color: #dc2626; }
              .rating-block { background: #fffbeb; border: 1px solid #fcd34d;
                              padding: 10pt 14pt; border-radius: 6pt; margin: 8pt 0; }
              .rating-head { display: flex; align-items: baseline; gap: 10pt; margin-bottom: 6pt; }
              .rating-title { font-size: 13pt; font-weight: 700; color: #92400e; }
              .stars { font-size: 14pt; color: #f59e0b; letter-spacing: 2pt; }
              .stars-num { font-family: "Courier New", monospace; font-size: 10pt; color: #92400e; }
              .dim-row { display: grid; grid-template-columns: 80pt 1fr 30pt;
                         grid-template-rows: auto auto; gap: 2pt 8pt;
                         align-items: center; padding: 4pt 0;
                         border-bottom: 1px dashed #fde68a; font-size: 10pt; }
              .dim-name { color: #4b5563; font-weight: 600; }
              .dim-stars { text-align: right; font-family: "Courier New", monospace;
                           color: #92400e; font-weight: 600; }
              .dim-bar { height: 4pt; background: #fef3c7; border-radius: 2pt; overflow: hidden; }
              .dim-bar-fill { height: 100%; background: linear-gradient(to right, #6366f1, #8b5cf6); }
              .dim-reason { grid-column: 1 / 4; font-size: 9pt; color: #6b7280; line-height: 1.5; }
              .footer { margin-top: 18pt; padding-top: 6pt; border-top: 1px solid #d0d7e2;
                        color: #9aa4b2; font-size: 8pt; text-align: center; }
              .page-break { page-break-before: always; }
              ul { padding-left: 18pt; margin: 4pt 0; }
              li { margin-bottom: 3pt; font-size: 10pt; }
              """)
        .append("</style></head><body>");

    // 标题
    sb.append("<h1>")
        .append(esc(d.getStockName()))
        .append(" <small>")
        .append(esc(d.getStockCode()))
        .append(" 实战选股分析</small></h1>");
    sb.append("<div class='sub'>数据：财务 / 走势 / 估值 / AI 评级 · 生成时间: ")
        .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
        .append(" · 记录ID: ")
        .append(rec.getId())
        .append(" · 耗时 ")
        .append(rec.getElapsedMs() == null ? "—" : rec.getElapsedMs() + " ms")
        .append("</div>");

    // 头部 banner
    if (d.getSummaryHeadline() != null) {
      sb.append("<div class='summary-box'>").append(esc(d.getSummaryHeadline())).append("</div>");
    }

    // 1. 走势
    if (d.getTrend() != null) {
      TrendAnalysis t = d.getTrend();
      sb.append("<h2>1. 完美的走势</h2>");
      if (t.getSummary() != null) {
        sb.append("<div class='summary-box'>").append(esc(t.getSummary())).append("</div>");
      }
      sb.append("<div class='kpi-grid'>");
      sb.append(kpi("本月至今", t.getMonthToDateReturnPct(), "%"));
      sb.append(kpi("最近一月", t.getLastMonthReturnPct(), "%"));
      sb.append(kpi("近 60 日最大涨幅", t.getSixtyDayMaxGainPct(), "%"));
      sb.append(kpi("近 60 日最大回撤", t.getSixtyDayMaxDrawdownPct(), "%"));
      sb.append("</div>");
      if (t.isBreakoutDetected() && t.getBreakoutNote() != null) {
        sb.append("<div class='summary-box' style='background:#d1fae5;border-left-color:#10b981'>")
            .append("🚀 ")
            .append(esc(t.getBreakoutNote()))
            .append("</div>");
      }
      if (t.getMonthlyBars() != null && !t.getMonthlyBars().isEmpty()) {
        sb.append("<h3 style='font-size:11pt;color:#4b5563;margin:8pt 0 4pt'>月线走势（最新 ")
            .append(t.getMonthlyBars().size())
            .append(" 月）</h3>");
        sb.append(
            "<table><thead><tr><th>月份</th><th>收盘</th><th>最高</th><th>最低</th><th>涨幅</th></tr></thead><tbody>");
        for (TrendAnalysis.MonthlyBar b : t.getMonthlyBars()) {
          sb.append("<tr><td>")
              .append(esc(b.getMonth()))
              .append("</td>")
              .append("<td>")
              .append(b.getClose() == null ? "—" : String.format("%.2f", b.getClose()))
              .append("</td>")
              .append("<td>")
              .append(b.getHigh() == null ? "—" : String.format("%.2f", b.getHigh()))
              .append("</td>")
              .append("<td>")
              .append(b.getLow() == null ? "—" : String.format("%.2f", b.getLow()))
              .append("</td>")
              .append("<td class='")
              .append(b.getReturnPct() == null ? "" : (b.getReturnPct() >= 0 ? "up" : "down"))
              .append("'>")
              .append(
                  b.getReturnPct() == null
                      ? "—"
                      : (b.getReturnPct() >= 0 ? "+" : "")
                          + String.format("%.2f%%", b.getReturnPct()))
              .append("</td></tr>");
        }
        sb.append("</tbody></table>");
      }
      if (t.getRecentBigYang() != null && !t.getRecentBigYang().isEmpty()) {
        sb.append("<h3 style='font-size:11pt;color:#4b5563;margin:10pt 0 4pt'>最近大阳线（≥ 9.5%）</h3>");
        sb.append(
            "<table><thead><tr><th>日期</th><th>开盘</th><th>收盘</th><th>最高</th><th>涨幅</th><th>换手</th></tr></thead><tbody>");
        for (TrendAnalysis.BigYangLine b : t.getRecentBigYang()) {
          sb.append("<tr><td>")
              .append(esc(b.getDate()))
              .append("</td>")
              .append("<td>")
              .append(b.getOpenPrice() == null ? "—" : String.format("%.2f", b.getOpenPrice()))
              .append("</td>")
              .append("<td>")
              .append(b.getClosePrice() == null ? "—" : String.format("%.2f", b.getClosePrice()))
              .append("</td>")
              .append("<td>")
              .append(b.getHighPrice() == null ? "—" : String.format("%.2f", b.getHighPrice()))
              .append("</td>")
              .append("<td class='up'>+")
              .append(String.format("%.2f%%", b.getPctChange()))
              .append("</td>")
              .append("<td>")
              .append(
                  b.getTurnoverRate() == null ? "—" : String.format("%.2f%%", b.getTurnoverRate()))
              .append("</td></tr>");
        }
        sb.append("</tbody></table>");
      }
    }

    // 2. 漂亮数字
    if (d.getFinancials() != null) {
      FinancialAnalysis f = d.getFinancials();
      sb.append("<h2>2. 漂亮的数字 · 16 季度财务</h2>");
      if (f.getSummary() != null) {
        sb.append("<div class='summary-box'>").append(esc(f.getSummary())).append("</div>");
      }
      if (f.getSopVerdict() != null) {
        String vc = f.getSopVerdict();
        String tag =
            switch (vc) {
              case "pass" -> "✓ PASS · 三大数字漂亮";
              case "warn" -> "⚠ WARN · 部分指标偏弱";
              case "fail" -> "✗ FAIL · 数字不漂亮";
              default -> vc;
            };
        sb.append("<div><span class='verdict verdict-")
            .append(vc)
            .append("'>")
            .append(tag)
            .append("</span>")
            .append(esc(f.getSopSummary() == null ? "" : f.getSopSummary()))
            .append("</div>");
      }
      // SOP 三项明细
      if (f.getSopMetrics() != null && !f.getSopMetrics().isEmpty()) {
        sb.append("<h3 style='font-size:11pt;color:#4b5563;margin:8pt 0 4pt'>SOP 体检分项</h3>");
        sb.append(
            "<table><thead><tr><th>指标</th><th>最新值</th><th>判定</th><th>说明</th></tr></thead><tbody>");
        for (FinancialAnalysis.SopMetricBrief m : f.getSopMetrics()) {
          sb.append("<tr><td>")
              .append(esc(m.getLabel()))
              .append("</td>")
              .append("<td>")
              .append(esc(m.getLatestText()))
              .append("</td>")
              .append("<td><span class='verdict verdict-")
              .append(esc(m.getVerdict()))
              .append("'>")
              .append(esc(m.getVerdict().toUpperCase()))
              .append("</span></td>")
              .append("<td style='text-align:left;font-family:inherit'>")
              .append(esc(m.getTip()))
              .append("</td></tr>");
        }
        sb.append("</tbody></table>");
      }
      // 16 季度横向表格
      if (f.getQuarters() != null && !f.getQuarters().isEmpty()) {
        sb.append("<h3 style='font-size:11pt;color:#4b5563;margin:10pt 0 4pt'>近 ")
            .append(f.getQuarters().size())
            .append(" 季度财务数据</h3>");
        sb.append("<table><thead><tr><th>指标</th>");
        for (FinancialAnalysis.QuarterSnapshot q : f.getQuarters()) {
          sb.append("<th>")
              .append(esc(q.getQuarter()))
              .append("<br/><span style='font-weight:400;color:#9ca3af;font-size:8pt'>")
              .append(esc((q.getReportDate() == null ? "" : q.getReportDate().substring(2))))
              .append("</span></th>");
        }
        sb.append("</tr></thead><tbody>");
        appendQuarterRow(
            sb,
            "营收 (亿)",
            f.getQuarters(),
            q -> q.getRevenueYi(),
            v -> v == null ? "—" : String.format("%.2f", v));
        appendQuarterRow(
            sb,
            "同比",
            f.getQuarters(),
            q -> q.getRevenueYoy(),
            v -> v == null ? "—" : (v >= 0 ? "+" : "") + String.format("%.2f%%", v),
            true);
        appendQuarterRow(
            sb,
            "毛利率",
            f.getQuarters(),
            q -> q.getGrossMargin(),
            v -> v == null ? "—" : String.format("%.2f%%", v));
        appendQuarterRow(
            sb,
            "净利率",
            f.getQuarters(),
            q -> q.getNetMargin(),
            v -> v == null ? "—" : String.format("%.2f%%", v));
        appendQuarterRow(
            sb,
            "EPS",
            f.getQuarters(),
            q -> q.getEps(),
            v -> v == null ? "—" : String.format("%.2f", v));
        appendQuarterRow(
            sb,
            "ROE",
            f.getQuarters(),
            q -> q.getRoe(),
            v -> v == null ? "—" : String.format("%.2f%%", v));
        sb.append("</tbody></table>");
      }
    }

    // 3. 估值
    if (d.getValuation() != null) {
      ValuationAnalysis v = d.getValuation();
      sb.append("<h2>3. 成长与估值的匹配 · ")
          .append(esc(v.getMethod() == null ? "估值" : v.getMethod()))
          .append("</h2>");
      String verdict = v.getVerdict();
      if (verdict != null) {
        sb.append("<div><span class='verdict verdict-")
            .append(verdict)
            .append("'>估值结论：")
            .append(esc(verdict))
            .append("</span>")
            .append(v.getMethodReason() == null ? "" : esc(v.getMethodReason()))
            .append("</div>");
      }
      if (v.getCommentary() != null) {
        sb.append("<div class='summary-box'>").append(esc(v.getCommentary())).append("</div>");
      }
      sb.append("<table><tbody>");
      sb.append(valRow("当前股价", v.getCurrentPrice(), "元"));
      sb.append(valRow("总股本", v.getTotalSharesYi(), "亿股"));
      sb.append(valRow("当前市值", v.getCurrentMarketCapYi(), "亿元"));
      sb.append(valRow("最新净利率", v.getLatestNetMargin(), "%"));
      sb.append(valRow("PS 倍数", v.getPsMultiple(), "倍（统一）"));
      sb.append(valRow("今年预测营收 Y0", v.getForecastRevenueY0(), "亿"));
      sb.append(valRow("明年预测营收 Y1", v.getForecastRevenueY1(), "亿"));
      sb.append(valRow("后年预测营收 Y2", v.getForecastRevenueY2(), "亿"));
      sb.append(valRow("Y1×10 合理市值", v.getFairCapY1Yi(), "亿元"));
      sb.append(valRow("Y2×10 合理市值", v.getFairCapY2Yi(), "亿元"));
      sb.append("</tbody></table>");
      if (v.getBuildPositionTip() != null) {
        sb.append(
                "<div class='summary-box' style='background:#fef3c7;border-left-color:#f59e0b'>💡 ")
            .append(esc(v.getBuildPositionTip()))
            .append("</div>");
      }
    }

    // 4. 星级评级
    if (d.getRating() != null) {
      StarRating r = d.getRating();
      sb.append("<h2>4. 稀缺性 + 成长动力 星级评级 ")
          .append(
              r.isAiGenerated()
                  ? "<small style='color:#6d28d9'>· 🤖 AI 生成</small>"
                  : "<small style='color:#6b7280'>· ⚙️ 本地启发式</small>")
          .append("</h2>");
      sb.append(
          renderRatingBlock(
              "稀缺性",
              r.getScarcityStars(),
              r.getScarcityStarsText(),
              r.getScarcitySummary(),
              r.getScarcityDimensions(),
              null));
      sb.append(
          renderRatingBlock(
              "成长动力",
              r.getGrowthStars(),
              r.getGrowthStarsText(),
              r.getGrowthSummary(),
              r.getGrowthDimensions(),
              r.getGrowthWeaknesses()));
    }

    // footer
    sb.append("<div class='footer'>⚠️ 本报告由系统自动生成 · AI 部分基于大模型推理 · 仅供学习研究，不构成投资建议 · ")
        .append("记录 ID: ")
        .append(rec.getId())
        .append(" · 生成于 ")
        .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
        .append("</div>");
    sb.append("</body></html>");
    return sb.toString();
  }

  private String renderRatingBlock(
      String label,
      Double stars,
      String starsText,
      String summary,
      List<StarRating.DimensionRating> dims,
      List<String> weaknesses) {
    StringBuilder sb = new StringBuilder();
    sb.append("<div class='rating-block'>");
    sb.append("<div class='rating-head'><div class='rating-title'>")
        .append(esc(label))
        .append("</div>");
    sb.append("<div><span class='stars'>")
        .append(esc(starsText == null ? starsToText(stars) : starsText))
        .append("</span> ");
    sb.append("<span class='stars-num'>")
        .append(stars == null ? "—" : String.format("%.1f / 5.0", stars))
        .append("</span></div></div>");
    if (summary != null)
      sb.append("<div style='font-size:10pt;color:#78350f;margin-bottom:6pt'>")
          .append(esc(summary))
          .append("</div>");
    if (dims != null && !dims.isEmpty()) {
      for (StarRating.DimensionRating d : dims) {
        double w = (d.getStars() == null ? 0 : d.getStars()) / 5.0 * 100;
        sb.append("<div class='dim-row'>");
        sb.append("<div class='dim-name'>").append(esc(d.getName())).append("</div>");
        sb.append("<div class='dim-bar'><div class='dim-bar-fill' style='width:")
            .append(String.format("%.1f", w))
            .append("%'></div></div>");
        sb.append("<div class='dim-stars'>")
            .append(d.getStars() == null ? "—" : String.format("%.1f", d.getStars()))
            .append("</div>");
        if (d.getReason() != null) {
          sb.append("<div class='dim-reason'>").append(esc(d.getReason())).append("</div>");
        }
        sb.append("</div>");
      }
    }
    if (weaknesses != null && !weaknesses.isEmpty()) {
      sb.append(
          "<div style='margin-top:6pt;font-size:9pt;color:#9a3412'><b>⚠ 短板：</b><ul style='margin:4pt 0'>");
      for (String w : weaknesses) sb.append("<li>").append(esc(w)).append("</li>");
      sb.append("</ul></div>");
    }
    sb.append("</div>");
    return sb.toString();
  }

  private String starsToText(Double s) {
    if (s == null) return "☆☆☆☆☆";
    int full = (int) Math.floor(s);
    boolean half = (s - full) >= 0.5;
    StringBuilder t = new StringBuilder();
    for (int i = 0; i < 5; i++) {
      if (i < full) t.append("★");
      else if (i == full && half) t.append("☆");
      else t.append("☆");
    }
    return t.toString();
  }

  private String kpi(String label, Double v, String unit) {
    if (v == null)
      return "<div class='kpi'><div class='kpi-label'>"
          + esc(label)
          + "</div><div class='kpi-value'>—</div></div>";
    String cls = v >= 0 ? "up" : "down";
    String sign = v >= 0 ? "+" : "";
    String val =
        label.contains("回撤")
            ? String.format("%.2f%s", v, unit)
            : sign + String.format("%.2f%s", v, unit);
    return "<div class='kpi'><div class='kpi-label'>"
        + esc(label)
        + "</div><div class='kpi-value "
        + cls
        + "'>"
        + esc(val)
        + "</div></div>";
  }

  private String valRow(String label, Double v, String unit) {
    if (v == null) return "<tr><td>" + esc(label) + "</td><td>—</td></tr>";
    return "<tr><td>"
        + esc(label)
        + "</td><td>"
        + esc(String.format("%.2f %s", v, unit))
        + "</td></tr>";
  }

  private interface QuarterExtractor {
    Double get(FinancialAnalysis.QuarterSnapshot q);
  }

  private interface Formatter {
    String format(Double v);
  }

  private void appendQuarterRow(
      StringBuilder sb,
      String label,
      List<FinancialAnalysis.QuarterSnapshot> quarters,
      QuarterExtractor extractor,
      Formatter fmt) {
    appendQuarterRow(sb, label, quarters, extractor, fmt, false);
  }

  private void appendQuarterRow(
      StringBuilder sb,
      String label,
      List<FinancialAnalysis.QuarterSnapshot> quarters,
      QuarterExtractor extractor,
      Formatter fmt,
      boolean colorPosNeg) {
    sb.append("<tr><td>").append(esc(label)).append("</td>");
    for (FinancialAnalysis.QuarterSnapshot q : quarters) {
      Double v = extractor.get(q);
      String cls = "";
      if (colorPosNeg && v != null) cls = v >= 0 ? "up" : "down";
      sb.append("<td class='").append(cls).append("'>").append(esc(fmt.format(v))).append("</td>");
    }
    sb.append("</tr>");
  }

  private String esc(Object o) {
    if (o == null) return "";
    return String.valueOf(o)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }
}
