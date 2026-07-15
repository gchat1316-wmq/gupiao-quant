package com.quant.dto.wishpool;

import com.quant.entity.WishPool;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 后台管理列表的单条留言。
 */
@Getter
@Setter
@Builder
public class WishAdminDto {

    private Long id;
    private String wish;
    private String page;
    private String email;
    private String ip;
    private WishPool.Status status;
    private String reply;
    private String replyBy;
    private LocalDateTime replyAt;
    private Boolean display;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static WishAdminDto of(WishPool w) {
        if (w == null) return null;
        return WishAdminDto.builder()
                .id(w.getId())
                .wish(w.getWish())
                .page(w.getPage())
                .email(w.getEmail())
                .ip(w.getIp())
                .status(w.getStatus())
                .reply(w.getReply())
                .replyBy(w.getReplyBy())
                .replyAt(w.getReplyAt())
                .display(Boolean.TRUE.equals(w.getDisplayFlag()))
                .createdAt(w.getCreatedAt())
                .updatedAt(w.getUpdatedAt())
                .build();
    }
}
