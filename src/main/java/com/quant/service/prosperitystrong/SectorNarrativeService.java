package com.quant.service.prosperitystrong;

import com.quant.config.ProsperityStrongProperties;
import com.quant.entity.ProsperityHotSector;
import com.quant.service.ai.MiniMaxClient;
import com.quant.service.ai.SenseNovaClient;
import com.quant.service.search.WebSearchClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 板块叙事 AI 解读:
 *   - 主调 MiniMax,失败回退 SenseNova,最后回退到模板兜底。
 *   - 联网检索摘要由 WebSearchClient 提供(失败时静默忽略)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SectorNarrativeService {

    private static final String SYSTEM_PROMPT =
            "你是 A 股资深热点选股分析师。请仅使用东方财富/同花顺/公司公告等一手数据,严禁引用卖方研报。" +
                    "输出必须中立客观,不出现 '可能/大概' 等模糊表述,核心数据加粗。" +
                    "围绕板块给出: ①板块利好/政策映射, ②近 5 个交易日资金流向, ③主要业绩兑现公司方向。" +
                    "总字数 200-400 字,纯文本,不使用 markdown。";

    private final MiniMaxClient miniMax;
    private final SenseNovaClient senseNova;
    private final WebSearchClient webSearch;
    private final ProsperityStrongProperties props;

    public String generate(ProsperityHotSector sector) {
        if (!props.getAi().isEnabled()) {
            return fallback(sector);
        }
        String prompt = buildPrompt(sector);
        try {
            return miniMax.chatComplete(SYSTEM_PROMPT, prompt);
        } catch (Exception e) {
            log.warn("MiniMax 板块叙事失败 [{}]: {}", sector.getSectorName(), e.getMessage());
        }
        try {
            return senseNova.chatComplete(SYSTEM_PROMPT, prompt);
        } catch (Exception e) {
            log.warn("SenseNova 板块叙事失败 [{}]: {}", sector.getSectorName(), e.getMessage());
        }
        return fallback(sector);
    }

    private String buildPrompt(ProsperityHotSector s) {
        StringBuilder sb = new StringBuilder();
        sb.append("板块名称: ").append(s.getSectorName()).append('\n');
        if (s.getChange1d() != null) sb.append("当日涨幅: ").append(s.getChange1d()).append("%\n");
        if (s.getChange5d() != null) sb.append("近5日涨幅: ").append(s.getChange5d()).append("%\n");
        if (s.getChange20d() != null) sb.append("近20日涨幅: ").append(s.getChange20d()).append("%\n");
        if (s.getCapitalInflow5d() != null)
            sb.append("近5日主力净流入: ").append(s.getCapitalInflow5d()).append("元\n");
        if (s.getPersistenceDays() != null)
            sb.append("近10日红盘天数: ").append(s.getPersistenceDays()).append('\n');
        sb.append("综合评分: ").append(s.getScore()).append('\n');

        if (webSearch.isEnabled()) {
            try {
                List<WebSearchClient.SearchResult> r = webSearch.search(
                        s.getSectorName() + " 板块 利好 政策 业绩 近期");
                if (!r.isEmpty()) {
                    sb.append("\n联网检索摘要:\n");
                    for (WebSearchClient.SearchResult sr : r) {
                        sb.append(sr.toLine()).append('\n');
                    }
                }
            } catch (Exception ignored) {}
        }
        sb.append("\n请输出 200-400 字的板块叙事。");
        return sb.toString();
    }

    private String fallback(ProsperityHotSector s) {
        return String.format("【%s】板块综合评分 %s,当日涨幅 %s%%。AI 服务暂未开启或调用失败,请稍后通过手动触发刷新叙事。",
                s.getSectorName(),
                s.getScore() == null ? "--" : s.getScore().toPlainString(),
                s.getChange1d() == null ? "--" : s.getChange1d().toPlainString());
    }
}
