package com.quant.invest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.quant.config.AiProperties;
import com.quant.config.StockAnalysisProperties;
import com.quant.dto.invest.ProsperityPickRecentDTO;
import com.quant.dto.invest.ProsperityPickResultDTO;
import com.quant.dto.invest.ProsperityPickResultDTO.Profile;
import com.quant.entity.InvestProsperityPick;
import com.quant.entity.TradeStockBasic;
import com.quant.repository.InvestProsperityPickRepository;
import com.quant.repository.TradeStockFinancialRepository;
import com.quant.service.ProsperityPickService;
import com.quant.service.StockQueryService;
import com.quant.service.ai.MiniMaxClient;
import com.quant.service.ai.SenseNovaClient;
import com.quant.service.aistockdata.AStockDataQuoteService;
import com.quant.service.prosperitystrong.ProsperityPickAiPromptBuilder;
import com.quant.service.prosperitystrong.ProsperityPickBaostockLoader;
import com.quant.service.prosperitystrong.ProsperityPickInfographicPromptBuilder;
import com.quant.service.prosperitystrong.ProsperityPickProfileBuilder;
import com.quant.service.prosperitystrong.ProsperityPickReportRenderer;
import com.quant.service.prosperitystrong.ProsperityPickResultAnalyzer;
import com.quant.service.search.WebSearchClient;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProsperityPickService")
class ProsperityPickServiceTest {

  private static final String REAL_JSON =
      """
            {
              "summary": {
                "bullets": ["真实分析要点"],
                "oneLiner": "真实分析结论"
              }
            }
            """;

  @Mock StockQueryService stockQueryService;
  @Mock TradeStockFinancialRepository financialRepo;
  @Mock AStockDataQuoteService aStockDataQuoteService;
  @Mock InvestProsperityPickRepository repo;
  @Mock MiniMaxClient miniMaxClient;
  @Mock SenseNovaClient senseNovaClient;
  @Mock WebSearchClient webSearchClient;
  @Mock ProsperityPickProfileBuilder profileBuilder;
  @Mock ProsperityPickBaostockLoader baostockLoader;
  @Mock ProsperityPickAiPromptBuilder aiPromptBuilder;
  @Mock ProsperityPickResultAnalyzer resultAnalyzer;
  @Mock ProsperityPickReportRenderer reportRenderer;
  @Mock ProsperityPickInfographicPromptBuilder infographicPromptBuilder;

  private ProsperityPickService service;

  @BeforeEach
  void setUp() {
    AiProperties aiProperties = new AiProperties();
    aiProperties.setFallbackToMock(true);
    aiProperties.getSensenova().setEnabled(true);
    aiProperties.getSensenova().setApiKey("test-key");
    StockAnalysisProperties stockAnalysisProperties = new StockAnalysisProperties();
    stockAnalysisProperties.setEnabled(false);
    // 默认实时行情为空，迫使快照里 currentPrice/marketCap 走 null 兜底
    org.mockito.Mockito.lenient()
        .when(aStockDataQuoteService.fetchQuotes(anyList()))
        .thenReturn(Map.of());
    // 给 profileBuilder 一个最小 Profile（currentPrice 为 null），保证 analyze 走到 resultAnalyzer
    org.mockito.Mockito.lenient()
        .when(profileBuilder.buildProfile(any(TradeStockBasic.class)))
        .thenAnswer(
            invocation -> {
              TradeStockBasic b = invocation.getArgument(0);
              return Profile.builder()
                  .stockCode(b.getStockCode())
                  .stockName(b.getStockName())
                  .build();
            });
    // resultAnalyzer 紫苏叶阶段空跑，返回空 outcome
    org.mockito.Mockito.lenient()
        .when(resultAnalyzer.runPurplePerillaStage(any(), any(), any()))
        .thenAnswer(
            invocation ->
                new ProsperityPickResultAnalyzer.PurplePerillaOutcome(
                    null, null, null, null, List.of(), List.of()));
    // aiPromptBuilder 提示词生成返回固定字符串
    org.mockito.Mockito.lenient()
        .when(aiPromptBuilder.buildPrompt(any(), any(), any()))
        .thenReturn("prompt-stub");
    org.mockito.Mockito.lenient().when(aiPromptBuilder.extractJson(any())).thenReturn(REAL_JSON);
    // baostock loader 在 test 下关闭 stockAnalysis，返回 null
    org.mockito.Mockito.lenient()
        .when(baostockLoader.fetchBaostockPack(anyString()))
        .thenReturn(null);
    // resultAnalyzer.buildEntity 直接 echo 一个 InvestProsperityPick
    org.mockito.Mockito.lenient()
        .when(
            resultAnalyzer.buildEntity(
                any(),
                any(),
                anyString(),
                any(),
                any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                any()))
        .thenAnswer(
            invocation -> {
              TradeStockBasic b = invocation.getArgument(0);
              String aiJson = invocation.getArgument(2);
              InvestProsperityPick e = new InvestProsperityPick();
              e.setStockCode(b.getStockCode());
              e.setStockName(b.getStockName());
              e.setResultJson(aiJson);
              return e;
            });
    // reportRenderer.buildReportHtml 返回 null（facade 不要求非空）
    org.mockito.Mockito.lenient()
        .when(reportRenderer.buildReportHtml(any(), any()))
        .thenReturn(null);
    // resultAnalyzer.toResultDTO 返回最小 ProsperityPickResultDTO
    org.mockito.Mockito.lenient()
        .when(resultAnalyzer.toResultDTO(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
        .thenAnswer(
            invocation -> {
              TradeStockBasic b = invocation.getArgument(1);
              InvestProsperityPick e = invocation.getArgument(0);
              com.fasterxml.jackson.databind.ObjectMapper m =
                  new com.fasterxml.jackson.databind.ObjectMapper();
              com.fasterxml.jackson.databind.JsonNode analysis = null;
              try {
                analysis =
                    e.getResultJson() == null || e.getResultJson().isBlank()
                        ? null
                        : m.readTree(e.getResultJson());
              } catch (Exception ignored) {
              }
              return ProsperityPickResultDTO.builder()
                  .id(e.getId())
                  .stockCode(e.getStockCode())
                  .stockName(e.getStockName() != null ? e.getStockName() : b.getStockName())
                  .analysisDate(e.getAnalysisDate())
                  .analysis(analysis)
                  .build();
            });
    // resultAnalyzer.toRecentDTO 返回最小 ProsperityPickRecentDTO
    org.mockito.Mockito.lenient()
        .when(resultAnalyzer.toRecentDTO(any()))
        .thenAnswer(
            invocation -> {
              InvestProsperityPick e = invocation.getArgument(0);
              com.fasterxml.jackson.databind.ObjectMapper m =
                  new com.fasterxml.jackson.databind.ObjectMapper();
              String oneLiner = "";
              String valuationVerdict = "";
              String technicalVerdict = "";
              String capitalVerdict = "";
              java.util.List<String> bullets = new java.util.ArrayList<>();
              try {
                com.fasterxml.jackson.databind.JsonNode root =
                    m.readTree(e.getResultJson() == null ? "{}" : e.getResultJson());
                com.fasterxml.jackson.databind.JsonNode summary = root.path("summary");
                oneLiner = summary.path("oneLiner").asText("");
                com.fasterxml.jackson.databind.JsonNode bulletNode = summary.path("bullets");
                if (bulletNode.isArray()) {
                  int i = 0;
                  for (com.fasterxml.jackson.databind.JsonNode n : bulletNode) {
                    String t = n.asText("");
                    if (!t.isBlank()) bullets.add(t);
                    if (++i >= 3) break;
                  }
                }
                valuationVerdict = root.path("valuation").path("verdict").asText("");
                technicalVerdict = root.path("technical").path("verdict").asText("");
                capitalVerdict = root.path("capital").path("verdict").asText("");
              } catch (Exception ignored) {
              }
              return ProsperityPickRecentDTO.builder()
                  .id(e.getId())
                  .stockCode(e.getStockCode())
                  .stockName(e.getStockName())
                  .analysisDate(e.getAnalysisDate())
                  .summaryOneLiner(oneLiner)
                  .summaryBullets(bullets)
                  .valuationVerdict(valuationVerdict)
                  .technicalVerdict(technicalVerdict)
                  .capitalVerdict(capitalVerdict)
                  .build();
            });
    service =
        new ProsperityPickService(
            stockQueryService,
            financialRepo,
            aStockDataQuoteService,
            repo,
            miniMaxClient,
            senseNovaClient,
            webSearchClient,
            aiProperties,
            stockAnalysisProperties,
            profileBuilder,
            baostockLoader,
            aiPromptBuilder,
            resultAnalyzer,
            reportRenderer,
            infographicPromptBuilder);
  }

  @Test
  @DisplayName("当天缓存是演示数据时重新分析而不是直接返回缓存")
  void analyzeRefreshesDegradedCache() {
    TradeStockBasic basic = basic();
    InvestProsperityPick cached = entity(1L, 1, "{\"summary\":{\"oneLiner\":\"演示数据\"}}");

    when(stockQueryService.resolveStock("麦格米特")).thenReturn(Optional.of(basic));
    when(repo.findByStockCodeAndAnalysisDate("002851.SZ", LocalDate.now()))
        .thenReturn(Optional.of(cached), Optional.of(cached));
    when(miniMaxClient.chatComplete(anyString(), anyString())).thenReturn(REAL_JSON);
    when(repo.save(any(InvestProsperityPick.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ProsperityPickResultDTO result = service.analyze("麦格米特", false);

    assertThat(result.isCached()).isFalse();
    assertThat(result.isDegraded()).isFalse();
    assertThat(result.getAnalysis().path("summary").path("oneLiner").asText()).isEqualTo("真实分析结论");
    verify(miniMaxClient).chatComplete(anyString(), anyString());
  }

  @Test
  @DisplayName("MiniMax 失败时使用 SenseNova 生成真实分析")
  void analyzeUsesSenseNovaWhenMiniMaxFails() {
    TradeStockBasic basic = basic();

    when(stockQueryService.resolveStock("麦格米特")).thenReturn(Optional.of(basic));
    when(repo.findByStockCodeAndAnalysisDate("002851.SZ", LocalDate.now()))
        .thenReturn(Optional.empty());
    when(miniMaxClient.chatComplete(anyString(), anyString()))
        .thenThrow(new IllegalStateException("401 Unauthorized"));
    when(senseNovaClient.chatComplete(anyString(), anyString())).thenReturn(REAL_JSON);
    when(repo.save(any(InvestProsperityPick.class)))
        .thenAnswer(
            invocation -> {
              InvestProsperityPick e = invocation.getArgument(0);
              e.setId(2L);
              return e;
            });

    ProsperityPickResultDTO result = service.analyze("麦格米特", false);

    assertThat(result.isDegraded()).isFalse();
    assertThat(result.getAnalysis().path("summary").path("oneLiner").asText()).isEqualTo("真实分析结论");
    verify(senseNovaClient).chatComplete(anyString(), anyString());
  }

  @Test
  @DisplayName("所有 AI 失败时不回退保存演示数据")
  void analyzeDoesNotPersistDemoDataWhenAiFails() {
    TradeStockBasic basic = basic();

    when(stockQueryService.resolveStock("麦格米特")).thenReturn(Optional.of(basic));
    when(repo.findByStockCodeAndAnalysisDate("002851.SZ", LocalDate.now()))
        .thenReturn(Optional.empty());
    when(miniMaxClient.chatComplete(anyString(), anyString()))
        .thenThrow(new IllegalStateException("MiniMax down"));
    when(senseNovaClient.chatComplete(anyString(), anyString()))
        .thenThrow(new IllegalStateException("SenseNova down"));

    assertThatThrownBy(() -> service.analyze("麦格米特", false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AI 调用失败");
    verify(repo, never()).save(any(InvestProsperityPick.class));
  }

  @Test
  @DisplayName("最近分析只返回近 3 天非演示记录并携带关键结论")
  void recentReturnsThreeDayRealRecordsWithSummary() {
    LocalDate today = LocalDate.now();
    InvestProsperityPick real =
        entity(
            1L,
            0,
            """
                {
                  "valuation": {"verdict": "合理偏低"},
                  "technical": {"verdict": "短线趋势向上"},
                  "capital": {"verdict": "资金温和流入"},
                  "summary": {
                    "bullets": ["行业景气向上", "估值仍有修复空间"],
                    "oneLiner": "五维结论偏积极"
                  }
                }
                """);
    real.setAnalysisDate(today.minusDays(2));
    InvestProsperityPick degraded = entity(2L, 1, "{\"summary\":{\"oneLiner\":\"演示数据\"}}");
    degraded.setAnalysisDate(today);

    when(repo.findTop30ByAnalysisDateGreaterThanEqualOrderByAnalysisDateDescIdDesc(
            today.minusDays(2)))
        .thenReturn(List.of(degraded, real));

    var result = service.recent();

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getId()).isEqualTo(1L);
    assertThat(result.get(0).getSummaryOneLiner()).isEqualTo("五维结论偏积极");
    assertThat(result.get(0).getSummaryBullets()).containsExactly("行业景气向上", "估值仍有修复空间");
    assertThat(result.get(0).getValuationVerdict()).isEqualTo("合理偏低");
    assertThat(result.get(0).getTechnicalVerdict()).isEqualTo("短线趋势向上");
    assertThat(result.get(0).getCapitalVerdict()).isEqualTo("资金温和流入");
    assertThat(result.get(0).isDegraded()).isFalse();
  }

  private TradeStockBasic basic() {
    TradeStockBasic basic = new TradeStockBasic();
    basic.setStockCode("002851.SZ");
    basic.setStockName("麦格米特");
    basic.setExchange("SZ");
    return basic;
  }

  private InvestProsperityPick entity(Long id, int degraded, String resultJson) {
    InvestProsperityPick entity = new InvestProsperityPick();
    entity.setId(id);
    entity.setStockCode("002851.SZ");
    entity.setStockName("麦格米特");
    entity.setAnalysisDate(LocalDate.now());
    entity.setDegraded(degraded);
    entity.setResultJson(resultJson);
    return entity;
  }
}
