package com.quant.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(StockAnalysisProperties.class)
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final ApiKeyAuthInterceptor apiKeyAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 个股分析 API 需要 API Key 鉴权
        registry.addInterceptor(apiKeyAuthInterceptor)
                .addPathPatterns("/api/stock-analysis/**")
                .excludePathPatterns("/api/stock-analysis/health");
    }
}
