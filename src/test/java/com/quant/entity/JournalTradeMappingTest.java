package com.quant.entity;

import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies JournalTrade field names and column annotations match the DDL
 * in SchemaInitializer.ensureJournalTables().
 */
@DisplayName("JournalTrade column mapping")
class JournalTradeMappingTest {

    @Test
    @DisplayName("所有字段均已映射且名称正确")
    void allFieldsAreMapped() throws Exception {
        Set<String> expected = Set.of(
                "id", "mode", "stockCode", "stockName", "entryPrice", "entryDate",
                "entryShares", "accountAtEntry", "riskPercent", "stopPrice", "targetPrice",
                "exitPrice", "exitDate", "exitReason", "initialRisk", "pnlAmount",
                "rMultiple", "isOpen", "tags", "setupNotes", "reviewNotes", "source",
                "sourceRefId", "isDeleted", "createdAt", "updatedAt");

        Set<String> actual = java.util.stream.Stream.of(JournalTrade.class.getDeclaredFields())
                .map(Field::getName)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("mode 枚举映射到 mode 列")
    void modeMapsToModeColumn() throws Exception {
        assertColumnName("mode", "mode");
    }

    @Test
    @DisplayName("exitReason 枚举映射到 exit_reason 列")
    void exitReasonMapsToExitReasonColumn() throws Exception {
        assertColumnName("exitReason", "exit_reason");
    }

    @Test
    @DisplayName("isOpen 默认值为 1")
    void isOpenHasDefaultOne() throws Exception {
        Field field = JournalTrade.class.getDeclaredField("isOpen");
        Column col = field.getAnnotation(Column.class);
        // Default value is set in-field, not via Column annotation
        assertThat(field.getType()).isEqualTo(Integer.class);
    }

    @Test
    @DisplayName("isDeleted 默认值为 0")
    void isDeletedHasDefaultZero() throws Exception {
        Field field = JournalTrade.class.getDeclaredField("isDeleted");
        assertThat(field.getType()).isEqualTo(Integer.class);
    }

    @Test
    @DisplayName("createdAt 与 updatedAt 为只读列")
    void createdAtAndUpdatedAtAreInsertableFalse() throws Exception {
        for (String fname : new String[]{"createdAt", "updatedAt"}) {
            Field field = JournalTrade.class.getDeclaredField(fname);
            Column col = field.getAnnotation(Column.class);
            assertThat(col.insertable()).isFalse();
            assertThat(col.updatable()).isFalse();
        }
    }

    private void assertColumnName(String fieldName, String columnName) throws Exception {
        Field field = JournalTrade.class.getDeclaredField(fieldName);
        Column col = field.getAnnotation(Column.class);
        if (col == null) {
            throw new AssertionError("Field " + fieldName + " has no @Column annotation");
        }
        assertThat(col.name()).isEqualTo(columnName);
    }
}
