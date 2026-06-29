package com.quant.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户每日活跃聚合数据。
 * 每日凌晨由定时任务或首次访问时更新，也可实时增量更新。
 */
@Getter
@Setter
@Entity
@Table(name = "user_daily_stat",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_uds_user_date", columnNames = {"user_id", "stat_date"})
        },
        indexes = {
                @Index(name = "idx_uds_date", columnList = "stat_date"),
                @Index(name = "idx_uds_user", columnList = "user_id")
        })
public class UserDailyStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户ID，null 表示游客/未登录用户 */
    private Long userId;

    /** 统计日期 */
    private LocalDate statDate;

    /** 当日页面访问次数（PV） */
    private Integer pageViewCount = 0;

    /** 当日访问页面种类数（UV） */
    private Integer uniquePages = 0;

    /** 当日累计停留时长（秒） */
    private Integer totalDurationSeconds = 0;

    /** 当日首次访问时间 */
    private LocalDateTime firstVisitTime;

    /** 当日最近访问时间 */
    private LocalDateTime lastVisitTime;

    /** 登录次数（登录事件触发） */
    private Integer loginCount = 0;

    /** 是否当日注册 */
    private Boolean isNewUser = false;

    public UserDailyStat() {
    }

    public UserDailyStat(Long userId, LocalDate date) {
        this.userId = userId;
        this.statDate = date;
        this.firstVisitTime = LocalDateTime.now();
        this.lastVisitTime = LocalDateTime.now();
    }

    public void addPageView(int durationSeconds, String pagePath) {
        this.pageViewCount = (this.pageViewCount == null ? 0 : this.pageViewCount) + 1;
        this.totalDurationSeconds = (this.totalDurationSeconds == null ? 0 : this.totalDurationSeconds) + durationSeconds;
        this.lastVisitTime = LocalDateTime.now();
    }

    public void incrementLogin() {
        this.loginCount = (this.loginCount == null ? 0 : this.loginCount) + 1;
    }
}
