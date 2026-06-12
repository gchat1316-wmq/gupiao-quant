package com.quant.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 个股分析 API 鉴权拦截器
 * - 请求头 X-API-Key 必须匹配 application.yml 中配置的 api-keys 之一
 * - 多个 Key 以逗号分隔
 * - 留空则禁用鉴权（仅本地开发）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyAuthInterceptor implements HandlerInterceptor {

    private final StockAnalysisProperties properties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!properties.isEnabled()) {
            return true;
        }
        String configured = properties.getApiKeys();
        if (configured == null || configured.trim().isEmpty()) {
            // 未配置 Key -> 仅放行本地回环
            String remote = request.getRemoteAddr();
            if ("127.0.0.1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote)) {
                return true;
            }
            log.warn("stock-analysis 未配置 api-keys, 拒绝外部请求 remote={}", remote);
            writeUnauthorized(response, "API Key 未配置, 仅本机可访问");
            return false;
        }
        List<String> validKeys = Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        String provided = request.getHeader("X-API-Key");
        if (provided == null) {
            provided = request.getParameter("api_key"); // 也支持 query string 形式
        }
        if (provided == null || !validKeys.contains(provided)) {
            log.warn("stock-analysis 鉴权失败 remote={} key={}", request.getRemoteAddr(), provided);
            writeUnauthorized(response, "无效的 API Key");
            return false;
        }
        return true;
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
            "{\"ok\":false,\"code\":401,\"message\":\"" + message + "\"}"
        );
    }
}
