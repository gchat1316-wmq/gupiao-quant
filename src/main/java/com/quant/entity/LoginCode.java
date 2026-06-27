package com.quant.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 一次性登录码（ADMIN 给 MANAGER/ADMIN 用户发放的临时凭证）。
 * 格式：GP-{YEAR}{MM}{DD}-{6位随机}
 * 例：GP-20260627-A3B2K9
 */
@Getter
@Setter
@Entity
@Table(name = "login_code")
public class LoginCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 20)
    private String code;

    /**
     * 发行者 user_id（ADMIN）。
     */
    @Column(name = "issuer_id")
    private Long issuerId;

    /**
     * 期望给到的角色（MANAGER 或 ADMIN）。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "intended_role", nullable = false, length = 20)
    private User.Role intendedRole;

    /**
     * 关联的 user_id（用过后写入）。
     */
    @Column(name = "used_by_user_id")
    private Long usedByUserId;

    /**
     * 过期时间，默认 7 天后。
     */
    @Column(name = "expire_at", nullable = false)
    private LocalDateTime expireAt;

    /**
     * 是否已使用。
     */
    @Column(name = "used", nullable = false)
    private Boolean used = false;

    /**
     * 创建时间。
     */
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
