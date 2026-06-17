package com.quant.dto.lynchinvest;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class LynchNewsDTO {
    private String collectedAt;
    private List<NewsItemDTO> stockNews;
    private List<NewsItemDTO> announcements;
    private List<NewsItemDTO> marketNews;

    @Data
    @Builder
    public static class NewsItemDTO {
        private String category;
        private String ticker;
        private String title;
        private String content;
        private String time;
        private String source;
        private String url;
    }
}
