package com.example.newmusic.module.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 日志异步执行器。
 *
 * <p>有界队列 + DiscardOldest：日志可以丢最旧的，但**不能阻塞业务请求**。
 * 丢弃会打 ERROR（带任务计数），生产可据此告警。
 */
@Slf4j
@Configuration
public class AdminExecutorConfig {

    @Bean(name = "adminLogExecutor")
    public ThreadPoolTaskExecutor adminLogExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("admin-log-");
        executor.setRejectedExecutionHandler((r, pool) -> {
            log.error("后台日志队列已满，丢弃最旧任务 active={} queue={}",
                    pool.getActiveCount(), pool.getQueue().size());
            if (!pool.isShutdown()) {
                ThreadPoolExecutor executorPool = (ThreadPoolExecutor) pool;
                Runnable discarded = executorPool.getQueue().poll();
                if (discarded != null) {
                    executorPool.execute(r);
                }
            }
        });
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }
}
