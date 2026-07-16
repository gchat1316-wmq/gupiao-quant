package com.quant.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.persistence.Column;

@DisplayName("InvestStockPool column mapping")
class InvestStockPoolMappingTest {

  @Test
  @DisplayName("公司名称字段映射到股票池 stock_name 列")
  void stockNameFieldMapsToStockNameColumn() throws Exception {
    assertColumnName("stockName", "stock_name");
  }

  @Test
  @DisplayName("历史营收字段映射到已有数据的 rev_* 列")
  void revenueHistoryFieldsMapToRevColumns() throws Exception {
    assertColumnName("revenue2023", "rev_2023");
    assertColumnName("revenue2024", "rev_2024");
    assertColumnName("revenue2025", "rev_2025");
  }

  @Test
  @DisplayName("科技风投截图看板字段映射到持久化列")
  void techVcSnapshotFieldsMapToColumns() throws Exception {
    assertColumnName("displayOrder", "display_order");
    assertColumnName("currentMarketCap", "current_market_cap");
    assertColumnName("ytdGainPct", "ytd_gain_pct");
    assertColumnName("poolDataUpdatedAt", "pool_data_updated_at");
    assertColumnName("poolUpdateError", "pool_update_error");
  }

  private void assertColumnName(String fieldName, String columnName) throws Exception {
    Field field = InvestStockPool.class.getDeclaredField(fieldName);
    assertThat(field.getAnnotation(Column.class).name()).isEqualTo(columnName);
  }
}
