package com.quant.dto.wishpool;

import java.time.LocalDateTime;

import com.quant.entity.WishPool;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/** 前台右下角浮动卡片轮播数据 — 隐藏用户邮箱 / IP / page / status。 */
@Getter
@Setter
@Builder
public class WishPublicDto {

  private Long id;
  private String wish;
  private String reply;
  private String replyBy;
  private LocalDateTime replyAt;

  public static WishPublicDto of(WishPool w) {
    if (w == null) return null;
    return WishPublicDto.builder()
        .id(w.getId())
        .wish(w.getWish())
        .reply(w.getReply())
        .replyBy(w.getReplyBy())
        .replyAt(w.getReplyAt())
        .build();
  }
}
