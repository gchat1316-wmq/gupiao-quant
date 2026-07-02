package com.quant.service;

import com.quant.config.BaostockSyncProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BaostockSyncService")
class BaostockSyncServiceTest {

    @Mock
    JdbcTemplate jdbcTemplate;

    @Mock
    BaostockSyncService.CommandRunner commandRunner;

    private BaostockSyncService service;

    @BeforeEach
    void setUp() {
        BaostockSyncProperties props = new BaostockSyncProperties();
        props.setEnabled(true);
        // 默认 financialEnabled=false，老测试保持原行为
        props.setPythonCommand("python3");
        props.setTimeoutSeconds(120);

        DataSourceProperties dataSourceProperties = new DataSourceProperties();
        dataSourceProperties.setUrl("jdbc:mysql://43.140.208.165:3306/wucai_trade?useUnicode=true");
        dataSourceProperties.setUsername("root");
        dataSourceProperties.setPassword("secret");

        service = new BaostockSyncService(props, dataSourceProperties, jdbcTemplate, commandRunner);
    }

    private BaostockSyncService buildService(boolean financialEnabled, int startYear) {
        BaostockSyncProperties props = new BaostockSyncProperties();
        props.setEnabled(true);
        props.setFinancialEnabled(financialEnabled);
        props.setFinancialStartYear(startYear);
        props.setPythonCommand("python3");
        props.setTimeoutSeconds(120);

        DataSourceProperties dataSourceProperties = new DataSourceProperties();
        dataSourceProperties.setUrl("jdbc:mysql://43.140.208.165:3306/wucai_trade?useUnicode=true");
        dataSourceProperties.setUsername("root");
        dataSourceProperties.setPassword("secret");

        return new BaostockSyncService(props, dataSourceProperties, jdbcTemplate, commandRunner);
    }

    @Test
    @DisplayName("同步时先保障唯一索引，再执行日线和估值脚本")
    void syncRunsDailyAndBasicScriptsWithDatasourceEnv() throws Exception {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("trade_stock_daily"), eq("uk_trade_stock_daily_code_date")))
                .thenReturn(1);
        when(commandRunner.run(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new BaostockSyncService.CommandResult(0, "ok", ""));

        service.syncNow("startup", 30);

        ArgumentCaptor<List<String>> commandCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Map<String, String>> envCaptor = ArgumentCaptor.forClass(Map.class);
        verify(commandRunner, times(2))
                .run(commandCaptor.capture(), org.mockito.ArgumentMatchers.eq(Path.of("").toAbsolutePath()), envCaptor.capture(), eq(Duration.ofSeconds(120)));

        List<List<String>> commands = commandCaptor.getAllValues();
        assertThat(commands.get(0)).containsExactly("python3", "scripts/baostock_daily_sync.py", "--days-back", "30");
        assertThat(commands.get(1)).containsExactly("python3", "scripts/baostock_basic_sync.py");

        Map<String, String> env = envCaptor.getValue();
        assertThat(env).containsEntry("DB_HOST", "43.140.208.165");
        assertThat(env).containsEntry("DB_PORT", "3306");
        assertThat(env).containsEntry("DB_NAME", "wucai_trade");
        assertThat(env).containsEntry("DB_USERNAME", "root");
        assertThat(env).containsEntry("DB_PASSWORD", "secret");
    }

    @Test
    @DisplayName("financial-enabled 时 syncNow 顺次跑 daily/basic/financial 三个 stage")
    void syncRunsThreeStagesWhenFinancialEnabled() throws Exception {
        BaostockSyncService svc = buildService(true, 2024);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("trade_stock_daily"), eq("uk_trade_stock_daily_code_date")))
                .thenReturn(1);
        when(commandRunner.run(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new BaostockSyncService.CommandResult(0, "ok", ""));

        svc.syncNow("startup", 30);

        ArgumentCaptor<List<String>> commandCaptor = ArgumentCaptor.forClass(List.class);
        verify(commandRunner, times(3))
                .run(commandCaptor.capture(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any());

        List<List<String>> commands = commandCaptor.getAllValues();
        assertThat(commands.get(0)).containsExactly("python3", "scripts/baostock_daily_sync.py", "--days-back", "30");
        assertThat(commands.get(1)).containsExactly("python3", "scripts/baostock_basic_sync.py");
        assertThat(commands.get(2)).containsExactly(
                "python3", "scripts/baostock_financial_sync.py", "--start-year", "2024");
    }

    @Test
    @DisplayName("syncFinancialOnly 只跑 financial stage，注入 start-year 与 properties 一致")
    void syncFinancialOnlyRunsFinancialScriptWithConfigStartYear() throws Exception {
        BaostockSyncService svc = buildService(true, 2020);
        when(commandRunner.run(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new BaostockSyncService.CommandResult(0, "ok", ""));

        svc.syncFinancialOnly("manual-backfill");

        ArgumentCaptor<List<String>> commandCaptor = ArgumentCaptor.forClass(List.class);
        verify(commandRunner, times(1))
                .run(commandCaptor.capture(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any());

        assertThat(commandCaptor.getValue()).containsExactly(
                "python3", "scripts/baostock_financial_sync.py", "--start-year", "2020");
    }

    @Test
    @DisplayName("缺少唯一索引时自动创建")
    void syncCreatesUniqueIndexWhenMissing() throws Exception {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("trade_stock_daily"), eq("uk_trade_stock_daily_code_date")))
                .thenReturn(0);
        when(commandRunner.run(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new BaostockSyncService.CommandResult(0, "ok", ""));

        service.syncNow("startup", 15);

        verify(jdbcTemplate).execute(org.mockito.ArgumentMatchers.contains("CREATE UNIQUE INDEX uk_trade_stock_daily_code_date"));
    }

    @Test
    @DisplayName("日线阶段失败时仍继续执行估值阶段，并汇总失败结果")
    void syncContinuesToBasicWhenDailyFails() throws Exception {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("trade_stock_daily"), eq("uk_trade_stock_daily_code_date")))
                .thenReturn(1);
        when(commandRunner.run(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new BaostockSyncService.CommandResult(1, "", "daily boom"))
                .thenReturn(new BaostockSyncService.CommandResult(0, "ok", ""));

        assertThatThrownBy(() -> service.syncNow("startup", 15))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("daily boom");

        verify(commandRunner, times(2))
                .run(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any());
    }
}
