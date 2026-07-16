package com.quant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

@Data
@ConfigurationProperties(prefix = "ai")
public class AiProperties {
  private boolean fallbackToMock = true;
  private MiniMax minimax = new MiniMax();
  private SenseNova sensenova = new SenseNova();
  private Tavily tavily = new Tavily();

  @Data
  public static class Tavily {
    private boolean enabled = false;
    private String apiKey = "";
    private String baseUrl = "https://api.tavily.com";
    private int timeoutSeconds = 15;
    private int maxResults = 5;
  }

  @Data
  public static class MiniMax {
    private boolean enabled = false;
    private String baseUrl = "https://api.minimaxi.com/anthropic/v1";
    private String apiKey = "";
    private String groupId = "";
    private String model = "MiniMax-M2.7-highspeed";
    private String visionModel = "";
    private int timeoutSeconds = 90;
    private int maxInputChars = 8000;
  }

  @Data
  public static class SenseNova {
    private boolean enabled = false;
    private String baseUrl = "https://token.sensenova.cn/v1";
    private String apiKey = "";
    private String imageModel = "sensenova-u1-fast";
    private String chatModel = "deepseek-v4-flash";
    private String imageSize = "2752x1536";
    private int timeoutSeconds = 120;
  }
}
