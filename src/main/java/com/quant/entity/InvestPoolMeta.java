package com.quant.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "invest_pool_meta")
public class InvestPoolMeta {

  @Id
  @Column(name = "pool_type", length = 20)
  private String poolType;

  @Column(name = "display_name", nullable = false, length = 64)
  private String displayName;

  @Column(name = "cover_image_url", length = 512)
  private String coverImageUrl;

  @Column(name = "valuation_method_md", columnDefinition = "LONGTEXT")
  private String valuationMethodMd;

  @Column(name = "valuation_method_html", columnDefinition = "LONGTEXT")
  private String valuationMethodHtml;

  @Column(name = "weekly_opportunity_md", columnDefinition = "LONGTEXT")
  private String weeklyOpportunityMd;

  @Column(name = "weekly_opportunity_html", columnDefinition = "LONGTEXT")
  private String weeklyOpportunityHtml;

  @Column(name = "display_order", nullable = false)
  private Integer displayOrder;

  @Column(name = "created_at", insertable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private LocalDateTime updatedAt;
}
