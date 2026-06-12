package com.quant.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(StockAnalysisProperties.class)
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {
    // 个股分析 API 鉴权改为 Controller 内置 ?api_key= 方式
    // 无需拦截器, 保持简洁
}
