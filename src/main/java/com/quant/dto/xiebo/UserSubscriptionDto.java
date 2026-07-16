package com.quant.dto.xiebo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.quant.entity.UserStockSubscription;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class UserSubscriptionDto {
  private Long id;
  private String stockCode;
  private Boolean enabled;
  private String status;
  private BigDecimal priceBuy;
  private BigDecimal priceStopLoss;
  private BigDecimal priceAddPosition;
  private BigDecimal priceReducePosition;
  private BigDecimal priceClearPosition;
  private LocalDateTime alertBuyTriggeredAt;
  private LocalDateTime alertStopLossTriggeredAt;
  private LocalDateTime alertAddPositionTriggeredAt;
  private LocalDateTime alertReducePositionTriggeredAt;
  private LocalDateTime alertClearPositionTriggeredAt;
  private String serverchanSendKey;

  public static UserSubscriptionDto of(UserStockSubscription e) {
    if (e == null) return null;
    return UserSubscriptionDto.builder()
        .id(e.getId())
        .stockCode(e.getStockCode())
        .enabled(e.getEnabled())
        .status(e.getStatus())
        .priceBuy(e.getPriceBuy())
        .priceStopLoss(e.getPriceStopLoss())
        .priceAddPosition(e.getPriceAddPosition())
        .priceReducePosition(e.getPriceReducePosition())
        .priceClearPosition(e.getPriceClearPosition())
        .alertBuyTriggeredAt(e.getAlertBuyTriggeredAt())
        .alertStopLossTriggeredAt(e.getAlertStopLossTriggeredAt())
        .alertAddPositionTriggeredAt(e.getAlertAddPositionTriggeredAt())
        .alertReducePositionTriggeredAt(e.getAlertReducePositionTriggeredAt())
        .alertClearPositionTriggeredAt(e.getAlertClearPositionTriggeredAt())
        .serverchanSendKey(e.getServerchanSendKey())
        .build();
  }
}
