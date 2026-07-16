package com.quant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.quant.entity.InvestXieboAnalysisRecord;
import com.quant.entity.InvestXieboWatchlist;
import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockFinancial;
import com.quant.repository.InvestXieboAnalysisRecordRepository;
import com.quant.repository.InvestXieboWatchlistRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.repository.TradeStockFinancialRepository;
import com.quant.service.xieboinvest.XieboInvestAnalysisService;
import com.quant.service.xieboinvest.XieboInvestService;

@ExtendWith(MockitoExtension.class)
@DisplayName("XieboInvestService")
class XieboInvestServiceTest {

  @Mock InvestXieboWatchlistRepository watchlistRepository;
  @Mock InvestXieboAnalysisRecordRepository analysisRecordRepository;
  @Mock TradeStockBasicRepository stockBasicRepository;
  @Mock AStockDataQuoteService aStockDataQuoteService;
  @Mock TradeStockFinancialRepository financialRepository;
  @Mock StockQueryService stockQueryService;
  @Mock Ps10ValuationService ps10ValuationService;
  @Mock com.quant.service.ai.MiniMaxClient miniMaxClient;
  @Mock com.quant.service.ai.SenseNovaClient senseNovaClient;

  XieboInvestService service;
  XieboInvestAnalysisService analysisService;

  @BeforeEach
  void setUp() {
    // 默认所有股票实时行情为空；测试用例按需 stub aStockDataQuoteService.fetchQuotes
    org.mockito.Mockito.lenient()
        .when(aStockDataQuoteService.fetchQuotes(any()))
        .thenReturn(Map.of());
    // 默认财务数据返回空列表，ps10ValuationService 返回不适用的结果
    org.mockito.Mockito.lenient()
        .when(financialRepository.findByStockCodeOrderByReportDateDesc(any()))
        .thenReturn(List.of());
    org.mockito.Mockito.lenient()
        .when(
            ps10ValuationService.evaluateFromMarketCap(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(Ps10ValuationService.Ps10Result.inapplicable("—", "测试默认", "10 倍 PS 法"));
    service =
        new XieboInvestService(
            watchlistRepository,
            stockBasicRepository,
            aStockDataQuoteService,
            financialRepository,
            stockQueryService,
            ps10ValuationService);
    analysisService =
        new XieboInvestAnalysisService(
            analysisRecordRepository, stockQueryService, service, miniMaxClient, senseNovaClient);
  }

  @Test
  @DisplayName("buildWatchlist returns peg metrics and rating from realtime quote (a-stock-data)")
  void buildWatchlistReturnsPegMetrics() {
    InvestXieboWatchlist row = new InvestXieboWatchlist();
    row.setStockCode("600519.SH");
    row.setStockName("贵州茅台");

    TradeStockBasic basic = new TradeStockBasic();
    basic.setStockCode("600519.SH");
    basic.setStockName("贵州茅台");
    basic.setSectorNames("白酒,消费");
    basic.setPeTtm(new BigDecimal("28.60"));
    basic.setPb(new BigDecimal("8.50"));
    basic.setTotalShares(1256197800L);

    // 实时价由 a-stock-data 给出
    when(watchlistRepository.findAllByOrderByDisplayOrderAscCreatedAtAsc())
        .thenReturn(List.of(row));
    when(stockBasicRepository.findByStockCodeIn(anyCollection())).thenReturn(List.of(basic));
    when(aStockDataQuoteService.fetchQuotes(any()))
        .thenReturn(
            Map.of(
                "600519.SH",
                new AStockDataQuoteService.QuoteSnapshot(
                    "600519.SH",
                    new BigDecimal("1490.00"),
                    new BigDecimal("1485.00"),
                    new BigDecimal("18716"),
                    LocalDateTime.now(),
                    "a-stock-data/tencent")));
    when(financialRepository.findByStockCodeOrderByReportDateDesc("600519.SH"))
        .thenReturn(sampleProfits());

    var items = service.getWatchlist();

    assertThat(items).hasSize(1);
    assertThat(items.get(0).getStockCode()).isEqualTo("600519.SH");
    assertThat(items.get(0).getPeg()).isNotNull();
    assertThat(items.get(0).getPegRating()).isNotBlank();
    assertThat(items.get(0).getPrice()).isEqualByComparingTo("1490.00");
    assertThat(items.get(0).getPeTtm()).isEqualByComparingTo("28.60");
  }

  @Test
  @DisplayName("addWatchlist resolves stock and persists when stock is new")
  void addWatchlistPersistsResolvedStock() {
    TradeStockBasic basic = new TradeStockBasic();
    basic.setStockCode("002371.SZ");
    basic.setStockName("北方华创");

    when(stockQueryService.resolveStock("北方华创")).thenReturn(Optional.of(basic));
    when(watchlistRepository.findByStockCode("002371.SZ")).thenReturn(Optional.empty());
    when(watchlistRepository.save(
            argThat(
                row ->
                    "002371.SZ".equals(row.getStockCode()) && "北方华创".equals(row.getStockName()))))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(watchlistRepository.findAllByOrderByDisplayOrderAscCreatedAtAsc())
        .thenReturn(List.of(watchlist("002371.SZ", "北方华创")));
    when(stockBasicRepository.findByStockCodeIn(anyCollection())).thenReturn(List.of(basic));
    when(financialRepository.findByStockCodeOrderByReportDateDesc("002371.SZ"))
        .thenReturn(List.of());

    var items = service.addWatchlist("北方华创");

    assertThat(items).hasSize(1);
    assertThat(items.get(0).getStockCode()).isEqualTo("002371.SZ");
    verify(watchlistRepository).save(argThat(row -> "002371.SZ".equals(row.getStockCode())));
  }

  @Test
  @DisplayName("sectorPe returns top peers with aggregate stats")
  void sectorPeReturnsAggregateStats() {
    TradeStockBasic base = new TradeStockBasic();
    base.setStockCode("002371.SZ");
    base.setStockName("北方华创");
    base.setSectorNames("半导体设备,芯片");
    base.setPeTtm(new BigDecimal("42.00"));
    base.setPb(new BigDecimal("7.10"));
    base.setTotalShares(100_000_000L);

    TradeStockBasic peer = new TradeStockBasic();
    peer.setStockCode("688012.SH");
    peer.setStockName("中微公司");
    peer.setSectorNames("半导体设备,芯片");
    peer.setPeTtm(new BigDecimal("55.00"));
    peer.setPb(new BigDecimal("8.00"));
    peer.setTotalShares(200_000_000L);

    when(stockQueryService.resolveStock("北方华创")).thenReturn(Optional.of(base));
    when(stockBasicRepository.findBySectorNameLike("半导体设备")).thenReturn(List.of(base, peer));
    when(aStockDataQuoteService.fetchQuotes(
            argThat(
                (Collection<String> codes) ->
                    codes.contains("002371.SZ") && codes.contains("688012.SH"))))
        .thenReturn(
            Map.of(
                "002371.SZ",
                    new AStockDataQuoteService.QuoteSnapshot(
                        "002371.SZ",
                        new BigDecimal("410.00"),
                        new BigDecimal("405.00"),
                        null,
                        LocalDateTime.now(),
                        "a-stock-data/tencent"),
                "688012.SH",
                    new AStockDataQuoteService.QuoteSnapshot(
                        "688012.SH",
                        new BigDecimal("180.00"),
                        new BigDecimal("178.00"),
                        null,
                        LocalDateTime.now(),
                        "a-stock-data/tencent")));

    Map<String, Object> sector = service.getSectorPe("北方华创");

    assertThat(sector.get("sectorName")).isEqualTo("半导体设备");
    assertThat((List<?>) sector.get("stocks")).hasSize(2);
    assertThat(sector.get("avgPe")).isEqualTo(new BigDecimal("48.50"));
    assertThat(sector.get("medianPe")).isEqualTo(new BigDecimal("48.50"));
  }

  @Test
  @DisplayName("createAnalysis stores record and returns generated report detail")
  void createAnalysisStoresRecordAndReturnsDetail() {
    TradeStockBasic basic = new TradeStockBasic();
    basic.setStockCode("002371.SZ");
    basic.setStockName("北方华创");
    basic.setSectorNames("半导体设备,芯片");
    basic.setPeTtm(new BigDecimal("42.00"));
    basic.setPb(new BigDecimal("7.10"));
    basic.setTotalShares(100_000_000L);
    when(stockQueryService.resolveStock("北方华创")).thenReturn(Optional.of(basic));
    when(aStockDataQuoteService.fetchQuotes(any()))
        .thenReturn(
            Map.of(
                "002371.SZ",
                new AStockDataQuoteService.QuoteSnapshot(
                    "002371.SZ",
                    new BigDecimal("410.00"),
                    new BigDecimal("405.00"),
                    null,
                    LocalDateTime.now(),
                    "a-stock-data/tencent")));
    when(financialRepository.findByStockCodeOrderByReportDateDesc("002371.SZ"))
        .thenReturn(
            List.of(
                annualFinancial("002371.SZ", "2025-12-31", "5200000000"),
                annualFinancial("002371.SZ", "2024-12-31", "4200000000"),
                annualFinancial("002371.SZ", "2023-12-31", "3200000000"),
                annualFinancial("002371.SZ", "2022-12-31", "2400000000")));
    when(analysisRecordRepository.save(
            argThat(
                record ->
                    "002371.SZ".equals(record.getStockCode())
                        && "北方华创".equals(record.getStockName()))))
        .thenAnswer(
            invocation -> {
              InvestXieboAnalysisRecord record = invocation.getArgument(0);
              if (record.getId() == null) record.setId(1L);
              return record;
            });

    var detail = analysisService.create("北方华创");

    assertThat(detail.getId()).isEqualTo(1L);
    assertThat(detail.getStockCode()).isEqualTo("002371.SZ");
    assertThat(detail.getStatus()).isEqualTo("completed");
    assertThat(detail.getReportMarkdown()).contains("PEG", "北方华创");
  }

  private List<TradeStockFinancial> sampleProfits() {
    return List.of(
        financial("2025-12-31", "87000000000"),
        financial("2024-12-31", "76000000000"),
        financial("2023-12-31", "62000000000"),
        financial("2022-12-31", "52000000000"));
  }

  private TradeStockFinancial financial(String reportDate, String netProfit) {
    TradeStockFinancial f = new TradeStockFinancial();
    f.setStockCode("600519.SH");
    f.setStockName("贵州茅台");
    f.setReportDate(LocalDate.parse(reportDate));
    f.setNetProfit(new BigDecimal(netProfit));
    return f;
  }

  private TradeStockFinancial annualFinancial(String code, String reportDate, String netProfit) {
    TradeStockFinancial f = new TradeStockFinancial();
    f.setStockCode(code);
    f.setStockName(code);
    f.setReportDate(LocalDate.parse(reportDate));
    f.setNetProfit(new BigDecimal(netProfit));
    return f;
  }

  private InvestXieboWatchlist watchlist(String code, String name) {
    InvestXieboWatchlist row = new InvestXieboWatchlist();
    row.setStockCode(code);
    row.setStockName(name);
    return row;
  }
}
