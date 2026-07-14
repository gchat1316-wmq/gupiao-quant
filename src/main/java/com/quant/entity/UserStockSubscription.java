package com.quant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(
    name = "user_stock_subscription",
    uniqueConstraints = @UniqueConstraint(name = "uk_user_stock", columnNames = {"user_id", "stock_code"}),
    indexes = {
        @Index(name = "idx_user", columnList = "user_id"),
        @Index(name = "idx_stock", columnList = "stock_code"),
        @Index(name = "idx_enabled", columnList = "enabled, stock_code")
    }
)
public class UserStockSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "stock_code", nullable = false, length = 16)
    private String stockCode;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = false;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "关注";

    @Column(name = "status_updated_at")
    private LocalDateTime statusUpdatedAt;

    @Column(name = "price_buy", precision = 10, scale = 2)
    private BigDecimal priceBuy;

    @Column(name = "price_stop_loss", precision = 10, scale = 2)
    private BigDecimal priceStopLoss;

    @Column(name = "price_add_position", precision = 10, scale = 2)
    private BigDecimal priceAddPosition;

    @Column(name = "price_reduce_position", precision = 10, scale = 2)
    private BigDecimal priceReducePosition;

    @Column(name = "price_clear_position", precision = 10, scale = 2)
    private BigDecimal priceClearPosition;

    @Column(name = "alert_buy_triggered_at")
    private LocalDateTime alertBuyTriggeredAt;

    @Column(name = "alert_stop_loss_triggered_at")
    private LocalDateTime alertStopLossTriggeredAt;

    @Column(name = "alert_add_position_triggered_at")
    private LocalDateTime alertAddPositionTriggeredAt;

    @Column(name = "alert_reduce_position_triggered_at")
    private LocalDateTime alertReducePositionTriggeredAt;

    @Column(name = "alert_clear_position_triggered_at")
    private LocalDateTime alertClearPositionTriggeredAt;

    @Column(name = "serverchan_send_key", length = 64)
    private String serverchanSendKey;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 0;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
