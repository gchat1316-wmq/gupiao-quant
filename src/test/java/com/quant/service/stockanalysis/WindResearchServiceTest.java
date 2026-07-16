package com.quant.service.stockanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.quant.dto.stockanalysis.WindResearchContext;
import com.quant.service.prosperitystrong.WindAifinMarketClient;

@ExtendWith(MockitoExtension.class)
@DisplayName("WindResearchService")
class WindResearchServiceTest {

  @Mock WindAifinMarketClient windClient;

  private WindResearchService service;
  private final ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    service = new WindResearchService(windClient);
  }

  @Test
  @DisplayName("Wind 未安装时直接返回 available=false, 不调 Wind")
  void fetchSkipsWhenWindNotInstalled() throws Exception {
    when(windClient.isInstalled()).thenReturn(false);
    WindResearchContext ctx = service.fetch("002851", "麦格米特", "full");
    assertThat(ctx.isAvailable()).isFalse();
    assertThat(ctx.isWindInstalled()).isFalse();
    assertThat(ctx.getConsensus()).isNull();
    assertThat(ctx.getReports()).isNull();
    verify(windClient, never()).call(any(), any(), any());
  }

  @Test
  @DisplayName("Wind 无 Key 时降级, 不调 Wind")
  void fetchSkipsWhenNoKey() throws Exception {
    when(windClient.isInstalled()).thenReturn(true);
    when(windClient.hasApiKey()).thenReturn(false);
    WindResearchContext ctx = service.fetch("002851", "麦格米特", "gaojingqi");
    assertThat(ctx.isAvailable()).isFalse();
    assertThat(ctx.isWindInstalled()).isTrue();
    assertThat(ctx.isWindHasKey()).isFalse();
    verify(windClient, never()).call(any(), any(), any());
  }

  @Test
  @DisplayName("一致预期命中时 consensus 有 rating/targetPrice/sourceRowCount")
  void fetchConsensusParsesAnalyticsDataResponse() throws Exception {
    when(windClient.isInstalled()).thenReturn(true);
    when(windClient.hasApiKey()).thenReturn(true);
    var node =
        buildAnalyticsDataResponse(
            new String[] {"Wind代码", "证券简称", "一致预测目标价", "交易币种", "综合评级_中文"},
            new Object[] {"002851.SZ", "麦格米特", 80.5, "CNY", "增持"},
            "一致预期");
    when(windClient.call(eq("analytics_data"), eq("get_financial_data"), any())).thenReturn(node);

    WindResearchContext ctx = service.fetch("002851", "麦格米特", "gaojingqi");
    assertThat(ctx.isAvailable()).isTrue();
    assertThat(ctx.getConsensus()).isNotNull();
    assertThat(ctx.getConsensus().getRating()).isEqualTo("增持");
    assertThat(ctx.getConsensus().getTargetPrice()).isEqualTo(80.5);
    assertThat(ctx.getConsensus().getCurrency()).isEqualTo("CNY");
    assertThat(ctx.getConsensus().getSourceRowCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("Wind call 抛异常时不阻塞, 返回 available=false")
  void fetchReturnsEmptyOnWindException() throws Exception {
    when(windClient.isInstalled()).thenReturn(true);
    when(windClient.hasApiKey()).thenReturn(true);
    when(windClient.call(any(), any(), any())).thenThrow(new RuntimeException("Wind down"));
    WindResearchContext ctx = service.fetch("002851", "麦格米特", "gaojingqi");
    assertThat(ctx.isAvailable()).isFalse();
  }

  @Test
  @DisplayName("缓存命中时不调 Wind, 直接返回上次结果")
  void fetchUsesCacheOnSecondCall() throws Exception {
    when(windClient.isInstalled()).thenReturn(true);
    when(windClient.hasApiKey()).thenReturn(true);
    var node = buildAnalyticsDataResponse(new String[] {"综合评级_中文"}, new Object[] {"买入"}, "x");
    when(windClient.call(eq("analytics_data"), eq("get_financial_data"), any())).thenReturn(node);

    // 第一次
    WindResearchContext first = service.fetch("002851", "麦格米特", "gaojingqi");
    assertThat(first.isAvailable()).isTrue();
    // 第二次：缓存命中, 不应再调 Wind
    WindResearchContext second = service.fetch("002851", "麦格米特", "gaojingqi");
    assertThat(second.isAvailable()).isTrue();
    assertThat(second.getConsensus().getRating()).isEqualTo("买入");
    verify(windClient, times(1)).call(eq("analytics_data"), eq("get_financial_data"), any());
  }

  @Test
  @DisplayName("不同 method 独立缓存")
  void fetchCachesPerMethod() throws Exception {
    when(windClient.isInstalled()).thenReturn(true);
    when(windClient.hasApiKey()).thenReturn(true);
    var node = buildAnalyticsDataResponse(new String[] {"综合评级_中文"}, new Object[] {"买入"}, "x");
    when(windClient.call(eq("analytics_data"), eq("get_financial_data"), any())).thenReturn(node);

    service.fetch("002851", "麦格米特", "gaojingqi");
    // full 和 gaojingqi 是不同 key, 应当再调一次
    service.fetch("002851", "麦格米特", "full");
    verify(windClient, times(2)).call(eq("analytics_data"), eq("get_financial_data"), any());
  }

  /**
   * 构造 Wind analytics_data.get_financial_data 风格的 mock 返回。 外层 MCP result: {content:[{text:"<json
   * string>"}]} 内层: {data:{data:[{columns, rows, resolved_question}]}}
   */
  private com.fasterxml.jackson.databind.JsonNode buildAnalyticsDataResponse(
      String[] colNames, Object[] row, String resolvedQuestion) throws Exception {
    var columns = mapper.createArrayNode();
    for (String n : colNames) {
      var col = mapper.createObjectNode();
      col.put("name", n);
      columns.add(col);
    }
    var rows = mapper.createArrayNode();
    var rowNode = mapper.createArrayNode();
    for (Object v : row) {
      if (v instanceof Number num) rowNode.add(num.doubleValue());
      else rowNode.add(String.valueOf(v));
    }
    rows.add(rowNode);

    var inner = mapper.createObjectNode();
    var data = inner.putObject("data");
    var first = data.putArray("data");
    var firstObj = first.addObject();
    firstObj.set("columns", columns);
    firstObj.set("rows", rows);
    firstObj.put("resolved_question", resolvedQuestion);

    var outer = mapper.createObjectNode();
    var content = outer.putArray("content");
    var item = content.addObject();
    item.put("type", "text");
    item.put("text", mapper.writeValueAsString(inner));
    return outer;
  }
}
