package com.quant.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.quant.dto.xiebo.UserSubscriptionDto;
import com.quant.dto.xiebo.UserSubscriptionUpsertRequest;
import com.quant.entity.UserStockSubscription;
import com.quant.repository.UserStockSubscriptionRepository;

@ExtendWith(MockitoExtension.class)
class XieboRecentSubscriptionServiceTest {

  @Mock UserStockSubscriptionRepository repo;
  @InjectMocks XieboRecentSubscriptionService service;

  private UserSubscriptionUpsertRequest req(Boolean enabled, BigDecimal priceBuy) {
    UserSubscriptionUpsertRequest r = new UserSubscriptionUpsertRequest();
    r.setEnabled(enabled);
    r.setStatus("关注");
    r.setPriceBuy(priceBuy);
    return r;
  }

  @Test
  void upsert_newSubscription_createsRow() {
    when(repo.findByUserIdAndStockCode(7L, "600519")).thenReturn(Optional.empty());
    when(repo.save(any(UserStockSubscription.class)))
        .thenAnswer(
            inv -> {
              UserStockSubscription s = inv.getArgument(0);
              s.setId(100L);
              return s;
            });

    UserSubscriptionDto out = service.upsert(7L, "600519", req(true, new BigDecimal("1850")));

    ArgumentCaptor<UserStockSubscription> captor =
        ArgumentCaptor.forClass(UserStockSubscription.class);
    verify(repo).save(captor.capture());
    assertThat(captor.getValue().getUserId()).isEqualTo(7L);
    assertThat(captor.getValue().getStockCode()).isEqualTo("600519");
    assertThat(captor.getValue().getEnabled()).isTrue();
    assertThat(captor.getValue().getStatus()).isEqualTo("关注");
    assertThat(captor.getValue().getPriceBuy()).isEqualByComparingTo("1850");
    assertThat(out.getId()).isEqualTo(100L);
  }

  @Test
  void upsert_existingSubscription_updatesFields() {
    UserStockSubscription existing = new UserStockSubscription();
    existing.setId(50L);
    existing.setUserId(7L);
    existing.setStockCode("600519");
    existing.setEnabled(false);
    existing.setStatus("关注");
    when(repo.findByUserIdAndStockCode(7L, "600519")).thenReturn(Optional.of(existing));
    when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    UserSubscriptionDto out = service.upsert(7L, "600519", req(true, new BigDecimal("1820")));

    assertThat(existing.getEnabled()).isTrue();
    assertThat(existing.getPriceBuy()).isEqualByComparingTo("1820");
    assertThat(out.getId()).isEqualTo(50L);
  }

  @Test
  void upsert_zeroPrice_throwsIllegalArgument() {
    assertThatThrownBy(() -> service.upsert(7L, "600519", req(true, BigDecimal.ZERO)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("priceBuy");
  }

  @Test
  void upsert_enabledTrue_missingStatus_throws() {
    UserSubscriptionUpsertRequest r = new UserSubscriptionUpsertRequest();
    r.setEnabled(true);
    r.setStatus(null);
    assertThatThrownBy(() -> service.upsert(7L, "600519", r))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("status");
  }

  @Test
  void listByUser_returnsMapped() {
    UserStockSubscription s = new UserStockSubscription();
    s.setId(1L);
    s.setUserId(7L);
    s.setStockCode("600519");
    s.setEnabled(true);
    s.setStatus("建仓");
    when(repo.findByUserId(7L)).thenReturn(List.of(s));

    List<UserSubscriptionDto> out = service.listByUser(7L);

    assertThat(out).hasSize(1);
    assertThat(out.get(0).getStatus()).isEqualTo("建仓");
  }

  @Test
  void resetAlerts_callsRepoAndReturnsTrue() {
    when(repo.clearAllTriggeredAt(7L, "600519")).thenReturn(1);
    boolean ok = service.resetAlerts(7L, "600519");
    assertThat(ok).isTrue();
    verify(repo).clearAllTriggeredAt(7L, "600519");
  }

  @Test
  void resetAlerts_zeroAffected_throws() {
    when(repo.clearAllTriggeredAt(7L, "600519")).thenReturn(0);
    assertThatThrownBy(() -> service.resetAlerts(7L, "600519"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("订阅不存在");
  }
}
