package com.quant.service.prosperitystrong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

import com.quant.config.ProsperityStrongProperties;
import com.quant.entity.ProsperityHotSector;
import com.quant.entity.ProsperityLeaderCandidate;
import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockDaily;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.repository.TradeStockDailyRepository;
import com.quant.service.aistockdata.AStockDataQuoteService;

@ExtendWith(MockitoExtension.class)
@DisplayName("LeaderIdentifier")
class LeaderIdentifierTest {

  @Mock private TradeStockBasicRepository basicRepo;

  @Mock private TradeStockDailyRepository dailyRepo;

  @Mock private AStockDataQuoteService aStockDataQuoteService;

  @Mock private SectorIndexCache sectorIndexCache;

  private LeaderIdentifier identifier;

  @BeforeEach
  void setUp() {
    ProsperityStrongProperties props = new ProsperityStrongProperties();
    props.setLeadersPerSector(5);
    // 默认所有股票实时行情返回空，迫使 identify 跳过（与 memberStats 用法隔离）
    org.mockito.Mockito.lenient()
        .when(aStockDataQuoteService.fetchQuotes(anyList()))
        .thenReturn(Map.of());
    // 默认倒排索引为冷启动状态 (stockCount=0)，迫使 findMembersByAliases 回退到 DB LIKE
    org.mockito.Mockito.lenient()
        .when(sectorIndexCache.getStatus())
        .thenReturn(new SectorIndexCache.Status(0, null));
    org.mockito.Mockito.lenient()
        .when(sectorIndexCache.findStockCodesByKeywords(anyList()))
        .thenReturn(java.util.Set.of());
    org.mockito.Mockito.lenient()
        .when(sectorIndexCache.getOrLoadByKeywords(anyList(), any()))
        .thenAnswer(
            inv -> {
              java.util.function.Supplier<List<TradeStockBasic>> loader = inv.getArgument(1);
              return loader.get();
            });
    identifier =
        org.mockito.Mockito.spy(
            new LeaderIdentifier(
                basicRepo, dailyRepo, aStockDataQuoteService, props, sectorIndexCache));
  }

  @Test
  @DisplayName("优先使用东方财富板块代码匹配本地成分股")
  void memberStatsPreferEastMoneyMembers() throws Exception {
    ProsperityHotSector sector = new ProsperityHotSector();
    sector.setSectorCode("BK0478");
    sector.setSectorName("有色金属");

    doReturn(List.of("000001", "000002")).when(identifier).fetchEastMoneyMemberCodes("BK0478");
    when(basicRepo.findByStockCodePrefix("000001")).thenReturn(List.of(basic("000001.SZ", "平安银行")));
    when(basicRepo.findByStockCodePrefix("000002")).thenReturn(List.of(basic("000002.SZ", "万科A")));
    when(dailyRepo.findLatestByStockCodes(anyList()))
        .thenReturn(List.of(quote("000001.SZ"), quote("000002.SZ")));

    LeaderIdentifier.MemberStats stats = identifier.memberStats(sector);

    assertThat(stats.matchedMemberCount()).isEqualTo(2);
    assertThat(stats.quotedMemberCount()).isEqualTo(2);
    assertThat(stats.diagnosticMessage()).contains("东方财富板块成分股");
    verify(basicRepo, never()).findBySectorNameLike(eq("有色金属"));
  }

  @Test
  @DisplayName("东方财富未命中时回退到本地别名匹配")
  void memberStatsFallbackToLocalAliases() {
    ProsperityHotSector sector = new ProsperityHotSector();
    sector.setSectorName("半导体");

    when(basicRepo.findBySectorNameLike("半导体")).thenReturn(List.of());
    when(basicRepo.findBySectorNameLike("芯片")).thenReturn(List.of(basic("000001.SZ", "平安银行")));
    when(basicRepo.findBySectorNameLike("集成电路")).thenReturn(List.of());
    when(basicRepo.findBySectorNameLike("中芯国际")).thenReturn(List.of());
    when(basicRepo.findBySectorNameLike("华为海思")).thenReturn(List.of());
    when(dailyRepo.findLatestByStockCodes(anyList())).thenReturn(List.of(quote("000001.SZ")));

    LeaderIdentifier.MemberStats stats = identifier.memberStats(sector);

    assertThat(stats.matchedMemberCount()).isEqualTo(1);
    assertThat(stats.quotedMemberCount()).isEqualTo(1);
    assertThat(stats.diagnosticMessage()).contains("别名匹配");
  }

  @Test
  @DisplayName("不再使用停牌字段过滤龙头候选")
  void identifyDoesNotFilterBySuspensionFlag() {
    LocalDate snapDate = LocalDate.of(2026, 6, 16);
    ProsperityHotSector sector = new ProsperityHotSector();
    sector.setSectorName("半导体");

    TradeStockBasic suspendedFlagged = basic("688001.SH", "测试科技");
    suspendedFlagged.setIsTrading(0);
    when(basicRepo.findBySectorNameLike("半导体")).thenReturn(List.of(suspendedFlagged));
    when(basicRepo.findBySectorNameLike("芯片")).thenReturn(List.of());
    when(basicRepo.findBySectorNameLike("集成电路")).thenReturn(List.of());
    when(basicRepo.findBySectorNameLike("中芯国际")).thenReturn(List.of());
    when(basicRepo.findBySectorNameLike("华为海思")).thenReturn(List.of());

    // 当前价统一走 a-stock-data 实时接口（mock 给一个明显偏离的实时价用于对比）
    when(aStockDataQuoteService.fetchQuotes(anyList()))
        .thenReturn(
            Map.of(
                "688001.SH",
                new AStockDataQuoteService.QuoteSnapshot(
                    "688001.SH",
                    new BigDecimal("21.50"),
                    new BigDecimal("20.00"),
                    null,
                    null,
                    LocalDateTime.now(),
                    "a-stock-data/tencent"
)));
    when(dailyRepo.findLatestByStockCodes(anyList()))
        .thenReturn(List.of(quote("688001.SH", snapDate.minusDays(10), "20.00", "3.20")));
    when(dailyRepo.findFirstAfterDateByStockCodes(anyList(), any()))
        .thenReturn(List.of(quote("688001.SH", LocalDate.of(2026, 1, 2), "10.00", "1.00")));
    when(dailyRepo.findTop6ByStockCodeOrderByTradeDateDesc("688001.SH"))
        .thenReturn(
            List.of(
                quote("688001.SH", snapDate.minusDays(10), "20.00", "3.20"),
                quote("688001.SH", snapDate.minusDays(11), "19.00", "2.90"),
                quote("688001.SH", snapDate.minusDays(12), "18.00", "2.70"),
                quote("688001.SH", snapDate.minusDays(13), "17.00", "2.50"),
                quote("688001.SH", snapDate.minusDays(14), "16.00", "2.30")));

    List<ProsperityLeaderCandidate> candidates = identifier.identify(snapDate, sector);

    assertThat(candidates).hasSize(1);
    assertThat(candidates.get(0).getFilterPassed()).isEqualTo(1);
    assertThat(candidates.get(0).getFilterReason()).isNull();
    // 年初至今涨幅应基于 a-stock-data 实时价 21.50： (21.5 - 10) / 10 = 1.15 = 115.00%
    assertThat(candidates.get(0).getYtdChange()).isEqualByComparingTo("115.00");
  }

  private TradeStockBasic basic(String stockCode, String stockName) {
    TradeStockBasic basic = new TradeStockBasic();
    basic.setStockCode(stockCode);
    basic.setStockName(stockName);
    basic.setListDate(LocalDate.of(2020, 1, 1));
    return basic;
  }

  private TradeStockDaily quote(String stockCode) {
    return quote(stockCode, LocalDate.now(), "10", "1");
  }

  private TradeStockDaily quote(
      String stockCode, LocalDate tradeDate, String closePrice, String turnoverRate) {
    TradeStockDaily daily = new TradeStockDaily();
    daily.setStockCode(stockCode);
    daily.setTradeDate(tradeDate);
    daily.setClosePrice(new BigDecimal(closePrice));
    daily.setTurnoverRate(new BigDecimal(turnoverRate));
    return daily;
  }
}
