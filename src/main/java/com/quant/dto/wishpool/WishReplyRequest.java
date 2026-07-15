package com.quant.dto.wishpool;

import lombok.Getter;
import lombok.Setter;

/**
 * 管理员回复留言的请求体。
 */
@Getter
@Setter
public class WishReplyRequest {

    /** 回复内容（必填，去前后空格） */
    private String reply;
}
