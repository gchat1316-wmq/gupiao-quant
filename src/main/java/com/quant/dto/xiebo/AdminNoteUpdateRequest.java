package com.quant.dto.xiebo;

import lombok.Data;

@Data
public class AdminNoteUpdateRequest {
    /** 已 sanitize 的 HTML,允许的图片标签见后端 Safelist */
    private String noteHtml;
}
