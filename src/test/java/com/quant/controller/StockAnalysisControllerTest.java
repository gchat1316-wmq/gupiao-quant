package com.quant.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.quant.dto.stockanalysis.StockAnalysisResponse;
import com.quant.entity.StockAnalysisRecord;
import com.quant.service.StockAnalysisPdfService;
import com.quant.service.StockAnalysisService;

@DisplayName("StockAnalysisController")
class StockAnalysisControllerTest {

  @Test
  @DisplayName("record 返回统一富报告字段")
  void recordReturnsUnifiedReport() {
    StockAnalysisService service = mock(StockAnalysisService.class);
    StockAnalysisPdfService pdfService = mock(StockAnalysisPdfService.class);
    StockAnalysisController controller = new StockAnalysisController(service, pdfService);

    StockAnalysisRecord record = new StockAnalysisRecord();
    record.setId(9L);
    record.setStatus("SUCCESS");
    record.setStockCode("002851.SZ");
    record.setStockCodeRaw("002851");
    record.setStockName("麦格米特");
    record.setMethod("full");
    record.setElapsedMs(1200);
    record.setSubmittedAt(LocalDateTime.now());
    record.setFinishedAt(LocalDateTime.now());

    StockAnalysisResponse response =
        StockAnalysisResponse.builder()
            .ok(true)
            .code("002851.SZ")
            .name("麦格米特")
            .reportHtml("<html>统一报告</html>")
            .sourceMetadata(Map.of("db", Map.of("available", true)))
            .build();

    when(service.getById(9L)).thenReturn(record);
    when(service.parseRecordJson(record)).thenReturn(response);

    Map<String, Object> result = controller.record(9L);

    assertThat(result.get("ok")).isEqualTo(true);
    assertThat(result.get("status")).isEqualTo("SUCCESS");
    StockAnalysisResponse report = (StockAnalysisResponse) result.get("report");
    assertThat(report.getReportHtml()).contains("统一报告");
    assertThat(((Map<?, ?>) report.getSourceMetadata().get("db")).get("available")).isEqualTo(true);
  }
}
