package com.quant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "prosperity-strong")
public class ProsperityStrongProperties {

  /** 是否启用整个流水线 */
  private boolean enabled = true;

  /** 定时 cron */
  private String cron = "0 30 15 * * MON-FRI";

  /** 每日 Top N 板块 */
  private int maxSectors = 5;

  /** 每个板块取 Top N 候选龙头 */
  private int leadersPerSector = 5;

  /** 候选清单最终保留上限 */
  private int maxCandidates = 15;

  /** 默认数据链路: local / wind / tdx / hybrid */
  private String provider = "local";

  /** AI 调用开关 */
  private Ai ai = new Ai();

  /** 板块抓取数据源开关 */
  private Source source = new Source();

  /** Wind AI 金融终端 Skill 链路 */
  private Wind wind = new Wind();

  /** 通达信 MCP 链路 */
  private Tdx tdx = new Tdx();

  @Data
  public static class Ai {
    /** AI 是否启用（关闭时只输出基础叙事） */
    private boolean enabled = true;

    /** 失败时是否回退到 mock */
    private boolean fallbackToMock = true;
  }

  @Data
  public static class Source {
    /** eastmoney / a_stock_data / local */
    private String sector = "eastmoney";

    /** 网络超时秒数 */
    private int timeoutSeconds = 15;
  }

  @Data
  public static class Wind {
    /** 全局安装后的 wind-mcp-skill 目录 */
    private String skillDir = System.getProperty("user.home") + "/.agents/skills/wind-mcp-skill";

    /** 全局 API key 配置文件,格式: WIND_API_KEY=... */
    private String configPath = System.getProperty("user.home") + "/.wind-aifinmarket/config";

    /** CLI 调用超时秒数 */
    private int timeoutSeconds = 15;
  }

  @Data
  public static class Tdx {
    /** 当前 Java 应用是否直接启用通达信 MCP 调用 */
    private boolean enabled = false;

    /** WorkBuddy 通达信 connector 安装目录,用于能力探测 */
    private String connectorDir =
        System.getProperty("user.home")
            + "/.workbuddy/connectors-marketplace/connectors/tdx-connector";

    /** 通达信 MCP 服务地址 (API Key 直调模式用 mcp.tdx.com.cn, OAuth 模式用 txmcp.tdx.com.cn) */
    private String mcpUrl = "https://mcp.tdx.com.cn:3001/mcp";

    /** 通达信 OAuth authorization endpoint (从 RFC 8414 metadata 拉取) */
    private String authorizationEndpoint =
        "https://auth.tdx.com.cn/tdx-oauth/page_workbuddy_oauth.html";

    /** 通达信 OAuth token endpoint */
    private String tokenEndpoint = "https://auth.tdx.com.cn/token";

    /** 通达信 OAuth 动态注册 endpoint */
    private String registrationEndpoint = "https://auth.tdx.com.cn/register";

    /** 本地回调地址 (需在 TDX 注册时声明,例如 http://localhost:8080/gp/api/tdx/auth/callback) */
    private String redirectUri = "http://localhost:8080/gp/api/tdx/auth/callback";

    /** 客户端名 (动态注册时使用) */
    private String clientName = "gupiao-quant-local";

    /** token 缓存路径,文件内放 access_token / refresh_token / expires_at / client_id / code_verifier 临时态 */
    private String tokenCachePath =
        System.getProperty("user.home")
            + "/.workbuddy/connectors-marketplace/connectors/tdx-connector/token.json";

    /** 通达信 API Key (TDX-c62ebd01... 这种, 通过 tdx-api-key header 鉴权, 不走 OAuth) */
    private String apiKey = "";

    /** 调 MCP 工具超时秒数 */
    private int timeoutSeconds = 20;
  }
}
