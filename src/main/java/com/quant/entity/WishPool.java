package com.quant.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 许愿池留言：用户在页面右下角「支持作者 / 提个需求」留下的愿望。
 *
 * 流程：
 *  1) 用户提交 → {@code status=PENDING, display=false}，异步推飞书 webhook 通知到管理员。
 *  2) 管理员在后台回复（{@code reply} + status=REPLIED, reply_by=admin, reply_at=now}）。
 *  3) 管理员可勾选 {@code display=true}，前台右下角 {@code GET /api/wishes/public}
 *     会轮播这些已 display=true 且有回复的条目。
 *
 * @see com.quant.config.SchemaInitializer#ensureWishPoolTable()
 */
@Getter
@Setter
@Entity
@Table(name = "wish_pool")
public class WishPool {

    public enum Status {
        /** 待回复 */
        PENDING,
        /** 已回复 */
        REPLIED,
        /** 归档（用户撤回 / 管理员下线） */
        ARCHIVED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户留言内容 */
    @Column(name = "wish", nullable = false, length = 500)
    private String wish;

    /** 来源页面路径（用户提供或前端自动填的 location.pathname） */
    @Column(name = "page", length = 120)
    private String page;

    /** 用户联系邮箱（可选） */
    @Column(name = "email", length = 120)
    private String email;

    /** 提交 IP（限流/反垃圾用） */
    @Column(name = "ip", length = 45)
    private String ip;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PENDING;

    /** 管理员回复 */
    @Column(name = "reply", columnDefinition = "TEXT")
    private String reply;

    /** 回复人（admin username / phone / email） */
    @Column(name = "reply_by", length = 50)
    private String replyBy;

    /** 回复时间 */
    @Column(name = "reply_at")
    private LocalDateTime replyAt;

    /** 是否在右下角公开轮播（admin 控制开关） */
    @Column(name = "display_flag", nullable = false)
    private Boolean displayFlag = Boolean.FALSE;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
