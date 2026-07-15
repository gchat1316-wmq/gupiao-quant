package com.quant.controller;

import com.quant.dto.wishpool.WishPublicDto;
import com.quant.dto.wishpool.WishSubmitRequest;
import com.quant.entity.WishPool;
import com.quant.service.WishPoolService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/wishes")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class WishPoolController {

    private final WishPoolService wishPoolService;

    /** 用户提交许愿：先入库,再异步推飞书(失败不阻塞) */
    @PostMapping
    public Map<String, Object> submitWish(@Valid @RequestBody WishSubmitRequest request,
                                          HttpServletRequest httpRequest) {
        String ip = resolveClientIp(httpRequest);
        WishPool saved = wishPoolService.submitWish(request, ip);
        return Map.of(
                "message", "已收到许愿，我们会认真评估",
                "id", saved.getId()
        );
    }

    /** 前台右下角浮动卡片轮播数据(仅返回已 display=true 且有回复的) */
    @GetMapping("/public")
    public List<WishPublicDto> listPublic(@RequestParam(name = "size", defaultValue = "20") int size) {
        return wishPoolService.listPublic(size);
    }

    /** 从 header 取真实 IP,优先 X-Forwarded-For */
    private String resolveClientIp(HttpServletRequest req) {
        if (req == null) return null;
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            int comma = fwd.indexOf(',');
            return (comma > 0 ? fwd.substring(0, comma) : fwd).trim();
        }
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) return real.trim();
        return req.getRemoteAddr();
    }
}
