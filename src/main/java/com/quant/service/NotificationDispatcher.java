package com.quant.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quant.entity.User;
import com.quant.entity.UserNotificationLog;
import com.quant.repository.UserNotificationLogRepository;
import com.quant.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 通知分发器：把同一事件按用户偏好 fanout 到不同渠道， 同时落 user_notification_log 便于重发与查询。
 *
 * <p>- SMS: 调用 SmsService.sendAlarm（华信通道） - WECHAT: 调用 NotificationService.sendServerChan（Server 酱）
 * （当前 Server 酱只支持一个全局 sendKey，WECHAT 渠道目前共用同一通道， 若后续要按用户 openid 走公众号模板消息再扩展）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDispatcher {

  public static final String CHANNEL_SMS = "SMS";
  public static final String CHANNEL_WECHAT = "WECHAT";
  public static final String CHANNEL_PHONE = "PHONE";

  public static final String STATUS_SUCCESS = "SUCCESS";
  public static final String STATUS_FAILED = "FAILED";
  public static final String STATUS_DISABLED = "DISABLED";

  private final UserRepository userRepository;
  private final UserNotificationLogRepository logRepository;
  private final SmsService smsService;
  private final NotificationService notificationService;

  /**
   * 推送价格告警（按用户偏好 fanout 到 SMS / WECHAT）。
   *
   * @param stockCode 触发的股票代码（用于日志聚合与去重）
   * @param type PRICE_BUY_ALERT / PRICE_SELL_ALERT
   * @param title 标题
   * @param content 正文（短信侧会完整下发；微信侧会作为 desp）
   * @return 汇总：(尝试数, 成功数)
   */
  @Transactional
  public DispatchResult dispatchPriceAlert(
      String stockCode, String type, String title, String content) {
    int attempted = 0;
    int succeeded = 0;

    // 1. SMS 群发
    List<User> smsTargets = userRepository.findActiveSmsTargets();
    for (User u : smsTargets) {
      attempted++;
      boolean ok = smsService.sendAlarm(u.getPhone(), content);
      persistLog(
          u.getId(),
          CHANNEL_SMS,
          stockCode,
          type,
          title,
          content,
          ok ? STATUS_SUCCESS : STATUS_FAILED,
          ok ? null : "sms send failed");
      if (ok) succeeded++;
    }

    // 2. WECHAT 群发（共用 Server 酱通道，写一条代表全站推送的日志）
    List<User> wechatTargets = userRepository.findActiveByWechat(true);
    if (!wechatTargets.isEmpty()) {
      attempted++;
      boolean ok = notificationService.sendServerChan(title, content);
      // 代表发送：落到第一个目标 user 上方便追踪
      persistLog(
          wechatTargets.get(0).getId(),
          CHANNEL_WECHAT,
          stockCode,
          type,
          title,
          content,
          ok ? STATUS_SUCCESS : STATUS_FAILED,
          ok ? null : "serverchan failed");
      if (ok) succeeded++;
    }

    log.info(
        "通知 fanout: type={} stock={} attempted={} succeeded={} (smsTargets={} wechatTargets={})",
        type,
        stockCode,
        attempted,
        succeeded,
        smsTargets.size(),
        wechatTargets.size());

    return new DispatchResult(attempted, succeeded);
  }

  private void persistLog(
      Long userId,
      String channel,
      String stockCode,
      String type,
      String title,
      String content,
      String status,
      String error) {
    try {
      UserNotificationLog logRow = new UserNotificationLog();
      logRow.setUserId(userId);
      logRow.setChannel(channel);
      logRow.setStockCode(stockCode);
      logRow.setType(type);
      logRow.setTitle(title);
      logRow.setContent(content);
      logRow.setStatus(status);
      logRow.setError(error);
      logRepository.save(logRow);
    } catch (Exception e) {
      NotificationDispatcher.log.warn("写入 user_notification_log 失败: {}", e.getMessage());
    }
  }

  /** 拉取某用户最近 N 天的通知（前端 /api/auth/me/notifications 用） */
  @Transactional(readOnly = true)
  public List<UserNotificationLog> recentForUser(Long userId, int days) {
    return logRepository.findByUserIdAndSentAtAfterOrderBySentAtDesc(
        userId,
        java.time.LocalDateTime.now().minusDays(days),
        org.springframework.data.domain.PageRequest.of(0, 50));
  }

  public record DispatchResult(int attempted, int succeeded) {}
}
