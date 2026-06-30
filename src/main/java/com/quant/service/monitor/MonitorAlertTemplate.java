package com.quant.service.monitor;

import org.springframework.stereotype.Component;

/**
 * 把 MonitorSignal 渲染成 Server酱 Markdown。支持 3 种模板。
 *  - standard : 标题 + 单段详尽 Markdown（默认）
 *  - compact  : 单行紧凑摘要，便于在告警风暴中快速扫读
 *  - verbose  : 标准 + 元数据块，便于存档回溯
 */
@Component
public class MonitorAlertTemplate {

    public static String render(MonitorSignal sig) {
        if (sig == null) return "";
        if (sig.getTemplate() == null) {
            return sig.getTitle() + "\n\n" + sig.getContent();
        }
        switch (sig.getTemplate().toLowerCase()) {
            case "compact":
                return compact(sig);
            case "verbose":
                return verbose(sig);
            case "standard":
            default:
                return sig.getTitle() + "\n\n" + sig.getContent();
        }
    }

    private static String compact(MonitorSignal sig) {
        return String.format("%s · %s · 现价 %s",
                sig.getTitle() == null ? "" : sig.getTitle(),
                sig.getStockCode(),
                sig.getTriggerPrice());
    }

    private static String verbose(MonitorSignal sig) {
        return String.format("""
                        %s

                        ============= 详情 =============

                        %s

                        ---
                        信号类型: %s
                        触发价:   %s
                        阈值:     %s
                        当前值:   %s
                        模板:     %s
                        时间:     %s
                        """,
                sig.getTitle() == null ? "" : sig.getTitle(),
                sig.getContent() == null ? "" : sig.getContent(),
                sig.getSignalType(),
                sig.getTriggerPrice(),
                sig.getThreshold(),
                sig.getCurrentValue(),
                sig.getTemplate(),
                sig.getTriggeredAt());
    }
}
