package com.quant.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Caffeine 缓存配置。
 *
 * <p>大阳线战法 (big-yang-signals / big-yang-summary) 用短 TTL，避免用户扫完后等 60min 才看到新数据；
 * 其它业务继续用默认 60min / 500 条上限。
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        // 默认策略（覆盖未单独指定的 cache）
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(60, TimeUnit.MINUTES));
        // 大阳线专用短 TTL
        manager.setCacheNames(Set.of("big-yang-signals", "big-yang-summary", "poolMeta", "weeklyOpportunity", "stockPool", "financial", "sopCheckup"));
        manager.registerCustomCache("big-yang-signals",
                Caffeine.newBuilder()
                        .maximumSize(10)
                        .expireAfterWrite(10, TimeUnit.SECONDS)
                        .build());
        manager.registerCustomCache("big-yang-summary",
                Caffeine.newBuilder()
                        .maximumSize(20)
                        .expireAfterWrite(30, TimeUnit.SECONDS)
                        .build());
        // stockPool（股票池 + 实时行情）30s：行情 30s 内基本不变，反复切换 tab / 刷新页面秒开
        manager.registerCustomCache("stockPool",
                Caffeine.newBuilder()
                        .maximumSize(20)
                        .expireAfterWrite(30, TimeUnit.SECONDS)
                        .build());
        // poolMeta / weeklyOpportunity 走默认 60min / 500 条
        return manager;
    }
}