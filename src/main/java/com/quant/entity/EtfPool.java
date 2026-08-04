package com.quant.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** 省心 ETF 池条目。档位 done 标志随卖出录单自动流转，清仓后重置。 */
@Getter
@Setter
@Entity
@Table(name = "etf_pool")
public class EtfPool {

  public static final String CATEGORY_BROAD = "BROAD";
  public static final String CATEGORY_SECTOR = "SECTOR";
  public static final String STATUS_ACTIVE = "ACTIVE";
  public static final String STATUS_REMOVED = "REMOVED";
  public static final String RECOUP_NONE = "NONE";
  public static final String RECOUP_WAITING = "WAITING";
  public static final String RECOUP_READY = "READY";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "stock_code", nullable = false, length = 20)
  private String stockCode;

  @Column(name = "stock_name", length = 50)
  private String stockName;

  /** BROAD(宽基) | SECTOR(行业/主题) — 决定止损档位 */
  @Column(name = "category", nullable = false, length = 10)
  private String category = CATEGORY_SECTOR;

  @Column(name = "status", nullable = false, length = 20)
  private String status = STATUS_ACTIVE;

  /** +5% 已减 1/3 */
  @Column(name = "tp1_done")
  private Integer tp1Done = 0;

  /** +10% 已再减 1/3 */
  @Column(name = "tp2_done")
  private Integer tp2Done = 0;

  /** 宽基-15%/行业-10% 已减半 */
  @Column(name = "sl1_done")
  private Integer sl1Done = 0;

  /** 宽基-30% 已再减半 / 行业-18% 已清仓 */
  @Column(name = "sl2_done")
  private Integer sl2Done = 0;

  /** NONE | WAITING(止损减仓后待回补) | READY(可回补) */
  @Column(name = "recoup_status", nullable = false, length = 20)
  private String recoupStatus = RECOUP_NONE;

  /** 周K收盘站上5日线的连续周数（≥2 → READY） */
  @Column(name = "recoup_weeks")
  private Integer recoupWeeks = 0;

  @Column(name = "memo", length = 500)
  private String memo;

  @Column(name = "created_at", insertable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private LocalDateTime updatedAt;

  public boolean isBroad() {
    return CATEGORY_BROAD.equals(category);
  }
}
