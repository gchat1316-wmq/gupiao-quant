package com.quant.service.aistockdata;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.quant.config.BaostockSyncProperties;
import com.quant.service.notification.NotificationService;

@ExtendWith(MockitoExtension.class)
@DisplayName("BaostockSyncCoordinator")
class BaostockSyncCoordinatorTest {

  @Mock BaostockSyncService syncService;

  @Mock NotificationService notificationService;

  private BaostockSyncCoordinator coordinator;

  @BeforeEach
  void setUp() {
    BaostockSyncProperties props = new BaostockSyncProperties();
    props.setEnabled(true);
    props.setStartupEnabled(true);
    props.setStartupDaysBack(45);
    props.setDailyDaysBack(7);
    coordinator = new BaostockSyncCoordinator(props, syncService, notificationService);
  }

  @Test
  @DisplayName("应用启动后触发补跑")
  void runsOnStartup() {
    coordinator.runOnStartup();

    verify(syncService).syncNow("startup", 45);
  }

  @Test
  @DisplayName("每日调度触发日常同步")
  void runsDailySchedule() {
    coordinator.runDaily();

    verify(syncService).syncNow("daily", 7);
  }

  @Test
  @DisplayName("同步失败时发送告警但不抛出")
  void sendsNotificationOnFailure() {
    doThrow(new IllegalStateException("baostock sync failed"))
        .when(syncService)
        .syncNow("daily", 7);

    coordinator.runDaily();

    verify(notificationService)
        .sendServerChan(
            org.mockito.ArgumentMatchers.contains("BaoStock同步失败"),
            org.mockito.ArgumentMatchers.contains("baostock sync failed"));
  }
}
