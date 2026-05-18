package com.quant.sop;

import com.quant.dto.invest.SopCheckupDTO;
import com.quant.entity.TradeStockFinancial;
import com.quant.entity.TradeStockInfo;
import com.quant.repository.InvestStockPoolRepository;
import com.quant.repository.TradeStockFinancialRepository;
import com.quant.repository.TradeStockInfoRepository;
import com.quant.service.InvestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InvestService - SOP 三大数字体检")
class InvestServiceSopTest {

    @Mock TradeStockInfoRepository stockInfoRepo;
    @Mock TradeStockFinancialRepository financialRepo;
    @Mock InvestStockPoolRepository poolRepo;

    InvestService service;

    @BeforeEach
    void setUp() {
        service = new InvestService(stockInfoRepo, financialRepo, poolRepo);
    }

    // ──────────────────────────────────────────────────
    // 辅助方法
    // ──────────────────────────────────────────────────

    private TradeStockInfo stockInfo(String code, String name) {
        TradeStockInfo info = new TradeStockInfo();
        info.setStockCode(code);
        info.setStockName(name);
        return info;
    }

    /**
     * 构造 n 条财务记录（desc 顺序）。
     * grossMargin / revenueYoy / deductedNetProfitYoy 统一赋值。
     */
    private List<TradeStockFinancial> buildFinancials(String code, int n,
                                                       double grossMargin,
                                                       double revenueYoy,
                                                       double profitYoy) {
        List<TradeStockFinancial> list = new ArrayList<>();
        // 最新季度在前（desc）
        for (int i = 0; i < n; i++) {
            TradeStockFinancial f = new TradeStockFinancial();
            f.setStockCode(code);
            // 2025Q4 往前推
            f.setReportDate(LocalDate.of(2025, 12, 31).minusMonths(3L * i));
            f.setGrossMargin(BigDecimal.valueOf(grossMargin));
            f.setRevenueYoy(BigDecimal.valueOf(revenueYoy));
            f.setDeductedNetProfitYoy(BigDecimal.valueOf(profitYoy));
            list.add(f);
        }
        return list;
    }

    /**
     * 构造记录列表，每条 grossMargin 单独指定（用于测试趋势）。
     */
    private List<TradeStockFinancial> buildFinancialsWithTrend(String code,
                                                                double[] grossMargins,
                                                                double revenueYoy,
                                                                double profitYoy) {
        List<TradeStockFinancial> list = new ArrayList<>();
        // index 0 = 最新（desc）
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

    // ──────────────────────────────────────────────────
    // 用例 1：股票不存在
    // ──────────────────────────────────────────────────

    @Test
    @DisplayName("TC01 - 股票名/代码不存在时返回 matched=false 且 message 非空")
    void tc01_unknownStock_returnsNotMatched() {
        // "不存在的股票" 非数字，resolveStock 只走 findByStockNameLike 分支
        when(stockInfoRepo.findByStockNameLike(anyString())).thenReturn(Collections.emptyList());

        SopCheckupDTO result = service.sopCheckup("不存在的股票");

        assertThat(result.isMatched()).isFalse();
        assertThat(result.getMessage()).isNotBlank();
    }

    // ──────────────────────────────────────────────────
    // 用例 2：股票存在但无财务数据
    // ──────────────────────────────────────────────────

    @Test
    @DisplayName("TC02 - 股票在 stock_info 中但财务数据为空时返回 matched=false")
    void tc02_stockFoundButNoFinancials_returnsNotMatched() {
        when(stockInfoRepo.findByStockCode("888888")).thenReturn(Optional.of(stockInfo("888888", "测试股票")));
        when(financialRepo.findByStockCodeOrderByReportDateDesc("888888")).thenReturn(Collections.emptyList());

        SopCheckupDTO result = service.sopCheckup("888888");

        assertThat(result.isMatched()).isFalse();
        assertThat(result.getStockCode()).isEqualTo("888888");
        assertThat(result.getMessage()).isNotBlank();
    }

    // ──────────────────────────────────────────────────
    // 用例 3：三项全部 PASS
    // ──────────────────────────────────────────────────

    @Test
    @DisplayName("TC03 - 毛利率稳定/营收持续≥20%/扣非>营收 => overall=pass")
    void tc03_allMetricsPass_returnsOverallPass() {
        String code = "600000";
        when(stockInfoRepo.findByStockCode(code)).thenReturn(Optional.of(stockInfo(code, "优质股")));
        when(financialRepo.findByStockCodeOrderByReportDateDesc(code))
                .thenReturn(buildFinancials(code, 8, 45.0, 30.0, 40.0));

        SopCheckupDTO r = service.sopCheckup(code);

        assertThat(r.isMatched()).isTrue();
        assertThat(r.getGrossMargin().getVerdict()).isEqualTo("pass");
        assertThat(r.getRevenueYoy().getVerdict()).isEqualTo("pass");
        assertThat(r.getProfitYoy().getVerdict()).isEqualTo("pass");
        assertThat(r.getOverallVerdict()).isEqualTo("pass");
        assertThat(r.getOverallSummary()).contains("三大数字全部通过");
    }

    // ──────────────────────────────────────────────────
    // 用例 4：营收增速低于 10% => overall=fail
    // ──────────────────────────────────────────────────

    @Test
    @DisplayName("TC04 - 营收同比<10% 触发 fail，整体判定 fail")
    void tc04_lowRevenue_returnsFail() {
        String code = "600001";
        when(stockInfoRepo.findByStockCode(code)).thenReturn(Optional.of(stockInfo(code, "慢增长股")));
        when(financialRepo.findByStockCodeOrderByReportDateDesc(code))
                .thenReturn(buildFinancials(code, 8, 40.0, 5.0, 5.0));

        SopCheckupDTO r = service.sopCheckup(code);

        assertThat(r.getRevenueYoy().getVerdict()).isEqualTo("fail");
        assertThat(r.getOverallVerdict()).isEqualTo("fail");
    }

    // ──────────────────────────────────────────────────
    // 用例 5：毛利率大幅下滑 => 毛利率 fail
    // ──────────────────────────────────────────────────

    @Test
    @DisplayName("TC05 - 毛利率从 50% 跌至 40%，下滑>3pct => verdict=fail")
    void tc05_grossMarginDroppingBadly_returnsFail() {
        String code = "600002";
        when(stockInfoRepo.findByStockCode(code)).thenReturn(Optional.of(stockInfo(code, "毛利下滑股")));
        // 8条，从旧到新（但我们 buildFinancials 是 desc，最新在前）
        // 用 buildFinancialsWithTrend：index0=最新，index7=最旧
        double[] grossMargins = {40.0, 42.0, 43.0, 44.0, 45.0, 46.0, 48.0, 50.0};
        when(financialRepo.findByStockCodeOrderByReportDateDesc(code))
                .thenReturn(buildFinancialsWithTrend(code, grossMargins, 25.0, 30.0));

        SopCheckupDTO r = service.sopCheckup(code);

        assertThat(r.getGrossMargin().getVerdict()).isEqualTo("fail");
    }

    // ──────────────────────────────────────────────────
    // 用例 6：毛利率下滑 1-3pct => warn
    // ──────────────────────────────────────────────────

    @Test
    @DisplayName("TC06 - 毛利率下滑 2pct (1~3区间) => verdict=warn")
    void tc06_grossMarginSlightDrop_returnsWarn() {
        String code = "600003";
        when(stockInfoRepo.findByStockCode(code)).thenReturn(Optional.of(stockInfo(code, "毛利微降股")));
        double[] grossMargins = {43.0, 43.5, 44.0, 44.5, 44.5, 44.8, 45.0, 45.0};
        when(financialRepo.findByStockCodeOrderByReportDateDesc(code))
                .thenReturn(buildFinancialsWithTrend(code, grossMargins, 25.0, 30.0));

        SopCheckupDTO r = service.sopCheckup(code);

        assertThat(r.getGrossMargin().getVerdict()).isEqualTo("warn");
    }

    // ──────────────────────────────────────────────────
    // 用例 7：扣非 < 营收 且差距 > 5pct => warn/fail
    // ──────────────────────────────────────────────────

    @Test
    @DisplayName("TC07 - 扣非(5%)远落后营收(25%)，差距>5pct => profitYoy=fail")
    void tc07_profitFarBehindRevenue_returnsFail() {
        String code = "600004";
        when(stockInfoRepo.findByStockCode(code)).thenReturn(Optional.of(stockInfo(code, "规模不经济股")));
        when(financialRepo.findByStockCodeOrderByReportDateDesc(code))
                .thenReturn(buildFinancials(code, 8, 40.0, 25.0, 5.0));

        SopCheckupDTO r = service.sopCheckup(code);

        assertThat(r.getProfitYoy().getVerdict()).isEqualTo("fail");
    }

    // ──────────────────────────────────────────────────
    // 用例 8：营收刚好 ≥20% 但未连续（60% 以下季度满足） => warn
    // ──────────────────────────────────────────────────

    @Test
    @DisplayName("TC08 - 最新营收≥20%但历史仅3/8季度达标(不足60%) => revenueYoy=warn")
    void tc08_recentHighRevenueButNotConsistent_returnsWarn() {
        String code = "600005";
        when(stockInfoRepo.findByStockCode(code)).thenReturn(Optional.of(stockInfo(code, "偶尔高增股")));
        // 8条 desc：最新3条 revenueYoy=25，其余=10（3/8=37.5% < 60%）
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

    // ──────────────────────────────────────────────────
    // 用例 9：按股票代码（6位数字）解析
    // ──────────────────────────────────────────────────

    @Test
    @DisplayName("TC09 - 6位股票代码直接解析，stockName 取自 stock_info")
    void tc09_resolveByCode_nameFromStockInfo() {
        String code = "600519";
        when(stockInfoRepo.findByStockCode(code)).thenReturn(Optional.of(stockInfo(code, "贵州茅台")));
        when(financialRepo.findByStockCodeOrderByReportDateDesc(code))
                .thenReturn(buildFinancials(code, 4, 91.0, 10.0, 10.0));

        SopCheckupDTO r = service.sopCheckup(code);

        assertThat(r.isMatched()).isTrue();
        assertThat(r.getStockCode()).isEqualTo(code);
        assertThat(r.getStockName()).isEqualTo("贵州茅台");
    }

    // ──────────────────────────────────────────────────
    // 用例 10：series 长度不超过 8
    // ──────────────────────────────────────────────────

    @Test
    @DisplayName("TC10 - 数据库有12条记录，series 只取最近8条")
    void tc10_moreRecordsThanLimit_onlyTakes8() {
        String code = "600006";
        when(stockInfoRepo.findByStockCode(code)).thenReturn(Optional.of(stockInfo(code, "数据多股")));
        when(financialRepo.findByStockCodeOrderByReportDateDesc(code))
                .thenReturn(buildFinancials(code, 12, 40.0, 25.0, 30.0));

        SopCheckupDTO r = service.sopCheckup(code);

        assertThat(r.getGrossMargin().getSeries()).hasSize(8);
        assertThat(r.getRevenueYoy().getSeries()).hasSize(8);
        assertThat(r.getProfitYoy().getSeries()).hasSize(8);
    }

    // ──────────────────────────────────────────────────
    // 用例 11：null keyword 不抛异常
    // ──────────────────────────────────────────────────

    @Test
    @DisplayName("TC11 - keyword 为 null 时安全返回 matched=false")
    void tc11_nullKeyword_doesNotThrow() {
        // null → "" → resolveStock 直接 return Optional.empty()（isEmpty 提前退出），无需 stub

        SopCheckupDTO r = service.sopCheckup(null);

        assertThat(r.isMatched()).isFalse();
    }

    // ──────────────────────────────────────────────────
    // 用例 12：毛利率仅1条数据（无法比较趋势）-> pass
    // ──────────────────────────────────────────────────

    @Test
    @DisplayName("TC12 - 只有1条财务数据，毛利率无法算趋势，verdict=pass(稳定)")
    void tc12_singleRecord_grossMarginPassAsStable() {
        String code = "600007";
        when(stockInfoRepo.findByStockCode(code)).thenReturn(Optional.of(stockInfo(code, "新上市股")));
        when(financialRepo.findByStockCodeOrderByReportDateDesc(code))
                .thenReturn(buildFinancials(code, 1, 50.0, 25.0, 30.0));

        SopCheckupDTO r = service.sopCheckup(code);

        assertThat(r.isMatched()).isTrue();
        // first == latest, delta=0, 属于稳定区间 [-1, +inf)
        assertThat(r.getGrossMargin().getVerdict()).isEqualTo("pass");
    }
}
