package com.quant.service.prosperitystrong;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Step 4: 主线判定与评分
 *
 *   主营占比得分: 主营占比 >= 50% 得 100, < 30% 直接 0, 中间线性
 *   净利率接近 25% 得分: score = 100 - |netMargin - 25| * 2
 *   成长稳定性占比: 此 MVP 阶段以财务评分代理
 */
@Component
@RequiredArgsConstructor
public class MainlineEvaluator {

    /**
     * @param mainBizRatio   主营占比(0-100),无数据时按 60 假设(适度宽松)
     * @param netMarginAvg4q 近4季净利率均值(单位 %)
     * @param financeScore   财务评分作为成长稳定性代理
     * @return 主线评分 0-100
     */
    public Score evaluate(BigDecimal mainBizRatio, BigDecimal netMarginAvg4q, BigDecimal financeScore) {
        double mb = mainBizRatio == null ? 60 : mainBizRatio.doubleValue();
        double mbScore;
        if (mb >= 50) {
            mbScore = 100;
        } else if (mb <= 30) {
            mbScore = 0;
        } else {
            mbScore = (mb - 30) / 20.0 * 100;
        }

        double nm = netMarginAvg4q == null ? 15 : netMarginAvg4q.doubleValue();
        double nmScore = Math.max(0, 100 - Math.abs(nm - 25) * 2);

        double stability = financeScore == null ? 50 : financeScore.doubleValue();

        double total = 0.4 * mbScore + 0.4 * nmScore + 0.2 * stability;
        total = Math.max(0, Math.min(100, total));

        return new Score(
                BigDecimal.valueOf(total).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(mb).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(nm).setScale(2, RoundingMode.HALF_UP),
                isMainline(mb)
        );
    }

    private boolean isMainline(double mainBiz) {
        return mainBiz >= 50;
    }

    public record Score(BigDecimal mainlineScore,
                        BigDecimal mainBizRatio,
                        BigDecimal netMarginAvg,
                        boolean mainlinePassed) {}
}
