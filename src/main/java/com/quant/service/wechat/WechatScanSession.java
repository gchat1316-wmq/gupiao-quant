package com.quant.service.wechat;

import java.time.Instant;

import com.quant.service.AuthService.UserDto;

import lombok.Getter;
import lombok.Setter;

/** 公众号扫码登录会话：浏览器显示二维码 -> 用户扫码 -> 公众号事件推送绑定 -> 拉起登录。 周期短（默认 5 分钟），全部驻内存，重启即丢，符合预期。 */
@Getter
@Setter
public class WechatScanSession {

  public enum ScanStatus {
    /** 等待扫码 */
    SCANNING,
    /** 已扫码（在手机上扫了但未确认/未达到业务可信任节点） */
    SCANNED,
    /** 服务端已确认授权（公众号推送了事件，可继续生成 token） */
    CONFIRMED,
    /** 登录完成，前端可拿 token 跳回主页 */
    LOGGED_IN,
    /** 过期或被清理 */
    EXPIRED
  }

  private String sessionId;
  private ScanStatus status = ScanStatus.SCANNING;

  /** 二维码图片 URL，前端直接 <img src="..." /> */
  private String qrUrl;

  private int expireSeconds = 300;
  private Instant createdAt = Instant.now();

  /** 扫码用户的 openid（事件推送里 FromUserName） */
  private String openid;

  private String unionid;

  /** 生成结果 */
  private String accessToken;

  private UserDto userDto;

  public boolean isExpired() {
    return Instant.now().isAfter(createdAt.plusSeconds(expireSeconds));
  }
}
