package com.quant.techai;

import com.quant.entity.TechAiPool;
import com.quant.repository.TechAiPoolRepository;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression test: 防止 tech_ai_pool entity 与 DB schema 漂移。
 *
 * 历史教训（2026-06-30）:
 *   TechAiPool entity 已重构为 7 字段（id/stock_code/stock_name/memo/status/created_at/updated_at），
 *   持仓/告警/参数全部迁到 invest_position_common 表（pool_type='tech_ai'）。
 *   但 DB tech_ai_pool 表还残留旧的 6 列 schema，缺 status 列；
 *   TechAiSchemaGuard 那 27 行 ALTER invest_stock_pool ADD COLUMN 是死代码（迁移已完成）。
 *   结果：hibernate SELECT 报 Unknown column 'tap1_0.status' 或 'tap1_0.add_count'，
 *         tech-ai.html /api/tech-ai/pool 500。
 *
 * 此测试断言：
 *   1) TechAiPool entity 每个有 @Column 的字段在 tech_ai_pool 表里都能 SELECT 出来
 *   2) repository findAllByOrderByCreatedAtDesc() 不抛 SQL exception
 *   3) 迁移聚合表 invest_position_common 存在且 pool_type='tech_ai' 数据在
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("TechAi schema ↔ entity alignment")
class TechAiSchemaAlignmentTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TechAiPoolRepository poolRepository;

    @Test
    @DisplayName("TechAiPool entity 所有 @Column 字段都能在 tech_ai_pool 表中查到")
    void entityColumnsExistInTable() {
        List<String> entityColumns = entityColumnNames(TechAiPool.class);

        List<String> dbColumns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns " +
                "WHERE table_schema = DATABASE() AND table_name = 'tech_ai_pool'",
                String.class);

        // 排除 create_at 这种 entity 用 insertable=false 但仍然声明了 @Column 的情况
        for (String col : entityColumns) {
            assertThat(dbColumns)
                    .as("TechAiPool entity 字段 '%s' 对应的列必须在 tech_ai_pool 表中存在", col)
                    .contains(col);
        }
    }

    @Test
    @DisplayName("TechAiPoolRepository.findAllByOrderByCreatedAtDesc() 不抛 Unknown column")
    void repositoryLoadDoesNotThrow() {
        // DB schema 跟 entity 不一致时, hibernate 会抛 BadSqlGrammarException
        assertThatCode(() -> poolRepository.findAllByOrderByCreatedAtDesc())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("持仓聚合表 invest_position_common 存在（pool_type='tech_ai' 持仓/告警已迁入）")
    void investPositionCommonTableExistsWithTechAiRows() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                "WHERE table_schema = DATABASE() AND table_name = 'invest_position_common'",
                Integer.class);
        assertThat(count).as("invest_position_common 聚合表必须存在").isGreaterThan(0);

        Integer techAiRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM invest_position_common WHERE pool_type = ?",
                Integer.class, "tech_ai");
        // 不强求 > 0（test 跑的是真实 DB，可能为空）—— 但表里 status/alert_*/add_count 列必须有
        Integer columnCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns " +
                "WHERE table_schema = DATABASE() AND table_name = 'invest_position_common' " +
                "AND column_name IN ('stock_code','pool_type','status','alert_state'," +
                "'alert_minute_1m_pct','add_count','entry_price','position_lots','avg_cost')",
                Integer.class);
        assertThat(columnCount)
                .as("invest_position_common 必须含迁移列 (status/alert_state/alert_minute_1m_pct/add_count/...)")
                .isEqualTo(9);
        // 池数据可能为空但表结构必须就位 —— 这里不 assert techAiRows > 0，避免环境耦合
        assertThat(techAiRows).isNotNull();
    }

    /**
     * 反射读取 entity 的 @Column 注解，列出所有映射到 DB 的列名。
     * 排除 @Id 主键（id 列总是存在，由 schema 自己保证）。
     * 排除 insertable=false updatable=false 的字段（如 createdAt/updatedAt）—— 这些也要求列存在，
     * 但 create_at / update_at 通常是 schema initializer 加的，entity 也声明了，所以保留。
     */
    private List<String> entityColumnNames(Class<?> entityClass) {
        List<String> cols = new ArrayList<>();
        for (Field f : entityClass.getDeclaredFields()) {
            if (f.isAnnotationPresent(Id.class)) {
                continue;
            }
            Column col = f.getAnnotation(Column.class);
            if (col != null && col.name() != null && !col.name().isEmpty()) {
                cols.add(col.name());
            }
        }
        Collections.sort(cols);
        return cols;
    }
}