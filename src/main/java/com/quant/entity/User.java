package com.quant.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "auth_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String phone;

    @Column(unique = true)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(unique = true)
    private String openid;

    @Column(unique = true)
    private String unionid;

    @Column(unique = true)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.USER;

    @Column(nullable = false)
    private Boolean disabled = false;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    /** 头像 URL */
    private String avatarUrl;

    /** 接收微信通知 */
    @Column(nullable = false)
    private Boolean notifyWechat = true;

    /** 接收短信通知 */
    @Column(nullable = false)
    private Boolean notifySms = false;

    /** 接收电话通知 */
    @Column(nullable = false)
    private Boolean notifyPhone = false;

    /** 默认 Server酱 SendKey — 给近期关注等模块订阅 fallback 用 */
    @Column(name = "serverchan_send_key", length = 64)
    private String serverchanSendKey;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public enum Role {
        ADMIN,   // 系统管理员
        MANAGER, // 龙江股票池管理员
        USER     // 普通用户
    }
}
