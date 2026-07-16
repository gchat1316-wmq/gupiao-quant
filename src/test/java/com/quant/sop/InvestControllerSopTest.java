package com.quant.sop;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import com.quant.controller.InvestController;
import com.quant.dto.invest.SopCheckupDTO;
import com.quant.repository.UserRepository;
import com.quant.security.JwtTokenProvider;
import com.quant.service.InvestPoolMetaService;
import com.quant.service.InvestPoolRefreshService;
import com.quant.service.InvestPoolSeedService;
import com.quant.service.InvestService;
import com.quant.service.InvestWeeklyOpportunityService;
import com.quant.service.OcrPoolImportService;
import com.quant.service.notification.PriceMonitorService;

@WebMvcTest(InvestController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("InvestController - /api/invest/sop/checkup")
class InvestControllerSopTest {

  @Autowired MockMvc mvc;
  @MockBean InvestService investService;
  @MockBean OcrPoolImportService ocrPoolImportService;
  @MockBean PriceMonitorService priceMonitorService;
  @MockBean InvestPoolSeedService investPoolSeedService;
  @MockBean InvestPoolRefreshService investPoolRefreshService;
  @MockBean InvestPoolMetaService poolMetaService;
  @MockBean InvestWeeklyOpportunityService weeklyOpportunityService;
  @MockBean JwtTokenProvider jwtTokenProvider;
  @MockBean UserRepository userRepository;

  private SopCheckupDTO.MetricCheck metric(
      String label, String verdict, double latest, String tip) {
    return SopCheckupDTO.MetricCheck.builder()
        .label(label)
        .unit("%")
        .series(
            List.of(
                SopCheckupDTO.QuarterPoint.builder()
                    .quarter("25Q4")
                    .value(BigDecimal.valueOf(latest))
                    .build()))
        .latest(BigDecimal.valueOf(latest))
        .verdict(verdict)
        .tip(tip)
        .build();
  }

  // ──────────────────────────────────────────────────
  // CT01：有效股票 => 200 + matched=true
  // ──────────────────────────────────────────────────

  @Test
  @DisplayName("CT01 - 有效股票代码返回 200，matched=true，包含三项指标")
  void ct01_validStockCode_returns200WithMatchedTrue() throws Exception {
    SopCheckupDTO dto =
        SopCheckupDTO.builder()
            .matched(true)
            .stockCode("600519")
            .stockName("贵州茅台")
            .grossMargin(metric("毛利率", "pass", 91.5, "稳定"))
            .revenueYoy(metric("营收同比", "fail", 6.3, "增长乏力"))
            .profitYoy(metric("扣非净利润同比", "warn", 1.4, "同步"))
            .overallVerdict("fail")
            .overallSummary("数字不漂亮")
            .build();
    when(investService.sopCheckup("600519")).thenReturn(dto);

    mvc.perform(get("/api/invest/sop/checkup").param("keyword", "600519"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.matched").value(true))
        .andExpect(jsonPath("$.stockCode").value("600519"))
        .andExpect(jsonPath("$.stockName").value("贵州茅台"))
        .andExpect(jsonPath("$.overallVerdict").value("fail"))
        .andExpect(jsonPath("$.grossMargin.verdict").value("pass"))
        .andExpect(jsonPath("$.revenueYoy.verdict").value("fail"))
        .andExpect(jsonPath("$.profitYoy.verdict").value("warn"))
        .andExpect(jsonPath("$.grossMargin.series").isArray())
        .andExpect(jsonPath("$.grossMargin.series[0].quarter").value("25Q4"));
  }

  // ──────────────────────────────────────────────────
  // CT02：股票不存在 => 200 + matched=false + message
  // ──────────────────────────────────────────────────

  @Test
  @DisplayName("CT02 - 不存在股票返回 200，matched=false，message 非空")
  void ct02_nonExistentStock_returns200WithMatchedFalse() throws Exception {
    SopCheckupDTO dto =
        SopCheckupDTO.builder().matched(false).message("未找到股票：北方华创（请输入6位代码或完整名称）").build();
    when(investService.sopCheckup("北方华创")).thenReturn(dto);

    mvc.perform(get("/api/invest/sop/checkup").param("keyword", "北方华创"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.matched").value(false))
        .andExpect(jsonPath("$.message").isNotEmpty());
  }

  // ──────────────────────────────────────────────────
  // CT03：缺少 keyword 参数 => 400
  // ──────────────────────────────────────────────────

  @Test
  @DisplayName("CT03 - 缺少 keyword 参数返回 400")
  void ct03_missingKeyword_returns400() throws Exception {
    mvc.perform(get("/api/invest/sop/checkup")).andExpect(status().isBadRequest());
  }

  // ──────────────────────────────────────────────────
  // CT04：overallVerdict 取值范围校验
  // ──────────────────────────────────────────────────

  @Test
  @DisplayName("CT04 - overallVerdict 只能为 pass/warn/fail 之一")
  void ct04_overallVerdictIsValidEnum() throws Exception {
    for (String verdict : new String[] {"pass", "warn", "fail"}) {
      SopCheckupDTO dto =
          SopCheckupDTO.builder()
              .matched(true)
              .stockCode("000001")
              .stockName("测试股")
              .grossMargin(metric("毛利率", verdict, 40.0, "tip"))
              .revenueYoy(metric("营收同比", verdict, 25.0, "tip"))
              .profitYoy(metric("扣非净利润同比", verdict, 28.0, "tip"))
              .overallVerdict(verdict)
              .overallSummary("summary")
              .build();
      when(investService.sopCheckup("000001")).thenReturn(dto);

      mvc.perform(get("/api/invest/sop/checkup").param("keyword", "000001"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.overallVerdict").value(verdict));
    }
  }

  // ──────────────────────────────────────────────────
  // CT05：series 数据结构完整性
  // ──────────────────────────────────────────────────

  @Test
  @DisplayName("CT05 - series 每条含 quarter 和 value 字段")
  void ct05_seriesStructureIsCorrect() throws Exception {
    SopCheckupDTO.MetricCheck m =
        SopCheckupDTO.MetricCheck.builder()
            .label("毛利率")
            .unit("%")
            .series(
                List.of(
                    SopCheckupDTO.QuarterPoint.builder()
                        .quarter("25Q3")
                        .value(BigDecimal.valueOf(45.0))
                        .build(),
                    SopCheckupDTO.QuarterPoint.builder()
                        .quarter("25Q4")
                        .value(BigDecimal.valueOf(43.0))
                        .build()))
            .latest(BigDecimal.valueOf(43.0))
            .verdict("warn")
            .tip("下滑")
            .build();
    SopCheckupDTO dto =
        SopCheckupDTO.builder()
            .matched(true)
            .stockCode("000002")
            .stockName("系列测试股")
            .grossMargin(m)
            .revenueYoy(metric("营收同比", "pass", 25.0, "ok"))
            .profitYoy(metric("扣非净利润同比", "pass", 28.0, "ok"))
            .overallVerdict("warn")
            .overallSummary("summary")
            .build();
    when(investService.sopCheckup("000002")).thenReturn(dto);

    mvc.perform(get("/api/invest/sop/checkup").param("keyword", "000002"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.grossMargin.series").isArray())
        .andExpect(jsonPath("$.grossMargin.series.length()").value(2))
        .andExpect(jsonPath("$.grossMargin.series[0].quarter").value("25Q3"))
        .andExpect(jsonPath("$.grossMargin.series[1].quarter").value("25Q4"))
        .andExpect(jsonPath("$.grossMargin.series[1].value").value(43.0));
  }
}
