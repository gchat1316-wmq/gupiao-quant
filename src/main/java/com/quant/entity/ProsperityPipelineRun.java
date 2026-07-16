package com.quant.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** 热点选股流水线执行记录。 每次流水线运行生成一条记录，标注日期、结果摘要和状态。 用于前端"历史执行"展示和删除。 */
@Getter
@Setter
@Entity
@Table(name = "prosperity_pipeline_run")
public class ProsperityPipelineRun {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  /** 快照日期，即本次流水线跑的日期 */
  @Column(name = "snap_date", nullable = false)
  private LocalDate snapDate;

  /** 执行开始时间 */
  @Column(name = "started_at", nullable = false)
  private LocalDateTime startedAt;

  /** 执行结束时间 */
  @Column(name = "finished_at")
  private LocalDateTime finishedAt;

  /** 耗时 ms */
  @Column(name = "duration_ms")
  private Long durationMs;

  /** SUCCESS / PARTIAL / FAILED / BUSY */
  @Column(name = "status", length = 20, nullable = false)
  private String status;

  /** SUCCESS: 完成 PARTIAL: 外部依赖失败但跑完 FAILED: 异常中断 BUSY: 并发拒绝 */
  @Column(name = "message", length = 256)
  private String message;

  /** 数据源 provider 标识 */
  @Column(name = "provider", length = 32)
  private String provider;

  /** 扫描到的板块数量 */
  @Column(name = "sector_count")
  private Integer sectorCount;

  /** 龙头候选数量 */
  @Column(name = "leader_count")
  private Integer leaderCount;

  /** 财务硬筛过滤数量 */
  @Column(name = "hard_filtered_count")
  private Integer hardFilteredCount;

  /** 最终候选数量 */
  @Column(name = "candidate_count")
  private Integer candidateCount;
}
