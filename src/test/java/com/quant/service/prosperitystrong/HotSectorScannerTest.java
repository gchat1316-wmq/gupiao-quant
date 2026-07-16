package com.quant.service.prosperitystrong;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.quant.config.ProsperityStrongProperties;
import com.quant.entity.ProsperityHotSector;

@DisplayName("HotSectorScanner")
class HotSectorScannerTest {

  @Test
  @DisplayName("a_stock_data 源解析行业板块排名增强字段")
  void scanAStockDataParsesIndustryComparisonFields() throws Exception {
    ProsperityStrongProperties props = props("a_stock_data");
    HotSectorScanner scanner = new StubScanner(props, aStockDataBody(), null);

    List<ProsperityHotSector> sectors = scanner.scan(LocalDate.of(2026, 6, 16));

    assertThat(sectors).hasSize(1);
    ProsperityHotSector first = sectors.get(0);
    assertThat(first.getSectorCode()).isEqualTo("BK1036");
    assertThat(first.getSectorName()).isEqualTo("半导体");
    assertThat(first.getChange1d()).isEqualByComparingTo("3.42");
    assertThat(first.getUpCount()).isEqualTo(72);
    assertThat(first.getDownCount()).isEqualTo(8);
    assertThat(first.getLeadStock()).isEqualTo("长川科技");
    assertThat(first.getLeadStockChange()).isEqualByComparingTo("12.34");
    assertThat(first.getDataSource()).isEqualTo("a_stock_data");
  }

  @Test
  @DisplayName("a_stock_data 失败时回退 eastmoney, eastmoney 也失败时才 mock")
  void scanAStockDataFallsBackToEastMoneyBeforeMock() {
    ProsperityStrongProperties props = props("a_stock_data");
    HotSectorScanner scanner = new StubScanner(props, null, eastMoneyBody());

    List<ProsperityHotSector> sectors = scanner.scan(LocalDate.of(2026, 6, 16));

    assertThat(sectors).hasSize(1);
    assertThat(sectors.get(0).getSectorName()).isEqualTo("机器人");
    assertThat(sectors.get(0).getDataSource()).isEqualTo("eastmoney");
  }

  private ProsperityStrongProperties props(String source) {
    ProsperityStrongProperties props = new ProsperityStrongProperties();
    props.setMaxSectors(5);
    props.getSource().setSector(source);
    props.getSource().setTimeoutSeconds(1);
    return props;
  }

  private String aStockDataBody() {
    return """
                {"data":{"diff":[
                  {"f12":"BK1036","f14":"半导体","f3":3.42,"f62":2100000000,"f104":72,"f105":8,"f140":"长川科技","f136":12.34}
                ]}}
                """;
  }

  private String eastMoneyBody() {
    return """
                {"data":{"diff":[
                  {"f12":"BK1187","f14":"机器人","f3":2.10,"f62":900000000,"f104":44,"f105":12,"f140":"绿的谐波","f136":7.89}
                ]}}
                """;
  }

  private static class StubScanner extends HotSectorScanner {
    private final String aStockDataBody;
    private final String eastMoneyBody;

    StubScanner(ProsperityStrongProperties props, String aStockDataBody, String eastMoneyBody) {
      super(props);
      this.aStockDataBody = aStockDataBody;
      this.eastMoneyBody = eastMoneyBody;
    }

    @Override
    protected String fetchSectorBody(String source) throws Exception {
      if ("a_stock_data".equals(source)) {
        if (aStockDataBody == null) throw new IllegalStateException("a-stock-data down");
        return aStockDataBody;
      }
      if ("eastmoney".equals(source)) {
        if (eastMoneyBody == null) throw new IllegalStateException("eastmoney down");
        return eastMoneyBody;
      }
      throw new IllegalStateException("unexpected source: " + source);
    }
  }
}
