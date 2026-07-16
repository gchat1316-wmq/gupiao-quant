package com.quant.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quant.dto.xiebo.UserSubscriptionDto;
import com.quant.dto.xiebo.UserSubscriptionUpsertRequest;
import com.quant.entity.UserStockSubscription;
import com.quant.repository.UserStockSubscriptionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class XieboRecentSubscriptionService {

  private final UserStockSubscriptionRepository repo;

  @Transactional
  public UserSubscriptionDto upsert(
      Long userId, String stockCode, UserSubscriptionUpsertRequest req) {
    if (userId == null) throw new IllegalArgumentException("userId 不能为空");
    if (stockCode == null || stockCode.isBlank())
      throw new IllegalArgumentException("stockCode 不能为空");
    validate(req);

    UserStockSubscription s =
        repo.findByUserIdAndStockCode(userId, stockCode)
            .orElseGet(
                () -> {
                  UserStockSubscription n = new UserStockSubscription();
                  n.setUserId(userId);
                  n.setStockCode(stockCode);
                  n.setStatus("关注");
                  return n;
                });

    s.setEnabled(Boolean.TRUE.equals(req.getEnabled()));
    if (req.getStatus() != null) {
      s.setStatus(req.getStatus());
      s.setStatusUpdatedAt(LocalDateTime.now());
    }
    s.setPriceBuy(req.getPriceBuy());
    s.setPriceStopLoss(req.getPriceStopLoss());
    s.setPriceAddPosition(req.getPriceAddPosition());
    s.setPriceReducePosition(req.getPriceReducePosition());
    s.setPriceClearPosition(req.getPriceClearPosition());
    s.setServerchanSendKey(emptyToNull(req.getServerchanSendKey()));

    UserStockSubscription saved = repo.save(s);
    return UserSubscriptionDto.of(saved);
  }

  @Transactional(readOnly = true)
  public List<UserSubscriptionDto> listByUser(Long userId) {
    return repo.findByUserId(userId).stream().map(UserSubscriptionDto::of).toList();
  }

  @Transactional
  public boolean resetAlerts(Long userId, String stockCode) {
    int n = repo.clearAllTriggeredAt(userId, stockCode);
    if (n == 0) {
      throw new IllegalArgumentException("订阅不存在或已重置: " + stockCode);
    }
    return true;
  }

  private void validate(UserSubscriptionUpsertRequest req) {
    if (req.getEnabled() == null) {
      throw new IllegalArgumentException("enabled 必填");
    }
    if (Boolean.TRUE.equals(req.getEnabled())
        && (req.getStatus() == null || req.getStatus().isBlank())) {
      throw new IllegalArgumentException("启用提醒时 status 必填");
    }
    rejectIfZero("priceBuy", req.getPriceBuy());
    rejectIfZero("priceStopLoss", req.getPriceStopLoss());
    rejectIfZero("priceAddPosition", req.getPriceAddPosition());
    rejectIfZero("priceReducePosition", req.getPriceReducePosition());
    rejectIfZero("priceClearPosition", req.getPriceClearPosition());
  }

  private void rejectIfZero(String name, BigDecimal v) {
    if (v != null && v.signum() <= 0) {
      throw new IllegalArgumentException(name + " 必须 > 0");
    }
  }

  private static String emptyToNull(String s) {
    return (s == null || s.isBlank()) ? null : s;
  }
}
