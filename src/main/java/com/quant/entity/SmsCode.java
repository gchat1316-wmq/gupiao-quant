package com.quant.entity;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "auth_sms_code")
public class SmsCode {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 20)
  private String phone;

  @Column(nullable = false, length = 6)
  private String code;

  @Column(name = "expire_at", nullable = false)
  private LocalDateTime expireAt;

  @Column(nullable = false)
  private Boolean used = false;

  @Column(name = "created_at", insertable = false, updatable = false)
  private LocalDateTime createdAt;
}
