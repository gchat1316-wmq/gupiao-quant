package com.quant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "stock-analysis")
public class StockAnalysisProperties {

    /** API Key, 多个以逗号分隔. 留空则禁用鉴权(仅本地调试用) */
    private String apiKeys = "";

    /** baostock python 脚本路径 (相对项目根目录, 也可通过 stock-analysis.python-script 覆盖) */
    private String pythonScript = "scripts/baostock_client.py";

    /** Python 解释器 */
    private String pythonCommand = "python3";

    /** 单次分析超时(秒) */
    private int timeoutSeconds = 90;

    /** 是否启用 */
    private boolean enabled = true;
}
