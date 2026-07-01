package com.quant.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

@Configuration
@EnableConfigurationProperties(StockAnalysisProperties.class)
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.upload-dir:uploads}")
    private String uploadDir;

    /**
     * 把 URL /uploads/** 映射到 ${app.upload-dir} 文件系统目录。
     * 影响：pool-meta 封面图 / weekly-opportunity 9 格截图 / 学习搭子上传 等。
     * 生产环境通常用 nginx 代理 /uploads/，本配置主要服务本地开发。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = "file:" + Paths.get(uploadDir).toAbsolutePath().normalize() + "/";
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(location);
    }
}
