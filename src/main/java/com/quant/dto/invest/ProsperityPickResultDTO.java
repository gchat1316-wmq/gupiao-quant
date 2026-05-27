package com.quant.dto.invest;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
public class ProsperityPickResultDTO {

    private Long id;
    private String stockCode;
    private String stockName;
    private LocalDate analysisDate;

    /** 公司速览卡的基础数据 */
    private Profile profile;

    /** AI 输出的结构化六维结果（保留为 JsonNode，前端按 schema 渲染） */
    private JsonNode analysis;

    /** 已生成的信息图 URL（懒生成） */
    private String imageUrl;

    /** 是否为退化（mock）数据 */
    private boolean degraded;

    /** 是否命中缓存 */
    private boolean cached;

    @Data
    @Builder
    public static class Profile {
        private String stockCode;
        private String stockName;
        private String exchange;
        private String board;
        private String industry;
        private String chairman;
        private String mainBusiness;
        private BigDecimal currentPrice;
        private BigDecimal totalMarketCap;
        private BigDecimal peTtm;
        private BigDecimal pb;
        private BigDecimal psTtm;
        private String latestRevenue;
        private String latestNetProfit;
        private String latestReportDate;
    }
}
