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
@Table(name = "invest_xiebo_stock_note")
public class InvestXieboStockNote {

  @Id
  @Column(name = "stock_code", nullable = false, length = 16)
  private String stockCode;

  @Column(name = "note_html", columnDefinition = "LONGTEXT")
  private String noteHtml;

  @Column(name = "updated_by_admin_id")
  private Long updatedByAdminId;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private LocalDateTime updatedAt;
}
