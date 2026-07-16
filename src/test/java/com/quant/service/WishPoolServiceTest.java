package com.quant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.quant.config.NotificationProperties;
import com.quant.dto.wishpool.WishSubmitRequest;
import com.quant.entity.WishPool;
import com.quant.repository.WishPoolRepository;
import com.quant.service.ai.WishPoolNotifier;
import com.quant.service.ai.WishPoolService;

/** WishPoolService 单测：验证入库 + 触发异步通知。 飞书 webhook 调用已迁移到 {@link WishPoolNotifier} 内部，失败仅日志。 */
@DisplayName("WishPoolService")
class WishPoolServiceTest {

  private NotificationProperties properties;
  private WishPoolRepository repository;
  private WishPoolNotifier notifier;
  private WishPoolService service;

  @BeforeEach
  void setUp() {
    properties = new NotificationProperties();
    properties.getWishPool().setEnabled(true);
    properties
        .getWishPool()
        .setWebhookUrl("https://open.feishu.cn/open-apis/bot/v2/hook/test-hook");
    repository = mock(WishPoolRepository.class);
    notifier = mock(WishPoolNotifier.class);
    // save() 回传原 entity
    when(repository.save(any(WishPool.class))).thenAnswer(inv -> inv.getArgument(0));

    service = new WishPoolService(repository, notifier, properties);
  }

  @Test
  @DisplayName("提交许愿：入库并触发异步飞书通知（不抛异常）")
  void submitWishPersistsAndDispatchesAsync() {
    WishSubmitRequest request = new WishSubmitRequest();
    request.setWish("希望增加复盘摘要导出，帮我每天整理晨会材料。");
    request.setPage("/gp/market-recap.html");
    request.setEmail("user@example.com");

    WishPool saved = service.submitWish(request, "203.0.113.7");

    assertThat(saved.getId()).isNull(); // mock save 不自增
    assertThat(saved.getWish()).isEqualTo(request.getWish());
    assertThat(saved.getPage()).isEqualTo("/gp/market-recap.html");
    assertThat(saved.getEmail()).isEqualTo("user@example.com");
    assertThat(saved.getIp()).isEqualTo("203.0.113.7");
    assertThat(saved.getStatus()).isEqualTo(WishPool.Status.PENDING);
    assertThat(saved.getDisplayFlag()).isFalse();

    ArgumentCaptor<WishPool> captor = ArgumentCaptor.forClass(WishPool.class);
    verify(repository).save(captor.capture());
    verify(notifier).notifyNewWish(captor.getValue());

    WishPool persisted = captor.getValue();
    assertThat(persisted.getWish()).isEqualTo(request.getWish());
    assertThat(persisted.getStatus()).isEqualTo(WishPool.Status.PENDING);
  }

  @Test
  @DisplayName("未填写邮箱时持久化的 email 字段为空字符串")
  void blankEmailPersistedAsEmpty() {
    WishSubmitRequest request = new WishSubmitRequest();
    request.setWish("希望增加每日复盘导出功能。");
    request.setPage("/gp/index.html");

    WishPool saved = service.submitWish(request, null);

    assertThat(saved.getEmail()).isEmpty();
    assertThat(saved.getIp()).isNull();
    verify(notifier).notifyNewWish(any(WishPool.class));
  }

  @Test
  @DisplayName("空许愿内容直接拒绝")
  void rejectsBlankWish() {
    WishSubmitRequest request = new WishSubmitRequest();
    request.setWish("   ");

    assertThatThrownBy(() -> service.submitWish(request, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("请输入");
  }

  @Test
  @DisplayName("来源页面为空时入库落 '未知页面'")
  void unknownPageFallback() {
    WishSubmitRequest request = new WishSubmitRequest();
    request.setWish("希望增加导出能力");
    request.setPage(null);

    WishPool saved = service.submitWish(request, null);

    assertThat(saved.getPage()).isEqualTo("未知页面");
  }
}
