package com.quant.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "potential_position_fill")
public class PotentialPositionFill {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "pool_id", nullable = false)
  private Integer poolId;

  @Column(name = "stock_code", nullable = false, length = 20)
  private String stockCode;

  /** open / add / reduce / clear */
  @Column(name = "action", nullable = false, length = 10)
  private String action;

  @Column(name = "price", nullable = false, precision = 10, scale = 2)
  private BigDecimal price;

  @Column(name = "lots", nullable = false, precision = 10, scale = 2)
  private BigDecimal lots;

  @Column(name = "amount", precision = 14, scale = 2)
  private BigDecimal amount;

  @Column(name = "fee", precision = 10, scale = 2)
  private BigDecimal fee;

  @Column(name = "note", length = 255)
  private String note;

  @Column(name = "filled_at", nullable = false)
  private LocalDateTime filledAt;

  @Column(name = "created_at", insertable = false, updatable = false)
  private LocalDateTime createdAt;
}
