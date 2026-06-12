package com.quant.dto.wishpool;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WishSubmitRequest {

    @NotBlank(message = "请输入想要的能力或需求")
    @Size(max = 500, message = "需求描述请控制在 500 字以内")
    private String wish;

    @Size(max = 120, message = "页面信息过长")
    private String page;
}
