package com.quant.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步任务线程池 - 个股分析后台跑分析
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "stockAnalysisExecutor")
    public Executor stockAnalysisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("sa-async-");
        executor.setKeepAliveSeconds(60);
        executor.setRejectedExecutionHandler((r, e) -> {
            // 队列满时, 同步执行 (避免任务丢失)
            r.run();
        });
        executor.initialize();
        return executor;
    }
}
