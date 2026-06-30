package com.quant.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 用户通知发送记录。
 * 用于：(1) 重度用户查询"我收到了哪些推送"；
 *      (2) 按用户维度实现去重/限流，避免轰炸；
 *      (3) 通知发送失败后便于排查。
 */
@Getter
@Setter
@Entity
@Table(name = "user_notification_log", indexes = {
        @Index(name = "idx_unl_user_time", columnList = "user_id, sent_at"),
        @Index(name = "idx_unl_stock",     columnList = "stock_code, sent_at")
})
public class UserNotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 通知渠道：SMS / WECHAT / PHONE */
    @Column(nullable = false, length = 16)
    private String channel;

    /** 关联股票代码，可空（系统通知） */
    @Column(name = "stock_code", length = 16)
    private String stockCode;

    /** 通知类型：PRICE_BUY_ALERT / PRICE_SELL_ALERT / SYSTEM ... */
    @Column(nullable = false, length = 32)
    private String type;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    /** SUCCESS / FAILED */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(length = 500)
    private String error;

    @Column(name = "sent_at", insertable = false, updatable = false)
    private LocalDateTime sentAt;
}
