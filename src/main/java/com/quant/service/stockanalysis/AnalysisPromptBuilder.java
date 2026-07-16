package com.quant.service.stockanalysis;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.quant.dto.stockanalysis.WindResearchContext;
import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockFinancial;
import com.quant.repository.TradeStockFinancialRepository;
import com.quant.service.StockQueryService;
import com.quant.service.prosperitystrong.WindAifinMarketClient;
import com.quant.service.search.WebSearchClient;
import com.quant.service.tdx.TdxMcpClient;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 拼装 AI 分析用的 prompt：财务表 + baostock 行情 + forecast + 联网检索 + Wind 研报 + TDX 结构化数据 + JSON schema。
 *
 * <p>JSON schemas 体积较大 (~330 行)，放在 resources/stockanalysis/ai-schemas.json，本类在启动时加载一次。 从 {@code
 * StockAnalysisService.buildPrompt +
 * appendSearch/appendWindResearchContext/appendWindFinancialDocs/appendTdxFinanceData +
 * SYSTEM_PROMPT/FIVE_DIM_SYSTEM_PROMPT/JSON_SCHEMA/FIVE_DIM_JSON_SCHEMA} 拆出。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisPromptBuilder {

  private static final String SCHEMA_RESOURCE = "stockanalysis/ai-schemas.json";

  private final StockQueryService stockQueryService;
  private final TradeStockFinancialRepository financialRepository;
  private final WebSearchClient webSearchClient;
  private final WindAifinMarketClient windAifinMarketClient;
  private final TdxMcpClient tdxMcpClient;
  private final NineDimensionComposer util;
  private final ObjectMapper objectMapper;

  public static final String SYSTEM_PROMPT =
      "你是一名资深的 A 股价值景气投资分析师，擅长从全球产业趋势、行业周期、国家政策、公司基本面、管理层、估值、技术面、资金面进行全维度分析。"
          + "请严格按照用户给出的 JSON Schema 输出，不要使用 markdown，不要输出任何解释或前后多余文字，输出必须是合法的 JSON。";

  public static final String FIVE_DIM_SYSTEM_PROMPT =
      "你是一名资深的 A 股产业研究与成长股估值分析师，专注用「五维模型 + 市值阶梯式增长路径」拆解公司的中长期投资价值。"
          + "五维模型分别是：稀缺卡位、成长动力、业绩兑现、瓶颈壁垒、估值阶梯。"
          + "你的核心方法论是：(1) 优先用联网检索获得的产业链证据作支撑，引用时要给出依据；"
          + "(2) 检索不到的深度数据（设备数量、产能爬坡、订单排期等），明确写\"未检索到，待人工核实\"，**绝不编造具体数字**；"
          + "(3) 每个维度先给定量数据，再给定性结论；(4) 估值阶梯用 PE+PS 双体系交叉验证。"
          + "请严格按照用户给出的 JSON Schema 输出，不要使用 markdown，不要输出任何解释或前后多余文字，输出必须是合法的 JSON。";

  private String jsonSchema;
  private String fiveDimJsonSchema;

  @PostConstruct
  void loadSchemas() throws IOException {
    try (InputStream in = new ClassPathResource(SCHEMA_RESOURCE).getInputStream()) {
      JsonNode root = objectMapper.readTree(in.readAllBytes());
      jsonSchema = root.path("default").asText();
      fiveDimJsonSchema = root.path("five_dimension").asText();
      if (jsonSchema.isEmpty() || fiveDimJsonSchema.isEmpty()) {
        throw new IllegalStateException(SCHEMA_RESOURCE + " missing default / five_dimension keys");
      }
      log.info(
          "加载 AI schemas 成功: default={} chars, five_dimension={} chars",
          jsonSchema.length(),
          fiveDimJsonSchema.length());
    }
  }

  public String pickSystemPrompt(String method) {
    return "five_dimension".equalsIgnoreCase(method) ? FIVE_DIM_SYSTEM_PROMPT : SYSTEM_PROMPT;
  }

  /** 拼完整 user prompt：基础信息 + 财务表 + baostock 行情/历史/forecast + 联网检索 + Wind 研报 + JSON schema。 */
  public String buildPrompt(
      TradeStockBasic basic,
      Map<String, Object> rawData,
      String method,
      WindResearchContext windResearch) {
    StringBuilder sb = new StringBuilder();
    boolean isFiveDim = "five_dimension".equalsIgnoreCase(method);
    sb.append("分析日期: ").append(LocalDate.now()).append('\n');
    sb.append("公司: ")
        .append(basic.getStockName())
        .append(' ')
        .append(basic.getStockCode())
        .append(" (A股)\n");
    if (basic.getSectorNames() != null)
      sb.append("所属行业: ").append(basic.getSectorNames()).append('\n');
    if (basic.getPeTtm() != null) sb.append("PE-TTM: ").append(basic.getPeTtm()).append('\n');
    if (basic.getPb() != null) sb.append("PB: ").append(basic.getPb()).append('\n');
    if (basic.getPsTtm() != null) sb.append("PS-TTM: ").append(basic.getPsTtm()).append('\n');
    if (basic.getTotalShares() != null)
      sb.append("总股本: ").append(basic.getTotalShares()).append(" 亿股\n");

    appendFinancialTable(sb, basic.getStockCode());
    appendBaostock(sb, rawData);
    appendWebSearch(sb, basic.getStockName(), isFiveDim);

    if (isFiveDim) {
      appendWindFinancialDocs(sb, basic.getStockName());
      appendTdxFinanceData(sb, basic.getStockName());
    }
    appendWindResearchContext(sb, windResearch, method);

    sb.append("\n请严格按照下方 JSON 格式输出，不要输出任何额外文字、不要使用 markdown：\n");
    sb.append(isFiveDim ? fiveDimJsonSchema : jsonSchema);
    return sb.toString();
  }

  private void appendFinancialTable(StringBuilder sb, String stockCode) {
    List<TradeStockFinancial> records =
        financialRepository.findByStockCodeOrderByReportDateDesc(stockCode).stream()
            .limit(12)
            .toList();
    if (records.isEmpty()) return;
    sb.append("\n最近 ").append(records.size()).append(" 季度财务（单位：元）:\n");
    sb.append("报告期 | 营收 | 净利润 | EPS | ROE | 毛利率 | 净利率 | 营收同比 | 扣非同比\n");
    for (TradeStockFinancial f : records) {
      sb.append(
              joinRow(
                  f.getReportDate(),
                  util.safe(f.getRevenue()),
                  util.safe(f.getNetProfit()),
                  util.safe(f.getEps()),
                  util.safe(f.getRoe()),
                  util.safe(f.getGrossMargin()),
                  util.safe(f.getNetMargin()),
                  util.safe(f.getRevenueYoy()),
                  util.safe(f.getDeductedNetProfitYoy())))
          .append('\n');
    }
  }

  private static String joinRow(Object... cells) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < cells.length; i++) {
      if (i > 0) sb.append(" | ");
      sb.append(cells[i] == null ? "" : cells[i].toString());
    }
    return sb.toString();
  }

  private void appendBaostock(StringBuilder sb, Map<String, Object> rawData) {
    Map<String, Object> quote = util.asMap(rawData.get("quote"));
    if (!quote.isEmpty()) {
      sb.append("\nbaostock 行情数据:\n");
      String[][] qkv = {
        {"收盘: ", quote.get("close")},
        {"成交量: ", quote.get("volume")},
        {"换手率: ", quote.get("turn")},
        {"区间最高: ", quote.get("period_high")},
        {"区间最低: ", quote.get("period_low")},
        {"区间涨跌幅: ", quote.get("period_change_pct")}
      };
      for (String[] e : qkv) sb.append(e[0]).append(util.safe(e[1])).append('\n');
    }
    List<Object> finHistory = util.asList(rawData.get("financial_history"));
    if (!finHistory.isEmpty()) {
      sb.append("\nbaostock 财务历史 (近 ").append(finHistory.size()).append(" 季度):\n");
      sb.append("报告期 | ROE | 毛利率 | 净利率 | 营收YoY | 净利YoY\n");
      for (Object item : finHistory) {
        Map<String, Object> rec = util.asMap(item);
        Map<String, Object> p = util.asMap(rec.get("profitability"));
        Map<String, Object> g = util.asMap(rec.get("growth"));
        sb.append(
                joinRow(
                    util.safe(rec.get("statDate")),
                    util.safe(p.get("roe_avg")),
                    util.safe(p.get("gp_margin")),
                    util.safe(p.get("np_margin")),
                    util.safe(g.get("yoy_revenue")),
                    util.safe(g.get("yoy_ni"))))
            .append('\n');
      }
    }
    List<Object> forecast = util.asList(rawData.get("forecast"));
    if (!forecast.isEmpty()) {
      sb.append("\nforecast 数据:\n");
      for (Object item : forecast) sb.append("- ").append(util.safe(item)).append('\n');
    }
  }

  private void appendWebSearch(StringBuilder sb, String name, boolean isFiveDim) {
    if (!webSearchClient.isEnabled()) {
      sb.append("\n（未启用联网检索，请仅基于已知信息分析）\n");
      return;
    }
    if (isFiveDim) {
      appendSearch(sb, name + " 产业链 行业地位 卡脖子 国产替代 全球玩家");
      appendSearch(sb, name + " 订单 产能 客户结构 大客户认证 第二曲线 业务拆分");
      appendSearch(sb, name + " 估值 目标价 机构预测 券商研报 PS PE");
      appendSearch(sb, name + " 风险 瓶颈 产能爬坡 地缘 供应链");
      sb.append("\n⚠️ 重要约束：产业链深度数据（设备数量、产能、订单排期等）如果联网检索不到，");
      sb.append("请明确在对应字段写\"未检索到，待人工核实\"，**禁止编造具体数字**。\n");
    } else {
      appendSearch(sb, name + " 行业景气度 机构预测 目标价");
      appendSearch(sb, name + " 主力资金 北向资金 龙虎榜");
    }
  }

  private void appendSearch(StringBuilder sb, String query) {
    List<WebSearchClient.SearchResult> results = webSearchClient.search(query);
    if (results.isEmpty()) return;
    sb.append("【").append(query).append("】\n");
    for (WebSearchClient.SearchResult result : results) sb.append(result.toLine()).append('\n');
  }

  /**
   * 把 Wind 研报 + 一致预期塞进 prompt。 一致预期是估值段最高优先级证据——强制 AI 引用； 研报片段按 method 加权 (purple/gaojingqi 5 条,
   * full/五维 2 条)。
   */
  void appendWindResearchContext(StringBuilder sb, WindResearchContext ctx, String method) {
    if (ctx == null) {
      sb.append("\n（Wind 研报：本次未拉取，跳过）\n");
      return;
    }
    if (!ctx.isWindInstalled() || !ctx.isWindHasKey()) {
      sb.append("\n（Wind 研报：未安装或无 API Key，跳过）\n");
      return;
    }
    if (!ctx.isAvailable()) {
      sb.append("\n（Wind 研报：本次拉取无可用数据，不影响主报告）\n");
      return;
    }

    WindResearchContext.Consensus c = ctx.getConsensus();
    sb.append("\n【Wind 一致预期（卖方共识, 估值段最高优先级证据 ⚠️）】\n");
    if (c != null && c.getSourceRowCount() > 0) {
      String r = c.getRating(), cur = c.getCurrency();
      Double tp = c.getTargetPrice(), e26 = c.getEps2026(), e27 = c.getEps2027();
      Double g26 = c.getNetProfitGrowth2026(), g27 = c.getNetProfitGrowth2027();
      if (r != null) sb.append("  综合评级: ").append(r).append('\n');
      if (tp != null) sb.append("  一致预期目标价: ").append(tp).append(" 元\n");
      if (cur != null) sb.append("  货币: ").append(cur).append('\n');
      if (e26 != null) sb.append("  一致预期 2026 EPS: ").append(e26).append(" 元\n");
      if (e27 != null) sb.append("  一致预期 2027 EPS: ").append(e27).append(" 元\n");
      if (g26 != null) sb.append("  一致预期 2026 净利同比: ").append(g26).append("%\n");
      if (g27 != null) sb.append("  一致预期 2027 净利同比: ").append(g27).append("%\n");
    } else {
      sb.append("  （本次未取到一致预期结构化数据，仅供参考）\n");
    }
    sb.append(
        "\n⚠️ 强制要求: 你的估值段 (target2026 / target2027 / verdict / reasoning) 必须围绕上述一致预期目标价和 EPS 生成。\n");
    sb.append("  - 如果 AI 推算目标价与一致预期偏离 ±20% 以上, 必须在 reasoning 字段说明偏离原因。\n");
    sb.append("  - 一致预期未提供具体数字时, 可以自由推算, 但仍需引用评级 (增持/买入/中性) 作为定性锚点。\n");
    sb.append("  - 不允许完全忽略一致预期, 不允许编造评级。\n");

    List<WindResearchContext.ResearchExcerpt> reports = ctx.getReports();
    if (reports == null || reports.isEmpty()) {
      sb.append("\n（Wind 研报片段: 本次未检索到）\n");
      return;
    }
    int maxShow =
        ("purple_perilla".equalsIgnoreCase(method) || "gaojingqi".equalsIgnoreCase(method)) ? 5 : 2;
    List<WindResearchContext.ResearchExcerpt> picked =
        reports.subList(0, Math.min(maxShow, reports.size()));
    sb.append("\n【Wind 研报片段（来自 financial_docs 检索, doc_type=")
        .append(picked.get(0).getDocType())
        .append(")】\n");
    for (int i = 0; i < picked.size(); i++) {
      WindResearchContext.ResearchExcerpt r = picked.get(i);
      sb.append("\n▍研报 #").append(i + 1);
      if (r.getSource() != null) sb.append(" | ").append(r.getSource());
      if (r.getDate() != null) sb.append(" | ").append(r.getDate());
      sb.append('\n').append("  标题: ").append(util.safe(r.getTitle())).append('\n');
      sb.append("  摘要: ").append(util.safe(r.getContent())).append('\n');
    }
    sb.append("\n⚠️ 上面是从 Wind 卖方研报/财经媒体抓到的片段, 优先级高于普通联网检索。\n");
  }

  /** Wind financial_docs RAG：拉取本股的公告 + 财经新闻 + 投资者互动答复。五维独享。 */
  private void appendWindFinancialDocs(StringBuilder sb, String stockName) {
    if (!windAifinMarketClient.isInstalled() || !windAifinMarketClient.hasApiKey()) {
      sb.append("\n（Wind financial_docs 未启用或无 API Key，跳过）\n");
      return;
    }
    sb.append("\n【Wind financial_docs 检索 · 高优先级证据（公告 + 财经新闻 + 投资者互动）】\n");
    String cn = stockName == null ? "" : stockName.replaceAll("\\s+", "");
    String[][] newsQueries = {
      {"HBM订单", "▍投资者互动/HBM/订单"},
      {"海外客户", "▍海外大客户/三星/SK海力士"},
      {"产能", "▍产能/募投/南浔"}
    };
    String[][] annQueries = {
      {"半导体", "▍公司公告/半导体"},
      {"2025年报", "▍公司公告/2025 年报"}
    };
    try {
      for (String[] q : newsQueries)
        windItem(sb, "financial_docs", "get_financial_news", cn + q[0], 3, q[1]);
    } catch (Exception e) {
      log.warn("Wind financial_docs 检索失败: {}", e.getMessage());
      sb.append("（Wind 检索异常: ").append(e.getMessage()).append("）\n");
    }
    try {
      for (String[] q : annQueries)
        windItem(sb, "financial_docs", "get_company_announcements", cn + q[0], 2, q[1]);
    } catch (Exception e) {
      log.warn("Wind announcements 检索失败: {}", e.getMessage());
    }
    sb.append("\n⚠️ Wind financial_docs 是 RAG 检索结果（基于上交所/深交所/财经媒体原始数据）。\n");
    sb.append("其中\"投资者互动\"板块的答复是公司官方回应，**优先级最高**——尤其涉及客户/订单/HBM/产能/海外认证的答复，必须当作高确定性证据写入对应维度。\n");
  }

  /** TDX 自然语言查询：补 Wind 拿不到的结构化财务/行业地位。 */
  private void appendTdxFinanceData(StringBuilder sb, String stockName) {
    if (!tdxMcpClient.isAuthorized()) {
      sb.append("\n（TDX 通达信 MCP 未配置 API Key, 跳过结构化财务查询。\n");
      sb.append("  如需启用, 请在 application.yml 设置 prosperity-strong.tdx.api-key = TDX-c62ebd01...\n");
      sb.append("  或环境变量 TDX_API_KEY=...）\n");
      return;
    }
    sb.append("\n【通达信 MCP 结构化财务数据（NL 查询,补充 Wind 拿不到的具体数字）】\n");
    String stockCode = resolveStockCode(stockName);
    if (stockCode == null || stockCode.isBlank()) {
      sb.append("（未能解析股票代码, 跳过 TDX 查询）\n");
      return;
    }
    String[][] queries = {
      {"2025年报 关键财务指标", "▍2025 年报关键指标"},
      {"主营业务收入 同比", "▍最新营收/利润同比"},
      {"行业地位", "▍行业地位/投资逻辑"},
      {"一致预期 EPS", "▍一致预期 EPS（卖方共识）"}
    };
    try {
      for (String[] q : queries) tdxAskAppend(sb, stockCode + " " + q[0], q[1]);
    } catch (Exception e) {
      log.warn("TDX 财务查询失败: {}", e.getMessage());
      sb.append("（TDX 财务查询异常: ").append(e.getMessage()).append("）\n");
    }
    sb.append("\n⚠️ 上面是 TDX 通过自然语言问出的结构化数据（营收/净利同比/行业地位描述）。\n");
    sb.append("这些是**结构化字段**（不是研报文本），优先级与 Wind 文本证据相当。\n");
  }

  private void tdxAskAppend(StringBuilder sb, String question, String label) {
    Optional<JsonNode> respOpt = tdxMcpClient.ask(question);
    sb.append('\n').append(label).append("：\n");
    if (respOpt == null || respOpt.isEmpty()) {
      sb.append("  （未返回, 跳过）\n");
      return;
    }
    int total = respOpt.get().path("meta").path("total").asInt(0);
    if (total == 0) {
      sb.append("  （TDX 返回 0 条, 此 query 不适用此股）\n");
      return;
    }
    sb.append("  ").append(TdxMcpClient.tableToText(respOpt.get(), 5).replace("\n", "\n  "));
  }

  /** 调一次 Wind 工具，提取 items[].content/title/date，拼成文本塞进 sb。 */
  private void windItem(
      StringBuilder sb, String serverType, String toolName, String query, int topK, String label) {
    try {
      JsonNode root =
          windAifinMarketClient.call(serverType, toolName, Map.of("query", query, "top_k", topK));
      if (root == null) return;
      JsonNode textNode = root.path("content").path(0).path("text");
      if (textNode.isMissingNode()) return;
      JsonNode items = objectMapper.readTree(textNode.asText()).path("data").path("items");
      if (!items.isArray() || items.isEmpty()) {
        sb.append(label).append("：未检索到。\n");
        return;
      }
      sb.append('\n').append(label).append("：\n");
      int shown = 0;
      for (JsonNode item : items) {
        if (shown >= 3) break;
        String content = item.path("content").asText("");
        if (content.length() > 500) content = content.substring(0, 500) + "...";
        String header =
            "  - [" + item.path("date").asText("") + "] " + item.path("title").asText("");
        sb.append(header).append('\n').append("    ").append(content).append('\n');
        shown++;
      }
    } catch (Exception e) {
      sb.append(label).append("：检索失败 - ").append(e.getMessage()).append('\n');
    }
  }

  private String resolveStockCode(String stockName) {
    if (stockName == null) return null;
    String s = stockName.trim();
    if (s.matches(".*\\d{6}.*")) return s.replaceAll("[^0-9]", "").substring(0, 6);
    try {
      return stockQueryService.resolveStock(s).map(TradeStockBasic::getStockCode).orElse(null);
    } catch (Exception e) {
      return null;
    }
  }
}
