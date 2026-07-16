package com.quant.service;

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

import com.quant.dto.stockanalysis.StockAnalysisResponse;
import com.quant.entity.StockAnalysisRecord;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 个股分析 PDF 导出 (Playwright headless chromium 渲染 HTML 模板) */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockAnalysisPdfService {

  private final StockAnalysisService stockAnalysisService;

  @Value("${app.upload-dir:uploads}")
  private String uploadDir;

  @Value("${app.render-pdf-script:scripts/render_pdf.py}")
  private String renderPdfScript;

  /**
   * 生成 PDF (从 record 加载, 调 stockAnalysisService 解析 result_json)
   *
   * @return 相对 uploadDir 的路径, 如 "stock-analysis/688627/5-20260612-145212.pdf"
   */
  public String generate(StockAnalysisRecord rec) {
    if (rec == null) throw new IllegalArgumentException("记录不存在");
    if (!"SUCCESS".equals(rec.getStatus())) {
      throw new IllegalStateException("仅 SUCCESS 状态的记录可生成 PDF, 当前: " + rec.getStatus());
    }
    // 已有则直接复用
    if (rec.getPdfPath() != null && Files.exists(Paths.get(uploadDir, rec.getPdfPath()))) {
      log.info("PDF 已存在, 复用: {}", rec.getPdfPath());
      return rec.getPdfPath();
    }

    StockAnalysisResponse report = stockAnalysisService.parseRecordJson(rec);
    if (report == null) throw new IllegalStateException("研报数据为空");

    String codeRaw = rec.getStockCodeRaw() == null ? rec.getStockCode() : rec.getStockCodeRaw();
    String html = rec.getReportHtml();
    if (html == null || html.isBlank()) {
      html = report.getReportHtml();
    }
    if (html == null || html.isBlank()) {
      html = renderHtml(report, rec);
    }
    String fileName =
        String.format(
            "stock-analysis/%s/%d-%s.pdf",
            codeRaw,
            rec.getId(),
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
    Path outPath = Paths.get(uploadDir, fileName);
    try {
      Files.createDirectories(outPath.getParent());
    } catch (IOException e) {
      throw new RuntimeException("创建 PDF 目录失败: " + outPath.getParent(), e);
    }
    htmlToPdf(html, outPath);
    log.info("PDF 生成成功: {} ({} bytes)", fileName, outPath.toFile().length());
    return fileName;
  }

  private void htmlToPdf(String html, Path outPath) {
    // 脚本路径：先按绝对路径试，再按相对路径（相对当前进程 cwd）试
    java.io.File scriptFile = new java.io.File(renderPdfScript);
    if (!scriptFile.isAbsolute()) {
      // 相对路径 → 相对 user.dir（restart.sh 启动时 cwd 是项目根目录）
      scriptFile = new java.io.File(System.getProperty("user.dir"), renderPdfScript);
    }
    if (!scriptFile.exists()) {
      throw new RuntimeException(
          "PDF 渲染脚本不存在: "
              + scriptFile.getAbsolutePath()
              + "（可在 application.yml 用 app.render-pdf-script 或环境变量 RENDER_PDF_SCRIPT 覆盖）");
    }
    ProcessBuilder pb =
        new ProcessBuilder(
            "python3", scriptFile.getAbsolutePath(), outPath.toAbsolutePath().toString());
    pb.redirectErrorStream(true);
    Process process = null;
    try {
      process = pb.start();
      // 通过 stdin 传 HTML (避免命令行长度限制)
      process.getOutputStream().write(html.getBytes(StandardCharsets.UTF_8));
      process.getOutputStream().close();
      boolean done = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
      if (!done) {
        process.destroyForcibly();
        throw new RuntimeException("PDF 渲染超时 (60s)");
      }
      if (process.exitValue() != 0) {
        // 读取 stdout/stderr (合并流)
        throw new RuntimeException(
            "PDF 渲染失败, 退出码 " + process.exitValue() + "（脚本: " + scriptFile.getAbsolutePath() + "）");
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

  // ============================================================
  // HTML 模板 (可打印友好)
  // ============================================================
  private String renderHtml(StockAnalysisResponse r, StockAnalysisRecord rec) {
    StringBuilder sb = new StringBuilder(8192);
    sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'><title>")
        .append(esc(r.getName()))
        .append(" 投资分析报告</title>")
        .append("<style>")
        .append(
            """
              @page { size: A4; margin: 18mm 15mm; }
              * { box-sizing: border-box; }
              body { font-family: "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", sans-serif;
                     color: #1a2233; font-size: 11pt; line-height: 1.6; margin: 0; }
              h1 { font-size: 22pt; color: #1e88ff; margin: 0 0 6pt; border-bottom: 3px solid #1e88ff; padding-bottom: 6pt; }
              .sub { color: #6b7280; font-size: 10pt; margin-bottom: 12pt; }
              h2 { font-size: 14pt; color: #1a2233; margin: 14pt 0 6pt;
                   border-left: 4px solid #1e88ff; padding-left: 8pt; }
              h3 { font-size: 12pt; color: #1a2233; margin: 10pt 0 4pt; }
              .meta-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 6pt; margin: 6pt 0; }
              .meta-item { background: #f5f7fb; padding: 6pt 8pt; border-radius: 4pt; }
              .meta-label { font-size: 8pt; color: #6b7280; }
              .meta-value { font-size: 12pt; font-weight: 600; margin-top: 2pt; }
              .meta-value.up { color: #e74c3c; }
              .meta-value.down { color: #27ae60; }
              .verdict { display: inline-block; padding: 4pt 12pt; border-radius: 14pt;
                         font-size: 10pt; font-weight: 600; margin-top: 4pt; }
              .verdict-green { background: #d4edda; color: #155724; }
              .verdict-yellow { background: #fff3cd; color: #856404; }
              .verdict-gray { background: #e2e3e5; color: #383d41; }
              .cat-list, .risk-list { padding-left: 16pt; margin: 4pt 0; }
              .cat-list li, .risk-list li { margin-bottom: 3pt; font-size: 10pt; }
              table { width: 100%; border-collapse: collapse; margin: 6pt 0; font-size: 10pt; }
              th, td { border: 1px solid #d0d7e2; padding: 4pt 6pt; text-align: left; }
              th { background: #e3f2fd; font-weight: 600; }
              .footer { margin-top: 20pt; padding-top: 6pt; border-top: 1px solid #d0d7e2;
                        color: #9aa4b2; font-size: 8pt; text-align: center; }
              .chart-box { background: #fafbfc; border: 1px solid #e0e7ef; border-radius: 6pt;
                           padding: 8pt; margin: 6pt 0; text-align: center; }
              .chart-bar { display: inline-block; width: 10pt; background: #1e88ff; margin: 0 1pt;
                           vertical-align: bottom; }
              """)
        .append("</style></head><body>");

    // 标题
    sb.append("<h1>")
        .append(esc(r.getName()))
        .append(" (")
        .append(esc(rec.getStockCodeRaw()))
        .append(") 投资分析报告</h1>");
    sb.append("<div class='sub'>紫苏叶产业链拆解 + 高景气九维框架 · 数据源: baostock · 生成时间: ")
        .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
        .append("</div>");

    // 1. 总览
    sb.append("<h2>1. 总览</h2>");
    Double price = r.getCurrentPrice();
    Double changePct = null;
    if (r.getNineDimension() != null
        && r.getNineDimension().get("market") instanceof java.util.Map) {
      Object pct =
          ((java.util.Map<?, ?>) r.getNineDimension().get("market")).get("periodChangePct");
      if (pct != null) {
        try {
          changePct = Double.parseDouble(String.valueOf(pct).replace("%", ""));
        } catch (Exception ignore) {
        }
      }
    }
    sb.append("<div class='meta-grid'>");
    sb.append(metaItem("现价", price == null ? "-" : String.format("%.2f 元", price), changePct));
    sb.append(metaItem("紫苏叶判定", esc(r.getVerdict()), null));
    sb.append(metaItem("护城河", r.getMoatScore() + "/10", null));
    sb.append(metaItem("耗时", r.getElapsedMs() + " ms", null));
    sb.append("</div>");

    // 2. 财务摘要
    if (r.getFinancialSummary() != null
        && r.getFinancialSummary().get("periodLabels") instanceof List) {
      sb.append("<h2>2. 财务趋势 (近 ")
          .append(((List<?>) r.getFinancialSummary().get("periodLabels")).size())
          .append(" 季度)</h2>");
      List<String> labels = (List<String>) r.getFinancialSummary().get("periodLabels");
      List<Double> roe = (List<Double>) r.getFinancialSummary().get("roeList");
      List<Double> gm = (List<Double>) r.getFinancialSummary().get("grossMarginList");
      List<Double> nm = (List<Double>) r.getFinancialSummary().get("netMarginList");
      List<Double> yoy = (List<Double>) r.getFinancialSummary().get("yoyNetProfitList");
      sb.append("<table><thead><tr><th>期间</th>");
      for (String l : labels) sb.append("<th>").append(esc(l)).append("</th>");
      sb.append("</tr></thead><tbody>");
      appendRow(sb, "ROE %", roe, true);
      appendRow(sb, "毛利率 %", gm, true);
      appendRow(sb, "净利率 %", nm, true);
      appendRow(sb, "净利 YoY %", yoy, false);
      sb.append("</tbody></table>");
    }

    // 3. 紫苏叶 - 产业链定位
    if (r.getChainPosition() != null) {
      sb.append("<h2>3. 紫苏叶 · 产业链定位</h2>");
      java.util.Map<?, ?> c = r.getChainPosition();
      sb.append("<p><b>行业:</b> ").append(esc(c.get("industry"))).append("</p>");
      sb.append("<p><b>位置:</b> ").append(esc(c.get("layer"))).append("</p>");
      sb.append("<p><b>护城河类型:</b> ").append(esc(c.get("moatType"))).append("</p>");
      sb.append("<p><b>拆解路径:</b> ").append(esc(c.get("chainPath"))).append("</p>");
    }

    // 4. 紫苏叶 - 竞争格局
    if (r.getCompetition() != null) {
      sb.append("<h2>4. 紫苏叶 · 全球竞争格局</h2>");
      java.util.Map<?, ?> comp = r.getCompetition();
      sb.append("<p><b>玩家:</b> ").append(esc(comp.get("globalPlayers"))).append("</p>");
      sb.append("<p><b>中国位置:</b> ").append(esc(comp.get("chinesePosition"))).append("</p>");
      sb.append("<p><b>地缘优势:</b> ").append(esc(comp.get("geographicAdvantage"))).append("</p>");
    }

    // 5. 紫苏叶 - 三问
    if (r.getThreeQuestions() != null) {
      sb.append("<h2>5. 紫苏叶 · 下单前三问</h2>");
      java.util.Map<?, ?> q = r.getThreeQuestions();
      sb.append("<table><tbody>");
      sb.append("<tr><th>① 不可替代?</th><td>")
          .append(esc(q.get("Q1_irreplaceable")))
          .append("</td></tr>");
      sb.append("<tr><th>② 玩家数?</th><td>")
          .append(esc(q.get("Q2_competitorCount")))
          .append("</td></tr>");
      sb.append("<tr><th>③ 需求?</th><td>").append(esc(q.get("Q3_demandTrend"))).append("</td></tr>");
      sb.append("</tbody></table>");
    }

    // 6. 催化剂 & 风险
    if (r.getCatalysts() != null && !r.getCatalysts().isEmpty()) {
      sb.append("<h2>6. 📢 核心催化剂</h2><ul class='cat-list'>");
      for (String c : r.getCatalysts()) sb.append("<li>").append(esc(c)).append("</li>");
      sb.append("</ul>");
    }
    if (r.getRisks() != null && !r.getRisks().isEmpty()) {
      sb.append("<h2>7. ⚠️ 关键风险</h2><ul class='risk-list'>");
      for (String risk : r.getRisks()) sb.append("<li>").append(esc(risk)).append("</li>");
      sb.append("</ul>");
    }

    // 8. 九维摘要
    if (r.getNineDimension() != null
        && r.getNineDimension().get("financial") instanceof java.util.Map) {
      java.util.Map<?, ?> f = (java.util.Map<?, ?>) r.getNineDimension().get("financial");
      sb.append("<h2>8. 高景气九维 · 财务摘要</h2>");
      sb.append("<table><tbody>");
      sb.append("<tr><th>报告期</th><td>").append(esc(f.get("latestPeriod"))).append("</td></tr>");
      sb.append("<tr><th>最新 ROE</th><td>").append(esc(f.get("roe"))).append("</td></tr>");
      sb.append("<tr><th>最新毛利率</th><td>").append(esc(f.get("grossMargin"))).append("</td></tr>");
      sb.append("<tr><th>最新净利率</th><td>").append(esc(f.get("netMargin"))).append("</td></tr>");
      sb.append("<tr><th>净利 YoY</th><td>").append(esc(f.get("yoyNetProfit"))).append("</td></tr>");
      sb.append("<tr><th>EPS-TTM</th><td>").append(esc(f.get("epsTtm"))).append("</td></tr>");
      sb.append("</tbody></table>");
    }

    // footer
    sb.append("<div class='footer'>本报告由 AI 基于 baostock 公开数据 + 紫苏叶方法论生成 · 不构成投资建议 · ")
        .append("记录ID: ")
        .append(rec.getId())
        .append(" · 方法: ")
        .append(esc(rec.getMethod()))
        .append("</div>");

    sb.append("</body></html>");
    return sb.toString();
  }

  private void appendRow(StringBuilder sb, String label, List<Double> values, boolean percent) {
    sb.append("<tr><td>").append(esc(label)).append("</td>");
    if (values == null) {
      sb.append("<td>-</td>");
      return;
    }
    for (Double v : values) {
      if (v == null) sb.append("<td>-</td>");
      else if (percent) sb.append("<td>").append(String.format("%.2f%%", v * 100)).append("</td>");
      else sb.append("<td>").append(String.format("%.2f%%", v * 100)).append("</td>");
    }
    sb.append("</tr>");
  }

  private String metaItem(String label, String value, Double changePct) {
    String cls = "";
    if (changePct != null) cls = changePct >= 0 ? " up" : " down";
    return "<div class='meta-item'><div class='meta-label'>"
        + esc(label)
        + "</div>"
        + "<div class='meta-value"
        + cls
        + "'>"
        + esc(value)
        + "</div></div>";
  }

  private String esc(Object o) {
    if (o == null) return "-";
    return String.valueOf(o)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  public File resolvePdfFile(String relativePath) {
    if (relativePath == null || relativePath.isBlank()) return null;
    Path p = Paths.get(uploadDir, relativePath).normalize();
    if (!p.startsWith(Paths.get(uploadDir).normalize())) return null; // 防穿越
    return p.toFile();
  }
}
