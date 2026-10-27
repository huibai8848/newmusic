package com.example.newmusic.module.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 调用线程池。
 *
 * <p>承担两件事：HTTP Provider 的超时控制（{@code Future.get(timeout)} 才能真正打断，
 * RestTemplate 的 readTimeout 是全局的），以及后台标注任务的异步执行。
 *
 * <p>有界队列 + {@code CallerRunsPolicy}：队列满时让调用方线程自己跑。AI 请求比统计
 * 数据重要，宁可让这一个请求慢一点，也不要静默丢弃后返回一个空结果。
 */
@Configuration
public class AiExecutorConfig {

    @Bean(name = "aiExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor aiExecutor() {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "ai-call-" + seq.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                4, 8, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(200),
                factory,
                new ThreadPoolExecutor.CallerRunsPolicy());
        executor.allowCoreThreadTimeOut(false);
        return executor;
    }
}
