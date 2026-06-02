package com.quant.entity;

import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InvestStockPool column mapping")
class InvestStockPoolMappingTest {

    @Test
    @DisplayName("历史营收字段映射到已有数据的 rev_* 列")
    void revenueHistoryFieldsMapToRevColumns() throws Exception {
        assertColumnName("revenue2023", "rev_2023");
        assertColumnName("revenue2024", "rev_2024");
        assertColumnName("revenue2025", "rev_2025");
    }

    private void assertColumnName(String fieldName, String columnName) throws Exception {
        Field field = InvestStockPool.class.getDeclaredField(fieldName);
        assertThat(field.getAnnotation(Column.class).name()).isEqualTo(columnName);
    }
}
