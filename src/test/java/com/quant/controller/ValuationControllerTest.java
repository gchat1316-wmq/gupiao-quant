package com.quant.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.quant.service.Ps10ValuationService;

/**
 * POST /api/valuation/ps10 —— 给前端 calcPS 用。
 *
 * <p>场景：用户在"科技风投 · 10 倍 PS 法"卡片里手动输入市值和 Y0/Y1/Y2 营收， 前端调这个端点拿回 verdict + commentary + 三行明细。
 *
 * <p>2026-07-01 新增：迁移前端 calcPS 逻辑到后端，前端只负责显示。
 */
@WebMvcTest(ValuationController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("ValuationController")
class ValuationControllerTest {

  @Autowired MockMvc mvc;

  @MockBean Ps10ValuationService ps10ValuationService;
  @MockBean com.quant.security.JwtTokenProvider jwtTokenProvider;
  @MockBean com.quant.repository.UserRepository userRepository;

  @Nested
  @DisplayName("POST /api/valuation/ps10")
  class CalcPs10 {

    @Test
    @DisplayName("正常入参：返回 verdict + commentary + 三行明细")
    void returnsVerdictAndRows() throws Exception {
      Ps10ValuationService.Ps10Result stub =
          new Ps10ValuationService.Ps10Result(
              true,
              "合理",
              "当前市值 113.7 亿在 Y1×10=90.8 亿至 Y2×10=118.0 亿区间，透支约 25%（注：净利率 23.51% 偏低）",
              "10 倍 PS 法",
              null,
              new java.math.BigDecimal("9.08"),
              new java.math.BigDecimal("11.80"),
              new java.math.BigDecimal("90.8"),
              new java.math.BigDecimal("118.0"),
              new java.math.BigDecimal("23.51"),
              new java.math.BigDecimal("20.00"),
              new java.math.BigDecimal("113.70"));
      when(ps10ValuationService.evaluateFromInputs(any(), any(), any(), any(), any()))
          .thenReturn(stub);

      String body =
          """
                    {
                      "marketCap": 113.70,
                      "revenueY0": 6.73,
                      "revenueY1": 9.08,
                      "revenueY2": 11.80,
                      "netMarginPct": 23.51
                    }
                    """;
      mvc.perform(post("/api/valuation/ps10").contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.verdict").value("合理"))
          .andExpect(
              jsonPath("$.commentary").value(org.hamcrest.Matchers.containsString("113.7 亿")))
          .andExpect(jsonPath("$.rows.length()").value(3))
          .andExpect(jsonPath("$.rows[0].label").value("今年"))
          .andExpect(jsonPath("$.rows[1].label").value("明年"))
          .andExpect(jsonPath("$.rows[2].label").value("后年"));
    }

    @Test
    @DisplayName("缺 netMarginPct 也能算（前端可选字段）")
    void worksWithoutNetMargin() throws Exception {
      Ps10ValuationService.Ps10Result stub =
          new Ps10ValuationService.Ps10Result(
              true,
              "低估",
              "当前市值 100 亿 < Y1×10=200 亿",
              "10 倍 PS 法",
              null,
              new java.math.BigDecimal("20"),
              new java.math.BigDecimal("30"),
              new java.math.BigDecimal("200"),
              new java.math.BigDecimal("300"),
              null,
              null,
              new java.math.BigDecimal("100"));
      when(ps10ValuationService.evaluateFromInputs(
              eq(new java.math.BigDecimal("100")),
              any(),
              any(),
              any(),
              eq((java.math.BigDecimal) null)))
          .thenReturn(stub);

      String body =
          """
                    { "marketCap": 100, "revenueY0": 10, "revenueY1": 20, "revenueY2": 30 }
                    """;
      mvc.perform(post("/api/valuation/ps10").contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.verdict").value("低估"));
    }

    @Test
    @DisplayName("缺 marketCap → verdict=—，不抛 500")
    void missingMarketCap() throws Exception {
      Ps10ValuationService.Ps10Result stub =
          new Ps10ValuationService.Ps10Result(
              false,
              "—",
              "缺少市值数据，无法判定",
              "10 倍 PS 法",
              null,
              new java.math.BigDecimal("20"),
              new java.math.BigDecimal("30"),
              new java.math.BigDecimal("200"),
              new java.math.BigDecimal("300"),
              null,
              null,
              null);
      when(ps10ValuationService.evaluateFromInputs(
              eq((java.math.BigDecimal) null), any(), any(), any(), any()))
          .thenReturn(stub);

      String body =
          """
                    { "revenueY0": 10, "revenueY1": 20, "revenueY2": 30 }
                    """;
      mvc.perform(post("/api/valuation/ps10").contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.verdict").value("—"));
    }
  }
}
