package com.quant.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** 页面访问明细记录。 每次访问页面时前端上报一条记录，后端以此计算各维度的日统计数据。 */
@Getter
@Setter
@Entity
@Table(
    name = "page_view_stat",
    indexes = {
      @Index(name = "idx_pvs_user_date", columnList = "user_id, visit_date"),
      @Index(name = "idx_pvs_date", columnList = "visit_date"),
      @Index(name = "idx_pvs_page", columnList = "page_path")
    })
public class PageViewStat {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 用户ID，null 表示游客/未登录用户 */
  private Long userId;

  /** 页面路径，如 /gp/index.html */
  private String pagePath;

  /** 访问时间 */
  private LocalDateTime visitTime;

  /** 访问日期（冗余字段，方便按天查询） */
  private LocalDate visitDate;

  /** 本次访问在该页面的停留时长（秒），由前端下次访问时回填 */
  private Integer durationSeconds;

  /** 客户端 UA 摘要（仅记录浏览器信息，便于区分） */
  private String userAgent;

  /** 会话ID，同一浏览器窗口内共享 */
  private String sessionId;

  public PageViewStat() {}

  public PageViewStat(Long userId, String pagePath, String sessionId, String userAgent) {
    this.userId = userId;
    this.pagePath = pagePath;
    this.visitTime = LocalDateTime.now();
    this.visitDate = LocalDate.now();
    this.sessionId = sessionId;
    this.userAgent = userAgent;
  }
}
