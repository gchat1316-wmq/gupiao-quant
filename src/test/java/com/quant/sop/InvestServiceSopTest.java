package com.quant.sop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.quant.dto.invest.SopCheckupDTO;
import com.quant.entity.TradeStockBasic;
import com.quant.entity.TradeStockFinancial;
import com.quant.repository.InvestPositionCommonRepository;
import com.quant.repository.InvestStockPoolRepository;
import com.quant.repository.TradeStockBasicRepository;
import com.quant.repository.TradeStockFinancialRepository;
import com.quant.service.AStockDataQuoteService;
import com.quant.service.InvestService;

@ExtendWith(MockitoExtension.class)
@DisplayName("InvestService - SOP 三大数字体检")
class InvestServiceSopTest {

  @Mock TradeStockBasicRepository stockBasicRepo;
  @Mock TradeStockFinancialRepository financialRepo;
  @Mock InvestStockPoolRepository poolRepo;
  @Mock InvestPositionCommonRepository positionRepo;
  @Mock AStockDataQuoteService quoteService;

  InvestService service;

  @BeforeEach
  void setUp() {
    service =
        new InvestService(stockBasicRepo, financialRepo, poolRepo, positionRepo, quoteService);
  }

  private TradeStockBasic stockBasic(String code, String name) {
    TradeStockBasic b = new TradeStockBasic();
    b.setStockCode(code);
    b.setStockName(name);
    return b;
  }

  private List<TradeStockFinancial> buildFinancials(
      String code, int n, double grossMargin, double revenueYoy, double profitYoy) {
    List<TradeStockFinancial> list = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      TradeStockFinancial f = new TradeStockFinancial();
      f.setStockCode(code);
      f.setReportDate(LocalDate.of(2025, 12, 31).minusMonths(3L * i));
      f.setGrossMargin(BigDecimal.valueOf(grossMargin));
      f.setRevenueYoy(BigDecimal.valueOf(revenueYoy));
      f.setDeductedNetProfitYoy(BigDecimal.valueOf(profitYoy));
      list.add(f);
    }
    return list;
  }

  private List<TradeStockFinancial> buildFinancialsWithTrend(
      String code, double[] grossMargins, double revenueYoy, double profitYoy) {
    List<TradeStockFinancial> list = new ArrayList<>();
    for (int i = 0; i < grossMargins.length; i++) {
      TradeStockFinancial f = new TradeStockFinancial();
      f.setStockCode(code);
      f.setReportDate(LocalDate.of(2025, 12, 31).minusMonths(3L * i));
      f.setGrossMargin(BigDecimal.valueOf(grossMargins[i]));
      f.setRevenueYoy(BigDecimal.valueOf(revenueYoy));
      f.setDeductedNetProfitYoy(BigDecimal.valueOf(profitYoy));
      list.add(f);
    }
    return list;
  }

  @Test
  @DisplayName("TC01 - 股票名/代码不存在时返回 matched=false 且 message 非空")
  void tc01_unknownStock_returnsNotMatched() {
    when(stockBasicRepo.findByStockNameLike(anyString())).thenReturn(Collections.emptyList());

    SopCheckupDTO result = service.sopCheckup("不存在的股票");

    assertThat(result.isMatched()).isFalse();
    assertThat(result.getMessage()).isNotBlank();
  }

  @Test
  @DisplayName("TC02 - 股票存在但无财务数据时返回 matched=false")
  void tc02_stockFoundButNoFinancials_returnsNotMatched() {
    // resolveStock 路径: findByStockCode(empty) -> findByStockCodePrefix(empty) ->
    // findByStockNameLike(有)
    when(stockBasicRepo.findByStockCode("888888")).thenReturn(Optional.empty());
    when(stockBasicRepo.findByStockCodePrefix("888888")).thenReturn(Collections.emptyList());
    when(stockBasicRepo.findByStockNameLike("888888"))
        .thenReturn(List.of(stockBasic("888888", "测试股票")));
    when(financialRepo.findByStockCodeOrderByReportDateDesc("888888"))
        .thenReturn(Collections.emptyList());

    SopCheckupDTO result = service.sopCheckup("888888");

    assertThat(result.isMatched()).isFalse();
    assertThat(result.getStockCode()).isEqualTo("888888");
  }

  @Test
  @DisplayName("TC03 - 毛利率稳定/营收持续>=20%/扣非>营收 => overall=pass")
  void tc03_allMetricsPass_returnsOverallPass() {
    String code = "600000";
    when(financialRepo.findByStockCodeOrderByReportDateDesc(code))
        .thenReturn(buildFinancials(code, 8, 45.0, 30.0, 40.0));

    SopCheckupDTO r = service.sopCheckup(code);

    assertThat(r.isMatched()).isTrue();
    assertThat(r.getGrossMargin().getVerdict()).isEqualTo("pass");
    assertThat(r.getRevenueYoy().getVerdict()).isEqualTo("pass");
    assertThat(r.getProfitYoy().getVerdict()).isEqualTo("pass");
    assertThat(r.getOverallVerdict()).isEqualTo("pass");
  }

  @Test
  @DisplayName("TC04 - 营收同比<10% 触发 fail，整体判定 fail")
  void tc04_lowRevenue_returnsFail() {
    String code = "600001";
    when(financialRepo.findByStockCodeOrderByReportDateDesc(code))
        .thenReturn(buildFinancials(code, 8, 40.0, 5.0, 5.0));

    SopCheckupDTO r = service.sopCheckup(code);

    assertThat(r.getRevenueYoy().getVerdict()).isEqualTo("fail");
    assertThat(r.getOverallVerdict()).isEqualTo("fail");
  }

  @Test
  @DisplayName("TC05 - 毛利率从 50% 跌至 40%，下滑>3pct => verdict=fail")
  void tc05_grossMarginDroppingBadly_returnsFail() {
    String code = "600002";
    double[] grossMargins = {40.0, 42.0, 43.0, 44.0, 45.0, 46.0, 48.0, 50.0};
    when(financialRepo.findByStockCodeOrderByReportDateDesc(code))
        .thenReturn(buildFinancialsWithTrend(code, grossMargins, 25.0, 30.0));

    SopCheckupDTO r = service.sopCheckup(code);

    assertThat(r.getGrossMargin().getVerdict()).isEqualTo("fail");
  }

  @Test
  @DisplayName("TC06 - 毛利率下滑 2pct (1~3区间) => verdict=warn")
  void tc06_grossMarginSlightDrop_returnsWarn() {
    String code = "600003";
    double[] grossMargins = {43.0, 43.5, 44.0, 44.5, 44.5, 44.8, 45.0, 45.0};
    when(financialRepo.findByStockCodeOrderByReportDateDesc(code))
        .thenReturn(buildFinancialsWithTrend(code, grossMargins, 25.0, 30.0));

    SopCheckupDTO r = service.sopCheckup(code);

    assertThat(r.getGrossMargin().getVerdict()).isEqualTo("warn");
  }

  @Test
  @DisplayName("TC07 - 扣非(5%)远落后营收(25%)，差距>5pct => profitYoy=fail")
  void tc07_profitFarBehindRevenue_returnsFail() {
    String code = "600004";
    when(financialRepo.findByStockCodeOrderByReportDateDesc(code))
        .thenReturn(buildFinancials(code, 8, 40.0, 25.0, 5.0));

    SopCheckupDTO r = service.sopCheckup(code);

    assertThat(r.getProfitYoy().getVerdict()).isEqualTo("fail");
  }

  @Test
  @DisplayName("TC08 - 最新营收>=20%但历史仅3/8季度达标(不足60%) => revenueYoy=warn")
  void tc08_recentHighRevenueButNotConsistent_returnsWarn() {
    String code = "600005";
    List<TradeStockFinancial> list = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      TradeStockFinancial f = new TradeStockFinancial();
      f.setStockCode(code);
      f.setReportDate(LocalDate.of(2025, 12, 31).minusMonths(3L * i));
      f.setGrossMargin(BigDecimal.valueOf(40.0));
      f.setRevenueYoy(BigDecimal.valueOf(i < 3 ? 25.0 : 10.0));
      f.setDeductedNetProfitYoy(BigDecimal.valueOf(28.0));
      list.add(f);
    }
    when(financialRepo.findByStockCodeOrderByReportDateDesc(code)).thenReturn(list);

    SopCheckupDTO r = service.sopCheckup(code);

    assertThat(r.getRevenueYoy().getVerdict()).isEqualTo("warn");
    assertThat(r.getRevenueYoy().getLatest().doubleValue()).isEqualTo(25.0);
  }

  @Test
  @DisplayName("TC09 - 股票名称解析，stockName 取自 stock_basic")
  void tc09_resolveByName_nameFromStockBasic() {
    String code = "600519";
    when(stockBasicRepo.findByStockCode(code)).thenReturn(Optional.of(stockBasic(code, "贵州茅台")));
    when(financialRepo.findByStockCodeOrderByReportDateDesc(code))
        .thenReturn(buildFinancials(code, 4, 91.0, 10.0, 10.0));

    SopCheckupDTO r = service.sopCheckup(code);

    assertThat(r.isMatched()).isTrue();
    assertThat(r.getStockCode()).isEqualTo(code);
    assertThat(r.getStockName()).isEqualTo("贵州茅台");
  }

  @Test
  @DisplayName("TC10 - 数据库有12条记录，series 只取最近8条")
  void tc10_moreRecordsThanLimit_onlyTakes8() {
    String code = "600006";
    when(financialRepo.findByStockCodeOrderByReportDateDesc(code))
        .thenReturn(buildFinancials(code, 12, 40.0, 25.0, 30.0));

    SopCheckupDTO r = service.sopCheckup(code);

    assertThat(r.getGrossMargin().getSeries()).hasSize(8);
    assertThat(r.getRevenueYoy().getSeries()).hasSize(8);
    assertThat(r.getProfitYoy().getSeries()).hasSize(8);
  }

  @Test
  @DisplayName("TC11 - keyword 为 null 时安全返回 matched=false")
  void tc11_nullKeyword_doesNotThrow() {
    SopCheckupDTO r = service.sopCheckup(null);
    assertThat(r.isMatched()).isFalse();
  }

  @Test
  @DisplayName("TC12 - 只有1条财务数据，毛利率无法算趋势，verdict=pass(稳定)")
  void tc12_singleRecord_grossMarginPassAsStable() {
    String code = "600007";
    when(financialRepo.findByStockCodeOrderByReportDateDesc(code))
        .thenReturn(buildFinancials(code, 1, 50.0, 25.0, 30.0));

    SopCheckupDTO r = service.sopCheckup(code);

    assertThat(r.isMatched()).isTrue();
    assertThat(r.getGrossMargin().getVerdict()).isEqualTo("pass");
  }
}
