package com.quant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.quant.dto.stockanalysis.StockAnalysisResponse;
import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockFinancial;
import com.quant.repository.TradeStockFinancialRepository;
import com.quant.service.ai.MiniMaxClient;
import com.quant.service.ai.SenseNovaClient;
import com.quant.service.search.WebSearchClient;

@ExtendWith(MockitoExtension.class)
@DisplayName("UnifiedStockResearchService")
class UnifiedStockResearchServiceTest {

  @Mock TradeStockFinancialRepository financialRepository;
  @Mock AStockDataQuoteService aStockDataQuoteService;
  @Mock WebSearchClient webSearchClient;
  @Mock MiniMaxClient miniMaxClient;
  @Mock SenseNovaClient senseNovaClient;

  private UnifiedStockResearchService service;

  @BeforeEach
  void setUp() {
    // 默认实时行情返回麦格米特 25.30，覆盖最新股价逻辑
    org.mockito.Mockito.lenient()
        .when(aStockDataQuoteService.fetchQuotes(any()))
        .thenReturn(
            Map.of(
                "002851.SZ",
                new AStockDataQuoteService.QuoteSnapshot(
                    "002851.SZ",
                    new BigDecimal("25.30"),
                    new BigDecimal("24.80"),
                    new BigDecimal("253"),
                    LocalDateTime.now(),
                    "a-stock-data/tencent")));
    service =
        new UnifiedStockResearchService(
            financialRepository,
            aStockDataQuoteService,
            webSearchClient,
            miniMaxClient,
            senseNovaClient);
  }

  @Test
  @DisplayName("缺少 forecast 与检索数据时仍生成统一报告并标记来源缺失")
  void buildUnifiedResponseDegradesMissingSources() {
    TradeStockBasic basic = basic();
    when(financialRepository.findByStockCodeOrderByReportDateDesc("002851.SZ"))
        .thenReturn(List.of(financial()));
    when(webSearchClient.isEnabled()).thenReturn(false);

    StockAnalysisResponse response =
        service.buildUnifiedResponse(
            basic,
            Map.of(
                "basic", Map.of("code_name", "麦格米特"),
                "quote",
                    Map.of(
                        "close",
                        25.30,
                        "turn",
                        0.12,
                        "period_high",
                        28.0,
                        "period_low",
                        21.0,
                        "period_change_pct",
                        0.18),
                "financial_history",
                    List.of(
                        Map.of(
                            "statDate", "2025-12-31",
                            "profitability",
                                Map.of(
                                    "roe_avg",
                                    0.15,
                                    "gp_margin",
                                    0.31,
                                    "np_margin",
                                    0.12,
                                    "eps_ttm",
                                    1.23),
                            "growth", Map.of("yoy_ni", 0.25, "yoy_revenue", 0.20)))),
            Map.of(
                "industry", Map.of("cyclePosition", "景气上行"),
                "summary", Map.of("oneLiner", "结论积极", "bullets", List.of("需求向上"))),
            "full",
            1234L);

    assertThat(response.getSourceMetadata())
        .containsKeys("db", "baostock", "forecast", "webSearch", "aStockData");
    assertThat(((Map<?, ?>) response.getSourceMetadata().get("forecast")).get("available"))
        .isEqualTo(false);
    assertThat(((Map<?, ?>) response.getSourceMetadata().get("webSearch")).get("available"))
        .isEqualTo(false);
    assertThat(response.getExternalExpectation().get("summary")).isEqualTo("暂无可用结构化数据");
    assertThat(response.getDbFinancials()).hasSize(1);
    assertThat(response.getReportHtml()).contains("数据来源状态");
    assertThat(response.getReportHtml()).contains("暂无可用结构化数据");
    // 当前价应来自 a-stock-data 实时接口
    assertThat(response.getCurrentPrice()).isEqualTo(25.30);
  }

  @Test
  @DisplayName("存在 forecast 与检索摘要时写入估值预期章节")
  void buildUnifiedResponseIncludesForecastAndSearchSummary() {
    TradeStockBasic basic = basic();
    when(financialRepository.findByStockCodeOrderByReportDateDesc("002851.SZ"))
        .thenReturn(List.of(financial()));
    when(webSearchClient.isEnabled()).thenReturn(true);
    when(webSearchClient.search("麦格米特 机构预测 盈利预测 目标价"))
        .thenReturn(
            List.of(
                new WebSearchClient.SearchResult(
                    "研报摘要", "https://example.com", "机构预期 2026 年利润继续增长")));

    StockAnalysisResponse response =
        service.buildUnifiedResponse(
            basic,
            Map.of(
                "basic", Map.of("code_name", "麦格米特"),
                "quote", Map.of("close", 25.30),
                "forecast", List.of(Map.of("type", "业绩预告", "content", "预计净利润增长 20%-30%")),
                "financial_history",
                    List.of(
                        Map.of(
                            "statDate", "2025-12-31",
                            "profitability",
                                Map.of(
                                    "roe_avg",
                                    0.15,
                                    "gp_margin",
                                    0.31,
                                    "np_margin",
                                    0.12,
                                    "eps_ttm",
                                    1.23),
                            "growth", Map.of("yoy_ni", 0.25, "yoy_revenue", 0.20)))),
            Map.of(
                "valuation", Map.of("verdict", "合理", "target2026", "30 元"),
                "summary", Map.of("oneLiner", "结论积极", "bullets", List.of("需求向上"))),
            "full",
            1234L);

    assertThat(((Map<?, ?>) response.getSourceMetadata().get("forecast")).get("available"))
        .isEqualTo(true);
    assertThat(response.getForecastSummary().get("items")).asList().hasSize(1);
    assertThat(response.getExternalExpectation().get("summary")).isEqualTo("机构预期 2026 年利润继续增长");
    assertThat(response.getReportHtml()).contains("估值与预期");
    assertThat(response.getReportHtml()).contains("机构预期 2026 年利润继续增长");
  }

  @Test
  @DisplayName("sourceMetadata 包含 wind 字段, 反映 Wind 研报上下文状态")
  void sourceMetadataIncludesWindField() {
    TradeStockBasic basic = basic();
    when(financialRepository.findByStockCodeOrderByReportDateDesc("002851.SZ"))
        .thenReturn(List.of(financial()));
    when(webSearchClient.isEnabled()).thenReturn(false);

    // Case 1: windResearch=null → wind 字段存在但 available=false
    StockAnalysisResponse noWind =
        service.buildUnifiedResponse(
            basic,
            Map.of("basic", Map.of("code_name", "麦格米特"), "quote", Map.of("close", 25.30)),
            Map.of("summary", Map.of("oneLiner", "ok")),
            "full",
            0L);
    Map<?, ?> windMeta = (Map<?, ?>) noWind.getSourceMetadata().get("wind");
    assertThat(windMeta).isNotNull();
    assertThat(windMeta.get("available")).isEqualTo(false);
    assertThat(windMeta.get("detail")).isEqualTo("本次未拉取");

    // Case 2: windResearch available → wind 字段 available=true, detail 包含具体数量
    com.quant.dto.stockanalysis.WindResearchContext ctx =
        com.quant.dto.stockanalysis.WindResearchContext.builder()
            .available(true)
            .windInstalled(true)
            .windHasKey(true)
            .method("gaojingqi")
            .consensus(
                com.quant.dto.stockanalysis.WindResearchContext.Consensus.builder()
                    .rating("增持")
                    .targetPrice(80.0)
                    .sourceRowCount(1)
                    .build())
            .reports(
                List.of(
                    com.quant.dto.stockanalysis.WindResearchContext.ResearchExcerpt.builder()
                        .title("景气拐点研报")
                        .content("...")
                        .date("2026-06-20")
                        .docType("news")
                        .build()))
            .build();

    StockAnalysisResponse withWind =
        service.buildUnifiedResponse(
            basic,
            Map.of("basic", Map.of("code_name", "麦格米特"), "quote", Map.of("close", 25.30)),
            Map.of("summary", Map.of("oneLiner", "ok")),
            "gaojingqi",
            0L,
            ctx);
    Map<?, ?> windMeta2 = (Map<?, ?>) withWind.getSourceMetadata().get("wind");
    assertThat(windMeta2.get("available")).isEqualTo(true);
    assertThat(windMeta2.get("detail").toString()).contains("一致预期").contains("研报片段");
    assertThat(withWind.getWindResearch()).isSameAs(ctx);
  }

  private TradeStockBasic basic() {
    TradeStockBasic basic = new TradeStockBasic();
    basic.setStockCode("002851.SZ");
    basic.setStockName("麦格米特");
    basic.setExchange("SZ");
    basic.setSectorNames("电气设备,AI电源");
    basic.setPeTtm(new BigDecimal("22.5"));
    basic.setPb(new BigDecimal("4.2"));
    basic.setPsTtm(new BigDecimal("3.6"));
    basic.setTotalShares(100_000_000L);
    return basic;
  }

  private TradeStockFinancial financial() {
    TradeStockFinancial financial = new TradeStockFinancial();
    financial.setStockCode("002851.SZ");
    financial.setStockName("麦格米特");
    financial.setReportDate(LocalDate.of(2025, 12, 31));
    financial.setRevenue(new BigDecimal("1250000000"));
    financial.setNetProfit(new BigDecimal("168000000"));
    financial.setEps(new BigDecimal("1.23"));
    financial.setRoe(new BigDecimal("0.15"));
    financial.setGrossMargin(new BigDecimal("0.31"));
    financial.setNetMargin(new BigDecimal("0.12"));
    financial.setRevenueYoy(new BigDecimal("20"));
    financial.setDeductedNetProfitYoy(new BigDecimal("25"));
    return financial;
  }
}
