package com.quant.dto.xieboinvest;

import java.util.List;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class XieboNewsDTO {
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
