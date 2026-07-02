package com.quant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "baostock-sync")
public class BaostockSyncProperties {

    private boolean enabled = true;
    private boolean startupEnabled = true;
    private int startupDaysBack = 45;
    private int dailyDaysBack = 7;
    private String dailyCron = "0 20 18 * * MON-FRI";
    private String pythonCommand = "python3";
    private int timeoutSeconds = 1800;

    /** BaoStock 财务（季度 ROE/毛利率/净利率/资产负债率 等）→ trade_stock_financial 周期同步。 */
    private boolean financialEnabled = false;
    /** 仅插入表中缺失的 (stock_code, report_date)，不会覆盖既有的 qmt/wind 数据。 */
    private String financialCron = "0 30 19 * * MON-FRI";
    /**
     * 历史回填最早年。BaoStock 财务历史有效覆盖约为最近 2~3 年，
     * 2024 起既保证新股/最近 8 季度全覆盖又把单次同步压在 30min 之内。
     * 历史数据由 qmt/eastmoney/akshare 各自提供，无需 BaoStock 全量回填。
     */
    private int financialStartYear = 2024;
}
