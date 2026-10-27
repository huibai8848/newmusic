package com.example.newmusic.module.dataplatform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 采集落库线程池。
 *
 * <p>有界队列 + CallerRuns：队列满时由请求线程自己写，宁可让这一条慢一点，
 * 也不能丢埋点，更不能让队列无限增长拖垮进程（BR-26-006 / EX-26-001-2）。
 */
@Configuration
public class DataPlatformExecutorConfig {

    public static final String EXECUTOR = "dataPlatformExecutor";

    @Bean(EXECUTOR)
    public ThreadPoolTaskExecutor dataPlatformExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("dp-ingest-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
