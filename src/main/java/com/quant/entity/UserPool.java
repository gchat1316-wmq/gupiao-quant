package com.quant.entity;

import java.time.LocalDateTime;
import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "auth_user_pool")
public class UserPool {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "pool_name", nullable = false)
  private String poolName = "我的股票池";

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "json")
  private List<StockItem> stocks;

  @Column(name = "is_public", nullable = false)
  private Boolean isPublic = false;

  @Column(name = "created_at", insertable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", insertable = false, updatable = false)
  private LocalDateTime updatedAt;

  @Getter
  @Setter
  public static class StockItem {
    private String code;
    private String name;
    private String note;
    private String addedAt;
  }
}
