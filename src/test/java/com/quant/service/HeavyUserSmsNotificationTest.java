package com.quant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.quant.entity.User;
import com.quant.entity.UserNotificationLog;
import com.quant.repository.UserNotificationLogRepository;
import com.quant.repository.UserRepository;

/**
 * 重度用户（notifySms=true, 监控股价波动）通知 fanout 测试。
 *
 * <p>覆盖 F5: NotificationDispatcher.dispatchPriceAlert - SMS 通知按用户偏好 fanout 到 SmsService - 微信通知
 * fanout 到 ServerChan - disabled / 空目标用户不会收到 - 重度用户发了 fanout 后落入 user_notification_log
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("重度用户 SMS / 微信通知 fanout (F5)")
class HeavyUserSmsNotificationTest {

  @Mock private UserRepository userRepository;
  @Mock private UserNotificationLogRepository logRepository;
  @Mock private SmsService smsService;
  @Mock private NotificationService notificationService;

  private NotificationDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    dispatcher =
        new NotificationDispatcher(userRepository, logRepository, smsService, notificationService);
  }

  private User heavyUser(long id, String phone, boolean disabled) {
    User u = new User();
    u.setId(id);
    u.setPhone(phone);
    u.setUsername("user" + id);
    u.setRole(User.Role.USER);
    u.setDisabled(disabled);
    u.setNotifySms(true);
    u.setNotifyWechat(false);
    return u;
  }

  private User wechatUser(long id) {
    User u = new User();
    u.setId(id);
    u.setUsername("wc" + id);
    u.setRole(User.Role.USER);
    u.setDisabled(false);
    u.setNotifySms(false);
    u.setNotifyWechat(true);
    return u;
  }

  @Test
  @DisplayName("重度 USER 数量 2: 应该每人都发一次 SMS")
  void heavyUsersGetSmsPerUser() {
    when(userRepository.findActiveSmsTargets())
        .thenReturn(
            List.of(heavyUser(1L, "13800000001", false), heavyUser(2L, "13800000002", false)));
    when(userRepository.findActiveByWechat(true)).thenReturn(List.of());
    when(smsService.sendAlarm(anyString(), anyString())).thenReturn(true);

    var result = dispatcher.dispatchPriceAlert("600519.SH", "PRICE_BUY_ALERT", "买入", "现价 1234.56");

    assertThat(result.attempted()).isEqualTo(2);
    assertThat(result.succeeded()).isEqualTo(2);
    verify(smsService, times(2)).sendAlarm(anyString(), anyString());
    verify(notificationService, never()).sendServerChan(anyString(), anyString());
  }

  @Test
  @DisplayName("重度 USER phone 为空不会落 SMS 任务(被 repository 过滤掉了)")
  void heavyUserWithoutPhoneIsFilteredByRepo() {
    when(userRepository.findActiveSmsTargets())
        .thenReturn(List.of(heavyUser(3L, "13800000003", false)));
    when(userRepository.findActiveByWechat(true)).thenReturn(List.of());
    when(smsService.sendAlarm(eq("13800000003"), anyString())).thenReturn(true);

    dispatcher.dispatchPriceAlert("002371", "PRICE_SELL_ALERT", "卖出", "现价 234.56");

    verify(smsService, times(1)).sendAlarm(eq("13800000003"), anyString());
  }

  @Test
  @DisplayName("微信开启的用户收到 Server Chan 推送")
  void wechatUsersGetServerChan() {
    when(userRepository.findActiveSmsTargets()).thenReturn(List.of());
    when(userRepository.findActiveByWechat(true))
        .thenReturn(List.of(wechatUser(10L), wechatUser(11L)));
    when(notificationService.sendServerChan(anyString(), anyString())).thenReturn(true);

    var result = dispatcher.dispatchPriceAlert("002371", "PRICE_BUY_ALERT", "买入", "现价");

    assertThat(result.attempted()).isEqualTo(1); // WECHAT 共用一条代表消息
    assertThat(result.succeeded()).isEqualTo(1);
    verify(notificationService, times(1)).sendServerChan(anyString(), anyString());
  }

  @Test
  @DisplayName("没有 fanout 目标时 attempted=0, 不发空消息")
  void noTargetsNoFanout() {
    when(userRepository.findActiveSmsTargets()).thenReturn(List.of());
    when(userRepository.findActiveByWechat(true)).thenReturn(List.of());

    var result = dispatcher.dispatchPriceAlert("002371", "PRICE_BUY_ALERT", "买入", "现价");

    assertThat(result.attempted()).isZero();
    verify(smsService, never()).sendAlarm(anyString(), anyString());
    verify(notificationService, never()).sendServerChan(anyString(), anyString());
  }

  @Test
  @DisplayName("SMS 发送失败时也会落 FAILED 状态日志, 不会破坏 fanout 流程")
  void smsFailurePersistsFailedLog() {
    when(userRepository.findActiveSmsTargets())
        .thenReturn(List.of(heavyUser(20L, "13800000020", false)));
    when(userRepository.findActiveByWechat(true)).thenReturn(List.of());
    when(smsService.sendAlarm(anyString(), anyString())).thenReturn(false);

    var result = dispatcher.dispatchPriceAlert("002371", "PRICE_BUY_ALERT", "买入", "现价");

    assertThat(result.attempted()).isEqualTo(1);
    assertThat(result.succeeded()).isZero();

    ArgumentCaptor<UserNotificationLog> captor = ArgumentCaptor.forClass(UserNotificationLog.class);
    verify(logRepository, times(1)).save(captor.capture());
    UserNotificationLog row = captor.getValue();
    assertThat(row.getUserId()).isEqualTo(20L);
    assertThat(row.getChannel()).isEqualTo(NotificationDispatcher.CHANNEL_SMS);
    assertThat(row.getStatus()).isEqualTo(NotificationDispatcher.STATUS_FAILED);
    assertThat(row.getStockCode()).isEqualTo("002371");
    assertThat(row.getTitle()).isEqualTo("买入");
  }

  @Test
  @DisplayName("WECHAT 成功发出去时 SUCCESS 日志落到第一个目标用户上")
  void wechatSuccessWritesLogToFirstTarget() {
    when(userRepository.findActiveSmsTargets()).thenReturn(List.of());
    when(userRepository.findActiveByWechat(true))
        .thenReturn(List.of(wechatUser(30L), wechatUser(31L)));
    when(notificationService.sendServerChan(anyString(), anyString())).thenReturn(true);

    dispatcher.dispatchPriceAlert("002371", "PRICE_SELL_ALERT", "卖出", "现价 200");

    ArgumentCaptor<UserNotificationLog> captor = ArgumentCaptor.forClass(UserNotificationLog.class);
    verify(logRepository, times(1)).save(captor.capture());
    UserNotificationLog row = captor.getValue();
    assertThat(row.getChannel()).isEqualTo(NotificationDispatcher.CHANNEL_WECHAT);
    assertThat(row.getStatus()).isEqualTo(NotificationDispatcher.STATUS_SUCCESS);
    assertThat(row.getUserId()).isEqualTo(30L); // 第一个目标
  }

  @Test
  @DisplayName("重度 USER 同时开了微信+短信, 走两条通道")
  void heavyUserMultiChannel() {
    User dual = new User();
    dual.setId(40L);
    dual.setPhone("13800000040");
    dual.setRole(User.Role.USER);
    dual.setDisabled(false);
    dual.setNotifySms(true);
    dual.setNotifyWechat(true);

    when(userRepository.findActiveSmsTargets()).thenReturn(List.of(dual));
    when(userRepository.findActiveByWechat(true)).thenReturn(List.of(dual));
    when(smsService.sendAlarm(anyString(), anyString())).thenReturn(true);
    when(notificationService.sendServerChan(anyString(), anyString())).thenReturn(true);

    var result = dispatcher.dispatchPriceAlert("002371", "PRICE_BUY_ALERT", "买入", "现价");

    assertThat(result.attempted()).isEqualTo(2); // SMS + WECHAT
    assertThat(result.succeeded()).isEqualTo(2);
    verify(smsService, times(1)).sendAlarm(anyString(), anyString());
    verify(notificationService, times(1)).sendServerChan(anyString(), anyString());
  }
}
