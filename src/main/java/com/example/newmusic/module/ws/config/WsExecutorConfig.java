package com.example.newmusic.module.ws.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 落库线程池（FR-13-003 步骤 5「落库异步」）。
 *
 * <p>单独成一个配置类，而不是放在 {@link WsConfig} 里：
 * {@code WsConfig} 需要注入 {@code MusicWebSocketHandler} 来注册端点，而 Handler 又需要
 * 这个线程池；两者写在同一个类里会形成构造器循环依赖（Spring Boot 2.6 默认禁止）。
 *
 * <p>有界队列 + CallerRuns：队列满时宁可让发送方多等一次插入，也不静默丢消息。
 * 落库失败只影响「断线补偿」能力，不影响实时广播（EX-13-003-6）。
 */
@Configuration
public class WsExecutorConfig {

    @Bean("wsMessageExecutor")
    public ThreadPoolTaskExecutor wsMessageExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(2000);
        executor.setThreadNamePrefix("ws-msg-");
        executor.setRejectedExecutionHandler(
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
